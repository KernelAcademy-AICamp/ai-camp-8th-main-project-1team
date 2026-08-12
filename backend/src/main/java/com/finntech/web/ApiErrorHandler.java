package com.finntech.web;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link ResponseStatusException} 의 <b>사유를 사용자에게 돌려준다</b>.
 *
 * <h2>왜 필요한가</h2>
 *
 * <p>{@code server.error.include-message: always} 가 설정돼 있는데도 400 응답 본문에
 * {@code message} 가 실리지 않았다(2026-08-12 운영에서 실측):
 *
 * <pre>{@code
 * {"timestamp":"...","status":400,"error":"Bad Request","path":"/api/admin/password"}
 * }</pre>
 *
 * <p>그래서 화면에는 <b>"요청 실패 (400)"</b> 만 떴다. admin 이 비밀번호를 바꾸려는데
 * <i>지금 비밀번호가 틀린 것인지</i> <i>새 비밀번호가 12자 미만인 것인지</i> 알 길이 없었다.
 * 신청 화면의 검증 문구("이름을 확인해 주세요" 등)도 같은 이유로 전부 사라지고 있었다 —
 * {@code ResponseStatusException} 을 쓰는 곳이 <b>18개 파일</b>이다.
 *
 * <p>서블릿 오류 디스패치와 Boot 의 {@code DefaultErrorAttributes} 에 기대는 대신,
 * <b>여기서 직접 만들어 돌려준다.</b> 이 저장소에서 이미 동작이 증명된 방식이다 —
 * admin 로그인만 문구가 제대로 보였는데, 그것이 유일하게 본문을 직접 만들던 곳이었다.
 *
 * <h2>사유만 나간다</h2>
 *
 * <p>돌려주는 것은 <b>우리가 직접 쓴 {@code reason}</b> 뿐이다. 예외 메시지·원인·스택은
 * 싣지 않는다 — 그것들은 내부 구조를 흘린다. 사유가 없는 예외는 상태 코드만 나간다.
 */
@RestControllerAdvice
public class ApiErrorHandler {

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handle(ResponseStatusException exception) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", exception.getStatusCode().value());
        // 우리가 쓴 문구만 나간다. 없으면 아무 말도 안 한다 — 지어내지 않는다.
        if (exception.getReason() != null && !exception.getReason().isBlank()) {
            body.put("message", exception.getReason());
        }
        return ResponseEntity.status(exception.getStatusCode())
                .headers(exception.getHeaders())
                .body(body);
    }
}
