package com.finntech.mydata.crypto;

import com.finntech.mydata.domain.MyDataUser;
import com.finntech.mydata.repository.MyDataUserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

/**
 * 옛 평문 신원을 <b>암호문과 지문으로 채운다</b> — 기동할 때마다 남은 것이 있으면 이어서.
 *
 * <h2>왜 기동 러너인가</h2>
 *
 * <p>마이그레이션(V13)은 칸만 더한다. SQL 로는 암호화를 못 한다 — 키가 KMS 에 있고 형식이
 * {@code [버전][IV][본문+태그]} 라 애플리케이션만 만들 수 있다.
 *
 * <p>한 번 쓰고 버리는 스크립트로 하지 않은 이유는 <b>중간에 끊길 수 있어서</b>다. 4,513행은
 * 금방이지만, 끊긴 채 남으면 그 사람들은 지문이 없어 <b>조회에 안 걸리고 로그인하지 못한다.</b>
 * 기동마다 확인하면 다음 배포가 저절로 마저 채운다.
 *
 * <h2>왜 조각으로 나눠 도는가</h2>
 *
 * <p>4,513행을 한 트랜잭션에 넣으면 그동안 표가 잠기고, 실패하면 통째로 되돌아간다.
 * 조각마다 커밋하면 <b>거기까지는 남는다</b> — 이어서 하기의 전제다.
 *
 * <h2>평문 칸은 안 건드린다</h2>
 *
 * <p>여기서 평문을 지우면 되돌릴 방법이 사라진다. 백필과 로그인을 실측으로 확인한 뒤
 * <b>V14 가 따로</b> 비운다. 그때까지는 두 벌이 공존하고, 그동안은 평문이 남아 있다 —
 * 그 사실을 알고 넘어가는 것이지 안전해서가 아니다.
 */
@Component
public class UserIdentityBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(UserIdentityBackfill.class);

    /** 한 트랜잭션에 담는 행 수. 표를 오래 잡지 않으면서 왕복도 줄이는 크기다. */
    private static final int CHUNK = 500;
    /** 한 기동에서 도는 조각 수 상한 — 무한 반복을 막는 안전장치다. */
    private static final int MAX_CHUNKS = 200;

    private final MyDataUserRepository users;
    private final UserIdentityIndex index;
    private final FieldCrypto crypto;
    private final boolean enabled;
    /**
     * 트랜잭션을 <b>손으로 연다.</b>
     *
     * <p>{@code @Transactional} 을 붙인 메서드를 <b>같은 객체 안에서</b> 부르면 스프링 프록시를
     * 지나지 않아 애너테이션이 <b>조용히 무시된다.</b> 실제로 그렇게 만들었다가 운영 DB 로
     * 시험하고서야 잡았다(2026-08-13):
     *
     * <pre>
     *   로그  "백필 — 100000행을 채웠다"   ← 200조각 × 500, 무의미한 반복
     *   DB    enc 0행, 지문 0행            ← 한 행도 안 써졌다
     * </pre>
     *
     * <p>커밋이 안 되니 다음 조각이 <b>같은 행을 다시 집고</b>, 상한까지 헛돌다 성공했다고
     * 거짓 로그를 남긴다. 컨테이너는 healthy 라 배포했으면 아무도 몰랐을 것이다.
     * 그 상태에서 평문까지 지웠으면 <b>암호문도 평문도 없는</b> 행이 됐다.
     */
    private final TransactionTemplate tx;

    public UserIdentityBackfill(MyDataUserRepository users, UserIdentityIndex index, FieldCrypto crypto,
                                PlatformTransactionManager txManager,
                                @Value("${mydata.crypto.backfill-on-startup:true}") boolean enabled) {
        this.users = users;
        this.index = index;
        this.crypto = crypto;
        this.tx = new TransactionTemplate(txManager);
        this.enabled = enabled;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) return;
        // **암호화가 꺼져 있으면 아무것도 안 한다.** 꺼진 상태에서 돌면 `encrypt` 가 평문을
        // 그대로 통과시켜, 암호화된 것처럼 보이는 평문이 새 칸에 앉는다 — 가장 나쁜 결과다.
        if (!crypto.isEnabled()) {
            log.info("신원 암호화가 꺼져 있어 백필을 건너뛴다");
            return;
        }
        int total = 0;
        for (int chunk = 0; chunk < MAX_CHUNKS; chunk++) {
            Integer done = backfillOnce();
            if (done == null || done == 0) break;
            total += done;

            // **진행하지 않으면 멈추고 소리를 낸다.**
            //
            // 커밋이 안 되면 다음 조각이 같은 행을 다시 집는다. 그대로 두면 상한까지 헛돌고
            // 로그에는 "10만 행 채웠다"가 남는다 — 실제로 그렇게 됐고, DB 를 직접 보고서야
            // 알았다(2026-08-13). 조용히 성공한 척하는 것이 이 기능의 가장 나쁜 실패다.
            if (!remaining().isEmpty() && chunk > 0 && total > users.count()) {
                log.error("백필이 진행되지 않는다 — 같은 행을 다시 집고 있다. "
                        + "쓰기가 커밋되지 않는 것으로 보인다(총 {}행 시도, 표에는 {}행)",
                        total, users.count());
                return;
            }
        }
        if (total == 0) {
            log.debug("제공자 신원 백필 — 채울 것 없음");
            return;
        }
        log.info("제공자 신원 백필 — {}행을 암호문·지문으로 채웠다", total);
        // 다 돌고도 남아 있으면 값이 안 붙은 행이 있다는 뜻이다. 다음 기동이 이어받지만
        // **왜 남았는지는 사람이 봐야 한다.**
        if (!remaining().isEmpty()) {
            log.warn("백필 뒤에도 남은 행이 있다 — 원인을 확인하라");
        }
    }

    /** 아직 안 채워진 행이 하나라도 있는가. 있으면 그 한 행만 들고 온다. */
    private List<MyDataUser> remaining() {
        return users.findNeedingEncryption(PageRequest.of(0, 1));
    }

    /**
     * 조각 하나를 <b>트랜잭션 안에서</b> 채우고 커밋한다.
     *
     * <p>여기가 진짜 진입점이다. {@link #encryptChunk()} 를 직접 부르면 트랜잭션이 없어
     * 쓰기가 사라진다 — 그 실수를 실제로 했고, 시험은 <b>이 메서드</b>를 불러야 의미가 있다.
     */
    int backfillOnce() {
        Integer done = tx.execute(status -> encryptChunk());
        return done == null ? 0 : done;
    }

    /** 조각 하나를 채운다. <b>커밋은 {@link #backfillOnce()} 가 한다</b> — 직접 부르지 마라. */
    private int encryptChunk() {
        List<MyDataUser> rows = users.findNeedingEncryption(PageRequest.of(0, CHUNK));
        for (MyDataUser user : rows) {
            // 게터가 **암호문 우선**이라, 이미 반쯤 채워진 행도 올바른 값을 돌려준다.
            String name = user.getName();
            String social = user.getSocialNumber();
            String phone = user.getPhoneNumber();
            user.encryptInto(name, social, phone,
                    index.ofPhone(phone), index.ofPerson(name, social));
        }
        return rows.size();
    }
}
