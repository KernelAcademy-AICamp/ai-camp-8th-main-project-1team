/**
 * 마이 &gt; 낭비 판정 관리 — 느슨하게 보고 있는 가맹점을 보여주고 되돌린다.
 *
 * 온보딩에서 "이건 낭비가 아니다"를 누르면 그 가맹점의 판정이 느슨해진다. 그런데 그 판단은
 * <b>바뀔 수 있다</b> — 한동안 필수였던 지출이 다시 낭비가 되기도 한다. 되돌릴 자리가 없으면
 * 한 번 새어나간 지출이 영영 안 잡히고, 사용자는 자기가 무엇을 빼 뒀는지조차 모른다.
 *
 * 그래서 <b>목록으로 보여 주는 것 자체가 이 화면의 절반</b>이다. 되돌리기는 나머지 절반.
 */
import { useState } from 'react';
import { Icon } from '../components/Icons';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty, SectionTitle } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, type MerchantStance } from '../lib/api';
import { iconOf, shortDate } from '../lib/format';

const LABEL: Record<string, { text: string; desc: string; color: string }> = {
  LENIENT: {
    text: '확실할 때만',
    desc: '평소보다 확실히 클 때만 낭비로 알려드려요',
    color: 'var(--amber-t)',
  },
  EXCLUDED: {
    text: '낭비로 안 봄',
    desc: '이곳 결제는 줄일 소비로 세지 않아요',
    color: 'var(--t3)',
  },
};

export function MyStances() {
  const { back, userId } = useSession();
  const list = useAsync(() => api.merchantStances(userId), [userId]);
  const [busy, setBusy] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);

  const items: MerchantStance[] = list.data?.items ?? [];

  /**
   * 되돌리기 — 버튼 하나로 합쳤다.
   *
   * 예전에는 '역시 낭비였어요'(한 단계)와 '설정 지우기'(전부)를 나란히 뒀는데,
   * <b>LENIENT 상태에서는 둘의 결과가 같아서</b> 사용자가 구분할 수 없었다(사용자 지적 2026-07-31).
   * 내부의 '한 칸씩' 규칙을 버튼으로 노출한 것이 잘못이었다.
   *
   * 이제 하나만 둔다 — '낭비로 안 봄'이면 한 칸 내려 '확실할 때만'이 되고,
   * '확실할 때만'이면 기록째 지워 평소대로 돌아간다. 사용자는 <b>되돌린다</b>만 알면 되고,
   * 지금 어느 단계인지는 오른쪽 배지가 말해 준다.
   */
  async function revert(s: MerchantStance) {
    setBusy(s.businessNumber); setError(null); setMsg(null);
    const name = s.merchantName ?? '이 가맹점';
    try {
      if (s.stance === 'EXCLUDED') {
        await api.revertStance(userId, s.businessNumber);
        setMsg(`${name} — 이제 확실할 때만 알려드려요`);
      } else {
        await api.clearStance(userId, s.businessNumber);
        setMsg(`${name} — 이제 평소대로 판정해요`);
      }
      await list.reload();
    } catch (e) { setError(e); } finally { setBusy(null); }
  }

  return (
    <Screen title="낭비 판정 관리" hasTabBar>
      <AppBar onBack={back} title="낭비 판정 관리" />
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>
        <p className="h-sub" style={{ margin: '0 0 12px' }}>
          챌린지를 정할 때 <b>"이건 낭비가 아니에요"</b>로 빼신 곳이에요.
          생각이 바뀌면 언제든 되돌릴 수 있어요.
        </p>

        <ErrorBox error={error} />
        {msg && <p className="notice-ok" role="status">{msg}</p>}
        <ErrorBox error={list.error} onRetry={list.reload} />
        {list.loading && <Loading label="불러오는 중" rows={3} />}

        {!list.loading && items.length === 0 && !list.error && (
          <div className="card">
            <Empty>
              아직 빼 둔 곳이 없어요. 챌린지를 정할 때 줄일 소비를 펼쳐서
              &lsquo;낭비가 아닌 결제&rsquo;를 빼면 여기에 모여요.
            </Empty>
          </div>
        )}

        {items.length > 0 && (
          <>
            <SectionTitle aux={`${items.length}곳`}>느슨하게 보는 곳</SectionTitle>
            <div className="card" style={{ padding: '6px 18px' }}>
              {items.map((s, i) => {
                const meta = LABEL[s.stance] ?? LABEL.LENIENT;
                const name = s.merchantName ?? '가맹점 미상';
                const { icon, bg } = iconOf(name);
                return (
                  <div key={s.businessNumber}>
                    <div className="list-item" style={{ padding: '12px 0' }}>
                      <span className="ic" style={{ background: bg }}><Icon id={icon} /></span>
                      <div className="tx">
                        <b>{name}</b>
                        <span>{meta.desc}</span>
                      </div>
                      <span style={{ flex: '0 0 auto', textAlign: 'right' }}>
                        <b style={{ display: 'block', fontSize: 12.5, color: meta.color }}>{meta.text}</b>
                        <span style={{ fontSize: 11, color: 'var(--t3)' }}>{shortDate(s.updatedAt)}</span>
                      </span>
                    </div>
                    <div className="form-inline" style={{ margin: '0 0 10px' }}>
                      <button type="button" className="btn btn-ghost btn-sm" disabled={busy !== null}
                        onClick={() => void revert(s)}>
                        {busy === s.businessNumber ? '바꾸는 중…'
                          : s.stance === 'EXCLUDED' ? '다시 지켜볼래요' : '평소대로 되돌리기'}
                      </button>
                    </div>
                    {i < items.length - 1 && <div className="divider" />}
                  </div>
                );
              })}
            </div>
            <div className="pv" style={{ margin: '10px 0 0' }}>
              <b>낭비로 안 봄</b>에서 되돌리면 <b>확실할 때만</b>이 되고, 거기서 한 번 더 되돌리면
              평소대로 돌아가요. 한 번에 다 지우지 않는 건, 쌓아 오신 판단을 함부로 버리지 않으려는 거예요.
            </div>
          </>
        )}
        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
