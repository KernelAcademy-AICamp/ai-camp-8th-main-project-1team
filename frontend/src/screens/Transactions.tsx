/**
 * TX-01 거래 내역 — 연결한 모든 카드의 결제를 월별로 모아 본다(§13-11).
 * 결제에 실린 사업자등록번호로 가맹점 주소를 눌러서 조회할 수 있다(§13).
 * 상단 '동기화'는 마이데이터에서 새 결제를 당겨오고 지킴이 원장에도 반영한다.
 */
import { useEffect, useMemo, useState } from 'react';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty } from '../components/ui';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { autoSyncMyData } from '../state/autoSync';
import { useAsync } from '../state/useAsync';
import { api, catLabel, type MyMerchant } from '../lib/api';
import { SpendCalendar } from '../components/SpendCalendar';
import { won, shortDate, monthLabel } from '../lib/format';

type SpendFilter = 'all' | 'disc' | 'fixed' | 'sanct';
/** 개편안의 필터 4종. '재량'은 성역·고정지출을 뺀 나머지다 — 줄일 수 있는 것만 남긴다. */
const SPEND_FILTERS: { key: SpendFilter; label: string }[] = [
  { key: 'all', label: '전체' },
  { key: 'disc', label: '재량' },
  { key: 'fixed', label: '고정지출' },
  { key: 'sanct', label: '성역' },
];
/** 고정지출로 보는 중분류 — 달마다 같은 금액이 나가 줄이기 어려운 것들. */
const FIXED_CATEGORIES = new Set(['주거/통신']);

/** 사업자등록번호 10자리 → XXX-YY-ZZZZZ 표시. */
const bizFmt = (b: string) => (b.length === 10 ? `${b.slice(0, 3)}-${b.slice(3, 5)}-${b.slice(5)}` : b);

export function Transactions() {
  const { back, userId } = useSession();
  const { home, reload: reloadGuardian } = useGuardian();
  const payments = useAsync(() => api.allPayments(userId, 6), [userId]);
  const [syncing, setSyncing] = useState(false);
  const [syncMsg, setSyncMsg] = useState<string | null>(null);
  const [merchantOf, setMerchantOf] = useState<Record<string, MyMerchant | 'loading'>>({});
  /** 달력에서 고른 날. null이면 전체 기간. */
  const [pickedDate, setPickedDate] = useState<string | null>(null);
  const [filter, setFilter] = useState<SpendFilter>('all');

  // 달력에 얹을 값 — 날짜별 지출 합계와 '지킨 날'.
  // 지킨 날은 지킴이가 판정한 사실이라 여기서 다시 계산하지 않고 홈이 준 잔디를 그대로 쓴다.
  const totalsByDate = useMemo(() => {
    const out: Record<string, number> = {};
    for (const p of payments.data ?? []) {
      const d = p.date.slice(0, 10);
      out[d] = (out[d] ?? 0) + p.amount;
    }
    return out;
  }, [payments.data]);
  const keptDates = useMemo(
    () => new Set((home?.grass ?? [])
      .filter((g) => g.result === 'NO_SPEND_DAY' || g.result === 'ON_PACE_DAY')
      .map((g) => g.date)),
    [home],
  );
  /** 성역·고정지출 판정에 쓸 카테고리 집합 — 챌린지가 정한 것을 그대로 본다. */
  const sanctuary = useMemo(() => new Set(home?.challenge?.sanctuaryCategories ?? []), [home]);

  const months = useMemo(() => {
    const all = payments.data ?? [];
    const rows = all.filter((p) => {
      if (pickedDate && p.date.slice(0, 10) !== pickedDate) return false;
      if (filter === 'all') return true;
      const sanct = p.category ? sanctuary.has(p.category) : false;
      if (filter === 'sanct') return sanct;
      const fixed = p.category ? FIXED_CATEGORIES.has(p.category) : false;
      if (filter === 'fixed') return fixed;
      return !sanct && !fixed;      // 재량 = 성역도 고정지출도 아닌 것
    });
    const byMonth: Record<string, typeof rows> = {};
    for (const p of rows) (byMonth[p.date.slice(0, 7)] ??= []).push(p);
    return Object.keys(byMonth).sort((a, b) => b.localeCompare(a)).map((m) => ({
      key: m,
      rows: byMonth[m].slice().sort((a, b) => b.date.localeCompare(a.date)),
      total: byMonth[m].reduce((s, p) => s + p.amount, 0),
    }));
  }, [payments.data, pickedDate, filter, sanctuary]);

  // 화면에 들어오면 새 결제를 조용히 당겨온다. 목록을 먼저 그리고 결과가 오면 그때 다시 부른다 —
  // 상단 '동기화' 버튼은 결과 문구가 필요한 수동 경로라 그대로 둔다.
  useEffect(() => {
    let alive = true;
    void autoSyncMyData(userId).then((n) => {
      if (n > 0 && alive) { payments.reload(); void reloadGuardian(); }
    });
    return () => { alive = false; };
    // payments.reload는 useAsync가 매 렌더 새로 만들 수 있어 의존성에 넣지 않는다(진입당 한 번).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId]);

  async function lookupMerchant(bizno: string) {
    if (merchantOf[bizno]) return;
    setMerchantOf((prev) => ({ ...prev, [bizno]: 'loading' }));
    try {
      const m = await api.merchant(bizno);
      setMerchantOf((prev) => {
        const next = { ...prev };
        if (m) next[bizno] = m; else delete next[bizno];
        return next;
      });
    } catch {
      setMerchantOf((prev) => { const next = { ...prev }; delete next[bizno]; return next; });
    }
  }

  async function doSync() {
    setSyncing(true); setSyncMsg(null);
    try {
      const r = await api.syncMyData(userId);
      setSyncMsg(r.newPayments > 0 ? `새 결제 ${r.newPayments}건을 불러왔어요` : '이미 최신 상태예요');
      if (r.newPayments > 0) { payments.reload(); await reloadGuardian(); }
    } catch (e) {
      setSyncMsg(e instanceof Error ? e.message : String(e));
    } finally {
      setSyncing(false);
    }
  }

  const total = payments.data?.length ?? 0;

  return (
    <Screen title="거래 내역" hasTabBar>
      <AppBar onBack={back} title="거래 내역" action={
        <button type="button" className="act" onClick={() => void doSync()} disabled={syncing}>
          {syncing ? '동기화 중…' : '동기화'}
        </button>
      } />
      {/* 달력 (개편안 `.cal`) — 날짜별 지출과 지킨 날. 누르면 그날만 본다. */}
      {home && (
        <SpendCalendar
          today={home.asOf.slice(0, 10)}
          totalsByDate={totalsByDate}
          keptDates={keptDates}
          selected={pickedDate}
          onSelect={setPickedDate}
        />
      )}
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>
        {/* 필터 칩 (개편안 `.fchips`) — 성역·고정지출을 걷어내고 '내가 줄일 수 있는 것'만 보는 용도. */}
        <div className="fchips">
          {SPEND_FILTERS.map((f) => (
            <button key={f.key} type="button" className={filter === f.key ? 'on' : ''}
              aria-pressed={filter === f.key} onClick={() => setFilter(f.key)}>
              {f.label}
            </button>
          ))}
        </div>
        {pickedDate && (
          <button type="button" className="btn btn-ghost btn-sm" style={{ marginBottom: 12 }}
            onClick={() => setPickedDate(null)}>
            {shortDate(pickedDate)}만 보는 중 · 전체 보기
          </button>
        )}
        <p className="h-sub" style={{ margin: '0 0 12px' }}>
          연결한 모든 카드의 최근 6개월 결제예요{total ? ` · 총 ${total.toLocaleString('ko-KR')}건` : ''}.
          {syncMsg && <span role="status" style={{ display: 'block', marginTop: 4, color: 'var(--blue-t)' }}>· {syncMsg}</span>}
        </p>

        <ErrorBox error={payments.error} onRetry={payments.reload} />
        {payments.loading && <Loading label="결제 내역을 불러오는 중" rows={6} />}
        {!payments.loading && total === 0 && !payments.error && (
          <div className="card"><Empty>불러온 결제내역이 없어요. 마이 &gt; 연결 관리에서 기관을 연결해 보세요.</Empty></div>
        )}

        {months.map((m) => (
          <div className="card" key={m.key} style={{ padding: '14px 18px' }}>
            <div className="month-head">
              <b>{monthLabel(m.key)}</b>
              <span className="muted small">{m.rows.length}건 · {won(m.total)}</span>
            </div>
            {m.rows.map((p) => {
              const found = p.businessNumber ? merchantOf[p.businessNumber] : undefined;
              return (
                <div key={p.paymentId} className="txn-item">
                  <div className="txn">
                    <span className="d">{shortDate(p.date)}</span>
                    <span className="m">{p.merchantName ?? catLabel(p.category2 ?? p.category)}</span>
                    {p.cardName && (
                      <span className="c" style={{ border: `1px solid ${p.cardColor || 'var(--line)'}`, color: p.cardColor || 'var(--t3)', background: 'transparent' }}>
                        {p.cardName}
                      </span>
                    )}
                    <span className="a">{won(p.amount)}</span>
                  </div>
                  {p.businessNumber && (
                    <div className="txn-biz">
                      {/* 보이는 글자는 번호뿐이지만, 눌러서 주소를 조회하는 컨트롤이라
                          화면낭독기에는 무엇을 하는 버튼인지 aria-label로 알려준다. */}
                      <button type="button" className="biz-link"
                        onClick={() => void lookupMerchant(p.businessNumber!)}
                        aria-label={`사업자등록번호 ${bizFmt(p.businessNumber)} — 가맹점 주소 조회`}>
                        {bizFmt(p.businessNumber)}
                      </button>
                      {found === 'loading' && <span className="biz-addr">주소 조회중…</span>}
                      {found && found !== 'loading' && (
                        <span className="biz-addr">📍 {found.address}{found.online ? ' (본사)' : ''}</span>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        ))}

        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
