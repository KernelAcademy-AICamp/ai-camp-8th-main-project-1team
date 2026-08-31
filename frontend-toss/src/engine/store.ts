/**
 * 기기 저장소 — 소비 내역은 <b>이 기기를 떠나지 않는다.</b>
 *
 * <h2>사실만 담고 판단은 담지 않는다</h2>
 *
 * 저장하는 것은 날짜·가맹점명·금액뿐이다. 카테고리도 낭비 여부도 안 담는다 —
 * <b>읽을 때마다 다시 계산한다.</b> 그래야 표가 좋아졌을 때 옛 소비도 같이 좋아지고,
 * 같은 데이터에서 늘 같은 답이 나온다(재현성, 설계 원칙 3).
 *
 * 예외가 하나다. <b>사용자가 고친 것</b>은 계산으로 되살릴 수 없으므로 따로 담는다.
 *
 * <h2>왜 localStorage 인가</h2>
 *
 * IndexedDB 가 정석이지만 이 앱이 담을 것은 명세서 몇 달치, 많아야 수천 줄이다.
 * 비동기 스키마 관리를 얹을 만큼의 양이 아니고, <b>동기로 읽히면 화면이 안 깜빡인다.</b>
 * 다만 저장소가 막힌 기기가 있으므로 읽기·쓰기를 전부 감싼다 — 못 담아도 앱은 돌아야 한다.
 */
const KEY_ROWS = 'moa.rows.v1';
const KEY_FIXED = 'moa.fixed.v1';
const KEY_VERDICT = 'moa.verdict.v1';

/** 명세서에서 읽은 그대로. 판단은 하나도 안 들어 있다. */
export interface Row {
  id: string;
  /** YYYY-MM-DD */
  date: string;
  /**
   * `HH:MM` — <b>있을 때만 있다.</b>
   *
   * 카드 명세서에는 시각이 없다. 손으로 넣을 때는 아는 값이라 받는다 — 같은 날 여러 건을
   * 넣으면 <b>순서가 있어야</b> 목록이 실제로 쓴 차례대로 선다. 없는 것을 정오로 채워 넣지
   * 않는다. 채우면 명세서에서 온 건과 손으로 넣은 건이 구별되지 않는다.
   */
  time?: string;
  merchant: string;
  amount: number;
  biz: string;
  industry: string;
}

function read<T>(key: string, fallback: T): T {
  try {
    const raw = localStorage.getItem(key);
    return raw ? (JSON.parse(raw) as T) : fallback;
  } catch {
    return fallback;
  }
}

function write(key: string, value: unknown): boolean {
  try {
    localStorage.setItem(key, JSON.stringify(value));
    return true;
  } catch {
    // 저장소가 꽉 찼거나 막혀 있다. 화면은 이미 그 값을 들고 있으므로 이번 세션은 멀쩡하다.
    return false;
  }
}

export const rows = () => read<Row[]>(KEY_ROWS, []);

/** 사용자가 고친 분류 — `가맹점키 → 중분류`. */
export const fixed = () => read<Record<string, string>>(KEY_FIXED, {});

/**
 * 사람이 정한 판정 — `결제키 → 아깝다/괜찮다`.
 *
 * <b>규칙은 짐작만 한다.</b> "평소의 세 배"가 누구에게나 낭비인 것은 아니다 — 오랜만의
 * 미용실은 원래 비싸고, 매달 하는 장보기는 커도 낭비가 아니다. 규칙이 고른 것을 사람에게
 * 되묻고, 사람이 답한 것은 규칙을 <b>이긴다.</b>
 */
export const verdicts = () => read<Record<string, 'WASTE' | 'FINE'>>(KEY_VERDICT, {});

export function setVerdict(paymentId: string, waste: boolean) {
  const map = verdicts();
  map[paymentId] = waste ? 'WASTE' : 'FINE';
  write(KEY_VERDICT, map);
}

/**
 * 넣기 — <b>같은 결제를 두 번 넣지 않는다.</b>
 *
 * 명세서를 두 달 겹쳐 받는 일이 흔하다. 겹친 줄을 그대로 쌓으면 합계가 부풀고,
 * 사용자는 자기가 두 번 넣은 줄 모른다. 날짜·가맹점·금액이 같으면 같은 결제로 본다 —
 * 하루에 같은 가게에서 같은 금액을 두 번 쓰는 일은 드물고, 드문 쪽을 잃는 편이
 * 합계가 틀리는 것보다 낫다.
 */
export function add(incoming: Omit<Row, 'id'>[]): { added: number; skipped: number } {
  const current = rows();
  const seen = new Set(current.map((r) => r.id));
  let added = 0, skipped = 0;
  for (const r of incoming) {
    const id = idOf(r);
    if (seen.has(id)) { skipped++; continue; }
    seen.add(id);
    current.push({ ...r, id });
    added++;
  }
  // 최신이 위. 같은 날이면 시각으로, 시각을 모르면 이름으로 — 순서가 흔들리면 안 된다.
  current.sort((a, b) =>
    b.date.localeCompare(a.date)
    || (b.time ?? '').localeCompare(a.time ?? '')
    || a.merchant.localeCompare(b.merchant));
  write(KEY_ROWS, current);
  return { added, skipped };
}

export function remember(merchantKey: string, category2: string) {
  const map = fixed();
  map[merchantKey] = category2;
  write(KEY_FIXED, map);
}

/** 그 건에 달렸던 답을 버린다 — 지운 소비의 답이 남으면 다시 넣었을 때 따라붙는다. */
export function dropVerdict(paymentId: string) {
  const map = verdicts();
  if (!(paymentId in map)) return;
  delete map[paymentId];
  write(KEY_VERDICT, map);
}

export function removeRow(id: string) {
  write(KEY_ROWS, rows().filter((r) => r.id !== id));
}

/** 전부 지운다 — 지우는 길이 없으면 넣을 마음이 안 든다. */
export function clearAll() {
  try {
    localStorage.removeItem(KEY_ROWS);
    localStorage.removeItem(KEY_FIXED);
    localStorage.removeItem(KEY_VERDICT);
  } catch { /* 막힌 기기 */ }
}

/** 방금 넣은 것을 되찾으려면 부르는 쪽도 같은 열쇠를 만들 수 있어야 한다. */
export const idOf = (r: Omit<Row, 'id'>) =>
  `${r.date}|${r.time ?? ''}|${r.merchant.replace(/\s+/g, '')}|${r.amount}`;
