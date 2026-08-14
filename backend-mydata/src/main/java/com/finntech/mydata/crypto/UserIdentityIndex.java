package com.finntech.mydata.crypto;

import com.finntech.mydata.domain.MyDataUser;
import com.finntech.mydata.util.Msisdn;
import org.springframework.stereotype.Component;

/**
 * 신원 <b>지문</b>을 만드는 한 곳.
 *
 * <h2>왜 한 곳인가</h2>
 *
 * <p>지문은 저장할 때와 찾을 때 <b>같은 규칙</b>으로 계산해야 한다. 한 글자라도 갈리면
 * 있는 사람을 못 찾고, 증상은 "분명히 맞게 넣었는데 신원 정보가 불일치합니다"로 나온다 —
 * 이 저장소는 이미 전화번호 표기가 갈려 같은 사고를 겪었다(2026-08-05).
 * 규칙을 두 곳에 적으면 언젠가 갈린다.
 *
 * <h2>정규화</h2>
 *
 * <p>전화번호는 {@link Msisdn#format} 한 벌만 쓴다 — 저장 표기와 같은 규칙이다.
 * 이름+주민앞7 은 구분자를 사이에 끼워 잇는다. 안 끼우면 {@code ("홍길", "동0309303")} 과
 * {@code ("홍길동", "0309303")} 이 <b>같은 지문</b>이 되어 남의 신원으로 통과할 수 있다.
 */
@Component
public class UserIdentityIndex {

    /** 이름과 주민앞7 사이의 구분자. 이름에 나올 수 없는 글자라야 한다. */
    private static final char SEPARATOR = '';   // UNIT SEPARATOR

    private final FieldCrypto crypto;

    public UserIdentityIndex(FieldCrypto crypto) {
        this.crypto = crypto;
    }

    /** 전화번호 지문. 저장 표기와 같은 규칙으로 정규화한 뒤 계산한다. */
    public String ofPhone(String phone) {
        if (phone == null || phone.isBlank()) return null;
        return crypto.blindIndex(Msisdn.format(phone));
    }

    /** 이름+주민앞7 지문. 주민번호가 7자보다 길면 앞 7자리만 쓴다(저장은 뒤가 가려져 있다). */
    public String ofPerson(String name, String socialNumber) {
        if (name == null || name.isBlank() || socialNumber == null || socialNumber.length() < 7) {
            return null;
        }
        return crypto.blindIndex(name + SEPARATOR + socialNumber.substring(0, 7));
    }

    /**
     * 신원을 <b>지문까지 찍어서</b> 만든다.
     *
     * <p>생성자를 직접 부르면 지문이 빈 채로 저장되고, 그 사람은 조회에 안 걸려
     * <b>로그인하지 못한다.</b> 백필이 다음 기동에 채워 주지만 그때까지 못 들어간다 —
     * 새로 신청한 사람에게는 그것이 곧 실패다. 만드는 길을 하나로 모아 빠뜨릴 자리를 없앤다.
     */
    public MyDataUser newUser(String ci, String name, String socialNumber, String phone) {
        MyDataUser user = new MyDataUser(ci, name, socialNumber, phone);
        user.encryptInto(name, socialNumber, phone, ofPhone(phone), ofPerson(name, socialNumber));
        return user;
    }

    /** 번호를 고치면 지문도 함께 고친다 — 따로 하면 한쪽만 바뀌어 그 사람을 못 찾는다. */
    public void changePhone(MyDataUser user, String phone) {
        user.setPhoneNumber(phone);
        user.setPhoneBlindIndex(ofPhone(phone));
    }
}
