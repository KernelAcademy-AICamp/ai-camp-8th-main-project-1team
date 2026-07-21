package com.finntech.repository;

import com.finntech.domain.Category;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CategoryRepository extends JpaRepository<Category, Long> {
    Optional<Category> findByCode(String code);
    List<Category> findAllByOrderByCodeAsc();
}
