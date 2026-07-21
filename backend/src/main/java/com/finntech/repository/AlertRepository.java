package com.finntech.repository;

import com.finntech.domain.Alert;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface AlertRepository extends JpaRepository<Alert, Long> {
    List<Alert> findByUserIdOrderByOccurredAtDescIdDesc(Long userId);
    void deleteByUserId(Long userId);

    /**
     * 파기 대상 소비내역에 딸린 경고를 함께 지운다.
     * Alert는 amount·occurredAt·categoryCode를 <b>자기 테이블에 복사해 갖고 있어서</b>
     * Consumption만 지우면 개인정보가 그대로 남는다 (처리방침 3·4·6번 위반).
     */
    void deleteByConsumptionIdIn(Collection<Long> consumptionIds);
}
