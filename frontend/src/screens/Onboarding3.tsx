/**
 * 이번 챌린지 정하기 3/4 — CT-02 절약 강도 + CT-03 지킬 돈 확정.
 *
 * 지킬 돈 = 기준 지출 × 강도. 기준 지출·사용 예산 같은 내부값은 노출하지 않는다(IA §1.2).
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
  won, wonShort, iconOf, shortDateTime, INTENSITY_TIERS, DEFAULT_INTENSITY,
  INTENSITY_MIN, INTENSITY_MAX, INTENSITY_STEP, round1,
} from '../lib/format';

export function Onboarding3() {
  const { go, back, userId, analysis, draft, patchDraft } = useSession();
  const { reload } = useGuardian();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  /** 서버가 409로 거절했을 때의 안내 — 오류가 아니라 '지금은 못 바꾼다'는 사실이다. */
  const [conflict, setConflict] = useState<string | null>(null);
  /** 지금 펼쳐 놓은 카테고리. 한 번에 하나만 열어 화면이 길어지지 않게 한다. */
  const [expanded, setExpanded] = useState<string | null>(null);
  const inited = useRef(false);

  /** 그 카테고리의 최근 30일 실제 지출(창 안 합계). */
  const spendOf = (code: string) => draft.baseline[code]?.monthlyAmount ?? 0;
  const paymentsOf = (code: string) => draft.baseline[code]?.payments ?? [];

  /**
   * 강도를 곱할 <b>대상 금액</b> — 낭비로 판정됐고 사용자가 빼지 않은 결제의 합.
   *
   * <p>전체 지출에 곱하면 월세·병원비까지 줄이라는 말이 된다. 그리고 ML 판정은 완벽하지
   * 않으므로(운영 실측 정밀도 0.689) 사용자가 "이건 낭비가 아니다"를 빼는 절차가 있어야
   * 숫자를 믿을 수 있다. 뺀 만큼 여기서 정확히 줄어든다.
   */
  const kept = new Set(draft.keptPaymentIds);
  const wasteOf = (code: string) => paymentsOf(code)
    .filter((p) => p.waste === true && !kept.has(p.paymentId))
    .reduce((s, p) => s + p.amount, 0);
  /** 판정 목록이 아예 없으면(모델 미배치 등) 옛 방식대로 전체 지출을 기준으로 둔다. */
  const baseOf = (code: string) => {
    const w = wasteOf(code);
    return w > 0 || paymentsOf(code).some((p) => p.waste === true) ? w : spendOf(code);
  };
  /** 그 강도를 전 카테고리에 적용했을 때 한 달에 지킬 돈. 버튼에 결과를 미리 보여주려고 센다. */
  const tierTotal = (v: number) =>
    draft.cutCats.reduce((sum, code) => sum + Math.round(baseOf(code) * v), 0);
  const nameOf = (code: string) => draft.baseline[code]?.displayName ?? code;

  const toggleKeep = (paymentId: string) => {
    const on = kept.has(paymentId);
    patchDraft({
      keptPaymentIds: on
        ? draft.keptPaymentIds.filter((k) => k !== paymentId)
        : [...draft.keptPaymentIds, paymentId],
    });
  };

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
  // 서버는 지킬 돈이 기준 지출보다 **작을 것**을 요구한다(예산이 0원이 되는 챌린지는 만들지 않는다).
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
    setBusy(true); setError(null); setConflict(null);
    try {
      await api.guardian.createChallenge(userId, {
        categories: draft.cutCats,
        sanctuaryCategories: draft.sanctuary,
        targetSaving: total,
        durationDays: CHALLENGE_DAYS,
        // 뺀 결제를 서버에도 알린다. 안 보내면 화면만 줄고 서버 예산은 그대로라
        // 사용자가 고른 의미가 사라진다.
        keptPaymentIds: draft.keptPaymentIds,
        // 강도가 카테고리마다 다르므로 목표도 카테고리별로 보낸다. 하나로 보내면 서버가
        // 균등분할해, 사용자가 정한 것과 화면이 보여준 것이 달라진다.
        categoryTargets: Object.fromEntries(draft.cutCats.map((code) =>
          [code, Math.round(baseOf(code) * (draft.intensities[code] ?? DEFAULT_INTENSITY))])),
      });
      await trackCutSelections();
      await reload();
      go('done');
    } catch (e) {
      // 이미 진행 중인 챌린지가 있으면(409) 새로 만들 수 없다. 예전에는 **말없이 홈으로 보냈는데**,
      // 그러면 방금 고른 카테고리 대신 지난 챌린지가 떠 있어 "온보딩이 아무 소용 없다"로 보인다
      // (2026-07-31 운영). 화면에 남아서 사실을 알리고, 홈으로 갈지는 사용자가 고른다.
      if (e instanceof ApiError && e.status === 409) {
        await reload();
        setConflict('지금 지키는 중인 챌린지가 있어서 이번 선택은 저장되지 않았어요. '
          + '지금 챌린지가 끝나면 새로 정할 수 있어요.');
        return;
      }
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

        {/* 강도 버튼에는 **금액**을 적는다(개편안). "50%"는 얼마인지 모르지만 "월 16.5만"은 안다 —
            고르기 전에 결과가 보여야 고를 수 있다. */}
        <div className="int-seg" role="group" aria-label="절약 강도 프리셋">
          {INTENSITY_TIERS.map((t) => (
            <button type="button" key={t.key} className={activeTier?.key === t.key ? 'on' : ''}
              aria-pressed={activeTier?.key === t.key} onClick={() => setAll(t.value)}>
              <b>{t.label}</b><span>월 {wonShort(tierTotal(t.value))}</span>
            </button>
          ))}
        </div>
        <p className="int-caption">{activeTier ? activeTier.caption : '카테고리별로 직접 맞췄어요'}</p>

        <div className="goal-card">
          <div className="gh-head">
            <div className="gh-cap">
              이번 챌린지에 지킬 돈
              {/* 프리셋에 없는 조합이면 직접 맞춘 것이다 — 그 사실을 표시해 준다(개편안 `custom-chip`). */}
              {!activeTier && <span className="custom-chip">직접 설정</span>}
            </div>
            <div className="gh-num">{won(total)}</div>
          </div>
          {draft.cutCats.map((code) => {
            const inten = draft.intensities[code] ?? DEFAULT_INTENSITY;
            const goal = Math.round(baseOf(code) * inten);
            const name = nameOf(code);
            const { icon, bg } = iconOf(name);
            // 낭비로 판정된 결제를 보여준다. **하나도 없으면 그 카테고리의 결제 전부**를
            // 금액 큰 순으로 보여준다 — 예전에는 빈 목록이라, 고를 수는 있는데 무슨 내역인지
            // 확인할 길이 없었다(교통처럼 판정이 0건인 카테고리에서 늘 그랬다).
            const judged = paymentsOf(code).filter((p) => p.waste === true);
            const rows = judged.length > 0
              ? judged
              : [...paymentsOf(code)].sort((x, y) => y.amount - x.amount);
            const unjudged = judged.length === 0;
            const open = expanded === code;
            const keptCount = rows.filter((p) => kept.has(p.paymentId)).length;
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

                {/* 줄일 수 있는 소비 펼치기 — ML 판정은 완벽하지 않다(정밀도 0.689).
                    "이건 낭비가 아니다" 싶은 것을 사용자가 빼야 숫자를 믿을 수 있다. */}
                {rows.length > 0 && (
                  <>
                    <button type="button" className="pick-toggle" aria-expanded={open}
                      onClick={() => setExpanded(open ? null : code)}>
                      <span>{unjudged ? '이 카테고리 소비' : '줄일 수 있는 소비'} {rows.length}건 · 기준 {won(baseOf(code))}
                        {keptCount > 0 && <b> · {keptCount}건 뺐어요</b>}</span>
                      <span className="chev" aria-hidden="true">{open ? '⌃' : '⌄'}</span>
                    </button>
                    {open && (
                      <ul className="pick-list">
                        {rows.map((p) => {
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
                              {/* 왜 낭비로 봤는지 — **확인할 수 있는 숫자로** 말한다.
                                  "평소보다 큰 금액"까지만 하면 동의도 반박도 할 수 없다.
                                  "평소 23,000원 → 78,000원(3.4배)"이라야 "그날은 회식이었다"고
                                  답할 수 있고, 그 답이 곧 이 화면이 받으려는 신호다. */}
                              {p.factors?.length > 0 && (
                                <ul className="why">
                                  {p.factors.map((f, i) => (
                                    <li key={i}>
                                      <b>{f.label}</b>
                                      {f.detail && <span>{f.detail}</span>}
                                    </li>
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
                <div className="divider" />
              </div>
            );
          })}
          <div className="pv" style={{ margin: '6px 0 12px' }}>
            강도가 높을수록 지킬 돈도, 난도도 함께 커져요. 무리 없이 시작해보세요.
          </div>
        </div>

        {conflict != null && (
          <>
            <p className="notice-warn" role="alert">{conflict}</p>
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => go('home')}>
              지금 지키는 중인 챌린지 보기
            </button>
          </>
        )}
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
