package com.finntech.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>답이 안 오는 번호를 영원히 묻지 않는다</b>(V27).
 *
 * <p>V26 의 주소 백필은 "주소가 없는 행"을 회차마다 골라 물었는데, <b>조회처에 주소가 없는 것이
 * 정상인 번호</b>가 있다(롯데아울렛 서울역점은 주소칸이 비어 있고, 어떤 번호는 사업자 자체가 안
 * 나온다). 그런 행은 성공이 영영 안 오므로 실패를 세지 않으면 멎지 않는다 — 실측으로 7곳이
 * 5분마다, 하루 2,016회가 헛나갔다.
 */
class MerchantAddressMissTest {

    private static MerchantCategory row() {
        return new MerchantCategory("1048526046", "롯데아울렛 서울역점", "쇼핑",
                MerchantCategory.Source.USER_CSV, null, null);
    }

    @Test
    @DisplayName("없더라를 세 번 적으면 백필 대상에서 빠진다")
    void threeMissesStop() {
        MerchantCategory m = row();
        assertThat(m.getAddressMisses()).isZero();

        LocalDateTime at = LocalDateTime.of(2026, 8, 8, 9, 0);
        for (int i = 1; i <= MerchantCategory.GIVE_UP_AFTER; i++) {
            m.noteAddressMiss(at.plusMinutes(5L * i));
            assertThat(m.getAddressMisses()).isEqualTo(i);
        }
        // findMissingAddress 의 조건과 같은 술어 — 여기서 거짓이 되면 더 안 뽑힌다.
        assertThat(m.getAddressMisses() < MerchantCategory.GIVE_UP_AFTER).isFalse();
        assertThat(m.getLastAttemptAt()).isEqualTo(at.plusMinutes(15));
    }

    @Test
    @DisplayName("주소를 얻으면 실패 수와 무관하게 그것으로 끝난다")
    void addressWins() {
        MerchantCategory m = row();
        m.noteAddressMiss(LocalDateTime.of(2026, 8, 8, 9, 0));

        assertThat(m.noteAddress("서울특별시 중구 한강대로 405 (봉래동2가)")).isTrue();
        assertThat(m.getAddress()).startsWith("서울특별시");
        // 이미 적힌 주소는 덮이지 않는다 — 백필이 같은 행을 두 번 집어도 안전하다.
        assertThat(m.noteAddress("다른 주소")).isFalse();
    }

    @Test
    @DisplayName("빈 주소는 적지 않는다 — 그건 얻은 것이 아니다")
    void blankIsNotAnAddress() {
        MerchantCategory m = row();
        assertThat(m.noteAddress(null)).isFalse();
        assertThat(m.noteAddress("   ")).isFalse();
        assertThat(m.getAddress()).isNull();
    }
}
