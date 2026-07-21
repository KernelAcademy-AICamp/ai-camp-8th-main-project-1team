package com.finntech.repository;

import com.finntech.domain.FinancialProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface FinancialProductRepository extends JpaRepository<FinancialProduct, Long> {
    List<FinancialProduct> findAllByOrderByIdAsc();
}
