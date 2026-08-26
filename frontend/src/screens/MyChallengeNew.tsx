/**
 * 마이 &gt; 챌린지 관리 &gt; 새 챌린지 만들기.
 *
 * <p><b>위에서 고르고, 아래에서 이미 하고 있는 것을 본다.</b> 무엇을 더할지 정하려면 지금
 * 무엇을 하고 있는지가 같은 화면에 있어야 한다 — 안 보이면 이미 줄이는 곳을 또 고르거나,
 * 성역으로 둔 곳을 고른 뒤 저장 단계에서야 막힌다.
 *
 * <p><b>한 번에 하나만 더한다.</b> 여럿을 한꺼번에 얹으면 예산이 갑자기 넓어져, 지금까지
 * 지켜온 페이스가 무슨 뜻이었는지 알 수 없게 된다.
 *
 * <p>새 챌린지를 따로 만들지 않고 <b>진행 중인 것에 붙인다</b>. 카테고리마다 기간이 제각각이면
 * "이번 달"이라는 말이 뜻을 잃고 월말 결산도 여러 번 일어난다.
 */
import { useState } from 'react';
import { Icon } from '../components/Icons';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Cta } from '../components/ui';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api, catLabel, isUnknownCategory } from '../lib/api';
import { won, iconOf } from '../lib/format';

export function MyChallengeNew() {
  const { back, userId, openChallenge } = useSession();
  const { home, reload } = useGuardian();
  const rows = useAsync(() => api.guardian.challengeCategories(userId), [userId]);
  const cats = useAsync(() => api.categories().catch(() => []), [userId]);
  const [picked, setPicked] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  if (rows.loading && !rows.data) return <Loading label="불러오는 중" />;

  const doing = new Set((rows.data ?? []).map((r) => r.category));
  const sanctuary = new Set(home?.challenge?.sanctuaryCategories ?? []);
  // 이미 줄이는 곳과 성역은 후보에서 뺀다 — 고를 수 없는 것을 목록에 두면 왜 안 되는지 묻게 된다.
  const options = (cats.data ?? []).filter(
    // **모르는 칸은 고를 수 없다.** 목표를 걸어도 무엇을 줄여야 하는지 말해 줄 수 없다.
    (c) => !doing.has(c.code) && !sanctuary.has(c.code) && !isUnknownCategory(c.code));

  async function add() {
    if (!picked) return;
    setBusy(true); setError(null);
    try {
      await api.guardian.addChallengeCategory(userId, picked);
      await reload();
      openChallenge(picked);   // 더한 뒤에는 그 카테고리의 강도를 정하러 간다
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  }

  return (
    <Screen title="새 챌린지 만들기">
      <AppBar onBack={back} title="새 챌린지 만들기" />
      <Scroll><div className="pad">
        <p className="h-title">어떤 지출을<br />더 줄여볼까요?</p>
        <p className="h-sub">
          지금 챌린지에 하나를 더해요. 기간은 그대로라 <b>같은 날 함께 끝나요</b>.
        </p>

        <ErrorBox error={error} onRetry={() => setError(null)} />

        {!home?.challenge ? (
          <div className="card"><p className="empty" style={{ margin: 0 }}>
            진행 중인 챌린지가 없어요. 온보딩에서 먼저 시작해 주세요.
          </p></div>
        ) : (
          <>
            <div className="chips" style={{ gap: 12 }}>
              {options.map((c) => {
                const on = picked === c.code;
                const { icon } = iconOf(c.displayName);
                return (
                  <button type="button" key={c.code} className={`chip${on ? ' on' : ''}`}
                    aria-pressed={on} onClick={() => setPicked(on ? null : c.code)}>
                    <Icon id={icon} className="ci" />{catLabel(c.code, c.displayName)}
                  </button>
                );
              })}
              {options.length === 0 && (
                <p className="empty">더할 수 있는 카테고리가 없어요 — 이미 다 고르셨거나 성역이에요.</p>
              )}
            </div>

            {/* ── 이미 줄이고 있는 것 ── */}
            <p className="label" style={{ marginTop: 28 }}>이미 줄이고 있어요</p>
            <div className="card menu" style={{ padding: '8px 20px' }}>
              {(rows.data ?? []).map((r) => {
                const { icon, bg } = iconOf(r.label);
                return (
                  <button type="button" key={r.category} className="list-item"
                    onClick={() => openChallenge(r.category)}>
                    <span className="ic" style={{ background: bg }}><Icon id={icon} /></span>
                    <div className="tx">
                      <b>{r.label} 줄이기</b>
                      <span>이번 달 {won(r.cap)}까지 · 지금까지 {won(r.spent)}</span>
                    </div>
                    <span className="arrow" aria-hidden="true">›</span>
                  </button>
                );
              })}
              {(rows.data ?? []).length === 0 && (
                <p className="empty" style={{ margin: '8px 0' }}>아직 없어요.</p>
              )}
            </div>
          </>
        )}
        <div className="spacer" />
      </div></Scroll>
      {home?.challenge && (
        <Cta>
          <button type="button" className="btn btn-primary" disabled={!picked || busy}
            onClick={() => void add()}>
            {busy ? '더하는 중…' : picked ? `${catLabel(picked)} 줄이기 시작` : '줄일 곳을 골라주세요'}
          </button>
        </Cta>
      )}
    </Screen>
  );
}
