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

    /**
     * 탈퇴·삭제요청 파기 — <b>표는 남기고 사람만 뗀다</b>(V38).
     *
     * <p>지우지 않는 이유는 표가 <b>사전을 정하는 우리 자산</b>이기 때문이다. 한 표가 사라지면
     * 다음 투표 때 다수가 뒤집혀 <b>남의 사전이 남의 탈퇴로 나빠진다.</b> 반대로 그대로 두면
     * "그 사람이 그 가맹점을 안다"가 남는다 — {@code user_merchant_stance} 를 파기하는 것과
     * 같은 이유로 그것은 개인정보다. 연결만 끊으면 둘 다 지킨다.
     */
    /*
       `clearAutomatically` 를 쓰지 않는다 — 켰더니 <b>아직 flush 안 된 삭제가 통째로 버려졌다.</b>
       파기는 이 호출 앞에서 Alert·Report 등을 엔티티로 지우는데, 그것들은 커밋 때 flush 된다.
       그 전에 영속성 컨텍스트를 비우면 지우기로 한 일이 사라지고, 파기는 <b>조용히 절반만</b>
       된다. `PrivacyFlowTest` 가 그것을 잡았다(2026-08-20).

       대신 `flushAutomatically` 로 **앞의 일을 먼저 내보낸다.** 이 벌크 갱신은 컨텍스트를
       거치지 않으므로, 앞의 변경이 아직 안 나갔으면 순서가 뒤집힌다.
    */
    @org.springframework.data.jpa.repository.Modifying(flushAutomatically = true)
    @Query("update MerchantCategoryVote v set v.userId = null where v.userId = :userId")
    int detachUser(@Param("userId") Long userId);
}
