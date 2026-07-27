/**
 * 리포트 &gt; 내 카드 (§13-6) — 마이데이터로 불러온 카드별 실적 진행률·이번 달 받은 혜택,
 * 그리고 카드별 결제내역. 판매·중개가 아니라 이미 가진 카드를 정리해 보여주는 것뿐이다.
 */
import { useState } from 'react';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty, SectionTitle } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, catLabel, type MyPayment } from '../lib/api';
import { won, man, shortDate } from '../lib/format';

export function ReportCards() {
  const { back, userId } = useSession();
  const cards = useAsync(() => api.myCards(userId), [userId]);
  const account = useAsync(() => api.account(userId).catch(() => null), [userId]);
  const [open, setOpen] = useState<string | null>(null);
  const [payments, setPayments] = useState<Record<string, MyPayment[]>>({});

  async function toggle(serial: string) {
    if (open === serial) { setOpen(null); return; }
    setOpen(serial);
    if (!payments[serial]) {
      try {
        const rows = await api.cardPayments(userId, serial);
        setPayments((prev) => ({ ...prev, [serial]: rows }));
      } catch { setPayments((prev) => ({ ...prev, [serial]: [] })); }
    }
  }

  const list = cards.data ?? [];
  const monthSpend = list.reduce((sum, c) => sum + c.currentPerformance, 0);
  const monthBenefit = list.reduce((sum, c) => sum + c.earnedThisMonth, 0);

  return (
    <Screen title="내 카드" hasTabBar>
      <AppBar onBack={back} title="내 카드" />
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>
        <ErrorBox error={cards.error} onRetry={cards.reload} />
        {cards.loading && <Loading label="카드를 불러오는 중" rows={5} />}

        {!cards.loading && list.length === 0 && !cards.error && (
          <div className="card"><Empty>아직 불러온 카드가 없어요. 마이 &gt; 연결 관리에서 카드사를 연결해 보세요.</Empty></div>
        )}

        {list.length > 0 && (
          <>
            <div className="asset-row">
              <div className="asset"><b>{won(monthSpend)}</b><span>이번 달 사용</span></div>
              <div className="asset"><b style={{ color: 'var(--green-t)' }}>{won(monthBenefit)}</b><span>받은 혜택</span></div>
              <div className="asset"><b>{list.length}장</b><span>연결 카드</span></div>
            </div>

            {list.map((c) => {
              const progress = c.requirement > 0
                ? Math.min(100, Math.round((c.currentPerformance / c.requirement) * 100)) : 100;
              const rows = payments[c.serialNumber] ?? [];
              const expanded = open === c.serialNumber;
              return (
                <div className="card" key={c.serialNumber} style={{ padding: 16 }}>
                  <div className="mc-face" style={{ background: c.cardColor || 'var(--blue-dark)' }}>
                    <span className="co">{c.companyName}</span>
                    <span className="nm">{c.cardName}</span>
                    <span className="sn">{c.serialNumber.slice(-4)} 로 끝나는 카드</span>
                  </div>

                  <div style={{ marginTop: 14 }}>
                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, color: 'var(--t2)' }}>
                      <span>전월실적 {man(c.requirement)}</span>
                      <span style={{ fontWeight: 700, color: c.requirementMet ? 'var(--green)' : 'var(--t2)' }}>
                        {c.requirementMet ? '✓ 충족' : `${won(c.toRequirement)} 더`}
                      </span>
                    </div>
                    <div className="bar" style={{ height: 6 }}>
                      <i style={{ width: `${progress}%`, background: c.requirementMet ? 'var(--green)' : 'var(--blue)' }} />
                    </div>
                    <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 12.5, marginTop: 8, color: 'var(--t3)' }}>
                      <span>사용 {won(c.currentPerformance)}</span>
                      <span>받은 혜택 <b style={{ color: 'var(--green-t)' }}>{won(c.earnedThisMonth)}</b></span>
                    </div>
                  </div>

                  <button type="button" className="btn btn-ghost btn-sm" style={{ marginTop: 12, width: '100%' }}
                    aria-expanded={expanded} onClick={() => void toggle(c.serialNumber)}>
                    {expanded ? '결제내역 접기' : '결제내역 보기'}
                  </button>

                  {expanded && (
                    <div style={{ marginTop: 8 }}>
                      {rows.length === 0 ? <Empty>불러오는 중이거나 결제가 없어요.</Empty> : rows.slice(0, 30).map((p) => (
                        <div className="txn" key={p.paymentId}>
                          <span className="d">{shortDate(p.date)}</span>
                          <span className="m">{p.merchantName ?? catLabel(p.category2 ?? p.category1)}</span>
                          <span className="a">
                            {won(p.amount)}
                            {p.receivedBenefit > 0 && (
                              <em style={{ color: 'var(--green-t)', fontStyle: 'normal', marginLeft: 4 }}>−{won(p.receivedBenefit)}</em>
                            )}
                          </span>
                        </div>
                      ))}
                    </div>
                  )}
                </div>
              );
            })}
          </>
        )}

        {/* 입출금 통장(§13-11 경제 모델) — 카드=출금, 매달 월급=입금 */}
        {account.data && (
          <>
            <SectionTitle aux="마이데이터">내 통장</SectionTitle>
            <div className="card">
              <div className="asset-row" style={{ marginBottom: 12 }}>
                <div className="asset">
                  <b style={{ color: account.data.balance < 0 ? 'var(--red)' : undefined }}>{won(account.data.balance)}</b>
                  <span>잔액</span>
                </div>
                <div className="asset"><b style={{ color: 'var(--green-t)' }}>{won(account.data.salary)}</b><span>월급</span></div>
              </div>
              <p className="empty" style={{ marginTop: 0 }}>
                {account.data.bank} · {account.data.product} · <span className="num">{account.data.accountNumber}</span><br />
                매월 {account.data.payday}일 · <b>{account.data.salaryPayer}</b>에서 급여 입금
              </p>
              {account.data.transactions.slice(0, 8).map((t, i) => (
                <div className="txn" key={i}>
                  <span className="d">{shortDate(t.date)}</span>
                  <span className="m">{t.description}</span>
                  <span className="a" style={{ color: t.type === 'DEPOSIT' ? 'var(--green)' : undefined }}>
                    {t.type === 'DEPOSIT' ? '+' : '−'}{won(t.amount)}
                  </span>
                </div>
              ))}
            </div>
          </>
        )}

        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
