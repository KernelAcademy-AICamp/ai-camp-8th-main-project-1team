package com.finntech.mydata.repository;

import com.finntech.mydata.domain.MyDataAccount;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface MyDataAccountRepository extends JpaRepository<MyDataAccount, String> {
    Optional<MyDataAccount> findByUser_Id(String userId);
}
