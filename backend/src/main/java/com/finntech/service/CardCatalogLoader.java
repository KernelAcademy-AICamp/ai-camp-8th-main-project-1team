package com.finntech.service;

import com.finntech.domain.CardAnnualFee;
import com.finntech.domain.CardBenefit;
import com.finntech.domain.CardBenefitCap;
import com.finntech.domain.CardBenefitTarget;
import com.finntech.domain.CardCombinedCap;
import com.finntech.domain.CardExclusion;
import com.finntech.domain.CardPerformanceRule;
import com.finntech.domain.CardPerformanceTier;
import com.finntech.domain.CardProduct;
import com.finntech.repository.CardProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 카드 카탈로그를 기동할 때 표로 옮긴다 — {@code card-catalog.json} → 표 아홉(V36).
 *
 * <p><b>왜 시드 API 가 아니라 기동 시 적재인가.</b> 카드는 사용자 데이터가 아니라 <b>앱의
 * 상수</b>다. 시드 API 에 두면 "시드를 돌렸는가"에 따라 화면이 달라지고, 실제로
 * {@code financial_product} 가 비어서 추천 화면이 빈 채로 있던 적이 있다. 리소스로 함께
 * 배포하고 기동할 때 넣으면 <b>어느 환경에서 켜도 같은 카드가 나온다</b> —
 * {@code CardRecommendProperties} 가 카드를 설정에 두었던 이유가 이것이었고, 그 성질을
 * 잃지 않은 채로 DB 로 옮기는 것이 이 클래스의 일이다.
 *
 * <p><b>매번 지우고 다시 넣는다.</b> 카탈로그가 원천이고 표는 파생이라, 부분 갱신을 상정하면
 * 원천에서 사라진 혜택이 표에 남는다. 지우면 {@code ON DELETE CASCADE} 로 딸린 것이 함께
 * 사라져 유령 행이 안 생긴다. 카드가 수백 장을 넘어가면 이 방식이 비싸지는데, 우리는
 * <b>카탈로그 규모로 경쟁하지 않으므로</b>(07 §4.4) 그 자리에 가지 않는다.
 *
 * <p><b>이 클래스는 판정을 하지 않는다.</b> 게이트 3(규칙 검산)은 빌드 스크립트
 * {@code scripts/collect-cards/build_catalog.py} 가 이미 돌렸고, 여기서는 그 결과
 * ({@code grade}·{@code countable})를 그대로 옮겨 적는다. 검산을 두 곳에 두면 갈라진다.
 */
@Component
public class CardCatalogLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(CardCatalogLoader.class);
    private static final String PATH = "card-catalog.json";

    private final CardProductRepository cards;
    private final ObjectMapper objectMapper;

    public CardCatalogLoader(CardProductRepository cards, ObjectMapper objectMapper) {
        this.cards = cards;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        List<Map<String, Object>> catalog = read();
        cards.deleteAllInBatch();
        int precise = 0;
        for (Map<String, Object> raw : catalog) {
            CardProduct card = toCard(raw);
            cards.save(card);
            if (card.isPrecise()) precise++;
        }
        log.info("카드 카탈로그 적재 — {}장 (정밀 {} · 참고 {})",
                catalog.size(), precise, catalog.size() - precise);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> read() {
        try (InputStream is = new ClassPathResource(PATH).getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(is, Map.class);
            List<Map<String, Object>> list = (List<Map<String, Object>>) root.get("cards");
            return list == null ? List.of() : list;
        } catch (IOException e) {
            throw new UncheckedIOException("카드 카탈로그를 읽지 못했다: " + PATH, e);
        }
    }

    @SuppressWarnings("unchecked")
    private CardProduct toCard(Map<String, Object> raw) {
        CardProduct card = new CardProduct(
                str(raw, "issuer"), str(raw, "name"), str(raw, "productId"),
                CardProduct.CardType.valueOf(str(raw, "cardType")),
                CardProduct.Status.valueOf(str(raw, "status")),
                CardProduct.BenefitStyle.valueOf(str(raw, "benefitStyle")));
        card.describe(bool(raw, "hasTransit"), Boolean.TRUE.equals(bool(raw, "policyCard")),
                date(raw, "asOf"), str(raw, "reviewNo"), date(raw, "postedAt"),
                str(raw, "sourceUrl"), str(raw, "annualFeeNote"), str(raw, "benefitNote"));
        card.grade(CardProduct.Grade.valueOf(str(raw, "grade")), str(raw, "gradeReason"));

        for (Map<String, Object> fee : list(raw, "annualFees")) {
            card.add(new CardAnnualFee(
                    CardAnnualFee.Scope.valueOf(str(fee, "scope")), str(fee, "brand"),
                    intOf(fee, "total"), integer(fee, "base"), integer(fee, "affiliate")));
        }

        Map<String, Object> perf = (Map<String, Object>) raw.get("performance");
        if (perf != null) {
            String exception = str(perf, "basisException");
            CardPerformanceRule rule = new CardPerformanceRule(
                    str(perf, "periodLabel"),
                    CardPerformanceRule.Basis.valueOf(str(perf, "basis")),
                    exception == null ? null : CardPerformanceRule.Basis.valueOf(exception),
                    str(perf, "basisExceptionTargets"), str(perf, "includes"),
                    bool(perf, "includesFamilyCard"));
            rule.graceForNewMember(str(perf, "newMemberGraceUntil"),
                    integer(perf, "newMemberAppliedTierKrw"), str(perf, "newMemberNote"));
            card.set(rule);
        }

        // 구간을 먼저 세운다 — 혜택의 requires_tier 와 한도가 이 행을 가리킨다.
        Map<Integer, CardPerformanceTier> byThreshold = new HashMap<>();
        for (Map<String, Object> tier : list(raw, "tiers")) {
            int threshold = intOf(tier, "thresholdKrw");
            CardPerformanceTier row = new CardPerformanceTier(intOf(tier, "tierNo"), threshold);
            card.add(row);
            byThreshold.put(threshold, row);
        }

        for (Map<String, Object> exclusion : list(raw, "exclusions")) {
            card.add(new CardExclusion(
                    CardExclusion.Axis.valueOf(str(exclusion, "axis")),
                    str(exclusion, "code"), str(exclusion, "label")));
        }

        for (Map<String, Object> raw2 : list(raw, "benefits")) {
            card.add(toBenefit(raw2, byThreshold));
        }

        for (Map<String, Object> combined : list(raw, "combinedCaps")) {
            String groupName = str(combined, "groupName");
            for (Map<String, Object> cap : list(combined, "caps")) {
                CardPerformanceTier tier = byThreshold.get(intOf(cap, "thresholdKrw"));
                if (tier == null) continue;   // 빌드가 이미 걸렀다. 여기서는 조용히 넘긴다.
                card.add(new CardCombinedCap(groupName, tier, intOf(cap, "capKrw")));
            }
        }
        return card;
    }

    private CardBenefit toBenefit(Map<String, Object> raw, Map<Integer, CardPerformanceTier> tiers) {
        CardBenefit benefit = new CardBenefit(
                str(raw, "groupName"), CardBenefit.Kind.valueOf(str(raw, "kind")),
                CardBenefit.Scope.valueOf(str(raw, "scope")), intOf(raw, "sortNo"));
        benefit.rate(decimal(raw, "ratePercent"), str(raw, "rateConditional"),
                integer(raw, "amountKrw"), integer(raw, "minAmountPerTxn"));

        Integer requires = integer(raw, "requiresTierKrw");
        benefit.conditions(requires == null ? null : tiers.get(requires),
                str(raw, "combinedCapGroup"), str(raw, "unit"), str(raw, "unitThirdParty"),
                !Boolean.FALSE.equals(bool(raw, "targetsComplete")), str(raw, "payChannel"),
                !Boolean.FALSE.equals(bool(raw, "countable")),
                Boolean.TRUE.equals(bool(raw, "isHeadline")),
                str(raw, "conditionsText"), str(raw, "exclusiveWith"), str(raw, "settle"));

        for (Map<String, Object> cap : list(raw, "caps")) {
            CardPerformanceTier tier = tiers.get(intOf(cap, "thresholdKrw"));
            if (tier == null) continue;
            benefit.add(new CardBenefitCap(tier, intOf(cap, "capKrw")));
        }
        for (Map<String, Object> target : list(raw, "targets")) {
            benefit.add(new CardBenefitTarget(
                    str(target, "targetGroup"),
                    CardBenefitTarget.Kind.valueOf(str(target, "kind")),
                    str(target, "value"), str(target, "channel"),
                    str(target, "excludePlace"), str(target, "note")));
        }
        return benefit;
    }

    // ── JSON 은 칸이 없으면 null 이다. '없음'과 '0'을 섞지 않으려고 전부 여기를 지난다.

    private static String str(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : value.toString();
    }

    private static Integer integer(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : ((Number) value).intValue();
    }

    private static int intOf(Map<String, Object> map, String key) {
        Integer value = integer(map, key);
        return value == null ? 0 : value;
    }

    private static Boolean bool(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : (Boolean) value;
    }

    private static BigDecimal decimal(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : new BigDecimal(value.toString());
    }

    private static LocalDate date(Map<String, Object> map, String key) {
        String value = str(map, key);
        return value == null || value.isBlank() ? null : LocalDate.parse(value);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Map<String, Object> map, String key) {
        List<Map<String, Object>> value = (List<Map<String, Object>>) map.get(key);
        return value == null ? List.of() : value;
    }
}
