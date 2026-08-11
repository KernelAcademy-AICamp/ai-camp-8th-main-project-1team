package com.finntech.service;

import com.finntech.domain.MerchantCategoryVote;
import com.finntech.repository.MerchantCategoryVoteRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 사람의 확정을 <b>한 표</b>로 세고, 전역 분류는 다수가 정한다(V30).
 *
 * <p><b>집계 규칙이 여기 한 곳에만 있다.</b> 두 곳에 적으면 갈라진다
 * ({@link BusinessNumberKindService} 가 전이 규칙을 한 곳에 둔 것과 같은 이유).
 *
 * <pre>
 *   쇼핑 2 · 식비 1   → 쇼핑
 *   쇼핑 2 · 식비 2   → **아무것도 안 바꾼다** (사전 행을 그대로 둔다)
 *   쇼핑 3 · 식비 2   → 쇼핑
 * </pre>
 *
 * <h2>왜 단순 다수결인가</h2>
 *
 * 표가 이미 <b>사람당 하나</b>라 다수결 자체가 비례적이다. {@link BusinessNumberKindService} 가
 * 뒤집는 데 별도 비율({@code overturn-ratio})을 요구한 것은 그쪽의 관측 단위가 <i>상호</i>라
 * 한 사람이 수만 개를 만들 수 있었기 때문이다 — 여기 단위는 <i>사람</i>이라 그 사정이 없다.
 *
 * <h2>왜 동률에 안 고르나</h2>
 *
 * 갈렸다는 것은 <b>그 가맹점이 사람마다 다르게 보인다</b>는 뜻이다 — 배달의민족은 등록이
 * 통신판매업이라 '쇼핑'이지만 쓴 돈은 밥값이다. 둘 다 틀리지 않은 자리에서 억지로 고르면
 * 진 쪽의 전역 화면이 이유 없이 흔들린다. 모를 때는 보류가 기본이다.
 *
 * <h2>져도 자기 결제는 자기 답이다</h2>
 *
 * 표에서 지는 것이 판단을 잃는 것이 아니다. 본인의 결제는
 * {@code user_payment.category2_source='USER'} 가 지키고, 그 값은 사전이 덮지 않는다.
 * 전역은 <b>처음 보는 결제에 붙는 기본값</b>일 뿐이다.
 */
@Service
public class MerchantCategoryVoteService {

    private final MerchantCategoryVoteRepository repository;

    public MerchantCategoryVoteService(MerchantCategoryVoteRepository repository) {
        this.repository = repository;
    }

    /**
     * 한 표를 적고 <b>지금의 다수</b>를 돌려준다.
     *
     * <p>다시 확정하면 그 사람의 표가 <b>바뀐다</b>(늘지 않는다).
     *
     * @return 다수가 정해졌으면 그 중분류, <b>동률이면 {@link Optional#empty()}</b>
     *         — 부르는 쪽은 사전을 건드리지 않는다
     */
    @Transactional
    public Optional<String> castAndTally(String businessNumber, String merchantName,
                                         Long userId, String category2) {
        if (merchantName == null || merchantName.isBlank()
                || userId == null || category2 == null || category2.isBlank()) {
            return Optional.empty();
        }
        String biz = businessNumber == null ? "" : businessNumber;
        repository.findByBusinessNumberAndMerchantNameAndUserId(biz, merchantName, userId)
                .ifPresentOrElse(
                        vote -> vote.recast(category2),
                        () -> repository.save(
                                new MerchantCategoryVote(biz, merchantName, userId, category2)));
        return winner(repository.findBallots(biz, merchantName));
    }

    /** 그 사람이 이 가맹점에 던진 표 — 없으면 empty. */
    @Transactional(readOnly = true)
    public Optional<String> voteOf(String businessNumber, String merchantName, Long userId) {
        if (merchantName == null || merchantName.isBlank() || userId == null) {
            return Optional.empty();
        }
        return repository
                .findByBusinessNumberAndMerchantNameAndUserId(
                        businessNumber == null ? "" : businessNumber, merchantName, userId)
                .map(MerchantCategoryVote::getCategory2);
    }

    /** 지금 표의 다수 — 동률이면 empty. 사전을 건드리지 않고 세기만 한다. */
    @Transactional(readOnly = true)
    public Optional<String> tally(String businessNumber, String merchantName) {
        if (merchantName == null || merchantName.isBlank()) return Optional.empty();
        return winner(repository.findBallots(
                businessNumber == null ? "" : businessNumber, merchantName));
    }

    /**
     * 표를 세어 <b>단독 최다</b>를 고른다 — 동률이면 아무도 안 고른다.
     *
     * <p>{@code TreeMap} 을 쓰는 것은 재현성 때문이다(§4-3). 동률 판정이 순서에 기대지 않지만,
     * 로그·시험이 같은 것을 보려면 세는 순서도 고정돼야 한다.
     */
    private static Optional<String> winner(List<MerchantCategoryVote> ballots) {
        if (ballots.isEmpty()) return Optional.empty();
        Map<String, Integer> counts = new TreeMap<>();
        for (MerchantCategoryVote v : ballots) {
            counts.merge(v.getCategory2(), 1, Integer::sum);
        }
        String best = null;
        int top = 0;
        boolean tied = false;
        for (Map.Entry<String, Integer> e : counts.entrySet()) {
            if (e.getValue() > top) {
                top = e.getValue();
                best = e.getKey();
                tied = false;
            } else if (e.getValue() == top) {
                tied = true;
            }
        }
        return tied ? Optional.empty() : Optional.ofNullable(best);
    }
}
