/**
 * 리포트 탭 (프로토타입_0806 `s-report`) — "이 주를 어떻게 지켰는가"에 답한다.
 *
 * <b>개편안의 다섯 절을 그대로 얹었다.</b> 주차 이동 → 지킨 금액과 차트 → 많이 쓴 곳 →
 * 지킴이가 본 이번 주 → 지난 챌린지 달성률 → 카드 추천.
 *
 * <b>개편안에 없는 절은 뺐다.</b> 소비 건강 점수·절약 리포트·소비 성격 분석·주간 미션 정산과
 * 자세히 보기 메뉴는 디자이너가 다시 그리지 않았다. 지우지는 않고 **마이 > 임시 보관함**으로
 * 옮겼다(`m-parked`) — 이 화면은 개편안이 그린 것만 담고, 갈 곳은 나중에 정한다.
 *
 * <b>계산은 서버가 한다.</b> 방어율·요일별 금액·달성률은 `/api/guardian/report/weekly` 가
 * 완성해 내려준다(마스터 §4 원칙 2). 여기서 하는 것은 그리기와 문장 조립뿐이다.
 */
import { useState } from 'react';
import { Scroll, Screen } from '../components/ui';
import { WeekChart } from '../components/WeekChart';
import { WeekPicker, weekOfMonth, mondayOf, type WeekSel } from '../components/WeekPicker';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api } from '../lib/api';
import { won, wonNum, shortDate } from '../lib/format';

/** "7.20 ~ 7.26" */
const fmtRange = (a: string, b: string) =>
  `${Number(a.slice(5, 7))}.${Number(a.slice(8, 10))} ~ ${Number(b.slice(5, 7))}.${Number(b.slice(8, 10))}`;

/** 미션 배너의 동전 그림 — 개편안 원본 SVG. */
const CoinArt = () => (
  <svg width="56" height="56" viewBox="0 0 56 56" aria-hidden="true">
    <circle cx="34" cy="24" r="15" fill="#F5B73C" />
    <circle cx="26" cy="30" r="17" fill="#FFCB3D" />
    <circle cx="26" cy="30" r="12.5" fill="none" stroke="#F0A93B" strokeWidth="2" />
    <text x="26" y="36" textAnchor="middle" fontSize="16" fontWeight="700" fill="#FFF">P</text>
    <path d="M46 42l1.6 3.4L51 47l-3.4 1.6L46 52l-1.6-3.4L41 47l3.4-1.6z" fill="#FFB03A" />
  </svg>
);

/** 카드 추천 배너의 카드 그림 — 개편안 원본 SVG. */
const CardArt = () => (
  <svg width="56" height="48" viewBox="0 0 56 48" aria-hidden="true">
    <rect x="18" y="6" width="30" height="40" rx="5" fill="#00B14F" />
    <rect x="14" y="10" width="30" height="40" rx="5" fill="#33C475" />
    <rect x="22" y="18" width="10" height="10" rx="2.5" fill="#fff" />
    <path d="M7 8l1.4 3L11.4 12.4l-3 1.4L7 16.8l-1.4-3-3-1.4 3-1.4z" fill="#FFC53D" />
    <path d="M13 1l.9 1.9 1.9.9-1.9.9L13 6.6l-.9-1.9-1.9-.9 1.9-.9z" fill="#FFC53D" />
  </svg>
);

export function Report() {
  const { go, userId } = useSession();
  const { home } = useGuardian();
  const [weeksAgo, setWeeksAgo] = useState(0);
  const [mode, setMode] = useState<0 | 1>(0);
  /** 주차 고르기 시트 — 열려 있는가, 그 안에서 굴리고 있는 값은 무엇인가. */
  const [pickOpen, setPickOpen] = useState(false);
  const [pick, setPick] = useState<WeekSel | null>(null);
  /** '오늘'은 서버가 정한다 — 데모 시계를 켜면 실제 오늘과 다르다(원칙 3). */
  const today = home?.asOf ? new Date(`${home.asOf.slice(0, 10)}T00:00:00`) : new Date();
  // 챌린지가 없으면 404다 — 리포트 나머지는 멀쩡히 보여야 하므로 조용히 비운다.
  const weekly = useAsync(
    () => api.guardian.weeklyReport(userId, weeksAgo).catch(() => null),
    [userId, weeksAgo],
  );

  const w = weekly.data;
  const ch = home?.challenge;
  const isCur = weeksAgo === 0;
  /** 판정이 하나도 없는 주 — 개편안의 `#rpEmpty`. */

  /** 차트 위 한 줄 요약. 모드마다 무엇을 견주는지가 다르다. */
  const lead = mode === 0
    ? (() => {
      // 오늘은 `judged=false` 라 빠진다 — 쓴 돈이 있으면 오늘도 평균에 넣는다.
      // 안 그러면 오늘 하루만 쓴 주가 "하루 평균 0원"으로 나온다.
      const shown = w?.days.filter((d) => d.judged || d.amount > 0) ?? [];
      const avg = shown.length ? Math.round(shown.reduce((a, d) => a + d.amount, 0) / shown.length) : 0;
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

  return (
    <Screen title="리포트" hasTabBar>
      <Scroll>
        {/* ── 주차 이동 ─────────────────────────────────────────────── */}
        <div className="rp-sec" style={{ paddingTop: 20 }}>
          <div className="wk-nav">
            <button type="button" aria-label="지난주" onClick={() => setWeeksAgo((x) => x + 1)}>‹</button>
            {/* 가운데를 누르면 휠로 아무 주나 고른다 — ‹ › 만으로는 석 달 전에 가려고
                열두 번을 눌러야 해서, 지난 기록을 훑는 화면에서 사실상 못 가는 것과 같다. */}
            <button type="button" className="wk-txt" aria-label="다른 주 고르기"
              onClick={() => { setPick(selOf(today, weeksAgo)); setPickOpen(true); }}>
              <b>{w?.weekLabel ?? '이번 주'}</b>
              <span>{w ? fmtRange(w.weekStart, w.weekEnd) : ''}</span>
            </button>
            <button type="button" aria-label="다음주" disabled={isCur}
              onClick={() => setWeeksAgo((x) => Math.max(0, x - 1))}>›</button>
          </div>
        </div>
        <div className="rp-line" />

        {/* **소비가 없다고 화면을 통째로 감추지 않는다.**
            예전에는 그 주에 판정된 소비가 하나도 없으면 다섯 절을 다 지웠는데, 그러면
            "이번 주는 아직 조용하다"와 "리포트 기능이 고장났다"가 화면에서 구별되지 않았다.
            빈 차트라도 서 있어야 무엇을 보는 화면인지 알고, 다음 주에 채워질 자리도 보인다.
            절마다 자기 자리에서 "아직 없어요"를 말한다.

            챌린지가 아예 없을 때만 다르다 — 그때는 어느 주로 옮겨도 영영 안 나오므로,
            헤매게 두지 않고 시작하는 길을 준다. */}
        {!ch ? (
          <div className="rp-sec">
            <div className="rp-emp">
              <b>아직 보여드릴 리포트가 없어요</b>
              <p>챌린지를 시작하면 그 주부터 쌓여요</p>
              <button type="button" className="btn btn-primary"
                style={{ marginTop: 16 }} onClick={() => go('ob1')}>챌린지 시작하기</button>
            </div>
          </div>
        ) : (
          <>
            {/* ── ① 지킨 금액 + 차트 ──────────────────────────────── */}
            <div className="rp-sec">
              <div className="t-cap">{isCur ? '이번 주' : '이 주'} 동안 지킨 금액</div>
              <div className="rp-keep">{wonNum(ch?.securedSaving ?? 0)}<em>원</em></div>
              {/* **챌린지 시작 전 소비는 여기 안 들어온다.** 소비 내역에는 잔뜩 보이는데
                  리포트는 0이면 사용자는 화면이 고장난 줄 안다 — 그 사정을 그 자리에서 말한다.
                  시작 전까지 세면 시작하자마자 실패한 상태가 되므로, 안 세는 것이 맞다. */}
              {w && ch && w.days.some((d) => !d.judged) && w.weekStart < ch.startDate && (
                <p className="pv" style={{ margin: '0 0 12px' }}>
                  이 주는 <b>{shortDate(ch.startDate)}에 챌린지를 시작</b>해서, 그전 소비는 세지 않아요.
                  전체 결제는 <b>소비 내역</b>에서 볼 수 있어요.
                </p>
              )}
              <WeekChart mode={mode} onMode={setMode} days={w?.days ?? []}
                trend={w?.trend ?? []} lead={lead} />
            </div>
            <div className="rp-band" />

            {/* ── ② 많이 쓴 곳 ───────────────────────────────────── */}
            <div className="rp-sec">
              <div className="t-sec row">많이 쓴 곳
                <button type="button" className="sec-more" onClick={() => go('transactions')}>
                  전체보기<span className="chev" aria-hidden="true">›</span>
                </button>
              </div>
              {ch?.categorySpend?.length
                ? ch.categorySpend.filter((c) => c.spent > 0).slice(0, 5).map((c) => (
                  <div className="crow" key={c.code}>
                    <span className="cn">{c.label}</span>
                    <span className="cv">{won(c.spent)}</span>
                    <div className="bar"><i style={{ width: `${Math.round(c.share * 100)}%`, background: 'var(--blue)' }} /></div>
                  </div>
                ))
                : <p className="empty">이 주에 집계된 소비가 없어요.</p>}
            </div>
            <div className="rp-band" />

            {/* ── ③ 지킴이가 본 이번 주 + 미션 다리 ────────────────── */}
            <>
              <div className="rp-sec">
                <div className="t-sec">지킴이가 본 이번 주</div>
                <div className="ins">
                  {w?.coaching.good && (
                    <div className="ig"><span className="itag good">잘한 점</span><p>{w.coaching.good}</p></div>
                  )}
                  {w?.coaching.watch && (
                    <div className="ig"><span className="itag warn">살펴볼 점</span><p>{w.coaching.watch}</p></div>
                  )}
                  {/* 견줄 지난주가 없으면 두 문장 모두 비어 온다 — 그때도 절은 남긴다. */}
                  {!w?.coaching.good && !w?.coaching.watch && (
                    <p className="empty" style={{ margin: 0 }}>
                      견줄 지난주가 아직 없어요. 한 주가 더 쌓이면 무엇이 달라졌는지 말해드릴게요.
                    </p>
                  )}
                </div>
                {/* **미션이 없을 때 더 필요한 문이다.** 예전에는 미션이 있을 때만 배너를 띄웠는데,
                    미션을 고르러 가는 문이 미션이 없으면 사라지는 셈이었다. 개편안도 두 경우의
                    문구를 각각 적어 두고 배너는 늘 보인다. */}
                <button type="button" className="bn msn" onClick={() => go('myroom')}>
                  <div className="bnt">
                    {(w?.missions.length ?? 0) > 0 ? (
                      <>
                        <b>이번 주 미션 {w!.missions.length}개<br />진행 중이에요</b>
                        <span>마이룸에서 확인해 보세요<i className="chev" aria-hidden="true">›</i></span>
                      </>
                    ) : (
                      <>
                        <b>이번 주 소비를 보고<br />다음 주 미션을 준비했어요</b>
                        <span>마이룸에서 골라 보세요<i className="chev" aria-hidden="true">›</i></span>
                      </>
                    )}
                  </div>
                  <CoinArt />
                </button>
              </div>
              <div className="rp-band" />
            </>

            {/* ── ④ 지난 챌린지 달성률 ────────────────────────────── */}
            <>
              <div className="rp-sec">
                <div className="t-sec">지난 챌린지 달성률</div>
                {(w?.pastChallenges.length ?? 0) > 0 ? w!.pastChallenges.map((p) => (
                  <div className="ch" key={p.challengeId}>
                    <div className="chh"><b>{p.label}</b><span>{Math.round(p.rate * 100)}%</span></div>
                    <div className="cbar"><i style={{ width: `${Math.round(p.rate * 100)}%` }} /></div>
                    <div className="chs">{p.period} / {p.keptDays}일 지킴</div>
                  </div>
                )) : (
                  /* 진행 중인 회차는 여기 안 온다 — 확정되지 않은 성적을 최종처럼 보이면 안 된다.
                     그래서 첫 챌린지를 끝내기 전까지는 비어 있는 것이 정상이고, 그 사실을 적는다. */
                  <p className="empty" style={{ margin: 0 }}>
                    아직 끝난 챌린지가 없어요. 이번 회차가 끝나면 여기에 달성률이 남아요.
                  </p>
                )}
              </div>
              <div className="rp-band" />
            </>

            {/* ── ⑤ 카드 추천 ────────────────────────────────────── */}
            <div className="rp-sec">
              <button type="button" className="bn cardbn" onClick={() => go('r-compare')}>
                <b>내 소비에 딱 맞는<br />카드를 추천해드릴게요</b>
                <CardArt />
              </button>
            </div>
            <div className="rp-band" />
          </>
        )}

        <div className="spacer" />
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
