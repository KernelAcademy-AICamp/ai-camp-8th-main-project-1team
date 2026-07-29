package com.finntech.guardian;

import com.finntech.guardian.domain.GuardianEnums.Grade;
import com.finntech.guardian.domain.GuardianItems;
import com.finntech.guardian.domain.GuardianEnums.ObjectSource;
import com.finntech.guardian.domain.RoomObject;
import com.finntech.guardian.repository.GuardianItemsRepository;
import com.finntech.guardian.repository.RoomObjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 도감 · 포인트샵 (개편안 {@code s-collection} · {@code s-shop}).
 *
 * <p><b>도감은 기록이지 목록이 아니다.</b> "무엇을 가졌나"만 보여주면 그냥 인벤토리다. 언제 · 왜
 * 들어왔는지가 함께 있어야 "7.14 위기 방어"처럼 그날의 이야기가 된다. 그래서 카탈로그(표시 정보)와
 * {@link RoomObject}(획득 사실)를 합쳐 내려보낸다.
 *
 * <p><b>마일스톤 보상은 멱등이어야 한다.</b> 도감을 열 때마다 10종째 보상을 다시 주면 안 된다.
 * 지급 여부를 따로 저장하지 않고 <b>소유 종수로 판정</b>한다 — 종수는 줄어들지 않으므로, 이미 넘어선
 * 마일스톤은 {@code claimed=true}로 나가고 청구 API가 거절한다.
 */
@Service
public class GuardianCollectionService {

    private final GuardianCatalog catalog;
    private final RoomObjectRepository roomObjectRepository;
    private final GuardianItemsRepository itemsRepository;
    private final Clock clock;

    public GuardianCollectionService(GuardianCatalog catalog,
                                     RoomObjectRepository roomObjectRepository,
                                     GuardianItemsRepository itemsRepository,
                                     Clock clock) {
        this.catalog = catalog;
        this.roomObjectRepository = roomObjectRepository;
        this.itemsRepository = itemsRepository;
        this.clock = clock;
    }

    // ======================================================================
    //  DTO
    // ======================================================================

    /**
     * 도감 한 칸.
     *
     * @param owned   false면 화면에 자물쇠로 그린다 — 무엇이 남았는지 보여야 모을 마음이 생긴다
     * @param reason  획득 사유 코드(무지출·연속 보너스·위기 방어…). 미보유면 null
     */
    public record Cell(String code, String name, String grade, String glyph, String story,
                       boolean owned, LocalDate acquiredDate, String reason) {}

    /** @param claimed 이미 받은 보상인가 — 소유 종수가 기준을 넘었으면 받은 것으로 본다 */
    public record MilestoneView(int count, String reward, String label, boolean claimed) {}

    public record CollectionView(int owned, int total, int percent,
                                 List<Cell> cells, List<MilestoneView> milestones,
                                 MilestoneView next, int exemption, int missionChange,
                                 int grassGuard, int points) {}

    public record ShopEntry(String code, String name, String glyph, String story,
                            String category, int price, boolean owned, boolean affordable) {}

    public record ShopView(int points, List<ShopEntry> items) {}

    // ======================================================================
    //  도감
    // ======================================================================

    @Transactional(readOnly = true)
    public CollectionView collection(Long userId) {
        List<RoomObject> mine = roomObjectRepository.findByUser(userId);
        // 같은 소품을 두 번 받는 일은 없지만(pickUnowned), 구버전 데이터를 위해 코드로 접는다.
        var owned = new java.util.LinkedHashMap<String, RoomObject>();
        for (RoomObject o : mine) owned.putIfAbsent(o.getObjectId(), o);

        List<Cell> cells = new ArrayList<>();
        for (GuardianCatalog.Item item : catalog.collectible()) {
            RoomObject got = owned.get(item.code());
            cells.add(new Cell(item.code(), item.name(), item.grade().name(), item.glyph(),
                    item.story(), got != null,
                    got == null ? null : got.getAcquiredDate(),
                    got == null ? null : got.getReasonCode()));
        }
        int total = cells.size();
        int have = (int) cells.stream().filter(Cell::owned).count();

        List<MilestoneView> ms = new ArrayList<>();
        for (GuardianCatalog.Milestone m : catalog.milestones()) {
            ms.add(new MilestoneView(m.count(), m.reward(), m.label(), have >= m.count()));
        }
        MilestoneView next = catalog.nextMilestone(have)
                .map(m -> new MilestoneView(m.count(), m.reward(), m.label(), false))
                .orElse(null);

        GuardianItems it = items(userId);
        return new CollectionView(have, total, total == 0 ? 0 : have * 100 / total,
                cells, ms, next, it.getExemption(), it.getMissionChange(),
                it.getGrassGuard(), it.getPointBalance());
    }

    // ======================================================================
    //  포인트샵
    // ======================================================================

    @Transactional(readOnly = true)
    public ShopView shop(Long userId) {
        Set<String> owned = ownedCodes(userId);
        int points = items(userId).getPointBalance();
        List<ShopEntry> out = new ArrayList<>();
        for (GuardianCatalog.Item i : catalog.shopItems()) {
            boolean have = owned.contains(i.code());
            out.add(new ShopEntry(i.code(), i.name(), i.glyph(), i.story(),
                    i.shop().name(), i.price(), have, !have && points >= i.price()));
        }
        return new ShopView(points, out);
    }

    /**
     * 구매 — 포인트를 깎고 방에 놓는다.
     *
     * <p><b>포인트로만 산다.</b> 현금 결제 경로는 만들지 않는다(설계 원칙). 그래서 잔액이 모자라면
     * 그냥 거절이고, 부분 결제나 외상은 없다.
     */
    @Transactional
    public ShopView buy(Long userId, String code) {
        GuardianCatalog.Item item = catalog.find(code);
        if (!item.purchasable()) throw new IllegalArgumentException("상점에 없는 물건입니다: " + code);
        if (ownedCodes(userId).contains(code)) throw new IllegalStateException("이미 가지고 있어요");

        GuardianItems it = items(userId);
        if (it.getPointBalance() < item.price()) {
            throw new IllegalStateException("포인트가 " + (item.price() - it.getPointBalance()) + "P 모자라요");
        }
        LocalDateTime now = LocalDateTime.now(clock);
        it.addPoints(-item.price(), now);
        itemsRepository.save(it);

        RoomObject obj = new RoomObject(userId, code, Grade.COMMON, now.toLocalDate(),
                "SHOP_PURCHASE", ObjectSource.SHOP, now);
        // 배경(벽지·바닥)은 슬롯을 차지하지 않는다 — 방 전체에 입히는 것이라 자리가 없다.
        if (item.shop() == GuardianCatalog.ShopCategory.FURNITURE) {
            obj.placeAt(GuardianRewardService.firstFreeSlot(roomObjectRepository.findOccupiedSlots(userId)));
        }
        roomObjectRepository.save(obj);
        return shop(userId);
    }

    /**
     * 마일스톤 보상 청구 — 도감 N종을 채웠을 때 한 번만.
     *
     * <p>이미 넘어선 마일스톤을 다시 청구하는 것을 막아야 하는데, 지급 이력을 따로 두는 대신
     * <b>보상 아이템 자체를 근거로</b> 삼는다. 면제권은 도감 10종 외에는 나오지 않으므로
     * (상점에서 팔지 않는다), 이미 갖고 있거나 쓴 적이 있으면 받은 것이다.
     * — 이력 테이블을 하나 덜 만들고도 같은 보장을 얻는다.
     */
    @Transactional
    public CollectionView claim(Long userId, int count) {
        CollectionView view = collection(userId);
        GuardianCatalog.Milestone target = catalog.milestones().stream()
                .filter(m -> m.count() == count).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("그런 마일스톤이 없어요: " + count));
        if (view.owned() < target.count()) {
            throw new IllegalStateException(target.count() + "종을 모으면 열려요");
        }
        GuardianItems it = items(userId);
        LocalDateTime now = LocalDateTime.now(clock);
        switch (target.reward()) {
            case "EXEMPTION" -> it.grant(1, 0, 0, now);
            case "MISSION_CHANGE" -> it.grant(0, 0, 1, now);
            case "EPIC_DRAW" -> it.grant(0, 1, 0, now);   // 에픽 뽑기는 잔디 보호권으로 대신 지급
            default -> throw new IllegalStateException("모르는 보상: " + target.reward());
        }
        itemsRepository.save(it);
        return collection(userId);
    }

    // ======================================================================
    //  마이룸 배치 (개편안 '꾸미기 모드')
    // ======================================================================

    /**
     * 소품을 슬롯에 놓거나(0~19) 창고로 내린다({@code slot=null}).
     *
     * <p><b>그 자리에 있던 소품은 사라지지 않고 창고로 간다.</b> 도감의 기록이라 지울 수 없고,
     * 사용자도 "바꿨더니 없어졌다"를 겪으면 안 된다 — 개편안의 문구도 "○○는 도감에 보관돼요"다.
     *
     * @throws IllegalArgumentException 남의 소품이거나 슬롯 번호가 범위를 벗어난 경우
     */
    @Transactional
    public List<RoomObject> place(Long userId, String code, Integer slot) {
        List<RoomObject> mine = roomObjectRepository.findByUser(userId);
        RoomObject target = mine.stream()
                .filter(o -> o.getObjectId().equals(code)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("가지고 있지 않은 소품이에요: " + code));

        if (slot != null) {
            // 그 자리를 쓰고 있던 다른 소품을 먼저 비운다 — 한 자리에 둘이 겹치면 씬이 깨진다.
            for (RoomObject o : mine) {
                if (!o.getId().equals(target.getId()) && slot.equals(o.getSlotIndex())) {
                    o.placeAt(null);
                    roomObjectRepository.save(o);
                }
            }
        }
        target.placeAt(slot);          // 범위 검사는 엔티티가 한다
        roomObjectRepository.save(target);
        return roomObjectRepository.findByUser(userId);
    }

    // ======================================================================
    //  내부
    // ======================================================================

    private Set<String> ownedCodes(Long userId) {
        Set<String> out = new LinkedHashSet<>();
        for (RoomObject o : roomObjectRepository.findByUser(userId)) {
            out.add(o.getObjectId());
        }
        return out;
    }

    private GuardianItems items(Long userId) {
        return itemsRepository.findByUserId(userId)
                .orElseGet(() -> itemsRepository.save(
                        new GuardianItems(userId, LocalDateTime.now(clock))));
    }
}
