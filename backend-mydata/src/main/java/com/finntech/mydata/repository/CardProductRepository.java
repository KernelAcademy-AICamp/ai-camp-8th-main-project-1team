package com.finntech.mydata.repository;

import com.finntech.mydata.domain.CardProduct;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CardProductRepository extends JpaRepository<CardProduct, Long> {
    List<CardProduct> findAllByOrderByCodeAsc();
}
