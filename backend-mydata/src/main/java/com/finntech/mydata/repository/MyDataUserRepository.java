package com.finntech.mydata.repository;

import com.finntech.mydata.domain.MyDataUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MyDataUserRepository extends JpaRepository<MyDataUser, String> {
    // existsById(ci) 로 /bank/mydata/ci/{ci} 존재확인 처리.

    /**
     * 전화번호로 명의자를 찾는다 — 본인인증이 <b>어느 항목이 틀렸는지</b> 가려내는 데 쓴다.
     * 저장 형식이 `010-1234-5678`이라 하이픈 있는 값으로 찾는다.
     */
    Optional<MyDataUser> findByPhoneNumber(String phoneNumber);

    /**
     * 이름+주민번호 앞 7자리로 사람을 찾는다.
     * 주민번호는 `0309303******` 처럼 뒤가 가려진 채 저장돼 앞 7자리로만 비교한다.
     */
    @Query("select u from MyDataUser u where u.name = :name and substring(u.socialNumber, 1, 7) = :social7")
    Optional<MyDataUser> findByNameAndSocial7(@Param("name") String name, @Param("social7") String social7);
}
