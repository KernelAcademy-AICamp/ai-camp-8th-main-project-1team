package com.finntech.intake;

import com.finntech.audit.AuditService;
import com.finntech.crypto.FieldCrypto;
import com.finntech.domain.RealUserIntake;
import com.finntech.repository.RealUserIntakeRepository;
import com.finntech.service.MyDataClient;
import com.finntech.util.Msisdn;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실사용자 신청 접수·승인 (설계서 Phase 3).
 *
 * <h2>왜 신원과 명세서를 한 번에 받는가</h2>
 *
 * <p>승인 모델에서는 순서가 뒤집힌다 — 사용자가 로그인하려면 제공자에 신원이 있어야 하는데,
 * 신원 등록이 승인을 기다리면 <b>로그인을 못 해 업로드도 못 한다.</b> 그래서 로그인 전에
 * 신원 3값과 카드별 명세서를 <b>한 배치</b>로 받고 승인도 한 번이다.
 *
 * <h2>대기열은 통로다</h2>
 *
 * <p>실 개인정보를 담는 자리이므로 암호화해서 넣고, 승인·반려 직후 <b>행을 지운다.</b>
 * 아무도 손대지 않은 것은 만료된다.
 */
@Service
public class IntakeService {

    private static final Logger log = LoggerFactory.getLogger(IntakeService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] TICKET_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789".toCharArray();

    private final RealUserIntakeRepository repository;
    private final FieldCrypto crypto;
    private final MyDataClient myDataClient;
    private final AuditService audit;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();

    private final int maxRows;
    private final int maxBytes;
    private final int dailyPerIp;
    private final int expireDays;

    public IntakeService(RealUserIntakeRepository repository, FieldCrypto crypto,
                         MyDataClient myDataClient, AuditService audit, Clock clock,
                         @Value("${finntech.intake.max-rows:10000}") int maxRows,
                         @Value("${finntech.intake.max-bytes:5242880}") int maxBytes,
                         @Value("${finntech.intake.daily-per-ip:10}") int dailyPerIp,
                         @Value("${finntech.intake.expire-days:7}") int expireDays) {
        this.repository = repository;
        this.crypto = crypto;
        this.myDataClient = myDataClient;
        this.audit = audit;
        this.clock = clock;
        this.maxRows = maxRows;
        this.maxBytes = maxBytes;
        this.dailyPerIp = dailyPerIp;
        this.expireDays = expireDays;
    }

    /** 카드 한 장 — 카드사·상품·표시명과 그 카드의 명세서. */
    public record CardSubmission(Long cardCode, String displayName, String csv) {}

    public record Submission(String name, String social7, String phone,
                             List<CardSubmission> cards, boolean consent) {}

    /** 접수 결과 — 통과했으면 접수증, 아니면 줄 번호가 달린 사유 목록. */
    public record SubmitResult(String ticket, int accepted, int rejected,
                               List<StatementValidator.Problem> problems) {}

    /**
     * 신청을 받는다.
     *
     * <p>브라우저도 같은 검사를 하지만 그것은 편의다 — <b>여기가 권위</b>다.
     */
    @Transactional
    public SubmitResult submit(Submission submission, HttpServletRequest request) {
        requireConsent(submission);
        requireIdentity(submission);
        requirePhoneIsYours(submission);
        String ip = com.finntech.auth.AuthTokenService.clientIp(request);
        requireQuota(ip);

        LocalDate today = LocalDate.now(clock);
        List<Map<String, Object>> cardPayloads = new ArrayList<>();
        List<StatementValidator.Problem> problems = new ArrayList<>();
        int totalRows = 0, totalBytes = 0, refundCount = 0, withBusinessNumber = 0;
        long totalAmount = 0, refundAmount = 0;
        LocalDate from = null, to = null;
        java.util.Set<String> merchants = new java.util.LinkedHashSet<>();

        for (int index = 0; index < submission.cards().size(); index++) {
            CardSubmission card = submission.cards().get(index);
            totalBytes += card.csv() == null ? 0 : card.csv().length();
            if (totalBytes > maxBytes) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "명세서가 너무 큽니다(" + (maxBytes / 1_048_576) + "MB 까지).");
            }
            StatementValidator.Result result = StatementValidator.validate(card.csv(), today);
            // 줄 번호에 카드 순서를 붙여 어느 파일의 몇 번째 줄인지 알 수 있게 한다.
            result.problems().forEach(problem -> problems.add(
                    new StatementValidator.Problem(problem.line(),
                            "[" + (cardPayloads.size() + 1) + "번째 카드] " + problem.reason())));

            totalRows += result.rows().size();
            if (totalRows > maxRows) {
                throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                        "결제 건수가 상한(" + maxRows + ")을 넘습니다.");
            }
            totalAmount += result.totalAmount();
            refundCount += result.refundCount();
            refundAmount += result.refundAmount();
            withBusinessNumber += result.withBusinessNumber();
            result.rows().forEach(row -> merchants.add(row.merchant()));
            from = earlier(from, result.from().orElse(null));
            to = later(to, result.to().orElse(null));

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("cardCode", card.cardCode());
            payload.put("displayName", card.displayName());
            payload.put("csv", result.toCsv());          // **검증을 통과한 줄만** 실린다
            cardPayloads.add(payload);
        }

        if (totalRows == 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "읽을 수 있는 결제가 한 건도 없습니다. 양식을 확인해 주세요.");
        }

        String ticket = newTicket();
        LocalDateTime now = LocalDateTime.now(clock);
        RealUserIntake intake = new RealUserIntake(ticket,
                crypto.encrypt(submission.name().trim()),
                crypto.encrypt(submission.social7().replaceAll("\\D", "")),
                // 숫자만 남겨 둔다 — 표기(하이픈)는 제공자가 원장 형식으로 맞춘다.
                // CI 산식도 숫자만 쓰므로 표기를 바꿔도 신원은 그대로다.
                crypto.encrypt(submission.phone().replaceAll("\\D", "")),
                crypto.encrypt(writeJson(cardPayloads)),
                RealUserIntake.mask(submission.name()),
                now, ip, now.plusDays(expireDays));
        intake.summarize(cardPayloads.size(), totalRows, problems.size(), totalAmount,
                refundCount, refundAmount, withBusinessNumber, merchants.size(), from, to);
        repository.save(intake);

        Map<String, Object> auditPayload = new LinkedHashMap<>();
        auditPayload.put("ticket", ticket);
        auditPayload.put("cards", cardPayloads.size());
        auditPayload.put("rows", totalRows);
        auditPayload.put("ip", ip);
        audit.append("INTAKE_RECEIVED", auditPayload, now);

        return new SubmitResult(ticket, totalRows, problems.size(), List.copyOf(problems));
    }

    /**
     * 승인 — 복호화 → <b>재검증</b> → 제공자 적재 → 감사 → 대기 행 삭제.
     *
     * <p>재검증을 다시 하는 이유: 저장 시점과 승인 시점 사이에 무슨 일이 있었든, 제공자로
     * 나가는 것은 <b>지금 검증을 통과한 것</b>이어야 한다. 프록시는 통과가 아니라 번역이다.
     */
    @Transactional
    public Map<String, Object> approve(Long intakeId, String adminUsername) {
        RealUserIntake intake = repository.findByIdForUpdate(intakeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (intake.getStatus() != RealUserIntake.Status.RECEIVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 신청입니다.");
        }

        String name = crypto.decrypt(intake.getNameEnc());
        String social7 = crypto.decrypt(intake.getSocial7Enc());
        String phone = crypto.decrypt(intake.getPhoneEnc());
        List<Map<String, Object>> cards = readJson(crypto.decrypt(intake.getPayloadEnc()));

        LocalDate today = LocalDate.now(clock);
        List<MyDataClient.SelfCardBody> bodies = new ArrayList<>(cards.size());
        for (Map<String, Object> card : cards) {
            // **여기서 또 검증한다.** 저장 시점과 별개다.
            StatementValidator.Result revalidated =
                    StatementValidator.validate((String) card.get("csv"), today);
            bodies.add(new MyDataClient.SelfCardBody(
                    card.get("cardCode") == null ? null : ((Number) card.get("cardCode")).longValue(),
                    (String) card.get("displayName"),
                    revalidated.toCsv()));
        }

        LocalDateTime now = LocalDateTime.now(clock);
        Map<String, Object> approved = new LinkedHashMap<>();
        approved.put("intakeId", intakeId);
        approved.put("ticket", intake.getTicket());
        approved.put("admin", adminUsername);
        approved.put("cards", bodies.size());
        approved.put("rows", intake.getRowCount());
        audit.append("INTAKE_APPROVED", approved, now);

        Map<String, Object> result;
        try {
            result = myDataClient.selfImport(name, social7, phone, bodies);
        } catch (org.springframework.web.client.HttpClientErrorException.Conflict e) {
            /* 제공자가 **판정으로** 막았다 — 대개 "그 번호는 이미 다른 분 명의"다.
               사유를 그대로 관리자에게 보인다. 500 으로 뭉뚱그리면 무엇을 반려해야 하는지
               알 수가 없고, 승인 버튼만 계속 눌리게 된다.
               트랜잭션이 되감기므로 위에 적은 INTAKE_APPROVED 도 함께 사라진다 —
               승인되지 않은 일이 승인으로 남지 않는다. */
            log.warn("신청 반영 거절 — ticket={} 사유={}", intake.getTicket(), e.getResponseBodyAsString());
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "제공자가 이 신청을 받지 않았어요 — " + reasonOf(e));
        }

        Map<String, Object> imported = new LinkedHashMap<>(approved);
        imported.put("accepted", result.get("accepted"));
        imported.put("rejected", result.get("rejected"));
        audit.append("INTAKE_IMPORTED", imported, now);

        intake.markImported(adminUsername, now);
        // 반영이 끝났으므로 실 개인정보를 여기 남길 이유가 없다. **통로는 비운다.**
        repository.delete(intake);
        log.info("실사용자 신청 반영 완료 — ticket={} admin={} 카드={}",
                intake.getTicket(), adminUsername, bodies.size());
        return result;
    }

    @Transactional
    public void reject(Long intakeId, String adminUsername, RealUserIntake.RejectReason reason) {
        RealUserIntake intake = repository.findByIdForUpdate(intakeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (intake.getStatus() != RealUserIntake.Status.RECEIVED) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "이미 처리된 신청입니다.");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        intake.markRejected(adminUsername, reason, now);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("intakeId", intakeId);
        payload.put("ticket", intake.getTicket());
        payload.put("admin", adminUsername);
        payload.put("reason", reason.name());
        audit.append("INTAKE_REJECTED", payload, now);
        repository.delete(intake);
    }

    @Transactional(readOnly = true)
    public List<RealUserIntake> pending() {
        return repository.findByStatusOrderBySubmittedAtAsc(RealUserIntake.Status.RECEIVED);
    }

    @Transactional(readOnly = true)
    public java.util.Optional<RealUserIntake> byTicket(String ticket) {
        return repository.findByTicket(ticket);
    }

    /** 만료된 신청을 치운다. 실 개인정보라 <b>방치하지 않는다.</b> */
    @Transactional
    public int purgeExpired() {
        List<RealUserIntake> expired = repository.findByStatusAndExpiresAtBefore(
                RealUserIntake.Status.RECEIVED, LocalDateTime.now(clock));
        expired.forEach(repository::delete);
        if (!expired.isEmpty()) {
            log.info("만료된 실사용자 신청 {}건 파기", expired.size());
        }
        return expired.size();
    }

    // ── 검사 ────────────────────────────────────────────────────────────────

    private static void requireConsent(Submission submission) {
        if (!submission.consent()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "개인(신용)정보 수집·이용 동의가 필요합니다.");
        }
    }

    /**
     * 신원 세 값 검사.
     *
     * <p>빈 값으로 CI 를 만들면 <b>모두가 같은 사람</b>이 된다 — 해시의 입력이 같아지기 때문이다.
     */
    private static void requireIdentity(Submission submission) {
        String name = submission.name() == null ? "" : submission.name().trim();
        String social7 = submission.social7() == null ? "" : submission.social7().replaceAll("\\D", "");
        if (name.isEmpty() || name.length() > 40 || !name.matches("[가-힣a-zA-Z ]+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "이름을 확인해 주세요.");
        }
        if (social7.length() != 7) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "주민등록번호 앞 7자리를 확인해 주세요.");
        }
        // 국번이 실존하는지까지 본다 — 형식만 맞고 배정되지 않은 대역일 수 있다.
        if (Msisdn.carrierOfPhone(submission.phone()) == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "휴대폰 번호를 확인해 주세요.");
        }
        if (submission.cards() == null || submission.cards().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "명세서를 한 장 이상 올려 주세요.");
        }
    }

    /** 제공자가 보낸 우리말 사유를 꺼낸다. 못 꺼내면 본문을 그대로 보인다 — 감추지 않는다. */
    private String reasonOf(org.springframework.web.client.HttpClientErrorException e) {
        String body = e.getResponseBodyAsString();
        try {
            Map<?, ?> parsed = mapper.readValue(body, Map.class);
            Object message = parsed.get("message");
            if (message != null) return String.valueOf(message);
        } catch (Exception ignored) {
            // JSON 이 아니면 아래로 — 이 모듈의 매퍼는 Jackson 2 라 검사 예외를 던진다.
        }
        return body == null || body.isBlank() ? e.getStatusText() : body;
    }

    /**
     * <b>남의 번호로는 신청할 수 없다.</b>
     *
     * <p>번호는 사람을 특정하는 열쇠다 — 본인인증이 그것으로 명의자를 찾는다. 신청자가 자기
     * 번호를 <b>다른 실사용자의 번호로</b> 잘못 적어 한 번호에 두 사람이 붙은 적이 있고,
     * 그때 그 번호를 쓰는 <b>두 사람 모두</b> 본인인증이 막혔다(2026-08-20 운영).
     * 승인까지 가서야 걸리면 신청자는 며칠 뒤 "반려"만 듣고 무엇이 틀렸는지 모른다.
     * 여기서 물으면 <b>그 자리에서 고칠 수 있다.</b>
     *
     * <p><b>이름·주민번호까지 맞으면 통과시킨다.</b> 이미 등록된 본인이 카드를 더 올리는
     * 재신청이고, 그것까지 막으면 두 번째 카드사 명세서를 영영 못 낸다.
     *
     * <p><b>제공자가 안 뜨면 통과시킨다(fail open).</b> 여기는 친절이지 관문이 아니다 —
     * 진짜 관문은 적재 직전의 {@code ensurePerson} 이고 그쪽은 막힌 채로 죽는다.
     * 제공자 장애 때문에 정상 신청자를 돌려보낼 이유가 없다.
     */
    private void requirePhoneIsYours(Submission submission) {
        MyDataClient.IdentityMatch match;
        try {
            match = myDataClient.matchIdentity(submission.name().trim(),
                    submission.social7().replaceAll("\\D", ""), submission.phone());
        } catch (RuntimeException e) {
            log.warn("신청 단계 번호 확인을 건너뛴다 — 제공자 조회 실패: {}", e.toString());
            return;
        }
        if (match == null) return;
        boolean mine = match.phoneNameOk() && match.phoneSocialOk();
        if (match.phoneTaken() && !mine) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "이 휴대폰 번호는 이미 다른 분 명의로 등록돼 있어요. 번호를 다시 확인해 주세요.");
        }
    }

    /**
     * IP 당 하루 상한.
     *
     * <p>초대코드를 두지 않기로 했으므로 <b>게이트는 승인 하나뿐</b>이다. 그러면 대기열을
     * 쓰레기로 채워 admin 을 마비시킬 수 있다. 정당한 신청자는 상한에 닿지 않는다.
     */
    private void requireQuota(String ip) {
        long recent = repository.countBySubmittedIpAndSubmittedAtAfter(
                ip, LocalDateTime.now(clock).minusDays(1));
        if (recent >= dailyPerIp) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "오늘은 더 신청할 수 없습니다. 내일 다시 시도해 주세요.");
        }
    }

    private static String newTicket() {
        StringBuilder sb = new StringBuilder(9);
        for (int i = 0; i < 8; i++) {
            if (i == 4) sb.append('-');
            sb.append(TICKET_CHARS[RANDOM.nextInt(TICKET_CHARS.length)]);
        }
        return sb.toString();
    }

    private String writeJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("신청 직렬화 실패", exception);
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readJson(String json) {
        try {
            return mapper.readValue(json, List.class);
        } catch (Exception exception) {
            throw new IllegalStateException("신청 역직렬화 실패", exception);
        }
    }

    private static LocalDate earlier(LocalDate left, LocalDate right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isBefore(right) ? left : right;
    }

    private static LocalDate later(LocalDate left, LocalDate right) {
        if (left == null) return right;
        if (right == null) return left;
        return left.isAfter(right) ? left : right;
    }
}
