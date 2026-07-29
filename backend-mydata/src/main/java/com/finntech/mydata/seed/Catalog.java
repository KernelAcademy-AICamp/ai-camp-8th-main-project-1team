package com.finntech.mydata.seed;

import java.util.List;
import java.util.Map;

/**
 * 마이데이터 더미의 참조 데이터 — 카테고리 트리·가맹점·카드 카탈로그(코드 고정).
 * 7대분류×N중분류 택소노미와 실적구간 혜택 모델을 축소 반영한다.
 * 판단 로직이 아니라 '데이터'다(설계원칙 4: 판단 코드엔 카테고리를 박지 않는다).
 */
public final class Catalog {
    private Catalog() {}

    /**
     * 업종코드(KSIC 세분류) → 소비맥락 목록. 순서 고정(재현성).
     *
     * <p>예전에는 7대분류를 키로 썼는데, 그 축이 소비 카테고리를 겸하고 있어 없앴다.
     * 이 시드는 소규모 데모용이라 대량 생성기({@code generation/})의 52맥락 전체를 담지 않고
     * 대표 맥락만 둔다 — 코드는 같은 체계를 쓴다(scripts/ksic/ksic-mapping.tsv).
     */
    public static final Map<String, List<String>> CONTEXTS_BY_KSIC = Map.ofEntries(
            Map.entry("5611", List.of("한식")),
            Map.entry("5612", List.of("양식")),
            Map.entry("5619", List.of("배달", "분식")),
            Map.entry("5622", List.of("카페")),
            Map.entry("4712", List.of("편의점", "화장품")),
            Map.entry("4711", List.of("대형마트", "백화점")),
            Map.entry("4781", List.of("약국")),
            Map.entry("4771", List.of("주유소")),
            Map.entry("4921", List.of("대중교통")),
            Map.entry("6122", List.of("통신비")),
            Map.entry("6031", List.of("스트리밍")),
            Map.entry("6312", List.of("간편결제")),
            Map.entry("5914", List.of("영화")),
            Map.entry("9113", List.of("헬스장")),
            Map.entry("8620", List.of("의원"))
    );

    /** 순서 고정용 업종코드 배열. */
    public static final List<String> KSIC_CODES = List.of(
            "5611", "5612", "5619", "5622", "4712", "4711", "4781", "4771",
            "4921", "6122", "6031", "6312", "5914", "9113", "8620");

    /** 중분류 → 가맹점 후보. */
    public static final Map<String, List<String>> MERCHANTS = Map.ofEntries(
            Map.entry("간편결제", List.of("카카오페이", "네이버페이", "삼성페이")),
            Map.entry("배달", List.of("배달의민족", "요기요", "쿠팡이츠")),
            Map.entry("스트리밍", List.of("넷플릭스", "유튜브프리미엄", "멜론")),
            Map.entry("통신", List.of("SKT", "KT", "LGU+")),
            Map.entry("교통", List.of("지하철", "버스", "택시")),
            Map.entry("대형마트", List.of("이마트", "홈플러스", "롯데마트")),
            Map.entry("백화점", List.of("롯데백화점", "현대백화점", "신세계백화점")),
            Map.entry("뷰티", List.of("올리브영", "롭스")),
            Map.entry("아울렛", List.of("롯데아울렛", "현대아울렛")),
            Map.entry("병원", List.of("튼튼병원", "연세병원")),
            Map.entry("약국", List.of("온누리약국", "메디팜")),
            Map.entry("주유소", List.of("SK주유소", "GS칼텍스", "S-OIL")),
            Map.entry("일반음식점", List.of("김밥천국", "한솥도시락", "본죽")),
            Map.entry("휴게음식점", List.of("파리바게뜨", "뚜레쥬르")),
            Map.entry("영화", List.of("CGV", "롯데시네마", "메가박스")),
            Map.entry("헬스장", List.of("애니타임피트니스", "짐박스")),
            Map.entry("카페", List.of("스타벅스", "이디야", "투썸플레이스", "메가커피")),
            Map.entry("편의점", List.of("CU", "GS25", "세븐일레븐"))
    );

    /** 카드사 목록(이름). */
    public static final List<String> COMPANIES = List.of(
            "신한카드", "삼성카드", "현대카드", "KB국민카드", "롯데카드");

    /** 카드 혜택 정의: 대분류·할인율(%)·실적구간[start,end]·월한도. */
    /** @param midCategory 혜택 대상 소비 중분류. 실제 카드도 소비자가 아는 묶음 단위로 준다. */
    public record BenefitDef(String midCategory, int percent, int perfStart, int perfEnd, int monthlyLimit) {}

    /** 카드 상품 정의: 이름·색·카드사·혜택 목록. */
    public record CardDef(String name, String color, String company, List<BenefitDef> benefits) {}

    /**
     * 카드 카탈로그(코드 고정). 각 카드는 전월실적 30~40만 구간에서 중분류별 혜택을 준다.
     *
     * <p>혜택 기준이 7대분류에서 <b>우리 중분류</b>로 바뀌었다. 옛 '온라인 5%'처럼 교통까지
     * 뭉뚱그리던 것이 사라지고, 카드마다 성격이 갈린다 — 교통 특화·의료 특화 같은 것이
     * 실제 카드 시장의 모습이기도 하다. 15개 중분류를 고루 덮어야 어느 카드를 골라도
     * 혜택이 붙는 소비가 생긴다.
     */
    public static final List<CardDef> CARD_DEFS = List.of(
            new CardDef("신한카드 Deep Dream", "#1a2b6b", "신한카드", List.of(
                    new BenefitDef("카페/간식", 10, 300000, 0, 10000),
                    new BenefitDef("쇼핑", 5, 300000, 0, 10000))),
            new CardDef("삼성카드 taptap O", "#1428a0", "삼성카드", List.of(
                    new BenefitDef("편의점/잡화", 10, 400000, 0, 5000),
                    new BenefitDef("교통/자동차", 5, 400000, 0, 10000))),
            new CardDef("현대카드 ZERO", "#111111", "현대카드", List.of(
                    new BenefitDef("생활", 3, 0, 0, 20000),
                    new BenefitDef("식비", 3, 0, 0, 20000),
                    new BenefitDef("쇼핑", 3, 0, 0, 20000))),
            new CardDef("KB국민 굿데이", "#6b4e9e", "KB국민카드", List.of(
                    new BenefitDef("대형마트", 7, 300000, 0, 15000),
                    new BenefitDef("의료", 5, 300000, 0, 10000))),
            new CardDef("롯데카드 LOCA", "#d0021b", "롯데카드", List.of(
                    new BenefitDef("쇼핑", 5, 300000, 0, 12000),
                    new BenefitDef("취미/여가", 8, 300000, 0, 8000))),
            new CardDef("신한카드 카카오페이", "#ffcd00", "신한카드", List.of(
                    new BenefitDef("교통/자동차", 3, 300000, 0, 12000),
                    new BenefitDef("카페/간식", 5, 300000, 0, 6000))),
            new CardDef("삼성카드 iD", "#0a0a0a", "삼성카드", List.of(
                    new BenefitDef("식비", 5, 400000, 0, 12000),
                    new BenefitDef("술/유흥", 5, 400000, 0, 6000))),
            new CardDef("현대카드 M", "#2e2e2e", "현대카드", List.of(
                    new BenefitDef("건강/피트니스", 5, 300000, 0, 8000),
                    new BenefitDef("미용", 5, 300000, 0, 8000))),
            new CardDef("하나카드 트래블로그", "#00857e", "하나카드", List.of(
                    new BenefitDef("여행/숙박", 8, 300000, 0, 15000),
                    new BenefitDef("교통/자동차", 3, 300000, 0, 8000))),
            new CardDef("우리카드 카드의정석", "#0067ac", "우리카드", List.of(
                    new BenefitDef("주거/통신", 5, 300000, 0, 12000),
                    new BenefitDef("편의점/잡화", 3, 300000, 0, 5000)))
    );
}
