/**
 * 백엔드 연결층 — 기존 frontend/src/api.ts의 규약(get/post 헬퍼 + 타입드 api 객체)을 그대로 가져왔다.
 * ①소비분석은 실제 엔드포인트(feat/BE-consumption-agent, /api/analysis)에 붙는다.
 * ②지킴·성장 / ③리포트는 아직 백엔드 미구현 → screens는 USE_MOCK일 때 lib/mock을 쓴다.
 */
import { API_BASE } from './config'

async function get<T>(path: string): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`)
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} — ${path}`)
  return res.json() as Promise<T>
}
async function post<T>(path: string, body?: unknown): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (!res.ok) throw new Error(`${res.status} ${res.statusText} — ${path}`)
  return res.json() as Promise<T>
}

/* ── ① 소비 분석 (실제 API · /api/analysis) ─────────────────────────── */
export interface AnalysisProfile {
  abnormalityIndex: number
  contributionPoints: Record<string, number>
  totalSpend: number
  topCategory1: string | null
}
export interface RecurringPayment {
  merchantName: string
  category2: string | null
  amount: number
  /** FIXED = 고정지출(통신·구독), ROUTINE = 습관 반복 */
  type: 'FIXED' | 'ROUTINE'
  dayOfMonth: number | null
}
export interface SpendingPattern {
  amountByDayOfWeek: Record<string, number>
  amountByDaypart: Record<string, number>
}
export interface CutCandidate {
  category2: string
  /** REMOVABLE = 제거가능, OPTIMIZABLE = 최적화가능 */
  type: 'REMOVABLE' | 'OPTIMIZABLE'
  estimatedSaving: number
  monthlyAmount: number
  reason: string
}
export interface AnalysisSummary {
  profile: AnalysisProfile
  recurring: RecurringPayment[]
  pattern: SpendingPattern
  cutCandidates: CutCandidate[]
}
export interface Narrative { text: string; source: string }
export interface CutSelection { category2: string; monthlyAmount: number; chosenAt: string }

/* ── 마이데이터 (본인인증·연결) ─────────────────────────────────────── */
export interface VerifyResult { ci: string; verified: boolean; existsInMyData: boolean }
export interface MyDataCompany { id: number; name: string; imgUrl: string }
export interface MyDataLinkResult { cardCount: number; paymentCount: number }

/* ── ③ 금융상품 정보성 비교 (예·적금 통장) ──────────────────────────── */
export interface AccountView {
  company: string; name: string; baseRate: number; primeRate: number
  /* 금감원 오픈API에서만 오는 값(더미 폴백 시 빈 값). saveTrm=예치기간(개월),
     reserveType=적립방식(자유적립식), joinMember=가입대상 원문(자격 표시용). */
  saveTrm?: number; reserveType?: string; joinDeny?: string
  joinMember?: string; spclCnd?: string; prdtKey?: string
}
export interface SavingsCompare { accounts: AccountView[]; live: boolean; totalConsidered: number; note: string | null }

export const api = {
  // ① 분석
  analysis: (userId: number, days = 90) => get<AnalysisSummary>(`/api/analysis?userId=${userId}&days=${days}`),
  profileNarrative: (userId: number, days = 90) =>
    get<Narrative>(`/api/analysis/profile/narrative?userId=${userId}&days=${days}`),
  explainCut: (userId: number, category2: string, days = 90) =>
    get<Narrative>(`/api/analysis/cut/explain?userId=${userId}&category2=${encodeURIComponent(category2)}&days=${days}`),
  chooseCut: (userId: number, category2: string, days = 90) =>
    post<CutSelection>(`/api/analysis/cut/choose?userId=${userId}&category2=${encodeURIComponent(category2)}&days=${days}`),

  // 마이데이터 연결
  verify: (userId: number, name: string, social7: string, phone: string) =>
    post<VerifyResult>('/api/mydata/verify', { userId, name, social7, phone }),
  mydataCompanies: () => get<MyDataCompany[]>('/api/mydata/companies'),
  mydataLink: (userId: number, companyIds: number[]) =>
    post<MyDataLinkResult>('/api/mydata/link', { userId, companyIds }),

  // ③ 통장 비교 (정보성)
  // userId를 주면 그 사용자의 출생연도로 나이 자격까지 맞춰 거른다(백엔드 SavingsCompareController).
  // 없으면 나이 조건은 따지지 않고 특수 신분 조건만 걸러 보여준다 — 판매가 아니라 정보성 비교라서.
  compareSavings: (opts?: { limit?: number; userId?: number }) => {
    const qs = new URLSearchParams()
    if (opts?.limit) qs.set('limit', String(opts.limit))
    if (opts?.userId != null) qs.set('userId', String(opts.userId))
    const s = qs.toString()
    return get<SavingsCompare>(`/api/savings/compare${s ? `?${s}` : ''}`)
  },

  /* ── 본인인증 SMS (외부 시연용, 백엔드 미구현 · TODO) ──────────────────
     실제 발송/검증은 백엔드가 Solapi로 처리한다. 프론트는 엔드포인트만 호출.
     ⚠️ 프론트는 API 키/시크릿을 절대 갖지 않는다(백엔드 .env). */
  smsRequest: (phone: string) => post<{ requestId: string }>('/api/auth/sms/request', { phone }),
  smsVerify: (requestId: string, code: string) =>
    post<VerifyResult>('/api/auth/sms/verify', { requestId, code }),
}

/** 카테고리 코드 → 한글 표시명 (표현 전용 폴백). 서버 displayName 우선. */
export const CATEGORY_LABEL: Record<string, string> = {
  FOOD: '식비', CAFE: '카페·간식', SHOPPING: '쇼핑', TRANSPORT: '교통',
  HOUSING: '주거', MEDICAL: '의료', CULTURE: '문화·여가', EDUCATION: '교육',
  COMMUNICATION: '통신', BEAUTY: '미용', TRAVEL: '여행', ETC: '기타',
}
export const catLabel = (code: string, displayName?: string) =>
  (displayName && displayName !== code ? displayName : CATEGORY_LABEL[code]) ?? code
