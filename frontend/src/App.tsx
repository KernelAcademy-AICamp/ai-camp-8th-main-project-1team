import { useEffect, useRef, useState, type ReactNode } from 'react';
import {
  api, RULE_LABEL, catLabel,
  type AlertResponse, type ReportResponse,
  type ScoreResponse, type VerifyResponse, type DataSourceMode, type GoalRecommendation,
  type MyCard,
} from './api';
import { ConsumptionPanel } from './ConsumptionPanel';
import { SurveyPanel } from './SurveyPanel';
import { PointsPanel } from './PointsPanel';
import { ImpulseSaverPanel } from './ImpulseSaverPanel';
import { Onboarding } from './Onboarding';
import { MyCardPanel } from './MyCardPanel';
import { DonutChart, DonutLegend, BarChart } from './Charts';
import './App.css';

const USER_ID = 1;
const won = (n: number) => n.toLocaleString('ko-KR') + '원';
const wonShort = (n: number) =>
  n >= 10000 ? `${(n / 10000).toLocaleString('ko-KR', { maximumFractionDigits: 1 })}만원` : won(n);
const pct = (v: number) => `${Math.round(v * 100)}%`;
const GRADE_VAR: Record<string, string> = { A: 'var(--good)', B: 'var(--brand)', C: 'var(--warn)', D: 'var(--bad)' };

type Tab = 'home' | 'card' | 'spend' | 'save' | 'more';

/* ── 아이콘 (인라인 SVG) — 장식용이므로 aria-hidden ─────────────────── */
const Icon = {
  logo: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M3 17l5-5 4 3 6-7" /><path d="M14 8h4v4" />
    </svg>
  ),
  sun: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="12" cy="12" r="4" /><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4" />
    </svg>
  ),
  moon: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z" />
    </svg>
  ),
  shield: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 2l8 3v6c0 5-3.5 8.5-8 11-4.5-2.5-8-6-8-11V5l8-3z" /><path d="M9 12l2 2 4-4" />
    </svg>
  ),
  alert: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M12 9v4M12 17h.01" /><path d="M10.3 3.9L2 18a2 2 0 0 0 1.7 3h16.6a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z" />
    </svg>
  ),
  check: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M20 6L9 17l-5-5" />
    </svg>
  ),
  /* 하단 탭 아이콘 */
  home: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M3 10.5L12 3l9 7.5" /><path d="M5 9.5V20h14V9.5" /><path d="M9.5 20v-6h5v6" />
    </svg>
  ),
  card: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <rect x="2.5" y="5" width="19" height="14" rx="2.5" /><path d="M2.5 9.5h19M6 15h4" />
    </svg>
  ),
  chart: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 20V10M10 20V4M16 20v-7M4 20h16" />
    </svg>
  ),
  save: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d="M4 8a8 5 0 0 0 16 0 8 5 0 0 0-16 0z" /><path d="M4 8v6a8 5 0 0 0 16 0V8" /><path d="M12 11v3" />
    </svg>
  ),
  more: (
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <circle cx="5" cy="12" r="1" /><circle cx="12" cy="12" r="1" /><circle cx="19" cy="12" r="1" />
    </svg>
  ),
};

const TABS: { id: Tab; label: string; icon: ReactNode }[] = [
  { id: 'home', label: '홈', icon: Icon.home },
  { id: 'card', label: '내 카드', icon: Icon.card },
  { id: 'spend', label: '내 소비', icon: Icon.chart },
  { id: 'save', label: '혜택·저축', icon: Icon.save },
  { id: 'more', label: '더보기', icon: Icon.more },
];

/* ── 추정/확정 배지 (문서 §5-2 ②) ──────────────────────────────── */
function ModeBadge({ mode, reason }: { mode?: DataSourceMode; reason?: string | null }) {
  if (!mode) return null;
  const confirmed = mode === 'CONFIRMED';
  return (
    <span className={`mode ${confirmed ? 'mode-confirmed' : 'mode-estimated'}`}>
      <span className="dot" aria-hidden="true" />
      {confirmed ? '실제 소비 데이터' : '참고용 추정치'}
      {reason && <span className="mode-reason">· {reason}</span>}
    </span>
  );
}

/* ── 점수 게이지 ──────────────────────────────────────────────── */
function ScoreGauge({ score, grade }: { score: number; grade: string }) {
  const r = 42, C = 2 * Math.PI * r;
  const offset = C * (1 - Math.max(0, Math.min(100, score)) / 100);
  return (
    <div className="gauge" role="img" aria-label={`소비 건강 점수 ${score}점, ${grade}등급`}>
      <svg viewBox="0 0 100 100" aria-hidden="true">
        <circle className="track" cx="50" cy="50" r={r} strokeWidth="9" />
        <circle className="prog" cx="50" cy="50" r={r} strokeWidth="9"
          strokeDasharray={C} strokeDashoffset={offset} style={{ stroke: GRADE_VAR[grade] ?? 'var(--brand)' }} />
      </svg>
      <div className="center" aria-hidden="true">
        <div className="g-score">{score}<small>점</small></div>
        <div className={`g-grade pill-bg grade-${grade}`}>{grade}등급</div>
      </div>
    </div>
  );
}

function Factor({ label, value }: { label: string; value: number }) {
  return (
    <div className="factor">
      <span className="f-label">{label}</span>
      <span className="f-track" aria-hidden="true"><span className="f-fill" style={{ width: pct(Math.min(1, value)) }} /></span>
      <span className="f-val">{pct(value)}</span>
    </div>
  );
}

/* ── 카테고리 소비 막대 ──────────────────────────────────────── */
function CatBar({ code, name, amount, spendPercent, max, tone }:
  { code: string; name: string; amount: number; spendPercent: number; max: number; tone: 'pos' | 'neg' }) {
  return (
    <div className={`catbar ${tone}`}>
      <span className="c-name">{catLabel(code, name)}</span>
      <span className="c-track" aria-hidden="true"><span className="c-fill" style={{ width: `${Math.max(6, (spendPercent / max) * 100)}%` }} /></span>
      <span className="c-meta"><b>{spendPercent}%</b> · {wonShort(amount)}</span>
    </div>
  );
}

export default function App() {
  const [onboarded, setOnboarded] = useState<boolean>(
    () => typeof localStorage !== 'undefined' && localStorage.getItem('mydata_onboarded') === 'true',
  );
  const [alerts, setAlerts] = useState<AlertResponse | null>(null);
  const [goalRecs, setGoalRecs] = useState<GoalRecommendation[]>([]);
  const [report, setReport] = useState<ReportResponse | null>(null);
  const [score, setScore] = useState<ScoreResponse | null>(null);
  const [audit, setAudit] = useState<VerifyResponse | null>(null);
  const [myCards, setMyCards] = useState<MyCard[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [anchoring, setAnchoring] = useState(false);
  const [anchorMsg, setAnchorMsg] = useState<string | null>(null);
  const [tab, setTab] = useState<Tab>('home');
  const [theme, setTheme] = useState<'light' | 'dark'>(
    () => (typeof localStorage !== 'undefined' && localStorage.getItem('theme') === 'dark' ? 'dark' : 'light'),
  );
  const mainRef = useRef<HTMLElement>(null);
  const mounted = useRef(false);

  useEffect(() => {
    document.documentElement.dataset.theme = theme;
    try { localStorage.setItem('theme', theme); } catch { /* noop */ }
  }, [theme]);

  useEffect(() => {
    if (!mounted.current) { mounted.current = true; return; }
    window.scrollTo({ top: 0 });
    mainRef.current?.focus();
  }, [tab]);

  async function loadMyData() {
    try { setMyCards(await api.myCards(USER_ID)); } catch { /* 미연동이면 빈 배열 */ }
  }

  async function loadAll() {
    setLoading(true);
    setError(null);
    try {
      const [a, rp, s, v] = await Promise.all([
        api.alerts(USER_ID), api.report(USER_ID),
        api.score(USER_ID), api.verifyAudit(),
      ]);
      setAlerts(a); setReport(rp); setScore(s); setAudit(v);
      void loadMyData();

      void api.goalRecommendations(USER_ID).then((gr) => {
        setGoalRecs(gr);
        void api.track('recommend_view', USER_ID, { itemCount: gr.length });
      }).catch(() => undefined);
      void api.track('report_view', USER_ID);
      void api.track('alert_view', USER_ID, { alertCount: a.items.length });
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e));
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => { if (onboarded) void loadAll(); }, [onboarded]);

  async function rescan() {
    try { await api.rescan(USER_ID); await loadAll(); }
    catch (e) { setError(e instanceof Error ? e.message : String(e)); }
  }

  async function anchor() {
    setAnchoring(true);
    try {
      const r = await api.anchorAudit();
      setAnchorMsg(r.tsaEnabled
        ? `앵커링 ${r.anchored}건 성공 / ${r.failed}건 실패`
        : 'TSA가 꺼져 있습니다 — TSA_ENABLED=true 로 서버를 띄우면 외부 시각 증명이 붙습니다.');
      await loadAll();
    } catch (e) {
      setAnchorMsg(e instanceof Error ? e.message : String(e));
    } finally {
      setAnchoring(false);
    }
  }

  // 온보딩 미완료면 온보딩 화면만 (진입 필수)
  if (!onboarded) {
    return <Onboarding userId={USER_ID} onDone={() => setOnboarded(true)} />;
  }

  const hitCount = alerts?.items.length ?? 0;
  const maxPct = Math.max(
    1, ...(report ? [...report.positive, ...report.negative].map((l) => l.spendPercent) : [1]),
  );
  const months = report ? Object.entries(report.monthlySpend).sort(([a], [b]) => a.localeCompare(b)) : [];

  // 마이데이터 이번달 요약
  const monthSpend = myCards.reduce((sum, c) => sum + c.currentPerformance, 0);
  const monthBenefit = myCards.reduce((sum, c) => sum + c.earnedThisMonth, 0);

  // 도넛 슬라이스: 리포트 카테고리(양·음) 합쳐 금액순
  const donutSlices = report
    ? [...report.negative, ...report.positive]
        .map((l) => ({ label: catLabel(l.categoryCode, l.displayName), value: l.amount }))
        .sort((a, b) => b.value - a.value)
    : [];

  return (
    <div className="app">
      <a href="#main" className="skip-link">본문 바로가기</a>

      <header className="topbar">
        <div className="topbar-in">
          <div className="brand">
            <span className="logo" aria-hidden="true">{Icon.logo}</span>
            <span className="name">소비·저축 어드바이저<small>줄인 만큼 모이는 걸 보여주는 도우미</small></span>
          </div>
          <span className="spacer" />
          <span className="dummy-badge">더미 · 학습용</span>
          <button type="button" className="theme-toggle" onClick={() => setTheme((t) => (t === 'dark' ? 'light' : 'dark'))}
            aria-label={theme === 'dark' ? '밝은 테마로 전환' : '어두운 테마로 전환'} title={theme === 'dark' ? '밝은 테마로' : '어두운 테마로'}>
            {theme === 'dark' ? Icon.sun : Icon.moon}
          </button>
        </div>
      </header>

      <main id="main" className="wrap" ref={mainRef} tabIndex={-1} aria-label="주요 콘텐츠">
        <h1 className="sr-only">소비·저축 어드바이저 — {TABS.find((t) => t.id === tab)?.label} 화면</h1>

        {error && (
          <div className="error" role="alert">
            <strong>데이터를 불러오지 못했어요</strong>
            <code>{error}</code>
            <p>백엔드(8080)와 마이데이터 서버(8082)가 실행 중인지 확인해 주세요.</p>
          </div>
        )}
        {loading && <div className="loading-bar" role="status" aria-label="불러오는 중" />}

        {/* ── 홈 ── */}
        {tab === 'home' && (
          <div className="view">
            <div className="greet-row">
              <p className="hero-greet">안녕하세요 👋</p>
              <p className="greet-sub">이번 달 소비와 받은 혜택을 한눈에. 줄인 만큼은 <b>절약통에 저절로 쌓여요</b>.</p>
            </div>

            <section className="section card card-pad" aria-labelledby="h-home-md">
              <div className="section-head" style={{ marginBottom: 10 }}>
                <h2 id="h-home-md">이번 달 요약</h2><span className="badge-aux">마이데이터</span>
              </div>
              <div className="md-summary">
                <div className="md-stat"><span className="md-l">이번 달 사용</span><b className="md-v">{won(monthSpend)}</b></div>
                <div className="md-stat"><span className="md-l">받은 혜택</span><b className="md-v sav">{won(monthBenefit)}</b></div>
                <div className="md-stat"><span className="md-l">연결 카드</span><b className="md-v">{myCards.length}장</b></div>
              </div>
            </section>

            <ImpulseSaverPanel userId={USER_ID} />

            <section className="section card card-pad" aria-labelledby="h-home-score">
              <div className="section-head" style={{ marginBottom: 14 }}>
                <h2 id="h-home-score">소비 건강 점수</h2>
                {score && <ModeBadge mode={score.dataSourceMode} reason={score.estimationReason} />}
              </div>
              {score ? (
                <div className="score-body">
                  <ScoreGauge score={score.score} grade={score.grade} />
                  <div className="factors" style={{ marginTop: 0 }}>
                    <Factor label="저축 진행률" value={score.breakdown.savingsProgress} />
                    <Factor label="소비 안정성" value={score.breakdown.stability} />
                    <Factor label="계획 소비" value={score.breakdown.plannedRatio} />
                  </div>
                </div>
              ) : <p className="muted">불러오는 중…</p>}
            </section>
          </div>
        )}

        {/* ── 내 카드 ── */}
        {tab === 'card' && <MyCardPanel userId={USER_ID} />}

        {/* ── 내 소비 (차트 + 리포트 + 추천 + 이상소비) ── */}
        {tab === 'spend' && (
          <div className="view">
            <section className="section card card-pad" aria-labelledby="h-donut">
              <div className="section-head" style={{ marginBottom: 12 }}>
                <h2 id="h-donut">카테고리별 소비</h2>
                <ModeBadge mode={report?.dataSourceMode} reason={report?.estimationReason} />
              </div>
              {donutSlices.length > 0 ? (
                <div className="donut-wrap">
                  <DonutChart slices={donutSlices} centerLabel={report ? wonShort(report.totalSpend) : ''} />
                  <DonutLegend slices={donutSlices} />
                </div>
              ) : <p className="muted small">아직 소비 데이터가 없어요.</p>}
              {months.length > 1 && (
                <>
                  <div className="chart-sub">월별 지출</div>
                  <BarChart bars={months.map(([m, v]) => ({ label: m.slice(5), value: v }))} />
                </>
              )}
            </section>

            <section className="section card card-pad" aria-labelledby="h-report">
              <div className="section-head" style={{ marginBottom: 12 }}><h2 id="h-report">절약 리포트</h2></div>
              {report && (
                <>
                  <div className="narrative">
                    {report.narrative}
                    <span className="src">{report.narrativeSource === 'AI' ? '✦ AI가 요약했어요' : '고정 템플릿'}</span>
                  </div>
                  <div className="catbars">
                    {report.negative.map((l) => (
                      <CatBar key={l.categoryCode} code={l.categoryCode} name={l.displayName}
                        amount={l.amount} spendPercent={l.spendPercent} max={maxPct} tone="neg" />
                    ))}
                    {report.positive.map((l) => (
                      <CatBar key={l.categoryCode} code={l.categoryCode} name={l.displayName}
                        amount={l.amount} spendPercent={l.spendPercent} max={maxPct} tone="pos" />
                    ))}
                  </div>
                  <div className="cat-legend">
                    <span><i style={{ background: 'var(--bad)' }} aria-hidden="true" />줄이면 좋은 소비</span>
                    <span><i style={{ background: 'var(--good)' }} aria-hidden="true" />잘 관리한 소비</span>
                  </div>
                </>
              )}
            </section>

            <section className="section card card-pad" aria-labelledby="h-rec">
              <div className="section-head" style={{ marginBottom: 12 }}>
                <h2 id="h-rec">맞춤 상품 추천 <span className="badge-aux">목표별 · 정보성</span></h2>
              </div>
              <div className="rec-grid">
                {goalRecs.map((g) => (
                  <button type="button" className="product" key={g.goalId}
                    onClick={() => void api.track('product_click', USER_ID, { goalId: g.goalId })}
                    aria-label={g.productName
                      ? `${g.goalName} 추천 통장 ${g.company} ${g.productName}, 기본금리 ${g.baseRate.toFixed(2)}%, ${g.periodMonths}개월`
                      : `${g.goalName} 추천 준비 중`}>
                    <div className="p-top">
                      <span className="p-rank" aria-hidden="true">{g.emoji}</span>
                      <span className="p-name">{g.productName ? `${g.company} ${g.productName}` : '추천 준비 중'}</span>
                      {g.productName && <span className="p-rate">{g.baseRate.toFixed(2)}%<small> 기본</small></span>}
                    </div>
                    <div className="p-meta">
                      <b>{g.goalName}</b> · {g.periodMonths}개월
                      {g.planMonths > 0 ? ` · 계획 ${g.planMonths}개월` : ' · 계획 미설정(기본 기간)'}
                    </div>
                  </button>
                ))}
                {goalRecs.length === 0 && <p className="muted small">목표를 세우면 그 기간에 맞는 통장을 찾아드려요.</p>}
              </div>
            </section>

            <section className="section card card-pad" aria-labelledby="h-alert">
              <div className="section-head" style={{ marginBottom: 8 }}>
                <h2 id="h-alert">이상 소비 감지 <span className="badge-aux">부가</span></h2>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => void rescan()}>다시 검사</button>
              </div>
              <p className="muted small" style={{ marginTop: 0 }}>
                평소 패턴에서 크게 벗어난 결제를 잡아요.
                {alerts && ` 최근 ${alerts.evaluatedCount}건 중 ${hitCount}건 발견.`}
              </p>
              {hitCount > 0 ? (
                <ul className="alert-grid">
                  {alerts?.items.map((a) => (
                    <li className="alert-card" key={a.alertId}>
                      <div className="a-top">
                        <span className="a-cat">{catLabel(a.categoryCode)}</span>
                        <span className="a-amt">{won(a.amount)}</span>
                      </div>
                      <div className="a-when">🕐 {a.occurredAt.replace('T', ' ').slice(0, 16)}</div>
                      <div className="a-rules">
                        {a.matchedRules.map((r) => <span className="chip" key={r}>{RULE_LABEL[r] ?? r}</span>)}
                        <span className="chip z">평소보다 {a.deviationScore.toFixed(1)}σ</span>
                      </div>
                    </li>
                  ))}
                </ul>
              ) : <p className="muted small">지금은 이상 소비가 없어요. 👍</p>}
            </section>
          </div>
        )}

        {/* ── 혜택·저축 (게임화 저축·목표·통장비교·고민목록) ── */}
        {tab === 'save' && (
          <div className="view">
            <PointsPanel userId={USER_ID} onChanged={() => void loadAll()} />
          </div>
        )}

        {/* ── 더보기 (기록 + 안심 + 설문) ── */}
        {tab === 'more' && (
          <div className="view">
            <ConsumptionPanel userId={USER_ID} onChanged={() => void loadAll()} />

            <section className="section card card-pad" aria-labelledby="h-trust">
              <div className="trust-head">
                <span className="seal" aria-hidden="true">{Icon.shield}</span>
                <div>
                  <div className="section-head" style={{ marginBottom: 2 }}><h2 id="h-trust">데이터 무결성 보증</h2></div>
                  <span className="hint small">모든 처리 기록은 해시체인 + Merkle 트리로 봉인돼, 누가 몰래 고치면 즉시 드러납니다</span>
                </div>
              </div>
              {audit && (
                <>
                  <div style={{ marginTop: 16 }}>
                    <span className={`verdict ${audit.valid ? 'ok' : 'broken'}`}>
                      {audit.valid ? Icon.check : Icon.alert}
                      {audit.valid ? '검증 통과 — 변조 없음' : `변조 감지 — ${audit.firstBrokenSeq}번 기록에서 체인이 깨짐`}
                    </span>
                  </div>
                  <div className="trust-stats">
                    <div className="tstat"><div className="tv num">{audit.entryCount}</div><div className="tl">기록 엔트리</div></div>
                    <div className="tstat"><div className="tv num">{audit.batchCount}</div><div className="tl">Merkle 배치</div></div>
                    <div className={`tstat ${audit.anchoredBatchCount === 0 ? 'pending' : 'anchored'}`}>
                      <div className="tv num">{audit.anchoredBatchCount}/{audit.batchCount}</div><div className="tl">외부 TSA 앵커</div>
                    </div>
                  </div>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 12, flexWrap: 'wrap' }}>
                    <button type="button" className="btn btn-primary btn-sm" onClick={() => void anchor()} disabled={anchoring}>
                      {anchoring ? '앵커링 중… (요청 간 15초 지연)' : '외부 TSA에 시각 앵커링'}
                    </button>
                    {anchorMsg && <span className="muted small" role="status">{anchorMsg}</span>}
                  </div>
                  {audit.anchoredBatchCount === 0 && (
                    <div className="trust-note">
                      아직 외부 앵커가 없습니다. 해시체인만으로는 <strong>운영자가 DB를 통째로 재생성하는 공격</strong>을 막지 못해요 —
                      외부 TSA에 앵커링하면 이 공격까지 막습니다.
                    </div>
                  )}
                  {audit.problems.length > 0 && (
                    <ul className="problems">{audit.problems.map((p, i) => <li key={i}>{p}</li>)}</ul>
                  )}
                </>
              )}
            </section>

            <SurveyPanel userId={USER_ID} />
          </div>
        )}

        <footer className="foot">
          <strong>더미 데이터 기반 학습용 프로토타입입니다.</strong> 실제 금융거래·결제·송금 기능을 제공하지 않으며,
          표시되는 금융상품은 모두 <strong>실재하지 않는 더미 상품</strong>입니다. 마이데이터로 불러오는 카드·소비내역도 가상 데이터입니다.
        </footer>
      </main>

      <nav className="tabbar" aria-label="주요 화면">
        <div className="tabbar-in">
          {TABS.map((t) => (
            <button type="button" key={t.id} className="tab"
              aria-current={tab === t.id ? 'page' : undefined}
              onClick={() => setTab(t.id)}>
              <span className="tab-ic" aria-hidden="true">{t.icon}</span>
              <span className="tab-lb">{t.label}</span>
            </button>
          ))}
        </div>
      </nav>
    </div>
  );
}
