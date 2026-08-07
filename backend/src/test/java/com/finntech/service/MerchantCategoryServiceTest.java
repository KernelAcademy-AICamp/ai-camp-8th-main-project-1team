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
        // **대역이 쿼리의 ORDER BY 를 그대로 흉내내야 한다.** 예전에는 가맹점명 순으로 정렬해
        // 두었는데, 실제 쿼리는 출처(사람의 확인 > 국세청 등록 > 추정) 순이다. 대역이 계약과
        // 갈리면 테스트는 통과하는데 운영은 틀린다 — 실제로 그렇게 됐다(2026-08-05 티머니).
        when(repo.findByBusinessNumberOrdered(anyString()))
                .thenAnswer(inv -> table.stream()
                        .filter(m -> !m.getBusinessNumber().isEmpty()
                                && m.getBusinessNumber().equals(inv.getArgument(0)))
                        .sorted(BY_SOURCE_THEN_INSERTION)
                        .toList());
        when(repo.findByNameOnly(anyString()))
                .thenAnswer(inv -> table.stream()
                        .filter(m -> m.getBusinessNumber().isEmpty()
                                && m.getMerchantName().equals(inv.getArgument(0)))
                        .sorted(BY_SOURCE_THEN_INSERTION)
                        .toList());

        // 저장은 표에 담고 그대로 돌려준다 — 쌓은 것이 곧바로 조회에 보여야 하기 때문이다.
        // (쌓는 키와 찾는 키가 어긋나는 사고를 시험에서 잡으려면 이 왕복이 있어야 한다.)
        when(repo.save(org.mockito.ArgumentMatchers.any(MerchantCategory.class)))
                .thenAnswer(inv -> {
                    MerchantCategory m = inv.getArgument(0);
                    table.add(m);
                    return m;
                });

        service = new MerchantCategoryService(repo, mapper, kinds(), noBrands());
    }

    /** 리포지토리의 {@code ORDER BY CASE source … , id} 를 그대로 옮긴 것. id 가 null 인
     *  시험 행은 표에 담은 순서로 본다(실제로는 auto increment 가 그 순서를 준다). */
    private static final Comparator<MerchantCategory> BY_SOURCE_THEN_INSERTION =
            Comparator.comparingInt((MerchantCategory m) ->
                    switch (m.getSource()) {
                        case "USER_CONFIRMED" -> 0;
                        case "USER_CSV" -> 1;
                        default -> 2;
                    });

    /**
     * 관측 판정 서비스 — 시험에서는 <b>표가 비어 있다</b>. 그러면 완화가 허용되는데,
     * 그것이 "상호가 하나뿐이라 판정 대상이 아니다"와 같은 상태라 기존 시험의 전제와 맞는다.
     * 판정이 완화를 막는 경우는 {@code 복합_사업자는_번호로_묶지_않는다} 가 따로 본다.
     */
    /**
     * 브랜드 대기 장소 대역 — 비어 있다. 이 시험이 보는 것은 분류 조회 규칙이라
     * 브랜드는 상관이 없지만, 사전에 쌓을 때 승격을 부르므로 대역이 필요하다.
     */
    private static MerchantBrandService noBrands() {
        var brandRepo = mock(com.finntech.repository.MerchantBrandRepository.class);
        when(brandRepo.findByMerchantName(anyString())).thenReturn(Optional.empty());
        return new MerchantBrandService(brandRepo,
                mock(com.finntech.repository.MerchantCategoryRepository.class),
                mock(TempClassifierService.class), new tools.jackson.databind.ObjectMapper());
    }

    private BusinessNumberKindService kinds() {
        var repo = mock(com.finntech.repository.BusinessNumberKindRepository.class);
        when(repo.findById(anyString())).thenReturn(Optional.empty());
        return new BusinessNumberKindService(repo, 5, 2, 0.10);
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
    @DisplayName("PG 를 거친 결제는 번호를 버리고 이름으로 붙는다 — PG 가 달라도 같은 답")
    void paymentAgencyFallsBackToNameOnly() {
        // 넷플릭스 한 곳이 명세서에서 KG이니시스 8건·NHNKCP 4건으로 갈라져 있었다(2026-08-05).
        // PG 별 복합키로 넣으면 PG 가 늘 때마다 따라 넣어야 하고, 하나 빠뜨리면 그만큼 조용히
        // 미분류가 된다. **이름 한 행**이 어느 PG 를 거치든 붙어야 한다.
        seed("", "넷플릭스서비시스코리아 유한회사", "취미/여가");

        for (String pgNumber : new String[]{"2208155597", "1138521083", "5278800686"}) {
            assertThat(mapper.isPaymentAgency(pgNumber)).as(pgNumber + " 는 PG 다").isTrue();
            assertThat(service.lookup(pgNumber, "넷플릭스서비시스코리아 유한회사"))
                    .as("PG 가 무엇이든 가맹점명이 같으면 같은 분류다").contains("취미/여가");
            assertThat(new MerchantCategoryService.Snapshot(table, mapper)
                    .lookup(pgNumber, "넷플릭스서비시스코리아 유한회사"))
                    .as("적재용 스냅샷도 같은 답을 준다").contains("취미/여가");
        }

        // 그래도 **이름이 다르면 안 붙는다** — PG 오염을 막는 경계는 그대로다.
        assertThat(service.lookup("2208155597", "스타벅스코리아"))
                .as("같은 PG 라도 이름이 다르면 남의 분류를 물려받지 않는다").isEmpty();
    }

    @Test
    @DisplayName("LLM 추정은 사전에 남되 판정에는 안 쓰인다 — 다시 묻지 않기 위한 자리다")
    void llmGuessesAreRememberedButNeverJudge() {
        UserPayment real = realPayment("2208155597", "넷플릭스서비시스코리아 유한회사");

        assertThat(service.guess("2208155597", "넷플릭스서비시스코리아 유한회사"))
                .as("아직 물어본 적이 없다").isEmpty();

        service.rememberGuess(real, "취미/여가");

        // ① 판정에는 안 쓴다 — 사람이 확인해야 확정이다(마스터 §4 원칙 1).
        assertThat(service.lookup("2208155597", "넷플릭스서비시스코리아 유한회사"))
                .as("추정은 lookup 이 돌려주지 않는다").isEmpty();
        assertThat(new MerchantCategoryService.Snapshot(table, mapper)
                .lookup("2208155597", "넷플릭스서비시스코리아 유한회사"))
                .as("적재 스냅샷에도 안 담긴다 — 담기면 Consumption 카테고리로 굳는다").isEmpty();

        // ② 그러나 "이미 물어봤다"는 것은 남는다 — 다음 달 새 결제에서 또 묻지 않는다.
        assertThat(service.guess("1138521083", "넷플릭스서비시스코리아 유한회사"))
                .as("PG 가 달라도 같은 가맹점이면 이미 물어본 것이다").contains("취미/여가");
    }

    @Test
    @DisplayName("적재 스냅샷도 '이미 물어본 것'을 안다 — 재연동이 추정을 지우지 못한다")
    void 스냅샷이_추정을_들고_있다() {
        // 재연동은 결제 행을 통째로 지우고 다시 만든다. 추정이 결제 행에만 있으면 그때 날아가고,
        // 사전에 멀쩡히 남아 있는데도 화면에서 사라진다(2026-08-05 운영: 82건 → 0건).
        // 그래서 적재 경로가 추정을 다시 칠할 수 있어야 한다.
        UserPayment real = realPayment("2208155597", "넷플릭스서비시스코리아 유한회사");
        service.rememberGuess(real, "취미/여가");

        var snap = new MerchantCategoryService.Snapshot(table, mapper);
        assertThat(snap.lookup("2208155597", "넷플릭스서비시스코리아 유한회사"))
                .as("판정층에는 없다 — 추정은 확정이 아니다").isEmpty();
        assertThat(snap.guess("1138521083", "넷플릭스서비시스코리아 유한회사"))
                .as("PG 가 달라도 이미 물어본 것이다").contains("취미/여가");
        assertThat(snap.guess("2208155597", "스타벅스코리아"))
                .as("안 물어본 것은 없다고 답한다").isEmpty();
    }

    @Test
    @DisplayName("추정은 확정을 덮지 못한다 — 사실과 사람의 확인이 위다")
    void guessesNeverOverwriteConfirmedRows() {
        seed("", "넷플릭스서비시스코리아 유한회사", "취미/여가");   // USER_CSV = 사실

        UserPayment real = realPayment("2208155597", "넷플릭스서비시스코리아 유한회사");
        assertThat(service.rememberGuess(real, "쇼핑"))
                .as("확정이 이미 있으면 추정을 남기지 않는다").isEmpty();

        assertThat(service.lookup("2208155597", "넷플릭스서비시스코리아 유한회사"))
                .as("확정이 그대로 남는다").contains("취미/여가");
    }

    @Test
    @DisplayName("더미 결제의 추정은 사전에 안 쌓인다 — 사업자번호가 실재하지 않는다")
    void guessesFromSyntheticPaymentsAreRejected() {
        UserPayment dummy = payment("77:gen-8a3f-0012");

        assertThat(service.rememberGuess(dummy, "식비"))
                .as("더미의 번호가 사전에 실리면 '실제 사업자번호' 라는 약속이 깨진다").isEmpty();
        assertThat(table).isEmpty();
    }

    @Test
    @DisplayName("PG 결제를 확정하면 번호 없이 쌓인다 — 쌓는 자리와 찾는 자리가 같아야 한다")
    void confirmingAPgPaymentStoresItByNameOnly() {
        // 사람이 KG이니시스를 거친 넷플릭스에 "맞아요"를 눌렀다.
        MerchantCategory saved = service.confirm("2208155597", "넷플릭스서비시스코리아 유한회사",
                "취미/여가", MerchantCategory.Source.USER_CONFIRMED, 7L);

        assertThat(saved.getBusinessNumber())
                .as("PG 번호는 키가 아니다 — 무엇을 샀는지 말해 주지 않는다").isEmpty();

        // 그래서 **다른 PG** 를 거친 같은 가맹점에도 곧바로 붙는다. 이것이 목적이다.
        assertThat(service.lookup("1138521083", "넷플릭스서비시스코리아 유한회사"))
                .as("NHNKCP 를 거친 넷플릭스에도 붙는다").contains("취미/여가");
    }

    @Test
    @DisplayName("사전이 갈리면 완화를 멈춘다 — 한 번의 교정이 남의 분류를 바꾸지 못한다")
    void 갈린_번호는_완화하지_않는다() {
        // 2026-08-05 운영: 티머니(396-87-03587)가 등록 업종 '전자상거래 소매업' 때문에 씨앗에
        // '쇼핑'으로 들어갔고, 사용자가 한 건을 '교통/자동차'로 고쳤다.
        //
        // **고친 것을 다른 차량에 번지게 하면 안 된다.** 한 번의 교정이 그 번호 전체를 바꾸면,
        // 택시처럼 상호가 수천 종인 곳에서 누군가 한 번 실수하는 것만으로 전부 뒤집힌다.
        // 그래서 사전이 두 중분류를 알게 된 순간 완화를 멈춘다 — 고친 것은 그 가맹점에만 남는다.
        //
        // (씨앗이 틀렸다면 **씨앗을 고치는 것**이 답이다. 실제로 그렇게 했다.)
        seed("0000000099", "", "쇼핑");                       // 씨앗(USER_CSV)
        table.add(new MerchantCategory("0000000099", "티머니 택시-경북15바7380", "교통/자동차",
                MerchantCategory.Source.USER_CONFIRMED, 7L)); // 사람이 고친 한 대

        assertThat(service.lookup("0000000099", "티머니 택시-경북15바7380"))
                .as("고친 그 가맹점은 정확일치로 곧바로 보인다").contains("교통/자동차");
        assertThat(service.lookup("0000000099", "티머니 택시-서울31바3715"))
                .as("다른 차량에는 번지지 않는다 — 업종코드·미분류로 내려간다").isEmpty();
        assertThat(new MerchantCategoryService.Snapshot(table, mapper)
                .lookup("0000000099", "티머니 택시-서울31바3715"))
                .as("적재 스냅샷도 같아야 한다 — 갈리면 연동할 때마다 답이 달라진다").isEmpty();
    }

    @Test
    @DisplayName("복합 사업자는 완화하지 않는다 — 하나를 고쳐도 나머지가 안 따라간다")
    void 복합_사업자는_번호로_묶지_않는다() {
        // 울릉크루즈(132-88-01755) 한 번호에 여객선과 배 안의 GS25 가 붙어 있다.
        // 번호로 묶으면 GS25 를 '편의점'으로 고치는 순간 여객선까지 편의점이 된다.
        String CRUISE = "1328801755";
        assertThat(mapper.isMultiBusiness(CRUISE)).as("복합 사업자 목록에 있어야 한다").isTrue();

        seed(CRUISE, "", "쇼핑");                       // 번호 전체
        seed(CRUISE, "GS25 울룽크루즈점", "편의점/잡화");   // 그 가게만

        assertThat(service.lookup(CRUISE, "GS25 울룽크루즈점"))
                .as("정확일치는 된다").contains("편의점/잡화");
        assertThat(service.lookup(CRUISE, "울릉크루즈 주식회사"))
                .as("번호가 같아도 다른 가게에는 안 붙는다 — 업종코드·미분류로 내려간다").isEmpty();
        assertThat(new MerchantCategoryService.Snapshot(table, mapper)
                .lookup(CRUISE, "울릉크루즈 주식회사"))
                .as("적재 스냅샷도 같아야 한다").isEmpty();

        // 복합이 아닌 번호는 완화가 그대로 살아 있어야 한다 — 택시가 그것으로 산다.
        seed("0000000077", "", "교통/자동차");
        assertThat(service.lookup("0000000077", "카카오택시-서울12가3456"))
                .as("복합이 아니면 완화는 유지된다").contains("교통/자동차");
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
    @DisplayName("씨앗 모양 — 이름이 빈 행도 번호로 붙는다 (실데이터가 닿는 유일한 경로)")
    void seedRowsWithoutMerchantNameStillMatch() {
        // `realdatas.csv` 에는 사업자번호와 업종만 있고 **가맹점 풀네임이 없다.** 그래서 씨앗은
        // 이름이 빈 채로 쌓인다. 실데이터 결제는 이름이 있으므로 정확 일치는 절대 안 맞고,
        // **번호로 붙는 완화(③)만이 유일한 경로**다 — 그 경로가 죽으면 사전 144곳이 통째로
        // 무용지물이 되는데 아무 오류도 안 난다. 그래서 여기서 못박는다.
        seed("0000000055", "", "생활");

        assertThat(service.lookup("0000000055", "어느 택배회사"))
                .as("이름이 뭐든 그 사업자의 업종이 붙는다").contains("생활");
        assertThat(new MerchantCategoryService.Snapshot(table, mapper)
                .lookup("0000000055", "어느 택배회사"))
                .as("적재용 스냅샷도 같은 답을 준다").contains("생활");
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
        var svc = new MerchantCategoryService(repo, mapper, kinds(), noBrands());

        var dummy = payment("77:gen-8a3f-0012");            // 생성기가 만든 결제
        assertThat(svc.confirmFrom(dummy, "식비", 7L)).as("더미는 거절된다").isEmpty();
        assertThat(table).as("한 줄도 쌓이지 않는다").isEmpty();

        var real = payment("77:real-9c2b1d04-20260804-3");  // 실제 사람이 넣은 결제
        assertThat(svc.confirmFrom(real, "식비", 7L)).isPresent();
        assertThat(table).hasSize(1);
        assertThat(table.get(0).getSource()).isEqualTo("USER_CONFIRMED");
    }

    @Test
    @DisplayName("조회 답을 사전에 남긴다 — 확정으로 들어오고 추정을 덮는다")
    void registryAnswerBecomesConfirmed() {
        UserPayment real = realPayment("0000000021", "어느 가구점");
        service.rememberGuess(real, "쇼핑");                       // 먼저 추정이 있었다
        assertThat(service.lookup("0000000021", "어느 가구점")).isEmpty();

        service.rememberRegistry(real, "생활");
        assertThat(service.lookup("0000000021", "어느 가구점"))
                .as("사실이 추정을 덮는다").contains("생활");
        assertThat(table).as("행이 늘지 않고 고쳐진다").hasSize(1);
    }

    @Test
    @DisplayName("사람이 정한 것은 조회가 덮지 않는다")
    void registryNeverOverwritesPeople() {
        UserPayment real = realPayment("0000000022", "그 가게");
        service.confirmFrom(real, "식비", 7L);
        assertThat(service.rememberRegistry(real, "쇼핑")).isEmpty();
        assertThat(service.lookup("0000000022", "그 가게")).contains("식비");
    }

    @Test
    @DisplayName("더미 결제로는 사전이 자라지 않는다 — 조회 통로도 마찬가지다")
    void registryRejectsDummyPayments() {
        // 더미 결제에도 실재하는 사업자번호가 섞여 있어 조회 자체는 성공할 수 있다.
        // 그렇게 들어온 행은 아무도 결제한 적 없는 가맹점을 사전에 앉힌다.
        assertThat(service.rememberRegistry(payment("77:gen-8a3f-0012"), "쇼핑")).isEmpty();
        assertThat(service.attemptRow(payment("77:gen-8a3f-0012"))).isEmpty();
        assertThat(table).isEmpty();
    }

    @Test
    @DisplayName("LLM 을 세 번 헛물켜면 '기타'로 종결하고 더 묻지 않는다")
    void givesUpAfterThreeLlmMisses() {
        UserPayment real = realPayment("0000000023", "알 수 없는 곳");
        LocalDateTime at = LocalDateTime.now();

        assertThat(service.noteLlmMiss(real, at)).as("1회").isFalse();
        assertThat(service.noteLlmMiss(real, at)).as("2회").isFalse();
        assertThat(service.needsWork("0000000023", "알 수 없는 곳"))
                .as("아직 남았다").isTrue();

        assertThat(service.noteLlmMiss(real, at)).as("3회 — 종결").isTrue();
        assertThat(service.needsWork("0000000023", "알 수 없는 곳"))
                .as("더 조회도 질문도 하지 않는다").isFalse();
        assertThat(service.lookup("0000000023", "알 수 없는 곳"))
                .contains(IndustryCategoryMapper.OTHER);
        assertThat(table).hasSize(1);
    }

    @Test
    @DisplayName("종결돼도 사람이 고치면 그것이 이긴다 — 영구가 아니다")
    void peopleCanOverrideGiveUp() {
        UserPayment real = realPayment("0000000024", "그래도 아는 곳");
        LocalDateTime at = LocalDateTime.now();
        service.noteLlmMiss(real, at);
        service.noteLlmMiss(real, at);
        service.noteLlmMiss(real, at);
        assertThat(service.lookup("0000000024", "그래도 아는 곳"))
                .contains(IndustryCategoryMapper.OTHER);

        service.confirmFrom(real, "카페/간식", 7L);
        assertThat(service.lookup("0000000024", "그래도 아는 곳")).contains("카페/간식");
        assertThat(service.needsWork("0000000024", "그래도 아는 곳")).isFalse();
    }

    @Test
    @DisplayName("시도 이력 행은 분류가 아니다 — 추정 조회에 걸리지 않는다")
    void attemptRowIsNotAClassification() {
        UserPayment real = realPayment("0000000025", "물어본 곳");
        service.noteLookup(real, "아파트 건설업", LocalDateTime.now());

        assertThat(service.lookup("0000000025", "물어본 곳")).as("확정 아님").isEmpty();
        assertThat(service.guess("0000000025", "물어본 곳")).as("추정도 아님").isEmpty();
        assertThat(table).hasSize(1);
        assertThat(table.get(0).registryAnswered())
                .as("못 붙였어도 답은 남는다 — 안 남기면 다음 연동에 또 조회한다").isTrue();
    }

    /** 실제 사람의 결제 — 번호와 가맹점명을 지정한다(사전에 쌓이는 유일한 출처다). */
    private static UserPayment realPayment(String biz, String name) {
        return new UserPayment("77:real-9c2b1d04-20260805-1", 77L, "S1", 9001L,
                LocalDateTime.now(), null, IndustryCategoryMapper.UNCLASSIFIED,
                5000, name, 0, biz);
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
        var svc = new MerchantCategoryService(repo, mapper, kinds(), noBrands());

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
