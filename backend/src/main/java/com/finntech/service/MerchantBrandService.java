package com.finntech.service;

import com.finntech.domain.MerchantBrand;
import com.finntech.domain.MerchantCategory;
import com.finntech.repository.MerchantBrandRepository;
import com.finntech.repository.MerchantCategoryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 가맹점명에서 <b>브랜드</b>를 뽑아 두 곳에 나눠 담는다.
 *
 * <pre>
 *   merchant_category.brand   확정된 가맹점의 브랜드 — 사전과 한 몸
 *   merchant_brand            아직 사전에 못 들어간 가맹점의 브랜드 — 대기 장소
 * </pre>
 *
 * <p><b>왜 나누나.</b> 사전은 <i>"이 점포의 업종이 무엇인가"</i>에 대한 답만 담는다는 약속이
 * 있다. 브랜드만 알아낸 가맹점을 그 안에 넣으면 분류 없는 행이 사전에 앉아 약속이 깨진다.
 * 그래서 대기 장소를 두고, <b>그 가맹점이 사전에 들어가는 순간 옮기고 지운다</b>
 * ({@link #promote}).
 *
 * <p><b>브랜드가 무엇을 벌어 주나.</b> 실 명세서의 가맹점명에는 지점이 붙어 있다
 * ({@code GS25 강남역점}). 지금은 지점마다 따로 묻고 따로 쌓는데, 브랜드를 알면
 * ① 그 브랜드의 새 지점은 다시 안 물어도 되고 ② 한 지점이 분류되면 나머지에 물려줄 수 있다.
 *
 * <p><b>하나씩 묻는다.</b> 무료 통로가 답하므로 회수를 아낄 이유가 없고, 묶어 물으면 모델이
 * 지점명을 흘리거나 엉뚱한 것을 브랜드로 잡는다.
 */
@Service
public class MerchantBrandService {

    private static final Logger log = LoggerFactory.getLogger(MerchantBrandService.class);

    /**
     * <b>브랜드가 없는 개인 상호</b>임을 적어 두는 값.
     *
     * <p>비워 두는 것과 다르다. 비워 두면 "아직 안 물어봤다"와 구별이 안 돼 볼 때마다 다시
     * 묻는다 — 사전에서 '카테고리없음'과 '기타'를 가른 것과 같은 이치다.
     */
    public static final String NONE = "브랜드없음";

    private final MerchantBrandRepository brands;
    private final MerchantCategoryRepository dictionary;
    private final TempClassifierService temporary;

    public MerchantBrandService(MerchantBrandRepository brands,
                                MerchantCategoryRepository dictionary,
                                TempClassifierService temporary) {
        this.brands = brands;
        this.dictionary = dictionary;
        this.temporary = temporary;
    }

    /**
     * 이 가맹점들의 브랜드를 채운다 — <b>모르는 것만 하나씩 묻는다.</b>
     *
     * <p>이미 사전이나 대기 장소에 있으면 묻지 않는다. 그래서 같은 가맹점을 두 번 묻지 않고,
     * 브랜드가 쌓일수록 호출이 줄어든다.
     *
     * @return 가맹점명 → 브랜드 (알아낸 것만)
     */
    @Transactional
    public Map<String, String> fill(List<String> merchantNames) {
        Map<String, String> out = new LinkedHashMap<>();
        if (merchantNames == null || merchantNames.isEmpty()) return out;
        List<String> distinct = merchantNames.stream()
                .filter(n -> n != null && !n.isBlank()).distinct().toList();
        if (distinct.isEmpty()) return out;

        // ① 이미 아는 것 — 사전이 먼저다(확정된 가맹점의 브랜드).
        //    **사전을 통째로 읽지 않는다** — 필요한 이름만 물어본다.
        for (String name : distinct) {
            dictionary.findByMerchantName(name).stream()
                    .map(MerchantCategory::getBrand)
                    .filter(b -> b != null && !b.isBlank())
                    .findFirst()
                    .ifPresent(b -> out.put(name, b));
        }
        for (MerchantBrand b : brands.findByMerchantNameIn(distinct)) {
            out.putIfAbsent(b.getMerchantName(), b.getBrand());
        }

        // ② 모르는 것만 하나씩 묻는다 — **두 단계다.**
        //    1차: 이름만 보고 브랜드를 뽑는다(목록을 안 준다).
        //    2차: 뽑은 것이 이미 아는 브랜드와 같은지 대조해 표기를 통일한다.
        //
        //    1차에 목록을 함께 주면 모델이 거기 있는 것 중에 고르려고 해서 엉뚱한 기존 브랜드에
        //    끌려간다(앵커링). 그래서 자유롭게 뽑게 하고 통일만 따로 한다.
        java.util.Set<String> known = new java.util.TreeSet<>(out.values());
        known.remove(NONE);
        int asked = 0, got = 0, unified = 0;
        for (String name : distinct) {
            if (out.containsKey(name)) continue;
            if (!temporary.usable()) break;
            asked++;
            Optional<String> first = temporary.brandOf(name);
            if (first.isEmpty()) continue;
            String brand = first.get();
            if (!NONE.equals(brand)) {
                String same = temporary.unify(brand, known);
                if (!same.equals(brand)) unified++;
                brand = same;
                known.add(brand);
            }
            got++;
            out.put(name, brand);
            remember(name, brand);
        }
        if (asked > 0) {
            log.info("브랜드 추출 — 물어본 곳 {}, 알아낸 곳 {}, 기존 브랜드로 통일 {}",
                    asked, got, unified);
        }
        return out;
    }

    /** 알아낸 브랜드를 제자리에 적는다 — 사전에 있으면 사전에, 없으면 대기 장소에. */
    @Transactional
    public void remember(String merchantName, String brand) {
        if (merchantName == null || merchantName.isBlank()
                || brand == null || brand.isBlank()) return;

        List<MerchantCategory> rows = dictionary.findByMerchantName(merchantName);
        if (!rows.isEmpty()) {
            rows.forEach(m -> m.adoptBrand(brand));
            brands.deleteByMerchantName(merchantName);      // 사전에 있으면 대기 장소는 필요 없다
            return;
        }
        brands.findByMerchantName(merchantName)
                .ifPresentOrElse(b -> b.rename(brand, MerchantBrand.Source.TEMP_MODEL),
                        () -> brands.save(new MerchantBrand(
                                merchantName, brand, MerchantBrand.Source.TEMP_MODEL)));
    }

    /**
     * 가맹점이 <b>사전에 들어갔을 때</b> 브랜드를 옮긴다 — 대기 장소에서 지운다.
     *
     * <p>사전에 쌓는 곳({@code MerchantCategoryService})이 부른다. 이걸 안 하면 같은 가맹점의
     * 브랜드가 두 곳에 남아 어느 쪽이 정본인지 알 수 없게 된다.
     */
    @Transactional
    public void promote(MerchantCategory row) {
        if (row == null || row.getMerchantName() == null) return;
        brands.findByMerchantName(row.getMerchantName()).ifPresent(b -> {
            row.adoptBrand(b.getBrand());
            brands.deleteByMerchantName(row.getMerchantName());
        });
    }

    /** 이 가맹점의 브랜드 — 사전이 먼저, 없으면 대기 장소. */
    @Transactional(readOnly = true)
    public Optional<String> brandOf(String merchantName) {
        if (merchantName == null || merchantName.isBlank()) return Optional.empty();
        Optional<String> fromDictionary = dictionary.findByMerchantName(merchantName).stream()
                .map(MerchantCategory::getBrand)
                .filter(b -> b != null && !b.isBlank())
                .findFirst();
        if (fromDictionary.isPresent()) return fromDictionary;
        return brands.findByMerchantName(merchantName).map(MerchantBrand::getBrand);
    }
}
