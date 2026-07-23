package com.finntech.web;

import com.finntech.service.AuthService;
import com.finntech.service.AuthService.VerifyResult;
import com.finntech.service.MyDataLinkService;
import com.finntech.service.MyDataLinkService.LinkResult;
import com.finntech.service.MyDataLinkService.MyCardView;
import com.finntech.service.MyDataLinkService.PaymentHistoryRow;
import com.finntech.service.MyDataLinkService.PaymentRow;
import com.finntech.service.MyDataResponses.CompanyView;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 마이데이터 온보딩·연동·조회 API (§13). 본체(backend)측.
 * 본인인증(가상)→금융사 선택→연동(마이데이터에서 카드·결제 적재)→내 카드/상세 조회.
 */
@RestController
@RequestMapping("/api/mydata")
public class MyDataController {

    private final AuthService authService;
    private final MyDataLinkService linkService;

    public MyDataController(AuthService authService, MyDataLinkService linkService) {
        this.authService = authService;
        this.linkService = linkService;
    }

    /** 본인인증(가상) — 신원으로 가상 CI 계산·연결, 마이데이터 존재 확인. 실 SMS 없음(§13-2). */
    @PostMapping("/verify")
    public VerifyResult verify(@RequestBody VerifyRequest request) {
        return authService.verifyAssumed(request.userId(), request.name(),
                request.social7(), request.phone());
    }

    /** 카드사(연동 기관) 목록. */
    @GetMapping("/companies")
    public List<CompanyView> companies() {
        return linkService.companies();
    }

    /** 카드사 연결 → 마이데이터에서 카드·결제 적재 + Consumption(MYDATA) 투영. */
    @PostMapping("/link")
    public LinkResult link(@RequestBody LinkRequest request) {
        return linkService.linkCardCompanies(request.userId(), request.companyIds());
    }

    /** 내 카드 — 카드별 실적 진행률 + 이번달 받은 혜택. */
    @GetMapping("/cards")
    public List<MyCardView> cards(@RequestParam Long userId) {
        return linkService.myCards(userId);
    }

    /** 카드 상세 — 결제내역. */
    @GetMapping("/cards/{cardSerial}/payments")
    public List<PaymentRow> cardPayments(@PathVariable String cardSerial, @RequestParam Long userId) {
        return linkService.cardPayments(userId, cardSerial);
    }

    /** 결제내역 모아보기(§13-11) — 카드 구분 없이 최근 N개월(기본 6) 결제를 최신순으로, 실카드명 포함. */
    @GetMapping("/payments")
    public List<PaymentHistoryRow> payments(@RequestParam Long userId,
                                            @RequestParam(defaultValue = "6") int months) {
        return linkService.allPayments(userId, months);
    }

    /** 실시간 증분 동기화(§13-11, W2) — 마지막 동기화 이후 새 결제만 당겨온다(마이데이터 now 전진 시 미래 결제 등장). */
    @PostMapping("/sync")
    public MyDataLinkService.SyncResult sync(@RequestParam Long userId) {
        return linkService.renew(userId);
    }

    public record VerifyRequest(Long userId, String name, String social7, String phone) {}
    public record LinkRequest(Long userId, List<Long> companyIds) {}
}
