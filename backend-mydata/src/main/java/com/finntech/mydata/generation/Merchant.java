package com.finntech.mydata.generation;

/**
 * 고정 가맹점 1건 — 신원(이름+동, 온라인은 이름)에서 결정론적으로 파생된 사업자등록번호·지번주소·좌표.
 * 같은 신원 → 항상 같은 값. registry/CSV·조회 엔드포인트의 단위.
 *
 * @param name           정규 표시명(브랜드는 "브랜드 동점", 독립상호는 상호, 온라인은 서비스명)
 * @param businessNumber 사업자등록번호 10자리(하이픈 없음)
 * @param address        지번 주소("시도 시군구 동 본번[-부번]번지"), 온라인은 본사 소재지
 * @param lat,lon        좌표(온라인은 본사 좌표)
 * @param online         온라인(전국 본사 결제) 여부
 */
public record Merchant(String name, String businessNumber, String address,
                       double lat, double lon, boolean online) {
}
