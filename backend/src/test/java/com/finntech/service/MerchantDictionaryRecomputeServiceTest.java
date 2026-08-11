package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.domain.MerchantCategory;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.AppUserRepository;
import com.finntech.repository.MerchantCategoryRepository;
import com.finntech.repository.ReportRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 대조표를 고쳤을 때 사전을 다시 계산한다(V29).
 *
 * <p>여기 있는 것은 전부 <b>조용히 틀어지는</b> 종류다 — 재계산이 사람의 판단을 덮거나,
 * 사전만 고치고 원장을 두거나, 확정을 '카테고리없음'으로 강등시켜도 예외는 안 난다.
 */
class MerchantDictionaryRecomputeServiceTest {

    private final List<MerchantCategory> table = new ArrayList<>();
    private MerchantDictionaryRecomputeService service;
    private MyDataLinkService ledger;
    private ReportRepository reports;

    @BeforeEach
    void setUp() {
        table.clear();
        IndustryCategoryMapper mapper = new IndustryCategoryMapper(new ObjectMapper());

        MerchantCategoryRepository repo = mock(MerchantCategoryRepository.class);
        // 질의가 그렇듯 **표에서 유도된 행만** 돌려준다.
        when(repo.findTableDerived(any())).thenAnswer(inv -> table.stream()
                .filter(m -> MerchantCategory.Source.USER_CSV.name().equals(m.getSource())
                        || MerchantCategory.Source.REGISTRY.name().equals(m.getSource()))
                .toList());

        // **대역을 먼저 다 만들고 나서 스터빙한다.** when(...) 의 인자 안에서 또 스터빙하면
        // Mockito 가 UnfinishedStubbing 으로 막는다.
        List<AppUser> people = List.of(realPerson(1L), dummy(2L));
        AppUserRepository users = mock(AppUserRepository.class);
        when(users.findAll()).thenReturn(people);

        ledger = mock(MyDataLinkService.class);
        when(ledger.applyResolved(anyLong(), anyMap())).thenReturn(3);
        reports = mock(ReportRepository.class);

        service = new MerchantDictionaryRecomputeService(repo, mapper, users, ledger, reports, 2000);
    }

    private static AppUser realPerson(Long id) {
        AppUser u = mock(AppUser.class);
        when(u.getId()).thenReturn(id);
        when(u.isRealPerson()).thenReturn(true);
        return u;
    }

    private static AppUser dummy(Long id) {
        AppUser u = mock(AppUser.class);
        when(u.isRealPerson()).thenReturn(false);
        return u;
    }

    private MerchantCategory row(String biz, String name, String cat,
                                 MerchantCategory.Source src, List<String> codes) {
        MerchantCategory m = new MerchantCategory(biz, name, cat, src, null, codes);
        table.add(m);
        return m;
    }

    @Test
    @DisplayName("표가 답을 바꾸면 사전이 따라온다 — 굳은 행이 옛 답을 들고 있지 않게")
    void recomputeFollowsTheTable() {
        // 521912 는 '대형 마트'다. 어떤 이유로 사전에 '쇼핑'으로 굳어 있다고 하자.
        MerchantCategory stale = row("0000000011", "어느 마트", "쇼핑",
                MerchantCategory.Source.REGISTRY, List.of("521912"));

        var result = service.recompute(true);

        assertThat(stale.getCategory2()).isEqualTo("대형마트");
        assertThat(stale.getSource()).as("출처는 그대로다 — 답만 표를 따라간다")
                .isEqualTo(MerchantCategory.Source.REGISTRY.name());
        assertThat(result.changes()).singleElement()
                .satisfies(c -> assertThat(c.from() + "→" + c.to()).isEqualTo("쇼핑→대형마트"));
    }

    @Test
    @DisplayName("사람이 정한 것은 재계산이 건드리지 않는다 — 질의부터 안 잡는다")
    void neverTouchesWhatPeopleDecided() {
        row("0000000012", "배달의민족", "식비",
                MerchantCategory.Source.USER_CONFIRMED, List.of("525101"));
        row("0000000013", "어느 카페", "쇼핑", MerchantCategory.Source.LLM_GUESS, null);

        var result = service.recompute(true);

        assertThat(result.scanned()).as("표에서 유도된 행이 없다").isZero();
        assertThat(result.changes()).isEmpty();
        assertThat(table.get(0).getCategory2()).isEqualTo("식비");
    }

    @Test
    @DisplayName("강등하지 않는다 — 확정을 '카테고리없음'으로 내리면 그 지출이 리포트에서 사라진다")
    void neverDemotesToUnknown() {
        // 표에서 빠진 코드(대조표에 없는 번호)를 근거로 든 행.
        MerchantCategory orphan = row("0000000014", "사라진 업종의 가게", "쇼핑",
                MerchantCategory.Source.USER_CSV, List.of("999999"));

        var result = service.recompute(true);

        assertThat(orphan.getCategory2()).as("손대지 않는다").isEqualTo("쇼핑");
        assertThat(result.unmappable()).isEqualTo(1);
        assertThat(result.changes()).isEmpty();
    }

    @Test
    @DisplayName("코드가 갈리면 고르지 않는다 — 살아 있는 경로와 같은 규칙이라야 한다")
    void splitCodesAreLeftAlone() {
        // 521910(백화점→쇼핑)과 521912(대형 마트→대형마트)가 한 행에 같이 있다.
        MerchantCategory split = row("0000000015", "갈리는 곳", "쇼핑",
                MerchantCategory.Source.REGISTRY, List.of("521910", "521912"));

        var result = service.recompute(true);

        assertThat(split.getCategory2()).isEqualTo("쇼핑");
        assertThat(result.unmappable()).isEqualTo(1);
    }

    @Test
    @DisplayName("근거가 비면 조회 답에서 되찾는다 — 바깥 호출 없이")
    void backfillsCodesFromTheRegistryAnswer() {
        MerchantCategory noCodes = row("0000000016", "어느 편의점", "편의점/잡화",
                MerchantCategory.Source.REGISTRY, null);
        noCodes.noteLookup("체인화 편의점", java.time.LocalDateTime.now());

        var result = service.recompute(true);

        assertThat(result.backfilled()).isEqualTo(1);
        assertThat(noCodes.getNtsCodes()).as("이름에서 코드를 역산해 채운다").isNotNull();
        assertThat(noCodes.getCategory2()).as("답은 그대로다").isEqualTo("편의점/잡화");
    }

    @Test
    @DisplayName("역산도 안 되는 행은 건드리지 않는다 — 다시 조회하지 않는다")
    void leavesRowsThatCannotBeRecovered() {
        MerchantCategory lost = row("0000000017", "이름을 잃은 곳", "생활",
                MerchantCategory.Source.REGISTRY, null);

        var result = service.recompute(true);

        assertThat(result.backfilled()).isZero();
        assertThat(lost.getNtsCodes()).isNull();
        assertThat(lost.getCategory2()).isEqualTo("생활");
    }

    @Test
    @DisplayName("사전만 고치면 반쪽이다 — 원장을 함께 고치고 리포트 캐시를 깬다")
    void alsoFixesTheLedgerAndBreaksTheReportCache() {
        row("0000000018", "어느 마트", "쇼핑",
                MerchantCategory.Source.REGISTRY, List.of("521912"));

        var result = service.recompute(true);

        // 실제 사람만 본다 — 더미의 원장에는 이 가맹점이 없다.
        verify(ledger, times(1)).applyResolved(anyLong(), anyMap());
        verify(reports, times(1)).deleteByUserId(1L);
        assertThat(result.ledgerRowsFixed()).isEqualTo(3);
    }

    @Test
    @DisplayName("dry-run 은 아무것도 쓰지 않는다 — 그래도 무엇이 달라질지는 말한다")
    void dryRunWritesNothing() {
        MerchantCategory stale = row("0000000019", "어느 마트", "쇼핑",
                MerchantCategory.Source.REGISTRY, List.of("521912"));

        var result = service.recompute(false);

        assertThat(result.changes()).hasSize(1);
        assertThat(stale.getCategory2()).as("사전은 그대로").isEqualTo("쇼핑");
        verify(ledger, never()).applyResolved(anyLong(), anyMap());
        verify(reports, never()).deleteByUserId(anyLong());
    }

    @Test
    @DisplayName("두 번 돌려도 두 번째는 할 일이 없다 — 멱등")
    void isIdempotent() {
        row("0000000020", "어느 마트", "쇼핑",
                MerchantCategory.Source.REGISTRY, List.of("521912"));

        assertThat(service.recompute(true).changes()).hasSize(1);
        assertThat(service.recompute(true).changes()).as("두 번째는 0건").isEmpty();
    }
}
