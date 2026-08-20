package com.finntech.mydata.web;

import com.finntech.mydata.service.RealPersonImportService.PhoneAlreadyTakenException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>거절은 고장이 아니다</b> — 판정으로 막은 것은 사유를 달아 돌려준다.
 *
 * <p>예전에는 이 모듈에 예외 처리가 한 곳도 없었다. 그래서 판정으로 막은 것이든 진짜 고장이든
 * 전부 <b>본문 없는 500</b> 이 됐고, 부르는 쪽(본체)은 "왜 안 됐는지"를 알 방법이 없었다.
 * 실제로 그렇게 겪었다 — 한 번호에 두 사람이 붙어 조회가 터졌을 때 본체 로그에 남은 것은
 * {@code 500 Internal Server Error: {"path":"/bank/mydata/identity-match"}} 한 줄뿐이라,
 * 제공자 로그를 따로 열어 보고서야 원인을 알았다(2026-08-20).
 *
 * <p>여기서 다루는 것은 <b>사유를 아는 거절</b>뿐이다. 모르는 예외는 그대로 500 으로 둔다 —
 * 사유를 지어내면 고장이 정상 응답처럼 보인다.
 */
@RestControllerAdvice
public class ProviderExceptionAdvice {

    private static final Logger log = LoggerFactory.getLogger(ProviderExceptionAdvice.class);

    /** 그 번호는 이미 남의 것이다 — 신청자가 고칠 수 있도록 문장을 그대로 넘긴다. */
    @ExceptionHandler(PhoneAlreadyTakenException.class)
    public ResponseEntity<Map<String, Object>> phoneTaken(PhoneAlreadyTakenException e) {
        log.info("번호 중복으로 적재 거절 — {}", e.getMessage());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", HttpStatus.CONFLICT.value());
        body.put("error", "PHONE_ALREADY_TAKEN");
        body.put("message", e.getMessage());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }
}
