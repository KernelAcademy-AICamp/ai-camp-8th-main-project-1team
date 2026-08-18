package com.finntech.repository;

import com.finntech.domain.UsageSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 세션 축 집계 — GA4 의 <b>획득 · Tech · 인구통계</b> 보고서가 전부 여기서 나온다.
 *
 * <p>이 축들은 세션 내내 안 변해서 세션 표에 한 번만 적힌다. 그래서 집계도 이벤트 표가 아니라
 * 여기를 센다 — 이벤트 표를 세면 이벤트가 많은 세션이 더 무겁게 세어진다(같은 브라우저가
 * 클릭 수만큼 되풀이해 잡힌다).
 *
 * <p>인구통계는 {@code app_user} 를 조인한다. 출생연도·성별은 <b>사람</b>에게 붙은 값이라
 * 세션마다 복사하지 않는다.
 */
public interface UsageSessionRepository extends JpaRepository<UsageSession, String> {

    /** 유입 경로 갈래 — GA4 의 기본 채널 그룹에 해당한다. */
    @Query("""
            select s.channel, count(s), count(distinct s.userId)
            from UsageSession s
            where s.startedAt >= :since
            group by s.channel
            order by count(s) desc
            """)
    List<Object[]> channelTotals(@Param("since") LocalDateTime since);

    /** 출처/매체 — GA4 의 {@code session_source / medium}. */
    @Query("""
            select coalesce(s.source, '(direct)'), coalesce(s.medium, '(none)'),
                   count(s), count(distinct s.userId)
            from UsageSession s
            where s.startedAt >= :since
            group by s.source, s.medium
            order by count(s) desc
            """)
    List<Object[]> sourceMediumTotals(@Param("since") LocalDateTime since);

    /** 캠페인 — utm_campaign 이 붙은 것만. 없으면 빈 목록이고 그것이 정상이다. */
    @Query("""
            select s.campaign, count(s), count(distinct s.userId)
            from UsageSession s
            where s.startedAt >= :since and s.campaign is not null
            group by s.campaign
            order by count(s) desc
            """)
    List<Object[]> campaignTotals(@Param("since") LocalDateTime since);

    /** 참조 사이트 — 호스트만 적혀 있다. */
    @Query("""
            select s.referrerHost, count(s), count(distinct s.userId)
            from UsageSession s
            where s.startedAt >= :since and s.referrerHost is not null
            group by s.referrerHost
            order by count(s) desc
            """)
    List<Object[]> referrerTotals(@Param("since") LocalDateTime since);

    // ── Tech ─────────────────────────────────────────────────────────────────

    @Query("""
            select coalesce(s.platform, '(미상)'), count(s), count(distinct s.userId)
            from UsageSession s where s.startedAt >= :since
            group by s.platform order by count(s) desc
            """)
    List<Object[]> platformTotals(@Param("since") LocalDateTime since);

    @Query("""
            select coalesce(s.deviceCategory, '(미상)'), count(s), count(distinct s.userId)
            from UsageSession s where s.startedAt >= :since
            group by s.deviceCategory order by count(s) desc
            """)
    List<Object[]> deviceTotals(@Param("since") LocalDateTime since);

    /** 브라우저 — 이름과 주버전을 함께 센다(GA4 의 "브라우저 버전"과 같은 굵기). */
    @Query("""
            select coalesce(s.browser, '(미상)'), coalesce(s.browserVersion, ''),
                   count(s), count(distinct s.userId)
            from UsageSession s where s.startedAt >= :since
            group by s.browser, s.browserVersion order by count(s) desc
            """)
    List<Object[]> browserTotals(@Param("since") LocalDateTime since);

    @Query("""
            select coalesce(s.os, '(미상)'), coalesce(s.osVersion, ''),
                   count(s), count(distinct s.userId)
            from UsageSession s where s.startedAt >= :since
            group by s.os, s.osVersion order by count(s) desc
            """)
    List<Object[]> osTotals(@Param("since") LocalDateTime since);

    /** 기기 화면 해상도 — 창 크기(viewport)와 다르다. */
    @Query("""
            select coalesce(s.screenSize, '(미상)'), count(s), count(distinct s.userId)
            from UsageSession s where s.startedAt >= :since
            group by s.screenSize order by count(s) desc
            """)
    List<Object[]> screenSizeTotals(@Param("since") LocalDateTime since);

    // ── 지역·언어 ────────────────────────────────────────────────────────────

    @Query("""
            select coalesce(s.country, '(미상)'), count(s), count(distinct s.userId)
            from UsageSession s where s.startedAt >= :since
            group by s.country order by count(s) desc
            """)
    List<Object[]> countryTotals(@Param("since") LocalDateTime since);

    @Query("""
            select coalesce(s.timeZone, '(미상)'), count(s), count(distinct s.userId)
            from UsageSession s where s.startedAt >= :since
            group by s.timeZone order by count(s) desc
            """)
    List<Object[]> timeZoneTotals(@Param("since") LocalDateTime since);

    @Query("""
            select coalesce(s.language, '(미상)'), count(s), count(distinct s.userId)
            from UsageSession s where s.startedAt >= :since
            group by s.language order by count(s) desc
            """)
    List<Object[]> languageTotals(@Param("since") LocalDateTime since);

    // ── 인구통계 — 사람에게 붙은 값이라 app_user 를 조인한다 ─────────────────

    /**
     * 성별 분포. {@code (성별, 사용자 수, 세션 수)}.
     *
     * <p>세션이 아니라 <b>사람</b>을 세는 것이 요점이라 사용자 수를 먼저 낸다 — 한 사람이
     * 많이 들어오면 세션만으로는 분포가 그 사람 쪽으로 기운다.
     */
    @Query("""
            select coalesce(u.gender, '(미상)'), count(distinct s.userId), count(s)
            from UsageSession s, AppUser u
            where u.id = s.userId and s.startedAt >= :since
            group by u.gender
            order by count(distinct s.userId) desc
            """)
    List<Object[]> genderTotals(@Param("since") LocalDateTime since);

    /**
     * 출생연도별 — 연령대로 묶는 일은 부른 쪽이 한다.
     *
     * <p>여기서 묶지 않는 이유: 나이는 <b>오늘 날짜를 봐야</b> 나오는 값이라 질의에 넣으면
     * 재현성이 깨진다(마스터 §4 원칙 3 — 엔진은 {@code now()} 를 직접 읽지 않는다).
     * 연도만 세어 주고, 기준 시각을 쥔 쪽이 나눈다.
     */
    @Query("""
            select u.birthYear, count(distinct s.userId), count(s)
            from UsageSession s, AppUser u
            where u.id = s.userId and s.startedAt >= :since
            group by u.birthYear
            order by u.birthYear
            """)
    List<Object[]> birthYearTotals(@Param("since") LocalDateTime since);

    // ── 파기 ─────────────────────────────────────────────────────────────────

    @Modifying
    @Transactional
    @Query("delete from UsageSession s where s.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);

    /** 보관기간 정리 — 이벤트가 다 지워진 세션은 남겨 둘 이유가 없다. */
    @Modifying
    @Transactional
    @Query("""
            delete from UsageSession s
            where s.startedAt < :cutoff
              and not exists (select 1 from UsageEvent e where e.sessionId = s.sessionId)
            """)
    int deleteOrphansBefore(@Param("cutoff") LocalDateTime cutoff);
}
