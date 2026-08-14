package com.finntech.web;

import com.finntech.service.KeptMoneyParkingService;
import com.finntech.service.SavingsCompareService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 통장 비교 (정보성) 조회 — 마스터 §5-5. 실 예·적금 금리를 자격 제한 상품 제외 후 금리순으로 제공한다.
 * <b>정보성일 뿐 판매·중개가 아니다</b>(가입 버튼·제휴 없음, 가입은 각 금융사에서).
 */
@RestController
@RequestMapping("/api/savings")
public class SavingsCompareController {

    private final SavingsCompareService service;
    private final KeptMoneyParkingService keptMoneyParkingService;

    public SavingsCompareController(SavingsCompareService service,
                                    KeptMoneyParkingService keptMoneyParkingService) {
        this.service = service;
        this.keptMoneyParkingService = keptMoneyParkingService;
    }

    /**
     * 사용자 데이터와 무관한 일반 비교 목록을 반환한다. 기본금리와 공시 최고금리만 보여 주며
     * 우대조건 충족 여부나 실수령 금리는 계산하지 않는다.
     */
    @GetMapping("/compare")
    public SavingsCompareService.CompareResult compare(@RequestParam(required = false) Integer limit) {
        return service.compare(limit);
    }

    /**
     * 결산 화면의 「지킨 돈 굴리기」(§4.7) — 지킨 돈을 파킹통장에 뒀을 때의 원금·이자.
     *
     * <p><b>보여줄 게 없으면 204</b>다(지킨 돈이 0이거나 파킹 조회가 막힘). 빈 껍데기를 내려
     * 화면이 `0원`을 그리게 두지 않는다 — 없는 성과를 축하하지 않는다(거울 원칙).
     *
     * <p>금액은 ②가 확정한 지킨 돈을 합산만 한 값이고, 우대조건 판정은 하지 않는다(개인화 아님).
     */
    @GetMapping("/kept-money")
    public ResponseEntity<KeptMoneyParkingService.KeptMoneyPlan> keptMoney(@RequestParam Long userId) {
        KeptMoneyParkingService.KeptMoneyPlan plan = keptMoneyParkingService.plan(userId);
        return plan == null ? ResponseEntity.noContent().build() : ResponseEntity.ok(plan);
    }
}
