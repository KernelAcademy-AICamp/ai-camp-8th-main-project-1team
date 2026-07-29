/**
 * 도감 (개편안 `s-collection`) — 모은 소품과 아직 못 모은 칸, 마일스톤 진행.
 *
 * <p><b>못 모은 칸을 자물쇠로 보여준다.</b> 가진 것만 늘어놓으면 인벤토리지 도감이 아니다.
 * 몇 칸이 남았는지 눈에 보여야 "네 칸만 더"가 된다.
 *
 * <p>칸을 누르면 아래 상세가 바뀐다 — 이름·등급·획득일·그날의 사연. 사연이 있어야 소품이
 * 장식이 아니라 기록이 된다.
 */
import { useEffect, useState } from 'react';
import { Icon } from '../components/Icons';
import { AppBar, Scroll, Screen, ErrorBox, Loading } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, type GuardianCollection, type CollectionCell } from '../lib/api';
import { ItemGlyph } from '../components/ItemGlyph';

const GRADE_LABEL: Record<string, string> = { COMMON: '일반', RARE: '희귀', EPIC: '에픽' };
/** 획득 사유 코드 → 사람 말. 서버가 코드로 주므로 표시는 화면 몫이다. */
const REASON_LABEL: Record<string, string> = {
  NO_SPEND_DAY: '무지출',
  ON_PACE_DAY: '페이스 유지',
  STREAK_BONUS: '연속 보너스',
  CRISIS_DEFENDED: '위기 방어',
  WEEKLY_MISSION: '주간 미션',
  SHOP_PURCHASE: '포인트샵',
  MONTHLY_COMPLETE: '완주 보너스',
};

export function Collection() {
  const { go, userId } = useSession();
  const state = useAsync(() => api.guardian.collection(userId), [userId]);
  const [sel, setSel] = useState<string | null>(null);
  const [claiming, setClaiming] = useState(false);
  const [data, setData] = useState<GuardianCollection | null>(null);

  useEffect(() => {
    if (state.data) setData(state.data);
  }, [state.data]);

  // 처음 열면 가장 최근에 받은 소품을 펼쳐 둔다 — 방금 무엇이 들어왔는지가 제일 궁금하다.
  useEffect(() => {
    if (sel || !data) return;
    const owned = data.cells.filter((c) => c.owned);
    if (owned.length) setSel(owned[owned.length - 1].code);
  }, [data, sel]);

  if (state.loading && !data) return <Loading label="도감을 여는 중" />;
  if (state.error && !data) return <ErrorBox error={state.error} onRetry={state.reload} />;
  if (!data) return null;

  const cell = data.cells.find((c) => c.code === sel) ?? null;
  const claimable = data.milestones.find((m) => data.owned >= m.count && !m.claimed);

  async function claim(count: number) {
    setClaiming(true);
    try {
      setData(await api.guardian.claimMilestone(userId, count));
    } catch {
      /* 실패해도 화면은 그대로 — 다시 누르면 된다. */
    } finally {
      setClaiming(false);
    }
  }

  return (
    <Screen title="도감">
      <AppBar title="도감" onBack={() => go('myroom')} steps={`${data.owned} / ${data.total} 수집`} />
      <Scroll>
        <div className="pad" style={{ paddingTop: 12 }}>
          <div className="card" style={{ padding: '16px 20px' }}>
            <div style={{ display: 'flex', alignItems: 'baseline', gap: 8 }}>
              <b style={{ fontSize: 20 }}>{data.percent}%</b>
              <span style={{ fontSize: 12, color: 'var(--t3)', fontWeight: 600 }}>
                {data.next
                  ? `다음 보상까지 ${data.next.count - data.owned}종 남았어요`
                  : '전부 모았어요!'}
              </span>
            </div>

            {/* 마일스톤 막대 — 점이 기준점, 채움이 현재 진행. */}
            <div className="mile-bar">
              <i style={{ width: `${data.percent}%` }} />
              {data.milestones.map((m) => (
                <span
                  key={m.count}
                  className={`mile-dot${data.owned >= m.count ? ' hit' : ''}`}
                  style={{ left: `${(m.count / data.total) * 100}%` }}
                />
              ))}
            </div>
            <div style={{ fontSize: 11, color: 'var(--t3)', display: 'flex', justifyContent: 'space-between' }}>
              {data.milestones.map((m) => (
                <span key={m.count}>{m.count}종, {m.label}</span>
              ))}
            </div>

            <div
              style={{
                marginTop: 12, paddingTop: 12, borderTop: '1px solid var(--bg)',
                fontSize: 12, color: 'var(--t2)', display: 'flex', alignItems: 'center', gap: 8,
              }}
            >
              <Icon id="i-shield" size={16} />
              <span>
                보유 아이템: <b>면제권 {data.exemption}장</b>
                {data.missionChange > 0 && <> · 미션 변경권 {data.missionChange}장</>}
                {data.grassGuard > 0 && <> · 잔디 보호권 {data.grassGuard}장</>}
                , 지출 1건을 차감에서 빼드려요
              </span>
            </div>

            {claimable && (
              <button
                className="btn btn-primary"
                style={{ marginTop: 12 }}
                disabled={claiming}
                onClick={() => claim(claimable.count)}
              >
                {claimable.count}종 보상 받기 — {claimable.label}
              </button>
            )}
          </div>

          <div className="col-grid" style={{ marginTop: 12 }}>
            {data.cells.map((c) =>
              c.owned ? (
                <div
                  key={c.code}
                  className={`col-cell${c.grade === 'RARE' || c.grade === 'EPIC' ? ' rare' : ''}${sel === c.code ? ' sel' : ''}`}
                  onClick={() => setSel(c.code)}
                >
                  <ItemGlyph glyph={c.glyph} />
                  <span>{c.name}</span>
                </div>
              ) : (
                <div key={c.code} className="col-cell locked">
                  <i>?</i>
                </div>
              ),
            )}
          </div>

          {cell && <Detail cell={cell} />}

          <div className="pv" style={{ marginTop: 12 }}>
            소품을 모을수록 <b>마일스톤 보상</b>이 열려요, 기록은 사라지지 않아요
          </div>
          <div className="spacer" style={{ height: 32 }} />
        </div>
      </Scroll>
    </Screen>
  );
}

function Detail({ cell }: { cell: CollectionCell }) {
  const rarity = cell.grade === 'COMMON' ? 'r-common' : cell.grade === 'RARE' ? 'r-rare' : 'r-furn';
  const when = cell.acquiredDate
    ? `${Number(cell.acquiredDate.slice(5, 7))}.${Number(cell.acquiredDate.slice(8, 10))}`
    : '';
  const why = cell.reason ? REASON_LABEL[cell.reason] ?? cell.reason : '';
  return (
    <div className="col-detail">
      <b>{cell.name}</b>
      <span className={`rar ${rarity}`} style={{ marginLeft: 6 }}>{GRADE_LABEL[cell.grade]}</span>
      <p className="dt">{[when, why].filter(Boolean).join(', ')}</p>
      <p>{cell.story}</p>
    </div>
  );
}
