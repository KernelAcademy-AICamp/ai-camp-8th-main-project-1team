package com.finntech.mydata;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 마이데이터 서버 (더미 본인신용정보 제공자) — 마스터 §13, tech_log §10.
 *
 * <p>은행/카드사 측 데이터를 보관·제공하는 별도 프로세스(마이데이터 제공자 시뮬레이션)다.
 * 본체(backend, 8080)와 분리된 8082 포트로 {@code /bank/mydata/**} 를 통해 카드·결제내역을 제공한다.
 * 개인은 실 마이데이터에 접근할 수 없으므로(마스터 §III-B §4·5), 공개 표준 API 규격 모양의 더미를 우리가 운영한다(§III-B §6-1).
 */
@SpringBootApplication
public class MydataApplication {
    public static void main(String[] args) {
        SpringApplication.run(MydataApplication.class, args);
    }
}
