package com.finntech.web;

import com.finntech.auth.AdminAuthService;
import com.finntech.auth.AuthFilter;
import com.finntech.domain.AdminAccount;
import com.finntech.domain.AppUser;
import com.finntech.repository.AppUserRepository;
import com.finntech.service.MyDataClient;
import com.finntech.service.PrivacyService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * <b>관리자 강제 파기는 CI 완전일치로만, 두 곳을 함께.</b>
 *
 * <p>열쇠를 CI 하나로 둔 것이 이 문의 설계다 — 이름·전화로 찾게 하면 <b>지우는 일 때문에
 * 관리자가 개인식별정보를 보게 된다.</b> 부분일치·검색·목록이 없어야 그 성질이 유지되므로,
 * 여기서 잠그는 것은 "64자가 아니면 아무것도 안 한다"이다.
 *
 * <p>그리고 <b>양쪽을 함께</b> 지운다. 사람의 자취는 본체(소비·지킴이·행태)와 제공자
 * (신원·카드·결제)에 나뉘어 있고, 한쪽만 지우면 파기가 아니다. 어느 한쪽에만 있는 사람도
 * 실제로 있어서(신청은 승인됐는데 앱은 안 쓴 사람) 한쪽이 비어도 나머지는 지워야 한다.
 */
class AdminUserPurgeTest {

    private static final String CI = "a".repeat(64);

    private AppUserRepository users;
    private PrivacyService privacy;
    private MyDataClient myDataClient;
    private AdminUserPurgeController controller;

    @BeforeEach
    void setUp() {
        users = mock(AppUserRepository.class);
        privacy = mock(PrivacyService.class);
        myDataClient = mock(MyDataClient.class);
        AdminAuthService adminAuth = mock(AdminAuthService.class);

        AdminAccount account = mock(AdminAccount.class);
        when(account.isMustChangePassword()).thenReturn(false);
        when(account.isTotpConfirmed()).thenReturn(true);
        when(account.getUsername()).thenReturn("운영자");
        when(adminAuth.find(anyLong())).thenReturn(Optional.of(account));

        when(users.findByCi(anyString())).thenReturn(Optional.empty());
        when(myDataClient.purgeUser(anyString()))
                .thenReturn(new MyDataClient.ProviderPurge(false, 0, 0, 0, 0, 0));

        controller = new AdminUserPurgeController(users, privacy, myDataClient, adminAuth,
                Clock.fixed(Instant.parse("2026-08-20T03:00:00Z"), ZoneId.of("Asia/Seoul")));
    }

    /** admin 쿠키를 통과한 요청 — {@code AuthFilter} 가 심어 두는 것과 같은 모양. */
    private MockHttpServletRequest asAdmin() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthFilter.ATTR_SUBJECT_ID, 1L);
        return request;
    }

    private AdminUserPurgeController.PurgeBody body(String ci) {
        return new AdminUserPurgeController.PurgeBody(ci, AdminUserPurgeController.CONFIRM);
    }

    // ── 열쇠 ────────────────────────────────────────────────────────────────

    @Test
    @DisplayName("CI 가 64자가 아니면 아무것도 안 한다")
    void 짧은_ci는_거절한다() {
        assertThatThrownBy(() -> controller.purge(body("abc123"), asAdmin()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.BAD_REQUEST);

        verify(users, never()).findByCi(anyString());
        verify(myDataClient, never()).purgeUser(anyString());
    }

    /**
     * <b>부분일치를 열지 않는다.</b> 앞자리만으로 지울 수 있으면 관리자가 값을 훑다가
     * 잘못 누를 수 있고, "이미 아는 사람만 지운다"는 성질이 무너진다.
     */
    @Test
    @DisplayName("앞자리만으로는 못 지운다 — 부분일치가 없다")
    void 앞자리만으로는_못_지운다() {
        assertThatThrownBy(() -> controller.purge(body(CI.substring(0, 40)), asAdmin()))
                .isInstanceOf(ResponseStatusException.class);
        verify(myDataClient, never()).purgeUser(anyString());
    }

    @Test
    @DisplayName("확인 문구가 없으면 진행하지 않는다 — 되돌릴 수 없는 일이다")
    void 확인문구가_없으면_안_한다() {
        var noConfirm = new AdminUserPurgeController.PurgeBody(CI, "네");

        assertThatThrownBy(() -> controller.purge(noConfirm, asAdmin()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining(AdminUserPurgeController.CONFIRM);
        verify(myDataClient, never()).purgeUser(anyString());
    }

    @Test
    @DisplayName("비밀번호를 안 바꿨거나 2단계 인증이 없으면 못 지운다")
    void 준비_안_된_계정은_못_지운다() {
        AdminAuthService weak = mock(AdminAuthService.class);
        AdminAccount fresh = mock(AdminAccount.class);
        when(fresh.isMustChangePassword()).thenReturn(true);
        when(weak.find(anyLong())).thenReturn(Optional.of(fresh));
        var guarded = new AdminUserPurgeController(users, privacy, myDataClient, weak,
                Clock.systemUTC());

        assertThatThrownBy(() -> guarded.purge(body(CI), asAdmin()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("로그인하지 않았으면 못 지운다")
    void 로그인_없이는_못_지운다() {
        assertThatThrownBy(() -> controller.purge(body(CI), new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // ── 두 곳을 함께 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("본체와 제공자를 함께 지운다")
    void 양쪽을_함께_지운다() {
        AppUser found = mock(AppUser.class);
        when(found.getId()).thenReturn(24L);
        when(users.findByCi(CI)).thenReturn(Optional.of(found));
        when(privacy.eraseUserData(anyLong(), any())).thenReturn(1042);
        when(myDataClient.purgeUser(CI))
                .thenReturn(new MyDataClient.ProviderPurge(true, 272, 0, 1, 0, 1));

        Map<String, Object> result = controller.purge(body(CI), asAdmin());

        verify(privacy, times(1)).eraseUserData(eq24(), any());
        verify(myDataClient, times(1)).purgeUser(CI);
        assertThat(result.get("found")).isEqualTo(true);
        assertThat(result.get("erasedConsumptions")).isEqualTo(1042);
    }

    /**
     * 신청은 승인됐는데 앱은 안 쓴 사람 — 제공자에만 있다. 오늘 손으로 SQL 을 두드린 것이
     * 정확히 이 경우였다(2026-08-20).
     */
    @Test
    @DisplayName("본체에 계정이 없어도 제공자는 지운다")
    void 제공자에만_있어도_지운다() {
        when(users.findByCi(CI)).thenReturn(Optional.empty());
        when(myDataClient.purgeUser(CI))
                .thenReturn(new MyDataClient.ProviderPurge(true, 272, 0, 1, 0, 1));

        Map<String, Object> result = controller.purge(body(CI), asAdmin());

        verify(privacy, never()).eraseUserData(anyLong(), any());
        verify(myDataClient, times(1)).purgeUser(CI);
        assertThat(result.get("found")).isEqualTo(true);
        assertThat(result.get("appUser")).isEqualTo("없음");
    }

    @Test
    @DisplayName("아무 데도 없으면 없다고 답한다 — 조용히 성공하지 않는다")
    void 없으면_없다고_한다() {
        Map<String, Object> result = controller.purge(body(CI), asAdmin());

        assertThat(result.get("found")).isEqualTo(false);
    }

    /** CI 를 통째로 되돌려 주면 화면·로그에 개인 열쇠가 그대로 남는다. */
    @Test
    @DisplayName("응답에 CI 를 통째로 싣지 않는다")
    void 응답에_ci를_통째로_안_싣는다() {
        Object echoed = controller.purge(body(CI), asAdmin()).get("ci");

        assertThat(String.valueOf(echoed)).isNotEqualTo(CI).hasSizeLessThan(20);
    }

    private static Long eq24() {
        return org.mockito.ArgumentMatchers.eq(24L);
    }
}
