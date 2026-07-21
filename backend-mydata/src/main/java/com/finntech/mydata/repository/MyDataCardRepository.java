package com.finntech.mydata.repository;

import com.finntech.mydata.domain.MyDataCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface MyDataCardRepository extends JpaRepository<MyDataCard, String> {

    /** 특정 사용자(CI)의 특정 카드사 카드들. 조회 순서 고정(재현성). */
    @Query("select c from MyDataCard c "
            + "where c.user.id = :userId and c.cardProduct.cardCompany.id = :companyId "
            + "order by c.id asc")
    List<MyDataCard> findByUserAndCompany(@Param("userId") String userId,
                                          @Param("companyId") Long companyId);

    @Query("select c from MyDataCard c where c.user.id = :userId order by c.id asc")
    List<MyDataCard> findByUser(@Param("userId") String userId);
}
