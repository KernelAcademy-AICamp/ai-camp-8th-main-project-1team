package com.finntech.service;

import com.finntech.service.SavingsMatchInputs.EarlyTermination;
import com.finntech.util.FinancialCompanyNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 중도해지이율 공급원(M10) — 수집해 둔 스냅샷을 읽어 상품에 붙인다.
 * 정본은 `07_취향분석및추천_Agent_설계.md` §4.5 M10.
 *
 * <p><b>왜 파일인가.</b> 금감원은 이 값을 주지 않아 각 은행 자사 공시에서 따로 모아야 하는데
 * ({@code mtrt_int}는 *만기 후* 이자율이라 다른 값이다), 사용자 요청 안에서 은행 홈페이지를 긁을 수는
 * 없다. 그래서 <b>수집은 배치로 하고(`scripts/collect-savings/collect_early_termination.py`) 앱은
 * 그 결과만 읽는다.</b> 카드 공시를 수집 시점 스냅샷으로 고정한 것과 같은 방식이며, 같은 방어를 쓴다 —
 * {@code as_of}(수집 기준일)를 함께 실어 화면이 "이 시점 공시 기준"이라고 밝히게 한다.
 *
 * <p><b>못 구한 상품은 {@code null}이다.</b> 0%로 메우면 "깨도 손해 없다"는 거짓말이 되고, 평균으로
 * 메우면 있지도 않은 값이 순위를 흔든다. 화면은 그 자리를 비운다(§14 거울 원칙).
 *
 * <p><b>덮는 범위가 좁다는 것을 알고 쓴다.</b> 2026-08-11 현재 케이뱅크 5건뿐이다 —
 * 은행권 96건 중 나머지는 미수집이다. 그래도 규칙(M9 동점 처리)은 값이 있는 상품에서만 켜지고
 * 없는 상품을 밀어내지 않으므로, 부분 수집 상태에서도 결과가 왜곡되지 않는다.
 */
@Component
public class EarlyTerminationSource {

    private static final Logger log = LoggerFactory.getLogger(EarlyTerminationSource.class);

    private static final String PATH = "savings/early-termination.json";

    /** {@code 정규화한 금융사|정규화한 상품명} → 중도해지이율. */
    private final Map<String, EarlyTermination> byProduct;

    public EarlyTerminationSource(ObjectMapper objectMapper) {
        this.byProduct = load(objectMapper);
        log.info("중도해지이율 스냅샷 {}건 적재", byProduct.size());
    }

    /**
     * 이 상품의 중도해지이율. 없으면 {@code null}(미수집).
     *
     * <p>상품키가 아니라 <b>금융사 + 상품명</b>으로 잇는다. 은행 공시에는 금감원 상품코드가 없어
     * 공통 식별자가 이름뿐이기 때문이다 — <b>이름이 바뀌면 조용히 끊긴다</b>는 약점을 안고 쓴다.
     * 잘못 이어 붙는 것보다는 끊겨서 값이 비는 편이 안전하다(빈 값은 화면이 비우고 끝난다).
     */
    public EarlyTermination find(String company, String productName) {
        String key = key(company, productName);
        return key == null ? null : byProduct.get(key);
    }

    /** 적재된 상품 수 — 기동 로그·테스트용. */
    public int size() {
        return byProduct.size();
    }

    /**
     * 대조용 키. 금융사는 그룹명으로 줄이고(`주식회사 케이뱅크` → `케이`), 상품명은 공백을 지운다 —
     * 금감원 응답의 상품명에는 생 개행이 섞여 온다(`Sh해양플라스틱Zero!적금\n(자유적립식)`).
     */
    static String key(String company, String productName) {
        String bank = FinancialCompanyNames.normalize(company);
        if (bank.isEmpty() || productName == null || productName.isBlank()) return null;
        return bank + "|" + productName.replaceAll("\\s+", "");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, EarlyTermination> load(ObjectMapper objectMapper) {
        Map<String, EarlyTermination> out = new HashMap<>();
        try (InputStream is = new ClassPathResource(PATH).getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(is, Map.class);
            if (!(root.get("banks") instanceof List<?> banks)) return Map.of();
            for (Object bankObj : banks) {
                if (!(bankObj instanceof Map<?, ?> bank)) continue;
                String company = String.valueOf(bank.get("bank"));
                LocalDate asOf = parseDate(bank.get("as_of"));
                if (!(bank.get("products") instanceof List<?> products)) continue;
                for (Object productObj : products) {
                    if (!(productObj instanceof Map<?, ?> product)) continue;
                    String key = key(company, String.valueOf(product.get("name")));
                    List<EarlyTermination.Tier> tiers = tiers(product.get("tiers"));
                    if (key == null || tiers.isEmpty()) continue;
                    out.put(key, new EarlyTermination(tiers, asOf));
                }
            }
        } catch (Exception e) {                                 // noqa — 없으면 전부 미수집으로 간다
            // 스냅샷이 없거나 깨져도 추천은 살아야 한다. M10만 조용히 꺼지고 나머지 규칙은 그대로다.
            log.warn("중도해지이율 스냅샷을 읽지 못했다 — M10을 끄고 진행한다: {}", e.toString());
            return Map.of();
        }
        return Map.copyOf(out);
    }

    private static List<EarlyTermination.Tier> tiers(Object raw) {
        List<EarlyTermination.Tier> out = new ArrayList<>();
        if (!(raw instanceof List<?> rows)) return out;
        for (Object rowObj : rows) {
            if (!(rowObj instanceof Map<?, ?> row)) continue;
            Integer from = intOrNull(row.get("from_months"));
            if (from == null) continue;
            out.add(new EarlyTermination.Tier(from,
                    Boolean.TRUE.equals(row.get("from_exclusive")),
                    doubleOrNull(row.get("rate")),
                    doubleOrNull(row.get("multiplier")),
                    Boolean.TRUE.equals(row.get("prorated")),
                    doubleOrNull(row.get("floor_rate"))));
        }
        // 판정이 "하한이 가장 큰 구간"을 고르므로 순서가 결과를 바꾼다. 수집기도 정렬하지만 여기서 다시 굳힌다.
        out.sort((a, b) -> Integer.compare(a.fromMonths(), b.fromMonths()));
        return List.copyOf(out);
    }

    private static LocalDate parseDate(Object v) {
        try {
            return v == null ? null : LocalDate.parse(v.toString().trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static Integer intOrNull(Object v) {
        if (v instanceof Number n) return n.intValue();
        return null;
    }

    private static Double doubleOrNull(Object v) {
        if (v instanceof Number n) return n.doubleValue();
        return null;
    }
}
