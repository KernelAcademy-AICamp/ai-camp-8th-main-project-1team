/**
 * 지킴이 모양의 답을 <b>사용자가 적은 소비</b>로 만든다.
 *
 * <h2>왜 지킴이 모양인가</h2>
 *
 * 홈과 리포트는 본 서비스의 화면을 그대로 가져온 것이고, 그 둘은 `/api/guardian/home` 과
 * `/api/guardian/report/weekly` 를 읽는다. 화면을 고쳐 다른 것을 읽게 만들 수도 있지만
 * 그러면 두 앱이 갈라진다 — <b>같은 자리에 다른 뜻을 넣는 편</b>이 낫다.
 *
 * <h2>자리마다 무엇이 들어가나</h2>
 *
 * 챌린지가 없으므로 '지킨 돈'이라는 값이 없다. 대신 이렇게 옮겨 담는다.
 *
 * <pre>
 *   securedSaving            이번 달 쓴 돈        (홈 히어로의 큰 숫자)
 *   targetSaving             이번 달 쓴 돈        (리포트 범례 왼쪽)
 *   challengeCap−remainingCap  낭비              (리포트 범례 오른쪽 · 진행바)
 *   achievementRate          낭비의 비율          (홈의 링)
 *   categorySpend            카테고리별 사용액     (홈 소비 현황 · 리포트 도넛)
 * </pre>
 *
 * 화면의 글자는 이 뜻에 맞게 고쳤다 — 숫자만 바꾸고 라벨을 두면 <b>거짓말이 된다.</b>
 */
import type {
  DayPoint, GuardianChallenge, GuardianHome, PeriodSpend, WeekPoint, WeeklyReport,
} from './api';
import type { Entry } from '../engine/report';
import { UNKNOWN } from '../engine/tables';

const won = (n: number) => Math.round(n).toLocaleString('ko-KR') + '원';
const iso = (d: Date) => {
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())}`;
};

/** 오늘. `Date` 를 한 번만 읽어 화면 안에서 날짜가 갈리지 않게 한다. */
const now = () => new Date();

const spendable = (all: Entry[]) => all.filter((e) => e.amount > 0);

export function guardianHome(all: Entry[]): GuardianHome {
  const today = now();
  const month = iso(today).slice(0, 7);
  const rows = spendable(all).filter((e) => e.date.startsWith(month));

  const total = rows.reduce((s, e) => s + e.amount, 0);
  const flagged = rows.filter((e) => e.waste).reduce((s, e) => s + e.amount, 0);

  const daysTotal = new Date(today.getFullYear(), today.getMonth() + 1, 0).getDate();
  const daysElapsed = today.getDate();

  const byCat: Record<string, { spent: number; count: number }> = {};
  for (const e of rows) {
    if (e.category2 === UNKNOWN) continue;
    (byCat[e.category2] ??= { spent: 0, count: 0 }).spent += e.amount;
    byCat[e.category2].count++;
  }
  const categorySpend = Object.entries(byCat)
    .map(([code, v]) => ({
      code,
      label: code,
      spent: v.spent,
      share: total === 0 ? 0 : v.spent / total,
      // 예산이 없다. 0 으로 두면 화면이 '예산 없음'이라 적으므로, 그 자리는 화면에서 고쳤다.
      cap: 0,
      remaining: 0,
      ratio: total === 0 ? 0 : v.spent / total,
    }))
    .sort((a, b) => b.spent - a.spent);

  const challenge: GuardianChallenge = {
    id: 0,
    state: 'ACTIVE',
    categories: Object.keys(byCat),
    sanctuaryCategories: [],
    categoryLabel: '전체',
    categorySpend,
    baselineAmount: total,
    // 리포트 범례 왼쪽 — '쓴 돈'.
    targetSaving: total,
    // `usedAmount = challengeCap − remainingCap` 이 리포트 범례 오른쪽('낭비')이다.
    challengeCap: Math.max(total, 1),
    remainingCap: Math.max(total, 1) - flagged,
    // 홈 히어로의 큰 숫자 — '이번 달 쓴 돈'.
    securedSaving: total,
    spentAmount: flagged,
    spentRatio: total === 0 ? 0 : flagged / total,
    // 홈의 링 — 낭비로 본 돈의 비율.
    achievementRate: total === 0 ? 0 : flagged / total,
    daysElapsed,
    daysLeft: Math.max(0, daysTotal - daysElapsed),
    daysTotal,
    paceRatio: daysTotal === 0 ? 0 : daysElapsed / daysTotal,
    allowedRatio: 1,
    bufferRatio: 0,
    startDate: `${month}-01`,
    endDate: iso(new Date(today.getFullYear(), today.getMonth() + 1, 0)),
    rewardName: null,
    rewardPrice: 0,
  };

  const unknownCount = all.filter((e) => e.category2 === UNKNOWN).length;

  return {
    asOf: today.toISOString(),
    challenge,
    strip: {
      // 홈 '소비 현황' 첫 줄의 오른쪽. 예산이 없으므로 남은 돈이 아니라 쓴 돈을 적는다.
      remainingCapLabel: won(total),
      pendingCount: unknownCount,
      pendingBadge: unknownCount > 0 ? `아직 무엇인지 모르는 소비 ${unknownCount}건` : null,
      noSpendStreak: 0,
      grassStreak: 0,
      pointBalance: 0,
      unopenedCeremony: false,
    },
    oneline: {
      caseId: 'IDLE',
      text: total === 0
        ? '소비를 넣으면 어디에 얼마를 썼는지 보여드릴게요.'
        : `이번 달 ${rows.length}건, ${won(total)} 썼어요.`,
    },
    ceremony: null,
    grass: [],
    itemsHeld: { exemption: 0, grassGuard: 0, missionChange: 0, pointBalance: 0 },
    unreadNotifications: 0,
    demoMode: false,
  };
}

/**
 * 주간 리포트 — 요일별·주별 <b>사용액</b>.
 *
 * 본 서비스의 `defenseRate` 는 '지킨 날 ÷ 판정한 날'인데 여기엔 지킬 것이 없다.
 * 그 자리에는 <b>낭비로 본 돈의 비율</b>을 넣고 화면의 글자를 그에 맞게 고쳤다.
 */
export function weeklyReport(all: Entry[], weeksAgo: number): WeeklyReport {
  const rows = spendable(all);
  const start = weekStart(now(), weeksAgo);
  const end = new Date(start);
  end.setDate(end.getDate() + 6);

  const days: DayPoint[] = [];
  const KR = ['월', '화', '수', '목', '금', '토', '일'];
  for (let i = 0; i < 7; i++) {
    const d = new Date(start);
    d.setDate(d.getDate() + i);
    const key = iso(d);
    const amount = rows.filter((e) => e.date === key).reduce((s, e) => s + e.amount, 0);
    days.push({
      date: key,
      label: KR[i],
      amount,
      kept: !rows.some((e) => e.date === key && e.waste),
      // 아직 오지 않은 날은 <b>0원이 아니라 빈칸</b>이다. 0 으로 그리면 안 쓴 날처럼 보인다.
      judged: d <= now(),
    });
  }

  // 최근 여덟 주 — 주별 사용액.
  const trend: WeekPoint[] = [];
  for (let k = 7; k >= 0; k--) {
    const s = weekStart(now(), weeksAgo + k);
    const e = new Date(s);
    e.setDate(e.getDate() + 6);
    const inWeek = rows.filter((r) => r.date >= iso(s) && r.date <= iso(e));
    const spent = inWeek.reduce((a, b) => a + b.amount, 0);
    const flagged = inWeek.filter((r) => r.waste).reduce((a, b) => a + b.amount, 0);
    trend.push({
      weekStart: iso(s),
      label: `${s.getMonth() + 1}/${s.getDate()}`,
      keptDays: inWeek.length,
      judgedDays: inWeek.length,
      defenseRate: spent === 0 ? 0 : flagged / spent,
      current: k === 0,
    });
  }

  const inRange = rows.filter((e) => e.date >= iso(start) && e.date <= iso(end));
  const total = inRange.reduce((s, e) => s + e.amount, 0);
  const flagged = inRange.filter((e) => e.waste).reduce((s, e) => s + e.amount, 0);

  const byCat: Record<string, number> = {};
  for (const e of inRange) if (e.category2 !== UNKNOWN) byCat[e.category2] = (byCat[e.category2] ?? 0) + 1;
  const labels = Object.entries(byCat)
    .map(([key, count]) => ({ key, label: key, count, ratio: inRange.length === 0 ? 0 : count / inRange.length }))
    .sort((a, b) => b.count - a.count);

  return {
    weekStart: iso(start),
    weekEnd: iso(end),
    weekLabel: `${start.getMonth() + 1}월 ${Math.ceil(start.getDate() / 7)}주차`,
    defenseRate: total === 0 ? 0 : flagged / total,
    deltaFromLastWeek: null,
    trend,
    days,
    labels,
    labeledCount: inRange.length,
    exemptedAmount: 0,
    headline: total === 0 ? '이 주에는 넣은 소비가 없어요.' : `이 주에 ${won(total)} 썼어요.`,
    missions: [],
    missionReward: 0,
    coaching: { good: null, watch: null },
    pastChallenges: [],
  };
}

/** 기간별 지출 — 리포트의 주간·월간 막대와 카테고리 목록이 읽는다. */
export function periodSpend(all: Entry[], period: 'week' | 'month', offset: number): PeriodSpend {
  const rows = spendable(all);
  let start: Date, end: Date;
  if (period === 'week') {
    start = weekStart(now(), offset);
    end = new Date(start);
    end.setDate(end.getDate() + 6);
  } else {
    const t = now();
    start = new Date(t.getFullYear(), t.getMonth() - offset, 1);
    end = new Date(t.getFullYear(), t.getMonth() - offset + 1, 0);
  }

  const inRange = rows.filter((e) => e.date >= iso(start) && e.date <= iso(end));
  const days: { date: string; amount: number }[] = [];
  for (const d = new Date(start); d <= end; d.setDate(d.getDate() + 1)) {
    const key = iso(d);
    // **결제가 없는 날도 0 으로 넣는다** — 칸 수가 늘 같아야 막대가 안 밀린다.
    days.push({ date: key, amount: inRange.filter((e) => e.date === key).reduce((s, e) => s + e.amount, 0) });
  }

  const byCat: Record<string, number> = {};
  for (const e of inRange) if (e.category2 !== UNKNOWN) byCat[e.category2] = (byCat[e.category2] ?? 0) + e.amount;

  return {
    period,
    start: iso(start),
    end: iso(end),
    total: inRange.reduce((s, e) => s + e.amount, 0),
    count: inRange.length,
    days,
    byCategory: Object.entries(byCat)
      .map(([code, amount]) => ({ code, name: code, amount }))
      .sort((a, b) => b.amount - a.amount || a.code.localeCompare(b.code)),
    uncategorised: inRange.filter((e) => e.category2 === UNKNOWN).reduce((s, e) => s + e.amount, 0),
    flagged: inRange.filter((e) => e.waste).reduce((s, e) => s + e.amount, 0),
  };
}

/** 그 주의 월요일. 주가 월요일에 시작한다는 것은 본 서비스와 같은 약속이다. */
function weekStart(from: Date, weeksAgo: number): Date {
  const d = new Date(from.getFullYear(), from.getMonth(), from.getDate());
  const dow = (d.getDay() + 6) % 7;             // 월=0
  d.setDate(d.getDate() - dow - weeksAgo * 7);
  return d;
}
