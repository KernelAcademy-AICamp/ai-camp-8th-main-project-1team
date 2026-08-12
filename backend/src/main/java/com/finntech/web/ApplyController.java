package com.finntech.web;

import com.finntech.intake.ColumnMapperService;
import com.finntech.intake.IntakeService;
import com.finntech.service.MyDataClient;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 실사용자 신청 — <b>비로그인</b> 공개 경로 (설계서 Phase 3).
 *
 * <h2>왜 로그인이 없는가</h2>
 *
 * <p>순서 때문이다. 사용자가 로그인하려면 제공자에 신원이 있어야 하는데, 신원 등록이 승인을
 * 기다리면 <b>로그인을 못 해 업로드도 못 한다.</b> 그래서 로그인 전에 신원 3값과 카드별
 * 명세서를 한 번에 받고, 승인이 끝난 뒤 사용자가 <b>평소대로</b> 본인인증하면 통과한다.
 *
 * <h2>게이트</h2>
 *
 * <p>초대코드를 두지 않기로 했으므로 게이트는 <b>승인 하나뿐</b>이다. 대기열이 쓰레기로
 * 채워지는 것은 IP 당 하루 상한이 막는다({@code IntakeService}).
 *
 * <p>화면은 별도 번들({@code apply.html})이고 사용자 앱 어디에도 링크가 없다 —
 * 다만 그것은 소음 감소이지 방어가 아니다.
 */
@RestController
@RequestMapping("/api/apply")
@ConditionalOnProperty(name = "finntech.intake.enabled", havingValue = "true")
public class ApplyController {

    private final IntakeService intake;
    private final MyDataClient myDataClient;
    private final ColumnMapperService columnMapper;

    public ApplyController(IntakeService intake, MyDataClient myDataClient,
                           ColumnMapperService columnMapper) {
        this.intake = intake;
        this.myDataClient = myDataClient;
        this.columnMapper = columnMapper;
    }

    public record ColumnsBody(List<List<String>> rows) {}

    /**
     * <b>칸 이름만</b> 보내 어느 칸이 무엇인지 묻는다 — 별칭표가 실패했을 때만 화면이 부른다.
     *
     * <p>결제 자료는 오지 않는다. 브라우저가 머리글 후보만 골라 보내고, 서버가
     * {@link ColumnMapperService#isHeaderCandidate} 로 <b>한 번 더</b> 거른다 —
     * 값이 한 칸이라도 섞인 줄은 모델에 닿지 않는다.
     *
     * <p>못 찾으면 200 에 {@code found:false} 다. 오류가 아니라 <b>"모르겠다"</b>이고,
     * 화면은 종전대로 "칸을 못 찾았어요"를 보여 준다.
     */
    @PostMapping("/columns")
    public Map<String, Object> columns(@RequestBody ColumnsBody body, HttpServletRequest request) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (body.rows() == null || body.rows().isEmpty()) {
            payload.put("found", false);
            return payload;
        }
        String ip = com.finntech.auth.AuthTokenService.clientIp(request);
        return columnMapper.map(body.rows(), ip)
                .map(mapping -> {
                    Map<String, Object> found = ColumnMapperService.describe(mapping);
                    found.put("found", true);
                    return found;
                })
                .orElseGet(() -> {
                    payload.put("found", false);
                    return payload;
                });
    }

    /**
     * 카드 상품 카탈로그 — 신청 화면이 카드사·카드를 고르게 한다.
     *
     * <p>기준 데이터라 개인정보가 없다. 제공자에서 그대로 중계한다.
     */
    @GetMapping("/card-catalog")
    public List<Map<String, Object>> catalog() {
        try {
            return myDataClient.selfCardCatalog();
        } catch (RuntimeException exception) {
            // 제공자 쪽 스위치(mydata.selfimport.enabled)가 꺼져 있으면 404 가 돌아온다.
            // 그대로 500 을 내면 화면은 카드 목록이 빈 채로 멈추고 **사용자는 이유를 모른다** —
            // 고를 것이 없어 신청도 못 하는데 아무 말도 안 하는 상태다. 사유를 말해 준다.
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "지금은 신청을 받을 수 없어요. 잠시 후 다시 시도해 주세요.");
        }
    }

    public record CardBody(Long cardCode, String displayName, String csv) {}

    public record ApplyBody(String name, String social7, String phone,
                            List<CardBody> cards, Boolean consent) {}

    /**
     * 신청 접수.
     *
     * <p>브라우저에서 파싱·검증하지만 <b>여기가 권위</b>다 — 브라우저 코드는 사용자가 고칠 수 있다.
     * 원본 파일은 오지 않는다. 5칸으로 줄인 텍스트만 온다.
     */
    @PostMapping
    public Map<String, Object> apply(@RequestBody ApplyBody body, HttpServletRequest request) {
        if (body.cards() == null || body.cards().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "명세서를 한 장 이상 올려 주세요.");
        }
        List<IntakeService.CardSubmission> cards = body.cards().stream()
                .map(card -> new IntakeService.CardSubmission(
                        card.cardCode(), card.displayName(), card.csv()))
                .toList();
        IntakeService.SubmitResult result = intake.submit(
                new IntakeService.Submission(body.name(), body.social7(), body.phone(),
                        cards, Boolean.TRUE.equals(body.consent())),
                request);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ticket", result.ticket());
        payload.put("accepted", result.accepted());
        payload.put("rejected", result.rejected());
        payload.put("problems", result.problems());
        return payload;
    }

    /**
     * 접수증으로 상태를 본다.
     *
     * <p>접수증은 신원을 담지 않는 무작위 값이라, <b>그 번호를 아는 사람만</b> 자기 신청을 본다.
     * 계정이 아직 없으므로 이것이 유일한 조회 수단이다.
     *
     * <p>승인·반려가 끝나면 대기 행은 지워진다 — 그때는 "처리 완료"로만 답한다.
     * <b>실 개인정보를 상태 조회를 위해 남겨 두지 않는다.</b>
     */
    @GetMapping("/{ticket}")
    public Map<String, Object> status(@PathVariable String ticket) {
        return intake.byTicket(ticket)
                .map(found -> {
                    Map<String, Object> payload = new LinkedHashMap<>();
                    payload.put("status", found.getStatus().name());
                    payload.put("submittedAt", found.getSubmittedAt());
                    payload.put("cardCount", found.getCardCount());
                    payload.put("rowCount", found.getRowCount());
                    return payload;
                })
                .orElseGet(() -> Map.of("status", "DONE_OR_UNKNOWN",
                        "message", "처리가 끝났거나 없는 접수증입니다. 앱에서 본인인증을 해 보세요."));
    }
}
