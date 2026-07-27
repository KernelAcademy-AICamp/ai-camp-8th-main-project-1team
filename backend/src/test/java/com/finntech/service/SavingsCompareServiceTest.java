package com.finntech.service;

import com.finntech.service.SavingsCompareService.Account;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 통장 비교의 순수 필터·정렬만 검증한다(외부 API 실호출은 하지 않는다). */
class SavingsCompareServiceTest {

    private static final List<String> EXCLUDE = List.of("간부", "청년", "장병", "미소", "청약");

    @Test
    void accountsWithRestrictedKeywordsAreExcluded() {
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
    void sortedByBaseRateDescending() {
        List<Account> all = List.of(
                new Account("A", "낮은적금", 2.0, 2.0),
                new Account("B", "높은적금", 4.5, 4.5),
                new Account("C", "중간적금", 3.3, 3.3));

        List<Account> out = SavingsCompareService.filterAndRank(all, EXCLUDE);

        assertThat(out).extracting(Account::name)
                .containsExactly("높은적금", "중간적금", "낮은적금");
    }

    @Test
    void tieOnBaseRateBrokenByMaxRate() {
        List<Account> all = List.of(
                new Account("A", "동률낮은최고", 3.5, 3.5),
                new Account("B", "동률높은최고", 3.5, 4.2));

        List<Account> out = SavingsCompareService.filterAndRank(all, EXCLUDE);

        assertThat(out).extracting(Account::name)
                .containsExactly("동률높은최고", "동률낮은최고");
    }

    @Test
    void nearestPeriodBucket은_지원되는_기간으로_매핑한다() {
        assertThat(SavingsCompareService.nearestPeriodBucket(6)).isEqualTo(6);
        assertThat(SavingsCompareService.nearestPeriodBucket(8)).isEqualTo(6);   // 6·12 중 6에 더 가까움
        assertThat(SavingsCompareService.nearestPeriodBucket(14)).isEqualTo(12); // 12·24 중 12
        assertThat(SavingsCompareService.nearestPeriodBucket(20)).isEqualTo(24); // 12·24 중 24
        assertThat(SavingsCompareService.nearestPeriodBucket(40)).isEqualTo(24); // 최대 버킷
        assertThat(SavingsCompareService.nearestPeriodBucket(0)).isEqualTo(12);  // 계획 없음 → 기본 12
    }

    @Test
    void parseRate는_문자열_금리를_숫자로_바꾸고_실패는_0() {
        assertThat(SavingsCompareService.parseRate("4.50")).isEqualTo(4.50);
        assertThat(SavingsCompareService.parseRate(" 3.1 ")).isEqualTo(3.1);
        assertThat(SavingsCompareService.parseRate(null)).isEqualTo(0.0);
        assertThat(SavingsCompareService.parseRate("N/A")).isEqualTo(0.0);
    }
}
