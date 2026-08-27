/**
 * 내 목표 — 하나를 열어서 본다 (프로토타입_0825 `s-goalv`).
 *
 * <p><b>목록에서 못 하는 일을 여기서 한다.</b> 목록(`m-goals`)은 여럿을 견주는 자리라 한 줄에
 * 이름·진행률까지가 한계다. 이 화면은 하나만 놓고 <b>지금 어디쯤이고, 이 속도면 언제 닿는지</b>를
 * 말한다.
 *
 * <h2>보여 주는 넷</h2>
 *
 * <pre>
 *   지킨 돈 / 남은 돈      진행 막대. 남은 돈이 같이 보여야 "얼마 더"가 잡힌다
 *   월 평균 지킨 돈        이 사람이 실제로 매달 지켜 온 돈
 *   예상 달성일            그 속도로 갔을 때. **속도가 0이면 날짜를 만들지 않는다**
 *   매달 쌓인 기록         달마다 얼마씩 쌓였는지
 * </pre>
 *
 * <p><b>예상 달성일이 비는 것은 고장이 아니다.</b> 아직 한 번도 지킨 적이 없으면 속도가 없고,
 * 속도가 없으면 언제 닿을지 알 수 없다. 그때 "2126년"처럼 형식만 맞는 답을 내놓으면 화면은
 * 그것을 사실처럼 그린다 — 모르는 것은 모른다고 적는다(서버가 `projectedDate: null` 로 준다).
 *
 * <p>숫자는 전부 서버가 계산해 온다. 화면에서 다시 세지 않는다(마스터 §4 원칙 2).
 */
import { useState } from 'react';
import { Sheet } from '../components/Sheet';
import { AppBar, ErrorBox, Loading, Screen, Scroll, SectionTitle } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, type GoalView } from '../lib/api';
import { man, pctNum, won } from '../lib/format';

/** `yyyy-MM` → `8월`. 그래프 축은 짧아야 읽힌다. */
const monthLabel = (m: string) => `${Number(m.slice(5, 7))}월`;

/** `yyyy-MM-dd` → `2026년 11월`. 날짜까지 말하면 없는 정밀도를 주장하게 된다. */
const dateLabel = (d: string) => `${d.slice(0, 4)}년 ${Number(d.slice(5, 7))}월`;

export function MyGoal() {
  const { back, go, userId, view } = useSession();
  const snap = useAsync(() => api.points(userId), [userId]);
  const [dropping, setDropping] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  if (snap.loading && !snap.data) return <Loading label="불러오는 중" />;
  if (snap.error) return <ErrorBox error={snap.error} onRetry={snap.reload} />;

  const goals = snap.data?.goals ?? [];
  // 어느 목표인지는 이동할 때 넘겨 준다(`go('m-goal', { goal: String(id) })`).
  // 없으면 우선 목표, 그것도 없으면 첫 번째 — 주소로 바로 들어와도 빈 화면이 안 나온다.
  const wanted = Number(view.goal ?? NaN);
  const goal: GoalView | undefined =
    goals.find((g) => g.id === wanted) ?? goals.find((g) => g.priority) ?? goals[0];

  if (!goal) {
    return (
      <Screen title="내 목표" id="m-goal">
        <AppBar onBack={back} title="내 목표" />
        <Scroll>
          <div className="pad">
            <p className="gd-r2">아직 목표가 없어요.</p>
            <button type="button" className="btn btn-primary"
                    onClick={() => go('m-goal-new')}>목표 만들기</button>
          </div>
        </Scroll>
      </Screen>
    );
  }

  const remaining = Math.max(0, goal.targetAmount - goal.balance);
  const history = goal.monthlyHistory ?? [];
  const peak = history.reduce((m, h) => Math.max(m, h.amount), 0);

  async function drop() {
    setBusy(true);
    setError(null);
    try {
      await api.deleteGoal(userId, goal!.id);
      go('m-goals');
    } catch (e) {
      setError(e);
      setDropping(false);
    } finally {
      setBusy(false);
    }
  }

  return (
    <Screen title="내 목표" id="m-goal">
      <AppBar onBack={back} title="내 목표" />
      <Scroll>
        <div className="pad">
          <div className="gv-hl-row">
            <span className="gv-obj" aria-hidden="true">{goal.emoji}</span>
            <div>
              <b className="gv-hl">{goal.name}</b>
              <span className="gv-sub">{won(goal.balance)} / {won(goal.targetAmount)}</span>
            </div>
          </div>

          <div className="gv-pb" role="progressbar"
               aria-valuenow={Math.round(goal.progress * 100)} aria-valuemin={0} aria-valuemax={100}
               aria-label={`${goal.name} 진행률`}>
            <i style={{ width: `${Math.min(100, Math.round(goal.progress * 100))}%` }} />
          </div>
          <div className="gv-leg">
            <span><i className="gv-dot on" />지킨 돈 {won(goal.balance)}</span>
            <span><i className="gv-dot" />남은 돈 {won(remaining)}</span>
          </div>

          <div className="gv-stat2">
            <div className="gv-st">
              <span>월 평균 지킨 돈</span>
              <b>{goal.monthlyAverageSaved > 0 ? man(goal.monthlyAverageSaved) : '—'}</b>
            </div>
            <div className="gv-st">
              <span>예상 달성일</span>
              {/* 속도가 없으면 날짜가 없다 — 없는 것을 지어내지 않는다. */}
              <b>{goal.projectedDate ? dateLabel(goal.projectedDate) : '아직 몰라요'}</b>
            </div>
          </div>

          {goal.monthlyRequired > 0 && (
            <p className="gd-r2">
              기한 안에 맞추려면 매달 {won(goal.monthlyRequired)}이에요
              {goal.monthlyAverageSaved > 0 && ` · 지금은 매달 ${man(goal.monthlyAverageSaved)}`}
            </p>
          )}

          <SectionTitle>매달 쌓인 기록</SectionTitle>
          {history.length === 0 ? (
            <p className="gd-r2">아직 쌓인 달이 없어요. 참은 순간이 여기에 쌓여요.</p>
          ) : (
            <div className="gv-bars">
              {history.map((h) => (
                <div key={h.month} className="gv-bar">
                  <i style={{ height: `${peak > 0 ? Math.max(6, Math.round((h.amount / peak) * 100)) : 6}%` }}
                     title={won(h.amount)} />
                  <span>{monthLabel(h.month)}</span>
                </div>
              ))}
            </div>
          )}

          <div className="gv-actions">
            <button type="button" className="gv-ar" onClick={() => go('m-goals')}>
              목표 다시 설정하기<span className="gv-chev">›</span>
            </button>
            <button type="button" className="gv-ar quiet" onClick={() => setDropping(true)}>
              목표 내려놓기<span className="gv-chev">›</span>
            </button>
          </div>

          {error != null && <ErrorBox error={error} />}
          <div className="spacer" style={{ height: 32 }} />
        </div>
      </Scroll>

      <Sheet open={dropping} onClose={() => setDropping(false)} title="목표를 내려놓을까요?">
        <div className="sheet-handle" />
        <div className="sheet-title">목표를 내려놓을까요?</div>
        {/* 무엇을 잃는지 먼저 말한다 — 되돌릴 수 없는 일은 누르기 전에 알아야 한다. */}
        <p className="sheet-sub">
          지금까지 쌓인 {won(goal.balance)}({pctNum(goal.progress)}%)도 함께 사라져요.
        </p>
        <div className="sheet-cta">
          <button type="button" className="btn btn-ghost" disabled={busy}
                  onClick={drop}>내려놓기</button>
          <button type="button" className="btn btn-primary" disabled={busy}
                  onClick={() => setDropping(false)}>계속하기</button>
        </div>
      </Sheet>
    </Screen>
  );
}
