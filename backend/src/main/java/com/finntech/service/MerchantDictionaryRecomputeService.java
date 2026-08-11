package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.domain.MerchantCategory;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.MerchantCategoryRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>대조표를 고쳤을 때 사전을 다시 계산한다</b>(V29).
 *
 * <p>이것이 없으면 {@code nts_codes} 는 장식이다. 근거를 적어 두는 이유가 <i>"표를 고쳤을 때
 * 다시 계산하기 위해서"</i>인데, 원재료만 쌓고 함수를 다시 돌릴 방법이 없으면 데이터만 늘고
 * 아무 일도 안 일어난다.
 *
 * <h2>왜 Python 스크립트가 아니라 여기인가</h2>
 *
 * <b>가장 재계산이 필요한 행이 어떤 파일에도 없기 때문</b>이다. {@code USER_CSV} 는 씨앗을
 * 다시 빌드하면 되지만, {@code REGISTRY} 로 들어온 행은 조회처의 답을 받아 그 자리에서 사전에
 * 앉은 것이라 <b>DB 에만 산다</b>. 그리고 {@link IndustryCategoryMapper} 가 이미 표를 기동 때
 * 들고 있어 재계산에 I/O 가 0회다. Python 판을 만들면 만장일치 규칙이 두 벌이 되는데,
 * 그걸 피하려고 코드를 <b>목록</b>으로 담기로 한 것이라 여기서 도로 만들면 앞뒤가 안 맞는다.
 *
 * <h2>사전만 고치면 반쪽이다</h2>
 *
 * 리포트·판정이 읽는 것은 {@code Consumption} 이고 그 카테고리는 <b>적재할 때 박힌 값</b>이다.
 * 사전만 고치고 원장을 두면 사용자 화면은 그대로다 — 이 저장소가 이미 두 번 겪은 자리다
 * ({@code MyDataLinkService.applyResolved} 의 비아인키노, {@code MerchantCategoryController}
 * 의 {@code alsoFixed} 루프). 그래서 {@link MyDataLinkService#applyResolved} 를 <b>그대로
 * 재사용</b>하고 리포트 캐시까지 깬다.
 *
 * <h2>강등하지 않는다</h2>
 *
 * 새 중분류가 '카테고리없음'·'기타'가 되는 경우 <b>손대지 않고 세기만 한다.</b>
 * {@link IndustryCategoryMapper#isUnknown} 이 그 값을 판정에서 빼므로, 확정 행 하나를
 * 조용히 강등시키면 <b>그 지출이 리포트에서 통째로 사라진다.</b> 표에서 코드가 빠진 것은
 * 사람이 봐야 할 일이지 자동으로 처리할 일이 아니다.
 *
 * <h2>스케줄러에 걸지 않는다</h2>
 *
 * 매 기동·매 회차 재계산은 <b>조용한 원장 재작성</b>이다. 부르는 자리는 운영자의 손
 * ({@code /api/ops})뿐이고, 기본은 dry-run 이다.
 */
@Service
public class MerchantDictionaryRecomputeService {

    private final MerchantCategoryRepository repository;
    private final IndustryCategoryMapper mapper;
    private final AppUserRepository users;
    private final MyDataLinkService ledger;
    private final com.finntech.repository.ReportRepository reports;

    /** 한 번에 훑을 사전 행 수. 사전은 가맹점 단위라 작지만 상한은 둔다. */
    private final int batch;

    public MerchantDictionaryRecomputeService(
            MerchantCategoryRepository repository,
            IndustryCategoryMapper mapper,
            AppUserRepository users,
            MyDataLinkService ledger,
            com.finntech.repository.ReportRepository reports,
            @Value("${finntech.merchant-dictionary.recompute.batch:2000}") int batch) {
        this.repository = repository;
        this.mapper = mapper;
        this.users = users;
        this.ledger = ledger;
        this.reports = reports;
        this.batch = Math.max(1, batch);
    }

    /** 한 행에서 무엇이 달라지는가 — dry-run 이 그대로 돌려준다. */
    public record Change(String businessNumber, String merchantName, String source,
                         String from, String to, String codes) {
    }

    /** 한 번 훑은 결과. 사용자에게 보이는 것이 아니라 <b>운영자</b>가 읽는다. */
    public record Result(boolean applied, int scanned, int backfilled, int unmappable,
                         int ledgerRowsFixed, List<Change> changes) {
    }

    /**
     * 사전을 다시 계산한다.
     *
     * @param apply {@code false} 면 아무것도 쓰지 않고 무엇이 달라질지만 돌려준다(기본).
     */
    @Transactional
    public Result recompute(boolean apply) {
        List<MerchantCategory> rows = repository.findTableDerived(PageRequest.of(0, batch));
        List<Change> changes = new ArrayList<>();
        int backfilled = 0;
        int unmappable = 0;
        // 가맹점명 → 새 중분류. 원장을 고칠 때 쓴다(applyResolved 가 받는 모양 그대로).
        Map<String, String> resolved = new LinkedHashMap<>();

        for (MerchantCategory row : rows) {
            List<String> codes = row.ntsCodeList();

            // ① 근거가 비어 있으면 조회 답(업종 이름)에서 되찾는다 — 바깥 호출이 0회다.
            //    이 행이 REGISTRY 가 됐다는 것은 그 이름이 색인에 있었다는 뜻이므로
            //    원리적으로 역산이 된다. 절단 등으로 실패하면 그 행은 건드리지 않는다.
            if (codes.isEmpty()) {
                codes = mapper.codesOfFineName(row.getRegistryIndustry());
                if (codes.isEmpty()) continue;
                if (apply) row.reclassify(row.getCategory2(),
                        MerchantCategory.Source.valueOf(row.getSource()), row.getConfirmedBy(), codes);
                backfilled++;
            }

            // ② 그 코드들을 지금 표로 다시 읽는다 — 살아 있는 경로와 **같은 함수**다.
            String now = mapper.midOfCodes(codes);
            if (now.equals(row.getCategory2())) continue;

            // ③ 강등은 하지 않는다. 표에서 코드가 빠진 것은 사람이 봐야 할 일이다.
            if (IndustryCategoryMapper.isUnknown(now)) {
                unmappable++;
                continue;
            }

            changes.add(new Change(row.getBusinessNumber(), row.getMerchantName(),
                    row.getSource(), row.getCategory2(), now, String.join(",", codes)));
            if (apply) {
                row.reclassify(now, MerchantCategory.Source.valueOf(row.getSource()),
                        row.getConfirmedBy(), codes);
                resolved.putIfAbsent(row.getMerchantName(), now);
            }
        }

        int ledgerFixed = apply && !resolved.isEmpty() ? applyToLedger(resolved) : 0;
        return new Result(apply, rows.size(), backfilled, unmappable, ledgerFixed, changes);
    }

    /**
     * 새 분류를 <b>결제와 소비에 실제로 입힌다</b> — 실제 사람만.
     *
     * <p>더미 사용자는 볼 필요가 없다. 사전은 실물에서만 자라므로({@code isFromRealPerson} 관문)
     * 여기 걸리는 가맹점은 더미의 원장에 없고, 1,100만 건을 훑어 봐야 0건을 고친다.
     *
     * <p>사람이 이미 정한 결제는 {@code applyResolved} 가 건너뛴다 — 그 규칙이 한 곳에 있다.
     */
    private int applyToLedger(Map<String, String> resolved) {
        int fixed = 0;
        // 정렬 고정 — 같은 입력이 같은 순서로 처리돼야 한다(§4-3).
        Map<Long, AppUser> realPeople = new TreeMap<>();
        for (AppUser u : users.findAll()) {
            if (u.isRealPerson()) realPeople.put(u.getId(), u);
        }
        for (Long userId : realPeople.keySet()) {
            int n = ledger.applyResolved(userId, resolved);
            if (n > 0) {
                // **리포트 캐시를 깬다.** 안 깨면 사용자는 옛 숫자를 계속 본다 —
                // 소비 원장을 고친 모든 자리가 지키는 규칙이다.
                reports.deleteByUserId(userId);
                fixed += n;
            }
        }
        return fixed;
    }
}
