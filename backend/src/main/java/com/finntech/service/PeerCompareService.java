package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.ConsumptionRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * <b>또래는 얼마나 쓰는가</b> — 리포트의 비교 막대(프로토타입_0818 `.peer`).
 *
 * <h2>왜 이 비교를 두는가</h2>
 *
 * <p>"이번 주에 12만원 썼다"만으로는 잘한 것인지 알 수 없다. 사람은 절대액이 아니라
 * <b>견줄 것</b>이 있어야 자기 소비를 읽는다. 그런데 견줄 대상이 '평균적인 한국인'이면
 * 너무 멀고, '나의 지난달'이면 이미 리포트 다른 자리가 말하고 있다. 나이대가 가까운 사람들이
 * 그 사이에 있다.
 *
 * <h2>중앙값을 쓴다</h2>
 *
 * <p>평균이 아니다. 소비는 <b>오른쪽으로 긴 꼬리</b>를 가진 분포라(대부분 적게 쓰고 몇 명이
 * 아주 많이 쓴다) 평균은 그 몇 명에게 끌려간다. 그러면 대부분의 사용자가 "나는 또래보다
 * 적게 쓴다"는 말을 듣게 되고, 그 말은 위로도 정보도 아니다. 중앙값은 "절반은 이보다 적게
 * 쓴다"는 뜻이라 자기 자리를 알려 준다.
 *
 * <h2>표본이 적으면 아예 말하지 않는다</h2>
 *
 * <p>또래가 서넛뿐이면 그중 한 명의 이사·수술 한 번이 '또래'가 된다. 최소 인원에 못 미치면
 * {@code null} 을 돌려주고 <b>화면은 그 절을 통째로 감춘다</b> — 표본이 얇다는 주석을 달아
 * 보여주는 것보다 안 보여주는 편이 정직하다.
 *
 * <h2>남의 것은 숫자 하나도 안 가져온다</h2>
 *
 * <p>집계는 DB 안에서 끝난다({@code sumByUserInRange}). 애플리케이션으로 올라오는 것은
 * 사람별 <b>합계</b>뿐이고, 응답으로 나가는 것은 그 합계들의 <b>중앙값 하나</b>다.
 * 누가 얼마를 썼는지는 어디에도 안 남는다(마스터 §4 원칙 1과 같은 태도).
 */
@Service
public class PeerCompareService {

    /** 나이대 폭(±년). 좁히면 표본이 마르고 넓히면 '또래'가 아니게 된다. */
    private static final int AGE_BAND = 3;
    /** 이 인원에 못 미치면 비교를 만들지 않는다. */
    private static final int MIN_SAMPLE = 5;

    private final AppUserRepository users;
    private final ConsumptionRepository consumptions;
    private final Clock clock;
    /** 나이대 폭·최소 표본은 데이터가 쌓이면 조정한다 — 설정으로 뺀다(원칙 4). */
    private final int ageBand;
    private final int minSample;

    public PeerCompareService(AppUserRepository users, ConsumptionRepository consumptions, Clock clock,
                              @Value("${finntech.peer.age-band:" + AGE_BAND + "}") int ageBand,
                              @Value("${finntech.peer.min-sample:" + MIN_SAMPLE + "}") int minSample) {
        this.users = users;
        this.consumptions = consumptions;
        this.clock = clock;
        this.ageBand = ageBand;
        this.minSample = minSample;
    }

    /**
     * @param mine       내 기간 지출
     * @param peer       또래의 <b>중앙값</b>
     * @param ageFrom    또래로 본 출생연도 범위(표시용)
     * @param ageTo      위와 같음
     * @param sampleSize 그 범위에서 실제로 소비가 있던 사람 수
     * @param days       비교한 기간(일)
     */
    public record PeerCompare(long mine, long peer, int ageFrom, int ageTo, int sampleSize, int days) {}

    /**
     * 같은 나이대와 견준다. 견줄 수 없으면 {@code null} —
     * 출생연도를 모르거나(본인인증 전) 또래 표본이 얇을 때다.
     */
    @Transactional(readOnly = true)
    public PeerCompare compare(Long userId, int days) {
        AppUser me = users.findById(userId).orElse(null);
        if (me == null || me.getBirthYear() == null) return null;

        LocalDateTime to = LocalDateTime.now(clock);
        LocalDateTime from = to.minusDays(days);
        int year = me.getBirthYear();
        List<Long> peerIds = users.findPeerIds(year - ageBand, year + ageBand, userId);
        if (peerIds.size() < minSample) return null;

        List<Long> sums = new ArrayList<>();
        for (Object[] row : consumptions.sumByUserInRange(peerIds, from, to)) {
            BigDecimal total = (BigDecimal) row[1];
            if (total != null) sums.add(total.longValue());
        }
        // **소비가 0인 사람은 세지 않는다.** 가입만 하고 안 쓰는 계정이 섞이면 중앙값이
        // 0 쪽으로 끌려가 "또래는 거의 안 쓴다"가 된다 — 비교가 아니라 착시다.
        if (sums.size() < minSample) return null;

        long mine = 0;
        for (Object[] row : consumptions.sumByUserInRange(List.of(userId), from, to)) {
            BigDecimal total = (BigDecimal) row[1];
            if (total != null) mine += total.longValue();
        }

        return new PeerCompare(mine, median(sums), year - ageBand, year + ageBand, sums.size(), days);
    }

    /** 짝수 개면 가운데 둘의 평균 — 그래야 한 명이 빠질 때 값이 튀지 않는다. */
    private static long median(List<Long> values) {
        List<Long> sorted = values.stream().sorted().toList();
        int n = sorted.size();
        return n % 2 == 1 ? sorted.get(n / 2)
                : Math.round((sorted.get(n / 2 - 1) + sorted.get(n / 2)) / 2.0);
    }
}
