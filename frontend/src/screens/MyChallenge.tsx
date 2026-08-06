/**
 * 마이 &gt; 챌린지 관리 &gt; ○○ 줄이기 (프로토타입_0806 `s-my` 의 `myChal` 줄).
 *
 * <p><b>진행 중에도 강도를 바꿀 수 있어야 한다.</b> 한 달을 견딜 수 있는 강도는 해보기 전에는
 * 모른다. 처음 고른 값으로 못 박으면 빡빡하게 잡은 사람은 포기하고, 헐겁게 잡은 사람은
 * 아무것도 안 바뀐 채 한 달을 보낸다.
 *
 * <p><b>기준 지출은 못 바꾼다.</b> 그건 실측이라 사용자가 정할 값이 아니다 — 여기서 옮기는
 * 것은 "그중 얼마를 지킬까"뿐이고, 예산은 기준에서 그만큼을 뺀 나머지다.
 *
 * <p><b>이미 쓴 돈 아래로는 못 내린다.</b> 그러면 저장하는 순간 예산 초과가 되어 사용자가
 * 한 적 없는 실패가 만들어진다. 슬라이더의 바닥이 곧 지금까지 쓴 돈이다.
 */
import { useEffect, useState } from 'react';
import { Icon } from '../components/Icons';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Cta } from '../components/ui';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api, type ChallengeCategory } from '../lib/api';
import { won, iconOf } from '../lib/format';

/** 강도 눈금 — 기준 지출의 몇 %를 지킬까. 개편안 ob4 의 세 단계와 같은 값이다. */
const TIERS = [
  { key: 'soft', label: '조금', ratio: 0.2, caption: '기준의 20%만 아껴요 · 부담 적음' },
  { key: 'mid', label: '적당히', ratio: 0.5, caption: '기준의 절반을 아껴요 · 균형' },
  { key: 'hard', label: '확실히', ratio: 0.8, caption: '기준의 80%를 아껴요 · 도전' },
];

export function MyChallenge() {
  const { back, userId, challengeCategory } = useSession();
  const { reload } = useGuardian();
  const rows = useAsync(() => api.guardian.challengeCategories(userId), [userId]);
  const [target, setTarget] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [saved, setSaved] = useState(false);

  const row: ChallengeCategory | undefined =
    rows.data?.find((r) => r.category === challengeCategory) ?? rows.data?.[0];

  useEffect(() => { if (row) setTarget(row.target); }, [row?.category, row?.target]);

  if (rows.loading && !rows.data) return <Loading label="챌린지를 불러오는 중" />;
  if (!row) {
    return (
      <Screen title="챌린지 관리">
        <AppBar onBack={back} title="챌린지 관리" />
        <div className="pad">
          <ErrorBox error={rows.error} onRetry={rows.reload} />
          <div className="card"><p className="empty" style={{ margin: 0 }}>
            진행 중인 챌린지가 없어요.
          </p></div>
        </div>
      </Screen>
    );
  }

  const { icon, bg } = iconOf(row.label);
  // 지킬 돈의 천장 = 기준 − 이미 쓴 돈. 그 아래로는 저장하는 순간 예산 초과다.
  const maxTarget = Math.max(0, row.baseline - row.spent);
  const cur = target ?? row.target;
  const dirty = cur !== row.target;

  async function save() {
    setBusy(true); setError(null);
    try {
      await api.guardian.retarget(userId, row!.category, cur);
      rows.reload();
      await reload();
      setSaved(true);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Screen title={`${row.label} 줄이기`}>
      <AppBar onBack={back} title={`${row.label} 줄이기`} />
      <Scroll><div className="pad">
        <div className="list-item" style={{ padding: '4px 0 16px' }}>
          <span className="ic" style={{ background: bg }}><Icon id={icon} /></span>
          <div className="tx">
            <b>{row.label}</b>
            <span>기준 지출 {won(row.baseline)} · 지금까지 {won(row.spent)} 썼어요</span>
          </div>
        </div>

        <ErrorBox error={error} onRetry={() => setError(null)} />

        {/* ── 강도 세 단계 ── */}
        <div className="int-seg">
          {TIERS.map((t) => {
            const v = Math.round(row.baseline * t.ratio);
            const on = cur === v;
            return (
              <button type="button" key={t.key} className={on ? 'on' : undefined}
                disabled={v > maxTarget} aria-pressed={on}
                onClick={() => { setTarget(v); setSaved(false); }}>
                <b>{t.label}</b>
                <span>{won(v)}</span>
              </button>
            );
          })}
        </div>
        <p className="int-caption">
          {TIERS.find((t) => cur === Math.round(row.baseline * t.ratio))?.caption
            ?? '직접 설정 중 — 원하는 만큼 맞춰보세요'}
        </p>

        {/* ── 직접 조절 ── */}
        <div className="goal-card">
          <div className="gh-head">
            <div className="gh-cap">이번 달 지킬 돈</div>
            <div className="gh-num">{won(cur)}</div>
          </div>
          <div className="list-item">
            <div className="tx">
              <b>남는 예산</b>
              <span>기준에서 지킬 돈을 뺀 나머지예요</span>
            </div>
            <span className="amt">{won(Math.max(0, row.baseline - cur))}</span>
          </div>
          <input type="range" className="slider" min={0} max={maxTarget}
            step={Math.max(1000, Math.round(row.baseline / 100))} value={cur}
            aria-label={`${row.label} 지킬 돈`}
            onChange={(e) => { setTarget(Number(e.target.value)); setSaved(false); }} />
          <p className="pv" style={{ margin: '8px 0 12px' }}>
            상한은 기준 지출 <b>{won(row.baseline)}</b>이에요 — 쓰는 것보다 더 아낄 순 없으니까요.
            {row.spent > 0 && <> 이미 {won(row.spent)}을 써서 그만큼은 뺀 값까지만 고를 수 있어요.</>}
          </p>
        </div>

        {saved && !dirty && (
          <p className="pv" role="status" style={{ color: 'var(--green-t)' }}>
            저장했어요. 오늘부터 이 기준으로 지켜볼게요.
          </p>
        )}
        <div className="spacer" />
      </div></Scroll>
      <Cta>
        <button type="button" className="btn btn-primary" disabled={!dirty || busy}
          onClick={() => void save()}>
          {busy ? '저장하는 중…' : dirty ? '이대로 저장' : '바뀐 것이 없어요'}
        </button>
      </Cta>
    </Screen>
  );
}
