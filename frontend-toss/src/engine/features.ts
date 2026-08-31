/**
 * 거래 한 건 → 특징 11개. 본 서비스 `ml/WasteFeatureExtractor.java` 의 이식이다.
 *
 * <b>학습 때의 정의와 한 글자도 달라지면 안 된다.</b> 달라도 예외가 안 나서 아무도 모르고,
 * 판정만 조용히 어긋난다. 요일이 월=0 인 것(pandas `dayofweek`), 금액에 `log1p` 를 쓰는 것,
 * `amt_vs_typical` 을 20 에서 자르는 것 — 전부 학습 코드에서 온 값이다.
 *
 * 특징에 가맹점명·브랜드·업종코드가 없다. 카테고리와 금액과 시각과 <b>그 사람의 평소</b>만 본다.
 */
import { essentialCategories } from './tables';

export interface Payment { category2: string; amount: number; at: Date }

/** 사용자 단위 집계 — 카테고리별 중앙값 · 평균 log금액 · 재량지출 비율. */
export interface UserStats {
  categoryMedian: Record<string, number>;
  categoryCount: Record<string, number>;
  meanLogAmount: number;
  discRatio: number;
}

export function userStats(payments: Payment[]): UserStats {
  const byCat: Record<string, number[]> = {};
  let sumLog = 0;
  let disc = 0;
  for (const p of payments) {
    (byCat[p.category2] ??= []).push(p.amount);
    sumLog += Math.log1p(p.amount);
    if (!essentialCategories.has(p.category2)) disc++;
  }
  const categoryMedian: Record<string, number> = {};
  const categoryCount: Record<string, number> = {};
  for (const [cat, list] of Object.entries(byCat)) {
    list.sort((a, b) => a - b);
    const n = list.length;
    categoryMedian[cat] = n % 2 === 1 ? list[n >> 1] : (list[n / 2 - 1] + list[n / 2]) / 2;
    categoryCount[cat] = n;
  }
  const total = Math.max(1, payments.length);
  return { categoryMedian, categoryCount, meanLogAmount: sumLog / total, discRatio: disc / total };
}

export function features(p: Payment, s: UserStats): Record<string, string | number> {
  const hour = p.at.getHours();
  // 월=0..일=6 — pandas `dayofweek` 와 맞춘다. JS 는 일=0 이라 그대로 쓰면 하루가 밀린다.
  const dow = (p.at.getDay() + 6) % 7;
  const med = s.categoryMedian[p.category2] ?? p.amount;
  return {
    cat2: p.category2,
    log_amount: Math.log1p(p.amount),
    hour_sin: Math.sin((2 * Math.PI * hour) / 24),
    hour_cos: Math.cos((2 * Math.PI * hour) / 24),
    night: hour >= 23 || hour <= 4 ? 1 : 0,
    dow_sin: Math.sin((2 * Math.PI * dow) / 7),
    dow_cos: Math.cos((2 * Math.PI * dow) / 7),
    weekend: dow >= 5 ? 1 : 0,
    amt_vs_typical: Math.min(20, p.amount / Math.max(1, med)),
    user_mean_log_amount: s.meanLogAmount,
    user_disc_ratio: s.discRatio,
  };
}

/**
 * 판정의 이유를 <b>사람 말로</b> 옮긴다.
 *
 * 기여값이 큰 특징 이름을 그대로 보여주면(`amt_vs_typical: 0.83`) 아무 말도 안 한 것과 같다.
 * 그렇다고 지어내지도 않는다 — 가장 세게 민 특징 하나만 골라, <b>그 특징이 실제로 본 값</b>을
 * 함께 말한다.
 */
export function reasonOf(
  top: { feature: string; value: number }[],
  p: Payment,
  s: UserStats,
): string {
  const med = s.categoryMedian[p.category2];
  const enough = (s.categoryCount[p.category2] ?? 0) >= 2;
  const lead = top.find((t) => t.value > 0.05);
  if (!lead) return '평소 소비와 견줘 조금 튀는 편이에요.';
  switch (lead.feature) {
    case 'amt_vs_typical':
      // 표본이 한 건뿐이면 그 값은 자기 자신이라 '보통'이라 부를 수 없다.
      return enough && med
        ? `${p.category2}에 보통 ${won(med)} 쓰는데 이번엔 ${won(p.amount)}이에요.`
        : '이 카테고리에서 평소보다 많이 썼어요.';
    case 'log_amount':
      return `한 번에 ${won(p.amount)}은 큰 편이에요.`;
    case 'night':
      return `밤 ${p.at.getHours()}시 결제예요.`;
    case 'weekend':
    case 'dow_sin':
    case 'dow_cos':
      return '주말에 몰린 소비 중 하나예요.';
    case 'hour_sin':
    case 'hour_cos':
      return `${p.at.getHours()}시 결제예요.`;
    case 'cat2':
      return `${p.category2}에서 쓴 돈이에요.`;
    case 'user_disc_ratio':
      return '꼭 필요하지 않은 소비의 비중이 높은 달이에요.';
    default:
      return '평소 소비와 견줘 튀는 편이에요.';
  }
}

const won = (n: number) => Math.round(n).toLocaleString('ko-KR') + '원';
