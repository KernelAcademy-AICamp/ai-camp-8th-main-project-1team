package com.finntech.service;

import com.finntech.guardian.GuardianSettlementService;
import com.finntech.service.ParkingAccountSource.ParkingAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;

/**
 * 지킨 돈 굴리기 — 결산 화면에서 <b>"이 돈을 그냥 두실 건가요"</b>에 숫자로 답한다.
 * 정본은 `07_취향분석및추천_Agent_설계.md` §4.7.
 *
 * <p><b>왜 이 자리인가.</b> 챌린지를 끝내면 지킨 돈이 생기는데 <b>그 돈을 어디 둘지는 아무도 안 알려 준다.</b>
 * 예적금 비교 화면을 따로 두면 "얼마 넣을 건데?"를 사용자가 타이핑해야 하지만, 결산 시점에는 그 금액이
 * 이미 정해져 있다(사용자 결정 2026-08-12).
 *
 * <p><b>개인화가 아니다.</b> 마이데이터를 읽지 않고, 우대조건 충족을 판정하지 않는다. 금액만 자동으로
 * 채워질 뿐 <b>87,000원인 사람은 누구나 같은 답</b>을 받는다 — 예적금을 비교만 하기로 한 결정(§4.5)과
 * 어긋나지 않는다.
 *
 * <p><b>파킹통장만 쓴다.</b> 지킨 돈은 매달 금액이 다르고 결산마다 덩어리로 들어온다. 정기예금은 만기까지
 * 묶여 다음 달 지킨 돈을 못 얹고, 정액적금은 매달 같은 금액을 요구한다. 파킹만 이 흐름과 맞는다.
 *
 * <p><b>금액은 ②가 계산한 것을 그대로 쓴다</b>(R10). 여기서 하는 것은 <b>합산</b>이지 재계산이 아니다 —
 * 거래를 다시 훑으면 결산 화면과 다른 숫자를 말하게 된다.
 *
 * <p><b>표현의 선</b>(§4.4 카드 추천과 같은 자리).
 * <pre>
 *   O  "이 페이스로 1년이면 1,054,120원"        ← 산수 + 가정을 밝힘
 *   X  "1년 목표 100만원까지 얼마 안 남았어요"    ← 없던 목표를 만든다 (R9)
 *   X  "지금 가입하세요"                        ← 가입 편의는 주지 않는다 (마스터 원칙 5)
 * </pre>
 */
@Service
public class KeptMoneyParkingService {

    private final GuardianSettlementService settlementService;
    private final ParkingAccountSource parkingAccountSource;
    private final Clock clock;

    /**
     * 이자소득세율. 일반과세 15.4%(소득세 14% + 지방소득세 1.4%)다.
     * 비과세종합저축·세금우대는 <b>사람마다 달라서 쓰지 않는다</b> — 개인화가 되고, 우리는 그 자격을 모른다.
     */
    private final double interestTaxRate;

    /** 화면에 세울 파킹통장 수. 목록을 늘리면 비교가 아니라 나열이 된다. */
    private final int optionLimit;

    /** 환산 기간(개월). "1년 두면"이 사람이 가장 쉽게 그리는 단위다. */
    private final int projectionMonths;

    public KeptMoneyParkingService(
            GuardianSettlementService settlementService,
            ParkingAccountSource parkingAccountSource,
            @Value("${finntech.kept-money-parking.interest-tax-rate:0.154}") double interestTaxRate,
            @Value("${finntech.kept-money-parking.option-limit:3}") int optionLimit,
            @Value("${finntech.kept-money-parking.projection-months:12}") int projectionMonths,
            Clock clock) {
        this.settlementService = settlementService;
        this.parkingAccountSource = parkingAccountSource;
        this.interestTaxRate = interestTaxRate;
        this.optionLimit = optionLimit;
        this.projectionMonths = projectionMonths;
        this.clock = clock;
    }

    // ======================================================================
    //  공개 API
    // ======================================================================

    /**
     * 결산 화면에 실을 「지킨 돈 굴리기」. <b>보여줄 게 없으면 {@code null}</b>이다 —
     * 지킨 돈이 0이거나(없는 성과를 축하하지 않는다) 파킹 조회가 막혔을 때다.
     */
    public KeptMoneyPlan plan(Long userId) {
        List<GuardianSettlementService.SettledChallenge> history = settlementService.history(userId);
        if (history.isEmpty()) return null;

        // history는 최신순이다(저장소 정렬 그대로 · 설계원칙 3).
        long thisChallenge = Math.max(0, history.get(0).securedSaving());
        long cumulative = history.stream().mapToLong(h -> Math.max(0, h.securedSaving())).sum();
        if (cumulative <= 0) return null;

        List<ParkingAccount> accounts = parkingAccountSource.accounts().stream()
                // 기본금리순. primeRate(최고)는 예치금 구간·첫거래 같은 조건이 붙어 줄 세우기에 못 쓴다.
                .sorted(Comparator.comparingDouble(ParkingAccount::baseRate).reversed()
                        .thenComparing(ParkingAccount::company)
                        .thenComparing(ParkingAccount::productKey))   // 전순서 보장(설계원칙 3)
                .limit(Math.max(1, optionLimit))
                .toList();
        if (accounts.isEmpty()) return null;

        List<ParkingOption> options = accounts.stream()
                .map(a -> option(a, thisChallenge, cumulative))
                .toList();

        return new KeptMoneyPlan(thisChallenge, cumulative, projectionMonths,
                thisChallenge * (long) projectionMonths, options, LocalDate.now(clock));
    }

    private ParkingOption option(ParkingAccount a, long thisChallenge, long cumulative) {
        long paceInterest = monthlyAfterTaxInterest(thisChallenge, a.baseRate(), projectionMonths, interestTaxRate);
        long keptInterest = lumpAfterTaxInterest(cumulative, a.baseRate(), projectionMonths, interestTaxRate);
        return new ParkingOption(a.company(), a.name(), a.baseRate(),
                paceInterest, thisChallenge * (long) projectionMonths + paceInterest,
                keptInterest, cumulative + keptInterest);
    }

    // ======================================================================
    //  순수 계산 (단위 테스트 진입점)
    // ======================================================================

    /**
     * 목돈을 {@code months}개월 두었을 때 <b>세후</b> 이자(원). 단리로 센다 —
     * 파킹통장은 이자 지급 주기가 상품마다 달라(매일·매월) 복리로 계산하면 실제보다 부풀려진다.
     */
    static long lumpAfterTaxInterest(long principal, double annualRatePct, int months, double taxRate) {
        if (principal <= 0 || annualRatePct <= 0 || months <= 0) return 0;
        double gross = principal * (annualRatePct / 100.0) * (months / 12.0);
        return Math.round(gross * (1 - taxRate));
    }

    /**
     * <b>매달 말</b> 같은 금액을 {@code months}번 넣었을 때 세후 이자(원).
     *
     * <p><b>전액이 1년 내내 있는 게 아니다.</b> k번째 달에 넣은 돈은 {@code months - k}개월만 예치되므로
     * 예치 개월수의 합은 {@code months(months-1)/2}다. 12개월이면 66개월분 = 월납입액의 5.5개월치다.
     * 이걸 빼먹고 <b>총액 × 금리</b>로 세면 12개월 기준 <b>2.2배로 부풀려진다</b>
     * (104만 × 2.5% = 26,100원 vs 실제 11,962원).
     *
     * <p>결산이 월말에 나오므로 <b>월말 입금</b>을 가정한다. 월초 입금이면 한 달치가 더 붙는다.
     */
    static long monthlyAfterTaxInterest(long monthly, double annualRatePct, int months, double taxRate) {
        if (monthly <= 0 || annualRatePct <= 0 || months <= 1) return 0;
        double depositMonths = months * (months - 1) / 2.0;
        double gross = monthly * (annualRatePct / 100.0 / 12.0) * depositMonths;
        return Math.round(gross * (1 - taxRate));
    }

    // ======================================================================
    //  산출 타입
    // ======================================================================

    /**
     * @param thisChallenge    이번 챌린지에서 지킨 돈(원).
     * @param cumulative       확정된 챌린지 전부의 합(원).
     * @param projectionMonths 환산 기간(개월).
     * @param pacePrincipal    `이 페이스로 {projectionMonths}개월` 이어졌을 때의 <b>원금</b>.
     *                         <b>가정이므로 화면이 그 사실을 밝혀야 한다</b>(R9 — 목표로 읽히면 안 된다).
     * @param asOf             금리 조회 기준일. 화면에 병기한다.
     */
    public record KeptMoneyPlan(long thisChallenge, long cumulative, int projectionMonths,
                                long pacePrincipal, List<ParkingOption> options, LocalDate asOf) {}

    /**
     * @param baseRate       조건 없이 받는 금리(%). 최고금리는 조건부라 싣지 않는다.
     * @param paceInterest   `이 페이스로 계속` 넣었을 때의 세후 이자.
     * @param paceTotal      위 원금 + 이자.
     * @param keptInterest   지금까지 모은 돈을 그대로 뒀을 때의 세후 이자.
     * @param keptTotal      누적 원금 + 이자.
     */
    public record ParkingOption(String company, String name, double baseRate,
                                long paceInterest, long paceTotal,
                                long keptInterest, long keptTotal) {}
}
