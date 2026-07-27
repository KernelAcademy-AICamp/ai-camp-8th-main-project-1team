/**
 * ②지킴·성장 프론트 도메인 + mock. 금액 모델은 PRD §5.4:
 *   사용 한도 = 기준소비 × (1 − 강도) · 지킬 돈 = 기준소비 × 강도
 *   차감 = max(누적소비 − 사용한도, 0) · 지킨 돈 = max(지킬 돈 − 차감, 0)
 * 카테고리는 코드 대신 자립형(name·icon 포함) — mock/실API(category2 문자열) 둘 다 같은 타입.
 */
import type { AnalysisSummary } from './api'

/** 절약 강도 3단계 (사용자 확정: 20/50/100%). 미세조정은 스테퍼로. */
export const INTENSITY_TIERS = [
  { key: 'soft', label: '살짝', value: 0.2, caption: '기준의 20%만 아껴요 · 부담 적음' },
  { key: 'mid', label: '적당히', value: 0.5, caption: '기준의 절반을 아껴요 · 균형' },
  { key: 'hard', label: '많이', value: 1.0, caption: '이번 달 완전히 끊어봐요 · 도전' },
] as const
export const DEFAULT_INTENSITY = 0.5

/** 아이콘 → 배경색 토큰. */
export const ICON_BG: Record<string, string> = {
  'i-food': 'var(--c-food)', 'i-cafe': 'var(--c-cafe)', 'i-taxi': 'var(--c-taxi)',
  'i-cvs': 'var(--c-cvs)', 'i-shop': 'var(--c-shop)', 'i-ott': 'var(--c-ott)',
  'i-heart': '#FFE9EC', 'i-book': '#FFF7E6', 'i-gift': '#FFF1E8',
  'i-paw': '#F3EEFF', 'i-med': '#FDECEE', 'i-plane': '#E8F6FE', 'i-game': '#EEF0FF',
}
/** 카테고리 표시명(category2) → 아이콘 id. 실 API의 한글 카테고리도 매핑. */
export function iconFor(name: string): string {
  const n = name.replace(/\s/g, '')
  if (/배달|외식|음식|식비/.test(n)) return 'i-food'
  if (/카페|간식|커피|디저트/.test(n)) return 'i-cafe'
  if (/택시|교통|대중교통/.test(n)) return 'i-taxi'
  if (/편의점/.test(n)) return 'i-cvs'
  if (/쇼핑|의류|패션/.test(n)) return 'i-shop'
  if (/구독|OTT|스트리밍/.test(n)) return 'i-ott'
  return 'i-shop'
}
export const bgFor = (icon: string) => ICON_BG[icon] ?? 'var(--bg)'

/** 가치 소비(성역) 칩 카탈로그 — 줄이고 싶지 않은 소비. 선택 시 절약 후보 우선순위만 낮춤. */
export const VALUE_CATS = [
  { key: 'health', name: '건강·운동', icon: 'i-heart' },
  { key: 'study', name: '책·공부', icon: 'i-book' },
  { key: 'family', name: '가족·선물', icon: 'i-gift' },
  { key: 'pet', name: '반려동물', icon: 'i-paw' },
  { key: 'medical', name: '병원·약', icon: 'i-med' },
  { key: 'travel', name: '여행', icon: 'i-plane' },
  { key: 'hobby', name: '취미', icon: 'i-game' },
] as const

/* ── 홈(②) 도메인 ─────────────────────────────────────────────────── */
export interface KeepCategory {
  key: string; name: string; icon: string; iconBg: string
  baseSpend: number     // 기준소비(원)
  intensity: number     // 0.2 · 0.5 · 1.0
  used: number          // 이번 달 누적 소비(원)
}
export interface KeepCategoryView extends KeepCategory {
  cap: number; goal: number; deducted: number; remain: number; usedPct: number
}
export interface RecentTx {
  merchant: string; when: string; amount: number; icon: string; iconBg: string
  effect: 'deducted' | 'nochange' | 'unselected'; deducted?: number
}
export interface KeepState {
  month: number; chapterLabel: string; level: number
  streakDays: number; points: number
  categories: KeepCategory[]
  guardianMessage: string
  recentTx: RecentTx[]
}

/** 파생 계산 — 화면 공용. */
export function deriveKeep(cats: KeepCategory[]) {
  const views: KeepCategoryView[] = cats.map((c) => {
    const goal = Math.round(c.baseSpend * c.intensity)
    const cap = c.baseSpend - goal
    const deducted = Math.max(c.used - cap, 0)
    return { ...c, cap, goal, deducted, remain: Math.max(goal - deducted, 0), usedPct: Math.min(c.used / c.baseSpend, 1) }
  })
  const savingGoal = views.reduce((s, v) => s + v.goal, 0)
  const keptAmount = views.reduce((s, v) => s + v.remain, 0)
  const defenseRate = savingGoal ? Math.round((keptAmount / savingGoal) * 100) : 0
  const status: 'keeping' | 'depleted' = keptAmount > 0 ? 'keeping' : 'depleted'
  return { views, savingGoal, keptAmount, defenseRate, status }
}

/** 기본 데모 홈 상태 — 배달·카페 50%, 방어율 ≈ 74%. */
export function mockKeepState(): KeepState {
  return {
    month: 7, chapterLabel: '7월의 길', level: 3, streakDays: 6, points: 340,
    categories: [
      { key: '배달·외식', name: '배달·외식', icon: 'i-food', iconBg: 'var(--c-food)', baseSpend: 250_000, intensity: 0.5, used: 168_000 },
      { key: '카페·간식', name: '카페·간식', icon: 'i-cafe', iconBg: 'var(--c-cafe)', baseSpend: 80_000, intensity: 0.5, used: 30_000 },
    ],
    guardianMessage: '배달 지킬 돈이 커피 몇 잔 정도만 남았어요. 오늘 지키면 내일 아침 방에 선물이 도착해요.',
    recentTx: [
      { merchant: '배달의민족', when: '어제 21:40', amount: 12_000, icon: 'i-food', iconBg: 'var(--c-food)', effect: 'deducted', deducted: 12_000 },
      { merchant: '스타벅스', when: '오늘 08:12', amount: 4_800, icon: 'i-cafe', iconBg: 'var(--c-cafe)', effect: 'nochange' },
      { merchant: 'GS25', when: '오늘 12:30', amount: 3_200, icon: 'i-cvs', iconBg: 'var(--c-cvs)', effect: 'unselected' },
    ],
  }
}

/** ① 분석 mock — 실 API(AnalysisSummary)와 같은 타입. USE_MOCK일 때 사용. */
export function mockAnalysis(): AnalysisSummary {
  return {
    profile: {
      abnormalityIndex: 47,
      contributionPoints: { 낭비: 18, 집중: 12, 변동: 9, 심야충동: 8 },
      totalSpend: 1_240_000,
      topCategory1: '배달·외식',
    },
    recurring: [
      { merchantName: '넷플릭스', category2: '구독·OTT', amount: 13_500, type: 'FIXED', dayOfMonth: 17 },
      { merchantName: 'SKT 통신요금', category2: '통신', amount: 55_000, type: 'FIXED', dayOfMonth: 25 },
      { merchantName: '배달의민족', category2: '배달·외식', amount: 210_000, type: 'ROUTINE', dayOfMonth: null },
      { merchantName: '스타벅스', category2: '카페·간식', amount: 72_000, type: 'ROUTINE', dayOfMonth: null },
    ],
    pattern: {
      amountByDayOfWeek: { MONDAY: 32000, TUESDAY: 28000, WEDNESDAY: 30000, THURSDAY: 41000, FRIDAY: 88000, SATURDAY: 64000, SUNDAY: 47000 },
      amountByDaypart: { 아침: 18000, 점심: 61000, 저녁: 96000, 심야: 74000 },
    },
    cutCandidates: [
      { category2: '배달·외식', type: 'OPTIMIZABLE', monthlyAmount: 250_000, estimatedSaving: 125_000, reason: '금요일 밤에 배달이 몰려요' },
      { category2: '카페·간식', type: 'OPTIMIZABLE', monthlyAmount: 80_000, estimatedSaving: 40_000, reason: '거의 매일 카페를 이용해요' },
      { category2: '택시', type: 'REMOVABLE', monthlyAmount: 60_000, estimatedSaving: 30_000, reason: '심야 택시가 잦아요' },
      { category2: '편의점', type: 'OPTIMIZABLE', monthlyAmount: 50_000, estimatedSaving: 20_000, reason: '습관적 편의점 결제가 보여요' },
    ],
  }
}

/* ── 월말 결산 데모(s-settle) ─────────────────────────────────────── */
export interface SettleState {
  month: number; defenseRate: number; savingGoal: number; keptAmount: number
  perCategory: { name: string; icon: string; iconBg: string; goal: number; spent: number; achievedPct: number }[]
  streakDays: number; bestStreak: number; pointsThisMonth: number; objectsCollected: number
}
export function mockSettle(): SettleState {
  return {
    month: 7, defenseRate: 68, savingGoal: 165_000, keptAmount: 112_200,
    perCategory: [
      { name: '배달·외식', icon: 'i-food', iconBg: 'var(--c-food)', goal: 125_000, spent: 84_000, achievedPct: 67 },
      { name: '카페·간식', icon: 'i-cafe', iconBg: 'var(--c-cafe)', goal: 40_000, spent: 38_000, achievedPct: 95 },
    ],
    streakDays: 21, bestStreak: 9, pointsThisMonth: 240, objectsCollected: 18,
  }
}
