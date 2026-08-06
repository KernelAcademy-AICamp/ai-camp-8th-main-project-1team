package com.finntech.guardian;

import com.finntech.guardian.domain.GuardianEnums.Grade;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 소품·가구 카탈로그 — <b>도감·상점·마이룸이 같은 표를 본다</b>.
 *
 * <p>예전에는 {@code GuardianRewardService}가 사물 코드({@code plant_small_01})만 알고 있었다.
 * 코드로는 화면을 그릴 수 없다 — 도감은 "몬스테라 화분 · 희귀 · 7.14 위기 방어"처럼 이름·등급·
 * 획득 사연을 보여줘야 하고, 상점은 값과 그림이 필요하다. 그 표시 정보가 프론트에 하드코딩되면
 * 새 소품을 넣을 때 두 곳을 고쳐야 하고, 서버가 준 코드와 화면의 목록이 조용히 갈라진다.
 *
 * <p><b>글리프</b>는 프론트의 SVG 심볼 키다. 그림 자체는 프론트에 있고 서버는 어느 그림인지만
 * 가리킨다 — 서버가 SVG를 들고 있을 이유는 없다.
 *
 * <p><b>사연(story)</b>은 문구 템플릿이 아니라 그 소품의 성격이다. 실제 획득 사유는
 * {@code RoomObject.reasonCode}에 남고, 도감이 그 둘을 합쳐 보여준다.
 */
@Component
public class GuardianCatalog {

    /** 상점 분류 — 가구는 방에 놓고, 배경은 벽·바닥을 바꾼다. 소품은 지급 전용이라 팔지 않는다. */
    /**
     * 상점 진열대. {@code CHARACTER} 는 <b>방에 놓는 물건이 아니라 지킴이 자신</b>이라 따로 둔다 —
     * 가구는 여러 개를 함께 놓지만 캐릭터는 언제나 하나만 고른다.
     */
    public enum ShopCategory { FURNITURE, BACKGROUND, CHARACTER, NONE }

    /**
     * 카탈로그 1행.
     *
     * @param code     서버 코드({@code RoomObject.objectId})
     * @param name     화면 표시명
     * @param grade    등급 — 지급 확률과 도감 뱃지 색을 함께 정한다
     * @param glyph    프론트 SVG 심볼 키
     * @param story    이 소품이 어떤 소품인지
     * @param shop     상점 분류
     * @param price    포인트 가격(상점에 없으면 0)
     */
    public record Item(String code, String name, Grade grade, String glyph,
                       String story, ShopCategory shop, int price) {

        /** 상점에서 살 수 있는가 — 지급 전용 소품과 가르는 기준. */
        public boolean purchasable() { return shop != ShopCategory.NONE && price > 0; }
    }

    /**
     * 도감 마일스톤 — 몇 종을 모으면 무엇을 주는가.
     *
     * <p>보상은 <b>아이템</b>이지 포인트가 아니다. 포인트로도 줄 수 있지만, 그러면 상점에서 살 수
     * 있는 것과 구분이 사라져 "모으는 보람"이 옅어진다. 면제권·미션 변경권은 상점에서 팔지 않는다.
     */
    public record Milestone(int count, String reward, String label) {}

    private static final List<Milestone> MILESTONES = List.of(
            new Milestone(10, "EXEMPTION", "면제권"),
            new Milestone(15, "MISSION_CHANGE", "미션 변경권"),
            new Milestone(20, "EPIC_DRAW", "에픽 뽑기"));

    private final Map<String, Item> byCode = new LinkedHashMap<>();

    public GuardianCatalog() {
        // ── 일반(COMMON) — 무지출 하루에 흔히 들어온다
        add("plant_small_01", "몬스테라 화분", Grade.COMMON, "plant", "작게 시작하는 방의 첫 초록이에요.");
        add("plant_small_02", "산세베리아", Grade.COMMON, "plant", "물을 자주 안 줘도 잘 크는 화분이에요.");
        add("cushion_01", "체크 쿠션", Grade.COMMON, "sofa", "바닥에 앉는 날이 편해졌어요.");
        add("mug_01", "머그컵", Grade.COMMON, "mug", "카페 대신 집에서 내려 마신 날의 기념이에요.");
        add("book_stack_01", "쌓아둔 책", Grade.COMMON, "books", "사놓고 못 읽던 책을 펼친 밤이에요.");
        add("lamp_small_01", "작은 조명", Grade.COMMON, "lamp", "밤을 부드럽게 만들어 주는 불빛이에요.");
        add("rug_small_01", "작은 러그", Grade.COMMON, "rug2", "발밑이 포근해졌어요.");
        add("clock_01", "벽시계", Grade.COMMON, "frame", "시간을 보는 자리가 생겼어요.");
        add("frame_01", "그린 액자", Grade.COMMON, "frame", "빈 벽에 걸린 첫 그림이에요.");
        add("basket_01", "라탄 바구니", Grade.COMMON, "shelf", "흩어진 것들이 제자리를 찾았어요.");

        // ── 희귀(RARE) — 위기를 넘긴 날처럼 드문 사건에 붙는다
        add("plant_large_01", "큰 화분", Grade.RARE, "plant", "방의 공기가 달라지는 큰 초록이에요.");
        add("armchair_01", "1인 소파", Grade.RARE, "sofa", "혼자 앉아 쉬는 자리가 생겼어요.");
        add("record_player_01", "턴테이블", Grade.RARE, "mood", "조용한 밤에 어울리는 소리예요.");
        add("aquarium_small_01", "작은 어항", Grade.RARE, "mood", "바라보고 있으면 마음이 가라앉아요.");
        add("bookshelf_01", "원목 책장", Grade.RARE, "shelf", "첫 연속 기록을 세운 날 도착했어요.");
        add("floor_lamp_01", "스탠드 조명", Grade.RARE, "lamp", "예산을 넘길 뻔한 날을 끝까지 지켜낸 표식이에요.");
        add("cat_bed_01", "냥이 방석", Grade.RARE, "bed", "냥지킴이가 제일 좋아하는 자리예요.");

        // ── 에픽(EPIC) — 도감 마일스톤이나 큰 성취에서만
        add("window_garden_01", "창가 정원", Grade.EPIC, "plant", "창밖까지 이어지는 초록이에요.");
        add("fireplace_01", "벽난로", Grade.EPIC, "mood", "겨울 밤을 데워주는 자리예요.");
        add("piano_01", "업라이트 피아노", Grade.EPIC, "books", "방의 주인공이 되는 가구예요.");
        add("telescope_01", "망원경", Grade.EPIC, "frame", "먼 곳을 보는 습관이 생겼어요.");

        // ── 상점 가구 — 포인트로만 산다(현금 충전 경로 없음)
        shopItem("furn_sofa_mint", "민트 소파", "sofa", "방의 중심이 되는 소파예요.", ShopCategory.FURNITURE, 150);
        shopItem("furn_rug_pink", "핑크 러그", "rug", "방 분위기가 확 바뀌는 러그예요.", ShopCategory.FURNITURE, 100);
        shopItem("furn_rug_mint", "민트 러그", "rug2", "기분 따라 바꿔 까는 러그예요.", ShopCategory.FURNITURE, 100);
        shopItem("furn_table_side", "사이드테이블", "table", "무드등 자리를 만들어 주는 짝꿍이에요.", ShopCategory.FURNITURE, 110);
        shopItem("furn_bed_cozy", "포근한 침대", "bed", "하루를 마무리하는 자리예요.", ShopCategory.FURNITURE, 120);

        // ── 상점 배경 — 벽지·바닥
        shopItem("bg_wall_mint", "민트 벽지", "wall1", "방 전체가 산뜻해져요.", ShopCategory.BACKGROUND, 250);
        shopItem("bg_wall_cream", "크림 벽지", "wall2", "따뜻한 톤으로 바뀌어요.", ShopCategory.BACKGROUND, 250);
        shopItem("bg_floor_dark", "다크우드 바닥", "floor1", "차분하게 가라앉는 바닥이에요.", ShopCategory.BACKGROUND, 380);
        shopItem("bg_floor_check", "체크 바닥", "floor2", "경쾌한 패턴의 바닥이에요.", ShopCategory.BACKGROUND, 380);

        // ── 상점 캐릭터 — 지킴이의 털색
        //
        // 크림·그레이·치즈·초코는 **처음부터 준다**(프로토타입_0806 꾸미기 시트). 사는 것은 삼색이
        // 하나뿐이다 — 고를 수 있는 것이 하나도 없으면 꾸미기가 상점 광고가 되고, 반대로 전부 팔면
        // 기본 모습조차 돈을 내야 하는 것이 된다. 기본 넷 + 사는 하나가 그 사이다.
        shopItem("char_cat_calico", "삼색이", "catsit_calico",
                "포인트를 모아 데려온 세 가지 색 친구예요.", ShopCategory.CHARACTER, 300);
    }

    private void add(String code, String name, Grade grade, String glyph, String story) {
        byCode.put(code, new Item(code, name, grade, glyph, story, ShopCategory.NONE, 0));
    }

    private void shopItem(String code, String name, String glyph, String story,
                          ShopCategory cat, int price) {
        // 상점 가구는 등급을 매기지 않는다 — 모아서 얻는 것이 아니라 사는 것이라 희소성이 없다.
        byCode.put(code, new Item(code, name, Grade.COMMON, glyph, story, cat, price));
    }

    /** 코드로 조회. 모르는 코드는 null이 아니라 <b>코드를 이름으로 쓰는 임시 행</b>을 준다. */
    public Item find(String code) {
        Item it = byCode.get(code);
        if (it != null) return it;
        // 카탈로그에 없는 코드가 DB에 남아 있을 수 있다(구버전 지급분). 화면이 비지 않게 대체한다.
        return new Item(code, code, Grade.COMMON, "plant", "", ShopCategory.NONE, 0);
    }

    /** 도감에 실리는 전부 — 지급 소품만. 상점 가구는 '모으는 대상'이 아니다. */
    public List<Item> collectible() {
        List<Item> out = new ArrayList<>();
        for (Item i : byCode.values()) if (i.shop() == ShopCategory.NONE) out.add(i);
        return out;
    }

    /** 상점에 진열되는 전부. */
    public List<Item> shopItems() {
        List<Item> out = new ArrayList<>();
        for (Item i : byCode.values()) if (i.purchasable()) out.add(i);
        return out;
    }

    public List<Milestone> milestones() { return MILESTONES; }

    /** 지금까지 모은 종수로 <b>다음</b> 마일스톤을 찾는다. 다 채웠으면 empty. */
    public java.util.Optional<Milestone> nextMilestone(int owned) {
        for (Milestone m : MILESTONES) if (owned < m.count()) return java.util.Optional.of(m);
        return java.util.Optional.empty();
    }
}
