package com.finntech.guardian;

import com.finntech.guardian.domain.*;
import com.finntech.guardian.domain.GuardianEnums.*;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 지킴이 Agent REST API (설계서 §API 1~6).
 *
 * <p><b>프론트는 계산하지 않는다.</b> 남은 한도, 달성률, 며칠 남았는지, 사물을 줄지 말지는
 * 전부 서버가 계산해 완성된 값으로 내려준다. 프론트에서 한 번 더 계산하면 두 곳의 로직이
 * 조금씩 어긋나는데, 이런 프로젝트에서 제일 흔하고 제일 찾기 어려운 버그다.
 *
 * <p>인증은 이 저장소의 기존 방식({@code ?userId=})을 그대로 따른다. 설계서의 Supabase
 * 세션 쿠키·RLS는 이 백엔드 구조에 해당하지 않는다.
 */
@RestController
@RequestMapping("/api/guardian")
public class GuardianController {

    private final GuardianService guardianService;
    private final GuardianBatchService batchService;
    private final GuardianRewardService rewardService;
    private final GuardianCollectionService collectionService;
    private final GuardianCatalog catalog;
    private final GuardianSettlementService settlementService;
    private final GuardianClock clock;

    public GuardianController(GuardianService guardianService, GuardianBatchService batchService,
                              GuardianRewardService rewardService,
                              GuardianCollectionService collectionService,
                              GuardianCatalog catalog,
                              GuardianSettlementService settlementService, GuardianClock clock) {
        this.guardianService = guardianService;
        this.batchService = batchService;
        this.rewardService = rewardService;
        this.collectionService = collectionService;
        this.catalog = catalog;
        this.settlementService = settlementService;
        this.clock = clock;
    }

    // ======================================================================
    //  챌린지
    // ======================================================================

    public record CreateChallengeRequest(
            @NotNull List<String> categories,
            List<String> sanctuaryCategories,
            Long targetSaving,
            String rewardName,
            Long rewardPrice,
            Integer durationDays) {}

    @PostMapping("/challenges")
    public Map<String, Object> createChallenge(@RequestParam Long userId,
                                               @Valid @RequestBody CreateChallengeRequest req) {
        GuardianChallenge ch = guardianService.createChallenge(userId, req.categories(),
                req.sanctuaryCategories(), req.targetSaving(), req.rewardName(),
                req.rewardPrice(), req.durationDays());
        return Map.of("challenge", challengeView(ch),
                "snapshot", snapshotView(guardianService.snapshotOf(ch, clock.today(userId))));
    }

    // ======================================================================
    //  거래 수신 (설계서 §API 1)
    // ======================================================================

    public record TransactionRequest(
            LocalDateTime occurredAt,
            @NotNull String merchantName,
            String merchantDisplayName,
            @Positive long amount,
            String mcc,
            String category,
            Double categoryConfidence,
            TxType txType,
            boolean isDemo) {}

    /**
     * 거래가 들어왔을 때의 전체 흐름. 데모 모드의 "하루치 거래 주입" 버튼도 이걸 쓴다.
     *
     * <p>침묵일 때는 {@code notification: null}을 내려주되, 프론트는 이걸 오류로 취급하지 않아야 한다.
     * <b>침묵은 정상 동작이다.</b>
     */
    @PostMapping("/transactions")
    public Map<String, Object> ingest(@RequestParam Long userId, @Valid @RequestBody TransactionRequest req) {
        GuardianService.IngestResult r = guardianService.ingest(userId, new GuardianService.IngestCommand(
                req.occurredAt(), req.merchantName(), req.merchantDisplayName(), req.amount(),
                req.mcc(), req.category(), req.categoryConfidence(),
                req.txType() == null ? TxType.EXPENSE : req.txType(), req.isDemo(), null));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transaction", transactionView(r.transaction(), userId));
        out.put("snapshot", r.snapshot() == null ? null : snapshotView(r.snapshot()));
        out.put("state", r.state());
        out.put("notification", notificationView(r.notification()));
        return out;
    }

    public record UndoRequest(@NotNull UndoReason reason) {}

    @PostMapping("/transactions/{id}/undo")
    public Map<String, Object> undo(@RequestParam Long userId, @PathVariable Long id,
                                    @Valid @RequestBody UndoRequest req) {
        GuardianService.UndoResult r = guardianService.undo(userId, id, req.reason());
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("transaction", transactionView(r.transaction(), userId));
        out.put("snapshot", snapshotView(r.snapshot()));
        out.put("state", r.state());
        out.put("toast", r.toast());
        out.put("itemsHeld", itemsView(r.items()));
        return out;
    }

    public record ClassifyRequest(@NotNull String category, Double categoryConfidence) {}

    /** C7으로 되물은 결제에 사용자가 분류를 달아줬다 — 라벨링 포인트 대상. */
    @PostMapping("/transactions/{id}/category")
    public Map<String, Object> classify(@RequestParam Long userId, @PathVariable Long id,
                                        @Valid @RequestBody ClassifyRequest req) {
        GuardianService.IngestResult r = guardianService.classifyPending(
                userId, id, req.category(), req.categoryConfidence());
        return Map.of("transaction", transactionView(r.transaction(), userId),
                "snapshot", snapshotView(r.snapshot()),
                "state", r.state());
    }

    /** 마이데이터 투영에서 아직 원장에 없는 결제를 끌어온다. */
    @PostMapping("/sync")
    public Map<String, Object> sync(@RequestParam Long userId) {
        return Map.of("added", guardianService.syncFromMyData(userId));
    }

    // ======================================================================
    //  홈 (설계서 §API 3)
    // ======================================================================

    @GetMapping("/home")
    public Map<String, Object> home(@RequestParam Long userId) {
        GuardianService.HomeView h = guardianService.home(userId);
        GuardianRules.Snapshot s = h.snapshot();
        GuardianChallenge ch = h.challenge();

        Map<String, Object> strip = new LinkedHashMap<>();
        strip.put("remainingCapLabel", GuardianCopy.won(Math.max(0L, s.remainingCap())) + "원 남음");
        strip.put("pendingCount", h.pendingCount());
        strip.put("pendingBadge", h.pendingBadge());
        strip.put("noSpendStreak", ch.getNoSpendStreak());
        strip.put("grassStreak", ch.getGrassStreak());
        strip.put("pointBalance", h.items().getPointBalance());
        strip.put("unopenedCeremony", h.ceremony() != null);

        Map<String, Object> challenge = challengeView(ch);
        challenge.put("categoryLabel", h.categoryLabel());
        challenge.putAll(snapshotView(s));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("asOf", h.asOf());
        out.put("challenge", challenge);
        out.put("strip", strip);
        out.put("ceremony", ceremonyView(h.ceremony()));
        out.put("grass", h.grass().stream().map(g -> Map.of(
                "date", g.date(), "result", g.result(),
                "granted", g.granted(), "protected", g.protectedDay())).toList());
        out.put("itemsHeld", itemsView(h.items()));
        out.put("unreadNotifications", h.unreadNotifications());
        out.put("demoMode", h.demoMode());
        return out;
    }

    @PostMapping("/ceremony/{verdictId}/seen")
    public Map<String, Object> ceremonySeen(@RequestParam Long userId, @PathVariable Long verdictId) {
        guardianService.markCeremonySeen(userId, verdictId);
        return Map.of("ok", true);
    }

    @GetMapping("/room")
    public Map<String, Object> room(@RequestParam Long userId) {
        return roomView(rewardService.objects(userId));
    }

    /**
     * 방 상태를 표시 정보와 함께 내려준다.
     *
     * <p>사물 코드({@code plant_small_01})만 주면 화면이 이름을 못 붙인다. 프론트에 이름표를
     * 복사해 두면 소품을 추가할 때 두 곳을 고쳐야 하고 조용히 갈라진다 — 카탈로그가 한 곳이어야 한다.
     */
    private Map<String, Object> roomView(List<RoomObject> source) {
        List<Map<String, Object>> objects = new ArrayList<>();
        for (RoomObject o : source) {
            GuardianCatalog.Item item = catalog.find(o.getObjectId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("objectId", o.getObjectId());
            m.put("name", item.name());
            m.put("glyph", item.glyph());
            m.put("grade", o.getGrade());
            m.put("acquiredDate", o.getAcquiredDate());
            m.put("reasonCode", o.getReasonCode());
            m.put("slotIndex", o.getSlotIndex());
            objects.add(m);
        }
        return Map.of("objects", objects, "slotCount", RoomObject.SLOT_COUNT);
    }

    // ======================================================================
    //  배치 · 데모 (설계서 §API 4·6)
    // ======================================================================

    public record DailyBatchRequest(LocalDate targetDate) {}

    @PostMapping("/cron/daily")
    public Map<String, Object> runDaily(@RequestParam Long userId,
                                        @RequestBody(required = false) DailyBatchRequest req) {
        return batchResultView(batchService.runDaily(userId, req == null ? null : req.targetDate()));
    }

    public record AdvanceRequest(Integer days) {}

    /**
     * 데모: 가상 시계를 밀고 배치를 즉시 돌린다. 버튼 하나로 "다음 날 아침"이 재현된다.
     * 이게 없으면 30일 챌린지를 발표에서 보여줄 수 없다.
     */
    @PostMapping("/demo/advance")
    public Map<String, Object> advance(@RequestParam Long userId,
                                       @RequestBody(required = false) AdvanceRequest req) {
        int days = req == null || req.days() == null ? 1 : req.days();
        Map<String, Object> out = new LinkedHashMap<>();
        List<Map<String, Object>> batches = new ArrayList<>();
        for (int i = 0; i < days; i++) {
            LocalDateTime before = clock.now(userId);
            clock.advance(userId, 1);
            batches.add(batchResultView(batchService.runDaily(userId, before.toLocalDate())));
        }
        out.put("asOf", clock.now(userId));
        out.put("batches", batches);
        out.put("home", home(userId));
        return out;
    }

    // ======================================================================
    //  알림 (설계서 §API 5)
    // ======================================================================

    @GetMapping("/notifications")
    public Map<String, Object> notifications(@RequestParam Long userId) {
        return Map.of("notifications",
                guardianService.notifications(userId).stream().map(this::notificationView).toList());
    }

    public record FeedbackRequest(@NotNull Feedback feedback, FeedbackReason reason) {}

    @PostMapping("/notifications/{id}/feedback")
    public Map<String, Object> feedback(@RequestParam Long userId, @PathVariable Long id,
                                        @Valid @RequestBody FeedbackRequest req) {
        guardianService.feedback(userId, id, req.feedback(), req.reason());
        return Map.of("ok", true);
    }

    // ======================================================================
    //  뷰 변환 — 프론트가 다시 가공하지 않도록 완성된 값으로 내린다
    // ======================================================================

    private Map<String, Object> challengeView(GuardianChallenge ch) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", ch.getId());
        m.put("state", ch.getState());
        m.put("categories", ch.getCategorySet());
        // 성역도 함께 내려준다 — 소비 내역의 '성역' 필터가 이 목록으로 거른다.
        // 없으면 필터가 조용히 빈 결과를 내는데, 화면은 "성역 지출이 없다"로 읽어 버린다.
        m.put("sanctuaryCategories", ch.getSanctuarySet());
        m.put("baselineAmount", ch.getBaselineAmount());
        m.put("targetSaving", ch.getTargetSaving());
        m.put("challengeCap", ch.getChallengeCap());
        m.put("bufferRatio", ch.getBufferRatio());
        m.put("startDate", ch.getStartDate());
        m.put("endDate", ch.getEndDate());
        m.put("daysTotal", ch.getDaysTotal());
        m.put("rewardName", ch.getRewardName());
        m.put("rewardPrice", ch.getRewardPrice());
        return m;
    }

    private Map<String, Object> snapshotView(GuardianRules.Snapshot s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("spentAmount", s.spentAmount());
        m.put("remainingCap", s.remainingCap());
        m.put("spentRatio", round4(s.spentRatio()));
        m.put("securedSaving", s.securedSaving());
        m.put("achievementRate", round4(s.achievementRate()));
        m.put("daysElapsed", s.daysElapsed());
        m.put("daysLeft", s.daysLeft());
        m.put("paceRatio", round4(s.paceRatio()));
        m.put("allowedRatio", round4(s.allowedRatio()));
        return m;
    }

    private Map<String, Object> transactionView(GuardianTransaction tx, Long userId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", tx.getId());
        m.put("state", tx.getState());
        m.put("amount", tx.getAmount());
        m.put("category", tx.getCategory());
        m.put("undoDeadline", tx.getUndoDeadline());
        if (tx.isUndoable(clock.now(userId))) {
            m.put("undoActions", List.of(
                    Map.of("reason", UndoReason.NOT_MINE, "label", GuardianCopy.BUTTON_NOT_MINE),
                    Map.of("reason", UndoReason.EXEMPTION, "label", GuardianCopy.BUTTON_EXEMPTION,
                            "remaining", rewardService.items(userId, clock.now(userId)).getExemption())));
        }
        return m;
    }

    /** 침묵 기록은 목록에 안 나가지만, 방금 만든 결정은 프론트가 알아야 애니메이션을 정한다. */
    private Map<String, Object> notificationView(GuardianNotification n) {
        if (n == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", n.getId());
        m.put("caseId", n.getCaseId());
        m.put("tone", n.getTone() == null ? null : n.getTone().wire());
        m.put("phrasingMode", n.getPhrasingMode());
        m.put("delivery", n.getDelivery());
        m.put("suppressedReason", n.getSuppressedReason());
        m.put("title", n.getTitle());
        m.put("body", n.getBody());
        m.put("isFallback", n.isFallback());
        m.put("sentAt", n.getSentAt());
        m.put("feedback", n.getFeedback());
        return m;
    }

    private Map<String, Object> itemsView(GuardianItems items) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exemption", items.getExemption());
        m.put("grassGuard", items.getGrassGuard());
        m.put("missionChange", items.getMissionChange());
        m.put("pointBalance", items.getPointBalance());
        return m;
    }

    private Map<String, Object> ceremonyView(GuardianService.CeremonyView c) {
        if (c == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("verdictDate", c.verdictDate());
        m.put("result", c.result());
        m.put("objectId", c.objectId());
        m.put("grade", c.grade());
        m.put("message", c.message());
        m.put("rerollAvailable", c.rerollAvailable());
        return m;
    }

    private Map<String, Object> batchResultView(GuardianBatchService.BatchResult r) {
        Map<String, Object> verdict = new LinkedHashMap<>();
        DailyVerdict v = r.verdict();
        // 판정이 없을 수 있다 — 대상 날짜가 챌린지 시작 전이면 설계서 §2에 따라 판정하지 않는다.
        if (v == null) {
            Map<String, Object> skipped = new LinkedHashMap<>();
            skipped.put("verdict", null);
            skipped.put("grantedObject", null);
            skipped.put("notifications", List.of());
            skipped.put("pointEvents", List.of());
            skipped.put("stateTransition", null);
            return skipped;
        }
        verdict.put("date", v.getVerdictDate());
        verdict.put("result", v.getResult());
        verdict.put("grantObject", v.isGrantObject());
        verdict.put("gradeWeights", v.getGradeWeights());
        verdict.put("reasonCode", v.getReasonCode());
        verdict.put("snapshot", Map.of(
                "spentAtDate", v.getSpentAtDate(),
                "spentRatio", round4(v.getSpentRatio()),
                "paceRatio", round4(v.getPaceRatio()),
                "allowedRatio", round4(v.getAllowedRatio())));

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("verdict", verdict);
        out.put("grantedObject", r.granted() == null ? null
                : Map.of("objectId", r.granted().objectId(), "grade", r.granted().grade()));
        out.put("notifications", r.notifications().stream().map(this::notificationView).toList());
        out.put("pointEvents", r.pointEvents());
        out.put("stateTransition", r.stateTransition());
        return out;
    }

    // ======================================================================
    //  도감 · 포인트샵 · 결산 · 갱신 (개편안 s-collection·s-shop·s-settle·s-renew)
    // ======================================================================

    /** 도감 — 모은 소품과 아직 못 모은 칸, 마일스톤 진행. */
    @GetMapping("/collection")
    public GuardianCollectionService.CollectionView collection(@RequestParam Long userId) {
        return collectionService.collection(userId);
    }

    /** 마일스톤 보상 청구 — N종을 채웠을 때. 아직이면 400. */
    @PostMapping("/collection/milestones/{count}/claim")
    public GuardianCollectionService.CollectionView claimMilestone(@RequestParam Long userId,
                                                                   @PathVariable int count) {
        return collectionService.claim(userId, count);
    }

    /** 포인트샵 진열대 — 보유 여부와 살 수 있는지까지 서버가 판단해 내려준다. */
    @GetMapping("/shop")
    public GuardianCollectionService.ShopView shop(@RequestParam Long userId) {
        return collectionService.shop(userId);
    }

    /** 구매 — 포인트로만. 잔액이 모자라거나 이미 가진 물건이면 400. */
    @PostMapping("/shop/{code}/buy")
    public GuardianCollectionService.ShopView buy(@RequestParam Long userId, @PathVariable String code) {
        return collectionService.buy(userId, code);
    }

    /** 배치 변경(꾸미기 모드) — slot을 비우면 창고로 내린다. 응답은 방 전체 상태. */
    @PostMapping("/room/place")
    public Map<String, Object> place(@RequestParam Long userId, @RequestBody PlaceRequest req) {
        return roomView(collectionService.place(userId, req.objectId(), req.slot()));
    }

    /** @param slot null이면 창고로 내린다. */
    public record PlaceRequest(String objectId, Integer slot) {}

    /** 월간 결산 — 방어율·카테고리별 성적·지킨 날·최장 연속·포인트·소품. */
    @GetMapping("/settlement")
    public GuardianSettlementService.SettlementView settlement(@RequestParam Long userId) {
        return settlementService.settle(userId);
    }

    /** 다음 달 조정안 — 지난달 실적에서 유도한다. 사용자가 그대로 쓰거나 직접 고친다. */
    @GetMapping("/renewal")
    public GuardianSettlementService.RenewalView renewal(@RequestParam Long userId) {
        return settlementService.renewal(userId);
    }

    /** 비율은 소수 넷째 자리까지 — 설계서 응답 예시와 자릿수를 맞춘다. */
    private static double round4(double v) {
        return Math.round(v * 10_000.0) / 10_000.0;
    }
}
