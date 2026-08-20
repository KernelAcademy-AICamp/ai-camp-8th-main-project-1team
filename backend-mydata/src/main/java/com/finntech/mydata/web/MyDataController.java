package com.finntech.mydata.web;

import com.finntech.mydata.dto.ApiResponse;
import com.finntech.mydata.dto.MyDataDtos.AccountView;
import com.finntech.mydata.dto.MyDataDtos.IdentityMatchView;
import com.finntech.mydata.dto.MyDataDtos.BankView;
import com.finntech.mydata.dto.MyDataDtos.CardView;
import com.finntech.mydata.dto.MyDataDtos.MerchantView;
import com.finntech.mydata.service.MyDataService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 마이데이터 제공 API ({@code /bank/mydata/**}).
 * 본체(backend, 8080)가 RestClient로 호출한다. 인증 없음 — 내부 서버-투-서버 신뢰.
 */
@RestController
@RequestMapping("/bank/mydata")
public class MyDataController {

    private final MyDataService myDataService;

    public MyDataController(MyDataService myDataService) {
        this.myDataService = myDataService;
    }

    /** 전체 조회 — 사용자의 카드사 카드 + 결제내역 전부. */
    @GetMapping
    public ApiResponse<List<CardView>> getCards(@RequestParam Long cardCompanyId,
                                                @RequestParam String userId) {
        return ApiResponse.ok(myDataService.findCards(cardCompanyId, userId));
    }

    /** 증분 조회 — 마지막 동기화 이후 결제만. */
    @GetMapping("/renewal")
    public ApiResponse<List<CardView>> getCardsSince(
            @RequestParam Long cardCompanyId,
            @RequestParam String userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime lastRenewalTime) {
        return ApiResponse.ok(myDataService.findCardsSince(cardCompanyId, userId, lastRenewalTime));
    }

    /** CI 존재 확인 — 본인인증 후 "마이데이터에 있는 회원인가". */
    @GetMapping("/ci/{userCi}")
    public ApiResponse<Boolean> checkUser(@PathVariable String userCi) {
        return ApiResponse.ok(myDataService.userExists(userCi));
    }

    /**
     * 신원 대조 — 본인인증이 어느 항목이 틀렸는지 가려내도록 <b>조회 사실만</b> 돌려준다.
     * 이름·주민번호는 일치 여부(불리언)로만 나가므로 남의 실명이 넘어가지 않는다.
     */
    @GetMapping("/identity-match")
    public ApiResponse<IdentityMatchView> matchIdentity(
            @RequestParam String name, @RequestParam String social7, @RequestParam String phone) {
        return ApiResponse.ok(myDataService.matchIdentity(name, social7, phone));
    }

    /** 입출금 통장 조회(§13-11) — 은행·계좌·월급·잔액 + 최근 입출금 내역. 계좌 없으면 data=null. */
    @GetMapping("/account")
    public ApiResponse<AccountView> getAccount(@RequestParam String userId,
                                               @RequestParam(defaultValue = "1") int months) {
        return ApiResponse.ok(myDataService.findAccount(userId, months).orElse(null));
    }

    /** 연동 가능 은행 목록 — 자산연결 화면이 고를 수 있는 은행. */
    @GetMapping("/banks")
    public ApiResponse<List<BankView>> getBanks() {
        return ApiResponse.ok(myDataService.findBanks());
    }

    /** 고른 은행들에 있는 계좌(0~1건). 없으면 빈 목록 — 그 은행에 계좌가 없다는 뜻이다. */
    @GetMapping("/accounts")
    public ApiResponse<List<AccountView>> getAccountsByBanks(@RequestParam String userId,
                                                             @RequestParam List<Long> bankIds) {
        return ApiResponse.ok(myDataService.findAccountsByBanks(userId, bankIds));
    }

    /**
     * <b>그 사람의 것을 전부 지운다</b> — 관리자 강제 삭제(본체가 부른다).
     *
     * <p>열쇠가 CI 하나뿐인 것이 요점이다. 이름·주민번호·전화로 찾게 하면 <b>지우는 일 때문에
     * 관리자가 개인식별정보를 손에 쥐게 된다.</b> CI 는 되돌릴 수 없는 해시라 그 자체로는 누구인지
     * 말해 주지 않고, 목록 조회가 없으니 이미 아는 사람만 지울 수 있다.
     *
     * <p>{@code /bank/**} 아래라 공유 시크릿 필터가 걸린다 — 본체 말고는 못 부른다.
     */
    @DeleteMapping("/user/{userCi}")
    public ApiResponse<MyDataService.PurgeResult> purgeUser(@PathVariable String userCi) {
        return ApiResponse.ok(myDataService.purgeUser(userCi));
    }

    /** 가맹점 조회(번호→주소) — 사용자가 결제의 사업자번호로 가맹점명·지번주소를 조회. 없으면 data=null. */
    @GetMapping("/merchant/{businessNumber}")
    public ApiResponse<MerchantView> getMerchant(@PathVariable String businessNumber) {
        return ApiResponse.ok(myDataService.findMerchant(businessNumber).orElse(null));
    }
}
