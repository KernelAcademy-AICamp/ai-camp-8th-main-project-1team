/**
 * 마이룸 (게임화) — 방 씬 + 이번 주 현황 + 지킨 날 잔디 + 모은 사물.
 * 목업의 화면 구성을 그대로 두고 값만 서버(`/api/guardian/home`·`/api/guardian/room`)로 바꿨다.
 *
 * '오늘'은 브라우저 시계가 아니라 서버가 준 `asOf`다 — 데모에서 시계를 밀면 잔디도 같이 움직여야 한다.
 * 주간 미션은 백엔드에 조회 API가 없어(설계서 §9 미정), 같은 카드 모양에 잔디로 계산한 이번 주 현황을 넣었다.
 */
import { useMemo } from 'react';
import { Icon } from '../components/Icons';
import { AppBar, Scroll, Screen, ErrorBox, Loading, SectionTitle } from '../components/ui';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api } from '../lib/api';
import { GRASS_LEVEL, DAILY_RESULT_LABEL, GRADE_LABEL, GRADE_EMOJI, won } from '../lib/format';

const DOW = ['일', '월', '화', '수', '목', '금', '토'];
const DAY = 86_400_000;
/** 로컬 벽시계 기준 YYYY-MM-DD. toISOString()은 UTC라 KST 자정이 전날로 밀린다. */
const iso = (d: Date) =>
  `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;

export function Myroom() {
  const { go, userId } = useSession();
  const { home, loading, error, reload } = useGuardian();
  const room = useAsync(() => api.guardian.room(userId).catch(() => ({ objects: [], slotCount: 20 })), [userId]);

  const grid = useMemo(() => {
    if (!home) return { cells: [] as { date: string; level: number; day: number; today: boolean; result?: string }[], lead: 0 };
    const today = new Date(`${home.asOf.slice(0, 10)}T00:00:00`);
    const byDate = new Map(home.grass.map((g) => [g.date, g]));
    const cells = Array.from({ length: 30 }, (_, i) => {
      const d = new Date(today.getTime() - (29 - i) * DAY);
      const key = iso(d);
      const g = byDate.get(key);
      return {
        date: key,
        level: g ? GRASS_LEVEL[g.result] ?? 0 : 0,
        day: d.getDate(),
        today: i === 29,
        result: g?.result,
      };
    });
    const first = new Date(today.getTime() - 29 * DAY);
    return { cells, lead: first.getDay() };
  }, [home]);

  const week = useMemo(() => {
    const last7 = grid.cells.slice(-7);
    const kept = last7.filter((c) => c.level >= 2).length;
    return { kept, total: last7.length || 7 };
  }, [grid.cells]);

  if (loading && !home) {
    return (
      <Screen title="마이룸" hasTabBar>
        <AppBar onBack={() => go('home')} title="마이룸" />
        <div className="pad"><Loading label="마이룸을 불러오는 중" rows={6} /></div>
      </Screen>
    );
  }
  // 챌린지가 없으면 방도 아직 비어 있다 — 오류가 아니라 시작 전 상태다.
  if (!home) {
    return (
      <Screen title="마이룸" hasTabBar>
        <AppBar onBack={() => go('home')} title="마이룸" />
        <div className="pad">
          <ErrorBox error={error} onRetry={() => void reload()} />
          <div className="card" style={{ textAlign: 'center', padding: '28px 20px' }}>
            <div className="orb orb-bob" style={{ width: 72, height: 72, margin: '0 auto 14px' }} />
            <p style={{ fontSize: 17, fontWeight: 700, margin: '0 0 6px' }}>아직 방이 비어 있어요</p>
            <p className="empty" style={{ margin: '0 0 18px' }}>
              이번에 지킬 것을 정하면 지킨 날마다 사물이 하나씩 도착해요.
            </p>
            <button type="button" className="btn btn-primary" onClick={() => go('loading')}>지킬 것 정하러 가기</button>
          </div>
        </div>
      </Screen>
    );
  }

  const items = home.itemsHeld;
  const objects = room.data?.objects ?? [];
  const keptDays = home.grass.filter((g) => g.result === 'NO_SPEND_DAY' || g.result === 'ON_PACE_DAY').length;
  const gotToday = objects.some((o) => o.acquiredDate === home.asOf.slice(0, 10));

  return (
    <Screen title="마이룸" hasTabBar>
      <AppBar onBack={() => go('home')} title="마이룸" />
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>

        {/* 방 씬 */}
        <div className="scene" role="img" aria-label={`지킴이의 방 · 모은 사물 ${objects.length}개`}>
          <div className="sun" />
          <div className="sc-rug" />
          <div className="sc-plant"><span className="leaf l1" /><span className="leaf l2" /><span className="leaf l3" /><div className="pot" /><div className="shadow" /></div>
          <div className="sc-books"><i className="b3" style={{ width: 42 }} /><i className="b2" /><i className="b1" /><div className="shadow" /></div>
          <div className="sc-orb"><div className="orb orb-bob" style={{ width: 76, height: 76 }} /><div className="shadow" /></div>
          <div className="sc-lamp"><div className="shade" /><div className="pole" /><div className="base" /><div className="shadow" /></div>
          <div className={`sc-new${gotToday ? ' pop' : ''}`}><div className="dome" /><div className="nbase" /><div className="shadow" /></div>
          <div className="sc-hint">지킨 만큼 방이 채워져요 · 포인트로 아이템을 배치해요</div>
        </div>

        <div className="today-line">
          <span className="dot" aria-hidden="true" />
          <p>
            {home.strip.noSpendStreak > 0
              ? <><b>무지출 {home.strip.noSpendStreak}일째</b> — 자정까지 지키면 내일 아침 새 아이템이 도착해요</>
              : <><b>{home.challenge.categoryLabel}</b>를 지켜보는 중 — 한도 안에서는 조용히 있을게요</>}
          </p>
        </div>

        <div className="asset-row">
          <div className="asset">
            <b style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 3 }}>
              <Icon id="i-coin" className="" size={15} />{items.pointBalance}
            </b><span>꾸미기 포인트</span>
          </div>
          <div className="asset"><b>{objects.length}개</b><span>모은 사물</span></div>
          <div className="asset"><b>{home.strip.grassStreak}일</b><span>연속 지킴</span></div>
          <div className="asset"><b>{keptDays}일</b><span>이번 챌린지</span></div>
        </div>

        {/* 포인트샵·도감 진입 (개편안 `.entry-row`) — 방을 채우는 두 경로다.
            포인트샵은 사서 놓고, 도감은 지켜서 받는다. */}
        <div className="entry-row">
          <div className="entry" role="button" tabIndex={0}
               onClick={() => go('shop')}
               onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') go('shop'); }}>
            <Icon id="i-coin" className="" size={24} />
            <div><b>{items.pointBalance}P</b><span>포인트샵</span></div>
            <em>›</em>
          </div>
          <div className="entry" role="button" tabIndex={0}
               onClick={() => go('collection')}
               onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') go('collection'); }}>
            <Icon id="i-gift" className="" size={24} />
            <div><b>{objects.length}종</b><span>도감</span></div>
            <em>›</em>
          </div>
        </div>

        {/* 이번 주 현황 */}
        <SectionTitle aux="지킨 날 기준">이번 주</SectionTitle>
        <div className="mcard">
          <div className="mtop">
            <span className="mic" style={{ background: 'var(--green-weak)' }}><Icon id="i-shield" /></span>
            <span className="mtx">
              <b>이번 주 지킨 날 {week.kept} / {week.total}일</b>
              <span>무지출이거나 페이스 안에서 쓴 날을 셉니다</span>
            </span>
            <span className={`mchip ${week.kept >= 5 ? 'c-green' : week.kept >= 3 ? 'c-blue' : 'c-amber'}`}>
              {week.kept >= 5 ? '아주 좋아요' : week.kept >= 3 ? '괜찮아요' : '천천히'}
            </span>
          </div>
          <div className="mbar"><i style={{ width: `${(week.kept / week.total) * 100}%`, background: 'var(--green)' }} /></div>
        </div>
        <div className="mcard">
          <div className="mtop">
            <span className="mic" style={{ background: 'var(--blue-weak)' }}><Icon id="i-gift" /></span>
            <span className="mtx">
              <b>가진 아이템</b>
              <span>면제권은 결제를 챌린지에서 빼고, 잔디 보호권은 하루를 지켜줘요</span>
            </span>
          </div>
          <div className="chips" style={{ marginTop: 11 }}>
            <span className="chip static">면제권 {items.exemption}장</span>
            <span className="chip static">잔디 보호권 {items.grassGuard}장</span>
            <span className="chip static">미션 교체권 {items.missionChange}장</span>
          </div>
        </div>

        {/* 지킨 날 잔디 */}
        <SectionTitle aux="최근 30일">지킨 날</SectionTitle>
        <div className="grass-card">
          <div className="streak-line">
            <b><Icon id="i-flame" className="" size={17} />{home.strip.grassStreak}일 연속</b>
            <span>이번 챌린지 {keptDays}일 지킴 · 무지출 {home.strip.noSpendStreak}일째</span>
          </div>
          <div className="dow" aria-hidden="true">{DOW.map((d) => <div key={d}>{d}</div>)}</div>
          <div className="ggrid">
            {Array.from({ length: grid.lead }, (_, i) => (
              <div key={`lead${i}`} className="gcell" style={{ visibility: 'hidden' }} />
            ))}
            {grid.cells.map((c) => (
              <div key={c.date}
                className={`gcell${c.level ? ` g${c.level}` : ''}${c.today ? ' today' : ''}`}
                title={`${c.date} · ${c.result ? DAILY_RESULT_LABEL[c.result] : '기록 없음'}`}>
                <em>{c.day}</em>
              </div>
            ))}
          </div>
          <p className="empty" style={{ marginTop: 10, marginBottom: 0 }}>
            진한 칸일수록 잘 지킨 날이에요. 쓴 날에도 페이스 안이면 사물을 받아요 — 사물은 벌이 아니에요.
          </p>
        </div>

        {/* 모은 사물 */}
        <SectionTitle aux={`${objects.length} / ${room.data?.slotCount ?? 20}`}>모은 사물</SectionTitle>
        <div className="card">
          {objects.length === 0 ? (
            <p className="empty" style={{ margin: 0 }}>아직 모은 사물이 없어요. 하루를 지켜내면 다음 날 아침에 도착해요.</p>
          ) : (
            <div className="room-grid">
              {objects.map((o) => (
                <div key={o.objectId} className={`room-slot ${o.grade.toLowerCase()}`}
                  title={`${o.objectId} · ${GRADE_LABEL[o.grade]} · ${o.acquiredDate} 획득`}>
                  <span aria-hidden="true" style={{ fontSize: 20 }}>{GRADE_EMOJI[o.grade]}</span>
                  <span>{GRADE_LABEL[o.grade]}</span>
                </div>
              ))}
            </div>
          )}
        </div>

        {/* 보상은 내 돈이 아니라는 것을 계속 분명히 한다 */}
        <div className="pv">
          지킨 돈 <b>{won(home.challenge.securedSaving)}</b>은 그대로 내 계좌에 있어요.
          포인트와 사물은 방 꾸미기용이라 돈으로 바꾸지 않아요.
        </div>

        <div className="spacer" style={{ height: 30 }} />
      </div></Scroll>
    </Screen>
  );
}
