package com.finntech.guardian;

import com.finntech.guardian.domain.DailyVerdict;
import com.finntech.guardian.domain.GuardianChallenge;
import com.finntech.guardian.domain.GuardianEnums.DailyResult;
import com.finntech.guardian.domain.GuardianEnums.TxState;
import com.finntech.guardian.domain.GuardianTransaction;
import com.finntech.guardian.domain.RoomObject;
import com.finntech.guardian.repository.DailyVerdictRepository;
import com.finntech.guardian.repository.GuardianChallengeRepository;
import com.finntech.guardian.repository.GuardianPointEventRepository;
import com.finntech.guardian.repository.GuardianTransactionRepository;
import com.finntech.guardian.repository.RoomObjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 월간 결산 · 다음 달 갱신 (개편안 {@code s-settle} · {@code s-renew}).
 *
 * <p><b>왜 챌린지가 끝난 뒤에도 화면이 필요한가.</b> 30일을 버틴 사람에게 "끝났습니다"만 보여주면
 * 그 달의 노력이 숫자로 남지 않는다. 결산은 <b>무엇을 지켰는지</b>를 셈해 보여주고, 갱신은
 * <b>다음 달 목표를 지난달 실적에서 유도</b>한다.
 *
 * <p><b>목표를 낮추는 것이 후퇴가 아니다.</b> 달성률이 낮은 카테고리는 목표가 현실과 안 맞았던
 * 것이므로 내려 잡는다 — 못 지킬 목표를 그대로 두면 사람이 그만둔다. 반대로 잘 지킨 카테고리는
 * 유지한다. 올리지는 않는다: 성공했다고 더 조이면 성공이 벌이 된다.
 */
@Service
public class GuardianSettlementService {

    /** 이 달성률 이상이면 목표를 유지한다. 미만이면 실제 지출에 맞춰 내린다. */
    private static final double KEEP_THRESHOLD = 0.85;
    /** 목표를 내릴 때 실제 지출에 주는 여유. 딱 실적에 맞추면 첫날부터 빠듯하다. */
    private static final double LOWER_HEADROOM = 1.10;
    /** 한 번에 내리는 폭의 하한 — 목표가 반토막 나면 챌린지가 의미를 잃는다. */
    private static final double LOWER_FLOOR = 0.60;
    /** 완주 보너스 포인트. */
    private static final int COMPLETION_BONUS = 100;

    private final GuardianChallengeRepository challengeRepository;
    private final GuardianTransactionRepository txRepository;
    private final DailyVerdictRepository verdictRepository;
    private final RoomObjectRepository roomObjectRepository;
    private final GuardianPointEventRepository pointEventRepository;

    public GuardianSettlementService(GuardianChallengeRepository challengeRepository,
                                     GuardianTransactionRepository txRepository,
                                     DailyVerdictRepository verdictRepository,
                                     RoomObjectRepository roomObjectRepository,
                                     GuardianPointEventRepository pointEventRepository) {
        this.challengeRepository = challengeRepository;
        this.txRepository = txRepository;
        this.verdictRepository = verdictRepository;
        this.roomObjectRepository = roomObjectRepository;
        this.pointEventRepository = pointEventRepository;
    }

    // ======================================================================
    //  DTO
    // ======================================================================

    /**
     * 카테고리 한 줄의 성적.
     *
     * @param cap     그 카테고리에 걸었던 한도
     * @param spent   실제 쓴 금액
     * @param kept    지켜낸 금액(한도 − 지출, 음수면 0)
     * @param rate    달성률 = kept / cap
     */
    public record CategoryResult(String category, long cap, long spent, long kept, double rate) {}

    public record SettlementView(Long challengeId, LocalDate startDate, LocalDate endDate,
                                 long targetSaving, long securedSaving, double defenseRate,
                                 List<CategoryResult> categories,
                                 int keptDays, int bestStreak, int pointsEarned,
                                 int objectsCollected, int completionBonus) {}

    /**
     * 다음 달 조정안 한 줄.
     *
     * @param action KEEP(유지) · LOWER(하향). 올리는 선택지는 두지 않는다
     */
    public record RenewalLine(String category, long currentCap, long suggestedCap,
                              String action, double lastRate, String reason) {}

    public record RenewalView(List<RenewalLine> lines, long suggestedTargetSaving,
                              List<String> sanctuaries) {}

    // ======================================================================
    //  결산
    // ======================================================================

    @Transactional(readOnly = true)
    public SettlementView settle(Long userId) {
        GuardianChallenge ch = latest(userId);
        List<String> cats = csv(ch.getCategories());

        // 카테고리별 지출 — 확정(COUNTED)만 센다. 되돌린 결제는 판정에 들어가지 않았다.
        Map<String, Long> spentBy = new LinkedHashMap<>();
        for (String c : cats) spentBy.put(c, 0L);
        for (GuardianTransaction t : txRepository.findByChallenge(ch.getId())) {
            if (t.getState() != TxState.COUNTED) continue;
            String c = t.getCategory();
            if (c != null && spentBy.containsKey(c)) spentBy.merge(c, t.getAmount(), Long::sum);
        }

        // 한도는 챌린지 전체 캡을 카테고리 수로 나눈 값이다 — 카테고리별 캡을 따로 저장하지 않는다.
        // (사용자는 "배달·카페를 합쳐 얼마"로 정하고, 화면이 나눠 보여준다.)
        long perCap = cats.isEmpty() ? 0 : ch.getChallengeCap() / cats.size();
        List<CategoryResult> results = new ArrayList<>();
        for (String c : cats) {
            long spent = spentBy.getOrDefault(c, 0L);
            long kept = Math.max(0, perCap - spent);
            results.add(new CategoryResult(c, perCap, spent, kept,
                    perCap == 0 ? 0.0 : (double) kept / perCap));
        }

        // '지킨 날' = 무지출이거나 페이스를 지킨 날. OFF_PACE(초과)와 NO_GRANT(미판정)는 뺀다.
        List<DailyVerdict> verdicts = verdictRepository.findByChallenge(ch.getId());
        int keptDays = (int) verdicts.stream()
                .filter(v -> v.getResult() == DailyResult.NO_SPEND_DAY
                        || v.getResult() == DailyResult.ON_PACE_DAY).count();

        long secured = Math.max(0, ch.getChallengeCap() - ch.getSpentAmount());
        int points = pointEventRepository.sumAll(userId);
        int objects = (int) roomObjectRepository.findByUser(userId).stream()
                .filter(o -> !o.getAcquiredDate().isBefore(ch.getStartDate())
                        && !o.getAcquiredDate().isAfter(ch.getEndDate()))
                .map(RoomObject::getObjectId).distinct().count();

        return new SettlementView(ch.getId(), ch.getStartDate(), ch.getEndDate(),
                ch.getTargetSaving(), secured,
                ch.getTargetSaving() == 0 ? 0.0 : (double) secured / ch.getTargetSaving(),
                results, keptDays, ch.getNoSpendStreakBest(), points, objects, COMPLETION_BONUS);
    }

    // ======================================================================
    //  다음 달 갱신
    // ======================================================================

    @Transactional(readOnly = true)
    public RenewalView renewal(Long userId) {
        SettlementView s = settle(userId);
        GuardianChallenge ch = latest(userId);

        List<RenewalLine> lines = new ArrayList<>();
        long total = 0;
        for (CategoryResult r : s.categories()) {
            long suggested;
            String action, reason;
            if (r.rate() >= KEEP_THRESHOLD) {
                suggested = r.cap();
                action = "KEEP";
                reason = "이 페이스가 딱 좋아요";
            } else {
                // 실제로 쓴 만큼에 여유를 얹는다. 다만 한 번에 너무 깎지는 않는다.
                long fromActual = Math.round(r.spent() * LOWER_HEADROOM);
                suggested = Math.max(Math.round(r.cap() * LOWER_FLOOR), fromActual);
                suggested = Math.min(suggested, r.cap());      // 올리지는 않는다
                suggested = Math.round(suggested / 10_000.0) * 10_000L;   // 만원 단위로 다듬는다
                action = suggested < r.cap() ? "LOWER" : "KEEP";
                reason = action.equals("LOWER")
                        ? "목표가 조금 빡셌어요 — 현실에 맞게 낮췄어요"
                        : "이 정도면 지킬 수 있어요";
            }
            lines.add(new RenewalLine(r.category(), r.cap(), suggested, action, r.rate(), reason));
            total += suggested;
        }
        // 다음 달 저금 목표 = 기준 지출 − 새 한도 합. 한도를 내렸으면 저금 목표도 함께 내려간다.
        long suggestedSaving = Math.max(0, ch.getBaselineAmount() - total);
        return new RenewalView(lines, suggestedSaving, csv(ch.getSanctuaryCategories()));
    }

    // ======================================================================
    //  내부
    // ======================================================================

    private GuardianChallenge latest(Long userId) {
        List<GuardianChallenge> all = challengeRepository.findByUserIdOrderByIdDesc(userId);
        if (all.isEmpty()) throw new IllegalStateException("아직 챌린지가 없어요");
        return all.get(0);
    }

    /** CSV 카테고리 문자열을 목록으로. 빈 값·공백은 버린다. */
    static List<String> csv(String s) {
        if (s == null || s.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String p : s.split(",")) {
            String t = p.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }
}
