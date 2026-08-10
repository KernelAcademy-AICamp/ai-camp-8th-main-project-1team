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
                request.social7(), request.phone(), request.carrier());
    }

    /** 연동 가능 은행 목록 — 자산연결 화면이 카드사와 함께 보여준다. */
    @GetMapping("/banks")
    public List<com.finntech.service.MyDataResponses.BankView> banks() {
        return linkService.banks();
    }

    /** 내가 연동한 은행 — '연결 관리' 화면용. */
    @GetMapping("/my-banks")
    public List<com.finntech.domain.UserBank> myBanks(@RequestParam Long userId) {
        return linkService.linkedBanks(userId);
    }

    /** 카드사(연동 기관) 목록. */
    @GetMapping("/companies")
    public List<CompanyView> companies() {
        return linkService.companies();
    }

    /**
     * 본인인증을 마친 사람이 <b>실제로 가진</b> 카드사·은행을 찾는다 (프로토타입_0806).
     *
     * <p>연결하지는 않는다 — 화면이 "N곳을 찾았어요"로 보여 주고, 사용자가 뺄 것을 해제한 뒤
     * {@code POST /link} 를 부른다. 인증 전에 부르면 400 이다(CI 가 없으면 물어볼 신원이 없다).
     */
    @GetMapping("/discover")
    public com.finntech.service.MyDataLinkService.Discovered discover(@RequestParam Long userId) {
        return linkService.discover(userId);
    }

    /** 카드사 연결 → 마이데이터에서 카드·결제 적재 + Consumption(MYDATA) 투영. */
    @PostMapping("/link")
    public LinkResult link(@RequestBody LinkRequest request) {
        return linkService.linkCardCompanies(request.userId(), request.companyIds(),
                request.bankIds() == null ? java.util.List.of() : request.bankIds());
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

    /** 입출금 통장(§13-11) — 은행·계좌·월급·잔액 + 최근 입출금 내역. 통장 없으면 null. */
    @GetMapping("/account")
    public com.finntech.service.MyDataResponses.AccountView account(
            @RequestParam Long userId, @RequestParam(defaultValue = "1") int months) {
        return linkService.account(userId, months);
    }

    /** 가맹점 조회(번호→주소) — 결제에 실린 사업자번호로 가맹점명·지번주소를 조회. 없으면 null. */
    @GetMapping("/merchant/{businessNumber}")
    public com.finntech.service.MyDataResponses.MerchantView merchant(@PathVariable String businessNumber) {
        return linkService.merchant(businessNumber);
    }

    /** {@code carrier}는 온보딩에서 고른 통신사(`SKT`·`KT`·`LG U+`·`알뜰폰`). 없으면 통신사 대조를 건너뛴다. */
    public record VerifyRequest(Long userId, String name, String social7, String phone,
                                String carrier) {}
    public record LinkRequest(Long userId, List<Long> companyIds, List<Long> bankIds) {}
}
