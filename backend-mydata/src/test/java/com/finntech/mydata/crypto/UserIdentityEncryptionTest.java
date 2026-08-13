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
        assertThat(users.findByPhoneBlindIndex(index.ofPhone(phoneOf("B")))).isPresent();
        assertThat(users.findByPersonBlindIndex(index.ofPerson(NAME + "B", SOCIAL))).isPresent();
    }

    @Test
    @DisplayName("번호 표기가 달라도 같은 사람을 찾는다 — 정규화가 한 벌이다")
    void normalisationIsShared() {
        String hyphenated = phoneOf("C");
        String digitsOnly = hyphenated.replace("-", "");
        saved("C");

        // 저장 표기와 조회 표기가 갈리면 "분명히 맞게 넣었는데 불일치"가 뜬다.
        // 이 저장소는 실제로 그 사고를 겪었다(2026-08-05).
        assertThat(users.findByPhoneBlindIndex(index.ofPhone(digitsOnly))).isPresent();
        assertThat(users.findByPhoneBlindIndex(index.ofPhone(hyphenated))).isPresent();
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

        assertThat(users.findByPhoneBlindIndex(index.ofPhone(moved))).isPresent();
        assertThat(users.findByPhoneBlindIndex(index.ofPhone(phoneOf("D"))))
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

    @Test
    @DisplayName("시험 환경에서는 암호화가 꺼져 있다 — 운영 강제는 가드 소관이다")
    void cryptoIsOffInTests() {
        // 켜져 있으면 KMS 를 부르려다 시험이 통째로 죽는다. 꺼진 채로 도는 것이 정상이고,
        // 운영에서 꺼져 있으면 `CryptoRequiredGuard` 가 기동을 막는다.
        assertThat(crypto.isEnabled()).isFalse();
    }
}
