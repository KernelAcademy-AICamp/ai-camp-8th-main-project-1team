package com.finntech.guardian;

import com.finntech.guardian.domain.GuardianEnums.Grade;
import com.finntech.guardian.domain.GuardianEnums.ObjectSource;
import com.finntech.guardian.domain.GuardianEnums.PointType;
import com.finntech.guardian.domain.GuardianItems;
import com.finntech.guardian.domain.GuardianPointEvent;
import com.finntech.guardian.domain.RoomObject;
import com.finntech.guardian.repository.GuardianItemsRepository;
import com.finntech.guardian.repository.GuardianPointEventRepository;
import com.finntech.guardian.repository.RoomObjectRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * 보상 계층 — 사물 추첨과 포인트 적립 (설계서 §3.5·§3.6·§3.7).
 *
 * <p><b>역할 분담.</b> 지킴이({@link GuardianRules})가 <b>가중치를 정하고</b>, 이 클래스가
 * <b>추첨을 실행</b>한다. 판정 로직이 보상 쪽으로 새면 규칙이 두 곳으로 갈라진다.
 *
 * <p><b>추첨은 결정론이다(마스터 §4 원칙 3).</b> {@code Math.random()}을 쓰면 같은 입력이
 * 매번 다른 결과를 내어 재현성 검증도, 데모 재생도 불가능해진다. 그래서 시드를
 * (챌린지, 판정일, 리롤 횟수)에서 유도한다 — 같은 날을 다시 판정하면 같은 사물이 나온다.
 */
@Service
public class GuardianRewardService {

    /**
     * 사물 마스터 데이터. 게임 에셋 키이므로 코드에 둔다 — 마스터 §4 원칙 4가 금지하는 것은
     * <b>카테고리 이름</b>이지 에셋 식별자가 아니다.
     */
    private static final Map<Grade, List<String>> OBJECT_POOL = Map.of(
            Grade.COMMON, List.of(
                    "plant_small_01", "plant_small_02", "cushion_01", "mug_01", "book_stack_01",
                    "lamp_small_01", "rug_small_01", "clock_01", "frame_01", "basket_01"),
            Grade.RARE, List.of(
                    "plant_large_01", "armchair_01", "record_player_01", "aquarium_small_01",
                    "bookshelf_01", "floor_lamp_01", "cat_bed_01"),
            Grade.EPIC, List.of(
                    "window_garden_01", "fireplace_01", "piano_01", "telescope_01"));

    /** 등급 확인 순서 — 뽑힌 등급이 동나면 이 순서로 내려가며 대체한다. */
    private static final List<Grade> GRADE_FALLBACK_ORDER = List.of(Grade.EPIC, Grade.RARE, Grade.COMMON);

    private final RoomObjectRepository roomObjectRepository;
    private final GuardianItemsRepository itemsRepository;
    private final GuardianPointEventRepository pointEventRepository;
    private final GuardianProperties props;

    public GuardianRewardService(RoomObjectRepository roomObjectRepository,
                                 GuardianItemsRepository itemsRepository,
                                 GuardianPointEventRepository pointEventRepository,
                                 GuardianProperties props) {
        this.roomObjectRepository = roomObjectRepository;
        this.itemsRepository = itemsRepository;
        this.pointEventRepository = pointEventRepository;
        this.props = props;
    }

    // ======================================================================
    //  순수 계산 (단위 테스트 진입점)
    // ======================================================================

    /**
     * 가중치와 난수(0~1)로 등급을 고른다. 순수 함수.
     * 누적 확률을 COMMON→RARE→EPIC 순으로 훑어 처음 넘어서는 등급을 낸다.
     */
    static Grade drawGrade(Map<Grade, Double> weights, double roll) {
        double cumulative = 0.0;
        for (Grade g : List.of(Grade.COMMON, Grade.RARE, Grade.EPIC)) {
            cumulative += weights.getOrDefault(g, 0.0);
            if (roll < cumulative) return g;
        }
        return Grade.COMMON;   // 가중치 합이 1에 못 미치는 경우의 안전망
    }

    /**
     * 추첨 시드 — (챌린지, 판정일, 리롤 횟수)에서 유도한다. 순수 함수.
     * 같은 날을 다시 판정하면 같은 결과가 나와야 재현성 검증이 가능하다.
     */
    static long drawSeed(long challengeId, LocalDate date, int rerollCount) {
        long h = challengeId * 1_000_003L;
        h = h * 31L + date.toEpochDay();
        h = h * 31L + rerollCount;
        return h;
    }

    /** 주간 상한 계산 키 — 그 날짜가 속한 주의 월요일. 순수 함수. */
    static LocalDate weekStart(LocalDate date) {
        return date.with(DayOfWeek.MONDAY);
    }

    /** 빈 슬롯 중 가장 앞 번호. 다 찼으면 null(창고로 간다). 순수 함수. */
    static Integer firstFreeSlot(List<Integer> occupied) {
        Set<Integer> used = new LinkedHashSet<>(occupied);
        for (int i = 0; i < RoomObject.SLOT_COUNT; i++) {
            if (!used.contains(i)) return i;
        }
        return null;
    }

    // ======================================================================
    //  공개 API
    // ======================================================================

    public record Granted(String objectId, Grade grade) {}

    /**
     * 사물 하나를 지급한다. 이미 다 모았으면 {@code empty}.
     *
     * @param rerollCount 리롤이면 1 이상 — 시드가 달라져 다른 사물이 나온다
     */
    @Transactional
    public Optional<Granted> grantObject(Long userId, Long challengeId, LocalDate date,
                                         Map<Grade, Double> weights, String reasonCode,
                                         int rerollCount, LocalDateTime now) {
        if (weights == null) return Optional.empty();

        Random rng = new Random(drawSeed(challengeId, date, rerollCount));
        Grade drawn = drawGrade(weights, rng.nextDouble());

        Optional<String> picked = pickUnowned(userId, drawn, rng);
        Grade grade = drawn;
        if (picked.isEmpty()) {
            // 뽑힌 등급이 동났다 — 다른 등급으로 대체한다. 빈손으로 돌려보내지 않는다.
            for (Grade alt : GRADE_FALLBACK_ORDER) {
                if (alt == drawn) continue;
                Optional<String> fromAlt = pickUnowned(userId, alt, rng);
                if (fromAlt.isPresent()) {
                    picked = fromAlt;
                    grade = alt;
                    break;
                }
            }
        }
        if (picked.isEmpty()) return Optional.empty();

        RoomObject obj = new RoomObject(userId, picked.get(), grade, date,
                reasonCode, ObjectSource.DAILY, now);
        obj.placeAt(firstFreeSlot(roomObjectRepository.findOccupiedSlots(userId)));
        roomObjectRepository.save(obj);
        return Optional.of(new Granted(picked.get(), grade));
    }

    private Optional<String> pickUnowned(Long userId, Grade grade, Random rng) {
        List<String> candidates = new ArrayList<>();
        for (String id : OBJECT_POOL.getOrDefault(grade, List.of())) {
            if (!roomObjectRepository.existsByUserIdAndObjectId(userId, id)) candidates.add(id);
        }
        if (candidates.isEmpty()) return Optional.empty();
        return Optional.of(candidates.get(rng.nextInt(candidates.size())));
    }

    /**
     * 포인트를 적립한다. 주간 상한을 적용한 실제 적립분을 돌려준다.
     *
     * <p>완주 보상(MONTHLY_COMPLETE)은 장기 보상이라 주간 상한과 별도로 지급한다 —
     * 상한에 걸려 완주 보상이 깎이면 한 달을 지킨 의미가 사라진다.
     */
    @Transactional
    public int award(Long userId, Long challengeId, PointType type, LocalDate date,
                     Long sourceRef, LocalDateTime now) {
        int amount = ruleAmount(type);
        boolean exempt = type == PointType.MONTHLY_COMPLETE && props.getPoint().isMonthlyExemptFromCap();

        LocalDate week = exempt ? null : weekStart(date);
        int capped = exempt ? amount : (int) GuardianRules.applyWeeklyCap(
                amount,
                pointEventRepository.sumCappedInWeek(userId, week),
                props.getPoint().getWeeklyCap());

        pointEventRepository.save(new GuardianPointEvent(
                userId, challengeId, type, amount, capped, week, sourceRef, now));
        if (capped > 0) items(userId, now).addPoints(capped, now);
        return capped;
    }

    private int ruleAmount(PointType type) {
        GuardianProperties.Point p = props.getPoint();
        return switch (type) {
            case WEEKLY_MISSION -> p.getWeeklyMission();
            case RISK_DEFENSE -> p.getRiskDefense();
            case LABELING -> p.getLabeling();
            case MONTHLY_COMPLETE -> p.getMonthlyComplete();
        };
    }

    /** 보유 아이템 레코드. 없으면 만든다. */
    @Transactional
    public GuardianItems items(Long userId, LocalDateTime now) {
        return itemsRepository.findByUserId(userId)
                .orElseGet(() -> itemsRepository.save(new GuardianItems(userId, now)));
    }

    /** 마이룸 사물 목록. */
    public List<RoomObject> objects(Long userId) {
        return roomObjectRepository.findByUser(userId);
    }
}
