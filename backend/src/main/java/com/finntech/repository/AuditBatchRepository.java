package com.finntech.repository;

import com.finntech.domain.AuditBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AuditBatchRepository extends JpaRepository<AuditBatch, Long> {
    List<AuditBatch> findAllByOrderByIdAsc();
    Optional<AuditBatch> findTopByOrderByIdDesc();
}
