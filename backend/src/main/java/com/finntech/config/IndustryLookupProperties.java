package com.finntech.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 사업자등록번호로 <b>등록 업종</b>을 돌려주는 바깥 조회처 설정.
 *
 * <p><b>주소와 읽는 방법이 저장소에 없다.</b> 둘 다 환경변수로 들어오며 기본값은 빈 문자열이라,
 * 아무것도 주지 않으면 이 통로는 <b>꺼진 채로</b> 있고 분류는 종전대로 LLM 으로 내려간다.
 * 조회처는 우리가 계약한 곳이 아니라 공개 페이지를 읽는 것이므로, 어디를 어떻게 읽는지가
 * 코드에 박히면 그 페이지가 바뀔 때마다 저장소를 고쳐야 하고 배포처마다 다른 곳을 쓸 수도 없다.
 * 운영이 값을 쥐는 편이 맞다.
 *
 * <p><b>없어도 되는 통로다.</b> 조회가 실패하거나 느리거나 답이 대조표에 없으면 그냥 LLM 이 받는다
 * (분류 순위 ②-b → ③). 그래서 여기 있는 값들은 전부 "안 되면 만다"를 전제로 잡혀 있다 —
 * 짧은 제한시간, 연동 한 번당 상한, 실패 시 조용한 통과.
 */
@ConfigurationProperties(prefix = "finntech.industry-lookup")
public class IndustryLookupProperties {

    /** 꺼져 있으면 조회를 아예 시도하지 않는다. 주소가 비어도 마찬가지다. */
    private boolean enabled = false;

    /**
     * 조회 주소. {@code {businessNumber}} 자리에 <b>하이픈 있는</b> 사업자번호가 들어간다
     * (원장은 하이픈 없이 보관하므로 부르는 쪽에서 넣어 준다).
     */
    private String url = "";

    /**
     * 답에서 업종 이름을 뽑는 정규식. <b>1번 그룹</b>이 업종 이름이라야 한다.
     * 비어 있으면 조회하지 않는다 — 뽑는 법을 모르면 부를 이유가 없다.
     */
    private String pattern = "";

    /** 한 번 부르는 데 이만큼 넘게 걸리면 포기한다. 연동이 조회 때문에 느려지면 안 된다. */
    private int timeoutMs = 4000;

    /**
     * 연동 한 번에 물어볼 가맹점 수 상한. 첫 연동은 처음 보는 가맹점이 수십 곳이라
     * 상한이 없으면 조회처에 한꺼번에 몰린다. 넘친 것은 다음 연동에서 이어 받는다 —
     * 사전에 쌓이므로 이미 물어본 곳은 다시 묻지 않기 때문이다.
     */
    private int maxPerSync = 40;

    /** 연속 호출 사이에 두는 간격(ms). 남의 서버를 두드리는 일이라 예의가 필요하다. */
    private int delayMs = 300;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getMaxPerSync() { return maxPerSync; }
    public void setMaxPerSync(int maxPerSync) { this.maxPerSync = maxPerSync; }

    public int getDelayMs() { return delayMs; }
    public void setDelayMs(int delayMs) { this.delayMs = delayMs; }

    /** 부를 수 있는 상태인가 — 셋 다 있어야 한다. */
    public boolean usable() {
        return enabled && !url.isBlank() && !pattern.isBlank();
    }
}
