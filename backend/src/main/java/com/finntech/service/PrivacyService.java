package com.finntech.service;

import com.finntech.audit.AuditService;
import com.finntech.domain.AppUser;
import com.finntech.domain.Consumption;
import com.finntech.domain.Enums;
import com.finntech.repository.AlertRepository;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.ConsumptionRepository;
import com.finntech.repository.CutCandidateSelectionRepository;
import com.finntech.repository.CouponRepository;
import com.finntech.repository.GoalMilestoneRepository;
import com.finntech.repository.ImpulseSaverStateRepository;
import com.finntech.repository.PointEventRepository;
import com.finntech.repository.ReportRepository;
import com.finntech.repository.SavingsGoalRepository;
import com.finntech.repository.UserBankRepository;
import com.finntech.repository.UserCardCompanyRepository;
import com.finntech.repository.UserCardRepository;
import com.finntech.repository.UserPaymentRepository;
import com.finntech.repository.UserSpendingOverrideRepository;
import com.finntech.repository.WishlistItemRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
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
    private final UserCardCompanyRepository userCardCompanyRepository;
    private final UserBankRepository userBankRepository;
    private final UserSpendingOverrideRepository overrideRepository;
    private final com.finntech.repository.UserMerchantStanceRepository merchantStanceRepository;
    private final CutCandidateSelectionRepository cutSelectionRepository;
    /**
     * 정리된 소비 원장(V34)과 그 재작성 대기열.
     *
     * <p><b>이 표는 개인정보의 사본이다</b> — 어디서 무엇을 언제 얼마에 샀는지가 한 줄에 다
     * 들어 있다. 원본을 지우고 사본을 남기면 "파기했다"고 해놓고 개인정보가 그대로 남는다
     * ({@code purgeExpired} 의 alert 처리와 같은 이유).
     */
    private final com.finntech.repository.SpendingLedgerRepository spendingLedgerRepository;
    private final com.finntech.repository.SpendingLedgerDirtyRepository spendingLedgerDirtyRepository;
    /**
     * 행태 기록(V35).
     *
     * <p>방침 33조가 행태정보의 보유기간을 <b>"회원 탈퇴, 동의 철회까지"</b> 로 정해 두었다.
     * 그러니 여기서 지우는 것은 선택이 아니라 그 조항의 이행이다.
     */
    private final com.finntech.repository.UsageEventRepository usageEventRepository;
    private final com.finntech.repository.UsageSessionRepository usageSessionRepository;
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
                          UserCardCompanyRepository userCardCompanyRepository,
                          UserBankRepository userBankRepository,
                          UserSpendingOverrideRepository overrideRepository,
                          com.finntech.repository.UserMerchantStanceRepository merchantStanceRepository,
                          CutCandidateSelectionRepository cutSelectionRepository,
                          com.finntech.repository.SpendingLedgerRepository spendingLedgerRepository,
                          com.finntech.repository.SpendingLedgerDirtyRepository spendingLedgerDirtyRepository,
                          com.finntech.repository.UsageEventRepository usageEventRepository,
                          com.finntech.repository.UsageSessionRepository usageSessionRepository,
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
        this.userCardCompanyRepository = userCardCompanyRepository;
        this.userBankRepository = userBankRepository;
        this.overrideRepository = overrideRepository;
        this.merchantStanceRepository = merchantStanceRepository;
        this.cutSelectionRepository = cutSelectionRepository;
        this.spendingLedgerRepository = spendingLedgerRepository;
        this.spendingLedgerDirtyRepository = spendingLedgerDirtyRepository;
        this.usageEventRepository = usageEventRepository;
        this.usageSessionRepository = usageSessionRepository;
        this.auditService = auditService;
        this.retentionDays = retentionDays;
    }

    public int getRetentionDays() { return retentionDays; }

    /** 동의 기록. 동의 시각도 감사로그에 남긴다 — 동의 여부는 사후 다툼의 대상이 된다. */
    @Transactional
    public AppUser setConsent(Long userId, boolean consent, LocalDateTime at) {
        AppUser user = userRepository.findById(userId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user " + userId + " not found"));
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
     * 보유기간(3개월)이 지난 <b>USER_INPUT</b>을 파기한다(주기 자동 파기).
     * DUMMY_SEED는 개인정보가 아니므로 대상이 아니다.
     * <p><b>MYDATA(연동 데이터)는 주기 자동 파기 대상이 아니다</b>(정책 결정, W7-5d) — 마이데이터로 불러온
     * 카드·결제는 "삭제 요청·동의 철회 시까지 보유"하고 그때 {@link #eraseUserData}로 즉시 파기한다.
     * 방침 3조와 정합. (자동 파기로 바꾸려면 {@code findBySourceAndOccurredAtBefore}에 MYDATA 소스를 추가하고 보존일 키를 신설.)
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
        userCardCompanyRepository.deleteByUserId(userId);   // 연동 카드사·동기화 기록도 파기(W2)
        userBankRepository.deleteByUserId(userId);         // 연동 은행 기록도 함께 파기
        overrideRepository.deleteByUserId(userId);   // 개인화 override도 파기(W8-5)
        // 가맹점 판정 성향도 사용자가 쌓은 판단이다 — 어디서 무엇을 사는지가 드러난다.
        merchantStanceRepository.deleteByUserId(userId);
        cutSelectionRepository.deleteByUserId(userId);   // 절약후보 선택추적(⑤)도 소비결정 정보이므로 파기
        // 정리된 소비 원장(V34)은 위 결제들의 **사본**이다 — 어디서 무엇을 언제 샀는지가
        // 한 줄에 다 있다. 원본만 지우면 파기했다고 해놓고 그대로 남는다.
        spendingLedgerRepository.deleteByUserId(userId);
        // 대기 중인 재작성 표시도 치운다. 남겨 두면 배수가 지워진 사용자를 한 번 더 집어
        // "쓸 것이 없다"를 확인하고 지운다 — 결과는 같지만 그 헛걸음이 로그를 흐린다.
        spendingLedgerDirtyRepository.deleteByUserId(userId);
        // 행태 기록 — 방침 33조가 "탈퇴·철회까지"로 정한 보유기간의 끝이 여기다.
        // 어느 화면에 얼마나 머물렀는지는 그 사람이 무엇에 관심 있는지를 그대로 말한다.
        usageEventRepository.deleteByUserId(userId);
        usageSessionRepository.deleteByUserId(userId);
        userRepository.findById(userId).ifPresent(user -> {
            user.setCi(null);
            user.setBirthYear(null);   // 본인인증에서 파생한 출생연도도 개인정보다 — 함께 파기한다.
            user.setGender(null);      // 성별도 같은 한 글자에서 나온 값이라 보유 근거가 같다.
            userRepository.save(user);
        });

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
     * 개인정보 처리방침 — <b>정본을 그대로 내려보낸다</b>({@code legal/privacy-policy.md}).
     *
     * <p><b>왜 요약을 그만뒀나.</b> 예전에는 여기 손으로 쓴 요약이 들어 있었다. 화면은 정본
     * 파일이 아니라 이 문자열을 읽으므로, <b>정본을 고쳐도 화면은 안 바뀐다.</b> 실제로 갈라졌다
     * — 2026-08-10 개정 뒤 정본은 이름·CI·DI·계좌번호를 수집 항목으로 명시하는데 화면은 여전히
     * "실명·계좌번호는 수집하지 않습니다"라고 말하고 있었다. 이용자가 <b>운영사가 쓰지도 않은
     * 방침에 동의</b>하는 셈이다.
     *
     * <p>그래서 사람이 옮겨 적는 자리를 없앴다. 정본을 읽어 절로 쪼개 내려보내므로 옮겨 적을
     * 일이 없고, 어긋날 자리도 없다.
     */
    public PrivacyPolicy policy() {
        Document d = LEGAL.get("privacy-policy");
        return new PrivacyPolicy(d.title(), d.clauses(), "");
    }

    /**
     * 이용약관 — <b>정본을 그대로 내려보낸다</b>({@code legal/terms-of-service.md}).
     *
     * <p>가입 화면의 '상세보기'가 읽는 것이 이것이다. {@link #policy()} 와 같은 이유로 요약을
     * 두지 않는다 — 개정으로 조문이 아홉 개로 바뀌었을 때 손으로 쓴 요약은 <b>없어진 제8조·
     * 제10조를 인용</b>하고 있었다.
     */
    public Terms terms() {
        Document d = LEGAL.get("terms-of-service");
        return new Terms(d.title(), d.clauses(), "");
    }

    /**
     * 동의 항목 하나가 펼치는 문서 — <b>정본을 그대로 내려보낸다</b>.
     *
     * <p>가입 화면의 동의 항목 넷 중 셋은 각자 다른 문서를 편다. 예전에는 셋 다 개인정보
     * 처리방침을 폈는데, 그 방침에는 <b>고유식별정보도 마케팅 수신도 한 번도 안 나온다.</b>
     * 상세보기를 눌러도 자기 얘기가 없는 문서가 떴다는 뜻이다.
     *
     * @param id {@code credit-info} · {@code unique-id} · {@code marketing}
     */
    public PrivacyPolicy consent(String id) {
        Document d = LEGAL.get("consent-" + id);
        if (d == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "그런 동의 문서는 없습니다: " + id);
        return new PrivacyPolicy(CONSENT_TITLES.get(id), d.clauses(), "");
    }

    // ======================================================================
    //  법무 정본 읽기
    // ======================================================================

    /** 정본 한 편 — 제목과 절. */
    private record Document(String title, List<Clause> clauses) {}

    /**
     * 동의 문서의 제목 — <b>화면의 동의 항목 이름</b>을 그대로 쓴다.
     *
     * <p>파일에 제목 줄을 넣지 않는 이유는 그 문서들이 <b>받은 문장만</b> 담기 때문이다.
     * 제목까지 파일에 적으면 원문에 없던 줄이 하나 늘어난다.
     */
    private static final Map<String, String> CONSENT_TITLES = Map.of(
            "credit-info", "개인(신용)정보 수집·이용 동의",
            "unique-id", "고유식별정보 처리 동의",
            "marketing", "지킴이 알림, 혜택 수신");

    /**
     * 기동할 때 한 번 읽어 둔다. 방침·약관·동의서는 배포 중에 안 바뀌므로 요청마다 파일을 열
     * 이유가 없다.
     *
     * <p><b>왜 저장소 루트가 아니라 리소스에서 읽나.</b> 운영은 도커로 도는데 백엔드 이미지의
     * 빌드 맥락이 {@code ./backend} 라 {@code ../legal} 이 이미지 안으로 안 들어온다. 맥락을
     * 저장소 루트로 넓히면 4.7GB 를 도커 데몬에 보내게 된다. 그래서 정본을 리소스로 <b>함께
     * 싣고</b>, 루트의 정본과 한 글자라도 다르면 시험이 깨지게 했다({@code PrivacyFlowTest}).
     */
    private static final Map<String, Document> LEGAL = Map.of(
            "privacy-policy", load("privacy-policy"),
            "terms-of-service", load("terms-of-service"),
            "consent-credit-info", load("consent-credit-info"),
            "consent-unique-id", load("consent-unique-id"),
            "consent-marketing", load("consent-marketing"));

    /**
     * 정본을 화면용 절로 쪼갠다. <b>글자는 하나도 더하지도 빼지도 않는다.</b>
     *
     * <p>규칙은 셋이다.
     * <ul>
     *   <li>{@code # } 한 줄은 문서 제목이다(없으면 절 제목도 없이 통째로 한 덩이가 된다)</li>
     *   <li>표제 앞에 오는 <b>머리글도 절이 된다</b> — 제목만 없다. 이걸 버리면 방침 첫
     *       문단("운영사는 …을 준수하며")이 화면에서 통째로 사라진다</li>
     *   <li>내용 없는 표제는 버린다 — 약관의 {@code ## 제1장 총칙} 같은 묶음이다. 남기면 빈
     *       절이 뜬다</li>
     * </ul>
     */
    private static Document load(String name) {
        String md;
        try (var in = PrivacyService.class.getResourceAsStream("/legal/" + name + ".md")) {
            if (in == null) throw new IllegalStateException("법무 정본이 빠졌다: " + name);
            md = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("법무 정본을 못 읽었다: " + name, e);
        }

        String title = "";
        List<Clause> clauses = new java.util.ArrayList<>();
        String heading = "";                       // 머리글은 제목 없는 절이다
        StringBuilder body = new StringBuilder();

        for (String line : md.split("\n", -1)) {
            if (!line.startsWith("#")) {
                body.append(line).append('\n');
                continue;
            }
            if (!body.toString().isBlank()) {
                clauses.add(new Clause(heading, body.toString().strip()));
            }
            body.setLength(0);
            String text = line.replaceFirst("^#+\\s*", "").strip();
            if (line.startsWith("# ")) {           // 문서 제목 — 절이 아니다
                title = text;
                heading = "";
            } else {
                heading = text;
            }
        }
        if (!body.toString().isBlank()) {
            clauses.add(new Clause(heading, body.toString().strip()));
        }
        return new Document(title, List.copyOf(clauses));
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
