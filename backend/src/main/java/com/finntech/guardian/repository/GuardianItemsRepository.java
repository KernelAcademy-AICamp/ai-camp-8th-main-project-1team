package com.finntech.guardian.repository;

import com.finntech.guardian.domain.GuardianItems;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface GuardianItemsRepository extends JpaRepository<GuardianItems, Long> {

    Optional<GuardianItems> findByUserId(Long userId);
}
