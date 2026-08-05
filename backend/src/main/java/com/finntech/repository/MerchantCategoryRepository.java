package com.finntech.repository;

import com.finntech.domain.MerchantCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MerchantCategoryRepository extends JpaRepository<MerchantCategory, Long> {

    /** ① 정확 일치 — (사업자번호, 풀네임). 사전의 정상 경로다. */
    Optional<MerchantCategory> findByBusinessNumberAndMerchantName(String businessNumber,
                                                                  String merchantName);

    /**
     * ② 같은 사업자번호의 다른 행 — <b>PG 가 아닐 때만</b> 쓰는 완화다.
     *
     * <p>한 사업자번호에 상호가 38,690종 붙은 것이 있다(택시 — 표시명 뒤에 차량번호가 붙는다).
     * 정확 일치만 쓰면 이런 가맹점은 사전이 영영 재사용되지 않고 행만 쌓인다. PG 가 아닌 번호는
     * 한 사업자의 것이라 업종이 하나이므로, 풀네임이 달라도 같은 분류를 써도 된다.
     *
     * <p>정렬을 고정한다 — 같은 데이터면 같은 답이 나와야 한다(마스터 §4-3 재현성).
     */
    @Query("""
            SELECT m FROM MerchantCategory m
            WHERE m.businessNumber = :businessNumber AND m.businessNumber <> ''
            ORDER BY m.id ASC
            """)
    List<MerchantCategory> findByBusinessNumberOrdered(@Param("businessNumber") String businessNumber);

    /** 번호가 없는 해외 가맹점 — 풀네임만으로 찾는다. */
    @Query("""
            SELECT m FROM MerchantCategory m
            WHERE m.businessNumber = '' AND m.merchantName = :merchantName
            ORDER BY m.id ASC
            """)
    List<MerchantCategory> findByNameOnly(@Param("merchantName") String merchantName);
}
