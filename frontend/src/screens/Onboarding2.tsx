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
import { useEffect, useMemo, useRef, useState } from 'react';
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

  const { options, folded, recommended, noJudgment } = useMemo(() => {
    const r = report.data;
    const none = { options: [] as Option[], folded: [] as Option[], recommended: [] as string[], noJudgment: false };
    if (!r) return none;

    // **낭비로 판정된 것만 올린다.** 이 화면이 묻는 것은 "얼마 썼나"가 아니라 "얼마를 줄일 수
    // 있나"다. 지출액을 띄우면 교통비 526,600원이 맨 위에 앉는데, 줄일 수 없는 돈이라
    // 사용자가 할 수 있는 일이 없다. 그래서 금액도 정렬도 **낭비 금액**으로 통일한다.
    const withWaste = r.categories.filter((c) => c.wasteAmount > 0);

    // 모델이 아직 판정을 못 하면(학습 전·근거 없음) 전부 0이 되어 화면이 텅 빈다. 그때는
    // 지출 기준으로 되돌려 보여준다 — 조언을 지우는 것보다 예전 모습이 덜 놀랍다.
    const base = withWaste.length > 0 ? withWaste : r.categories.filter((c) => c.amount > 0);
    const byWaste = (x: Option, y: Option) => y.wasteAmount - x.wasteAmount || y.amount - x.amount;
    const all: Option[] = base.map((c) => ({ ...c, rec: !c.protectedCategory && c.wasteAmount > 0 }));

    return {
      // 줄이라고 권하지 않는 카테고리(교통·통신·의료)는 접어 둔다 — 없애지는 않는다.
      // 본인이 굳이 줄이겠다면 막을 이유가 없고, 다만 기본 화면을 어지럽히지는 않는다.
      options: all.filter((c) => !c.protectedCategory).sort(byWaste),
      folded: all.filter((c) => c.protectedCategory).sort(byWaste),
      recommended: all.filter((c) => c.rec).slice(0, 2).map((c) => c.categoryCode),
      noJudgment: withWaste.length === 0,
    };
  }, [report.data]);
  const [showFolded, setShowFolded] = useState(false);

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
    if (inited.current || options.length + folded.length === 0) return;
    inited.current = true;
    const baseline: typeof draft.baseline = {};
    [...options, ...folded].forEach((o) => {
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
  }, [options, folded, recommended, reasonOf, patchDraft, draft.cutCats, draft.baseline]);

  const toggle = (code: string) => {
    const on = draft.cutCats.includes(code);
    patchDraft({ cutCats: on ? draft.cutCats.filter((k) => k !== code) : [...draft.cutCats, code] });
  };

  /** 카드 한 장 — 접힌 목록도 같은 모양이라야 "같은 걸 고르는 것"으로 읽힌다. */
  const card = (c: Option) => {
    const on = draft.cutCats.includes(c.categoryCode);
    const name = catLabel(c.categoryCode, c.displayName);
    const { icon, bg } = iconOf(name);
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
              {c.protectedCategory ? '줄이라고 권하지 않아요' : '줄이면 좋아요'}</span></b>
            {/* 큰 숫자가 '줄일 수 있는 돈'이므로, 설명줄이 '쓴 돈'을 맡는다 — 둘을 같은 크기로
                나란히 두면 어느 쪽이 목표인지 흐려진다. */}
            <span>{`최근 30일 ${won(c.amount)} 씀 · ${c.count.toLocaleString('ko-KR')}건`}</span>
          </span>
          <span style={{ textAlign: 'right', flex: '0 0 auto' }}>
            <b style={{ color: 'var(--t1)', fontSize: 15, display: 'block' }}>
              {won(noJudgment ? c.amount : c.wasteAmount)}</b>
            <span style={{ fontSize: 11, color: 'var(--t3)' }}>
              {noJudgment ? '최근 30일' : '줄일 수 있어요'}</span>
          </span>
        </span>
      </button>
    );
  };

  return (
    <Screen title="줄일 카테고리 선택">
      <AppBar onBack={back} steps="2 / 4" />
      <ProgressBar value={0.5} />
      <Scroll><div className="pad">
        <p className="h-title">뭘 줄여볼까요?</p>
        <p className="h-sub">
          지킴이가 <b style={{ color: 'var(--blue-t)' }}>AI 추천</b>으로 골라봤어요. 1~2개 권장.
          금액은 <b>{noJudgment ? '최근 30일 실제 지출' : '줄일 수 있는 최대 금액'}</b>이에요 —
          다음에서 어떤 결제를 뺄지 고르고 강도를 정해요.
        </p>

        <ErrorBox error={report.error} onRetry={report.reload} />
        {report.loading && <Loading label="카테고리를 불러오는 중" rows={4} />}
        {!report.loading && options.length + folded.length === 0 && !report.error && (
          <Empty>아직 줄일 만한 소비를 찾지 못했어요. 결제가 더 쌓이면 후보가 생겨요.</Empty>
        )}

        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {options.map(card)}
        </div>

        {/* 줄이라고 권하지 않는 카테고리 — 접어 둔다. 없애면 본인 의지로 줄일 길이 막히고,
            펼쳐 두면 첫 화면이 "줄일 수 없는 돈"으로 채워진다. */}
        {folded.length > 0 && (
          <div style={{ marginTop: 14 }}>
            <button type="button" onClick={() => setShowFolded((v) => !v)}
              style={{ background: 'none', border: 'none', padding: '6px 0', cursor: 'pointer',
                       fontFamily: 'inherit', fontSize: 13, color: 'var(--t3)', fontWeight: 600 }}>
              {showFolded ? '▾' : '▸'} 줄이라고 권하지 않는 {folded.length}개 (교통·통신·의료 등)
            </button>
            {showFolded && (
              <>
                <p style={{ fontSize: 12, color: 'var(--t3)', margin: '2px 0 10px' }}>
                  생활에 꼭 필요한 소비예요. 그래도 직접 줄여보고 싶다면 고를 수 있어요.
                </p>
                <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
                  {folded.map(card)}
                </div>
              </>
            )}
          </div>
        )}
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
