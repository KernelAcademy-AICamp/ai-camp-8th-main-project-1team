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

    /** 카드 한 장 — 카드사·상품·표시명과 그 카드의 명세서(5칸 CSV). */
    public record SelfCardBody(Long cardCode, String displayName, String csv) {}

    private record SelfImportBody(String name, String social7, String phone,
                                  List<SelfCardBody> cards) {}

    /**
     * <b>승인된 실사용자 신청을 제공자에 적재한다</b> (설계서 Phase 3).
     *
     * <p>이 클래스의 다른 14개 메서드는 전부 {@code client.get()} — <b>읽기</b>다.
     * 여기부터 쓰기가 셋 생긴다(적재·파기·카탈로그 조회는 읽기). <b>더 늘리지 않는다</b> —
     * 늘어나는 순간 "본체는 제공자에서 읽기만 한다"는 성질이 사라지고, 제공자를 격리해 둔
     * 이유가 흐려진다.
     *
     * <p>{@code /self/**} 는 제공자의 공유 시크릿 필터가 검사한다. 토큰은
     * {@code MyDataClientConfig} 가 모든 요청에 기본 헤더로 붙인다.
     */
    public java.util.Map<String, Object> selfImport(String name, String social7, String phone,
                                                    List<SelfCardBody> cards) {
        return client.post()
                .uri("/self/import")
                .body(new SelfImportBody(name, social7, phone, cards))
                .retrieve()
                .body(new ParameterizedTypeReference<java.util.Map<String, Object>>() {});
    }

    /** 카드 상품 카탈로그 — 신청 화면이 카드사·카드를 고르게 하려면 목록이 필요하다. */
    public List<java.util.Map<String, Object>> selfCardCatalog() {
        List<java.util.Map<String, Object>> catalog = client.get()
                .uri("/self/card-catalog")
                .retrieve()
                .body(new ParameterizedTypeReference<List<java.util.Map<String, Object>>>() {});
        return catalog == null ? List.of() : catalog;
    }

    /** CI 존재 확인 — 본인인증 후 "마이데이터에 있는 회원인가". */
    public boolean checkCi(String ci) {
        Envelope<Boolean> response = client.get()
                .uri("/bank/mydata/ci/{ci}", ci)
                .retrieve()
                .body(new ParameterizedTypeReference<Envelope<Boolean>>() {});
        return response != null && Boolean.TRUE.equals(response.data());
    }

    /**
     * 신원 대조 — 어느 항목이 틀렸는지 가려내려고 제공자에 조회 사실만 묻는다.
     * CI는 해시라 안 맞는다는 것까지만 알 수 있어, 항목별 판정에는 이 조회가 필요하다.
     */
    public IdentityMatch matchIdentity(String name, String social7, String phone) {
        Envelope<IdentityMatch> response = client.get()
                .uri(b -> b.path("/bank/mydata/identity-match")
                        .queryParam("name", name)
                        .queryParam("social7", social7)
                        .queryParam("phone", phone).build())
                .retrieve()
                .body(new ParameterizedTypeReference<Envelope<IdentityMatch>>() {});
        return response == null ? null : response.data();
    }

    /** 제공자의 조회 결과. 판정은 {@code AuthService}가 한다(마스터 §4 원칙 1). */
    public record IdentityMatch(boolean exists, boolean phoneTaken,
                                boolean phoneNameOk, boolean phoneSocialOk,
                                boolean personFound) {}

    /** 카드사(연동 기관) 목록. */
    public List<CompanyView> findCompanies() {
        Envelope<List<CompanyView>> response = client.get()
                .uri("/bank/mydata/card-company")
                .retrieve()
                .body(new ParameterizedTypeReference<Envelope<List<CompanyView>>>() {});
        return response == null ? List.of() : response.data();
    }

    /** 입출금 통장 조회(§13-11) — 은행·계좌·월급·잔액 + 입출금 내역. 계좌 없으면 null. */
    public AccountView findAccount(String ci) {
        return findAccount(ci, 1);
    }

    /** @param months 최근 N개월(당월 포함). 통장 화면의 '이전 6개월 보기'가 7을 보낸다. */
    public AccountView findAccount(String ci, int months) {
        Envelope<AccountView> response = client.get()
                .uri(builder -> builder.path("/bank/mydata/account")
                        .queryParam("userId", ci).queryParam("months", months).build())
                .retrieve()
                .body(new ParameterizedTypeReference<Envelope<AccountView>>() {});
        return response == null ? null : response.data();
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

    /** 가맹점 조회(번호→주소) — 사용자가 결제의 사업자번호로 가맹점명·지번주소를 조회. 없으면 null. */
    public MerchantView findMerchant(String businessNumber) {
        Envelope<MerchantView> response = client.get()
                .uri("/bank/mydata/merchant/{businessNumber}", businessNumber)
                .retrieve()
                .body(new ParameterizedTypeReference<Envelope<MerchantView>>() {});
        return response == null ? null : response.data();
    }

    /** 증분 조회 — 마지막 동기화 이후 결제만. */
    /** 연동 가능 은행 목록. */
    public List<BankView> findBanks() {
        Envelope<List<BankView>> response = client.get()
                .uri(builder -> builder.path("/bank/mydata/banks").build())
                .retrieve()
                .body(new ParameterizedTypeReference<Envelope<List<BankView>>>() {});
        return response == null || response.data() == null ? List.of() : response.data();
    }

    /** 고른 은행들에 있는 계좌(0~1건). 계좌가 없는 은행을 골랐으면 빈 목록이다. */
    public List<AccountView> findAccountsByBanks(String ci, List<Long> bankIds) {
        Envelope<List<AccountView>> response = client.get()
                .uri(builder -> builder.path("/bank/mydata/accounts")
                        .queryParam("userId", ci)
                        .queryParam("bankIds", bankIds).build())
                .retrieve()
                .body(new ParameterizedTypeReference<Envelope<List<AccountView>>>() {});
        return response == null || response.data() == null ? List.of() : response.data();
    }

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
