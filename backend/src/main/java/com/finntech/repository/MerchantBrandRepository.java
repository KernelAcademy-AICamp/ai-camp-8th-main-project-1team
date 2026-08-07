package com.finntech.repository;

import com.finntech.domain.MerchantBrand;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** 브랜드 대기 장소 — 사전에 못 들어간 가맹점의 브랜드. 키는 가맹점 풀네임이다. */
public interface MerchantBrandRepository extends JpaRepository<MerchantBrand, Long> {

    Optional<MerchantBrand> findByMerchantName(String merchantName);

    List<MerchantBrand> findByMerchantNameIn(List<String> merchantNames);

    /** 사전에 들어간 가맹점의 대기 행을 치운다 — 브랜드는 사전으로 옮긴 뒤다. */
    void deleteByMerchantName(String merchantName);
}
