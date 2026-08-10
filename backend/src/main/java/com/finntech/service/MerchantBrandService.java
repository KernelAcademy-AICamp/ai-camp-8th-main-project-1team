package com.finntech.service;

import com.finntech.domain.MerchantBrand;
import com.finntech.domain.MerchantCategory;
import com.finntech.repository.MerchantBrandRepository;
import com.finntech.freechannel.FreeChannelQueue;
import com.finntech.freechannel.Lane;
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
    /** 그 상호가 <b>실제 사람의 결제</b>에 있는지 확인한다 — 저장 직전의 마지막 방벽. */
    private final com.finntech.repository.UserPaymentRepository payments;
    /**
     * 가맹점명에 든 표기 → 브랜드. 생성기 카탈로그에서 파생한 것이라 물어볼 필요가 없다.
     *
     * <p><b>공백을 미리 지우고 긴 것부터 세워 둔다.</b> 맞출 때마다 413개의 표기에 정규식을
     * 새로 컴파일하던 것을 생성 때 한 번으로 옮긴 것이다 — 가맹점 하나 맞추는 데 정규식
     * 826회(표기 413 × 컴파일+치환)가 나가고 있었다(2026-08-07 감사).
     *
     * <p>긴 표기가 앞에 있어야 {@code 세븐일레븐} 이 {@code 세븐} 보다 먼저 걸린다.
     */
    private final List<Map.Entry<String, String>> squashedForms;
    /**
     * <b>자기 자신의 프록시.</b> {@code @Transactional} 은 프록시가 걸어 주는 것이라
     * 같은 객체 안에서 그냥 부르면 <b>트랜잭션이 안 열린다</b>. 읽기·쓰기를 짧은
     * 트랜잭션으로 나누려면 프록시를 거쳐야 한다.
     */
    private final org.springframework.beans.factory.ObjectProvider<MerchantBrandService> selfProvider;
    /** 무료 통로로 나가는 유일한 문 — 예산과 순서를 여기가 정한다. */
    private final FreeChannelQueue queue;

    @SuppressWarnings("unchecked")
    public MerchantBrandService(MerchantBrandRepository brands,
                                MerchantCategoryRepository dictionary,
                                TempClassifierService temporary,
                                com.finntech.repository.UserPaymentRepository payments,
                                tools.jackson.databind.ObjectMapper json,
                                org.springframework.beans.factory.ObjectProvider<MerchantBrandService> selfProvider,
                                FreeChannelQueue queue) {
        this.brands = brands;
        this.dictionary = dictionary;
        this.temporary = temporary;
        this.payments = payments;
        this.selfProvider = selfProvider;
        this.queue = queue;
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
        // 공백을 지운 형태로 한 번만 만들어 두고, 긴 것부터 세운다.
        this.squashedForms = forms.entrySet().stream()
                .map(e -> Map.entry(SPACES.matcher(e.getKey()).replaceAll(""), e.getValue()))
                .filter(e -> !e.getKey().isEmpty())
                .sorted(java.util.Comparator
                        .comparingInt((Map.Entry<String, String> e) -> e.getKey().length()).reversed()
                        .thenComparing(Map.Entry::getKey))
                .toList();
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
        String n = SPACES.matcher(merchantName).replaceAll("");
        for (var e : squashedForms) {
            if (matches(n, e.getKey())) return Optional.of(e.getValue());
        }
        return Optional.empty();
    }

    /**
     * 표기가 상호 안에 들어 있는가 — <b>라틴 표기는 낱말 경계에서만 인정한다.</b>
     *
     * <p>한글에는 낱말 경계가 없어 부분문자열이 유일한 방법이고, 그건 긴 표기를 앞에 세워
     * 다룬다. 그런데 라틴 표기는 다르다. {@code KT} 는 두 글자라 {@code 고속철도(KTX)서울-포항}
     * 안에 그대로 들어 있고, {@code UT}·{@code CU}·{@code SR}·{@code K2} 도 같은 위험이 있다.
     * 실측으로 실사용자 상호 7곳 40건이 {@code KT} 로 잘못 맞았다(2026-08-07 재감사).
     *
     * <p>글자 앞뒤가 다른 라틴 글자·숫자면 그건 <b>다른 낱말의 일부</b>다 — 인정하지 않는다.
     * 한글·기호·문자열 끝이면 경계로 본다.
     */
    private static boolean matches(String name, String form) {
        if (!ASCII_FORM.matcher(form).matches()) return name.contains(form);
        int from = 0, at;
        while ((at = name.indexOf(form, from)) >= 0) {
            boolean leftOk = at == 0 || !isAsciiWordChar(name.charAt(at - 1));
            int end = at + form.length();
            boolean rightOk = end == name.length() || !isAsciiWordChar(name.charAt(end));
            if (leftOk && rightOk) return true;
            from = at + 1;
        }
        return false;
    }

    private static boolean isAsciiWordChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
    }

    private static final java.util.regex.Pattern SPACES = java.util.regex.Pattern.compile("\\s+");
    /** 라틴 글자·숫자·기호만으로 된 표기 — 낱말 경계를 따질 수 있는 것들. */
    private static final java.util.regex.Pattern ASCII_FORM =
            java.util.regex.Pattern.compile("[\\p{ASCII}]+");

    /**
     * <p><b>트랜잭션이 열려 있지 않다.</b> 세 단계로 갈라져 있고 가운데(모델 질의)만 밖에서 돈다.
     *
     * <pre>
     *   ① 무엇이 필요한지 읽는다   짧은 트랜잭션 · 질의 두 번
     *   ② 모델에 묻는다           트랜잭션 없음 — HTTP 6~10초 × N
     *   ③ 알아낸 것을 쓴다        짧은 트랜잭션
     * </pre>
     *
     * <p>한 트랜잭션으로 묶으면 <b>DB 커넥션을 몇 분씩 붙잡는다</b> — 20곳이면 최대 3분이고,
     * 그동안 커넥션 풀이 그만큼 줄어 다른 요청이 대기한다.
     */
    /**
     * 브랜드가 필요한 가맹점을 <b>큐에 올린다</b> — 여기서 모델을 부르지 않는다.
     *
     * <p><b>왜 큐로 옮겼나.</b> 예전에는 이 서비스가 회차당 20곳을 직접 물었고, 그래서 두 가지를
     * 스스로 감당해야 했다 — 회차 상한(한 번에 몰아 부르지 않기)과 회전 창(앞머리가 늘 실패해도
     * 뒤쪽이 차례를 얻기). 둘 다 <b>통로 예산을 나눠 쓰는 문제</b>인데, 그것을 아는 자리는
     * 통로 하나를 통째로 보는 큐다. 문장 갱신도 같은 예산을 쓰므로 각자 상한을 두면 합이 안 맞는다.
     *
     * <p>옮기고 나면 여기 남는 일은 둘뿐이다 — <b>카탈로그로 즉시 붙는 것은 바로 적고</b>
     * (호출이 없으니 예산과 무관하다), 모르는 것만 한 건씩 큐에 올린다.
     *
     * <p>차선은 {@link Lane#USER_BACKGROUND} 다. 실사용자의 데이터라 더미보다 앞이고, 화면에
     * 걸려 있지 않아 문장 갱신보다는 뒤다.
     *
     * @return 이번에 새로 올린 건수 (카탈로그로 즉시 붙은 것 + 큐에 새로 들어간 것)
     */
    public int enqueuePending(List<String> merchantNames, java.util.Set<String> askable) {
        if (merchantNames == null || merchantNames.isEmpty()) return 0;
        List<String> distinct = merchantNames.stream()
                .filter(n -> n != null && !n.isBlank()).distinct().sorted().toList();
        if (distinct.isEmpty()) return 0;

        MerchantBrandService self = selfProvider.getObject();
        Pending pending = self.findPending(distinct, askable);
        if (pending.fromCatalog().isEmpty() && pending.needAsking().isEmpty()) return 0;

        // 카탈로그로 맞은 것은 호출이 없다 — 큐를 거칠 이유가 없어 바로 적는다.
        int added = self.persist(pending.fromCatalog(), Map.of());

        for (String name : pending.needAsking()) {
            boolean queued = queue.submit(Lane.USER_BACKGROUND, "brand:" + name,
                    () -> askAndStore(name, pending.knownBrands()));
            if (queued) added++;
        }
        if (added > 0) {
            log.info("브랜드 — 카탈로그로 {}곳 즉시, 큐에 {}곳 (대기 {})",
                    pending.fromCatalog().size(), pending.needAsking().size(), queue.queued());
        }
        return added;
    }

    /** 큐가 부르는 자리 — 한 가맹점만 묻고 그 자리에서 적는다. 실패하면 아무것도 안 한다. */
    private void askAndStore(String name, java.util.Set<String> knownBrands) {
        var first = temporary.brandOf(name);
        if (first.isEmpty()) return;      // 답을 못 받았다 — 사실이 아니므로 아무것도 안 적는다
        String brand = first.get();
        if (!NONE.equals(brand)) brand = temporary.unify(brand, knownBrands);
        selfProvider.getObject().persist(Map.of(), Map.of(name, brand));
    }

    /**
     * @deprecated 큐로 옮겼다 — {@link #enqueuePending} 을 쓴다. 남겨 둔 이유는 시험이
     *             "예전에는 이렇게 몰아 불렀다"를 대조군으로 쓰기 때문이다.
     */
    @Deprecated
    public int label(List<String> merchantNames, java.util.Set<String> askable, int limit) {
        if (merchantNames == null || merchantNames.isEmpty()) return 0;
        List<String> distinct = merchantNames.stream()
                .filter(n -> n != null && !n.isBlank()).distinct().sorted().toList();
        if (distinct.isEmpty()) return 0;

        MerchantBrandService self = selfProvider.getObject();
        Pending pending = self.findPending(distinct, askable);
        if (pending.fromCatalog().isEmpty() && pending.needAsking().isEmpty()) return 0;

        // ② 모델에 묻는다 — **트랜잭션 밖**이다.
        Map<String, String> asked = new LinkedHashMap<>();
        java.util.Set<String> brandNames = new java.util.TreeSet<>(pending.knownBrands());
        // **상한은 '물어본 횟수'다 — 답을 얻은 횟수가 아니다.** 통로가 죽어 전부 빈손으로
        // 돌아오면 성공만 세는 상한은 한 번도 안 차고, 273곳 전부에 호출이 나간다
        // (2026-08-07 감사). 호출이 드는 것은 답이 왔는지와 무관하다.
        //
        // **못 얻은 것은 기록하지 않는다** — 그리고 그것이 맞다(사용자 판단 2026-08-08).
        // 모델이 "브랜드가 없다"고 답하면 그건 사실이라 `NONE` 으로 남고 다시 안 묻는다.
        // 여기서 빈손인 것은 **답을 못 받은 것**(파싱 실패·무응답)이라 사실이 아니다. 그것을
        // "브랜드 없음"으로 적으면 통로 장애를 데이터로 굳히는 것이고, 분류 쪽에서 헛물을
        // 통로 장애와 갈라 놓은 것과 같은 이유로 하면 안 된다. 무료 통로라 다시 묻는 값도 0 이다.
        //
        // **다만 순서가 고착되면 안 된다.** 실패는 `temperature 0` 이라 결정론적이라서, 앞머리
        // 20곳이 늘 실패하면 뒤쪽은 **영영 차례가 안 온다** — 값이 아니라 진행이 막히는 문제다.
        // 그래서 회차마다 시작점을 밀어 창(窓)을 굴린다. 어떤 이름도 ⌈N/limit⌉ 회차 안에 온다.
        // (붙는 브랜드는 순서와 무관하므로 §4 원칙 3 의 재현성은 이 회전에 영향받지 않는다.)
        List<String> queue = rotated(pending.needAsking(), limit);
        int tried = 0, unified = 0;
        for (String name : queue) {
            if (tried >= limit || !temporary.usable()) break;
            tried++;
            var first = temporary.brandOf(name);
            if (first.isEmpty()) continue;
            String brand = first.get();
            if (!NONE.equals(brand)) {
                String same = temporary.unify(brand, brandNames);
                if (!same.equals(brand)) unified++;
                brand = same;
                brandNames.add(brand);
            }
            asked.put(name, brand);
        }

        int added = self.persist(pending.fromCatalog(), asked);
        if (added > 0) {
            log.info("브랜드 라벨링 — 가맹점 {}, 새로 붙임 {}(카탈로그 {}), 모델 질의 {}(답 {}), 통일 {}, 남은 곳 {}",
                    distinct.size(), added, pending.fromCatalog().size(), tried, asked.size(), unified,
                    Math.max(0, pending.needAsking().size() - tried));
        }
        return added;
    }

    /**
     * <b>회차마다 시작점을 민다</b> — 앞머리가 늘 실패해도 뒤쪽이 차례를 얻게.
     *
     * <p>못 얻은 것을 기록하지 않기로 했으므로(위 설명) 실패한 이름은 목록에 계속 남는다.
     * 목록 순서가 고정이고 상한이 있으면 그 이름들이 앞자리를 영구히 차지한다 — 실패가
     * {@code temperature 0} 이라 결정론적이라서 다음 회차에도 똑같이 실패한다.
     *
     * <p>돌려서 훑으면 어떤 이름도 {@code ⌈N/limit⌉} 회차 안에 온다.
     */
    private List<String> rotated(List<String> names, int limit) {
        if (names.size() <= limit) return names;
        int start = Math.floorMod(rotation.getAndAdd(limit), names.size());
        List<String> out = new java.util.ArrayList<>(names.size());
        out.addAll(names.subList(start, names.size()));
        out.addAll(names.subList(0, start));
        return out;
    }

    /** 회차 시작점. 값 자체는 뜻이 없고 <b>매번 달라진다</b>는 것만이 뜻이다. */
    private final java.util.concurrent.atomic.AtomicInteger rotation =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 이번 회차에 할 일 — 카탈로그로 즉시 붙는 것, 모델에 물을 것, 대조에 쓸 브랜드 이름들. */
    public record Pending(Map<String, String> fromCatalog, List<String> needAsking,
                          java.util.Set<String> knownBrands) {}

    /**
     * 할 일을 <b>질의 두 번</b>으로 추린다 — 대기 장소 한 번, 사전 한 번.
     *
     * <p>가맹점마다 묻지 않는다. 273곳이면 273회가 나가고, 그 질의가 인덱스를 못 타면 회차마다
     * 풀스캔이 273번이다(2026-08-07 감사에서 발견).
     */
    @Transactional(readOnly = true)
    public Pending findPending(List<String> distinct, java.util.Set<String> askable) {
        java.util.Set<String> known = new java.util.HashSet<>();
        for (MerchantBrand b : brands.findByMerchantNameIn(distinct)) known.add(b.getMerchantName());
        for (MerchantCategory m : dictionary.findByMerchantNameIn(distinct)) {
            if (m.getBrand() != null && !m.getBrand().isBlank()) known.add(m.getMerchantName());
        }

        // 대조 후보는 **이번 회차가 아니라 아는 전부**다. 이번에 들어온 이름들의 브랜드만 모으면
        // 통일이 배치 안에서만 일어나, 지난 회차에 `스타벅스`로 정한 것을 이번 회차가 모른다.
        java.util.Set<String> brandNames = new java.util.TreeSet<>(brands.findDistinctBrands(NONE));
        brandNames.addAll(dictionary.findDistinctBrands(NONE));

        Map<String, String> catalog = new LinkedHashMap<>();
        List<String> ask = new java.util.ArrayList<>();
        for (String name : distinct) {
            // **표에 쌓는 것은 실사용자 것만.** 더미의 상호는 생성기가 조립한 것이라 자격이 없다
            // (2026-08-07 운영: 전원에게 쌓았더니 273곳용 표가 4,860줄이 됐다).
            if (known.contains(name) || !askable.contains(name)) continue;
            var hit = fromCatalog(name);
            if (hit.isPresent()) catalog.put(name, hit.get());
            else ask.add(name);
        }
        return new Pending(catalog, ask, capped(brandNames));
    }

    /** 알아낸 것을 한 트랜잭션으로 쓴다. */
    @Transactional
    public int persist(Map<String, String> fromCatalog, Map<String, String> asked) {
        int n = 0;
        for (var e : fromCatalog.entrySet()) if (remember(e.getKey(), e.getValue())) n++;
        for (var e : asked.entrySet()) if (remember(e.getKey(), e.getValue())) n++;
        return n;
    }

    /**
     * 2차 대조에 쓸 브랜드 이름 — 상한을 둔다.
     *
     * <p>이 목록이 통째로 프롬프트에 들어가므로, 커지면 대조가 비싸지고 모델이 흘린다.
     */
    private static java.util.Set<String> capped(java.util.Set<String> names) {
        if (names.size() <= MAX_BRANDS_IN_PROMPT) return names;
        return new java.util.TreeSet<>(
                new java.util.ArrayList<>(names).subList(0, MAX_BRANDS_IN_PROMPT));
    }

    /** 2차 대조 프롬프트에 담을 브랜드 수 상한. 넘으면 통일이 덜 되지만 프롬프트가 안 터진다. */
    private static final int MAX_BRANDS_IN_PROMPT = 300;

    /**
     * 알아낸 브랜드를 제자리에 적는다 — 사전에 있으면 사전에, 없으면 대기 장소에.
     *
     * <p><b>실제 사람의 결제에 있는 상호만 받는다.</b> 게이트를 <b>저장하는 자리</b>에 두는 것이
     * 요점이다 — 부르는 쪽에만 두면 호출부가 하나 늘 때마다 빠뜨릴 수 있고, 실제로 그렇게
     * 새어 273곳용 표가 4,860줄이 됐다(2026-08-07 운영). 여기서 막으면 어디서 불러도 안 들어온다.
     *
     * <p>더미의 상호는 생성기가 조립한 것이라 브랜드 표에 앉을 자격이 없다. 카탈로그가 이미
     * 알고 있어 필요하면 즉석에서 맞추면 되고, 표에 쌓을 이유가 없다.
     */
    @Transactional
    public boolean remember(String merchantName, String brand) {
        if (merchantName == null || merchantName.isBlank()
                || brand == null || brand.isBlank()) return false;
        if (!payments.existsRealPersonPaymentByMerchantName(merchantName)) return false;

        List<MerchantCategory> rows = dictionary.findByMerchantName(merchantName);
        if (!rows.isEmpty()) {
            rows.forEach(m -> m.adoptBrand(brand));
            brands.deleteByMerchantName(merchantName);      // 사전에 있으면 대기 장소는 필요 없다
            return true;
        }
        brands.findByMerchantName(merchantName)
                .ifPresentOrElse(b -> b.rename(brand, MerchantBrand.Source.TEMP_MODEL),
                        () -> brands.save(new MerchantBrand(
                                merchantName, brand, MerchantBrand.Source.TEMP_MODEL)));
        return true;
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
