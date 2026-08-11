package com.finntech.service;

import com.finntech.domain.MerchantBrand;
import com.finntech.domain.MerchantCategory;
import com.finntech.repository.MerchantBrandRepository;
import com.finntech.repository.MerchantCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 브랜드는 <b>이름에 붙는다</b> — 사업자번호를 안 탄다.
 *
 * <p>사전은 일반 사업자·PG·복합 사업자를 서로 다르게 저장한다(PG 는 번호를 지우고, 복합은
 * 완화 없이 정확일치만). 그런데 브랜드는 그 차이를 안 탄다 — 번호는 브랜드를 말해 주지 않고
 * (PG 를 거치면 남의 번호, 프랜차이즈는 지점마다 다른 번호) 이름은 언제나 있기 때문이다.
 * 그 계약을 여기서 못박는다.
 */
class MerchantBrandServiceTest {

    private final List<MerchantBrand> staging = new ArrayList<>();
    private final List<MerchantCategory> dictionary = new ArrayList<>();
    /** 실제 사람의 결제에 있는 상호 — 이 집합 밖은 브랜드 표에 못 들어간다. */
    private final java.util.Set<String> realNames = new java.util.HashSet<>();
    private com.finntech.repository.UserPaymentRepository payments;
    private MerchantBrandService service;
    /** 가맹점 <b>하나씩</b> 묻는 질의가 몇 번 나갔는지 — N+1 을 단정으로 못박기 위해 센다. */
    private final java.util.concurrent.atomic.AtomicInteger perNameQueries =
            new java.util.concurrent.atomic.AtomicInteger();

    @BeforeEach
    void setUp() {
        staging.clear();
        dictionary.clear();
        perNameQueries.set(0);
        // 이 시험의 상호들은 기본적으로 실물로 본다 — 더미 차단은 별도 시험이 따로 본다.
        realNames.clear();
        realNames.addAll(List.of("GS25 강남역점", "스타벅스 포항공대점", "유니클로 온라인 스토어",
                "어떤 가게", "물고기자리"));

        MerchantBrandRepository brands = mock(MerchantBrandRepository.class);
        when(brands.findByMerchantName(anyString())).thenAnswer(inv -> staging.stream()
                .filter(b -> b.getMerchantName().equals(inv.getArgument(0))).findFirst());
        when(brands.findByMerchantNameIn(any())).thenAnswer(inv -> {
            List<String> names = inv.getArgument(0);
            return staging.stream().filter(b -> names.contains(b.getMerchantName())).toList();
        });
        when(brands.findDistinctBrands(anyString())).thenAnswer(inv -> staging.stream()
                .map(MerchantBrand::getBrand)
                .filter(b -> !b.equals(inv.getArgument(0))).distinct().sorted().toList());
        when(brands.save(any(MerchantBrand.class))).thenAnswer(inv -> {
            staging.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        org.mockito.Mockito.doAnswer(inv -> staging.removeIf(
                b -> b.getMerchantName().equals(inv.getArgument(0))))
                .when(brands).deleteByMerchantName(anyString());

        MerchantCategoryRepository categories = mock(MerchantCategoryRepository.class);
        when(categories.findByMerchantName(anyString())).thenAnswer(inv -> {
            perNameQueries.incrementAndGet();
            return dictionary.stream()
                    .filter(m -> m.getMerchantName().equals(inv.getArgument(0))).toList();
        });
        when(categories.findByMerchantNameIn(any())).thenAnswer(inv -> {
            List<String> names = inv.getArgument(0);
            return dictionary.stream().filter(m -> names.contains(m.getMerchantName())).toList();
        });
        when(categories.findDistinctBrands(anyString())).thenAnswer(inv -> dictionary.stream()
                .map(MerchantCategory::getBrand)
                .filter(b -> b != null && !b.equals(inv.getArgument(0))).distinct().sorted().toList());

        // **실사용자 결제에 있는 상호만** 브랜드 표에 앉는다. 대역은 `realNames` 에 담긴 것만
        // 실물로 인정한다 — 게이트 자체를 시험할 수 있어야 하기 때문이다.
        payments = mock(com.finntech.repository.UserPaymentRepository.class);
        when(payments.existsRealPersonPaymentByMerchantName(anyString()))
                .thenAnswer(inv -> realNames.contains(inv.getArgument(0)));

        service = TestServices.brandService(brands, categories,
                mock(TempClassifierService.class), payments);
    }

    /** 실사용자 결제에서 온 이름들 — {@code label} 이 모델에 물어도 되는 집합. */
    private java.util.Set<String> askable(String... names) {
        return new java.util.LinkedHashSet<>(List.of(names));
    }

    @Test
    @DisplayName("사전에 없으면 대기 장소에 쌓인다 — 분류 없는 행이 사전에 앉지 않게")
    void unknownMerchantGoesToStaging() {
        service.remember("GS25 강남역점", "GS25");

        assertThat(staging).hasSize(1);
        assertThat(staging.get(0).getBrand()).isEqualTo("GS25");
        assertThat(dictionary).as("사전은 안 건드린다").isEmpty();
        assertThat(service.brandOf("GS25 강남역점")).contains("GS25");
    }

    @Test
    @DisplayName("사전에 있으면 사전에 적고 대기 장소는 안 쓴다")
    void knownMerchantGetsBrandInDictionary() {
        var row = new MerchantCategory("0000000011", "GS25 강남역점", "편의점/잡화",
                MerchantCategory.Source.USER_CSV, null, null);
        dictionary.add(row);

        service.remember("GS25 강남역점", "GS25");

        assertThat(row.getBrand()).isEqualTo("GS25");
        assertThat(staging).as("사전에 있으면 대기 장소는 필요 없다").isEmpty();
    }

    @Test
    @DisplayName("사전에 들어가면 대기 장소의 브랜드를 옮기고 지운다")
    void promoteMovesBrandIntoDictionary() {
        service.remember("스타벅스 포항공대점", "스타벅스");
        assertThat(staging).hasSize(1);

        var row = new MerchantCategory("0000000012", "스타벅스 포항공대점", "카페/간식",
                MerchantCategory.Source.USER_CONFIRMED, 7L, null);
        service.promote(row);

        assertThat(row.getBrand()).isEqualTo("스타벅스");
        assertThat(staging).as("두 곳에 남으면 어느 쪽이 정본인지 알 수 없다").isEmpty();
    }

    @Test
    @DisplayName("번호가 달라도 이름이 같으면 같은 브랜드 — PG·복합·일반을 안 가린다")
    void brandKeysOnNameNotBusinessNumber() {
        // PG 를 거친 행(번호가 지워짐)과 일반 행(번호 있음)이 같은 상호로 사전에 있다고 하자.
        var viaPg = new MerchantCategory("", "유니클로 온라인 스토어", "쇼핑",
                MerchantCategory.Source.USER_CSV, null, null);
        var direct = new MerchantCategory("0000000013", "유니클로 온라인 스토어", "쇼핑",
                MerchantCategory.Source.USER_CSV, null, null);
        dictionary.add(viaPg);
        dictionary.add(direct);

        service.remember("유니클로 온라인 스토어", "유니클로");

        assertThat(viaPg.getBrand()).isEqualTo("유니클로");
        assertThat(direct.getBrand()).as("번호가 달라도 같은 이름이면 같은 브랜드").isEqualTo("유니클로");
    }

    @Test
    @DisplayName("이미 있는 브랜드는 덮지 않는다 — 먼저 들어온 것이 사람의 손일 수 있다")
    void existingBrandIsNotOverwritten() {
        var row = new MerchantCategory("0000000014", "어떤 가게", "생활",
                MerchantCategory.Source.USER_CSV, null, null);
        row.adoptBrand("사람이적은브랜드");
        dictionary.add(row);

        service.remember("어떤 가게", "모델이적은브랜드");

        assertThat(row.getBrand()).isEqualTo("사람이적은브랜드");
    }

    @Test
    @DisplayName("빈 값은 아무 데도 안 쌓는다")
    void blanksAreIgnored() {
        service.remember("어떤 가게", "");
        service.remember("", "브랜드");
        service.remember(null, "브랜드");
        assertThat(staging).isEmpty();
        assertThat(service.brandOf("없는 가게")).isEmpty();
    }

    @Test
    @DisplayName("'브랜드없음'도 기록한다 — 비워 두면 볼 때마다 다시 묻는다")
    void noBrandIsRecordedNotLeftBlank() {
        service.remember("물고기자리", MerchantBrandService.NONE);

        assertThat(staging).hasSize(1);
        assertThat(service.brandOf("물고기자리")).contains(MerchantBrandService.NONE);
        // 사전에서 '카테고리없음'과 '기타'를 가른 것과 같은 이치다 —
        // "아직 안 물어봤다"와 "물어봤는데 브랜드가 없다"는 다르다.
    }

    @Test
    @DisplayName("모르는 가맹점만 묻는다 — 이미 아는 것은 호출을 쓰지 않는다")
    void asksOnlyForUnknownMerchants() {
        staging.add(new MerchantBrand("GS25 강남역점", "GS25", MerchantBrand.Source.TEMP_MODEL));
        var row = new MerchantCategory("0000000015", "스타벅스 포항공대점", "카페/간식",
                MerchantCategory.Source.USER_CSV, null, null);
        row.adoptBrand("스타벅스");
        dictionary.add(row);

        int added = service.label(List.of("GS25 강남역점", "스타벅스 포항공대점"),
                askable("GS25 강남역점", "스타벅스 포항공대점"), 10);

        assertThat(added).as("둘 다 이미 알므로 새로 붙일 것이 없다").isZero();
        assertThat(service.brandOf("GS25 강남역점")).contains("GS25");
        assertThat(service.brandOf("스타벅스 포항공대점")).contains("스타벅스");
    }

    @Test
    @DisplayName("가맹점마다 질의하지 않는다 — 할 일을 추리는 데 드는 질의는 회차당 두 번")
    void findsPendingWithBatchQueriesNotPerMerchant() {
        // 2026-08-07 감사에서 발견한 것: 회차마다 `findByMerchantName` 이 가맹점 수만큼 나갔다.
        // 273곳이면 273회이고, 그 질의가 인덱스를 못 타면 273번의 풀스캔이다.
        List<String> names = new ArrayList<>();
        for (int i = 0; i < 50; i++) names.add("가맹점" + i);

        service.findPending(names, new java.util.HashSet<>(names));

        assertThat(perNameQueries.get())
                .as("이름 하나씩 묻는 질의가 한 번도 나가면 안 된다").isZero();
    }

    @Test
    @DisplayName("모델에 묻는 동안 트랜잭션을 붙잡지 않는다 — 읽기·질의·쓰기가 갈라져 있다")
    void modelCallsHappenOutsideTransaction() throws Exception {
        // HTTP 6~10초짜리 호출 20개를 한 트랜잭션에 넣으면 DB 커넥션을 3분씩 붙잡는다.
        // 계약을 서명으로 못박는다 — label 에는 트랜잭션이 없고, 안에서 부르는 두 단계에만 있다.
        var label = MerchantBrandService.class.getMethod(
                "label", List.class, java.util.Set.class, int.class);
        assertThat(label.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class))
                .as("label 자체에 트랜잭션이 붙으면 모델 호출이 그 안에서 돈다").isNull();

        var findPending = MerchantBrandService.class.getMethod(
                "findPending", List.class, java.util.Set.class);
        assertThat(findPending.getAnnotation(
                org.springframework.transaction.annotation.Transactional.class).readOnly())
                .as("할 일을 추리는 단계는 읽기 전용").isTrue();

        assertThat(MerchantBrandService.class.getMethod("persist", java.util.Map.class, java.util.Map.class)
                .getAnnotation(org.springframework.transaction.annotation.Transactional.class))
                .as("쓰는 단계에는 트랜잭션이 있어야 한다").isNotNull();
    }

    /**
     * <b>부분문자열로 맞추면 실사용자 상호에 엉뚱한 브랜드가 사실처럼 박힌다.</b>
     *
     * <p>카탈로그로 맞은 것은 모델에 묻지 않고 바로 사전·대기장소에 적히고, 한 번 적히면
     * {@code findPending} 의 known 검사에 걸려 다시는 안 묻는다 — 교정 기회가 없다.
     * 실측(2026-08-07 재감사): 표기 {@code 카카오}(→멜론)가 실사용자 상호 <b>74곳</b>에,
     * 두 글자 라틴 표기 {@code KT} 가 {@code 고속철도(KTX)…} <b>7곳</b>에 잘못 맞았다.
     */
    @Test
    @DisplayName("카탈로그가 회사 접두·짧은 라틴 표기로 엉뚱한 브랜드를 박지 않는다")
    void catalogDoesNotMislabelOnSubstrings() {
        realNames.addAll(List.of("카카오택시-서울33바2592", "고속철도(KTX)서울-포항", "(주)카카오"));

        var pending = service.findPending(
                List.of("카카오택시-서울33바2592", "고속철도(KTX)서울-포항", "(주)카카오"),
                askable("카카오택시-서울33바2592", "고속철도(KTX)서울-포항", "(주)카카오"));
        var hit = pending.fromCatalog();

        // 회사 접두는 그 회사로 — 제품(멜론)으로 새지 않는다. 더 긴 표기가 있으면 그쪽이 이긴다.
        assertThat(hit.get("카카오택시-서울33바2592")).isNotEqualTo("멜론");
        assertThat(hit.get("(주)카카오")).isNotEqualTo("멜론");
        // 라틴 표기는 낱말 경계에서만 — 'KT' 가 'KTX' 안에 걸리면 안 된다.
        // 같은 명세서의 `코레일유통…` 이 한국철도공사로 가므로, 갈리면 같은 철도 결제가 두 브랜드다.
        assertThat(hit.get("고속철도(KTX)서울-포항")).isEqualTo("한국철도공사");
    }

    @Test
    @DisplayName("카탈로그는 긴 표기부터 맞춘다 — '세븐일레븐'이 '세븐'보다 먼저")
    void catalogPrefersLongerForm() {
        // 표기를 생성 때 한 번만 접어 두고 길이 내림차순으로 세운다. 접는 순서가 흐트러지면
        // `세븐일레븐 강남점`이 `세븐`(다른 브랜드)으로 걸린다.
        realNames.add("세븐일레븐 강남점");
        int added = service.label(List.of("세븐일레븐 강남점"), askable("세븐일레븐 강남점"), 10);

        if (added > 0) {
            assertThat(service.brandOf("세븐일레븐 강남점"))
                    .as("긴 표기가 먼저 걸려야 한다").contains("세븐일레븐");
        }
    }

    @Test
    @DisplayName("사전이 대기 장소보다 먼저다")
    void dictionaryWinsOverStaging() {
        staging.add(new MerchantBrand("어떤 가게", "대기장소브랜드", MerchantBrand.Source.TEMP_MODEL));
        var row = new MerchantCategory("0000000016", "어떤 가게", "생활",
                MerchantCategory.Source.USER_CSV, null, null);
        row.adoptBrand("사전브랜드");
        dictionary.add(row);

        assertThat(service.brandOf("어떤 가게")).contains("사전브랜드");
    }

    @Test
    @DisplayName("더미 결제의 상호는 브랜드 표에 못 들어간다 — 저장 자리에서 막는다")
    void dummyMerchantsNeverEnterTheTable() {
        // 게이트를 **저장하는 자리**에 둔 것이 요점이다. 부르는 쪽에만 두면 호출부가 하나 늘
        // 때마다 빠뜨릴 수 있고, 실제로 그렇게 새어 273곳용 표가 4,860줄이 됐다(2026-08-07 운영).
        service.remember("생성기가만든가게", "어떤브랜드");

        assertThat(staging).as("실사용자 결제에 없는 상호는 안 쌓인다").isEmpty();
        assertThat(service.brandOf("생성기가만든가게")).isEmpty();

        // 사전에 그 이름의 행이 있어도 마찬가지다 — 실물 여부가 먼저다.
        dictionary.add(new MerchantCategory("0000000099", "생성기가만든가게", "쇼핑",
                MerchantCategory.Source.USER_CSV, null, null));
        service.remember("생성기가만든가게", "어떤브랜드");
        assertThat(dictionary.get(0).getBrand()).isNull();
    }

    @Test
    @DisplayName("빈 목록은 호출도 안 한다")
    void emptyInputShortCircuits() {
        assertThat(service.label(List.of(), java.util.Set.of(), 10)).isZero();
        assertThat(service.label(null, java.util.Set.of(), 10)).isZero();
        assertThat(service.label(java.util.Arrays.asList("", "  "), java.util.Set.of(), 10)).isZero();
        assertThat(Optional.ofNullable(staging.isEmpty() ? null : staging.get(0))).isEmpty();
    }
}
