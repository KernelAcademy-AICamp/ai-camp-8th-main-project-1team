package com.finntech.crypto;

import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 운영에서 개인정보 암호화가 꺼져 있으면 <b>기동을 막는다</b>.
 *
 * <p>제공자의 {@code SharedSecretRequiredGuard} 와 같은 태도다. 스위치를 잊고 배포하면
 * 서비스는 멀쩡히 돌면서 <b>이름·주민번호·전화번호가 평문으로 쌓인다</b> — 아무 오류도 없이.
 * 그 상태를 나중에 알아채면 이미 쌓인 평문을 되돌릴 방법이 없다.
 * <b>조용히 평문으로 도는 것보다 기동이 실패하는 편이 낫다.</b>
 */
@Component
@Profile("mysql")
public class CryptoRequiredGuard implements InitializingBean {

    private final FieldCrypto crypto;

    public CryptoRequiredGuard(FieldCrypto crypto) {
        this.crypto = crypto;
    }

    @Override
    public void afterPropertiesSet() {
        if (!crypto.isEnabled()) {
            throw new IllegalStateException("""
                    운영(mysql) 프로파일에서 개인정보 컬럼 암호화가 꺼져 있다.
                    finntech.crypto.enabled=true 와 kms-key-id · encrypted-dek · encrypted-pepper 를 넣어라.
                    끄고 띄우면 이름·주민번호·전화번호가 평문으로 쌓인다 — 되돌릴 수 없다.""");
        }
    }
}
