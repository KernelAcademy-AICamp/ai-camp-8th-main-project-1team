/**
 * 이번 챌린지 정하기 2/4 — CT-01 줄일 카테고리 선택. 사용자가 1~2개 확정한다.
 *
 * 금액은 <b>최근 30일 실측</b>이다(`/api/onboarding/window`). 예전에는 이 화면이 전 기간을
 * 관측 개월수로 나눈 값을 보여줬고, 앞 화면(ob1)은 최근 90일을 월로 환산한 값을 보여줬다 —
 * 같은 '취미/여가'가 691,150원과 745,118원으로 갈렸다(2026-07-31 실측). 서버가 챌린지 기준으로
 * 삼는 값은 또 그 둘과 달랐다.
 *
 * <b>이제 셋이 같은 창을 본다.</b> 그래야 다음 화면에서 결제를 펼쳐 "이건 낭비가 아니다"를
 * 골랐을 때, 금액이 정확히 그만큼 줄어드는 것이 설명된다.
 *
 * 추천은 ML 낭비 판정 금액(`wasteAmount`)이 큰 카테고리다.
 */
import { useEffect, useMemo, useRef } from 'react';
import { Icon } from '../components/Icons';
import { AppBar, ProgressBar, Cta, Scroll, Screen, ErrorBox, Loading, Empty } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, catLabel, type OnboardingCategory } from '../lib/api';
import { won, iconOf } from '../lib/format';

type Option = OnboardingCategory & { rec: boolean };

export function Onboarding2() {
  const { go, back, userId, analysis, draft, patchDraft } = useSession();
  const report = useAsync(() => api.onboardingWindow(userId), [userId]);
  const inited = useRef(false);

  const { options, recommended } = useMemo(() => {
    const r = report.data;
    if (!r) return { options: [] as Option[], recommended: [] as string[] };
    // 낭비로 판정된 금액이 그 카테고리 지출의 3분의 1을 넘으면 '줄여볼 만한 곳'으로 본다.
    // 비율로 보는 이유: 금액만 보면 식비처럼 원래 큰 카테고리가 늘 1등이 된다.
    const all: Option[] = r.categories
      .filter((c) => c.amount > 0)
      .map((c) => ({ ...c, rec: c.wasteAmount > 0 && c.wasteAmount / c.amount >= 0.33 }))
      .sort((x, y) => y.wasteAmount - x.wasteAmount || y.amount - x.amount);
    return { options: all, recommended: all.filter((c) => c.rec).slice(0, 2).map((c) => c.categoryCode) };
  }, [report.data]);

  /** ① 분석의 절약 후보(category2 단위) 근거 문장을 카테고리 이름으로 이어 붙인다. */
  const reasonOf = useMemo(() => {
    const map = new Map<string, string>();
    (analysis?.cutCandidates ?? []).forEach((c) => map.set(c.category2, c.reason));
    return (displayName: string) => {
      for (const [name, reason] of map) {
        if (name.includes(displayName) || displayName.includes(name)) return reason;
      }
      return null;
    };
  }, [analysis]);

  // 첫 진입 시 AI 추천 상위 2개를 미리 선택한다(사용자가 해제 가능 — IA CT-01).
  useEffect(() => {
    if (inited.current || options.length === 0) return;
    inited.current = true;
    const baseline: typeof draft.baseline = {};
    options.forEach((o) => {
      baseline[o.categoryCode] = {
        displayName: catLabel(o.categoryCode, o.displayName),
        monthlyAmount: o.amount,
        wasteAmount: o.wasteAmount,
        payments: o.payments,
        reason: reasonOf(o.displayName) ?? undefined,
        type: o.rec ? 'RECOMMENDED' : 'OTHER',
      };
    });
    patchDraft({ baseline, cutCats: draft.cutCats.length ? draft.cutCats : recommended });
  }, [options, recommended, reasonOf, patchDraft, draft.cutCats, draft.baseline]);

  const toggle = (code: string) => {
    const on = draft.cutCats.includes(code);
    patchDraft({ cutCats: on ? draft.cutCats.filter((k) => k !== code) : [...draft.cutCats, code] });
  };

  return (
    <Screen title="줄일 카테고리 선택">
      <AppBar onBack={back} steps="2 / 4" />
      <ProgressBar value={0.5} />
      <Scroll><div className="pad">
        <p className="h-title">뭘 줄여볼까요?</p>
        <p className="h-sub">
          지킴이가 <b style={{ color: 'var(--blue-t)' }}>AI 추천</b>으로 골라봤어요. 1~2개 권장.
          금액은 <b>최근 30일 실제 지출</b>이에요 — 다음에서 어떤 결제를 뺄지 고르고 강도를 정해요.
        </p>

        <ErrorBox error={report.error} onRetry={report.reload} />
        {report.loading && <Loading label="카테고리를 불러오는 중" rows={4} />}
        {!report.loading && options.length === 0 && !report.error && (
          <Empty>아직 카테고리별 소비가 쌓이지 않았어요. 카드 연결 뒤 결제가 들어오면 후보가 생겨요.</Empty>
        )}

        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {options.map((c) => {
            const on = draft.cutCats.includes(c.categoryCode);
            const name = catLabel(c.categoryCode, c.displayName);
            const { icon, bg } = iconOf(name);
            const reason = reasonOf(c.displayName);
            return (
              <button type="button" key={c.categoryCode} onClick={() => toggle(c.categoryCode)} aria-pressed={on}
                className="card" style={{
                  margin: 0, padding: 16, cursor: 'pointer', position: 'relative', textAlign: 'left',
                  fontFamily: 'inherit', width: '100%',
                  border: `1.5px solid ${on ? 'var(--blue)' : 'var(--line)'}`,
                  background: on ? 'var(--blue-weak)' : 'var(--card)',
                }}>
                {c.rec && (
                  <span style={{ position: 'absolute', top: -8, right: 14, fontSize: 10, fontWeight: 700, background: 'var(--blue-surface)', color: '#fff', padding: '2px 8px', borderRadius: 20 }}>
                    AI 추천
                  </span>
                )}
                <span className="list-item" style={{ padding: 0 }}>
                  <span className="ic" style={{ background: bg }}><Icon id={icon} /></span>
                  <span className="tx">
                    <b>{name} <span style={{ fontSize: 12, color: 'var(--t3)', fontWeight: 600 }}>
                      {c.rec ? '줄이면 좋아요' : '잘 관리 중'}</span></b>
                    <span>{reason ?? (c.wasteAmount > 0
                      ? `${c.count.toLocaleString('ko-KR')}건 중 ${won(c.wasteAmount)}이 줄일 수 있는 소비`
                      : `최근 ${c.count.toLocaleString('ko-KR')}건`)}</span>
                  </span>
                  <span style={{ textAlign: 'right', flex: '0 0 auto' }}>
                    <b style={{ color: 'var(--t1)', fontSize: 15, display: 'block' }}>{won(c.amount)}</b>
                    <span style={{ fontSize: 11, color: 'var(--t3)' }}>최근 30일</span>
                  </span>
                </span>
              </button>
            );
          })}
        </div>
        <div className="spacer" />
      </div></Scroll>
      <Cta>
        <button type="button" className="btn btn-primary" disabled={draft.cutCats.length === 0} onClick={() => go('ob3')}>
          {draft.cutCats.length === 0 ? '줄일 소비를 골라주세요' : `${draft.cutCats.length}개로 시작하기`}
        </button>
      </Cta>
    </Screen>
  );
}
