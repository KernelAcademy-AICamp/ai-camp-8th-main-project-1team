/**
 * 데이터 로더 — USE_MOCK 스위치로 mock ↔ 실 API를 고른다.
 * ①소비분석은 실 엔드포인트(/api/analysis)가 있으면 붙고, 없으면(USE_MOCK) mock으로.
 * 화면은 이 함수만 호출하므로, 백엔드가 준비되면 여기만 바꾸면 된다.
 */
import { api, type AnalysisSummary, type CutCandidate } from './api'
import { USE_MOCK } from './config'
import {
  mockAnalysis, bgFor, iconFor, DEFAULT_INTENSITY,
  type KeepState, type KeepCategory,
} from './mock'

/** ① 분석 요약 로드. */
export async function loadAnalysis(userId: number): Promise<AnalysisSummary> {
  if (USE_MOCK) return Promise.resolve(mockAnalysis())
  return api.analysis(userId)
}

/** 온보딩 선택(줄일 카테고리 + 강도) → 첫 달 홈 상태(②)를 만든다.
 *  ②는 아직 백엔드 미구현이라 프론트에서 시드한다. used는 데모용 소액(≈기준의 15%). */
export function buildKeepFromDraft(
  cutCats: string[], intensities: Record<string, number>, candidates: CutCandidate[],
): KeepState {
  const byName = new Map(candidates.map((c) => [c.category2, c]))
  const categories: KeepCategory[] = cutCats.map((name) => {
    const base = byName.get(name)?.monthlyAmount ?? 100_000
    const icon = iconFor(name)
    return {
      key: name, name, icon, iconBg: bgFor(icon),
      baseSpend: base,
      intensity: intensities[name] ?? DEFAULT_INTENSITY,
      used: Math.round(base * 0.15),
    }
  })
  const first = categories[0]?.name ?? '소비'
  return {
    month: 7, chapterLabel: '7월의 길', level: 1, streakDays: 0, points: 0,
    categories,
    guardianMessage: `첫 챕터가 시작됐어요. ${first}부터 저랑 같이 지켜봐요. 무리 안 하게 옆에서 챙길게요.`,
    recentTx: categories.slice(0, 1).map((c) => ({
      merchant: c.name, when: '오늘', amount: Math.round(c.used * 0.2),
      icon: c.icon, iconBg: c.iconBg, effect: 'nochange' as const,
    })),
  }
}
