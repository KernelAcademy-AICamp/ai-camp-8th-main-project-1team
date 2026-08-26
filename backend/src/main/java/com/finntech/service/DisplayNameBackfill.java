package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.UserPayment;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.CategoryRepository;
import com.finntech.repository.ConsumptionRepository;
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
    private final TempClassifierService temporary;
    private final ReportRepository reports;
    private final ConsumptionRepository consumptions;
    private final CategoryRepository categories;
    private final com.finntech.ledger.SpendingLedgerDirtyMarker ledgerDirty;

    public DisplayNameBackfill(AppUserRepository users, UserPaymentRepository payments,
                               MerchantBrandService brands, MerchantDisplayName displayNames,
                               TempClassifierService temporary, ReportRepository reports,
                               ConsumptionRepository consumptions, CategoryRepository categories,
                               com.finntech.ledger.SpendingLedgerDirtyMarker ledgerDirty) {
        this.users = users;
        this.payments = payments;
        this.brands = brands;
        this.displayNames = displayNames;
        this.temporary = temporary;
        this.reports = reports;
        this.consumptions = consumptions;
        this.categories = categories;
        this.ledgerDirty = ledgerDirty;
    }

    /**
     * @param scanned  본 결제
     * @param fixed    표시명을 새로 적거나 고친 결제
     * @param bySource 무엇으로 정했는지의 분포 — 경유만 아는 행이 몇인지 보는 자리다
     */
    public record Result(boolean dryRun, int scanned, int fixed, int simplePay,
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
        // 규칙으로 못 줄인 이름 — 이번 회차가 끝나면 큐에 올린다(중복 없이 한 번씩).
        java.util.Set<String> tooLong = new java.util.LinkedHashSet<>();
        int scanned = 0, fixed = 0, simplePay = 0;

        for (Long userId : realPeople) {
            boolean touched = false;
            boolean markedSimplePay = false;
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
                // **최후의 수단** — 규칙을 다 거치고도 여전히 길면 모델에게 줄이기를 맡긴다.
                // 있으면 쓰고 없으면 큐에 올린다 — 이번 회차는 규칙이 정한 이름을 그대로
                // 쓰고, 답이 오면 다음 회차가 집어 간다. 화면이 빈칸이 되는 일은 없다.
                if (shown.display().length() > MerchantDisplayName.TOO_LONG
                        && shown.source() != MerchantDisplayName.Source.BRAND) {
                    tooLong.add(shown.display());
                    var cut = temporary.shortened(shown.display());
                    if (cut.isPresent()) {
                        shown = new MerchantDisplayName.Shown(cut.get(),
                                MerchantDisplayName.Source.MODEL_SHORT, shown.viaAgency());
                    }
                }
                bySource.merge(shown.source().name(), 1, Integer::sum);
                // **결제대행사 자신이면 카테고리도 그렇게 적는다.** 걷어내니 아무것도 안 남은
                // 결제는 무엇을 샀는지 원리적으로 알 수 없다 — 그런데 운영에서 179건 중
                // 142건에 카테고리가 붙어 있었고 `NICE_통신판매` 79건이 <b>쇼핑</b>이었다
                // (2026-08-26 실측). 여기가 그 길을 막는 자리다.
                if (shown.source() == MerchantDisplayName.Source.AGENCY_ONLY && !dryRun
                        && p.markSimplePay()) {
                    simplePay++;
                    touched = true;
                    markedSimplePay = true;
                    // **원장의 짝도 함께 고친다.** 분석·리포트·점수가 읽는 것은 `Consumption`
                    // 이고, 결제만 고치면 화면과 계산이 갈린다.
                    for (Consumption c : consumptions.findBySourcePaymentId(p.getPaymentId())) {
                        c.reclassify(simplePayCategory());
                    }
                }
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
            // **소비 원장에도 알린다.** 분류를 바꿔 놓고 안 알리면 원장이 옛 카테고리를 든
            // 채로 남고, 낭비 판정도 그대로 산다 — 판정 갱신은 <b>사실이 바뀌었는가</b>
            // (`wasteRecordedAt < factsUpdatedAt`)를 보는데 그 사실이 안 움직이기 때문이다.
            // 실제로 그렇게 낭비 5건 92,850원이 남았다(2026-08-26 운영 실측).
            if (markedSimplePay) {
                ledgerDirty.mark(userId, com.finntech.domain.SpendingLedgerDirty.Reason.CATEGORY);
            }
        }

        // **줄이기는 마지막에 한 번만 올린다.** 결제마다 올리면 같은 이름이 큐를 채운다.
        if (!dryRun && !tooLong.isEmpty()) {
            temporary.shorten(new ArrayList<>(tooLong), MerchantDisplayName.TOO_LONG,
                    com.finntech.freechannel.Lane.USER_BACKGROUND);
        }

        Result result = new Result(dryRun, scanned, fixed, simplePay, bySource, samples);
        // **0 도 정보다.** 대상이 없으면 없다고 남겨야 "왜 안 붙나"를 좁힐 수 있다.
        log.info("표시명 적기{} - 사람 {} / 본 결제 {} / 고친 결제 {} / 간편결제 {} / 출처 {}",
                dryRun ? "(맛보기)" : "", realPeople.size(), scanned, fixed, simplePay, bySource);
        return result;
    }

    /** `간편결제` 분류 행 — 없으면 만든다({@code CategoryPromotionService} 와 같은 방식). */
    private Category simplePayCategory() {
        return categories.findByCode(IndustryCategoryMapper.SIMPLE_PAY)
                .orElseGet(() -> categories.save(new Category(
                        IndustryCategoryMapper.SIMPLE_PAY, IndustryCategoryMapper.SIMPLE_PAY)));
    }

    /** 표시명은 상호와 <b>사업자번호</b>가 함께 정한다 — 같은 상호라도 PG 를 타면 답이 다르다. */
    private static String key(String name, String businessNumber) {
        return name + "\u0001" + (businessNumber == null ? "" : businessNumber);
    }
}
