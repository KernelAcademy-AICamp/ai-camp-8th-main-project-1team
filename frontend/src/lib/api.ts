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
  /** 전 기간 누적 지출. 월 단위가 아니다 — 월평균이 필요하면 monthlyAmount를 쓴다. */
  amount: number;
  spendPercent: number;
  count: number;
  /** 이 카테고리의 월평균 지출. 서버가 카테고리별 관측 개월수로 나눠 준다. */
  monthlyAmount: number;
  /** 위 월평균을 낼 때 쓴 분모(그 카테고리가 등장한 달의 수). */
  observedMonths: number;
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
/**
 * 가상 본인인증 결과.
 *
 * `verified`는 **네 관문을 모두 통과했을 때만** true다. 실패 사유는 `reason`이 말해 준다 —
 * 판정 표는 서버에만 있고(국번 대역표) 화면은 사유에 맞는 문장을 고르기만 한다.
 */
export type VerifyReason =
  | 'OK'
  | 'UNASSIGNED_EXCHANGE'        // 배정되지 않은 국번 — 실존하지 않는 번호
  | 'NAME_MISMATCH'              // 번호 명의자와 이름만 다름
  | 'SOCIAL_MISMATCH'            // 번호 명의자와 주민번호만 다름
  | 'NAME_AND_SOCIAL_MISMATCH'   // 이름·주민번호 모두 다름
  | 'PHONE_OWNED_BY_OTHER'       // 그 번호가 다른 사람 명의
  | 'PHONE_MISMATCH'             // 신원은 실재하나 번호가 다름
  | 'NOT_FOUND'                  // 어느 조합으로도 못 찾음
  | 'CARRIER_MISMATCH';          // 신원은 맞으나 통신사 대역이 다름
export interface VerifyResult {
  ci: string | null;
  verified: boolean;
  existsInMyData: boolean;
  reason: VerifyReason;
  /** 번호 대역의 실제 통신사. 불일치 안내에 쓴다. */
  actualCarrier: string | null;
  /**
   * **이 신원의 계정.** 요청에 실어 보낸 userId와 다를 수 있다 — 서버는 CI로 계정을 고르므로,
   * 앞사람이 쓰던 브라우저에서 인증하면 여기로 갈아타야 한다. 실패하면 null.
   */
  userId: number | null;
}
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
  /** 소비 중분류. 제공자는 업종코드까지만 주고 이 값은 앱이 붙인다. */
  category: string;
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
  /** 소비 중분류. 제공자는 업종코드까지만 주고 이 값은 앱이 붙인다. */
  category: string;
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
  /** 제공자가 준 업종(KSIC 세분류). 표시용이 아니라 근거용이다. */
  ksicCode: string | null;
  /** 우리가 붙인 소비 중분류. 화면에는 이걸 쓴다. */
  category: string | null;
  businessNumber: string;
  merchantName: string | null;
  address: string | null;
  lat: number | null;
  lng: number | null;
  online: boolean;
}
/** 입출금 통장(§13-11 경제 모델). */
export interface MyAccountTxn {
  date: string;
  type: 'DEPOSIT' | 'WITHDRAWAL';
  amount: number;
  /** 적요 — 거래 상대나 성격. 예: 뚜레쥬르 병영1동점 · 이자입금 · 김민준 */
  description: string;
  /** 비고 — 취급점이나 채널. 예: KB국민카드 · BNK경남은행본부 · 전자금융이체 */
  note: string;
  /** 이 거래 직후의 잔액. 서버가 전체 이력 기준으로 굴려 준다. */
  balanceAfter: number;
}
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
/** 가맹점 판정 성향 — NORMAL은 목록에 오지 않는다(아무것도 안 한 곳). */
export type StanceLevel = 'NORMAL' | 'LENIENT' | 'EXCLUDED';
export interface MerchantStance {
  businessNumber: string;
  merchantName: string | null;
  stance: StanceLevel;
  /** '낭비 아님'을 누른 횟수. */
  keptCount: number;
  updatedAt: string;
}

/** 온보딩 창 안의 결제 1건. `waste`가 null이면 모델이 판정하지 못한 것이다(체크하지 않는다). */
export interface OnboardingPayment {
  paymentId: string;
  date: string;
  merchantName: string | null;
  businessNumber: string | null;
  amount: number;
  cardName: string | null;
  cardColor: string | null;
  waste: boolean | null;
  wasteProbability: number | null;
  reason: string | null;
  /**
   * 판정을 밀어올린 축들 — **확인할 수 있는 숫자**로 온다(2026-08-02).
   *
   * `reason`("평소보다 큰 금액 요인으로 낭비 판정")까지만 있으면 사용자는 동의도 반박도
   * 할 수 없다. `detail`이 그 숫자다 — "평소 23,000원 → 78,000원 (3.4배)".
   * 반박할 수 있어야 그 반박이 가맹점 성향의 교정 신호가 된다.
   *
   * `detail`이 빈 문자열인 축은 **사용자가 확인할 방법이 없는 것**(전반적 소비 성향 등)이라
   * 일부러 숫자를 안 붙인 것이다. 그때는 이름만 보여준다.
   */
  factors: WasteFactor[];
}
export interface WasteFactor {
  label: string;
  detail: string;
  /** 로그오즈 기여. 양수면 낭비 쪽으로 민 것. 품목 축은 0(모델이 아직 안 본다). */
  weight: number;
}
/** 카테고리 하나 — `amount`는 창 안의 **실제 합계**다(월 환산·관측월 나눗셈을 하지 않는다). */
export interface OnboardingCategory {
  categoryCode: string;
  displayName: string;
  amount: number;
  count: number;
  wasteAmount: number;
  payments: OnboardingPayment[];
}
export interface OnboardingWindow {
  userId: number;
  windowDays: number;
  from: string;
  to: string;
  categories: OnboardingCategory[];
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
  /** 아직 빠져나가는 중인가. 끝난 구독은 `nextExpected`가 null이다. */
  status: 'ACTIVE' | 'ENDED';
  category2: string;
  merchantName: string | null;
  businessNumber: string | null;
  daypart: string | null;
  /** 금액이 안정적이면 중앙값, `amountVaries`면 최근 결제액. */
  representativeAmount: number;
  amountVaries: boolean;
  /** 요금이 바뀐 경우 그 이전 금액("13,500 → 17,000"의 앞자리). 안 바뀌었으면 null. */
  priorAmount: number | null;
  periodDays: number | null;
  nextExpected: string | null;
  /** 첫 결제일 — "언제부터 구독했나". */
  firstSeen: string;
  /** 마지막 결제일 — "언제까지 구독했나". */
  lastSeen: string;
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
   지킴이 Agent (§/api/guardian) — 설계서 06_지킴이_Agent_설계.md
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
/** 카테고리 한 줄 — 홈의 '지킴 현황'을 갈라 그린다. 한도는 묶음 하나라 카테고리별 한도는 없다. */
export interface CategorySpend {
  code: string;
  label: string;
  spent: number;
  /** 챌린지 전체 사용액에서 이 카테고리가 차지하는 비율(0~1). */
  share: number;
  /** 그 카테고리의 한도. 온보딩에서 정한 강도가 그대로 반영된다. */
  cap: number;
  remaining: number;
  /** 한도 대비 소진율(0~1). 1을 넘을 수 있다 — 넘긴 것도 보여야 한다. */
  ratio: number;
}
export interface GuardianChallenge extends GuardianSnapshot {
  id: number;
  state: ChallengeState;
  categories: string[];
  /** 성역 — 줄이지 않기로 한 카테고리. 소비 내역의 '성역' 필터가 이걸로 거른다. */
  sanctuaryCategories: string[];
  baselineAmount: number;
  targetSaving: number;
  challengeCap: number;
  categorySpend?: CategorySpend[];
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
/** 주간 리포트의 한 주. defenseRate = 지킨 날 ÷ 판정한 날. */
export interface WeekPoint {
  weekStart: string;
  label: string;
  keptDays: number;
  judgedDays: number;
  defenseRate: number;
  current: boolean;
}
export interface LabelSlice { key: string; label: string; count: number; ratio: number }
export interface WeeklyReport {
  weekStart: string;
  weekEnd: string;
  weekLabel: string;
  defenseRate: number;
  /** 지난주 대비 증감(비율 차). 지난주 판정이 없으면 null. */
  deltaFromLastWeek: number | null;
  trend: WeekPoint[];
  labels: LabelSlice[];
  labeledCount: number;
  exemptedAmount: number;
  headline: string;
  /** 주간 미션 정산 (개편안 s-report). 미션이 없으면 빈 배열이라 화면이 절을 감춘다. */
  missions: MissionLine[];
  /** 성공한 미션에 일요일 정산으로 지급될 포인트 합계. */
  missionReward: number;
  /** '지킴이가 본 이번 주'. 견줄 지난주가 없으면 두 문장 모두 null. */
  coaching: { good: string | null; watch: string | null };
}

/** 주간 미션 한 줄. ONGOING이면 아직 기간 중(일요일 배치가 정산한다). */
export interface MissionLine {
  text: string;
  status: 'SUCCESS' | 'FAILED' | 'ONGOING';
  reward: number;
}

/** 도감 한 칸. owned=false면 자물쇠로 그린다(무엇이 남았는지 보여야 모을 마음이 생긴다). */
export interface CollectionCell {
  code: string;
  name: string;
  grade: 'COMMON' | 'RARE' | 'EPIC';
  /** 프론트 SVG 심볼 키 — 그림은 프론트에 있고 서버는 어느 그림인지만 가리킨다. */
  glyph: string;
  story: string;
  owned: boolean;
  acquiredDate: string | null;
  reason: string | null;
}
export interface CollectionMilestone {
  count: number;
  reward: 'EXEMPTION' | 'MISSION_CHANGE' | 'EPIC_DRAW';
  label: string;
  claimed: boolean;
}
export interface GuardianCollection {
  owned: number;
  total: number;
  percent: number;
  cells: CollectionCell[];
  milestones: CollectionMilestone[];
  next: CollectionMilestone | null;
  exemption: number;
  missionChange: number;
  grassGuard: number;
  points: number;
}
export interface ShopEntry {
  code: string;
  name: string;
  glyph: string;
  story: string;
  category: 'FURNITURE' | 'BACKGROUND';
  price: number;
  owned: boolean;
  /** 서버가 잔액과 대조해 판단한 값 — 화면은 이걸 믿는다. */
  affordable: boolean;
}
export interface GuardianShop { points: number; items: ShopEntry[] }

/** 월간 결산의 카테고리 한 줄. rate = 지켜낸 금액 / 한도. */
export interface SettlementCategory {
  category: string;
  cap: number;
  spent: number;
  kept: number;
  rate: number;
}
export interface GuardianSettlement {
  challengeId: number;
  startDate: string;
  endDate: string;
  targetSaving: number;
  securedSaving: number;
  defenseRate: number;
  categories: SettlementCategory[];
  keptDays: number;
  bestStreak: number;
  pointsEarned: number;
  objectsCollected: number;
  completionBonus: number;
}
/** 다음 달 조정안. action=KEEP(유지)·LOWER(하향) — 올리는 선택지는 없다. */
export interface RenewalLine {
  category: string;
  currentCap: number;
  suggestedCap: number;
  action: 'KEEP' | 'LOWER';
  lastRate: number;
  reason: string;
}
export interface GuardianRenewal {
  lines: RenewalLine[];
  suggestedTargetSaving: number;
  sanctuaries: string[];
}

export interface GuardianRoomObject {
  objectId: string;
  grade: Grade;
  acquiredDate: string;
  reasonCode: string | null;
  /** 놓인 자리(0~19). null이면 창고에 있다. */
  slotIndex: number | null;
  /** 표시명·그림 — 서버 카탈로그가 정한다. 프론트에 이름표를 복사해 두면 조용히 갈라진다. */
  name: string;
  glyph: string;
}
export interface GuardianRoom { objects: GuardianRoomObject[]; slotCount: number }
export interface CreateChallengeInput {
  categories: string[];
  sanctuaryCategories?: string[];
  targetSaving?: number;
  rewardName?: string;
  rewardPrice?: number;
  durationDays?: number;
  /**
   * 온보딩에서 **"이건 낭비가 아니다"**로 뺀 결제 id.
   * 서버가 기준 지출에서 그만큼 뺀다 — 화면이 보여준 '지킬 돈'과 서버 한도가 어긋나지 않게.
   */
  keptPaymentIds?: string[];
  /**
   * 카테고리 → 그 카테고리에서 지킬 돈. 강도를 카테고리마다 다르게 잡을 수 있으므로
   * 한 숫자로는 표현되지 않는다. 안 보내면 서버가 균등분할한다.
   */
  categoryTargets?: Record<string, number>;
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
  /**
   * 온보딩이 보는 **하나의 창**(기본 최근 30일). 카테고리 금액·결제 목록·ML 낭비 판정이
   * 전부 같은 구간에서 나오므로, 화면 금액과 서버 기준 지출이 어긋나지 않는다.
   */
  onboardingWindow: (userId: number, windowDays = 0) =>
    get<OnboardingWindow>(`/api/onboarding/window?userId=${userId}&windowDays=${windowDays}`),
  /* ── 가맹점 판정 성향 (마이 > 낭비 판정 관리) ── */
  merchantStances: (userId: number) =>
    get<{ userId: number; items: MerchantStance[] }>(`/api/merchant-stance?userId=${userId}`),
  /** "역시 낭비였다" — 한 단계 되돌린다. */
  revertStance: (userId: number, businessNumber: string) =>
    post<{ businessNumber: string; stance: StanceLevel; keptCount: number }>(
      `/api/merchant-stance/${encodeURIComponent(businessNumber)}/revert?userId=${userId}`, {}),
  /** 설정을 통째로 지운다 — 다음부터 전역 임계로 돌아간다. */
  clearStance: (userId: number, businessNumber: string) =>
    del<{ businessNumber: string; stance: StanceLevel }>(
      `/api/merchant-stance/${encodeURIComponent(businessNumber)}?userId=${userId}`),
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
  /** 이용약관 요약. 정본은 legal/terms-of-service.md — 방침과 같은 모양으로 내려온다. */
  privacyTerms: () => get<PrivacyPolicy>('/api/privacy/terms'),
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

  /**
   * 통장 비교 (정보성) — 자격 제한 제외 후 금리순. 판매·중개 아님.
   *
   * `userId`를 보내면 서버가 그 사용자의 출생연도로 **나이 자격까지 맞춰** 거른다.
   * 안 보내면 서버는 나이 조건을 따지지 않는다 — 즉 보내지 않으면 자격 필터가 절반만 도는 셈이라,
   * 로그인 상태에서는 항상 함께 보낸다.
   */
  compareSavings: (limit?: number, userId?: number) => {
    const q = new URLSearchParams();
    if (limit) q.set('limit', String(limit));
    if (userId) q.set('userId', String(userId));
    const s = q.toString();
    return get<SavingsCompare>(`/api/savings/compare${s ? `?${s}` : ''}`);
  },

  /* ── 충동예산 절약통 ── */
  impulse: (userId: number) => get<ImpulseSnapshot>(`/api/impulse?userId=${userId}`),
  setImpulseCategories: (userId: number, categories: string[]) =>
    post<ImpulseSnapshot>('/api/impulse/categories', { userId, categories }),
  impulseSpend: (userId: number, categoryCode: string, amount: number) =>
    post<ImpulseSnapshot>('/api/impulse/spend', { userId, categoryCode, amount }),
  impulseUpload: (userId: number, csv: string) =>
    post<ImpulseSnapshot>('/api/impulse/upload', { userId, csv }),

  /* ── 마이데이터 (§13) ── */
  /** `carrier`는 온보딩에서 고른 통신사. 서버가 번호 대역과 대조한다(알뜰폰은 대조 생략). */
  verify: (userId: number, name: string, social7: string, phone: string, carrier?: string) =>
    post<VerifyResult>('/api/mydata/verify', { userId, name, social7, phone, carrier }),
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
  /** @param months 최근 N개월(당월 포함). 1=이번 달, 7=이번 달+이전 6개월. */
  account: (userId: number, months = 1) =>
    get<MyAccount | null>(`/api/mydata/account?userId=${userId}&months=${months}`),
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

    /* ── 도감·포인트샵 (개편안 s-collection·s-shop) ── */
    /**
     * 배치 변경(꾸미기 모드) — slot=null이면 창고로 내린다.
     * 그 자리에 있던 소품은 사라지지 않고 창고로 간다(도감 기록은 지워지지 않는다).
     */
    placeObject: (userId: number, objectId: string, slot: number | null) =>
      post<GuardianRoom>(`/api/guardian/room/place?userId=${userId}`, { objectId, slot }),

    /** 도감 — 모은 칸과 못 모은 칸, 마일스톤 진행까지 서버가 계산해 준다. */
    collection: (userId: number) =>
      get<GuardianCollection>(`/api/guardian/collection?userId=${userId}`),
    /** 마일스톤 보상 청구(10종 면제권·15종 미션변경권·20종 에픽뽑기). */
    claimMilestone: (userId: number, count: number) =>
      post<GuardianCollection>(`/api/guardian/collection/milestones/${count}/claim?userId=${userId}`, {}),
    shop: (userId: number) => get<GuardianShop>(`/api/guardian/shop?userId=${userId}`),
    /** 구매 — 살 수 있는지는 서버가 판단한다(프론트의 P 비교는 표시용일 뿐). */
    buyItem: (userId: number, code: string) =>
      post<GuardianShop>(`/api/guardian/shop/${encodeURIComponent(code)}/buy?userId=${userId}`, {}),

    /* ── 월말 사이클 (개편안 s-settle·s-renew) ── */
    /** 주간 리포트 — weeksAgo=0 이번 주, 1 지난주. */
    weeklyReport: (userId: number, weeksAgo = 0) =>
      get<WeeklyReport>(`/api/guardian/report/weekly?userId=${userId}&weeksAgo=${weeksAgo}`),
    settlement: (userId: number) =>
      get<GuardianSettlement>(`/api/guardian/settlement?userId=${userId}`),
    renewal: (userId: number) => get<GuardianRenewal>(`/api/guardian/renewal?userId=${userId}`),
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
 *
 * 남은 항목은 **옛 영문 코드**뿐이다. 카테고리 체계가 업종코드 기반 중분류(한글)로 바뀌어
 * 새 데이터에는 영문 코드가 나오지 않는다. 이전에 적재된 소비를 위해 남겨 둘 뿐이니
 * 새 카테고리를 여기에 추가하지 않는다 — 중분류는 이름이 곧 표시명이라 폴백이 필요 없다.
 */
export const CATEGORY_LABEL: Record<string, string> = {
  FOOD: '식비', CAFE: '카페·간식', SHOPPING: '쇼핑', TRANSPORT: '교통',
  HOUSING: '주거', MEDICAL: '의료', CULTURE: '문화·여가', EDUCATION: '교육',
  COMMUNICATION: '통신', BEAUTY: '미용', TRAVEL: '여행', ETC: '기타',
};
export const catLabel = (code: string, displayName?: string) =>
  (displayName && displayName !== code ? displayName : CATEGORY_LABEL[code]) ?? code;
