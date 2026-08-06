/**
 * 마이룸 아이소메트릭 씬 — 개편안(`MOA_UI_0729(2).html` `buildIsoScene`)을 그대로 옮긴 것.
 *
 * <b>왜 SVG 한 장인가.</b> 방은 "지킨 날"이 쌓여 채워지는 그림이라, 소품이 겹치는 순서(뎁스)가
 * 무너지면 침대 위에 러그가 깔린다. 아이소메트릭 투영은 `x+y`가 클수록 앞이라, 그리는 순서만
 * 지키면 뎁스가 저절로 맞는다. 그래서 DOM을 쌓지 않고 폴리곤·이미지를 한 좌표계에 순서대로 놓는다.
 *
 * 좌표: 바닥 6×6 격자. `iso(x, y, z)` 가 격자 좌표를 화면 좌표로 옮긴다(T=타일 반폭, z=높이).
 * 에셋 16종은 `public/room/*.png` — 개편안에 base64로 박혀 있던 것을 파일로 뺐다(번들 142KB 절감).
 */
import { useMemo } from 'react';

/** 에셋의 원본 크기(px). 개편안 `IMGD` 를 그대로 옮겼다 — 이 값으로 바닥에 발을 맞춘다. */
const IMGD: Record<string, [number, number]> = {
  lamp: [33, 72], plant: [31, 44], frame: [23, 42], shelf: [43, 89],
  bed: [87, 70], mood: [14, 16], sofa: [64, 60], table: [40, 43],
  rug: [104, 53], rug2: [104, 53], bowl: [25, 16], yarn: [19, 26],
  catsit: [28, 41], catnap: [28, 28], catread: [34, 49], catlounge: [28, 28],
};

const SRC = (k: string) => `/room/${k}.png`;

/** 냥지킴이가 지금 뭘 하고 있나. 진입할 때마다 달라진다(개편안 `currentAct`). */
export type CatAct = 'nap' | 'read' | 'rug' | 'sofa';

export const CAT_ACT_LABEL: Record<CatAct, string> = {
  nap: '침대에서 낮잠 자는 중',
  read: '책장 앞에서 독서 중',
  rug: '러그에서 햇살 쬐는 중',
  sofa: '소파에서 뒹굴거리는 중',
};

/** 꾸미기로 바꿀 수 있는 자리. 값은 그 자리에 놓인 에셋 키다. */
export interface RoomSel { rug: 'rug' | 'rug2' }

export interface IsoRoomProps {
  /** 지금 고른 소품(자리별). */
  sel: RoomSel;
  /** 냥지킴이 행동. */
  act: CatAct;
  /** 꾸미기 모드 — 켜면 교체 가능한 자리에 점선이 뜬다. */
  editing: boolean;
  /** 세리머니로 도착한 무드등을 테이블 위에 올릴지. */
  moodPlaced: boolean;
  /** 소파를 샀는지(포인트샵). 사기 전에는 자리가 비어 있다. */
  sofaOwned: boolean;
  /**
   * 고른 털색 (프로토타입_0806 꾸미기 &gt; 캐릭터). `cat`(크림)이면 접미사가 없다.
   *
   * 색은 <b>파일만 바꾼다</b> — 자세(`catnap`·`catread`…)가 크기와 자리를 정하고, 색은 같은
   * 그림의 다른 칠이다. 그래서 크기표를 색마다 만들 필요가 없다.
   */
  catSkin?: string;
  /** 소품을 눌렀을 때 — 꾸미기 중이면 자리 이름, 아니면 소품 키가 온다. */
  onPick: (key: string) => void;
  /** 꾸미기 중 자리를 눌렀을 때. */
  onSlot: (slot: keyof RoomSel | 'bed' | 'table') => void;
}

// ── 아이소메트릭 투영 ────────────────────────────────────────────────────
const T = 26, OX = 163, OY = 116;
const iso = (x: number, y: number, z: number): [number, number] =>
  [OX + (x - y) * T, OY + ((x + y) * T) / 2 - z];
const P = (pts: [number, number][]) => pts.map((p) => p.join(',')).join(' ');

/** 바닥에 지는 그림자. 소품이 떠 보이지 않게 하는 유일한 장치다. */
function Shadow({ rx, ry }: { rx: number; ry?: number }) {
  return <ellipse cx={0} cy={1} rx={rx} ry={ry ?? Math.round(rx * 0.38)} fill="rgba(30,45,70,.12)" />;
}

/** 에셋 한 장. 바닥 기준으로 위로 세운다(`y = -높이`). */
/**
 * @param k   크기표({@link IMGD})의 키 — 자리와 크기를 정한다
 * @param src 실제 파일 이름. 안 주면 {@code k} 를 쓴다. 털색처럼 <b>같은 크기의 다른 그림</b>을
 *            그릴 때만 갈라 쓴다 — 색마다 크기표를 만들지 않으려는 것이다.
 */
function Asset({ k, src, dy = 0 }: { k: string; src?: string; dy?: number }) {
  const [w, h] = IMGD[k];
  return <image href={SRC(src ?? k)} x={-w / 2} y={-h + dy} width={w} height={h}
    preserveAspectRatio="xMidYMax meet" />;
}

/** 격자 좌표에 무언가를 놓는다. */
function At({ x, y, z = 0, onClick, className, style, children }: {
  x: number; y: number; z?: number; onClick?: () => void;
  className?: string; style?: React.CSSProperties; children: React.ReactNode;
}) {
  const [cx, cy] = iso(x, y, z);
  return (
    <g transform={`translate(${cx} ${cy})`} className={className} style={style}
       onClick={onClick} role={onClick ? 'button' : undefined}>
      {children}
    </g>
  );
}

/** 냥지킴이 위치·자세 — 행동마다 다르다. */
const ACT_POS: Record<CatAct, { p: [number, number]; k: string; floating: boolean }> = {
  nap: { p: iso(0.9, 4.2, 18), k: 'catnap', floating: true },
  read: { p: iso(4.55, 1.65, 0), k: 'catread', floating: false },
  rug: { p: iso(2.8, 3.25, 3), k: 'catsit', floating: false },
  sofa: { p: iso(3.12, 0.92, 19), k: 'catlounge', floating: true },
};

/** 꾸미기에서 점선으로 표시할 자리. */
const SLOTS = {
  rug: { x: 3.3, y: 3.35, w: 2.7, d: 2.7 },
  bed: { x: 0.85, y: 4.3, w: 1.7, d: 2.5 },
  table: { x: 0.6, y: 2.5, w: 1.5, d: 1.5 },
} as const;

export function IsoRoom({ sel, act, editing, moodPlaced, sofaOwned, catSkin = 'cat', onPick, onSlot }: IsoRoomProps) {
  // 바닥 타일은 36장 고정이라 한 번만 만든다.
  const floor = useMemo(() => {
    const out: React.ReactElement[] = [];
    for (let x = 0; x < 6; x++) {
      for (let y = 0; y < 6; y++) {
        out.push(
          <polygon key={`${x}-${y}`}
            points={P([iso(x, y, 0), iso(x + 1, y, 0), iso(x + 1, y + 1, 0), iso(x, y + 1, 0)])}
            fill={(x + y) % 2 ? '#EDE3D4' : '#F5EDE0'} stroke="#E3D6C2" strokeWidth={0.6} />,
        );
      }
    }
    return out;
  }, []);

  const WH = 96;
  const a = ACT_POS[act] ?? ACT_POS.rug;
  /** 꾸미기 중이면 자리를 열고, 아니면 소품 설명을 연다. */
  const tap = (slot: keyof typeof SLOTS, key: string) =>
    editing ? () => onSlot(slot) : () => onPick(key);

  return (
    <svg width="375" height="353" viewBox="0 0 327 308" aria-label="마이룸">
      {floor}

      {/* 바닥 두께 — 방이 종이가 아니라 상자로 보이게 한다 */}
      <polygon points={P([iso(0, 6, 0), iso(6, 6, 0), iso(6, 6, -12), iso(0, 6, -12)])} fill="#D9C9B0" />
      <polygon points={P([iso(6, 6, 0), iso(6, 0, 0), iso(6, 0, -12), iso(6, 6, -12)])} fill="#CBB795" />

      {/* 벽 두 면 */}
      <polygon points={P([iso(0, 0, WH), iso(6, 0, WH), iso(6, 0, 0), iso(0, 0, 0)])} fill="#DBE9FB" />
      <polygon points={P([iso(0, 0, WH), iso(0, 6, WH), iso(0, 6, 0), iso(0, 0, 0)])} fill="#C9DDF7" />
      {/* 창문 */}
      <polygon points={P([iso(2.1, 0, 78), iso(3.9, 0, 78), iso(3.9, 0, 36), iso(2.1, 0, 36)])}
        fill="#BBDDF9" stroke="#fff" strokeWidth={3} />

      {/* ── 소품: 뒤에서 앞으로(x+y 오름차순) 그린다 ── */}
      <At x={0.9} y={0.4} onClick={() => onPick('lamp')} style={{ cursor: 'pointer' }}>
        <Shadow rx={13} /><Asset k="lamp" />
      </At>

      <At x={0} y={2.2} z={40} onClick={() => onPick('frame')} style={{ cursor: 'pointer' }}>
        <Asset k="frame" />
      </At>

      {/* 소파 — 사기 전에는 흐리게 비워 둔다(무엇이 올 자리인지 보이게) */}
      <g style={{ cursor: 'pointer', opacity: sofaOwned ? 1 : 0.18 }} onClick={() => onPick('sofa')}>
        <At x={3.15} y={0.85}><Shadow rx={31} ry={10} /><Asset k="sofa" /></At>
      </g>

      <At x={5.15} y={0.55} onClick={() => onPick('shelf')} style={{ cursor: 'pointer' }}>
        <Shadow rx={21} ry={7} /><Asset k="shelf" />
      </At>

      <At x={0.6} y={2.5} onClick={tap('table', 'table')} style={{ cursor: 'pointer' }}>
        <Shadow rx={19} /><Asset k="table" />
      </At>
      {/* 무드등 — 세리머니로 테이블 위에 도착한다 */}
      <g style={{ cursor: 'pointer', opacity: moodPlaced ? 1 : 0 }} onClick={() => onPick('moodlight')}>
        <At x={0.6} y={2.5} z={25}><Asset k="mood" /></At>
      </g>

      <At x={3.3} y={3.35} onClick={tap('rug', sel.rug)} style={{ cursor: 'pointer' }}>
        <Asset k={sel.rug} dy={Math.round(IMGD[sel.rug][1] * 0.52)} />
      </At>

      <At x={3.95} y={3.5} onClick={() => onPick('bowl')} style={{ cursor: 'pointer' }}>
        <Shadow rx={12} /><Asset k="bowl" />
      </At>

      <At x={0.85} y={4.3} z={-3} onClick={tap('bed', 'bed')} style={{ cursor: 'pointer' }}>
        <Shadow rx={42} ry={14} /><Asset k="bed" />
      </At>

      <At x={3.05} y={4.35} onClick={() => onPick('yarn')} style={{ cursor: 'pointer' }}>
        <Shadow rx={8} /><Asset k="yarn" />
      </At>

      <At x={2.35} y={5.3}><Shadow rx={14} /></At>
      <g className="g-sway" style={{ cursor: 'pointer' }} onClick={() => onPick('plant')}>
        <At x={2.35} y={5.3}><Asset k="plant" /></At>
      </g>

      {/* 냥지킴이 */}
      <g className="catg" transform={`translate(${a.p[0]} ${a.p[1]})`}
         style={{ cursor: 'pointer' }} onClick={() => onPick('cat')}>
        {!a.floating && <ellipse cx={0} cy={2} rx={17} ry={5} fill="rgba(30,45,70,.13)" />}
        {/* 크기는 자세가 정하고(`IMGD[a.k]`) 파일만 색을 따른다. */}
        <Asset k={a.k} src={catSkin === 'cat' ? a.k : `${a.k}_${catSkin}`} />
      </g>

      {/* 꾸미기 모드 — 바꿀 수 있는 자리를 점선으로 */}
      {editing && (Object.keys(SLOTS) as (keyof typeof SLOTS)[]).map((id) => {
        const t = SLOTS[id];
        return (
          <polygon key={id} className="slotmark"
            points={P([
              iso(t.x - t.w / 2, t.y - t.d / 2, 1), iso(t.x + t.w / 2, t.y - t.d / 2, 1),
              iso(t.x + t.w / 2, t.y + t.d / 2, 1), iso(t.x - t.w / 2, t.y + t.d / 2, 1),
            ])}
            onClick={() => onSlot(id)} />
        );
      })}
    </svg>
  );
}
