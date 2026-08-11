package com.finntech.service;

import com.finntech.service.SavingsCompareService.Account;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 통장 비교의 순수 필터·정렬만 검증한다(외부 API 실호출은 하지 않는다). */
class SavingsCompareServiceTest {

    private static final List<String> EXCLUDE = List.of("간부", "청년", "장병", "미소", "청약");

    @Test
    void 자격제한_키워드가_든_통장은_제외된다() {
        List<Account> all = List.of(
                new Account("우리은행", "Npay 우리 적금", 4.50, 4.50),
                new Account("KB국민은행", "KB장병내일준비적금", 4.00, 9.50),   // 장병 → 제외
                new Account("신한은행", "청년희망적금", 3.90, 6.00),          // 청년 → 제외
                new Account("농협", "미소드림적금", 3.80, 3.80),             // 미소 → 제외
                new Account("국민", "주택청약종합저축", 3.70, 3.70),         // 청약 → 제외
                new Account("하나은행", "간부사랑적금", 3.60, 3.60),         // 간부 → 제외
                new Account("카카오뱅크", "자유적금", 3.50, 3.70));

        List<Account> out = SavingsCompareService.filterAndRank(all, EXCLUDE);

        assertThat(out).extracting(Account::name)
                .containsExactly("Npay 우리 적금", "자유적금");
    }

    @Test
    void 기본금리_내림차순으로_정렬된다() {
        List<Account> all = List.of(
                new Account("A", "낮은적금", 2.0, 2.0),
                new Account("B", "높은적금", 4.5, 4.5),
                new Account("C", "중간적금", 3.3, 3.3));

        List<Account> out = SavingsCompareService.filterAndRank(all, EXCLUDE);

        assertThat(out).extracting(Account::name)
                .containsExactly("높은적금", "중간적금", "낮은적금");
    }

    @Test
    void 기본금리_동률이면_최고금리로_가른다() {
        List<Account> all = List.of(
                new Account("A", "동률낮은최고", 3.5, 3.5),
                new Account("B", "동률높은최고", 3.5, 4.2));

        List<Account> out = SavingsCompareService.filterAndRank(all, EXCLUDE);

        assertThat(out).extracting(Account::name)
                .containsExactly("동률높은최고", "동률낮은최고");
    }

    @Test
    void 가입제한_코드로는_여기서_거르지_않는다() {
        // join_deny는 양방향으로 틀린다(제한 상품이 1로, 제한없는 상품이 3으로 올라온다).
        // 자격 판정은 EligibilityLabelService가 자연어를 읽어서 하고, 이 단계는 정렬만 책임진다.
        List<Account> all = List.of(
                제한코드("코드3인데금리높음", 4.9, "3"),
                제한코드("코드1이고금리보통", 3.0, "1"));

        List<Account> out = SavingsCompareService.filterAndRank(all, EXCLUDE);

        assertThat(out).extracting(Account::name).containsExactly("코드3인데금리높음", "코드1이고금리보통");
    }

    @Test
    void nearestPeriodBucket은_지원되는_기간으로_매핑한다() {
        assertThat(SavingsCompareService.nearestPeriodBucket(6)).isEqualTo(6);
        assertThat(SavingsCompareService.nearestPeriodBucket(8)).isEqualTo(6);   // 6·12 중 6에 더 가까움
        assertThat(SavingsCompareService.nearestPeriodBucket(14)).isEqualTo(12); // 12·24 중 12
        assertThat(SavingsCompareService.nearestPeriodBucket(20)).isEqualTo(24); // 12·24 중 24
        assertThat(SavingsCompareService.nearestPeriodBucket(30)).isEqualTo(24); // 24·36 동률 → 작은 쪽 고정(결정론)
        assertThat(SavingsCompareService.nearestPeriodBucket(40)).isEqualTo(36); // 금감원은 36개월도 준다
        assertThat(SavingsCompareService.nearestPeriodBucket(0)).isEqualTo(12);  // 계획 없음 → 기본 12
    }

    @Test
    void parseRate는_문자열_금리를_숫자로_바꾸고_실패는_0() {
        assertThat(SavingsCompareService.parseRate("4.50")).isEqualTo(4.50);
        assertThat(SavingsCompareService.parseRate(" 3.1 ")).isEqualTo(3.1);
        assertThat(SavingsCompareService.parseRate(null)).isEqualTo(0.0);
        assertThat(SavingsCompareService.parseRate("N/A")).isEqualTo(0.0);
    }

    @Test
    void oneLine은_상품명에_섞인_생개행을_눌러_한_줄로_만든다() {
        // 금감원 응답은 상품명 안에도 이스케이프 안 된 개행이 들어온다 — 목록에서 깨진다.
        assertThat(SavingsCompareService.oneLine("Sh해양플라스틱Zero!적금\n(자유적립식)"))
                .isEqualTo("Sh해양플라스틱Zero!적금 (자유적립식)");
        assertThat(SavingsCompareService.oneLine("  앞뒤\t공백  적금 ")).isEqualTo("앞뒤 공백 적금");
        assertThat(SavingsCompareService.oneLine(null)).isEmpty();
    }

    /** 가입제한 코드만 다르게 두는 헬퍼. 나머지 금감원 필드는 이 테스트와 무관해 비운다. */
    private static Account 제한코드(String name, double rate, String joinDeny) {
        return new Account("은행", name, rate, rate, 12, "자유적립식", joinDeny, "", "", "key:" + name,
                SavingsCompareService.ProductKind.SAVING);
    }

    // ── FP-01 매칭 계약으로의 변환 (§10 3단계) ─────────────────

    @Test
    void 적립방식과_상품군으로_매칭_그룹이_갈린다() {
        assertThat(SavingsCompareService.accrualTypeOf(적금("자유적립식")))
                .isEqualTo(SavingsMatchInputs.AccrualType.FLEXIBLE);
        assertThat(SavingsCompareService.accrualTypeOf(적금("정액적립식")))
                .isEqualTo(SavingsMatchInputs.AccrualType.FIXED);
        assertThat(SavingsCompareService.accrualTypeOf(예금()))
                .isEqualTo(SavingsMatchInputs.AccrualType.DEPOSIT);
    }

    /** 금감원은 최소 가입금액을 주지 않는다 — 없는 값으로 M5가 상품을 지우면 안 된다. */
    @Test
    void 변환된_후보는_최소납입금액이_비어_M5를_통과한다() {
        var candidate = SavingsCompareService.toCandidate(적금("자유적립식"));

        assertThat(candidate.minMonthlyAmount()).isNull();
        assertThat(SavingsMatchService.fitsSize(candidate, 10_000L)).isTrue();
    }

    /** spclCnd가 자연어뿐이라(D2) 상품별 조건을 모른다 → 카드실적·급여이체를 대표값으로 넣는다. */
    @Test
    void 변환된_후보는_우대조건을_카드실적_급여이체로_가정한다() {
        var candidate = SavingsCompareService.toCandidate(적금("자유적립식"));

        assertThat(candidate.requiredConditions()).containsExactlyInAnyOrder(
                SavingsMatchInputs.PreferentialCondition.CARD_PERFORMANCE,
                SavingsMatchInputs.PreferentialCondition.SALARY_TRANSFER);
        // null(미파싱)이 아니어야 M6의 이분(전부 충족=최고 / 미충족=기본)이 성립한다
        assertThat(candidate.requiredConditions()).isNotNull();
    }

    @Test
    void 지원_예치기간에_단기_1개월_3개월이_포함된다() {
        assertThat(SavingsCompareService.PERIOD_BUCKETS).contains(1, 3);
        assertThat(SavingsCompareService.nearestPeriodBucket(1)).isEqualTo(1);
        assertThat(SavingsCompareService.nearestPeriodBucket(2)).isEqualTo(1);
        assertThat(SavingsCompareService.nearestPeriodBucket(4)).isEqualTo(3);
    }

    private static Account 적금(String reserveType) {
        return new Account("은행", "적금", 3.0, 4.0, 12, reserveType, "1", "", "", "key:s",
                SavingsCompareService.ProductKind.SAVING);
    }

    private static Account 예금() {
        return new Account("은행", "예금", 3.0, 4.0, 12, "", "1", "", "", "key:d",
                SavingsCompareService.ProductKind.DEPOSIT);
    }
}
