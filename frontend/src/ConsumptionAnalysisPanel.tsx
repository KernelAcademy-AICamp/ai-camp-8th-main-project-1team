import { useEffect, useState } from 'react';
import { api, type AnalysisSummary, type Narrative } from './api';

const won = (n: number) => Math.round(n).toLocaleString('ko-KR') + '원';
const DOW_ORDER = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
const DOW_KR: Record<string, string> = {
  MONDAY: '월', TUESDAY: '화', WEDNESDAY: '수', THURSDAY: '목', FRIDAY: '금', SATURDAY: '토', SUNDAY: '일',
};
const DAYPART_ORDER = ['아침', '점심', '저녁', '심야'];
const FACTOR_ORDER = ['낭비', '집중', '변동', '심야충동'];

/** 내 소비 분석(§소비분석 ②③④⑤) — 이상소비지수·반복결제·소비패턴·절약후보. 판단은 서버 엔진, 문장은 온디맨드 LLM. */
export function ConsumptionAnalysisPanel({ userId }: { userId: number }) {
  const [data, setData] = useState<AnalysisSummary | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [profileNarr, setProfileNarr] = useState<Narrative | 'loading' | null>(null);
  const [cutNarr, setCutNarr] = useState<Record<string, Narrative | 'loading'>>({});
  const [chosen, setChosen] = useState<Record<string, boolean>>({});
  const [msg, setMsg] = useState<string | null>(null);

  useEffect(() => {
    setData(null); setErr(null);
    api.analysis(userId).then(setData).catch((e) => setErr(String(e)));
  }, [userId]);

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

  if (err) return <section className="section card card-pad"><div className="error" role="alert"><code>{err}</code></div></section>;
  if (!data) return <section className="section card card-pad"><div className="loading-bar" role="status" aria-label="불러오는 중" /></section>;

  const { profile, recurring, pattern, cutCandidates } = data;
  const fixed = recurring.filter((r) => r.type === 'FIXED');
  const routine = recurring.filter((r) => r.type === 'ROUTINE');
  const maxFactor = Math.max(1, ...Object.values(profile.contributionPoints));
  const maxDaypart = Math.max(1, ...Object.values(pattern.amountByDaypart));
  const maxDow = Math.max(1, ...Object.values(pattern.amountByDayOfWeek));
  const indexColor = profile.abnormalityIndex >= 60 ? '#d9480f' : profile.abnormalityIndex >= 35 ? '#e8a33d' : '#2f9e44';

  return (
    <>
      {/* ④ 이상소비지수 */}
      <section className="section card card-pad" aria-labelledby="h-idx">
        <div className="section-head" style={{ marginBottom: 6 }}>
          <h2 id="h-idx">내 소비 분석</h2><span className="badge-aux">이상소비지수</span>
        </div>
        <div style={{ display: 'flex', alignItems: 'baseline', gap: 10, marginBottom: 10 }}>
          <span style={{ fontSize: '2.4rem', fontWeight: 800, color: indexColor, lineHeight: 1 }}>
            {profile.abnormalityIndex}</span>
          <span className="muted">/ 100</span>
          <span className="muted small" style={{ marginLeft: 'auto' }}>
            총지출 {won(profile.totalSpend)}{profile.topCategory1 && ` · 최다 ${profile.topCategory1}`}</span>
        </div>
        {/* 성분별 기여 분해(합=지수) — 설명가능 */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 4 }}>
          {FACTOR_ORDER.map((f) => {
            const pts = profile.contributionPoints[f] ?? 0;
            return (
              <div key={f} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: '.82rem' }}>
                <span style={{ width: 60, flexShrink: 0 }} className="muted">{f}</span>
                <div style={{ flex: 1, background: 'var(--line,#eee)', borderRadius: 999, height: 10, overflow: 'hidden' }}>
                  <span style={{ display: 'block', height: '100%', width: `${(pts / maxFactor) * 100}%`, background: indexColor }} />
                </div>
                <span style={{ width: 34, textAlign: 'right', fontVariantNumeric: 'tabular-nums' }}>{pts}점</span>
              </div>
            );
          })}
        </div>
        <div style={{ marginTop: 10 }}>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => void loadProfileNarr()}>
            {profileNarr === 'loading' ? '요약 생성 중…' : 'AI 요약 보기'}
          </button>
          {profileNarr && profileNarr !== 'loading' && (
            <p className="muted small" style={{ marginTop: 6 }}>
              {profileNarr.text} <span className="badge-aux" style={{ marginLeft: 4 }}>{profileNarr.source}</span>
            </p>
          )}
        </div>
      </section>

      {/* ⑤ 절약 후보 */}
      <section className="section card card-pad" aria-labelledby="h-cut">
        <div className="section-head" style={{ marginBottom: 6 }}>
          <h2 id="h-cut">줄일 수 있는 소비</h2><span className="badge-aux">절약 후보</span>
        </div>
        {msg && <p className="muted small" role="status" style={{ marginTop: 0 }}>· {msg}</p>}
        {cutCandidates.length === 0 ? (
          <p className="muted small">지금은 줄이라고 권할 만한 소비가 없어요. 좋은 흐름이에요.</p>
        ) : (
          <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 8 }}>
            {cutCandidates.slice(0, 6).map((c) => {
              const n = cutNarr[c.category2];
              return (
                <li key={c.category2} style={{ padding: '8px 0', borderBottom: '1px solid var(--line,#eee)' }}>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
                    <b>{c.category2}</b>
                    <span className="badge-aux">{c.type === 'REMOVABLE' ? '제거가능' : '최적화가능'}</span>
                    <span style={{ marginLeft: 'auto', fontWeight: 700, color: '#2f9e44' }}>−{won(c.estimatedSaving)}</span>
                  </div>
                  <p className="muted small" style={{ margin: '2px 0 6px' }}>{c.reason}</p>
                  <div style={{ display: 'flex', alignItems: 'center', gap: 6 }}>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => void explainCut(c.category2)}>
                      {n === 'loading' ? '설명 생성 중…' : '왜?'}
                    </button>
                    <button type="button" className="btn btn-ghost btn-sm" disabled={chosen[c.category2]}
                      onClick={() => void choose(c.category2)}>
                      {chosen[c.category2] ? '추적 중 ✓' : '줄이기'}
                    </button>
                  </div>
                  {n && n !== 'loading' && (
                    <p className="muted small" style={{ marginTop: 6 }}>
                      {n.text} <span className="badge-aux" style={{ marginLeft: 4 }}>{n.source}</span>
                    </p>
                  )}
                </li>
              );
            })}
          </ul>
        )}
      </section>

      {/* ② 반복 결제 */}
      <section className="section card card-pad" aria-labelledby="h-rec">
        <div className="section-head" style={{ marginBottom: 6 }}>
          <h2 id="h-rec">반복되는 결제</h2><span className="badge-aux">고정 {fixed.length} · 루틴 {routine.length}</span>
        </div>
        {recurring.length === 0 ? (
          <p className="muted small">아직 반복 패턴이 잡히지 않았어요.</p>
        ) : (
          <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 4 }}>
            {fixed.map((r, i) => (
              <li key={`f${i}`} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: '.84rem', padding: '3px 0' }}>
                <span className="badge-aux">고정</span>
                <span>{r.merchantName ?? r.category2}</span>
                <span className="muted small">{r.periodDays}일마다{r.nextExpected ? ` · 다음 ${r.nextExpected.slice(5)}` : ''}</span>
                <span style={{ marginLeft: 'auto', fontWeight: 600 }}>{won(r.representativeAmount)}</span>
              </li>
            ))}
            {routine.map((r, i) => (
              <li key={`r${i}`} style={{ display: 'flex', alignItems: 'center', gap: 8, fontSize: '.84rem', padding: '3px 0' }}>
                <span className="badge-aux">루틴</span>
                <span>{r.category2}</span>
                <span className="muted small">{r.daypart} · 주 {r.perWeekFrequency}회 · {r.occurrenceDays}일</span>
                <span style={{ marginLeft: 'auto', fontWeight: 600 }}>{won(r.representativeAmount)}</span>
              </li>
            ))}
          </ul>
        )}
      </section>

      {/* ③ 소비 패턴 */}
      <section className="section card card-pad" aria-labelledby="h-pat">
        <div className="section-head" style={{ marginBottom: 6 }}>
          <h2 id="h-pat">언제 쓰나</h2>
          {pattern.peak && <span className="badge-aux">피크 {DOW_KR[pattern.peak.dayOfWeek]}요일 {pattern.peak.daypart}</span>}
        </div>
        <div className="muted small" style={{ marginBottom: 4 }}>시간대별</div>
        <div style={{ display: 'flex', gap: 6, marginBottom: 10 }}>
          {DAYPART_ORDER.map((d) => {
            const amt = pattern.amountByDaypart[d] ?? 0;
            return (
              <div key={d} style={{ flex: 1, textAlign: 'center' }}>
                <div style={{ height: 46, display: 'flex', alignItems: 'flex-end' }}>
                  <span style={{ display: 'block', width: '100%', height: `${(amt / maxDaypart) * 100}%`,
                    background: '#4263eb', borderRadius: '3px 3px 0 0', minHeight: 2 }} />
                </div>
                <div className="muted small" style={{ marginTop: 2 }}>{d}</div>
              </div>
            );
          })}
        </div>
        <div className="muted small" style={{ marginBottom: 4 }}>요일별</div>
        <div style={{ display: 'flex', gap: 4 }}>
          {DOW_ORDER.map((d) => {
            const amt = pattern.amountByDayOfWeek[d] ?? 0;
            return (
              <div key={d} style={{ flex: 1, textAlign: 'center' }}>
                <div style={{ height: 40, display: 'flex', alignItems: 'flex-end' }}>
                  <span style={{ display: 'block', width: '100%', height: `${(amt / maxDow) * 100}%`,
                    background: '#748ffc', borderRadius: '3px 3px 0 0', minHeight: 2 }} />
                </div>
                <div className="muted small" style={{ marginTop: 2 }}>{DOW_KR[d]}</div>
              </div>
            );
          })}
        </div>
      </section>
    </>
  );
}
