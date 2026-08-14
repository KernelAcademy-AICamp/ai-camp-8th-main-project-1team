package com.finntech.mydata.web;

import com.finntech.mydata.service.RealPersonImportService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * <b>승인된 실사용자 신청을 적재한다</b> (설계서 Phase 3).
 *
 * <h2>{@code /admin/realdata} 와 무엇이 다른가</h2>
 *
 * <p>{@link RealPersonController} 는 <b>인증이 없고 본문의 신원을 그대로 믿는</b> 무방비
 * 관리자 입구다. 그래서 "빈을 아예 안 만든다 + 8082 미발행" 두 겹으로 막고 있고,
 * <b>그 입구는 계속 닫아둔다</b>({@code mydata.realdata.enabled=false}).
 *
 * <p>이 입구는 성질이 다르다 — 부르는 것은 <b>본체뿐</b>이고(공유 시크릿), 본체는
 * <b>admin 이 승인한 신청만</b> 여기로 보낸다. 위험한 것은 "쓰기"가 아니라
 * <b>"인증 없는 쓰기"</b> 였다.
 *
 * <p><b>{@code /self/**} 는 공유 시크릿 필터가 검사한다</b>({@code MyDataSharedSecretFilter}).
 * {@code /admin/realdata} 가 필터 밖이라 무방비인 빚도 그 자리에서 같이 갚았다.
 */
@RestController
@RequestMapping("/self")
@ConditionalOnProperty(name = "mydata.selfimport.enabled", havingValue = "true")
public class SelfImportController {

    private final RealPersonImportService service;

    public SelfImportController(RealPersonImportService service) {
        this.service = service;
    }

    /**
     * 카드 상품 카탈로그 — 신청 화면이 카드사·카드를 고르게 하려면 목록이 필요하다.
     *
     * <p>기준 데이터라 개인정보가 없다. 카드사 7곳 · 상품 115종.
     *
     * <p>조립은 서비스가 한다 — {@code open-in-view: false} 라 엔티티를 여기까지 들고 오면
     * 세션이 닫혀 {@code getCardCompany()} 에서 터진다.
     */
    @GetMapping("/card-catalog")
    public List<RealPersonImportService.CatalogRow> catalog() {
        return service.cardCatalog();
    }

    /** 카드 한 장 — 카드사·상품·표시명과 그 카드의 명세서(5칸 CSV). */
    public record CardBody(Long cardCode, String displayName, String csv) {}

    public record BatchBody(String name, String social7, String phone, List<CardBody> cards) {}

    /**
     * 신원을 만들고 <b>카드마다</b> 명세서를 넣는다.
     *
     * <p>신원 등록과 결제 적재를 한 호출로 묶는다 — 갈라 두면 "신원은 들어갔는데 결제는 안 들어간"
     * 중간 상태가 생기고, 그 상태의 사용자는 로그인은 되는데 아무것도 안 보인다.
     */
    @PostMapping("/import")
    public RealPersonImportService.BatchImportResult importBatch(@RequestBody BatchBody body) {
        requireIdentity(body);
        if (body.cards() == null || body.cards().isEmpty()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "카드가 하나도 없습니다");
        }
        List<RealPersonImportService.CardImport> imports = body.cards().stream()
                .map(card -> new RealPersonImportService.CardImport(
                        card.cardCode(), card.displayName(), card.csv()))
                .toList();
        return service.importBatch(body.name(), body.social7(), body.phone(), imports);
    }

    public record PurgeBody(String name, String social7, String phone) {}

    /**
     * 그 사람의 결제 전량 파기.
     *
     * <p>넣는 길과 <b>같은 무게</b>로 둔다 — 보유기간이 "동의 철회 시까지"라 파기는 기능이지
     * 편의가 아니다.
     */
    @DeleteMapping("/payments")
    public Map<String, Object> purge(@RequestBody PurgeBody body) {
        if (isBlank(body.name()) || isBlank(body.social7()) || isBlank(body.phone())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST, "신원 세 값이 모두 필요합니다");
        }
        return Map.of("deleted", service.purge(body.name(), body.social7(), body.phone()));
    }

    /**
     * 신원 세 값이 다 있어야 한다.
     *
     * <p>빈 값으로 CI 를 만들면 <b>모두가 같은 사람</b>이 된다 — 해시의 입력이 같아지기 때문이다.
     * 본체가 이미 검증하지만 여기서 또 본다: 제공자는 본체도 신뢰하지 않는다.
     */
    private static void requireIdentity(BatchBody body) {
        if (isBlank(body.name()) || isBlank(body.social7()) || isBlank(body.phone())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "이름·주민앞7·전화번호가 모두 있어야 CI 를 만들 수 있어요");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
