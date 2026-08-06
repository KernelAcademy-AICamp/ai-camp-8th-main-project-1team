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
import { Sheet } from '../components/Sheet';

const GRADE_LABEL: Record<string, string> = { COMMON: '일반', RARE: '희귀', EPIC: '에픽' };
/** 획득 사유 코드 → 사람 말. 서버가 코드로 주므로 표시는 화면 몫이다. */
const REASON_LABEL: Record<string, string> = {
  NO_SPEND_DAY: '무지출',
  ON_PACE: '페이스 유지',
  ON_PACE_DAY: '페이스 유지',
  STREAK_BONUS: '연속 보너스',
  CRISIS_DEFENDED: '위기 방어',
  WEEKLY_MISSION: '주간 미션',
  SHOP_PURCHASE: '포인트샵',
  MONTHLY_COMPLETE: '완주 보너스',
};

/**
 * 획득 사유를 사람 말로.
 *
 * <b>서버는 연속 일수를 코드에 붙여 보낸다</b> — `NO_SPEND_STREAK_3` 처럼. 표에 접두어만 두고
 * 찾으면 못 찾아 코드가 그대로 화면에 나온다(실제로 `NO_SPEND_STREAK_1` 이 노출됐다).
 * 숫자를 버리지 않고 살려서 "무지출 3일째"로 읽히게 한다 — 그 숫자가 이 사물을 받은 이유다.
 */
function reasonText(reason: string): string {
  const streak = /^NO_SPEND_STREAK_(\d+)$/.exec(reason);
  if (streak) return `무지출 ${streak[1]}일째`;
  return REASON_LABEL[reason] ?? reason;
}

export function Collection() {
  const { go, userId } = useSession();
  const state = useAsync(() => api.guardian.collection(userId), [userId]);
  const [sel, setSel] = useState<string | null>(null);
  const [claiming, setClaiming] = useState(false);
  const [data, setData] = useState<GuardianCollection | null>(null);

  useEffect(() => {
    if (state.data) setData(state.data);
  }, [state.data]);

  // **자동으로 고르지 않는다.** 예전에는 가장 최근 소품을 펼쳐 뒀는데, 상세가 목록 아래 붙어
  // 있을 때 이야기다. 시트로 바뀐 뒤로는 도감을 열자마자 시트가 덮어 격자가 안 보인다 —
  // 무엇을 모았는지 훑으러 온 화면에서 그건 방해다.

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

            {/* 마일스톤 막대 — 점이 기준점, 채움이 현재 진행.
                다음 보상의 점은 테두리를 파랗게 해 어디를 향해 가는지 표시한다. */}
            <div className="mile-bar">
              <i style={{ width: `${data.percent}%` }} />
              {data.milestones.map((m) => (
                <span
                  key={m.count}
                  className={`mile-dot${data.owned >= m.count ? ' hit' : ''}${data.next?.count === m.count ? ' next' : ''}`}
                  style={{ left: `${(m.count / data.total) * 100}%` }}
                />
              ))}
            </div>
            {/* 라벨은 **점 위치에 맞춰** 절대배치한다. 균등 분배(space-between)로 두었더니
                점은 10·15·21종 자리에 있는데 글자는 3등분 자리에 서서 서로를 안 가리켰다.
                마지막 것은 화면 밖으로 나가지 않게 왼쪽으로 더 당긴다(`.edge`). */}
            <div className="mile-labs">
              {data.milestones.map((m, i) => (
                <span key={m.count}
                  className={`${data.owned >= m.count ? 'on' : ''}${i === data.milestones.length - 1 ? ' edge' : ''}`}
                  style={{ left: `${(m.count / data.total) * 100}%` }}>
                  {m.count}종<i>{m.label}</i>
                </span>
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
                <button
                  type="button"
                  key={c.code}
                  className={`col-cell${c.grade === 'RARE' || c.grade === 'EPIC' ? ' rare' : ''}${sel === c.code ? ' sel' : ''}`}
                  aria-pressed={sel === c.code}
                  onClick={() => setSel(c.code)}
                >
                  <ItemGlyph glyph={c.glyph} />
                  <span>{c.name}</span>
                </button>
              ) : (
                <div key={c.code} className="col-cell locked">
                  <i>?</i>
                </div>
              ),
            )}
          </div>

          <div className="pv" style={{ marginTop: 12 }}>
            소품을 모을수록 <b>마일스톤 보상</b>이 열려요, 기록은 사라지지 않아요
          </div>
          <Detail cell={cell} onClose={() => setSel(null)} />
          <div className="spacer" style={{ height: 32 }} />
        </div>
      </Scroll>
    </Screen>
  );
}

/**
 * 사물 상세 — 목록 위에 뜨는 시트 (프로토타입_0806 `s-collection`).
 *
 * <b>왜 시트인가.</b> 도감은 격자를 훑는 화면이라 한 칸을 눌렀다고 화면을 옮기면 훑던 자리를
 * 잃는다. 예전에는 목록 <b>아래</b>에 상세를 폈는데, 격자 위쪽 칸을 누르면 설명이 화면 밖에
 * 생겨 아무 일도 안 일어난 것처럼 보였다.
 *
 * <b>영웅샷은 연출이다.</b> 뒤에서 빛이 도는 원을 깔아 "받은 것"으로 보이게 한다 — 격자 안의
 * 작은 칸일 때와 같은 그림인데, 크게 놓고 빛을 주면 기념품이 된다.
 */
function Detail({ cell, onClose }: { cell: CollectionCell | null; onClose: () => void }) {
  const rarity = !cell ? '' : cell.grade === 'COMMON' ? 'r-common' : cell.grade === 'RARE' ? 'r-rare' : 'r-furn';
  const when = cell?.acquiredDate
    ? `${Number(cell.acquiredDate.slice(5, 7))}.${Number(cell.acquiredDate.slice(8, 10))}`
    : '';
  const why = cell?.reason ? reasonText(cell.reason) : '';
  return (
    <Sheet open={cell !== null} onClose={onClose} title={cell?.name ?? '사물'}>
      {cell && (
        <>
          <div className="cd-hero"><ItemGlyph glyph={cell.glyph} /></div>
          <div className="cd-name">
            {cell.name}<span className={`rar ${rarity}`}>{GRADE_LABEL[cell.grade]}</span>
          </div>
          <div className="cd-meta">{[when, why].filter(Boolean).join(', ')}</div>
          <p className="cd-story">{cell.story}</p>
        </>
      )}
    </Sheet>
  );
}
