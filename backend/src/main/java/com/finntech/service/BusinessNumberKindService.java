package com.finntech.service;

import com.finntech.domain.BusinessNumberKind;
import com.finntech.domain.BusinessNumberKind.Kind;
import com.finntech.repository.BusinessNumberKindRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * 사업자번호가 <b>한 사업인가 여러 사업인가</b>를 관측으로 판정한다(V16).
 *
 * <p>전이 규칙이 <b>여기 한 곳에만</b> 있다. 두 곳에 적으면 갈라진다.
 *
 * <pre>
 *   UNKNOWN → SINGLE   서로 다른 상호 N종 이상이 **전부 한 중분류**로 모임
 *   UNKNOWN → MULTI    중분류가 갈림
 *   SINGLE  → MULTI    **서로 다른 상호 M종 이상**이 정착 분류와 다르게 확정됨
 * </pre>
 *
 * <h2>왜 뒤집는 데도 증거를 요구하나</h2>
 *
 * 그러지 않으면 <b>증거의 무게가 안 맞는다.</b> SINGLE 은 상호 5종을 관측해 굳는데 MULTI 는
 * 한 번의 교정으로 뒤집힌다면, 사용자가 택시 한 건을 실수로 잘못 고치는 순간 <b>그 뒤의 수만 건이
 * 전부 따로 기록된다</b>(사용자 지적 2026-08-05). 완화가 지키던 것이 통째로 무너진다.
 *
 * 그래서 한 번의 반대는 <b>그 상호만의 예외</b>로 둔다 — 정확일치 행이 이미 그 일을 한다.
 * <b>서로 다른 상호에서 또 갈릴 때</b> 비로소 사업이 여럿이라고 본다. 실수를 되돌리면 반대 증거가
 * 사라져 SINGLE 이 그대로 산다.
 */
@Service
public class BusinessNumberKindService {

    private final BusinessNumberKindRepository repository;

    /** SINGLE 로 굳는 데 필요한 <b>서로 다른 상호</b> 수. 적으면 성급하고 많으면 영영 안 굳는다. */
    private final int singleEvidenceNames;

    /** 뒤집는 데 필요한 반대 상호 수의 <b>최소값</b>. 실제 문턱은 관측량에 따라 더 높아진다. */
    private final int multiEvidenceNames;

    public BusinessNumberKindService(
            BusinessNumberKindRepository repository,
            @Value("${finntech.analysis.merchant.single-evidence-names:5}") int singleEvidenceNames,
            @Value("${finntech.analysis.merchant.multi-evidence-names:2}") int multiEvidenceNames,
            @Value("${finntech.analysis.merchant.overturn-ratio:0.10}") double overturnRatio) {
        this.repository = repository;
        this.singleEvidenceNames = Math.max(2, singleEvidenceNames);
        this.multiEvidenceNames = Math.max(2, multiEvidenceNames);
        this.overturnRatio = overturnRatio;
    }

    /** 뒤집는 데 필요한 반대 비율. 관측 상호의 이만큼이 다르게 확정돼야 복합으로 본다. */
    private final double overturnRatio;

    /**
     * 완화를 써도 되는가.
     *
     * <p><b>행이 없으면 허용한다.</b> 이 표에는 <b>상호가 둘 이상 관측된 번호만</b> 올라온다 —
     * 상호가 하나뿐인 번호는 완화해도 <b>오염될 대상이 없다</b>. 그런 번호까지 보류하면
     * 사전이 통째로 무력해진다(실측: 확정 319건이 전부 완화로 붙는다).
     *
     * <p>행이 있으면 {@code SINGLE} 만 허용한다 — 관측으로 "전부 같은 것을 판다"가 확인된 것이다.
     * {@code UNKNOWN} 은 아직 모르는 것이고, 모를 때는 보류가 기본이다.
     */
    @Transactional(readOnly = true)
    public boolean relaxationAllowed(String businessNumber) {
        return repository.findById(businessNumber).map(BusinessNumberKind::isSingle).orElse(true);
    }

    /** 스냅샷에서 같은 판정을 한다 — 적재 루프가 건마다 질의하지 않게. 규칙은 위와 같다. */
    public static boolean relaxationAllowed(BusinessNumberKind row) {
        return row == null || row.isSingle();
    }

    /** 여러 번호의 판정을 한 번에 읽는다(적재 루프용). */
    @Transactional(readOnly = true)
    public Map<String, BusinessNumberKind> snapshot(Collection<String> businessNumbers) {
        Map<String, BusinessNumberKind> out = new HashMap<>();
        if (businessNumbers.isEmpty()) return out;
        for (BusinessNumberKind k : repository.findByBusinessNumberIn(businessNumbers)) {
            out.put(k.getBusinessNumber(), k);
        }
        return out;
    }

    /**
     * 한 번호에서 관측한 <b>(가맹점명 → 중분류)</b> 로 판정을 갱신한다.
     *
     * <p>분류가 안 된 이름({@code null})은 <b>세되 판정에는 안 쓴다</b> — 상호 수는 늘지만
     * 갈렸는지는 말해 주지 않는다. 그래야 "아직 모른다"와 "같다고 확인했다"가 구분된다.
     *
     * @param observed 그 번호에서 본 가맹점명 → 중분류(모르면 null)
     * @param confirmedByUser 사람이 확정한 (가맹점명 → 중분류). 뒤집는 증거는 이것만 센다.
     */
    @Transactional
    public BusinessNumberKind observe(String businessNumber, Map<String, String> observed,
                                      Map<String, String> confirmedByUser, LocalDateTime at) {
        // 상호가 하나뿐이면 판정할 것이 없다 — 완화해도 오염될 대상이 없으므로 행을 만들지 않는다.
        if (observed.size() < 2) {
            return repository.findById(businessNumber).orElse(null);
        }

        Map<String, String> known = new TreeMap<>();
        observed.forEach((name, mid) -> {
            if (mid != null && !mid.isBlank()) known.put(name, mid);
        });
        Set<String> kinds = new TreeSet<>(known.values());

        Kind decided;
        String settled = null;
        if (kinds.size() >= 2) {
            decided = Kind.MULTI;
        } else if (kinds.size() == 1 && observed.size() >= singleEvidenceNames) {
            decided = Kind.SINGLE;
            settled = kinds.iterator().next();
        } else {
            decided = Kind.UNKNOWN;
            if (kinds.size() == 1) settled = kinds.iterator().next();
        }

        BusinessNumberKind row = repository.findById(businessNumber).orElse(null);
        if (row == null) {
            return repository.save(new BusinessNumberKind(
                    businessNumber, decided, settled, observed.size(), at));
        }

        // **이미 SINGLE 로 굳은 것은 쉽게 뒤집지 않는다.** 문턱은 관측량에 비례한다.
        if (row.isSingle() && decided == Kind.MULTI) {
            long dissenting = confirmedByUser.entrySet().stream()
                    .filter(e -> e.getValue() != null && !e.getValue().equals(row.getSettledCategory2()))
                    .count();
            if (dissenting < overturnThreshold(row.getMerchantNames())) {
                // 그 상호만 예외로 둔다 — 정확일치 행이 이미 그 일을 한다. 완화는 살려 둔다.
                row.observe(Kind.SINGLE, row.getSettledCategory2(),
                        Math.max(row.getMerchantNames(), observed.size()), at);
                return row;
            }
        }
        // MULTI 는 한 번 정해지면 관측만으로 되돌리지 않는다 — 갈렸다는 사실이 사라지진 않는다.
        if (row.isMulti() && decided != Kind.MULTI) {
            row.observe(Kind.MULTI, row.getSettledCategory2(),
                    Math.max(row.getMerchantNames(), observed.size()), at);
            return row;
        }
        row.observe(decided, settled, Math.max(row.getMerchantNames(), observed.size()), at);
        return row;
    }

    /**
     * {@code SINGLE} 을 뒤집는 데 필요한 <b>반대 상호</b> 수 — <b>관측한 상호의 10%</b>.
     *
     * <p>고정값이면 무게가 안 맞는다. 상호 5종이 같은 것과 <b>3,000종이 같은 것</b>은 증거의
     * 크기가 다른데, 둘 다 반대 2종으로 뒤집힌다면 택시처럼 많이 쓰이는 번호는 <b>누군가 두 번
     * 실수하는 것만으로</b> 무너진다(사용자 지적 2026-08-05). 택시를 타는 사람이 많을수록 실수도
     * 늘어난다 — 표본이 클수록 문턱도 커져야 한다.
     *
     * <p>그래서 비율로 둔다. <b>열에 하나가 다르다면 그건 실수가 아니라 그 번호가 원래 갈린 것</b>
     * 이라고 보는 것이다.
     *
     * <pre>
     *   문턱 = max(최소값, ⌈관측 상호 수 × 10%⌉)
     *
     *     상호    5종 →  2종      상호   40종 →   4종
     *     상호  320종 → 32종      상호 3000종 → 300종
     * </pre>
     *
     * <p><b>SINGLE 에 도달했다는 것 자체가 강한 증거다</b> — 서로 다른 상호가 전부 한 분류로
     * 모였다는 뜻이다. 그런 번호가 실제로 복합일 확률은 낮으므로 문턱이 높아도 된다.
     * 백화점처럼 진짜 복합인 곳은 <b>첫 관측에서 이미 갈려</b> SINGLE 을 거치지 않는다.
     *
     * <p><b>이 문턱은 전역 판정에만 걸린다.</b> 사용자가 고친 그 가맹점은 정확일치 행이 되어
     * <b>즉시 그 분류로 보인다</b> — 문턱을 못 넘었다고 교정이 무시되는 것이 아니다.
     */
    int overturnThreshold(int merchantNames) {
        return Math.max(multiEvidenceNames, (int) Math.ceil(merchantNames * overturnRatio));
    }

    /** 판정이 아직 없는 번호들 — 관측 대상. */
    @Transactional(readOnly = true)
    public List<BusinessNumberKind> all() {
        return repository.findAll();
    }
}
