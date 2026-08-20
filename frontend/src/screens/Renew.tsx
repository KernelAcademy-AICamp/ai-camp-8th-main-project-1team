/**
 * 다음 달 갱신 (개편안 `s-renew`) — 지난달 실적으로 만든 조정안.
 *
 * <p><b>목표를 낮추는 것이 후퇴가 아니다.</b> 못 지킬 목표를 그대로 두면 사람이 그만둔다.
 * 그래서 달성률이 낮았던 카테고리는 실제 지출에 맞춰 내려 잡고, 잘 지킨 카테고리는 유지한다.
 * <b>올리는 선택지는 없다</b> — 성공했다고 더 조이면 성공이 벌이 된다.
 *
 * <p>성역과 연결 계좌는 건드리지 않는다. 매달 다시 고르게 하면 그건 설정이지 습관이 아니다.
 */
import { Icon } from '../components/Icons';
import { AppBar, Scroll, Screen, ErrorBox, Loading } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, type RenewalLine } from '../lib/api';
import { iconFor, won } from '../lib/format';

export function Renew() {
  const { go, back, userId } = useSession();
  const { data, loading, error, reload } = useAsync(() => api.guardian.renewal(userId), [userId]);

  if (loading) return <Loading label="조정안을 만드는 중" />;
  if (error) return <ErrorBox error={error} onRetry={reload} />;
  if (!data) return null;

  return (
    <Screen id="renew" title="다음 달 목표">
      <AppBar title="다음 달 목표" onBack={back} />
      <Scroll>
        <div className="pad">
          <div className="h-title">다음 달,<br />어떻게 갈까요?</div>
          <div className="h-sub">
            지난달 실적으로 지킴이가 조정안을 만들었어요.
            {data.sanctuaries.length > 0 ? ' 성역과 연결 계좌는 그대로예요.' : ' 연결 계좌는 그대로예요.'}
          </div>

          <div className="card" style={{ padding: '8px 20px' }}>
            {data.lines.map((l, i) => (
              <div key={l.category}>
                {i > 0 && <div className="divider" />}
                <Line row={l} />
              </div>
            ))}
            <div className="divider" />
            <div className="list-item" style={{ borderTop: '1px dashed var(--line)' }}>
              <div className="tx" style={{ paddingLeft: 2 }}>
                <b style={{ fontSize: 15 }}>다음 달 목표 저금액</b>
              </div>
              <span className="amt" style={{ color: 'var(--blue-t)', fontSize: 18 }}>
                {won(data.suggestedTargetSaving)}
              </span>
            </div>
          </div>

          <div className="pv">
            낮추는 건 후퇴가 아니에요 — <b>지킬 수 있는 목표</b>가 계속하게 만들어요. 다음 달에 다시 올리면 돼요.
          </div>
          <div className="spacer" style={{ height: 96 }} />
        </div>
      </Scroll>
      <div className="cta-fixed">
        {/* 추천대로 시작 — 챌린지 생성 화면으로 값을 들고 간다(ob3가 최종 확인을 맡는다). */}
        <button className="btn btn-primary" onClick={() => go('ob')}>추천대로 시작하기</button>
        <button className="btn btn-ghost" style={{ marginTop: 8 }} onClick={() => go('ob')}>
          직접 조정하기
        </button>
      </div>
    </Screen>
  );
}

function Line({ row }: { row: RenewalLine }) {
  const lower = row.action === 'LOWER';
  return (
    <div className="list-item">
      <span className="ic"><Icon id={iconFor(row.category)} /></span>
      <div className="tx">
        <b>
          {row.category}{' '}
          <span style={{ color: lower ? 'var(--amber-t)' : 'var(--green-t)', fontSize: 13 }}>
            {won(row.currentCap)} → {won(row.suggestedCap)} {lower ? '하향' : '유지'}
          </span>
        </b>
        <span>{Math.round(row.lastRate * 100)}% 달성 — {row.reason}</span>
      </div>
    </div>
  );
}
