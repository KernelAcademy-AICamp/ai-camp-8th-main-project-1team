package com.finntech.service;

import com.finntech.domain.Consumption;
import com.finntech.repository.ConsumptionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * <b>그 주·그 달에 얼마를 썼는가</b> — 챌린지와 무관한 기간 집계.
 *
 * <h2>왜 따로 만드는가</h2>
 *
 * <p>리포트의 일별 계열은 지금까지 <b>지킴이 주간 리포트</b>에서만 나왔다. 그런데 그것은
 * 챌린지에 딸린 것이라 진행 중인 챌린지가 없으면 404 이고, 있어도 <b>시작일 이전은 안 센다</b>
 * (그 화면의 목적이 "약속을 지켰나"이기 때문이다. 그건 그것대로 맞다).
 *
 * <p>그래서 소비 내역에는 결제가 잔뜩 쌓여 있는데 리포트는 비어 있는 일이 생겼다
 * (사용자 보고 2026-08-20). 리포트가 답해야 할 질문은 <b>"이 기간에 내가 어떻게 썼나"</b>이고,
 * 그 답은 챌린지가 있든 없든 소비 내역만으로 나온다. 여기가 그 답을 만드는 자리다.
 *
 * <p>지킴이 주간 리포트를 고치지 않는 이유도 같다 — 둘은 <b>다른 질문</b>이다.
 * "약속을 지켰나"는 시작일부터 세는 것이 맞고, "얼마 썼나"는 처음부터 세는 것이 맞다.
 * 화면은 챌린지가 있으면 둘을 겹쳐 보여주고, 없으면 이것만 보여준다.
 *
 * <h2>재현성</h2>
 *
 * <p>'오늘'을 {@code now()} 로 직접 읽지 않고 {@link Clock} 에서 받는다(마스터 §4 원칙 3).
 * 데모 시계를 돌리면 리포트도 같이 움직여야 하고, 시험이 시간에 흔들리면 안 된다.
 * 날짜별 합계는 {@link TreeMap} 이라 순서가 고정되고, 카테고리는 금액 내림차순 →
 * 코드 오름차순으로 <b>동점에서도 순서가 정해진다</b>.
 *
 * <h2>주의 시작은 월요일</h2>
 *
 * <p>{@code GuardianRewardService.weekStart} 와 같은 규칙이다. 두 곳이 다른 요일로 주를
 * 자르면 같은 화면 안에서 "이번 주"가 두 개가 된다.
 */
@Service
public class PeriodSpendService {

    private final ConsumptionRepository consumptions;
    private final Clock clock;

    public PeriodSpendService(ConsumptionRepository consumptions, Clock clock) {
        this.consumptions = consumptions;
        this.clock = clock;
    }

    /** 하루치 합계. 결제가 없는 날도 0으로 채워 넣는다 — 빠진 날이 있으면 막대가 밀린다. */
    public record DaySpend(LocalDate date, long amount) {}

    /** 카테고리별 합계. {@code code} 는 판정에 쓰는 값, {@code name} 은 화면에 쓰는 말이다. */
    public record CatSpend(String code, String name, long amount) {}

    public record PeriodSpend(String period, LocalDate start, LocalDate end,
                              long total, int count,
                              List<DaySpend> days, List<CatSpend> byCategory) {}

    /**
     * @param period {@code "week"} 또는 {@code "month"}
     * @param offset 0 이면 이번 주/달, 1 이면 지난 주/달 … <b>음수는 받지 않는다</b>
     *               (미래 구간은 늘 비어 있어 "고장난 화면"으로 보인다)
     */
    @Transactional(readOnly = true)
    public PeriodSpend of(Long userId, String period, int offset) {
        int back = Math.max(0, offset);
        LocalDate today = LocalDate.now(clock);
        boolean monthly = "month".equalsIgnoreCase(period);

        LocalDate start = monthly
                ? today.minusMonths(back).withDayOfMonth(1)
                : today.with(DayOfWeek.MONDAY).minusWeeks(back);
        LocalDate endExclusive = monthly ? start.plusMonths(1) : start.plusWeeks(1);
        LocalDate end = endExclusive.minusDays(1);

        List<Consumption> rows = consumptions.findInRange(
                userId, start.atStartOfDay(), endExclusive.atStartOfDay());

        // 날짜별 — 빈 날도 0으로 채운다. TreeMap 이라 넣는 순서와 무관하게 날짜순이다.
        Map<LocalDate, Long> byDay = new TreeMap<>();
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) byDay.put(d, 0L);

        // 카테고리별 — 코드로 모으고, 화면에 쓸 이름은 처음 만난 것을 쓴다.
        Map<String, Long> byCatAmount = new TreeMap<>();
        Map<String, String> catName = new TreeMap<>();

        long total = 0L;
        for (Consumption c : rows) {
            long amount = c.getAmount() == null ? 0L : c.getAmount().longValue();
            total += amount;
            LocalDate d = c.getOccurredAt().toLocalDate();
            byDay.merge(d, amount, Long::sum);
            String code = c.getCategory() == null ? "기타" : c.getCategory().getCode();
            byCatAmount.merge(code, amount, Long::sum);
            catName.putIfAbsent(code,
                    c.getCategory() == null ? "기타" : c.getCategory().getDisplayName());
        }

        List<DaySpend> days = new ArrayList<>(byDay.size());
        byDay.forEach((d, amount) -> days.add(new DaySpend(d, amount)));

        List<CatSpend> cats = new ArrayList<>(byCatAmount.size());
        byCatAmount.forEach((code, amount) -> cats.add(new CatSpend(code, catName.get(code), amount)));
        // 금액 내림차순, 같으면 코드 오름차순 — 동점에서도 순서가 흔들리지 않는다(원칙 3).
        cats.sort(Comparator.comparingLong(CatSpend::amount).reversed()
                .thenComparing(CatSpend::code));

        return new PeriodSpend(monthly ? "month" : "week", start, end,
                total, rows.size(), days, cats);
    }
}
