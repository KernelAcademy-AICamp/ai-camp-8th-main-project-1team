/**
 * 마이룸 (게임화) — 방 씬 + 이번 주 현황 + 지킨 날 잔디 + 모은 사물.
 * 목업의 화면 구성을 그대로 두고 값만 서버(`/api/guardian/home`·`/api/guardian/room`)로 바꿨다.
 *
 * '오늘'은 브라우저 시계가 아니라 서버가 준 `asOf`다 — 데모에서 시계를 밀면 잔디도 같이 움직여야 한다.
 * 주간 미션은 백엔드에 조회 API가 없어(설계서 §9 미정), 같은 카드 모양에 잔디로 계산한 이번 주 현황을 넣었다.
 */
import { useEffect, useMemo, useState } from 'react';
import { Icon } from '../components/Icons';
import { Modal } from '../components/Sheet';
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

/**
 * 세리머니를 하루에 한 번만 띄우려고 마지막으로 본 판정일을 남긴다.
 *
 * <b>왜 마이룸인가.</b> 개편안은 마이룸에 들어올 때 소품이 도착한다 — 방이 채워지는 것을
 * 눈앞에서 보여 주는 연출이다. 우리는 홈에서 띄우고 있었는데, 그러면 방을 보지도 않은 채
 * 모달만 닫게 되어 "무엇이 어디에 놓였는지"가 남지 않는다.
 */
const SEEN_KEY = 'guardian_ceremony_seen';
const readSeen = () => { try { return localStorage.getItem(SEEN_KEY) ?? ''; } catch { return ''; } };
const writeSeen = (d: string) => { try { localStorage.setItem(SEEN_KEY, d); } catch { /* noop */ } };

export function Myroom() {
  const { go, userId } = useSession();
  const [editing, setEditing] = useState(false);
  /** 이동 중인 소품 코드 — 연타로 서버에 두 번 보내지 않게 잠근다. */
  const [moving, setMoving] = useState<string | null>(null);
  const { home, loading, error, reload } = useGuardian();
  const [ceremonyOpen, setCeremonyOpen] = useState(false);
  const ceremony = home?.ceremony ?? null;

  // 방이 그려진 뒤에 뜨도록 한 박자 늦춘다(개편안도 450ms 뒤에 연다).
  useEffect(() => {
    if (!ceremony || readSeen() === ceremony.verdictDate) return;
    const t = setTimeout(() => setCeremonyOpen(true), 450);
    return () => clearTimeout(t);
  }, [ceremony]);

  function closeCeremony() {
    if (ceremony) writeSeen(ceremony.verdictDate);
    setCeremonyOpen(false);
  }

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

  /**
   * 창고에서 올릴 때 쓸 빈 자리. 다 찼으면 null이라 버튼이 막힌다.
   *
   * **이른 반환보다 위에 있어야 한다.** 아래(로딩·빈 방 분기 뒤)에 두었더니 첫 렌더는 로딩으로
   * 빠져 이 훅을 건너뛰고, 데이터가 온 두 번째 렌더에서야 실행돼 훅 개수가 달라졌다 —
   * React가 "Rendered more hooks than during the previous render"로 죽어 마이룸이 열리지 않는다.
   */
  const nextFreeSlot = useMemo(() => {
    const objs = room.data?.objects ?? [];
    const slots = room.data?.slotCount ?? 20;
    const used = new Set(objs.filter((o) => o.slotIndex !== null).map((o) => o.slotIndex));
    for (let i = 0; i < slots; i++) if (!used.has(i)) return i;
    return null;
  }, [room.data]);

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
  const placed = objects.filter((o) => o.slotIndex !== null);
  const stored = objects.filter((o) => o.slotIndex === null);

  /** 자리를 옮긴다. 서버가 방 전체를 돌려주므로 그 응답으로 화면을 갱신한다. */
  async function move(objectId: string, slot: number | null) {
    if (moving) return;
    setMoving(objectId);
    try {
      room.set(await api.guardian.placeObject(userId, objectId, slot));
    } catch {
      room.reload();       // 실패하면 서버 상태로 되돌린다 — 화면만 옮겨져 있으면 거짓말이 된다
    } finally {
      setMoving(null);
    }
  }
  const keptDays = home.grass.filter((g) => g.result === 'NO_SPEND_DAY' || g.result === 'ON_PACE_DAY').length;
  const gotToday = objects.some((o) => o.acquiredDate === home.asOf.slice(0, 10));
  /** 남은 한도 비율 — 게이지를 '남은 여유'로 채우기 위한 값. 한도가 0이면 0으로 둔다. */
  const capLeftRatio = home.challenge.challengeCap > 0
    ? Math.max(0, Math.min(1, home.challenge.remainingCap / home.challenge.challengeCap))
    : 0;

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

        {/* 마이룸 히어로 (개편안 `.mr-hero`) — 연속 방어 · 오늘 진행 · 내일의 약속.
            게이지는 한도 대비 쓴 비율이 아니라 **남은 여유**를 채운다. 다 쓰면 비고 안 쓰면 가득 차
            "지킬수록 는다"가 눈에 보인다 — 소진율을 채우면 잘 지킨 사람의 막대가 비어 버린다. */}
        <div className="mr-hero">
          <div className="streakrow">
            <Icon id="i-flame" className="" size={20} />
            {home.strip.grassStreak > 0 ? `${home.strip.grassStreak}일 연속 방어 중` : '오늘부터 다시 시작'}
            <small>이번 챌린지 {keptDays}일 지킴</small>
          </div>
          <div className="day-gauge">
            <div className="lbl">
              <span>{home.strip.noSpendStreak > 0 ? '오늘 무지출 진행 중' : home.challenge.categoryLabel}</span>
              <span>{home.strip.remainingCapLabel}</span>
            </div>
            <div className="gbar">
              <i style={{ width: `${Math.round(capLeftRatio * 100)}%` }} />
            </div>
          </div>
          <p className="promise">
            {home.strip.noSpendStreak > 0
              ? '오늘을 지키면 내일 아침, 방에 새 소품이 도착해요'
              : '한도 안에서 쓴 날에도 소품은 와요 — 소품은 벌이 아니에요'}
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

        {/* 모은 사물 · 꾸미기 모드 (개편안 '꾸미기 모드')
            방에 놓인 것과 창고에 있는 것을 갈라 보여주고, 눌러서 옮긴다. 내려도 사라지지 않는다 —
            도감의 기록이라 지울 수 없고, "바꿨더니 없어졌다"를 겪게 하면 안 된다. */}
        <SectionTitle
          onAux={() => setEditing((v) => !v)}
          auxLabel={editing ? '완료' : '꾸미기'}
        >
          모은 사물 {placed.length} / {room.data?.slotCount ?? 20}
        </SectionTitle>
        {editing && (
          <p className="pv" style={{ marginTop: 0 }}>
            놓을 자리를 눌러 바꿔 보세요. 내린 소품은 <b>창고</b>로 가고 도감에는 그대로 남아요.
          </p>
        )}
        <div className="card">
          {objects.length === 0 ? (
            <p className="empty" style={{ margin: 0 }}>아직 모은 사물이 없어요. 하루를 지켜내면 다음 날 아침에 도착해요.</p>
          ) : (
            <>
              <div className="room-grid">
                {placed.map((o) => (
                  <button key={o.objectId} type="button" disabled={!editing || moving !== null}
                    className={`room-slot ${o.grade.toLowerCase()}`}
                    onClick={() => void move(o.objectId, null)}
                    title={`${o.name} · ${GRADE_LABEL[o.grade]} · ${o.acquiredDate} 획득`}>
                    <span aria-hidden="true" style={{ fontSize: 20 }}>{GRADE_EMOJI[o.grade]}</span>
                    <span>{o.name}</span>
                    {editing && <em style={{ fontSize: 10, color: 'var(--t3)', fontStyle: 'normal' }}>내리기</em>}
                  </button>
                ))}
                {placed.length === 0 && (
                  <p className="empty" style={{ margin: 0 }}>방이 비어 있어요. 아래 창고에서 올려 보세요.</p>
                )}
              </div>

              {stored.length > 0 && (
                <>
                  <div className="divider" style={{ margin: '12px 0' }} />
                  <p className="h-sub" style={{ margin: '0 0 8px' }}>창고 {stored.length}개</p>
                  <div className="room-grid">
                    {stored.map((o) => (
                      <button key={o.objectId} type="button" disabled={!editing || moving !== null}
                        className={`room-slot ${o.grade.toLowerCase()}`}
                        style={{ opacity: 0.6 }}
                        onClick={() => nextFreeSlot !== null && void move(o.objectId, nextFreeSlot)}
                        title={`${o.name} · 창고`}>
                        <span aria-hidden="true" style={{ fontSize: 20 }}>{GRADE_EMOJI[o.grade]}</span>
                        <span>{o.name}</span>
                        {editing && <em style={{ fontSize: 10, color: 'var(--blue-t)', fontStyle: 'normal' }}>올리기</em>}
                      </button>
                    ))}
                  </div>
                </>
              )}
            </>
          )}
        </div>

        {/* 보상은 내 돈이 아니라는 것을 계속 분명히 한다 */}
        <div className="pv">
          지킨 돈 <b>{won(home.challenge.securedSaving)}</b>은 그대로 내 계좌에 있어요.
          포인트와 사물은 방 꾸미기용이라 돈으로 바꾸지 않아요.
        </div>

        <div className="spacer" style={{ height: 30 }} />
      </div></Scroll>

      {/* 아침 세리머니 — 방에 들어왔을 때 소품이 도착한다(개편안 openMyroom). */}
      <Modal open={ceremonyOpen && !!ceremony} onClose={closeCeremony} title="지킴이 세리머니">
        {ceremony && (
          <>
            <div className="orb orb-bob" />
            <h3>{ceremony.result === 'NO_SPEND_DAY' ? '어젯밤을 지켜냈어요!' : '어제도 잘 지켰어요'}</h3>
            <p>
              {ceremony.message ?? '새 소품이 도착했어요'}
              {ceremony.objectId && (
                <><br /><b style={{ color: 'var(--blue-t)' }}>
                  {GRADE_EMOJI[ceremony.grade ?? 'COMMON']} {ceremony.objectId}
                </b> · {GRADE_LABEL[ceremony.grade ?? 'COMMON']} 등급</>
              )}
            </p>
            <p className="fine">포인트는 방 꾸미기 전용이에요 · 내 돈은 그대로 내 계좌에</p>
            <button type="button" className="btn btn-primary" style={{ padding: 14 }}
              onClick={() => { closeCeremony(); void reload(); }}>방에 두기</button>
          </>
        )}
      </Modal>
    </Screen>
  );
}
