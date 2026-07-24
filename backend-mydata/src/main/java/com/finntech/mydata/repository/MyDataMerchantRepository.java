package com.finntech.mydata.repository;

import com.finntech.mydata.domain.MyDataMerchant;
import org.springframework.data.jpa.repository.JpaRepository;

/** 고유 가맹점(사업자번호 키) 조회 — 사용자의 '번호→주소' 조회에 쓴다. */
public interface MyDataMerchantRepository extends JpaRepository<MyDataMerchant, String> {
}
