package com.finntech.mydata.web;

import com.finntech.mydata.dto.ApiResponse;
import com.finntech.mydata.dto.MyDataDtos.AccountView;
import com.finntech.mydata.dto.MyDataDtos.CardView;
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

    /** 입출금 통장 조회(§13-11) — 은행·계좌·월급·잔액 + 최근 입출금 내역. 계좌 없으면 data=null. */
    @GetMapping("/account")
    public ApiResponse<AccountView> getAccount(@RequestParam String userId) {
        return ApiResponse.ok(myDataService.findAccount(userId).orElse(null));
    }
}
