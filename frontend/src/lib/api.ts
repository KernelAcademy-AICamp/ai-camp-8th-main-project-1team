/**
 * 백엔드 연결층 — 이 파일 하나가 서버와의 계약 전부다.
 *
 * 기존 화면이 쓰던 엔드포인트를 하나도 잃지 않고 그대로 옮겼고(§소비분석·마이데이터·게임화 저축·
 * 충동절약통·설문·동의), 여기에 **지킴이 Agent(`/api/guardian/*`)** 를 새로 이었다.
 * 지킴이는 백엔드에 구현돼 있었지만 어느 화면에서도 부르지 않던 미연결 영역이었다.
 *
 * 원칙: <b>프론트는 계산하지 않는다.</b> 남은 한도·달성률·며칠 남았는지는 서버가 완성해 내려준다
 * (GuardianController 주석). 여기 타입은 그 응답을 그대로 받아 적은 것이다.
 */
import { API_BASE } from './config';

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

/* ── 게임화 저축 루프 (문서 §5-5) ─────────────────────────────────────── */
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
  /** 이 목표의 자유입출금통장(§13-11) */
  accountBank: string | null;
  accountProduct: string | null;
  accountNumber: string | null;
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
export interface ForcedWithdrawal { goalName: string; amount: number }
/** 참는 순간의 목표 진척 변화 — "62% → 68% · D-N 단축" (획득 프레이밍). */
export interface GoalGain {
  goalName: string;
  emoji: string;
  progressBefore: number;
  progressAfter: number;
  daysAdded: number;
  balanceAfter: number;
}
export interface CouponView { id: number; categoryCode: string | null; benefitAmount: number }
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
  note: string | null;
}
export interface PointSnapshot {
  userId: number;
  month: string;
  monthlyBudget: number;
  thisMonthSpent: number;
  thisMonthSaved: number;
  pointsRemaining: number;
  totalSavings: number;
  totalTarget: number;
  giftFill: number;
  lastAction: string | null;
  lastAmount: number;
  forcedWithdrawal: ForcedWithdrawal | null;
  coupon: CouponView | null;
  productName: string | null;
  productRate: number;
  goalMonths: number;
  goals: GoalView[];
  suggestions: PointSuggestion[];
  recentEvents: PointEventView[];
  wishlist: WishlistView[];
  savedByNotBuying: number;
  healthScore: number;
  healthGrade: string;
  unnecessaryStreak: number;
  behaviorAlerts: string[];
  gain: GoalGain | null;
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
  totalConsidered: number;
  note: string | null;
}

/* ── 충동예산 절약통 (문서 §5-5) ──────────────────────────────────────── */
export interface ImpulseCategoryOption { categoryCode: string; displayName: string; monthlyAmount: number }
export interface ImpulseVerifyRow {
  categoryCode: string; displayName: string;
  baseline: number; latest: number; changePct: number; improved: boolean;
}
export interface ImpulseSnapshot {
  budget: number;
  giftBalance: number;
  giftFill: number;
  dailyQuota: number;
  impulseCategories: string[];
  options: ImpulseCategoryOption[];
  hasUpload: boolean;
  verify: ImpulseVerifyRow[];
  lastAction: string | null;
  uploaded: number;
}

/* ── 마이데이터 (§13) ─────────────────────────────────────────────────── */
/** 가상 본인인증 결과. verified는 항상 true(가상), existsInMyData=false면 마이데이터에 없는 신원. */
export interface VerifyResult { ci: string; verified: boolean; existsInMyData: boolean }
export interface MyDataCompany { id: number; name: string; imgUrl: string }
export interface MyDataLinkResult { cardCount: number; paymentCount: number; bankCount: number }
/** 연동 가능 은행. id는 제공자가 이름순으로 매긴 순번이라 조회마다 같다. */
export interface MyDataBank { id: number; name: string }
/** 내가 연동한 은행. */
export interface MyLinkedBank { id: number; bankId: number; bankName: string; linkedAt: string }
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
  businessNumber: string | null;
}
/** 결제내역 모아보기 1건(§13-11) — 결제 정보 + 어느 카드인지. */
export interface MyPaymentHistory {
  paymentId: string;
  date: string;
  category1: string;
  category2: string | null;
  amount: number;
  merchantName: string | null;
  receivedBenefit: number;
  cardName: string | null;
  cardColor: string | null;
  companyName: string | null;
  businessNumber: string | null;
}
/** 가맹점 조회(번호→주소). */
export interface MyMerchant {
  businessNumber: string;
  merchantName: string | null;
  address: string | null;
  lat: number | null;
  lng: number | null;
  online: boolean;
}
/** 입출금 통장(§13-11 경제 모델). */
export interface MyAccountTxn { date: string; type: 'DEPOSIT' | 'WITHDRAWAL'; amount: number; description: string }
export interface MyAccount {
  accountNumber: string;
  bank: string;
  product: string;
  salaryPayer: string;
  salary: number;
  payday: number;
  balance: number;
  transactions: MyAccountTxn[];
}
/** 결제별 ML 낭비/필수 판정 + '왜' (§W8, /api/ml/waste). */
export interface WasteJudgment {
  paymentId: string;
  category2: string | null;
  amount: number;
  date: string;
  wasteProbability: number;
  waste: boolean;
  explanation: string;
}

export interface ConsumptionInput {
  userId: number;
  categoryCode: string;
  amount: number;
  occurredAt: string;
  planned: boolean;
}

/* ── 소비 분석(②③④⑤) ────────────────────────────────────────────────── */
export interface AnalysisProfile {
  abnormalityIndex: number;
  wasteRatio: number;
  concentrationRatio: number;
  volatility: number;
  nightImpulseRatio: number;
  contributionPoints: Record<string, number>;
  totalSpend: number;
  topCategory1: string | null;
  fixedCount: number;
  routineCount: number;
  peak: { dayOfWeek: string; daypart: string; amount: number } | null;
}
export interface RecurringPayment {
  type: 'FIXED' | 'ROUTINE';
  category2: string;
  merchantName: string | null;
  businessNumber: string | null;
  daypart: string | null;
  representativeAmount: number;
  periodDays: number | null;
  nextExpected: string | null;
  occurrenceDays: number;
  perWeekFrequency: number;
}
export interface SpendingPattern {
  amountByDayOfWeek: Record<string, number>;
  amountByDaypart: Record<string, number>;
  countByCell: Record<string, number>;
  peak: { dayOfWeek: string; daypart: string; amount: number } | null;
}
export interface CutCandidate {
  category2: string;
  type: 'REMOVABLE' | 'OPTIMIZABLE';
  monthlySpend: number;
  estimatedSaving: number;
  reason: string;
}
export interface AnalysisSummary {
  profile: AnalysisProfile;
  recurring: RecurringPayment[];
  pattern: SpendingPattern;
  cutCandidates: CutCandidate[];
}
export interface CutSelection {
  id: number;
  userId: number;
  category2: string;
  type: 'REMOVABLE' | 'OPTIMIZABLE';
  targetSaving: number;
  baselineSpend: number;
  selectedAt: string;
  status: 'ACTIVE' | 'VERIFIED';
  verifiedAt: string | null;
  actualSpend: number | null;
  improved: boolean | null;
}
export interface Narrative { text: string; source: string }

/* ══════════════════════════════════════════════════════════════════════
   지킴이 Agent (§/api/guardian) — 설계서 11_지킴이_Agent_설계.md
   ══════════════════════════════════════════════════════════════════════ */

export type ChallengeState =
  | 'SETUP' | 'ACTIVE' | 'AT_RISK' | 'EXCEEDED' | 'SETTLING'
  | 'SUCCESS' | 'PARTIAL' | 'SHORTFALL' | 'FAILED' | 'ABANDONED'
  | 'REWARD_PENDING' | 'RESTART_OFFER' | 'CLOSED';
export type DailyResult = 'NO_SPEND_DAY' | 'ON_PACE_DAY' | 'OFF_PACE_DAY' | 'NO_GRANT';
export type Grade = 'COMMON' | 'RARE' | 'EPIC';
export type TxState = 'PENDING_CATEGORY' | 'COUNTED' | 'EXCLUDED' | 'EXEMPTED';
export type UndoReason = 'NOT_MINE' | 'EXEMPTION';
export type Feedback = 'USEFUL' | 'NOT_USEFUL';
export type FeedbackReason = 'TIMING' | 'TONE' | 'ALREADY_KNEW' | 'NOT_MINE' | 'TOO_OFTEN';

/** 챌린지 스냅샷 — 전부 서버 계산값. 프론트는 다시 계산하지 않는다. */
export interface GuardianSnapshot {
  spentAmount: number;
  remainingCap: number;
  spentRatio: number;
  /** 확보 절약액 = 지금 지키고 있는 돈. Home의 주 지표. */
  securedSaving: number;
  achievementRate: number;
  daysElapsed: number;
  daysLeft: number;
  paceRatio: number;
  allowedRatio: number;
}
export interface GuardianChallenge extends GuardianSnapshot {
  id: number;
  state: ChallengeState;
  categories: string[];
  baselineAmount: number;
  targetSaving: number;
  challengeCap: number;
  bufferRatio: number;
  startDate: string;
  endDate: string;
  daysTotal: number;
  rewardName: string | null;
  rewardPrice: number | null;
  /** 선택 카테고리 표시명(·로 이어붙인 것). 서버가 만들어 내려준다. */
  categoryLabel: string;
}
export interface GuardianStrip {
  remainingCapLabel: string;
  pendingCount: number;
  pendingBadge: string | null;
  noSpendStreak: number;
  grassStreak: number;
  pointBalance: number;
  unopenedCeremony: boolean;
}
export interface GuardianCeremony {
  verdictDate: string;
  result: DailyResult;
  objectId: string | null;
  grade: Grade | null;
  message: string | null;
  rerollAvailable: boolean;
}
export interface GrassCell {
  date: string;
  result: DailyResult;
  granted: boolean;
  /** 잔디 보호권으로 지켜진 날. */
  protected: boolean;
}
export interface GuardianItems {
  exemption: number;
  grassGuard: number;
  missionChange: number;
  pointBalance: number;
}
export interface GuardianHome {
  asOf: string;
  challenge: GuardianChallenge;
  strip: GuardianStrip;
  ceremony: GuardianCeremony | null;
  grass: GrassCell[];
  itemsHeld: GuardianItems;
  unreadNotifications: number;
  demoMode: boolean;
}
export interface GuardianNotification {
  id: number;
  caseId: string;
  tone: string | null;
  phrasingMode: 'TENTATIVE' | 'DEFINITIVE' | null;
  delivery: 'PUSH' | 'INAPP' | 'MODAL' | 'SILENT';
  suppressedReason: string | null;
  title: string | null;
  body: string | null;
  isFallback: boolean;
  sentAt: string | null;
  feedback: Feedback | null;
}
export interface GuardianTransactionView {
  id: number;
  state: TxState;
  amount: number;
  category: string | null;
  undoDeadline: string | null;
  undoActions?: { reason: UndoReason; label: string; remaining?: number }[];
}
export interface GuardianRoomObject {
  objectId: string;
  grade: Grade;
  acquiredDate: string;
  reasonCode: string | null;
  slotIndex: number | null;
}
export interface GuardianRoom { objects: GuardianRoomObject[]; slotCount: number }
export interface CreateChallengeInput {
  categories: string[];
  sanctuaryCategories?: string[];
  targetSaving?: number;
  rewardName?: string;
  rewardPrice?: number;
  durationDays?: number;
}
export interface GuardianIngestResult {
  transaction: GuardianTransactionView;
  snapshot: GuardianSnapshot | null;
  state: ChallengeState;
  notification: GuardianNotification | null;
}
export interface GuardianUndoResult {
  transaction: GuardianTransactionView;
  snapshot: GuardianSnapshot;
  state: ChallengeState;
  toast: string | null;
  itemsHeld: GuardianItems;
}
export interface GuardianVerdict {
  date: string;
  result: DailyResult;
  grantObject: boolean;
  reasonCode: string | null;
  snapshot: { spentAtDate: number; spentRatio: number; paceRatio: number; allowedRatio: number };
}
export interface GuardianBatchResult {
  verdict: GuardianVerdict;
  grantedObject: { objectId: string; grade: Grade } | null;
  notifications: GuardianNotification[];
  pointEvents: unknown[];
  stateTransition: string | null;
}
export interface GuardianAdvanceResult {
  asOf: string;
  batches: GuardianBatchResult[];
  home: GuardianHome;
}

/* ── HTTP 헬퍼 ────────────────────────────────────────────────────────── */

/** 서버가 message를 실어 보내면 그 문장을 사용자에게 그대로 보여준다(백엔드가 우리말로 쓴다). */
export class ApiError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.name = 'ApiError';
    this.status = status;
  }
}

async function fail(res: Response, path: string): Promise<never> {
  let message = `${res.status} ${res.statusText} — ${path}`;
  try {
    const body = await res.json() as { message?: string; error?: string };
    if (body?.message) message = body.message;
    else if (body?.error) message = body.error;
  } catch { /* 본문이 JSON이 아니면 기본 문구 */ }
  throw new ApiError(res.status, message);
}

async function request<T>(method: string, path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method,
    headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) return fail(res, path);
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : null) as T;
}

const get = <T,>(path: string) => request<T>('GET', path);
const post = <T,>(path: string, body?: unknown) => request<T>('POST', path, body);
const put = <T,>(path: string, body?: unknown) => request<T>('PUT', path, body);
const del = <T,>(path: string) => request<T>('DELETE', path);

export const api = {
  recommend: (userId: number) => get<RecommendResponse>(`/api/products/recommend?userId=${userId}`),
  alerts: (userId: number) => get<AlertResponse>(`/api/alert/list?userId=${userId}`),
  rescan: (userId: number) => post<unknown>(`/api/alert/rescan?userId=${userId}`),
  report: (userId: number) => get<ReportResponse>(`/api/report/monthly?userId=${userId}`),
  score: (userId: number) => get<ScoreResponse>(`/api/score/${userId}`),

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

  /** 계측 — 실패해도 화면이 죽으면 안 되므로 조용히 삼킨다. */
  track: (event: string, userId?: number, properties?: Record<string, unknown>) =>
    fetch(`${API_BASE}/api/analytics/track`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ event, userId, properties }),
    }).catch(() => undefined),

  survey: (body: Record<string, unknown>) => post<{ responseCount: number }>('/api/analytics/survey', body),

  /* ── 게임화 저축 루프 ── */
  points: (userId: number) => get<PointSnapshot>(`/api/points?userId=${userId}`),
  avoid: (userId: number, categoryCode: string, amount: number) =>
    post<PointSnapshot>('/api/points/avoided', { userId, categoryCode, amount }),
  spend: (userId: number, categoryCode: string, amount: number, necessary: boolean) =>
    post<PointSnapshot>('/api/points/spend', { userId, categoryCode, amount, necessary }),

  createGoal: (userId: number, name: string, emoji: string, targetAmount: number) =>
    post<PointSnapshot>('/api/points/goals', { userId, name, emoji, targetAmount }),
  updateGoal: (userId: number, goalId: number,
    patch: { name?: string; emoji?: string; targetAmount?: number; priority?: boolean }) =>
    put<PointSnapshot>(`/api/points/goals/${goalId}`, { userId, ...patch }),
  deleteGoal: (userId: number, goalId: number) =>
    del<PointSnapshot>(`/api/points/goals/${goalId}?userId=${userId}`),

  addMilestone: (userId: number, goalId: number, m: { name: string; emoji: string; cost: number }) =>
    post<PointSnapshot>(`/api/points/goals/${goalId}/milestones`, { userId, ...m }),
  deleteMilestone: (userId: number, milestoneId: number) =>
    del<PointSnapshot>(`/api/points/milestones/${milestoneId}?userId=${userId}`),

  setGoalPlan: (userId: number, goalId: number, cutCategories: string[]) =>
    post<PointSnapshot>(`/api/points/goals/${goalId}/plan`, { userId, cutCategories }),
  goalRecommendations: (userId: number) =>
    get<GoalRecommendation[]>(`/api/points/recommendations?userId=${userId}`),

  useCoupon: (userId: number, couponId: number) =>
    post<PointSnapshot>(`/api/points/coupon/${couponId}/use?userId=${userId}`),
  declineCoupon: (userId: number, couponId: number) =>
    post<PointSnapshot>(`/api/points/coupon/${couponId}/decline?userId=${userId}`),

  // 고민 목록 — 조회(추출만)와 담기(저장)를 분리
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
  impulse: (userId: number) => get<ImpulseSnapshot>(`/api/impulse?userId=${userId}`),
  setImpulseCategories: (userId: number, categories: string[]) =>
    post<ImpulseSnapshot>('/api/impulse/categories', { userId, categories }),
  impulseSpend: (userId: number, categoryCode: string, amount: number) =>
    post<ImpulseSnapshot>('/api/impulse/spend', { userId, categoryCode, amount }),
  impulseUpload: (userId: number, csv: string) =>
    post<ImpulseSnapshot>('/api/impulse/upload', { userId, csv }),

  /* ── 마이데이터 (§13) ── */
  verify: (userId: number, name: string, social7: string, phone: string) =>
    post<VerifyResult>('/api/mydata/verify', { userId, name, social7, phone }),
  mydataCompanies: () => get<MyDataCompany[]>('/api/mydata/companies'),
  mydataBanks: () => get<MyDataBank[]>('/api/mydata/banks'),
  myBanks: (userId: number) => get<MyLinkedBank[]>(`/api/mydata/my-banks?userId=${userId}`),
  /** 카드사와 은행을 함께 연동한다. 은행은 계좌가 있는 곳만 실제로 붙는다. */
  mydataLink: (userId: number, companyIds: number[], bankIds: number[] = []) =>
    post<MyDataLinkResult>('/api/mydata/link', { userId, companyIds, bankIds }),
  myCards: (userId: number) => get<MyCard[]>(`/api/mydata/cards?userId=${userId}`),
  cardPayments: (userId: number, serial: string) =>
    get<MyPayment[]>(`/api/mydata/cards/${encodeURIComponent(serial)}/payments?userId=${userId}`),
  allPayments: (userId: number, months = 6) =>
    get<MyPaymentHistory[]>(`/api/mydata/payments?userId=${userId}&months=${months}`),
  account: (userId: number) => get<MyAccount | null>(`/api/mydata/account?userId=${userId}`),
  merchant: (businessNumber: string) =>
    get<MyMerchant | null>(`/api/mydata/merchant/${encodeURIComponent(businessNumber)}`),
  syncMyData: (userId: number) =>
    post<{ newPayments: number }>(`/api/mydata/sync?userId=${userId}`),

  /** 결제별 ML 낭비/필수 판정 + '왜' (§W8). */
  mlWaste: (userId: number) => get<WasteJudgment[]>(`/api/ml/waste/${userId}`),

  /**
   * [dev·데모 전용] 생성 마이데이터 CI를 직접 연결한다(가상 인증 우회, §13-11).
   * 생성 CI는 GenSeed 해시라 정상 verify로 못 맞추므로 데모에선 CI를 직접 주입한다.
   */
  linkSynthetic: (ci: string, companyIds: number[]) =>
    post<{ userId: number; ci: string; cardCount: number; paymentCount: number }>(
      '/api/dev/link-synthetic', { ci, companyIds }),

  /* ── 소비 분석(②③④⑤) ── */
  analysis: (userId: number, days = 90) =>
    get<AnalysisSummary>(`/api/analysis?userId=${userId}&days=${days}`),
  profileNarrative: (userId: number, days = 90) =>
    get<Narrative>(`/api/analysis/profile/narrative?userId=${userId}&days=${days}`),
  explainCut: (userId: number, category2: string, days = 90) =>
    get<Narrative>(`/api/analysis/cut/explain?userId=${userId}&category2=${encodeURIComponent(category2)}&days=${days}`),
  chooseCut: (userId: number, category2: string, days = 90) =>
    post<CutSelection>(`/api/analysis/cut/choose?userId=${userId}&category2=${encodeURIComponent(category2)}&days=${days}`),
  verifyCut: (userId: number, days = 90) =>
    post<CutSelection[]>(`/api/analysis/cut/verify?userId=${userId}&days=${days}`),
  cutHistory: (userId: number) =>
    get<CutSelection[]>(`/api/analysis/cut/history?userId=${userId}`),

  /* ── 지킴이 Agent (§/api/guardian) ── */
  guardian: {
    /** 홈 한 방. 진행 중 챌린지가 없으면 404(ApiError.status===404). */
    home: (userId: number) => get<GuardianHome>(`/api/guardian/home?userId=${userId}`),
    room: (userId: number) => get<GuardianRoom>(`/api/guardian/room?userId=${userId}`),
    createChallenge: (userId: number, input: CreateChallengeInput) =>
      post<{ challenge: GuardianChallenge; snapshot: GuardianSnapshot }>(
        `/api/guardian/challenges?userId=${userId}`, input),
    /** 마이데이터 투영에서 아직 원장에 없는 결제를 끌어온다. */
    sync: (userId: number) => post<{ added: number }>(`/api/guardian/sync?userId=${userId}`),
    notifications: (userId: number) =>
      get<{ notifications: GuardianNotification[] }>(`/api/guardian/notifications?userId=${userId}`),
    feedback: (userId: number, id: number, feedback: Feedback, reason?: FeedbackReason) =>
      post<{ ok: boolean }>(`/api/guardian/notifications/${id}/feedback?userId=${userId}`,
        { feedback, reason: reason ?? null }),
    undo: (userId: number, txId: number, reason: UndoReason) =>
      post<GuardianUndoResult>(`/api/guardian/transactions/${txId}/undo?userId=${userId}`, { reason }),
    classify: (userId: number, txId: number, category: string, categoryConfidence = 1) =>
      post<GuardianIngestResult>(`/api/guardian/transactions/${txId}/category?userId=${userId}`,
        { category, categoryConfidence }),
    ceremonySeen: (userId: number, verdictId: number) =>
      post<{ ok: boolean }>(`/api/guardian/ceremony/${verdictId}/seen?userId=${userId}`),
    /** [데모] 가상 시계를 밀고 새벽 배치를 즉시 돌린다 — 30일 챌린지를 5분에 시연한다. */
    advance: (userId: number, days = 1) =>
      post<GuardianAdvanceResult>(`/api/guardian/demo/advance?userId=${userId}`, { days }),
    runDaily: (userId: number, targetDate?: string) =>
      post<GuardianBatchResult>(`/api/guardian/cron/daily?userId=${userId}`,
        targetDate ? { targetDate } : {}),
  },
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
