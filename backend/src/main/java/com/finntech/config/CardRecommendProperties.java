package com.finntech.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 카드 추천 화면의 <b>모양 설정</b> — 카드 자체는 여기 없다.
 *
 * <p><b>카드가 왜 빠졌나.</b> 예전에는 이 클래스가 {@code [더미]} 5장을 통째로 들고 있었고,
 * 그때의 논거는 "카드는 사용자 데이터가 아니라 앱의 상수라 DB 에 두면 시드를 돌렸는지에 화면이
 * 달라진다"였다. <b>그 논거는 지금도 옳다.</b> 다만 카드를 실제 상품으로 바꾸면서
 * (마스터 원칙 5 재개정 2026-08-10) 한 장이 {@code (요율, 카테고리)} 로 안 접히게 됐다 —
 * 실적 구간이 2~4단이고, 한도가 구간마다 다르고, 그 위에 통합한도가 또 있고, 실적 제외
 * 목록이 카드마다 다르다.
 *
 * <p>그래서 카드는 표 아홉({@code V34})으로 옮기되, <b>"어느 환경에서 켜도 같은 카드가
 * 나온다"는 성질은 잃지 않았다</b> — {@code card-catalog.json} 을 리소스로 함께 배포하고
 * 기동할 때 싣는다({@code CardCatalogLoader}). 시드 API 와 무관하다.
 *
 * <p>여기 남은 둘은 <b>화면이 몇 줄을 보여줄지</b>일 뿐이라 임계치의 자리(원칙 4)가 맞다.
 */
@ConfigurationProperties(prefix = "finntech.card-recommend")
public class CardRecommendProperties {

    /** 소비 요약에 보일 카테고리 수 — 개편안은 3위까지 보여준다. */
    private int summaryTop = 3;

    /** 추천으로 보일 카드 수. */
    private int maxCards = 3;

    public int getSummaryTop() { return summaryTop; }
    public void setSummaryTop(int v) { this.summaryTop = v; }
    public int getMaxCards() { return maxCards; }
    public void setMaxCards(int v) { this.maxCards = v; }
}
