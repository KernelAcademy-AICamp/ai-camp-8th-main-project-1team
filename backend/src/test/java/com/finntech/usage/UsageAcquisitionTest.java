package com.finntech.usage;

import com.finntech.domain.UsageSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 유입 경로·국가를 정하는 순수 함수.
 *
 * <p>{@link UsageEventService#hostOf} 가 <b>호스트만</b> 남기는 것이 가장 중요하다 —
 * 남의 사이트 주소에는 검색어가 질의로 실려 오는 일이 흔하고, 그것을 그대로 저장하면
 * "행태정보"가 아니라 "남이 무엇을 검색했는가"를 담게 된다.
 */
class UsageAcquisitionTest {

    @Test
    @DisplayName("참조 주소에서 호스트만 남고 경로·질의는 버려진다")
    void 호스트만_남긴다() {
        assertThat(UsageEventService.hostOf("https://search.naver.com/search.naver?query=신용카드"))
                .isEqualTo("search.naver.com");
        assertThat(UsageEventService.hostOf("http://blog.example.com/a/b?utm=1#x"))
                .isEqualTo("blog.example.com");
        assertThat(UsageEventService.hostOf(null)).isNull();
        assertThat(UsageEventService.hostOf("")).isNull();
        assertThat(UsageEventService.hostOf("주소가 아님")).isNull();
    }

    @Test
    @DisplayName("검색·소셜은 호스트의 등록 이름으로 가른다")
    void 채널을_호스트로_가른다() {
        assertThat(UsageEventService.channelOf(null, null, "www.google.co.kr"))
                .isEqualTo(UsageSession.Channel.ORGANIC);
        assertThat(UsageEventService.channelOf(null, null, "m.search.naver.com"))
                .isEqualTo(UsageSession.Channel.ORGANIC);
        assertThat(UsageEventService.channelOf(null, null, "www.instagram.com"))
                .isEqualTo(UsageSession.Channel.SOCIAL);
        assertThat(UsageEventService.channelOf(null, null, "blog.example.com"))
                .isEqualTo(UsageSession.Channel.REFERRAL);
    }

    @Test
    @DisplayName("참조도 utm 도 없으면 직접 유입이다")
    void 아무것도_없으면_직접() {
        assertThat(UsageEventService.channelOf(null, null, null))
                .isEqualTo(UsageSession.Channel.DIRECT);
    }

    @Test
    @DisplayName("utm_medium 이 있으면 그 말을 먼저 믿는다 — 링크를 만든 쪽이 밝힌 것이다")
    void utm이_호스트보다_세다() {
        assertThat(UsageEventService.channelOf("naver", "cpc", "www.google.com"))
                .isEqualTo(UsageSession.Channel.REFERRAL);
        assertThat(UsageEventService.channelOf("naver", "organic", "blog.example.com"))
                .isEqualTo(UsageSession.Channel.ORGANIC);
        assertThat(UsageEventService.channelOf("kakao", "social", null))
                .isEqualTo(UsageSession.Channel.SOCIAL);
    }

    @Test
    @DisplayName("국가는 브라우저 언어의 지역 부분에서 나온다 — 지역이 없으면 미상이다")
    void 국가는_언어에서_나온다() {
        assertThat(UsageEventService.countryOf("ko-KR")).isEqualTo("KR");
        assertThat(UsageEventService.countryOf("en-US")).isEqualTo("US");
        assertThat(UsageEventService.countryOf("ko")).isNull();      // 지역 없음 — 억지로 KR 로 안 만든다
        assertThat(UsageEventService.countryOf(null)).isNull();
        assertThat(UsageEventService.countryOf("  ")).isNull();
    }
}
