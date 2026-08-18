package com.finntech.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 한 번의 이용 — 그동안 변하지 않는 것만 담는다 (V35).
 *
 * <p>유입 경로·브라우저·OS·기기 종류·해상도·언어·시간대는 세션이 열릴 때 정해지고 닫힐 때까지
 * 그대로다. 이벤트 줄마다 되풀이하면 가장 빨리 자라는 표가 더 빨리 자란다. GA4 도 이 축들을
 * <b>세션 범위 차원</b>으로 둔다.
 *
 * <p><b>세터를 칸마다 만들지 않는다.</b> 세션은 열릴 때 한 번 쓰고 다시 안 고친다 —
 * 생성자로만 만들면 "어떤 칸을 갱신하다 빠뜨렸다"가 애초에 생기지 않는다.
 */
@Entity
@Table(name = "usage_session")
public class UsageSession {

    /** 유입 경로의 갈래 — {@code source}·{@code medium} 에서 서버가 판정한다. */
    public enum Channel { DIRECT, REFERRAL, ORGANIC, SOCIAL, INTERNAL }

    @Id
    @Column(name = "session_id", length = 36, nullable = false)
    private String sessionId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "started_at", nullable = false)
    private LocalDateTime startedAt;

    @Column(name = "channel", length = 20, nullable = false)
    private String channel = Channel.DIRECT.name();

    @Column(name = "source", length = 60)
    private String source;

    @Column(name = "medium", length = 40)
    private String medium;

    @Column(name = "campaign", length = 60)
    private String campaign;

    @Column(name = "referrer_host", length = 100)
    private String referrerHost;

    @Column(name = "device_category", length = 10)
    private String deviceCategory;

    @Column(name = "browser", length = 30)
    private String browser;

    @Column(name = "browser_version", length = 10)
    private String browserVersion;

    @Column(name = "os", length = 20)
    private String os;

    @Column(name = "os_version", length = 10)
    private String osVersion;

    @Column(name = "screen_size", length = 12)
    private String screenSize;

    @Column(name = "platform", length = 20)
    private String platform;

    @Column(name = "language", length = 20)
    private String language;

    @Column(name = "time_zone", length = 40)
    private String timeZone;

    @Column(name = "country", length = 2)
    private String country;

    protected UsageSession() {
    }

    public UsageSession(String sessionId, Long userId, LocalDateTime startedAt) {
        this.sessionId = sessionId;
        this.userId = userId;
        this.startedAt = startedAt;
    }

    /** 유입 경로. {@code channel} 은 부른 쪽이 판정해서 넘긴다 — 규칙을 두 벌로 적지 않는다. */
    public void acquisition(Channel channel, String source, String medium, String campaign,
                            String referrerHost) {
        this.channel = (channel == null ? Channel.DIRECT : channel).name();
        this.source = source;
        this.medium = medium;
        this.campaign = campaign;
        this.referrerHost = referrerHost;
    }

    public void device(String deviceCategory, String browser, String browserVersion,
                       String os, String osVersion, String screenSize, String platform) {
        this.deviceCategory = deviceCategory;
        this.browser = browser;
        this.browserVersion = browserVersion;
        this.os = os;
        this.osVersion = osVersion;
        this.screenSize = screenSize;
        this.platform = platform;
    }

    public void locale(String language, String timeZone, String country) {
        this.language = language;
        this.timeZone = timeZone;
        this.country = country;
    }

    public String getSessionId() { return sessionId; }
    public Long getUserId() { return userId; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public String getChannel() { return channel; }
    public String getSource() { return source; }
    public String getMedium() { return medium; }
    public String getCampaign() { return campaign; }
    public String getReferrerHost() { return referrerHost; }
    public String getDeviceCategory() { return deviceCategory; }
    public String getBrowser() { return browser; }
    public String getBrowserVersion() { return browserVersion; }
    public String getOs() { return os; }
    public String getOsVersion() { return osVersion; }
    public String getScreenSize() { return screenSize; }
    public String getPlatform() { return platform; }
    public String getLanguage() { return language; }
    public String getTimeZone() { return timeZone; }
    public String getCountry() { return country; }
}
