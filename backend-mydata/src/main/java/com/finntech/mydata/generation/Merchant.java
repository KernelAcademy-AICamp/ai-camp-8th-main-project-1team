package com.finntech.mydata.generation;

/**
 * 고정 가맹점 1건 — 신원(이름+동, 온라인은 이름)에서 결정론적으로 파생된 사업자등록번호·지번주소·좌표.
 * 같은 신원 → 항상 같은 값. registry/CSV·조회 엔드포인트의 단위.
 *
 * <p><b>번호·좌표가 없을 수 있다.</b> 해외 본사(스팀·아마존·아고다 등)는 국내 사업자등록번호도
 * 국내 좌표도 없다. 그래서 원시타입이 아니라 래퍼를 쓴다 — 예전에는 {@code double}이라
 * "좌표 없음"을 표현할 방법이 0.0(서아프리카 앞바다)뿐이었다.
 *
 * @param name           정규 표시명(브랜드는 "브랜드 동점", 독립상호는 상호, 온라인은 서비스명)
 * @param businessNumber 사업자등록번호 10자리(하이픈 없음). 해외 본사는 null
 * @param address        지번 주소("시도 시군구 동 본번[-부번]번지"), 본사·시설은 실주소
 * @param lat,lon        좌표. 해외 본사는 null
 * @param online         온라인(전국 본사 결제) 여부
 */
public record Merchant(String name, String businessNumber, String address,
                       Double lat, Double lon, boolean online) {
}
