package com.finntech.service;

import com.finntech.domain.MerchantCategoryVote;
import com.finntech.repository.MerchantCategoryVoteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 집계 규칙 — <b>단순 다수결, 동률이면 아무도 안 고른다</b>(V30).
 *
 * <p>여기 있는 것도 조용히 틀어지는 종류다. 표를 잘못 세도 예외는 안 나고 전역 분류만
 * 바뀌므로, 단정으로 잡지 않으면 누군가의 화면이 이유 없이 흔들린 뒤에야 알게 된다.
 */
class MerchantCategoryVoteServiceTest {

    private final List<MerchantCategoryVote> ballots = new ArrayList<>();
    private MerchantCategoryVoteService service;

    @BeforeEach
    void setUp() {
        ballots.clear();
        MerchantCategoryVoteRepository repo = mock(MerchantCategoryVoteRepository.class);
        when(repo.findByBusinessNumberAndMerchantNameAndUserId(anyString(), anyString(), anyLong()))
                .thenAnswer(inv -> ballots.stream()
                        .filter(v -> v.getBusinessNumber().equals(inv.getArgument(0))
                                && v.getMerchantName().equals(inv.getArgument(1))
                                && v.getUserId().equals(inv.getArgument(2)))
                        .findFirst());
        when(repo.findBallots(anyString(), anyString()))
                .thenAnswer(inv -> ballots.stream()
                        .filter(v -> v.getBusinessNumber().equals(inv.getArgument(0))
                                && v.getMerchantName().equals(inv.getArgument(1)))
                        .toList());
        when(repo.save(any(MerchantCategoryVote.class))).thenAnswer(inv -> {
            ballots.add(inv.getArgument(0));
            return inv.getArgument(0);
        });
        service = new MerchantCategoryVoteService(repo);
    }

    private java.util.Optional<String> cast(Long userId, String category2) {
        return service.castAndTally("0000000011", "배달의민족", userId, category2);
    }

    @Test
    @DisplayName("혼자면 그 사람이 다수다 — 처음 보는 가맹점이 사전에 못 들어가면 안 된다")
    void aSingleVoterDecides() {
        assertThat(cast(1L, "식비")).contains("식비");
    }

    @Test
    @DisplayName("동률이면 아무도 안 고른다 — 갈렸다는 것은 사람마다 다르게 본다는 뜻이다")
    void tiesDecideNothing() {
        assertThat(cast(1L, "식비")).contains("식비");
        assertThat(cast(2L, "쇼핑")).as("1:1 — 부르는 쪽이 사전을 안 건드린다").isEmpty();
    }

    @Test
    @DisplayName("다수가 갈리면 다수를 따른다")
    void majorityWins() {
        cast(1L, "식비");
        cast(2L, "쇼핑");
        assertThat(cast(3L, "쇼핑")).as("2:1").contains("쇼핑");
        assertThat(cast(4L, "식비")).as("2:2 — 다시 동률").isEmpty();
        assertThat(cast(5L, "식비")).as("3:2").contains("식비");
    }

    @Test
    @DisplayName("표는 사람당 하나다 — 다시 확정하면 늘지 않고 바뀐다")
    void oneBallotPerPerson() {
        cast(1L, "식비");
        cast(1L, "쇼핑");
        cast(1L, "생활");

        assertThat(ballots).as("한 사람이 몇 번을 눌러도 한 표다").hasSize(1);
        assertThat(service.tally("0000000011", "배달의민족")).contains("생활");
    }

    @Test
    @DisplayName("실수를 되돌리면 반대 증거가 사라진다 — 다수가 원래대로 돌아온다")
    void undoingAMistakeRestoresTheMajority() {
        cast(1L, "식비");
        cast(2L, "식비");
        cast(3L, "쇼핑");
        assertThat(service.tally("0000000011", "배달의민족")).as("2:1").contains("식비");

        cast(1L, "쇼핑");      // 한 명이 실수로 옮겼다
        assertThat(service.tally("0000000011", "배달의민족")).as("1:2 — 뒤집힌다").contains("쇼핑");

        cast(1L, "식비");      // 되돌린다
        assertThat(service.tally("0000000011", "배달의민족")).as("2:1 로 복귀").contains("식비");
    }

    @Test
    @DisplayName("가맹점마다 표가 따로 산다 — 남의 가맹점 표가 섞이면 안 된다")
    void ballotsDoNotLeakBetweenMerchants() {
        service.castAndTally("0000000011", "배달의민족", 1L, "식비");
        service.castAndTally("0000000011", "쿠팡", 2L, "쇼핑");

        assertThat(service.tally("0000000011", "배달의민족")).contains("식비");
        assertThat(service.tally("0000000011", "쿠팡")).contains("쇼핑");
    }

    @Test
    @DisplayName("그 사람의 표를 되읽을 수 있다 — 본인 결제는 이것이 전역보다 먼저다")
    void ownVoteIsReadable() {
        cast(1L, "식비");
        cast(2L, "쇼핑");   // 동률이라 전역은 안 정해진다

        assertThat(service.voteOf("0000000011", "배달의민족", 1L)).contains("식비");
        assertThat(service.voteOf("0000000011", "배달의민족", 2L)).contains("쇼핑");
        assertThat(service.voteOf("0000000011", "배달의민족", 9L)).as("안 던진 사람").isEmpty();
    }

    @Test
    @DisplayName("빈 입력은 표가 되지 않는다")
    void rejectsEmptyInput() {
        assertThat(service.castAndTally("0000000011", null, 1L, "식비")).isEmpty();
        assertThat(service.castAndTally("0000000011", "  ", 1L, "식비")).isEmpty();
        assertThat(service.castAndTally("0000000011", "배달의민족", null, "식비")).isEmpty();
        assertThat(service.castAndTally("0000000011", "배달의민족", 1L, null)).isEmpty();
        assertThat(ballots).isEmpty();
    }

    @Test
    @DisplayName("표가 하나도 없으면 아무도 안 고른다")
    void noBallotsNoWinner() {
        assertThat(service.tally("0000000011", "아무도 안 본 가게")).isEmpty();
    }
}
