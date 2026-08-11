package com.finntech.web;

import com.finntech.service.PrivacyService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 개인정보 처리방침 고지 + 보유기간 파기 배치 (문서 §5-3).
 *
 * <p>화면이 방침 문안을 하드코딩하지 않고 이 API를 읽는다 — 방침을 고칠 때
 * 문서·백엔드·화면이 따로 노는 것을 막기 위함이다.
 */
@RestController
@RequestMapping("/api/privacy")
public class PrivacyController {

    private final PrivacyService privacyService;
    private final Clock clock;

    public PrivacyController(PrivacyService privacyService, Clock clock) {
        this.privacyService = privacyService;
        this.clock = clock;
    }

    @GetMapping("/policy")
    public PrivacyService.PrivacyPolicy policy() {
        return privacyService.policy();
    }

    /** 이용약관 (정본: legal/terms-of-service.md). */
    @GetMapping("/terms")
    public PrivacyService.Terms terms() {
        return privacyService.terms();
    }

    /**
     * 동의 항목 하나가 펼치는 문서 (정본: legal/consent-*.md).
     *
     * <p>가입 화면의 동의 넷 중 셋이 여기를 읽는다 — 개인(신용)정보 수집·이용, 고유식별정보
     * 처리, 마케팅 수신. 예전에는 셋 다 개인정보 처리방침을 폈는데 그 방침에는 고유식별정보도
     * 마케팅도 안 나온다.
     */
    @GetMapping("/consent/{id}")
    public PrivacyService.PrivacyPolicy consent(@PathVariable String id) {
        return privacyService.consent(id);
    }

    /** 수동 파기 트리거 (시연·운영용). 스케줄러와 동일한 로직을 탄다. */
    @PostMapping("/purge")
    public PrivacyService.PurgeReport purge() {
        return privacyService.purgeExpired(LocalDateTime.now(clock));
    }

    /**
     * 매일 04:00 보유기간 초과분 자동 파기.
     * "기간 경과 시 자동 파기"라고 고지했으므로 실제로 도는 배치가 있어야 한다.
     */
    @Scheduled(cron = "${finntech.privacy.purge-cron:0 0 4 * * *}")
    public void scheduledPurge() {
        privacyService.purgeExpired(LocalDateTime.now(clock));
    }
}
