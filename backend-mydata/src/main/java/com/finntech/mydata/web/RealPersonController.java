package com.finntech.mydata.web;

import com.finntech.mydata.service.RealPersonImportService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>실제 사람 한 명의 카드내역을 제공자에 넣는다</b> (2026-08-02).
 *
 * <p>여기 넣어야 정상 경로로 흐른다 — 본인인증(CI) → 카드사 연결 → 본체 조회.
 * 그리고 덤프({@code finntech_mydata})에 실려 <b>로컬·AWS·운영 MySQL 모두에</b> 간다.
 *
 * <p><b>기본은 꺼져 있다</b>({@code mydata.realdata.enabled}). 실제 개인정보를 받고
 * 지우는 길까지 있는 입구다. 8082는 외부에 발행되지 않지만(W7-2 격리) 그 격리는
 * <b>설정 실수 하나면 무너지는 단층 방어</b>라, 여기서도 빈 자체를 안 만든다.
 *
 * <p>{@code /bank/**} 밖이라 공유 시크릿 필터가 걸리지 않는다 — 그래서 더더욱 스위치가 필요하다.
 */
@RestController
@RequestMapping("/admin/realdata")
@ConditionalOnProperty(name = "mydata.realdata.enabled", havingValue = "true")
public class RealPersonController {

    private final RealPersonImportService service;

    public RealPersonController(RealPersonImportService service) {
        this.service = service;
    }

    /**
     * 신원 세 값을 손으로 검사한다 — 이 모듈에는 bean validation 의존성이 없다.
     * 빈 값으로 CI를 만들면 <b>모두가 같은 사람</b>이 되므로 여기서 막는다.
     */
    private static void requireIdentity(String name, String social7, String phone) {
        if (name == null || name.isBlank() || social7 == null || social7.isBlank()
                || phone == null || phone.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "이름·주민앞7·전화번호가 모두 있어야 CI를 만들 수 있어요");
        }
    }

    /**
     * @param name     실명. CI 산식에 들어간다(생성된 사람들과 같은 산식이라 본인인증이 찾아낸다).
     * @param social7  주민등록번호 앞 7자리. 뒷자리는 받지 않는다 — 쓸 데가 없다.
     * @param phone    휴대폰 번호. 하이픈은 있어도 된다(정규화한다).
     * @param cardCode 붙일 카드 상품. 비우면 카탈로그 첫 상품.
     */
    public record PersonRequest(String name, String social7,
                                String phone, Long cardCode) {}

    /** 신원을 만들거나 확인한다. 같은 신원이면 그 사람을 그대로 쓴다. */
    @PostMapping("/person")
    public Map<String, Object> person(@RequestBody PersonRequest req) {
        requireIdentity(req.name(), req.social7(), req.phone());
        var u = service.ensurePerson(req.name(), req.social7(), req.phone(), req.cardCode());
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ci", u.getId());
        m.put("dataSplit", u.getDataSplit());
        m.put("note", "dataSplit=SERVICE 라 ml/train.py 가 학습에서 자동으로 제외한다. 재학습은 필요 없다.");
        return m;
    }

    public record CsvRequest(String name, String social7,
                             String phone, Long cardCode, String csv) {}

    /**
     * 명세서 CSV 적재 — {@code 날짜,가맹점,금액[,업종코드][,사업자번호]}.
     *
     * <p>사업자번호는 확정 분류 사전({@code merchant_category})이 찾는 키다. 명세서에 있으면
     * 넣어야 사전이 붙는다. 뒤에 붙인 칸이라 기존 4칸 파일도 그대로 읽힌다.
     *
     * <p>못 읽은 줄은 <b>줄 번호와 사유를 달고</b> 돌아온다. 조용히 건너뛰면
     * "다 들어갔다"와 "절반만 들어갔다"가 화면에서 똑같아 보인다(tech_log §8-U).
     */
    @PostMapping("/payments/csv")
    public RealPersonImportService.ImportResult uploadCsv(@RequestBody CsvRequest req) {
        requireIdentity(req.name(), req.social7(), req.phone());
        return service.importCsv(req.name(), req.social7(), req.phone(), req.cardCode(), req.csv());
    }

    /** 이 사람의 결제 전량 파기. */
    @DeleteMapping("/payments")
    public Map<String, Object> purge(@RequestBody PersonRequest req) {
        requireIdentity(req.name(), req.social7(), req.phone());
        return Map.of("deleted", service.purge(req.name(), req.social7(), req.phone()));
    }
}
