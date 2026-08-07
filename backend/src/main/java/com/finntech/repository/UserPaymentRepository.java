package com.finntech.repository;

import com.finntech.domain.UserPayment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

public interface UserPaymentRepository extends JpaRepository<UserPayment, String> {
    List<UserPayment> findByUserIdOrderByPaymentDateDesc(Long userId);
    List<UserPayment> findByUserIdAndCardSerialOrderByPaymentDateDesc(Long userId, String cardSerial);

    /**
     * 아직 분류되지 않은 결제 — <b>DB 에서 걸러서</b> 가져온다.
     *
     * <p>예전에는 사용자의 결제를 전부 읽어 메모리에서 걸렀다. 데모 사용자는 결제가 4,000건이라
     * 화면에 100건을 보여 주려고 4,000건을 실어 왔다. 조건과 개수를 쿼리에 맡긴다.
     */
    List<UserPayment> findTop100ByUserIdAndCategory2OrderByPaymentDateDesc(Long userId, String category2);
    boolean existsById(String paymentId);

    /**
     * <b>아직 분류가 안 붙은 결제가 남았는가</b> — 등록 업종 조회를 시작할지 정하는 값싼 물음.
     *
     * <p>증분 동기화는 5분마다 도는데 대개 새 결제가 없다. 그렇다고 조회를 <i>새 결제가 있을
     * 때만</i> 돌리면 <b>이미 쌓인 미분류는 영영 안 물어본다</b> — 새 결제가 안 오는 한 그 결제들은
     * 계속 '카테고리없음'으로 남는다(2026-08-07 실측: 실사용자 미분류 53곳이 그 상태였다).
     *
     * <p>그래서 조건을 "새 결제"가 아니라 "할 일"로 바꾸고, 그 판단을 결제 전체를 읽지 않고
     * 개수 하나로 한다. 할 일이 없으면 여기서 끝나고 아무것도 읽지 않는다.
     */
    long countByUserIdAndCategory2(Long userId, String category2);

    /**
     * 그 분류의 결제 <b>전부</b> — 최신 100건 제한이 없다.
     *
     * <p>{@code findTop100...} 은 화면이 보여줄 만큼만 가져오려고 자른 것인데, 무료 통로가
     * <b>미분류 전부</b>를 훑게 되면서 그 제한이 곧 사각지대가 됐다 — 미분류가 150건인데
     * 100건만 보면 오래된 50건은 영원히 차례가 오지 않는다(2026-08-07 운영 실측: 카드사
     * 수수료가 그 바깥에 있었다).
     */
    List<UserPayment> findByUserIdAndCategory2OrderByPaymentDateDesc(Long userId, String category2);

    /** 벌크 삭제(즉시 DML) — 재연동 delete→insert 순서 역전 방지. */
    @Modifying
    @Transactional
    @Query("delete from UserPayment p where p.userId = :userId")
    void deleteByUserId(@Param("userId") Long userId);
}
