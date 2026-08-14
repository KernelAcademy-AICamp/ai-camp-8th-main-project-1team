package com.finntech.mydata.crypto;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.DataKeySpec;
import software.amazon.awssdk.services.kms.model.DecryptRequest;
import software.amazon.awssdk.services.kms.model.GenerateDataKeyRequest;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 제공자의 <b>실 신원 컬럼 암호화</b> — envelope encryption.
 *
 * <pre>
 *   KMS CMK (alias/finntech-pii)
 *        │ GenerateDataKey
 *        ▼
 *      DEK (AES-256) ──암호화──▶ 이름 · 주민앞7 · 전화
 * </pre>
 *
 * <h2>왜 뒤늦게 왔나</h2>
 *
 * <p>본체(backend)는 신청 대기열을 처음부터 암호화했는데(`realuser_intake` 의 `*_enc`),
 * <b>승인 뒤 실제로 저장되는 이쪽은 평문이었다.</b> 2026-08-13 운영 실측:
 *
 * <pre>
 *   mydata_user.mydata_user_name           varchar(40)   ← 실제 이름
 *   mydata_user.mydata_user_social_number  varchar(20)   ← 주민번호 앞자리
 *   mydata_user.mydata_user_phone_number   varchar(20)   ← 전화번호
 *   4,513행
 * </pre>
 *
 * <p>대기열은 승인되면 지워지므로 <b>오래 남는 쪽이 오히려 평문</b>이었다. 실제 사람들의
 * 신원이 여기 있다.
 *
 * <h2>거래내역은 암호화하지 않는다</h2>
 *
 * <p>가맹점명·금액·일시는 분류·집계·ML 이 전부 읽으므로 암호화하면 서비스가 성립하지 않는다.
 * 그쪽은 접근 통제와 EBS 암호화가 맡는다(2026-08-13 루트 볼륨 암호화 완료).
 * 이 경계를 흐리면 "암호화했다"는 말만 남고 기능이 죽는다.
 *
 * <h2>검색이 깨지는 문제</h2>
 *
 * <p>본인인증은 {@code findByPhoneNumber}·{@code findByNameAndSocial7} 로 <b>정확일치</b>
 * 조회를 한다. 그냥 암호화하면 아무도 로그인하지 못한다. 그래서 {@link #blindIndex} 로
 * 조회 전용 지문을 따로 만든다. <b>결정론 암호화(고정 IV)는 쓰지 않는다</b> —
 * 같은 값이 같은 암호문이 되어 "이 둘은 같은 사람"이 복호화 없이 드러난다.
 *
 * <h2>키는 본체와 따로 둔다</h2>
 *
 * <p>같은 CMK 를 쓰더라도 DEK·pepper 는 {@code mydata.crypto.*} 로 따로 받는다. 한쪽 키가
 * 새도 다른 쪽이 함께 열리지 않게 하려는 것이다 — 두 모듈을 갈라 둔 이유와 같다.
 *
 * <h2>꺼져 있을 때</h2>
 *
 * <p>{@code mydata.crypto.enabled=false}(기본)면 <b>평문 그대로</b> 통과시킨다. 로컬 개발과
 * 시험이 KMS 없이 돌아야 하기 때문이다. 운영에서 꺼져 있으면
 * {@link CryptoRequiredGuard} 가 기동을 막는다 — <b>조용히 평문으로 도는 것이 가장 나쁘다.</b>
 */
@Component
public class FieldCrypto {

    private static final Logger log = LoggerFactory.getLogger(FieldCrypto.class);

    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_BYTES = 12;          // GCM 권장 96비트
    private static final byte VERSION = 1;           // 키 회전 시 옛 형식을 알아보기 위한 표식
    private static final String HMAC = "HmacSHA256";

    private final boolean enabled;
    private final SecureRandom random = new SecureRandom();

    /** 복호화된 데이터 키. **메모리에만 있다.** */
    private final byte[] dek;
    /** 블라인드 인덱스용 pepper. DEK 와 함께 KMS 로 감싸 보관한다. */
    private final byte[] pepper;

    public FieldCrypto(
            @Value("${mydata.crypto.enabled:false}") boolean enabled,
            @Value("${mydata.crypto.kms-key-id:}") String kmsKeyId,
            @Value("${mydata.crypto.encrypted-dek:}") String encryptedDek,
            @Value("${mydata.crypto.encrypted-pepper:}") String encryptedPepper) {
        this.enabled = enabled;
        if (!enabled) {
            this.dek = null;
            this.pepper = null;
            log.warn("제공자 신원 암호화가 꺼져 있다 — 평문으로 저장된다. 운영에서는 CryptoRequiredGuard 가 막는다.");
            return;
        }
        if (encryptedDek.isBlank() || encryptedPepper.isBlank()) {
            throw new IllegalStateException(
                    "mydata.crypto.enabled=true 인데 encrypted-dek/encrypted-pepper 가 비어 있다. "
                    + "scripts/crypto/bootstrap-keys.sh 로 발급한다.");
        }
        try (KmsClient kms = KmsClient.create()) {
            this.dek = kms.decrypt(DecryptRequest.builder()
                    .keyId(kmsKeyId.isBlank() ? null : kmsKeyId)
                    .ciphertextBlob(SdkBytes.fromByteArray(Base64.getDecoder().decode(encryptedDek)))
                    .build()).plaintext().asByteArray();
            this.pepper = kms.decrypt(DecryptRequest.builder()
                    .keyId(kmsKeyId.isBlank() ? null : kmsKeyId)
                    .ciphertextBlob(SdkBytes.fromByteArray(Base64.getDecoder().decode(encryptedPepper)))
                    .build()).plaintext().asByteArray();
        }
        log.info("제공자 신원 암호화 준비됨 (envelope, DEK {}바이트)", dek.length);
    }

    public boolean isEnabled() { return enabled; }

    /**
     * 컬럼 값을 암호화한다. 형식은 {@code [버전 1][IV 12][암호문+태그]}.
     *
     * <p><b>IV 를 매번 새로 뽑는다.</b> 고정하면 같은 값이 같은 암호문이 되어, 복호화하지 않고도
     * "이 두 사람은 같은 이름"임을 알 수 있게 된다.
     */
    public byte[] encrypt(String plain) {
        if (plain == null) return null;
        if (!enabled) return plain.getBytes(StandardCharsets.UTF_8);
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(dek, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] body = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + iv.length + body.length)
                    .put(VERSION).put(iv).put(body).array();
        } catch (Exception exception) {
            throw new IllegalStateException("컬럼 암호화 실패", exception);
        }
    }

    public String decrypt(byte[] stored) {
        if (stored == null) return null;
        if (!enabled) return new String(stored, StandardCharsets.UTF_8);
        try {
            ByteBuffer buffer = ByteBuffer.wrap(stored);
            byte version = buffer.get();
            if (version != VERSION) {
                throw new IllegalStateException("모르는 암호문 버전: " + version);
            }
            byte[] iv = new byte[IV_BYTES];
            buffer.get(iv);
            byte[] body = new byte[buffer.remaining()];
            buffer.get(body);
            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(dek, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return new String(cipher.doFinal(body), StandardCharsets.UTF_8);
        } catch (Exception exception) {
            throw new IllegalStateException("컬럼 복호화 실패", exception);
        }
    }

    /**
     * 조회 전용 지문 — {@code HMAC-SHA256(pepper, 정규화값)}.
     *
     * <p>암호문은 매번 달라 조회에 쓸 수 없다. 이 값은 같은 입력이면 항상 같아 인덱스가 걸리고,
     * pepper 없이는 되돌릴 수 없어 <b>목록만 훔쳐도 원문을 얻지 못한다</b>(무지개표 방어).
     *
     * <p>정규화는 호출부가 {@code Ci.of} 와 <b>같은 규칙</b>으로 해서 넘겨야 한다 —
     * 갈리면 있는 사람을 못 찾는다.
     */
    public String blindIndex(String normalized) {
        if (normalized == null || normalized.isEmpty()) return null;
        if (!enabled) return normalized;                 // 개발에서는 값 그대로 — 조회가 그대로 돈다
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(pepper, HMAC));
            return HexFormat.of().formatHex(mac.doFinal(normalized.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("블라인드 인덱스 계산 실패", exception);
        }
    }

    /**
     * 키를 처음 만들 때 쓴다 — 운영 부트스트랩 전용.
     *
     * @return {@code [평문 키, 암호화된 키]} — 평문은 즉시 버리고 암호문만 설정에 넣는다
     */
    public static String[] generateDataKey(String kmsKeyId) {
        try (KmsClient kms = KmsClient.create()) {
            var response = kms.generateDataKey(GenerateDataKeyRequest.builder()
                    .keyId(kmsKeyId).keySpec(DataKeySpec.AES_256).build());
            return new String[] {
                    Base64.getEncoder().encodeToString(response.plaintext().asByteArray()),
                    Base64.getEncoder().encodeToString(response.ciphertextBlob().asByteArray())
            };
        }
    }
}
