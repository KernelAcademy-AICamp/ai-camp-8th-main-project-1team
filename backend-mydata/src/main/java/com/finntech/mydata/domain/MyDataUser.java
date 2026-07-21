package com.finntech.mydata.domain;

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

    @Column(name = "mydata_user_name", nullable = false, length = 40)
    private String name;

    @Column(name = "mydata_user_social_number", nullable = false, length = 20)
    private String socialNumber; // 주민등록번호 (더미)

    @Column(name = "mydata_user_phone_number", nullable = false, length = 20)
    private String phoneNumber;

    @OneToMany(mappedBy = "user", fetch = FetchType.LAZY)
    private List<MyDataCard> cards = new ArrayList<>();

    protected MyDataUser() {}

    public MyDataUser(String id, String name, String socialNumber, String phoneNumber) {
        this.id = id;
        this.name = name;
        this.socialNumber = socialNumber;
        this.phoneNumber = phoneNumber;
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getSocialNumber() { return socialNumber; }
    public String getPhoneNumber() { return phoneNumber; }
    public List<MyDataCard> getCards() { return cards; }
}
