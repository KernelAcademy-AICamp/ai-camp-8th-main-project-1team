package com.finntech.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;

/**
 * 사용자. 실명·이메일·계좌·카드·주민번호 필드를 두지 않는다 (문서 §5-3, RFP D26).
 * 식별자는 닉네임 기반 익명 계정이다.
 */
@Entity
@Table(name = "app_user")
public class AppUser {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 익명 닉네임 */
    @Column(nullable = false, unique = true, length = 40)
    private String nickname;

    /** 월 소득 — 저축진행률 계산용 */
    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal monthlyIncome = BigDecimal.ZERO;

    @Column(nullable = false, precision = 15, scale = 2)
    private BigDecimal goalAmount = BigDecimal.ZERO;

    /** 목표 기간(개월) */
    @Column(nullable = false)
    private Integer goalMonths = 6;

    /** 개인정보 수집 동의 여부 — 미동의 시 더미 데모 모드 */
    @Column(nullable = false)
    private boolean consentGiven = false;

    /**
     * 이 사용자의 결제가 <b>실제 사람의 명세서</b>에서 왔는가 — 더미와 실물을 가르는 표시.
     *
     * <p><b>왜 사용자에 두나.</b> 지금까지 그 구분은 {@code payment_id} 의 접두라는 <b>문자열
     * 관습</b> 하나였고({@link UserPayment#isFromRealPerson}), 그것을 여덟 자리가 각자 검사했다.
     * 하나가 빠지면 조용히 샌다 — 실제로 관측({@code observeBusinessNumbers}·
     * {@code observeAfterConfirm}) 두 자리가 빠져 전역 판정 표가 더미로 채워졌다
     * (2026-08-07 실측: {@code business_number_kind} 22행 중 20행이 더미만으로 만들어진 것이었다).
     *
     * <p>그리고 결제 단위 술어로는 <b>값싼 조기 종료를 못 한다.</b> "이 사용자는 더미다"를 모르니
     * 매 회차 결제를 전부 읽고 나서 버렸다 — 5분마다 3만 8천 행을 읽어 439건을 처리했다.
     *
     * <p><b>손으로 켜지 않는다.</b> 적재가 결제를 보면서 스스로 정한다 — 연동은 전부 지우고 다시
     * 넣으므로 그때마다 다시 계산되고, 증분은 덧붙이기만 하므로 켜기만 한다. 그래서 값이
     * 데이터와 어긋날 자리가 없다.
     */
    @Column(name = "real_person", nullable = false)
    private boolean realPerson = false;

    /**
     * 마이데이터 연동용 CI (§13). 실 NICE 인증값이 아니라 본인인증으로 받은 <b>가상 생성값</b>이다.
     * 마이데이터(더미) 서버 조회 키로만 쓴다. 미연동이면 null.
     * <p>전화번호는 CI 계산에만 쓰고 <b>저장하지 않는다</b>(현 스텁 단계 '전화번호 실수집 없음', §13-2).
     * 실 coolsms 도입 시 그때 전화번호 필드·처리방침을 추가한다.
     */
    @Column(length = 64)
    private String ci;

    /**
     * 출생연도 (예: 1995). 본인인증의 주민번호 앞 7자리에서 <b>연도만</b> 파생한다 — 월·일은 버린다.
     * 용도는 하나뿐이다: 금융상품의 나이 자격(`만 19세~만 34세` 등)을 맞춰 보는 것.
     * 우리가 아는 정보가 나이뿐이므로 장병·공무원·조합원처럼 신분이 필요한 상품은 애초에 추천하지 않는다.
     * 미연동이면 null이며, 삭제권 행사 시 CI와 함께 파기한다.
     */
    @Column(name = "birth_year")
    private Integer birthYear;

    /**
     * 성별 — {@code MALE} · {@code FEMALE}. 출생연도와 같은 한 글자(주민번호 성별세대코드)에서
     * 파생하고, 정본이 이미 수집항목으로 적고 있다(`legal/privacy-policy.md` 33조).
     *
     * <p>쓰는 곳은 <b>admin 의 행태 통계</b>뿐이다 — GA4 의 인구통계 보고서에 해당한다.
     * 판단(추천·판정)에는 쓰지 않는다. 성별로 금융상품을 가르면 그 자체가 차별이 된다.
     *
     * <p>미연동이거나 성별 도입 전에 인증한 사람은 null 이고, 통계는 '미상'으로 센다.
     * 삭제권 행사 시 CI·출생연도와 함께 파기한다.
     */
    @Column(name = "gender", length = 10)
    private String gender;

    /**
     * 이 사용자가 정한 하루 알림 상한. <b>0이면 '설정 안 함'</b>이고 전역 기본값을 따른다.
     *
     * <p>전역값(`finntech.guardian.notification.daily-push-limit`)은 운영이 정하는 기본값이고,
     * "나한테는 많다/적다"는 사람마다 다르다. 사람마다 다른 값을 설정 파일에 둘 수는 없다.
     */
    @Column(name = "notify_daily_limit", nullable = false)
    private int notifyDailyLimit = 0;

    protected AppUser() {}

    public AppUser(String nickname, BigDecimal monthlyIncome, BigDecimal goalAmount, Integer goalMonths) {
        this.nickname = nickname;
        this.monthlyIncome = monthlyIncome;
        this.goalAmount = goalAmount;
        this.goalMonths = goalMonths;
    }

    public Long getId() { return id; }
    public String getNickname() { return nickname; }
    /** 본인인증으로 확인된 이름을 계정 이름으로 쓴다 — 화면이 '○○님'이라 부를 근거다. */
    public void setNickname(String nickname) { this.nickname = nickname; }
    public int getNotifyDailyLimit() { return notifyDailyLimit; }
    public void setNotifyDailyLimit(int v) { this.notifyDailyLimit = v; }
    public BigDecimal getMonthlyIncome() { return monthlyIncome; }
    public void setMonthlyIncome(BigDecimal v) { this.monthlyIncome = v; }
    public BigDecimal getGoalAmount() { return goalAmount; }
    public void setGoalAmount(BigDecimal v) { this.goalAmount = v; }
    public Integer getGoalMonths() { return goalMonths; }
    public void setGoalMonths(Integer v) { this.goalMonths = v; }
    public boolean isConsentGiven() { return consentGiven; }
    public void setConsentGiven(boolean v) { this.consentGiven = v; }
    public boolean isRealPerson() { return realPerson; }
    public void setRealPerson(boolean v) { this.realPerson = v; }
    public String getCi() { return ci; }
    public void setCi(String v) { this.ci = v; }
    public Integer getBirthYear() { return birthYear; }
    public void setBirthYear(Integer v) { this.birthYear = v; }
    public String getGender() { return gender; }
    public void setGender(String v) { this.gender = v; }
}
