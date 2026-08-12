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
import { ApiError, api, clearAuthToken } from '../lib/api';
import type { AnalysisSummary, OnboardingPayment } from '../lib/api';

export type ScreenId =
  // L0 최초 온보딩
  | 'boot' | 'walk' | 'auth' | 'connect' | 'loading'
  // 이번 챌린지 정하기 (최초 · 월초 재진입 공용)
  | 'ob1' | 'ob2' | 'ob3' | 'ob4' | 'done'
  // 상시 탐색 3탭
  | 'home' | 'report' | 'my'
  // 홈 하위
  | 'myroom' | 'notifications' | 'transactions'
  // 마이룸 하위 — 도감·포인트샵 (개편안 s-collection·s-shop)
  | 'collection' | 'shop'
  // 월말 사이클 — 완료 축하 → 결산 → 다음 달 갱신 (개편안 s-monthend·s-settle·s-renew)
  | 'monthend' | 'settle' | 'renew'
  // 리포트 하위 — r-compare 는 개편안 s-compare(맞춤 상품 Top3)
  | 'r-compare' | 'r-analysis' | 'r-spending' | 'r-cards' | 'r-account' | 'r-waste' | 'r-savings'
  // 마이 하위
  | 'm-impulse' | 'm-goals' | 'm-connections' | 'm-record' | 'm-policy' | 'm-survey' | 'm-demo'
  | 'm-stances' | 'm-unclassified'
  // 임시 — 프로토타입_0806 이 자리를 안 정한 화면들을 모아 둔 곳. 정해지면 없앤다.
  | 'm-parked' | 'm-products' | 'm-sanctuary' | 'm-voice'
  | 'm-challenge' | 'm-challenge-new';

export const TAB_SCREENS = ['home', 'report', 'my'] as const;
export type TabId = (typeof TAB_SCREENS)[number];
export const isTab = (s: ScreenId): s is TabId => (TAB_SCREENS as readonly string[]).includes(s);

/**
 * 주소(#)로 복원할 수 있는 화면. **ScreenId와 하나도 빠짐없이 같아야 한다.**
 *
 * 빠지면 조용히 망가진다 — 화면 이동은 되는데(직접 setScreen) 새로고침이나 링크로 들어올 때만
 * {@link hashScreen}이 못 알아보고 홈으로 튕긴다. 실제로 `r-account`(내 통장)가 메뉴와 라우터에는
 * 있는데 여기만 빠져 있었고, 그래서 통장을 보다 새로고침하면 홈으로 돌아갔다.
 * 아래 위성 타입이 그 누락을 컴파일 단계에서 잡는다.
 */
const ALL_SCREENS = [
  'boot', 'walk', 'auth', 'connect', 'loading', 'ob1', 'ob2', 'ob3', 'ob4', 'done',
  'home', 'report', 'my', 'myroom', 'notifications', 'transactions',
  'collection', 'shop', 'monthend', 'settle', 'renew',
  'r-compare', 'r-analysis', 'r-spending', 'r-cards', 'r-account', 'r-waste', 'r-savings',
  'm-impulse', 'm-goals', 'm-connections', 'm-record', 'm-policy', 'm-survey', 'm-demo',
  'm-stances', 'm-unclassified', 'm-parked', 'm-products', 'm-sanctuary', 'm-voice',
  'm-challenge', 'm-challenge-new',
] as const;

// 하나라도 빠지면 여기서 타입 오류가 난다(빠진 ScreenId가 never에 배정되지 못한다).
type _AllScreensCoverEveryScreenId = ScreenId extends (typeof ALL_SCREENS)[number] ? true : never;
const _screenCoverage: _AllScreensCoverEveryScreenId = true;
void _screenCoverage;
const isScreen = (v: string): v is ScreenId => (ALL_SCREENS as readonly string[]).includes(v);

/** 각 화면이 속한 탭 — 하단 탭의 현재 위치 표시에 쓴다. */
export function tabOf(screen: ScreenId): TabId | null {
  if (isTab(screen)) return screen;
  if (screen.startsWith('r-')) return 'report';
  if (screen.startsWith('m-')) return 'my';
  if (screen === 'myroom' || screen === 'notifications' || screen === 'transactions'
      || screen === 'collection' || screen === 'shop') return 'home';
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
  /**
   * 코드 → 표시명·창 안 실측금액·그 안의 결제들. 화면이 매번 다시 묻지 않도록 함께 들고 다닌다.
   *
   * <b>`payments`는 강도 화면이 펼쳐 보여주는 목록이다.</b> 금액과 목록이 같은 응답에서 와야
   * "이 결제를 빼면 금액이 이만큼 줄어든다"가 성립한다.
   */
  baseline: Record<string, {
    displayName: string;
    /** 최근 30일 **실측** 합계. 월 환산이 아니다. */
    monthlyAmount: number;
    /** ML이 낭비로 본 금액(그 카테고리 안에서). */
    wasteAmount?: number;
    payments?: OnboardingPayment[];
    reason?: string;
    type?: string;
  }>;
  /**
   * 사용자가 "이건 낭비가 아니다"로 해제한 결제 id.
   *
   * 지킬 돈은 <b>낭비로 남은 금액</b>에만 강도를 곱한다 — 전체 지출에 곱하면 월세·병원비까지
   * 줄이라는 말이 된다.
   */
  keptPaymentIds: string[];
}
const emptyDraft: ChallengeDraft = {
  sanctuary: [], cutCats: [], intensities: {}, baseline: {}, keptPaymentIds: [],
};

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
  /**
   * 챌린지 관리에서 지금 열어 둔 카테고리.
   *
   * 주소에 담지 않는 이유: 이 앱은 해시 한 칸으로만 화면을 가르고, 카테고리 이름에는
   * `/`·공백이 섞여 있어(`교통/자동차`) 주소로 옮기면 이스케이프 규칙이 하나 더 생긴다.
   */
  challengeCategory: string | null;
  openChallenge: (category: string) => void;
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

/**
 * 세리머니를 하루 한 번으로 묶는 날짜 키. 쓰는 곳은 마이룸이지만 <b>이름은 여기가 갖는다</b> —
 * 로그아웃이 지워야 할 목록에서 빠지면 앞사람의 날짜가 뒷사람의 연출을 삼킨다.
 */
export const CEREMONY_SEEN_KEY = 'guardian_ceremony_seen';

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
  const [challengeCategory, setChallengeCategory] = useState<string | null>(null);
  const [screen, setScreen] = useState<ScreenId>(() => hashScreen() ?? (read('mydata_onboarded') === 'true' ? 'home' : 'boot'));
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

  /** 그 카테고리의 관리 화면을 연다. */
  const openChallenge = useCallback((category: string) => {
    setChallengeCategory(category);
    go('m-challenge');
  }, [go]);

  /**
   * 처음으로 되돌린다(= 로그아웃).
   *
   * <b>userId 도 함께 버린다.</b> 예전에는 `mydata_onboarded` 만 지워서, 다음 사람이 같은 브라우저에서
   * 인증해도 세션은 앞사람의 계정을 들고 있었다. 그 상태로 연동하면 앞사람 계정에 뒷사람 신원이
   * 덮어써지고, 홈은 앞사람의 챌린지를 계속 보여줬다(2026-07-31 운영). 서버도 CI 로 계정을 고르도록
   * 함께 고쳤지만, 신원을 끊는 일은 로그아웃이 먼저 해야 한다.
   *
   * <b>앞사람의 흔적은 하나도 남기지 않는다.</b> 같은 이유로 브라우저에 남는 나머지도 지운다 —
   * `guardian_ceremony_seen` 은 세리머니를 하루 한 번으로 묶는 날짜라, 남겨 두면 뒷사람은
   * 그날 받은 소품 연출을 <b>못 보고 지나간다</b>. `demo_ci` 는 데모 패널이 기억하는 앞사람의 CI다.
   *
   * <b>인증 토큰이 가장 중요하다.</b> 남겨 두면 뒷사람의 브라우저가 앞사람의 열쇠를 들고 있는
   * 셈이라, 로그아웃했는데도 앞사람 계정으로 요청이 나간다.
   */
  const resetOnboarding = useCallback(() => {
    clearAuthToken();
    remove('mydata_onboarded');
    remove('demo_user_id');
    remove(CEREMONY_SEEN_KEY);
    remove('demo_ci');
    setUserIdState(DEFAULT_USER_ID);
    setLinkedState(false);
    setDraft(emptyDraft);
    setAnalysis(null);
    setScreen('boot');
    window.history.pushState(null, '', '#/boot');
  }, []);

  /**
   * 기동 시 **저장된 사용자가 서버에 실제로 있는지** 한 번 확인하고, 없으면 처음으로 되돌린다.
   *
   * 서버 DB가 갈리면(개발 재기동·운영 DB 교체·새 환경 배포) 남아 있던 id 로 모든 요청이 404 가
   * 되어 화면마다 'Load Failed'만 뜬다. 사용자에겐 **다시 가입할 길조차 없다** — 온보딩도 같은
   * id 로 부르기 때문이다. 저장값을 버려야 빠져나올 수 있다.
   *
   * 오류 문구로 판별하지 않는 이유: Spring 기본 404 본문은 사유를 싣지 않아
   * (`server.error.include-message`가 never) "사용자 없음"과 "챌린지 없음"이 구분되지 않는다.
   * 그래서 존재 여부를 **명시적으로** 묻는다. 기동당 요청 한 번이다.
   *
   * <b>401도 같이 본다.</b> 인증을 도입한 배포 직후, 이미 가입해 둔 사람의 브라우저에는
   * `mydata_onboarded`와 `demo_user_id`는 있는데 **토큰만 없다.** 404만 보고 있으면 그 사람은
   * 모든 요청이 401로 막힌 채 화면마다 'Load Failed'만 보게 되고, 온보딩도 못 탄다 —
   * 위에 적힌 그 상황이 사유만 바뀌어 그대로 재현된다. 토큰이 없거나 만료됐으면
   * 처음으로 되돌려 <b>다시 인증할 길을 준다.</b>
   */
  useEffect(() => {
    if (!read('mydata_onboarded')) return;          // 아직 가입 전이면 확인할 것이 없다
    let alive = true;
    void api.getUser(userId).catch((e: unknown) => {
      // 네트워크 오류는 건드리지 않는다 — 잠깐 끊긴 것으로 가입을 날리면 안 된다.
      // 404 = 그 사용자가 없다 · 401 = 토큰이 없거나 만료됐다 · 403 = 남의 id 를 들고 있다.
      // 셋 다 "지금 이 신원으로는 아무것도 못 한다"이고, 빠져나갈 길은 처음부터 다시뿐이다.
      if (!alive || !(e instanceof ApiError)) return;
      if (e.status !== 404 && e.status !== 401 && e.status !== 403) return;
      remove('demo_user_id');
      setUserIdState(DEFAULT_USER_ID);
      resetOnboarding();
    });
    return () => { alive = false; };
    // 기동 시 한 번만 — 사람을 바꿀 때마다 다시 물을 필요는 없다.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const patchDraft = useCallback((patch: Partial<ChallengeDraft>) => {
    setDraft((d) => ({ ...d, ...patch }));
  }, []);

  const value = useMemo<Session>(() => ({
    userId, setUserId, linked, setLinked, screen, go, back, resetOnboarding,
    draft, patchDraft, analysis, setAnalysis, challengeCategory, openChallenge,
  }), [userId, setUserId, linked, setLinked, screen, go, back, resetOnboarding,
       draft, patchDraft, analysis, challengeCategory, openChallenge]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useSession(): Session {
  const v = useContext(Ctx);
  if (!v) throw new Error('useSession must be used within SessionProvider');
  return v;
}
