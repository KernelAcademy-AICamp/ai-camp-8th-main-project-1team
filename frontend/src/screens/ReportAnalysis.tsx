/**
 * 리포트 &gt; 내 소비 분석 (①의 ②③④⑤) — 이상소비지수 · 절약 후보 · 반복 결제 · 언제 쓰나.
 * 판단은 서버 엔진(결정론)이 하고, 'AI 요약'·'왜?' 문장만 온디맨드로 LLM에 물어본다(마스터 §4 원칙 1).
 */
import { useState } from 'react';
import { Icon } from '../components/Icons';
import { BarChart } from '../components/Charts';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty, SectionTitle } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, type Narrative } from '../lib/api';
import { won, iconOf, DOW_ORDER, DOW_KR, DAYPART_ORDER, FACTOR_ORDER } from '../lib/format';

export function ReportAnalysis() {
  const { back, userId } = useSession();
  const analysis = useAsync(() => api.analysis(userId), [userId]);
  const [profileNarr, setProfileNarr] = useState<Narrative | 'loading' | null>(null);
  const [cutNarr, setCutNarr] = useState<Record<string, Narrative | 'loading'>>({});
  const [chosen, setChosen] = useState<Record<string, boolean>>({});
  const [msg, setMsg] = useState<string | null>(null);

  async function loadProfileNarr() {
    if (profileNarr) return;
    setProfileNarr('loading');
    try { setProfileNarr(await api.profileNarrative(userId)); } catch { setProfileNarr(null); }
  }
  async function explainCut(cat2: string) {
    if (cutNarr[cat2]) return;
    setCutNarr((p) => ({ ...p, [cat2]: 'loading' }));
    try {
      const n = await api.explainCut(userId, cat2);
      setCutNarr((p) => ({ ...p, [cat2]: n }));
    } catch { setCutNarr((p) => { const x = { ...p }; delete x[cat2]; return x; }); }
  }
  async function choose(cat2: string) {
    try {
      await api.chooseCut(userId, cat2);
      setChosen((p) => ({ ...p, [cat2]: true }));
      setMsg(`'${cat2}' 절약을 추적하기 시작했어요`);
    } catch (e) { setMsg(e instanceof Error ? e.message : String(e)); }
  }

  const data = analysis.data;

  return (
    <Screen title="내 소비 분석" hasTabBar>
      <AppBar onBack={back} title="내 소비 분석" />
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>
        <ErrorBox error={analysis.error} onRetry={analysis.reload} />
        {analysis.loading && <Loading label="분석 결과를 불러오는 중" rows={6} />}

        {data && (() => {
          const { profile, recurring, pattern, cutCandidates } = data;
          const fixed = recurring.filter((r) => r.type === 'FIXED');
          const routine = recurring.filter((r) => r.type === 'ROUTINE');
          const maxFactor = Math.max(1, ...Object.values(profile.contributionPoints));
          // 글자로도, 막대로도 쓰이는 색이다. 큰 숫자(38px)라 3:1 이면 되지만 브랜드 그린은 2.49:1 이라
          // 미달한다 — 그래픽 색이 아니라 **글자용 토큰**을 쓴다.
          const indexColor = profile.abnormalityIndex >= 60 ? 'var(--red-t)'
            : profile.abnormalityIndex >= 35 ? 'var(--amber-t)' : 'var(--green-t)';

          return (
            <>
              {/* ④ 이상소비지수 */}
              <SectionTitle aux="0에 가까울수록 안정적">이상소비지수</SectionTitle>
              <div className="card">
                <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 12 }}>
                  <span style={{ fontSize: 38, fontWeight: 800, color: indexColor, lineHeight: 1, letterSpacing: '-1px' }}>
                    {profile.abnormalityIndex}
                  </span>
                  <span className="muted">/ 100</span>
                  <span className="muted small" style={{ marginLeft: 'auto', textAlign: 'right' }}>
                    총지출 {won(profile.totalSpend)}
                    {profile.topCategory1 && <><br />최다 {profile.topCategory1}</>}
                  </span>
                </div>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                  {FACTOR_ORDER.map((f) => {
                    const pts = profile.contributionPoints[f] ?? 0;
                    return (
                      <div key={f} style={{ display: 'flex', alignItems: 'center', gap: 10, fontSize: 13 }}>
                        <span className="muted" style={{ width: 58, flex: '0 0 auto' }}>{f}</span>
                        <span className="bar" style={{ flex: 1, margin: 0 }} aria-hidden="true">
                          <i style={{ width: `${(pts / maxFactor) * 100}%`, background: indexColor }} />
                        </span>
                        <span className="num" style={{ width: 36, textAlign: 'right', fontWeight: 700 }}>{pts}점</span>
                      </div>
                    );
                  })}
                </div>
                <div style={{ marginTop: 14 }}>
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => void loadProfileNarr()}>
                    {profileNarr === 'loading' ? '요약 생성 중…' : '✦ AI 요약 보기'}
                  </button>
                  {profileNarr && profileNarr !== 'loading' && (
                    <div className="pv">
                      {profileNarr.text} <span className="aux-badge" style={{ marginLeft: 4 }}>{profileNarr.source}</span>
                    </div>
                  )}
                </div>
              </div>

              {/* ⑤ 절약 후보 */}
              <SectionTitle aux="줄일 수 있는 소비">절약 후보</SectionTitle>
              {msg && <p className="notice-ok" role="status">{msg}</p>}
              {cutCandidates.length === 0 ? (
                <div className="card"><Empty>지금은 줄이라고 권할 만한 소비가 없어요. 좋은 흐름이에요.</Empty></div>
              ) : (
                <div className="card" style={{ padding: '6px 18px' }}>
                  {cutCandidates.slice(0, 6).map((c) => {
                    const n = cutNarr[c.category2];
                    const { icon, bg } = iconOf(c.category2);
                    return (
                      <div key={c.category2} style={{ padding: '12px 0', borderBottom: '1px solid var(--bg)' }}>
                        <div className="list-item" style={{ padding: 0 }}>
                          <span className="ic" style={{ background: bg }}><Icon id={icon} /></span>
                          <div className="tx">
                            <b>{c.category2} <span className="aux-badge">{c.type === 'REMOVABLE' ? '제거 가능' : '줄이기 가능'}</span></b>
                            <span>{c.reason}</span>
                          </div>
                          <div style={{ textAlign: 'right' }}>
                            <b style={{ color: 'var(--green-t)', fontSize: 15 }}>−{won(c.estimatedSaving)}</b>
                            <div style={{ fontSize: 11, color: 'var(--t3)' }}>월 {won(c.monthlySpend)}</div>
                          </div>
                        </div>
                        <div className="ctx3">
                          <button type="button" onClick={() => void explainCut(c.category2)}>
                            {n === 'loading' ? '설명 생성 중…' : '왜?'}
                          </button>
                          <button type="button" className={chosen[c.category2] ? 'on' : ''}
                            disabled={chosen[c.category2]} onClick={() => void choose(c.category2)}>
                            {chosen[c.category2] ? '추적 중 ✓' : '줄이기'}
                          </button>
                        </div>
                        {n && n !== 'loading' && (
                          <div className="pv" style={{ marginTop: 8 }}>
                            {n.text} <span className="aux-badge" style={{ marginLeft: 4 }}>{n.source}</span>
                          </div>
                        )}
                      </div>
                    );
                  })}
                </div>
              )}

              {/* ② 반복 결제 */}
              <SectionTitle aux={`고정 ${fixed.length} · 루틴 ${routine.length}`}>반복되는 결제</SectionTitle>
              <div className="card" style={{ padding: '10px 18px' }}>
                {recurring.length === 0 ? <Empty>아직 반복 패턴이 잡히지 않았어요.</Empty> : (
                  <>
                    {fixed.map((r, i) => (
                      <div className="txn" key={`f${i}`}>
                        <span className="c">고정</span>
                        <span className="m">{r.merchantName ?? r.category2}</span>
                        <span className="muted small" style={{ flex: '0 0 auto' }}>
                          {r.periodDays ? `${r.periodDays}일마다` : ''}{r.nextExpected ? ` · 다음 ${r.nextExpected.slice(5)}` : ''}
                        </span>
                        <span className="a">{won(r.representativeAmount)}</span>
                      </div>
                    ))}
                    {routine.map((r, i) => (
                      <div className="txn" key={`r${i}`}>
                        <span className="c">루틴</span>
                        <span className="m">{r.category2}</span>
                        <span className="muted small" style={{ flex: '0 0 auto' }}>
                          {r.daypart} · 주 {r.perWeekFrequency}회
                        </span>
                        <span className="a">{won(r.representativeAmount)}</span>
                      </div>
                    ))}
                  </>
                )}
              </div>

              {/* ③ 소비 패턴 */}
              <SectionTitle aux={pattern.peak ? `피크 ${DOW_KR[pattern.peak.dayOfWeek]}요일 ${pattern.peak.daypart}` : undefined}>
                언제 쓰나
              </SectionTitle>
              <div className="card">
                <p className="label" style={{ margin: '0 0 4px' }}>시간대별</p>
                <BarChart height={84} color="var(--blue)"
                  bars={DAYPART_ORDER.map((d) => ({ label: d, value: pattern.amountByDaypart[d] ?? 0 }))} />
                <p className="label" style={{ margin: '16px 0 4px' }}>요일별</p>
                <BarChart height={84} color="#8B5CF6"
                  bars={DOW_ORDER.map((d) => ({ label: DOW_KR[d], value: pattern.amountByDayOfWeek[d] ?? 0 }))} />
              </div>
            </>
          );
        })()}
        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
