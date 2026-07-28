/**
 * 세션 · 내비게이션.
 *
 * 목업은 화면 id 하나 + 뒤로가기 스택이었다. 반응형 웹에서는 그것만으로 부족해
 * **주소(해시)** 를 화면 상태의 단일 출처로 삼았다 — 브라우저 뒤로가기·새로고침·링크 공유가
 * 그냥 동작해야 하고(KWCAG 2.4 쉬운 내비게이션), 라우터 라이브러리를 새로 들일 필요는 없다.
 */
import {
  createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode,
} from 'react';
import { DEFAULT_USER_ID } from '../lib/config';
import type { AnalysisSummary } from '../lib/api';

export type ScreenId =
  // L0 최초 온보딩
  | 'splash' | 'auth' | 'connect' | 'loading'
  // 이번 챌린지 정하기 (최초 · 월초 재진입 공용)
  | 'ob1' | 'ob2' | 'ob3' | 'done'
  // 상시 탐색 3탭
  | 'home' | 'report' | 'my'
  // 홈 하위
  | 'myroom' | 'notifications' | 'transactions'
  // 리포트 하위
  | 'r-analysis' | 'r-spending' | 'r-cards' | 'r-waste' | 'r-savings'
  // 마이 하위
  | 'm-impulse' | 'm-goals' | 'm-connections' | 'm-record' | 'm-policy' | 'm-survey' | 'm-demo';

export const TAB_SCREENS = ['home', 'report', 'my'] as const;
export type TabId = (typeof TAB_SCREENS)[number];
export const isTab = (s: ScreenId): s is TabId => (TAB_SCREENS as readonly string[]).includes(s);

const ALL_SCREENS: ScreenId[] = [
  'splash', 'auth', 'connect', 'loading', 'ob1', 'ob2', 'ob3', 'done',
  'home', 'report', 'my', 'myroom', 'notifications', 'transactions',
  'r-analysis', 'r-spending', 'r-cards', 'r-waste', 'r-savings',
  'm-impulse', 'm-goals', 'm-connections', 'm-record', 'm-policy', 'm-survey', 'm-demo',
];
const isScreen = (v: string): v is ScreenId => (ALL_SCREENS as string[]).includes(v);

/** 각 화면이 속한 탭 — 하단 탭의 현재 위치 표시에 쓴다. */
export function tabOf(screen: ScreenId): TabId | null {
  if (isTab(screen)) return screen;
  if (screen.startsWith('r-')) return 'report';
  if (screen.startsWith('m-')) return 'my';
  if (screen === 'myroom' || screen === 'notifications' || screen === 'transactions') return 'home';
  return null;
}

/** 이번 챌린지를 정하는 동안 사용자가 고른 값. 확정되면 서버(챌린지)로 넘어간다. */
export interface ChallengeDraft {
  /** 가치 소비(성역) 카테고리 코드 — 지킴이가 먼저 침묵한다. */
  sanctuary: string[];
  /** 줄일 카테고리 코드. */
  cutCats: string[];
  /** 카테고리별 절약 강도(0.1~0.9). */
  intensities: Record<string, number>;
  /** 코드 → 표시명·월평균. 화면이 매번 다시 묻지 않도록 함께 들고 다닌다. */
  baseline: Record<string, { displayName: string; monthlyAmount: number; reason?: string; type?: string }>;
}
const emptyDraft: ChallengeDraft = { sanctuary: [], cutCats: [], intensities: {}, baseline: {} };

interface Session {
  userId: number;
  setUserId: (id: number) => void;
  /** 마이데이터 연결을 마쳤는가(최초 온보딩 통과 여부). */
  linked: boolean;
  setLinked: (v: boolean) => void;
  screen: ScreenId;
  go: (id: ScreenId) => void;
  back: () => void;
  /** 최초 온보딩부터 다시 — 연결 상태와 선택을 모두 비운다. */
  resetOnboarding: () => void;
  draft: ChallengeDraft;
  patchDraft: (patch: Partial<ChallengeDraft>) => void;
  analysis: AnalysisSummary | null;
  setAnalysis: (a: AnalysisSummary | null) => void;
}

const Ctx = createContext<Session | null>(null);

const read = (key: string) => {
  try { return localStorage.getItem(key); } catch { return null; }
};
const write = (key: string, value: string) => {
  try { localStorage.setItem(key, value); } catch { /* 사파리 프라이빗 등 — 무시 */ }
};
const remove = (key: string) => {
  try { localStorage.removeItem(key); } catch { /* noop */ }
};

const hashScreen = (): ScreenId | null => {
  const raw = window.location.hash.replace(/^#\/?/, '');
  return raw && isScreen(raw) ? raw : null;
};

export function SessionProvider({ children }: { children: ReactNode }) {
  const [userId, setUserIdState] = useState<number>(() => {
    const v = Number(read('demo_user_id'));
    return v > 0 ? v : DEFAULT_USER_ID;
  });
  const [linked, setLinkedState] = useState<boolean>(() => read('mydata_onboarded') === 'true');
  const [screen, setScreen] = useState<ScreenId>(() => hashScreen() ?? (read('mydata_onboarded') === 'true' ? 'home' : 'splash'));
  const [draft, setDraft] = useState<ChallengeDraft>(emptyDraft);
  const [analysis, setAnalysis] = useState<AnalysisSummary | null>(null);

  // 주소 ↔ 화면 동기화. 뒤로가기/앞으로가기는 브라우저가 맡는다.
  useEffect(() => {
    const onPop = () => setScreen(hashScreen() ?? 'home');
    window.addEventListener('popstate', onPop);
    window.addEventListener('hashchange', onPop);
    return () => {
      window.removeEventListener('popstate', onPop);
      window.removeEventListener('hashchange', onPop);
    };
  }, []);

  const go = useCallback((id: ScreenId) => {
    setScreen(id);
    if (hashScreen() !== id) window.history.pushState(null, '', `#/${id}`);
  }, []);

  const back = useCallback(() => {
    if (window.history.length > 1) window.history.back();
    else go('home');
  }, [go]);

  const setUserId = useCallback((id: number) => {
    write('demo_user_id', String(id));
    setUserIdState(id);
    setAnalysis(null);
    setDraft(emptyDraft);
  }, []);

  const setLinked = useCallback((v: boolean) => {
    if (v) write('mydata_onboarded', 'true'); else remove('mydata_onboarded');
    setLinkedState(v);
  }, []);

  const resetOnboarding = useCallback(() => {
    remove('mydata_onboarded');
    setLinkedState(false);
    setDraft(emptyDraft);
    setAnalysis(null);
    setScreen('splash');
    window.history.pushState(null, '', '#/splash');
  }, []);

  const patchDraft = useCallback((patch: Partial<ChallengeDraft>) => {
    setDraft((d) => ({ ...d, ...patch }));
  }, []);

  const value = useMemo<Session>(() => ({
    userId, setUserId, linked, setLinked, screen, go, back, resetOnboarding,
    draft, patchDraft, analysis, setAnalysis,
  }), [userId, setUserId, linked, setLinked, screen, go, back, resetOnboarding, draft, patchDraft, analysis]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSession(): Session {
  const v = useContext(Ctx);
  if (!v) throw new Error('useSession must be used within SessionProvider');
  return v;
}
