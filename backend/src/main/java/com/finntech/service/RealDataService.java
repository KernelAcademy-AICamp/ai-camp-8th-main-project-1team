package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.Enums;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.CategoryRepository;
import com.finntech.repository.ConsumptionRepository;
import com.finntech.repository.AppUserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * <b>실제 사람 한 명의 카드 사용내역을 받는다</b> (2026-08-02).
 *
 * <p><b>왜 만드나.</b> 이 앱의 데이터는 전부 생성기가 만든 것이고, "시간이 지날수록 낭비가
 * 줄어든다"는 서비스 효과도 <b>생성기가 심어 놓은 가정</b>이다. 모델이 발견한 게 아니다.
 * 실제 사람의 소비를 한 번이라도 통과시켜 봐야 "우리 판정이 진짜 사람에게도 말이 되는가"를
 * 말할 수 있다. 그 전까지 이 앱이 증명한 것은 <b>자기 가정을 재현한다</b>는 것뿐이다.
 *
 * <h2>세 가지를 못박아 둔다</h2>
 *
 * <p><b>① 더미와 절대 안 섞는다.</b> 전용 계정 하나에만 넣고 {@code source=CARD_UPLOAD}로
 * 표시한다. 섞이면 "무엇이 진짜였는지"를 나중에 되물을 수 없고, 그 순간 검증이 아니라 오염이 된다.
 *
 * <p><b>② 학습에 쓰지 않는다.</b> 실데이터로 모델을 다시 학습하면 그건 검증이 아니다 —
 * 시험 문제를 교재에 넣는 것과 같다. 학습은 {@code ml/train.py}가 제공자 DB에서만 읽으므로
 * 구조적으로 이미 분리돼 있고, 이 경로는 <b>본체 DB에만</b> 쓴다.
 *
 * <p><b>③ 지우는 길을 함께 만든다.</b> 여기 들어오는 것은 <b>실제 개인정보</b>다. 넣는 길만
 * 만들고 빼는 길을 미루면, 미룬 그 상태가 기본값이 된다. {@code CARD_UPLOAD}는 이미
 * {@code PrivacyService}의 파기·철회 대상이고, 여기에 즉시 전량 삭제를 더 둔다.
 *
 * <h2>신원</h2>
 * 계정은 <b>닉네임만</b> 갖는다. 이름·전화번호·주민번호는 받지 않는다 — 마이데이터 연동 계정과
 * 달리 CI를 만들 이유가 없고, 안 받는 것이 가장 확실한 보호다. 소유자를 표시할 필요가 생기면
 * 그때 닉네임만 바꾸면 된다.
 */
@Service
public class RealDataService {

    /** 전용 계정을 알아보는 표식. 사람 이름을 쓰지 않는다 — 계정 자체가 신원을 말하면 안 된다. */
    public static final String ACCOUNT_NICKNAME = "실데이터-검증용";

    /**
     * 명세서가 쓰는 날짜 표기들. 카드사마다 다르고, 한 파일 안에서도 섞여 나온다.
     * <b>못 읽은 줄은 버리지 않고 사유와 함께 돌려준다</b> — 조용히 건너뛰면 몇 건이 왜
     * 빠졌는지 아무도 모른다({@code tech_log} §8-U에서 배운 것이다).
     */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yy-MM-dd"),
            DateTimeFormatter.ofPattern("yy.MM.dd"));

    private final AppUserRepository userRepository;
    private final ConsumptionRepository consumptionRepository;
    private final CategoryRepository categoryRepository;
    private final IndustryCategoryMapper industryMapper;

    public RealDataService(AppUserRepository userRepository, ConsumptionRepository consumptionRepository,
                           CategoryRepository categoryRepository, IndustryCategoryMapper industryMapper) {
        this.userRepository = userRepository;
        this.consumptionRepository = consumptionRepository;
        this.categoryRepository = categoryRepository;
        this.industryMapper = industryMapper;
    }

    /** 한 줄의 결과 — 들어갔거나, 안 들어갔거나. 안 들어간 이유를 반드시 들고 있다. */
    public record RowResult(int line, boolean accepted, String reason, String raw) {}

    public record ImportResult(Long userId, int accepted, int rejected, List<RowResult> problems) {}

    public record Row(LocalDate date, String merchant, long amount, String ksicCode) {}

    /**
     * 전용 계정을 찾거나 만든다. <b>이미 있으면 그것을 쓴다</b> — 부를 때마다 새로 만들면
     * 실데이터가 여러 계정에 흩어져 ①(안 섞는다)이 깨진다.
     */
    @Transactional
    public AppUser account() {
        return userRepository.findAll().stream()
                .filter(u -> ACCOUNT_NICKNAME.equals(u.getNickname()))
                .findFirst()
                .orElseGet(() -> userRepository.save(
                        new AppUser(ACCOUNT_NICKNAME, BigDecimal.ZERO, BigDecimal.ZERO, 6)));
    }

    /**
     * 카드사 명세서 CSV를 적재한다.
     *
     * <p>형식: {@code 날짜,가맹점,금액[,업종코드]}. 업종코드는 명세서에 없는 것이 보통이라
     * 선택이다 — 없으면 카테고리를 {@code 카테고리없음}으로 두고, 판정은 하지 않는다.
     * <b>모르는 것을 아는 척 분류하지 않는다</b>(마스터 §4 원칙 4와 같은 태도다).
     *
     * <p>머리글 줄(첫 칸이 날짜로 안 읽히는 줄)과 빈 줄·{@code #} 주석은 넘어간다.
     */
    @Transactional
    public ImportResult importCsv(String csv) {
        AppUser user = account();
        List<RowResult> problems = new ArrayList<>();
        int accepted = 0, rejected = 0;
        String[] lines = csv == null ? new String[0] : csv.split("\\r?\\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] c = splitCsv(line);
            if (c.length < 3) {
                // 머리글일 가능성이 높은 첫 줄은 조용히 넘긴다 — 오류로 세면 매번 1건이 실패로 뜬다.
                if (i == 0) continue;
                rejected++; problems.add(new RowResult(i + 1, false, "칸이 3개 미만", line));
                continue;
            }
            Optional<LocalDate> d = parseDate(c[0]);
            if (d.isEmpty()) {
                if (i == 0) continue;   // 머리글
                rejected++; problems.add(new RowResult(i + 1, false, "날짜를 못 읽음: " + c[0], line));
                continue;
            }
            long amount = parseAmount(c[2]);
            if (amount <= 0) {
                // 취소·환불(음수)은 지금 단계에서 안 받는다. 버리는 게 아니라 <b>모아서 보여준다</b>.
                rejected++;
                problems.add(new RowResult(i + 1, false,
                        amount < 0 ? "취소·환불(음수)은 아직 안 받아요" : "금액을 못 읽음: " + c[2], line));
                continue;
            }
            String merchant = c[1].trim();
            String ksic = c.length >= 4 ? c[3].trim() : null;
            save(user, new Row(d.get(), merchant, amount, ksic));
            accepted++;
        }
        return new ImportResult(user.getId(), accepted, rejected, List.copyOf(problems));
    }

    /**
     * 한 건 직접 입력. 명세서에는 결측·이상치가 있어 <b>손으로 고칠 자리</b>가 필요하다 —
     * CSV만 두면 못 읽은 줄이 영영 안 들어간다.
     */
    @Transactional
    public Long addOne(LocalDate date, String merchant, long amount, String ksicCode) {
        AppUser user = account();
        save(user, new Row(date, merchant, amount, ksicCode));
        return user.getId();
    }

    /** 전량 파기 — 실제 개인정보이므로 넣는 길과 같은 무게로 둔다. */
    @Transactional
    public long purge() {
        AppUser user = account();
        long before = consumptionRepository.countByUserIdAndSource(user.getId(), Enums.DataSource.CARD_UPLOAD);
        consumptionRepository.deleteByUserIdAndSource(user.getId(), Enums.DataSource.CARD_UPLOAD);
        return before;
    }

    private void save(AppUser user, Row r) {
        String mid = r.ksicCode() == null || r.ksicCode().isBlank()
                ? IndustryCategoryMapper.UNCLASSIFIED
                : industryMapper.midOf(r.ksicCode());
        Category cat = categoryRepository.findByCode(mid)
                .orElseGet(() -> categoryRepository.save(new Category(mid, mid)));
        // 명세서는 시각을 안 준다. 정오로 둔다 — 심야 결제(night) 축이 <b>거짓으로 켜지지 않게</b>
        // 하려는 것이다. 모르는 시각을 0시로 두면 모든 결제가 심야가 된다.
        LocalDateTime at = r.date().atTime(12, 0);
        consumptionRepository.save(new Consumption(user.getId(), cat,
                BigDecimal.valueOf(r.amount()), at, false, Enums.DataSource.CARD_UPLOAD));
    }

    /** 따옴표로 감싼 칸을 살린다 — 가맹점명에 쉼표가 흔하다("스타벅스 강남R점, 1층"). */
    private static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') { quoted = !quoted; continue; }
            if (ch == ',' && !quoted) { out.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(ch);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static Optional<LocalDate> parseDate(String raw) {
        String s = raw == null ? "" : raw.trim().replaceAll("[()]", "");
        if (s.isEmpty()) return Optional.empty();
        for (DateTimeFormatter f : DATE_FORMATS) {
            try { return Optional.of(LocalDate.parse(s, f)); } catch (RuntimeException ignored) { /* 다음 형식 */ }
        }
        return Optional.empty();
    }

    /** "12,000원" · "₩12,000" · "-3,000" 을 읽는다. 음수는 부호를 살려 돌려준다(호출부가 가른다). */
    private static long parseAmount(String raw) {
        if (raw == null) return 0;
        String s = raw.trim();
        boolean negative = s.startsWith("-") || s.startsWith("−");
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            long v = Long.parseLong(digits);
            return negative ? -v : v;
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
