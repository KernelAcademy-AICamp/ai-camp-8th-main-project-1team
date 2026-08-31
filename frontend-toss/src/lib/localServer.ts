/**
 * 서버가 하던 답을 <b>기기 안에서</b> 만든다.
 *
 * <h2>왜 여기 하나만 바꾸는가</h2>
 *
 * 본 서비스의 화면·컴포넌트·상태·`api.ts` 를 그대로 가져왔다. 그것들이 서버와 만나는 자리는
 * `api.ts` 의 {@code request()} 한 함수뿐이라, <b>거기서 이리로 돌리면 나머지는 손댈 것이 없다.</b>
 * 화면은 자기가 HTTP 를 쓰는지 함수를 부르는지 모른다 — 알 필요도 없다.
 *
 * <h2>무엇을 못 하나</h2>
 *
 * 국세청 등록 업종 조회와 모델 추정은 통로가 서버에만 있어 못 가져왔다. 그 자리는
 * <b>사용자가 직접 고르는 것</b>이 대신하고, 고른 것은 이 기기가 기억한다.
 * 여기 없는 진입로는 404 를 던진다 — 화면들이 이미 실패를 감싸고 있다.
 */
import type {
  AlertResponse, CategoryView, ConfirmCategoryResult, MyMerchant, MyPaymentHistory,
  ReportLine, ReportResponse, UnclassifiedItem, UnclassifiedResponse, UserView, WasteJudgment,
} from './api';
import { ApiError } from './api';
import { analyze, type Entry } from '../engine/report';
import { guardianHome, periodSpend, weeklyReport } from './localGuardian';
import { classify, key } from '../engine/classify';
import { add, clearAll, dropVerdict, fixed, idOf, remember, removeRow, rows, setVerdict, verdicts } from '../engine/store';
import { MID_CATEGORIES, UNKNOWN, byBiz } from '../engine/tables';

/** 계정이 없다. 화면들이 `userId` 를 들고 다니므로 하나를 정해 준다. */
export const LOCAL_USER_ID = 1;

/* ── 라우터 ────────────────────────────────────────────── */

export async function serve<T>(method: string, path: string, body?: unknown): Promise<T> {
  const [route, query] = path.split('?');
  const q = new URLSearchParams(query ?? '');
  const seg = route.split('/').filter(Boolean);           // ['api','report','monthly']
  const at = (i: number) => decodeURIComponent(seg[i] ?? '');
  const r = `${method} /${seg.slice(0, 3).join('/')}`;

  switch (true) {
    case r === 'GET /api/categories':
      return categories() as T;

    case r === 'GET /api/mydata/payments':
      return payments() as T;

    case r === 'GET /api/mydata/merchant':
      return merchant(at(3)) as T;

    case r === 'POST /api/mydata/sync':
      return { newPayments: 0 } as T;

    case r === 'GET /api/report/monthly':
      return report() as T;

    case r === 'GET /api/report/period':
      return periodSpend(entries(),
        q.get('period') === 'month' ? 'month' : 'week',
        Math.max(0, Number(q.get('offset') ?? 0))) as T;

    // 또래 비교는 남의 소비를 알아야 나온다. 이 앱은 남의 것을 모른다 —
    // 지어내지 않고 없다고 답하면 화면이 그 절을 통째로 감춘다.
    case r === 'GET /api/report/peer':
      return null as T;

    case r === 'GET /api/guardian/home':
      return guardianHome(entries()) as T;

    case r === 'GET /api/guardian/report':
      return weeklyReport(entries(), Math.max(0, Number(q.get('weeksAgo') ?? 0))) as T;

    case method === 'POST' && seg[1] === 'guardian':
      return { newTransactions: 0 } as T;

    case r === 'GET /api/ml/waste':
      return waste() as T;

    case r === 'GET /api/alert/list':
      return alerts() as T;

    case r === 'POST /api/alert/rescan':
      return { created: 0 } as T;

    case r === 'GET /api/merchant-category/unclassified':
      return unclassified() as T;

    // 넣기 화면이 <b>타이핑하는 동안</b> 무엇으로 잡히는지 보여주려고 부른다.
    // 넣고 나서 알려주면 이미 늦다 — 틀린 것을 알아채는 자리는 넣기 전이다.
    case r === 'GET /api/merchant-category/preview':
      return preview(q.get('name') ?? '') as T;

    case method === 'POST' && seg[1] === 'merchant-category' && seg[3] === 'confirm':
      return confirm(at(2), (body as { category2: string }).category2) as T;

    /*
     * <b>`r` 에 세 번째 조각이 섞인다.</b> `r` 은 `GET /api/users/1` 이 되므로
     * `r === 'GET /api/users'` 로는 영영 안 걸린다. 실제로 그래서 404 가 났고,
     * 세션이 그것을 "그 사용자가 없다"로 읽어 <b>가입을 통째로 되돌렸다</b> —
     * 홈이 소비가 있는데도 빈 화면이 되고 주소가 `#/boot` 로 튕겼다.
     * 조각으로 본다.
     */
    case method === 'GET' && seg[1] === 'users' && seg.length === 3:
      return user() as T;

    case method === 'POST' && seg[1] === 'users' && seg[3] === 'consent':
      return user() as T;

    case method === 'GET' && seg[1] === 'users' && seg[3] === 'data':
      return { recordCount: rows().length, records: rows() } as T;

    case method === 'DELETE' && seg[1] === 'users' && seg[3] === 'data': {
      const n = rows().length;
      clearAll();
      return { deletedCount: n } as T;
    }

    case r === 'POST /api/consumption':
      return addConsumption(body as ConsumptionBody) as T;

    /** 한 건 지우기 — 소비내역에서 옆으로 밀어 지운다. 되돌릴 수 없다. */
    case method === 'DELETE' && seg[1] === 'consumption' && seg.length === 3: {
      const id = at(2);
      removeRow(id);
      // 그 건에 달렸던 답도 같이 버린다. 남겨 두면 다시 넣었을 때 옛 답이 따라붙는다.
      dropVerdict(id);
      invalidate();
      return { paymentId: id } as T;
    }

    /** 아직 답하지 않은 소비 — 되묻기 덱이 이 순서대로 한 장씩 보여준다. */
    case r === 'GET /api/ml/pending':
      return pending() as T;

    // 사람이 정한 판정 — 규칙이 고른 것을 되묻고 받은 답이다.
    case r === 'POST /api/ml/verdict': {
      const v = body as { paymentId: string; waste: boolean };
      setVerdict(v.paymentId, v.waste);
      invalidate();
      return { paymentId: v.paymentId, waste: v.waste } as T;
    }

    // 계측은 보낼 데가 없다. 조용히 성공으로 둔다 — 화면이 실패를 세지 않는다.
    case r === 'POST /api/analytics/track':
      return undefined as T;

    default:
      throw new ApiError(404, `이 앱에는 없는 기능이에요. (${route})`);
  }
}

/* ── 하나의 분석을 모두가 나눠 쓴다 (설계 원칙 2) ─────────── */

let cached: { at: number; entries: Entry[] } | null = null;

function entries(): Entry[] {
  // 저장소가 바뀌면 무효가 되어야 한다. 줄 수와 고친 개수로 판을 센다.
  const stamp = rows().length;
  if (!cached || cached.at !== stamp) cached = { at: stamp, entries: analyze().entries };
  return cached.entries;
}

/** 넣거나 고친 뒤에 부른다 — 판정이 그 사람의 전체 소비를 보므로 옛 값이 남으면 안 된다. */
function invalidate() { cached = null; }

/* ── 각 진입로 ────────────────────────────────────────── */

const categories = (): CategoryView[] =>
  MID_CATEGORIES.map((code, i) => ({ id: i + 1, code, displayName: code }));

const user = (): UserView => ({
  userId: LOCAL_USER_ID,
  nickname: '나',
  monthlyIncome: 0,
  goalAmount: 0,
  goalMonths: 0,
  // 기기 밖으로 내보내는 것이 없으므로 받을 동의도 없다.
  consentGiven: true,
});

/**
 * 소비 내역.
 *
 * <b>시각을 지어내지 않는다.</b> 손으로 넣은 건에는 시각이 있고, 명세서에서 온 건에는 없다.
 * 없는 것은 정오로 적는다 — 화면의 형식이 `날짜T시각` 이라 무엇이든 채워야 하는데,
 * 그럴듯하게 흩어 놓으면 사용자가 그 시각을 사실로 읽는다. 정오는 '모른다'는 뜻으로 늘 같다.
 */
function payments(): MyPaymentHistory[] {
  const said = verdicts();
  return entries().map((e) => ({
    paymentId: e.id,
    date: `${e.date}T${e.time ?? '12:00'}:00`,
    category: e.category2,
    category2: e.category2 === UNKNOWN ? null : e.category2,
    category2Llm: null,
    // 표에서 온 것과 사람이 고친 것뿐이다. 모델이 없으므로 'AI 추정' 배지는 뜰 일이 없다.
    category2Source: e.source === 'NONE' ? 'NONE' : e.source === 'USER' ? 'USER' : 'DICT',
    amount: e.amount,
    merchantName: e.merchant,
    cardName: null,
    cardColor: null,
    companyName: null,
    businessNumber: null,
    brand: e.brand,
    displayName: e.brand ?? e.merchant,
    displayNameSource: e.brand ? 'BRAND' : 'RAW',
    viaAgency: null,
    waste: e.waste,
    verdict: said[e.id] ?? null,
  }));
}

/**
 * 가맹점 조회 — 번호로 사전을 본다.
 *
 * 서버는 여기서 국세청에 물어 주소까지 받아 왔다. 그 통로가 없으므로 <b>사전에 있는 것만</b>
 * 답하고 주소는 비운다. 지어낸 주소를 지도에 찍는 것보다 안 찍는 편이 낫다.
 */
function merchant(businessNumber: string): MyMerchant | null {
  const hit = byBiz[businessNumber.replace(/\D/g, '')];
  if (!hit) return null;
  return {
    industryCode: null,
    category: hit.c2,
    businessNumber,
    merchantName: null,
    address: null,
    lat: null,
    lng: null,
    online: false,
  };
}

/**
 * 리포트.
 *
 * `negative`(줄이면 좋은 소비)와 `positive`(잘 관리한 소비)를 <b>판정에서 유도한다</b> —
 * 낭비로 본 결제가 하나라도 있는 카테고리가 앞쪽이다. 서버는 여기에 ML 판정을 썼고
 * 여기는 이탈 판정을 쓰지만, 화면이 받는 뜻은 같다.
 */
function report(): ReportResponse {
  /*
   * <b>합계는 모르는 칸도 센다.</b> 예전에는 `카테고리없음` 을 빼고 더해서, 사전에 없는
   * 가게만 적은 사람에게는 쓴 돈이 0원으로 보였다. 무엇으로 분류됐는지 몰라도
   * <b>쓴 것은 쓴 것</b>이다. 카테고리별 줄에서만 빼고 합계에는 넣는다.
   */
  const all = entries().filter((e) => e.amount > 0);
  const total = all.reduce((s, e) => s + e.amount, 0);

  const acc: Record<string, { amount: number; count: number; months: Set<string>; waste: boolean }> = {};
  const monthlySpend: Record<string, number> = {};
  for (const e of all) {
    monthlySpend[e.date.slice(0, 7)] = (monthlySpend[e.date.slice(0, 7)] ?? 0) + e.amount;
    // 카테고리별 줄에는 모르는 칸을 안 세운다 — '카테고리없음' 이 하나의 카테고리인 척한다.
    if (e.category2 === UNKNOWN) continue;
    const a = (acc[e.category2] ??= { amount: 0, count: 0, months: new Set(), waste: false });
    a.amount += e.amount;
    a.count++;
    a.months.add(e.date.slice(0, 7));
    a.waste ||= e.waste;
  }

  const lines: (ReportLine & { waste: boolean })[] = Object.entries(acc).map(([code, a]) => ({
    categoryCode: code,
    displayName: code,
    amount: a.amount,
    spendPercent: total === 0 ? 0 : Math.round((a.amount / total) * 100),
    count: a.count,
    monthlyAmount: Math.round(a.amount / Math.max(1, a.months.size)),
    observedMonths: a.months.size,
    waste: a.waste,
  })).sort((x, y) => y.amount - x.amount);

  const strip = ({ waste: _w, ...line }: ReportLine & { waste: boolean }) => line;
  return {
    totalSpend: total,
    negative: lines.filter((l) => l.waste).map(strip),
    positive: lines.filter((l) => !l.waste).map(strip),
    monthlySpend,
    narrative: '',
    narrativeSource: 'NONE',
    // 넣은 명세서가 곧 사실이라 추정 구간이 없다.
    dataSourceMode: 'CONFIRMED',
    estimationReason: null,
  };
}

function waste(): WasteJudgment[] {
  return entries().filter((e) => e.waste).map((e) => ({
    paymentId: e.id,
    category2: e.category2,
    amount: e.amount,
    date: `${e.date}T12:00:00`,
    // 확률로 판정하지 않는다. 화면이 숫자를 쓰면 근거가 아니라 점수처럼 읽혀서
    // 여기서는 판정 결과만 참으로 두고, 이유는 문장으로 준다.
    wasteProbability: 1,
    waste: true,
    explanation: e.reason ?? '',
  }));
}

/** 규칙 FDS 경고는 서버가 쌓아 두던 것이다. 여기서는 판정과 따로 쌓을 이유가 없어 비운다. */
const alerts = (): AlertResponse => ({
  userId: LOCAL_USER_ID,
  items: [],
  evaluatedCount: entries().length,
  dataSourceMode: 'CONFIRMED',
  estimationReason: null,
});

function unclassified(): UnclassifiedResponse {
  const items: UnclassifiedItem[] = entries()
    .filter((e) => e.category2 === UNKNOWN)
    .map((e) => ({
      paymentId: e.id,
      date: `${e.date}T12:00:00`,
      amount: e.amount,
      merchantName: e.merchant,
      businessNumber: null,
      // 지어낸 추정을 보여주지 않는다 — 모델이 없으므로 짐작할 근거도 없다.
      suggested: null,
      source: 'NONE',
      paymentAgency: e.paymentAgency,
      canConfirm: true,
    }));
  return { categories: MID_CATEGORIES, aiEnabled: false, items };
}

/**
 * 사람이 분류를 확정한다.
 *
 * 기억의 열쇠는 <b>이름</b>이다. 그래서 한 건을 고치면 <b>같은 가게의 다른 결제도 함께</b>
 * 바뀐다 — 서버가 사전에 쌓아 다음 연동부터 반영하던 것을, 여기서는 곧바로 한다.
 */
function confirm(paymentId: string, category2: string): ConfirmCategoryResult {
  const row = rows().find((x) => x.id === paymentId);
  if (!row) throw new ApiError(404, '결제를 찾을 수 없어요.');
  if (!MID_CATEGORIES.includes(category2)) throw new ApiError(400, '모르는 카테고리예요.');

  const k = key(row.merchant);
  remember(k, category2);
  invalidate();

  return {
    paymentId,
    category2,
    reclassifiedConsumptions: rows().filter((x) => key(x.merchant) === k).length,
    storedInDictionary: true,
  };
}

/**
 * 아직 답하지 않은 소비 — 최신이 위다.
 *
 * <b>모델이 낭비라고 본 것만 묻지 않는다.</b> 걸린 것만 물으면 판정이 틀렸을 때 바로잡을 길이
 * 없고, 답이 쌓여야 다음 판정이 그 사람에게 맞게 움직인다. 다만 한 번에 스무 장까지만 준다 —
 * 명세서를 한꺼번에 넣은 사람에게 수백 장을 넘기게 하면 그건 일이 된다.
 */
function pending() {
  const said = verdicts();
  return entries()
    // **모르는 칸도 물어본다.** 예전에는 `카테고리없음` 을 걸렀는데, 사전에 없는 가게를 적으면
    // 그 건이 통째로 빠져 <b>되묻는 화면이 "답할 소비가 없어요" 로 떴다</b>(실사용자 신고 2건,
    // 2026-08-30). 무엇으로 분류됐는지와 그 돈이 필요했는지는 <b>다른 물음</b>이다 —
    // 카테고리를 몰라도 필요했는지는 그 사람이 안다.
    .filter((e) => e.amount > 0 && !said[e.id])
    .slice(0, 20)
    .map((e) => ({
      paymentId: e.id,
      merchant: e.merchant,
      amount: e.amount,
      date: e.date,
      time: e.time ?? null,
      category2: e.category2,
      waste: e.waste,
      reason: e.reason,
    }));
}

/** 가게 이름 하나를 무엇으로 볼지 — 저장하지 않고 답만 한다. */
function preview(name: string): { category2: string | null; category3: string | null; brand: string | null } {
  if (!name.trim()) return { category2: null, category3: null, brand: null };
  const v = classify(name, undefined, undefined, fixed());
  return {
    category2: v.category2 === UNKNOWN ? null : v.category2,
    category3: v.category3,
    brand: v.brand,
  };
}

interface AddResult {
  id: number;
  paymentId: string;
  flagged: boolean;
  reason: string | null;
  category2: string | null;
  typical: number | null;
  samples: number;
}

interface ConsumptionBody { categoryCode: string; amount: number; occurredAt: string; merchantName?: string }

/**
 * 한 건 넣기.
 *
 * 서버는 카테고리를 받아 그대로 박았다. 여기서는 <b>가게 이름이 있으면 그것으로 찾고</b>,
 * 고른 카테고리는 그 이름에 대한 확정으로 기억한다 — 다음에 같은 가게가 오면 안 묻는다.
 */
function addConsumption(input: ConsumptionBody): AddResult {
  const at = input.occurredAt ?? '';
  const date = at.slice(0, 10);
  // `2026-08-29T19:40:00` 의 가운데 다섯 글자. 시각을 안 보냈으면 빈 값으로 둔다.
  const time = at.length >= 16 ? at.slice(11, 16) : undefined;
  const merchant = (input.merchantName ?? input.categoryCode).trim();
  const row = { date, time, merchant, amount: input.amount, biz: '', industry: '' };
  add([row]);
  if (input.categoryCode && MID_CATEGORIES.includes(input.categoryCode)) {
    remember(key(merchant), input.categoryCode);
  }
  invalidate();

  // **넣자마자 되묻기 위해** 그 한 건이 눈에 띄었는지 함께 답한다. 목록에서 찾아
  // 다시 누르게 하면 아무도 안 한다.
  const paymentId = idOf(row);
  const e = entries().find((x) => x.id === paymentId);
  return {
    id: rows().length,
    paymentId,
    flagged: e?.waste ?? false,
    reason: e?.reason ?? null,
    category2: e?.category2 ?? null,
    typical: e?.typical ?? null,
    samples: e?.samples ?? 0,
  };
}
