/**
 * 마이 &gt; 연결 관리 (MD-05) — 연결한 기관과 마지막 상태를 보고, 새 결제를 당겨오거나
 * 기관을 더 연결한다. 결제·송금 권한은 애초에 요구하지 않으므로 여기에 그런 항목이 없다.
 */
import { useMemo, useState } from 'react';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty, SectionTitle } from '../components/ui';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { resetAutoSyncThrottle } from '../state/autoSync';
import { useAsync } from '../state/useAsync';
import { api } from '../lib/api';
import { brandOf, logoOf } from '../lib/institutions';
import { won } from '../lib/format';

/**
 * 기관 로고 한 칸 — 연결 화면(`s-connect`)과 <b>같은 규칙</b>으로 그린다.
 *
 * <p>이 화면은 로고 이미지를 안 쓰고 브랜드색 원에 첫 글자만 찍고 있었다. 같은 은행이
 * 연결 화면에서는 CI 로, 연결 관리에서는 글자 배지로 나와 다른 곳처럼 보였다.
 * 그림이 없는 기관만 글자 배지로 떨어진다.
 */
function InstLogo({ name }: { name: string }) {
  const logo = logoOf(name);
  const b = brandOf(name);
  return (
    <span className="inst-logo"
      style={logo ? undefined : { background: b.bg, color: b.fg ?? '#fff' }}
      aria-hidden="true">
      {logo ? <img src={logo} alt="" loading="lazy" /> : b.label}
    </span>
  );
}

export function MyConnections() {
  const { back, userId, go } = useSession();
  const { reload: reloadGuardian } = useGuardian();
  const cards = useAsync(() => api.myCards(userId), [userId]);
  const companies = useAsync(() => api.mydataCompanies().catch(() => []), []);
  const banks = useAsync(() => api.mydataBanks().catch(() => []), []);
  const myBanks = useAsync(() => api.myBanks(userId).catch(() => []), [userId]);
  const [busy, setBusy] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);

  /** 연결된 카드사 = 불러온 카드들의 발급사 집합. */
  const connected = useMemo(() => {
    const by = new Map<string, number>();
    (cards.data ?? []).forEach((c) => by.set(c.companyName, (by.get(c.companyName) ?? 0) + 1));
    return [...by.entries()].sort((a, b) => a[0].localeCompare(b[0]));
  }, [cards.data]);

  const notConnected = useMemo(() => {
    const names = new Set(connected.map(([n]) => n));
    return (companies.data ?? []).filter((c) => !names.has(c.name));
  }, [companies.data, connected]);

  async function sync() {
    setBusy('sync'); setMsg(null); setError(null);
    try {
      const r = await api.syncMyData(userId);
      setMsg(r.newPayments > 0 ? `새 결제 ${r.newPayments}건을 불러왔어요` : '이미 최신 상태예요');
      cards.reload();
      await reloadGuardian();
    } catch (e) { setError(e); } finally { setBusy(null); }
  }

  async function linkAll() {
    setBusy('link'); setMsg(null); setError(null);
    try {
      const ids = (companies.data ?? []).map((c) => c.id);
      const bankIds = (banks.data ?? []).map((b) => b.id);
      const r = await api.mydataLink(userId, ids, bankIds);
      resetAutoSyncThrottle(); // 방금 새로 연결했다 — 자동 동기화가 스로틀에 걸려 쉬면 안 된다
      setMsg(`카드 ${r.cardCount}장 · 결제 ${r.paymentCount.toLocaleString('ko-KR')}건`
        + `${r.bankCount > 0 ? ` · 통장 ${r.bankCount}개` : ''}를 불러왔어요`);
      cards.reload();
      myBanks.reload();
      await reloadGuardian();
    } catch (e) { setError(e); } finally { setBusy(null); }
  }

  return (
    <Screen title="연결 관리" hasTabBar>
      <AppBar onBack={back} title="연결 관리" />
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>
        <p className="h-sub" style={{ margin: '0 0 12px' }}>
          카드 이용내역·승인내역과 입출금 통장만 가져와요. 결제·송금 권한은 포함되지 않아요.
        </p>

        <ErrorBox error={cards.error ?? error} onRetry={cards.reload} />
        {msg && <p className="notice-ok" role="status">{msg}</p>}
        {cards.loading && <Loading label="연결 상태를 불러오는 중" rows={4} />}

        <SectionTitle aux={`${connected.length}개 기관`}>연결된 기관</SectionTitle>
        <div className="card" style={{ padding: '6px 18px' }}>
          {connected.length === 0 ? (
            <Empty>아직 연결된 기관이 없어요. 아래에서 연결해 보세요.</Empty>
          ) : connected.map(([name, count]) => {
            return (
              <div className="list-item" key={name} style={{ padding: '13px 0', borderBottom: '1px solid var(--bg)' }}>
                <InstLogo name={name} />
                <div className="tx"><b>{name}</b><span>카드 {count}장 연결됨</span></div>
                <span className="aux-badge green">정상</span>
              </div>
            );
          })}
        </div>

        {(myBanks.data ?? []).length > 0 && (
          <>
            <SectionTitle aux={`${myBanks.data!.length}곳`}>연결된 은행</SectionTitle>
            <div className="card" style={{ padding: '6px 18px' }}>
              {myBanks.data!.map((b) => {
                return (
                  <div className="list-item" key={b.id} style={{ padding: '13px 0', borderBottom: '1px solid var(--bg)' }}>
                    <InstLogo name={b.bankName} />
                    <div className="tx"><b>{b.bankName}</b><span>입출금 통장 연결됨</span></div>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => go('r-account')}>보기</button>
                  </div>
                );
              })}
            </div>
          </>
        )}

        <div style={{ display: 'flex', gap: 8 }}>
          <button type="button" className="btn btn-primary btn-sm" style={{ flex: 1 }}
            disabled={busy !== null} onClick={() => void sync()}>
            {busy === 'sync' ? '동기화 중…' : '새 결제 가져오기'}
          </button>
          <button type="button" className="btn btn-ghost btn-sm" style={{ flex: 1 }}
            disabled={busy !== null || (companies.data ?? []).length === 0} onClick={() => void linkAll()}>
            {busy === 'link' ? '연결 중…' : '전체 기관 다시 연결'}
          </button>
        </div>

        {notConnected.length > 0 && (
          <>
            <SectionTitle aux={`${notConnected.length}개`}>연결 가능한 기관</SectionTitle>
            <div className="card" style={{ padding: '6px 18px' }}>
              {notConnected.map((c) => {
                return (
                  <div className="list-item" key={c.id} style={{ padding: '13px 0', borderBottom: '1px solid var(--bg)' }}>
                    <InstLogo name={c.name} />
                    <div className="tx"><b>{c.name}</b><span>아직 연결하지 않았어요</span></div>
                  </div>
                );
              })}
              <p className="empty">위 ‘전체 기관 다시 연결’을 누르면 한 번에 연결돼요.</p>
            </div>
          </>
        )}

        <SectionTitle>내역 확인</SectionTitle>
        <div className="menu">
          <button type="button" className="menu-item" onClick={() => go('transactions')}>
            <span className="mi-ic" style={{ background: 'var(--blue-weak)' }} aria-hidden="true">🧾</span>
            <span className="mi-tx"><b>거래 내역</b><span>연결한 모든 카드의 결제를 월별로</span></span>
            <span className="chev" aria-hidden="true">›</span>
          </button>
          <button type="button" className="menu-item" onClick={() => go('r-cards')}>
            <span className="mi-ic" style={{ background: 'var(--c-taxi)' }} aria-hidden="true">💳</span>
            <span className="mi-tx"><b>내 카드</b><span>카드별 실적과 받은 혜택</span></span>
            <span className="chev" aria-hidden="true">›</span>
          </button>
        </div>

        {cards.data && cards.data.length > 0 && (
          <div className="pv">
            이번 달 사용 <b>{won(cards.data.reduce((s, c) => s + c.currentPerformance, 0))}</b> ·
            받은 혜택 <b>{won(cards.data.reduce((s, c) => s + c.earnedThisMonth, 0))}</b>
          </div>
        )}

        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
