package com.finntech.ledger;

import com.finntech.engine.IndustryCategoryMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.OptionalDouble;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>모르는 칸에는 판정이 남아 있으면 안 된다.</b>
 *
 * <h2>왜 시험으로 못박나 — 92,850원이 그렇게 남았다</h2>
 *
 * <p>{@code WasteScoringService} 는 모르는 칸을 <b>건너뛴다</b>({@code isUnknown} 이면
 * {@code continue}). 그런데 건너뛰는 것은 <i>"판정을 안 한다"</i> 이지 <i>"옛 판정을 지운다"</i>
 * 가 아니다. 지우는 일은 기록하는 쪽이 한다 — 판정이 없으면 {@link SpendingLedgerRowMapper#wasteOf}
 * 가 {@code unjudged} 를 돌려주고, 그것이 옛 값을 덮는다.
 *
 * <p><b>그 두 단계가 갈리면 값이 남는다.</b> V46 이 결제 179건을 `간편결제` 로 옮겼는데
 * `facts_updated_at` 을 안 올려, 판정 갱신이 그 사용자를 <b>낡았다고 보지 않았다</b>.
 * 모델은 이미 판정하지 않는데 옛 낭비 5건 92,850원이 그대로 살아 있었다(2026-08-26 운영 실측).
 */
class UnknownCategoryHasNoJudgmentTest {

    @Test
    @DisplayName("판정이 없으면 옛 값을 지운다 — 남기지 않는다")
    void 판정이_없으면_지운다() {
        var facts = SpendingLedgerRowMapper.wasteOf(
                null, null, OptionalDouble.empty(), false, 0.479, "fp-1");

        assertThat(facts.waste()).as("판정이 없는데 낭비로 남으면 리포트가 거짓을 센다").isNull();
        assertThat(facts.probability()).isNull();
        assertThat(facts.labelSource())
                .as("모델이 낸 값이 아니라 '판정 안 함'으로 적혀야 한다")
                .isEqualTo(com.finntech.domain.SpendingLedger.WASTE_UNJUDGED);
        assertThat(facts.factors()).isEmpty();
    }

    /**
     * <b>모르는 칸은 판정 대상이 아니다</b> — 셋 다 같은 이유로 빠진다.
     * 이름을 여기 박지 않는다: 판정은 {@code isUnknown} 한 곳이 한다(§4 원칙 4).
     */
    @Test
    @DisplayName("모르는 칸 셋은 모두 판정에서 빠진다")
    void 모르는_칸은_판정_대상이_아니다() {
        assertThat(IndustryCategoryMapper.isUnknown(IndustryCategoryMapper.SIMPLE_PAY)).isTrue();
        assertThat(IndustryCategoryMapper.isUnknown(IndustryCategoryMapper.OTHER)).isTrue();
        assertThat(IndustryCategoryMapper.isUnknown(IndustryCategoryMapper.UNCLASSIFIED)).isTrue();
    }
}
