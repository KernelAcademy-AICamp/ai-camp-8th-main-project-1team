/**
 * 낭비 판정 — 학습된 EBM 을 그대로 돌린다.
 *
 * <h2>왜 앱에서 돌릴 수 있는가</h2>
 *
 * 판단은 <b>설명가능한 모델</b>이어야 하고 블랙박스는 쓰지 않는다(설계 원칙 1).
 * 그 원칙을 지킨 결과가 여기서 값을 한다 — EBM 은 특징마다 표를 하나씩 들고
 * <b>찾아서 더하는 것</b>이 전부다. 신경망이었다면 52KB 에 안 들어갔고 브라우저에서
 * 못 돌렸다. 설명가능성을 고른 것이 결국 서버를 지웠다.
 *
 * <pre>
 *   로그오즈 = 절편 + Σ 특징별_표_조회(값)
 *   확률     = 1 / (1 + e^-로그오즈)
 *   낭비     = 확률 ≥ 임계값(학습 시 F1 최적)
 * </pre>
 *
 * 본 서비스 `ml/SpendingClassifier.java` 의 이식이다. 경계 규칙(`bisectRight`)까지 같아야
 * 같은 데이터에서 같은 답이 나온다.
 */
import model from '../data/ebm_model.json';

interface RawTerm { feature: string; type: string; names: (string | number)[]; scores: number[] }

const raw = model as unknown as {
  intercept: number;
  terms: RawTerm[];
  decision_threshold: number;
};

interface Term {
  feature: string;
  nominal: boolean;
  index: Map<string, number> | null;
  edges: number[] | null;
  scores: number[];
}

const TERMS: Term[] = raw.terms.map((t) => {
  const nominal = t.type === 'nominal';
  return {
    feature: t.feature,
    nominal,
    index: nominal ? new Map(t.names.map((n, i) => [String(n), i])) : null,
    edges: nominal ? null : (t.names as number[]),
    scores: t.scores,
  };
});

export const THRESHOLD = raw.decision_threshold;

/** `bisect_right` — 파이썬 학습 코드와 같은 경계 규칙이라야 답이 갈리지 않는다. */
function bisectRight(a: number[], x: number): number {
  let lo = 0, hi = a.length;
  while (lo < hi) {
    const mid = (lo + hi) >> 1;
    if (x < a[mid]) hi = mid; else lo = mid + 1;
  }
  return lo;
}

function termScore(t: Term, v: unknown): number {
  if (v === undefined || v === null) return 0;
  if (t.nominal) {
    const i = t.index!.get(String(v));
    // 모르는 카테고리는 <b>0 으로 둔다.</b> 억지로 가까운 칸에 끼우면 그 카테고리가
    // 통째로 남의 점수를 받는다.
    return i === undefined ? 0 : t.scores[i];
  }
  let i = bisectRight(t.edges!, Number(v)) - 1;
  if (i < 0) i = 0;
  if (i >= t.scores.length) i = t.scores.length - 1;
  return t.scores[i];
}

export type Features = Record<string, string | number>;

export function logit(f: Features): number {
  let s = raw.intercept;
  for (const t of TERMS) s += termScore(t, f[t.feature]);
  return s;
}

export const probability = (f: Features) => 1 / (1 + Math.exp(-logit(f)));

/**
 * 특징별 기여값(로그오즈) — <b>왜 그렇게 봤는지</b>를 화면이 말할 수 있는 유일한 근거다.
 * 큰 값일수록 낭비 쪽으로 민 것이다.
 */
export function contributions(f: Features): { feature: string; value: number }[] {
  return TERMS.map((t) => ({ feature: t.feature, value: termScore(t, f[t.feature]) }))
    .sort((a, b) => b.value - a.value);
}
