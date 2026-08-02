package com.finntech.guardian;

import java.text.NumberFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 지킴이가 사용자에게 하는 <b>모든 말</b>을 한 곳에 모은다 (설계서 §5·§3.4.1).
 *
 * <p><b>※ 문구 출처 주의.</b> 설계서 v1.2 원본의 한글이 전달 과정에서 인코딩이 깨져
 * (UTF-8 3바이트 중 0x80~0x9F 구간이 유실) 원문을 복원할 수 없었다. 그래서 아래 문구는
 * 설계서에서 <b>복원 가능했던 규칙</b>(케이스별 톤·화법·사용 변수·길이 제한)에 맞춰 새로 썼다.
 * 원문이 확보되면 이 클래스의 문자열만 갈아끼우면 된다 — 로직은 문구를 모른다.
 *
 * <p><b>화법(§5).</b>
 * <ul>
 *   <li>TENTATIVE — 24시간 안에 되돌릴 수 있는 결제라 아직 확정이 아니다. 일어난 일은
 *       '결제 사실'까지만 말하고, 결과 숫자는 조건부로 감싼다.
 *       ○ "챌린지에 넣으면 118,000원 남아요" / ✗ "118,000원 남았어요"</li>
 *   <li>DEFINITIVE — 이미 확정된 사실이라 조건부로 쓰지 않는다. 알려주기만 한다.</li>
 * </ul>
 *
 * <p><b>금지.</b> 또·역시·이번에도·낭비·충동적·참으세요·안 됩니다·실패·포기·이러다·습관을 고쳐야 ·
 * 사용자(사람)를 평가하는 말. 지적은 '패턴'에 대해서만 한다.
 */
public final class GuardianCopy {

    private GuardianCopy() {}

    /** v1.3: C3·C6에서 카테고리를 "한도"에서 떼고 "어디서 샜는지" 문장으로 옮겼다(2026-08-02). */
    public static final String PROMPT_VERSION = "v1.3.0";
    public static final int MAX_TITLE_LEN = 20;
    /** v1.2: 조건부 표현이 길어져 80 → 90. */
    public static final int MAX_BODY_LEN = 90;

    // =====================================================================
    //  되돌리기 UX (설계서 §3.4.1)
    // =====================================================================

    /**
     * "이 소비 아니었어요"는 사용자가 지킴이를 반박하는 프레임이라 쓰지 않는다.
     * 챌린지 대상이 아니라고 <b>알려주는</b> 쪽으로 뒤집는다.
     */
    public static final String BUTTON_NOT_MINE = "챌린지랑 상관없어요";
    public static final String BUTTON_EXEMPTION = "면제권 쓸게요";
    public static final String UNDO_EXPIRED = "이 결제는 이미 반영됐어요";

    public static String undoToast(long remainingCap) {
        return "알려줘서 고마워요. 한도를 " + won(remainingCap) + "원으로 되돌렸어요.";
    }

    public static String pendingBadge(int count) {
        return "판정 대기 " + count + "건";
    }

    // =====================================================================
    //  고정구 — 반복 금지 대상에서 빼야 하는 표현 (설계서 §5)
    // =====================================================================
    // GuardianRules.FIXED_PHRASES 가 정본이며, 여기서는 문장을 만들 때 참고만 한다.

    // =====================================================================
    //  정적 폴백 (설계서 §5.4)
    // =====================================================================

    /**
     * LLM은 맨 마지막에 붙인다. 먼저 이 문구들로 전체 흐름을 완성하면 API 비용도 안 들고
     * 디버깅도 훨씬 쉽다. LLM이 실패해도 여기로 조용히 떨어져 시연이 죽지 않는다.
     *
     * @param caseId C1..C14 · W1 · M1
     * @param v      템플릿 변수. 없는 키는 빈 문자열로 나간다.
     */
    public static String fallback(String caseId, Map<String, Object> v) {
        return switch (caseId) {
            // TENTATIVE — 되돌릴 수 있으므로 결과를 조건부로
            case "C1" -> s(v, "category") + " " + money(v, "amount") + "원 결제가 들어왔어요. "
                    + "챌린지에 넣으면 한도는 " + money(v, "remaining") + "원 남아요.";
            case "C2" -> "이번 주 들어온 " + s(v, "category") + " 결제가 " + s(v, "count") + "번째예요.";
            /* 카테고리를 "한도"에 붙이지 않는다. 여기 쓰는 remaining·cap은 <b>합계</b> 기준인데
               예전 문안은 "식비 한도의 80%"라고 읽혀, 방금 결제의 꼬리표를 카테고리 한도처럼 말했다.
               카테고리별 한도가 실제로 생긴 뒤로는(tech_log §8-T) 홈의 "식비 198%"와 정면충돌한다.
               카테고리는 지우지 않고 <b>어디서 새는지 지목</b>하는 자리로 옮겼다. (2026-08-02) */
            case "C3" -> "이 결제까지 넣으면 한도의 80%예요. "
                    + "남는 건 " + money(v, "remaining") + "원이에요."
                    + leak(v);
            case "C8" -> "오늘 " + s(v, "category") + " 결제가 " + s(v, "count") + "건, "
                    + "합쳐서 " + money(v, "total") + "원이에요.";

            // DEFINITIVE — 이미 확정된 사실
            case "C5" -> s(v, "days") + "일째 " + s(v, "category") + " 결제가 없어요.";
            case "C6" -> "한도 " + money(v, "cap") + "원을 넘었어요. "
                    + "지금까지 확보한 절약액은 " + money(v, "secured") + "원이에요."
                    + leak(v);
            case "C7" -> "방금 " + money(v, "amount") + "원 결제, 어떤 소비였어요?";
            case "C9" -> s(v, "weekday") + " " + s(v, "timeRange") + "네요. "
                    + "지난 4주 중 " + s(v, "count") + "번은 이 시간에 " + s(v, "category") + "를 이용했어요.";
            case "C10" -> s(v, "daysLeft") + "일 남았고 한도는 " + money(v, "remaining") + "원 남았어요.";
            case "C11" -> s(v, "daysLeft") + "일 남았어요. 이번 회차는 여기까지고, "
                    + "확보한 절약액은 " + money(v, "secured") + "원이에요.";
            case "M1" -> "어제 잘 지키셔서 사물이 하나 도착했어요.";
            case "W1" -> "지난주 " + s(v, "category") + " 결제는 " + s(v, "count") + "건, "
                    + "남은 한도는 " + money(v, "remaining") + "원이에요.";
            default -> throw new IllegalArgumentException("폴백 문구가 없는 케이스: " + caseId);
        };
    }

    /** 케이스별 제목 — 20자 이내. */
    public static String fallbackTitle(String caseId, Map<String, Object> v) {
        String category = s(v, "category");
        return switch (caseId) {
            case "C1" -> trim(category + " 첫 결제");
            case "C2" -> trim(category + " 이번 주 " + s(v, "count") + "번째");
            // C3·C6의 한도는 합계 기준이다 — 제목에 카테고리를 붙이면 카테고리 한도로 읽힌다.
            case "C3" -> "한도 80%";
            case "C5" -> trim("무지출 " + s(v, "days") + "일째");
            case "C6" -> "한도 초과";
            case "C7" -> "이 결제 분류할까요";
            case "C8" -> trim("오늘 " + category + " 잔돈");
            case "C9" -> trim(category + " 시간대예요");
            case "C10", "C11" -> trim(s(v, "daysLeft") + "일 남았어요");
            case "M1" -> "사물이 도착했어요";
            case "W1" -> "지난주 기록";
            default -> "지킴이";
        };
    }

    /** 문장에서 뽑은 특징 표현 — 반복 감지의 재료. 고정구는 호출부가 걸러낸다. */
    public static List<String> fallbackKeyPhrases(String caseId) {
        return switch (caseId) {
            case "C2" -> List.of("이번 주", "번째예요");
            case "C3" -> List.of("한도의 80%", "남는 건");
            case "C5" -> List.of("일째", "결제가 없어요");
            case "C6" -> List.of("한도를 넘었어요", "확보한 절약액");
            case "C8" -> List.of("합쳐서");
            case "C9" -> List.of("지난 4주 중", "이 시간에");
            case "C10", "C11" -> List.of("일 남았고");
            case "W1" -> List.of("지난주", "남은 한도");
            default -> List.of();
        };
    }

    // =====================================================================
    //  헬퍼
    // =====================================================================

    private static String trim(String s) {
        return s.length() <= MAX_TITLE_LEN ? s : s.substring(0, MAX_TITLE_LEN);
    }

    private static String s(Map<String, Object> v, String key) {
        Object o = v == null ? null : v.get(key);
        return o == null ? "" : String.valueOf(o);
    }

    /**
     * "어디서 새고 있는지" 한 문장 — 한도 이야기(C3·C6) 뒤에만 붙인다.
     *
     * <p>알림을 카테고리별로 <b>쪼개지 않기로</b> 한 자리를 이 문장이 메운다. 푸시는 하루 2건으로
     * 희소하고(`daily-push-limit`), 판정은 합계 기준이므로(tech_log §8-T) 카테고리마다 알리면
     * 예산만 놓고 다투다 대부분 침묵하고, 합계는 멀쩡한데 "초과" 알림이 나가기도 한다.
     * <b>알림 수를 늘리지 않고 정보만 늘리는</b> 방법이 이것이다 — 상세는 홈이 이미 보여준다.
     *
     * <p>{@code topCategory} 가 없으면(카테고리가 하나뿐이거나 집계가 비었으면) 아무것도 안 붙인다.
     * 지목할 것이 없는데 지목하는 문장은 군더더기다.
     *
     * <p>조사는 <b>받침에 무관한 것만</b> 쓴다. 카테고리 이름이 데이터에서 오므로
     * "식비<b>예요</b>"/"쇼핑<b>이에요</b>"를 가르려면 받침 판정이 필요한데, 그 헬퍼가 이 저장소에 없다.
     * {@code 에서} 는 받침과 무관하니 어떤 이름이 와도 문장이 안 깨진다 —
     * <b>없는 규칙을 지어내는 것보다 규칙이 필요 없는 표현을 고르는 편이 낫다.</b>
     */
    private static String leak(Map<String, Object> v) {
        String top = s(v, "topCategory");
        return top.isBlank() ? "" : " " + top + "에서 가장 많이 나갔어요.";
    }

    private static String money(Map<String, Object> v, String key) {
        Object o = v == null ? null : v.get(key);
        if (o == null) return "0";
        return won(((Number) o).longValue());
    }

    /** 13310544 → "13,310,544". 자릿수 구분이 없으면 사람이 읽지 못한다. */
    public static String won(long v) {
        return NumberFormat.getIntegerInstance(Locale.KOREA).format(v);
    }
}
