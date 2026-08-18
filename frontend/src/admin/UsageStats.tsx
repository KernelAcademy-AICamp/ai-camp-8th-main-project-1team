/**
 * 이용 통계 — GA4 표준 보고서에 대응하는 화면.
 *
 * <h2>화면을 지배하는 규칙 넷</h2>
 *
 * ① **먼저 큰 그림, 그다음 상세.** 맨 위 큰 그래프 하나로 흐름을 보고 아래로 파고든다.
 * ② **비교는 길이로.** 숫자만 세로로 쌓으면 사람은 1위와 3위의 차이를 못 읽는다.
 * ③ **설명은 숨겨 둔다.** 화면에 설명을 늘어놓으면 정작 숫자가 안 보인다. 대신 줄마다
 *    `?` 를 두고, 누르면 그 말이 무슨 뜻인지 친절하게 펼친다.
 * ④ **없는 것은 없다고 적는다.** 빈 판을 지우지 않는다 — 판이 사라지면 보는 사람은 그
 *    보고서가 존재하지 않는 줄 안다.
 *
 * <h2>`?` 안의 문장은 어디서 오나</h2>
 *
 * 사실은 서버가 들고 있고({@code UsageGlossary}), 무료 통로(NVIDIA)가 <b>말투만</b> 다듬는다.
 * `r-compare` 가 무슨 화면인지는 우리 라우터가 정한 이름이라 모델이 알 수 없기 때문이다 —
 * 물어보면 그럴듯하게 지어낸다. 다듬어진 문장이 아직 없으면 원문이 그대로 뜬다.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Bars, Donut, OverviewChart, RankBars, RetentionCurve } from './charts';

// ── 서버가 주는 모양 ───────────────────────────────────────────────────────

type Row = Record<string, unknown>;

interface Overview {
  generatedAt: string;
  since: string;
  days: number;
  realtime: Realtime;
  summary: Row;
  byDay: Row[];
  byScreen: Row[];
  landingScreens: Row[];
  exitScreens: Row[];
  screenFlow: Row[];
  byEvent: Row[];
  byClick: Row[];
  byHour: Row[];
  byWeekday: Row[];
  keyEvents: Row[];
  acquisition: { byChannel: Row[]; bySourceMedium: Row[]; byCampaign: Row[]; byReferrer: Row[] };
  tech: { byPlatform: Row[]; byDevice: Row[]; byBrowser: Row[]; byOs: Row[];
          byScreenSize: Row[]; byViewport: Row[] };
  demographics: { byGender: Row[]; byAge: Row[]; byCountry: Row[]; byLanguage: Row[];
                  byTimeZone: Row[] };
  retention: { byOffset: Row[]; cohorts: Row[] };
  sessionDuration: Row[];
  byUser: Row[];
}

interface Realtime {
  windowMinutes: number; users: number; sessions: number; events: number; byScreen: Row[];
}

interface GlossaryItem { title: string; text: string; source: 'AI' | 'BASE' }
interface Glossary { screens: Record<string, GlossaryItem>; terms: Record<string, GlossaryItem> }

// ── 값 다루기 ──────────────────────────────────────────────────────────────

const n = (v: unknown): number => (typeof v === 'number' ? v : 0);
const num = (v: unknown): string => n(v).toLocaleString('ko-KR');

/** 분모가 0이면 서버가 `null`을 준다 — 0으로 그리면 "0%"라는 거짓말이 된다. */
const pct = (v: unknown): string => (typeof v === 'number' ? `${v}%` : '—');
const dec = (v: unknown): string => (typeof v === 'number' ? String(v) : '—');
const text = (v: unknown): string => (v === null || v === undefined || v === '' ? '—' : String(v));

/** 밀리초를 사람이 읽는 길이로. `null`은 "잰 적 없음"이라 대시로 남긴다. */
function dur(v: unknown): string {
  if (typeof v !== 'number') return '—';
  const s = Math.round(v / 1000);
  if (s < 60) return `${s}초`;
  const m = Math.floor(s / 60);
  if (m < 60) return `${m}분 ${s % 60}초`;
  return `${Math.floor(m / 60)}시간 ${m % 60}분`;
}

const when = (v: unknown) => String(v ?? '').replace('T', ' ').slice(0, 16);

// ── 조각 ───────────────────────────────────────────────────────────────────

function Scorecard({ label, value, unit, sub, live, onExplain }: {
  label: string; value: string; unit?: string; sub?: string; live?: boolean;
  onExplain?: () => void;
}) {
  return (
    <div className={live ? 'scorecard live' : 'scorecard'}>
      <dt>
        {label}
        {onExplain && (
          <button type="button" className="why" title={`${label} 설명 보기`}
                  onClick={onExplain}>?</button>
        )}
      </dt>
      <dd>{value}{unit && <span className="unit">{unit}</span>}
        {sub && <span className="sub">{sub}</span>}
      </dd>
    </div>
  );
}

function Panel({ title, children, wide, empty, onExplain }: {
  title: string; children?: React.ReactNode; wide?: boolean; empty?: boolean;
  onExplain?: () => void;
}) {
  return (
    <section className={wide ? 'panel wide' : 'panel'}>
      <h3>
        {title}
        {onExplain && (
          <button type="button" className="why" title={`${title} 설명 보기`}
                  onClick={onExplain}>?</button>
        )}
      </h3>
      {empty ? <p className="empty">아직 기록이 없습니다.</p> : children}
    </section>
  );
}

/** 설명 창 — 화면 가운데에 띄운다. 한 번에 하나만 열린다. */
function Explain({ item, onClose }: { item: GlossaryItem | null; onClose: () => void }) {
  useEffect(() => {
    if (!item) return;
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [item, onClose]);

  if (!item) return null;
  return (
    <div className="explain-back" onClick={onClose} role="presentation">
      <div className="explain" role="dialog" aria-modal="true" aria-label={item.title}
           onClick={(e) => e.stopPropagation()}>
        <h4>{item.title}</h4>
        <p>{item.text}</p>
        <button type="button" className="primary" onClick={onClose}>알겠어요</button>
      </div>
    </div>
  );
}

// ── 화면 ───────────────────────────────────────────────────────────────────

const RANGES = [7, 30, 90, 365];

export function UsageStats({ call }: { call: <T>(path: string, init?: RequestInit) => Promise<T> }) {
  const [days, setDays] = useState(30);
  const [data, setData] = useState<Overview | null>(null);
  const [glossary, setGlossary] = useState<Glossary | null>(null);
  const [live, setLive] = useState<Realtime | null>(null);
  const [open, setOpen] = useState<GlossaryItem | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);
  const [trail, setTrail] = useState<{ userId: number; rows: Row[] } | null>(null);

  const load = useCallback(async () => {
    setLoading(true); setError(null);
    try {
      const overview = await call<Overview>(`/usage/overview?days=${days}`);
      setData(overview);
      setLive(overview.realtime);
    } catch (e) {
      setError(e instanceof Error ? e.message : '통계를 불러오지 못했어요.');
    } finally { setLoading(false); }
  }, [call, days]);

  useEffect(() => { void load(); }, [load]);

  // 용어 사전은 기간과 무관하다 — 한 번만 받는다. 뒤에서 다듬어지므로 화면을 막지 않는다.
  useEffect(() => {
    void call<Glossary>('/usage/glossary').then(setGlossary).catch(() => undefined);
  }, [call]);

  // 실시간만 30초마다 다시 묻는다. 전체 집계를 그 주기로 돌리는 것은 낭비다.
  useEffect(() => {
    const id = setInterval(() => {
      void call<Realtime>('/usage/realtime').then(setLive).catch(() => undefined);
    }, 30_000);
    return () => clearInterval(id);
  }, [call]);

  /** 용어를 펼친다. 사전에 없는 열쇠면 그 사실을 정직히 말한다. */
  const explain = useCallback((kind: 'screens' | 'terms', key: string) => {
    const found = glossary?.[kind]?.[key];
    setOpen(found ?? {
      title: key,
      text: glossary
        ? '이 항목의 설명은 아직 준비되지 않았습니다.'
        : '설명을 불러오는 중입니다. 잠시 뒤 다시 눌러 주세요.',
      source: 'BASE',
    });
  }, [glossary]);

  const term = useCallback((key: string) => explain('terms', key), [explain]);
  const screen = useCallback((key: string) => explain('screens', key), [explain]);

  /** 화면 id 를 사람이 읽는 이름으로. 사전이 아직 없으면 id 를 그대로 보여 준다. */
  const screenName = useCallback((id: unknown): string => {
    const key = String(id ?? '');
    return glossary?.screens?.[key]?.title ?? key;
  }, [glossary]);

  const s = data?.summary ?? {};

  const dayPoints = useMemo(() => (data?.byDay ?? []).map((r) => ({
    label: String(r.date), bars: n(r.sessions), line: n(r.users),
  })), [data]);

  async function openTrail(userId: number) {
    try {
      setTrail({ userId, rows: await call<Row[]>(`/usage/trail/${userId}`) });
    } catch (e) { setError(e instanceof Error ? e.message : '발자취를 불러오지 못했어요.'); }
  }

  const rank = (rows: Row[], key: string, value: string, opts?: {
    aside?: string; note?: (r: Row) => string | undefined; kind?: 'screens' | 'terms';
    label?: (r: Row) => string;
  }) => rows.map((r) => ({
    key: String(r[key]),
    label: opts?.label ? opts.label(r) : text(r[key]),
    note: opts?.note?.(r),
    value: n(r[value]),
    aside: opts?.aside === undefined ? undefined : n(r[opts.aside]),
  }));

  return (
    <>
      <Explain item={open} onClose={() => setOpen(null)} />

      <div className="range">
        {RANGES.map((d) => (
          <button key={d} type="button" aria-pressed={days === d} onClick={() => setDays(d)}>
            최근 {d === 365 ? '1년' : `${d}일`}
          </button>
        ))}
        <span className="spacer" />
        <button type="button" onClick={() => void load()} disabled={loading}>
          {loading ? '불러오는 중…' : '새로고침'}
        </button>
      </div>

      {error && <p className="notice error" role="alert">{error}</p>}
      {!data && !error && <p className="muted">불러오는 중…</p>}

      {data && (
        <>
          {/* ── 큰 그래프 — 전체 흐름을 한눈에 ─────────────────────────────── */}
          <section className="hero">
            <div className="hero-head">
              <h2>최근 {data.days}일</h2>
              <dl className="hero-figures">
                <div><dt>활성 사용자</dt><dd>{num(s.activeUsers)}<i>명</i></dd></div>
                <div><dt>세션</dt><dd>{num(s.sessions)}<i>회</i></dd></div>
                <div><dt>참여율</dt><dd>{pct(s.engagementRate)}</dd></div>
                <div><dt>세션당 참여</dt><dd>{dur(s.avgEngagedMsPerSession)}</dd></div>
              </dl>
            </div>
            {dayPoints.length > 0
              ? <OverviewChart points={dayPoints} barName="세션" lineName="활성 사용자" />
              : <p className="empty">아직 기록이 없습니다.</p>}
          </section>

          {/* ── 실시간 ─────────────────────────────────────────────────────── */}
          <p className="group-title">
            실시간 · 최근 {live?.windowMinutes ?? 30}분
            <button type="button" className="why" onClick={() => term('realtime')}>?</button>
          </p>
          <dl className="scorecards">
            <Scorecard live label="지금 사용자" value={num(live?.users)} unit="명" />
            <Scorecard live label="세션" value={num(live?.sessions)} unit="회" />
            <Scorecard live label="이벤트" value={num(live?.events)} unit="건" />
            <Scorecard live label="보고 있는 화면"
              value={live?.byScreen?.length ? screenName(live.byScreen[0].screen) : '—'}
              sub={live?.byScreen?.length ? `${num(live.byScreen[0].users)}명` : '아무도 없음'} />
          </dl>

          {/* ── 요약 ───────────────────────────────────────────────────────── */}
          <p className="group-title">요약</p>
          <dl className="scorecards">
            <Scorecard label="활성 사용자" value={num(s.activeUsers)} unit="명"
              sub={`신규 ${num(s.newUsers)} · 재방문 ${num(s.returningUsers)}`}
              onExplain={() => term('activeUsers')} />
            <Scorecard label="세션" value={num(s.sessions)} unit="회"
              sub={`사용자당 ${dec(s.sessionsPerUser)}회`} onExplain={() => term('sessions')} />
            <Scorecard label="참여 세션" value={num(s.engagedSessions)} unit="회"
              onExplain={() => term('engagedSessions')} />
            <Scorecard label="참여율" value={pct(s.engagementRate)}
              onExplain={() => term('engagementRate')} />
            <Scorecard label="이탈률" value={pct(s.bounceRate)}
              onExplain={() => term('bounceRate')} />
            <Scorecard label="세션당 참여 시간" value={dur(s.avgEngagedMsPerSession)}
              onExplain={() => term('avgEngagedMsPerSession')} />
            <Scorecard label="사용자당 참여 시간" value={dur(s.avgEngagedMsPerUser)}
              onExplain={() => term('avgEngagedMsPerUser')} />
            <Scorecard label="세션 길이" value={dur(s.avgSessionDurationMs)}
              onExplain={() => term('avgSessionDurationMs')} />
            <Scorecard label="화면 조회" value={num(s.screenViews)} unit="회"
              sub={`세션당 ${dec(s.viewsPerSession)}`} onExplain={() => term('screenViews')} />
            <Scorecard label="이벤트" value={num(s.eventCount)} unit="건"
              sub={`세션당 ${dec(s.eventsPerSession)}`} onExplain={() => term('eventCount')} />
          </dl>

          {/* ── 전환 ───────────────────────────────────────────────────────── */}
          <p className="group-title">전환</p>
          <div className="panels">
            <Panel title="핵심 이벤트" wide empty={data.keyEvents.length === 0}
              onExplain={() => term('keyEvent')}>
              <div className="scroll-x">
                <table className="grid">
                  <caption className="sr-only">전환별 발생·사용자·세션·세션 전환율</caption>
                  <thead><tr>
                    <th scope="col">이름</th><th scope="col">발생</th><th scope="col">사용자</th>
                    <th scope="col">세션</th><th scope="col">전환율</th><th scope="col">화면</th>
                  </tr></thead>
                  <tbody>
                    {data.keyEvents.map((r, i) => (
                      <tr key={i}>
                        <td>{text(r.label)}</td><td>{num(r.count)}</td><td>{num(r.users)}</td>
                        <td>{num(r.sessions)}</td><td>{pct(r.sessionRate)}</td>
                        <td>
                          {screenName(r.screen)}
                          <button type="button" className="why"
                                  onClick={() => screen(String(r.screen))}>?</button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Panel>
          </div>

          {/* ── 획득 ───────────────────────────────────────────────────────── */}
          <p className="group-title">획득 · 어디서 왔나</p>
          <div className="panels">
            <Panel title="유입 채널" empty={data.acquisition.byChannel.length === 0}
              onExplain={() => term('channel')}>
              <Donut unit="회" total={data.acquisition.byChannel.reduce((a, r) => a + n(r.sessions), 0)}
                slices={data.acquisition.byChannel.map((r) => ({
                  label: glossary?.terms?.[String(r.channel)]?.title ?? String(r.channel),
                  value: n(r.sessions),
                }))} />
              <RankBars unit="회" aside="명" onExplain={(k) => term(k)}
                rows={rank(data.acquisition.byChannel, 'channel', 'sessions', {
                  aside: 'users',
                  label: (r) => glossary?.terms?.[String(r.channel)]?.title ?? String(r.channel),
                })} />
            </Panel>
            <Panel title="출처 / 매체" empty={data.acquisition.bySourceMedium.length === 0}
              onExplain={() => term('sourceMedium')}>
              <RankBars unit="회" aside="명"
                rows={rank(data.acquisition.bySourceMedium, 'source', 'sessions', {
                  aside: 'users', note: (r) => `/ ${text(r.medium)}`,
                })} />
            </Panel>
            <Panel title="캠페인" empty={data.acquisition.byCampaign.length === 0}
              onExplain={() => term('campaign')}>
              <RankBars unit="회" aside="명"
                rows={rank(data.acquisition.byCampaign, 'campaign', 'sessions', { aside: 'users' })} />
            </Panel>
            <Panel title="참조 사이트" empty={data.acquisition.byReferrer.length === 0}
              onExplain={() => term('referrer')}>
              <RankBars unit="회" aside="명"
                rows={rank(data.acquisition.byReferrer, 'referrer', 'sessions', { aside: 'users' })} />
            </Panel>
          </div>

          {/* ── 참여도 ─────────────────────────────────────────────────────── */}
          <p className="group-title">참여도 · 무엇을 보고 무엇을 눌렀나</p>
          <div className="panels">
            <Panel title="화면별 조회" wide empty={data.byScreen.length === 0}>
              <RankBars unit="회" aside="명" onExplain={(k) => screen(k)}
                rows={rank(data.byScreen, 'screen', 'views', {
                  aside: 'users', label: (r) => screenName(r.screen),
                  note: (r) => `조회당 ${dur(r.avgEngagedMsPerView)}`,
                })} />
            </Panel>

            <Panel title="진입 화면" empty={data.landingScreens.length === 0}
              onExplain={() => term('landing')}>
              <RankBars unit="회" onExplain={(k) => screen(k)}
                rows={rank(data.landingScreens, 'screen', 'sessions',
                  { label: (r) => screenName(r.screen) })} />
            </Panel>
            <Panel title="이탈 화면" empty={data.exitScreens.length === 0}
              onExplain={() => term('exit')}>
              <RankBars unit="회" onExplain={(k) => screen(k)}
                rows={rank(data.exitScreens, 'screen', 'sessions',
                  { label: (r) => screenName(r.screen) })} />
            </Panel>

            <Panel title="화면 이동" wide empty={data.screenFlow.length === 0}
              onExplain={() => term('flow')}>
              <ul className="flow">
                {data.screenFlow.map((r, i) => (
                  <li key={i}>
                    <span className="from">
                      {screenName(r.from)}
                      <button type="button" className="why"
                              onClick={() => screen(String(r.from))}>?</button>
                    </span>
                    <span className="arrow" aria-label="에서">→</span>
                    <span className="to">
                      {screenName(r.to)}
                      <button type="button" className="why"
                              onClick={() => screen(String(r.to))}>?</button>
                    </span>
                    <span className="count">{num(r.moves)}회</span>
                  </li>
                ))}
              </ul>
            </Panel>

            <Panel title="이벤트 종류" empty={data.byEvent.length === 0}>
              <Donut unit="건" total={data.byEvent.reduce((a, r) => a + n(r.count), 0)}
                slices={data.byEvent.map((r) => ({ label: String(r.event), value: n(r.count) }))} />
            </Panel>
            <Panel title="많이 눌린 것" empty={data.byClick.length === 0}
              onExplain={() => term('element')}>
              <RankBars unit="회" aside="명"
                rows={rank(data.byClick, 'element', 'clicks', {
                  aside: 'users', note: (r) => `@${screenName(r.screen)}`,
                })} />
            </Panel>
          </div>

          {/* ── 시간 ───────────────────────────────────────────────────────── */}
          <p className="group-title">시간 · 언제 쓰나</p>
          <div className="panels">
            <Panel title="시간대" wide empty={data.byHour.length === 0}
              onExplain={() => term('hour')}>
              <Bars unit="건" items={hoursOfDay(data.byHour)}
                highlight={(x) => x.value === Math.max(...hoursOfDay(data.byHour).map((h) => h.value))} />
            </Panel>
            <Panel title="요일" empty={data.byWeekday.length === 0}
              onExplain={() => term('weekday')}>
              <Bars unit="건" height={160}
                items={data.byWeekday.map((r) => ({ label: String(r.weekday), value: n(r.events) }))} />
            </Panel>
            <Panel title="세션 길이 분포" empty={data.sessionDuration.every((r) => n(r.sessions) === 0)}
              onExplain={() => term('sessionDuration')}>
              <Bars unit="회" height={160}
                items={data.sessionDuration.map((r) => ({
                  label: String(r.bucket).replace(' 미만', '↓').replace(' 이상', '↑'),
                  value: n(r.sessions),
                }))} />
            </Panel>
          </div>

          {/* ── 기기 ───────────────────────────────────────────────────────── */}
          <p className="group-title">기기</p>
          <div className="panels">
            <Panel title="기기 종류" empty={data.tech.byDevice.length === 0}
              onExplain={() => term('device')}>
              <Donut unit="회" total={data.tech.byDevice.reduce((a, r) => a + n(r.sessions), 0)}
                slices={data.tech.byDevice.map((r) => ({ label: String(r.device), value: n(r.sessions) }))} />
            </Panel>
            <Panel title="플랫폼" empty={data.tech.byPlatform.length === 0}
              onExplain={() => term('platform')}>
              <Donut unit="회" total={data.tech.byPlatform.reduce((a, r) => a + n(r.sessions), 0)}
                slices={data.tech.byPlatform.map((r) => ({ label: String(r.platform), value: n(r.sessions) }))} />
            </Panel>
            <Panel title="브라우저" empty={data.tech.byBrowser.length === 0}
              onExplain={() => term('browser')}>
              <RankBars unit="회" aside="명"
                rows={rank(data.tech.byBrowser, 'browser', 'sessions', {
                  aside: 'users', note: (r) => (r.version ? String(r.version) : undefined),
                })} />
            </Panel>
            <Panel title="운영체제" empty={data.tech.byOs.length === 0}
              onExplain={() => term('os')}>
              <RankBars unit="회" aside="명"
                rows={rank(data.tech.byOs, 'os', 'sessions', {
                  aside: 'users', note: (r) => (r.version ? String(r.version) : undefined),
                })} />
            </Panel>
            <Panel title="기기 화면 크기" empty={data.tech.byScreenSize.length === 0}
              onExplain={() => term('screenSize')}>
              <RankBars unit="회" aside="명"
                rows={rank(data.tech.byScreenSize, 'screenSize', 'sessions', { aside: 'users' })} />
            </Panel>
            <Panel title="창 크기" empty={data.tech.byViewport.length === 0}
              onExplain={() => term('viewport')}>
              <RankBars unit="건" aside="명"
                rows={rank(data.tech.byViewport, 'viewport', 'events', { aside: 'users' })} />
            </Panel>
          </div>

          {/* ── 인구통계 ───────────────────────────────────────────────────── */}
          <p className="group-title">인구통계</p>
          <div className="panels">
            <Panel title="성별" empty={data.demographics.byGender.length === 0}
              onExplain={() => term('gender')}>
              <Donut unit="명" total={data.demographics.byGender.reduce((a, r) => a + n(r.users), 0)}
                slices={data.demographics.byGender.map((r) => ({
                  label: genderName(r.gender), value: n(r.users),
                }))} />
            </Panel>
            <Panel title="연령대" empty={data.demographics.byAge.length === 0}
              onExplain={() => term('age')}>
              <Bars unit="명" height={160}
                items={data.demographics.byAge.map((r) => ({ label: String(r.age), value: n(r.users) }))} />
            </Panel>
            <Panel title="국가" empty={data.demographics.byCountry.length === 0}
              onExplain={() => term('country')}>
              <RankBars unit="회" aside="명"
                rows={rank(data.demographics.byCountry, 'country', 'sessions', { aside: 'users' })} />
            </Panel>
            <Panel title="언어" empty={data.demographics.byLanguage.length === 0}
              onExplain={() => term('language')}>
              <RankBars unit="회" aside="명"
                rows={rank(data.demographics.byLanguage, 'language', 'sessions', { aside: 'users' })} />
            </Panel>
            <Panel title="시간대" empty={data.demographics.byTimeZone.length === 0}
              onExplain={() => term('timeZone')}>
              <RankBars unit="회" aside="명"
                rows={rank(data.demographics.byTimeZone, 'timeZone', 'sessions', { aside: 'users' })} />
            </Panel>
          </div>

          {/* ── 리텐션 ─────────────────────────────────────────────────────── */}
          <p className="group-title">리텐션 · 다시 오나</p>
          <div className="panels">
            <Panel title="첫 방문 후 재방문율" wide empty={data.retention.byOffset.length === 0}
              onExplain={() => term('retention')}>
              <RetentionCurve points={data.retention.byOffset.map((r) => ({
                day: n(r.day),
                rate: typeof r.rate === 'number' ? r.rate : null,
                cohort: n(r.cohort),
              }))} />
              <div className="scroll-x">
                <table className="grid">
                  <caption className="sr-only">경과일별 재방문율</caption>
                  <thead><tr>
                    <th scope="col">경과</th><th scope="col">대상</th>
                    <th scope="col">다시 옴</th><th scope="col">비율</th>
                  </tr></thead>
                  <tbody>
                    {data.retention.byOffset.map((r) => (
                      <tr key={String(r.day)}>
                        <td>D{text(r.day)}</td><td>{num(r.cohort)}명</td>
                        <td>{num(r.returned)}명</td><td>{pct(r.rate)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Panel>
            <Panel title="코호트" wide empty={data.retention.cohorts.length === 0}
              onExplain={() => term('cohort')}>
              <Bars unit="명" height={160}
                items={data.retention.cohorts.map((r) => ({
                  label: String(r.firstSeen).slice(5), value: n(r.users),
                }))} />
            </Panel>
          </div>

          {/* ── 사람별 ─────────────────────────────────────────────────────── */}
          <p className="group-title">사람별</p>
          <div className="panels">
            <Panel title="사용자" wide empty={data.byUser.length === 0}>
              <div className="scroll-x">
                <table className="grid">
                  <caption className="sr-only">사용자별 세션·이벤트·참여시간</caption>
                  <thead><tr>
                    <th scope="col">사용자</th><th scope="col">첫 방문</th><th scope="col">세션</th>
                    <th scope="col">이벤트</th><th scope="col">참여 시간</th>
                    <th scope="col">마지막</th>
                    <th scope="col"><span className="sr-only">원본 보기</span></th>
                  </tr></thead>
                  <tbody>
                    {data.byUser.map((r) => (
                      <tr key={String(r.userId)}>
                        <td>#{text(r.userId)}</td><td>{text(r.firstEverSeen)}</td>
                        <td>{num(r.sessions)}</td><td>{num(r.events)}</td>
                        <td>{dur(r.engagedMs)}</td>
                        <td className="muted small">{when(r.lastSeen)}</td>
                        <td>
                          <button type="button" className="link"
                            onClick={() => void openTrail(n(r.userId))}>발자취</button>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </Panel>

            {trail && (
              <Panel title={`#${trail.userId} 최근 발자취`} wide empty={trail.rows.length === 0}>
                <div className="scroll-x">
                  <table className="grid">
                    <caption className="sr-only">한 사용자의 최근 행태 기록</caption>
                    <thead><tr>
                      <th scope="col">시각</th><th scope="col">종류</th><th scope="col">화면</th>
                      <th scope="col">눌린 것</th><th scope="col">참여</th>
                    </tr></thead>
                    <tbody>
                      {trail.rows.map((r, i) => (
                        <tr key={i}>
                          <td>{String(r.at ?? '').replace('T', ' ').slice(0, 19)}</td>
                          <td>{text(r.kind)}</td><td>{screenName(r.screen)}</td>
                          <td>{text(r.element)}</td><td>{dur(r.engagedMs)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
                <div className="actions">
                  <button type="button" onClick={() => setTrail(null)}>닫기</button>
                </div>
              </Panel>
            )}
          </div>
        </>
      )}
    </>
  );
}

/** 성별 코드를 사람이 읽는 말로. */
function genderName(v: unknown): string {
  const raw = String(v ?? '');
  if (raw === 'MALE') return '남성';
  if (raw === 'FEMALE') return '여성';
  return raw;
}

/**
 * 0시부터 23시까지 **빈 시간도 채워** 돌려준다.
 *
 * 서버는 기록이 있는 시간만 준다. 그대로 그리면 새벽 3시와 4시가 붙어 버려 "아무도 안 쓰는
 * 시간"이 그래프에서 사라진다 — 그건 값진 정보인데 없어진다.
 */
function hoursOfDay(rows: Row[]): { label: string; value: number }[] {
  const byHour = new Map<number, number>();
  rows.forEach((r) => byHour.set(n(r.hour), n(r.events)));
  return Array.from({ length: 24 }, (_, h) => ({
    label: h % 3 === 0 ? String(h) : '',
    value: byHour.get(h) ?? 0,
  }));
}
