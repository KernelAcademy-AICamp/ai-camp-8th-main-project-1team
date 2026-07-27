/**
 * 절약통(선물상자) — 참으면 채워지고, 충동소비면 금이 간다. 계산은 서버가 하고 여기선 그리기만.
 * 목업엔 없던 컴포넌트라 MOA 토큰(--blue/--blue-weak/--radius)으로 다시 그렸다.
 */
export function GiftBox({ fill, totalSavings, lastAction, actionKey }: {
  /** 채움 비율 0~1 (서버 giftFill). */
  fill: number;
  totalSavings: number;
  /** 직전 액션 — SAVED·GROW=반짝, UNNECESSARY=균열, OVERSPEND=크게 깨짐. */
  lastAction: string | null;
  /** 액션마다 바뀌는 키 — 같은 애니메이션을 다시 재생시킨다. */
  actionKey: number;
}) {
  const percent = Math.round(Math.max(0, Math.min(1, fill)) * 100);
  const react =
    lastAction === 'SAVED' || lastAction === 'GROW' ? 'shine'
    : lastAction === 'OVERSPEND' ? 'bigbreak'
    : lastAction === 'UNNECESSARY' ? 'crack'
    : '';
  const amount = Math.round(totalSavings).toLocaleString('ko-KR');

  return (
    <div className="giftbox-wrap">
      <div className={`giftbox ${react}`} key={actionKey}
        role="img" aria-label={`절약통 ${percent}% 채움 · ${amount}원`}>
        <div className="gb-box">
          <div className="gb-fill" style={{ height: `${percent}%` }} />
          <span className="gb-amount" aria-hidden="true">₩{amount}</span>
        </div>
        <div className="gb-lid" aria-hidden="true" />
        <div className="gb-ribbon" aria-hidden="true" />
        <span className="gb-bow" aria-hidden="true">🎀</span>
        {react === 'crack' && <span className="gb-fx" aria-hidden="true">💢</span>}
        {react === 'bigbreak' && <span className="gb-fx" aria-hidden="true">💥</span>}
        {react === 'shine' && <span className="gb-fx" aria-hidden="true">✨</span>}
      </div>
      <div className="gb-caption"><b>{percent}%</b> 채웠어요</div>
    </div>
  );
}
