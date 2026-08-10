package com.finntech.mydata.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/** 마이데이터 결제내역 (mydata_payment). 카드 사용 1건 = 소비내역 1건. */
@Entity
@Table(name = "mydata_payment", indexes = {
        @Index(name = "idx_mydata_payment_card_date", columnList = "mydata_card_id, mydata_payment_date")
})
public class MyDataPayment {

    @Id
    @Column(name = "mydata_payment_id", length = 40)
    private String id; // uuid

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "mydata_card_id", nullable = false)
    private MyDataCard card;

    @Column(name = "mydata_payment_date", nullable = false)
    private LocalDateTime paymentDate;

    /** KSIC 세분류 4자리. 앱은 이 값으로 소비 카테고리를 붙인다(대조표 1:1). */
    @Column(name = "mydata_payment_ksic_code", nullable = false, length = 8)
    private String industryCode;

    @Column(name = "mydata_payment_category2", length = 30)
    private String category2;

    @Column(name = "mydata_payment_amount", nullable = false)
    private int amount;

    @Column(name = "mydata_payment_merchant_name", length = 60)
    private String merchantName;

    // 받은 혜택 금액은 두지 않는다 — 실 마이데이터에는 할인·적립액 필드가 없다
    // (카드-005 청구 추가정보에도, 카드-008 승인내역에도 없다). 여기서 값을 내주면
    // "이 결제로 얼마 아꼈다"를 실측처럼 그릴 수 있고, 실데이터로 넘어가는 날 그 화면이
    // 통째로 사라진다.

    // ── 대량 생성(W1)·낭비/필수 ML(W8)용 확장 컬럼 (전부 nullable·additive: 기존 12명 시드 비파괴) ──

    /** 결제 채널: ONLINE / OFFLINE (온라인 결제 반영, 개정4·W1-1a). */
    @Column(name = "mydata_payment_channel", length = 10)
    private String channel;

    /** 대표 품목명(상품 카탈로그, W1). */
    @Column(name = "mydata_payment_product_name", length = 60)
    private String productName;

    /** 대표 품목 단가(원). amount=총액≈단가×수량+노이즈. */
    @Column(name = "mydata_payment_product_price")
    private Integer productPrice;

    /** 수량. */
    @Column(name = "mydata_payment_quantity")
    private Integer quantity;

    /** 낭비/필수 정답 라벨(생성 ground truth): WASTE / ESSENTIAL. 인간 검수 후 학습(W8). */
    @Column(name = "mydata_payment_waste_label", length = 10)
    private String wasteLabel;

    /** 라벨러가 draw에 사용한 잠재 재량성 확률(0..1) — 디버깅·검증용, ML 특징 아님(누수 방지). */
    @Column(name = "mydata_payment_discretionary_score")
    private Double discretionaryScore;

    /** 오프라인 위치 — 주소(온라인은 null). 하루활동·동선 앵커(요구9). */
    @Column(name = "mydata_payment_location_address", length = 120)
    private String locationAddress;

    /** 위도/경도(WGS84, 오프라인만). 실사용자 위치 수집 시 개인정보 방침 갱신 트리거. */
    @Column(name = "mydata_payment_location_lat")
    private Double locationLat;

    @Column(name = "mydata_payment_location_lng")
    private Double locationLng;

    /** 가맹점 사업자등록번호 10자리(신원에서 결정론 파생, W?-가맹점정보). 사용자는 이 번호로 가맹점 주소를 조회한다. */
    @Column(name = "mydata_payment_business_number", length = 10)
    private String businessNumber;

    protected MyDataPayment() {}

    public MyDataPayment(String id, MyDataCard card, LocalDateTime paymentDate,
                         String industryCode, String category2, int amount,
                         String merchantName) {
        this.id = id;
        this.card = card;
        this.paymentDate = paymentDate;
        this.industryCode = industryCode;
        this.category2 = category2;
        this.amount = amount;
        this.merchantName = merchantName;
    }

    public String getId() { return id; }
    public MyDataCard getCard() { return card; }
    public LocalDateTime getPaymentDate() { return paymentDate; }
    public String getKsicCode() { return industryCode; }
    public String getCategory2() { return category2; }
    public int getAmount() { return amount; }
    public String getMerchantName() { return merchantName; }

    public String getChannel() { return channel; }
    public void setChannel(String channel) { this.channel = channel; }
    public String getProductName() { return productName; }
    public void setProductName(String productName) { this.productName = productName; }
    public Integer getProductPrice() { return productPrice; }
    public void setProductPrice(Integer productPrice) { this.productPrice = productPrice; }
    public Integer getQuantity() { return quantity; }
    public void setQuantity(Integer quantity) { this.quantity = quantity; }
    public String getWasteLabel() { return wasteLabel; }
    public void setWasteLabel(String wasteLabel) { this.wasteLabel = wasteLabel; }
    public Double getDiscretionaryScore() { return discretionaryScore; }
    public void setDiscretionaryScore(Double discretionaryScore) { this.discretionaryScore = discretionaryScore; }
    public String getLocationAddress() { return locationAddress; }
    public void setLocationAddress(String locationAddress) { this.locationAddress = locationAddress; }
    public Double getLocationLat() { return locationLat; }
    public void setLocationLat(Double locationLat) { this.locationLat = locationLat; }
    public Double getLocationLng() { return locationLng; }
    public void setLocationLng(Double locationLng) { this.locationLng = locationLng; }
    public String getBusinessNumber() { return businessNumber; }
    public void setBusinessNumber(String businessNumber) { this.businessNumber = businessNumber; }
}
