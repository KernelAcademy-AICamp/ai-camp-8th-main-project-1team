/**
 * 예외 2. 주간 정산 (프로토타입_0818 `s-weekly`) — 0818 신설 화면.
 *
 * <p>일요일 밤에 한 주를 닫는 화면이다. <b>잘된 것부터</b> 보여주고, 못 지킨 날은
 * "기록으로만 남는다"고 분명히 말한다 — 지킨 날과 모은 소품·포인트는 그대로다.
 *
 * <p><b>실패한 미션도 사라지지 않는다.</b> '재도전' 이라고 적고 다음 주에 다시 시작한다고
 * 말한다. 사라지면 그 주에 애쓴 것이 없던 일이 되고, 그게 사람이 그만두는 자리다.
 */
import { AppBar, Scroll, Screen, Cta, Loading, ErrorBox } from '../components/ui';
import { Icon } from '../components/Icons';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api } from '../lib/api';
import { iconOf } from '../lib/format';

/** "7.20 ~ 7.26" */
const fmtRange = (a: string, b: string) =>
  `${Number(a.slice(5, 7))}.${Number(a.slice(8, 10))}~${Number(b.slice(5, 7))}.${Number(b.slice(8, 10))}`;

export function WeeklyWrap() {
  const { back, go, userId } = useSession();
  const week = useAsync(() => api.guardian.weeklyReport(userId, 0).catch(() => null), [userId]);
  const w = week.data;

  if (week.loading && !w) {
    return (
      <Screen id="weekly" title="주간 정산">
        <AppBar onBack={back} title="주간 정산" />
        <div className="pad"><Loading label="이번 주를 정리하는 중" rows={5} /></div>
      </Screen>
    );
  }

  const kept = (w?.days ?? []).filter((d) => d.judged && d.amount === 0).length;
  const missed = (w?.days ?? []).filter((d) => d.judged && d.amount > 0).length;
  const missions = w?.missions ?? [];

  return (
    <Screen id="weekly" title="주간 정산">
      <AppBar onBack={back} title="주간 정산"
        steps={w ? fmtRange(w.weekStart, w.weekEnd) : undefined} />
      <Scroll><div className="pad">
        <div className="h-title">한 주가 끝났어요</div>
        <div className="h-sub">일요일 밤, 지킴이가 이번 주를 정리했어요. 잘된 것부터 볼게요.</div>

        <ErrorBox error={week.error} onRetry={week.reload} />

        <div className="label">이번 주 미션</div>
        <div className="card" style={{ padding: '8px 20px' }}>
          {missions.length === 0 && (
            <p className="empty" style={{ margin: '8px 0' }}>이번 주에 걸린 미션이 없었어요.</p>
          )}
          {missions.map((m, i) => {
            const ok = m.status === 'SUCCESS';
            const { icon, bg } = iconOf(m.text);
            return (
              <div key={`${m.text}-${i}`}>
                {i > 0 && <div className="divider" />}
                <div className="list-item">
                  <span className="ic" style={{ background: bg }}><Icon id={icon} /></span>
                  <div className="tx">
                    <b>{m.text}</b>
                    <span>{ok
                      ? '지켰어요. 다음 주에 자동으로 이어져요'
                      : '사라지지 않고 다음 주에 다시 시작해요'}</span>
                  </div>
                  {/* 실패를 실패라 적지 않는다 — <b>재도전</b>이다(기획 §5.1.5). */}
                  <span className={ok ? 'tag-good' : 'tag-warn'}>
                    {ok ? `+${m.reward}P` : '재도전'}
                  </span>
                </div>
              </div>
            );
          })}
        </div>

        <div className="label">이번 주 기록</div>
        <div className="asset-row">
          <div className="asset"><b>{kept}일</b><span>지킨 날</span></div>
          <div className="asset"><b>{missed}일</b><span>못 지킨 날</span></div>
          <div className="asset"><b>+{w?.missionReward ?? 0}P</b><span>이번 주 획득</span></div>
        </div>
        <div className="pv">
          못 지킨 {missed}일은 기록으로만 남아요. 지킨 {kept}일과 모은 소품, 포인트는 <b>그대로</b>예요.
        </div>
        <div className="spacer" style={{ height: 96 }} />
      </div></Scroll>
      <Cta>
        <button type="button" className="btn btn-primary" onClick={() => go('myroom')}>
          다음 주 미션 고르기
        </button>
        <button type="button" className="btn btn-ghost" style={{ marginTop: 8 }}
          onClick={() => go('home')}>홈으로</button>
      </Cta>
    </Screen>
  );
}
