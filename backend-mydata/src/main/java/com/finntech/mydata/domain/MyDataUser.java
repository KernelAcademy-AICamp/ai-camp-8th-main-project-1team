package com.finntech.mydata.domain;

import com.finntech.mydata.crypto.EncryptedStringConverter;
import jakarta.persistence.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 마이데이터 사용자 (mydata_user). 은행/카드사 측이 보관하는 신원.
 * PK는 CI 해시(본체가 본인인증으로 계산해 넘기는 식별자)다.
 */
@Entity
@Table(name = "mydata_user")
public class MyDataUser {

    @Id
    @Column(name = "mydata_user_id", length = 64)
    private String id; // CI = SHA-256(이름+주민6+성별세대+전화)

    /**
     * <b>옛 평문 칸.</b> V13 이 암호문 칸을 더했고 V14 가 이 칸을 비운다. 그 사이에만 값이 있다.
     *
     * <p>읽을 때는 암호문이 먼저다({@link #getName()}) — 백필이 끝난 행은 이 칸을 안 본다.
     * 새로 만드는 행에는 빈 문자열이 들어간다(NOT NULL 이라 null 은 못 넣는다).
     */
    @Column(name = "mydata_user_name", nullable = false, length = 40)
    private String name;

    @Column(name = "mydata_user_social_number", nullable = false, length = 20)
    private String socialNumber;

    @Column(name = "mydata_user_phone_number", nullable = false, length = 20)
    private String phoneNumber;

    /**
     * 암호문 칸 — {@link EncryptedStringConverter} 가 읽고 쓸 때마다 감싸고 푼다.
     *
     * <p>실제 사람의 신원이 여기 있다. 본체(backend)의 신청 대기열은 처음부터 암호화했는데
     * <b>승인 뒤 오래 남는 이쪽이 평문이었다</b>(2026-08-13 실측 4,513행).
     */
    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "mydata_user_name_enc")
    private String nameEnc;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "mydata_user_social_enc")
    private String socialNumberEnc;

    @Convert(converter = EncryptedStringConverter.class)
    @Column(name = "mydata_user_phone_enc")
    private String phoneNumberEnc;

    /**
     * 조회 전용 지문 — {@code HMAC-SHA256(pepper, 정규화 전화번호)}.
     *
     * <p>암호문은 IV 가 매번 달라 정확일치 조회에 못 쓴다. 본인인증이 전화번호로 명의자를
     * 찾으므로 이것이 없으면 <b>아무도 로그인하지 못한다.</b> pepper 없이는 되돌릴 수 없어
     * 이 칸만 훔쳐도 원문은 안 나온다.
     */
    @Column(name = "mydata_user_phone_bi", length = 64)
    private String phoneBlindIndex;

    /** 이름+주민앞7 지문. 본인인증이 "무엇이 틀렸는지" 가릴 때 쓴다. */
    @Column(name = "mydata_user_person_bi", length = 64)
    private String personBlindIndex;

    /**
     * 페르소나 라벨 — 향후 Faker 대량 생성(§13-11) 시 사용자별 소비 성향을 태그하기 위한 자리.
     * 현재 시드 데이터는 null이며, 대량 생성 파이프라인이 채운다.
     */
    @Column(name = "mydata_user_persona", length = 40)
    private String persona;

    /**
     * 데이터 분리 파티션(과적합 방지, W1·W8 요구11): TRAIN / VAL / TEST / SERVICE.
     * 사용자 단위 disjoint 배분 — 앱은 SERVICE만 시연(학습 데이터로 데모하지 않는다).
     * 현재 12명 시드는 null(전량 서빙). 대량 생성 파이프라인이 채운다.
     */
    @Column(name = "mydata_user_data_split", length = 10)
    private String dataSplit;

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    private List<MyDataCard> cards = new ArrayList<>();

    protected MyDataUser() {}

    /**
     * 새 신원. <b>평문 칸에는 아무것도 안 넣는다</b> — 값은 암호문 칸으로만 간다.
     *
     * <p>NOT NULL 이라 빈 문자열을 넣는다. V14 가 옛 행들의 평문을 비우고 나면 이 칸은
     * 모든 행에서 빈 값이 되고, 그때 칸 자체를 없앨 수 있다.
     *
     * <p>지문은 {@code MyDataUserIdentity} 가 붙인다 — 그 계산에는 pepper 가 필요하고,
     * 엔티티가 스프링 빈을 들고 있으면 안 되기 때문이다.
     */
    public MyDataUser(String id, String name, String socialNumber, String phoneNumber) {
        this.id = id;
        this.name = "";
        this.socialNumber = "";
        this.phoneNumber = "";
        this.nameEnc = name;
        this.socialNumberEnc = socialNumber;
        this.phoneNumberEnc = phoneNumber;
    }

    public String getId() { return id; }

    /**
     * <b>암호문이 먼저다.</b> 백필이 끝난 행은 평문 칸을 안 본다.
     *
     * <p>백필 도중에 기동이 끊겨 일부 행만 채워졌더라도 이 순서 덕에 <b>양쪽 다 읽힌다</b> —
     * 로그인이 중간 상태에서 깨지지 않는 것이 이 한 줄의 목적이다.
     */
    public String getName() { return nameEnc != null ? nameEnc : name; }
    public String getSocialNumber() { return socialNumberEnc != null ? socialNumberEnc : socialNumber; }
    public String getPhoneNumber() { return phoneNumberEnc != null ? phoneNumberEnc : phoneNumber; }

    /** 아직 암호화 안 된 행인가 — 백필이 이 값으로 대상을 고른다. */
    public boolean needsEncryption() { return nameEnc == null || phoneBlindIndex == null; }

    /** 백필과 신규 생성이 함께 쓰는 자리. 평문 칸은 건드리지 않는다(V14 가 비운다). */
    public void encryptInto(String name, String socialNumber, String phoneNumber,
                            String phoneBlindIndex, String personBlindIndex) {
        this.nameEnc = name;
        this.socialNumberEnc = socialNumber;
        this.phoneNumberEnc = phoneNumber;
        this.phoneBlindIndex = phoneBlindIndex;
        this.personBlindIndex = personBlindIndex;
    }

    public String getPhoneBlindIndex() { return phoneBlindIndex; }
    public String getPersonBlindIndex() { return personBlindIndex; }

    /**
     * 저장 표기를 바로잡는 자리 — <b>본인인증이 이 값으로 명의자를 찾는다</b>(정확일치).
     *
     * <p>실데이터 적재만 숫자로 저장하던 시절이 있었고, 그래서 실제 사람이 자기 번호를
     * 정확히 넣어도 "전화번호가 다릅니다"가 떴다(2026-08-05). 표기는 {@link
     * com.finntech.mydata.util.Msisdn#format} 한 벌만 쓴다.
     */
    public void setPhoneNumber(String phoneNumber) { this.phoneNumberEnc = phoneNumber; }

    /** 표기를 바로잡으면 지문도 함께 바뀌어야 한다 — 안 그러면 그 사람을 못 찾는다. */
    public void setPhoneBlindIndex(String phoneBlindIndex) { this.phoneBlindIndex = phoneBlindIndex; }
    public String getPersona() { return persona; }
    public void setPersona(String persona) { this.persona = persona; }
    public String getDataSplit() { return dataSplit; }
    public void setDataSplit(String dataSplit) { this.dataSplit = dataSplit; }
    public List<MyDataCard> getCards() { return cards; }
}
