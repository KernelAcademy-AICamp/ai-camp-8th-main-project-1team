/**
 * 리포트 탭 (프로토타입_0818 `s-report`) — "이 기간을 어떻게 지켰는가"에 답한다.
 *
 * <h2>0818 개편에서 통째로 바뀐 화면이다</h2>
 *
 * <p>예전에는 <b>주간 하나</b>였고 위에 ‹ 7월 4주차 › 가 있었다. 지금은 맨 위가
 * <b>주간·월간 두 갈래</b>이고, 갈래마다 보여주는 것이 다르다 —
 *
 * <pre>
 *   공통   기간 고르기 · 진행 히어로 · 도넛과 순위 · 또래 비교 · 카드 추천
 *   주간   요일별/주별 차트 · 지킴이가 본 이번 주 · 미션 다리
 *   월간   최근 3개월 선 그래프 · 가장 많이 간 곳 캐러셀 · 1년 뒤 지킨 돈
 * </pre>
 *
 * <p><b>왜 갈래를 나눴나.</b> 주간은 "이번 주를 지켰는가"를 묻고 월간은 "어떤 사람인가"를
 * 묻는다. 물음이 다르면 보여줄 것도 다른데, 한 화면에 다 얹으면 스크롤만 길어지고 어느 것도
 * 안 읽힌다.
 *
 * <h2>또래 비교는 서버가 만든다</h2>
 *
 * <p>같은 나이대의 <b>중앙값</b>과 견준다({@code /api/report/peer}). 견줄 수 없으면 서버가
 * 204 를 주고 이 절은 <b>통째로 사라진다</b> — 표본이 얇을 때 억지로 숫자를 만들면 없는
 * 비교를 사실처럼 보여주게 된다. 왜 평균이 아니라 중앙값인지는 `PeerCompareService` 에 있다.
 *
 * <h2>계산은 서버가 한다</h2>
 *
 * <p>방어율·요일별 금액·달성률은 `/api/guardian/report/weekly` 가 완성해 내려준다
 * (마스터 §4 원칙 2). 여기서 하는 것은 그리기와 문장 조립뿐이다.
 */
import { useMemo, useState } from 'react';
import { Scroll, Screen, Loading } from '../components/ui';
import { Icon } from '../components/Icons';
import { WeekChart } from '../components/WeekChart';
import { WeekPicker, weekOfMonth, mondayOf, type WeekSel } from '../components/WeekPicker';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
/*
 * <b>그림을 주소로 부르지 않는다.</b> `/report/mo-most.svg` 처럼 절대경로로 두면 서버가 있어야
 * 찾을 수 있는데, 이 앱은 <b>파일로 말려</b> 기기에서 열린다 — 주소의 뿌리가 우리 것이 아니다.
 * 실제로 월간 리포트에서 세 장이 전부 깨져 물음표로 나왔다. 불러오면 번들러가 자리를 잡아
 * 주고, 셋 다 2KB 미만이라 그대로 파일 안에 실린다.
 */
import MO_MOST from '../assets/report/mo-most.svg';
import MO_BIGGEST from '../assets/report/mo-biggest.svg';
import MO_WEEKLY from '../assets/report/mo-weekly.svg';
import { useAsync } from '../state/useAsync';
import { api, type DayPoint } from '../lib/api';
import { won, wonNum, iconOf } from '../lib/format';

/** "7.20 ~ 7.26" */
const fmtRange = (a: string, b: string) =>
  `${Number(a.slice(5, 7))}.${Number(a.slice(8, 10))} ~ ${Number(b.slice(5, 7))}.${Number(b.slice(8, 10))}`;

/** 도넛 색 — 온보딩 막대와 <b>같은 팔레트</b>. 거기서 배운 색이 여기서 이어져야 한다. */
const DONUT = ['#F08812', '#E85D9F', '#8B5CF6', '#3671E9', '#34C38F'];
const DONUT_ETC = '#D9DDE1';



/** 카드 추천 배너의 카드 그림 — 프로토타입 원본 SVG. */

/** 도넛 한 조각의 경로 — 반지름 100 기준, 12시부터 시계방향. */
function arc(from: number, to: number, r = 78, w = 30) {
  const p = (deg: number, rad: number) => {
    const a = ((deg - 90) * Math.PI) / 180;
    return [100 + rad * Math.cos(a), 100 + rad * Math.sin(a)];
  };
  const big = to - from > 180 ? 1 : 0;
  const [x1, y1] = p(from, r + w / 2);
  const [x2, y2] = p(to, r + w / 2);
  const [x3, y3] = p(to, r - w / 2);
  const [x4, y4] = p(from, r - w / 2);
  return `M${x1} ${y1} A${r + w / 2} ${r + w / 2} 0 ${big} 1 ${x2} ${y2} `
    + `L${x3} ${y3} A${r - w / 2} ${r - w / 2} 0 ${big} 0 ${x4} ${y4} Z`;
}

/** 최근 3개월 선 그래프 — 세 점을 부드럽게 잇는다(프로토타입 `buildMoChart`). */
function MonthLine({ points }: { points: { label: string; amount: number }[] }) {
  const W = 287;
  const H = 176;
  if (points.length === 0) return null;
  const max = Math.max(...points.map((p) => p.amount), 1);
  const step = points.length > 1 ? (W - 40) / (points.length - 1) : 0;
  const xy = points.map((p, i) => [20 + i * step, H - 36 - (p.amount / max) * (H - 76)] as const);
  const path = xy.map(([x, y], i) => (i === 0 ? `M${x} ${y}` : `L${x} ${y}`)).join(' ');
  return (
    <svg viewBox={`0 0 ${W} ${H}`} style={{ width: '100%', height: 'auto', display: 'block' }}
      role="img" aria-label="최근 3개월 소비 추이">
      <path d={`${path} L${xy[xy.length - 1][0]} ${H - 36} L${xy[0][0]} ${H - 36} Z`}
        fill="var(--brand-weak)" opacity="0.6" />
      <path d={path} fill="none" stroke="var(--blue)" strokeWidth="2.5"
        strokeLinecap="round" strokeLinejoin="round" />
      {xy.map(([x, y], i) => (
        <g key={points[i].label}>
          <circle cx={x} cy={y} r={i === xy.length - 1 ? 5 : 3.5} fill="#fff"
            stroke="var(--blue)" strokeWidth="2.5" />
          <text x={x} y={H - 14} textAnchor="middle" fontSize="12" fill="var(--t3)"
            fontWeight="500">{points[i].label}</text>
          <text x={x} y={y - 12} textAnchor="middle" fontSize="12" fill="var(--t1)"
            fontWeight="700">{wonNum(points[i].amount)}</text>
        </g>
      ))}
    </svg>
  );
}

export function Report() {
  const { go, userId, view, setView } = useSession();
  const { home, loading: guardianLoading } = useGuardian();
  /**
   * 주간이냐 월간이냐 — 0818 이 나눈 두 갈래. <b>주소가 정본이다</b>(`#/report?period=month`).
   *
   * <p>예전에는 `useState` 였다. 그래서 주간→월간이 이력에 <b>한 칸도 안 쌓였고</b>, 소비내역에서
   * 뒤로 누르면 월간을 건너뛰고 주간으로 갔다 — 사용자가 밟은 자리가 사라진 것이다.
   * 갈래를 주소에 실으면 그것이 이력의 한 칸이 되고, 새로고침·링크 공유에도 유지된다.
   */
  const period: 'week' | 'month' = view.period === 'month' ? 'month' : 'week';
  const setPeriod = (next: 'week' | 'month') => setView(next === 'week' ? {} : { period: next });
  const [weeksAgo, setWeeksAgo] = useState(0);
  const [mode, setMode] = useState<0 | 1>(0);
  const [pickOpen, setPickOpen] = useState(false);
  const [pick, setPick] = useState<WeekSel | null>(null);
  /** 1년 뒤 카드가 지금 보여주는 항목. 탭하면 다음으로 — 2초 자동 순환은 아래 effect. */

  /** '오늘'은 서버가 정한다 — 데모 시계를 켜면 실제 오늘과 다르다(원칙 3). */
  const today = home?.asOf ? new Date(`${home.asOf.slice(0, 10)}T00:00:00`) : new Date();
  // 챌린지가 없으면 404다 — 리포트 나머지는 멀쩡히 보여야 하므로 조용히 비운다.
  const weekly = useAsync(
    () => api.guardian.weeklyReport(userId, weeksAgo).catch(() => null),
    [userId, weeksAgo],
  );
  const report = useAsync(() => api.report(userId).catch(() => null), [userId]);
  /**
   * <b>챌린지와 무관한 기간 집계.</b> 위의 `weekly` 는 지킴이 것이라 챌린지가 없으면 404 이고,
   * 있어도 시작일 전은 안 센다 — 그건 "약속을 지켰나"를 재는 화면이라 맞는 규칙이다.
   * 리포트가 답할 질문은 "이 기간에 어떻게 썼나"라서, 소비만 보는 답이 따로 필요하다
   * (사용자 보고 2026-08-20: 소비 내역엔 결제가 잔뜩인데 리포트가 비었다).
   */
  const spend = useAsync(
    () => api.periodSpend(userId, period, period === 'week' ? weeksAgo : 0).catch(() => null),
    [userId, period, weeksAgo],
  );
  /** 또래 비교 — 204 면 `null` 이고 그 절은 안 그린다. */
  const peer = useAsync(() => api.peerCompare(userId, period === 'week' ? 7 : 30)
    .catch(() => null), [userId, period]);
  /** 월간 캐러셀이 쓸 결제 — 가맹점 빈도·최대액·연속 주를 여기서 센다. */
  const payments = useAsync(() => (period === 'month'
    ? api.allPayments(userId, 3).catch(() => []) : Promise.resolve([])), [userId, period]);

  const w = weekly.data;
  const ch = home?.challenge;
  const isCur = weeksAgo === 0;
  /**
   * 지킴이가 아직 안 왔을 뿐인데 본문을 띄우면 <b>있는 챌린지를 없다고 말하는 것</b>이다.
   * 다만 <b>`!home` 으로 판단하면 안 된다</b> — 챌린지가 없으면 `home` 은 영영 null 이라
   * 화면이 스켈레톤에 갇힌다(사용자 보고 2026-08-20). 기다리는 것과 없는 것은 다르고,
   * 그 구분은 문맥의 `loading` 이 갖고 있다.
   */
  const waiting = guardianLoading || spend.loading;

  /* ── 기간 표시 ─────────────────────────────────────────────────────── */
  const rangeText = period === 'week'
    ? (w ? fmtRange(w.weekStart, w.weekEnd) : '이번 주')
    : `${today.getFullYear()}. ${today.getMonth() + 1}월`;

  /* ── ① 히어로 ──────────────────────────────────────────────────────── */
  const goal = ch?.targetSaving ?? 0;
  const used = ch ? Math.max(0, ch.baselineAmount - ch.securedSaving - (ch.challengeCap - ch.remainingCap)) : 0;
  /**
   * 진행바는 <b>쓴 돈 중 낭비로 본 몫</b>이다.
   *
   * 본 서비스에서는 '예산을 얼마나 썼는가'였는데 여기엔 예산이 없다. 그 자리를 비워 두면
   * 막대가 늘 0이라 아무 말도 안 하므로, 같은 자리에 다른 뜻을 넣고 범례의 글자를 맞췄다.
   */
  const flagged = spend.data?.flagged ?? 0;
  const spentTotal = spend.data?.total ?? 0;
  const flaggedRatio = spentTotal > 0 ? Math.min(1, flagged / spentTotal) : 0;
  void used; void goal;

  /* ── ③ 도넛 ───────────────────────────────────────────────────────── */
  const cats = useMemo(() => {
    const rows = (spend.data?.byCategory ?? [])
      .map((c) => ({ code: c.code, label: c.name, spent: c.amount, share: 0, cap: 0 }))
      .filter((c) => c.spent > 0)
      .slice().sort((a, b) => b.spent - a.spent);
    const total = rows.reduce((s, c) => s + c.spent, 0);
    if (total === 0) return { rows: [], total: 0, slices: [] as { d: string; fill: string }[] };
    const head = rows.slice(0, 5);
    const tail = rows.slice(5);
    const shown = tail.length
      ? [...head, { code: '기타', label: '기타', spent: tail.reduce((s, c) => s + c.spent, 0), share: 0, cap: 0 }]
      : head;
    let acc = 0;
    const slices = shown.map((c, i) => {
      const from = (acc / total) * 360;
      acc += c.spent;
      return { d: arc(from, (acc / total) * 360), fill: i < DONUT.length ? DONUT[i] : DONUT_ETC };
    });
    return { rows: shown, total, slices };
  }, [spend.data?.byCategory]);

  /* ── ③-2 월간 캐러셀 ────────────────────────────────────────────────── */
  const places = useMemo(() => {
    const rows = payments.data ?? [];
    if (rows.length === 0) return [];
    const byName = new Map<string, { count: number; sum: number; weeks: Set<string> }>();
    for (const p of rows) {
      const name = p.merchantName?.trim();
      if (!name) continue;
      const cur = byName.get(name) ?? { count: 0, sum: 0, weeks: new Set<string>() };
      cur.count += 1;
      cur.sum += p.amount;
      cur.weeks.add(p.date.slice(0, 10).slice(0, 7) + '-' + Math.ceil(Number(p.date.slice(8, 10)) / 7));
      byName.set(name, cur);
    }
    const list = [...byName.entries()];
    if (list.length === 0) return [];
    const most = list.slice().sort((a, b) => b[1].count - a[1].count)[0];
    const biggest = list.slice().sort((a, b) => b[1].sum - a[1].sum)[0];
    const steady = list.slice().sort((a, b) => b[1].weeks.size - a[1].weeks.size)[0];
    const out = [
      { cap: '가장 많이 간 곳은', name: most[0], val: `${most[1].count}회`, img: MO_MOST },
      { cap: '가장 큰 소비는', name: biggest[0], val: won(biggest[1].sum), img: MO_BIGGEST },
    ];
    if (steady[1].weeks.size >= 2) {
      out.push({ cap: '매주 빠지지 않고 쓴 곳은', name: steady[0],
        val: `${steady[1].weeks.size}주 연속`, img: MO_WEEKLY });
    }
    return out;
  }, [payments.data]);


  /* ── ② 월간 선 그래프 ──────────────────────────────────────────────── */
  const monthPoints = useMemo(() => {
    const map = report.data?.monthlySpend ?? {};
    return Object.keys(map).sort().slice(-3)
      .map((k) => ({ label: `${Number(k.slice(5, 7))}월`, amount: map[k] }));
  }, [report.data]);

  /** 차트 위 한 줄 요약. 모드마다 무엇을 견주는지가 다르다. */
  /**
   * <b>기준은 챌린지 유무다.</b> 지킴이 주간 리포트는 챌린지가 없어도 0으로 채운 계열을
   * 주기 때문에, "`w` 가 있으면 그것을 쓴다"로 판단하면 히어로는 8만원인데 차트는 0원인
   * 어긋남이 생긴다(실측 2026-08-20). 챌린지가 없으면 <b>소비 집계로 같은 모양을 만든다</b> —
   * 차트·평균이 그대로 돌아가고, 판정(`kept`/`judged`)만 없다. 판정은 챌린지의 개념이라
   * 없는 것이 맞고, 있는 척하면 안 된다.
   */
  const DOW = ['일', '월', '화', '수', '목', '금', '토'];
  const days: DayPoint[] = (ch && w?.days?.length ? w.days
    : (spend.data?.days ?? []).map((d) => ({
      date: d.date,
      label: DOW[new Date(`${d.date}T00:00:00`).getDay()] ?? '',
      amount: d.amount,
      kept: false,
      judged: false,
    })));

  const lead = mode === 0
    ? (() => {
      // 오늘은 `judged=false` 라 빠진다 — 쓴 돈이 있으면 오늘도 평균에 넣는다.
      /* 지킴이 계열이 있으면 그것을, 없으면 <b>서버가 준 기간 합계</b>를 쓴다 —
         후자는 빈 날까지 포함한 칸 수로 나눈다(그 기간의 하루 평균이 그 뜻이다). */
      const shown = days.filter((d) => d.judged || d.amount > 0);
      const avg = ch && w?.days?.length
        ? (shown.length ? Math.round(shown.reduce((a, d) => a + d.amount, 0) / shown.length) : 0)
        : (spend.data && spend.data.days.length
          ? Math.round(spend.data.total / spend.data.days.length) : 0);
      return { label: '하루 평균', value: <><b>{won(avg)}</b> 썼어요</> };
    })()
    : (() => {
      const t = w?.trend ?? [];
      const cur = t[t.length - 1]?.defenseRate ?? 0;
      const prev = t[t.length - 2]?.defenseRate ?? 0;
      const diff = Math.round((cur - prev) * 100);
      return {
        label: isCur ? '지난 주보다' : '그 전 주보다',
        value: diff >= 0 ? <><b>{diff}%p 더</b> 지켰어요</> : <><b>{Math.abs(diff)}%p 덜</b> 지켰어요</>,
      };
    })();

  /**
   * 프로토타입의 `#rpEmpty` — <b>기록이 아예 없는 지난 주</b>에만 뜬다.
   *
   * <p>이번 주에까지 쓰면 안 된다. 챌린지를 오늘 시작한 사람은 판정된 날이 하루도 없는데,
   * 그때 "분석할 소비가 없어요"를 띄우면 <b>지킨 돈이 있는데 없다고 말하는 셈</b>이다
   * (실측으로 그렇게 나왔다). 이번 주는 늘 본문을 세우고, 빈 절은 각자 자리에서 말한다.
   */
  /** 그 기간에 결제가 하나라도 있었는가 — 지킴이가 아니라 <b>소비</b>가 기준이다. */
  const hasSpend = (spend.data?.count ?? 0) > 0 || days.some((d) => d.amount > 0);
  const showEmpty = period === 'week' && weeksAgo > 0 && !spend.loading && !hasSpend;

  return (
    <Screen id="report" title="리포트" hasTabBar>
      <Scroll>
        {/* ── 주간 · 월간 ─────────────────────────────────────────────── */}
        <div className="rp-seg" role="tablist">
          <button type="button" role="tab" aria-selected={period === 'week'}
            className={period === 'week' ? 'on' : undefined}
            onClick={() => setPeriod('week')}>주간</button>
          <button type="button" role="tab" aria-selected={period === 'month'}
            className={period === 'month' ? 'on' : undefined}
            onClick={() => setPeriod('month')}>월간</button>
        </div>
        <div className="rp-line" style={{ margin: '0 0 4px' }} />

        {/* 기간 고르기 — 주간만 고를 수 있다(월간은 이번 달 하나다). */}
        <div className="rp-sec">
          <button type="button" className="rp-date" disabled={period === 'month'}
            aria-label="기간 선택"
            onClick={() => { setPick(selOf(today, weeksAgo)); setPickOpen(true); }}>
            <b>{rangeText}</b>
            {period === 'week' && <span className="car" aria-hidden="true">▾</span>}
          </button>
        </div>

        {waiting ? (
          <div className="rp-sec"><Loading label="리포트를 불러오는 중" rows={6} /></div>
        ) : !ch && !hasSpend ? (
          /* **챌린지가 아니라 소비가 없을 때만 비운다.** 예전에는 챌린지가 없으면 본문을
             통째로 감췄는데, 리포트가 답할 질문은 "이 기간에 어떻게 썼나"라 챌린지와
             무관하다 — 소비 내역엔 결제가 잔뜩인데 리포트만 비었다(사용자 보고 2026-08-20). */
          <div className="rp-sec">
            <div className="rp-emp">
              <b>이 기간에는 분석할 소비가 없어요</b>
              <p>결제가 쌓이면 그 주부터 보여드릴게요</p>
              <button type="button" className="btn btn-primary"
                style={{ marginTop: 16 }} onClick={() => go('ob')}>챌린지 시작하기</button>
            </div>
          </div>
        ) : showEmpty ? (
          <div className="rp-sec">
            <div className="rp-emp">
              <b>이 주에는 분석할 소비가 없어요</b>
              <p>기록이 있는 주로 이동하면 리포트를 보여드릴게요</p>
            </div>
          </div>
        ) : (
          <>
            {/* ── ① 진행 히어로 ─────────────────────────────────────── */}
            <div className="rp-sec">
              {/* 챌린지가 있으면 <b>약속 대비</b>를, 없으면 <b>쓴 돈</b>을 말한다.
                  없는 약속을 0원짜리로 그리면 "지킨 돈 0원"이 되어 사실과 어긋난다. */}
              <div className="rph1">
                {isCur ? (period === 'week' ? '이번 주' : '이번 달')
                  : (period === 'week' ? '그 주' : '그 달')}{' '}
                쓴 돈은<br />
                <em>{wonNum(spend.data?.total ?? 0)}원</em>이에요
              </div>
              {hasSpend ? (
                <>
                  <div className="rp-prog">
                    <i style={{ width: `${Math.round(flaggedRatio * 100)}%` }} />
                  </div>
                  <div className="rp-leg">
                    <span><i style={{ background: 'var(--t4)' }} />쓴 돈<b>{won(spend.data?.total ?? 0)}</b></span>
                    <span><i style={{ background: 'var(--blue)' }} />낭비<b>{won(flagged)}</b></span>
                  </div>
                </>
              ) : (
                <div className="rp-leg">
                  <span><i style={{ background: 'var(--blue)' }} />결제<b>{spend.data?.count ?? 0}건</b></span>
                  <span>
                    <i style={{ background: 'var(--t4)' }} />하루 평균
                    <b>{won(days.length ? Math.round((spend.data?.total ?? 0) / days.length) : 0)}</b>
                  </span>
                </div>
              )}
            </div>
            <div className="rp-band" />

            {/* ── ② 차트 ────────────────────────────────────────────── */}
            <div className="rp-sec">
              {period === 'month' ? (
                <>
                  <div className="rph3" style={{ margin: '4px 0 32px' }}>최근 3개월 소비</div>
                  {monthPoints.length > 0
                    ? <MonthLine points={monthPoints} />
                    : <p className="empty">아직 견줄 달이 없어요.</p>}
                  <button type="button" className="rp-more" onClick={() => go('transactions')}>
                    전체 내역 보기<span className="chev" aria-hidden="true">›</span>
                  </button>
                </>
              ) : (
                <>
                  <WeekChart mode={mode} onMode={setMode} days={days}
                    trend={w?.trend ?? []} lead={lead} />
                  <button type="button" className="rp-more" onClick={() => go('transactions')}>
                    전체 내역 보기<span className="chev" aria-hidden="true">›</span>
                  </button>
                </>
              )}
            </div>
            <div className="rp-band" />

            {/* ── ③ 가장 많이 쓴 곳 ─────────────────────────────────── */}
            <div className="rp-sec">
              <div className="rph2">
                {cats.rows.length > 0
                  ? <>가장 많이 쓴 곳은<br /><b>{cats.rows[0].label}</b>이에요</>
                  : <>아직 집계된 소비가 없어요</>}
              </div>
              {cats.rows.length > 0 && (
                <>
                  <div className="dn-wrap">
                    <svg viewBox="0 0 200 200" role="img"
                      aria-label={`카테고리별 소비 비중, 1위 ${cats.rows[0].label}`}>
                      {cats.slices.map((s, i) => <path key={i} d={s.d} fill={s.fill} />)}
                    </svg>
                    <div className="dn-badge">
                      <span>1위</span>
                      <b>{Math.round((cats.rows[0].spent / cats.total) * 100)}%</b>
                    </div>
                  </div>
                  <div>
                    {cats.rows.map((c, i) => {
                      const { icon, bg } = iconOf(c.label);
                      return (
                        <div className="dnrow" key={c.code}>
                          <span className="ic" style={{ background: bg }}><Icon id={icon} /></span>
                          <span className="dl">
                            <b>{c.label}</b>
                            <i>{Math.round((c.spent / cats.total) * 100)}%</i>
                          </span>
                          <span className="dr">
                            <b>{won(c.spent)}</b>
                            <i>{i + 1}위</i>
                          </span>
                        </div>
                      );
                    })}
                  </div>
                  <button type="button" className="rp-more" onClick={() => go('r-rank')}>
                    전체 순위 보기<span className="chev" aria-hidden="true">›</span>
                  </button>
                </>
              )}
            </div>

            {/* ── ③-2 월간: 가장 많이 간 곳 ──────────────────────────── */}
            {period === 'month' && places.length > 0 && (
              <>
                <div className="rp-band" />
                <div className="mo-car">
                  {places.map((p) => (
                    <div className="mo-card" key={p.cap}>
                      <div className="mct"><span>{p.cap}</span><b>{p.name}</b><em>{p.val}</em></div>
                      <img className="mci" src={p.img} alt="" aria-hidden="true" />
                    </div>
                  ))}
                </div>
                <div className="mo-dots" aria-hidden="true">
                  {places.map((p, i) => <i key={p.cap} className={i === 0 ? 'on' : undefined} />)}
                </div>
              </>
            )}
            <div className="rp-band" />

            {/* ── ④ 또래 비교 — 견줄 수 없으면 절이 통째로 사라진다 ──── */}
            {peer.data && (
              <>
                <div className="rp-sec">
                  <div className="rph2">
                    또래보다 <b>{peer.data.mine <= peer.data.peer ? '적게' : '많이'}</b> 썼어요
                  </div>
                  <div className="peer">
                    <div className="pcol">
                      <div className="pbar gray" style={{ height: barH(peer.data.peer, peer.data) }} />
                      <b className="gray">{wonNum(peer.data.peer)}</b>
                      <span>또래</span>
                    </div>
                    <div className="pcol">
                      <div className="pbar green" style={{ height: barH(peer.data.mine, peer.data) }} />
                      <b className="green">{wonNum(peer.data.mine)}</b>
                      <span>나</span>
                    </div>
                  </div>
                  <p className="pv" style={{ textAlign: 'center', margin: '8px 0 0' }}>
                    {peer.data.ageFrom}~{peer.data.ageTo}년생 {peer.data.sampleSize}명의 <b>중앙값</b>이에요
                    · 최근 {peer.data.days}일
                  </p>
                </div>
                <div className="rp-band" />
              </>
            )}

          </>
        )}
        <div className="spacer" style={{ height: 24 }} />
      </Scroll>

      {pick && (
        <WeekPicker open={pickOpen} sel={pick} today={today}
          onChange={setPick} onClose={() => setPickOpen(false)}
          onConfirm={() => {
            setWeeksAgo(weeksAgoOf(today, pick));
            setPickOpen(false);
          }} />
      )}
    </Screen>
  );
}

/** 또래 막대 높이 — 큰 쪽을 120px 로 두고 비례로 줄인다. 0이어도 8px 는 남긴다. */
function barH(v: number, p: { mine: number; peer: number }) {
  const max = Math.max(p.mine, p.peer, 1);
  return `${Math.max(8, Math.round((v / max) * 120))}px`;
}

/** 지금 보고 있는 주(= N주 전)를 휠의 (연,월,주)로. */
function selOf(today: Date, weeksAgo: number): WeekSel {
  const d = mondayOfWeek(today);
  d.setDate(d.getDate() - weeksAgo * 7);
  return { y: d.getFullYear(), m: d.getMonth() + 1, w: weekOfMonth(d) };
}

/**
 * 휠에서 고른 주가 몇 주 전인가.
 *
 * <b>날짜 차이를 7로 나눈다.</b> 달을 건너뛰며 주를 세면 5주짜리 달에서 한 주씩 어긋난다.
 * 음수(미래)는 0으로 — 휠이 미래를 안 주지만, 데모 시계로 오늘이 바뀌면 어긋날 수 있다.
 */
function weeksAgoOf(today: Date, sel: WeekSel): number {
  const cur = mondayOfWeek(today);
  const got = mondayOf(sel);
  const days = Math.round((cur.getTime() - got.getTime()) / 86400000);
  return Math.max(0, Math.round(days / 7));
}

/** 그 날이 속한 주의 월요일. 리포트의 주 기준과 같다. */
function mondayOfWeek(d: Date): Date {
  const out = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  out.setDate(out.getDate() - ((out.getDay() + 6) % 7));
  return out;
}
