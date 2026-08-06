/**
 * 이번 챌린지 정하기 2/4 — 절대 안 건드릴 소비(성역) 고르기 (프로토타입_0806 `s-ob2`).
 *
 * <p><b>줄일 곳보다 지킬 곳을 먼저 정한다.</b> 개편안이 이 화면을 앞에 둔 이유다 —
 * "무엇을 줄일까"부터 물으면 전부 줄일 대상으로 보이고, 사람은 자기 가치를 방어하느라
 * 아무것도 못 고른다. 안 건드릴 곳을 먼저 못 박아 두면 나머지는 편하게 고를 수 있다.
 *
 * <p><b>금액을 보이지 않는다.</b> 여기서 고르는 것은 액수가 아니라 <b>가치</b>다. 숫자가
 * 옆에 있으면 "많이 쓴 곳을 지켜야 이득"이라는 계산이 끼어든다. 금액은 마지막 화면
 * (얼마나 줄여볼까요)에서 한 번에 나온다.
 */
import { Icon } from '../components/Icons';
import { AppBar, ProgressBar, Cta, Scroll, Screen } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, catLabel } from '../lib/api';
import { iconOf } from '../lib/format';

export function Onboarding2() {
  const { go, back, userId, draft, patchDraft } = useSession();
  const cats = useAsync(() => api.categories().catch(() => []), [userId]);

  const toggle = (code: string) => {
    const on = draft.sanctuary.includes(code);
    patchDraft({ sanctuary: on ? draft.sanctuary.filter((k) => k !== code) : [...draft.sanctuary, code] });
  };

  return (
    <Screen title="성역 고르기">
      <AppBar onBack={back} steps="2 / 4" />
      <ProgressBar value={0.70} />
      <Scroll><div className="pad">
        <p className="h-title">절대 안 건드릴 소비를<br />먼저 정해요</p>
        <p className="h-sub">
          여기 고른 소비는 지킴이가 <b style={{ color: 'var(--green-t)' }}>평생 침묵</b>해요.
          궁상이 아니라 조절이니까요.
        </p>

        <p className="label">성역으로 지정 <span style={{ color: 'var(--t3)', fontWeight: 600 }}>(여러 개 선택 가능)</span></p>
        <div className="chips">
          {(cats.data ?? []).map((c) => {
            const on = draft.sanctuary.includes(c.code);
            const { icon } = iconOf(c.displayName);
            return (
              <button type="button" key={c.code} className={`chip sanctuary${on ? ' on' : ''}`}
                aria-pressed={on} onClick={() => toggle(c.code)}>
                <Icon id={icon} className="ci" />{catLabel(c.code, c.displayName)}
              </button>
            );
          })}
          {cats.data?.length === 0 && <p className="empty">카테고리 목록을 불러오지 못했어요.</p>}
        </div>

        <div className="pv">
          고른 소비는 리포트에도 <b>'잘 쓴 돈'</b>으로만 보이고, 챌린지 대상에서 빠져요.
        </div>
        <div className="spacer" />
      </div></Scroll>
      <Cta>
        {/* 하나도 안 골라도 넘어간다 — 성역은 선택이지 숙제가 아니다. */}
        <button type="button" className="btn btn-primary" onClick={() => go('ob3')}>다음</button>
      </Cta>
    </Screen>
  );
}
