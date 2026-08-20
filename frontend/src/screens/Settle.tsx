/**
 * 월간 결산 (개편안 `s-settle`) — 한 달을 숫자로 셈해 보여준다.
 *
 * <p><b>방어율이 주인공이다.</b> "얼마 썼나"가 아니라 "얼마를 지켜냈나"로 말한다 — 같은 사실이지만
 * 앞의 것은 실패를 세고 뒤의 것은 성과를 센다. 카테고리별로도 '목표 12.5만 중 8.4만 지킴'처럼
 * 지켜낸 쪽을 앞에 둔다.
 *
 * <p>부분 달성을 실패로 표시하지 않는다. 67%는 3분의 2를 지켰다는 뜻이지 못 지켰다는 뜻이 아니다.
 */
import { Icon } from '../components/Icons';
import { AppBar, Scroll, Screen, ErrorBox, Loading } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, type KeptMoneyPlan, type SettlementCategory } from '../lib/api';
import { iconFor, won } from '../lib/format';

/** 달성률이 이 이상이면 '달성', 미만이면 '부분 달성'. 실패라는 말은 쓰지 않는다. */
const FULL = 0.9;

export function Settle() {
  const { go, back, userId } = useSession();
  const { data, loading, error, reload } = useAsync(() => api.guardian.settlement(userId), [userId]);

  if (loading) return <Loading label="결산을 셈하는 중" />;
  if (error) return <ErrorBox error={error} onRetry={reload} />;
  if (!data) return null;

  const range = `${fmtDate(data.startDate)}~${fmtDate(data.endDate)}`;
  const month = Number(data.endDate.slice(5, 7));

  return (
    <Screen title="월간 결산">
      <AppBar title={`${month}월 챌린지 결산`} onBack={back} steps={range} />
      <Scroll>
        <div className="pad">
          <div className="h-title">한 달, 수고했어요</div>
          <div className="h-sub">30일이 끝났어요. 지킴이가 정산해봤어요.</div>

          <div className="hero" style={{ marginTop: 4 }}>
            <div className="cap">최종 방어율</div>
            <div className="big">{Math.round(data.defenseRate * 100)}%</div>
            <div className="sub">
              목표 {won(data.targetSaving)} 중 {won(data.securedSaving)}을 지켜냈어요
            </div>
          </div>

          <div className="card" style={{ padding: '8px 20px' }}>
            {data.categories.map((c, i) => (
              <div key={c.category}>
                {i > 0 && <div className="divider" />}
                <CategoryRow row={c} />
              </div>
            ))}
          </div>

          <div className="asset-row">
            <div className="asset"><b>{data.keptDays}일</b><span>지킨 날</span></div>
            <div className="asset"><b>{data.bestStreak}일</b><span>최장 연속</span></div>
            <div className="asset"><b>+{data.pointsEarned}P</b><span>이번 달 획득</span></div>
            <div className="asset"><b>{data.objectsCollected}종</b><span>모은 소품</span></div>
          </div>

          <div className="pv">
            완주 보너스 <b>+{data.completionBonus}P</b>가 지급됐어요, 마이룸과 소품은 다음 달에도 그대로 이어져요
          </div>

          <KeptMoneyParking userId={userId} />

          <div className="spacer" style={{ height: 20 }} />
        </div>
      </Scroll>
      <div className="cta-fixed">
        <button className="btn btn-primary" onClick={() => go('renew')}>다음 달 준비하기</button>
      </div>
    </Screen>
  );
}

/**
 * 지킨 돈 굴리기 (문서 §4.7) — "이 돈을 그냥 두실 건가요"에 숫자로 답한다.
 *
 * <p><b>개인화가 아니다.</b> 금액만 결산에서 자동으로 채워질 뿐, 같은 금액이면 누구나 같은 답을 받는다.
 * 우대조건 충족 여부는 판정하지 않는다.
 *
 * <p><b>보여줄 게 없으면 아무것도 그리지 않는다</b> — 서버가 204를 준다(지킨 돈 0 · 파킹 조회 막힘).
 * 0원짜리 블록을 띄워 없는 성과를 축하하지 않는다.
 *
 * <p><b>표현의 선.</b> `이 페이스로 1년이면`은 가정이므로 각주로 밝힌다. 목표를 새로 만들거나
 * (`100만원까지 얼마 안 남았어요`) 페이스를 압박하는 문구는 쓰지 않는다(R9).
 */
function KeptMoneyParking({ userId }: { userId: number }) {
  const { data } = useAsync(() => api.keptMoneyParking(userId), [userId]);
  if (!data || data.options.length === 0) return null;

  const plan: KeptMoneyPlan = data;
  const best = plan.options[0];   // 기본금리순 상위 — 아래 금액은 이 금리 기준이다
  const years = plan.projectionMonths / 12;
  const term = years === 1 ? '1년' : `${plan.projectionMonths}개월`;

  return (
    <div className="card" style={{ marginTop: 12, padding: '16px 20px' }}>
      <div className="h-title" style={{ fontSize: 17 }}>지킨 돈, 그냥 두실 건가요?</div>
      <div className="h-sub">파킹통장은 언제든 넣고 뺄 수 있어요.</div>

      <div className="divider" />
      <div className="list-item">
        <div className="tx">
          <b>이 페이스로 {term}이면</b>
          <span>{won(plan.pacePrincipal)} + 이자 {won(best.paceInterest)}</span>
        </div>
        <span className="amt">{won(best.paceTotal)}</span>
      </div>

      <div className="divider" />
      <div className="list-item">
        <div className="tx">
          <b>지금까지 모은 돈을 {term} 두면</b>
          <span>{won(plan.cumulative)} + 이자 {won(best.keptInterest)}</span>
        </div>
        <span className="amt">{won(best.keptTotal)}</span>
      </div>

      <div className="divider" />
      {plan.options.map((o) => (
        <div key={`${o.company}:${o.name}`} className="list-item">
          <div className="tx">
            <b>{o.name}</b>
            <span>{o.company}</span>
          </div>
          <span className="tag-good">연 {o.baseRate.toFixed(2)}%</span>
        </div>
      ))}

      <div className="pv" style={{ marginTop: 8 }}>
        위 금액은 연 {best.baseRate.toFixed(2)}% 기준이고, <b>이 페이스가 이어진다고 가정</b>한 값이에요.
        이자는 세금(15.4%)을 뺀 금액이에요. {plan.asOf} 공시 기준 · 가입은 각 금융사에서 하세요.
      </div>
    </div>
  );
}

function CategoryRow({ row }: { row: SettlementCategory }) {
  const pct = Math.round(row.rate * 100);
  const full = row.rate >= FULL;
  return (
    <div className="list-item">
      <span className="ic"><Icon id={iconFor(row.category)} /></span>
      <div className="tx">
        <b>{row.category}</b>
        <span>목표 {won(row.cap)} 중 {won(row.kept)} 지킴</span>
      </div>
      <span className={full ? 'tag-good' : 'tag-bad'}>
        {pct}% {full ? '달성' : '부분 달성'}
      </span>
    </div>
  );
}

const fmtDate = (iso: string) => `${Number(iso.slice(5, 7))}.${Number(iso.slice(8, 10))}`;
