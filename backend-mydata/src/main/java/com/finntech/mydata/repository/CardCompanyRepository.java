package com.finntech.mydata.repository;

import com.finntech.mydata.domain.CardCompany;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CardCompanyRepository extends JpaRepository<CardCompany, Long> {
    List<CardCompany> findAllByOrderByIdAsc();
}
