/**
 * 포인트샵 (개편안 `s-shop`) — 가구·배경을 포인트로만 산다.
 *
 * <p><b>살 수 있는지는 서버가 판단한다.</b> 프론트에서 `points >= price`를 계산해 버튼을 켜면,
 * 다른 탭에서 포인트를 쓴 뒤 이 화면이 낡은 잔액을 들고 "구매하기"를 보여준다. 서버가 준
 * `affordable`을 그대로 믿고, 눌렀을 때의 최종 판정도 서버가 한다.
 *
 * <p>현금 충전 경로는 없다 — 포인트는 절약 행동으로만 모인다. 면제권 같은 아이템은 도감 보상
 * 전용이라 여기서 팔지 않는다.
 */
import { useEffect, useState } from 'react';
import { Icon } from '../components/Icons';
import { ItemGlyph } from '../components/ItemGlyph';
import { AppBar, Scroll, Screen, ErrorBox, Loading } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, type GuardianShop } from '../lib/api';

type Tab = 'FURNITURE' | 'BACKGROUND';

export function Shop() {
  const { back, userId, view, setView } = useSession();
  const state = useAsync(() => api.guardian.shop(userId), [userId]);
  const [data, setData] = useState<GuardianShop | null>(null);
  /**
   * <b>갈래는 주소가 정본이다</b>(`?tab=…`). `useState` 로 들면 뒤로가기가 이 자리를
   * 되살리지 못한다 — 리포트의 주간→월간이 그래서 이력에 한 칸도 안 쌓였고, 다른 화면에
   * 갔다 뒤로 오면 초기값으로 튕겼다(2026-08-25 신고).
   */
  const tab = (view.tab ?? 'FURNITURE') as Tab;
  const setTab = (next: Tab) => setView(next === 'FURNITURE' ? {} : { tab: next });
  const [toast, setToast] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  useEffect(() => { if (state.data) setData(state.data); }, [state.data]);

  // 토스트는 2.2초 뒤 스스로 사라진다(개편안과 같은 시간).
  useEffect(() => {
    if (!toast) return;
    const t = setTimeout(() => setToast(null), 2200);
    return () => clearTimeout(t);
  }, [toast]);

  if (state.loading && !data) return <Loading label="상점을 여는 중" />;
  if (state.error && !data) return <ErrorBox error={state.error} onRetry={state.reload} />;
  if (!data) return null;

  async function buy(code: string, name: string) {
    setBusy(code);
    try {
      setData(await api.guardian.buyItem(userId, code));
      setToast(`${name} 구매! 방에 배치했어요`);
    } catch (e) {
      setToast(e instanceof Error ? e.message : '지금은 살 수 없어요');
    } finally {
      setBusy(null);
    }
  }

  const shown = data.items.filter((i) => i.category === tab);

  return (
    <Screen id="shop" title="포인트샵">
      <AppBar
        title="포인트샵"
        onBack={back}
        action={
          <span className="steps" style={{ display: 'flex', alignItems: 'center', gap: 4 }}>
            <Icon id="i-coin" size={16} />
            <span>{data.points}</span>P
          </span>
        }
      />
      <Scroll>
        <div className="pad" style={{ paddingTop: 12 }}>
          {/* 0818: 붙은 세그먼트가 아니라 **떨어진 칩**이다. 항목이 늘어도 줄이 안 깨진다. */}
          <div className="fchip" style={{ marginBottom: 16 }}>
            <button className={tab === 'FURNITURE' ? 'on' : ''} onClick={() => setTab('FURNITURE')}>가구</button>
            <button className={tab === 'BACKGROUND' ? 'on' : ''} onClick={() => setTab('BACKGROUND')}>배경</button>
          </div>

          <div className="shop-grid">
            {shown.map((it) => (
              <div className="shop-card" key={it.code}>
                <ItemGlyph glyph={it.glyph} size={52} />
                <b>{it.name}</b>
                <span className="sprice">
                  <Icon id="i-coin" size={12} />
                  {it.price}P
                </span>
                {it.owned ? (
                  <button className="buy-btn owned" disabled>보유 중</button>
                ) : it.affordable ? (
                  <button className="buy-btn" disabled={busy === it.code} onClick={() => buy(it.code, it.name)}>
                    {busy === it.code ? '구매 중…' : '구매하기'}
                  </button>
                ) : (
                  <button className="buy-btn" disabled>{it.price - data.points}P 더 모으면</button>
                )}
              </div>
            ))}
          </div>

          <div className="pv" style={{ marginTop: 14 }}>
            포인트는 <b>절약 행동으로만</b> 모여요, 현금 충전은 없어요, 면제권 같은 아이템은 팔지 않아요(도감 보상 전용)
          </div>
          <div className="spacer" style={{ height: 32 }} />
        </div>
      </Scroll>
      {toast && <div className="mini-toast show">{toast}</div>}
    </Screen>
  );
}
