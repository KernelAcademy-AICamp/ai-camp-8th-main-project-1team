package com.finntech.mydata.generation;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * KSIC 세분류 → 우리 소비 중분류. <b>결정론 1:1 표</b>이며 ML이 관여하지 않는다.
 *
 * <p><b>왜 표인가.</b> 정답을 우리가 만들어야 하는 매핑이라, 학습을 시키면 우리 표를 외울
 * 뿐이다(순환). ML은 낭비/필수 판정에만 쓴다. 표 자체가 곧 "왜 이 소비가 이 카테고리인가"의
 * 설명이기도 하다.
 *
 * <p>원천은 {@code scripts/ksic/ksic-mapping.tsv} 하나이고, {@code build_resources.py}가
 * 이 JSON을 만든다. 손으로 고치면 다음 생성 때 덮인다.
 */
@Component
public class KsicCategoryMap {

    private static final String PATH = "generation/catalog/ksic-mid.json";

    private final Map<String, String> midByKsic;

    @SuppressWarnings("unchecked")
    public KsicCategoryMap(ObjectMapper objectMapper) {
        try (InputStream is = new ClassPathResource(PATH).getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(is, Map.class);
            this.midByKsic = (Map<String, String>) root.get("midByKsic");
        } catch (IOException e) {
            throw new UncheckedIOException("업종코드 대조표를 읽지 못했다: " + PATH, e);
        }
    }

    /** 모르는 코드면 null — 호출부가 '카테고리없음'으로 처리할지 정한다. */
    public String midOf(String ksicCode) {
        return ksicCode == null ? null : midByKsic.get(ksicCode);
    }

    public int size() {
        return midByKsic.size();
    }
}
