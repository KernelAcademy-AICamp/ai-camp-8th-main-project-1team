package com.finntech.engine;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Map;

/**
 * 업종코드(KSIC 세분류) → 우리 소비 중분류. <b>결정론 1:1 표</b>다.
 *
 * <p><b>왜 여기가 경계인가.</b> 마이데이터 제공자가 아는 것은 "이 가맹점이 무슨 업종인가"까지고,
 * "이 소비가 사용자에게 무엇인가"는 앱이 정한다. 예전에는 제공자가 7대분류를 그대로 넘겼고
 * 그 값이 곧 소비 카테고리가 됐다 — 한 축이 업종과 소비종류를 겸하다 보니 교통이 '온라인'에
 * 들어가는 왜곡이 났고, 지킴이 챌린지에서 배달을 줄이려면 지하철 요금까지 한도에 잡혔다.
 *
 * <p><b>왜 ML이 아닌가.</b> 매핑의 정답을 우리가 만들어야 하므로, 학습을 시키면 우리 표를
 * 외울 뿐이다(순환). ML은 낭비/필수 판정에만 쓴다. 표 자체가 곧 "왜 이 소비가 이 카테고리인가"의
 * 설명이 되므로 설명가능성도 함께 얻는다.
 *
 * <p>원천은 {@code scripts/ksic/ksic-mapping.tsv} 하나이고 {@code build_resources.py}가
 * 이 JSON을 만든다. 마이데이터 서버도 같은 표를 읽는다 — 둘이 갈라지면 혜택 계산이 어긋난다.
 */
@Component
public class IndustryCategoryMapper {

    private static final String PATH = "ksic-mid.json";

    /** 업종코드를 모를 때. 알 수 없는 가맹점·비소비 업종·간편결제가 여기로 온다. */
    public static final String UNCLASSIFIED = "카테고리없음";

    private final Map<String, String> midByKsic;
    private final Map<String, Double> discretionaryByMid;

    @SuppressWarnings("unchecked")
    public IndustryCategoryMapper(ObjectMapper objectMapper) {
        try (InputStream is = new ClassPathResource(PATH).getInputStream()) {
            Map<String, Object> root = objectMapper.readValue(is, Map.class);
            this.midByKsic = (Map<String, String>) root.get("midByKsic");
            Map<String, Number> disc = (Map<String, Number>) root.get("discretionaryByMid");
            Map<String, Double> d = new java.util.LinkedHashMap<>();
            if (disc != null) disc.forEach((k, v) -> d.put(k, v.doubleValue()));
            this.discretionaryByMid = d;
        } catch (IOException e) {
            throw new UncheckedIOException("업종코드 대조표를 읽지 못했다: " + PATH, e);
        }
    }

    /**
     * 중분류의 <b>재량성</b>(0~1) — 낮을수록 생존필수, 높을수록 재량.
     *
     * <p>카탈로그의 {@code discretionaryBase}를 빈도가중 평균한 값이다. 절약 후보의 등급을
     * 여기서 유도하므로, 카테고리가 늘어도 목록을 고칠 일이 없다.
     * 모르는 중분류는 중간값(0.5) — 판단을 못 하겠으면 최적화가능으로 둔다.
     */
    public double discretionaryOf(String mid) {
        return discretionaryByMid.getOrDefault(mid, 0.5);
    }

    /**
     * 업종코드를 소비 중분류로 옮긴다. 모르는 코드는 {@link #UNCLASSIFIED}.
     *
     * <p>미분류를 null이나 예외로 두지 않는 이유: 분석 엔진이 카테고리 코드로 집계하는데
     * null이 섞이면 그 소비가 통째로 사라진다. 알 수 없다는 것도 하나의 분류다.
     */
    public String midOf(String ksicCode) {
        if (ksicCode == null || ksicCode.isBlank()) return UNCLASSIFIED;
        return midByKsic.getOrDefault(ksicCode, UNCLASSIFIED);
    }

    /** 표에 있는 코드 수 — 기동 로그·테스트용. */
    public int size() {
        return midByKsic.size();
    }

    /**
     * 이 체계가 내놓을 수 있는 소비 중분류 전부.
     *
     * <p>ML 모델이 <b>같은 체계로 학습됐는지</b> 대조할 때 쓴다. 체계를 바꾸고 재학습을 잊으면
     * 모델의 명목 특징이 통째로 죽는데, 크래시가 안 나서 알아채기 어렵다.
     */
    public java.util.Set<String> midCategories() {
        return new java.util.LinkedHashSet<>(midByKsic.values());
    }
}
