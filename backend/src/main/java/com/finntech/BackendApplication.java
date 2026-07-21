package com.finntech;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/** {@code @EnableScheduling} — 개인정보 보유기간 자동 파기 배치를 돌리기 위함 (문서 §5-3 처리방침 3·4번). */
@SpringBootApplication
@EnableScheduling
public class BackendApplication {

	public static void main(String[] args) {
		SpringApplication.run(BackendApplication.class, args);
	}

}
