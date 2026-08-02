package com.finntech.web;

import com.finntech.service.RealDataService;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>실제 사람 한 명의 카드 사용내역 입력</b> (2026-08-02).
 *
 * <p>지금까지 이 앱이 판정한 것은 전부 <b>생성기가 만든 소비</b>다. "시간이 지날수록 낭비가
 * 줄어든다"는 효과도 생성 가정이라, 모델이 그걸 재현했다고 해서 효과를 <b>발견</b>한 것은 아니다.
 * 실제 사람의 소비를 한 번 통과시켜 봐야 그 구분을 말할 수 있다.
 *
 * <p><b>신원은 받지 않는다.</b> 계정은 닉네임만 갖고 이름·전화번호·주민번호가 없다 —
 * 마이데이터 연동과 달리 CI를 만들 이유가 없고, <b>안 받는 것이 가장 확실한 보호</b>다.
 * 소유자를 표시할 일이 생기면 그때 닉네임만 바꾸면 된다.
 *
 * <p><b>지우는 길을 같은 무게로 둔다</b>({@code DELETE}). 실제 개인정보를 넣는 길만 만들고
 * 빼는 길을 미루면, 미룬 그 상태가 기본값이 된다.
 *
 * <p><b>기본은 꺼져 있다.</b> 이 경로는 실제 개인정보를 받고 {@code DELETE}까지 있다.
 * 그리고 <b>nginx는 {@code /api/} 아래를 통째로 백엔드에 넘긴다</b> — 경로별 allowlist가 없다.
 * 즉 {@code /api/**} 에 컨트롤러를 하나 만들면 배포되는 순간 공개된다.
 *
 * <p>실제로 운영에서 {@code /api/dev/seed} 가 404인 것은 nginx가 막아서가 아니라
 * <b>속성 게이트로 빈이 없기 때문</b>이다(2026-08-02 확인). 그 선례를 그대로 따른다 —
 * 통제를 nginx 한 겹에 두면 설정 실수 하나로 무너진다(W7-2가 격리 뒤에 공유 시크릿을 둔 것과 같은 이유).
 * 쓸 때 명시적으로 켠다.
 */
@RestController
@RequestMapping("/api/realdata")
@ConditionalOnProperty(name = "finntech.realdata.enabled", havingValue = "true")
public class RealDataController {

    private final RealDataService service;

    public RealDataController(RealDataService service) {
        this.service = service;
    }

    /** 전용 계정을 확인·생성한다. 이미 있으면 그것을 준다 — 실데이터가 흩어지면 안 된다. */
    @PostMapping("/account")
    public Map<String, Object> account() {
        var user = service.account();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("userId", user.getId());
        m.put("nickname", user.getNickname());
        m.put("note", "이름·전화번호는 저장하지 않아요. 표시 이름이 필요하면 닉네임만 바꾸면 돼요.");
        return m;
    }

    public record CsvRequest(@NotNull String csv) {}

    /**
     * 명세서 CSV 일괄 적재 — {@code 날짜,가맹점,금액[,업종코드]}.
     *
     * <p>못 읽은 줄을 <b>조용히 버리지 않고 사유와 함께 돌려준다.</b> 몇 건이 왜 빠졌는지
     * 모르면 "다 들어갔다"와 "절반만 들어갔다"가 화면에서 똑같아 보인다(tech_log §8-U).
     */
    @PostMapping("/payments/csv")
    public RealDataService.ImportResult uploadCsv(@RequestBody CsvRequest req) {
        return service.importCsv(req.csv());
    }

    public record OneRequest(@NotNull LocalDate date, String merchant, long amount, String ksicCode) {}

    /** 한 건 직접 입력 — CSV가 못 읽은 줄을 손으로 채우는 자리. */
    @PostMapping("/payments")
    public Map<String, Object> addOne(@RequestBody OneRequest req) {
        Long userId = service.addOne(req.date(), req.merchant(), req.amount(), req.ksicCode());
        return Map.of("userId", userId, "accepted", 1);
    }

    /** 전량 파기. */
    @DeleteMapping("/payments")
    public Map<String, Object> purge() {
        return Map.of("deleted", service.purge());
    }
}
