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
                2026-07-03,취소건,-5000
                2026-07-04
                """);
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(r.rejected()).isEqualTo(3);
        assertThat(r.problems()).extracting(RealPersonImportService.RowResult::line)
                .as("줄 번호가 있어야 원본에서 찾아 고칠 수 있다").containsExactly(2, 3, 4);
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
}
