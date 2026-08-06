/**
 * 분석 로딩 일러스트 — 카드 내역 서류를 스캔 밴드가 훑는다 (프로토타입_0806 `.doc-load`).
 *
 * <b>왜 캐릭터가 아니라 서류인가.</b> 예전에는 냥지킴이가 둥실거렸다. 그건 "기다려 주세요"는
 * 말하지만 <b>무엇을 하는 중인지</b>는 말하지 않는다. 서류를 한 줄씩 훑는 그림은 "지금 내
 * 카드 내역을 읽고 있다"를 글 없이 전한다 — 옆의 문구가 말하는 것과 그림이 같은 것을 가리킨다.
 *
 * 움직임은 전부 CSS 다({@code dlrow}·{@code dlscan}). 그래야 동작 최소화를 켠 사람에게
 * 자동으로 멈춘다(`prefers-reduced-motion`, tokens.css).
 */
export function DocLoad({ size = 96 }: { size?: number }) {
  return (
    <svg className="doc-load" viewBox="0 0 96 96" width={size} height={size} aria-hidden="true">
      {/* 뒤에 겹친 종이 — 한 장이 아니라 여러 달치라는 뜻 */}
      <rect x="26" y="14" width="56" height="78" rx="12" fill="var(--blue-weak)" />
      <rect x="16" y="6" width="56" height="78" rx="12" fill="var(--card)"
        stroke="var(--line)" strokeWidth="2" />
      <rect x="24" y="16" width="16" height="12" rx="4" fill="var(--blue)" />
      <rect x="46" y="18" width="18" height="6" rx="3" fill="#D4DADF" />

      {/* 내역 줄 — 스캔이 지나갈 때 순서대로 물든다 */}
      <rect className="dl-row" x="24" y="38" width="40" height="6" rx="3" />
      <rect className="dl-row" style={{ animationDelay: '.22s' }} x="24" y="50" width="30" height="6" rx="3" />
      <rect className="dl-row" style={{ animationDelay: '.44s' }} x="24" y="62" width="40" height="6" rx="3" />
      <rect className="dl-row" style={{ animationDelay: '.66s' }} x="24" y="74" width="24" height="6" rx="3" />

      {/* 스캔 밴드 — 옅은 면과 진한 선 한 쌍이 같이 내려간다 */}
      <g className="dl-scan">
        <rect x="18" y="32" width="52" height="12" fill="var(--blue)" opacity="0.10" />
        <rect x="18" y="43" width="52" height="2" rx="1" fill="var(--blue)" opacity="0.55" />
      </g>
    </svg>
  );
}
