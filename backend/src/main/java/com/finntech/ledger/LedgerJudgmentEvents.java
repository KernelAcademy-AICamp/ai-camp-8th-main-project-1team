package com.finntech.ledger;

import com.finntech.engine.FixedGroup;
import com.finntech.ml.WasteScoringService;

import java.util.List;

/**
 * 판정이 <b>이미 낸 답</b>을 소비 원장에 알리는 통지 둘.
 *
 * <h2>왜 이런 모양인가</h2>
 *
 * <p>이 표의 첫 번째 원칙은 <b>표가 계산을 일으키지 않는다</b>는 것이다. 고정지출과 낭비는
 * 남이 제 필요로 판정할 때 답이 나오고, 표는 그 답을 옆에서 받아 적을 뿐이다. 그래서 표가
 * 판정기를 부르지 않고, 판정기가 답을 냈다고 알린다.
 *
 * <p>통지에 <b>결제 명단 전체를 싣지 않는다.</b> 받는 쪽이 그 사용자의 줄을 어차피 읽으므로,
 * 통지에 없는 줄은 "판정 대상이 아니었다"로 그 자리에서 정할 수 있다. 사용자 하나에 결제가
 * 수만 건이라 명단을 실으면 통지가 그만큼 무거워진다.
 */
public final class LedgerJudgmentEvents {

    private LedgerJudgmentEvents() {}

    /**
     * 고정 결제 판정이 돌았다 — {@code RecurringPaymentDetector} 가 낸다.
     *
     * <p>{@code groups} 에 없는 결제는 <b>고정지출이 아니다</b>(모른다가 아니라). 판정이 그
     * 사용자의 전 기간을 보고 났으므로, 빠졌다는 것 자체가 답이다.
     */
    public record FixedGroupsDetected(Long userId, List<FixedGroup> groups) {}

    /**
     * 낭비 판정이 돌았다 — {@code WasteScoringService.scoreUser} 가 낸다.
     *
     * <p>{@code judgments} 에 없는 결제는 분류가 없어({@code 카테고리없음}·{@code 기타})
     * <b>판정 자체를 건너뛴 것</b>이다 — "낭비가 아니다"와 다르다.
     *
     * <p>모델이 꺼져 있으면({@code SpendingClassifier.isReady()} 가 거짓) 이 통지가 아예 안
     * 나온다. 모델이 꺼진 것은 사건이 아니다 — 어제의 판정이 틀려진 게 아니라 오늘 물어볼 수
     * 없을 뿐이라, 이미 적힌 답을 지우면 안 된다.
     */
    public record WasteJudged(Long userId, List<WasteScoringService.WasteJudgment> judgments,
                              double modelThreshold, String modelFingerprint) {}
}
