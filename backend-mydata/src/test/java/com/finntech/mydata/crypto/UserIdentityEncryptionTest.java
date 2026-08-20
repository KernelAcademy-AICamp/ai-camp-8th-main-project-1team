package com.finntech.mydata.crypto;

import com.finntech.mydata.domain.MyDataUser;
import com.finntech.mydata.repository.MyDataUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>실 신원이 평문으로 남지 않는가.</b>
 *
 * <p>본체(backend)는 신청 대기열을 처음부터 암호화했는데 <b>승인 뒤 오래 남는 이쪽이
 * 평문이었다</b> — 2026-08-13 운영 실측에서 {@code mydata_user} 4,513행이 varchar 그대로였다.
 * 실제 사람들의 이름·주민앞7·전화가 거기 있었다.
 *
 * <p>시험 환경은 KMS 가 없어 암호화가 꺼진 채로 돈다({@code mydata.crypto.enabled=false}).
 * 그래서 여기서 검사하는 것은 <b>암호문 자체가 아니라 경로</b>다 — 값이 새 칸으로 가는가,
 * 지문이 붙는가, 지문으로 찾아지는가, 평문 칸이 비는가. 암호화 여부는
 * {@code CryptoRequiredGuard} 가 운영에서 강제한다.
 */
@SpringBootTest
class UserIdentityEncryptionTest {

    private static final String NAME = "김암호";
    private static final String SOCIAL = "0309303";
    /**
     * 시험마다 <b>다른 번호</b>를 쓴다. 같은 번호를 나눠 쓰면 지문이 같아져 조회가
     * "결과가 둘"로 터진다 — 시험이 서로를 오염시키는 자리다.
     */
    private static String phoneOf(String suffix) {
        return "010-9%03d-5432".formatted(suffix.charAt(0) % 1000);
    }

    @Autowired MyDataUserRepository users;
    @Autowired UserIdentityIndex index;
    @Autowired FieldCrypto crypto;
    @Autowired UserIdentityBackfill backfill;
    @Autowired com.finntech.mydata.service.MyDataService service;

    private MyDataUser saved(String suffix) {
        return users.save(index.newUser("ci-" + suffix, NAME + suffix, SOCIAL, phoneOf(suffix)));
    }

    @Test
    @DisplayName("새 신원은 평문 칸에 안 쌓인다 — 값은 암호문 칸으로만 간다")
    void newIdentityNeverLandsInThePlaintextColumn() {
        MyDataUser user = saved("A");

        // 게터는 암호문 칸을 먼저 본다. 값 자체는 그대로 읽혀야 서비스가 산다.
        assertThat(user.getName()).isEqualTo(NAME + "A");
        assertThat(user.getPhoneNumber()).isEqualTo(phoneOf("A"));

        // 그런데 **평문 칸으로 찾으면 안 나온다** — 거기엔 빈 문자열이 들어갔다는 뜻이다.
        assertThat(users.findByPhoneNumber(phoneOf("A")))
                .as("평문 칸에는 더 이상 안 쌓인다").isEmpty();
    }

    @Test
    @DisplayName("지문이 함께 찍힌다 — 없으면 그 사람은 로그인하지 못한다")
    void stampsBlindIndexes() {
        MyDataUser user = saved("B");

        assertThat(user.getPhoneBlindIndex()).isNotBlank();
        assertThat(user.getPersonBlindIndex()).isNotBlank();
        assertThat(users.findAllByPhoneBlindIndex(index.ofPhone(phoneOf("B"))))
.isNotEmpty();
        assertThat(users.findAllByPersonBlindIndex(index.ofPerson(NAME + "B", SOCIAL)))
.isNotEmpty();
    }

    @Test
    @DisplayName("번호 표기가 달라도 같은 사람을 찾는다 — 정규화가 한 벌이다")
    void normalisationIsShared() {
        String hyphenated = phoneOf("C");
        String digitsOnly = hyphenated.replace("-", "");
        saved("C");

        // 저장 표기와 조회 표기가 갈리면 "분명히 맞게 넣었는데 불일치"가 뜬다.
        // 이 저장소는 실제로 그 사고를 겪었다(2026-08-05).
        assertThat(users.findAllByPhoneBlindIndex(index.ofPhone(digitsOnly)))
.isNotEmpty();
        assertThat(users.findAllByPhoneBlindIndex(index.ofPhone(hyphenated)))
.isNotEmpty();
    }

    @Test
    @DisplayName("이름과 주민앞7 사이에 구분자가 있다 — 없으면 남의 신원으로 통과할 수 있다")
    void separatorPreventsAmbiguity() {
        // ("홍길", "동030930") 과 ("홍길동", "0309303") 을 이어 붙이면 같은 문자열이 된다.
        // 구분자가 없으면 지문이 같아져, 한쪽 신원으로 다른 쪽이 조회된다.
        String a = index.ofPerson("홍길", "동030930");
        String b = index.ofPerson("홍길동", "0309303");
        assertThat(a).isNotEqualTo(b);
    }

    @Test
    @DisplayName("번호를 고치면 지문도 함께 바뀐다 — 따로 두면 그 사람을 못 찾는다")
    void changingThePhoneMovesTheIndexToo() {
        MyDataUser user = saved("D");
        String moved = "010-1111-2222";

        index.changePhone(user, moved);
        users.save(user);

        assertThat(users.findAllByPhoneBlindIndex(index.ofPhone(moved)))
.isNotEmpty();
        assertThat(users.findAllByPhoneBlindIndex(index.ofPhone(phoneOf("D"))))
                .as("옛 번호로는 더 이상 안 찾힌다").isEmpty();
    }

    @Test
    @DisplayName("백필 대상 질의가 이미 채워진 행을 다시 집지 않는다")
    void backfillDoesNotRepickFinishedRows() {
        saved("E");

        assertThat(users.findNeedingEncryption(PageRequest.of(0, 100)))
                .as("지문까지 찍힌 행은 대상이 아니다")
                .noneMatch(row -> "ci-E".equals(row.getId()));
    }

    /**
     * <b>백필이 실제로 DB 에 쓰는가.</b>
     *
     * <p>이 시험이 없어서 결함이 운영 DB 까지 갔다(2026-08-13). {@code @Transactional} 을
     * 붙인 메서드를 같은 객체 안에서 부르면 프록시를 안 지나 <b>애너테이션이 조용히 무시된다.</b>
     * 커밋이 안 되니 다음 조각이 같은 행을 다시 집고, 로그에는 "10만 행 채웠다"가 남는데
     * <b>DB 에는 한 행도 안 써져 있었다.</b>
     *
     * <p>그래서 검사하는 것은 <b>돌려주는 숫자가 아니라 DB 의 상태</b>다. 숫자를 믿으면
     * 같은 결함을 또 놓친다.
     */
    @Test
    @DisplayName("백필이 실제로 DB 에 쓴다 — 숫자가 아니라 DB 를 본다")
    void backfillActuallyCommits() {
        // 백필이 집어야 할 행 — 지문이 없는 상태로 직접 만든다.
        users.save(new MyDataUser("ci-backfill", "백필대상", "0309303", "010-7777-8888"));
        assertThat(users.findNeedingEncryption(PageRequest.of(0, 100)))
                .as("만들 때는 대상이다").anyMatch(row -> "ci-backfill".equals(row.getId()));

        // **트랜잭션 진입점을 부른다.** 안쪽 메서드를 직접 부르면 커밋이 없어, 결함이 있어도
        // 이 시험이 통과해 버린다 — 실제로 그렇게 놓쳤다.
        backfill.backfillOnce();

        // **DB 를 다시 읽는다.** 메모리의 엔티티를 보면 커밋 여부를 알 수 없다.
        MyDataUser reloaded = users.findById("ci-backfill").orElseThrow();
        assertThat(reloaded.getPhoneBlindIndex()).as("지문이 실제로 저장됐다").isNotBlank();
        assertThat(users.findNeedingEncryption(PageRequest.of(0, 100)))
                .as("두 번째 회차가 같은 행을 다시 집지 않는다")
                .noneMatch(row -> "ci-backfill".equals(row.getId()));
    }

    /**
     * <b>평문을 비운 뒤 빈 값으로 조회하면 어떻게 되는가.</b>
     *
     * <p>V14 가 평문 칸을 빈 문자열로 만든다. 그 상태에서 옛 평문 조회를 남겨 두면
     * <b>빈 번호 하나로 수천 행이 걸린다</b> — 엉뚱한 사람이 잡히거나 질의가 터진다.
     * 그래서 폴백을 지웠다. 이 시험은 그 결정이 되돌려지지 않게 고정한다.
     */
    @Test
    @DisplayName("빈 값으로는 아무도 안 걸린다 — 평문을 비운 뒤의 함정")
    void emptyValuesMatchNobody() {
        // V14 이후의 모습: 평문은 비었고 지문은 아직 없는 행.
        users.save(new MyDataUser("ci-wiped", "", "", ""));

        // 지문 계산은 빈 입력에 null 을 준다.
        assertThat(index.ofPhone("")).isNull();
        assertThat(index.ofPerson("", "")).isNull();

        // **여기가 요점이다.** 그 null 을 파생 질의에 그대로 넘기면 `IS NULL` 이 되어
        // 지문 없는 행이 전부 걸린다. 서비스가 막아야 하고, 실제로 막는지 본다.
        var view = service.matchIdentity("", "", "");
        assertThat(view.exists()).isFalse();
        assertThat(view.phoneTaken()).as("빈 번호로 남의 행이 잡히면 안 된다").isFalse();
        assertThat(view.personFound()).isFalse();
    }

    @Test
    @DisplayName("시험 환경에서는 암호화가 꺼져 있다 — 운영 강제는 가드 소관이다")
    void cryptoIsOffInTests() {
        // 켜져 있으면 KMS 를 부르려다 시험이 통째로 죽는다. 꺼진 채로 도는 것이 정상이고,
        // 운영에서 꺼져 있으면 `CryptoRequiredGuard` 가 기동을 막는다.
        assertThat(crypto.isEnabled()).isFalse();
    }
}
