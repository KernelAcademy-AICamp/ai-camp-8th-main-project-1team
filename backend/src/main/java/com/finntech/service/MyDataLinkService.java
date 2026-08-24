package com.finntech.service;

import com.finntech.domain.*;
import com.finntech.repository.*;
import com.finntech.service.MyDataResponses.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 마이데이터 연동 (§13-3). 본인인증(가상 CI) 후 카드사를 연결하면 마이데이터 서버에서 카드·결제내역을 당겨와
 * <b>UserCard/UserPayment로 영속화</b>하고, 동시에 <b>Consumption(source=MYDATA)로 투영</b>해 기존 엔진(소비건전성·리포트·절약통·FDS)이
 * 재계산 없이 재사용하게 한다. 재연동은 전체 동기화(기존 MYDATA 데이터 교체)로 처리한다.
 */
@Service
public class MyDataLinkService {

    private static final Logger log = LoggerFactory.getLogger(MyDataLinkService.class);

    /**
     * 한 회차에 <b>모델에</b> 물어볼 브랜드 수. 카탈로그로 붙는 것은 호출이 없어 여기 안 센다.
     *
     * <p>상한을 두는 이유는 값이 아니라 <b>시간</b>이다 — 하나에 6~10초가 걸려 273곳을 한
     * 번에 물으면 동기화가 40분을 넘긴다. 넘친 것은 다음 회차(5분 뒤)가 이어 받는다.
     */

    private final MyDataClient myDataClient;
    private final AppUserRepository userRepository;
    private final UserCardRepository userCardRepository;
    private final UserPaymentRepository userPaymentRepository;
    private final ConsumptionRepository consumptionRepository;
    private final CategoryRepository categoryRepository;
    /** 업종코드 → 소비 중분류. 제공자는 업종까지만 주므로 분류는 우리가 한다. */
    private final com.finntech.engine.IndustryCategoryMapper industryMapper;
    private final MerchantCategoryService merchantCategoryService;
    /** 주소를 읽고 쓰는 자리 — 사전이 주소의 정본이다(V26). */
    private final MerchantCategoryRepository merchantCategoryRepository;
    /** 사업자번호가 한 사업인가 여러 사업인가 — 연동이 관측해 갱신한다(V16). */
    private final BusinessNumberKindService businessNumberKindService;
    /** 분류 순위 ②-b — 사업자등록번호로 등록 업종을 조회한다. 기본은 꺼져 있다. */
    private final IndustryLookupService industryLookup;
    /** 분류 순위 ③ — 40곳이 쌓이면 묶어서 묻는다. 화면 진입은 임계값 1 로 같은 것을 부른다. */
    private final MerchantAskService merchantAskService;
    /** 가맹점명 → 브랜드. 미분류와 무관하게 결제 전체를 훑는다. */
    private final MerchantBrandService merchantBrandService;
    private final UserCardCompanyRepository userCardCompanyRepository;
    private final UserBankRepository userBankRepository;
    private final ReportRepository reportRepository;
    private final java.time.Clock clock;
    /** 고정 기준일. null이면 {@link #referenceDate()}가 주입된 시계를 따른다. */
    private final LocalDate fixedReferenceDate;
    /**
     * <b>자기 자신의 프록시.</b> 적재는 트랜잭션 안에서, 바깥 서버 호출은 트랜잭션 밖에서
     * 돌려야 하는데 {@code @Transactional} 은 프록시가 걸어 주는 것이라 같은 객체 안에서
     * 그냥 부르면 안 걸린다.
     */
    private final org.springframework.beans.factory.ObjectProvider<MyDataLinkService> selfProvider;
    /**
     * <b>지금 동기화 중인 사용자.</b> 같은 사용자를 두 번 동시에 돌리지 않기 위한 것이다.
     *
     * <p>진입로가 둘이다 — 5분마다 도는 배치와 화면의 {@code POST /api/mydata/sync}. 배치는
     * {@code fixedDelay} 라 자기끼리는 안 겹치지만 화면 호출과는 겹친다. 겹치면 같은 가맹점을
     * <b>두 모델에 두 번 묻고</b>(유료 통로는 곧 돈이다), 브랜드 표에 같은 상호를 동시에 넣어
     * 유일키에 부딪힌다. 멱등이라 데이터가 깨지진 않지만 호출과 예외는 그대로 낭비다.
     */
    private final java.util.Set<Long> syncing = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * 후속 단계를 도는 <b>단 하나의 배경 일꾼.</b>
     *
     * <p><b>왜 요청 스레드에서 빼는가.</b> {@link #runFollowUps} 는 바깥 서버를 <b>순차로</b>
     * 부르고 사이에 {@code delay-ms} 만큼 쉰다 — 등록 업종 조회 최대 40곳, 주소 채우기 최대
     * 40곳, 거기에 모델 호출 둘이다. 한 곳이 최대 4초(timeout-ms)라 <b>최악은 몇 분</b>이고,
     * 주석(:214)이 "트랜잭션 밖"이라 적어 둔 것은 맞지만 <b>요청 밖은 아니었다.</b>
     * 그래서 자산연결을 누른 실사용자는 이 시간을 그대로 기다리다 게이트웨이 시한에 걸렸다
     * (2026-08-12 운영, {@code 504 /api/mydata/link}).
     *
     * <p><b>왜 이제야 났는가.</b> {@link #runFollowUps} 첫 줄이 더미 사용자를 곧바로 돌려보낸다.
     * 그동안 연동한 사람이 전부 더미라 이 길로 들어온 적이 없었다 — <b>첫 실사용자가 첫 피해자</b>다.
     *
     * <p><b>기다릴 필요가 없다.</b> 응답에 담기는 {@code LinkResult} 는 그 앞의
     * {@code loadCardCompanies} 가 이미 커밋하고 돌려준 값이다. 후속 단계는 분류·브랜드를
     * 채우는 일이고, 덜 채워진 동안 화면은 '카테고리없음'으로 정확히 표시된다. 게다가 5분 배치가
     * 같은 일을 이어받으므로 <b>여기서 놓쳐도 잃는 것이 없다.</b>
     *
     * <p><b>왜 한 스레드인가.</b> 이 통로가 지키던 성질이 "남의 서버를 연달아 두드리지 않는다"
     * 였다. 일꾼을 늘리면 그 성질이 일꾼 수만큼 깨진다 — 순차성은 유지하고 자리만 옮긴다.
     */
    /** 조회가 못 닿는 가맹점의 추정을 <b>이 사람의 원장에만</b> 반영한다. 사전은 안 건드린다. */
    private final CategoryPromotionService categoryPromotion;
    private final java.util.concurrent.Executor followUps;

    /**
     * 정리된 소비 원장(V34)을 다시 써야 한다고 적는 창구.
     *
     * <p>결제·분류 변경은 엔티티 콜백({@code LedgerDirtyListener})이 알아서 잡는다. 이 서비스가
     * 직접 부르는 곳은 <b>벌크 삭제 하나뿐</b>이다 — 그 자리는 콜백이 안 뜬다.
     */
    private final com.finntech.ledger.SpendingLedgerDirtyMarker ledgerDirtyMarker;

    public MyDataLinkService(MyDataClient myDataClient, AppUserRepository userRepository,
                             UserCardRepository userCardRepository, UserPaymentRepository userPaymentRepository,
                             ConsumptionRepository consumptionRepository, CategoryRepository categoryRepository,
                             com.finntech.engine.IndustryCategoryMapper industryMapper,
                             MerchantCategoryService merchantCategoryService,
                             MerchantCategoryRepository merchantCategoryRepository,
                             BusinessNumberKindService businessNumberKindService,
                             IndustryLookupService industryLookup,
                             MerchantAskService merchantAskService,
                             MerchantBrandService merchantBrandService,
                             UserCardCompanyRepository userCardCompanyRepository,
                             UserBankRepository userBankRepository, ReportRepository reportRepository,
                             java.time.Clock clock,
                             @Value("${finntech.mydata.reference-date:}") String referenceDate,
                             CategoryPromotionService categoryPromotion,
                             @org.springframework.beans.factory.annotation.Qualifier(
                                     FollowUpExecutorConfig.BEAN) java.util.concurrent.Executor followUps,
                             com.finntech.ledger.SpendingLedgerDirtyMarker ledgerDirtyMarker,
                             org.springframework.beans.factory.ObjectProvider<MyDataLinkService> selfProvider) {
        this.categoryPromotion = categoryPromotion;
        this.followUps = followUps;
        this.ledgerDirtyMarker = ledgerDirtyMarker;
        this.selfProvider = selfProvider;
        this.myDataClient = myDataClient;
        this.userRepository = userRepository;
        this.userCardRepository = userCardRepository;
        this.userPaymentRepository = userPaymentRepository;
        this.consumptionRepository = consumptionRepository;
        this.categoryRepository = categoryRepository;
        this.industryMapper = industryMapper;
        this.merchantCategoryService = merchantCategoryService;
        this.merchantCategoryRepository = merchantCategoryRepository;
        this.businessNumberKindService = businessNumberKindService;
        this.industryLookup = industryLookup;
        this.merchantAskService = merchantAskService;
        this.merchantBrandService = merchantBrandService;
        this.userCardCompanyRepository = userCardCompanyRepository;
        this.userBankRepository = userBankRepository;
        this.reportRepository = reportRepository;
        this.clock = clock;
        this.fixedReferenceDate = (referenceDate == null || referenceDate.isBlank())
                ? null : LocalDate.parse(referenceDate.trim());
    }

    /**
     * 링크·월집계의 기준이 되는 '오늘'.
     *
     * <p>비워 두면 주입된 {@link java.time.Clock}(= 시스템 단일 시간 출처, {@code finntech.demo.today}로
     * 고정 가능)을 따른다. 예전에는 이 값이 상수로 박혀 있어, 분석·지킴이는 실시간으로 앞서가는데
     * 링크 기준일만 과거에 멈춰 있었다 — 오늘 시작한 챌린지에 넣을 소비가 0건이 되는 원인이었다.
     * 값을 넣으면 그 날짜로 고정되지만, 그때는 {@code mydata.now}도 같이 고정해야 한다(짝이다).
     */
    private LocalDate referenceDate() {
        return fixedReferenceDate != null ? fixedReferenceDate : LocalDate.now(clock);
    }

    /** 카드사 목록(연동 기관 선택용). */
    public List<CompanyView> companies() {
        return myDataClient.findCompanies();
    }

    /**
     * 이 사람이 <b>실제로 가진</b> 카드사·은행을 찾는다 (프로토타입_0806 자산 연결).
     *
     * <p><b>흐름이 뒤집혔다.</b> 예전 화면은 기관을 사용자가 먼저 고르고 인증했다. 그러면 자기가
     * 어느 카드를 쓰는지 기억해 내야 하고, 빠뜨린 곳은 영영 연결되지 않는다. 개편안은 인증을
     * 먼저 받고 <b>"N곳을 찾았어요"</b>를 보여준 뒤 뺄 것만 해제하게 한다 — 실제 마이데이터
     * 통합인증이 그렇게 동작한다.
     *
     * <p><b>연결하지 않는다.</b> 이 조회는 읽기만 한다. 찾아 놓고 사용자가 확인 버튼을 눌러야
     * {@code link} 가 돈다 — 보여주기 위해 먼저 연결해 두면 해제가 '되돌리기'가 되고,
     * 그 사이에 동기화가 돌면 지우려던 데이터가 이미 퍼진다.
     *
     * <p>카드사는 하나씩 물어야 한다(제공자 API 가 카드사별이다). 8곳이면 왕복 8번 + 계좌 1번이다.
     * 한 곳이 실패해도 나머지는 살린다 — 한 카드사가 죽었다고 연결 자체를 못 하면 안 된다.
     *
     * <p><b>그래서 트랜잭션을 열지 않는다.</b> 왕복 아홉 번 내내 DB 커넥션을 붙잡을 이유가
     * 없다 — DB 를 만지는 것은 맨 앞의 사용자 조회 한 번뿐이고, 그것은 리포지토리가 자기
     * 트랜잭션으로 처리한다. CI 는 기본 칸이라 준영속 상태에서도 읽힌다
     * (2026-08-07 감사에서 남아 있던 네 번째 자리).
     */
    public Discovered discover(Long userId) {
        AppUser user = userRepository.findById(userId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user " + userId + " not found"));
        String ci = user.getCi();
        if (ci == null || ci.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "본인인증을 먼저 마쳐주세요");
        }

        List<CompanyView> cards = new ArrayList<>();
        for (CompanyView c : myDataClient.findCompanies()) {
            try {
                if (!myDataClient.findCards(c.id(), ci).isEmpty()) cards.add(c);
            } catch (RuntimeException e) {
                log.warn("카드사 조회 실패 — 건너뛴다. companyId={} : {}", c.id(), e.toString());
            }
        }

        List<BankView> allBanks = myDataClient.findBanks();
        List<BankView> banks = new ArrayList<>();
        try {
            // 계좌를 가진 은행 이름만 추린다. 제공자는 계좌를 은행 '이름'으로 돌려준다.
            var owned = myDataClient.findAccountsByBanks(ci, allBanks.stream().map(BankView::id).toList())
                    .stream().map(AccountView::bank).collect(Collectors.toSet());
            for (BankView b : allBanks) if (owned.contains(b.name())) banks.add(b);
        } catch (RuntimeException e) {
            log.warn("계좌 조회 실패 — 은행 없이 진행한다: {}", e.toString());
        }
        return new Discovered(cards, banks);
    }

    /** 찾은 기관. 화면은 이 둘을 합쳐 "N곳을 찾았어요"로 센다. */
    public record Discovered(List<CompanyView> cards, List<BankView> banks) {}

    /**
     * 카드사 연결 → 마이데이터에서 카드·결제 전체 조회 → UserCard/UserPayment 적재 + Consumption(MYDATA) 투영.
     * 전체 동기화: 기존 MYDATA 데이터(카드·결제·투영 소비)를 지우고 새로 적재한다.
     *
     * <p><b>여기에 트랜잭션을 붙이면 안 된다.</b> 이 메서드는 같은 객체 안에서 아래 3-인자
     * 형태를 부르므로 프록시를 거치지 않는다 — 즉 여기서 연 트랜잭션 안에서 조회·질의·브랜드가
     * 전부 돌아, 아래에서 갈라 놓은 경계가 통째로 무너진다(2026-08-07 감사에서 여기만 남아
     * 있었다). 적재만 트랜잭션이어야 하고 그것은 {@link #loadCardCompanies} 가 스스로 연다.
     */
    public LinkResult linkCardCompanies(Long userId, List<Long> companyIds) {
        return linkCardCompanies(userId, companyIds, List.of());
    }

    /**
     * 카드사와 은행을 함께 연동한다(§13 자산연결).
     *
     * <p>은행 쪽은 <b>고른 은행에 계좌가 있을 때만</b> 연동 기록을 남긴다 — 실제 마이데이터가
     * 그렇게 동작한다(체크한 기관에 자산이 없으면 아무것도 내려오지 않는다). 계좌의 잔액·내역은
     * 저장하지 않는다. 조회 시점에 계산되는 값이라 저장하면 즉시 낡는다.
     */
    public LinkResult linkCardCompanies(Long userId, List<Long> companyIds, List<Long> bankIds) {
        LinkResult result = selfProvider.getObject().loadCardCompanies(userId, companyIds, bankIds);
        // **후속 단계는 트랜잭션 밖이자 요청 밖이다.** 등록 업종 조회·분류 질의·브랜드 라벨링은
        // 전부 바깥 서버를 순차로 부르고 한 곳당 수 초가 걸린다 — 최악은 몇 분이다.
        // 예전에는 트랜잭션만 벗어나고 요청 스레드에는 남아 있어, 자산연결을 누른 실사용자가
        // 그 시간을 그대로 기다리다 게이트웨이 시한에 걸렸다(2026-08-12 · 504 /api/mydata/link).
        // 응답에 담을 것은 위에서 이미 커밋됐으므로 **기다릴 이유가 없다.**
        runFollowUpsInBackground(userId);
        return result;
    }

    /**
     * 후속 단계를 배경 일꾼에게 넘긴다 — <b>부르는 쪽은 기다리지 않는다.</b>
     *
     * <p>자물쇠는 <b>넘기기 전에</b> 잡는다. 넘긴 뒤에 잡으면 같은 사용자가 대기열에 여러 번
     * 쌓이고, 자물쇠는 결국 하나만 통과시키므로 나머지는 큐만 차지하다 버려진다.
     * 푸는 것은 일꾼이 끝낼 때다(성공이든 실패든).
     *
     * <p><b>실패는 조용하다.</b> 여기서 터져도 사용자가 할 수 있는 일이 없고, 5분 배치가 같은
     * 일을 이어받는다. 다만 <b>로그에는 남긴다</b> — 조용한 실패와 조용한 성공이 구별되어야 한다.
     */
    private void runFollowUpsInBackground(Long userId) {
        // 온보딩 중에 5분 배치가 같은 사용자를 집어 들 수 있다. 적재는 이미 끝났으므로
        // 후속 단계만 건너뛴다(다음 회차가 이어받는다).
        if (!syncing.add(userId)) {
            log.debug("이미 돌고 있어 후속 단계를 건너뜀 — userId={}", userId);
            return;
        }
        try {
            followUps.execute(() -> {
                try {
                    runFollowUps(userId);
                } catch (RuntimeException exception) {
                    log.warn("후속 단계 실패 — userId={} {}", userId, exception.toString());
                } finally {
                    syncing.remove(userId);
                }
            });
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            // 큐가 찼다. 버려도 안전하다 — 다음 5분 회차가 같은 일을 한다.
            syncing.remove(userId);
            log.info("후속 단계 대기열이 찼다 — 다음 회차가 이어받는다. userId={}", userId);
        }
    }

    /**
     * <b>바깥 서버를 부르는 후속 단계</b> — 트랜잭션 밖에서 돈다.
     *
     * <p>연동과 증분 동기화가 같은 것을 한다. 두 곳에 적으면 한쪽만 고쳐져 갈라진다.
     */
    private void runFollowUps(Long userId) {
        // **더미 사용자는 여기서 끝난다.** 아래 세 단계는 전부 실사용자만 상대한다 — 조회는
        // 더미 번호가 등록부에 없어서, 질의·브랜드는 더미 상호로 표를 넓힐 수 없어서다. 그런데
        // 그 판단이 각 단계 **안쪽**에 있어, 안 쓸 결제를 전부 읽고 나서 버렸다: 연동된 13명 중
        // 12명이 더미이고 전원 미분류 결제가 있어 관문을 통과했다. 5분 회차마다
        // `lookupUnknownIndustries` 와 `labelBrands` 가 각각 그 사용자의 결제를 통째로 읽어
        // **3만 8천 행을 읽고 439건을 처리**하고 있었다(2026-08-07 실측).
        //
        // 게이트를 여기 하나 더 두는 것이 요점이다 — **뒤에 단계가 하나 늘어도 자동으로 막힌다.**
        // 안쪽 게이트는 지우지 않는다. 저장하는 자리의 방벽은 그 자리에 있어야 한다(§13-13).
        if (!selfProvider.getObject().realPerson(userId)) {
            log.debug("더미 사용자 — 조회·질의·브랜드를 건너뛴다. userId={}", userId);
            return;
        }
        // **조건은 '새 결제'가 아니라 '할 일'이다.** 새 결제가 있을 때만 조회하면 이미 쌓인
        // 미분류는 영영 안 물어본다 — 새 결제가 안 오는 한 계속 '카테고리없음'으로 남는다
        // (2026-08-07 실측: 실사용자 미분류 53곳이 그 상태였다).
        //
        // 그래도 5분마다 바깥 서버를 두드리지는 않는다. 미분류 개수 하나로 먼저 걸러(값싼 질의)
        // 할 일이 없으면 결제를 읽지도 않고, 물어본 가맹점은 사전에 시도 이력이 남아 다시 묻지
        // 않는다. 그래서 반복 호출은 사전이 차는 만큼 저절로 마른다.
        int reclassified = 0;
        if (userPaymentRepository.countByUserIdAndCategory2(
                userId, com.finntech.engine.IndustryCategoryMapper.UNCLASSIFIED) > 0) {
            reclassified += lookupUnknownIndustries(userId);
            // **최초 연동은 크게 한 번 훑는다.** 사람이 로딩 화면 앞에서 기다리는 유일한
            // 자리라, 여기서만 유료 통로로 40곳씩 묶어 묻는다(2026-08-21 사용자 결정).
            // 많이 남았을 때만 깨어나고, 못 맞힌 것은 무료 통로가 한 곳씩 이어받는다.
            reclassified += merchantAskService.askInBulk(userId);
            // **③ LLM 은 40곳이 쌓여야 부른다.** 조회(②-b)와 달리 임계값을 두는 이유는 값이
            // 묶음 크기에 좌우되기 때문이다 — 프롬프트의 76%가 업종 목록(385종)이라 1곳을 묻든
            // 40곳을 묻든 값이 거의 같고, 1곳씩 40번 부르면 40배가 든다. 조회는 번호 하나만
            // 보내므로 그런 낭비가 없어 발견 즉시 한다.
            //
            // 기다리는 동안 화면은 '카테고리없음'이고 그것이 정확한 표현이다(낭비 판정에서도
            // 빠진다). 그리고 기다림이 눈에 띄는 순간 — 사용자가 화면을 열 때 — 에는 임계값이
            // 1 이라 남은 것을 전부 몰아 묻는다. 그래서 체감 지연이 없다.
            var asked = merchantAskService.ask(userId, MerchantAskService.BACKGROUND_MIN);
            if (asked != null) reclassified += asked.settled().size();

            // **추정을 이 사람의 원장에 반영한다**(2026-08-12 사용자 결정).
            //
            // 조회(②-b)가 사실을 못 주는 자리가 있다 — PG·상품권은 한 사업자번호에 토스페이·
            // 카카오페이·기프티스타가 함께 붙어, 물어봐야 결제대행사의 업종만 나온다. 운영
            // 로그가 `물어본 곳 24, 분류된 가맹점 0` 을 2분마다 반복했다. 그 결제들은 답이
            // 있는데도 추정층에 머물고, 계산이 읽는 `Consumption` 에는 확정만 들어가므로
            // 화면 110,680원이 서버에서 1,200원이 됐다 — 온보딩이 시작조차 안 됐다.
            //
            // **전역 사전은 안 건드린다.** 바뀌는 것은 이 사람의 원장뿐이라 오분류가 안 번진다.
            // 출처를 `LLM_LOCAL` 로 남겨, 뒤에 등록 업종 조회나 사람의 확정이 오면 덮인다.
            reclassified += categoryPromotion.applyEstimates(userId);
        }
        // **주소를 회차마다 조금씩 채운다.**
        //
        // 업종 조회는 *미분류* 결제만 훑는다. 그래서 이미 분류가 끝난 가맹점은 주소를 얻을
        // 기회가 없고, 실제로 실사용자 번호 132종 중 5%만 주소가 떴다 — 그 5%도 제공자가
        // 우연히 아는 번호였을 뿐이다. 분류와 무관하게 따로 훑어야 채워진다.
        //
        // 조회처를 연달아 두드리지 않게 **순차로, 회차당 상한까지만** 한다(`pause` 를 사이에 둔다).
        fillMissingAddresses();

        // **브랜드는 미분류와 무관하게 돈다** — 이미 분류된 가맹점도 브랜드가 필요하다.
        labelBrands(userId);

        // **번호별 판정을 회차마다 다시 관측한다**(V16).
        //
        // 예전에는 새 결제가 들어왔을 때만(`pullNewPayments` 의 `added > 0`) 돌았다. 그런데
        // V24 가 더미로 채워졌던 판정 표를 비웠고, 새 결제가 안 오면 **재구축이 영영 안 온다.**
        // 여기까지 온 것은 실사용자뿐이라(위 관문) 결제 439건을 한 번 훑는 것이고, 관측 자체는
        // 상호가 둘 이상인 번호에만 행을 만든다.
        selfProvider.getObject().observeAll(userId);

        // **소비 원장을 고쳤으면 리포트 캐시를 깬다.**
        //
        // 어제까지는 `renew` 하나가 통째로 트랜잭션이라 '캐시 삭제'(적재)와 '재분류'(후속)가
        // 같은 커밋이었다. 지금은 갈라져서, 적재가 커밋한 뒤 후속 단계가 수 분을 쓰고, 그 사이에
        // 사용자가 리포트를 열면 **아직 '카테고리없음'인 값이 캐시로 굳는다.** 그 뒤 조회·종결이
        // 분류를 고쳐도 화면은 옛 숫자 그대로다 — 2026-08-05 에 고쳤던 "조회로 알아낸 분류가
        // 화면에 안 나온다"와 같은 증상이 캐시 층에서 되살아난 것이다(2026-08-07 재감사).
        //
        // **고친 것이 있을 때만 깬다.** 5분마다 0건인데도 날리면 캐시가 캐시 노릇을 못 한다.
        if (reclassified > 0) {
            reportRepository.deleteByUserId(userId);
            log.info("분류가 바뀌어 리포트 캐시를 지웠다 — userId={} 고친 가맹점·결제 {}", userId, reclassified);
        }
    }

    /**
     * 이 사용자가 <b>실제 사람</b>인가 — 그리고 <b>스스로 고친다.</b>
     *
     * <p>{@code app_user.real_person} 은 적재가 정한다. 그런데 적재를 다시 돌리지 않으면 갱신될
     * 일이 없어, 표시가 <b>거짓으로 굳는</b> 상태가 만들어질 수 있다 — 결제는 이미 있는데 칸만
     * 나중에 생긴 DB(백필이 없는 개발 H2 가 그렇다), 또는 증분만 돌고 재연동이 없던 사용자.
     * 증분은 이미 있는 결제를 {@code existsById} 로 건너뛰므로 그 결제로는 켜 주지도 못한다.
     *
     * <p><b>그렇게 되면 아무 오류 없이 실사용자의 조회·질의·브랜드가 통째로 멈춘다.</b> 표시가
     * 틀리는 두 방향 중 이쪽이 훨씬 나쁘다 — 더미가 한 번 더 도는 것은 낭비지만, 실사용자가
     * 안 도는 것은 기능이 죽은 것인데 로그도 안 남는다.
     *
     * <p>그래서 <b>"아니다"일 때만</b> 원장으로 되짚는다. 참이면 칸이 바로 답하므로 값이 안 들고,
     * 거짓이면 그 사용자에 한정된 질의 한 번이다. 한 번 참으로 밝혀지면 칸에 적어 두어 다시
     * 오지 않는다.
     */
    @Transactional
    public boolean realPerson(Long userId) {
        AppUser user = userRepository.findById(userId).orElse(null);
        if (user == null) return false;
        if (user.isRealPerson()) return true;
        if (!userPaymentRepository.existsRealPersonPaymentByUserId(userId)) return false;
        log.info("실물 표시가 원장과 어긋나 있어 바로잡는다 — userId={}", userId);
        user.setRealPerson(true);
        userRepository.save(user);
        return true;
    }

    /** 조회로 알아낸 주소를 사전에 적는다 — 프록시를 거쳐 자기 트랜잭션을 연다. */
    @Transactional
    public void rememberAddress(String businessNumber, String merchantName, String address) {
        merchantCategoryService.rememberAddress(businessNumber, merchantName, address);
    }

    /**
     * <b>주소가 빈 사전 행을 순차로 채운다</b> — 회차당 상한까지만.
     *
     * <p>여기 오는 것은 실사용자뿐이다(위 관문). 사전에 자리가 있다는 것은 이미 실사용자 게이트를
     * 통과했다는 뜻이라, 여기서 다시 가릴 것이 없다.
     *
     * <p>업종 조회와 <b>같은 통로·같은 문서</b>를 쓴다. 즉 주소를 위해 새로 만든 호출이 아니라,
     * 이미 있던 조회를 아직 안 물어본 번호에 마저 하는 것이다.
     */
    private void fillMissingAddresses() {
        if (!industryLookup.usable()) return;
        var rows = merchantCategoryService.missingAddress(industryLookup.maxPerSync());
        if (rows.isEmpty()) return;
        int filled = 0, asked = 0, missed = 0;
        for (MerchantCategory row : rows) {
            String biz = row.getBusinessNumber();
            if (!industryLookup.askable(biz)) continue;      // PG·복합은 물어도 소용없다
            asked++;
            var found = industryLookup.lookup(biz);
            industryLookup.pause();                          // 남의 서버를 연달아 두드리지 않는다
            String addr = found.map(IndustryLookupService.Found::address).orElse(null);
            if (addr == null) {
                // **물었는데 없더라를 적는다.** 안 적으면 회차마다 같은 번호를 다시 묻는다 —
                // 조회처에 주소칸이 없는 사업자가 실재해서(2026-08-08 운영 7곳) 성공이 영영
                // 안 오고, 하루 2,016회가 남의 서버로 헛나갔다.
                selfProvider.getObject().noteAddressMiss(row.getId());
                missed++;
                continue;
            }
            if (selfProvider.getObject().storeAddress(row.getId(), addr)) filled++;
        }
        if (asked > 0) {
            log.info("가맹점 주소 채우기 — 대상 {}, 물어본 곳 {}, 채워진 곳 {}, 없던 곳 {}",
                    rows.size(), asked, filled, missed);
        }
    }

    /** 행 하나에 주소를 적는다 — 백필이 부르는 짧은 쓰기 트랜잭션. */
    @Transactional
    public boolean storeAddress(Long rowId, String address) {
        return merchantCategoryRepository.findById(rowId)
                .map(r -> r.noteAddress(address)).orElse(false);
    }

    /** 물었는데 주소가 없더라를 적는다 — 같은 번호를 회차마다 다시 묻지 않기 위해서다. */
    @Transactional
    public void noteAddressMiss(Long rowId) {
        merchantCategoryRepository.findById(rowId)
                .ifPresent(r -> r.noteAddressMiss(LocalDateTime.now(clock)));
    }

    /** 연동의 <b>적재 부분</b> — DB 만 만진다. 바깥 서버를 부르는 일은 여기 들어오지 않는다. */
    @Transactional
    public LinkResult loadCardCompanies(Long userId, List<Long> companyIds, List<Long> bankIds) {
        AppUser user = userRepository.findById(userId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user " + userId + " not found"));
        String ci = user.getCi();
        if (ci == null || ci.isBlank()) {
            throw new IllegalStateException("본인인증(가상 CI)이 먼저 필요합니다");
        }
        // 개인정보 수집 동의가 없으면 연동(=카드·결제 수집·투영) 불가 — 소비 입력의 403 패턴과 동일(W7-5c).
        if (!user.isConsentGiven()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "개인정보 수집 동의가 필요합니다");
        }

        userCardRepository.deleteByUserId(userId);
        userPaymentRepository.deleteByUserId(userId);
        // **벌크 삭제에는 엔티티 콜백이 안 뜬다.** 뒤이은 적재가 어차피 표시하겠지만, 결제가
        // 0건인 카드사로 재연동하면 넣을 것이 없어 콜백도 없고 — 소비 원장이 없어진 결제의
        // 줄을 그대로 안고 남는다. 그래서 여기서 손으로 표시한다.
        ledgerDirtyMarker.mark(userId, com.finntech.domain.SpendingLedgerDirty.Reason.PAYMENT);
        consumptionRepository.deleteByUserIdAndSource(userId, Enums.DataSource.MYDATA);
        userCardCompanyRepository.deleteByUserId(userId);
        userBankRepository.deleteByUserId(userId);
        reportRepository.deleteByUserId(userId);   // 판정 소스(ML)·데이터가 바뀌므로 리포트 캐시 무효화

        LocalDate today = referenceDate();
        YearMonth referenceMonth = YearMonth.from(today);
        LocalDateTime linkTime = LocalDateTime.now(clock);
        int cardCount = 0, paymentCount = 0;
        // **실물 여부는 여기서 정해진다.** 연동은 결제를 전부 지우고 다시 넣으므로 이 한 번의
        // 훑기가 곧 답이다 — 손으로 켜는 표시가 아니라 데이터에서 나온 값이라 어긋날 자리가 없다.
        boolean anyReal = false;
        // 사전은 루프 밖에서 **한 번만** 읽는다. 결제마다 조회하면 수천 번 질의가 나간다.
        MerchantCategoryService.Snapshot dict = merchantCategoryService.snapshot();

        for (Long companyId : companyIds) {
            String companyName = null;
            // 이 카드사에서 실제로 받아온 마지막 결제 시각. 다음 증분의 기준선이 된다.
            LocalDateTime lastPayment = null;
            for (CardView card : myDataClient.findCards(companyId, ci)) {
                companyName = card.cardProduct().company().name();
                int requirement = requirementOf(card);
                int currentPerformance = performanceOf(card, referenceMonth);
                // 전월 실적도 우리가 센다 — 제공자는 주지 않는다(실 마이데이터에 그 필드가 없다).
                int prevPerformance = performanceOf(card, referenceMonth.minusMonths(1));
                userCardRepository.save(new UserCard(userId, card.cardId(), card.cardProduct().code(),
                        card.cardProduct().name(), card.cardProduct().color(),
                        card.cardProduct().company().name(), prevPerformance,
                        currentPerformance, requirement));
                cardCount++;

                for (PaymentView payment : card.payments()) {
                    // 업종코드 → 우리 소비 중분류(결정론 1:1). 예전에는 제공자의 7대분류를 그대로
                    // 카테고리 코드로 썼는데, 그 축이 업종과 소비종류를 겸해 왜곡이 났다.
                    // 그 앞에 **확정 분류 사전**을 둔다 — 실제 명세서에는 업종코드가 없기 때문이다.
                    var fromDict = dict.lookup(payment.businessNumber(), payment.merchantName());
                    String mid = fromDict.orElseGet(
                            () -> industryMapper.midOf(payment.industryCode(), payment.businessNumber()));
                    UserPayment row = new UserPayment(
                            UserPayment.rowId(userId, payment.id()), userId, card.cardId(),
                            payment.cardCode(), payment.date(), payment.industryCode(), mid,
                            payment.amount(), payment.merchantName(),
                            payment.businessNumber());
                    // 사전에서 붙은 것은 근거가 사람이라 **처음부터 확정**이다(§F 격리 대상이 아니다).
                    if (fromDict.isPresent()) row.confirmCategory2(mid, "DICT");
                    else applyRemembered(dict, row);
                    userPaymentRepository.save(row);
                    anyReal |= row.isFromRealPerson();
                    Category category = categoryRepository.findByCode(mid)
                            .orElseGet(() -> categoryRepository.save(new Category(mid, mid)));
                    // 결제 키를 달고 간다 — 원장이 나중에 이 소비의 가맹점을 되찾는 유일한 길이다(V15).
                    consumptionRepository.save(new Consumption(userId, category,
                            BigDecimal.valueOf(payment.amount()), payment.date(), false,
                            Enums.DataSource.MYDATA)
                            .withSourcePayment(UserPayment.rowId(userId, payment.id())));
                    paymentCount++;
                    if (lastPayment == null || payment.date().isAfter(lastPayment)) lastPayment = payment.date();
                }
            }
            if (companyName != null) { // 카드가 있던 카드사만 연동 기록(다음 동기화 증분 기준)
                // 증분 기준선은 '실제로 받아온 마지막 결제 시각'이어야 한다.
                // 예전에는 연동한 날의 23:59:59로 앞질러 찍었는데, 마이데이터 서버는 커트오프(지금)까지만
                // 주므로 연동 시점부터 자정까지의 결제가 기준선 뒤로 밀려 영영 안 들어왔다.
                // 오전에 연동하면 그날 낮 결제가 통째로 사라지고, 지킴이 차감·판정도 그만큼 비었다.
                // 결제가 하나도 없던 카드사는 넉넉히 과거로 잡아 다음 증분이 무엇이든 집어오게 한다(멱등).
                LocalDateTime since = lastPayment != null ? lastPayment : today.minusMonths(12).atStartOfDay();
                userCardCompanyRepository.save(
                        new UserCardCompany(userId, companyId, companyName, linkTime, since));
            }
        }
        // 은행 — 고른 은행에 계좌가 있을 때만 연동 기록을 남긴다. 없으면 아무것도 남기지 않는 것이
        // 정확한 답이다(실제 마이데이터도 자산이 없는 기관에서는 아무것도 내려주지 않는다).
        int bankCount = 0;
        if (bankIds != null && !bankIds.isEmpty()) {
            List<BankView> banks = myDataClient.findBanks();
            for (MyDataResponses.AccountView acc : myDataClient.findAccountsByBanks(ci, bankIds)) {
                Long bankId = banks.stream()
                        .filter(b -> b.name().equals(acc.bank())).map(BankView::id).findFirst().orElse(null);
                if (bankId == null) continue;   // 제공자 목록에 없는 은행 — 남길 id가 없다
                userBankRepository.save(new UserBank(userId, bankId, acc.bank(), linkTime));
                bankCount++;
            }
        }

        // 입출금 통장의 월급을 앱 사용자 월급(=예산)으로 반영(§13-11 경제 모델). 통장 없으면 기존값 유지.
        MyDataResponses.AccountView account = myDataClient.findAccount(ci);
        if (account != null && account.salary() > 0) {
            user.setMonthlyIncome(BigDecimal.valueOf(account.salary()));
            userRepository.save(user);
        }
        // 이번 적재가 본 것으로 실물 여부를 정한다. 재연동으로 실물이 빠지면 꺼지는 것이 맞다 —
        // 표시가 데이터를 앞서면 더미에 실사용자 대접을 하게 된다.
        user.setRealPerson(anyReal);
        userRepository.save(user);
        // **번호별로 상호를 관측해 판정을 갱신한다**(V16). 앱은 결제를 건별로 보므로 조회 시점에는
        // 한 번호에 다른 상호가 있는지 알 수 없다 — **연동만이 그 사용자의 결제를 전부 본다.**
        observeBusinessNumbers(userId);
        // 조회·질의·브랜드는 여기서 하지 않는다 — 이 메서드가 트랜잭션 안이기 때문이다.
        // **관측 다음이라야 한다**는 순서는 그대로다: 부르는 쪽(linkCardCompanies)이
        // 이 메서드가 끝난 뒤에 후속 단계를 돌린다.

        log.info("마이데이터 연동 완료 — userId={} 카드사 {}개, 카드 {}장, 결제 {}건, 은행 {}곳 적재",
                userId, companyIds.size(), cardCount, paymentCount, bankCount);
        return new LinkResult(cardCount, paymentCount, bankCount);
    }

    /**
     * <b>분류 순위 ②-b</b> — 아직 분류가 없는 가맹점의 <b>등록 업종을 조회</b>해 중분류를 붙인다.
     *
     * <p><b>관측 판정 다음에 선다</b>({@link #observeBusinessNumbers}). 조회를 건너뛸지 말지를
     * "이 번호에 상호가 여럿인가"로 판단하는데, 그 사실은 방금 적재한 결제까지 봐야 알 수 있다.
     * 순서가 뒤집히면 한 번호에 앱스토어와 OTT 가 같이 달린 것을 모른 채 번호 하나의 업종으로
     * 칠하게 된다.
     *
     * <p><b>루프 밖에서 한 번에 한다.</b> 결제 건마다 부르면 수천 번 바깥 호출이 나가 연동이
     * 멈춘 것처럼 보인다. 가맹점 단위로 접어서 물어야 할 곳만 남긴 뒤, 상한({@code maxPerSync})
     * 까지만 묻는다. 넘친 것은 다음 연동이 이어 받는다 — 물어본 것은 사전에 쌓이므로 같은 곳을
     * 두 번 묻지 않기 때문이다.
     *
     * <p>답을 <b>못 붙였어도 기록한다.</b> '아파트 건설업'이라는 답을 받고 버리면 다음 연동에서
     * 같은 번호를 또 조회한다. 조회는 성공했고 그 업종이 소비 업종이 아니었다는 것도 사실이다.
     *
     * @return 이번에 분류가 붙은 가맹점 수
     */
    private int lookupUnknownIndustries(Long userId) {
        if (!industryLookup.usable()) return 0;
        List<UserPayment> rows = userPaymentRepository.findByUserIdOrderByPaymentDateDesc(userId);
        // 가맹점 단위로 접는다 — 정렬을 고정해 같은 입력이 같은 순서로 처리되게 한다(§4 원칙 3).
        Map<String, UserPayment> targets = new java.util.TreeMap<>();
        Map<String, String> found = new java.util.LinkedHashMap<>();   // 가맹점명 → 붙일 중분류
        for (UserPayment p : rows) {
            if (!p.isFromRealPerson()) continue;                     // 더미 번호는 등록부에 없다
            if (!com.finntech.engine.IndustryCategoryMapper.UNCLASSIFIED.equals(p.getCategory2())) continue;
            String biz = com.finntech.domain.MerchantCategory.normalize(p.getBusinessNumber());
            String name = p.getMerchantName();
            if (biz.length() != 10 || name == null || name.isBlank()) continue;
            // **사전이 이미 아는 것부터 입힌다.** 앞선 실행이 사전에 넣어 두고 결제를 못 고쳤다면
            // 그 결제는 영영 '카테고리없음'이다 — 사전이 아니까 다시 묻지도 않는다. 사전과 원장이
            // 갈라진 채로 굳는 자리라, 물어보기 전에 먼저 맞춘다(2026-08-07 비아인키노).
            var known = merchantCategoryService.lookup(biz, name)
                    .filter(m -> !com.finntech.engine.IndustryCategoryMapper.isUnknown(m));
            if (known.isPresent()) {
                found.putIfAbsent(name, known.get());
                continue;
            }
            // **물어볼 수 없는 곳은 시도 이력도 만들지 않는다.** 예전에는 여기를 그냥 통과시키고
            // 조회 함수 안에서 걸렀는데, 그 사이에 이력 행이 만들어져 PG 가맹점마다 빈 행이
            // 쌓이고 동기화마다 다시 쓰였다(2026-08-07 실측: 6곳). 답을 못 얻을 것이 확실한
            // 곳에 "물어봤다"를 적는 것은 기록이 아니라 잡음이다.
            if (!industryLookup.askable(biz)) continue;
            // **쉬는 시간까지 본다.** 답 없는 곳을 2분마다 다시 묻던 자리다 — 운영 로그가
            // `대상 33, 물어본 곳 24, 분류 0` 을 끝없이 반복했다(2026-08-13, 하루 약 7,000회).
            if (!merchantCategoryService.needsRegistryLookup(biz, name)) continue;
            targets.putIfAbsent(biz + '' + name, p);
        }
        // **물어볼 곳이 없어도 여기서 끝내지 않는다** — 사전이 아는 것을 입히는 일이 남았다.
        LocalDateTime now = LocalDateTime.now(clock);
        int asked = 0;
        for (UserPayment p : targets.values()) {
            if (asked >= industryLookup.maxPerSync()) break;
            String biz = com.finntech.domain.MerchantCategory.normalize(p.getBusinessNumber());
            var row = merchantCategoryService.attemptRow(p);
            if (row.isPresent() && row.get().registryAnswered()) continue;   // 이미 답을 받아 뒀다
            asked++;
            // 이름을 `found` 로 두면 위의 '가맹점명 → 중분류' 맵과 부딪힌다.
            var looked = industryLookup.lookup(biz);
            industryLookup.pause();
            var answer = looked.map(IndustryLookupService.Found::industry)
                    .filter(v -> v != null && !v.isBlank());
            merchantCategoryService.noteLookup(p, answer.orElse(null), now);
            // **주소는 덤이다.** 같은 문서에 들어 있으므로 호출이 안 는다 — 지금까지 버리고 있었다.
            String addr = looked.map(IndustryLookupService.Found::address).orElse(null);
            if (addr != null) selfProvider.getObject().rememberAddress(biz, p.getMerchantName(), addr);
            // **코드를 손에 쥔 채로 내려간다**(V29). 예전에는 이름에서 곧장 중분류를 얻어
            // 가운데 칸(국세청 코드)이 지역변수로 사라졌고, 그래서 이 통로로 들어온 사전 행은
            // 근거 없이 답만 들었다 — 나중에 대조표를 고쳐도 다시 계산할 길이 없었다.
            List<String> codes = answer.map(industryMapper::codesOfFineName).orElse(List.of());
            String mid = codes.isEmpty() ? null : industryMapper.midOfCodes(codes);
            if (mid == null || com.finntech.engine.IndustryCategoryMapper.UNCLASSIFIED.equals(mid)) {
                continue;                                                    // 소비 업종이 아니다 → LLM 이 받는다
            }
            if (merchantCategoryService.rememberRegistry(p, mid, codes).isPresent()) {
                found.put(p.getMerchantName(), mid);
            }
        }
        int resolved = selfProvider.getObject().applyResolved(userId, found);
        // **대상이 있으면 언제나 남긴다.** 예전에는 `asked > 0` 일 때만 찍었는데, 그러면
        // "물어볼 대상이 하나도 안 잡힌다"는 상황이 로그에 흔적을 안 남겨 원인을 못 좁혔다
        // (2026-08-07 운영: 조회가 도는지조차 알 수 없었다). 0 도 정보다.
        if (!targets.isEmpty() || !found.isEmpty()) {
            log.info("등록 업종 조회 — userId={} 대상 {}, 물어본 곳 {}, 분류된 가맹점 {}, 고쳐진 결제 {}건, 남은 곳 {}",
                    userId, targets.size(), asked, found.size(), resolved,
                    Math.max(0, targets.size() - asked));
        }
        return resolved;
    }

    /**
     * <b>브랜드가 없는 결제를 전부 라벨링한다</b> — 미분류인지와 무관하다.
     *
     * <p>부르는 자리가 요점이다. 예전에는 미분류 처리 흐름 안에서 불러 <b>이미 분류된 가맹점은
     * 브랜드를 얻을 기회가 없었다</b>(2026-08-07 운영: 273곳 중 15곳만 붙었다). 그런데 브랜드의
     * 값어치가 바로 거기 있다 — 한 지점이 분류되면 나머지 지점에 물려주는 것.
     *
     * <p>회차마다 조금씩 나아간다. 카탈로그로 붙는 것은 호출이 없어 한 번에 다 처리되고,
     * 모델에 묻는 것만 상한에 걸려 다음 회차로 넘어간다.
     */
    private void labelBrands(Long userId) {
        List<UserPayment> rows = userPaymentRepository.findByUserIdOrderByPaymentDateDesc(userId);
        if (rows.isEmpty()) return;
        // **실제 사람의 결제만 본다.** 더미의 상호는 생성기가 조립한 것이라 브랜드 표에 쌓을
        // 것이 아니고, 훑어 봐야 버려질 이름을 세는 일이라 그냥 여기서 끝낸다.
        // (2026-08-07 운영: 전원을 훑었더니 표가 273곳용인데 4,742줄로 불었다.)
        List<String> names = rows.stream()
                .filter(UserPayment::isFromRealPerson)
                .map(UserPayment::getMerchantName)
                .filter(n -> n != null && !n.isBlank())
                .distinct().toList();
        if (names.isEmpty()) return;
        // **큐에 올리고 끝난다.** 예전에는 여기서 회차당 20곳을 직접 물었다(`BRAND_ASKS_PER_SYNC`).
        // 그러면 이 흐름이 통로 예산을 혼자 정하게 되는데, 같은 통로를 문장 갱신도 쓴다 —
        // 각자 상한을 두면 합이 안 맞는다. 예산과 순서는 통로를 통째로 보는 큐가 정한다.
        merchantBrandService.enqueuePending(names, java.util.Set.copyOf(names));
    }

    /**
     * 새로 알아낸 분류를 <b>결제와 소비에 실제로 입힌다</b> — 사전에만 적으면 화면은 그대로다.
     *
     * <p>이것이 빠져 있었다. 조회가 '생활'을 알아내 사전에 넣었는데 결제 원장은 여전히
     * '카테고리없음'이었다(2026-08-07 실측 — 비아인키노). 리포트·판정이 읽는 것은
     * {@link Consumption} 이고 그 카테고리는 <b>적재할 때 박힌 값</b>이라, 짝을 함께 고쳐야
     * 반영된다. 사람이 화면에서 확정할 때 {@code MerchantCategoryController} 가 하는 일과 같다.
     *
     * <p><b>사람이 이미 정한 결제는 건드리지 않는다.</b> 조회는 사실이지만 "이 결제가 무엇에 쓴
     * 돈인가"에 대한 답은 사용자가 위다.
     *
     * @return 고쳐진 결제 건수
     */
    @Transactional
    public int applyResolved(Long userId, Map<String, String> found) {
        if (found.isEmpty()) return 0;
        // **여기서 다시 읽는다.** 조회는 트랜잭션 밖에서 돌므로 그때 손에 든 결제 엔티티는
        // 영속 상태가 아니다 — 거기에 값을 넣어 봐야 아무 데도 안 써진다. 고치는 순간에
        // 트랜잭션을 열고 그 안에서 읽은 것을 고쳐야 반영된다(2026-08-07 감사).
        List<UserPayment> rows = userPaymentRepository.findByUserIdOrderByPaymentDateDesc(userId);
        Map<String, Category> categories = new java.util.HashMap<>();
        int fixed = 0;
        for (UserPayment p : rows) {
            String mid = found.get(p.getMerchantName());
            if (mid == null || mid.equals(p.getCategory2())) continue;
            if ("USER".equals(p.getCategory2Source())) continue;      // 사람의 판단이 위다
            p.confirmCategory2(mid, "REGISTRY");
            Category category = categories.computeIfAbsent(mid, code ->
                    categoryRepository.findByCode(code)
                            .orElseGet(() -> categoryRepository.save(new Category(code, code))));
            for (Consumption c : consumptionRepository.findBySourcePaymentId(p.getPaymentId())) {
                c.reclassify(category);
            }
            fixed++;
        }
        return fixed;
    }

    /**
     * 그 사용자의 결제를 훑어 <b>번호별 상호</b>를 관측하고 판정을 갱신한다(V16).
     *
     * <p>여기가 유일한 자리다. 조회는 결제 한 건만 보므로 "이 번호에 다른 상호가 있는가"를 알 수
     * 없고, 그걸 아는 것은 <b>그 사용자의 결제를 전부 보는 연동</b>뿐이다.
     *
     * <p>사람이 확정한 것만 <b>뒤집는 증거</b>로 센다. 추정끼리 갈렸다고 굳은 판정을 뒤집으면,
     * 모델이 한 번 흔들릴 때마다 완화가 꺼져 사전 재사용이 무너진다.
     *
     * <p><b>실제 사람의 결제만 관측한다.</b> {@code business_number_kind} 는 사용자별 표가 아니라
     * <b>전역 판정 표</b>이고, 사전 조회({@code MerchantCategoryService.find} ⑤)가 실사용자에게
     * 그것을 적용한다. 그래서 여기에 더미가 들어오면 <b>생성기가 실사용자의 분류를 정한다.</b>
     *
     * <p>실제로 그랬다(2026-08-07 실측). 표 22행 중 <b>20행이 더미 결제만으로</b> 만들어져
     * 있었고, 가장 나쁜 것은 티머니({@code 1048183559}) — 생성기와 실제 사람이 <b>같이 쓰는
     * 실재 번호</b>다. 판정에 쓰인 상호 8종은 더미의 {@code 티머니·(주)티머니·TMONEY·온다택시}
     * 였고, 실물 쪽 {@code 카카오택시-서울33바2592} 류 25종은 세어지지 않았다. 그렇게 {@code SINGLE}
     * 로 굳으면 완화가 열려 <b>그 번호의 실사용자 결제 전부가 형제 행의 분류를 물려받는다.</b>
     */
    /** 후속 단계에서 부르는 자리 — 트랜잭션 밖이라 프록시를 거쳐 자기 트랜잭션을 연다. */
    @Transactional
    public void observeAll(Long userId) {
        observeBusinessNumbers(userId);
    }

    private void observeBusinessNumbers(Long userId) {
        Map<String, Map<String, String>> byNumber = new java.util.HashMap<>();
        Map<String, Map<String, String>> confirmed = new java.util.HashMap<>();
        for (UserPayment p : userPaymentRepository.findByUserIdOrderByPaymentDateDesc(userId)) {
            if (!p.isFromRealPerson()) continue;   // 전역 판정 표에 더미를 넣지 않는다
            String biz = p.getBusinessNumber();
            String name = p.getMerchantName();
            if (biz == null || biz.isBlank() || name == null || name.isBlank()) continue;
            if (industryMapper.isPaymentAgency(biz)) continue;   // PG 는 번호 자체가 남의 것이다
            // **'기타'는 분류가 아니다.** "다 해 봤지만 알 수 없었다"는 결론이지 "이것을 판다"가
            // 아니라서, 관측에서 진짜 중분류처럼 세면 안 된다. 세면 이렇게 된다 — 한 상호가
            // LLM 3회 침묵으로 종결되는 순간 그 번호의 관측이 {교통/자동차, 기타} 두 종이 되어
            // MULTI 로 굳고, MULTI 는 관측으로 되돌아오지 않는다. 그러면 그 번호의 완화가
            // 영구히 닫혀 새 택시 상호(승차마다 차량번호가 달라 매번 새 상호다)가 전부
            // '카테고리없음'으로 남는다(2026-08-07 재감사). '카테고리없음'과 같이 다뤄야 한다.
            String mid = com.finntech.engine.IndustryCategoryMapper.isUnknown(p.getCategory2())
                    ? null : p.getCategory2();
            byNumber.computeIfAbsent(biz, k -> new java.util.HashMap<>()).put(name, mid);
            if ("USER".equals(p.getCategory2Source())) {
                confirmed.computeIfAbsent(biz, k -> new java.util.HashMap<>()).put(name, mid);
            }
        }
        LocalDateTime now = LocalDateTime.now(clock);
        byNumber.forEach((biz, observed) -> businessNumberKindService.observe(
                biz, observed, confirmed.getOrDefault(biz, Map.of()), now));
    }

    /**
     * 가맹점 조회(번호→주소) — 결제에 실린 사업자번호로 가맹점명·지번주소를 제공자에서 조회(프록시). 없으면 null.
     *
     * <p>DB 를 하나도 만지지 않는다 — 그래서 트랜잭션도 열지 않는다. 열어 두면 바깥 왕복
     * 한 번 동안 커넥션만 잡고 있게 된다.
     */
    public MyDataResponses.MerchantView merchant(String businessNumber) {
        // **사전을 먼저 본다.** 조회처가 알려 준 주소가 여기 쌓인다. 제공자는 생성기가 만든
        // 번호만 알아서 실사용자 번호는 대부분 모른다 — 132종 중 7종뿐이었다.
        var known = merchantCategoryRepository.findWithAddress(
                com.finntech.domain.MerchantCategory.normalize(businessNumber));
        if (known.isPresent()) {
            MerchantCategory row = known.get();
            // 좌표는 조회처가 안 준다 — 주소만 있고 lat/lon 은 없다. online 은 원시형이라 null 을 못 넣는다.
            return new MyDataResponses.MerchantView(null, row.getCategory2(), businessNumber,
                    row.getMerchantName(), row.getAddress(), null, null, false);
        }
        MyDataResponses.MerchantView m = myDataClient.findMerchant(businessNumber);
        if (m == null) return null;
        // 업종코드는 사용자에게 보여줄 말이 아니다. 결제와 같은 표로 소비 중분류를 붙여 준다.
        return new MyDataResponses.MerchantView(m.industryCode(), industryMapper.midOf(m.industryCode(), m.businessNumber()),
                m.businessNumber(), m.merchantName(), m.address(), m.lat(), m.lng(), m.online());
    }

    /**
     * 입출금 통장 조회(§13-11) — 프론트 통장 화면용. 사용자의 CI로 제공자에 프록시한다.
     *
     * <p><b>은행을 연동한 사용자에게만 준다.</b> 연동하지 않았는데 통장이 보이면 연결이라는 절차가
     * 의미를 잃는다(카드만 고른 사람에게 통장이 딸려 나오면 안 된다). 잔액·내역은 저장하지 않고
     * 매번 계산해 받는다 — 결제가 들어올 때마다 값이 바뀌므로 저장하면 즉시 낡는다.
     */
    @Transactional(readOnly = true)
    public MyDataResponses.AccountView account(Long userId) {
        return account(userId, 1);
    }

    /** @param months 최근 N개월(당월 포함) — 화면의 '이전 6개월 보기'가 7을 보낸다. */
    @Transactional(readOnly = true)
    public MyDataResponses.AccountView account(Long userId, int months) {
        AppUser user = userRepository.findById(userId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user " + userId + " not found"));
        if (!userBankRepository.existsByUserId(userId)) return null;
        String ci = user.getCi();
        if (ci == null || ci.isBlank()) return null;
        return myDataClient.findAccount(ci, months);
    }

    /**
     * 실시간 증분 동기화(§13-11, W2) — 카드사별 lastRenewalTime 이후의 새 결제만 당겨와 append한다.
     * 마이데이터 커트오프(mydata.now)가 전진하면 미리 생성해둔 미래 결제가 등장한다. 멱등(이미 있는 결제 skip).
     */
    public SyncResult renew(Long userId) {
        // **결제를 당기는 일은 자물쇠 밖이다.** 예전에는 자물쇠를 못 잡으면 제공자를 부르지도
        // 않고 `SyncResult(0)` 을 돌려줬는데, 화면은 그 0 을 "이미 최신 상태예요"로 읽는다
        // (`MyConnections.tsx`). 즉 <b>"동기화를 안 했다"와 "했는데 새 것이 없다"가 구별되지
        // 않았다</b> — 방금 결제하고 동기화를 눌러도 안 보인다. 배치가 자물쇠를 쥐고 도는 시간이
        // 회차의 3~4할이라 드문 일도 아니다(2026-08-07 재감사).
        //
        // 당기는 것은 멱등이고(`existsById` 로 건너뛴다) 값이 안 든다. 자물쇠가 막으려던 것은
        // **모델을 두 번 부르는 것**이지 결제를 당기는 것이 아니었다. §13-13 이 `ask` 에 대해
        // 적어 둔 규약과 같게 맞춘다 — <b>빈손으로 돌려보내지 않고 모델만 건너뛴다.</b>
        // (연동 `linkCardCompanies` 도 적재를 자물쇠 밖에서 하므로 두 진입로의 모양이 같아진다.)
        int added = selfProvider.getObject().pullNewPayments(userId);
        // 바깥 서버 호출 — 트랜잭션 밖이자 **요청 밖**이다. 이 메서드는 5분 배치도 부르고
        // 화면의 `POST /api/mydata/sync` 도 부르는데, 후자는 사람이 기다리는 요청이다.
        // 돌려주는 `added` 는 위에서 이미 정해졌으므로 후속 단계를 기다릴 이유가 없다.
        runFollowUpsInBackground(userId);
        // 자동 동기화 배치가 5분마다 이 메서드를 부른다. 대부분 0건이라 INFO 로 남기면
        // 로그가 그것만으로 찬다.
        if (added > 0) log.info("마이데이터 증분 동기화 — userId={} 신규 결제 {}건", userId, added);
        else log.debug("마이데이터 증분 동기화 — userId={} 신규 결제 없음", userId);
        return new SyncResult(added);
    }

    /** 증분 동기화의 <b>적재 부분</b> — DB 와 제공자만 만진다. 분류·브랜드는 여기 없다. */
    @Transactional
    public int pullNewPayments(Long userId) {
        AppUser user = userRepository.findById(userId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user " + userId + " not found"));
        String ci = user.getCi();
        if (ci == null || ci.isBlank()) throw new IllegalStateException("본인인증(가상 CI)이 먼저 필요합니다");
        if (!user.isConsentGiven()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "개인정보 수집 동의가 필요합니다");
        }
        int added = 0;
        // **사전은 새 결제를 처음 만났을 때 만든다.** {@code snapshot()} 은 merchant_category 를
        // 통째로 읽는데(findAll), 증분 회차는 대개 새 결제가 0건이라 그 표를 읽고 그냥 버렸다.
        // 이 메서드는 5분 배치가 **연동된 사용자 전원**에게 부르므로, 더미까지 합치면 아무 일도
        // 없는 회차마다 사전을 사람 수만큼 읽고 있었다. 연동(수천 건)에서는 첫 결제에서 바로
        // 만들어지므로 원래의 이점(건마다 질의하지 않기)은 그대로다.
        MerchantCategoryService.Snapshot dict = null;
        for (UserCardCompany link : userCardCompanyRepository.findByUserIdOrderByCompanyIdAsc(userId)) {
            LocalDateTime since = link.getLastRenewalTime();
            LocalDateTime maxDate = since;
            for (CardView card : myDataClient.findCardsSince(link.getCompanyId(), ci, since)) {
                for (PaymentView payment : card.payments()) {
                    // 멱등 — 계정별 키로 확인한다. 제공자 id로 보면 남의 행을 내 것으로 착각한다.
                    if (userPaymentRepository.existsById(UserPayment.rowId(userId, payment.id()))) continue;
                    if (dict == null) dict = merchantCategoryService.snapshot();
                    var fromDict = dict.lookup(payment.businessNumber(), payment.merchantName());
                    String mid = fromDict.orElseGet(
                            () -> industryMapper.midOf(payment.industryCode(), payment.businessNumber()));
                    UserPayment row = new UserPayment(
                            UserPayment.rowId(userId, payment.id()), userId, card.cardId(),
                            payment.cardCode(), payment.date(), payment.industryCode(), mid,
                            payment.amount(), payment.merchantName(),
                            payment.businessNumber());
                    if (fromDict.isPresent()) row.confirmCategory2(mid, "DICT");
                    else applyRemembered(dict, row);
                    userPaymentRepository.save(row);
                    // 증분은 덧붙이기만 하므로 **켜기만 한다.** 실물 결제가 하나라도 새로 들어오면
                    // 그때부터 실사용자다. 끄는 것은 전부 다시 읽는 연동만이 할 수 있다.
                    if (row.isFromRealPerson() && !user.isRealPerson()) {
                        user.setRealPerson(true);
                        userRepository.save(user);
                    }
                    Category category = categoryRepository.findByCode(mid)
                            .orElseGet(() -> categoryRepository.save(new Category(mid, mid)));
                    // 결제 키를 달고 간다 — 원장이 나중에 이 소비의 가맹점을 되찾는 유일한 길이다(V15).
                    consumptionRepository.save(new Consumption(userId, category,
                            BigDecimal.valueOf(payment.amount()), payment.date(), false, Enums.DataSource.MYDATA)
                            .withSourcePayment(UserPayment.rowId(userId, payment.id())));
                    added++;
                    if (payment.date().isAfter(maxDate)) maxDate = payment.date();
                }
            }
            link.setLastRenewalTime(maxDate);      // 다음 증분 기준 전진
            userCardCompanyRepository.save(link);
        }
        // **관측은 여기서 하지 않는다.** 예전에는 여기서도 한 번 돌았는데, 이 메서드를 부르는 곳은
        // `renew` 하나뿐이고 그 바로 뒤 `runFollowUps` 가 `observeAll` 을 무조건 돌린다(:284).
        // 즉 여기 것은 원장을 통째로 한 번 더 읽고서 곧바로 덮였다. 게다가 여기는 분류가 고쳐지기
        // **전**이라 뒤엣것보다 낡은 관측이다 — 남길 이유가 없다.
        // 자물쇠에 막혀 후속이 건너뛰어진 회차에는 관측도 같이 밀리지만, `observeAll` 은 새 결제를
        // 조건으로 걸지 않으므로 다음 회차가 그대로 이어받는다.
        if (added > 0) {
            reportRepository.deleteByUserId(userId);   // 새 결제 반영 위해 리포트 캐시 무효화
        }
        return added;
    }

    public record SyncResult(int newPayments) {}

    /**
     * '내 카드' 화면 — 카드별 실적 진행률 + 이번달 받은 혜택.
     *
     * <p><b>실적도 조회 시점에 다시 센다.</b> 예전에는 연동하던 순간에 계산해 {@code UserCard}에
     * 저장한 값을 그대로 보여줬고, 갱신하는 곳이 어디에도 없었다({@code setCurrentPerformance}
     * 호출부 0건 — 증분 동기화도 건드리지 않았다). 그래서 6월에 연동하고 7월에 이 화면을 열면
     * "이번 달 사용"에는 <b>6월 금액</b>이, 바로 옆 "받은 혜택"에는 7월 금액이 나란히 떴다.
     * 한 줄 안에서 두 값의 기간이 달랐고, 전월실적 진행바도 7월 내내 움직이지 않았다.
     */
    @Transactional(readOnly = true)
    public List<MyCardView> myCards(Long userId) {
        YearMonth referenceMonth = YearMonth.from(referenceDate());
        List<MyCardView> views = new ArrayList<>();
        for (UserCard card : userCardRepository.findByUserIdOrderByIdAsc(userId)) {
            List<UserPayment> thisMonth = userPaymentRepository
                    .findByUserIdAndCardSerialOrderByPaymentDateDesc(userId, card.getSerialNumber()).stream()
                    .filter(payment -> YearMonth.from(payment.getPaymentDate()).equals(referenceMonth))
                    .toList();
            // 받은 혜택 합계는 더 내보내지 않는다 — 마이데이터가 할인·적립액을 주지 않으므로
            // 셀 수 있는 값이 아니다. 카드 혜택 룰이 갖춰지면 그때 우리가 계산해 되살린다.
            int currentPerformance = thisMonth.stream().mapToInt(UserPayment::getAmount).sum();

            boolean requirementMet = card.getRequirement() == 0
                    || currentPerformance >= card.getRequirement();
            int toRequirement = Math.max(0, card.getRequirement() - currentPerformance);
            views.add(new MyCardView(card.getSerialNumber(), card.getCardCode(), card.getCardName(),
                    card.getCardColor(), card.getCompanyName(), card.getRequirement(),
                    currentPerformance, requirementMet, toRequirement));
        }
        return views;
    }

    /** '내 카드' 상세 — 카드 결제내역(최신순). */
    @Transactional(readOnly = true)
    public List<PaymentRow> cardPayments(Long userId, String cardSerial) {
        return userPaymentRepository.findByUserIdAndCardSerialOrderByPaymentDateDesc(userId, cardSerial).stream()
                .map(payment -> new PaymentRow(payment.getPaymentId(), payment.getPaymentDate(),
                        payment.getCategory2(), payment.getCategory2(), payment.getAmount(),
                        payment.getMerchantName(), payment.getBusinessNumber()))
                .toList();
    }

    /**
     * '결제내역 모아보기'(§13-11) — 카드 구분 없이 최근 {@code monthsBack}개월 결제를 한 화면에 최신순으로.
     * 각 결제에 어느 카드(실카드명·색·카드사)인지 붙여, 여러 카드의 6개월치를 통합해 보여준다.
     */
    @Transactional(readOnly = true)
    public List<PaymentHistoryRow> allPayments(Long userId, int monthsBack) {
        LocalDateTime from = referenceDate().minusMonths(monthsBack).atStartOfDay();
        Map<String, UserCard> bySerial = userCardRepository.findByUserIdOrderByIdAsc(userId).stream()
                .collect(Collectors.toMap(UserCard::getSerialNumber, c -> c, (a, b) -> a));
        return userPaymentRepository.findByUserIdOrderByPaymentDateDesc(userId).stream()
                .filter(payment -> !payment.getPaymentDate().isBefore(from))
                .map(payment -> {
                    UserCard card = bySerial.get(payment.getCardSerial());
                    return new PaymentHistoryRow(payment.getPaymentId(), payment.getPaymentDate(),
                            payment.getCategory2(), payment.getCategory2(), payment.getAmount(),
                            payment.getMerchantName(),
                            card != null ? card.getCardName() : null,
                            card != null ? card.getCardColor() : null,
                            card != null ? card.getCompanyName() : null,
                            payment.getBusinessNumber(), payment.getCategory2Llm(),
                            payment.getCategory2Source());
                })
                .toList();
    }

    /**
     * 한 달치 실적액 = 그 달 승인액 합.
     *
     * <p>제공자가 전월 실적액을 주지 않으므로(실 마이데이터에 없는 필드다) 전월·당월 모두
     * 여기서 센다. <b>승인액 전액 기준이라 카드사 실적보다 크게 나온다</b> — 세금·공과금·
     * 상품권·무이자할부·해외이용분이 실적에서 빠지는데 그 목록은 카드마다 달라, 카드 혜택
     * 룰이 갖춰지기 전에는 뺄 수가 없다. 그래서 이 값은 <b>상한</b>이다.
     */
    private static int performanceOf(CardView card, YearMonth month) {
        return card.payments().stream()
                .filter(payment -> YearMonth.from(payment.date()).equals(month))
                .mapToInt(PaymentView::amount).sum();
    }

    /** 실적 요건 = 카드 혜택 구간의 하한 중 최솟값(양수). 없으면 0(조건 없음). */
    private static int requirementOf(CardView card) {
        return card.cardProduct().benefits().stream()
                .map(BenefitView::performanceStart)
                .filter(start -> start > 0)
                .min(Integer::compareTo)
                .orElse(0);
    }

    /** 연동 가능 은행 목록(프록시) — 자산연결 화면용. */
    @Transactional(readOnly = true)
    public List<BankView> banks() {
        return myDataClient.findBanks();
    }

    /** 연동한 은행 목록 — '연결 관리' 화면이 카드사와 함께 보여준다. */
    @Transactional(readOnly = true)
    public List<UserBank> linkedBanks(Long userId) {
        return userBankRepository.findByUserIdOrderByBankIdAsc(userId);
    }

    public record LinkResult(int cardCount, int paymentCount, int bankCount) {}

    public record MyCardView(String serialNumber, Long cardCode, String cardName, String cardColor,
                             String companyName, int requirement, int currentPerformance,
                             boolean requirementMet, int toRequirement) {}

    public record PaymentRow(String paymentId, java.time.LocalDateTime date, String category,
                             String category2, int amount, String merchantName,
                             String businessNumber) {}

    /** 결제내역 모아보기 1건 — 결제 정보 + 어느 카드(실카드명·색·카드사)인지 + 가맹점 사업자번호. */
    /**
     * @param category2Llm 확정이 아직 없을 때의 <b>AI 추정</b>. 화면이 "카테고리없음" 덩어리를
     *                     쪼개 보여 주고 사용자가 고르게 하려면 이 값이 함께 내려가야 한다.
     *                     {@code category2} 를 덮지 않는다 — 확정은 사람이 하는 것이다.
     */
    /**
     * <b>예전에 물어본 추정을 다시 칠한다.</b> 재연동은 결제 행을 통째로 지우고 다시 만들어서,
     * 추정이 결제 행에만 있으면 그때 전부 날아간다 — 사전에는 남아 있는데도 화면에서 사라지고,
     * 사용자는 "AI가 분류했다더니 안 보인다"를 겪는다(2026-08-05 운영 실측: 82건이 0건이 됐다).
     *
     * <p>복구를 '분류 정리' 화면 방문에 맡기지 않는다 — 거래내역만 보는 사용자는 영영 못 본다.
     * {@code category2} 는 건드리지 않는다. 추정은 여전히 추정이다(마스터 §4 원칙 1).
     */
    private static void applyRemembered(MerchantCategoryService.Snapshot dict, UserPayment row) {
        dict.guess(row.getBusinessNumber(), row.getMerchantName())
                .ifPresent(row::suggestCategory2);
    }

    public record PaymentHistoryRow(String paymentId, java.time.LocalDateTime date, String category,
                                    String category2, int amount, String merchantName,
                                    String cardName, String cardColor, String companyName,
                                    String businessNumber, String category2Llm,
                                    /**
                                     * 이 값이 어디서 왔는가. 화면이 <b>확정과 추정을 구별</b>하는 근거다 —
                                     * {@code LLM_LOCAL} 이면 값은 붙어 있어도 근거는 모델이다.
                                     * 이 칸이 없으면, 추정을 원장에 반영한 순간 화면에서
                                     * 사람의 확정과 똑같아 보인다.
                                     */
                                    String category2Source) {}
}
