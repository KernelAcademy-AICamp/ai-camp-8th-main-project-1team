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

    @BeforeEach
    void setUp() {
        staging.clear();
        dictionary.clear();
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
        when(brands.save(any(MerchantBrand.class))).thenAnswer(inv -> {
            staging.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        org.mockito.Mockito.doAnswer(inv -> staging.removeIf(
                b -> b.getMerchantName().equals(inv.getArgument(0))))
                .when(brands).deleteByMerchantName(anyString());

        MerchantCategoryRepository categories = mock(MerchantCategoryRepository.class);
        when(categories.findByMerchantName(anyString())).thenAnswer(inv -> dictionary.stream()
                .filter(m -> m.getMerchantName().equals(inv.getArgument(0))).toList());

        // **실사용자 결제에 있는 상호만** 브랜드 표에 앉는다. 대역은 `realNames` 에 담긴 것만
        // 실물로 인정한다 — 게이트 자체를 시험할 수 있어야 하기 때문이다.
        payments = mock(com.finntech.repository.UserPaymentRepository.class);
        when(payments.existsRealPersonPaymentByMerchantName(anyString()))
                .thenAnswer(inv -> realNames.contains(inv.getArgument(0)));

        service = new MerchantBrandService(brands, categories, mock(TempClassifierService.class),
                payments, new tools.jackson.databind.ObjectMapper());
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
                MerchantCategory.Source.USER_CSV, null);
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
                MerchantCategory.Source.USER_CONFIRMED, 7L);
        service.promote(row);

        assertThat(row.getBrand()).isEqualTo("스타벅스");
        assertThat(staging).as("두 곳에 남으면 어느 쪽이 정본인지 알 수 없다").isEmpty();
    }

    @Test
    @DisplayName("번호가 달라도 이름이 같으면 같은 브랜드 — PG·복합·일반을 안 가린다")
    void brandKeysOnNameNotBusinessNumber() {
        // PG 를 거친 행(번호가 지워짐)과 일반 행(번호 있음)이 같은 상호로 사전에 있다고 하자.
        var viaPg = new MerchantCategory("", "유니클로 온라인 스토어", "쇼핑",
                MerchantCategory.Source.USER_CSV, null);
        var direct = new MerchantCategory("0000000013", "유니클로 온라인 스토어", "쇼핑",
                MerchantCategory.Source.USER_CSV, null);
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
                MerchantCategory.Source.USER_CSV, null);
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
                MerchantCategory.Source.USER_CSV, null);
        row.adoptBrand("스타벅스");
        dictionary.add(row);

        var got = service.fill(List.of("GS25 강남역점", "스타벅스 포항공대점"));

        assertThat(got).containsEntry("GS25 강남역점", "GS25")
                       .containsEntry("스타벅스 포항공대점", "스타벅스");
        // 대역 분류기는 usable() 이 false 라 아무것도 못 묻는다 — 그래도 둘 다 나왔다는 것은
        // 이미 아는 것으로만 채웠다는 뜻이다.
    }

    @Test
    @DisplayName("사전이 대기 장소보다 먼저다")
    void dictionaryWinsOverStaging() {
        staging.add(new MerchantBrand("어떤 가게", "대기장소브랜드", MerchantBrand.Source.TEMP_MODEL));
        var row = new MerchantCategory("0000000016", "어떤 가게", "생활",
                MerchantCategory.Source.USER_CSV, null);
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
                MerchantCategory.Source.USER_CSV, null));
        service.remember("생성기가만든가게", "어떤브랜드");
        assertThat(dictionary.get(0).getBrand()).isNull();
    }

    @Test
    @DisplayName("빈 목록은 호출도 안 한다")
    void emptyInputShortCircuits() {
        assertThat(service.fill(List.of())).isEmpty();
        assertThat(service.fill(null)).isEmpty();
        assertThat(service.fill(java.util.Arrays.asList("", "  "))).isEmpty();
        assertThat(Optional.ofNullable(staging.isEmpty() ? null : staging.get(0))).isEmpty();
    }
}
