package com.finntech.mydata.generation;

import com.finntech.mydata.generation.CatalogModels.BrandEntry;
import com.finntech.mydata.generation.CatalogModels.CatalogContext;
import com.finntech.mydata.generation.CatalogModels.ContextsFile;
import com.finntech.mydata.generation.CatalogModels.HobbiesFile;
import com.finntech.mydata.generation.CatalogModels.HobbyType;
import com.finntech.mydata.generation.CatalogModels.PersonaProfile;
import com.finntech.mydata.generation.CatalogModels.PersonasFile;
import com.finntech.mydata.generation.CatalogModels.ProductEntry;
import com.finntech.mydata.generation.CatalogModels.RegionEntry;
import com.finntech.mydata.generation.CatalogModels.RegionsFile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 카탈로그 리소스({@code generation/catalog/*.json}) 로더 — Stage A 산출물을 읽는다.
 *
 * <p>실 데이터 기반: 상호는 서울 일반음식점 인허가·국회 지출(KAPF) 실 가맹점명 + 실 브랜드,
 * 상품은 2026 국내 실 메뉴/가격. 지연 로드(캐시)이며 시작 시 자동 실행하지 않는다(@PostConstruct 없음).
 */
@Component
public class CatalogLoader {

    private static final String BASE = "generation/catalog/";

    private final ObjectMapper objectMapper;

    private List<CatalogContext> contexts;
    private Map<String, List<ProductEntry>> products;
    private Map<String, List<BrandEntry>> brands;
    private List<RegionEntry> regions;
    private List<HobbyType> hobbies;
    private List<PersonaProfile> personas;
    private Map<String, Object> independents;
    private Map<String, Object> midmap;
    private Map<String, Object> fares;

    public CatalogLoader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 소비맥락(category2 → 7대분류 매핑 + 빈도·재량성). */
    public List<CatalogContext> contexts() {
        if (contexts == null) {
            contexts = readValue("contexts.json", ContextsFile.class).contexts();
        }
        return contexts;
    }

    /** category2 → 실 품목·가격·재량성. */
    @SuppressWarnings("unchecked")
    public Map<String, List<ProductEntry>> products() {
        if (products == null) {
            Map<String, Object> root = readValue("products.json", Map.class);
            products = mapProducts((Map<String, Object>) root.get("productsByCategory2"));
        }
        return products;
    }

    /** category2 → 브랜드/플랫폼(branchable·channel·forms). */
    public Map<String, List<BrandEntry>> brands() {
        if (brands == null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> root = readValue("merchants_brand.json", Map.class);
            @SuppressWarnings("unchecked")
            Map<String, Object> byCat = (Map<String, Object>) root.get("byCategory2");
            Map<String, List<BrandEntry>> out = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : byCat.entrySet()) {
                List<BrandEntry> list = new ArrayList<>();
                for (Object o : (List<?>) e.getValue()) {
                    list.add(objectMapper.convertValue(o, BrandEntry.class));
                }
                out.put(e.getKey(), list);
            }
            brands = out;
        }
        return brands;
    }

    /** 전국 행정동(3,495) 실 중심좌표 + 사용자 분포 가중 — 프랜차이즈 {동}점 합성·동선 앵커. */
    public List<RegionEntry> regions() {
        if (regions == null) {
            regions = readValue("regions.json", RegionsFile.class).regions();
        }
        return regions;
    }

    /** 취미 성향(12종) → 명백히 드러나는 category2. 사용자별 취미로 가끔·명백한 지출 주입. */
    public List<HobbyType> hobbies() {
        if (hobbies == null) {
            hobbies = readValue("hobbies.json", HobbiesFile.class).hobbies();
        }
        return hobbies;
    }

    /** 기본 페르소나 5종(Stage B 확정) — 생성기가 변형 확장해 사용자 인스턴스화. */
    public List<PersonaProfile> personas() {
        if (personas == null) {
            personas = readValue("personas.json", PersonasFile.class).personas();
        }
        return personas;
    }

    /** 실 상호 풀(소상공인, category2 → 실명 리스트) + 동 가중치 + 좌표 bbox (원자료). */
    @SuppressWarnings("unchecked")
    public Map<String, Object> independents() {
        if (independents == null) {
            independents = readValue("merchants_independent.json", Map.class);
        }
        return independents;
    }

    /**
     * 우리 중분류 → 업종코드 목록. 페르소나가 말하는 지출비중(중분류 단위)을
     * 실제 거래가 일어나는 업종 단위로 푸는 데 쓴다.
     * scripts/ksic 가 대조표와 contexts에서 생성하므로 손으로 고치지 않는다.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> midmap() {
        if (midmap == null) {
            midmap = readValue("midmap.json", Map.class);
        }
        return midmap;
    }

    /** 시변 표준요금 앵커 + 명세서 표기 포맷 메모. */
    @SuppressWarnings("unchecked")
    public Map<String, Object> fares() {
        if (fares == null) {
            fares = readValue("fares.json", Map.class);
        }
        return fares;
    }

    private Map<String, List<ProductEntry>> mapProducts(Map<String, Object> byCat) {
        Map<String, List<ProductEntry>> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : byCat.entrySet()) {
            List<ProductEntry> list = new ArrayList<>();
            for (Object row : (List<?>) e.getValue()) {
                List<?> t = (List<?>) row;
                // 5번째 원소는 선택 — 추출 가중치다. 없으면 1.0(균등).
                // 지하철처럼 '기본요금이 대부분이고 추가요금은 가끔'인 요금 사다리를 표현한다.
                list.add(new ProductEntry(
                        (String) t.get(0),
                        ((Number) t.get(1)).intValue(),
                        ((Number) t.get(2)).intValue(),
                        ((Number) t.get(3)).doubleValue(),
                        t.size() > 4 ? ((Number) t.get(4)).doubleValue() : 1.0));
            }
            out.put(e.getKey(), list);
        }
        return out;
    }

    private <T> T readValue(String file, Class<T> type) {
        try (InputStream is = new ClassPathResource(BASE + file).getInputStream()) {
            return objectMapper.readValue(is, type);
        } catch (IOException e) {
            throw new IllegalStateException("카탈로그 로드 실패: " + file, e);
        }
    }
}
