package com.finntech.domain;

/** 도메인 열거형 모음. */
public final class Enums {
    private Enums() {}

    /** 소비 데이터 출처 (문서 §5-2). MYDATA = 마이데이터 서버에서 불러온 카드사용내역(§13). */
    public enum DataSource { DUMMY_SEED, USER_INPUT, CARD_UPLOAD, MYDATA }

    /** 분석 결과의 신뢰 수준 (문서 §5-2 ②) */
    public enum DataSourceMode { ESTIMATED, CONFIRMED }

    /** 상품 리스크 등급 — 순서가 인접도 계산에 쓰인다 */
    public enum RiskGrade { STABLE, NEUTRAL, AGGRESSIVE }

    public enum ProductType { DEPOSIT, SAVINGS, FUND, CASHBACK_CARD }

    /** FDS 룰 (문서 §5 ①) */
    public enum FdsRule { NIGHT_HIGH_AMOUNT, NEW_CATEGORY_SPIKE, FREQUENCY_DEVIATION }

    /**
     * 포인트 이벤트 (문서 §5-5 게임화 저축 루프).
     * DEPOSIT = "살 뻔했다"를 참은 즉시 랜덤 목표 버킷에 자동 입금(goalId) ·
     * WITHDRAWAL = 예산 초과 소비 시 목표 버킷에서 강제 차감(goalId).
     * 월급(GRANT)·실지출(SPEND)은 저장하지 않고 설정·소비 데이터에서 파생한다.
     */
    public enum PointEventType { DEPOSIT, WITHDRAWAL }

    /** 치팅데이 쿠폰 상태 (문서 §5-5 Phase 3). 제안 → 사용/거절. */
    public enum CouponStatus { OFFERED, USED, DECLINED }

    /**
     * 고민 목록(위시리스트) 상태 (문서 §5-5, 폴센트 응용).
     * CONSIDERING = 사고 싶어 담아둠 · NOT_BOUGHT = 안 샀음(아낀 돈으로 적립) · BOUGHT = 결국 샀음.
     */
    public enum WishlistStatus { CONSIDERING, NOT_BOUGHT, BOUGHT }

    /** 위시리스트 담기 경로. URL 파싱 · 스크린샷 AI · 수동 입력. */
    public enum WishlistSource { URL, IMAGE, MANUAL }
}
