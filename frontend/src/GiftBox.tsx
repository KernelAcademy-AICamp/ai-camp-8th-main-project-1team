/**
 * 선물상자 (문서 §5-5). 저축하면 채워지고, 필요없는 소비면 균열, 예산 초과면 크게 깨진다.
 * fill(0~1)로 채움 높이를, lastAction/actionKey로 반응 애니메이션을 그린다. 계산은 서버가.
 */
export function GiftBox({ fill, totalSavings, lastAction, actionKey }: {
  fill: number;
  totalSavings: number;
  lastAction: string | null;
  actionKey: number;
}) {
  const pct = Math.round(Math.max(0, Math.min(1, fill)) * 100);
  const react =
    lastAction === 'SAVED' ? 'shine'
    : lastAction === 'OVERSPEND' ? 'bigbreak'
    : lastAction === 'UNNECESSARY' ? 'crack'
    : '';
  const won = Math.round(totalSavings).toLocaleString('ko-KR');

  return (
    <div className="giftbox-wrap">
      {/* key로 액션마다 애니메이션 재생 */}
      <div className={`giftbox ${react}`} key={actionKey} aria-label={`선물상자 ${pct}% 채움`}>
        <div className="gb-box">
          <div className="gb-fill" style={{ height: `${pct}%` }} />
          <span className="gb-amount">₩{won}</span>
        </div>
        <div className="gb-lid" />
        <div className="gb-ribbon" />
        <span className="gb-bow" aria-hidden="true">🎀</span>
        {react === 'crack' && <span className="gb-fx crack-fx" aria-hidden="true">💢</span>}
        {react === 'bigbreak' && <span className="gb-fx break-fx" aria-hidden="true">💥</span>}
        {react === 'shine' && <span className="gb-fx shine-fx" aria-hidden="true">✨</span>}
      </div>
      <div className="gb-caption">
        <b>{pct}%</b> 채웠어요 · 목표까지 모으는 중
      </div>
    </div>
  );
}
