package com.finntech.service;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 취미 성향 매핑 — {@code 취미유형 → signatureKsic}(업종코드)를 <b>역방향</b>
 * (업종코드 → 취미유형)으로 뒤집어 소비내역에서 취향을 읽게 한다.
 *
 * <p><b>왜 업종코드인가.</b> 생성기는 취미를 소비맥락(일식·백화점·스트리밍…)으로 말하지만
 * 그 축은 제공자 DB에만 있다 — 앱이 받는 것은 업종코드까지다. 예전에는 생성기 파일을 그대로
 * 복사해 두어 맥락 이름 27개가 전부 조회 불가였고, 아래 폴백이 조용히 삼켜
 * <b>취향 분석이 언제나 빈 결과</b>였다.
 *
 * <p>데이터는 {@code resources/taste/hobbies.json}이며 {@code scripts/ksic/build_taste.py}가
 * 생성기 카탈로그에서 파생한다(taste/README.md). 카테고리를 코드에 박지 않는다는 설계원칙 4에
 * 따라 매핑은 리소스로 두고 여기서 로드만 한다.
 *
 * <p><b>가맹점명 세분(refineByMerchant).</b> 한 업종코드가 서로 다른 취향을 뭉뚱그릴 때가 있다 —
 * {@code 6031 스트리밍}은 음악(멜론)·영상(넷플릭스)·독서(밀리의서재)가 섞여 있어 그대로 매핑하면
 * 멜론이 영화·게임으로 샌다. 그래서 hobbies.json의 {@code refineByMerchant}에
 * {@code 업종코드 → (세분유형 → 가맹점명 키워드들)}를 두고, 분석 시 가맹점명 부분일치로 세분한 뒤
 * 세분유형(음악감상/영상시청/독서구독)을 다시 취미유형으로 역매핑한다. 이 값도 코드가 아닌 데이터다.
 *
 * <p>한 업종코드가 여러 취미의 signature일 수 있다. 성향 신호는 겹쳐도 유효하므로 역매핑은
 * <b>1:N</b>을 허용한다 — 그런 지출은 관련 취미 모두에 카운트된다.
 *
 * <p>로드 실패(리소스 누락·형식 오류)해도 서비스가 죽지 않게 빈 매핑으로 폴백한다. 그 경우 취향 분석은
 * 빈 결과를 주고, 판정이 필요한 다른 기능에는 영향을 주지 않는다.
 */
@Component
public class HobbyCatalog {

    private static final String PATH = "taste/hobbies.json";

    /** 업종코드 → 그 업종이 signature인 취미유형들. */
    private final Map<String, List<String>> hobbiesByKsic;

    /** 업종코드 → (세분유형 → 가맹점명 키워드들). 예: 6031 → (음악감상 → [멜론, 스포티파이…]). */
    private final Map<String, Map<String, List<String>>> refineByMerchant;

    @SuppressWarnings("unchecked")
    public HobbyCatalog(ObjectMapper objectMapper) {
        Map<String, List<String>> reverse = new LinkedHashMap<>();
        Map<String, Map<String, List<String>>> refine = new LinkedHashMap<>();
        try (InputStream is = new ClassPathResource(PATH).getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(is, Map.class);

            List<Map<String, Object>> hobbies = (List<Map<String, Object>>) root.get("hobbies");
            if (hobbies != null) {
                for (Map<String, Object> h : hobbies) {
                    String type = String.valueOf(h.get("type"));
                    Object sig = h.get("signatureKsic");
                    if (!(sig instanceof List<?> cats)) continue;
                    for (Object c : cats) {
                        reverse.computeIfAbsent(String.valueOf(c), k -> new ArrayList<>()).add(type);
                    }
                }
            }

            Object refObj = root.get("refineByMerchant");
            if (refObj instanceof Map<?, ?> refRoot) {
                for (Map.Entry<?, ?> e : refRoot.entrySet()) {
                    String ksic = String.valueOf(e.getKey());
                    // "_note" 같은 메타 키는 건너뛴다(값이 Map이 아니면 자연히 제외되지만 방어적으로 명시).
                    if (ksic.startsWith("_") || !(e.getValue() instanceof Map<?, ?> subMap)) continue;
                    Map<String, List<String>> subtypes = new LinkedHashMap<>();
                    for (Map.Entry<?, ?> se : subMap.entrySet()) {
                        if (!(se.getValue() instanceof List<?> kws)) continue;
                        List<String> keywords = new ArrayList<>();
                        for (Object kw : kws) keywords.add(String.valueOf(kw));
                        subtypes.put(String.valueOf(se.getKey()), keywords);
                    }
                    refine.put(ksic, subtypes);
                }
            }
        } catch (Exception e) {
            // 리소스 누락·형식 오류 → 빈 매핑. 취향 분석만 빈 결과가 되고 다른 기능엔 영향 없다.
            reverse = Map.of();
            refine = Map.of();
        }
        this.hobbiesByKsic = reverse;
        this.refineByMerchant = refine;
    }

    /** 이 업종코드가 신호하는 취미유형들(없으면 빈 리스트). 일상 지출(식당·편의점 등)은 매핑에 없어 빈 값. */
    public List<String> hobbiesFor(String ksicCode) {
        if (ksicCode == null) return List.of();
        return hobbiesByKsic.getOrDefault(ksicCode, List.of());
    }

    /** 로드된 업종코드 → 취미유형 역매핑 전체(읽기 전용). 순수 집계 함수에 넘겨 테스트에 쓴다. */
    public Map<String, List<String>> reverseMapping() {
        return hobbiesByKsic;
    }

    /** 로드된 업종코드 → (세분유형 → 가맹점명 키워드) 전체(읽기 전용). 순수 집계 함수에 넘긴다. */
    public Map<String, Map<String, List<String>>> refineByMerchant() {
        return refineByMerchant;
    }
}
