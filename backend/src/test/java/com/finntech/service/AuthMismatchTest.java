package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.repository.AppUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 본인인증이 <b>어느 항목이 틀렸는지</b> 정확히 가려내는지 본다.
 *
 * <p>CI는 해시라 "안 맞는다"까지만 알려준다. 그래서 제공자의 조회 결과(번호 등록 여부·명의자와의
 * 이름/주민번호 일치·그 신원의 존재)를 조합해 사유를 고르는데, <b>조합이 8가지</b>라
 * 하나씩 짚지 않으면 어느 갈래가 잘못 묶였는지 드러나지 않는다.
 */
class AuthMismatchTest {

    private static final String NAME = "김우진";
    private static final String SOCIAL = "0309303";
    private static final String PHONE = "01039136360";      // 3913 → LG U+ 대역

    /** exists·phoneTaken·phoneNameOk·phoneSocialOk·personFound 를 주면 그 사유를 돌려준다. */
    private String reasonOf(boolean exists, boolean phoneTaken, boolean nameOk,
                            boolean socialOk, boolean personFound) {
        AppUserRepository users = mock(AppUserRepository.class);
        MyDataClient client = mock(MyDataClient.class);
        AppUser u = new AppUser("t", BigDecimal.ONE, BigDecimal.ONE, 12);
        when(users.findById(anyLong())).thenReturn(Optional.of(u));
        when(users.save(any())).thenReturn(u);
        when(client.matchIdentity(any(), any(), any())).thenReturn(
                new MyDataClient.IdentityMatch(exists, phoneTaken, nameOk, socialOk, personFound));
        return new AuthService(users, client)
                .verifyAssumed(1L, NAME, SOCIAL, PHONE, "LG U+").reason();
    }

    @Test
    @DisplayName("셋 다 맞으면 통과한다")
    void 통과() {
        assertThat(reasonOf(true, true, true, true, true)).isEqualTo("OK");
    }

    @Test
    @DisplayName("번호 명의자와 이름만 다르면 이름 문제로 본다")
    void 이름만_다름() {
        assertThat(reasonOf(false, true, false, true, false)).isEqualTo("NAME_MISMATCH");
    }

    @Test
    @DisplayName("번호 명의자와 주민번호만 다르면 주민번호 문제로 본다")
    void 주민번호만_다름() {
        assertThat(reasonOf(false, true, true, false, false)).isEqualTo("SOCIAL_MISMATCH");
    }

    @Test
    @DisplayName("이름·주민번호가 둘 다 다르고 그 신원도 없으면 둘 다 문제로 본다")
    void 이름과_주민번호_모두_다름() {
        assertThat(reasonOf(false, true, false, false, false))
                .isEqualTo("NAME_AND_SOCIAL_MISMATCH");
    }

    @Test
    @DisplayName("이름·주민번호는 실재하는데 그 번호가 남의 명의면 '남의 번호'로 본다")
    void 남의_번호() {
        assertThat(reasonOf(false, true, false, false, true)).isEqualTo("PHONE_OWNED_BY_OTHER");
    }

    @Test
    @DisplayName("이름·주민번호는 실재하는데 번호가 등록돼 있지 않으면 번호 문제로 본다")
    void 번호만_다름() {
        assertThat(reasonOf(false, false, false, false, true)).isEqualTo("PHONE_MISMATCH");
    }

    @Test
    @DisplayName("어느 조합으로도 찾을 수 없으면 못 찾음이다")
    void 아무것도_없음() {
        assertThat(reasonOf(false, false, false, false, false)).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("국번이 미배정이면 조회조차 하지 않는다")
    void 미배정_국번() {
        AppUserRepository users = mock(AppUserRepository.class);
        MyDataClient client = mock(MyDataClient.class);
        AppUser u = new AppUser("t", BigDecimal.ONE, BigDecimal.ONE, 12);
        when(users.findById(anyLong())).thenReturn(Optional.of(u));
        var r = new AuthService(users, client)
                .verifyAssumed(1L, NAME, SOCIAL, "01070685400", "SKT");   // 7068 → 미배정
        assertThat(r.reason()).isEqualTo("UNASSIGNED_EXCHANGE");
        assertThat(r.verified()).isFalse();
    }

    @Test
    @DisplayName("신원이 맞아도 통신사가 다르면 막고, 실제 대역을 알려준다")
    void 통신사_불일치() {
        AppUserRepository users = mock(AppUserRepository.class);
        MyDataClient client = mock(MyDataClient.class);
        AppUser u = new AppUser("t", BigDecimal.ONE, BigDecimal.ONE, 12);
        when(users.findById(anyLong())).thenReturn(Optional.of(u));
        when(users.save(any())).thenReturn(u);
        when(client.matchIdentity(any(), any(), any()))
                .thenReturn(new MyDataClient.IdentityMatch(true, true, true, true, true));
        var r = new AuthService(users, client).verifyAssumed(1L, NAME, SOCIAL, PHONE, "SKT");
        assertThat(r.reason()).isEqualTo("CARRIER_MISMATCH");
        assertThat(r.actualCarrier()).isEqualTo("LG U+");
    }
}
