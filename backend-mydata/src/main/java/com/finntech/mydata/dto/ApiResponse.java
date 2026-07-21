package com.finntech.mydata.dto;

/**
 * 공통 응답 엔벨로프(statusCode/message/data).
 * 본체(backend)가 RestClient로 이 모양을 그대로 역직렬화한다.
 */
public record ApiResponse<T>(int statusCode, String message, T data) {
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(200, "SUCCESS", data);
    }
}
