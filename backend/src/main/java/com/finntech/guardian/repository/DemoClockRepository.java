package com.finntech.guardian.repository;

import com.finntech.guardian.domain.DemoClock;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DemoClockRepository extends JpaRepository<DemoClock, Long> {

    Optional<DemoClock> findByUserId(Long userId);
}
