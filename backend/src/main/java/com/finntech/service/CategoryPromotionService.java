package com.finntech.service;

import com.finntech.domain.Category;
import com.finntech.domain.Consumption;
import com.finntech.domain.UserPayment;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.CategoryRepository;
import com.finntech.repository.ConsumptionRepository;
import com.finntech.repository.ReportRepository;
import com.finntech.repository.UserPaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * <b>LLM 추정을 그 사람의 원장에만 적용한다.</b>
 *
 * <h2>왜 필요한가 — 실측</h2>
 *
 * <p>실 명세서에는 업종코드가 없어 사업자번호로 등록 업종을 물어 확정을 채운다(§13-12 순위 ②-b).
 * 그런데 <b>PG·상품권 결제는 그 길이 막혀 있다</b> — {@code 411-86-01799} 하나에 토스페이·
 * 카카오페이·기프티스타가 함께 붙어, 물어봐야 나오는 것은 결제대행사의 업종이지 무엇을 샀는지가
 * 아니다(CLAUDE.md "PG 번호는 키가 아니다"). 운영 로그가 그 상태를 그대로 보여줬다 —
 * {@code 물어본 곳 24, 분류된 가맹점 0} 이 2분마다 반복됐다(2026-08-12).
 *
 * <p>그 결제들은 답이 <b>있는데도</b> 추정층에 머문다. 그리고 계산이 읽는 {@code Consumption}
 * 에는 확정만 들어가므로, 같은 사용자가 화면에서 110,680원을 보고 서버는 <b>1,200원</b>으로
 * 세는 일이 벌어졌다. 강도를 최소로 내려도 그보다 작아질 수 없어 <b>챌린지를 만드는 것이
 * 불가능</b>했고, 온보딩이 거기서 끝났다(운영 userId=30).
 *
 * <h2>어디까지 가는가 — 그 사람의 원장까지</h2>
 *
 * <pre>
 *   user_payment.category2      ✅ 바뀐다 (source = LLM_LOCAL)
 *   consumption.category        ✅ 함께 — 분석·리포트·점수가 읽는 값이다
 *   merchant_category (전역 사전) ❌ <b>안 바뀐다</b>
 *   다른 사용자                   ❌ 아무 영향 없다
 * </pre>
 *
 * <p><b>사전에 넣지 않는 것이 이 설계의 선이다.</b> 사전은 전역 자산이라 한 사람의 오분류가
 * 모두에게 간다. 한 사람의 원장 안에서는 그 위험이 없다 — 틀리면 그 사람만 틀리고, 그 사람이
 * 화면에서 고치면 끝난다. 사전에 들어가는 길은 종전대로 <b>사람이 직접 고른 것</b> 하나뿐이다
 * ({@code MerchantCategoryController.confirm} → {@code confirmFrom} → 한 표).
 *
 * <h2>원칙 1과의 관계 (2026-08-12 사용자 결정)</h2>
 *
 * <p>마스터 §4 원칙 1 은 "추정은 판정에 참여하지 않는다"고 적고 있고, 이 통로는 그 문장과
 * 어긋난다. <b>사용자가 알고 진행하기로 정했다.</b> 지키는 것과 양보하는 것을 분명히 해 둔다:
 *
 * <ul>
 *   <li><b>지킨다</b> — 낭비 판정과 점수는 여전히 규칙 엔진·EBM 이 한다. 모델이 답하는 것은
 *       "이 가게가 무엇을 파는가"뿐이고, 그 자리는 등록 업종 조회가 사실로 답하던 자리다.
 *       모델은 조회가 못 닿는 PG·상품권에서만 그 자리를 대신한다.</li>
 *   <li><b>지킨다</b> — 전역 사전은 안 건드린다. 오분류가 번지지 않는다.</li>
 *   <li><b>지킨다</b> — 사실이 추정을 이긴다. {@code LLM_LOCAL} 은 약한 출처라
 *       등록 업종 조회({@code REGISTRY})나 사람의 확정({@code USER})이 오면 덮인다.</li>
 *   <li><b>양보한다</b> — 그 사람의 수치에는 추정이 들어간다. 안 넣으면 화면과 계산이 갈리고,
 *       실제로 온보딩이 시작조차 되지 않았다.</li>
 * </ul>
 *
 * <h2>왜 "확인 버튼"을 두지 않는가</h2>
 *
 * <p>스물넷을 하나씩 확인시키는 화면은 온보딩에서 아무도 끝내지 않는다. 그렇다고 시작 버튼
 * 하나를 확인으로 치는 것은 <b>확인한 적 없는 것을 확인했다고 적는 일</b>이라 더 나쁘다 —
 * 기록이 거짓이 된다. 확인을 받지 않았으므로 <b>확인받았다고 적지도 않는다.</b>
 * 출처가 {@code LLM_LOCAL} 인 이유가 그것이다.
 */
@Service
public class CategoryPromotionService {

    private static final Logger log = LoggerFactory.getLogger(CategoryPromotionService.class);

    /**
     * 원장에 적히는 출처.
     *
     * <p>{@code USER}(사람이 집어 확정) · {@code DICT}(전역 사전) · {@code REGISTRY}(등록 업종)
     * 와 <b>구별된다.</b> 같은 이름으로 적으면 나중에 어느 값이 모델에서 왔는지 가려낼 방법이
     * 없고, "사실이 추정을 이긴다"를 코드로 지킬 수도 없다. 칸이 10자라 이름이 짧다.
     */
    public static final String SOURCE = "LLM_LOCAL";

    private final UserPaymentRepository payments;
    private final ConsumptionRepository consumptions;
    private final CategoryRepository categories;
    private final IndustryCategoryMapper mapper;
    private final ReportRepository reports;

    public CategoryPromotionService(UserPaymentRepository payments, ConsumptionRepository consumptions,
                                    CategoryRepository categories,
                                    IndustryCategoryMapper mapper, ReportRepository reports) {
        this.payments = payments;
        this.consumptions = consumptions;
        this.categories = categories;
        this.mapper = mapper;
        this.reports = reports;
    }

    /**
     * 이 사용자의 미분류 결제 중 <b>추정이 있는 것</b>을 원장에 반영한다.
     *
     * <p>카테고리도 기간도 가리지 않는다. 가리면 화면마다 다른 숫자가 나온다 — 챌린지는
     * 시작되는데 리포트와 점수는 옛 숫자인 상태가 되고, 그것이 이 저장소가 반복해서 고쳐 온 병이다.
     *
     * @return 반영한 결제 수
     */
    @Transactional
    public int applyEstimates(Long userId) {
        if (userId == null) return 0;
        Set<String> known = mapper.midCategories();

        int applied = 0;
        for (UserPayment payment : payments.findByUserIdOrderByPaymentDateDesc(userId)) {
            // 이미 답이 있는 결제는 건드리지 않는다 — 사람이든 사전이든 등록 업종이든
            // 앞선 근거가 추정보다 세다.
            String current = payment.getCategory2();
            if (current != null && !IndustryCategoryMapper.isUnknown(current)) continue;

            String guess = payment.getCategory2Llm();
            // **모르는 중분류는 거절한다.** 모델이 목록 밖의 이름을 답하는 일이 있고,
            // 그대로 적으면 어느 화면에도 안 잡히는 카테고리가 원장에 생긴다.
            if (guess == null || !known.contains(guess)) continue;

            payment.confirmCategory2(guess, SOURCE);
            // **원장의 짝도 함께 고친다.** 분석·리포트·점수가 읽는 것은 `Consumption` 이고 그
            // 카테고리는 적재할 때 박힌 값이라, 결제만 고치면 기준 지출은 그대로 1,200원이다.
            Category category = categories.findByCode(guess)
                    .orElseGet(() -> categories.save(new Category(guess, guess)));
            for (Consumption consumption : consumptions.findBySourcePaymentId(payment.getPaymentId())) {
                consumption.reclassify(category);
            }
            applied++;
        }

        if (applied > 0) {
            // 리포트는 (사용자, 연월)로 캐시되고 그 값은 `Consumption` 집계다. 안 깨면
            // 사용자는 바뀐 분류를 챌린지에서만 보고 리포트에서는 옛 숫자를 계속 본다.
            reports.deleteByUserId(userId);
            log.info("추정을 본인 원장에 반영했다 — userId={} {}건", userId, applied);
        }
        return applied;
    }
}
