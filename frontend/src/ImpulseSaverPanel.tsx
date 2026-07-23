import { useEffect, useState } from 'react';
import { api, catLabel, type ImpulseSnapshot } from './api';
import { GiftBox } from './GiftBox';

const won = (n: number) => Math.round(n).toLocaleString('ko-KR') + '원';

/**
 * 충동예산 절약통 (문서 §5-5, 2026-07-21 방향 전환) — 수동 '살 뻔했다'를 대체.
 * ① 카드내역에서 충동 카테고리 지정 → 예산 = 그 월 평균. ② 들어올 때마다 시간에 따라 자동 성장(하루 50/30/20, 안 온 날 누적).
 * ③ 충동소비 기록 시 균열. ④ 다음달 카드내역 재업로드 → 지정 카테고리 지출이 줄었는지 재검증.
 */
export function ImpulseSaverPanel({ userId }: { userId: number }) {
  const [snap, setSnap] = useState<ImpulseSnapshot | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [actionKey, setActionKey] = useState(0);

  const [csv, setCsv] = useState('');
  const [uploading, setUploading] = useState(false);

  async function load() {
    try {
      setSnap(await api.impulse(userId));
    } catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
  }
  useEffect(() => { void load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  function announce(s: ImpulseSnapshot) {
    setActionKey((k) => k + 1);
    if (s.lastAction === 'UNNECESSARY') setFeedback('💥 충동소비를 기록했어요 — 절약통에 금이 갔어요');
    else if (s.uploaded > 0) setFeedback(`📥 카드내역 ${s.uploaded}건을 반영했어요 — 아래에서 재검증 결과를 확인하세요`);
    else setFeedback(null);
  }

  async function run(p: Promise<ImpulseSnapshot>) {
    setErr(null);
    try { const s = await p; setSnap(s); announce(s); return s; }
    catch (e) { setErr(e instanceof Error ? e.message : String(e)); return null; }
  }

  function toggleCategory(code: string) {
    if (!snap) return;
    const cur = new Set(snap.impulseCategories);
    if (cur.has(code)) cur.delete(code); else cur.add(code);
    void run(api.setImpulseCategories(userId, [...cur]));
  }

  if (!snap) {
    return (
      <section className="card points"><div className="card-pad">
        {err ? <div className="error" role="alert"><code>{err}</code></div> : <div className="loading-bar" role="status" aria-label="불러오는 중" />}
      </div></section>
    );
  }

  const noBudget = snap.budget <= 0;

  return (
    <section className="card points">
      <div className="card-pad">
        <div className="pts-top">
          <div>
            <p className="eyebrow">충동예산 절약통 · 자동 성장</p>
            <h2 className="pts-title">참을수록 저절로 커져요</h2>
          </div>
        </div>

        {err && <div className="error" role="alert"><code>{err}</code></div>}
        {feedback && <div className="pts-feedback" key={actionKey} role="status" aria-live="polite">{feedback}</div>}

        {/* 선물상자 + 요약 */}
        <div className="gift-summary">
          <GiftBox fill={snap.giftFill} totalSavings={snap.giftBalance}
            lastAction={snap.lastAction === 'GROW' ? 'SAVED' : snap.lastAction} actionKey={actionKey} />
          <div className="gs-stats">
            <div className="gs-row"><span>충동예산(월)</span><b className="num">{noBudget ? '—' : won(snap.budget)}</b></div>
            <div className="gs-row big"><span>지금까지 모임</span><b className="num sav">{won(snap.giftBalance)}</b></div>
            <div className="gs-row"><span>하루 할당량</span><b className="num">{noBudget ? '—' : won(snap.dailyQuota)}</b></div>
            <span className="tiny muted">들어올 때마다 하루치를 50→30→20%씩, 안 온 날은 다음에 합쳐서 채워요</span>
          </div>
        </div>

        {/* ① 충동 카테고리 지정 → 예산 */}
        <div className="pts-section">
          <h3>어떤 소비가 충동이었나요? <span className="muted tiny">— 3개월 카드내역에서 고르면 그 월 평균이 예산이 돼요</span></h3>
          {snap.options.length === 0 ? (
            <p className="muted small">소비 이력이 아직 없어요. ‘기록’ 탭에서 소비를 남기거나 카드내역을 올려보세요.</p>
          ) : (
            <div className="imp-cats">
              {snap.options.slice(0, 8).map((o) => {
                const on = snap.impulseCategories.includes(o.categoryCode);
                return (
                  <button type="button" key={o.categoryCode} className={`imp-chip ${on ? 'on' : ''}`}
                    aria-pressed={on} onClick={() => toggleCategory(o.categoryCode)}>
                    {on ? '✓ ' : ''}{catLabel(o.categoryCode, o.displayName)}
                    <span className="imp-amt">월 {won(o.monthlyAmount)}</span>
                  </button>
                );
              })}
            </div>
          )}
        </div>

        {/* 다음달 카드내역 재업로드 → 재검증 */}
        <div className="pts-section">
          <h3>다음달 카드내역으로 재검증 <span className="muted tiny">— 충동소비가 실제로 줄었는지 확인해요</span></h3>
          <p className="muted small" style={{ marginTop: 0 }}>
            형식: <code>날짜,카테고리코드,금액</code> (한 줄에 한 건). 예: <code>2026-08-03,CAFE,5500</code>
          </p>
          <label className="sr-only" htmlFor="imp-csv">카드내역 CSV</label>
          <textarea id="imp-csv" className="imp-csv" rows={4} value={csv}
            onChange={(e) => setCsv(e.target.value)}
            placeholder={'2026-08-03,CAFE,5500\n2026-08-05,SHOPPING,32000'} />
          <div className="imp-upload-row">
            <button type="button" className="btn btn-primary btn-sm" disabled={uploading || !csv.trim()}
              onClick={() => { setUploading(true); void run(api.impulseUpload(userId, csv)).then((s) => { if (s) setCsv(''); }).finally(() => setUploading(false)); }}>
              {uploading ? '반영 중…' : '업로드 · 재검증'}
            </button>
          </div>

          {snap.verify.length > 0 && (
            <ul className="verify-list">
              {snap.verify.map((v) => {
                const dropped = Math.round(Math.abs(v.changePct) * 100);
                return (
                  <li key={v.categoryCode} className={v.improved ? 'better' : 'worse'}>
                    <span className="v-cat">{catLabel(v.categoryCode, v.displayName)}</span>
                    <span className="v-nums">{won(v.baseline)} → {won(v.latest)}</span>
                    <span className={`v-tag ${v.improved ? 'ok' : 'bad'}`}>
                      {v.improved ? `▼ ${dropped}% 줄었어요 👍` : v.changePct > 0 ? `▲ ${dropped}% 늘었어요` : '변화 없음'}
                    </span>
                  </li>
                );
              })}
            </ul>
          )}
          {snap.hasUpload && snap.verify.length === 0 && (
            <p className="muted small">충동 카테고리를 먼저 지정하면 재검증 결과가 나와요.</p>
          )}
        </div>
      </div>
    </section>
  );
}
