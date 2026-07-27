/**
 * 세션·내비게이션 — react-router 없이 프로토타입처럼 가벼운 화면 전환(KISS/YAGNI).
 * 화면 id 하나 + 뒤로가기 스택. 온보딩 선택값과 홈 지킴 상태를 함께 보관한다.
 */
import { createContext, useCallback, useContext, useMemo, useState, type ReactNode } from 'react'
import { DEMO_USER_ID } from '../lib/config'
import { mockKeepState, type KeepState } from '../lib/mock'
import type { AnalysisSummary } from '../lib/api'

export type ScreenId =
  | 'splash' | 'auth' | 'connect' | 'loading'
  | 'ob1' | 'ob2' | 'ob3' | 'done'
  | 'home' | 'myroom' | 'report' | 'my'
  | 'monthend' | 'settle' | 'renew'

export const TAB_SCREENS = ['home', 'report', 'my'] as const
export type TabId = (typeof TAB_SCREENS)[number]
export const isTab = (s: ScreenId): s is TabId => (TAB_SCREENS as readonly string[]).includes(s)

/** 온보딩 진행 중 사용자가 고른 값. */
export interface OnboardingDraft {
  valueCats: string[]          // 가치 소비(성역) 표시 — 후보 우선순위만 낮춤
  cutCats: string[]            // 줄일 카테고리
  intensities: Record<string, number>  // 카테고리별 강도(0.2/0.5/1.0)
}
const emptyDraft: OnboardingDraft = { valueCats: [], cutCats: [], intensities: {} }

interface Session {
  userId: number
  screen: ScreenId
  go: (id: ScreenId) => void
  back: () => void
  reset: () => void
  draft: OnboardingDraft
  patchDraft: (patch: Partial<OnboardingDraft>) => void
  analysis: AnalysisSummary | null
  setAnalysis: (a: AnalysisSummary) => void
  keep: KeepState
  setKeep: (k: KeepState) => void
}

const Ctx = createContext<Session | null>(null)

export function SessionProvider({ children }: { children: ReactNode }) {
  const [screen, setScreen] = useState<ScreenId>('splash')
  // 뒤로가기 스택 — 값은 setter 내부(functional update)에서만 읽으므로 setter만 보관
  const [, setHistory] = useState<ScreenId[]>([])
  const [draft, setDraft] = useState<OnboardingDraft>(emptyDraft)
  const [analysis, setAnalysis] = useState<AnalysisSummary | null>(null)
  const [keep, setKeep] = useState<KeepState>(mockKeepState)

  const go = useCallback((id: ScreenId) => {
    setHistory((h) => [...h, screen])
    setScreen(id)
  }, [screen])

  const back = useCallback(() => {
    setHistory((h) => {
      if (h.length === 0) return h
      setScreen(h[h.length - 1])
      return h.slice(0, -1)
    })
  }, [])

  const reset = useCallback(() => {
    setHistory([]); setDraft(emptyDraft); setAnalysis(null); setKeep(mockKeepState()); setScreen('splash')
  }, [])

  const patchDraft = useCallback((patch: Partial<OnboardingDraft>) => {
    setDraft((d) => ({ ...d, ...patch }))
  }, [])

  const value = useMemo<Session>(() => ({
    userId: DEMO_USER_ID, screen, go, back, reset, draft, patchDraft, analysis, setAnalysis, keep, setKeep,
  }), [screen, go, back, reset, draft, patchDraft, analysis, keep])

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>
}

export function useSession(): Session {
  const v = useContext(Ctx)
  if (!v) throw new Error('useSession must be used within SessionProvider')
  return v
}
