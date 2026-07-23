package com.finntech.mydata.service;

import com.finntech.mydata.domain.CardCompany;
import com.finntech.mydata.domain.MyDataCard;
import com.finntech.mydata.domain.MyDataUser;
import com.finntech.mydata.dto.MyDataDtos.CardView;
import com.finntech.mydata.repository.CardCompanyRepository;
import com.finntech.mydata.repository.MyDataCardRepository;
import com.finntech.mydata.repository.MyDataPaymentRepository;
import com.finntech.mydata.repository.MyDataUserRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 조회 커트오프(§13-11) 서빙 테스트 — "실시간 연동"의 핵심 보장.
 * 생성기가 과거~미래(≈2026-11)에 걸친 결제를 적재하므로, {@code mydata.now}만 다른 MyDataService 두 개로
 *   (1) now 이하 결제만 서빙되고(미래 결제는 숨김),
 *   (2) now를 미래로 전진시키면 그 사이 결제가 새로 등장하며,
 *   (3) 증분 조회(findCardsSince)는 [lastRenewal, now] 구간만 반환
 * 함을 검증한다. 커트오프는 생성자 주입(nowSetting)이라 같은 데이터에 서비스 두 개를 만들어 비교한다.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cutoff_serving;DB_CLOSE_DELAY=-1;MODE=MySQL",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "mydata.seed.enabled=false",
        "mydata.generation.enabled=true",
        "mydata.generation.target-count=24000"
})
class MyDataCutoffServingTest {

    @Autowired MyDataUserRepository userRepository;
    @Autowired MyDataCardRepository cardRepository;
    @Autowired MyDataPaymentRepository paymentRepository;
    @Autowired CardCompanyRepository companyRepository;

    private static final LocalDateTime EARLY = LocalDateTime.parse("2026-07-21T23:59:59"); // 시드 기준일 끝
    private static final LocalDateTime LATE  = LocalDateTime.parse("2026-12-31T23:59:59"); // 미래로 전진

    private MyDataService serviceAt(LocalDateTime now) {
        return new MyDataService(userRepository, cardRepository, paymentRepository, companyRepository,
                now.toString(), "2026-07-21", 0);
    }

    private int totalPayments(List<CardView> cards) {
        return cards.stream().mapToInt(c -> c.payments().size()).sum();
    }

    @Test
    @Transactional  // 수동 생성 서비스는 @Transactional 프록시가 없어 지연로딩 세션을 테스트가 연다.
    void 커트오프는_미래결제를_now_전진_시에만_노출한다() {
        // 카드를 가진 (userId, companyId) 한 쌍을 실제 데이터에서 고른다.
        Object[] pick = findUserWithCards();
        String userId = (String) pick[0];
        Long companyId = (Long) pick[1];

        MyDataService early = serviceAt(EARLY);
        MyDataService late  = serviceAt(LATE);

        List<CardView> earlyCards = early.findCards(companyId, userId);
        List<CardView> lateCards  = late.findCards(companyId, userId);

        int earlyN = totalPayments(earlyCards);
        int lateN  = totalPayments(lateCards);

        assertThat(earlyN).as("현재(커트오프 이하) 결제는 서빙된다").isGreaterThan(0);
        assertThat(lateN).as("now를 미래로 전진하면 미래 결제가 새로 등장한다").isGreaterThan(earlyN);

        // 커트오프 정확성: EARLY 서비스가 낸 결제는 전부 EARLY 이하여야 한다.
        assertThat(earlyCards).allSatisfy(card ->
                assertThat(card.payments()).allSatisfy(p ->
                        assertThat(p.date()).isBeforeOrEqualTo(EARLY)));

        // 증분 조회: [EARLY, LATE] 구간 결제 수 = (LATE 전체 - EARLY 전체)와 정합.
        int incremental = late.findCardsSince(companyId, userId, EARLY).stream()
                .mapToInt(c -> c.payments().size()).sum();
        assertThat(incremental).as("증분(=미래에 새로 생긴 결제)은 전체 증가분과 같다").isEqualTo(lateN - earlyN);
    }

    /** 카드를 가진 (userId, companyId) 조합을 실제 생성 데이터에서 찾는다. */
    private Object[] findUserWithCards() {
        List<String> users = userRepository.findAll().stream().map(MyDataUser::getId).sorted().toList();
        List<CardCompany> companies = companyRepository.findAllByOrderByIdAsc();
        for (String userId : users) {
            for (CardCompany co : companies) {
                List<MyDataCard> cards = cardRepository.findByUserAndCompany(userId, co.getId());
                if (!cards.isEmpty()) return new Object[]{userId, co.getId()};
            }
        }
        throw new IllegalStateException("카드를 가진 사용자를 찾지 못했다(생성 데이터 확인)");
    }
}
