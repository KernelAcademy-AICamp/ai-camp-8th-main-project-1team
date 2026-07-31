package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.repository.AppUserRepository;
import com.finntech.util.Ci;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>본인인증은 어느 계정에 붙는가.</b> 답은 하나다 — <b>CI가 가리키는 계정</b>.
 *
 * <p>운영에서 실제로 깨졌다(2026-07-31). 로그아웃이 브라우저의 userId를 지우지 않아, 다음 사람이
 * 같은 브라우저에서 인증하자 <b>앞사람의 계정에 뒷사람의 CI가 덮어써졌다</b>. 결제는 뒷사람 것으로
 * 갈렸는데 챌린지·지킴이 원장은 앞사람 것이 남아, 홈이 남의 챌린지를 보여줬다. 앞사람의 신원은
 * app_user에서 사라졌다.
 *
 * <p>프론트(로그아웃이 userId를 버린다)만 고치면 같은 사고가 URL·수동 조작·옛 클라이언트로 되돌아온다.
 * <b>서버가 클라이언트의 userId를 믿지 않는 것</b>이 진짜 방어선이라 여기서 고정한다.
 */
class AuthAccountBindingTest {

    private static final String NAME = "김우진";
    private static final String SOCIAL = "0309303";
    private static final String PHONE = "01039136360";      // 3913 → LG U+ 대역
    private static final String CI = Ci.of(NAME, SOCIAL, PHONE);

    private static MyDataClient okClient() {
        MyDataClient client = mock(MyDataClient.class);
        when(client.matchIdentity(any(), any(), any())).thenReturn(
                new MyDataClient.IdentityMatch(true, true, true, true, true));
        return client;
    }

    /** id는 DB가 매기는 값이라 세터가 없다. 이 테스트는 '어느 계정'인지가 전부라 리플렉션으로 심는다. */
    private static AppUser user(Long id, String ci) {
        AppUser u = new AppUser("u" + id, BigDecimal.ONE, BigDecimal.ONE, 12);
        ReflectionTestUtils.setField(u, "id", id);
        if (ci != null) u.setCi(ci);
        return u;
    }

    @Test
    @DisplayName("그 신원의 계정이 이미 있으면 요청의 userId를 무시하고 그 계정을 돌려준다")
    void 신원의_계정으로_붙는다() {
        AppUserRepository users = mock(AppUserRepository.class);
        AppUser 앞사람 = user(1L, "앞사람CI");
        AppUser 본인 = user(4L, CI);
        when(users.findById(anyLong())).thenReturn(Optional.of(앞사람));
        when(users.findByCi(CI)).thenReturn(Optional.of(본인));
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AuthService.VerifyResult r = new AuthService(users, okClient())
                .verifyAssumed(1L, NAME, SOCIAL, PHONE, "LG U+");

        assertThat(r.verified()).isTrue();
        assertThat(r.userId()).as("인증된 신원의 계정으로 갈아타야 한다").isEqualTo(4L);
        assertThat(앞사람.getCi()).as("앞사람의 신원은 건드리지 않는다").isEqualTo("앞사람CI");
    }

    @Test
    @DisplayName("처음 보는 신원인데 그 계정에 이미 남이 있으면 덮어쓰지 않고 새 계정을 만든다")
    void 남의_계정을_덮어쓰지_않는다() {
        AppUserRepository users = mock(AppUserRepository.class);
        AppUser 앞사람 = user(1L, "앞사람CI");
        when(users.findById(anyLong())).thenReturn(Optional.of(앞사람));
        when(users.findByCi(CI)).thenReturn(Optional.empty());
        when(users.save(any())).thenAnswer(inv -> {
            AppUser saved = inv.getArgument(0);
            if (saved.getId() == null) ReflectionTestUtils.setField(saved, "id", 99L);
            return saved;
        });

        AuthService.VerifyResult r = new AuthService(users, okClient())
                .verifyAssumed(1L, NAME, SOCIAL, PHONE, "LG U+");

        assertThat(r.verified()).isTrue();
        assertThat(앞사람.getCi()).as("앞사람 계정이 통째로 다른 사람이 되면 안 된다").isEqualTo("앞사람CI");
        assertThat(r.userId()).as("새 계정으로 붙는다").isEqualTo(99L);
    }

    @Test
    @DisplayName("계정이 비어 있으면 그 계정에 그대로 붙는다 — 정상 가입 경로는 계정을 늘리지 않는다")
    void 빈_계정에는_그대로_붙는다() {
        AppUserRepository users = mock(AppUserRepository.class);
        AppUser 신규 = user(7L, null);
        when(users.findById(anyLong())).thenReturn(Optional.of(신규));
        when(users.findByCi(CI)).thenReturn(Optional.empty());
        when(users.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AuthService.VerifyResult r = new AuthService(users, okClient())
                .verifyAssumed(7L, NAME, SOCIAL, PHONE, "LG U+");

        assertThat(r.userId()).isEqualTo(7L);
        assertThat(신규.getCi()).isEqualTo(CI);
    }

    @Test
    @DisplayName("인증에 실패하면 어느 계정에도 쓰지 않는다")
    void 실패하면_저장하지_않는다() {
        AppUserRepository users = mock(AppUserRepository.class);
        AppUser 앞사람 = user(1L, "앞사람CI");
        MyDataClient client = mock(MyDataClient.class);
        when(client.matchIdentity(any(), any(), any())).thenReturn(
                new MyDataClient.IdentityMatch(false, false, false, false, false));
        when(users.findById(anyLong())).thenReturn(Optional.of(앞사람));

        AuthService.VerifyResult r = new AuthService(users, client)
                .verifyAssumed(1L, NAME, SOCIAL, PHONE, "LG U+");

        assertThat(r.verified()).isFalse();
        assertThat(r.userId()).as("계정을 고르지 않았다").isNull();
        verify(users, never()).save(any());
        assertThat(앞사람.getCi()).isEqualTo("앞사람CI");
    }
}
