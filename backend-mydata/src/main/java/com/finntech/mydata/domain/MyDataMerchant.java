package com.finntech.mydata.domain;

import jakarta.persistence.*;

/**
 * 고유 가맹점 1건 (mydata_merchant) — 사업자등록번호를 키로 가맹점명·지번주소·좌표를 보관한다.
 * 결제(mydata_payment)에서 business_number DISTINCT로 집계해 채운다(생성 후 1회). 사용자는 결제에 실린
 * 사업자번호로 이 테이블을 조회해 가맹점 주소를 알 수 있다(번호→주소 조회). 온라인은 본사 소재지.
 */
@Entity
@Table(name = "mydata_merchant")
public class MyDataMerchant {

    @Id
    @Column(name = "business_number", length = 10)
    private String businessNumber;

    @Column(name = "merchant_name", length = 80)
    private String merchantName;

    @Column(name = "address", length = 160)
    private String address;

    @Column(name = "lat")
    private Double lat;

    @Column(name = "lng")
    private Double lng;

    @Column(name = "online", nullable = false)
    private boolean online;

    protected MyDataMerchant() {}

    public MyDataMerchant(String businessNumber, String merchantName, String address,
                          Double lat, Double lng, boolean online) {
        this.businessNumber = businessNumber;
        this.merchantName = merchantName;
        this.address = address;
        this.lat = lat;
        this.lng = lng;
        this.online = online;
    }

    public String getBusinessNumber() { return businessNumber; }
    public String getMerchantName() { return merchantName; }
    public String getAddress() { return address; }
    public Double getLat() { return lat; }
    public Double getLng() { return lng; }
    public boolean isOnline() { return online; }
}
