package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.domain.UserPayment;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.MerchantCategoryRepository;
import com.finntech.repository.ReportRepository;
import com.finntech.repository.UserPaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>비어 있는 업종코드를 소분류에서 되찾는다</b> — 확정과 추정을 갈라서.
 *
 * <h2>왜 필요한가 — 카드추천이 죽어 있다</h2>
 *
 * <p>카드 혜택축은 중분류가 아니라 <b>업종코드</b>로 정해진다({@code cardAxisOf}). 그런데
 * 실 명세서에는 업종코드가 없어 적재기가 자리채움값을 넣고, 그 값은 대조표에 없다.
 * 실사용자 원장 2,135건 중 <b>1,653건(77%)</b>이 그 상태였다(2026-08-25 실측).
 *
 * <h2>어디까지 되찾을 수 있나 — 확정은 40%가 한계다</h2>
 *
 * <p>소분류 170개 중 업종 하나를 가리키는 것은 <b>62개</b>뿐이다({@code codesOfSub}).
 * 그 밖에는 소분류 하나에 업종이 여럿이라(예: {@code 한식} 은 넷) 어느 코드인지 알 수 없다.
 * 브랜드가 안 붙는 상호도 절반이다.
 *
 * <p><b>그래서 두 칸을 쓴다.</b> 1:1 로 떨어지면 확정 칸({@code ksic_code}), 후보가 여럿이면
 * 중분류로 좁혀 첫 것을 추정 칸({@code ksic_code_llm})에 적는다. 한 칸에 섞으면 읽는 쪽이
 * 추정을 사실로 쓴다 — {@code category2}/{@code category2Llm} 을 가른 것과 같은 이유다.
 *
 * <p><b>모델에게 코드를 묻지 않는다.</b> 6자리 숫자는 모델이 추론하지 못하고 외운 것에 기대는데
 * 국세청은 구 분류 세대라 그 기억이 틀린다. 모델은 업종 <b>이름</b>만 답하고(원칙 1 그대로),
 * 이름→코드는 우리 표가 옮긴다. 여기서는 이미 정해진 소분류에서 옮기므로 호출이 아예 없다.
 */
@Service
public class IndustryCodeBackfill {

    private static final Logger log = LoggerFactory.getLogger(IndustryCodeBackfill.class);

    private final AppUserRepository users;
    private final UserPaymentRepository payments;
    private final MerchantCategoryRepository dictionary;
    private final IndustryCategoryMapper industries;
    private final MerchantBrandService brands;
    private final ReportRepository reports;

    public IndustryCodeBackfill(AppUserRepository users, UserPaymentRepository payments,
                                MerchantCategoryRepository dictionary, IndustryCategoryMapper industries,
                                MerchantBrandService brands, ReportRepository reports) {
        this.users = users;
        this.payments = payments;
        this.dictionary = dictionary;
        this.industries = industries;
        this.brands = brands;
        this.reports = reports;
    }

    /**
     * @param dryRun    참이면 세기만 한다 — <b>기본값</b>
     * @param scanned   본 결제 수
     * @param confirmed 확정 칸을 채운 결제 — 소분류가 업종 하나를 가리켰다
     * @param guessed   추정 칸을 채운 결제 — 후보가 여럿이라 중분류로 좁혔다
     * @param unknown   둘 다 못 채운 결제 — 소분류가 없거나 코드가 안 나온다
     * @param axes      되살아난 카드 혜택축별 결제 수
     */
    public record Result(boolean dryRun, int scanned, int confirmed, int guessed, int unknown,
                         Map<String, Integer> axes, List<String> samples) {}

    private static final int SAMPLE_LIMIT = 30;

    /**
     * <b>밤에 한 번 훑는다</b> — 05:10, 판정 갱신(04:50) 다음이다.
     *
     * <p>순서가 중요하다. 업종코드는 <b>소분류에서 되찾는</b> 값이라, 그날 큐가 채운 사전이
     * 먼저 반영돼야 건질 것이 있다. 연동 때 브랜드가 안 붙던 상호가 나중에 붙는 일이 흔하고,
     * 그때 결제의 업종코드를 다시 볼 사람이 <b>여기뿐</b>이다.
     *
     * <p>모델을 안 부르므로 비용이 없다 — 표에서 옮기는 일이고 DB 읽기가 전부다.
     * 고칠 것이 없으면 아무 일도 안 한다.
     */
    @org.springframework.scheduling.annotation.Scheduled(cron = "${finntech.industry-code.backfill-cron:0 10 5 * * *}")
    public void nightly() {
        Result r = run(false);
        if (r.confirmed() + r.guessed() > 0) {
            log.info("업종코드 되찾기(야간) — 확정 {} · 추정 {} · 살아난 축 {}",
                    r.confirmed(), r.guessed(), r.axes());
        }
    }

    /**
     * <b>실사용자 전원</b>을 훑는다 — 관리자 문이 부르는 자리.
     */
    @Transactional
    public Result run(boolean dryRun) {
        List<Long> realPeople = new ArrayList<>();
        for (AppUser u : users.findAll()) if (u.isRealPerson()) realPeople.add(u.getId());
        java.util.Collections.sort(realPeople);          // 정렬 고정 (§4 원칙 3)
        return run(realPeople, dryRun);
    }

    /**
     * <b>한 사람만</b> 훑는다 — 연동 끝과 밤 배치가 부르는 자리.
     *
     * <p>여기가 없으면 <b>추정 칸은 영영 안 채워진다</b>. 관리자가 손으로 누를 때만 도는
     * 값이 되고, 새 사용자는 명세서를 넣어도 비어 있는 칸을 본다.
     */
    @Transactional
    public Result runFor(Long userId, boolean dryRun) {
        return run(List.of(userId), dryRun);
    }

    private Result run(List<Long> realPeople, boolean dryRun) {
        // 가맹점명 → 소분류. 사전을 한 번만 읽는다 — 결제마다 조회하면 같은 질의가 수천 번 나간다.
        Map<String, String> subByName = new HashMap<>();
        dictionary.findAll().forEach(row -> {
            if (row.getCategory3() != null && !row.getCategory3().isBlank()) {
                subByName.putIfAbsent(row.getMerchantName(), row.getCategory3());
            }
        });

        Map<String, Integer> axes = new TreeMap<>();
        List<String> samples = new ArrayList<>();
        int scanned = 0, confirmed = 0, guessed = 0, unknown = 0;
        boolean touched = false;

        for (Long userId : realPeople) {
            boolean fixedForUser = false;
            for (UserPayment p : payments.findByUserIdOrderByPaymentDateDesc(userId)) {
                if (!UserPayment.PLACEHOLDER_INDUSTRY.equals(p.getKsicCode())) continue;  // 확정이 이미 있다
                scanned++;
                String sub = subOf(p, subByName);
                if (sub.isEmpty()) { unknown++; continue; }

                List<String> exact = industries.codesOfSub(sub);
                if (!exact.isEmpty()) {
                    confirmed++;
                    axes.merge(industries.cardAxisOf(exact.get(0)), 1, Integer::sum);
                    note(samples, p, sub, exact.get(0), "확정");
                    if (!dryRun && p.learnIndustryCode(exact.get(0))) { fixedForUser = true; touched = true; }
                    continue;
                }
                String guess = industries.guessCodeOfSub(sub, p.getCategory2());
                if (guess.isEmpty()) { unknown++; continue; }
                guessed++;
                note(samples, p, sub, guess, "추정");
                if (!dryRun && p.guessIndustryCode(guess)) { fixedForUser = true; touched = true; }
            }
            // **리포트 캐시를 깬다.** 안 깨면 사용자는 옛 숫자를 계속 본다.
            if (fixedForUser) reports.deleteByUserId(userId);
        }

        Result result = new Result(dryRun, scanned, confirmed, guessed, unknown, axes, samples);
        // **0 도 정보다.** 대상이 없으면 없다고 남겨야 "왜 안 채워지나"를 좁힐 수 있다.
        log.info("업종코드 되찾기{} — 사람 {} · 본 결제 {} · 확정 {} · 추정 {} · 못 채움 {}",
                dryRun ? "(맛보기)" : "", realPeople.size(), scanned, confirmed, guessed, unknown);
        if (touched) log.info("업종코드 되찾기 — 카드 혜택축이 살아난 결제: {}", axes);
        return result;
    }

    /**
     * 그 결제의 소분류 — 사전에 적힌 것을 먼저 보고, 없으면 표기표를 상호에 맞춘다.
     *
     * <p>사전을 먼저 보는 이유는 <b>사람이 확인한 것이 거기 있기 때문</b>이다. 표기표는
     * 사전이 아직 모르는 상호를 받는다.
     */
    private String subOf(UserPayment p, Map<String, String> subByName) {
        String fromDictionary = subByName.get(p.getMerchantName());
        if (fromDictionary != null) return fromDictionary;
        return industries.subOfBrand(
                brands.subBrandOf(p.getMerchantName(), industries::hasSub).orElse(""));
    }

    private void note(List<String> samples, UserPayment p, String sub, String code, String kind) {
        if (samples.size() >= SAMPLE_LIMIT) return;
        samples.add("%s : %s → %s (%s, %s)".formatted(
                p.getMerchantName(), sub, code, industries.cardAxisOf(code), kind));
    }
}
