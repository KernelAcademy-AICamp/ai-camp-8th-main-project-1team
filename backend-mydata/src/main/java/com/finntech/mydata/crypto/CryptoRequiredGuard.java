package com.finntech.mydata.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 운영에서 <b>제공자 신원 암호화</b>가 꺼져 있으면 기동을 막는다.
 *
 * <p>같은 모듈의 {@code SharedSecretRequiredGuard} 와 같은 태도다. 스위치를 잊고 배포하면
 * 서비스는 멀쩡히 돌면서 <b>이름·주민번호·전화번호가 평문으로 쌓인다</b> — 아무 오류도 없이.
 * 그 상태를 나중에 알아채면 이미 쌓인 평문을 되돌릴 방법이 없다.
 * <b>조용히 평문으로 도는 것보다 기동이 실패하는 편이 낫다.</b>
 *
 * <h2>CI 는 예외다 — 그리고 그 예외를 눈에 보이게 둔다</h2>
 *
 * <p>CI 의 '운영 중지 검사' 는 <b>운영과 같은 조건(mysql 프로파일)으로 컨테이너를 띄워</b>
 * 기동이 되는지 본다. 그런데 러너에는 KMS 키도 자격증명도 없다. 가드를 그대로 두면
 * <b>CI 가 영원히 실패한다</b> — 실제로 그렇게 막혔다(2026-08-12).
 *
 * <p>그래서 {@code mydata.crypto.required=false} 로 끌 수 있게 하되 <b>기본값은 켬</b>이고,
 * 끄면 <b>경고를 남긴다</b>. CI 워크플로가 그 값을 명시적으로 넣으므로, 무엇을 완화했는지
 * {@code ci.yml} 을 보면 바로 보인다. 운영 {@code .env} 에는 그 값이 없으므로 가드는 그대로 산다.

 * <p><b>이 가드가 없으면 4,513행이 다시 평문으로 쌓인다.</b> 본체는 처음부터 암호화했는데
 * 이쪽이 평문이었던 것도 "켜는 것을 잊었다"가 아니라 <b>켤 자리가 없었기</b> 때문이다
 * (2026-08-13 실측). 같은 일이 반복되지 않게 여기서 막는다.
 */
@Component
@Profile("mysql")
public class CryptoRequiredGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(CryptoRequiredGuard.class);

    private final FieldCrypto crypto;
    private final boolean required;

    public CryptoRequiredGuard(FieldCrypto crypto,
                               @Value("${mydata.crypto.required:true}") boolean required) {
        this.crypto = crypto;
        this.required = required;
    }

    @Override
    public void afterPropertiesSet() {
        if (crypto.isEnabled()) return;
        if (!required) {
            log.warn("""
                    개인정보 컬럼 암호화가 꺼진 채로 mysql 프로파일이 떴다 \
                    (mydata.crypto.required=false 로 가드를 껐다).
                    **운영에서는 이 값을 넣지 마라** — 이름·주민번호·전화번호가 평문으로 쌓인다.""");
            return;
        }
        throw new IllegalStateException("""
                운영(mysql) 프로파일에서 개인정보 컬럼 암호화가 꺼져 있다.
                finntech.crypto.enabled=true 와 kms-key-id · encrypted-dek · encrypted-pepper 를 넣어라.
                끄고 띄우면 이름·주민번호·전화번호가 평문으로 쌓인다 — 되돌릴 수 없다.
                (CI 처럼 KMS 가 없는 곳에서만 mydata.crypto.required=false 로 건너뛴다.)""");
    }
}
