package com.finntech.mydata.service;

import com.finntech.mydata.domain.MyDataPayment;
import com.finntech.mydata.repository.MyDataCardRepository;
import com.finntech.mydata.repository.MyDataPaymentRepository;
import com.finntech.mydata.repository.MyDataUserRepository;
import com.finntech.mydata.util.Ci;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * <b>실제 사람 한 명을 제공자에 넣는다</b> — 카드사마다 다른 표기를 견디고,
 * 못 읽은 줄을 감추지 않으며, <b>학습에서 자동으로 빠진다</b>.
 *
 * <p>이 앱이 지금까지 판정한 것은 전부 생성기가 만든 소비다. 실제 사람의 소비를 한 번
 * 통과시켜 봐야 "우리 판정이 진짜 사람에게도 말이 되는가"를 말할 수 있다.
 *
 * <p>여기(제공자)에 넣어야 정상 경로로 흐르고 — 본인인증 → 카드사 연결 → 본체 조회 —
 * 덤프에 실려 로컬·AWS·운영 MySQL 모두에 간다. 본체 DB에 직접 넣으면 둘 다 안 된다.
 */
@SpringBootTest
@Transactional
// 인메모리 DB로 격리한다. 이 모듈의 기본 H2는 **파일**이라(`./data/mydata`) 로컬에 쌓인
// 옛 스키마·데이터가 테스트 결과를 바꾼다 — 실제로 여기서 `ksic_code` 컬럼이 없다며 깨졌다.
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:realperson;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class RealPersonImportServiceTest {

    private static final String NAME = "테스트실인";
    private static final String SOCIAL7 = "9001011";
    private static final String PHONE = "010-4444-5555";

    @Autowired RealPersonImportService service;
    @Autowired MyDataUserRepository userRepository;
    @Autowired com.finntech.mydata.crypto.UserIdentityIndex identityIndex;
    @Autowired MyDataCardRepository cardRepository;
    @Autowired MyDataPaymentRepository paymentRepository;

    @BeforeEach
    void clean() {
        service.purge(NAME, SOCIAL7, PHONE);
    }

    private List<MyDataPayment> 결제들() {
        String ci = Ci.of(NAME, SOCIAL7, PHONE);
        return cardRepository.findByUser(ci).stream()
                .flatMap(c -> paymentRepository.findByCardUpTo(
                        c.getId(), LocalDateTime.of(2999, 12, 31, 23, 59)).stream())
                .toList();
    }

    @Test
    @DisplayName("학습에서 자동으로 빠진다 — ml/train.py 가 SERVICE 를 거른다. 재학습은 필요 없다")
    void 학습에서_빠진다() {
        var u = service.ensurePerson(NAME, SOCIAL7, PHONE, null);
        assertThat(u.getDataSplit())
                .as("시험 문제를 교재에 넣지 않는다")
                .isEqualTo("SERVICE");
    }

    @Test
    @DisplayName("페르소나를 붙이지 않는다 — 실제 사람에게 생성용 꼬리표를 씌우지 않는다")
    void 페르소나_없음() {
        var u = service.ensurePerson(NAME, SOCIAL7, PHONE, null);
        assertThat(u.getPersona()).as("우리가 모르는 것을 아는 척하지 않는다").isNull();
    }

    @Test
    @DisplayName("CI 가 생성된 사람들과 같은 산식이다 — 그래야 본인인증이 이 사람을 찾는다")
    void CI_산식이_같다() {
        var u = service.ensurePerson(NAME, SOCIAL7, PHONE, null);
        assertThat(u.getId()).isEqualTo(Ci.of(NAME, SOCIAL7, PHONE));
        // 하이픈이 있든 없든 같은 사람이어야 한다(2026-08-02 Ci 정규화).
        assertThat(u.getId()).isEqualTo(Ci.of(NAME, SOCIAL7, "01044445555"));
    }

    @Test
    @DisplayName("같은 신원을 다시 불러도 사람이 하나다 — 실데이터가 흩어지면 안 된다")
    void 사람은_하나() {
        var a = service.ensurePerson(NAME, SOCIAL7, PHONE, null);
        var b = service.ensurePerson(NAME, SOCIAL7, PHONE, null);
        assertThat(a.getId()).isEqualTo(b.getId());
        assertThat(cardRepository.findByUser(a.getId())).hasSize(1);
    }

    @Test
    @DisplayName("카드사마다 다른 날짜 표기를 견딘다")
    void 날짜_표기() {
        var r = service.importCsv(NAME, SOCIAL7, PHONE, null, """
                2026-07-01,스타벅스,4500
                2026.07.02,GS25,3200
                2026/07/03,올리브영,18000
                20260704,쿠팡,25000
                26-07-05,배달의민족,17000
                """);
        assertThat(r.accepted()).isEqualTo(5);
        assertThat(r.rejected()).isZero();
        assertThat(결제들()).hasSize(5);
    }

    @Test
    @DisplayName("가맹점명 속 쉼표를 따옴표로 살린다")
    void 따옴표_안의_쉼표() {
        var r = service.importCsv(NAME, SOCIAL7, PHONE, null,
                "2026-07-01,\"스타벅스 강남R점, 1층\",4500\n");
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(결제들().get(0).getMerchantName()).isEqualTo("스타벅스 강남R점, 1층");
    }

    @Test
    @DisplayName("머리글은 오류로 세지 않는다 — 매번 1건 실패로 뜨면 진짜 오류가 묻힌다")
    void 머리글() {
        var r = service.importCsv(NAME, SOCIAL7, PHONE, null, """
                이용일자,가맹점명,이용금액
                2026-07-01,스타벅스,4500
                """);
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(r.rejected()).isZero();
    }

    @Test
    @DisplayName("못 읽은 줄을 조용히 버리지 않는다 — 줄 번호와 사유를 함께 돌려준다")
    void 실패를_감추지_않는다() {
        var r = service.importCsv(NAME, SOCIAL7, PHONE, null, """
                2026-07-01,정상,4500
                날짜아님,가게,1000
                2026-07-04
                """);
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(r.rejected()).isEqualTo(2);
        assertThat(r.problems()).extracting(RealPersonImportService.RowResult::line)
                .as("줄 번호가 있어야 원본에서 찾아 고칠 수 있다").containsExactly(2, 3);
    }

    @Test
    @DisplayName("취소·환불(음수)도 받는다 — 버리면 안 쓴 돈이 소비로 남는다")
    void 취소를_받는다() {
        var r = service.importCsv(NAME, SOCIAL7, PHONE, null, """
                2026-07-01,고속철도(KTX)포항-서울,53600
                2026-07-03,고속철도(KTX)포항-서울,-53600
                """);
        assertThat(r.accepted()).as("원결제와 취소가 둘 다 들어간다").isEqualTo(2);
        assertThat(r.rejected()).isZero();
        // 짝을 찾아 원결제를 지우지 않는다 — 부분취소가 있고 원결제가 명세서 밖일 수도 있어
        // 짝짓기는 틀릴 때 조용히 틀린다. 음수 한 줄로 두면 합계가 알아서 상쇄된다.
        assertThat(결제들()).extracting(com.finntech.mydata.domain.MyDataPayment::getAmount)
                .containsExactlyInAnyOrder(53600, -53600);
    }

    @Test
    @DisplayName("업종코드가 없으면 '모름' 코드를 쓴다 — 가맹점명으로 업종을 넘겨짚지 않는다")
    void 업종을_넘겨짚지_않는다() {
        service.importCsv(NAME, SOCIAL7, PHONE, null, "2026-07-01,어떤가게,10000\n");
        assertThat(결제들().get(0).getKsicCode())
                .as("매핑에 없는 코드라 본체가 '카테고리없음'으로 받아 판정에서 뺀다")
                .isEqualTo(RealPersonImportService.UNKNOWN_INDUSTRY);
    }

    @Test
    @DisplayName("업종코드를 주면 그대로 싣는다 — 본체가 중분류로 옮긴다")
    void 업종코드_전달() {
        service.importCsv(NAME, SOCIAL7, PHONE, null, "2026-07-01,씨네Q,12000,5914\n");
        assertThat(결제들().get(0).getKsicCode()).isEqualTo("5914");
    }

    @Test
    @DisplayName("시각은 정오로 채운다 — 0시로 두면 모든 결제가 '심야 결제'가 된다")
    void 모르는_시각은_정오() {
        service.importCsv(NAME, SOCIAL7, PHONE, null, "2026-07-01,가게,10000\n");
        assertThat(결제들().get(0).getPaymentDate().getHour())
                .as("모르는 값을 0으로 채우는 건 의미를 가진 축에서는 중립이 아니다").isEqualTo(12);
    }

    @Test
    @DisplayName("같은 파일을 두 번 올려도 행이 두 배가 되지 않는다")
    void 멱등() {
        String csv = "2026-07-01,가게A,10000\n2026-07-02,가게B,20000\n";
        service.importCsv(NAME, SOCIAL7, PHONE, null, csv);
        service.importCsv(NAME, SOCIAL7, PHONE, null, csv);
        assertThat(결제들()).hasSize(2);
    }

    @Test
    @DisplayName("전량 파기가 실제로 지운다 — 넣는 길과 같은 무게로 둔다")
    void 파기() {
        service.importCsv(NAME, SOCIAL7, PHONE, null, "2026-07-01,가게,10000\n2026-07-02,가게,20000\n");
        assertThat(service.purge(NAME, SOCIAL7, PHONE)).isEqualTo(2);
        assertThat(결제들()).isEmpty();
    }

    @Test
    @DisplayName("빈 입력·null 도 죽지 않는다")
    void 빈_입력() {
        assertThat(service.importCsv(NAME, SOCIAL7, PHONE, null, null).accepted()).isZero();
        assertThat(service.importCsv(NAME, SOCIAL7, PHONE, null, "\n\n# 주석\n").accepted()).isZero();
    }

    // ── 사업자번호 — 확정 분류 사전의 키 (2026-08-05) ─────────────────────────────
    //
    // 이 칸이 없으면 사전이 아무리 차 있어도 실데이터에는 **한 건도 안 붙는다**.
    // 조회가 '번호 없음' 갈래로 빠져(`MerchantCategoryService.lookup` ②) 번호로 쌓아 둔
    // 씨앗을 영영 못 만나기 때문이다. 자리표 번호는 **0으로 시작하는 것만** 쓴다 —
    // 국세청이 발급하지 않는 대역이라 실재 사업자와 겹칠 수 없다(CI 가 유출을 검사한다).

    @Test
    @DisplayName("5번째 칸의 사업자번호를 싣는다 — 사전이 붙을 수 있는 유일한 길")
    void 사업자번호를_싣는다() {
        var r = service.importCsv(NAME, SOCIAL7, PHONE, null,
                "2026-07-01,가게A,10000,,0000000011\n");
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(r.withBusinessNumber()).isEqualTo(1);
        assertThat(결제들().get(0).getBusinessNumber()).isEqualTo("0000000011");
    }

    @Test
    @DisplayName("하이픈·공백이 섞여도 숫자 10자리로 맞춘다 — 표기가 갈리면 같은 사업자가 남이 된다")
    void 사업자번호를_정규화한다() {
        service.importCsv(NAME, SOCIAL7, PHONE, null,
                "2026-07-01,가게A,10000,, 000-00-00011 \n");
        assertThat(결제들().get(0).getBusinessNumber()).isEqualTo("0000000011");
    }

    @Test
    @DisplayName("10자리가 아니면 안 싣는다 — 잘린 번호를 넣으면 엉뚱한 사업자에 붙는다")
    void 사업자번호가_아니면_비운다() {
        service.importCsv(NAME, SOCIAL7, PHONE, null,
                "2026-07-01,가게A,10000,,1234\n2026-07-02,가게B,20000,,\n");
        assertThat(결제들()).allSatisfy(p -> assertThat(p.getBusinessNumber()).isNull());
    }

    @Test
    @DisplayName("기존 4칸 파일이 그대로 읽힌다 — 칸을 뒤에 붙인 이유")
    void 네칸_파일_회귀() {
        var r = service.importCsv(NAME, SOCIAL7, PHONE, null,
                "2026-07-01,가게A,10000,523131\n");
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(결제들().get(0).getKsicCode()).isEqualTo("523131");
        assertThat(결제들().get(0).getBusinessNumber()).isNull();
    }

    @Test
    @DisplayName("이미 넣은 행에 사업자번호를 채운다 — 건너뛰면 '아무 일도 안 일어남'이 성공처럼 보인다")
    void 사업자번호_채워넣기() {
        service.importCsv(NAME, SOCIAL7, PHONE, null, "2026-07-01,가게A,10000\n");
        assertThat(결제들().get(0).getBusinessNumber()).isNull();

        var again = service.importCsv(NAME, SOCIAL7, PHONE, null,
                "2026-07-01,가게A,10000,,0000000011\n");
        assertThat(again.accepted()).as("행이 늘지 않는다").isZero();
        assertThat(again.backfilled()).as("채워 넣었다").isEqualTo(1);
        assertThat(결제들()).hasSize(1);
        assertThat(결제들().get(0).getBusinessNumber()).isEqualTo("0000000011");
    }

    @Test
    @DisplayName("본인인증이 이 사람을 찾아낸다 — 전화번호 표기가 원장과 같아야 한다")
    void 본인인증이_찾아낸다() {
        service.importCsv(NAME, SOCIAL7, PHONE, null, "2026-07-01,가게A,10000\n");
        // 원장은 010-1234-5678 로 저장하고 조회도 그 표기로 한다. 숫자만으로 넣으면
        // 있는 사람을 못 찾아 실제 사람이 자기 번호를 정확히 넣고도 PHONE_MISMATCH 를 듣는다.
        //
        // **조회는 이제 지문으로 한다**(2026-08-13 신원 암호화). 번호가 암호문으로 저장되면
        // 정확일치 조회가 그대로는 안 되기 때문이다. 지문은 저장 표기와 **같은 규칙**으로
        // 정규화한 값에서 나오므로, 갈리면 여기서 걸린다 — 그것이 이 시험의 목적이다.
        assertThat(userRepository.findAllByPhoneBlindIndex(identityIndex.ofPhone(PHONE)))
                .as("하이픈 표기로 찾힌다").isNotEmpty();
        assertThat(userRepository.findAllByPhoneBlindIndex(identityIndex.ofPhone("01044445555")))
                .as("숫자만 넣어도 같은 사람을 찾는다 — 정규화가 한 벌이다").isNotEmpty();
        assertThat(userRepository.findByPhoneNumber(PHONE))
                .as("평문 칸에는 더 이상 안 쌓인다").isEmpty();
    }

    @Test
    @DisplayName("숫자만으로 저장돼 있던 사람도 다음 실행에서 표기가 맞춰진다")
    void 옛_표기를_고친다() {
        service.importCsv(NAME, SOCIAL7, PHONE, null, "2026-07-01,가게A,10000\n");
        var u = userRepository.findById(Ci.of(NAME, SOCIAL7, PHONE)).orElseThrow();
        u.setPhoneNumber("01044445555");                 // 옛 형식으로 되돌려 놓고
        userRepository.save(u);

        service.ensurePerson(NAME, SOCIAL7, PHONE, null);  // 다시 부르면
        assertThat(userRepository.findById(Ci.of(NAME, SOCIAL7, PHONE)).orElseThrow()
                .getPhoneNumber()).isEqualTo(PHONE);       // 고쳐진다
    }


    // ── 한 번호에 두 사람을 안 만든다 ───────────────────────────────────────
    //
    // 신청자가 자기 번호를 **다른 실사용자의 번호로** 잘못 적자 한 번호에 두 사람이 붙었고,
    // 조회가 "결과가 둘"로 터져 그 번호를 쓰는 **두 사람 모두** 본인인증이 500 이 됐다
    // (2026-08-20 운영). 터지는 것은 조회를 목록으로 바꿔 고쳤고, 여기서 잠그는 것은
    // **애초에 그런 데이터가 안 들어오는 것**이다.

    @Test
    @DisplayName("남의 번호로는 새 신원을 못 만든다")
    void 남의_번호로는_등록이_안_된다() {
        String phone = "010-2222-3333";
        service.importCsv(NAME, SOCIAL7, phone, null, "2026-07-01,가게A,10000\n");

        assertThatThrownBy(() -> service.importCsv("다른사람", "8505051", phone, null,
                "2026-07-02,가게B,20000\n"))
                .isInstanceOf(RealPersonImportService.PhoneAlreadyTakenException.class)
                .hasMessageContaining("이미 다른 분 명의");
    }

    @Test
    @DisplayName("막힌 뒤에도 원장은 깨끗하다 — 반쯤 들어간 신원이 안 남는다")
    void 막힌_뒤에도_원장이_깨끗하다() {
        String phone = "010-2222-3333";
        service.importCsv(NAME, SOCIAL7, phone, null, "2026-07-01,가게A,10000\n");
        String rejected = com.finntech.mydata.util.Ci.of("다른사람", "8505051", phone);

        assertThatThrownBy(() -> service.importCsv("다른사람", "8505051", phone, null,
                "2026-07-02,가게B,20000\n"))
                .isInstanceOf(RealPersonImportService.PhoneAlreadyTakenException.class);

        assertThat(userRepository.findById(rejected)).as("거절된 신원은 안 남는다").isEmpty();
        assertThat(userRepository.findAllByPhoneBlindIndex(identityIndex.ofPhone(phone)))
                .as("그 번호에는 여전히 한 사람뿐이다").hasSize(1);
    }

    @Test
    @DisplayName("본인의 재신청은 막지 않는다 — 두 번째 카드사 명세서를 낼 수 있어야 한다")
    void 본인_재신청은_통과한다() {
        String phone = "010-2222-3333";
        service.importCsv(NAME, SOCIAL7, phone, null, "2026-07-01,가게A,10000\n");

        var again = service.importCsv(NAME, SOCIAL7, phone, null, "2026-07-02,가게B,20000\n");

        assertThat(again.accepted()).as("본인은 계속 넣을 수 있다").isEqualTo(1);
        assertThat(userRepository.findAllByPhoneBlindIndex(identityIndex.ofPhone(phone))).hasSize(1);
    }

    @Test
    @DisplayName("번호가 다르면 같은 이름이라도 통과한다 — 동명이인을 막지 않는다")
    void 동명이인은_안_막는다() {
        service.importCsv(NAME, SOCIAL7, "010-2222-3333", null, "2026-07-01,가게A,10000\n");

        var other = service.importCsv(NAME, "8505051", "010-4444-5555", null,
                "2026-07-02,가게B,20000\n");

        assertThat(other.accepted()).isEqualTo(1);
    }
}
