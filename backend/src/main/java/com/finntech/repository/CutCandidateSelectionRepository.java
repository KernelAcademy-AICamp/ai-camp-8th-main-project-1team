package com.finntech.repository;

import com.finntech.domain.CutCandidateSelection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CutCandidateSelectionRepository extends JpaRepository<CutCandidateSelection, Long> {

    List<CutCandidateSelection> findByUserIdOrderBySelectedAtDesc(Long userId);

    List<CutCandidateSelection> findByUserIdAndStatus(Long userId, CutCandidateSelection.Status status);

    boolean existsByUserIdAndCategory2AndStatus(Long userId, String category2, CutCandidateSelection.Status status);

    void deleteByUserId(Long userId);
}
