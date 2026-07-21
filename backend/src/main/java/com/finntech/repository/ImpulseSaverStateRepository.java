package com.finntech.repository;

import com.finntech.domain.ImpulseSaverState;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ImpulseSaverStateRepository extends JpaRepository<ImpulseSaverState, Long> {
    Optional<ImpulseSaverState> findByUserId(Long userId);
    void deleteByUserId(Long userId);
}
