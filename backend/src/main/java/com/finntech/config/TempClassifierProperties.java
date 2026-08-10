package com.finntech.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>임시 분류기</b> 설정 — 무료 추론 API 로 미분류 결제에 <i>잠정</i> 업종을 붙인다.
 *
 * <p><b>왜 두 번째 모델인가.</b> 유료 모델(Gemini)은 40곳이 쌓여야 부른다 — 프롬프트의 76%가
 * 업종 목록이라 1곳씩 부르면 40배가 들기 때문이다. 그런데 그동안 새 결제는 '카테고리없음'으로
 * 남는다. 무료 모델은 그 값이 0 이라 <b>결제가 들어오는 대로</b> 물어도 손해가 없다.
 *
 * <p><b>느려도 된다.</b> 이 통로는 동기화(5분 주기)에서 백그라운드로 돈다. 사용자가 기다리지
 * 않으므로 한 번에 30초가 걸려도 무방하다 — 앱을 열 때는 이미 끝나 있다. 실측에서 40곳
 * 한 묶음에 28초였다(2026-08-07).
 *
 * <p><b>DB 에 넣지 않는다.</b> 사전({@code merchant_category})에는 유료 모델의 답과 사람의
 * 확정만 들어간다. 여기 답은 <b>메모리에만</b> 두고 화면 표시에만 쓴다. 두 모델의 답을 한
 * 사전에 섞으면 "어느 쪽이 넣은 값인가"가 판정·리포트까지 번지고, 무료 통로가 막히는 날
 * 사전의 절반이 근거를 잃는다.
 *
 * <p><b>기본은 꺼져 있다.</b> 주소·키·모델을 환경변수로 받으며 하나라도 비면 조용히 꺼진 채
 * 있고 분류는 종전대로 흐른다 — 등록 업종 조회와 같은 규칙이다.
 */
@ConfigurationProperties(prefix = "finntech.temp-classifier")
public class TempClassifierProperties {

    private boolean enabled = false;

    /** OpenAI 호환 chat completions 주소. 비면 꺼진다. */
    private String baseUrl = "";

    /** API 키. 비면 꺼진다. */
    private String apiKey = "";

    /**
     * 모델 이름. <b>환경변수로 받는다</b> — 목록에 있는데 부르면 404 인 모델이 실재한다
     * (2026-08-07 실측: 후보 7개 중 2개). 배포 없이 갈아탈 수 있어야 한다.
     */
    private String model = "";

    /** 한 번 부르는 데 이만큼 넘게 걸리면 포기한다. 백그라운드라 넉넉히 준다. */
    private int timeoutMs = 90_000;

    /**
     * 한 <b>묶음</b>에 담는 가맹점 수 — 회차당 상한이 아니다.
     *
     * <p>회차당 상한은 두지 않는다. 무료 통로라 아낄 것이 없고, 자르면 그만큼이 다음 회차
     * (5분 뒤)로 밀려 미분류가 오래 남는다. 유료 통로가 40곳씩 묶는 것과 혼동하기 쉬운데,
     * 그쪽은 프롬프트의 76%가 업종 목록이라 묶어야 값이 싸지는 것이고 여기는 그 이유가 없다.
     */
    private int maxPerRun = 40;

    /**
     * 연속 실패가 이만큼 쌓이면 <b>이 프로세스가 사는 동안</b> 스스로 끈다.
     *
     * <p>무료 통로는 어느 날 크레딧이 떨어지거나 막힐 수 있다. 그때 5분마다 헛되이 두드리면
     * 로그만 더럽히고 동기화도 그만큼 느려진다. 재기동하면 다시 시도한다 — 영구 차단이 아니다.
     */
    private int failureCutoff = 5;

    /** 메모리 캐시 보관 시간(분). 지나면 다시 묻는다. 재기동하면 어차피 비므로 길게 잡지 않는다. */
    private int cacheMinutes = 720;

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }

    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }

    public int getMaxPerRun() { return maxPerRun; }
    public void setMaxPerRun(int maxPerRun) { this.maxPerRun = maxPerRun; }

    public int getFailureCutoff() { return failureCutoff; }
    public void setFailureCutoff(int failureCutoff) { this.failureCutoff = failureCutoff; }

    public int getCacheMinutes() { return cacheMinutes; }
    public void setCacheMinutes(int cacheMinutes) { this.cacheMinutes = cacheMinutes; }

    /** 부를 수 있는 상태인가 — 넷 다 있어야 한다. */
    public boolean usable() {
        return enabled && !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }
}
