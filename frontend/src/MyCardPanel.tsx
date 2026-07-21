import { useEffect, useState } from 'react';
import { api, catLabel, type MyCard, type MyPayment } from './api';

const won = (n: number) => Math.round(n).toLocaleString('ko-KR') + '원';
const man = (n: number) => `${Math.round((n / 10000) * 10) / 10}만`;

/** 내 카드 (§13-6) — 마이데이터로 불러온 카드별 실적 진행률·받은/더 받을 혜택 + 카드 상세 결제내역. */
export function MyCardPanel({ userId }: { userId: number }) {
  const [cards, setCards] = useState<MyCard[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);
  const [payments, setPayments] = useState<Record<string, MyPayment[]>>({});

  useEffect(() => {
    api.myCards(userId).then(setCards).catch((e) => setErr(String(e)));
  }, [userId]);

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
    </div>
  );
}
