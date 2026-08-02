package com.finntech.web;

import com.finntech.guardian.GuardianCopy;
import com.finntech.guardian.GuardianNarrative;
import com.finntech.guardian.domain.GuardianEnums.DeliveryKind;
import com.finntech.guardian.repository.GuardianNotificationRepository;
import com.finntech.ml.SpendingClassifier;
import com.finntech.ml.WasteScoringService;
import com.finntech.guardian.GuardianClock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>지금 무엇이 잘못되고 있는지 볼 수 있게 한다</b> (2026-08-02).
 *
 * <p><b>왜 필요한가.</b> 이 시스템은 자기 상태를 꽤 성실히 기록한다 — 침묵도 남기고
 * ({@code delivery=SILENT} + {@code suppressedReason}), LLM 폴백 여부도 남기고, 프롬프트
 * 버전도 단다. <b>그런데 그걸 보는 눈이 없었다.</b> 운영 중에 이런 질문에 답할 수 없었다:
 *
 * <ul>
 *   <li>알림의 몇 %가 <b>예산 때문에</b> 침묵했나 — {@code daily-push-limit: 2}가 적정한가?</li>
 *   <li>LLM 폴백률이 목표 5% 이하인가 (그 목표가 코드 주석에만 적혀 있었다)</li>
 *   <li>ML이 낭비로 보는 비율이 어제와 다른가 — 모델이 이상해진 걸 어떻게 아나?</li>
 * </ul>
 *
 * <p>{@code _archive/tech_log.md} §8-U가 배운 것과 같은 형태다 —
 * <b>재지 않으면 통과로 보인다.</b> 침묵은 로그에 있었지만 아무도 안 세었기 때문에
 * "알림이 잘 나가고 있다"와 "알림이 전부 막혀 있다"가 화면에서 똑같아 보였다.
 *
 * <p><b>개인 식별 정보를 내려주지 않는다.</b> 전부 집계 수치이고 사용자별로 쪼개지 않는다 —
 * 마스터 §4 원칙 1이 AI에 집계만 보내라고 한 것과 같은 이유로, 운영 지표도 집계면 충분하다.
 *
 * <p><b>기본은 꺼져 있다.</b> 개인 식별 정보는 안 나가지만 운영 내부 상태(알림 예산 소진률·
 * 모델 임계·프롬프트 버전)는 밖에 보일 이유가 없다. nginx는 {@code /api/} 아래를 경로별 구분 없이
 * 백엔드로 넘기므로, <b>여기 만든 매핑은 배포되는 순간 공개된다</b> — 운영에서 볼 때 명시적으로 켠다.
 */
@RestController
@RequestMapping("/api/ops")
@ConditionalOnProperty(name = "finntech.ops.enabled", havingValue = "true")
public class ObservabilityController {

    private final GuardianNotificationRepository notifications;
    private final WasteScoringService wasteScoringService;
    private final SpendingClassifier classifier;
    private final GuardianNarrative narrative;
    private final GuardianClock clock;

    public ObservabilityController(GuardianNotificationRepository notifications,
                                   WasteScoringService wasteScoringService,
                                   SpendingClassifier classifier, GuardianNarrative narrative,
                                   GuardianClock clock) {
        this.notifications = notifications;
        this.wasteScoringService = wasteScoringService;
        this.classifier = classifier;
        this.narrative = narrative;
        this.clock = clock;
    }

    /**
     * 최근 {@code days}일의 알림·판정 상태.
     *
     * @param days 되돌아볼 일수. 기본 7일 — 하루는 표본이 너무 적고, 30일은 어제 생긴 이상을 묻는다.
     */
    @GetMapping("/health")
    public Map<String, Object> health(@RequestParam(defaultValue = "7") int days) {
        LocalDateTime since = clock.now(null).minusDays(Math.max(1, days));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("windowDays", days);
        out.put("since", since);
        out.put("notification", notificationHealth(since));
        out.put("model", modelHealth());
        return out;
    }

    private Map<String, Object> notificationHealth(LocalDateTime since) {
        Map<String, Object> m = new LinkedHashMap<>();

        Map<String, Long> byDelivery = tally(notifications.countByDeliverySince(since));
        long silent = byDelivery.getOrDefault(DeliveryKind.SILENT.name(), 0L);
        long total = byDelivery.values().stream().mapToLong(Long::longValue).sum();
        long spoken = total - silent;

        m.put("total", total);
        m.put("spoken", spoken);
        m.put("silent", silent);
        // 침묵률 자체는 나쁜 게 아니다 — 안 보내기로 한 것도 결정이다(설계서 §4.1).
        // 봐야 할 것은 <b>왜</b> 침묵했나다. BUDGET이 크면 예산이 너무 좁다는 뜻이다.
        m.put("silentRatio", ratio(silent, total));
        m.put("silentBy", tally(notifications.countBySuppressedReasonSince(since)));
        m.put("byCase", tally(notifications.countByCaseSince(since)));

        long fallback = notifications.countFallbackSince(since);
        long spokenForFallback = notifications.countSpokenSince(since);
        Map<String, Object> llm = new LinkedHashMap<>();
        /* <b>AI가 켜져 있는지를 먼저 말한다.</b> 이게 없으면 폴백률 100%가 두 가지 정반대 상황을
           똑같이 가리킨다 — "키가 없어 애초에 안 불렀다"(정상·설계된 D-02 폴백)와
           "불렀는데 전부 실패했다"(운영 장애). 처음 이 지표를 만들 때 둘을 뭉갰고,
           로컬에서 폴백률 1.0을 보고 원인을 못 짚어 바로 드러났다(2026-08-02).
           <b>해석할 수 없는 지표는 없는 것보다 나쁘다</b> — 있으면 봤다고 착각하게 만든다. */
        llm.put("aiEnabled", narrative.aiEnabled());
        llm.put("fallback", fallback);
        llm.put("spoken", spokenForFallback);
        llm.put("fallbackRatio", ratio(fallback, spokenForFallback));
        // 목표 5% 이하 — 지금까지 코드 주석에만 있던 수치를 여기서 실제로 잰다.
        // AI가 꺼져 있으면 목표 자체가 무의미하므로 null을 준다(0.05와 비교하면 늘 위반으로 보인다).
        llm.put("targetRatio", narrative.aiEnabled() ? 0.05 : null);
        llm.put("note", narrative.aiEnabled()
                ? "폴백은 호출 실패·형식 오류를 뜻한다. 이유는 GuardianNarrative 의 WARN 로그에 남는다."
                : "AI 키가 없어 전부 고정 템플릿으로 나간다 — 설계된 폴백이지 장애가 아니다(D-02).");
        llm.put("promptVersion", GuardianCopy.PROMPT_VERSION);
        m.put("llm", llm);

        List<Map<String, Object>> feedback = new ArrayList<>();
        for (Object[] row : notifications.countByFeedbackSince(since)) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("feedback", str(row[0]));
            f.put("reason", str(row[1]));
            f.put("count", ((Number) row[2]).longValue());
            feedback.add(f);
        }
        m.put("feedback", feedback);
        return m;
    }

    private Map<String, Object> modelHealth() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ready", wasteScoringService.modelReady());
        // 임계는 F0.5로 고른 값이다(tech_log §8-Q). 배포된 모델이 그 값을 들고 있는지 눈으로 본다.
        m.put("threshold", classifier.isReady() ? classifier.threshold() : null);
        return m;
    }

    /** {@code [[키, 건수], ...]} → 정렬 고정 맵. 키가 null이면 "(없음)"으로 묶는다. */
    private static Map<String, Long> tally(List<Object[]> rows) {
        Map<String, Long> m = new TreeMap<>();
        for (Object[] r : rows) m.put(str(r[0]), ((Number) r[1]).longValue());
        return m;
    }

    private static String str(Object o) {
        return o == null ? "(없음)" : String.valueOf(o);
    }

    /** 0으로 나누지 않는다 — 표본이 없을 때 0.0을 주면 "폴백 0%"로 읽혀 오히려 오해를 만든다. */
    private static Double ratio(long part, long whole) {
        return whole == 0 ? null : Math.round(part * 10000.0 / whole) / 10000.0;
    }
}
