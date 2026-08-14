package com.finntech.mydata.crypto;

import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import org.springframework.stereotype.Component;

/**
 * {@code String} 필드를 <b>암호문 바이트</b>로 저장한다.
 *
 * <p><b>왜 변환기인가.</b> 신원 값을 읽고 쓰는 자리가 엔티티·서비스·적재기에 흩어져 있는데,
 * 그중 한 곳만 암호화를 빠뜨리면 그 경로로 들어온 값이 <b>조용히 평문으로</b> 앉는다.
 * 변환기는 JPA 가 읽고 쓸 때마다 무조건 지나는 자리라, 빠뜨릴 곳 자체가 없다.
 *
 * <p><b>스프링 빈이다.</b> 하이버네이트가 컨버터를 직접 만들면 {@link FieldCrypto} 를 주입할
 * 방법이 없다. 스프링 부트는 빈으로 등록된 컨버터를 하이버네이트에 넘겨 주므로 이렇게 둔다.
 *
 * <p>{@code autoApply} 는 쓰지 않는다 — 모든 문자열을 암호화하면 페르소나·분할 라벨처럼
 * 개인정보가 아닌 값까지 읽을 수 없게 된다. <b>암호화할 칸에만 명시적으로</b> 붙인다.
 */
@Component
@Converter
public class EncryptedStringConverter implements AttributeConverter<String, byte[]> {

    private final FieldCrypto crypto;

    public EncryptedStringConverter(FieldCrypto crypto) {
        this.crypto = crypto;
    }

    @Override
    public byte[] convertToDatabaseColumn(String plain) {
        return crypto.encrypt(plain);
    }

    @Override
    public String convertToEntityAttribute(byte[] stored) {
        return crypto.decrypt(stored);
    }
}
