/**
 * RP 하위 — 내 통장 (§13-11 경제 모델).
 *
 * 카드가 '나가는 돈'이라면 통장은 '남는 돈'이다. 월급이 들어오고, 카드값이 빠지고, 매달 이자가
 * 붙고 그 자리에서 이자소득세가 떨어진다. 이 화면은 그 흐름을 그대로 보여준다.
 *
 * <b>잔액은 저장된 값이 아니다.</b> 제공자가 조회 시점에 계산해 준다
 * (초기잔액 + 월급누적 + 이자 − 세금 − 카드출금). 그래서 결제가 하나 들어오면 즉시 반영된다.
 *
 * 은행을 연동하지 않았으면 서버가 null을 준다 — 연결하지 않은 자산이 보이면 연결이라는 절차가
 * 의미를 잃기 때문이다. 그때는 연결 관리로 안내한다.
 */
import { useMemo, useState } from 'react';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty, SectionTitle } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api } from '../lib/api';
import { won, shortDate, monthLabel } from '../lib/format';

/** 계좌번호는 뒤 4자리만 남긴다 — 화면에 전부 띄울 이유가 없다. */
const maskAccount = (n: string) => {
  const tail = n.replace(/\D/g, '').slice(-4);
  return tail ? `****-${tail}` : n;
};

type Filter = 'all' | 'DEPOSIT' | 'WITHDRAWAL';

export function ReportAccount() {
  const { back, userId, go } = useSession();
  const account = useAsync(() => api.account(userId), [userId]);
  const [filter, setFilter] = useState<Filter>('all');

  const a = account.data;

  /** 이자·세금은 월 1회라 최근 거래에 묻힌다. 따로 모아 요약으로 보여준다. */
  const interest = useMemo(() => {
    const rows = (a?.transactions ?? []).filter((t) => t.description.includes('이자'));
    const got = rows.filter((t) => t.type === 'DEPOSIT').reduce((s, t) => s + t.amount, 0);
    const tax = rows.filter((t) => t.type === 'WITHDRAWAL').reduce((s, t) => s + t.amount, 0);
    const rate = rows.find((t) => t.type === 'DEPOSIT')?.description.match(/[\d.]+%/)?.[0] ?? null;
    return { got, tax, rate, count: rows.filter((t) => t.type === 'DEPOSIT').length };
  }, [a]);

  /** 월별로 묶어 본다 — 거래내역 화면과 같은 방식이라 읽는 법이 같다. */
  const months = useMemo(() => {
    const rows = (a?.transactions ?? []).filter((t) => filter === 'all' || t.type === filter);
    const by: Record<string, typeof rows> = {};
    for (const t of rows) (by[t.date.slice(0, 7)] ??= []).push(t);
    return Object.keys(by).sort((x, y) => y.localeCompare(x)).map((m) => ({ key: m, rows: by[m] }));
  }, [a, filter]);

  return (
    <Screen title="내 통장" hasTabBar>
      <AppBar onBack={back} title="내 통장" />
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>
        <ErrorBox error={account.error} onRetry={account.reload} />
        {account.loading && <Loading label="통장을 불러오는 중" rows={5} />}

        {!account.loading && !a && !account.error && (
          <div className="card">
            <Empty>연결된 은행이 없어요. 마이 &gt; 연결 관리에서 은행을 연결하면 통장이 보여요.</Empty>
            <button type="button" className="btn btn-ghost btn-sm" style={{ width: '100%', marginTop: 10 }}
              onClick={() => go('m-connections')}>연결 관리로 가기</button>
          </div>
        )}

        {a && (
          <>
            {/* 통장 한 장 — 카드면(.mc-face)과 같은 인상으로 자산을 한눈에 */}
            <div className="mc-face" style={{ background: 'linear-gradient(135deg,#2C74DB,#1B64DA)' }}>
              <span className="co">{a.bank}</span>
              <span className="nm">{a.product}</span>
              <span className="sn">{maskAccount(a.accountNumber)}</span>
              <b style={{ fontSize: 26, marginTop: 10 }}>{won(a.balance)}</b>
            </div>

            <div className="card">
              <div className="asset-row">
                <div className="asset"><b style={{ color: 'var(--green-t)' }}>{won(a.salary)}</b><span>월급</span></div>
                <div className="asset"><b>매월 {a.payday}일</b><span>급여일</span></div>
              </div>
              <p className="empty" style={{ marginTop: 4, marginBottom: 0 }}>
                <b>{a.salaryPayer}</b>에서 급여가 들어와요.
              </p>
            </div>

            {interest.count > 0 && (
              <>
                <SectionTitle aux={interest.rate ? `연 ${interest.rate}` : undefined}>받은 이자</SectionTitle>
                <div className="card">
                  <div className="asset-row">
                    <div className="asset"><b style={{ color: 'var(--green-t)' }}>{won(interest.got)}</b><span>이자 {interest.count}회</span></div>
                    <div className="asset"><b style={{ color: 'var(--red-t)' }}>−{won(interest.tax)}</b><span>이자소득세</span></div>
                    <div className="asset"><b>{won(interest.got - interest.tax)}</b><span>실수령</span></div>
                  </div>
                  <p className="empty" style={{ marginTop: 4, marginBottom: 0 }}>
                    이자는 매달 그때의 잔액에 붙어요. 적게 쓴 달일수록 더 붙습니다.
                    소득세 15.4%(소득세 14% + 지방소득세 1.4%)는 입금 직후 원천징수돼요.
                  </p>
                </div>
              </>
            )}

            <SectionTitle aux={`${a.transactions.length}건`}>입출금 내역</SectionTitle>
            <div className="seg" role="tablist" aria-label="입출금 구분" style={{ marginBottom: 10 }}>
              {([['all', '전체'], ['DEPOSIT', '입금'], ['WITHDRAWAL', '출금']] as const).map(([v, label]) => (
                <button key={v} type="button" role="tab" aria-selected={filter === v}
                  className={filter === v ? 'on' : undefined} onClick={() => setFilter(v)}>{label}</button>
              ))}
            </div>

            {months.length === 0 && <div className="card"><Empty>해당하는 내역이 없어요.</Empty></div>}
            {months.map((m) => (
              <div className="card" key={m.key} style={{ padding: '14px 18px' }}>
                <div className="month-head"><b>{monthLabel(m.key)}</b><span className="muted small">{m.rows.length}건</span></div>
                {m.rows.map((t, i) => (
                  <div className="txn" key={`${m.key}-${i}`}>
                    <span className="d">{shortDate(t.date)}</span>
                    <span className="m">{t.description}</span>
                    <span className="a" style={{ color: t.type === 'DEPOSIT' ? 'var(--green-t)' : undefined }}>
                      {t.type === 'DEPOSIT' ? '+' : '−'}{won(t.amount)}
                    </span>
                  </div>
                ))}
              </div>
            ))}
          </>
        )}

        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
