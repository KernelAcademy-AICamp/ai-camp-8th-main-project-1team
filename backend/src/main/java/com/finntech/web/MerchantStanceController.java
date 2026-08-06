package com.finntech.web;

import com.finntech.domain.UserMerchantStance;
import com.finntech.repository.UserMerchantStanceRepository;
import jakarta.validation.constraints.NotNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 가맹점 판정 성향 관리 (마이 &gt; 낭비 판정 관리).
 *
 * <p>온보딩에서 "이건 낭비가 아니다"를 누르면 그 가맹점의 판정이 느슨해진다. 그런데 그 판단이
 * <b>바뀔 수 있다</b> — 한동안 필수였던 지출이 다시 낭비가 되기도 한다. 되돌릴 자리가 없으면
 * 한 번 새어나간 지출이 영영 안 잡히고, 사용자는 자기가 무엇을 빼 뒀는지도 모른다.
 *
 * <p>그래서 <b>목록으로 보여 주고 되돌릴 수 있게</b> 한다. 어디를 얼마나 느슨하게 보고 있는지가
 * 사용자에게 보이는 것 자체가 이 기능의 절반이다.
 */
@RestController
@RequestMapping("/api/merchant-stance")
public class MerchantStanceController {

    private final UserMerchantStanceRepository repository;
    private final Clock clock;
    private final int toLenient;
    private final int toExcluded;

    public MerchantStanceController(UserMerchantStanceRepository repository, Clock clock,
                                    @Value("${finntech.ml.stance-to-lenient:1}") int toLenient,
                                    @Value("${finntech.ml.stance-to-excluded:3}") int toExcluded) {
        this.repository = repository;
        this.clock = clock;
        this.toLenient = toLenient;
        this.toExcluded = toExcluded;
    }

    /** 이 사용자가 느슨하게 보고 있는 가맹점들. NORMAL은 뺀다 — 아무것도 안 한 곳까지 보여줄 이유가 없다. */
    @GetMapping
    public Map<String, Object> list(@RequestParam Long userId) {
        List<Map<String, Object>> items = repository.findByUserId(userId).stream()
                .filter(s -> s.getStance() != UserMerchantStance.Stance.NORMAL)
                .sorted(Comparator.comparing(UserMerchantStance::getUpdatedAt).reversed())
                .map(s -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("businessNumber", s.getBusinessNumber());
                    m.put("merchantName", s.getMerchantName());
                    m.put("stance", s.getStance().name());
                    m.put("keptCount", s.getKeptCount());
                    m.put("updatedAt", s.getUpdatedAt());
                    return m;
                })
                .toList();
        return Map.of("userId", userId, "items", items);
    }

    /**
     * "역시 낭비였다" — 한 단계 되돌린다.
     *
     * <p>0으로 지우지 않는 이유는 {@link UserMerchantStance#notKept}에 적어 두었다.
     * 쌓아 온 판단을 통째로 버리면 사용자가 다시 처음부터 골라야 한다.
     */
    @PostMapping("/{businessNumber}/revert")
    @Transactional
    public Map<String, Object> revert(@RequestParam Long userId, @PathVariable String businessNumber) {
        UserMerchantStance s = repository.findByUserIdAndBusinessNumber(userId, businessNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "그 가맹점 설정이 없어요"));
        s.notKept(toLenient, toExcluded);
        s.setUpdatedAt(LocalDateTime.now(clock));
        repository.save(s);
        return Map.of("businessNumber", businessNumber, "stance", s.getStance().name(),
                "keptCount", s.getKeptCount());
    }

    /**
     * "이건 줄일 지출이 아니에요" — 온보딩에서 반복 지출을 짚어 물었을 때의 답(개편안 `sheet-ktx`).
     *
     * <p>사다리(느슨 → 제외)를 건너뛴다. 사다리는 "세 번 뺐으니 필수인 듯하다"는 <b>추정</b>인데,
     * 여기서는 사용자가 통근이라고 <b>말했다</b>. 말한 것을 세 번 더 말하라고 할 이유가 없다.
     */
    @PostMapping("/{businessNumber}/exclude")
    @Transactional
    public Map<String, Object> exclude(@RequestParam Long userId, @PathVariable String businessNumber,
                                       @RequestParam(required = false) String merchantName) {
        LocalDateTime now = LocalDateTime.now(clock);
        UserMerchantStance s = repository.findByUserIdAndBusinessNumber(userId, businessNumber)
                .orElseGet(() -> new UserMerchantStance(userId, businessNumber, merchantName, now));
        if (merchantName != null && !merchantName.isBlank()) s.setMerchantName(merchantName);
        s.excludedByUser(toExcluded, now);
        repository.save(s);
        return Map.of("businessNumber", businessNumber, "stance", s.getStance().name(),
                "keptCount", s.getKeptCount());
    }

    /** 한 가맹점 설정을 통째로 지운다 — 다음부터 전역 임계로 돌아간다. */
    @DeleteMapping("/{businessNumber}")
    @Transactional
    public Map<String, Object> clear(@RequestParam Long userId, @PathVariable String businessNumber) {
        repository.findByUserIdAndBusinessNumber(userId, businessNumber).ifPresent(repository::delete);
        return Map.of("businessNumber", businessNumber, "stance", "NORMAL");
    }

    public record RevertRequest(@NotNull String businessNumber) {}
}
