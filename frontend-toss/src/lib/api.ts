/**
 * 백엔드 연결층 — 이 파일 하나가 서버와의 계약 전부다.
 *
 * 기존 화면이 쓰던 엔드포인트를 하나도 잃지 않고 그대로 옮겼고(§소비분석·마이데이터·게임화 저축·
 * 충동절약통·설문·동의), 여기에 **지킴이 Agent(`/api/guardian/*`)** 를 새로 이었다.
 * 지킴이는 백엔드에 구현돼 있었지만 어느 화면에서도 부르지 않던 미연결 영역이었다.
 *
 * 원칙: <b>프론트는 계산하지 않는다.</b> 남은 예산·달성률·며칠 남았는지는 서버가 완성해 내려준다
 * (GuardianController 주석). 여기 타입은 그 응답을 그대로 받아 적은 것이다.
 */
import { serve } from './localServer';

export type DataSourceMode = 'ESTIMATED' | 'CONFIRMED';

/** 카드 추천(개편안 `s-compare`) — 카드보다 근거가 먼저 온다. */
export interface CardSummaryRow {
  rank: number;
  categoryCode: string;
  displayName: string;
  count: number;
  amount: number;
}
export interface CardBenefitRow { label: string; value: string }
export interface CardOffer {
  name: string;
  tagline: string;
  /** 카드 그림 색 갈래 — blue/gold/navy. 모르는 값이면 화면이 blue로 떨어뜨린다. */
  tint: string;
  mark: string;
  footer: string;
  /**
   * 내 소비와 겹친 대상 수. **순위의 근거이자 우리만 할 수 있는 말이다** — 카드 비교
   * 서비스는 "이 카드는 커피 5%"까지만 말하고, 마이데이터가 있어야 "회원님이 자주 가는
   * 스타벅스가 그 대상"을 말할 수 있다.
   */
  matchCount: number;
  /** 겹친 이름 — 브랜드 먼저, 축 나중. 화면이 그대로 보여줘 사용자가 반박할 수 있게 한다. */
  matched: string[];
  rows: CardBenefitRow[];
  /**
   * 공시 기준일(심의필 날짜, `2025-11-07`). **화면에 반드시 병기한다** — 혜택 개정 추적이
   * 스코프 밖이라 카드 정보는 수집 시점 스냅샷이고, 이 값이 낡음에 대한 유일한 방어다.
   * 신청 버튼을 두지 않는 것이 이 방어의 전제다.
   */
  asOf: string | null;
}
export interface CardRecommend {
  summary: CardSummaryRow[];
  offers: CardOffer[];
  /** "2026.05 ~ 2026.07" — 무엇을 근거로 셌는지. */
  periodLabel: string;
  months: number;
  /**
   * 겹침을 어느 구간에서 셌는지. "2026.04 ~ 2026.06".
   * 실적은 판정하지 않으므로 예전 이름(performanceMonth)은 버렸다.
   */
  spendWindow: string;
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
/** 한 달에 이 목표로 들어온 돈. month 는 `yyyy-MM`. */
export interface MonthlySaving {
  month: string;
  amount: number;
}

export interface GoalView {
  id: number;
  name: string;
  emoji: string;
  targetAmount: number;
  balance: number;
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
  /** 이루면 마이룸에 도착할 소품 코드. 안 골랐으면 null */
  rewardCode: string | null;
  /** 기한 안에 맞추려면 매달 넣어야 하는 돈 = 목표액 ÷ 기한개월 */
  monthlyRequired: number;
  /** 이 사람이 실제로 매달 지켜 온 돈 — 위와 견주라고 함께 온다 */
  monthlyAverageSaved: number;
  /** 지금 속도로 갔을 때의 달성일(yyyy-MM-dd). 속도가 0이면 null — 모르면 모른다고 온다 */
  projectedDate: string | null;
  /** 매달 쌓인 기록 — 오래된 달이 앞이다 */
  monthlyHistory: MonthlySaving[];
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
  /**
   * 이 사람이 실제로 매달 지켜 온 돈.
   * **첫 목표를 만들 때는 목표가 없어 `goals[0]` 에서 꺼낼 수 없으므로 여기서 읽는다.**
   */
  monthlyAverageSaved: number;
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

/* ── 지킨 돈 굴리기 (결산 화면 · 문서 §4.7) ───────────────────────────────
 * 개인화가 아니다 — 금액만 자동으로 채워질 뿐 같은 금액이면 누구나 같은 답을 받는다.
 * 파킹통장만 쓴다: 지킨 돈은 매달 금액이 다르고 결산마다 덩어리로 들어와, 묶이는 상품과 안 맞는다.
 */
export interface ParkingOption {
  company: string;
  name: string;
  /** 조건 없이 받는 금리(%). 최고금리는 조건부라 내려오지 않는다. */
  baseRate: number;
  /** `이 페이스로 계속` 넣었을 때의 세후 이자(원) */
  paceInterest: number;
  /** 위 원금 + 이자 */
  paceTotal: number;
  /** 지금까지 모은 돈을 그대로 뒀을 때의 세후 이자(원) */
  keptInterest: number;
  keptTotal: number;
}
export interface KeptMoneyPlan {
  /** 이번 챌린지에서 지킨 돈 */
  thisChallenge: number;
  /** 확정된 챌린지 전부의 합 */
  cumulative: number;
  projectionMonths: number;
  /** `이 페이스로 N개월` 이어졌을 때의 원금 — **가정**이라 화면이 그 사실을 밝혀야 한다 */
  pacePrincipal: number;
  options: ParkingOption[];
  /** 금리 조회 기준일 */
  asOf: string;
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
  /**
   * **이 앱의 로그인 열쇠.** 인증을 통과했을 때만 온다.
   *
   * 비밀번호를 따로 두지 않는 이유가 이것이다 — 신원 셋으로 이미 사람을 확인했으므로
   * 그 자리에서 토큰을 받으면 그게 로그인이다. 이후 모든 요청이 이 값을 헤더에 싣는다.
   */
  authToken: string | null;
}
export interface MyDataCompany { id: number; name: string; imgUrl: string }
export interface MyDataLinkResult { cardCount: number; paymentCount: number; bankCount: number }
/** 연동 가능 은행. id는 제공자가 이름순으로 매긴 순번이라 조회마다 같다. */
export interface MyDataBank { id: number; name: string }
/** 인증 뒤 찾은 기관. 둘을 합친 수가 화면의 "N곳"이다. */
export interface MyDataDiscovered { cards: MyDataCompany[]; banks: MyDataBank[] }
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
  businessNumber: string | null;
}
/** 결제내역 모아보기 1건(§13-11) — 결제 정보 + 어느 카드인지. */
export interface MyPaymentHistory {
  paymentId: string;
  date: string;
  /** 소비 중분류. 제공자는 업종코드까지만 주고 이 값은 앱이 붙인다. */
  category: string;
  category2: string | null;
  /** 확정이 없을 때의 **AI 추정**. 화면이 배지로 보여주고 사용자가 눌러 확정한다. */
  category2Llm?: string | null;
  /**
   * **이 카테고리를 누가 정했나** — `DICT`·`REGISTRY`·`LLM_LOCAL`·`LLM`·`NONE`.
   *
   * `LLM_LOCAL` 은 **모델의 추정이 그 사람의 원장에 반영된 것**이다(`CategoryPromotionService`).
   * 값은 `category2` 에 확정처럼 앉아 있지만 **근거는 모델**이다 — 이 칸을 안 보면 화면에서
   * 사람의 확정과 똑같아 보인다. 운영에 983건(46%)이 그 상태였다(2026-08-26 실측).
   */
  category2Source?: string | null;
  amount: number;
  merchantName: string | null;
  cardName: string | null;
  cardColor: string | null;
  companyName: string | null;
  businessNumber: string | null;
  /**
   * 표기표가 확정한 **브랜드**. 없으면 null.
   *
   * 화면은 이것이 있으면 앞세우고 가맹점 풀네임은 아래 줄로 내린다 —
   * `주식회사 빅바이트컴퍼니 쉐이크쉑 강남스퀘어` 보다 **쉐이크쉑** 이 먼저 읽힌다.
   * 결제대행사(토스페이·카카오페이)는 서버가 뺀다. 결제수단은 가게가 아니다.
   */
  brand?: string | null;
  /**
   * **소비내역에 적을 이름**(V44). 서버가 결제 행에 적어 둔 값이라 화면이 계산하지 않는다.
   *
   * 언제나 **원문의 부분집합**이다 — 지어내지 않는다. 실사용자 결제의 29%가 PG 번호로
   * 찍혀 `토스페이_일반-(주)비바리퍼블리카` 처럼 결제 경로가 상호를 밀어낸다.
   */
  displayName?: string | null;
  /** `BRAND`·`RESIDUE`·`AGENCY_ONLY`·`RAW` — 화면이 배지와 펼침을 가르는 근거다. */
  displayNameSource?: string | null;
  /** 거쳐 간 결제대행사. **사업자번호가 알려 준 사실**이라 상호에서 짐작한 것이 아니다. */
  viaAgency?: string | null;
  /**
   * 모델이 낭비로 봤는가 — 미니앱이 더한 칸이다. 서버는 안 주므로 본 서비스에서는 `undefined`.
   */
  waste?: boolean;
  /**
   * <b>사람이 답한 것</b> — `WASTE`(아까움) · `FINE`(필요) · 아직 안 답했으면 `null`.
   *
   * 모델의 판정과 <b>따로</b> 싣는다. 하나로 합치면 목록에서 "내가 답한 것"과 "모델이 본 것"이
   * 구별되지 않고, 답을 고칠 자리가 사라진다.
   */
  verdict?: 'WASTE' | 'FINE' | null;
}
/** 가맹점 조회(번호→주소). */
export interface MyMerchant {
  /** 제공자가 준 업종(국세청 업종코드 6자리). 표시용이 아니라 근거용이다. */
  industryCode: string | null;
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
  /** 확정 분류가 없어 **AI 추정 자리**에 놓인 결제. 화면은 배지로 구분해 보여준다. */
  categoryEstimated?: boolean;
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
  /** 재량성이 낮아 **줄이라고 권하지 않는** 카테고리(교통·통신·의료). 서버가 판정한다. */
  protectedCategory: boolean;
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
/** 아직 답하지 않은 소비 한 장 — 되묻기 덱의 카드. 미니앱 전용이다. */
export interface PendingVerdict {
  paymentId: string;
  merchant: string;
  amount: number;
  date: string;
  time: string | null;
  category2: string;
  waste: boolean;
  reason: string | null;
}

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
  /** 어디에서 썼나 — 미니앱의 가계부가 보낸다. 카테고리를 이 이름으로 찾는다. */
  merchantName?: string;
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

/** 또래 비교 — 같은 나이대의 <b>중앙값</b>과 내 지출. 평균이 아닌 이유는 서버 쪽에 적었다. */
export interface PeerCompare {
  mine: number;
  peer: number;
  ageFrom: number;
  ageTo: number;
  sampleSize: number;
  days: number;
}

/** 챌린지와 무관한 기간 집계 — `/api/report/period`. */
export interface PeriodSpend {
  period: 'week' | 'month';
  start: string;
  end: string;
  total: number;
  count: number;
  /** 결제가 없는 날도 0으로 들어 있다 — 칸 수가 항상 같아야 막대가 안 밀린다. */
  days: { date: string; amount: number }[];
  /** 금액 내림차순, 동점이면 코드순으로 서버가 고정해 준다. **간편결제는 빠져 있다.** */
  byCategory: { code: string; name: string; amount: number }[];
  /**
   * 카테고리 합에서 빠진 금액 — 간편결제(결제대행사 자신).
   *
   * **막대를 그리는 화면은 이 값을 함께 적어야 한다.** 빼 놓고 안 알리면 막대 합과
   * `total` 이 달라 보이고, 사용자는 숫자를 못 믿게 된다.
   */
  uncategorised?: number;
  /**
   * 그 기간에 <b>낭비로 본</b> 소비의 합 — 평소보다 많이 쓴 것으로 판정된 금액.
   *
   * 미니앱이 더한 칸이다. 서버는 이 값을 안 주므로 본 서비스 화면에서는 늘 `undefined` 고,
   * 그때는 그 자리가 0 으로 그려진다 — 없는 값을 지어내지 않는다.
   */
  flagged?: number;
}

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
/** 카테고리 한 줄 — 홈의 '지킴 현황'을 갈라 그린다. 예산은 묶음 하나라 카테고리별 예산은 없다. */
export interface CategorySpend {
  code: string;
  label: string;
  spent: number;
  /** 챌린지 전체 사용액에서 이 카테고리가 차지하는 비율(0~1). */
  share: number;
  /** 그 카테고리의 예산. 온보딩에서 정한 강도가 그대로 반영된다. */
  cap: number;
  remaining: number;
  /** 예산 대비 소진율(0~1). 1을 넘을 수 있다 — 넘긴 것도 보여야 한다. */
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
  /** 사람이 읽는 이름. 서버가 카탈로그에서 찾아 보낸다 — 예전엔 코드가 그대로 화면에 나갔다. */
  objectName: string | null;
  glyph: string | null;
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
/**
 * 홈 한마디 — 지금 걸린 케이스 하나와 그 문장.
 *
 * <b>비지 않는다.</b> 걸린 것이 없으면 서버가 `IDLE`과 함께 문장을 준다. 그래서 화면은
 * null 검사 없이 그대로 그린다 — 여기가 비면 사용자는 앱을 열고도 상태를 알 수 없다.
 */
export interface GuardianOneline {
  caseId: string;
  text: string;
}
export interface GuardianHome {
  asOf: string;
  challenge: GuardianChallenge;
  strip: GuardianStrip;
  oneline: GuardianOneline;
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
/**
 * 그 주 하루치 — 요일별 막대의 재료. **늘 7칸**이다.
 * `judged=false` 는 판정이 없는 날(미래·시작 전)이라 막대를 비워 그린다 — 빼면 월~일이 어긋난다.
 */
export interface DayPoint {
  date: string;
  label: string;
  amount: number;
  kept: boolean;
  judged: boolean;
}
/** 끝난 챌린지 한 줄. 달성률은 서버가 계산해 내려준다(프론트는 계산하지 않는다). */
export interface PastChallenge {
  challengeId: number;
  label: string;
  period: string;
  keptDays: number;
  totalDays: number;
  rate: number;
}
export interface WeeklyReport {
  weekStart: string;
  weekEnd: string;
  weekLabel: string;
  defenseRate: number;
  /** 지난주 대비 증감(비율 차). 지난주 판정이 없으면 null. */
  deltaFromLastWeek: number | null;
  trend: WeekPoint[];
  /** 그 주 7일치 — '요일별' 토글이 쓴다. */
  days: DayPoint[];
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
  /** 끝난 챌린지 달성률. 진행 중인 회차는 빠진다 — 확정되지 않은 성적을 최종처럼 보이면 안 된다. */
  pastChallenges: PastChallenge[];
}

/* ── 주간 미션 보드 (개편안 s-myroom) ──────────────────────────────────── */
/** 보드에 걸린 미션 한 줄. */
export interface MissionBoardLine {
  id: number;
  text: string;
  status: 'SUCCESS' | 'FAILED' | 'ONGOING';
  /** 이 미션 몫의 포인트. 만들 때 박아 둔 값이라 나중에 줄지 않는다. */
  reward: number;
  type: 'MAX_COUNT' | 'AVOID_SLOT' | 'NO_SPEND_STREAK_MIN' | 'LABELING_COUNT_MIN';
  category: string | null;
  /** 이 미션을 만든 후보의 키 — 시트를 다시 열 때 표시를 되살린다. */
  candidateKey: string;
}
/** 고를 수 있는 미션 하나. `key`로 고른다 — 후보는 매번 다시 계산돼 id가 없다. */
export interface MissionCandidate {
  key: string;
  type: 'MAX_COUNT' | 'AVOID_SLOT' | 'NO_SPEND_STREAK_MIN' | 'LABELING_COUNT_MIN';
  category: string | null;
  threshold: number;
  weekday: string | null;
  hourStart: number | null;
  hourEnd: number | null;
  text: string;
  /** 왜 이걸 권하는지 — 근거 없는 추천은 숙제가 된다. */
  why: string;
}
export interface MissionBoard {
  active: MissionBoardLine[];
  next: MissionBoardLine[];
  candidates: MissionCandidate[];
  nextWeekStart: string;
  weeklyPointPool: number;
}

/** 챌린지 카테고리 한 줄 (마이 > 챌린지 관리). */
export interface ChallengeCategory {
  category: string;
  label: string;
  /** 기준 지출(실측) — 사용자가 정할 값이 아니다. */
  baseline: number;
  /** 지키기로 한 돈. 이 값만 사용자가 옮긴다. */
  target: number;
  /** 예산 = 기준 − 지킬 돈. */
  cap: number;
  /** 지금까지 쓴 돈 — 목표를 얼마나 올릴 수 있는지의 천장이다. */
  spent: number;
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
export interface GuardianShop { points: number; items: ShopEntry[]; catSkin: string }

/**
 * 고를 수 있는 지킴이 털색 (프로토타입_0806 꾸미기 > 캐릭터).
 *
 * `owned=false` 는 아직 안 산 색이라 자물쇠로 그린다 — 무엇이 남았는지 보여야 모을 마음이 생긴다.
 * 크림·그레이·치즈·초코는 늘 `true`, 삼색이만 상점에서 산다.
 */
export interface CatSkin {
  key: string;
  name: string;
  /** `public/room/` 의 파일 이름(확장자 없이). */
  glyph: string;
  owned: boolean;
  selected: boolean;
}

/** 월간 결산의 카테고리 한 줄. rate = 지켜낸 금액 / 예산. */
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
   * 서버가 기준 지출에서 그만큼 뺀다 — 화면이 보여준 '지킬 돈'과 서버 예산이 어긋나지 않게.
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

/**
 * 인증 토큰 — <b>이 앱에서는 쓰지 않는다.</b>
 *
 * 소비 내역이 이 기기에만 있으므로 누구인지 알아야 할 이유가 생기지 않는다. 그래도 넣고
 * 빼는 함수는 남긴다 — 지우면 이것을 부르는 화면들을 함께 고쳐야 하고, 그 화면들은
 * 본 서비스와 같은 파일이라 갈라지면 안 된다.
 */
const TOKEN_KEY = 'auth_token';
export const saveAuthToken = (token: string) => {
  try { localStorage.setItem(TOKEN_KEY, token); } catch { /* 사파리 프라이빗 등 */ }
};
export const clearAuthToken = () => {
  try { localStorage.removeItem(TOKEN_KEY); } catch { /* noop */ }
};

/**
 * 응답을 기다리는 상한.
 *
 * <b>왜 있어야 하나.</b> 예전에는 `fetch` 에 `signal` 이 없었다. 서버가 응답을 <b>영영 안 주면</b>
 * 프라미스가 영원히 안 풀려 <b>오류 분기에 도달조차 못 한다</b> — 화면은 "다시 시도 / 그냥 진행"을
 * 잘 만들어 뒀는데 거기 갈 수가 없고, 진행 바가 100%인 채로 멈춰 있는다. 실제 상한은
 * nginx 기본값 60초였고, 그 선에서 잘린 사고가 이미 있었다(`FollowUpExecutorConfig`, 실측 57초).
 * 다른 앱에서 "로딩이 1분"으로 보이는 것이 대개 이 모양이다.
 *
 * 백엔드는 바깥을 부를 때마다 타임아웃을 꼼꼼히 걸어 뒀다(`util/HttpClients`). 프론트에만
 * 그 방어가 없었다.
 *
 * 15초는 실측(`/api/privacy/policy` 8ms · DB 질의 0.1~1.9ms)의 1,800배다. 정상 요청을 끊을
 * 여지는 없고, 끊겨도 사용자에게는 "다시 시도"가 남는다.
 */
const TIMEOUT_MS = 15_000;
/**
 * 바깥(LLM·마이데이터 제공자)을 기다리는 무거운 진입로. 60초는 nginx 기본 상한과 같은 값이라
 * <b>어느 쪽이 먼저 끊든 사용자에게는 같은 문장이 간다</b> — 프론트가 먼저 끊으면 우리 문장으로,
 * 늦게 끊으면 nginx 의 504 로. 둘을 어긋나게 두면 같은 사고가 두 얼굴로 보인다.
 */
const SLOW_TIMEOUT_MS = 60_000;

/**
 * <b>서버로 나가지 않는다.</b>
 *
 * 본 서비스에서 이 함수는 `fetch` 로 백엔드를 불렀다. 미니앱에는 서버가 없으므로 같은 경로를
 * 기기 안에서 처리한다({@link serve}). <b>바꾼 것은 이 함수 하나뿐이다</b> — 위의 형과 아래의
 * `api` 객체와 화면들은 그대로다. 부르는 쪽은 자기가 HTTP 를 쓰는지 함수를 쓰는지 모른다.
 *
 * `timeoutMs` 는 받아만 두고 안 쓴다. 네트워크가 없으니 늦을 일이 없고, 인자를 지우면
 * 부르는 자리를 스무 곳 고쳐야 한다.
 */
async function request<T>(method: string, path: string, body?: unknown,
                          _timeoutMs: number = TIMEOUT_MS): Promise<T> {
  void _timeoutMs;
  return serve<T>(method, path, body);
}

const get = <T,>(path: string, timeoutMs?: number) => request<T>('GET', path, undefined, timeoutMs);
const post = <T,>(path: string, body?: unknown, timeoutMs?: number) =>
  request<T>('POST', path, body, timeoutMs);
const put = <T,>(path: string, body?: unknown) => request<T>('PUT', path, body);
const del = <T,>(path: string) => request<T>('DELETE', path);

/** 아직 분류되지 않은 결제 한 건. `suggested` 는 **AI 추정**이라 판정에 쓰이지 않는다. */
export interface UnclassifiedItem {
  paymentId: string;
  date: string;
  amount: number;
  merchantName: string | null;
  businessNumber: string | null;
  /** AI 추정 중분류. 명백하지 않으면 null — 억지로 붙이지 않는다. */
  suggested: string | null;
  /** NONE · LLM · USER · DICT */
  source: string;
  /**
   * 상호 자체가 결제대행사인가 — <b>그러면 무엇을 샀는지 원리적으로 알 수 없다.</b>
   * '내가 알려주면 되는 것'과 '앱이 못 하는 것'을 화면에서 갈라 보여주기 위한 값이다.
   */
  paymentAgency?: boolean;
  /**
   * 확정이 **사전에 쌓일 수 있는가**. 더미 사용자의 사업자번호는 생성기가 만든 것이라
   * 실재하지 않아 사전에 넣지 않는다(결제 자체의 분류는 그래도 바뀐다).
   */
  canConfirm: boolean;
}

export interface UnclassifiedResponse {
  categories: string[];
  aiEnabled: boolean;
  items: UnclassifiedItem[];
}

export interface ConfirmCategoryResult {
  paymentId: string;
  category2: string;
  /** 함께 바로잡힌 소비 건수. 리포트가 읽는 것은 이쪽이라 0이면 화면이 안 바뀐다. */
  reclassifiedConsumptions: number;
  /** 확정 분류 사전에 쌓였는가(실제 사람의 결제일 때만 쌓인다). */
  storedInDictionary: boolean;
}

export const api = {
  recommendCards: (userId: number) =>
    get<CardRecommend>(`/api/products/recommend-cards?userId=${userId}`),
  alerts: (userId: number) => get<AlertResponse>(`/api/alert/list?userId=${userId}`),
  rescan: (userId: number) => post<unknown>(`/api/alert/rescan?userId=${userId}`),
  report: (userId: number) => get<ReportResponse>(`/api/report/monthly?userId=${userId}`),
  /**
   * 또래 비교 — 견줄 수 없으면 서버가 <b>204</b> 를 준다(출생연도 미상·표본 부족).
   * 그때는 `null` 이 되어 화면이 그 절을 통째로 감춘다 — 없는 비교를 지어내지 않는다.
   */
  peerCompare: (userId: number, days = 30) =>
    get<PeerCompare | null>(`/api/report/peer?userId=${userId}&days=${days}`),
  /**
   * <b>그 기간에 얼마를 썼는가</b> — 챌린지와 무관하다.
   *
   * <p>일별 계열을 지킴이 주간 리포트에서만 받던 탓에, 진행 중인 챌린지가 없으면 404 라
   * 리포트가 통째로 비었다. 이 진입로는 소비만 보므로 챌린지를 한 번도 만들지 않은
   * 사람에게도 같은 답을 준다.
   */
  periodSpend: (userId: number, period: 'week' | 'month', offset = 0) =>
    get<PeriodSpend>(`/api/report/period?userId=${userId}&period=${period}&offset=${offset}`),
  /**
   * 온보딩이 보는 **하나의 창**(기본 최근 30일). 카테고리 금액·결제 목록·ML 낭비 판정이
   * 전부 같은 구간에서 나오므로, 화면 금액과 서버 기준 지출이 어긋나지 않는다.
   */
  onboardingWindow: (userId: number, windowDays = 0) =>
    get<OnboardingWindow>(`/api/onboarding/window?userId=${userId}&windowDays=${windowDays}`),
  /* ── 미분류 정리 (실데이터에는 업종코드가 없다) ── */
  /**
   * 아직 분류되지 않은 결제와 **AI 추정**을 함께 받는다.
   * 추정은 표시 전용이라 판정에 쓰이지 않는다 — 사람이 확정해야 반영된다.
   */
  // 미분류를 채우는 동안 무료 모델을 부를 수 있어 느리다 — 넉넉한 상한을 준다.
  unclassified: (userId: number) =>
    get<UnclassifiedResponse>(`/api/merchant-category/unclassified?userId=${userId}`, SLOW_TIMEOUT_MS),
  /**
   * 가게 이름 하나를 무엇으로 볼지 <b>미리</b> 답한다 — 저장하지 않는다.
   *
   * 넣기 화면이 타이핑하는 동안 부른다. 넣고 나서 알려주면 이미 늦다 —
   * 틀린 것을 알아채고 고칠 수 있는 자리는 넣기 전이다.
   */
  previewCategory: (merchantName: string) =>
    get<{ category2: string | null; category3: string | null; brand: string | null }>(
      `/api/merchant-category/preview?name=${encodeURIComponent(merchantName)}`),

  /** 사람이 분류를 확정한다. 실제 사람의 결제면 확정 분류 사전에도 쌓인다. */
  confirmCategory: (userId: number, paymentId: string, category2: string) =>
    post<ConfirmCategoryResult>(
      `/api/merchant-category/${encodeURIComponent(paymentId)}/confirm?userId=${userId}`,
      { category2 }),

  /* ── 가맹점 판정 성향 (마이 > 낭비 판정 관리) ── */
  merchantStances: (userId: number) =>
    get<{ userId: number; items: MerchantStance[] }>(`/api/merchant-stance?userId=${userId}`),
  /** "역시 낭비였다" — 한 단계 되돌린다. */
  /** "이건 줄일 지출이 아니에요" — 사다리를 건너뛰고 바로 제외로. */
  excludeStance: (userId: number, businessNumber: string, merchantName?: string) =>
    post<{ businessNumber: string; stance: StanceLevel; keptCount: number }>(
      `/api/merchant-stance/${encodeURIComponent(businessNumber)}/exclude?userId=${userId}`
      + (merchantName ? `&merchantName=${encodeURIComponent(merchantName)}` : ''), {}),
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
  /** 이용약관. 정본은 legal/terms-of-service.md — 방침과 같은 모양으로 내려온다. */
  privacyTerms: () => get<PrivacyPolicy>('/api/privacy/terms'),
  /**
   * 동의 항목 하나가 펼치는 문서. 정본은 legal/consent-{id}.md.
   *
   * 가입 화면의 동의 넷 중 셋이 이걸 읽는다. 예전에는 셋 다 개인정보 처리방침을 폈는데,
   * 그 방침에는 고유식별정보도 마케팅 수신도 안 나온다 — 상세보기를 눌러도 자기 얘기가
   * 없는 문서가 떴다.
   */
  privacyConsent: (id: string) => get<PrivacyPolicy>(`/api/privacy/consent/${id}`),
  categories: () => get<CategoryView[]>('/api/categories'),
  addConsumption: (input: ConsumptionInput) =>
    post<{
      id: number;
      /**
       * 미니앱이 함께 답하는 것들 — 방금 넣은 건이 <b>눈에 띄었는가</b>.
       * 서버는 안 주므로 본 서비스 화면에서는 `undefined` 다.
       */
      paymentId?: string;
      flagged?: boolean;
      reason?: string | null;
      category2?: string | null;
      /** 그 카테고리에 보통 얼마 쓰는가. 두 건 미만이면 null — 없는 '보통'을 말하지 않는다. */
      typical?: number | null;
      samples?: number;
    }>('/api/consumption', input),


  /**
   * 소비 한 건을 지운다 — 되돌릴 수 없다.
   *
   * 미니앱 전용이다. 본 서비스는 마이데이터가 실어 온 결제를 지우지 않는다(사실이므로).
   * 여기서는 <b>사람이 적은 것</b>이라 잘못 적었으면 지울 수 있어야 한다.
   */
  deleteConsumption: (paymentId: string) =>
    del<{ paymentId: string }>(`/api/consumption/${encodeURIComponent(paymentId)}`),

  /** 아직 답하지 않은 소비 — 최신이 위다. */
  pendingVerdicts: () => get<PendingVerdict[]>('/api/ml/pending'),

  /**
   * <b>이 소비가 아까웠나요</b> — 사람이 답한 판정.
   *
   * 규칙은 물어볼 것을 고르는 데까지고, 무엇이 아까웠는지는 그 사람만 안다.
   * 답한 것은 규칙을 이긴다.
   */
  setWasteVerdict: (paymentId: string, waste: boolean) =>
    post<{ paymentId: string; waste: boolean }>('/api/ml/verdict', { paymentId, waste }),

  /** 계측 — 실패해도 화면이 죽으면 안 되므로 조용히 삼킨다. */
  /** 보낼 데가 없다. 행태를 모으지 않으므로 부르는 자리를 지우지 않고 여기서 끝낸다. */
  track: (_event: string, _userId?: number, _properties?: Record<string, unknown>) =>
    Promise.resolve(undefined),

  survey: (body: Record<string, unknown>) => post<{ responseCount: number }>('/api/analytics/survey', body),

  /* ── 게임화 저축 루프 ── */
  points: (userId: number) => get<PointSnapshot>(`/api/points?userId=${userId}`),
  avoid: (userId: number, categoryCode: string, amount: number) =>
    post<PointSnapshot>('/api/points/avoided', { userId, categoryCode, amount }),
  spend: (userId: number, categoryCode: string, amount: number, necessary: boolean) =>
    post<PointSnapshot>('/api/points/spend', { userId, categoryCode, amount, necessary }),

  /** `months`·`rewardCode` 는 선택이다 — 안 보내면 서버가 기존 기본값을 쓴다. */
  createGoal: (userId: number, name: string, emoji: string, targetAmount: number,
    opts?: { months?: number; rewardCode?: string }) =>
    post<PointSnapshot>('/api/points/goals', { userId, name, emoji, targetAmount, ...opts }),
  updateGoal: (userId: number, goalId: number,
    patch: { name?: string; emoji?: string; targetAmount?: number; priority?: boolean;
      months?: number; rewardCode?: string }) =>
    put<PointSnapshot>(`/api/points/goals/${goalId}`, { userId, ...patch }),
  deleteGoal: (userId: number, goalId: number) =>
    del<PointSnapshot>(`/api/points/goals/${goalId}?userId=${userId}`),

  addMilestone: (userId: number, goalId: number, m: { name: string; emoji: string; cost: number }) =>
    post<PointSnapshot>(`/api/points/goals/${goalId}/milestones`, { userId, ...m }),
  deleteMilestone: (userId: number, milestoneId: number) =>
    del<PointSnapshot>(`/api/points/milestones/${milestoneId}?userId=${userId}`),

  setGoalPlan: (userId: number, goalId: number, cutCategories: string[]) =>
    post<PointSnapshot>(`/api/points/goals/${goalId}/plan`, { userId, cutCategories }),

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

  /** 일반 예적금 비교 — 사용자 데이터 없이 공시 기본금리순으로 조회한다. */
  compareSavings: (limit?: number) => {
    const q = new URLSearchParams();
    if (limit) q.set('limit', String(limit));
    const s = q.toString();
    return get<SavingsCompare>(`/api/savings/compare${s ? `?${s}` : ''}`);
  },

  /**
   * 지킨 돈 굴리기 — 결산 화면 하단 블록.
   * 보여줄 게 없으면 서버가 **204**를 준다(지킨 돈 0 · 파킹 조회 막힘) → `null`.
   */
  keptMoneyParking: (userId: number) =>
    get<KeptMoneyPlan | null>(`/api/savings/kept-money?userId=${userId}`),

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
  /**
   * 인증을 마친 사람이 **실제로 가진** 기관을 찾는다. 연결은 하지 않는다 —
   * 화면이 "N곳을 찾았어요"로 보여 주고, 뺄 곳을 해제한 뒤 `mydataLink` 를 부른다.
   */
  mydataDiscover: (userId: number) => get<MyDataDiscovered>(`/api/mydata/discover?userId=${userId}`),
  myBanks: (userId: number) => get<MyLinkedBank[]>(`/api/mydata/my-banks?userId=${userId}`),
  /** 카드사와 은행을 함께 연동한다. 은행은 계좌가 있는 곳만 실제로 붙는다. */
  mydataLink: (userId: number, companyIds: number[], bankIds: number[] = []) =>
    // 제공자에서 전 이력을 끌어오는 자리 — 실측 57초로 nginx 기본 60초에 닿은 적이 있다
    // (`FollowUpExecutorConfig` 가 그 사고의 기록이다).
    post<MyDataLinkResult>('/api/mydata/link', { userId, companyIds, bankIds }, SLOW_TIMEOUT_MS),
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
    post<{ newPayments: number }>(`/api/mydata/sync?userId=${userId}`, SLOW_TIMEOUT_MS),

  /** 결제별 ML 낭비/필수 판정 + '왜' (§W8). */
  mlWaste: (userId: number) => get<WasteJudgment[]>(`/api/ml/waste/${userId}`),

  /**
   * [dev·데모 전용] 생성 마이데이터 CI를 직접 연결한다(가상 인증 우회, §13-11).
   * 생성 CI는 GenSeed 해시라 정상 verify로 못 맞추므로 데모에선 CI를 직접 주입한다.
   */
  // 이 경로는 본인인증을 건너뛰므로 서버가 토큰을 같이 준다. **바로 저장해야 한다** —
  // 안 그러면 사람을 바꾼 직후 모든 요청이 앞사람 토큰으로 나가 403이 된다.
  linkSynthetic: async (ci: string, companyIds: number[]) => {
    const r = await post<{ userId: number; ci: string; cardCount: number; paymentCount: number;
                           authToken?: string }>('/api/dev/link-synthetic', { ci, companyIds });
    if (r.authToken) saveAuthToken(r.authToken);
    return r;
  },

  /* ── 소비 분석(②③④⑤) ── */
  /**
   * <b>온보딩 전체가 이 한 번에 걸려 있다.</b> 그래서 기본 15초가 아니라 넉넉히 준다 —
   * 처음 들어온 사람은 명세서가 통째로 들어와 전 이력을 훑고, 15초에 끊기면 아무것도 못 하고
   * 오류 화면을 본다. 대신 화면에는 진행 바가 있고(`Loading`) 12초가 넘으면 그렇다고 말한다.
   */
  analysis: (userId: number, days = 90) =>
    get<AnalysisSummary>(`/api/analysis?userId=${userId}&days=${days}`, SLOW_TIMEOUT_MS),
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
    catSkins: (userId: number) => get<CatSkin[]>(`/api/guardian/cat-skins?userId=${userId}`),
    missions: (userId: number) => get<MissionBoard>(`/api/guardian/missions?userId=${userId}`),
    /** 진행 중 챌린지의 카테고리들 — 관리 화면이 읽는다. */
    challengeCategories: (userId: number) =>
      get<ChallengeCategory[]>(`/api/guardian/challenges/categories?userId=${userId}`),
    /** 한 카테고리의 지킬 돈을 다시 정한다. 이미 쓴 돈보다 낮추면 서버가 400으로 막는다. */
    retarget: (userId: number, category: string, target: number) =>
      post<ChallengeCategory[]>(
        `/api/guardian/challenges/categories/${encodeURIComponent(category)}/target?userId=${userId}`,
        { target }),
    /** 줄일 카테고리를 하나 더한다. 이미 줄이고 있거나 성역이면 서버가 400으로 막는다. */
    addChallengeCategory: (userId: number, category: string, targetSaving?: number) =>
      post<ChallengeCategory[]>(`/api/guardian/challenges/categories?userId=${userId}`,
        { category, targetSaving: targetSaving ?? null }),
    /** 지킴이 말수 — 하루 알림 상한. dailyLimit 0이면 '설정 안 함'이라 기본값을 따른다. */
    voice: (userId: number) =>
      get<{ dailyLimit: number; defaultLimit: number; effectiveLimit: number }>(
        `/api/guardian/voice?userId=${userId}`),
    setVoice: (userId: number, dailyLimit: number) =>
      post<{ dailyLimit: number; defaultLimit: number; effectiveLimit: number }>(
        `/api/guardian/voice?userId=${userId}`, { dailyLimit }),
    /** 성역을 다시 정한다. 줄이기로 한 카테고리와 겹치면 서버가 400으로 막는다. */
    setSanctuary: (userId: number, categories: string[]) =>
      post<{ sanctuaryCategories: string[] }>(
        `/api/guardian/challenges/sanctuary?userId=${userId}`, { categories }),
    pickMission: (userId: number, key: string) =>
      post<MissionBoard>(`/api/guardian/missions/pick?userId=${userId}&key=${encodeURIComponent(key)}`),
    /** 털색을 고른다. 가지지 않은 색이면 서버가 막는다. */
    chooseCatSkin: (userId: number, key: string) =>
      post<CatSkin[]>(`/api/guardian/cat-skins/${encodeURIComponent(key)}?userId=${userId}`, {}),
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
    // 새 결제를 훑어 판정까지 하는 자리라 건수에 비례해 늘어난다.
    sync: (userId: number) => post<{ added: number }>(`/api/guardian/sync?userId=${userId}`, SLOW_TIMEOUT_MS),
    /**
     * 진행 중인 챌린지를 중단한다 — <b>온보딩을 다시 열기 위한</b> 문.
     *
     * <p>온보딩은 진행 중인 챌린지가 있으면 409 로 막힌다. 목표를 통째로 다시 세우려면
     * 먼저 닫아야 한다. 방·소품·포인트는 그대로다. 진행 중인 것이 없어도 200 이다.
     */
    abandonChallenge: (userId: number) =>
      post<{ abandoned: boolean }>(`/api/guardian/challenges/abandon?userId=${userId}`),
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

/**
 * **무엇에 썼는지 모르는 칸** — `카테고리없음`(아직 못 정함)과 `기타`(다 해봤지만 못 정함).
 *
 * 둘은 과정이 다를 뿐 사용자에게는 같은 뜻이다: *"이 돈이 어디로 갔는지 우리도 모른다."*
 *
 * **챌린지에서는 고를 수 없다.** 모르는 칸에 목표를 걸면 무엇을 줄여야 하는지 말해 줄 수
 * 없고, 금액만 보여 주면 *"이만큼 줄일 수 있다"* 는 잘못된 확신을 준다. 실사용자 온보딩에서
 * `기타` 가 늘 후보로 떠 있었다(2026-08-26 제보).
 *
 * `간편결제` 는 셋 중에서도 특별하다 — **원리적으로** 알 수 없다. 상호가 결제대행사
 * 자신이라(`토스페이먼츠`) 무엇을 샀는지 카드사도 말해 주지 않는다. 여기에 목표를 걸면
 * 영원히 줄일 수 없는 것을 줄이라고 말하는 셈이다.
 *
 * 판정이 화면마다 흩어지면 한 곳이 빠진다 — 실제로 `카테고리없음` 만 거르고 `기타` 는
 * 안 거르는 자리가 넷이었다. 그래서 여기 하나로 둔다.
 */
export const UNKNOWN_CATEGORIES = ['카테고리없음', '기타', '간편결제'];
export const isUnknownCategory = (code: string | null | undefined) =>
  !code || UNKNOWN_CATEGORIES.includes(code);
