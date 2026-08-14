package com.finntech.intake;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 양식 검증 — <b>정해진 양식 외에는 아무것도 DB 에 닿지 않는다</b>는 약속을 못박는다.
 */
class StatementValidatorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 12);

    private static StatementValidator.Result validate(String csv) {
        return StatementValidator.validate(csv, TODAY);
    }

    @Test
    @DisplayName("카드사마다 다른 날짜 표기를 전부 읽는다")
    void readsAllDateFormats() {
        StatementValidator.Result result = validate("""
                2026-08-01,스타벅스,5600,,1234567890
                2026.08.02,GS25,3200,,
                2026/08/03,올리브영,12000,,
                20260804,쿠팡,25000,,
                26-08-05,배달의민족,18000,,
                26.08.06,카카오T,7800,,
                """);
        assertThat(result.rows()).hasSize(6);
        assertThat(result.problems()).isEmpty();
    }

    @Test
    @DisplayName("취소·환불(음수)을 살린다 — 버리면 안 쓴 돈이 소비로 잡힌다")
    void keepsRefunds() {
        StatementValidator.Result result = validate("""
                2026-08-01,쿠팡,25000,,
                2026-08-02,쿠팡,-25000,,
                """);
        assertThat(result.rows()).hasSize(2);
        assertThat(result.refundCount()).isEqualTo(1);
        assertThat(result.refundAmount()).isEqualTo(-25_000);
        assertThat(result.totalAmount()).isZero();      // 합계에서 상쇄된다
    }

    @Test
    @DisplayName("못 읽은 줄은 줄 번호와 사유를 달고 돌아온다 — 조용히 건너뛰지 않는다")
    void reportsProblemsWithLineNumbers() {
        StatementValidator.Result result = validate("""
                2026-08-01,스타벅스,5600,,
                2026-13-45,이상한날짜,1000,,
                2026-08-03,금액없음,없음,,
                2026-08-04
                """);
        assertThat(result.rows()).hasSize(1);
        assertThat(result.problems()).hasSize(3);
        assertThat(result.problems().get(0).line()).isEqualTo(2);
        assertThat(result.problems().get(0).reason()).contains("날짜를 못 읽음");
        assertThat(result.problems().get(1).line()).isEqualTo(3);
        assertThat(result.problems().get(1).reason()).contains("금액을 못 읽음");
        assertThat(result.problems().get(2).line()).isEqualTo(4);
        assertThat(result.problems().get(2).reason()).contains("칸이 3개 미만");
    }

    @Test
    @DisplayName("미래 날짜는 거부한다")
    void rejectsFutureDates() {
        StatementValidator.Result result = validate("2026-12-31,미래결제,10000,,\n");
        assertThat(result.rows()).isEmpty();
        assertThat(result.problems().get(0).reason()).contains("미래 날짜");
    }

    @Test
    @DisplayName("3년보다 오래된 날짜는 거부한다")
    void rejectsTooOldDates() {
        StatementValidator.Result result = validate("2020-01-01,옛날결제,10000,,\n");
        assertThat(result.rows()).isEmpty();
        assertThat(result.problems().get(0).reason()).contains("오래된 날짜");
    }

    @Test
    @DisplayName("사업자번호는 10자리가 정확할 때만 싣는다 — 잘린 번호는 엉뚱한 사업자에 붙는다")
    void onlyExactBusinessNumbers() {
        StatementValidator.Result result = validate("""
                2026-08-01,정상,5600,,123-45-67890
                2026-08-02,잘림,5600,,12345
                2026-08-03,없음,5600,,
                """);
        assertThat(result.rows()).hasSize(3);
        assertThat(result.rows().get(0).businessNumber()).isEqualTo("1234567890");
        assertThat(result.rows().get(1).businessNumber()).isNull();
        assertThat(result.rows().get(2).businessNumber()).isNull();
        assertThat(result.withBusinessNumber()).isEqualTo(1);
    }

    @Test
    @DisplayName("업종코드도 6자리일 때만 싣는다")
    void onlyExactIndustryCodes() {
        StatementValidator.Result result = validate("""
                2026-08-01,정상,5600,552101,
                2026-08-02,이상,5600,55,
                """);
        assertThat(result.rows().get(0).industryCode()).isEqualTo("552101");
        assertThat(result.rows().get(1).industryCode()).isNull();
    }

    @Test
    @DisplayName("가맹점명에 쉼표가 있어도 따옴표 안이면 살린다")
    void keepsQuotedCommas() {
        StatementValidator.Result result = validate("""
                2026-08-01,"스타벅스 강남R점, 1층",5600,,
                """);
        assertThat(result.rows()).hasSize(1);
        assertThat(result.rows().get(0).merchant()).isEqualTo("스타벅스 강남R점, 1층");
    }

    @Test
    @DisplayName("60자를 넘는 가맹점명은 자르지 않고 거부한다")
    void rejectsTooLongMerchant() {
        StatementValidator.Result result = validate(
                "2026-08-01," + "가".repeat(61) + ",5600,,\n");
        assertThat(result.rows()).isEmpty();
        assertThat(result.problems().get(0).reason()).contains("60자");
    }

    @Test
    @DisplayName("제어문자가 섞인 가맹점명은 거부한다")
    void rejectsControlChars() {
        StatementValidator.Result result = validate("2026-08-01,스타벅스,5600,,\n");
        assertThat(result.rows()).isEmpty();
        assertThat(result.problems().get(0).reason()).contains("제어문자");
    }

    @Test
    @DisplayName("금액 상한을 넘으면 거부한다 — 오타 하나가 합계를 뒤집는다")
    void rejectsHugeAmount() {
        StatementValidator.Result result = validate("2026-08-01,오타,999999999999,,\n");
        assertThat(result.rows()).isEmpty();
        assertThat(result.problems().get(0).reason()).contains("상한");
    }

    @Test
    @DisplayName("머리글은 조용히 건너뛴다 — 사용자가 지울 필요가 없어야 한다")
    void skipsHeaderRow() {
        StatementValidator.Result result = validate("""
                날짜,가맹점,금액,업종코드,사업자번호
                2026-08-01,스타벅스,5600,,
                """);
        assertThat(result.rows()).hasSize(1);
        assertThat(result.problems()).isEmpty();
    }

    @Test
    @DisplayName("통과한 것만 CSV 로 되돌아간다 — 제공자에는 검증된 줄만 간다")
    void csvCarriesOnlyValidRows() {
        StatementValidator.Result result = validate("""
                2026-08-01,스타벅스,5600,,1234567890
                2026-13-45,이상한날짜,1000,,
                """);
        String csv = result.toCsv();
        assertThat(csv).contains("스타벅스").doesNotContain("이상한날짜");
        assertThat(csv.strip().lines()).hasSize(1);
    }

    @Test
    @DisplayName("요약 지표가 맞다 — admin 이 보는 값이다")
    void summarizes() {
        StatementValidator.Result result = validate("""
                2026-08-01,스타벅스,5600,,1234567890
                2026-08-02,스타벅스,4100,,1234567890
                2026-08-03,GS25,3200,,
                2026-08-04,GS25,-3200,,
                """);
        assertThat(result.rows()).hasSize(4);
        assertThat(result.totalAmount()).isEqualTo(9_700);
        assertThat(result.distinctMerchants()).isEqualTo(2);
        assertThat(result.withBusinessNumber()).isEqualTo(2);
        assertThat(result.from()).contains(LocalDate.of(2026, 8, 1));
        assertThat(result.to()).contains(LocalDate.of(2026, 8, 4));
    }
}
