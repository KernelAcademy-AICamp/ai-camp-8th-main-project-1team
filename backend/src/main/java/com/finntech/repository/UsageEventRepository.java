package com.finntech.repository;

import com.finntech.domain.UsageEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 행태 기록 조회·집계 (V35).
 *
 * <p>집계는 전부 <b>질의로</b> 한다. 이벤트를 애플리케이션으로 끌어와 세면 사용자가 늘수록
 * 메모리가 그만큼 늘고, 그러다 지금 자리에 있는 인메모리 계측({@code AnalyticsController})과
 * 같은 물건이 된다.
 */
public interface UsageEventRepository extends JpaRepository<UsageEvent, Long> {

    /** 이미 받은 묶음인가 — sendBeacon 이 같은 것을 두 번 보낼 수 있다. */
    boolean existsBySessionIdAndSeq(String sessionId, int seq);

    /** 그 사용자·그 세션의 최대 순번. 클라이언트가 순번을 잃었을 때 이어 붙일 근거. */
    @Query("select coalesce(max(e.seq), -1) from UsageEvent e where e.sessionId = :sessionId")
    int maxSeqOf(@Param("sessionId") String sessionId);

    // ── admin 통계 ────────────────────────────────────────────────────────────

    /** 날짜별 활성 사용자·세션·참여시간. 정렬 고정(마스터 §4 원칙 3). */
    @Query("""
            select cast(e.occurredAt as LocalDate), count(distinct e.userId),
                   count(distinct e.sessionId), coalesce(sum(e.engagedMs), 0)
            from UsageEvent e
            where e.occurredAt >= :since
            group by cast(e.occurredAt as LocalDate)
            order by cast(e.occurredAt as LocalDate)
            """)
    List<Object[]> dailyTotals(@Param("since") LocalDateTime since);

    /** 화면별 진입 수·참여시간 — "어디에 오래 머무는가". */
    @Query("""
            select e.screen,
                   sum(case when e.kind = 'SCREEN_VIEW' then 1 else 0 end),
                   coalesce(sum(e.engagedMs), 0),
                   count(distinct e.userId)
            from UsageEvent e
            where e.occurredAt >= :since
            group by e.screen
            order by coalesce(sum(e.engagedMs), 0) desc
            """)
    List<Object[]> screenTotals(@Param("since") LocalDateTime since);

    /** 눌린 것 순위 — "무엇을 쓰는가". */
    @Query("""
            select e.screen, e.element, count(e), count(distinct e.userId)
            from UsageEvent e
            where e.kind = 'CLICK' and e.occurredAt >= :since and e.element is not null
            group by e.screen, e.element
            order by count(e) desc
            """)
    List<Object[]> clickTotals(@Param("since") LocalDateTime since);

    /**
     * 세션이 끝난 화면 — <b>이탈 지점</b>.
     *
     * <p>세션마다 마지막 이벤트의 화면을 센다. 사람들이 어디서 앱을 놓는지가 여기 있다.
     */
    @Query("""
            select e.screen, count(e)
            from UsageEvent e
            where e.occurredAt >= :since
              and e.id in (select max(x.id) from UsageEvent x
                           where x.occurredAt >= :since group by x.sessionId)
            group by e.screen
            order by count(e) desc
            """)
    List<Object[]> exitScreens(@Param("since") LocalDateTime since);

    /** 사용자별 요약 — 몇 번 왔고 얼마나 머물렀나. */
    @Query("""
            select e.userId, count(distinct e.sessionId), coalesce(sum(e.engagedMs), 0),
                   count(e), min(e.occurredAt), max(e.occurredAt)
            from UsageEvent e
            where e.occurredAt >= :since
            group by e.userId
            order by e.userId
            """)
    List<Object[]> perUserTotals(@Param("since") LocalDateTime since);

    /** 한 사람의 최근 발자취 — 통계가 이상할 때 원본을 본다. */
    List<UsageEvent> findTop200ByUserIdOrderByOccurredAtDesc(Long userId);

    /**
     * 세션 한 줄씩 — 참여 세션·이탈률·세션 길이가 전부 여기서 나온다.
     *
     * <p>{@code (세션, 사용자, 시작, 끝, 참여ms, 화면조회수, 이벤트수)}. 이것만은 애플리케이션에서
     * 센다 — 참여 세션의 판정이 <b>"10초 이상 <u>또는</u> 화면 2개 이상"</b> 이라 한 질의로
     * 표현하면 방언을 타고, 세션 수는 이벤트 수보다 두 자리 작아 끌어와도 안전하다.
     */
    @Query("""
            select e.sessionId, min(e.userId), min(e.occurredAt), max(e.occurredAt),
                   coalesce(sum(e.engagedMs), 0),
                   sum(case when e.kind = 'SCREEN_VIEW' then 1 else 0 end),
                   count(e)
            from UsageEvent e
            where e.occurredAt >= :since
            group by e.sessionId
            order by min(e.occurredAt)
            """)
    List<Object[]> sessionRollup(@Param("since") LocalDateTime since);

    /** 세션의 <b>첫</b> 화면 — GA4 의 랜딩 페이지. 사람들이 어디로 들어오는가. */
    @Query("""
            select e.screen, count(e)
            from UsageEvent e
            where e.occurredAt >= :since
              and e.id in (select min(x.id) from UsageEvent x
                           where x.occurredAt >= :since and x.kind = 'SCREEN_VIEW'
                           group by x.sessionId)
            group by e.screen
            order by count(e) desc
            """)
    List<Object[]> landingScreens(@Param("since") LocalDateTime since);

    /** 이벤트 종류별 — GA4 의 이벤트 보고서. */
    @Query("""
            select e.kind, count(e), count(distinct e.userId)
            from UsageEvent e
            where e.occurredAt >= :since
            group by e.kind
            order by count(e) desc
            """)
    List<Object[]> kindTotals(@Param("since") LocalDateTime since);

    /** 화면 크기 — 작아서 안 눌리는 버튼을 찾는 데 쓴다. */
    @Query("""
            select coalesce(e.viewport, '(미상)'), count(distinct e.userId), count(e)
            from UsageEvent e
            where e.occurredAt >= :since and e.viewport is not null
            group by e.viewport
            order by count(e) desc
            """)
    List<Object[]> viewportTotals(@Param("since") LocalDateTime since);

    /** 요일별 — 1=일요일(MySQL·H2 의 DAYOFWEEK 규약). */
    @Query("""
            select extract(day of week from e.occurredAt), count(e), count(distinct e.userId)
            from UsageEvent e
            where e.occurredAt >= :since
            group by extract(day of week from e.occurredAt)
            order by extract(day of week from e.occurredAt)
            """)
    List<Object[]> weekdayTotals(@Param("since") LocalDateTime since);

    /**
     * 사람마다 <b>맨 처음</b> 본 날 — 신규/재방문과 리텐션의 기준점.
     *
     * <p>기간을 안 자른다. 조회 창 안에서만 보면 <b>오래된 사용자가 매번 신규로 보인다.</b>
     */
    @Query("select e.userId, min(cast(e.occurredAt as LocalDate)) from UsageEvent e group by e.userId")
    List<Object[]> firstSeenPerUser();

    /** 사람이 활동한 날들 — 리텐션 코호트가 "N일 뒤에 다시 왔나"를 여기서 본다. */
    @Query("""
            select distinct e.userId, cast(e.occurredAt as LocalDate)
            from UsageEvent e
            order by e.userId
            """)
    List<Object[]> activeDaysPerUser();

    /**
     * 세션 안의 화면 이동 — GA4 의 경로 탐색에 해당한다.
     *
     * <p>같은 세션에서 순번이 바로 뒤인 화면 조회끼리 잇는다. "홈에서 어디로 가는가"에 답한다.
     */
    @Query("""
            select a.screen, b.screen, count(b.id)
            from UsageEvent a, UsageEvent b
            where a.sessionId = b.sessionId and b.seq > a.seq
              and a.kind = 'SCREEN_VIEW' and b.kind = 'SCREEN_VIEW'
              and a.occurredAt >= :since
              and not exists (select 1 from UsageEvent m
                              where m.sessionId = a.sessionId and m.kind = 'SCREEN_VIEW'
                                and m.seq > a.seq and m.seq < b.seq)
            group by a.screen, b.screen
            order by count(b.id) desc
            """)
    List<Object[]> screenFlow(@Param("since") LocalDateTime since);

    /** 최근 30분 — GA4 의 실시간. */
    @Query("""
            select count(distinct e.userId), count(distinct e.sessionId), count(e)
            from UsageEvent e where e.occurredAt >= :since
            """)
    List<Object[]> realtimeTotals(@Param("since") LocalDateTime since);

    /** 최근 30분의 화면별 — 지금 누가 어디 있나. */
    @Query("""
            select e.screen, count(distinct e.userId), count(e)
            from UsageEvent e where e.occurredAt >= :since
            group by e.screen order by count(e) desc
            """)
    List<Object[]> realtimeScreens(@Param("since") LocalDateTime since);

    /**
     * 전환(핵심 이벤트) 하나의 실적 — {@code (건수, 사용자, 세션)}.
     *
     * <p>무엇이 전환인지는 {@code UsageProperties} 가 설정에서 읽는다. 개수가 적어(열 개 미만)
     * 하나씩 세는 편이, 화면·요소를 통째로 묶어 세고 애플리케이션에서 걸러 내는 것보다 싸다 —
     * 요소 식별자는 DOM 경로라 종류가 아주 많다.
     */
    @Query("""
            select count(e), count(distinct e.userId), count(distinct e.sessionId)
            from UsageEvent e
            where e.occurredAt >= :since and e.kind = :kind and e.screen = :screen
            """)
    List<Object[]> keyEventTotals(@Param("since") LocalDateTime since,
                                  @Param("kind") String kind,
                                  @Param("screen") String screen);

    /** 요소까지 지정된 전환. */
    @Query("""
            select count(e), count(distinct e.userId), count(distinct e.sessionId)
            from UsageEvent e
            where e.occurredAt >= :since and e.kind = :kind and e.screen = :screen
              and e.element = :element
            """)
    List<Object[]> keyEventTotals(@Param("since") LocalDateTime since,
                                  @Param("kind") String kind,
                                  @Param("screen") String screen,
                                  @Param("element") String element);

    /** 시간대별 분포 — 언제 쓰는가. */
    @Query("""
            select extract(hour from e.occurredAt), count(e), count(distinct e.userId)
            from UsageEvent e
            where e.occurredAt >= :since
            group by extract(hour from e.occurredAt)
            order by extract(hour from e.occurredAt)
            """)
    List<Object[]> hourlyTotals(@Param("since") LocalDateTime since);

    long countByOccurredAtBefore(LocalDateTime cutoff);

    /**
     * 보관기간 지난 것의 식별자 — <b>청크로 끊어 지우려고</b> 먼저 고른다.
     *
     * <p>{@code DELETE … LIMIT} 은 MySQL 방언이라 시험(H2)에서 갈릴 수 있다. 식별자를 먼저
     * 고르고 {@code deleteAllByIdInBatch} 로 지우면 양쪽에서 같은 뜻이다.
     */
    @Query("select e.id from UsageEvent e where e.occurredAt < :cutoff order by e.id")
    List<Long> findIdsOlderThan(@Param("cutoff") LocalDateTime cutoff,
                                org.springframework.data.domain.Pageable page);

    /** 파기 — 방침 33조가 정한 "탈퇴·철회까지" 를 지키는 자리. */
    @Modifying
    @Transactional
    @Query("delete from UsageEvent e where e.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
