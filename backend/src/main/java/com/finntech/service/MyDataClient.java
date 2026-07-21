package com.finntech.service;

import com.finntech.service.MyDataResponses.*;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 마이데이터 서버(backend-mydata, 8082) 호출 클라이언트 (§13-3). 동기 RestClient로 호출한다(리액티브 스택 불필요).
 * 실패 시 예외를 던져 상위에서 처리한다(내부 서버-투-서버 호출).
 */
@Component
public class MyDataClient {

    private final RestClient client;

    public MyDataClient(RestClient myDataRestClient) {
        this.client = myDataRestClient;
    }

    /** CI 존재 확인 — 본인인증 후 "마이데이터에 있는 회원인가". */
    public boolean checkCi(String ci) {
        Envelope<Boolean> response = client.get()
                .uri("/bank/mydata/ci/{ci}", ci)
                .retrieve()
                .body(new ParameterizedTypeReference<Envelope<Boolean>>() {});
        return response != null && Boolean.TRUE.equals(response.data());
    }

    /** 카드사(연동 기관) 목록. */
    public List<CompanyView> findCompanies() {
        Envelope<List<CompanyView>> response = client.get()
                .uri("/bank/mydata/card-company")
                .retrieve()
                .body(new ParameterizedTypeReference<Envelope<List<CompanyView>>>() {});
        return response == null ? List.of() : response.data();
    }

    /** 전체 조회 — 사용자(CI)의 카드사 카드 + 결제내역 전부. */
    public List<CardView> findCards(Long companyId, String ci) {
        Envelope<List<CardView>> response = client.get()
                .uri(builder -> builder.path("/bank/mydata")
                        .queryParam("cardCompanyId", companyId)
                        .queryParam("userId", ci).build())
                .retrieve()
                .body(new ParameterizedTypeReference<Envelope<List<CardView>>>() {});
        return response == null ? List.of() : response.data();
    }

    /** 증분 조회 — 마지막 동기화 이후 결제만. */
    public List<CardView> findCardsSince(Long companyId, String ci, LocalDateTime lastRenewalTime) {
        Envelope<List<CardView>> response = client.get()
                .uri(builder -> builder.path("/bank/mydata/renewal")
                        .queryParam("cardCompanyId", companyId)
                        .queryParam("userId", ci)
                        .queryParam("lastRenewalTime", lastRenewalTime).build())
                .retrieve()
                .body(new ParameterizedTypeReference<Envelope<List<CardView>>>() {});
        return response == null ? List.of() : response.data();
    }
}
