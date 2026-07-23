import { useEffect, useState } from 'react';
import { api, catLabel, type MyCard, type MyPayment, type MyPaymentHistory } from './api';

const won = (n: number) => Math.round(n).toLocaleString('ko-KR') + '원';
const man = (n: number) => `${Math.round((n / 10000) * 10) / 10}만`;

/** 내 카드 (§13-6) — 마이데이터로 불러온 카드별 실적 진행률·받은/더 받을 혜택 + 카드 상세 결제내역. */
export function MyCardPanel({ userId }: { userId: number }) {
  const [cards, setCards] = useState<MyCard[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [payments, setPayments] = useState<Record<string, MyPayment[]>>({});
  const [history, setHistory] = useState<MyPaymentHistory[] | null>(null);
  const [syncing, setSyncing] = useState(false);
  const [syncMsg, setSyncMsg] = useState<string | null>(null);

  useEffect(() => {
    api.myCards(userId).then(setCards).catch((e) => setErr(String(e)));
    api.allPayments(userId, 6).then(setHistory).catch(() => setHistory([]));
  }, [userId]);

  async function doSync() {
    setSyncing(true); setSyncMsg(null);
    try {
      const r = await api.syncMyData(userId);
      setSyncMsg(r.newPayments > 0 ? `새 결제 ${r.newPayments}건을 불러왔어요` : '이미 최신 상태예요');
      if (r.newPayments > 0) setHistory(await api.allPayments(userId, 6));
    } catch (e) {
      setSyncMsg(e instanceof Error ? e.message : String(e));
    } finally {
      setSyncing(false);
    }
  }

  async function toggle(serial: string) {
    if (open === serial) { setOpen(null); return; }
    setOpen(serial);
    if (!payments[serial]) {
      try {
        const rows = await api.cardPayments(userId, serial);
        setPayments((prev) => ({ ...prev, [serial]: rows }));
      } catch (e) { setErr(String(e)); }
    }
  }

  if (err) return <section className="section card card-pad"><div className="error" role="alert"><code>{err}</code></div></section>;
  if (!cards) return <section className="section card card-pad"><div className="loading-bar" role="status" aria-label="불러오는 중" /></section>;

  // 결제내역 모아보기 — 최근 6개월, 월별 그룹(최신월 먼저)
  const byMonth: Record<string, MyPaymentHistory[]> = {};
  for (const p of history ?? []) (byMonth[p.date.slice(0, 7)] ??= []).push(p);
  const months = Object.keys(byMonth).sort((a, b) => b.localeCompare(a));

  return (
    <div className="view">
      <section className="section card card-pad" aria-labelledby="h-mycard">
        <div className="section-head" style={{ marginBottom: 6 }}>
          <h2 id="h-mycard">내 카드</h2>
          <span className="badge-aux">마이데이터</span>
        </div>
        <p className="muted small" style={{ marginTop: 0 }}>
          연결한 카드의 <b>실적</b>과 이번 달 <b>받은 혜택</b>을 모아봐요. 판매·중개가 아니에요.
        </p>

        {cards.length === 0 ? (
          <p className="muted small">아직 불러온 카드가 없어요. 더보기 → 설정에서 카드사를 연결해 보세요.</p>
        ) : (
          <div className="mc-list">
            {cards.map((c) => {
              const progress = c.requirement > 0 ? Math.min(100, Math.round((c.currentPerformance / c.requirement) * 100)) : 100;
              const rows = payments[c.serialNumber] ?? [];
              return (
                <div className="mc-card" key={c.serialNumber}>
                  <div className="mc-face" style={{ background: c.cardColor || '#191BA9' }}>
                    <span className="mc-co">{c.companyName}</span>
                    <span className="mc-name">{c.cardName}</span>
                    <span className="mc-serial">{c.serialNumber.slice(-4)} 로 끝나는 카드</span>
                  </div>
                  <div className="mc-body">
                    <div className="mc-perf">
                      <div className="mc-perf-top">
                        <span>전월실적 {man(c.requirement)}원</span>
                        <span className={c.requirementMet ? 'met' : ''}>
                          {c.requirementMet ? '✓ 충족' : `${won(c.toRequirement)} 더`}
                        </span>
                      </div>
                      <div className="mc-track" aria-hidden="true"><span className="mc-fill" style={{ width: `${progress}%` }} /></div>
                      <div className="mc-perf-nums">
                        <span>사용 {won(c.currentPerformance)}</span>
                        <span className="mc-earn">받은 혜택 <b>{won(c.earnedThisMonth)}</b></span>
                      </div>
                    </div>
                    <button type="button" className="btn btn-ghost btn-sm mc-more"
                      aria-expanded={open === c.serialNumber} onClick={() => void toggle(c.serialNumber)}>
                      {open === c.serialNumber ? '결제내역 접기' : '결제내역 보기'}
                    </button>
                    {open === c.serialNumber && (
                      <ul className="mc-tx">
                        {rows.length === 0 ? <li className="muted small">불러오는 중…</li> : rows.slice(0, 30).map((p) => (
                          <li key={p.paymentId}>
                            <span className="mc-tx-cat">{catLabel(p.category1)}</span>
                            <span className="mc-tx-merch">{p.merchantName}</span>
                            <span className="mc-tx-amt">{won(p.amount)}
                              {p.receivedBenefit > 0 && <em className="mc-tx-benefit"> −{won(p.receivedBenefit)}</em>}
                            </span>
                          </li>
                        ))}
                      </ul>
                    )}
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </section>

      {/* 결제내역 모아보기 — 전 카드 최근 6개월(§13-11) */}
      <section className="section card card-pad" aria-labelledby="h-hist">
        <div className="section-head" style={{ marginBottom: 6 }}>
          <span style={{ display: 'inline-flex', alignItems: 'baseline', gap: 8 }}>
            <h2 id="h-hist">결제내역 모아보기</h2><span className="badge-aux">최근 6개월 · 전 카드</span>
          </span>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => void doSync()} disabled={syncing}>
            {syncing ? '동기화 중…' : '동기화'}
          </button>
        </div>
        <p className="muted small" style={{ marginTop: 0 }}>
          연결한 모든 카드의 최근 6개월 결제를 한 곳에서 봐요.{history ? ` 총 ${history.length.toLocaleString('ko-KR')}건.` : ''}
          {syncMsg && <span style={{ marginLeft: 8 }} role="status">· {syncMsg}</span>}
        </p>
        {history === null ? (
          <div className="loading-bar" role="status" aria-label="불러오는 중" />
        ) : history.length === 0 ? (
          <p className="muted small">불러온 결제내역이 없어요.</p>
        ) : (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 16 }}>
            {months.map((m) => {
              const rows = byMonth[m];
              const total = rows.reduce((s, p) => s + p.amount, 0);
              const [y, mo] = m.split('-');
              return (
                <div key={m}>
                  <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'baseline',
                    padding: '4px 0 6px', borderBottom: '1px solid var(--line, #e5e5e5)', marginBottom: 6 }}>
                    <b>{y}년 {mo}월</b>
                    <span className="muted small">{rows.length}건 · {won(total)}</span>
                  </div>
                  <ul style={{ listStyle: 'none', margin: 0, padding: 0, display: 'flex', flexDirection: 'column', gap: 2 }}>
                    {rows.map((p) => (
                      <li key={p.paymentId} style={{ display: 'flex', alignItems: 'center', gap: 8,
                        padding: '5px 2px', fontSize: '.86rem' }}>
                        <span className="muted" style={{ width: 42, flexShrink: 0, fontVariantNumeric: 'tabular-nums' }}>
                          {p.date.slice(5, 10).replace('-', '.')}</span>
                        <span style={{ width: 60, flexShrink: 0 }}>{catLabel(p.category2 ?? p.category1)}</span>
                        <span style={{ flex: 1, minWidth: 0, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }}>
                          {p.merchantName}</span>
                        {p.cardName && (
                          <span style={{ flexShrink: 0, fontSize: '.72rem', padding: '1px 6px', borderRadius: 999,
                            border: `1px solid ${p.cardColor || '#ccc'}`, color: p.cardColor || '#666' }}>
                            {p.cardName}</span>
                        )}
                        <span style={{ width: 78, flexShrink: 0, textAlign: 'right', fontWeight: 600,
                          fontVariantNumeric: 'tabular-nums' }}>{won(p.amount)}</span>
                      </li>
                    ))}
                  </ul>
                </div>
              );
            })}
          </div>
        )}
      </section>
    </div>
  );
}
