package com.finntech.service;

import com.finntech.domain.MerchantCategory;
import com.finntech.domain.UserPayment;
import com.finntech.engine.IndustryCategoryMapper;
import com.finntech.repository.MerchantCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 확정 분류 사전의 조회 규칙 — <b>PG 에는 완화를 적용하지 않는다</b>가 핵심이다.
 *
 * <p>사전은 (사업자번호, 가맹점 풀네임) 이 키다. 그런데 한 사업자번호에 상호가 38,690종 붙은
 * 것이 있어(택시 — 표시명 뒤에 차량번호가 붙는다) 정확 일치만으로는 영영 재사용되지 않는다.
 * 그래서 "같은 번호의 다른 행을 쓴다"는 완화를 두는데, <b>PG 는 정반대</b>라 그 완화가 닿으면
 * 한 PG 를 거친 모든 결제가 한 분류로 오염된다. 그 경계를 여기서 못박는다.
 *
 * <p><b>여기 적힌 사업자번호는 시험용 자리표다.</b> 국세청은 {@code 0} 으로 시작하는 번호를
 * 발급하지 않으므로 실재하는 사업자와 겹칠 수 없다 — 실제 번호를 자리표로 쓰면 그 자체가
 * 누군가의 결제처를 저장소에 적어 넣는 일이 된다(PG 번호만 예외다. 이미 저장소에 있는
 * 공개 목록이고, PG 경계를 시험하려면 그 번호여야 한다).
 */
class MerchantCategoryServiceTest {

    private final List<MerchantCategory> table = new ArrayList<>();
    private MerchantCategoryService service;
    private IndustryCategoryMapper mapper;

    @BeforeEach
    void setUp() {
        table.clear();
        mapper = new IndustryCategoryMapper(new ObjectMapper());

        MerchantCategoryRepository repo = mock(MerchantCategoryRepository.class);
        when(repo.findByBusinessNumberAndMerchantName(anyString(), anyString()))
                .thenAnswer(inv -> table.stream()
                        .filter(m -> m.getBusinessNumber().equals(inv.getArgument(0))
                                && m.getMerchantName().equals(inv.getArgument(1)))
                        .findFirst());
        when(repo.findByBusinessNumberOrdered(anyString()))
                .thenAnswer(inv -> table.stream()
                        .filter(m -> !m.getBusinessNumber().isEmpty()
                                && m.getBusinessNumber().equals(inv.getArgument(0)))
                        .sorted(Comparator.comparing(MerchantCategory::getMerchantName))
                        .toList());
        when(repo.findByNameOnly(anyString()))
                .thenAnswer(inv -> table.stream()
                        .filter(m -> m.getBusinessNumber().isEmpty()
                                && m.getMerchantName().equals(inv.getArgument(0)))
                        .toList());

        service = new MerchantCategoryService(repo, mapper);
    }

    private void seed(String biz, String name, String cat) {
        table.add(new MerchantCategory(biz, name, cat, MerchantCategory.Source.USER_CSV, null));
    }

    @Test
    @DisplayName("정확 일치가 먼저다 — 같은 번호라도 점포가 다르면 그 점포의 분류를 쓴다")
    void exactMatchWins() {
        seed("0000000011", "신세계백화점 강남점", "쇼핑");
        seed("0000000011", "신세계백화점 강남점 식품관", "식비");

        assertThat(service.lookup("0000000011", "신세계백화점 강남점")).contains("쇼핑");
        assertThat(service.lookup("0000000011", "신세계백화점 강남점 식품관")).contains("식비");
    }

    @Test
    @DisplayName("PG 번호에는 완화를 쓰지 않는다 — 한 번호에 업종이 제각각이기 때문")
    void paymentAgencyNeverFallsBackToSiblings() {
        // 카카오페이 번호로 어떤 가맹점 하나가 사전에 들어와 있다고 하자.
        seed("5278800686", "삼성물산리조트(주)에버랜드", "취미/여가");
        assertThat(mapper.isPaymentAgency("5278800686")).as("카카오페이는 PG 다").isTrue();

        // 정확 일치는 당연히 된다.
        assertThat(service.lookup("5278800686", "삼성물산리조트(주)에버랜드")).contains("취미/여가");

        // 그러나 **같은 번호의 다른 가맹점**은 절대 그 분류를 물려받지 않는다.
        // 물려받는 순간 카카오페이를 거친 모든 결제가 '취미/여가'가 된다.
        assertThat(service.lookup("5278800686", "전혀 다른 가게"))
                .as("PG 는 같은 번호라도 업종이 다르다").isEmpty();
    }

    @Test
    @DisplayName("PG 가 아니면 같은 번호의 다른 행을 쓴다 — 택시처럼 표시명이 매번 다른 가맹점")
    void nonAgencyReusesSiblingRow() {
        // 카카오T 는 표시명 뒤에 차량번호가 붙어 결제마다 풀네임이 다르다.
        seed("0000000022", "카카오T서울12가3456", "교통/자동차");

        assertThat(service.lookup("0000000022", "카카오T경기33아6084"))
                .as("한 사업자의 업종은 하나라 풀네임이 달라도 같은 분류다")
                .contains("교통/자동차");
    }

    @Test
    @DisplayName("사업자번호가 없는 해외 가맹점은 풀네임만으로 찾는다")
    void foreignMerchantMatchesByNameOnly() {
        seed("", "OPENAI *CHATGPT SUBSCR", "취미/여가");

        assertThat(service.lookup(null, "OPENAI *CHATGPT SUBSCR")).contains("취미/여가");
        assertThat(service.lookup("", "OPENAI *CHATGPT SUBSCR")).contains("취미/여가");
        assertThat(service.lookup(null, "모르는 해외 가맹점")).isEmpty();
    }

    @Test
    @DisplayName("사전이 업종코드보다 앞선다 — 그리고 없으면 업종코드로 내려간다")
    void dictionaryOutranksIndustryCode() {
        // 업종코드는 '미용'이라고 하지만 사람이 '의료'로 확정했다면 사람이 이긴다.
        seed("0000000033", "어떤 가게", "의료");
        assertThat(service.resolve("523131", "0000000033", "어떤 가게")).isEqualTo("의료");

        // 사전에 없으면 업종코드가 답한다.
        assertThat(service.resolve("523131", "0000000044", "사전에 없는 가게")).isEqualTo("미용");
        // 업종코드도 없으면 카테고리없음이다 — null 을 흘리지 않는다.
        assertThat(service.resolve(null, "0000000044", "사전에 없는 가게"))
                .isEqualTo(IndustryCategoryMapper.UNCLASSIFIED);
    }

    @Test
    @DisplayName("하이픈이 있어도 같은 번호다 — 원장 표기가 갈라져도 키가 쪼개지지 않는다")
    void hyphensDoNotSplitTheKey() {
        seed("000-00-00011", "신세계백화점 강남점", "쇼핑");

        assertThat(table.get(0).getBusinessNumber()).isEqualTo("0000000011");
        assertThat(service.lookup("0000000011", "신세계백화점 강남점")).contains("쇼핑");
        assertThat(service.lookup("000-00-00011", "신세계백화점 강남점")).contains("쇼핑");
    }

    @Test
    @DisplayName("가맹점명이 없으면 사전을 쓰지 않는다 — 키의 절반이 없다")
    void blankMerchantNameIsNotLookedUp() {
        seed("0000000011", "신세계백화점 강남점", "쇼핑");

        assertThat(service.lookup("0000000011", null)).isEmpty();
        assertThat(service.lookup("0000000011", "  ")).isEmpty();
    }

    @Test
    @DisplayName("더미 사용자의 확인은 사전에 쌓이지 않는다 — 가짜 사업자번호가 들어가면 안 된다")
    void syntheticPaymentsNeverEnterTheDictionary() {
        // 사전이 담는 것은 **실재하는** 사업자번호다. 더미 사용자의 번호는 생성기가 만든 것이라
        // 실재하지 않는데, 데모로 둘러보다 "맞아요"를 누르면 그게 쌓여 사전이 거짓이 된다.
        MerchantCategoryRepository repo = mock(MerchantCategoryRepository.class);
        when(repo.findByBusinessNumberAndMerchantName(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(repo.save(org.mockito.ArgumentMatchers.any(MerchantCategory.class)))
                .thenAnswer(inv -> {
                    table.add(inv.getArgument(0));
                    return inv.getArgument(0);
                });
        var svc = new MerchantCategoryService(repo, mapper);

        var dummy = payment("77:gen-8a3f-0012");            // 생성기가 만든 결제
        assertThat(svc.confirmFrom(dummy, "식비", 7L)).as("더미는 거절된다").isEmpty();
        assertThat(table).as("한 줄도 쌓이지 않는다").isEmpty();

        var real = payment("77:real-9c2b1d04-20260804-3");  // 실제 사람이 넣은 결제
        assertThat(svc.confirmFrom(real, "식비", 7L)).isPresent();
        assertThat(table).hasSize(1);
        assertThat(table.get(0).getSource()).isEqualTo("USER_CONFIRMED");
    }

    private static UserPayment payment(String rowId) {
        return new UserPayment(rowId, 77L, "S1", 9001L, LocalDateTime.now(),
                null, IndustryCategoryMapper.UNCLASSIFIED, 5000, "어떤 가게", 0, "0000000011");
    }

    @Test
    @DisplayName("적재용 스냅샷은 건별 조회와 같은 답을 준다 — 규칙이 두 곳에서 갈리면 안 된다")
    void snapshotAgreesWithPerRowLookup() {
        // 연동 한 번에 결제가 수천 건 들어와 건별 조회는 느리다. 그래서 통째로 읽는 경로를 따로
        // 두었는데, 규칙이 갈리면 "어떤 경로로 붙었느냐"에 따라 분류가 달라진다.
        seed("0000000011", "신세계백화점 강남점", "쇼핑");
        seed("0000000022", "카카오T서울12가3456", "교통/자동차");
        seed("5278800686", "삼성물산리조트(주)에버랜드", "취미/여가");   // PG 번호
        seed("", "OPENAI *CHATGPT SUBSCR", "취미/여가");

        var snap = new MerchantCategoryService.Snapshot(table, mapper);
        String[][] cases = {
                {"0000000011", "신세계백화점 강남점"},        // 정확 일치
                {"0000000022", "카카오T경기33아6084"},        // PG 아님 → 완화
                {"5278800686", "삼성물산리조트(주)에버랜드"},  // PG 정확 일치
                {"5278800686", "전혀 다른 가게"},             // PG → 완화 없음
                {"", "OPENAI *CHATGPT SUBSCR"},              // 번호 없음
                {"0000000044", "모르는 가게"},
        };
        for (String[] c : cases) {
            assertThat(snap.lookup(c[0], c[1]))
                    .as("%s / %s — 스냅샷과 건별 조회가 갈렸다", c[0], c[1])
                    .isEqualTo(service.lookup(c[0], c[1]));
        }
        assertThat(snap.lookup("5278800686", "전혀 다른 가게"))
                .as("스냅샷에서도 PG 는 완화되지 않는다").isEmpty();
    }

    @Test
    @DisplayName("사람이 확인하면 분류가 바뀐다 — 오입력을 되돌릴 길")
    void confirmOverwritesExistingRow() {
        MerchantCategoryRepository repo = mock(MerchantCategoryRepository.class);
        when(repo.findByBusinessNumberAndMerchantName(anyString(), anyString()))
                .thenAnswer(inv -> table.stream()
                        .filter(m -> m.getBusinessNumber().equals(inv.getArgument(0))
                                && m.getMerchantName().equals(inv.getArgument(1)))
                        .findFirst());
        when(repo.save(org.mockito.ArgumentMatchers.any(MerchantCategory.class)))
                .thenAnswer(inv -> {
                    table.add(inv.getArgument(0));
                    return inv.getArgument(0);
                });
        var svc = new MerchantCategoryService(repo, mapper);

        var first = svc.confirm("0000000011", "어떤 가게", "쇼핑",
                MerchantCategory.Source.USER_CSV, null);
        assertThat(first.getCategory2()).isEqualTo("쇼핑");

        var again = svc.confirm("0000000011", "어떤 가게", "식비",
                MerchantCategory.Source.USER_CONFIRMED, 7L);
        assertThat(table).as("행이 늘지 않고 고쳐진다").hasSize(1);
        assertThat(again.getCategory2()).isEqualTo("식비");
        assertThat(again.getSource()).isEqualTo("USER_CONFIRMED");
        assertThat(again.getConfirmedBy()).isEqualTo(7L);
    }
}
