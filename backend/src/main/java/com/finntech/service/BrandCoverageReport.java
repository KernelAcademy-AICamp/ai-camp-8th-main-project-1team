package com.finntech.service;

import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.SpendingLedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>브랜드가 상호에 제대로 붙는지 — 운영 데이터로 되묻는 문.</b>
 *
 * <h2>왜 필요한가 — 회사명이 서비스를 가린다</h2>
 *
 * <p>브랜드 표는 <b>회사명과 서비스명을 갈라</b> 둔다({@code 카카오} 는 회사, {@code 카카오T} 는
 * 택시). 회사명에는 소분류를 안 붙이므로, 어떤 상호가 <b>회사명에만 걸리면 소분류를 영원히
 * 못 얻는다</b>. {@code 카카오스타일}(지그재그 운영사)이 그랬고 {@code 티머니지하철} 도 그랬다.
 *
 * <p>이건 표를 고칠 때마다 새로 생긴다 — 회사를 하나 넣을 때마다 그 회사의 서비스들이
 * 같이 들어와야 하기 때문이다. 손으로 찾으면 놓친다. <b>그래서 묻는 문을 둔다.</b>
 *
 * <h2>무엇을 답하나</h2>
 *
 * <p>소분류를 못 얻은 상호를 <b>무엇에 걸렸는지별로</b> 묶어 준다. {@code 카카오} 아래
 * 상호가 쌓여 있으면 그것이 바로 "카카오의 서비스 표기가 빠졌다"는 신호다. 아무 브랜드에도
 * 안 걸린 것은 따로 모은다 — 그쪽은 개인 상호이거나 결제대행사 통과분이다.
 *
 * <p>값을 고치지 않는다. 읽기만 한다.
 */
@Service
public class BrandCoverageReport {

    private final SpendingLedgerRepository ledger;
    private final MerchantBrandService brands;
    private final IndustryCategoryMapper industries;

    public BrandCoverageReport(SpendingLedgerRepository ledger, MerchantBrandService brands,
                               IndustryCategoryMapper industries) {
        this.ledger = ledger;
        this.brands = brands;
        this.industries = industries;
    }

    /**
     * @param merchants   본 상호 수
     * @param withSub     소분류를 얻은 상호 수
     * @param shadowed    <b>회사명·결제수단에만 걸려 못 얻은</b> 상호 — 브랜드별로 묶었다
     * @param unmatched   아무 브랜드에도 안 걸린 상호 수(개인 상호·PG 통과분)
     * @param samples     구멍이 큰 브랜드의 상호 표본
     */
    public record Result(int merchants, int withSub, Map<String, Integer> shadowed,
                         int unmatched, List<String> samples) {}

    /** 한 브랜드에서 보여 줄 표본 수. 다 보여 주면 응답이 로그가 된다. */
    private static final int SAMPLES_PER_BRAND = 5;

    @Transactional(readOnly = true)
    public Result scan(String origin) {
        Map<String, Integer> shadowed = new TreeMap<>();
        Map<String, List<String>> byBrand = new TreeMap<>();
        int withSub = 0, unmatched = 0;

        List<String> names = ledger.findDistinctMerchantNamesByOrigin(origin);
        for (String name : names) {
            if (industries.hasSub(brands.subBrandOf(name, industries::hasSub).orElse(""))) {
                withSub++;
                continue;
            }
            List<String> matched = brands.brandsInName(name);
            if (matched.isEmpty()) {
                unmatched++;
                continue;
            }
            // 걸리긴 했는데 전부 소분류가 없는 브랜드다 — 회사명이 서비스를 가린 자리.
            String blame = matched.get(0);
            shadowed.merge(blame, 1, Integer::sum);
            byBrand.computeIfAbsent(blame, k -> new ArrayList<>()).add(name);
        }

        // 구멍이 큰 브랜드부터 보여 준다 — 고칠 순서가 곧 크기 순서다.
        List<String> samples = new ArrayList<>();
        byBrand.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, List<String>>>comparingInt(e -> e.getValue().size()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .limit(10)
                .forEach(e -> e.getValue().stream().sorted().limit(SAMPLES_PER_BRAND)
                        .forEach(n -> samples.add(e.getKey() + " ← " + n)));
        return new Result(names.size(), withSub, shadowed, unmatched, samples);
    }
}
