package com.finntech.service;

import com.finntech.guardian.GuardianService;
import com.finntech.repository.UserCardCompanyRepository;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

/**
 * 자동 동기화 배치의 계약 — <b>한 사용자의 실패가 배치를 멈추지 않는다</b>가 핵심이다.
 * 마이데이터 서버는 외부이고, 동의 철회·본인인증 만료 같은 사용자별 사유로도 흔히 던진다.
 * 여기서 예외가 새면 그 뒤 사용자들은 며칠이고 동기화되지 않은 채 방치된다.
 */
class MyDataSyncSchedulerTest {

    private MyDataSyncScheduler scheduler(UserCardCompanyRepository repo,
                                          MyDataLinkService link, GuardianService guardian) {
        return new MyDataSyncScheduler(repo, link, guardian);
    }

    @Test
    void 한_사용자가_실패해도_나머지는_동기화된다() {
        UserCardCompanyRepository repo = mock(UserCardCompanyRepository.class);
        MyDataLinkService link = mock(MyDataLinkService.class);
        GuardianService guardian = mock(GuardianService.class);

        when(repo.findDistinctUserIds()).thenReturn(List.of(1L, 2L, 3L));
        when(link.renew(1L)).thenReturn(new MyDataLinkService.SyncResult(2));
        when(link.renew(2L)).thenThrow(new IllegalStateException("본인인증(가상 CI)이 먼저 필요합니다"));
        when(link.renew(3L)).thenReturn(new MyDataLinkService.SyncResult(1));
        when(guardian.syncFromMyData(anyLong())).thenReturn(0);

        scheduler(repo, link, guardian).syncLinkedUsers();

        verify(link).renew(1L);
        verify(link).renew(2L);
        verify(link).renew(3L);          // 2번에서 멈추지 않았다
        verify(guardian).syncFromMyData(1L);
        verify(guardian, never()).syncFromMyData(2L); // 실패한 사용자는 원장까지 가지 않는다
        verify(guardian).syncFromMyData(3L);
    }

    @Test
    void 새_결제가_없어도_지킴이_원장은_한번_더_맞춰본다() {
        UserCardCompanyRepository repo = mock(UserCardCompanyRepository.class);
        MyDataLinkService link = mock(MyDataLinkService.class);
        GuardianService guardian = mock(GuardianService.class);

        when(repo.findDistinctUserIds()).thenReturn(List.of(7L));
        when(link.renew(7L)).thenReturn(new MyDataLinkService.SyncResult(0));
        when(guardian.syncFromMyData(7L)).thenReturn(0);

        scheduler(repo, link, guardian).syncLinkedUsers();

        // 앞선 회차에서 ①만 성공하고 ②가 실패했을 수 있다. 0건이라고 건너뛰면 그 구멍이 영영 안 메워진다.
        verify(guardian).syncFromMyData(7L);
    }

    @Test
    void 연결한_사용자가_없으면_아무것도_부르지_않는다() {
        UserCardCompanyRepository repo = mock(UserCardCompanyRepository.class);
        MyDataLinkService link = mock(MyDataLinkService.class);
        GuardianService guardian = mock(GuardianService.class);

        when(repo.findDistinctUserIds()).thenReturn(List.of());

        assertDoesNotThrow(() -> scheduler(repo, link, guardian).syncLinkedUsers());
        verifyNoInteractions(link, guardian);
    }
}
