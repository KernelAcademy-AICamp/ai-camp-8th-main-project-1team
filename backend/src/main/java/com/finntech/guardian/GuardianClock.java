package com.finntech.guardian;

import com.finntech.guardian.domain.DemoClock;
import com.finntech.guardian.repository.DemoClockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 가상 시계 — 설계서의 {@code app_now(user_id)} / {@code app_today(user_id)}에 대응한다.
 *
 * <p><b>지킴이 코드는 어디서도 {@code LocalDateTime.now()}를 직접 부르지 않는다.</b>
 * 30일짜리 챌린지를 발표 5분 안에 시연하려면 시간을 앞으로 밀 수 있어야 하고,
 * 그걸 나중에 고치려면 전부 뒤져야 한다. 첫날 정하면 5분이다.
 *
 * <p>구조는 [주입된 {@link Clock} 빈] + [사용자별 오프셋]이다. 앞의 것이 마스터 §4 원칙 3의
 * 재현성(테스트가 고정 Clock 주입)을 지키고, 뒤의 것이 데모의 시간 전진을 담당한다.
 * 둘을 겹쳐도 서로 방해하지 않는다 — 고정 Clock + 오프셋 0이면 그냥 고정 시각이다.
 */
@Service
public class GuardianClock {

    private final Clock clock;
    private final DemoClockRepository demoClockRepository;

    public GuardianClock(Clock clock, DemoClockRepository demoClockRepository) {
        this.clock = clock;
        this.demoClockRepository = demoClockRepository;
    }

    /** 이 사용자에게 지금은 언제인가. */
    public LocalDateTime now(Long userId) {
        long offset = demoClockRepository.findByUserId(userId)
                .map(DemoClock::getVirtualOffsetSeconds)
                .orElse(0L);
        return LocalDateTime.now(clock).plusSeconds(offset);
    }

    /** 이 사용자에게 오늘은 며칠인가. */
    public LocalDate today(Long userId) {
        return now(userId).toLocalDate();
    }

    /** 실제 시각 — 가상 오프셋을 타지 않는다. 감사·계측처럼 진짜 시각이 필요한 곳에만 쓴다. */
    public LocalDateTime realNow() {
        return LocalDateTime.now(clock);
    }

    /** 데모: 가상 시계를 앞으로 민다. 되감기는 지원하지 않는다(원장이 이미 확정된 시각을 갖는다). */
    @Transactional
    public LocalDateTime advance(Long userId, int days) {
        DemoClock state = demoClockRepository.findByUserId(userId)
                .orElseGet(() -> new DemoClock(userId, true, realNow()));
        state.advanceDays(days, realNow());
        demoClockRepository.save(state);
        return now(userId);
    }

    public boolean isDemoMode(Long userId) {
        return demoClockRepository.findByUserId(userId).map(DemoClock::isDemoMode).orElse(false);
    }
}
