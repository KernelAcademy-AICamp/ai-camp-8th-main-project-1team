package com.finntech.service;

import com.finntech.domain.Enums;
import com.finntech.repository.ConsumptionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>실제 카드 명세서를 받는 길</b> — 카드사마다 다른 표기를 견디고, 못 읽은 줄을 감추지 않는다.
 *
 * <p>이 앱이 지금까지 판정한 것은 전부 생성기가 만든 소비다. "시간이 지날수록 낭비가 줄어든다"는
 * 서비스 효과도 생성 가정이라, 모델이 그것을 재현했다고 해서 효과를 <b>발견</b>한 것은 아니다.
 * 실제 사람의 소비를 한 번 통과시켜 봐야 그 구분을 말할 수 있다.
 *
 * <p>여기서 지키는 것은 파싱 정확도만이 아니다 — <b>못 읽은 줄을 조용히 버리지 않는 것</b>이
 * 절반이다. 조용히 건너뛰면 "다 들어갔다"와 "절반만 들어갔다"가 화면에서 똑같아 보인다(§8-U).
 */
@SpringBootTest
@Transactional
class RealDataServiceTest {

    @Autowired RealDataService service;
    @Autowired ConsumptionRepository consumptionRepository;

    @BeforeEach
    void clean() {
        service.purge();
    }

    private long 적재수() {
        return consumptionRepository.countByUserIdAndSource(
                service.account().getId(), Enums.DataSource.CARD_UPLOAD);
    }

    @Test
    @DisplayName("전용 계정은 하나뿐이다 — 실데이터가 여러 계정에 흩어지면 '무엇이 진짜인지'를 못 되묻는다")
    void 계정은_하나다() {
        assertThat(service.account().getId()).isEqualTo(service.account().getId());
        assertThat(service.account().getNickname()).isEqualTo(RealDataService.ACCOUNT_NICKNAME);
    }

    @Test
    @DisplayName("계정에 신원을 저장하지 않는다 — 안 받는 것이 가장 확실한 보호다")
    void 신원을_안_받는다() {
        var u = service.account();
        assertThat(u.getCi()).as("CI를 만들 이유가 없다(마이데이터 연동 계정이 아니다)").isNull();
        assertThat(u.getBirthYear()).as("주민번호를 안 받으니 파생할 출생연도도 없다").isNull();
        // 닉네임에 숫자가 없어야 한다 — 전화번호·주민번호 조각이 계정 이름으로 새는 유일한 통로다.
        assertThat(u.getNickname()).as("계정 이름 자체가 신원을 말하면 안 된다").doesNotMatch(".*\\d.*");
    }

    @Test
    @DisplayName("카드사마다 다른 날짜 표기를 견딘다 — 한 파일 안에서도 섞여 나온다")
    void 날짜_표기() {
        var r = service.importCsv("""
                2026-07-01,스타벅스,4500
                2026.07.02,GS25,3200
                2026/07/03,올리브영,18000
                20260704,쿠팡,25000
                26-07-05,배달의민족,17000
                """);
        assertThat(r.accepted()).isEqualTo(5);
        assertThat(r.rejected()).isZero();
        assertThat(적재수()).isEqualTo(5);
    }

    @Test
    @DisplayName("가맹점명 속 쉼표를 따옴표로 살린다 — '스타벅스 강남R점, 1층'이 흔하다")
    void 따옴표_안의_쉼표() {
        var r = service.importCsv("2026-07-01,\"스타벅스 강남R점, 1층\",4500\n");
        assertThat(r.accepted()).isEqualTo(1);
    }

    @Test
    @DisplayName("금액의 쉼표·원·통화기호를 읽는다")
    void 금액_표기() {
        var r = service.importCsv("""
                2026-07-01,가게A,"12,000원"
                2026-07-02,가게B,₩8500
                """);
        assertThat(r.accepted()).isEqualTo(2);
    }

    @Test
    @DisplayName("머리글 줄은 오류로 세지 않는다 — 매번 1건 실패로 뜨면 진짜 오류가 묻힌다")
    void 머리글() {
        var r = service.importCsv("""
                이용일자,가맹점명,이용금액
                2026-07-01,스타벅스,4500
                """);
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(r.rejected()).as("머리글은 실패가 아니다").isZero();
    }

    @Test
    @DisplayName("못 읽은 줄을 조용히 버리지 않는다 — 줄 번호와 사유를 함께 돌려준다")
    void 실패를_감추지_않는다() {
        var r = service.importCsv("""
                2026-07-01,정상,4500
                날짜아님,가게,1000
                2026-07-03,취소건,-5000
                2026-07-04
                """);
        assertThat(r.accepted()).isEqualTo(1);
        assertThat(r.rejected()).isEqualTo(3);
        assertThat(r.problems()).extracting(RealDataService.RowResult::reason)
                .anySatisfy(s -> assertThat(s).contains("날짜를 못 읽음"))
                .anySatisfy(s -> assertThat(s).contains("취소·환불"))
                .anySatisfy(s -> assertThat(s).contains("칸이 3개 미만"));
        assertThat(r.problems()).extracting(RealDataService.RowResult::line)
                .as("줄 번호가 있어야 원본에서 찾아 고칠 수 있다").containsExactly(2, 3, 4);
    }

    @Test
    @DisplayName("업종코드가 없으면 분류하지 않는다 — 모르는 것을 아는 척하지 않는다")
    void 업종코드가_없으면_미분류() {
        service.importCsv("2026-07-01,어떤가게,10000\n");
        var saved = consumptionRepository.findAll().stream()
                .filter(c -> c.getSource() == Enums.DataSource.CARD_UPLOAD).toList();
        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getCategory().getCode())
                .isEqualTo(com.finntech.engine.IndustryCategoryMapper.UNCLASSIFIED);
    }

    @Test
    @DisplayName("시각은 정오로 채운다 — 0시로 두면 모든 결제가 '심야 결제'로 판정된다")
    void 모르는_시각은_정오() {
        service.addOne(LocalDate.of(2026, 7, 1), "가게", 10000, null);
        var saved = consumptionRepository.findAll().stream()
                .filter(c -> c.getSource() == Enums.DataSource.CARD_UPLOAD).toList();
        assertThat(saved.get(0).getOccurredAt().getHour())
                .as("모르는 값을 0으로 채우는 건 의미를 가진 축에서는 중립이 아니다").isEqualTo(12);
    }

    @Test
    @DisplayName("전량 파기가 실제로 지운다 — 넣는 길만 만들고 빼는 길을 미루면 그 상태가 기본값이 된다")
    void 파기() {
        service.importCsv("2026-07-01,가게,10000\n2026-07-02,가게,20000\n");
        assertThat(적재수()).isEqualTo(2);
        assertThat(service.purge()).isEqualTo(2);
        assertThat(적재수()).isZero();
    }

    @Test
    @DisplayName("더미와 안 섞인다 — 실데이터는 CARD_UPLOAD로만 들어간다")
    void 더미와_안_섞인다() {
        service.importCsv("2026-07-01,가게,10000\n");
        Long uid = service.account().getId();
        assertThat(consumptionRepository.countByUserIdAndSource(uid, Enums.DataSource.MYDATA)).isZero();
        assertThat(consumptionRepository.countByUserIdAndSource(uid, Enums.DataSource.DUMMY_SEED)).isZero();
        assertThat(consumptionRepository.countByUserIdAndSource(uid, Enums.DataSource.CARD_UPLOAD)).isEqualTo(1);
    }

    @Test
    @DisplayName("빈 입력·null도 죽지 않는다")
    void 빈_입력() {
        assertThat(service.importCsv(null).accepted()).isZero();
        assertThat(service.importCsv("").accepted()).isZero();
        assertThat(service.importCsv("\n\n# 주석\n").accepted()).isZero();
    }

    @Test
    @DisplayName("한 건 직접 입력 — CSV가 못 읽은 줄을 손으로 채우는 자리")
    void 직접_입력() {
        service.addOne(LocalDate.of(2026, 7, 9), "손으로 넣은 가게", 33000, null);
        assertThat(적재수()).isEqualTo(1);
    }

    @Test
    @DisplayName("여러 번 올려도 계정은 그대로다")
    void 반복_업로드() {
        Long first = service.importCsv("2026-07-01,가게,1000\n").userId();
        Long second = service.importCsv("2026-07-02,가게,2000\n").userId();
        assertThat(first).isEqualTo(second);
        assertThat(적재수()).isEqualTo(2);
        assertThat(List.of(first, second)).doesNotContainNull();
    }
}
