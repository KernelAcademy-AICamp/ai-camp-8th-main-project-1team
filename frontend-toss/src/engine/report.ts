/**
 * 저장된 사실 → 화면이 그릴 것.
 *
 * <b>하나를 만들어 세 화면이 나눠 쓴다.</b> 목록·카테고리·낭비가 각자 계산하면 같은 소비가
 * 화면마다 다른 카테고리로 보이는 일이 생긴다 — 본 서비스에서 하나의 `AnalysisResult` 를
 * 세 서비스가 재사용하는 것과 같은 이유다(설계 원칙 2).
 */
import { classify, key, type Source } from './classify';
import { contributions, probability, THRESHOLD } from './ebm';
import { features, reasonOf, userStats, type Payment } from './features';
import { fixed, rows, verdicts, type Row } from './store';
import { UNKNOWN } from './tables';

export interface Entry {
  id: string;
  date: string;
  /** `HH:MM` — 손으로 넣은 건만 있다. 명세서에는 시각이 없다. */
  time?: string;
  merchant: string;
  amount: number;
  category2: string;
  category3: string | null;
  brand: string | null;
  source: Source;
  paymentAgency: boolean;
  waste: boolean;
  reason: string | null;
  /** 그 카테고리에 보통 얼마 쓰는가. 표본이 두 건 미만이면 `null`. */
  typical: number | null;
  samples: number;
}

export interface Analysis {
  entries: Entry[];
  unknown: number;
  total: number;
}

/**
 * 판정은 <b>한 번에 전부</b> 돈다.
 *
 * "평소보다 많이 썼다"의 <i>평소</i>는 그 사람의 전체 소비에서 나온다. 한 건씩 따로 보면
 * 알 수 없고, 소비가 늘면 옛 판정도 달라진다 — 평소가 자란 것이므로 그게 맞다.
 */
/**
 * 명세서에는 시각이 없고 손으로 넣은 건에는 있다. 없는 것은 정오로 읽는다 —
 * 그럴듯하게 흩어 놓으면 모델이 지어낸 시각으로 판정한다.
 */
function at(r: Row): Date {
  return new Date(`${r.date}T${r.time ?? '12:00'}:00`);
}

export function analyze(source: Row[] = rows()): Analysis {
  const remembered = fixed();
  const mine = verdicts();

  const classified = source.map((r) => {
    const v = classify(r.merchant, r.industry, r.biz, remembered);
    return { row: r, verdict: v };
  });

  // 통계는 **분류가 붙은 것만** 본다. 모르는 칸이 섞이면 '카테고리없음' 이 하나의
  // 카테고리인 척 중앙값을 갖게 되고, 그 중앙값으로 남을 판정하게 된다.
  const known: Payment[] = classified
    .filter((c) => c.verdict.category2 !== UNKNOWN && c.row.amount > 0)
    .map((c) => ({ category2: c.verdict.category2, amount: c.row.amount, at: at(c.row) }));
  const stats = userStats(known);

  const entries: Entry[] = classified.map(({ row, verdict }) => {
    const head = {
      id: row.id, date: row.date, time: row.time, merchant: row.merchant, amount: row.amount,
      category2: verdict.category2, category3: verdict.category3, brand: verdict.brand,
      source: verdict.source, paymentAgency: verdict.paymentAgency,
    };
    /*
     * <b>사람이 답한 것은 카테고리를 몰라도 센다.</b>
     *
     * 예전에는 모르는 칸이면 여기서 먼저 빠져나가 사람의 답을 보지도 않았다 — 사용자가
     * '새는 돈이었어요' 를 골라도 낭비 금액에 안 잡혔다. 내역에는 딱지가 붙는데 합계는
     * 안 바뀌니, 답한 것이 어디로 갔는지 알 수가 없다.
     *
     * 무엇으로 분류됐는지와 그 돈이 아까웠는지는 <b>다른 물음</b>이다.
     */
    const answered = mine[row.id];
    if (answered) {
      return { ...head, waste: answered === 'WASTE', reason: null, typical: null, samples: 0 };
    }

    // 환불(음수)과 모르는 칸은 <b>모델이</b> 판정하지 않는다. 모르는 것을 낭비라고 부르면
    // 사용자는 분류를 고치는 대신 판정을 안 믿게 된다.
    if (verdict.category2 === UNKNOWN || row.amount <= 0) {
      return { ...head, waste: false, reason: null, typical: null, samples: 0 };
    }
    // **사람이 답한 것이 규칙을 이긴다.** 규칙은 물어볼 것을 고르는 데까지고,
    // 무엇이 아까웠는지는 그 사람만 안다.
    const p: Payment = { category2: verdict.category2, amount: row.amount, at: at(row) };
    const f = features(p, stats);
    const prob = probability(f);
    const j = {
      waste: prob >= THRESHOLD,
      reason: prob >= THRESHOLD ? reasonOf(contributions(f), p, stats) : null,
      typical: (stats.categoryCount[verdict.category2] ?? 0) >= 2
        ? stats.categoryMedian[verdict.category2] : null,
      samples: stats.categoryCount[verdict.category2] ?? 0,
    };
    return { ...head, ...j };
  });

  return {
    entries,
    unknown: entries.filter((e) => e.category2 === UNKNOWN).length,
    total: entries.reduce((s, e) => s + Math.max(0, e.amount), 0),
  };
}

export interface Slice { name: string; amount: number; count: number }

/** 그 달에 무엇에 얼마나. `month` 는 `YYYY-MM`. */
export function byCategory(entries: Entry[], month: string): { slices: Slice[]; total: number } {
  const acc: Record<string, Slice> = {};
  let total = 0;
  for (const e of entries) {
    if (!e.date.startsWith(month) || e.amount <= 0) continue;
    (acc[e.category2] ??= { name: e.category2, amount: 0, count: 0 });
    acc[e.category2].amount += e.amount;
    acc[e.category2].count++;
    total += e.amount;
  }
  return { slices: Object.values(acc).sort((a, b) => b.amount - a.amount), total };
}

/** 소비가 있는 달만 돌려준다. 빈 달을 0 으로 채우면 안 쓴 것과 안 넣은 것이 같아 보인다. */
export function byMonth(entries: Entry[]): { month: string; amount: number }[] {
  const acc: Record<string, number> = {};
  for (const e of entries) {
    if (e.amount <= 0) continue;
    const m = e.date.slice(0, 7);
    acc[m] = (acc[m] ?? 0) + e.amount;
  }
  return Object.entries(acc)
    .map(([month, amount]) => ({ month, amount }))
    .sort((a, b) => a.month.localeCompare(b.month));
}

export { key };
export const money = (n: number) => Math.round(n).toLocaleString('ko-KR') + '원';
