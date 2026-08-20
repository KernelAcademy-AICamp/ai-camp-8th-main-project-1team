package com.finntech.repository;

import com.finntech.domain.NarrativeCache;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface NarrativeCacheRepository extends JpaRepository<NarrativeCache, Long> {

    /** 절약 후보는 카테고리마다 문장이 다르다 — {@code subject} 까지 봐야 그 화면의 것을 찾는다. */
    Optional<NarrativeCache> findByUserIdAndKindAndSubject(
            Long userId, NarrativeCache.Kind kind, String subject);

    /** 그 사용자의 문장 전부 — 상호작용 스캔이 한 번에 읽는다(종류마다 묻지 않는다). */
    List<NarrativeCache> findByUserId(Long userId);

    /**
     * 탈퇴·삭제요청 파기 (방침 6번).
     *
     * <p><b>지킴이 표가 파기에서 통째로 빠져 있었다</b>(2026-08-20 발견). 소비내역을 지워도
     * {@code guardian_transaction} 에 가맹점명과 금액이, {@code guardian_notification} 에
     * 그 소비를 두고 한 말이 그대로 남았다 — "삭제했다"고 해놓고 개인정보가 남는 것이
     * {@code PrivacyService} 가 처음부터 경계하던 실패 모양이다.
     */
    void deleteByUserId(Long userId);
}
