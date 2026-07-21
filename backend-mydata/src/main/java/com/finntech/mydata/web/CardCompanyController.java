package com.finntech.mydata.web;

import com.finntech.mydata.dto.ApiResponse;
import com.finntech.mydata.dto.MyDataDtos.CompanyView;
import com.finntech.mydata.service.MyDataService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** 카드사(연동 기관) 목록 API — 온보딩의 '금융사 선택' 단계에서 쓴다. */
@RestController
@RequestMapping("/bank/mydata/card-company")
public class CardCompanyController {

    private final MyDataService myDataService;

    public CardCompanyController(MyDataService myDataService) {
        this.myDataService = myDataService;
    }

    @GetMapping
    public ApiResponse<List<CompanyView>> getCompanies() {
        return ApiResponse.ok(myDataService.findCompanies());
    }
}
