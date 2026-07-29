/**
 * 이번 챌린지 정하기 3/4 — CT-02 절약 강도 + CT-03 지킬 돈 확정.
 *
 * 지킬 돈 = 기준 지출 × 강도. 기준 지출·사용 한도 같은 내부값은 노출하지 않는다(IA §1.2).
 * 확정하면 `POST /api/guardian/challenges`로 실제 챌린지가 시작되고, 같은 선택을
 * ①의 절약 후보 추적(`/api/analysis/cut/choose`)에도 남겨 월말 재검증이 돌게 한다.
 */
import { useEffect, useRef, useState } from 'react';
import { Icon } from '../components/Icons';
import { AppBar, ProgressBar, Cta, Scroll, Screen, ErrorBox } from '../components/ui';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { ApiError, api } from '../lib/api';
import { CHALLENGE_DAYS } from '../lib/config';
import {
  won, iconOf, INTENSITY_TIERS, DEFAULT_INTENSITY,
  INTENSITY_MIN, INTENSITY_MAX, INTENSITY_STEP, round1,
} from '../lib/format';

export function Onboarding3() {
  const { go, back, userId, analysis, draft, patchDraft } = useSession();
  const { reload } = useGuardian();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const inited = useRef(false);

  const baseOf = (code: string) => draft.baseline[code]?.monthlyAmount ?? 0;
  const nameOf = (code: string) => draft.baseline[code]?.displayName ?? code;

  useEffect(() => {
    if (inited.current) return;
    inited.current = true;
    const next = { ...draft.intensities };
    let changed = false;
    draft.cutCats.forEach((n) => { if (next[n] == null) { next[n] = DEFAULT_INTENSITY; changed = true; } });
    if (changed) patchDraft({ intensities: next });
  }, [draft.cutCats, draft.intensities, patchDraft]);

  const setAll = (value: number) => {
    const next: Record<string, number> = { ...draft.intensities };
    draft.cutCats.forEach((n) => { next[n] = value; });
    patchDraft({ intensities: next });
  };
  const bump = (code: string, dir: number) => {
    const cur = draft.intensities[code] ?? DEFAULT_INTENSITY;
    patchDraft({
      intensities: {
        ...draft.intensities,
        [code]: round1(Math.min(INTENSITY_MAX, Math.max(INTENSITY_MIN, cur + dir * INTENSITY_STEP))),
      },
    });
  };

  const activeTier = INTENSITY_TIERS.find((t) =>
    draft.cutCats.length > 0 && draft.cutCats.every((n) => (draft.intensities[n] ?? DEFAULT_INTENSITY) === t.value));
  // 서버는 지킬 돈이 기준 지출보다 **작을 것**을 요구한다(한도가 0원이 되는 챌린지는 만들지 않는다).
  // 반올림 때문에 소액 카테고리에서 둘이 같아질 수 있어 한 칸 낮춰 둔다.
  //
  // 서버의 기준 지출은 월평균을 **챌린지 일수로 환산**한 값이라 여기 월평균과 살짝 다르다.
  // 환산비가 가장 불리한 경우(관측 달이 전부 31일)에도 30/31 ≈ 0.968이고 강도 상한은
  // INTENSITY_MAX(0.9)이므로, 여기서 만든 지킬 돈이 서버 기준을 넘어 400이 될 일은 없다.
  const baselineTotal = draft.cutCats.reduce((s, n) => s + baseOf(n), 0);
  const rawTotal = draft.cutCats.reduce(
    (s, n) => s + Math.round(baseOf(n) * (draft.intensities[n] ?? DEFAULT_INTENSITY)), 0);
  const total = baselineTotal > 0 ? Math.min(rawTotal, baselineTotal - 1) : 0;

  /** ① 절약 후보 추적에도 남긴다 — 후보가 아니면 서버가 거부하므로 조용히 넘어간다. */
  async function trackCutSelections() {
    const candidates = analysis?.cutCandidates ?? [];
    for (const code of draft.cutCats) {
      const display = nameOf(code);
      const hit = candidates.find((c) => c.category2.includes(display) || display.includes(c.category2));
      if (!hit) continue;
      await api.chooseCut(userId, hit.category2).catch(() => undefined);
    }
  }

  async function start() {
    if (total <= 0) { setError(new Error('지킬 돈이 0원이에요. 강도를 올리거나 다른 카테고리를 골라주세요.')); return; }
    setBusy(true); setError(null);
    try {
      await api.guardian.createChallenge(userId, {
        categories: draft.cutCats,
        sanctuaryCategories: draft.sanctuary,
        targetSaving: total,
        durationDays: CHALLENGE_DAYS,
      });
      await trackCutSelections();
      await reload();
      go('done');
    } catch (e) {
      // 이미 진행 중인 챌린지가 있으면(409) 새로 만들 게 아니라 홈으로 돌아가면 된다.
      if (e instanceof ApiError && e.status === 409) { await reload(); go('home'); return; }
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Screen title="절약 강도 선택">
      <AppBar onBack={back} steps="3 / 4" />
      <ProgressBar value={0.75} />
      <Scroll><div className="pad">
        <p className="h-title">얼마나<br />줄여볼까요?</p>
        <p className="h-sub">강도를 고르면 이번 {CHALLENGE_DAYS}일 동안 지킬 돈이 정해져요. 카테고리별로 다르게 잡아도 돼요.</p>

        <div className="int-seg" role="group" aria-label="절약 강도 프리셋">
          {INTENSITY_TIERS.map((t) => (
            <button type="button" key={t.key} className={activeTier?.key === t.key ? 'on' : ''}
              aria-pressed={activeTier?.key === t.key} onClick={() => setAll(t.value)}>
              <b>{t.label}</b><span>{Math.round(t.value * 100)}%</span>
            </button>
          ))}
        </div>
        <p className="int-caption">{activeTier ? activeTier.caption : '카테고리별로 직접 맞췄어요'}</p>

        <div className="goal-card">
          <div className="gh-head">
            <div className="gh-cap">이번 챌린지에 지킬 돈</div>
            <div className="gh-num">{won(total)}</div>
          </div>
          {draft.cutCats.map((code) => {
            const inten = draft.intensities[code] ?? DEFAULT_INTENSITY;
            const goal = Math.round(baseOf(code) * inten);
            const name = nameOf(code);
            const { icon, bg } = iconOf(name);
            return (
              <div key={code}>
                <div className="list-item">
                  <span className="ic" style={{ background: bg }}><Icon id={icon} /></span>
                  <div className="tx">
                    <b>{name}</b>
                    <span>강도 {Math.round(inten * 100)}% · 지킬 돈 {won(goal)}</span>
                  </div>
                  <div className="stepper">
                    <button type="button" disabled={inten <= INTENSITY_MIN} onClick={() => bump(code, -1)}
                      aria-label={`${name} 강도 낮추기`}>–</button>
                    <b aria-live="off">{Math.round(inten * 100)}%</b>
                    <button type="button" disabled={inten >= INTENSITY_MAX} onClick={() => bump(code, +1)}
                      aria-label={`${name} 강도 높이기`}>+</button>
                  </div>
                </div>
                <div className="divider" />
              </div>
            );
          })}
          <div className="pv" style={{ margin: '6px 0 12px' }}>
            강도가 높을수록 지킬 돈도, 난도도 함께 커져요. 무리 없이 시작해보세요.
          </div>
        </div>

        {error != null && (
          <>
            <ErrorBox error={error} />
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => go('home')}>
              나중에 정할게요 · 홈으로
            </button>
          </>
        )}
        <div className="spacer" style={{ height: 40 }} />
      </div></Scroll>
      <Cta>
        <button type="button" className="btn btn-primary" disabled={busy || total <= 0} onClick={() => void start()}>
          {busy ? '챌린지를 시작하는 중…' : '이 강도로 지키기'}
        </button>
      </Cta>
    </Screen>
  );
}
