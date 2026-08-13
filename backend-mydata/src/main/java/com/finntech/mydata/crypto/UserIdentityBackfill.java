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
import org.springframework.transaction.annotation.Transactional;

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

    public UserIdentityBackfill(MyDataUserRepository users, UserIdentityIndex index, FieldCrypto crypto,
                                @Value("${mydata.crypto.backfill-on-startup:true}") boolean enabled) {
        this.users = users;
        this.index = index;
        this.crypto = crypto;
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
            int done = encryptChunk();
            if (done == 0) break;
            total += done;
        }
        if (total > 0) log.info("제공자 신원 백필 — {}행을 암호문·지문으로 채웠다", total);
        else log.debug("제공자 신원 백필 — 채울 것 없음");
    }

    /** 조각 하나를 채우고 커밋한다. 돌려주는 값이 0 이면 더 할 일이 없다는 뜻이다. */
    @Transactional
    public int encryptChunk() {
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
