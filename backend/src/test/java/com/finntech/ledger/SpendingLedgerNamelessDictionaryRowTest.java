package com.finntech.ledger;

import com.finntech.domain.MerchantCategory;
import com.finntech.domain.UserPayment;
import com.finntech.engine.IndustryCategoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>이름 없는 사전 행은 번호로 읽는다.</b>
 *
 * <h2>왜 시험으로 못박나 — 한 번 조용히 새 나갔다</h2>
 *
 * <p>씨앗의 원천({@code realdatas.csv})은 <b>사업자번호와 업종만</b> 주고 가맹점 풀네임을 안
 * 준다. 그래서 그 행들은 이름 자리가 빈 채로 사전에 앉고, <i>"이 사업자번호의 업종은 X"</i>
 * 라는 <b>번호 단위 사실</b>이 된다.
 *
 * <p>적재 쪽({@code MerchantCategoryService.Snapshot})은 번호로도 찾지만 <b>원장을 꾸미는
 * 쪽은 이름으로만 찾았다.</b> 그래서 사전이 아는 등록 업종이 원장에 영영 안 닿았고, 소분류는
 * 업종 이름에서 오므로 <b>그 칸이 통째로 비었다</b> — 실사용자 원장 557건이 그 사실을 못
 * 받았고 그중 171건은 소분류가 없었다(2026-08-25 운영 실측). <b>오류는 하나도 안 났다.</b>
 *
 * <p>{@code merchant_name = ''} 로 좁히는 것도 함께 지킨다. 번호로 전부 긁으면 택시처럼 한
 * 번호에 상호가 38,690종 붙은 곳이 딸려 온다.
 */
class SpendingLedgerNamelessDictionaryRowTest {

    private static final IndustryCategoryMapper INDUSTRIES =
            new IndustryCategoryMapper(new tools.jackson.databind.ObjectMapper());

    /** 국세청은 {@code 0} 으로 시작하는 번호를 발급하지 않는다 — 실재하는 사업자와 안 겹친다. */
    private static final String BIZ = "0000000077";

    @Test
    @DisplayName("가맹점명이 안 맞아도 번호로 등록 업종이 붙는다")
    void 번호로_등록_업종이_붙는다() {
        SpendingLedgerFactsWriter.Lookup lookup =
                new SpendingLedgerFactsWriter.Lookup(Map.of(), Map.of(), Map.of(BIZ, nameless()),
                        Map.of(), Map.of(), Map.of());

        var facts = lookup.merchantFactsOf(payment(BIZ, "두성전주콩나물국밥강남역점"));

        assertThat(facts.registryIndustryName())
                .as("이름 없는 씨앗 행은 이름으로 못 찾는다 — 번호가 유일한 길이다")
                .isEqualTo("한식 일반 음식점업");
        assertThat(INDUSTRIES.subOfIndustryName(facts.registryIndustryName()))
                .as("업종 이름이 닿아야 소분류가 생긴다").isEqualTo("한식");
    }

    /** 이름으로 찾은 행이 있으면 그것이 먼저다 — 번호 단위 사실은 마지막 수단이다. */
    @Test
    @DisplayName("이름으로 찾은 행이 번호보다 먼저다")
    void 이름이_번호보다_먼저다() {
        MerchantCategory byName = new MerchantCategory(
                BIZ, "두성전주콩나물국밥강남역점", "식비", MerchantCategory.Source.REGISTRY, null, null);
        byName.noteLookup("한식 면 요리 전문점", LocalDateTime.now());

        SpendingLedgerFactsWriter.Lookup lookup = new SpendingLedgerFactsWriter.Lookup(
                Map.of(BIZ + (char) 1 + "두성전주콩나물국밥강남역점", byName),
                Map.of(), Map.of(BIZ, nameless()), Map.of(), Map.of(), Map.of());

        assertThat(lookup.merchantFactsOf(payment(BIZ, "두성전주콩나물국밥강남역점"))
                .registryIndustryName())
                .as("그 가맹점 자신의 답이 번호 단위 사실을 이긴다")
                .isEqualTo("한식 면 요리 전문점");
    }

    /** 번호가 없는 결제(해외)는 번호 지도를 안 탄다 — 빈 문자열이 키가 되면 전부 한 곳으로 간다. */
    @Test
    @DisplayName("번호가 없으면 번호 지도를 안 탄다")
    void 번호가_없으면_안_탄다() {
        SpendingLedgerFactsWriter.Lookup lookup =
                new SpendingLedgerFactsWriter.Lookup(Map.of(), Map.of(), Map.of("", nameless()),
                        Map.of(), Map.of(), Map.of());

        assertThat(lookup.merchantFactsOf(payment("", "ANTHROPIC* CLAUDE SUB")).registryIndustryName())
                .as("번호 없는 결제가 빈 키로 남의 업종을 물려받으면 안 된다").isNull();
    }

    private static MerchantCategory nameless() {
        MerchantCategory row = new MerchantCategory(
                BIZ, "", "식비", MerchantCategory.Source.USER_CSV, null, null);
        row.noteLookup("한식 일반 음식점업", LocalDateTime.now());
        return row;
    }

    private static UserPayment payment(String biz, String name) {
        return new UserPayment("77:real-9c2b1d04-20260825-1", 77L, "S1", 9001L,
                LocalDateTime.now(), null, IndustryCategoryMapper.UNCLASSIFIED, 5000, name, biz);
    }
}
