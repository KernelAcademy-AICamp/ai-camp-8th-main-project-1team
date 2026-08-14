package com.finntech.service;

import java.time.LocalDate;
import java.util.List;

/**
 * 저축 상품 매칭(FP-01, M1~M10)의 입력 재료 — `07_취향분석및추천_Agent_설계.md` §4.5.
 *
 * <p><b>왜 계약을 따로 두나(seam).</b> {@link SavingsCompareService.Account}는 금감원 오픈API 응답 모양
 * 그대로다({@code joinDeny}·{@code spclCnd}·{@code prdtKey}). 매칭 규칙이 그 DTO에 직접 붙으면 출처가
 * 바뀔 때(파킹통장·은행 공시 중도해지이율·제휴 데이터 전환) 규칙까지 흔들린다. 그래서
 * {@link FundFlowInputs}와 같은 방식으로 <b>규칙이 필요로 하는 것만</b> 계약으로 세우고, 출처 → 계약 변환은
 * 바깥에서 한다. 의존을 구현이 아니라 계약에 둔다(DIP).
 *
 * <p>값을 아직 못 받는 항목은 {@code null}로 둔다. 규칙은 {@code null}을 <b>"모른다"</b>로 다루며
 * "없다"나 "0"으로 바꿔 읽지 않는다 — 재료 없는 축은 UNKNOWN으로 두고 숨기지 않는다(§14).
 *
 * @param candidates    후보 상품. 적립 방식(파킹·자유·예금·정액)이 섞여 들어와도 되며 M1이 가른다.
 * @param keptMeanAmount 월 확정 지킨 돈 평균(원). <b>{@code null}이면 M5 규모 필터를 건너뛴다</b> —
 *                       없는 값으로 상품을 걸러내면 근거 없이 후보가 사라진다.
 */
public record SavingsMatchInputs(
        List<ProductCandidate> candidates,
        Long keptMeanAmount
) {

    /**
     * 적립 방식 — M1이 상품을 가르는 기준이자 <b>그룹 순서가 곧 추천 결과</b>가 되는 축이다(§4.5 M1).
     * 전체를 하나의 순위표로 합치지 않는다.
     */
    public enum AccrualType {
        /** 파킹통장 — 수시입출금. 유동성이 급할 때 상단(M2). */
        PARKING,
        /** 자유적립식 — 납입액이 매달 달라도 되는 적금. 버퍼가 두껍고 지출이 예측 가능할 때 상단(M3). */
        FLEXIBLE,
        /**
         * 정기예금 — 목돈을 한 번에 넣고 만기까지 묶는다.
         *
         * <p>적립식(매달 조금씩)과 예금(목돈 한 번에)은 납입 구조가 아예 달라 한 그룹에 섞을 수 없어
         * 넷째 그룹으로 둔다(§4.5 M1, 2026-08-11 문서 반영 완료).
         */
        DEPOSIT,
        /** 정액적립식 — 매달 고정 금액 납입. 판정 없이 항상 노출한다(M4). */
        FIXED
    }

    // ── 우대조건 (M6) ────────────────────────────────────────────────────────

    /**
     * 상품이 요구하는 우대조건의 <b>종류</b>. 가산폭은 담지 않는다 — 실측(2026-08-11 적금 58건)에서
     * {@code %p}가 숫자로 적힌 25건 중 가산폭 합이 `(최고−기본)`과 맞는 것은 4건(16%)뿐이라, 파싱해도
     * 못 쓴다(§4.5 · §8.1 D2).
     *
     * <p>{@code judgeable}이 <b>이 목록의 핵심</b>이다. 우리가 마이데이터로 충족 여부를 가릴 수 있는 축은
     * 카드 실적·급여이체 둘뿐인데, 실측상 적금 58건 중 <b>48건(83%)이 그 둘을 언급조차 안 한다.</b>
     * 그래서 나머지를 "미충족"으로 뭉뚱그리지 않고 <b>판정 불가</b>로 따로 센다(M6 3분기).
     */
    public enum PreferentialCondition {
        /** 카드 실적(전월 이용금액). 실측 12%. */
        CARD_PERFORMANCE("카드 실적", true),
        /** 급여이체. 실측 12%. */
        SALARY_TRANSFER("급여이체", true),
        /** 자동이체·공과금. <b>실측 최빈(41%)인데 판정할 수 없다</b> — 더미에 계좌 자동이체 거래가 없다(R7). */
        AUTO_TRANSFER("자동이체", false),
        /** 첫거래·신규 가입. 실측 28%. */
        FIRST_TRADE("첫거래·신규", false),
        /** 마케팅 수신 동의. 실측 22%. */
        MARKETING_CONSENT("마케팅 동의", false),
        /** 비대면·모바일 가입. */
        ONLINE_JOIN("비대면·앱 가입", false),
        /** 금리쿠폰. */
        RATE_COUPON("금리쿠폰", false),
        /** 이벤트·추첨. 채울 수 있는 조건이 아니다. */
        EVENT("이벤트·추첨", false),
        /**
         * 주거래·예적금 보유. <b>정의가 상품마다 달라 판정하지 않는다</b>(M6 ⑥) — 어떤 상품은 계좌 보유만,
         * 어떤 상품은 `실적 월 수가 계약기간의 1/2 이상`을 요구한다.
         */
        MAIN_BANK("주거래·예적금 보유", false),
        /** 사전에 없는 종류. 문구는 있는데 무엇을 요구하는지 못 읽었다는 뜻이다. */
        OTHER("그 밖의 조건", false);

        private final String label;
        private final boolean judgeable;

        PreferentialCondition(String label, boolean judgeable) {
            this.label = label;
            this.judgeable = judgeable;
        }

        /** 화면에 그대로 쓰는 이름. */
        public String label() {
            return label;
        }

        /** 마이데이터로 충족 여부를 가릴 수 있는가. false면 M6에서 <b>판정 불가</b>다. */
        public boolean judgeable() {
            return judgeable;
        }
    }

    /**
     * 조건이 <b>특정 금융사</b>의 거래를 요구하는가. 실측상 28%(16/58)가 `당행`·`주거래`·`입출식 계좌`를 건다.
     */
    public enum IssuerScope {
        /** 금융사를 가리지 않는다 — 아무 카드/계좌로도 채울 수 있다. */
        ANY,
        /** 상품을 파는 그 금융사여야 한다(`우리은행 입출식 계좌에서…`). 그 금융사 것으로 좁혀 판정한다(M6 ④). */
        OWN
    }

    /**
     * 상품이 요구하는 조건 하나.
     *
     * @param type  조건 종류.
     * @param scope 당행 한정 여부. 모르면 {@link IssuerScope#ANY}로 둔다 — 좁히지 않는 쪽이 덜 단정적이다.
     */
    public record RequiredCondition(PreferentialCondition type, IssuerScope scope) {

        public static RequiredCondition any(PreferentialCondition type) {
            return new RequiredCondition(type, IssuerScope.ANY);
        }
    }

    // ── 가입 금액 (M5) ───────────────────────────────────────────────────────

    /** 금액 조건이 <b>매달</b>인지 <b>총액</b>인지. 적립식은 월 납입, 예금은 목돈이라 비교 대상이 다르다. */
    public enum AmountUnit {
        /** 적금 — 월 납입액. `kept_mean`과 직접 비교한다. */
        MONTHLY,
        /** 예금·파킹 — 총 예치액. 월 저축액과 비교 대상이 아니라 <b>표시만</b> 한다(§4.5 M5). */
        TOTAL
    }

    /**
     * 상품이 받아 주는 금액(M5). 하한과 상한을 <b>다르게 다룬다</b> —
     * 하한 미달은 가입 자체가 안 되므로 목록에서 빼고, 상한 초과는 가입이 되므로 남기고 사실만 적는다.
     *
     * @param min  최소 가입/납입액(원). {@code null}이면 미수집 — 실측상 하한은 `etc_note` 자연어에만
     *             있어 적금 58건 중 29건(50%)에서만 나온다.
     * @param max  최대 가입/납입액(원). {@code null}이면 미수집. `max_limit`으로 74% 수집된다.
     * @param unit 위 두 값이 월 단위인지 총액인지.
     */
    public record AmountLimit(Long min, Long max, AmountUnit unit) {

        /** 이 금액이 하한에 못 미치는가. 하한을 모르면 <b>막지 않는다</b>. */
        public boolean belowMin(long amount) {
            return min != null && amount < min;
        }

        /** 이 금액이 상한을 넘는가. 상한을 모르면 <b>넘지 않았다고 본다</b>(없는 조건을 지어내지 않는다). */
        public boolean aboveMax(long amount) {
            return max != null && amount > max;
        }
    }

    // ── 중도해지이율 (M10) ───────────────────────────────────────────────────

    /**
     * 중도해지이율(M10) — <b>금감원에는 이 칸이 없어</b> 각 은행 상품공시에서 따로 모은 값이다
     * ({@code mtrt_int}는 *만기 후* 이자율이라 다른 값이다).
     *
     * <p><b>모양은 실제 공시에서 역으로 뽑았다</b>(케이뱅크 `코드K 자유적금`, 2026-08-11). 카드 스키마를
     * BC 3장에서 뽑은 것과 같은 순서다 — 지어낸 구조로 그릇을 먼저 만들면 실제 표기와 어긋난다.
     *
     * <pre>
     *   1개월(30일) 미만    연 0.10 %
     *   1개월(30일) 이상    연 0.30 %
     *   3개월(90일) 이상    연 0.50 %
     *   6개월(180일) 이상   기본금리 × 70% × 경과일수/계약일수  (최저 연 0.50 %)
     *   9개월(270일) 이상   기본금리 × 80% × 경과일수/계약일수  (최저 연 0.50 %)
     * </pre>
     *
     * 여기서 세 가지가 드러난다 — 구간은 <b>`이상`(하한)</b>으로 끊기고, 배수 구간에는
     * <b>경과일수 비례</b>가 붙으며, 그 아래로 안 내려가는 <b>최저이율</b>이 따로 있다.
     *
     * @param tiers 하한이 작은 구간부터. `N개월 미만`으로 적힌 첫 줄은 {@code fromMonths=0}이다.
     * @param asOf  수집 기준일. 화면에 병기한다(§4.4와 같은 방어 — 스냅샷임을 밝힌다).
     */
    public record EarlyTermination(List<Tier> tiers, LocalDate asOf) {

        /**
         * @param fromMonths    이 개월 수부터 적용되는 구간.
         * @param fromExclusive 하한을 <b>포함하지 않는가</b>. `N개월 초과`면 true, `N개월 이상`이면 false.
         *                      <b>경계에서 결과가 갈린다</b> — 토스뱅크는 `3개월 초과 6개월 이하`로 끊는데
         *                      이걸 `6개월 이상`으로 읽으면 12개월 상품을 6개월에 깼을 때 다음 구간(70%)을
         *                      집어 실제(50%)보다 많이 준다고 말하게 된다.
         * @param rate          고정이율(%). {@code multiplier}와 <b>둘 중 하나만</b> 채운다.
         * @param multiplier    약정이율에 곱하는 비율(0.7 = 70%).
         * @param prorated      `× 경과일수/계약일수`가 붙는가. 붙으면 절반 시점에 절반만 받는다.
         * @param floorRate     이 아래로는 안 내려가는 최저이율(%). 없으면 null.
         */
        public record Tier(int fromMonths, boolean fromExclusive, Double rate, Double multiplier,
                           boolean prorated, Double floorRate) {

            /** 고정이율 구간(하한 포함). */
            public static Tier fixed(int fromMonths, double rate) {
                return new Tier(fromMonths, false, rate, null, false, null);
            }

            /** 이 예치기간이 이 구간에 드는가. */
            boolean covers(int heldMonths) {
                return fromExclusive ? heldMonths > fromMonths : heldMonths >= fromMonths;
            }
        }

        /**
         * 이 시점에 깨면 받는 이율(%). 구간을 못 찾으면 {@code null}이다 —
         * <b>0으로 바꿔 읽지 않는다</b>(모르는 것과 `연 0%`는 다르다).
         *
         * @param heldMonths   실제 예치한 개월 수.
         * @param termMonths   약정 기간(개월). 경과일수 비례 계산에 쓴다. 0 이하면 비례를 적용하지 않는다.
         * @param contractRate 약정이율(%). 배수 구간에 곱한다.
         */
        public Double rateAt(int heldMonths, int termMonths, double contractRate) {
            if (tiers == null || tiers.isEmpty()) return null;
            Tier applicable = null;
            for (Tier t : tiers) {
                if (t.covers(heldMonths)) applicable = t;           // 하한이 가장 큰 구간이 이긴다
            }
            if (applicable == null) return null;
            if (applicable.rate() != null) return applicable.rate();
            if (applicable.multiplier() == null) return null;

            double value = contractRate * applicable.multiplier();
            if (applicable.prorated() && termMonths > 0) {
                value *= (double) heldMonths / termMonths;
            }
            return applicable.floorRate() == null ? value : Math.max(value, applicable.floorRate());
        }
    }

    /**
     * 매칭 후보 상품 한 건.
     *
     * @param productKey        상품 식별키(금융사코드:상품코드). 동률·중복 판별용.
     * @param company           금융회사명. M9 최종 동점 처리의 가나다순 기준이자 M6 ④의 당행 판정 기준.
     * @param name              상품명.
     * @param accrualType       적립 방식(M1).
     * @param baseRate          기본금리(%).
     * @param maxRate           최고금리(%) — 우대조건을 모두 채웠을 때.
     * @param termMonths        만기(개월). M9 2순위 동점 처리 기준.
     * @param amountLimit       가입 금액 조건(M5). {@code null}이면 미수집이라 M5가 통과시킨다.
     * @param requiredConditions 이 상품이 최고금리를 주기 위해 요구하는 조건(M6).
     *                          <b>{@code null}이면 아직 라벨링하지 않았다</b>는 뜻이고, <b>빈 목록</b>은
     *                          <b>라벨링했더니 요구 조건이 없더라</b>는 뜻이다(실측: `퍼스트가계적금`의
     *                          `spcl_cnd`가 `없음`). 둘은 다르게 처리된다 — 빈 목록은 곧바로 최고금리다.
     * @param earlyTermination  중도해지이율(M10). {@code null}이면 미수집이라 화면에서 그 자리를 비운다.
     */
    public record ProductCandidate(
            String productKey,
            String company,
            String name,
            AccrualType accrualType,
            double baseRate,
            double maxRate,
            int termMonths,
            AmountLimit amountLimit,
            List<RequiredCondition> requiredConditions,
            EarlyTermination earlyTermination
    ) {}
}
