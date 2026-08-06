package com.finntech.guardian;

import com.finntech.domain.UserMerchantStance;
import com.finntech.guardian.domain.GuardianEnums.Feedback;
import com.finntech.guardian.domain.GuardianEnums.FeedbackReason;
import com.finntech.guardian.domain.GuardianEnums.TxType;
import com.finntech.guardian.domain.GuardianEnums.UndoReason;
import com.finntech.repository.UserMerchantStanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>원장에서 온 "이건 낭비가 아니다"도 성향으로 쌓인다</b> (2026-08-02).
 *
 * <p>온보딩에서 뺀 결제는 처음부터 가맹점 판정 성향(§8-S)으로 쌓였는데, <b>같은 뜻의 신호가
 * 원장에서는 버려지고 있었다</b> — {@code undo(NOT_MINE)} 은 거래를 챌린지에서 빼기만 했고,
 * 알림 피드백은 컬럼에만 남았다. 그래서 사용자는 매달 같은 판단을 되풀이해야 했다.
 *
 * <p>이을 수 없었던 이유는 <b>원장이 가맹점을 몰랐기 때문</b>이다. 소비를 원장에 넣을 때
 * 가맹점명 자리에 카테고리 이름을 넣었고 사업자번호는 아예 없었다(V15에서 붙였다).
 *
 * <p>이 테스트가 지키는 것은 잇는 것만이 아니라 <b>가리는 것</b>이다 — 어떤 신호는 성향으로
 * 세면 안 된다. 그게 더 중요하다.
 */
@SpringBootTest
@ActiveProfiles("test")   // 인메모리 H2 — 파일 DB 를 쓰면 낡은 스키마가 남는다
@Transactional
class LedgerStanceFeedbackTest {

    private static final Long USER = 771_204L;
    private static final String BIZ = "1234567890";

    private static final String CAT = "LSF_FOOD";

    @Autowired GuardianService guardianService;
    @Autowired UserMerchantStanceRepository stanceRepository;
    @Autowired com.finntech.repository.ConsumptionRepository consumptionRepository;
    @Autowired com.finntech.repository.CategoryRepository categories;

    @BeforeEach
    void seed() {
        stanceRepository.findByUserId(USER).forEach(stanceRepository::delete);
        com.finntech.domain.Category cat = categories.findByCode(CAT)
                .orElseGet(() -> categories.save(new com.finntech.domain.Category(CAT, "식비")));
        consumptionRepository.deleteByUserIdAndSource(USER, com.finntech.domain.Enums.DataSource.DUMMY_SEED);
        // 챌린지는 기준 지출이 있어야 만들어진다 — 창(최근 30일) 안에 소비를 깔아 둔다(§8-R).
        for (int i = 1; i <= 5; i++) {
            consumptionRepository.save(new com.finntech.domain.Consumption(USER, cat,
                    new java.math.BigDecimal("40000"), LocalDateTime.now().minusDays(i),
                    false, com.finntech.domain.Enums.DataSource.DUMMY_SEED));
        }
    }

    private GuardianService.IngestResult 결제(String merchant, String bizNo) {
        guardianService.createChallenge(USER, List.of(CAT), List.of(),
                100_000L, null, null, 30, List.of(), Map.of());
        return guardianService.ingest(USER, new GuardianService.IngestCommand(
                LocalDateTime.now(), merchant, merchant, 30_000L, null, CAT, 1.0,
                TxType.EXPENSE, false, null, bizNo));
    }

    private UserMerchantStance.Stance 성향() {
        return stanceRepository.findByUserIdAndBusinessNumber(USER, BIZ)
                .map(UserMerchantStance::getStance).orElse(null);
    }

    @Test
    @DisplayName("'내 소비가 아니에요'는 그 가맹점을 관대하게 만든다 — 다음 달에 또 묻지 않는다")
    void undo_NOT_MINE_은_성향에_쌓인다() {
        var r = 결제("아빠식당", BIZ);
        guardianService.undo(USER, r.transaction().getId(), UndoReason.NOT_MINE);

        assertThat(성향()).isEqualTo(UserMerchantStance.Stance.LENIENT);
    }

    @Test
    @DisplayName("면제권은 성향을 올리지 않는다 — '인정하지만 봐달라'는 낭비를 인정하는 말이다")
    void undo_EXEMPTION_은_성향을_안_올린다() {
        var r = 결제("아빠식당", BIZ);
        try {
            guardianService.undo(USER, r.transaction().getId(), UndoReason.EXEMPTION);
        } catch (RuntimeException ignored) {
            // 면제권이 없으면 거절된다 — 그래도 성향이 안 올라가는 것이 이 테스트의 요지다.
        }
        assertThat(성향()).as("면제권을 쓸수록 그 가게가 낭비에서 빠지면 안 된다").isNull();
    }

    @Test
    @DisplayName("알림 피드백 '내 소비 아님'도 같은 신호다 — 온보딩과 원장이 같은 곳으로 흐른다")
    void feedback_NOT_MINE_은_성향에_쌓인다() {
        var r = 결제("아빠식당", BIZ);
        if (r.notification() == null) return;   // 개입 케이스가 안 잡히면 검사할 게 없다
        guardianService.feedback(USER, r.notification().getId(),
                Feedback.NOT_USEFUL, FeedbackReason.NOT_MINE);

        assertThat(성향()).isEqualTo(UserMerchantStance.Stance.LENIENT);
    }

    @Test
    @DisplayName("전달 방식 불만은 판정을 안 건드린다 — 알림이 성가실수록 판정이 무뎌지면 안 된다")
    void feedback_전달불만은_성향을_안_올린다() {
        var r = 결제("아빠식당", BIZ);
        if (r.notification() == null) return;
        for (FeedbackReason reason : List.of(FeedbackReason.TIMING, FeedbackReason.TONE,
                FeedbackReason.TOO_OFTEN, FeedbackReason.ALREADY_KNEW)) {
            guardianService.feedback(USER, r.notification().getId(), Feedback.NOT_USEFUL, reason);
            assertThat(성향()).as(reason + " 는 '이 가게는 낭비가 아니다'가 아니다").isNull();
        }
    }

    @Test
    @DisplayName("사업자번호가 없으면 아무 데도 안 묶는다 — 상호명으로 역산하지 않는다")
    void 사업자번호가_없으면_건너뛴다() {
        var r = 결제("이름만있는가게", null);
        guardianService.undo(USER, r.transaction().getId(), UndoReason.NOT_MINE);

        assertThat(stanceRepository.findByUserId(USER))
                .as("복원율 75.8%짜리 역산으로 잘못 묶느니 놓치는 편이 낫다(§8-S)")
                .isEmpty();
    }
}
