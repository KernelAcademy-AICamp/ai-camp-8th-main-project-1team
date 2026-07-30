/**
 * 소품·가구 그림 (개편안의 `GLY` 표).
 *
 * <p>서버는 어느 그림인지(`glyph` 키)만 가리키고 그림 자체는 여기 있다 — 서버가 SVG를 들고 있을
 * 이유는 없고, 디자인이 바뀌면 여기만 고치면 된다.
 *
 * <p>`viewBox`는 40×40으로 통일한다. 도감(32px)·상점(52px)·마이룸에서 같은 그림을 크기만 달리
 * 쓰는데, 좌표계가 제각각이면 칸마다 그림이 튄다.
 */
export function ItemGlyph({ glyph, size }: { glyph: string; size?: number }) {
  const body = GLYPHS[glyph] ?? GLYPHS.plant;
  return (
    <svg
      viewBox="0 0 40 40"
      style={size ? { width: size, height: size, flex: '0 0 auto' } : undefined}
      aria-hidden="true"
    >
      {body}
    </svg>
  );
}

/** 개편안 원본의 그림을 그대로 옮긴 것. 색·좌표를 임의로 바꾸지 않는다. */
const GLYPHS: Record<string, React.ReactNode> = {
  plant: (
    <>
      <rect x="14" y="23" width="12" height="10" rx="2" fill="#E58A4E" />
      <ellipse cx="15" cy="16" rx="5" ry="8" fill="#57C785" transform="rotate(-18 15 16)" />
      <ellipse cx="20" cy="13" rx="5" ry="9" fill="#3FB06F" />
      <ellipse cx="25" cy="16" rx="5" ry="8" fill="#57C785" transform="rotate(18 25 16)" />
    </>
  ),
  frame: (
    <>
      <rect x="10" y="7" width="20" height="26" rx="2" fill="#fff" stroke="#D3D9DF" strokeWidth="1.5" />
      <rect x="13.5" y="11" width="13" height="18" fill="#8FC6A5" />
    </>
  ),
  mug: <use href="#i-cafe" transform="translate(4 4) scale(1.35)" />,
  books: <use href="#i-book" transform="translate(4 4) scale(1.35)" />,
  shelf: (
    <>
      <rect x="11" y="5" width="18" height="30" rx="2" fill="#E8D6BC" />
      <rect x="14" y="9" width="12" height="4" fill="#F2B84B" />
      <rect x="14" y="17" width="12" height="4" fill="#6D9BF2" />
      <rect x="14" y="25" width="12" height="4" fill="#EF8A9B" />
    </>
  ),
  lamp: (
    <>
      <circle cx="20" cy="12" r="9" fill="#FFE9B8" />
      <path d="M13 13 L27 13 L24 5 L16 5 Z" fill="#FFD37E" />
      <rect x="19" y="13" width="2.5" height="18" fill="#B9C1CC" />
      <ellipse cx="20" cy="33" rx="7" ry="2.5" fill="#A7AFBB" />
    </>
  ),
  bed: (
    <>
      <rect x="6" y="20" width="28" height="10" rx="2" fill="#E7EBF0" />
      <rect x="6" y="14" width="28" height="7" rx="2" fill="#9DBFF3" />
      <rect x="9" y="16" width="8" height="4" rx="2" fill="#fff" />
    </>
  ),
  mood: (
    <>
      <path d="M12 24 A8 10 0 0 1 28 24 Z" fill="#FF9EC6" />
      <rect x="13" y="24" width="14" height="5" rx="2.5" fill="#C9CFD8" />
      <ellipse cx="20" cy="18" rx="12" ry="8" fill="#FF9EC6" opacity=".25" />
    </>
  ),
  sofa: (
    <>
      <rect x="6" y="12" width="28" height="10" rx="3" fill="#8FC6A5" />
      <rect x="8" y="20" width="24" height="9" rx="3" fill="#BEE6CF" />
      <rect x="5" y="18" width="5" height="12" rx="2.5" fill="#8FC6A5" />
      <rect x="30" y="18" width="5" height="12" rx="2.5" fill="#8FC6A5" />
    </>
  ),
  rug: (
    <>
      <ellipse cx="20" cy="20" rx="15" ry="9" fill="#F7DCE3" />
      <ellipse cx="20" cy="20" rx="9" ry="5" fill="#F0C4CF" />
    </>
  ),
  rug2: (
    <>
      <ellipse cx="20" cy="20" rx="15" ry="9" fill="#F2DCDF" />
      <ellipse cx="20" cy="20" rx="9" ry="5" fill="#EBC9CF" />
    </>
  ),
  table: (
    <>
      <rect x="9" y="14" width="22" height="5" rx="2" fill="#E8D6BC" />
      <rect x="12" y="19" width="3" height="12" fill="#D9C2A0" />
      <rect x="25" y="19" width="3" height="12" fill="#D9C2A0" />
    </>
  ),
  wall1: (
    <>
      <rect x="7" y="7" width="26" height="26" rx="3" fill="#CBEBDD" />
      <circle cx="15" cy="15" r="1.6" fill="#fff" />
      <circle cx="25" cy="20" r="1.6" fill="#fff" />
      <circle cx="17" cy="27" r="1.6" fill="#fff" />
    </>
  ),
  wall2: (
    <>
      <rect x="7" y="7" width="26" height="26" rx="3" fill="#F6EBD9" />
      <path d="M7 15 H33 M7 24 H33" stroke="#EAD9BE" strokeWidth="1.5" />
    </>
  ),
  floor1: (
    <>
      <rect x="7" y="7" width="26" height="26" rx="3" fill="#B98A5C" />
      <path d="M7 16 H33 M7 25 H33 M20 7 V16 M14 16 V25 M26 25 V33" stroke="#A67848" strokeWidth="1.4" />
    </>
  ),
  floor2: (
    <>
      <rect x="7" y="7" width="26" height="26" rx="3" fill="#EDE3D4" />
      <rect x="7" y="7" width="13" height="13" fill="#DFD2BC" />
      <rect x="20" y="20" width="13" height="13" fill="#DFD2BC" />
    </>
  ),
};
