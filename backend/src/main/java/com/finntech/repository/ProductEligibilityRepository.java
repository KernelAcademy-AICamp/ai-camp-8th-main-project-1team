package com.finntech.repository;

import com.finntech.domain.ProductEligibility;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductEligibilityRepository extends JpaRepository<ProductEligibility, Long> {

    Optional<ProductEligibility> findByPrdtKey(String prdtKey);

    /** 한 화면에 필요한 상품 라벨을 한 번에 읽어 N+1 조회를 피한다. */
    List<ProductEligibility> findByPrdtKeyIn(List<String> prdtKeys);
}
