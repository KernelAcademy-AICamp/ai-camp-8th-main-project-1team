package com.finntech.repository;

import com.finntech.domain.MerchantCategoryVote;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MerchantCategoryVoteRepository extends JpaRepository<MerchantCategoryVote, Long> {

    /** 그 가맹점에 대한 <b>그 사람</b>의 표 — 다시 확정하면 이 행을 바꾼다. */
    Optional<MerchantCategoryVote> findByBusinessNumberAndMerchantNameAndUserId(
            String businessNumber, String merchantName, Long userId);

    /**
     * 그 가맹점의 <b>모든 표</b> — 집계는 언제나 가맹점 하나를 통째로 읽는다.
     *
     * <p>정렬을 고정한다(§4-3 재현성). 동률 판정이 순서에 안 기대게 만들어 두었지만,
     * 그렇다고 순서가 흔들려도 되는 것은 아니다 — 로그와 시험이 같은 것을 봐야 한다.
     */
    @Query("select v from MerchantCategoryVote v "
            + "where v.businessNumber = :biz and v.merchantName = :name order by v.id")
    List<MerchantCategoryVote> findBallots(@Param("biz") String businessNumber,
                                           @Param("name") String merchantName);
}
