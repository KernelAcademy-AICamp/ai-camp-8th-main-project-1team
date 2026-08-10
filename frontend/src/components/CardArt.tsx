/**
 * 카드 그림 (개편안 `s-compare` 의 `.art`) — 실물 카드 사진 대신 그리는 판.
 *
 * <b>왜 그림인가.</b> 실물 카드 이미지를 쓰면 그 순간 실재 상품이 되고, 이 화면은 추천이
 * 아니라 중개가 된다(마스터 §4 원칙 5). 카드는 전부 더미이므로 그림도 더미여야 앞뒤가 맞다.
 *
 * <b>글자 하나로 갈린다.</b> 색만 다르면 세 장이 같은 카드로 보인다. 가운데 큰 글자(`mark`)와
 * 아래 영문(`footer`)이 카드끼리를 구별해 주는 유일한 표시다.
 *
 * `id`가 필요한 그라디언트는 카드마다 다른 이름을 써야 한다 — 한 화면에 세 장이 함께 서므로
 * 같은 id를 쓰면 뒤에 온 정의가 앞의 것을 덮어 세 장이 한 색이 된다.
 */

const TINTS: Record<string, { from: string; to: string; ink: string }> = {
  blue: { from: '#4D9CFF', to: '#1D6BE8', ink: '#FFFFFF' },
  gold: { from: '#FFDB4D', to: '#F5B73C', ink: '#3C2A10' },
  navy: { from: '#3D4B66', to: '#1B2436', ink: '#FFFFFF' },
};

export function CardArt({ tint, mark, footer, uid }: {
  tint: string; mark: string; footer: string; uid: string;
}) {
  const t = TINTS[tint] ?? TINTS.blue;
  const gid = `cg-${uid}`;
  const sid = `sh-${uid}`;
  return (
    <svg className="art" viewBox="0 0 120 190" aria-hidden="true">
      <defs>
        <linearGradient id={gid} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor={t.from} />
          <stop offset="1" stopColor={t.to} />
        </linearGradient>
        <linearGradient id={sid} x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stopColor="#fff" stopOpacity=".28" />
          <stop offset=".5" stopColor="#fff" stopOpacity="0" />
        </linearGradient>
      </defs>
      <rect width="120" height="190" rx="12" fill={`url(#${gid})`} />
      <rect width="120" height="190" rx="12" fill={`url(#${sid})`} />
      <text x="60" y="128" textAnchor="middle" fontSize="72" fontWeight="700"
        fill={t.ink} opacity=".32" fontFamily="inherit">{mark}</text>
      {/* IC 칩 — 카드로 읽히게 하는 최소한의 단서 */}
      <rect x="16" y="20" width="26" height="20" rx="4" fill="#E8C56B" />
      <path d="M16 27h26M16 33h26M25 20v20M33 20v20" stroke="#C9A44C" strokeWidth="1.1" />
      <text x="16" y="176" fontSize="9" fontWeight="600" fill={t.ink} opacity=".85"
        fontFamily="inherit">{footer}</text>
    </svg>
  );
}
