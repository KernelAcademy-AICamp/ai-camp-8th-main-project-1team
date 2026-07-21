package com.finntech.mydata.repository;

import com.finntech.mydata.domain.MyDataUser;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MyDataUserRepository extends JpaRepository<MyDataUser, String> {
    // existsById(ci) 로 /bank/mydata/ci/{ci} 존재확인 처리.
}
