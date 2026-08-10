package com.finntech.repository;

import com.finntech.domain.Report;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ReportRepository extends JpaRepository<Report, Long> {
    Optional<Report> findByUserIdAndPeriod(Long userId, String period);

    /**
     * 캐시된 리포트는 카테고리별·월별 지출을 담고 있다 — 파생값이지만 여전히 개인의 소비 프로필이다.
     * 삭제·파기 시 함께 지워야 한다.
     */
    /**
     * <p><b>{@code @Transactional} 이 필요하다.</b> 파생 삭제는 스프링 데이터가 트랜잭션을
     * 걸어 주지 않아, 트랜잭션 밖에서 부르면 {@code InvalidDataAccessApiUsageException} 이 난다.
     * 오래 이 메서드는 적재 트랜잭션 안에서만 불렸는데, 재분류 뒤 캐시를 깨는 자리가
     * <b>트랜잭션 밖</b>(후속 단계)에 생기면서 드러났다 — 분류가 실제로 고쳐지는 순간
     * 동기화가 통째로 터졌을 것이다(2026-08-08 PR 직전 감사).
     *
     * <p>부르는 자리마다 챙기지 않고 <b>여기</b> 붙인다. 호출부가 늘 때마다 빠뜨릴 수 있고,
     * 오늘 하루 종일 본 실수가 정확히 그것이다.
     */
    @org.springframework.transaction.annotation.Transactional
    void deleteByUserId(Long userId);
}
