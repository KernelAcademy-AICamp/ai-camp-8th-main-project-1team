/**
 * 마이 &gt; 설정 &gt; 성역 관리 (프로토타입_0806 `s-my` 설정 2번째 줄).
 *
 * <p><b>성역은 약속이지 설정이 아니다.</b> "여기는 안 건드릴게요"라고 정한 곳이라, 지킴이는
 * 그 카테고리 결제에 침묵하고 챌린지 집계에서도 뺀다. 그래서 <b>바꿀 수 있어야 한다</b> —
 * 챌린지를 만들 때 한 번만 고를 수 있으면 잘못 고른 사람은 한 달을 견뎌야 한다.
 *
 * <p><b>줄이기로 한 곳은 성역이 될 수 없다.</b> 둘 다이면 "줄이라고 하면서 침묵한다"가 되어
 * 앞뒤가 안 맞는다. 그 칸은 눌리지 않게 두고 왜 안 되는지 그 자리에 적는다 — 서버도 같은
 * 판정을 하지만, 눌러 보고 나서야 알게 하면 안 된다.
 */
import { useEffect, useState } from 'react';
import { Icon } from '../components/Icons';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Cta } from '../components/ui';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api, catLabel } from '../lib/api';
import { iconOf } from '../lib/format';

export function MySanctuary() {
  const { back, userId } = useSession();
  const { home, loading, reload } = useGuardian();
  const ch = home?.challenge;
  const cats = useAsync(() => api.categories().catch(() => []), [userId]);

  const [picked, setPicked] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [saved, setSaved] = useState(false);

  // 서버가 준 값으로 시작한다 — 화면에서 만든 값을 기준으로 삼으면 새로고침마다 달라진다.
  useEffect(() => { if (ch) setPicked(ch.sanctuaryCategories); }, [ch]);

  /**
   * <b>기다리는 것과 없는 것을 가른다.</b> 예전에는 `!home` 하나로 판단해서, 진행 중인
   * 챌린지가 없으면(서버가 404) 화면이 <b>영원히 "불러오는 중"</b>이었다. 성역은 챌린지에
   * 딸린 약속이라 챌린지가 없으면 고를 것도 없다 — 아래의 `!ch` 빈 상태가 그 말을 한다.
   * (렌더링 감사의 '화면이 그려지지 않는다'가 잡았다, 2026-08-20)
   */
  if (loading) return <Loading label="불러오는 중" />;

  const cutting = new Set(ch?.categories ?? []);
  const dirty = ch != null
    && [...picked].sort().join(',') !== [...ch.sanctuaryCategories].sort().join(',');

  function toggle(code: string) {
    setSaved(false);
    setPicked((p) => (p.includes(code) ? p.filter((k) => k !== code) : [...p, code]));
  }

  async function save() {
    setBusy(true); setError(null);
    try {
      await api.guardian.setSanctuary(userId, picked);
      await reload();
      setSaved(true);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Screen title="성역 관리">
      <AppBar onBack={back} title="성역 관리" />
      <Scroll><div className="pad">
        <p className="h-title">절대 안 건드릴 소비</p>
        <p className="h-sub">
          고른 소비는 지킴이가 <b style={{ color: 'var(--green-t)' }}>침묵</b>해요.
          이번 챌린지의 집계에서도 빠져요.
        </p>

        <ErrorBox error={error} onRetry={() => setError(null)} />

        {!ch ? (
          <div className="card"><p className="empty" style={{ margin: 0 }}>
            진행 중인 챌린지가 없어요. 챌린지를 시작하면 성역을 정할 수 있어요.
          </p></div>
        ) : (
          <>
            <div className="chips">
              {(cats.data ?? []).map((c) => {
                const on = picked.includes(c.code);
                const blocked = cutting.has(c.code);
                const { icon } = iconOf(c.displayName);
                return (
                  <button type="button" key={c.code} disabled={blocked}
                    className={`chip sanctuary${on ? ' on' : ''}`} aria-pressed={on}
                    aria-label={blocked ? `${catLabel(c.code, c.displayName)} — 이번에 줄이기로 한 곳이라 고를 수 없어요` : undefined}
                    onClick={() => toggle(c.code)}>
                    <Icon id={icon} className="ci" />{catLabel(c.code, c.displayName)}
                  </button>
                );
              })}
              {cats.data?.length === 0 && <p className="empty">카테고리 목록을 불러오지 못했어요.</p>}
            </div>

            {cutting.size > 0 && (
              <p className="pv">
                <b>{[...cutting].map((c) => catLabel(c)).join(' · ')}</b>은(는) 이번에 줄이기로 한 곳이라
                성역으로 둘 수 없어요. 다음 챌린지에서 바꿀 수 있어요.
              </p>
            )}
            {saved && !dirty && (
              <p className="pv" role="status" style={{ color: 'var(--green-t)' }}>
                저장했어요. 지금부터 그 소비에는 조용히 있을게요.
              </p>
            )}
          </>
        )}
        <div className="spacer" />
      </div></Scroll>
      {ch && (
        <Cta>
          <button type="button" className="btn btn-primary" disabled={!dirty || busy}
            onClick={() => void save()}>
            {busy ? '저장하는 중…' : dirty ? '이대로 저장' : '바뀐 것이 없어요'}
          </button>
        </Cta>
      )}
    </Screen>
  );
}
