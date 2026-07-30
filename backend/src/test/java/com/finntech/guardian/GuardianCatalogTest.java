package com.finntech.guardian;

import com.finntech.guardian.domain.GuardianEnums.Grade;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 소품 카탈로그 — 도감·상점·마이룸이 <b>같은 표</b>를 보는지 고정한다.
 *
 * <p>이 표가 어긋나면 조용히 망가진다: 지급되는 사물 코드가 카탈로그에 없으면 도감에 안 뜨고,
 * 상점 물건이 도감에 섞이면 "모으는 것"과 "사는 것"의 구분이 사라진다. 크래시가 없으니
 * 테스트로 잡아야 한다.
 */
class GuardianCatalogTest {

    private final GuardianCatalog catalog = new GuardianCatalog();

    @Test
    @DisplayName("지급되는 사물 코드가 전부 카탈로그에 있다")
    void everyGrantableObjectIsInCatalog() {
        // GuardianRewardService가 뽑는 풀 — 여기 없는 코드가 지급되면 도감에 이름 없이 뜬다.
        List<String> pool = List.of(
                "plant_small_01", "plant_small_02", "cushion_01", "mug_01", "book_stack_01",
                "lamp_small_01", "rug_small_01", "clock_01", "frame_01", "basket_01",
                "plant_large_01", "armchair_01", "record_player_01", "aquarium_small_01",
                "bookshelf_01", "floor_lamp_01", "cat_bed_01",
                "window_garden_01", "fireplace_01", "piano_01", "telescope_01");

        Set<String> collectible = catalog.collectible().stream()
                .map(GuardianCatalog.Item::code).collect(Collectors.toSet());
        assertThat(collectible).as("도감에 실리는 코드").containsAll(pool);
    }

    @Test
    @DisplayName("상점 물건은 도감에 섞이지 않는다")
    void shopItemsAreNotCollectible() {
        Set<String> collectible = catalog.collectible().stream()
                .map(GuardianCatalog.Item::code).collect(Collectors.toSet());
        for (GuardianCatalog.Item shop : catalog.shopItems()) {
            assertThat(collectible).as("상점 물건 %s", shop.code()).doesNotContain(shop.code());
            assertThat(shop.purchasable()).isTrue();
            assertThat(shop.price()).isPositive();
        }
    }

    @Test
    @DisplayName("표시 정보가 비어 있지 않다 — 화면에 빈칸이 생기면 안 된다")
    void everyItemHasDisplayInfo() {
        List<GuardianCatalog.Item> all = new java.util.ArrayList<>(catalog.collectible());
        all.addAll(catalog.shopItems());
        assertThat(all).isNotEmpty();
        assertThat(all).allSatisfy(i -> {
            assertThat(i.name()).isNotBlank();
            assertThat(i.glyph()).isNotBlank();
            assertThat(i.story()).isNotBlank();
        });
    }

    @Test
    @DisplayName("모르는 코드는 null이 아니라 대체 행을 준다 — 구버전 지급분이 화면을 비우지 않게")
    void unknownCodeFallsBack() {
        GuardianCatalog.Item it = catalog.find("legacy_thing_99");
        assertThat(it).isNotNull();
        assertThat(it.name()).isEqualTo("legacy_thing_99");   // 이름이 없으면 코드라도 보여준다
        assertThat(it.glyph()).isNotBlank();
        assertThat(it.purchasable()).isFalse();               // 살 수 있는 물건으로 오해되면 안 된다
    }

    @Test
    @DisplayName("마일스톤은 오름차순이고 다음 목표를 정확히 짚는다")
    void milestonesAreOrderedAndNextIsCorrect() {
        List<GuardianCatalog.Milestone> ms = catalog.milestones();
        assertThat(ms).isNotEmpty();
        for (int i = 1; i < ms.size(); i++) {
            assertThat(ms.get(i).count()).isGreaterThan(ms.get(i - 1).count());
        }
        int first = ms.get(0).count();
        assertThat(catalog.nextMilestone(0)).get().extracting(GuardianCatalog.Milestone::count).isEqualTo(first);
        // 딱 채운 순간엔 그 마일스톤이 아니라 **다음** 것을 가리켜야 한다(받은 걸 또 가리키면 안 된다).
        assertThat(catalog.nextMilestone(first)).get()
                .extracting(GuardianCatalog.Milestone::count).isEqualTo(ms.get(1).count());
        assertThat(catalog.nextMilestone(9999)).isEmpty();
    }

    @Test
    @DisplayName("도감 총량이 마지막 마일스톤보다 많다 — 못 채우는 목표를 걸어두지 않는다")
    void collectibleCountCoversLastMilestone() {
        int last = catalog.milestones().get(catalog.milestones().size() - 1).count();
        assertThat(catalog.collectible().size()).isGreaterThanOrEqualTo(last);
    }

    @Test
    @DisplayName("에픽은 소수다 — 흔하면 희소성이 없다")
    void epicIsRare() {
        long epic = catalog.collectible().stream().filter(i -> i.grade() == Grade.EPIC).count();
        long total = catalog.collectible().size();
        assertThat(epic).isPositive();
        assertThat((double) epic / total).isLessThan(0.3);
    }
}
