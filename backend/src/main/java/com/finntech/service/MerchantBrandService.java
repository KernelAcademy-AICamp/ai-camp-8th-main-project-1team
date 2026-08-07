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
    /** 가맹점명에 든 표기 → 브랜드. 생성기 카탈로그에서 파생한 것이라 물어볼 필요가 없다. */
    private final Map<String, String> brandByForm;

    @SuppressWarnings("unchecked")
    public MerchantBrandService(MerchantBrandRepository brands,
                                MerchantCategoryRepository dictionary,
                                TempClassifierService temporary,
                                tools.jackson.databind.ObjectMapper json) {
        this.brands = brands;
        this.dictionary = dictionary;
        this.temporary = temporary;
        Map<String, String> forms = Map.of();
        try (java.io.InputStream is = new org.springframework.core.io.ClassPathResource(
                "brand-forms.json").getInputStream()) {
            Map<String, Object> root = json.readValue(is, Map.class);
            Object m = root.get("brandByForm");
            if (m instanceof Map<?, ?> raw) {
                Map<String, String> tmp = new java.util.LinkedHashMap<>();
                raw.forEach((k, v) -> tmp.put(String.valueOf(k), String.valueOf(v)));
                forms = tmp;
            }
        } catch (Exception e) {
            // 표가 없어도 동작한다 — 그러면 전부 모델에게 묻는다. 기동을 막을 일은 아니다.
            log.warn("브랜드 표기표를 읽지 못했다 — 전부 모델에 묻는다: {}", e.toString());
        }
        this.brandByForm = forms;
    }

    /**
     * <b>카탈로그로 먼저 맞춘다</b> — 생성기가 만든 가맹점명은 물어볼 필요가 없다.
     *
     * <p>더미 사용자의 상호는 {@code merchants_brand.json} 의 브랜드로 조립된 것이라 브랜드를
     * 이미 안다. 그걸 모델에 다시 묻는 것은 호출 낭비이고, 답이 흔들리면 같은 브랜드가 갈린다.
     *
     * <p>긴 표기부터 맞춘다 — {@code 세븐일레븐} 이 {@code 세븐} 보다 먼저 걸려야 한다.
     * 표는 그 순서로 만들어져 있다.
     */
    private Optional<String> fromCatalog(String merchantName) {
        String n = merchantName.replaceAll("\\s+", "");
        for (var e : brandByForm.entrySet()) {
            if (n.contains(e.getKey().replaceAll("\\s+", ""))) return Optional.of(e.getValue());
        }
        return Optional.empty();
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
        return fill(merchantNames, java.util.Set.copyOf(
                merchantNames == null ? List.of() : merchantNames));
    }

    /**
     * 브랜드를 채운다 — <b>카탈로그는 전원에게, 모델은 {@code askable} 에만.</b>
     *
     * <p>카탈로그(생성기가 쓰는 브랜드 목록)는 우리가 이미 큐레이션한 것이라 누구에게 적용하든
     * 새 사실이 들어오지 않는다. 그래서 더미든 실사용자든 <b>먼저 여기서 맞춘다</b> — 이미 아는
     * 브랜드를 모델에 다시 묻는 것은 호출 낭비이고, 답이 흔들리면 같은 브랜드가 갈린다.
     *
     * <p>모델에 묻는 것은 다르다. 그건 <b>표를 넓히는</b> 일이라, 더미의 상호(생성기가 조립한 것)로
     * 넓히면 아무도 결제한 적 없는 브랜드가 앉는다. 사전이 실제 사람의 결제만 받는 것과 같은
     * 이유로 여기도 막는다.
     *
     * @param askable 모델에 물어도 되는 가맹점명 — 실제 사람의 결제에서 온 것
     */
    @Transactional
    public Map<String, String> fill(List<String> merchantNames, java.util.Set<String> askable) {
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

        // ② 카탈로그로 맞춰 본다 — 생성기가 만든 상호는 물어볼 필요가 없다(브랜드를 이미 안다).
        for (String name : distinct) {
            if (out.containsKey(name)) continue;
            fromCatalog(name).ifPresent(b -> {
                out.put(name, b);
                remember(name, b);
            });
        }

        // ③ 그래도 모르는 것만 하나씩 묻는다 — **두 단계다.**
        //    여기서만 실사용자 게이트가 걸린다(위 카탈로그는 전원에게 적용된다).
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
            if (!askable.contains(name)) continue;      // 더미 상호로는 표를 넓히지 않는다
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

    /**
     * <b>그 사용자의 결제 전체</b>에 브랜드를 붙인다 — 미분류인지와 무관하다.
     *
     * <p>예전에는 미분류 처리 흐름 안에서 불렀는데, 그러면 <b>이미 분류된 가맹점은 브랜드를
     * 얻을 기회가 없다</b>(2026-08-07 운영 실측: 273곳 중 15곳만 붙었다). 그런데 브랜드의
     * 값어치가 바로 거기 있다 — <i>한 지점이 분류되면 나머지 지점에 물려주는 것</i>. 분류된
     * 쪽에 브랜드가 없으면 물려줄 근거가 안 생긴다.
     *
     * <p><b>회차마다 조금씩 나아간다.</b> 273곳을 한 번에 물으면 동기화가 그만큼 늘어지므로,
     * 아직 브랜드가 없는 곳부터 {@code limit} 만큼만 처리한다. 카탈로그로 붙는 것은 호출이
     * 없어 상한을 거의 안 쓰고, 모델에 묻는 것만 상한에 걸린다.
     *
     * @param merchantNames 그 사용자의 가맹점명 전부
     * @param askable       모델에 물어도 되는 것 — 실제 사람의 결제에서 온 이름
     * @param limit         이번 회차에 <b>모델에</b> 물어볼 최대 수
     * @return 이번에 새로 붙인 수
     */
    @Transactional
    public int label(List<String> merchantNames, java.util.Set<String> askable, int limit) {
        if (merchantNames == null || merchantNames.isEmpty()) return 0;
        List<String> distinct = merchantNames.stream()
                .filter(n -> n != null && !n.isBlank()).distinct().sorted().toList();

        // 이미 아는 것은 건너뛴다 — 사전이든 대기 장소든.
        java.util.Set<String> known = new java.util.HashSet<>();
        for (MerchantBrand b : brands.findByMerchantNameIn(distinct)) known.add(b.getMerchantName());
        for (String name : distinct) {
            if (known.contains(name)) continue;
            boolean inDictionary = dictionary.findByMerchantName(name).stream()
                    .anyMatch(m -> m.getBrand() != null && !m.getBrand().isBlank());
            if (inDictionary) known.add(name);
        }

        // ① 카탈로그 — 호출이 없으므로 상한을 안 쓴다. 전원에게 적용한다.
        int added = 0;
        List<String> rest = new java.util.ArrayList<>();
        for (String name : distinct) {
            if (known.contains(name)) continue;
            var hit = fromCatalog(name);
            if (hit.isPresent()) {
                remember(name, hit.get());
                added++;
            } else {
                rest.add(name);
            }
        }

        // ② 모델 — 실사용자 이름만, 이번 회차 상한까지. 두 단계(뽑기 → 표기 통일)를 거친다.
        java.util.Set<String> brandNames = new java.util.TreeSet<>(knownBrands());
        int asked = 0, unified = 0;
        for (String name : rest) {
            if (asked >= limit) break;
            if (!askable.contains(name) || !temporary.usable()) continue;
            asked++;
            var first = temporary.brandOf(name);
            if (first.isEmpty()) continue;
            String brand = first.get();
            if (!NONE.equals(brand)) {
                String same = temporary.unify(brand, brandNames);
                if (!same.equals(brand)) unified++;
                brand = same;
                brandNames.add(brand);
            }
            remember(name, brand);
            added++;
        }
        if (added > 0 || asked > 0) {
            log.info("브랜드 라벨링 — 가맹점 {}, 새로 붙임 {}(카탈로그 {}), 모델 질의 {}, 통일 {}, 남은 곳 {}",
                    distinct.size(), added, added - (asked > 0 ? asked : 0), asked, unified,
                    Math.max(0, rest.size() - asked));
        }
        return added;
    }

    /** 이미 아는 브랜드 이름들 — 2차 대조의 후보가 된다. */
    private java.util.Set<String> knownBrands() {
        java.util.Set<String> out = new java.util.TreeSet<>();
        brands.findAll().forEach(b -> out.add(b.getBrand()));
        out.remove(NONE);
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
