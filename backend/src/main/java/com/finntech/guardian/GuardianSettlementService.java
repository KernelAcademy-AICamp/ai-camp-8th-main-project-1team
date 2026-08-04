package com.finntech.guardian;

import com.finntech.guardian.domain.DailyVerdict;
import com.finntech.guardian.domain.GuardianChallenge;
import com.finntech.guardian.domain.GuardianEnums.ChallengeState;
import com.finntech.guardian.domain.GuardianEnums.DailyResult;
import com.finntech.guardian.domain.GuardianEnums.TxState;
import com.finntech.guardian.domain.GuardianTransaction;
import com.finntech.guardian.domain.RoomObject;
import com.finntech.guardian.repository.DailyVerdictRepository;
import com.finntech.guardian.repository.GuardianChallengeCategoryRepository;
import com.finntech.guardian.repository.GuardianChallengeRepository;
import com.finntech.guardian.repository.GuardianPointEventRepository;
import com.finntech.guardian.repository.GuardianTransactionRepository;
import com.finntech.guardian.repository.RoomObjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
    private final GuardianChallengeCategoryRepository challengeCategoryRepository;

    public GuardianSettlementService(GuardianChallengeRepository challengeRepository,
                                     GuardianTransactionRepository txRepository,
                                     DailyVerdictRepository verdictRepository,
                                     RoomObjectRepository roomObjectRepository,
                                     GuardianPointEventRepository pointEventRepository,
                                     GuardianChallengeCategoryRepository challengeCategoryRepository) {
        this.challengeRepository = challengeRepository;
        this.txRepository = txRepository;
        this.verdictRepository = verdictRepository;
        this.roomObjectRepository = roomObjectRepository;
        this.pointEventRepository = pointEventRepository;
        this.challengeCategoryRepository = challengeCategoryRepository;
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
     * 결과가 확정된 챌린지 한 줄 — <b>②가 ③에게 넘기는 이력</b>이다
     * (`07_취향분석및추천_Agent_설계.md` §11 · `02_에이전트_연결부.md` §8).
     *
     * <p>{@link SettlementView}를 그대로 쓰지 않는 이유는 그쪽이 포인트·수집물·카테고리별 결과까지
     * 담아 챌린지마다 조회를 여러 번 더 타기 때문이다. ③이 필요한 것은 <b>확정 지킨 돈과 종료일</b>뿐이다.
     */
    public record SettledChallenge(Long challengeId, LocalDate startDate, LocalDate endDate,
                                   long targetSaving, long securedSaving, double defenseRate) {}

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

        // 카테고리별 한도는 챌린지를 만들 때 저장한다(V13). 예전에는 전체 캡을 카테고리 수로
        // **균등분할**했는데, 온보딩에서 배달 50%·카페 20%처럼 다르게 정해도 화면은 같은 값을
        // 보여줬다. 이제 사용자가 정한 그대로다.
        //
        // 옛 챌린지(V13 이전)에는 행이 없을 수 있다 — 그때는 예전처럼 균등분할해 값이 바뀌지 않게 한다.
        Map<String, Long> capBy = new HashMap<>();
        for (var cc : challengeCategoryRepository.findByChallenge(ch.getId())) {
            capBy.put(cc.getCategory(), cc.getCap());
        }
        long evenCap = cats.isEmpty() ? 0 : ch.getChallengeCap() / cats.size();
        List<CategoryResult> results = new ArrayList<>();
        for (String c : cats) {
            long spent = spentBy.getOrDefault(c, 0L);
            long cap = capBy.getOrDefault(c, evenCap);
            long kept = Math.max(0, cap - spent);
            results.add(new CategoryResult(c, cap, spent, kept,
                    cap == 0 ? 0.0 : (double) kept / cap));
        }

        // '지킨 날' = 무지출이거나 페이스를 지킨 날. OFF_PACE(초과)와 NO_GRANT(미판정)는 뺀다.
        List<DailyVerdict> verdicts = verdictRepository.findByChallenge(ch.getId());
        int keptDays = (int) verdicts.stream()
                .filter(v -> v.getResult() == DailyResult.NO_SPEND_DAY
                        || v.getResult() == DailyResult.ON_PACE_DAY).count();

        // **확보 절약액은 직접 세지 않는다**(마스터 §4 원칙 2 — 서비스는 재계산하지 않는다).
        // `한도 − 지출`로 셌더니 방어율이 200%가 나왔다: 한도(444,992)는 쓸 수 있는 돈이고
        // 목표(222,495)는 아껴야 할 돈이라 단위가 다르다. 규칙이 쓰는 식은
        // `min(목표, 기준지출 − 지출)`이고, 그래야 100%를 넘지 않는다.
        long secured = Math.min(ch.getTargetSaving(),
                Math.max(0L, ch.getBaselineAmount() - ch.getSpentAmount()));
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

    /**
     * <b>결과가 확정된</b> 챌린지 이력 — ③이 규모(`kept_mean`)와 월간 회고에 쓴다
     * (`07_취향분석및추천_Agent_설계.md` §8 · §11의 요청분).
     *
     * <p><b>왜 ②가 내주나.</b> 확보 절약액은 저장된 컬럼이 아니라 `min(목표, 기준지출 − 지출)` 계산값이다.
     * ③이 저장소를 직접 읽어 스스로 세면 R10(③은 ②의 금액을 재계산하지 않는다) 위반이고, 식이 갈라지면
     * 화면끼리 숫자가 어긋난다. 그래서 <b>규칙을 가진 쪽이 계산해서 넘긴다.</b>
     *
     * <p><b>진행 중·정산 중·중도 포기는 뺀다.</b> 확정되지 않은 금액은 이력이 아니고, 포기한 챌린지는
     * 기간을 다 채우지 않아 월 납입 규모의 표본이 될 수 없다. {@code SETTLING}도 아직 확정 전이다.
     *
     * <p>조회는 한 번이고 계산은 엔티티에 있는 값만 쓴다 — {@link #settle}처럼 거래를 다시 훑지 않는다.
     * 최신순(저장소 정렬 그대로)이라 같은 입력이 같은 순서를 낸다(설계원칙 3).
     */
    @Transactional(readOnly = true)
    public List<SettledChallenge> history(Long userId) {
        return challengeRepository.findByUserIdOrderByIdDesc(userId).stream()
                .filter(ch -> FINALIZED_STATES.contains(ch.getState()))
                .map(GuardianSettlementService::toSettled)
                .toList();
    }

    /** 결과가 확정된 상태들. 진행 중(SETUP·ACTIVE·AT_RISK·EXCEEDED)·SETTLING·ABANDONED는 뺀다. */
    private static final Set<ChallengeState> FINALIZED_STATES = EnumSet.of(
            ChallengeState.SUCCESS, ChallengeState.PARTIAL, ChallengeState.SHORTFALL,
            ChallengeState.FAILED, ChallengeState.REWARD_PENDING,
            ChallengeState.RESTART_OFFER, ChallengeState.CLOSED);

    /**
     * 확보 절약액은 {@link #settle}과 <b>같은 식</b>이다 — `min(목표, max(0, 기준지출 − 지출))`.
     * 두 곳이 다른 식을 쓰면 결산 화면과 추천이 다른 숫자를 말하게 된다.
     *
     * <p>이 식은 결과를 {@code [0, 목표]}로 가둔다. 환불로 지출이 음수가 되어도 목표를 넘지 않고,
     * 초과 지출이 커도 0 아래로 안 간다 — §8.1이 걱정한 `취소·환불이 kept_mean을 흔든다`는
     * 여기서 상한·하한으로 막힌다.
     */
    private static SettledChallenge toSettled(GuardianChallenge ch) {
        long secured = Math.min(ch.getTargetSaving(),
                Math.max(0L, ch.getBaselineAmount() - ch.getSpentAmount()));
        return new SettledChallenge(ch.getId(), ch.getStartDate(), ch.getEndDate(),
                ch.getTargetSaving(), secured,
                ch.getTargetSaving() == 0 ? 0.0 : (double) secured / ch.getTargetSaving());
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
            RenewalLine line = suggest(r);
            lines.add(line);
            total += line.suggestedCap();
        }
        // 다음 달 저금 목표 = 기준 지출 − 새 한도 합. 한도를 내렸으면 저금 목표도 함께 내려간다.
        long suggestedSaving = Math.max(0, ch.getBaselineAmount() - total);
        return new RenewalView(lines, suggestedSaving, csv(ch.getSanctuaryCategories()));
    }

    /**
     * 카테고리 하나의 다음 달 한도를 정한다 — <b>순수 함수</b>.
     *
     * <p>규칙을 여기 한 곳에 모아 둔다. 잘 지켰으면 유지하고, 못 지켰으면 <b>실제로 쓴 만큼</b>에
     * 여유를 얹어 내린다. 올리지는 않는다: 성공했다고 더 조이면 성공이 벌이 된다.
     *
     * <p>한 번에 너무 깎지 않는 하한({@link #LOWER_FLOOR})을 두는 이유는, 한 달 크게 무너진 사람의
     * 목표가 반토막 나면 챌린지가 의미를 잃기 때문이다. 만원 단위로 다듬는 것은 화면에
     * `104,500원` 같은 값이 뜨면 사람이 정한 목표로 안 읽히기 때문이다.
     */
    static RenewalLine suggest(CategoryResult r) {
        if (r.rate() >= KEEP_THRESHOLD) {
            return new RenewalLine(r.category(), r.cap(), r.cap(), "KEEP", r.rate(), "이 페이스가 딱 좋아요");
        }
        long fromActual = Math.round(r.spent() * LOWER_HEADROOM);
        long suggested = Math.max(Math.round(r.cap() * LOWER_FLOOR), fromActual);
        suggested = Math.min(suggested, r.cap());                 // 올리지는 않는다
        suggested = Math.round(suggested / 10_000.0) * 10_000L;   // 만원 단위
        boolean lower = suggested < r.cap();
        return new RenewalLine(r.category(), r.cap(), suggested, lower ? "LOWER" : "KEEP", r.rate(),
                lower ? "목표가 조금 빡셌어요 — 현실에 맞게 낮췄어요" : "이 정도면 지킬 수 있어요");
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
