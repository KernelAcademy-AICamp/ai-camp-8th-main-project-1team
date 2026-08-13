package com.finntech.service;

import com.finntech.domain.MerchantCategory;
import com.finntech.domain.UserPayment;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.MerchantCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>답 없는 가맹점을 영원히 다시 묻던 자리.</b>
 *
 * <p>운영 로그가 {@code 대상 33, 물어본 곳 24, 분류된 가맹점 0} 을 <b>2분마다 끝없이</b>
 * 반복했다 — 하루 약 7,000회가 남의 서버로 헛나갔다(2026-08-13 실측).
 *
 * <p>원인은 시도 이력 행이 "아직 할 일 남음"으로 판정된 것이다. 시도를 적으면서도 그 기록을
 * 읽지 않았으니 <b>기록은 있고 효과는 없었다.</b> 클래스 주석은 "다시 묻지 않는다"고 적혀
 * 있었다 — 문서와 동작이 갈라져 있었다.
 */
@SpringBootTest
@ActiveProfiles("test")
class MerchantLookupBackoffTest {

    private static final String BIZ = "1234567890";
    private static final String NAME = "백오프시험가게";

    @Autowired MerchantCategoryService service;
    @Autowired MerchantCategoryRepository repository;
    @Autowired java.time.Clock clock;

    @BeforeEach
    void clean() {
        repository.findByBusinessNumberAndMerchantName(BIZ, NAME).ifPresent(repository::delete);
    }

    /** 실사용자 결제라야 시도 이력 행이 만들어진다(더미는 사전에 못 들어온다). */
    private UserPayment realPayment() {
        return new UserPayment("1:real-backoff", 1L, "card", 1L,
                LocalDateTime.now(clock).minusDays(1), "5814",
                IndustryCategoryMapper.UNCLASSIFIED, 10_000, NAME, BIZ);
    }

    private MerchantCategory attemptedNow(int attempts) {
        MerchantCategory row = service.attemptRow(realPayment()).orElseThrow();
        for (int i = 0; i < attempts; i++) row.noteLookup(null, LocalDateTime.now(clock));
        return repository.save(row);
    }

    @Test
    @DisplayName("처음 보는 가맹점은 물어본다")
    void asksAboutNewMerchants() {
        assertThat(service.needsRegistryLookup(BIZ, NAME)).isTrue();
    }

    @Test
    @DisplayName("방금 물어봤으면 다시 묻지 않는다 — 여기가 7,000회를 만들던 자리")
    void doesNotReaskImmediately() {
        attemptedNow(1);
        assertThat(service.needsRegistryLookup(BIZ, NAME))
                .as("1회 시도 뒤 한 시간은 쉰다").isFalse();
    }

    @Test
    @DisplayName("시간이 지나면 다시 묻는다 — 영구 차단이 아니다")
    void asksAgainAfterTheWindow() {
        MerchantCategory row = attemptedNow(1);
        // 한 시간 지난 것으로 되돌려 놓는다.
        row.noteLookup(null, LocalDateTime.now(clock).minusHours(2));
        repository.save(row);

        assertThat(service.needsRegistryLookup(BIZ, NAME))
                .as("지금 답이 없다고 앞으로도 없는 것은 아니다").isTrue();
    }

    @Test
    @DisplayName("실패가 쌓이면 간격이 벌어지되 하루에서 멈춘다")
    void backsOffButNeverForever() {
        MerchantCategory row = attemptedNow(1);
        // 열 번 실패한 것으로 만들고, 마지막 시도를 25시간 전으로.
        for (int i = 0; i < 10; i++) row.noteLookup(null, LocalDateTime.now(clock).minusHours(25));
        repository.save(row);

        assertThat(service.needsRegistryLookup(BIZ, NAME))
                .as("아무리 실패해도 하루에 한 번은 다시 묻는다").isTrue();
    }

    @Test
    @DisplayName("확정된 가맹점은 백오프와 무관하게 더 물을 일이 없다")
    void confirmedMerchantsAreDone() {
        MerchantCategory row = attemptedNow(1);
        row.reclassify("식비", MerchantCategory.Source.USER_CONFIRMED, null, null);
        repository.save(row);

        assertThat(service.needsRegistryLookup(BIZ, NAME)).isFalse();
    }
}
