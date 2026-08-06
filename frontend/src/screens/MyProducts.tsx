/**
 * 맞춤 상품 비교 (임시 보관함) — 소비 패턴과 맞는 순으로 예·적금·펀드 Top 3.
 *
 * <p><b>개편안에 없는 화면이다.</b> 프로토타입_0806 의 `s-compare` 는 같은 이름이지만
 * <b>카드 추천</b>이고, 이 화면(예·적금·펀드 매칭)은 개편안이 다시 그리지 않았다. 기능은
 * 멀쩡한데 갈 문만 없어져서 임시 보관함에 둔다 — 자리가 정해지면 옮긴다.
 *
 * <p><b>추천은 광고비가 아니라 매칭 점수 순이다.</b> 서버가 `matchScore`와 그 내역
 * (기간·위험·카테고리 적합도)을 함께 내려주므로, 화면은 순서를 다시 매기지 않고 왜 그 순위인지를
 * 그대로 보여준다 — 근거 없이 "추천"만 띄우면 그건 광고다.
 *
 * <p><b>여기 상품은 전부 더미다.</b> 가입 버튼도 외부 링크도 두지 않는다(금소법 — 판매·중개
 * 행위를 만들지 않는다). 실제 금리를 비교해 보고 싶으면 리포트의 예적금 비교로 간다.
 */
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, type RecommendItem } from '../lib/api';
import { won } from '../lib/format';

/** 상품 유형 → 한 줄 성격. 코드에 이름을 박지 않고 표시만 여기서 한다. */
const TYPE_TAGLINE: Record<string, string> = {
  DEPOSIT: '목돈을 묶어 두는 예금',
  SAVINGS: '매달 붓는 적금',
  FUND: '시장 수익을 노리는 펀드',
  CASHBACK_CARD: '쓴 만큼 돌려받는 카드',
};
/** 유형별 로고 자리 색·글자 — 실제 금융사 로고를 쓰지 않는다(더미 상품이라 브랜드가 없다). */
const TYPE_BADGE: Record<string, { bg: string; text: string; color?: string }> = {
  DEPOSIT: { bg: '#3182F6', text: '예금' },
  SAVINGS: { bg: '#00B14F', text: '적금' },
  FUND: { bg: '#8B5CF6', text: '펀드' },
  CASHBACK_CARD: { bg: '#FFCD00', text: '카드', color: '#3c1e1e' },
};

export function MyProducts() {
  const { back, userId } = useSession();
  const { data, loading, error, reload } = useAsync(() => api.recommend(userId), [userId]);

  if (loading) return <Loading label="맞는 상품을 고르는 중" />;
  if (error) return <ErrorBox error={error} onRetry={reload} />;

  const top = (data?.items ?? []).slice(0, 3);

  return (
    <Screen title="맞춤 상품 비교">
      <AppBar onBack={back} title="맞춤 상품 비교" />
      <Scroll>
        <div className="pad">
          <div className="h-title">회원님 소비에 맞는<br />상품 Top {top.length || 3}</div>
          <div className="h-sub">
            더미 상품 중 <b>소비 패턴 매칭 상위</b>예요. 순서는 광고비가 아니라 매칭 점수로 정해요.
            {data && ` 굴릴 수 있는 돈 ${won(data.availableFunds)} 기준이에요.`}
          </div>

          {top.length === 0 && (
            <div className="card"><Empty>지금은 맞는 상품이 없어요. 소비가 더 쌓이면 다시 골라볼게요.</Empty></div>
          )}

          {top.map((item, i) => <RankCard key={item.productId} item={item} best={i === 0} />)}

          <div className="pv">
            전월 실적 조건과 한도는 상세에서 확인하세요. 여기 상품은 <b>전부 더미</b>라 가입 경로를 두지 않아요.
          </div>
          <div className="spacer" style={{ height: 32 }} />
        </div>
      </Scroll>
    </Screen>
  );
}

function RankCard({ item, best }: { item: RecommendItem; best: boolean }) {
  const badge = TYPE_BADGE[item.productType] ?? { bg: '#868685', text: '상품' };
  // 기대 수익 = 굴릴 금액이 아니라 '연 이율'만 안다. 금액 환산은 사용자 자금에 달려 있어 서버가
  // 내려주지 않으므로, 없는 숫자를 지어내지 않고 이율 그대로 보여준다.
  const b = item.scoreBreakdown;
  return (
    <div className={`rank-card${best ? ' best' : ''}`} style={{ marginTop: best ? 8 : undefined }}>
      <span className="rank-no">{item.rank}위, 매칭 {Math.round(item.matchScore * 100)}%</span>
      <div className="rank-head" style={{ marginTop: 6 }}>
        <span className="logo" style={{ background: badge.bg, color: badge.color }}>{badge.text}</span>
        <div>
          <b>{item.name}</b>
          <span>{TYPE_TAGLINE[item.productType] ?? item.productType}</span>
        </div>
      </div>
      <div className="rank-save">
        연 {item.expectedRate}%
        <small>최소 {won(item.minJoinAmount)} · {item.minPeriodMonths}개월</small>
      </div>
      <div className="why">
        <b>왜 추천?</b>{' '}
        기간 적합 {Math.round(b.periodFit * 100)}% · 위험 성향 {Math.round(b.riskFit * 100)}% ·
        소비 카테고리 {Math.round(b.categoryFit * 100)}%로 맞았어요.
        {item.gateReason && <> 다만 {item.gateReason}.</>}
      </div>
    </div>
  );
}
