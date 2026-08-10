/**
 * 이번 챌린지 정하기 3/4 — 줄일 카테고리 고르기 (프로토타입_0806 `s-ob3`).
 *
 * <p><b>금액을 보이지 않는다.</b> 개편안이 이 화면을 칩만으로 그린 이유다. 여기서 정하는 것은
 * "어디를 건드릴까"이고, "얼마나"는 다음 화면이 묻는다. 카드마다 큰 숫자를 붙여 두면
 * 사람은 <b>가장 큰 숫자를 고르게</b> 되는데, 그건 자기 습관이 아니라 표를 고른 것이다.
 *
 * <p><b>'AI 추천'은 참고다.</b> ML 이 낭비로 판정한 금액이 큰 곳에 배지를 붙이지만, 결정은
 * 사용자가 한다 — 정밀도가 완벽하지 않고(운영 실측 0.689), 무엇을 줄일지는 애초에
 * 모델이 정할 일이 아니다.
 *
 * <p>성역으로 둔 곳은 <b>목록에 없다</b>. 앞 화면에서 "안 건드린다"고 한 곳을 다시 후보로
 * 올리면 앞의 선택이 무엇이었는지 흐려진다.
 */
import { useEffect, useMemo } from 'react';
import { Icon } from '../components/Icons';
import { AppBar, ProgressBar, Cta, Scroll, Screen, ErrorBox, Loading } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, catLabel } from '../lib/api';
import { iconOf } from '../lib/format';

/** 한 번에 고를 수 있는 최대 수. 셋을 넘으면 어느 것도 제대로 못 지킨다. */
const MAX_PICK = 3;

export function Onboarding3() {
  const { go, back, userId, draft, patchDraft } = useSession();
  const report = useAsync(() => api.onboardingWindow(userId), [userId]);

  /**
   * 고를 수 있는 카테고리와 'AI 추천' 배지.
   *
   * 배지는 <b>ML 낭비 판정 금액</b>이 큰 상위 둘에 붙인다 — 개편안이 배지를 둘만 그린다.
   * 서버가 "줄이라고 권하지 않는다"고 표시한 곳(교통·통신·의료 같은 재량성 낮은 곳)은 뺀다.
   */
  const options = useMemo(() => {
    const rows = (report.data?.categories ?? [])
      .filter((c) => !draft.sanctuary.includes(c.categoryCode))
      .filter((c) => c.categoryCode !== '카테고리없음');
    const rec = new Set(
      rows.filter((c) => !c.protectedCategory && c.wasteAmount > 0)
        .sort((a, b) => b.wasteAmount - a.wasteAmount)
        .slice(0, 2).map((c) => c.categoryCode));
    // 표시 순서는 낭비 금액이 아니라 **쓴 금액** 순 — 배지가 순서까지 정하면 추천이 강요가 된다.
    return rows.slice().sort((a, b) => b.amount - a.amount)
      .map((c) => ({ ...c, rec: rec.has(c.categoryCode) }));
  }, [report.data, draft.sanctuary]);

  /**
   * 다음 화면(ob4)이 쓸 재료를 담아 둔다.
   *
   * <b>여기서 담아야 한다.</b> ob4 는 결제별 낭비 판정까지 봐야 '지킬 돈'을 셀 수 있는데,
   * 그 창(`/api/onboarding/window`)을 부르는 곳이 이 화면이다. 예전에는 금액을 보여주던
   * 이 화면이 함께 담았는데, 개편안대로 금액을 걷어내면서 담는 일까지 빠져 ob4 가 0원이 됐다.
   */
  useEffect(() => {
    const rows = report.data?.categories ?? [];
    if (rows.length === 0) return;
    const baseline: typeof draft.baseline = {};
    for (const o of rows) {
      baseline[o.categoryCode] = {
        displayName: catLabel(o.categoryCode, o.displayName),
        monthlyAmount: o.amount,
        wasteAmount: o.wasteAmount,
        payments: o.payments,
        type: options.some((x) => x.categoryCode === o.categoryCode && x.rec) ? 'RECOMMENDED' : 'OTHER',
      };
    }
    patchDraft({ baseline });
    // 담는 일은 창이 오면 한 번이면 된다 — 고를 때마다 다시 담을 이유가 없다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [report.data]);

  const toggle = (code: string) => {
    const on = draft.cutCats.includes(code);
    if (!on && draft.cutCats.length >= MAX_PICK) return;
    patchDraft({ cutCats: on ? draft.cutCats.filter((k) => k !== code) : [...draft.cutCats, code] });
  };

  if (report.loading && !report.data) return <Loading label="소비를 살펴보는 중" />;

  return (
    <Screen title="줄일 곳 고르기">
      <AppBar onBack={back} steps="3 / 4" />
      <ProgressBar value={0.85} />
      <Scroll><div className="pad">
        <p className="h-title">어떤 지출을<br />줄여볼까요?</p>
        <p className="h-sub">
          <b style={{ color: 'var(--blue-t)' }}>AI 추천</b> 배지는 참고용이에요.
          결정은 언제나 회원님이 해요.
        </p>

        <ErrorBox error={report.error} onRetry={report.reload} />

        <div className="chips" style={{ gap: 16 }}>
          {options.map((c) => {
            const on = draft.cutCats.includes(c.categoryCode);
            const name = catLabel(c.categoryCode, c.displayName);
            const { icon } = iconOf(name);
            const full = !on && draft.cutCats.length >= MAX_PICK;
            return (
              <button type="button" key={c.categoryCode} className={`chip${on ? ' on' : ''}`}
                aria-pressed={on} disabled={full} onClick={() => toggle(c.categoryCode)}>
                <Icon id={icon} className="ci" />{name}
                {c.rec && <span className="badge">AI 추천</span>}
              </button>
            );
          })}
          {options.length === 0 && (
            <p className="empty">고를 만한 카테고리가 없어요. 앞에서 성역을 조금 줄여보세요.</p>
          )}
        </div>

        <div className="pv">
          선택한 카테고리마다 <b>예산이 따로</b> 잡혀요. 하나가 터져도 나머지는 계속 지킬 수 있어요.
          {draft.cutCats.length >= MAX_PICK && <> 한 번에 {MAX_PICK}개까지 고를 수 있어요.</>}
        </div>
        <div className="spacer" />
      </div></Scroll>
      <Cta>
        <button type="button" className="btn btn-primary" disabled={draft.cutCats.length === 0}
          onClick={() => go('ob4')}>
          {draft.cutCats.length === 0 ? '줄일 곳을 골라주세요' : '다음'}
        </button>
      </Cta>
    </Screen>
  );
}
