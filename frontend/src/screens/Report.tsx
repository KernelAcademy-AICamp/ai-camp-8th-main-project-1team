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
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api } from '../lib/api';
import { won, wonNum } from '../lib/format';

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
  // 챌린지가 없으면 404다 — 리포트 나머지는 멀쩡히 보여야 하므로 조용히 비운다.
  const weekly = useAsync(
    () => api.guardian.weeklyReport(userId, weeksAgo).catch(() => null),
    [userId, weeksAgo],
  );

  const w = weekly.data;
  const ch = home?.challenge;
  const isCur = weeksAgo === 0;
  /** 판정이 하나도 없는 주 — 개편안의 `#rpEmpty`. */
  const empty = !w || w.days.every((d) => !d.judged);

  /** 차트 위 한 줄 요약. 모드마다 무엇을 견주는지가 다르다. */
  const lead = mode === 0
    ? (() => {
      const shown = w?.days.filter((d) => d.judged) ?? [];
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
            <div className="wk-txt">
              <b>{w?.weekLabel ?? '이번 주'}</b>
              <span>{w ? fmtRange(w.weekStart, w.weekEnd) : ''}</span>
            </div>
            <button type="button" aria-label="다음주" disabled={isCur}
              onClick={() => setWeeksAgo((x) => Math.max(0, x - 1))}>›</button>
          </div>
        </div>
        <div className="rp-line" />

        {empty ? (
          <div className="rp-sec">
            <div className="rp-emp">
              <b>이 주에는 분석할 소비가 없어요</b>
              <p>기록이 있는 주로 이동하면 리포트를 보여드릴게요</p>
            </div>
          </div>
        ) : (
          <>
            {/* ── ① 지킨 금액 + 차트 ──────────────────────────────── */}
            <div className="rp-sec">
              <div className="t-cap">{isCur ? '이번 주' : '이 주'} 동안 지킨 금액</div>
              <div className="rp-keep">{wonNum(ch?.securedSaving ?? 0)}<em>원</em></div>
              <WeekChart mode={mode} onMode={setMode} days={w.days} trend={w.trend} lead={lead} />
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
            {(w.coaching.good || w.coaching.watch) && (
              <>
                <div className="rp-sec">
                  <div className="t-sec">지킴이가 본 이번 주</div>
                  <div className="ins">
                    {w.coaching.good && (
                      <div className="ig"><span className="itag good">잘한 점</span><p>{w.coaching.good}</p></div>
                    )}
                    {w.coaching.watch && (
                      <div className="ig"><span className="itag warn">살펴볼 점</span><p>{w.coaching.watch}</p></div>
                    )}
                  </div>
                  {w.missions.length > 0 && (
                    <button type="button" className="bn msn" onClick={() => go('myroom')}>
                      <div className="bnt">
                        <b>이번 주 소비를 보고<br />다음 주 미션을 준비했어요</b>
                        <span>마이룸에서 확인해 보세요<i className="chev" aria-hidden="true">›</i></span>
                      </div>
                      <CoinArt />
                    </button>
                  )}
                </div>
                <div className="rp-band" />
              </>
            )}

            {/* ── ④ 지난 챌린지 달성률 ────────────────────────────── */}
            {w.pastChallenges.length > 0 && (
              <>
                <div className="rp-sec">
                  <div className="t-sec">지난 챌린지 달성률</div>
                  {w.pastChallenges.map((p) => (
                    <div className="ch" key={p.challengeId}>
                      <div className="chh"><b>{p.label}</b><span>{Math.round(p.rate * 100)}%</span></div>
                      <div className="cbar"><i style={{ width: `${Math.round(p.rate * 100)}%` }} /></div>
                      <div className="chs">{p.period} / {p.keptDays}일 지킴</div>
                    </div>
                  ))}
                </div>
                <div className="rp-band" />
              </>
            )}

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
    </Screen>
  );
}
