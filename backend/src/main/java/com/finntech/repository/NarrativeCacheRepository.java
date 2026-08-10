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
}
