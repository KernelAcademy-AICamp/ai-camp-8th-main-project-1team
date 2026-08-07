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
     * <p><b>사람이 확인한 행이 먼저다.</b> id 순으로만 두면 <b>먼저 들어온 씨앗이 영원히 이긴다</b> —
     * 사용자가 "이건 교통비예요"를 눌러도 그 결제 하나만 바뀌고 같은 사업자의 나머지는 계속
     * 옛 분류로 나온다. 오류도 안 나므로 "고쳤는데 안 고쳐진다"만 남는다
     * (2026-08-05 운영: 티머니 396-87-03587 이 씨앗의 '쇼핑'에 막혀 16건이 그대로였다).
     *
     * <p>우선순위는 <b>사람의 확인 &gt; 국세청 등록 &gt; 추정</b>이다. 씨앗 적재 SQL 이
     * {@code USER_CONFIRMED} 를 덮지 않는 것과 같은 서열을 조회에서도 지킨다.
     *
     * <p>정렬을 고정한다 — 같은 데이터면 같은 답이 나와야 한다(마스터 §4-3 재현성).
     */
    @Query("""
            SELECT m FROM MerchantCategory m
            WHERE m.businessNumber = :businessNumber AND m.businessNumber <> ''
            ORDER BY CASE m.source
                       WHEN 'USER_CONFIRMED' THEN 0
                       WHEN 'USER_CSV'       THEN 1
                       ELSE 2
                     END ASC, m.id ASC
            """)
    List<MerchantCategory> findByBusinessNumberOrdered(@Param("businessNumber") String businessNumber);

    /** 번호가 없는 해외 가맹점 — 풀네임만으로 찾는다. 여기도 사람의 확인이 먼저다. */
    @Query("""
            SELECT m FROM MerchantCategory m
            WHERE m.businessNumber = '' AND m.merchantName = :merchantName
            ORDER BY CASE m.source
                       WHEN 'USER_CONFIRMED' THEN 0
                       WHEN 'USER_CSV'       THEN 1
                       ELSE 2
                     END ASC, m.id ASC
            """)
    List<MerchantCategory> findByNameOnly(@Param("merchantName") String merchantName);

    /**
     * <b>번호를 가리지 않고</b> 그 상호의 사전 행들 — 브랜드를 적을 자리를 찾을 때 쓴다.
     *
     * <p>{@link #findByNameOnly} 와 다르다. 그쪽은 번호가 빈 행(해외 가맹점)만 보는데,
     * 브랜드는 번호와 무관하게 <b>이름에 붙는 성질</b>이라 번호가 있든 없든 같은 상호면 같다.
     */
    List<MerchantCategory> findByMerchantName(String merchantName);
}
