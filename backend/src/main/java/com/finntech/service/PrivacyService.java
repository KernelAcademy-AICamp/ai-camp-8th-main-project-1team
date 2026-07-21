package com.finntech.service;

import com.finntech.audit.AuditService;
import com.finntech.domain.AppUser;
import com.finntech.domain.Consumption;
import com.finntech.domain.Enums;
import com.finntech.repository.AlertRepository;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.ConsumptionRepository;
import com.finntech.repository.CouponRepository;
import com.finntech.repository.GoalMilestoneRepository;
import com.finntech.repository.ImpulseSaverStateRepository;
import com.finntech.repository.PointEventRepository;
import com.finntech.repository.ReportRepository;
import com.finntech.repository.SavingsGoalRepository;
import com.finntech.repository.UserCardRepository;
import com.finntech.repository.UserPaymentRepository;
import com.finntech.repository.WishlistItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * 개인정보 처리 (문서 §5-3).
 *
 * <p>문서에 고지 문안으로 못박은 약속을 <b>코드로 지킨다</b>:
 * <ul>
 *   <li>3번 보유기간 3개월 → {@link #purgeExpired}</li>
 *   <li>4번 "삭제 사실은 감사로그에 기록되어 사후 검증이 가능합니다" → 파기 시 {@code AuditService.append}</li>
 *   <li>6번 정보주체의 열람·삭제 요청권 → {@link #exportUserData}, {@link #eraseUserData}</li>
 *   <li>7번 동의 거부 시 데모 모드 → {@code POST /api/consumption}이 403</li>
 * </ul>
 * 고지만 하고 구현하지 않으면 처리방침이 거짓말이 된다.
 */
@Service
public class PrivacyService {

    private static final Logger log = LoggerFactory.getLogger(PrivacyService.class);

    private final AppUserRepository userRepository;
    private final ConsumptionRepository consumptionRepository;
    private final AlertRepository alertRepository;
    private final ReportRepository reportRepository;
    private final PointEventRepository pointEventRepository;
    private final SavingsGoalRepository goalRepository;
    private final CouponRepository couponRepository;
    private final WishlistItemRepository wishlistRepository;
    private final GoalMilestoneRepository milestoneRepository;
    private final ImpulseSaverStateRepository impulseStateRepository;
    private final UserCardRepository userCardRepository;
    private final UserPaymentRepository userPaymentRepository;
    private final AuditService auditService;
    private final int retentionDays;

    public PrivacyService(AppUserRepository userRepository,
                          ConsumptionRepository consumptionRepository,
                          AlertRepository alertRepository,
                          ReportRepository reportRepository,
                          PointEventRepository pointEventRepository,
                          SavingsGoalRepository goalRepository,
                          CouponRepository couponRepository,
                          WishlistItemRepository wishlistRepository,
                          GoalMilestoneRepository milestoneRepository,
                          ImpulseSaverStateRepository impulseStateRepository,
                          UserCardRepository userCardRepository,
                          UserPaymentRepository userPaymentRepository,
                          AuditService auditService,
                          @Value("${finntech.privacy.retention-days:90}") int retentionDays) {
        this.userRepository = userRepository;
        this.consumptionRepository = consumptionRepository;
        this.alertRepository = alertRepository;
        this.reportRepository = reportRepository;
        this.pointEventRepository = pointEventRepository;
        this.goalRepository = goalRepository;
        this.couponRepository = couponRepository;
        this.wishlistRepository = wishlistRepository;
        this.milestoneRepository = milestoneRepository;
        this.impulseStateRepository = impulseStateRepository;
        this.userCardRepository = userCardRepository;
        this.userPaymentRepository = userPaymentRepository;
        this.auditService = auditService;
        this.retentionDays = retentionDays;
    }

    public int getRetentionDays() { return retentionDays; }

    /** 동의 기록. 동의 시각도 감사로그에 남긴다 — 동의 여부는 사후 다툼의 대상이 된다. */
    @Transactional
    public AppUser setConsent(Long userId, boolean consent, LocalDateTime at) {
        AppUser user = userRepository.findById(userId).orElseThrow(
                () -> new IllegalArgumentException("user " + userId + " not found"));
        user.setConsentGiven(consent);
        userRepository.save(user);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("consent", consent);
        auditService.append(consent ? "CONSENT_GRANTED" : "CONSENT_WITHDRAWN", payload, at);

        // 동의 철회 시 이미 수집된 데이터를 남겨두면 처리방침 위반이다. 즉시 파기한다.
        if (!consent) {
            int erased = eraseUserData(userId, at);
            log.info("동의 철회로 userId={} 의 USER_INPUT {}건 파기", userId, erased);
        }
        return user;
    }

    /**
     * 보유기간(3개월)이 지난 <b>USER_INPUT</b>을 파기한다.
     * DUMMY_SEED는 개인정보가 아니므로 대상이 아니다.
     */
    @Transactional
    public PurgeReport purgeExpired(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(retentionDays);
        List<Consumption> expired = consumptionRepository
                .findBySourceAndOccurredAtBefore(Enums.DataSource.USER_INPUT, cutoff);

        if (expired.isEmpty()) {
            return new PurgeReport(0, 0, 0, cutoff, retentionDays);
        }

        // Alert는 amount·occurredAt·categoryCode를 자기 테이블에 복사해 갖고 있다.
        // Consumption만 지우면 파기했다고 해놓고 개인정보가 그대로 남는다.
        List<Long> expiredIds = expired.stream().map(Consumption::getId).toList();
        alertRepository.deleteByConsumptionIdIn(expiredIds);

        // 캐시된 리포트도 카테고리별·월별 지출을 담고 있어 함께 무효화한다.
        Set<Long> affectedUsers = expired.stream()
                .map(Consumption::getUserId).collect(Collectors.toCollection(TreeSet::new));
        int reportsDeleted = 0;
        for (Long uid : affectedUsers) {
            reportRepository.deleteByUserId(uid);
            reportsDeleted++;
        }

        consumptionRepository.deleteAll(expired);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("deletedCount", expired.size());
        payload.put("affectedUsers", affectedUsers.size());
        payload.put("cutoff", cutoff.toString());
        payload.put("retentionDays", retentionDays);
        auditService.append("RETENTION_PURGE", payload, now);
        auditService.sealBatch(now);

        log.info("보유기간 초과 USER_INPUT {}건 파기 (cutoff={}, 영향 사용자 {}명)",
                expired.size(), cutoff, affectedUsers.size());
        return new PurgeReport(expired.size(), expiredIds.size(), reportsDeleted, cutoff, retentionDays);
    }

    /** 정보주체의 열람 요청 (처리방침 6번). 수집한 4개 항목만 그대로 돌려준다. */
    @Transactional(readOnly = true)
    public List<Map<String, Object>> exportUserData(Long userId) {
        return consumptionRepository.findAllForUser(userId).stream()
                .filter(c -> c.getSource() == Enums.DataSource.USER_INPUT)
                .map(c -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", c.getId());
                    m.put("categoryCode", c.getCategory().getCode());
                    m.put("amount", c.getAmount());
                    m.put("occurredAt", c.getOccurredAt());
                    m.put("planned", c.isPlanned());
                    return m;
                })
                .toList();
    }

    /**
     * 정보주체의 삭제 요청 (처리방침 6번). 삭제 사실을 감사로그에 남긴다.
     *
     * <p><b>Consumption만 지우면 안 된다.</b> {@code Alert}는 amount·occurredAt·categoryCode를
     * 자기 테이블에 복사해 갖고 있고, {@code Report}는 카테고리별·월별 지출을 직렬화해 갖고 있다.
     * 셋을 다 지워야 "삭제했다"가 사실이 된다.
     */
    @Transactional
    public int eraseUserData(Long userId, LocalDateTime at) {
        // 사용자가 준 소비(USER_INPUT)와 업로드한 카드내역(CARD_UPLOAD)은 개인정보이므로 파기 대상. DUMMY_SEED는 제외.
        List<Consumption> mine = consumptionRepository.findAllForUser(userId).stream()
                .filter(c -> c.getSource() != Enums.DataSource.DUMMY_SEED)
                .toList();

        // 소비내역이 없어도 잔재는 남아 있을 수 있으므로 항상 함께 정리한다.
        // 게임화 저축 데이터(PointEvent·SavingsGoal·Coupon·충동예산 절약통)도 소비 행태 정보이므로 함께 파기한다(§5-5, 잔재 방지).
        alertRepository.deleteByUserId(userId);
        reportRepository.deleteByUserId(userId);
        pointEventRepository.deleteByUserId(userId);
        goalRepository.deleteByUserId(userId);
        couponRepository.deleteByUserId(userId);
        wishlistRepository.deleteByUserId(userId);
        milestoneRepository.deleteByUserId(userId);
        impulseStateRepository.deleteByUserId(userId);
        // 마이데이터 연동 데이터(불러온 카드·결제)와 CI·전화번호도 개인정보이므로 함께 파기한다(§13).
        userCardRepository.deleteByUserId(userId);
        userPaymentRepository.deleteByUserId(userId);
        userRepository.findById(userId).ifPresent(user -> { user.setCi(null); userRepository.save(user); });

        if (mine.isEmpty()) return 0;
        consumptionRepository.deleteAll(mine);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("userId", userId);
        payload.put("deletedCount", mine.size());
        auditService.append("SUBJECT_ERASURE", payload, at);
        auditService.sealBatch(at);
        return mine.size();
    }

    /**
     * 개인정보 처리방침 화면용 요약.
     *
     * <p>정본은 {@code legal/privacy-policy.md}이며(『/reference』 예시 양식을 우리 관행으로 변형),
     * 이 메서드는 그 정본의 핵심 조항을 화면(`GET /api/privacy/policy`)에 그대로 내려주기 위한 요약이다.
     * 정본을 고치면 이 요약도 정합시킨다.
     */
    public PrivacyPolicy policy() {
        return new PrivacyPolicy(
                "소비내역 기록 기능 개인정보 처리방침",
                List.of(
                        new Clause("1. 수집 항목",
                                "소비 카테고리, 금액, 날짜, 계획소비 여부 (4개 항목)\n"
                                        + "※ 실명·이메일·연락처·계좌번호·카드번호·주민등록번호는 수집하지 않으며, "
                                        + "입력 화면에 해당 항목의 입력란 자체가 없습니다. 계정은 닉네임 기반 익명 계정입니다."),
                        new Clause("2. 수집 목적",
                                "소비 패턴 분석·소비건전성지수·절약 리포트·(더미)금융상품 추천·이상소비 탐지 (본 서비스 내에서만 이용).\n"
                                        + "외부 AI에는 개인을 식별할 수 없는 집계 수치만 전달되며, 광고·마케팅·AI 학습에 이용하지 않습니다."),
                        new Clause("3. 보유 및 이용 기간",
                                "입력일로부터 " + (retentionDays / 30) + "개월. 기간 경과 시 매일 배치로 자동 파기하며, "
                                        + "동의 철회·삭제 요청 시 즉시 파기합니다. 프로젝트 종료 시 전량 파기합니다."),
                        new Clause("4. 파기 절차 및 방법",
                                "보유기간이 지난 기록은 배치 작업으로 삭제하며, 해당 소비내역에서 파생된 이상소비 경고·리포트 캐시도 "
                                        + "함께 삭제합니다. 삭제 사실은 감사로그에 기록되어 사후 검증이 가능합니다."),
                        new Clause("5. 제3자 제공 및 안전성 확보조치",
                                "개인정보를 제3자에게 제공·위탁하지 않으며 국외로 이전하지 않습니다. "
                                        + "전송 구간 암호화(TLS)·저장 암호화, 접속·처리 기록의 해시체인+TSA 감사로그로 위·변조를 방지합니다."),
                        new Clause("6. 정보주체의 권리",
                                "언제든지 본인이 입력한 기록의 열람·내보내기·삭제를 요청할 수 있습니다. "
                                        + "예시(더미) 데이터는 개인정보가 아니므로 열람·삭제 대상에서 제외됩니다."),
                        new Clause("7. 동의 거부권 · 해당 없는 항목",
                                "동의를 거부할 수 있으며, 이 경우 예시 데이터 기반 데모 모드로 이용 가능합니다.\n"
                                        + "본 서비스는 위치정보·쿠키·결제·송금 기능이 없어 관련 개인정보를 처리하지 않습니다.")
                ),
                "본 서비스는 학습용 포트폴리오 프로토타입이며 실제 금융거래·결제·송금 기능을 제공하지 않고, "
                        + "표시되는 금융상품은 실재하지 않는 더미 상품입니다. 방침 전문: legal/privacy-policy.md");
    }

    /**
     * 이용약관 화면용 요약. 정본은 {@code legal/terms-of-service.md}.
     * 발표에서 중요한 제8조(금융 자문·중개가 아님)를 화면에서 바로 보여주기 위함이다.
     */
    public Terms terms() {
        return new Terms(
                "소비·저축 어드바이저 이용약관",
                List.of(
                        new Clause("제4조 (이용계약)",
                                "닉네임 익명 계정으로 이용하며 본인인증·실명·이메일·비밀번호를 요구하지 않습니다. "
                                        + "미동의 시에도 데모 모드로 모든 기능을 이용할 수 있습니다."),
                        new Clause("제8조 (금융 자문·중개가 아님)",
                                "표시되는 금융상품은 실재하지 않는 더미 상품이며 실제 계약·수수료가 발생하지 않아 "
                                        + "금융소비자보호법상 판매·중개·자문에 해당하지 않습니다. 결제·송금·계좌연동을 제공하지 않습니다."),
                        new Clause("제10조 (책임의 제한)",
                                "학습용 프로토타입으로 무료 제공되며 데이터의 영구 보존을 보증하지 않습니다. "
                                        + "추천·리포트·알림은 참고자료이며 실제 금융 의사결정의 근거로 사용해서는 안 됩니다.")
                ),
                "약관 전문: legal/terms-of-service.md");
    }

    public record Clause(String title, String body) {}
    public record PrivacyPolicy(String title, List<Clause> clauses, String notice) {}
    public record Terms(String title, List<Clause> clauses, String notice) {}
    public record PurgeReport(
            int deletedCount,
            /** 함께 정리된 경고 대상 소비 건수 */
            int alertsClearedFor,
            /** 무효화된 리포트 캐시 보유 사용자 수 */
            int reportsInvalidatedFor,
            LocalDateTime cutoff,
            int retentionDays
    ) {}
}
