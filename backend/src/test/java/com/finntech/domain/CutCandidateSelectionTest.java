package com.finntech.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 절약 후보 선택추적(⑤) 도메인 로직 — 월말 재검증의 개선 판정. */
class CutCandidateSelectionTest {

    private CutCandidateSelection active() {
        return new CutCandidateSelection(1L, "카페", CutCandidateSelection.Type.REMOVABLE,
                15000, 15000, LocalDateTime.of(2026, 2, 1, 0, 0));
    }

    @Test
    @DisplayName("선택 직후엔 ACTIVE·미검증")
    void startsActive() {
        CutCandidateSelection s = active();
        assertEquals(CutCandidateSelection.Status.ACTIVE, s.getStatus());
        assertNull(s.getActualSpend());
        assertNull(s.getImproved());
    }

    @Test
    @DisplayName("기준선보다 지출이 줄면 improved=true, VERIFIED")
    void verifyImprovedWhenSpendDropped() {
        CutCandidateSelection s = active();
        s.verify(9000, LocalDateTime.of(2026, 3, 1, 0, 0));
        assertEquals(9000, s.getActualSpend());
        assertTrue(s.getImproved());
        assertEquals(CutCandidateSelection.Status.VERIFIED, s.getStatus());
        assertEquals(LocalDateTime.of(2026, 3, 1, 0, 0), s.getVerifiedAt());
    }

    @Test
    @DisplayName("기준선보다 지출이 늘거나 같으면 improved=false")
    void verifyNotImprovedWhenSpendRose() {
        CutCandidateSelection s = active();
        s.verify(20000, LocalDateTime.of(2026, 3, 1, 0, 0));
        assertFalse(s.getImproved());
    }
}
