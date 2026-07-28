package com.finntech.mydata.repository;

import com.finntech.mydata.domain.MyDataAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MyDataAccountRepository extends JpaRepository<MyDataAccount, String> {
    Optional<MyDataAccount> findByUser_Id(String userId);

    /**
     * 실제로 계좌가 존재하는 은행 이름(중복 제거, 이름순).
     *
     * <p>은행에는 카드사와 달리 카탈로그 테이블이 없다. 목록을 새로 만들지 않고 데이터에 있는
     * 은행을 그대로 쓰되, <b>이름순 정렬</b>로 순서를 고정해 id(순번)가 조회마다 흔들리지 않게 한다
     * — 화면이 고른 id와 서버가 아는 id가 달라지면 엉뚱한 은행에 연동 요청이 나간다.
     */
    @Query("select distinct a.bank from MyDataAccount a order by a.bank asc")
    List<String> findDistinctBanks();

    /** 사용자의 계좌 중 지정한 은행들에 있는 것. 없으면 빈 목록(그 은행에 계좌가 없다는 뜻). */
    @Query("select a from MyDataAccount a where a.user.id = :userId and a.bank in :banks")
    List<MyDataAccount> findByUserAndBanks(@Param("userId") String userId, @Param("banks") List<String> banks);
}
