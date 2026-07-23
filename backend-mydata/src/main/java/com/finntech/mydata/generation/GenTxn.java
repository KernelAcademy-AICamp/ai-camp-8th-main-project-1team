package com.finntech.mydata.generation;

import java.time.LocalDateTime;

/**
 * 생성된 결제 1건(MyDataPayment 적재 전 형태). 하루활동 시뮬레이터의 산출.
 *
 * @param cardSlot            사용자 카드 인덱스(0..cardCount-1)
 * @param wasteLabel          WASTE|ESSENTIAL (생성 ground truth)
 * @param discretionaryScore  라벨러 잠재 p_waste(디버깅용, ML 특징 아님)
 * @param lat,lon,address     오프라인 위치(온라인은 null)
 */
public record GenTxn(
        int cardSlot, LocalDateTime date, String category1, String category2,
        int amount, String merchant, String channel, String productName, int productPrice,
        int quantity, String wasteLabel, double discretionaryScore,
        String address, Double lat, Double lon) {
}
