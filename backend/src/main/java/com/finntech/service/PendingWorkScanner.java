package com.finntech.service;

import com.finntech.domain.UserPayment;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.UserPaymentRepository;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * <b>아직 모델을 못 받은 일</b>을 훑어 큐에 올린다 — 사용자의 상호작용이 부른다.
 *
 * <p><b>왜 필요한가.</b> 큐는 메모리에 있어 재기동하면 대기 목록이 날아간다. 원본은 DB 에
 * 남으므로(브랜드가 없다·문장이 낡았다) 잃는 것은 없지만, <b>누군가 다시 올려 줘야 한다.</b>
 *
 * <p><b>여기서 훑는 것은 '화면 없이도 아는 일'뿐이다.</b> 문장은 여기서 안 훑는다 — 문장을
 * 만들려면 그 화면의 집계가 필요하고, 그것은 <b>화면이 이미 손에 들고 있다.</b> 그래서 문장은
 * 그 화면이 열릴 때 자기가 넣는다({@link NarrativeCacheService#enqueueIfNeeded}). 재료를 다시
 * 계산해 가며 여기서 훑으면 상호작용마다 리포트를 새로 집계하는 꼴이 된다.
 *
 * <p>그 분업에는 뜻이 하나 더 있다 — <b>아무도 안 보는 화면의 문장은 만들 필요가 없다.</b>
 * 보는 사람이 트리거이므로 예산이 보이는 것에만 쓰인다.
 *
 * <p><b>중복은 여기서 안 막는다.</b> 큐가 같은 키를 접고({@code FreeChannelQueue}), 이미
 * 끝난 일은 아래 조건("아직 브랜드가 없다")이 스스로 걸러 낸다.
 */
@Service
public class PendingWorkScanner {

    private final UserPaymentRepository payments;
    private final AppUserRepository users;
    private final MerchantBrandService brands;

    public PendingWorkScanner(UserPaymentRepository payments, AppUserRepository users,
                              MerchantBrandService brands) {
        this.payments = payments;
        this.users = users;
        this.brands = brands;
    }

    /**
     * 그 사용자의 밀린 일을 큐에 올린다.
     *
     * @return 새로 올린 건수 (이미 큐에 있던 것은 세지 않는다)
     */
    public int scan(Long userId) {
        // 더미는 브랜드를 안 쌓는다 — 생성기가 조립한 상호라 표에 앉을 자격이 없다(§13-13).
        if (!users.existsByIdAndRealPersonTrue(userId)) return 0;

        List<String> names = payments.findByUserIdOrderByPaymentDateDesc(userId).stream()
                .filter(UserPayment::isFromRealPerson)
                .map(UserPayment::getMerchantName)
                .filter(n -> n != null && !n.isBlank())
                .distinct().toList();
        if (names.isEmpty()) return 0;

        return brands.enqueuePending(names, java.util.Set.copyOf(names));
    }
}
