package com.finntech.mydata.config;

import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * 운영(mysql) 기동 시 서버간 인증 시크릿이 반드시 설정돼 있어야 한다(fail-fast, W7-2).
 * "운영 기본값은 안전해야 한다" — 시크릿 미설정 상태로 제공자 API가 무인증으로 뜨는 것을 원천 차단한다.
 * dev(h2)에는 이 가드가 없어 로컬 개발이 시크릿 없이도 통과한다.
 */
@Configuration
@Profile("mysql")
public class SharedSecretRequiredGuard {

    private final String secret;

    public SharedSecretRequiredGuard(@Value("${mydata.shared-secret:}") String secret) {
        this.secret = secret;
    }

    @PostConstruct
    void verify() {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "운영(mysql) 프로파일에는 mydata.shared-secret(env MYDATA_SHARED_SECRET)이 필수입니다 — 서버간 인증(W7-2).");
        }
    }
}
