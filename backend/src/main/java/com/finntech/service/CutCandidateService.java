package com.finntech.service;

import com.finntech.domain.CutCandidateSelection;
import com.finntech.domain.UserPayment;
import com.finntech.engine.CutCandidate;
import com.finntech.engine.CutCandidateSelector;
import com.finntech.repository.CutCandidateSelectionRepository;
import com.finntech.repository.UserPaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 절약 후보(⑤)의 조회·선택·월말 재검증을 묶는 서비스.
 *
 * <p>후보 산출은 순수 엔진({@link CutCandidateSelector})에 위임하고, 이 서비스는 사용자가 고른 후보를 추적
 * ({@link CutCandidateSelection})하고 월말 스냅샷 1회로 재검증한다(마스터 결정 3). {@code referenceTime}을 받아
 * 재현성(§3)을 유지한다.
 */
@Service
public class CutCandidateService {

    private final UserPaymentRepository payments;
    private final CutCandidateSelectionRepository selections;
    private final CutCandidateSelector selector;

    public CutCandidateService(UserPaymentRepository payments,
                               CutCandidateSelectionRepository selections,
                               CutCandidateSelector selector) {
        this.payments = payments;
        this.selections = selections;
        this.selector = selector;
    }

    /** 현재 절약 후보 목록(절감액 큰 순). */
    public List<CutCandidate> candidates(Long userId, LocalDateTime referenceTime, int windowDays) {
        return selector.select(userId, referenceTime, windowDays);
    }

    /** 후보 하나를 "줄이겠다"고 선택해 추적 시작. 이미 추적 중이거나 후보가 아니면 거부한다. */
    @Transactional
    public CutCandidateSelection choose(Long userId, String category2, LocalDateTime referenceTime, int windowDays) {
        if (selections.existsByUserIdAndCategory2AndStatus(userId, category2, CutCandidateSelection.Status.ACTIVE)) {
            throw new IllegalStateException("이미 추적 중인 절약 후보입니다: " + category2);
        }
        CutCandidate c = selector.select(userId, referenceTime, windowDays).stream()
                .filter(x -> x.category2().equals(category2))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("절약 후보가 아닙니다: " + category2));
        CutCandidateSelection.Type type = c.type() == CutCandidate.Type.REMOVABLE
                ? CutCandidateSelection.Type.REMOVABLE : CutCandidateSelection.Type.OPTIMIZABLE;
        return selections.save(new CutCandidateSelection(
                userId, category2, type, c.estimatedSaving(), c.monthlySpend(), referenceTime));
    }

    /** 월말 재검증(1회) — ACTIVE 선택들의 현재 창 지출을 기준선과 비교해 개선 여부를 확정한다(결정 3). */
    @Transactional
    public List<CutCandidateSelection> verifyActive(Long userId, LocalDateTime referenceTime, int windowDays) {
        LocalDateTime from = referenceTime.minusDays(windowDays);
        List<UserPayment> window = payments.findByUserIdOrderByPaymentDateDesc(userId).stream()
                .filter(p -> !p.getPaymentDate().isBefore(from) && !p.getPaymentDate().isAfter(referenceTime))
                .toList();
        List<CutCandidateSelection> active = selections.findByUserIdAndStatus(userId, CutCandidateSelection.Status.ACTIVE);
        for (CutCandidateSelection sel : active) {
            long actual = window.stream()
                    .filter(p -> sel.getCategory2().equals(p.getCategory2()))
                    .mapToLong(UserPayment::getAmount)
                    .sum();
            sel.verify(actual, referenceTime);
        }
        return selections.saveAll(active);
    }

    /** 선택 이력(최신순) — ACTIVE·VERIFIED 모두. */
    public List<CutCandidateSelection> history(Long userId) {
        return selections.findByUserIdOrderBySelectedAtDesc(userId);
    }
}
