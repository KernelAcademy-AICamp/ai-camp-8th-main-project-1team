package com.finntech.service;

import com.finntech.util.HttpClients;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 파킹통장(수시입출금) 목록 — 결산 화면의 「지킨 돈 굴리기」가 읽는다(`07_취향분석및추천_Agent_설계.md` §4.7).
 *
 * <p><b>왜 금감원이 아닌 다른 출처인가.</b> 금감원 금융상품통합비교공시는 <b>만기·금리가 정해진 상품</b>만
 * 모은다. 응답이 통째로 `예치기간 → 금리` 표라, <b>만기가 없는</b> 파킹통장은 그 틀에 들어갈 수 없다.
 * 실측(2026-08-04)으로도 적금 58건·정기예금 38건 중 파킹/수시입출금류는 0건이었다.
 *
 * <p><b>지킨 돈에 파킹이 맞는 이유.</b> 지킨 돈은 <b>매달 금액이 다르고 결산마다 덩어리로</b> 들어온다.
 * 정기예금은 만기까지 묶여 다음 달 지킨 돈을 못 얹고, 정액적금은 매달 같은 금액을 요구한다.
 * 파킹은 아무 때나 넣고 뺄 수 있어 이 흐름과 유일하게 맞는다(사용자 결정 2026-08-12).
 *
 * <p><b>비공식 API를 쓰는 근거 (2026-08-04 팀 확정).</b> 실서비스라면 상품 정보는 각 금융사에서 제휴로
 * 받아온다. 이 프로젝트는 <b>가상 환경의 학습용 서비스</b>이고, 여기서 실제 상품을 늘어놓는 목적은 판매가
 * 아니라 <b>지킨 돈을 어디에 둘 수 있는지 실제 분포로 보여 주기 위한 것</b>이다. 그래서 이 출처를
 * 쓰되, <b>그 이유를 알고 쓴다</b>. 뒤따르는 제약을 함께 적어 둔다.
 * <ul>
 *   <li>공식 API가 아니므로 <b>언제든 막히거나 스키마가 바뀔 수 있다</b> → 실패는 조용히 빈 목록이고,
 *       결산 화면의 파킹 블록만 사라지고 나머지는 그대로 산다.</li>
 *   <li>금감원 공시와 달리 <b>출처의 공신력에 기댈 수 없다</b> → 화면에서 파킹통장을 실 금리로 말할 때는
 *       출처를 밝혀야 한다. 정보성 비교 예외(마스터 §5-5)는 `무판매목적·무제휴·가입편의 없음`이 전제이며,
 *       그 전제는 여기서도 지킨다(가입 버튼·제휴 링크 없음).</li>
 *   <li>실서비스로 전환한다면 <b>이 클래스를 제휴 데이터 소스로 갈아끼운다.</b> 읽는 쪽은 손대지 않는다
 *       — 그러라고 {@link ParkingAccount} 라는 자체 레코드를 사이에 뒀다.</li>
 * </ul>
 *
 * <p><b>호출의 함정.</b> {@code depositPeriod}를 <b>보내면 안 된다</b>. 넣으면 0건, 빼면 93건이 온다
 * (2026-08-04 실측). 파킹통장은 만기가 없어 기간 조건과 맞물리지 않는데, 이 파라미터가 필수인 줄 알면
 * 목록이 비어도 원인을 못 찾는다. {@code productTypeCode}는 1001=파킹 · 1002=정기예금 · 1003=적금이고
 * 1004 이상은 400이다.
 */
@Service
public class ParkingAccountSource {

    private static final Logger log = LoggerFactory.getLogger(ParkingAccountSource.class);

    /** 파킹통장 상품군 코드. 1002=정기예금·1003=적금은 금감원(공식)에서 받으므로 여기서 쓰지 않는다. */
    static final String PRODUCT_TYPE_PARKING = "1001";

    private final boolean enabled;
    private final String path;
    private final String companyGroupCode;
    private final String sortType;
    private final String depositAmount;
    private final String userAgent;
    private final String referer;
    private final int maxPages;
    private final long cacheTtlMinutes;
    private final RestClient client;
    private final Clock clock;

    private List<ParkingAccount> cache = List.of();
    private Instant cachedAt;

    public ParkingAccountSource(
            @Value("${finntech.parking-accounts.enabled:true}") boolean enabled,
            @Value("${finntech.parking-accounts.base-url:https://new-m.pay.naver.com}") String baseUrl,
            @Value("${finntech.parking-accounts.path:/savings/api/v1/productList}") String path,
            @Value("${finntech.parking-accounts.company-group-code:BA}") String companyGroupCode,
            @Value("${finntech.parking-accounts.sort-type:INTEREST_RATE}") String sortType,
            @Value("${finntech.parking-accounts.deposit-amount:30000}") String depositAmount,
            @Value("${finntech.parking-accounts.user-agent:Mozilla/5.0}") String userAgent,
            @Value("${finntech.parking-accounts.referer:https://new-m.pay.naver.com/savings/list/parking}")
            String referer,
            @Value("${finntech.parking-accounts.max-pages:5}") int maxPages,
            @Value("${finntech.parking-accounts.cache-ttl-minutes:60}") long cacheTtlMinutes,
            Clock clock) {
        this.enabled = enabled;
        this.path = path;
        this.companyGroupCode = companyGroupCode;
        this.sortType = sortType;
        this.depositAmount = depositAmount;
        this.userAgent = userAgent;
        this.referer = referer;
        this.maxPages = maxPages;
        this.cacheTtlMinutes = cacheTtlMinutes;
        this.client = RestClient.builder().baseUrl(baseUrl)
                .requestFactory(HttpClients.factory(Duration.ofSeconds(3), Duration.ofSeconds(8)))
                .build();
        this.clock = clock;
    }

    /**
     * 파킹통장 한 줄 — 이 출처가 주는 것 전부다.
     *
     * <p><b>왜 자체 레코드인가</b>(2026-08-12). 예전에는 개인화 매칭 계약({@code SavingsMatchInputs})의
     * 타입을 그대로 냈는데, 예적금이 비교만 하기로 되면서 그 계약이 연결을 잃었다. 파킹 조회는 계속 쓰이므로
     * <b>죽은 계약에 매달아 두지 않는다</b> — 이 출처가 실제로 주는 넉 칸만 남긴다.
     *
     * @param productKey {@code 회사코드:상품코드}. 이름은 흔들려도 코드는 안 흔들린다.
     * @param primeRate  <b>조건부</b> 최고금리(예치금 구간·첫거래). 줄 세우기에 쓰지 않는다.
     */
    public record ParkingAccount(String productKey, String company, String name,
                                 double baseRate, double primeRate) {}

    /**
     * 파킹통장 목록. <b>실패하면 빈 목록</b>이다 — 비공식 출처가 막혔다고 화면이 죽지 않는다.
     * 파킹 블록만 사라지고, 화면은 그 사실을 그대로 말하면 된다(§14 거울 원칙).
     */
    public List<ParkingAccount> accounts() {
        if (!enabled) return List.of();
        Instant now = clock.instant();
        if (cachedAt != null && Duration.between(cachedAt, now).toMinutes() < cacheTtlMinutes) {
            return cache;
        }
        try {
            List<ParkingAccount> fetched = fetchAll();
            if (!fetched.isEmpty()) {
                cache = fetched;
                cachedAt = now;
            }
        } catch (RuntimeException e) {
            log.warn("파킹통장 조회 실패 — 파킹 그룹을 비운 채 진행한다: {}", e.toString());
        }
        return cache;
    }

    /** offset을 넘겨가며 전 페이지를 모은다. */
    @SuppressWarnings("unchecked")
    private List<ParkingAccount> fetchAll() {
        List<ParkingAccount> out = new ArrayList<>();
        int offset = 0;
        for (int page = 0; page < maxPages; page++) {
            final int off = offset;
            Map<String, Object> body = client.get()
                    // depositPeriod를 절대 붙이지 않는다 — 붙이면 0건이 온다(클래스 주석 참조).
                    .uri(b -> b.path(path)
                            .queryParam("productTypeCode", PRODUCT_TYPE_PARKING)
                            .queryParam("companyGroupCode", companyGroupCode)
                            .queryParam("sortType", sortType)
                            .queryParam("depositAmount", depositAmount)
                            .queryParam("offset", off)
                            .build())
                    .header("User-Agent", userAgent)
                    .header("Referer", referer)
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve().body(Map.class);

            if (body == null || !Boolean.TRUE.equals(body.get("isSuccess"))) break;
            if (!(body.get("result") instanceof Map)) break;
            Map<String, Object> result = (Map<String, Object>) body.get("result");
            if (!(result.get("products") instanceof List<?> products) || products.isEmpty()) break;

            for (Object po : products) {
                if (po instanceof Map) out.add(toAccount((Map<String, Object>) po));
            }
            int size = intOf(result.get("size"), products.size());
            offset += size <= 0 ? products.size() : size;
            int total = intOf(result.get("totalCount"), -1);
            if (total >= 0 && out.size() >= total) break;
        }
        return out;
    }

    /**
     * 응답 한 줄 → {@link ParkingAccount}. 상품키는 {@code companyCode:code}로 만든다 —
     * 이름은 흔들려도 코드는 안 흔들려서 정렬의 마지막 동점 처리를 이 키로 잠글 수 있다.
     *
     * <p><b>우대조건은 담지 않는다.</b> 이 출처는 그걸 구조로 주지 않는다({@code features}는 화면용
     * 문구다). 파킹통장의 우대는 보통 <b>예치금 구간·첫거래</b>라 지어내느니 안 담는다.
     */
    static ParkingAccount toAccount(Map<String, Object> p) {
        String key = str(p.get("companyCode")) + ":" + str(p.get("code"));
        return new ParkingAccount(
                key, str(p.get("companyName")), str(p.get("name")),
                parseRate(p.get("interestRate")), parseRate(p.get("primeInterestRate")));
    }

    static double parseRate(Object v) {
        if (v == null) return 0.0;
        try {
            return Double.parseDouble(v.toString().trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static String str(Object v) {
        return v == null ? "" : v.toString();
    }

    private static int intOf(Object v, int dflt) {
        if (v instanceof Number n) return n.intValue();
        try {
            return v == null ? dflt : Integer.parseInt(v.toString().trim());
        } catch (NumberFormatException e) {
            return dflt;
        }
    }
}
