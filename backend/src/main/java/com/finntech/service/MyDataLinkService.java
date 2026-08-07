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
    private static final int BRAND_ASKS_PER_SYNC = 20;

    private final MyDataClient myDataClient;
    private final AppUserRepository userRepository;
    private final UserCardRepository userCardRepository;
    private final UserPaymentRepository userPaymentRepository;
    private final ConsumptionRepository consumptionRepository;
    private final CategoryRepository categoryRepository;
    /** 업종코드 → 소비 중분류. 제공자는 업종까지만 주므로 분류는 우리가 한다. */
    private final com.finntech.engine.IndustryCategoryMapper industryMapper;
    private final MerchantCategoryService merchantCategoryService;
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

    public MyDataLinkService(MyDataClient myDataClient, AppUserRepository userRepository,
                             UserCardRepository userCardRepository, UserPaymentRepository userPaymentRepository,
                             ConsumptionRepository consumptionRepository, CategoryRepository categoryRepository,
                             com.finntech.engine.IndustryCategoryMapper industryMapper,
                             MerchantCategoryService merchantCategoryService,
                             BusinessNumberKindService businessNumberKindService,
                             IndustryLookupService industryLookup,
                             MerchantAskService merchantAskService,
                             MerchantBrandService merchantBrandService,
                             UserCardCompanyRepository userCardCompanyRepository,
                             UserBankRepository userBankRepository, ReportRepository reportRepository,
                             java.time.Clock clock,
                             @Value("${finntech.mydata.reference-date:}") String referenceDate) {
        this.myDataClient = myDataClient;
        this.userRepository = userRepository;
        this.userCardRepository = userCardRepository;
        this.userPaymentRepository = userPaymentRepository;
        this.consumptionRepository = consumptionRepository;
        this.categoryRepository = categoryRepository;
        this.industryMapper = industryMapper;
        this.merchantCategoryService = merchantCategoryService;
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
     */
    @Transactional(readOnly = true)
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
     */
    @Transactional
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
    @Transactional
    public LinkResult linkCardCompanies(Long userId, List<Long> companyIds, List<Long> bankIds) {
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
        consumptionRepository.deleteByUserIdAndSource(userId, Enums.DataSource.MYDATA);
        userCardCompanyRepository.deleteByUserId(userId);
        userBankRepository.deleteByUserId(userId);
        reportRepository.deleteByUserId(userId);   // 판정 소스(ML)·데이터가 바뀌므로 리포트 캐시 무효화

        LocalDate today = referenceDate();
        YearMonth referenceMonth = YearMonth.from(today);
        LocalDateTime linkTime = LocalDateTime.now(clock);
        int cardCount = 0, paymentCount = 0;
        // 사전은 루프 밖에서 **한 번만** 읽는다. 결제마다 조회하면 수천 번 질의가 나간다.
        MerchantCategoryService.Snapshot dict = merchantCategoryService.snapshot();

        for (Long companyId : companyIds) {
            String companyName = null;
            // 이 카드사에서 실제로 받아온 마지막 결제 시각. 다음 증분의 기준선이 된다.
            LocalDateTime lastPayment = null;
            for (CardView card : myDataClient.findCards(companyId, ci)) {
                companyName = card.cardProduct().company().name();
                int requirement = requirementOf(card);
                int currentPerformance = card.payments().stream()
                        .filter(payment -> YearMonth.from(payment.date()).equals(referenceMonth))
                        .mapToInt(PaymentView::amount).sum();
                userCardRepository.save(new UserCard(userId, card.cardId(), card.cardProduct().code(),
                        card.cardProduct().name(), card.cardProduct().color(),
                        card.cardProduct().company().name(), card.prevMonthAmount(),
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
                            payment.amount(), payment.merchantName(), payment.receivedBenefitAmount(),
                            payment.businessNumber());
                    // 사전에서 붙은 것은 근거가 사람이라 **처음부터 확정**이다(§F 격리 대상이 아니다).
                    if (fromDict.isPresent()) row.confirmCategory2(mid, "DICT");
                    else applyRemembered(dict, row);
                    userPaymentRepository.save(row);
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
        // **번호별로 상호를 관측해 판정을 갱신한다**(V16). 앱은 결제를 건별로 보므로 조회 시점에는
        // 한 번호에 다른 상호가 있는지 알 수 없다 — **연동만이 그 사용자의 결제를 전부 본다.**
        observeBusinessNumbers(userId);
        // **관측 다음이라야 한다.** 조회를 건너뛸지를 "이 번호에 상호가 여럿인가"로 판단하는데,
        // 그 사실은 방금 적재한 결제까지 봐야 안다.
        lookupUnknownIndustries(userId);
        labelBrands(userId);

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
            if (!merchantCategoryService.needsWork(biz, name)) continue;   // 이미 확정·종결
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
            var answer = industryLookup.industryOfMerchant(biz);
            industryLookup.pause();
            merchantCategoryService.noteLookup(p, answer.orElse(null), now);
            String mid = answer.map(industryMapper::midOfFineName)
                    .filter(m -> !com.finntech.engine.IndustryCategoryMapper.UNCLASSIFIED.equals(m))
                    .orElse(null);
            if (mid == null) continue;                                       // 소비 업종이 아니다 → LLM 이 받는다
            if (merchantCategoryService.rememberRegistry(p, mid).isPresent()) {
                found.put(p.getMerchantName(), mid);
            }
        }
        int resolved = applyResolved(rows, found);
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
        merchantBrandService.label(names, java.util.Set.copyOf(names), BRAND_ASKS_PER_SYNC);
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
    private int applyResolved(List<UserPayment> rows, Map<String, String> found) {
        if (found.isEmpty()) return 0;
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
     */
    private void observeBusinessNumbers(Long userId) {
        Map<String, Map<String, String>> byNumber = new java.util.HashMap<>();
        Map<String, Map<String, String>> confirmed = new java.util.HashMap<>();
        for (UserPayment p : userPaymentRepository.findByUserIdOrderByPaymentDateDesc(userId)) {
            String biz = p.getBusinessNumber();
            String name = p.getMerchantName();
            if (biz == null || biz.isBlank() || name == null || name.isBlank()) continue;
            if (industryMapper.isPaymentAgency(biz)) continue;   // PG 는 번호 자체가 남의 것이다
            String mid = com.finntech.engine.IndustryCategoryMapper.UNCLASSIFIED.equals(p.getCategory2())
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

    /** 가맹점 조회(번호→주소) — 결제에 실린 사업자번호로 가맹점명·지번주소를 제공자에서 조회(프록시). 없으면 null. */
    @Transactional(readOnly = true)
    public MyDataResponses.MerchantView merchant(String businessNumber) {
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
    @Transactional
    public SyncResult renew(Long userId) {
        AppUser user = userRepository.findById(userId).orElseThrow(
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user " + userId + " not found"));
        String ci = user.getCi();
        if (ci == null || ci.isBlank()) throw new IllegalStateException("본인인증(가상 CI)이 먼저 필요합니다");
        if (!user.isConsentGiven()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "개인정보 수집 동의가 필요합니다");
        }
        int added = 0;
        MerchantCategoryService.Snapshot dict = merchantCategoryService.snapshot();
        for (UserCardCompany link : userCardCompanyRepository.findByUserIdOrderByCompanyIdAsc(userId)) {
            LocalDateTime since = link.getLastRenewalTime();
            LocalDateTime maxDate = since;
            for (CardView card : myDataClient.findCardsSince(link.getCompanyId(), ci, since)) {
                for (PaymentView payment : card.payments()) {
                    // 멱등 — 계정별 키로 확인한다. 제공자 id로 보면 남의 행을 내 것으로 착각한다.
                    if (userPaymentRepository.existsById(UserPayment.rowId(userId, payment.id()))) continue;
                    var fromDict = dict.lookup(payment.businessNumber(), payment.merchantName());
                    String mid = fromDict.orElseGet(
                            () -> industryMapper.midOf(payment.industryCode(), payment.businessNumber()));
                    UserPayment row = new UserPayment(
                            UserPayment.rowId(userId, payment.id()), userId, card.cardId(),
                            payment.cardCode(), payment.date(), payment.industryCode(), mid,
                            payment.amount(), payment.merchantName(), payment.receivedBenefitAmount(),
                            payment.businessNumber());
                    if (fromDict.isPresent()) row.confirmCategory2(mid, "DICT");
                    else applyRemembered(dict, row);
                    userPaymentRepository.save(row);
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
        if (added > 0) {
            reportRepository.deleteByUserId(userId);   // 새 결제 반영 위해 리포트 캐시 무효화
            observeBusinessNumbers(userId);
        }
        // **조건은 '새 결제'가 아니라 '할 일'이다.** 새 결제가 있을 때만 조회하면 이미 쌓인
        // 미분류는 영영 안 물어본다 — 새 결제가 안 오는 한 계속 '카테고리없음'으로 남는다
        // (2026-08-07 실측: 실사용자 미분류 53곳이 그 상태였다. 통로를 켜고 동기화를 돌려도
        // `newPayments:0` 이라 아무것도 안 일어났다).
        //
        // 그래도 5분마다 바깥 서버를 두드리지는 않는다. 미분류 개수 하나로 먼저 걸러(값싼 질의)
        // 할 일이 없으면 결제를 읽지도 않고, 물어본 가맹점은 사전에 시도 이력이 남아 다시 묻지
        // 않는다. 그래서 반복 호출은 사전이 차는 만큼 저절로 마른다.
        if (userPaymentRepository.countByUserIdAndCategory2(
                userId, com.finntech.engine.IndustryCategoryMapper.UNCLASSIFIED) > 0) {
            lookupUnknownIndustries(userId);
            // **③ LLM 은 40곳이 쌓여야 부른다.** 조회(②-b)와 달리 임계값을 두는 이유는 값이
            // 묶음 크기에 좌우되기 때문이다 — 프롬프트의 76%가 업종 목록(385종)이라 1곳을 묻든
            // 40곳을 묻든 값이 거의 같고, 1곳씩 40번 부르면 40배가 든다. 조회는 번호 하나만
            // 보내므로 그런 낭비가 없어 발견 즉시 한다.
            //
            // 기다리는 동안 화면은 '카테고리없음'이고 그것이 정확한 표현이다(낭비 판정에서도
            // 빠진다). 그리고 기다림이 눈에 띄는 순간 — 사용자가 화면을 열 때 — 에는 임계값이
            // 1 이라 남은 것을 전부 몰아 묻는다. 그래서 체감 지연이 없다.
            merchantAskService.ask(userId, MerchantAskService.BACKGROUND_MIN);
        }
        // **브랜드는 미분류와 무관하게 돈다** — 이미 분류된 가맹점도 브랜드가 필요하다.
        labelBrands(userId);
        // 자동 동기화 배치가 5분마다 이 메서드를 부른다. 대부분 0건이라 INFO로 남기면 로그가 그것만으로 찬다.
        if (added > 0) log.info("마이데이터 증분 동기화 — userId={} 신규 결제 {}건", userId, added);
        else log.debug("마이데이터 증분 동기화 — userId={} 신규 결제 없음", userId);
        return new SyncResult(added);
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
            int earnedThisMonth = thisMonth.stream().mapToInt(UserPayment::getReceivedBenefit).sum();
            int currentPerformance = thisMonth.stream().mapToInt(UserPayment::getAmount).sum();

            boolean requirementMet = card.getRequirement() == 0
                    || currentPerformance >= card.getRequirement();
            int toRequirement = Math.max(0, card.getRequirement() - currentPerformance);
            views.add(new MyCardView(card.getSerialNumber(), card.getCardCode(), card.getCardName(),
                    card.getCardColor(), card.getCompanyName(), card.getRequirement(),
                    currentPerformance, requirementMet, toRequirement, earnedThisMonth));
        }
        return views;
    }

    /** '내 카드' 상세 — 카드 결제내역(최신순). */
    @Transactional(readOnly = true)
    public List<PaymentRow> cardPayments(Long userId, String cardSerial) {
        return userPaymentRepository.findByUserIdAndCardSerialOrderByPaymentDateDesc(userId, cardSerial).stream()
                .map(payment -> new PaymentRow(payment.getPaymentId(), payment.getPaymentDate(),
                        payment.getCategory2(), payment.getCategory2(), payment.getAmount(),
                        payment.getMerchantName(), payment.getReceivedBenefit(), payment.getBusinessNumber()))
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
                            payment.getMerchantName(), payment.getReceivedBenefit(),
                            card != null ? card.getCardName() : null,
                            card != null ? card.getCardColor() : null,
                            card != null ? card.getCompanyName() : null,
                            payment.getBusinessNumber(), payment.getCategory2Llm());
                })
                .toList();
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
                             boolean requirementMet, int toRequirement, int earnedThisMonth) {}

    public record PaymentRow(String paymentId, java.time.LocalDateTime date, String category,
                             String category2, int amount, String merchantName, int receivedBenefit,
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
                                    String category2, int amount, String merchantName, int receivedBenefit,
                                    String cardName, String cardColor, String companyName,
                                    String businessNumber, String category2Llm) {}
}
