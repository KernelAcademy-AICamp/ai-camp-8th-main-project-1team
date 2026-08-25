package com.finntech.service;

import com.finntech.domain.MerchantCategory;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.MerchantCategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * <b>소분류와 어긋난 사전 행을 찾아 되돌린다.</b>
 *
 * <h2>왜 필요한가 — 한 번 붙은 답은 스스로 안 고쳐진다</h2>
 *
 * <p>사전에 확정이 적히면 그 가맹점은 <b>다시 묻지 않는다</b>. 그래서 표를 고쳐도 이미 적힌
 * 행은 옛 답을 든 채로 굳는다. 표만 고치고 여기를 안 돌리면 바뀐 것이 아무것도 없다.
 *
 * <h2>무엇이 어긋난 것인가 — 판단이 아니라 대우(對偶)다</h2>
 *
 * <p>소분류는 정확히 한 중분류에만 속한다(빌드와 {@code IndustryCategoryMapper} 가 잠근다).
 * 그러니 <b>적힌 중분류가 소분류가 말하는 중분류와 다르면 둘 중 하나가 틀린 것</b>이고,
 * 확정 지식인 소분류 쪽을 믿는다. 새 규칙이 아니라 위 불변식의 대우라서 따로 판단할 것이 없다.
 *
 * <p>두 번째 무리는 <b>모호한 업종으로 확정된 행</b>이다 — 전자상거래 소매업 같은 이름은
 * 판매 방식만 말하고 무엇을 파는지 말하지 않는데, 그것이 한때 쇼핑으로 확정됐다.
 * 표에서 뺐으므로 이제 그 행들은 <b>근거를 잃은 답</b>이다.
 *
 * <h2>두 무리를 다르게 다룬다 — 답을 아는 것과 모르는 것</h2>
 *
 * <p><b>어긋난 행은 답을 안다.</b> 소분류가 중분류를 결정하므로 옳은 값이 이미 손에 있다.
 * 그러니 되돌려 다시 물을 이유가 없다 — <b>바로 고치고 결제까지 밀어 넣는다.</b>
 *
 * <p><b>모호한 업종으로 확정된 행은 답을 모른다.</b> 근거를 잃었을 뿐 옳은 값이 없다.
 * 그래서 {@code UNRESOLVED} 로 되돌려 큐가 다시 묻게 한다.
 *
 * <h2>사전만 고치면 화면은 안 바뀐다</h2>
 *
 * <p>원장의 분류는 사전이 아니라 <b>{@code UserPayment}</b> 에서 온다
 * ({@code SpendingLedgerRowMapper.factsOf} 가 {@code payment.getCategory2()} 를 읽는다).
 * 그리고 {@code LedgerDirtyListener} 는 {@code MerchantCategory} 에 안 달려 있다 — 사전을
 * 고쳐도 원장이 다시 써지지 않는다. 그 사이를 잇는 것이
 * {@code MyDataLinkService.applyResolved} 뿐이라 여기서 그것을 부른다. 결제가 고쳐지면
 * 리스너가 원장을 표시하고, 배수({@code /spending-ledger/drain})가 다시 쓴다.
 *
 * <h2>왜 지우지 않고 UNRESOLVED 로 두나</h2>
 *
 * <p>지우면 "아직 안 물어봤다"가 되어 조회부터 다시 하고, 그 조회가 또 같은 모호한 업종을
 * 준다. {@code UNRESOLVED} 는 <i>"다 해 봤지만 모른다"</i>라서 큐가 <b>LLM 부터</b> 잇는다 —
 * 이번엔 목록에 전자상거래가 없으니 모델이 무엇을 파는지 말해야 한다.
 */
@Service
public class SubCategorySweeper {

    private static final Logger log = LoggerFactory.getLogger(SubCategorySweeper.class);

    private final MerchantCategoryRepository dictionary;
    private final IndustryCategoryMapper industries;
    /** 표기표로 브랜드를 확정하는 문 — <b>저장된 브랜드는 못 믿는다</b>({@link #subOf} 참조). */
    private final MerchantBrandService brands;
    private final com.finntech.repository.AppUserRepository users;
    private final com.finntech.repository.ReportRepository reports;
    /** 사전의 답을 결제에 입히는 유일한 통로. 무거운 서비스라 필요할 때만 꺼낸다. */
    private final org.springframework.beans.factory.ObjectProvider<MyDataLinkService> linkService;

    public SubCategorySweeper(MerchantCategoryRepository dictionary, IndustryCategoryMapper industries,
                              MerchantBrandService brands,
                              com.finntech.repository.AppUserRepository users,
                              com.finntech.repository.ReportRepository reports,
                              org.springframework.beans.factory.ObjectProvider<MyDataLinkService> linkService) {
        this.dictionary = dictionary;
        this.industries = industries;
        this.brands = brands;
        this.users = users;
        this.reports = reports;
        this.linkService = linkService;
    }

    /**
     * @param dryRun    참이면 세기만 하고 아무것도 안 고친다 — <b>기본값이다</b>
     * @param scanned   본 행 수
     * @param stamped   소분류를 새로 찍은 행 수
     * @param disagreed 중분류가 소분류와 어긋나 되돌린 행 수
     * @param vague     모호한 업종으로 확정돼 되돌린 행 수
     * @param payments  <b>실제로 고친 결제 건수</b> — 여기가 0이면 화면은 안 바뀐다
     * @param samples   무엇이 어떻게 바뀌는지 보여 주는 표본
     */
    public record Result(boolean dryRun, int scanned, int stamped, int disagreed, int vague,
                         int payments, List<String> samples) {}

    /** 표본으로 남길 최대 줄 수. 다 남기면 응답이 로그가 된다. */
    private static final int SAMPLE_LIMIT = 40;

    @Transactional
    public Result sweep(boolean dryRun) {
        List<MerchantCategory> rows = dictionary.findAll();
        List<String> samples = new ArrayList<>();
        java.util.Map<String, String> fixedNames = new TreeMap<>();
        int stamped = 0, disagreed = 0, vague = 0;

        // 정렬 고정 — 같은 입력에 같은 표본이 나와야 두 번 돌려 견줄 수 있다(§4 원칙 3).
        rows.sort(java.util.Comparator.comparing(MerchantCategory::getBusinessNumber)
                .thenComparing(MerchantCategory::getMerchantName));

        for (MerchantCategory row : rows) {
            String before = row.getCategory3();
            String sub = subOf(row);
            if (!java.util.Objects.equals(emptyToNull(sub), before)) {
                if (!dryRun) row.applySub(sub);
                stamped++;
            }

            // ① 중분류가 소분류와 어긋난다 — 소분류를 믿고 되돌린다.
            String expected = industries.midOfSub(sub);
            boolean mismatch = !sub.isEmpty()
                    && !IndustryCategoryMapper.isUnknown(expected)
                    && !expected.equals(row.getCategory2());
            // ② 모호한 업종으로 확정됐다 — 근거를 잃은 답이다.
            boolean lostGround = row.isConfirmed()
                    && row.getRegistryIndustry() != null
                    && IndustryCategoryMapper.isUnknown(industries.midOfIndustryName(row.getRegistryIndustry()));

            if (!mismatch && !lostGround) continue;
            if (mismatch) disagreed++; else vague++;
            if (samples.size() < SAMPLE_LIMIT) {
                samples.add("%s / %s : %s → %s (%s)".formatted(
                        row.getMerchantName(), row.getSource(), row.getCategory2(),
                        mismatch ? expected + " [소분류 " + sub + "]" : "다시 물어봄",
                        mismatch ? "어긋남" : "모호한 업종"));
            }
            if (dryRun) continue;
            // 사람이 손으로 확인한 것은 건드리지 않는다 — 표보다 사람이 위다.
            if (MerchantCategory.Source.USER_CONFIRMED.name().equals(row.getSource())
                    || MerchantCategory.Source.USER_CSV.name().equals(row.getSource())) {
                continue;
            }
            if (mismatch) {
                // **답을 안다.** 소분류가 중분류를 결정하므로 다시 물을 이유가 없다.
                row.reclassify(expected, MerchantCategory.Source.USER_CSV, null, null);
                fixedNames.putIfAbsent(row.getMerchantName(), expected);
            } else {
                // **답을 모른다.** 근거만 잃었으므로 큐가 다시 묻게 되돌린다.
                row.reclassify(IndustryCategoryMapper.UNCLASSIFIED,
                        MerchantCategory.Source.UNRESOLVED, null, null);
            }
        }

        // **사전만 고치면 화면은 안 바뀐다.** 결제까지 밀어 넣어야 원장이 표시되고 다시 써진다.
        int payments = dryRun || fixedNames.isEmpty() ? 0 : pushToPayments(fixedNames);

        Result result = new Result(dryRun, rows.size(), stamped, disagreed, vague, payments, samples);
        log.info("소분류 훑기{} — 본 행 {} · 소분류 찍음 {} · 바로 고침 {} · 모호해 되돌림 {} · 결제 {}건",
                dryRun ? "(맛보기)" : "", rows.size(), stamped, disagreed, vague, payments);
        return result;
    }

    /**
     * 고친 분류를 <b>실제 사람의 결제에 입힌다</b> — 더미는 볼 필요가 없다.
     *
     * <p>사전은 실물에서만 자라므로 여기 걸리는 가맹점은 더미의 원장에 없다. 1,100만 건을
     * 훑어 봐야 0건을 고친다.
     */
    private int pushToPayments(java.util.Map<String, String> fixedNames) {
        MyDataLinkService link = linkService.getObject();
        int fixed = 0;
        // 정렬 고정 — 같은 입력이 같은 순서로 처리돼야 한다(§4 원칙 3).
        java.util.List<Long> realPeople = new java.util.ArrayList<>();
        for (com.finntech.domain.AppUser u : users.findAll()) {
            if (u.isRealPerson()) realPeople.add(u.getId());
        }
        java.util.Collections.sort(realPeople);
        for (Long userId : realPeople) {
            int n = link.applyResolved(userId, fixedNames, "DICT");
            if (n == 0) continue;
            // **리포트 캐시를 깬다.** 안 깨면 사용자는 옛 숫자를 계속 본다 —
            // 소비 원장을 고친 모든 자리가 지키는 규칙이다.
            reports.deleteByUserId(userId);
            fixed += n;
        }
        return fixed;
    }

    /**
     * 그 행의 소분류 — <b>표기표를 상호에 그 자리에서 맞춘다.</b>
     *
     * <p><b>저장된 브랜드를 읽으면 안 된다.</b> 그것은 무료 통로가 답한 추정일 수 있다 —
     * 운영 사전 845행 중 <b>269행</b>의 브랜드가 표기표에 없는 이름이었고, {@code (주)카카오} 는
     * <b>멜론</b>으로, {@code 주식회사 데이원컴퍼니}(온라인 교육)는 <b>배달의민족</b>으로 적혀
     * 있었다(2026-08-25 운영 실측).
     *
     * <p>훑기는 그 답을 <b>{@code USER_CSV} 확정으로 굳힌다</b>. 지어낸 브랜드를 타면
     * 틀린 답이 사람이 검수한 것과 같은 자리에 올라가고, 그다음부터는 아무도 다시 안 묻는다.
     */
    private String subOf(MerchantCategory row) {
        String byBrand = industries.subOfBrand(
                brands.subBrandOf(row.getMerchantName(), industries::hasSub).orElse(""));
        if (!byBrand.isEmpty()) return byBrand;
        // 사실(등록 업종)을 먼저 보고 없을 때만 추정(모델의 답)을 본다 — V43.
        String byName = industries.subOfIndustryName(row.getRegistryIndustry());
        if (byName.isEmpty()) byName = industries.subOfIndustryName(row.getLlmIndustry());
        // **어긋나면 안 찍는다.** 업종 이름은 어느 쪽이 틀렸는지를 말해 주지 않는다 —
        // 그냥 적으면 "식비인데 소분류는 커피" 같은 행이 남는다. 브랜드만이 중분류를 이긴다.
        if (byName.isEmpty()) return "";
        String expected = industries.midOfSub(byName);
        return IndustryCategoryMapper.isUnknown(expected) || expected.equals(row.getCategory2())
                ? byName : "";
    }

    /** 무엇이 몇 개나 어긋나는지 소분류별로 — 고치기 전에 규모를 보는 자리. */
    @Transactional(readOnly = true)
    public java.util.Map<String, Integer> byMid() {
        java.util.Map<String, Integer> counts = new TreeMap<>();
        for (MerchantCategory row : dictionary.findAll()) {
            String sub = subOf(row);
            if (sub.isEmpty()) continue;
            String expected = industries.midOfSub(sub);
            if (IndustryCategoryMapper.isUnknown(expected) || expected.equals(row.getCategory2())) continue;
            counts.merge(row.getCategory2() + " → " + expected, 1, Integer::sum);
        }
        return counts;
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
