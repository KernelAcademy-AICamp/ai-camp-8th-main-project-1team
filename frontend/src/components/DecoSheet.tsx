/**
 * 마이룸 꾸미기 시트 — 방을 보면서 고른다 (프로토타입_0806 `.deco-sheet`).
 *
 * <b>시트가 화면을 다 덮지 않는다.</b> 씬 아래까지만 올라온다 — 무엇이 바뀌는지 보이지 않으면
 * 고를 수가 없다. 그래서 바텀시트인데도 방이 위에 그대로 남는다.
 *
 * <b>탭마다 고르는 방식이 다르다.</b> 가구·소품은 <b>켜고 끄기</b>(여러 개를 함께 놓는다),
 * 배경과 캐릭터는 <b>고르기</b>(언제나 하나다). 같은 격자를 쓰지만 뜻이 달라 체크 표시의
 * 의미도 다르다 — 앞은 "놓여 있다", 뒤는 "이걸로 정했다".
 *
 * <b>잠긴 칸을 숨기지 않는다.</b> 아직 안 산 것도 자물쇠로 보인다. 무엇이 남았는지 보여야
 * 모을 마음이 생기고, 빈 격자는 "없다"가 아니라 "고장났다"로 읽힌다.
 */
import { Icon } from '../components/Icons';

export type DecoTab = 'furn' | 'prop' | 'bg' | 'char';

export interface DecoItem {
  key: string;
  name: string;
  /** `public/room/` 의 파일 이름(확장자 없이). 배경처럼 그림이 없으면 비운다. */
  glyph?: string;
  /** 가지고 있는가. false면 자물쇠. */
  owned: boolean;
  /** 지금 켜져 있거나 골라져 있는가. */
  on: boolean;
}

const TABS: { key: DecoTab; label: string }[] = [
  { key: 'furn', label: '가구' },
  { key: 'prop', label: '소품' },
  { key: 'bg', label: '배경' },
  { key: 'char', label: '캐릭터' },
];

/** 자물쇠 — 아직 없는 것. */
const Lock = () => (
  <svg viewBox="0 0 24 24" style={{ width: 56, height: 52, fill: 'none', stroke: '#B0B8C1', strokeWidth: 1.8, strokeLinecap: 'round' }}>
    <rect x="6" y="11" width="12" height="9" rx="2.5" />
    <path d="M9 11V8.5a3 3 0 0 1 6 0V11" />
  </svg>
);

export function DecoSheet({ open, tab, onTab, items, onPick, onClose }: {
  open: boolean;
  tab: DecoTab;
  onTab: (t: DecoTab) => void;
  items: DecoItem[];
  onPick: (key: string) => void;
  onClose: () => void;
}) {
  return (
    <div className={`deco-sheet${open ? ' open' : ''}`} role="dialog" aria-label="꾸미기"
      aria-hidden={!open}>
      <div className="deco-head">
        <b>꾸미기</b>
        <button type="button" onClick={onClose} aria-label="꾸미기 닫기">✕</button>
      </div>

      <div className="deco-seg" role="tablist">
        {TABS.map((t) => (
          <button type="button" key={t.key} role="tab" aria-selected={tab === t.key}
            className={tab === t.key ? 'on' : undefined} onClick={() => onTab(t.key)}>{t.label}</button>
        ))}
      </div>

      <div className="deco-grid">
        {items.map((it) => (
          <button type="button" key={it.key} disabled={!it.owned}
            className={`deco-card${it.on ? ' cur' : ''}${it.owned ? '' : ' lock'}`}
            onClick={() => it.owned && onPick(it.key)}
            aria-pressed={it.on}
            aria-label={it.owned ? it.name : `${it.name} — 아직 없어요`}>
            {/* 잠긴 칸에는 체크를 안 붙인다 — 없는데 '고름'은 모순이다. */}
            {it.on && it.owned && <span className="oncheck" aria-hidden="true"><Icon id="i-check" /></span>}
            {!it.owned ? <Lock />
              : it.glyph ? <img src={`/room/${it.glyph}.png`} alt="" loading="lazy" />
                : <span style={{ width: 56, height: 52 }} aria-hidden="true" />}
            <b>{it.name}</b>
          </button>
        ))}
        {items.length === 0 && (
          <p className="empty" style={{ gridColumn: '1 / -1' }}>여기에 놓을 수 있는 것이 아직 없어요.</p>
        )}
      </div>
    </div>
  );
}
