package com.finntech.repository;

import com.finntech.domain.MerchantBrand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 브랜드 대기 장소 — 사전에 못 들어간 가맹점의 브랜드. 키는 가맹점 풀네임이다. */
public interface MerchantBrandRepository extends JpaRepository<MerchantBrand, Long> {

    Optional<MerchantBrand> findByMerchantName(String merchantName);

    List<MerchantBrand> findByMerchantNameIn(List<String> merchantNames);

    /**
     * <b>브랜드 이름만</b> 뽑는다 — 2차 대조(표기 통일)의 후보다.
     *
     * <p>행이 아니라 이름이라 엔티티를 세우지 않고, 중복이 접혀 표보다 훨씬 작다. 표를 통째로
     * 읽던 것({@code findAll})을 대신한다 — 그건 대조에 안 쓸 칸까지 전부 메모리에 올렸다.
     */
    @org.springframework.data.jpa.repository.Query(
            "select distinct b.brand from MerchantBrand b where b.brand <> :exclude order by b.brand")
    List<String> findDistinctBrands(@org.springframework.data.repository.query.Param("exclude") String exclude);

    /** 사전에 들어간 가맹점의 대기 행을 치운다 — 브랜드는 사전으로 옮긴 뒤다. */
    void deleteByMerchantName(String merchantName);
}
