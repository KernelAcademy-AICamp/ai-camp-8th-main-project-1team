package com.finntech.intake;

import com.finntech.audit.AuditService;
import com.finntech.crypto.FieldCrypto;
import com.finntech.repository.RealUserIntakeRepository;
import com.finntech.service.MyDataClient;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * <b>남의 번호로는 신청을 못 받는다.</b>
 *
 * <p>번호는 사람을 특정하는 열쇠다 — 본인인증이 그것으로 명의자를 찾는다. 그런데 신청 단계에서
 * 그 번호가 <b>이미 남의 것인지</b>를 한 번도 안 물었다. 신청자가 자기 번호를 다른 실사용자의
 * 번호로 잘못 적자 한 번호에 두 사람이 붙었고, 조회가 "결과가 둘"로 터져 그 번호를 쓰는
 * <b>두 사람 모두</b> 본인인증이 500 이 됐다(2026-08-20 운영).
 *
 * <p>막는 자리가 둘이고 성격이 다르다 — 적재 직전의 {@code ensurePerson} 이 <b>불변식</b>이고,
 * 여기는 <b>친절</b>이다. 승인까지 가서 걸리면 신청자는 며칠 뒤 "반려"만 듣고 무엇이 틀렸는지
 * 모른다. 여기서 물으면 그 자리에서 고칠 수 있다. 성격이 다르므로 <b>fail open</b> 도 여기만이다.
 */
class IntakePhoneGateTest {

    private static final String NAME = "김신청";
    private static final String SOCIAL7 = "9001011";
    private static final String PHONE = "010-4444-5555";
    /** 한 줄짜리 정상 명세서 — 이 시험이 보는 것은 번호 관문뿐이라 최소한만 둔다. */
    private static final String CSV = "2026-07-01,가게A,10000\n";

    private RealUserIntakeRepository repository;
    private MyDataClient myDataClient;
    private IntakeService service;

    @BeforeEach
    void setUp() {
        repository = mock(RealUserIntakeRepository.class);
        myDataClient = mock(MyDataClient.class);
        FieldCrypto crypto = mock(FieldCrypto.class);
        // 암호화는 이 시험의 관심이 아니다 — 바이트로만 바꿔 흐름을 통과시킨다.
        when(crypto.encrypt(anyString())).thenAnswer(call ->
                ((String) call.getArgument(0)).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        AuditService audit = mock(AuditService.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-20T03:00:00Z"), ZoneId.of("Asia/Seoul"));

        service = new IntakeService(repository, crypto, myDataClient, audit, clock,
                10000, 5_242_880, 10, 7);
    }

    private IntakeService.Submission submission() {
        return new IntakeService.Submission(NAME, SOCIAL7, PHONE,
                List.of(new IntakeService.CardSubmission(null, "내 카드", CSV)), true);
    }

    private HttpServletRequest request() {
        MockHttpServletRequest req = new MockHttpServletRequest();
        req.setRemoteAddr("203.0.113.9");
        return req;
    }

    /** 그 번호에 등록된 사람이 있는데 이름·주민번호가 다르다 = 남의 번호다. */
    private void 그_번호는_남의_것이다() {
        when(myDataClient.matchIdentity(anyString(), anyString(), anyString()))
                .thenReturn(new MyDataClient.IdentityMatch(false, true, false, false, false));
    }

    /** 그 번호의 명의자가 본인이다 = 재신청이다. */
    private void 그_번호는_본인_것이다() {
        when(myDataClient.matchIdentity(anyString(), anyString(), anyString()))
                .thenReturn(new MyDataClient.IdentityMatch(true, true, true, true, true));
    }

    @Test
    @DisplayName("남의 번호로 신청하면 그 자리에서 막힌다")
    void 남의_번호는_접수되지_않는다() {
        그_번호는_남의_것이다();

        assertThatThrownBy(() -> service.submit(submission(), request()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("이미 다른 분 명의")
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.CONFLICT);

        // **접수 자체가 안 되어야 한다.** 저장해 놓고 승인 때 반려하면 그 사이 내내
        // 남의 명세서가 대기열에 남는다.
        org.mockito.Mockito.verify(repository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    @DisplayName("본인의 재신청은 막지 않는다 — 두 번째 카드사 명세서를 낼 수 있어야 한다")
    void 본인_재신청은_통과한다() {
        그_번호는_본인_것이다();

        IntakeService.SubmitResult result = service.submit(submission(), request());

        assertThat(result.ticket()).isNotBlank();
        assertThat(result.accepted()).isEqualTo(1);
    }

    @Test
    @DisplayName("아무도 안 쓰는 번호는 당연히 통과한다")
    void 처음_보는_번호는_통과한다() {
        when(myDataClient.matchIdentity(anyString(), anyString(), anyString()))
                .thenReturn(new MyDataClient.IdentityMatch(false, false, false, false, false));

        assertThat(service.submit(submission(), request()).ticket()).isNotBlank();
    }

    /**
     * <b>제공자가 죽었다고 정상 신청자를 돌려보내지 않는다.</b> 여기는 친절이지 관문이 아니다 —
     * 진짜 관문은 적재 직전의 {@code ensurePerson} 이고 그쪽은 막힌 채로 죽는다.
     */
    @Test
    @DisplayName("제공자가 안 뜨면 통과시킨다 — 관문은 적재 직전에 또 있다")
    void 제공자_장애면_통과시킨다() {
        when(myDataClient.matchIdentity(anyString(), anyString(), anyString()))
                .thenThrow(new IllegalStateException("제공자 접속 실패"));

        assertThat(service.submit(submission(), request()).ticket()).isNotBlank();
    }
}
