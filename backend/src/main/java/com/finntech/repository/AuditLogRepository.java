package com.finntech.repository;

import com.finntech.domain.AuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
    List<AuditLog> findAllByOrderBySeqAsc();
    Optional<AuditLog> findTopByOrderBySeqDesc();
    List<AuditLog> findBySeqBetweenOrderBySeqAsc(Long from, Long to);
}
