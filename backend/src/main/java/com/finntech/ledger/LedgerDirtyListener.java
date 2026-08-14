package com.finntech.ledger;

import com.finntech.domain.SpendingLedgerDirty;
import com.finntech.domain.UserMerchantStance;
import com.finntech.domain.UserPayment;
import com.finntech.domain.UserSpendingOverride;
import jakarta.persistence.PostPersist;
import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 소비 원장의 원천이 바뀌면 그 사용자를 표시한다 — <b>호출부를 하나도 고치지 않고.</b>
 *
 * <h2>왜 호출부가 아니라 여기인가</h2>
 *
 * <p>분류를 바꾸는 자리가 열 곳이고 결제를 넣는 자리가 둘인데, 그 전부가
 * {@link UserPayment} 의 도메인 메서드 둘({@code confirmCategory2}·{@code suggestCategory2})을
 * 지나거나 더티체킹으로 필드만 바꾼다. 호출부마다 표시를 심으면 <b>열한 번째 호출부가 생기는
 * 날 한 곳이 빠진다</b> — 이 저장소가 이미 겪은 형태다({@code UserPayment.confirmCategory2}
 * 의 머리말: <i>"확정을 적는 자리가 여섯 곳이라 흩어 놓으면 한 곳이 빠진다"</i>).
 * 엔티티 콜백은 누가 바꿨든 걸린다.
 *
 * <p>{@code @DomainEvents} 는 못 쓴다 — {@code repository.save()} 에서만 발행되는데,
 * 이 저장소의 분류 변경은 대부분 더티체킹이라({@code applyResolved}·{@code applyEstimates}·
 * {@code confirm}·{@code paint}·{@code applySettled}) 한 번도 안 터진다.
 *
 * <h2>여기서 DB 를 만지지 않는다</h2>
 *
 * <p>콜백은 flush 한복판에서 돈다. 쓰기를 걸면 Hibernate 의 작업 큐가 재귀한다. 하는 일은
 * 맵에 사용자 번호 하나를 넣는 것뿐이고, 실제 기록은 커밋 직전에 한 번에 일어난다
 * ({@link SpendingLedgerDirtyMarker}). 그래서 재연동이 결제 5,000건을 넣어도 그 트랜잭션에
 * 늘어나는 문장은 <b>사용자 수만큼</b>이다.
 *
 * <p>표시기를 {@link ObjectProvider} 로 받는 것도 같은 자리의 문제다 — 이 리스너는
 * EntityManagerFactory 를 세우는 도중에 만들어지므로, 저장소를 물고 있는 빈을 그때 요구하면
 * 순환이 된다.
 */
public class LedgerDirtyListener {

    @Autowired
    private ObjectProvider<SpendingLedgerDirtyMarker> markers;

    /**
     * 원천이 생기거나 사라졌다.
     *
     * <p>세 엔티티가 한 리스너를 함께 쓴다. JPA 는 콜백 서명을 {@code void m(Object)} 로도
     * 허용하므로, 같은 일을 하는 클래스를 셋 만들 이유가 없다.
     */
    @PostPersist
    @PostRemove
    void sourceAppearedOrVanished(Object entity) {
        mark(entity, SpendingLedgerDirty.Reason.PAYMENT);
    }

    /**
     * 원천의 내용이 바뀌었다.
     *
     * <p>{@link UserPayment} 에서 바뀔 수 있는 것은 <b>분류 세 칸뿐</b>이다
     * ({@code category2}·{@code category2Llm}·{@code category2Source}) — 나머지는 세터가 없다.
     * 그래서 갱신은 언제나 {@code CATEGORY} 다.
     */
    @PostUpdate
    void sourceChanged(Object entity) {
        mark(entity, SpendingLedgerDirty.Reason.CATEGORY);
    }

    /** 결제가 아닌 원천은 무엇이 일어났든 제 사유를 쓴다. 사유는 로그·점검용이라 그것으로 충분하다. */
    private void mark(Object entity, SpendingLedgerDirty.Reason paymentReason) {
        SpendingLedgerDirty.Reason reason;
        if (entity instanceof UserPayment) reason = paymentReason;
        else if (entity instanceof UserMerchantStance) reason = SpendingLedgerDirty.Reason.STANCE;
        else if (entity instanceof UserSpendingOverride) reason = SpendingLedgerDirty.Reason.OVERRIDE;
        else return;

        Long userId = userIdOf(entity);
        if (userId != null) markers.getObject().mark(userId, reason);
    }

    private static Long userIdOf(Object entity) {
        if (entity instanceof UserPayment payment) return payment.getUserId();
        if (entity instanceof UserMerchantStance stance) return stance.getUserId();
        if (entity instanceof UserSpendingOverride override) return override.getUserId();
        return null;
    }
}
