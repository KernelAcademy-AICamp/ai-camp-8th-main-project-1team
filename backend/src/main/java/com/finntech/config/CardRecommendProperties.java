package com.finntech.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 카드 추천에 쓸 <b>더미 카드</b> 목록 (개편안 {@code s-compare}).
 *
 * <p><b>왜 DB가 아니라 설정인가.</b> 이 카드들은 사용자 데이터가 아니라 앱의 상수다. DB에 두면
 * "시드를 돌렸는가"에 화면이 달라지고, 실제로 {@code financial_product} 가 비어 있어 추천 화면이
 * 빈 채로 있었다. 설정에 두면 어느 환경에서 켜도 같은 카드가 나온다.
 *
 * <p><b>카테고리 이름이 여기 있어도 원칙 4를 어기지 않는다.</b> 원칙은 "카테고리 이름을
 * <i>코드에</i> 박지 않는다"이고, 임계치·이름은 설정에 두는 것이 그 원칙이 지시하는 자리다.
 * 코드는 이름을 모르고, 설정이 준 이름과 집계의 키를 맞춰볼 뿐이다.
 *
 * <p><b>전부 더미다</b>(마스터 §4 원칙 5 — 금소법). 실재하는 카드를 넣는 순간 이 화면은 추천이
 * 아니라 중개가 된다. 이름 앞에 {@code [더미]} 를 붙이는 것도 그래서다.
 */
@ConfigurationProperties(prefix = "finntech.card-recommend")
public class CardRecommendProperties {

    /** 소비 요약에 보일 카테고리 수 — 개편안은 3위까지 보여준다. */
    private int summaryTop = 3;

    /** 추천으로 보일 카드 수. */
    private int maxCards = 3;

    private List<Card> cards = new ArrayList<>();

    public int getSummaryTop() { return summaryTop; }
    public void setSummaryTop(int v) { this.summaryTop = v; }
    public int getMaxCards() { return maxCards; }
    public void setMaxCards(int v) { this.maxCards = v; }
    public List<Card> getCards() { return cards; }
    public void setCards(List<Card> v) { this.cards = v; }

    public static class Card {
        /** 화면에 그대로 나가는 이름. {@code [더미]} 로 시작하게 둔다. */
        private String name;
        /** 카드 성격 한 줄 — "배달과 카페에 강한 카드". */
        private String tagline;
        /** 카드 그림 색 갈래. 화면이 아는 값(blue/gold/navy)만 쓴다. */
        private String tint = "blue";
        /** 카드 그림에 크게 박히는 글자 한 자. */
        private String mark = "C";
        /** 카드 그림 아래에 작게 박히는 영문. */
        private String footer = "dummy card";
        /** 연회비(원). 0이면 '없음'으로 보인다. */
        private BigDecimal annualFee = BigDecimal.ZERO;
        /** 전 가맹점 기본 적립률(%). */
        private BigDecimal baseRate = BigDecimal.ZERO;
        /** 연간 혜택 한도(원). 0이면 한도 없음. */
        private BigDecimal yearlyCap = BigDecimal.ZERO;
        /** 전월 실적 조건(원). 0이면 '없음'. */
        private BigDecimal monthlyRequirement = BigDecimal.ZERO;
        private List<Benefit> benefits = new ArrayList<>();

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }
        public String getTagline() { return tagline; }
        public void setTagline(String v) { this.tagline = v; }
        public String getTint() { return tint; }
        public void setTint(String v) { this.tint = v; }
        public String getMark() { return mark; }
        public void setMark(String v) { this.mark = v; }
        public String getFooter() { return footer; }
        public void setFooter(String v) { this.footer = v; }
        public BigDecimal getAnnualFee() { return annualFee; }
        public void setAnnualFee(BigDecimal v) { this.annualFee = v; }
        public BigDecimal getBaseRate() { return baseRate; }
        public void setBaseRate(BigDecimal v) { this.baseRate = v; }
        public BigDecimal getYearlyCap() { return yearlyCap; }
        public void setYearlyCap(BigDecimal v) { this.yearlyCap = v; }
        public BigDecimal getMonthlyRequirement() { return monthlyRequirement; }
        public void setMonthlyRequirement(BigDecimal v) { this.monthlyRequirement = v; }
        public List<Benefit> getBenefits() { return benefits; }
        public void setBenefits(List<Benefit> v) { this.benefits = v; }
    }

    /** 카테고리 하나에 붙는 혜택 — "카페/간식 10% 캐시백". */
    public static class Benefit {
        /** 카테고리 코드. 집계의 키와 같은 문자열이어야 맞물린다. */
        private String category;
        /** 적립·할인율(%). */
        private BigDecimal rate = BigDecimal.ZERO;
        /** 혜택 방식 표시어 — 캐시백/적립/할인. */
        private String kind = "캐시백";

        public String getCategory() { return category; }
        public void setCategory(String v) { this.category = v; }
        public BigDecimal getRate() { return rate; }
        public void setRate(BigDecimal v) { this.rate = v; }
        public String getKind() { return kind; }
        public void setKind(String v) { this.kind = v; }
    }
}
