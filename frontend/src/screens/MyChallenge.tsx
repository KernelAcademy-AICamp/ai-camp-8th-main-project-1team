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
 *
 * <p><b>'이건 낭비가 아니에요'가 여기로 왔다</b>(0818 개편). 예전에는 온보딩 마지막 화면에
 * 있었는데, 프로토타입_0818 이 온보딩을 한 화면으로 합치면서 그 자리가 없어졌다. 기능까지
 * 없앨 일은 아니다 — ML 판정은 완벽하지 않고(운영 실측 정밀도 0.689) 사용자가 빼는 절차가
 * 있어야 숫자를 믿을 수 있다. 오히려 <b>지내면서 다듬는 일</b>이라 처음 한 번뿐인 온보딩보다
 * 이 화면이 제자리다.
 */
import { useEffect, useMemo, useState } from 'react';
import { Icon } from '../components/Icons';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Cta } from '../components/ui';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api, type ChallengeCategory } from '../lib/api';
import { won, iconOf, shortDateTime } from '../lib/format';

/** 강도 눈금 — 기준 지출의 몇 %를 지킬까. 개편안 ob4 의 세 단계와 같은 값이다. */
const TIERS = [
  { key: 'soft', label: '조금', ratio: 0.2, caption: '기준의 20%만 아껴요 · 부담 적음' },
  { key: 'mid', label: '적당히', ratio: 0.5, caption: '기준의 절반을 아껴요 · 균형' },
  { key: 'hard', label: '확실히', ratio: 0.8, caption: '기준의 80%를 아껴요 · 도전' },
];

export function MyChallenge() {
  const { back, userId, challengeCategory, draft, patchDraft } = useSession();
  const { reload } = useGuardian();
  const rows = useAsync(() => api.guardian.challengeCategories(userId), [userId]);
  const [target, setTarget] = useState<number | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [saved, setSaved] = useState(false);
  /** 결제 목록은 접어 둔다 — 화면이 길어지면 슬라이더가 밀린다. */
  const [open, setOpen] = useState(false);

  const row: ChallengeCategory | undefined =
    rows.data?.find((r) => r.category === challengeCategory) ?? rows.data?.[0];

  /**
   * 그 카테고리에서 <b>낭비로 본 결제</b>. 온보딩이 담아 둔 창을 먼저 보고, 비어 있으면
   * (새로고침·다른 기기) 서버에 다시 묻는다 — 담긴 값에만 기대면 그 사람은 이 목록을
   * 영영 못 본다.
   */
  const win = useAsync(() => (draft.baseline[challengeCategory ?? ''] ? Promise.resolve(null)
    : api.onboardingWindow(userId).catch(() => null)), [userId, challengeCategory]);
  const picks = useMemo(() => {
    const code = row?.category ?? '';
    const fromDraft = draft.baseline[code]?.payments;
    const fromApi = win.data?.categories.find((c) => c.categoryCode === code)?.payments;
    return (fromDraft ?? fromApi ?? []).filter((p) => p.waste === true);
  }, [row?.category, draft.baseline, win.data]);
  const kept = new Set(draft.keptPaymentIds);
  const keptCount = picks.filter((p) => kept.has(p.paymentId)).length;
  const toggleKeep = (paymentId: string) => {
    const on = kept.has(paymentId);
    patchDraft({
      keptPaymentIds: on
        ? draft.keptPaymentIds.filter((k) => k !== paymentId)
        : [...draft.keptPaymentIds, paymentId],
    });
  };

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

        {/* ── 이건 낭비가 아니에요 ──
            왜 낭비로 봤는지 **확인할 수 있는 숫자로** 말한다. "평소보다 큰 금액"까지만 하면
            동의도 반박도 할 수 없다. "평소 23,000원 → 78,000원(3.4배)"이라야 "그날은
            회식이었다"고 답할 수 있고, 그 답이 곧 이 목록이 받으려는 신호다. */}
        {picks.length > 0 && (
          <>
            <button type="button" className="pick-toggle" aria-expanded={open}
              onClick={() => setOpen((v) => !v)}>
              <span>줄일 수 있는 소비 {picks.length}건
                {keptCount > 0 && <b> · {keptCount}건 뺐어요</b>}</span>
              <span className="chev" aria-hidden="true">{open ? '⌃' : '⌄'}</span>
            </button>
            {open && (
              <ul className="pick-list">
                {picks.map((p) => {
                  const off = kept.has(p.paymentId);
                  return (
                    <li key={p.paymentId}>
                      <button type="button" className={off ? 'pick off' : 'pick'}
                        aria-pressed={!off} onClick={() => toggleKeep(p.paymentId)}>
                        <span className="box" aria-hidden="true">{off ? '' : '✓'}</span>
                        <span className="d">{shortDateTime(p.date)}</span>
                        <span className="m">{p.merchantName ?? '가맹점 미상'}</span>
                        <span className="a">{won(p.amount)}</span>
                      </button>
                      {p.factors?.length > 0 && (
                        <ul className="why">
                          {p.factors.map((f, i) => (
                            <li key={i}><b>{f.label}</b>{f.detail && <span>{f.detail}</span>}</li>
                          ))}
                        </ul>
                      )}
                    </li>
                  );
                })}
              </ul>
            )}
          </>
        )}

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
