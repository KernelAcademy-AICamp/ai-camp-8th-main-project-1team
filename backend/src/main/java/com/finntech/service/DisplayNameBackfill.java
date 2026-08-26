package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.domain.UserPayment;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.ReportRepository;
import com.finntech.repository.UserPaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>소비내역에 적을 이름을 결제 행에 적어 둔다</b>(V44).
 *
 * <h2>왜 화면에서 계산하지 않나</h2>
 *
 * <p>표시명을 정하려면 표기 1,200여 개를 훑어야 한다. 목록을 열 때마다 하면 같은 상호를
 * 수백 번 다시 푼다. 그리고 이것은 <b>렌더링 결과가 아니라 사실</b>이다 — <i>"이 가맹점명은
 * 이렇게 읽는다"</i>. {@code category2}·{@code ksic_code} 를 결제 행에 적어 둔 것과 같은 이유다.
 *
 * <h2>언제 채워지나</h2>
 *
 * <ul>
 *   <li><b>연동이 끝날 때</b> — {@code MyDataLinkService} 가 {@link #runFor} 를 부른다.
 *       이 자리가 없으면 새 사용자는 영영 빈 칸을 본다.</li>
 *   <li><b>밤에 한 번</b> — 표기표·PG 목록을 고치면 옛 결제의 표시명도 따라 움직여야 한다.</li>
 *   <li><b>관리자 문</b> — 표를 고친 직후 바로 확인하는 자리. 기본은 맛보기다.</li>
 * </ul>
 *
 * <p><b>실제 사람의 결제만 본다.</b> 생성기의 상호는 브랜드로 조립된 것이라 PG 잡음이 없고,
 * 1,100만 건을 훑어 봐야 고칠 것이 없다.
 */
@Service
public class DisplayNameBackfill {

    private static final Logger log = LoggerFactory.getLogger(DisplayNameBackfill.class);

    private final AppUserRepository users;
    private final UserPaymentRepository payments;
    private final MerchantBrandService brands;
    private final MerchantDisplayName displayNames;
    private final ReportRepository reports;

    public DisplayNameBackfill(AppUserRepository users, UserPaymentRepository payments,
                               MerchantBrandService brands, MerchantDisplayName displayNames,
                               ReportRepository reports) {
        this.users = users;
        this.payments = payments;
        this.brands = brands;
        this.displayNames = displayNames;
        this.reports = reports;
    }

    /**
     * @param scanned  본 결제
     * @param fixed    표시명을 새로 적거나 고친 결제
     * @param bySource 무엇으로 정했는지의 분포 — 경유만 아는 행이 몇인지 보는 자리다
     */
    public record Result(boolean dryRun, int scanned, int fixed,
                         Map<String, Integer> bySource, List<String> samples) {}

    /** 표본으로 남길 최대 줄 수. 다 남기면 응답이 로그가 된다. */
    private static final int SAMPLE_LIMIT = 40;

    /**
     * 밤에 한 번 — <b>표를 고치면 옛 결제도 따라 움직여야 한다.</b>
     *
     * <p>{@code IndustryCodeBackfill}(05:10) 뒤에 둔다. 두 배치가 같은 결제 행을 동시에 쓰면
     * 낙관적 잠금이 부딪힌다.
     */
    @Scheduled(cron = "${finntech.display-name.backfill-cron:0 20 5 * * *}", zone = "Asia/Seoul")
    public void nightly() {
        run(false);
    }

    /** <b>실사용자 전원</b>을 훑는다 — 관리자 문이 부르는 자리. */
    @Transactional
    public Result run(boolean dryRun) {
        List<Long> realPeople = new ArrayList<>();
        for (AppUser u : users.findAll()) if (u.isRealPerson()) realPeople.add(u.getId());
        Collections.sort(realPeople);            // 정렬 고정 (§4 원칙 3)
        return run(realPeople, dryRun);
    }

    /**
     * <b>한 사람만</b> 훑는다 — 연동 끝이 부르는 자리.
     *
     * <p>여기가 없으면 <b>표시명은 새 사용자에게 영영 안 붙는다.</b> 관리자가 손으로 누를 때만
     * 도는 값이 되고, 명세서를 넣은 사람은 긴 PG 상호를 그대로 본다.
     */
    @Transactional
    public Result runFor(Long userId, boolean dryRun) {
        return run(List.of(userId), dryRun);
    }

    private Result run(List<Long> realPeople, boolean dryRun) {
        Map<String, Integer> bySource = new TreeMap<>();
        List<String> samples = new ArrayList<>();
        int scanned = 0, fixed = 0;

        for (Long userId : realPeople) {
            boolean touched = false;
            // **같은 상호는 한 번만 푼다.** 한 사람의 12개월치에 같은 가맹점이 여러 번 나온다.
            Map<String, MerchantDisplayName.Shown> memo = new HashMap<>();
            for (UserPayment p : payments.findByUserIdOrderByPaymentDateDesc(userId)) {
                String name = p.getMerchantName();
                if (name == null || name.isBlank()) continue;
                scanned++;
                MerchantDisplayName.Shown shown = memo.computeIfAbsent(
                        key(name, p.getBusinessNumber()),
                        ignored -> displayNames.of(name, p.getBusinessNumber(),
                                brands.shownBrandOf(name).orElse(null)));
                if (shown.display().isBlank()) continue;
                bySource.merge(shown.source().name(), 1, Integer::sum);
                if (samples.size() < SAMPLE_LIMIT && !shown.display().equals(name)) {
                    samples.add("%s -> %s [%s%s]".formatted(name, shown.display(), shown.source(),
                            shown.viaAgency() == null ? "" : " / " + shown.viaAgency()));
                }
                if (dryRun) continue;
                if (p.learnDisplayName(shown.display(), shown.source().name(), shown.viaAgency())) {
                    fixed++;
                    touched = true;
                }
            }
            // **리포트 캐시를 깬다.** 안 깨면 사용자는 옛 이름을 계속 본다 — 결제 행을 고친
            // 모든 자리가 지키는 규칙이다.
            if (touched) reports.deleteByUserId(userId);
        }

        Result result = new Result(dryRun, scanned, fixed, bySource, samples);
        // **0 도 정보다.** 대상이 없으면 없다고 남겨야 "왜 안 붙나"를 좁힐 수 있다.
        log.info("표시명 적기{} - 사람 {} / 본 결제 {} / 고친 결제 {} / 출처 {}",
                dryRun ? "(맛보기)" : "", realPeople.size(), scanned, fixed, bySource);
        return result;
    }

    /** 표시명은 상호와 <b>사업자번호</b>가 함께 정한다 — 같은 상호라도 PG 를 타면 답이 다르다. */
    private static String key(String name, String businessNumber) {
        return name + "\u0001" + (businessNumber == null ? "" : businessNumber);
    }
}
