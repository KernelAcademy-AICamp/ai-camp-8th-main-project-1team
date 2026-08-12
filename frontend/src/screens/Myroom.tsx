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
import { IsoRoom, CAT_ACT_LABEL, type CatAct, type RoomSel } from '../components/IsoRoom';
import { KeptDays } from '../components/KeptDays';
import { DecoSheet, type DecoTab, type DecoItem } from '../components/DecoSheet';
import { ItemGlyph } from '../components/ItemGlyph';
import { MissionSheet } from '../components/MissionSheet';
import { AppBar, Scroll, Screen, ErrorBox, Loading, SectionTitle } from '../components/ui';
import { useSession, CEREMONY_SEEN_KEY } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api } from '../lib/api';
import { GRADE_LABEL, GRADE_EMOJI, won } from '../lib/format';

const DAY = 86_400_000;
/** 로컬 벽시계 기준 YYYY-MM-DD. toISOString()은 UTC라 KST 자정이 전날로 밀린다. */

/**
 * 세리머니를 하루에 한 번만 띄우려고 마지막으로 본 판정일을 남긴다.
 *
 * <b>왜 마이룸인가.</b> 개편안은 마이룸에 들어올 때 소품이 도착한다 — 방이 채워지는 것을
 * 눈앞에서 보여 주는 연출이다. 우리는 홈에서 띄우고 있었는데, 그러면 방을 보지도 않은 채
 * 모달만 닫게 되어 "무엇이 어디에 놓였는지"가 남지 않는다.
 */
const readSeen = () => { try { return localStorage.getItem(CEREMONY_SEEN_KEY) ?? ''; } catch { return ''; } };
const writeSeen = (d: string) => { try { localStorage.setItem(CEREMONY_SEEN_KEY, d); } catch { /* noop */ } };

/**
 * 소품을 누르면 나오는 한 줄. 개편안 `objInfo` 를 옮겼다.
 * 방을 "예쁜 그림"이 아니라 "지켜서 모은 것"으로 읽히게 하는 장치라, 문장에 유래를 담는다.
 */
const OBJ_INFO: Record<string, string> = {
  lamp: '스탠드 조명 — 첫 주를 지켜냈을 때 도착했어요',
  frame: '그린 액자 — 무지출 3일 연속의 기록이에요',
  table: '사이드테이블 — 포인트샵에서 데려왔어요',
  moodlight: '무드등 — 어젯밤을 지켜낸 선물이에요',
  sofa: '민트 소파 — 포인트샵에서 150P에 만나요',
  bed: '포근한 침대 — 냥지킴이가 제일 좋아하는 자리',
  shelf: '원목 책장 — 읽은 만큼 채워져요',
  bowl: '머그컵 — 집에서 내려 마신 날의 흔적',
  yarn: '털뭉치 — 냥지킴이의 장난감',
  plant: '몬스테라 — 물 주는 걸 잊어도 잘 자라요',
  cat: '냥지킴이 — 오늘도 방을 지키고 있어요',
  rug: '핑크 러그 — 가운데를 포근하게',
  rug2: '민트 러그 — 가운데를 산뜻하게',
};

/** 꾸미기 서랍의 재고. 개편안 `TRAY_INV`. */
const TRAY_INV: Record<string, { label: string; items: { k: string; n: string }[] }> = {
  rug: { label: '러그 자리', items: [{ k: 'rug', n: '핑크 러그' }, { k: 'rug2', n: '민트 러그' }] },
  bed: { label: '침대 자리', items: [{ k: 'bed', n: '포근한 침대' }] },
  table: { label: '테이블 자리', items: [{ k: 'table', n: '사이드테이블' }] },
};

/** 냥지킴이 행동 후보 — 소파가 있으면 소파에서도 논다(개편안 openMyroom). */
const CAT_ACTS: CatAct[] = ['nap', 'read', 'rug'];

/** 미션 종류 → 아이콘·바탕색. 카테고리 이름이 아니라 **조건 유형**으로 가른다(원칙 4). */
const MISSION_KIND: Record<string, { icon: string; bg: string }> = {
  MAX_COUNT: { icon: 'i-card', bg: 'var(--blue-weak)' },
  AVOID_SLOT: { icon: 'i-bell', bg: 'var(--c-cafe)' },
  NO_SPEND_STREAK_MIN: { icon: 'i-flame', bg: 'var(--c-shop)' },
  LABELING_COUNT_MIN: { icon: 'i-doc', bg: 'var(--c-cvs)' },
};
const missionIcon = (t: string) => MISSION_KIND[t]?.icon ?? 'i-shield';
const missionBg = (t: string) => MISSION_KIND[t]?.bg ?? 'var(--bg)';
/** 미션 줄 위에 얹는 한 마디 — 개편안은 목적을 위, 미션명을 아래에 둔다. */
const MISSION_STATUS: Record<string, string> = {
  ONGOING: '이번 주 진행 중',
  SUCCESS: '이번 주 성공',
  FAILED: '이번 주 아쉬웠어요',
};

export function Myroom() {
  const { go, userId } = useSession();
  const [editing, setEditing] = useState(false);
  /** 이동 중인 소품 코드 — 연타로 서버에 두 번 보내지 않게 잠근다. */
  const [moving, setMoving] = useState<string | null>(null);
  const { home, loading, error, reload } = useGuardian();
  const [ceremonyOpen, setCeremonyOpen] = useState(false);
  /** 자리별로 고른 소품. 개편안 `ROOM_SEL` — 꾸미기에서 바꾼다. */
  const [sel, setSel] = useState<RoomSel>({ rug: 'rug' });
  /** 열려 있는 꾸미기 서랍(자리 이름). null이면 닫혀 있다. */
  const [tray, setTray] = useState<keyof RoomSel | 'bed' | 'table' | null>(null);
  /** 누른 소품의 설명. 개편안 `objInfo` → `.iso-pop`. */
  const [pop, setPop] = useState<string | null>(null);
  /** 꾸미기 시트에서 보고 있는 탭. */
  const [decoTab, setDecoTab] = useState<DecoTab>('furn');
  /**
   * 냥지킴이 행동 — 진입할 때 한 번 정한다.
   * `useState` 초기값으로 뽑는 이유: 렌더마다 다시 뽑으면 고양이가 방 안을 순간이동한다.
   */
  const [catAct] = useState<CatAct>(() => CAT_ACTS[Math.floor(Math.random() * CAT_ACTS.length)]);
  const ceremony = home?.ceremony ?? null;

  // 방이 그려진 뒤에 뜨도록 한 박자 늦춘다(개편안도 450ms 뒤에 연다).
  useEffect(() => {
    if (!ceremony || readSeen() === ceremony.verdictDate) return;
    const t = setTimeout(() => setCeremonyOpen(true), 450);
    return () => clearTimeout(t);
  }, [ceremony]);

  // 소품 설명은 2.5초 뒤 스스로 사라진다(개편안 objInfo). 누를 대상이 아니라 알림이다.
  useEffect(() => {
    if (!pop) return;
    const t = setTimeout(() => setPop(null), 2500);
    return () => clearTimeout(t);
  }, [pop]);

  function closeCeremony() {
    if (ceremony) writeSeen(ceremony.verdictDate);
    setCeremonyOpen(false);
  }

  const room = useAsync(() => api.guardian.room(userId).catch(() => ({ objects: [], slotCount: 20 })), [userId]);
  /** 고를 수 있는 털색과 지금 고른 것. 서버가 보유를 판정한다(안 산 색은 못 고른다). */
  const skins = useAsync(() => api.guardian.catSkins(userId).catch(() => []), [userId]);
  /** 주간 미션 보드. 못 불러와도 방은 그려야 하므로 실패를 삼킨다. */
  const board = useAsync(() => api.guardian.missions(userId).catch(() => null), [userId]);
  const [msOpen, setMsOpen] = useState(false);
  /** 지금 담아 둔 다음 주 미션의 후보 키 — 시트를 열면 여기에 표시가 가 있다. */
  const pickedKey = board.data?.next[0]?.candidateKey ?? null;
  const catSkin = skins.data?.find((s) => s.selected)?.key ?? 'cat';

  /** 털색을 바꾼다. 서버가 막으면 목록이 그대로라 화면도 안 바뀐다. */
  async function pickSkin(key: string) {
    try { skins.set(await api.guardian.chooseCatSkin(userId, key)); } catch { /* 못 고르는 색 — 무시 */ }
  }

  /**
   * 꾸미기 시트에 무엇을 보일지.
   *
   * <b>탭마다 고르는 방식이 다르다.</b> 가구·소품은 방에 놓였는지를 켜고 끄고(여러 개가 함께
   * 놓인다), 배경·캐릭터는 하나를 고른다. 그래서 `on` 의 뜻이 앞은 "놓여 있다", 뒤는 "정했다"다.
   *
   * 가진 것은 서버가 안다({@code room.objects}) — 화면이 판정하지 않는다.
   */
  function decoItems(): DecoItem[] {
    const has = (code: string) => objects.some((o) => o.objectId === code);
    switch (decoTab) {
      case 'furn':
        // 방이 처음부터 그리는 것과 상점에서 사는 것을 가른다(IsoRoom 참고).
        // 씬이 늘 그리는 침대·테이블을 '없음'으로 두면 목록과 방이 서로 다른 말을 한다.
        return [
          { key: 'rug', name: '핑크 러그', glyph: 'rug', owned: true, on: sel.rug === 'rug' },
          { key: 'rug2', name: '민트 러그', glyph: 'rug2',
            owned: has('furn_rug_mint'), on: sel.rug === 'rug2' },
          { key: 'bed', name: '포근한 침대', glyph: 'bed', owned: true, on: true },
          { key: 'table', name: '사이드테이블', glyph: 'table', owned: true, on: true },
          { key: 'sofa', name: '민트 소파', glyph: 'sofa',
            owned: has('furn_sofa_mint'), on: has('furn_sofa_mint') },
        ];
      case 'prop':
        // 소품은 방의 기본 구성이다. 무드등만 세리머니로 도착한다.
        return [
          { key: 'frame', name: '그린 액자', glyph: 'frame', owned: true, on: true },
          { key: 'plant', name: '몬스테라 화분', glyph: 'plant', owned: true, on: true },
          { key: 'bowl', name: '냥이 밥그릇', glyph: 'bowl', owned: true, on: true },
          { key: 'yarn', name: '실뭉치', glyph: 'yarn', owned: true, on: true },
          { key: 'mood', name: '무드등', glyph: 'mood', owned: gotToday, on: gotToday },
        ];
      case 'bg':
        // 벽지·바닥은 아직 씬이 그리지 못한다 — 있는 척하지 않고 잠근 채로 보인다.
        // 상점에는 팔고 있으므로 목록에서 빼지 않는다(무엇이 남았는지 보여야 한다).
        return [
          { key: 'wall1', name: '민트 벽지', owned: false, on: false },
          { key: 'wall2', name: '크림 벽지', owned: false, on: false },
          { key: 'floor1', name: '다크우드 바닥', owned: false, on: false },
          { key: 'floor2', name: '체크 바닥', owned: false, on: false },
        ];
      case 'char':
        return (skins.data ?? []).map((s) => ({
          key: s.key, name: s.name, glyph: s.glyph, owned: s.owned, on: s.selected,
        }));
    }
  }

  /** 시트에서 무엇을 눌렀나 — 탭마다 하는 일이 다르다. */
  function onDecoPick(key: string) {
    if (decoTab === 'char') { void pickSkin(key); return; }
    if (key === 'rug' || key === 'rug2') { setSel({ rug: key }); return; }
    // 가구·소품 배치는 서버의 자리 이동을 쓴다(창고 ↔ 방).
    setPop(OBJ_INFO[key] ?? null);
  }

  /**
   * 이번 주 지킨 날 — 히어로의 막대가 쓴다.
   *
   * 잔디 격자는 {@link KeptDays} 가 스스로 만든다. 예전에는 여기서 30일치를 계산해 넘겼는데,
   * 개편안이 '이번 주 7칸 + 접히는 월 달력'으로 바뀌면서 격자의 모양을 화면이 정하게 됐다 —
   * 계산을 두 곳에 두면 주 시작 요일 같은 것이 갈린다.
   */
  const week = useMemo(() => {
    if (!home) return { kept: 0, total: 7 };
    const today = new Date(`${home.asOf.slice(0, 10)}T00:00:00`);
    const start = new Date(today.getTime() - 6 * DAY);
    const kept = home.grass.filter((g) => {
      const d = new Date(`${g.date}T00:00:00`);
      return d >= start && d <= today
        && (g.result === 'NO_SPEND_DAY' || g.result === 'ON_PACE_DAY');
    }).length;
    return { kept, total: 7 };
  }, [home]);

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
  /** 남은 예산 비율 — 게이지를 '남은 여유'로 채우기 위한 값. 예산이 0이면 0으로 둔다. */
  const capLeftRatio = home.challenge.challengeCap > 0
    ? Math.max(0, Math.min(1, home.challenge.remainingCap / home.challenge.challengeCap))
    : 0;

  return (
    <Screen title="마이룸" hasTabBar>
      {/* 개편안은 앱바 대신 씬 위에 뜨는 두 버튼을 쓴다 — 방을 가리지 않으려는 배치다. */}
      <button type="button" className="mr-back" onClick={() => go('home')} aria-label="홈으로">‹</button>
      <button type="button" className={`mr-edit${editing ? ' on' : ''}`} aria-pressed={editing}
              onClick={() => { setEditing((v) => !v); setTray(null); setPop(null); }} aria-label="꾸미기">
        <svg viewBox="0 0 24 24" width={16} height={16} fill="none" stroke="currentColor"
             strokeWidth={2} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
          <path d="M12 20h9" /><path d="M16.5 3.5a2.1 2.1 0 0 1 3 3L7 19l-4 1 1-4Z" />
        </svg>
      </button>
      <Scroll>

        {/* 방 씬 — 개편안 `buildIsoScene` 을 그대로 옮긴 아이소메트릭 SVG.
            평면 일러스트로는 소품이 늘어날 때 겹침 순서가 무너져 침대 위에 러그가 깔렸다. */}
        <div className="scene-full" role="img"
             aria-label={`지킴이의 방 · 모은 사물 ${objects.length}개 · ${CAT_ACT_LABEL[catAct]}`}>
          <div className="mr-title">마이룸</div>
          <IsoRoom
            sel={sel}
            act={catAct}
            editing={editing}
            moodPlaced={gotToday}
            sofaOwned={objects.some((o) => o.objectId === 'sofa')}
            catSkin={catSkin}
            onPick={(k) => setPop(OBJ_INFO[k] ?? null)}
            onSlot={(slot) => setTray(slot)}
          />
          <div id="mrCap" className="sc-hint">소품 하나하나가 지켜낸 하루예요</div>
          {pop && <div className="iso-pop show" role="status">{pop}</div>}
        </div>

        {/* 꾸미기 시트 — 씬 아래까지만 올라와 방을 보면서 고른다(개편안 `.deco-sheet`). */}
        <DecoSheet open={editing} tab={decoTab} onTab={setDecoTab} items={decoItems()}
          onPick={onDecoPick} onClose={() => setEditing(false)} />

        <div className="content-sheet"><div className="pad" style={{ paddingTop: 18 }}>

        {/* 마이룸 히어로 (개편안 `.mr-hero`) — 연속 방어 · 오늘 진행 · 내일의 약속.
            게이지는 예산 대비 쓴 비율이 아니라 **남은 여유**를 채운다. 다 쓰면 비고 안 쓰면 가득 차
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
              : '예산 안에서 쓴 날에도 소품은 와요 — 소품은 벌이 아니에요'}
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

        {/* 주간 미션 보드 (개편안 `#msnList`) — 이번 주 걸린 미션과 다음 주에 담아 둔 것.
            <b>미래 시제는 시트 안에만</b> 둔다. 보드는 "지금 무엇이 걸려 있나"만 말하고,
            "다음 주에 뭘 할까"는 눌러서 여는 시트가 묻는다. */}
        <SectionTitle aux={`성공하면 +${board.data?.weeklyPointPool ?? 30}P`}>주간 미션</SectionTitle>
        <div className="card" style={{ padding: '4px 20px' }} id="msnList">
          {(board.data?.active ?? []).map((m) => (
            <div className="list-item" key={m.id}>
              <span className="ic" style={{ background: missionBg(m.type) }}>
                <Icon id={missionIcon(m.type)} className="ci" />
              </span>
              <div className="tx">
                <span className="mcap">{MISSION_STATUS[m.status]}</span>
                <b>{m.text}</b>
              </div>
              <span className={`mchip ${m.status === 'SUCCESS' ? 'c-green'
                : m.status === 'FAILED' ? 'c-amber' : 'c-blue'}`}>
                {m.status === 'ONGOING' ? `+${m.reward || (board.data?.weeklyPointPool ?? 30)}P`
                  : m.status === 'SUCCESS' ? '성공' : '아쉬워요'}
              </span>
            </div>
          ))}
          {(board.data?.active ?? []).length === 0 && (
            <p className="empty">이번 주에 걸린 미션이 없어요.</p>
          )}

          {(board.data?.next ?? []).length > 0 && (
            <>
              <div className="mdiv">다음 주에 시작해요</div>
              {board.data!.next.map((m) => (
                <div className="list-item" key={m.id}>
                  <span className="ic" style={{ background: missionBg(m.type) }}>
                    <Icon id={missionIcon(m.type)} className="ci" />
                  </span>
                  <div className="tx"><b>{m.text}</b></div>
                </div>
              ))}
            </>
          )}

          <button type="button" className="madd" onClick={() => setMsOpen(true)}>
            <Icon id="i-plus" />
            {(board.data?.next ?? []).length > 0 ? '다음 주 미션 바꾸기' : '다음 주 미션 고르기'}
          </button>
        </div>

        {/* 지킨 날 — 개편안은 이번 주 7칸을 먼저 보이고 월 달력은 접어 둔다.
            30일 격자를 늘 펼치면 화면이 격자로 차서 "오늘 지켰나"가 묻힌다. */}
        <SectionTitle>지킨 날</SectionTitle>
        <KeptDays asOf={home.asOf} grass={home.grass}
          streak={home.strip.grassStreak} keptThisMonth={keptDays} />

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
      </div></div></Scroll>

      {/* 꾸미기 서랍 — 자리를 누르면 그 자리에 놓을 수 있는 소품이 올라온다(개편안 `.tray`). */}
      <div className={`tray${tray ? ' open' : ''}`}>
        {tray && (
          <>
            <div className="tr-head">
              <b>{TRAY_INV[tray].label}</b>
              <small>고르면 바로 바뀌어요</small>
              <button type="button" onClick={() => setTray(null)} aria-label="닫기">✕</button>
            </div>
            <div className="tr-row">
              {TRAY_INV[tray].items.map((it) => {
                const on = tray === 'rug' && sel.rug === it.k;
                return (
                  <button type="button" key={it.k} className={`tr-card${on ? ' cur' : ''}`}
                          aria-pressed={on}
                          onClick={() => {
                            if (tray === 'rug') setSel((v) => ({ ...v, rug: it.k as RoomSel['rug'] }));
                            setTray(null);
                          }}>
                    {on && <span className="onbadge">사용 중</span>}
                    <img src={`/room/${it.k}.png`} alt="" />
                    <b>{it.n}</b>
                  </button>
                );
              })}
            </div>
          </>
        )}
      </div>

      {/* 다음 주 미션 고르기 (개편안 `#msSheet`) */}
      <MissionSheet open={msOpen} candidates={board.data?.candidates ?? []}
        picked={pickedKey} reward={board.data?.weeklyPointPool ?? 30}
        onClose={() => setMsOpen(false)}
        onConfirm={async (key) => {
          await api.guardian.pickMission(userId, key).catch(() => undefined);
          board.reload();
          setMsOpen(false);
        }} />

      {/* 아침 세리머니 — 방에 들어왔을 때 소품이 도착한다(개편안 openMyroom). */}
      <Modal open={ceremonyOpen && !!ceremony} onClose={closeCeremony} title="지킴이 세리머니">
        {ceremony && (
          <>
            {/* 영웅샷 — 받은 그 소품을 크게 띄운다(개편안 `.cere-hero`). 오브를 띄우면 무엇을
                받았는지가 안 보여, 세리머니가 '알림'이 되고 만다. */}
            <div className="cere-hero float-bob">
              <ItemGlyph glyph={ceremony.glyph ?? 'plant'} size={56} />
            </div>
            <h3>{ceremony.result === 'NO_SPEND_DAY' ? '어젯밤을 지켜냈어요!' : '어제도 잘 지켰어요'}</h3>
            <p>{ceremony.message ?? '새 소품이 도착했어요'}</p>
            {/* 이름표. 예전에는 여기에 서버 코드(`mug_01`)가 그대로 나왔다 — 이제 서버가
                카탈로그에서 찾은 이름을 보낸다. */}
            {ceremony.objectName && (
              <div className="cere-name">
                {GRADE_EMOJI[ceremony.grade ?? 'COMMON']} {ceremony.objectName}
                {' · '}{GRADE_LABEL[ceremony.grade ?? 'COMMON']}
              </div>
            )}
            <p className="fine">포인트는 방 꾸미기 전용이에요 · 내 돈은 그대로 내 계좌에</p>
            <button type="button" className="btn btn-primary" style={{ padding: 16 }}
              onClick={() => { closeCeremony(); void reload(); }}>방에 놓기</button>
          </>
        )}
      </Modal>
    </Screen>
  );
}
