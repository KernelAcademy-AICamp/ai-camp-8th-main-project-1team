const BASE = import.meta.env.VITE_API_BASE ?? 'http://localhost:8080';

export type DataSourceMode = 'ESTIMATED' | 'CONFIRMED';

export interface ScoreBreakdown {
  periodFit: number;
  riskFit: number;
  categoryFit: number;
}

export interface RecommendItem {
  rank: number;
  productId: number;
  name: string;
  productType: string;
  riskGrade: string;
  expectedRate: number;
  minJoinAmount: number;
  minPeriodMonths: number;
  targetCategoryCode: string | null;
  matchScore: number;
  scoreBreakdown: ScoreBreakdown;
  gateReason: string | null;
}

export interface RecommendResponse {
  userId: number;
  items: RecommendItem[];
  availableFunds: number;
  gatingRelaxed: boolean;
  overspendingCategories: string[];
  longTermVolatilityIndex: number;
  dataSourceMode: DataSourceMode;
  estimationReason: string | null;
}

export interface AlertItem {
  alertId: number;
  consumptionId: number;
  categoryCode: string;
  amount: number;
  occurredAt: string;
  deviationScore: number;
  matchedRules: string[];
}

export interface AlertResponse {
  userId: number;
  items: AlertItem[];
  evaluatedCount: number;
  dataSourceMode: DataSourceMode;
  estimationReason: string | null;
}

export interface ReportLine {
  categoryCode: string;
  displayName: string;
  amount: number;
  spendPercent: number;
  count: number;
}

export interface ReportResponse {
  totalSpend: number;
  positive: ReportLine[];
  negative: ReportLine[];
  monthlySpend: Record<string, number>;
  narrative: string;
  narrativeSource: string;
  dataSourceMode: DataSourceMode;
  estimationReason: string | null;
}

export interface ScoreResponse {
  score: number;
  grade: string;
  breakdown: { savingsProgress: number; stability: number; plannedRatio: number };
  dataSourceMode: DataSourceMode;
  estimationReason: string | null;
}

export interface AnchorStatusView {
  batchId: number;
  batchRoot: string;
  anchorStatus: 'PENDING' | 'ANCHORED' | 'FAILED';
  tsaGenTime: string | null;
  tsaName: string | null;
  problem: string | null;
}

export interface VerifyResponse {
  valid: boolean;
  entryCount: number;
  batchCount: number;
  anchoredBatchCount: number;
  firstBrokenSeq: number | null;
  problems: string[];
  batches: AnchorStatusView[];
}

export interface AnchorReport {
  pendingCount: number;
  anchored: number;
  failed: number;
  tsaEnabled: boolean;
  messages: string[];
}

export interface UserView {
  userId: number;
  nickname: string;
  monthlyIncome: number;
  goalAmount: number;
  goalMonths: number;
  consentGiven: boolean;
}

export interface PrivacyPolicy {
  title: string;
  clauses: { title: string; body: string }[];
  notice: string;
}

export interface CategoryView {
  id: number;
  code: string;
  displayName: string;
}

/* ── 게임화 저축 루프 (Qapital 벤치마크 + 치팅데이 쿠폰 · 문서 §5-5) ── */
export interface MilestoneView {
  id: number;
  name: string;
  emoji: string;
  cost: number;
  acquired: boolean;
  progress: number;
  remaining: number;
}
export interface GoalView {
  id: number;
  name: string;
  emoji: string;
  targetAmount: number;
  balance: number;
  projected: number;
  progress: number;
  priority: boolean;
  milestones: MilestoneView[];
  deadlineDays: number;
  /** 가는 날 N일 단축 = 잔액이 커버한 기한일수 */
  fundedDays: number;
  /** 저축 계획 — 줄이기로 한 습관 소비 카테고리 코드 */
  planCutCategories: string[];
  /** 그 소비들의 월 절약액 */
  planMonthlySaving: number;
  /** 그 절약액으로 이 목표 달성 개월수 (계획 없으면 0) */
  planMonths: number;
}
/** 계획에서 줄일 수 있는 습관 소비 후보 (카테고리별 월평균) */
export interface CutOption {
  categoryCode: string;
  displayName: string;
  monthlyAmount: number;
}
/** 목표별 추천 통장 (실 적금, 중복 없이) */
export interface GoalRecommendation {
  goalId: number;
  goalName: string;
  emoji: string;
  periodMonths: number;
  monthlyAmount: number;
  planMonths: number;
  company: string | null;
  productName: string | null;
  baseRate: number;
  live: boolean;
}
export interface ForcedWithdrawal { goalName: string; amount: number; }
/** 참는 순간의 목표 진척 변화 — "62% → 68% · D-N 단축" (획득 프레이밍). */
export interface GoalGain {
  goalName: string;
  emoji: string;
  progressBefore: number;
  progressAfter: number;
  daysAdded: number;
  balanceAfter: number;
}
export interface CouponView { id: number; categoryCode: string | null; benefitAmount: number; }
export interface PointSuggestion {
  categoryCode: string;
  displayName: string;
  typicalAmount: number;
  totalUnplanned: number;
}
export interface PointEventView {
  type: 'DEPOSIT' | 'WITHDRAWAL';
  reason: string | null;
  amount: number;
  categoryCode: string | null;
  occurredAt: string;
}
export interface WishlistView {
  id: number;
  name: string;
  price: number;
  categoryCode: string | null;
  imageUrl: string | null;
}
/** URL/스크린샷에서 추출한 상품 정보(저장 전 미리보기). 못 찾은 값은 null. */
export interface LookupResult {
  name: string | null;
  price: number | null;
  imageUrl: string | null;
  categoryCode: string | null;
  /** 안티봇 차단 등 안내 메모(있으면 화면에 표시) */
  note: string | null;
}
export interface PointSnapshot {
  userId: number;
  month: string;
  monthlyBudget: number;
  thisMonthSpent: number;
  thisMonthSaved: number;
  /** 이번 달 아직 쓸 수 있는 돈 (음수면 예산 초과) */
  pointsRemaining: number;
  /** 목표에 실제로 모인 총액 */
  totalSavings: number;
  totalTarget: number;
  /** 선물상자 채움 비율 0~1 */
  giftFill: number;
  /** 직전 액션: SAVED · SPEND · UNNECESSARY · OVERSPEND · COUPON_* · null */
  lastAction: string | null;
  lastAmount: number;
  forcedWithdrawal: ForcedWithdrawal | null;
  /** 대기 중 치팅데이 쿠폰 (없으면 null) */
  coupon: CouponView | null;
  productName: string | null;
  productRate: number;
  goalMonths: number;
  goals: GoalView[];
  suggestions: PointSuggestion[];
  recentEvents: PointEventView[];
  /** 고민 목록 — 살까 말까 고민 중인 상품 */
  wishlist: WishlistView[];
  /** 안 사서 아낀 총액 */
  savedByNotBuying: number;
  /** 소비건전성지수 (미계획 소비 지속 시 하락) */
  healthScore: number;
  healthGrade: string;
  /** 최근 연속 미계획(필요없는) 소비 횟수 */
  unnecessaryStreak: number;
  /** 행동 경고 — 미계획 연속·다짐 어김 */
  behaviorAlerts: string[];
  /** 직전 '살 뻔했다/안 샀어요'가 어느 목표를 얼마나 진척시켰는가 (획득 프레이밍, 없으면 null) */
  gain: GoalGain | null;
  /** 저축 계획에서 줄일 수 있는 습관 소비 후보 (카테고리별 월평균) */
  cutOptions: CutOption[];
}

/* ── 통장 비교 (정보성 · 문서 §5-5). 판매·중개 아님, 가입은 각 금융사에서. ── */
export interface AccountView {
  company: string;
  name: string;
  /** 기본금리(%) */
  baseRate: number;
  /** 최고금리(%) */
  primeRate: number;
}
export interface SavingsCompare {
  accounts: AccountView[];
  /** true=실시간 조회, false=예시(더미) 폴백 */
  live: boolean;
  /** 자격 제한 제외 후 남은 전체 상품 수 */
  totalConsidered: number;
  note: string | null;
}

/* ── 충동예산 절약통 (문서 §5-5, 2026-07-21 방향 전환) — 수동 '살 뻔했다' 대체 ── */
export interface ImpulseCategoryOption { categoryCode: string; displayName: string; monthlyAmount: number; }
export interface ImpulseVerifyRow {
  categoryCode: string; displayName: string;
  baseline: number; latest: number; changePct: number; improved: boolean;
}
export interface ImpulseSnapshot {
  /** 충동예산(월 평균) */
  budget: number;
  /** 현재 절약통 잔액 */
  giftBalance: number;
  /** 채움 비율 0~1 */
  giftFill: number;
  /** 하루 할당량 */
  dailyQuota: number;
  impulseCategories: string[];
  options: ImpulseCategoryOption[];
  hasUpload: boolean;
  verify: ImpulseVerifyRow[];
  /** 직전 액션(GROW·UNNECESSARY·null) — 선물상자 애니메이션 */
  lastAction: string | null;
  /** 방금 업로드로 적재된 건수 */
  uploaded: number;
}

/* ── 마이데이터 (§13) ── */
/** 가상 본인인증 결과. verified는 항상 true(가상), existsInMyData=false면 마이데이터에 없는 신원. */
export interface VerifyResult { ci: string; verified: boolean; existsInMyData: boolean; }
export interface MyDataCompany { id: number; name: string; imgUrl: string; }
export interface MyDataLinkResult { cardCount: number; paymentCount: number; }
/** 내 카드 — 실적 진행률 + 이번달 받은 혜택. */
export interface MyCard {
  serialNumber: string;
  cardCode: number;
  cardName: string;
  cardColor: string;
  companyName: string;
  requirement: number;
  currentPerformance: number;
  requirementMet: boolean;
  toRequirement: number;
  earnedThisMonth: number;
}
/** 카드 상세 결제내역 1건. */
export interface MyPayment {
  paymentId: string;
  date: string;
  category1: string;
  category2: string | null;
  amount: number;
  merchantName: string | null;
  receivedBenefit: number;
}

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} — ${path}`);
  return res.json() as Promise<T>;
}

async function post<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} — ${path}`);
  return res.json() as Promise<T>;
}

async function del<T>(path: string): Promise<T> {
  const res = await fetch(`${BASE}${path}`, { method: 'DELETE' });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} — ${path}`);
  return res.json() as Promise<T>;
}

async function put<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: 'PUT',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} — ${path}`);
  return res.json() as Promise<T>;
}

export interface ConsumptionInput {
  userId: number;
  categoryCode: string;
  amount: number;
  occurredAt: string;
  planned: boolean;
}

export const api = {
  recommend: (userId: number) => get<RecommendResponse>(`/api/products/recommend?userId=${userId}`),
  alerts: (userId: number) => get<AlertResponse>(`/api/alert/list?userId=${userId}`),
  rescan: (userId: number) => post<unknown>(`/api/alert/rescan?userId=${userId}`),
  report: (userId: number) => get<ReportResponse>(`/api/report/monthly?userId=${userId}`),
  score: (userId: number) => get<ScoreResponse>(`/api/score/${userId}`),
  verifyAudit: () => get<VerifyResponse>('/api/audit/verify'),
  anchorAudit: () => post<AnchorReport>('/api/audit/anchor'),
  seed: (body: unknown) => post<{ userId: number }>('/api/dev/seed', body),

  // 사용자 · 동의 · 정보주체 권리
  getUser: (userId: number) => get<UserView>(`/api/users/${userId}`),
  setConsent: (userId: number, consent: boolean) =>
    post<UserView>(`/api/users/${userId}/consent`, { consent }),
  exportMyData: (userId: number) =>
    get<{ recordCount: number; records: unknown[] }>(`/api/users/${userId}/data`),
  eraseMyData: (userId: number) =>
    del<{ deletedCount: number }>(`/api/users/${userId}/data`),

  privacyPolicy: () => get<PrivacyPolicy>('/api/privacy/policy'),
  categories: () => get<CategoryView[]>('/api/categories'),
  addConsumption: (input: ConsumptionInput) => post<{ id: number }>('/api/consumption', input),

  /** 계측 — 실패해도 화면이 죽으면 안 되므로 조용히 삼킨다 */
  track: (event: string, userId?: number, properties?: Record<string, unknown>) =>
    fetch(`${BASE}/api/analytics/track`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ event, userId, properties }),
    }).catch(() => undefined),

  survey: (body: Record<string, unknown>) => post<{ responseCount: number }>('/api/analytics/survey', body),

  /* ── 게임화 저축 루프 (Qapital 벤치마크 + 치팅데이 쿠폰 · 문서 §5-5). 판단은 서버가. ── */
  points: (userId: number) => get<PointSnapshot>(`/api/points?userId=${userId}`),
  /** "살 뻔했다" — 참은 즉시 랜덤 목표에 자동 입금 */
  avoid: (userId: number, categoryCode: string, amount: number) =>
    post<PointSnapshot>('/api/points/avoided', { userId, categoryCode, amount }),
  /** 실제 소비 (necessary=false면 필요없는 소비 → 선물상자 깨짐, 예산 초과 시 강제차감) */
  spend: (userId: number, categoryCode: string, amount: number, necessary: boolean) =>
    post<PointSnapshot>('/api/points/spend', { userId, categoryCode, amount, necessary }),

  // 목표 버킷 CRUD
  createGoal: (userId: number, name: string, emoji: string, targetAmount: number) =>
    post<PointSnapshot>('/api/points/goals', { userId, name, emoji, targetAmount }),
  updateGoal: (userId: number, goalId: number,
    patch: { name?: string; emoji?: string; targetAmount?: number; priority?: boolean }) =>
    put<PointSnapshot>(`/api/points/goals/${goalId}`, { userId, ...patch }),
  deleteGoal: (userId: number, goalId: number) =>
    del<PointSnapshot>(`/api/points/goals/${goalId}?userId=${userId}`),

  // 목표 마일스톤
  addMilestone: (userId: number, goalId: number, m: { name: string; emoji: string; cost: number }) =>
    post<PointSnapshot>(`/api/points/goals/${goalId}/milestones`, { userId, ...m }),
  deleteMilestone: (userId: number, milestoneId: number) =>
    del<PointSnapshot>(`/api/points/milestones/${milestoneId}?userId=${userId}`),

  // 저축 계획 · 목표별 통장 추천
  /** 이 목표를 위해 줄일 습관 소비 카테고리 저장 → 월 절약액·개월수 파생 */
  setGoalPlan: (userId: number, goalId: number, cutCategories: string[]) =>
    post<PointSnapshot>(`/api/points/goals/${goalId}/plan`, { userId, cutCategories }),
  /** 목표 1·2·3에 계획 기간에 맞는 실 적금 추천(중복 없이) */
  goalRecommendations: (userId: number) =>
    get<GoalRecommendation[]>(`/api/points/recommendations?userId=${userId}`),

  // 치팅데이 쿠폰 (Phase 3)
  useCoupon: (userId: number, couponId: number) =>
    post<PointSnapshot>(`/api/points/coupon/${couponId}/use?userId=${userId}`),
  declineCoupon: (userId: number, couponId: number) =>
    post<PointSnapshot>(`/api/points/coupon/${couponId}/decline?userId=${userId}`),

  // 고민 목록 (폴센트 응용) — 조회(추출만)와 담기(저장)를 분리
  lookupProductUrl: (url: string) =>
    post<LookupResult>('/api/points/wishlist/lookup-url', { url }),
  lookupProductImage: (imageBase64: string, mimeType: string) =>
    post<LookupResult>('/api/points/wishlist/lookup-image', { imageBase64, mimeType }),
  addWishlist: (userId: number, item: {
    name: string; price: number; categoryCode?: string; imageUrl?: string; sourceUrl?: string; source?: string;
  }) => post<PointSnapshot>('/api/points/wishlist/add', { userId, ...item }),
  wishlistNotBought: (userId: number, itemId: number) =>
    post<PointSnapshot>(`/api/points/wishlist/${itemId}/not-bought?userId=${userId}`),
  wishlistBought: (userId: number, itemId: number) =>
    post<PointSnapshot>(`/api/points/wishlist/${itemId}/bought?userId=${userId}`),
  deleteWishlist: (userId: number, itemId: number) =>
    del<PointSnapshot>(`/api/points/wishlist/${itemId}?userId=${userId}`),

  /** 통장 비교 (정보성) — 자격 제한 제외 후 금리순. 판매·중개 아님. */
  compareSavings: (limit?: number) =>
    get<SavingsCompare>(`/api/savings/compare${limit ? `?limit=${limit}` : ''}`),

  /* ── 충동예산 절약통 ── */
  /** 조회 = 방문. 시간에 따라 자동 성장한 스냅샷 */
  impulse: (userId: number) => get<ImpulseSnapshot>(`/api/impulse?userId=${userId}`),
  /** 충동 카테고리 지정 → 예산 재계산 */
  setImpulseCategories: (userId: number, categories: string[]) =>
    post<ImpulseSnapshot>('/api/impulse/categories', { userId, categories }),
  /** 충동소비 기록 → 절약통 균열 */
  impulseSpend: (userId: number, categoryCode: string, amount: number) =>
    post<ImpulseSnapshot>('/api/impulse/spend', { userId, categoryCode, amount }),
  /** 다음달 카드내역(CSV) 재업로드 → 재검증 */
  impulseUpload: (userId: number, csv: string) =>
    post<ImpulseSnapshot>('/api/impulse/upload', { userId, csv }),

  /* ── 마이데이터 (§13) ── */
  verify: (userId: number, name: string, social7: string, phone: string) =>
    post<VerifyResult>('/api/mydata/verify', { userId, name, social7, phone }),
  mydataCompanies: () => get<MyDataCompany[]>('/api/mydata/companies'),
  mydataLink: (userId: number, companyIds: number[]) =>
    post<MyDataLinkResult>('/api/mydata/link', { userId, companyIds }),
  myCards: (userId: number) => get<MyCard[]>(`/api/mydata/cards?userId=${userId}`),
  cardPayments: (userId: number, serial: string) =>
    get<MyPayment[]>(`/api/mydata/cards/${encodeURIComponent(serial)}/payments?userId=${userId}`),
};

/** 룰 코드 → 사람이 읽는 문구. 화면에서만 쓰는 표시용 매핑이다. */
export const RULE_LABEL: Record<string, string> = {
  NIGHT_HIGH_AMOUNT: '심야 고액',
  NEW_CATEGORY_SPIKE: '신규 카테고리 급증',
  FREQUENCY_DEVIATION: '빈도 이탈',
};

/**
 * 카테고리 코드 → 한글 표시명. RULE_LABEL과 같은 **표현 전용** 매핑이다.
 * 판단 로직(엔진·임계치)은 코드에 카테고리를 박지 않는다(설계원칙 4). 여기는 화면 표시일 뿐이다.
 * 서버가 내려준 displayName이 코드와 다르면 그쪽을 우선한다 — 이 맵은 폴백.
 */
export const CATEGORY_LABEL: Record<string, string> = {
  FOOD: '식비', CAFE: '카페·간식', SHOPPING: '쇼핑', TRANSPORT: '교통',
  HOUSING: '주거', MEDICAL: '의료', CULTURE: '문화·여가', EDUCATION: '교육',
  COMMUNICATION: '통신', BEAUTY: '미용', TRAVEL: '여행', ETC: '기타',
};
export const catLabel = (code: string, displayName?: string) =>
  (displayName && displayName !== code ? displayName : CATEGORY_LABEL[code]) ?? code;
