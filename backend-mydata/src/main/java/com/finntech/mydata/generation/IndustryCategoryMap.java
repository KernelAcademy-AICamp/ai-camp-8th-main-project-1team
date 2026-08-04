package com.finntech.mydata.generation;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * <b>국세청 업종코드(6자리)</b> → 우리 소비 중분류. 결정론 1:1 표이며 ML이 관여하지 않는다.
 *
 * <p><b>왜 표인가.</b> 정답을 우리가 만들어야 하는 매핑이라, 학습을 시키면 우리 표를 외울
 * 뿐이다(순환). ML은 낭비/필수 판정에만 쓴다. 표 자체가 곧 "왜 이 소비가 이 카테고리인가"의
 * 설명이기도 하다.
 *
 * <p>원천은 {@code scripts/industry/nts-mid.tsv} 하나이고, {@code build_industry.py}가
 * 이 JSON을 만든다. 손으로 고치면 다음 빌드에 덮인다.
 */
@Component
public class IndustryCategoryMap {

    private static final String PATH = "generation/catalog/industry-mid.json";

    private final Map<String, String> midByIndustry;
    private final List<Map.Entry<String, String>> paymentAgencies;

    @SuppressWarnings("unchecked")
    public IndustryCategoryMap(ObjectMapper objectMapper) {
        try (InputStream is = new ClassPathResource(PATH).getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(is, Map.class);
            this.midByIndustry = (Map<String, String>) root.get("midByIndustry");
            Map<String, String> pg = (Map<String, String>) root.get("pgBusinessNumbers");
            this.paymentAgencies = pg == null ? List.of() : List.copyOf(pg.entrySet());
        } catch (IOException e) {
            throw new UncheckedIOException("업종코드 대조표를 읽지 못했다: " + PATH, e);
        }
    }

    /** 모르는 코드면 null — 호출부가 '카테고리없음'으로 처리할지 정한다. */
    public String midOf(String industryCode) {
        return industryCode == null ? null : midByIndustry.get(industryCode);
    }

    /**
     * PG·간편결제 사업자 (사업자번호 → 상호). 생성기가 '알 수 없는 결제'를 만들 때 쓴다.
     *
     * <p>목록을 여기서 꺼내는 이유: 상호를 코드에 또 적어 두면 원천이 둘이 되고, 실제 번호가
     * 없으면 본체의 PG 차단이 <b>더미에서 한 번도 안 돌아</b> 실데이터에서 처음 실행된다.
     */
    public List<Map.Entry<String, String>> paymentAgencies() {
        return paymentAgencies;
    }

    public int size() {
        return midByIndustry.size();
    }
}
