/**
 * RP 하위 — 내 통장 (§13-11 경제 모델).
 *
 * 카드가 '나가는 돈'이라면 통장은 '남는 돈'이다. 월급이 들어오고, 카드값이 빠지고, 매달 이자가
 * 붙고 그 자리에서 이자소득세가 떨어진다. 이 화면은 그 흐름을 그대로 보여준다.
 *
 * <b>잔액은 저장된 값이 아니다.</b> 제공자가 조회 시점에 계산해 준다
 * (초기잔액 + 월급누적 + 이자 − 세금 − 카드출금). 그래서 결제가 하나 들어오면 즉시 반영된다.
 *
 * <b>기간을 맞춘다.</b> 기본은 이번 달 전부다. 예전에는 출금만 최근 40건으로 자르고 월급·이자는
 * 개설일부터 전부 보여줘서, 몇 달 전 칸에 급여·이자만 덩그러니 놓였다 — 한 푼도 안 쓰고 이자만
 * 받은 통장처럼 보였다. 지난 달들은 '이전 6개월 보기'로 한 번에 불러온다.
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

type Filter = 'all' | 'DEPOSIT' | 'WITHDRAWAL';

export function ReportAccount() {
  const { back, userId, go, view, setView } = useSession();
  /** 1 = 이번 달, 7 = 이번 달 + 이전 6개월. 서버가 이 기간의 거래만 계산해 준다. */
  const [months, setMonths] = useState(1);
  const account = useAsync(() => api.account(userId, months), [userId, months]);
  /**
   * <b>갈래는 주소가 정본이다</b>(`?filter=…`). `useState` 로 들면 뒤로가기가 이 자리를
   * 되살리지 못한다 — 리포트의 주간→월간이 그래서 이력에 한 칸도 안 쌓였고, 다른 화면에
   * 갔다 뒤로 오면 초기값으로 튕겼다(2026-08-25 신고).
   */
  const filter = (view.filter ?? 'all') as Filter;
  const setFilter = (next: Filter) => setView(next === 'all' ? {} : { filter: next });
  /** 월 펼침 상태. 기본은 전부 닫힘 — 한 달에 300건 안팎이라 열어둘 이유가 없다. */
  const [openMonths, setOpenMonths] = useState<Record<string, boolean>>({});

  const a = account.data;

  /** 월별로 묶는다. 각 줄에 건수와 입·출금 합계를 적어, 열지 않고도 그 달의 윤곽이 보이게 한다. */
  const groups = useMemo(() => {
    const rows = (a?.transactions ?? []).filter((t) => filter === 'all' || t.type === filter);
    const by: Record<string, typeof rows> = {};
    for (const t of rows) (by[t.date.slice(0, 7)] ??= []).push(t);
    return Object.keys(by).sort((x, y) => y.localeCompare(x)).map((m) => ({
      key: m,
      rows: by[m],
      inOut: by[m].reduce(
        (s, t) => (t.type === 'DEPOSIT' ? { ...s, in: s.in + t.amount } : { ...s, out: s.out + t.amount }),
        { in: 0, out: 0 },
      ),
    }));
  }, [a, filter]);

  const toggleMonth = (key: string) => setOpenMonths((p) => ({ ...p, [key]: !p[key] }));

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
              <span className="sn">{a.accountNumber}</span>
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

            <SectionTitle aux={months === 1 ? '이번 달' : '최근 7개월'}>입출금 내역</SectionTitle>
            <div className="seg" role="tablist" aria-label="입출금 구분" style={{ marginBottom: 10 }}>
              {([['all', '전체'], ['DEPOSIT', '입금'], ['WITHDRAWAL', '출금']] as const).map(([v, label]) => (
                <button key={v} type="button" role="tab" aria-selected={filter === v}
                  className={filter === v ? 'on' : undefined} onClick={() => setFilter(v)}>{label}</button>
              ))}
            </div>

            {groups.length === 0 && <div className="card"><Empty>해당하는 내역이 없어요.</Empty></div>}
            {groups.map((m) => {
              const open = !!openMonths[m.key];
              return (
                <div className="card" key={m.key} style={{ padding: '14px 18px' }}>
                  <button type="button" className="month-head" aria-expanded={open}
                    onClick={() => toggleMonth(m.key)}
                    style={{ width: '100%', background: 'none', border: 0, borderBottom: '1px solid var(--line)',
                             font: 'inherit', cursor: 'pointer', textAlign: 'left' }}>
                    <b>{monthLabel(m.key)}</b>
                    <span className="muted small">
                      {m.rows.length}건 · <em style={{ color: 'var(--green-t)', fontStyle: 'normal' }}>+{won(m.inOut.in)}</em>
                      {' '}<em style={{ color: 'var(--red-t)', fontStyle: 'normal' }}>−{won(m.inOut.out)}</em>
                      {' '}{open ? '▲' : '▼'}
                    </span>
                  </button>
                  {/* 실제 통장의 두 칸 — 위가 적요(상대·성격), 아래가 비고(취급점·채널). */}
                  {open && m.rows.map((t, i) => (
                    <div className="txn" key={`${m.key}-${i}`} style={{ alignItems: 'flex-start' }}>
                      {/* 날짜 아래에 시각 — 한 줄로 붙이면 적요가 밀려 좁아진다. */}
                      <span className="d" style={{ width: 46, flex: '0 0 auto', lineHeight: 1.35 }}>
                        {shortDate(t.date)}
                        <span style={{ display: 'block', fontSize: 10.5, color: 'var(--t3)' }}>
                          {t.date.slice(11, 16)}
                        </span>
                      </span>
                      <span className="m" style={{ whiteSpace: 'normal' }}>
                        {t.description}
                        {t.note && (
                          <span style={{ display: 'block', fontSize: 11, color: 'var(--t3)', fontWeight: 600 }}>
                            {t.note}
                          </span>
                        )}
                      </span>
                      <span className="a" style={{ textAlign: 'right' }}>
                        <em style={{ fontStyle: 'normal', color: t.type === 'DEPOSIT' ? 'var(--green-t)' : undefined }}>
                          {t.type === 'DEPOSIT' ? '+' : '−'}{won(t.amount)}
                        </em>
                        {/* 잔액을 함께 적는다 — 통장을 보는 이유는 "그래서 지금 얼마인가"다. */}
                        <span style={{ display: 'block', fontSize: 11, color: 'var(--t3)', fontWeight: 600 }}>
                          {won(t.balanceAfter)}
                        </span>
                      </span>
                    </div>
                  ))}
                </div>
              );
            })}

            {months === 1 && (
              <button type="button" className="btn btn-ghost btn-sm" style={{ width: '100%', marginTop: 10 }}
                disabled={account.loading} onClick={() => setMonths(7)}>
                {account.loading ? '불러오는 중…' : '이전 6개월 보기'}
              </button>
            )}
          </>
        )}

        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
