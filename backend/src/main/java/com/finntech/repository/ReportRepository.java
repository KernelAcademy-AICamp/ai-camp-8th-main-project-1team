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
    void deleteByUserId(Long userId);
}
