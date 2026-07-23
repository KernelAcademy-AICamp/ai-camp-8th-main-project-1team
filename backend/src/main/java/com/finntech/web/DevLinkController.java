package com.finntech.web;

import com.finntech.domain.AppUser;
import com.finntech.repository.AppUserRepository;
import com.finntech.service.MyDataLinkService;
import com.finntech.service.MyDataLinkService.LinkResult;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 개발/시연 전용 — 생성 마이데이터(11M, §13-11)의 SERVICE 사용자 하나를 백엔드에 '링크'해
 * 엔드투엔드(마이데이터 서빙 → 엔진 → ML 판정, W8)가 실제로 동작함을 검증한다.
 *
 * <p><b>왜 별도 링크가 필요한가.</b> 정상 온보딩은 본인인증({@link com.finntech.service.AuthService})으로
 * {@code Ci.of(이름·주민·전화)}를 계산해 CI를 만든다. 그러나 생성 파이프라인의 사용자 CI는
 * {@code GenSeed.ci}(=SHA-256("gen:seed:idx"))로 {@code Ci.of}와 호환되지 않아 정상 {@code /verify}로는
 * 매칭할 수 없다. 이 컨트롤러는 생성 CI를 직접 주입해 <b>링크 단계만 재현</b>한다(시연 목적).
 *
 * <p>{@code finntech.dev.seed-enabled=true}일 때만 빈으로 등록된다(운영 프로파일에서는 꺼진다,
 * {@link DevSeedController} 패턴 동일).
 */
@RestController
@RequestMapping("/api/dev")
@ConditionalOnProperty(name = "finntech.dev.seed-enabled", havingValue = "true")
public class DevLinkController {

    private final AppUserRepository userRepository;
    private final MyDataLinkService myDataLinkService;

    public DevLinkController(AppUserRepository userRepository, MyDataLinkService myDataLinkService) {
        this.userRepository = userRepository;
        this.myDataLinkService = myDataLinkService;
    }

    public record LinkSyntheticRequest(String ci, List<Long> companyIds, String nickname) {}

    /**
     * 생성 CI로 AppUser를 확보(가상 인증 우회, CI 직접 주입)하고 카드사를 연결해
     * 마이데이터 결제를 UserPayment/Consumption(MYDATA)로 적재한다.
     * CI에서 유도한 닉네임으로 재실행 시 같은 사용자를 재사용(재링크는 멱등)한다.
     *
     * <pre>curl -XPOST localhost:8090/api/dev/link-synthetic -H 'Content-Type: application/json' \
     *   -d '{"ci":"010ac641...","companyIds":[9001]}'</pre>
     */
    @PostMapping("/link-synthetic")
    public Map<String, Object> linkSynthetic(@RequestBody LinkSyntheticRequest req) {
        if (req.ci() == null || req.ci().isBlank()) {
            throw new IllegalArgumentException("ci is required");
        }
        String nickname = (req.nickname() == null || req.nickname().isBlank())
                ? "demo-" + req.ci().substring(0, 12) : req.nickname();

        // 닉네임(=CI 유도) 기준 재사용 — 같은 CI 재실행이면 새 사용자를 만들지 않는다.
        AppUser user = userRepository.findByNickname(nickname).orElseGet(
                () -> new AppUser(nickname, BigDecimal.valueOf(3_700_000), BigDecimal.valueOf(10_000_000), 12));
        user.setCi(req.ci());
        user.setConsentGiven(true);
        user = userRepository.save(user);

        // companyIds 미지정이면 전 카드사 연동(생성 데이터는 카드가 7개 카드사로 분산돼 있음, §13-11).
        List<Long> companyIds = (req.companyIds() == null || req.companyIds().isEmpty())
                ? myDataLinkService.companies().stream().map(c -> c.id()).toList()
                : req.companyIds();
        LinkResult result = myDataLinkService.linkCardCompanies(user.getId(), companyIds);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", user.getId());
        body.put("ci", req.ci());
        body.put("nickname", nickname);
        body.put("companyIds", companyIds);
        body.put("cardCount", result.cardCount());
        body.put("paymentCount", result.paymentCount());
        return body;
    }
}
