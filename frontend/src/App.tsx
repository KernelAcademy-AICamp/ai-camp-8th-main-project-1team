/**
 * 앱 셸 — 화면 레지스트리 + 진입 분기 + 하단 탭.
 *
 * 목업(frontend-moa)의 개발 보드(폰 목업 + 검정 버튼 4개)는 걷어냈다. 어느 화면을 처음 보여줄지는
 * 이제 버튼이 아니라 **상태**가 정한다:
 *   연결 안 함        → 최초 온보딩(스플래시부터)
 *   연결됨·챌린지 없음 → 이번 챌린지 정하기(ob1~ob3)  ← IA의 '월초 인터럽트'와 같은 자리
 *   연결됨·챌린지 있음 → 홈
 */
import { useEffect, type ComponentType } from 'react';
import { IconSprite } from './components/Icons';
import { BottomTab } from './components/BottomTab';
import { SessionProvider, useSession, tabOf, type ScreenId } from './state/session';
import { GuardianProvider } from './state/guardian';

import { Splash } from './screens/Splash';
import { Auth } from './screens/Auth';
import { Connect } from './screens/Connect';
import { Loading } from './screens/Loading';
import { Onboarding1 } from './screens/Onboarding1';
import { Onboarding2 } from './screens/Onboarding2';
import { Onboarding3 } from './screens/Onboarding3';
import { Done } from './screens/Done';
import { Home } from './screens/Home';
import { Myroom } from './screens/Myroom';
import { Notifications } from './screens/Notifications';
import { Transactions } from './screens/Transactions';
import { Report } from './screens/Report';
import { ReportSpending } from './screens/ReportSpending';
import { ReportAnalysis } from './screens/ReportAnalysis';
import { ReportCards } from './screens/ReportCards';
import { ReportWaste } from './screens/ReportWaste';
import { ReportSavings } from './screens/ReportSavings';
import { My } from './screens/My';
import { MyImpulse } from './screens/MyImpulse';
import { MyGoals } from './screens/MyGoals';
import { MyConnections } from './screens/MyConnections';
import { MyRecord } from './screens/MyRecord';
import { MyPolicy } from './screens/MyPolicy';
import { MySurvey } from './screens/MySurvey';
import { MyDemo } from './screens/MyDemo';

/** 최초 온보딩(마이데이터 연결 전)에만 열리는 화면. */
const LINK_FLOW: ScreenId[] = ['splash', 'auth', 'connect'];
/** 이번 챌린지를 정하는 흐름 — 이 화면들에서는 하단 탭을 감춘다(중간에 빠져나가면 흐름이 끊긴다). */
const SETUP_FLOW: ScreenId[] = ['loading', 'ob1', 'ob2', 'ob3', 'done'];

const SCREENS: Record<ScreenId, ComponentType> = {
  splash: Splash, auth: Auth, connect: Connect, loading: Loading,
  ob1: Onboarding1, ob2: Onboarding2, ob3: Onboarding3, done: Done,
  home: Home, report: Report, my: My,
  myroom: Myroom, notifications: Notifications, transactions: Transactions,
  'r-spending': ReportSpending, 'r-analysis': ReportAnalysis, 'r-cards': ReportCards,
  'r-waste': ReportWaste, 'r-savings': ReportSavings,
  'm-impulse': MyImpulse, 'm-goals': MyGoals, 'm-connections': MyConnections,
  'm-record': MyRecord, 'm-policy': MyPolicy, 'm-survey': MySurvey, 'm-demo': MyDemo,
};

function ScreenHost() {
  const { screen, linked, go } = useSession();

  /**
   * 강제 이동은 딱 하나뿐이다 — 마이데이터 연결 전에는 연결 흐름 밖으로 못 나가고, 연결 뒤에는 홈으로 온다.
   * 챌린지가 없을 때 온보딩으로 **밀어내지 않는** 이유: 지킴이 API가 없거나 실패해도 리포트·마이는 멀쩡히
   * 동작해야 하고, 챌린지 정하기는 홈의 시작 카드에서 사용자가 눌러서 들어가는 편이 빠져나오기도 쉽다.
   */
  useEffect(() => {
    if (!linked) {
      if (!LINK_FLOW.includes(screen)) go('splash');
    } else if (LINK_FLOW.includes(screen)) {
      go('home');
    }
  }, [linked, screen, go]);

  const Current = SCREENS[screen] ?? Home;
  const tab = tabOf(screen);
  const showTab = linked && tab !== null && !SETUP_FLOW.includes(screen);

  return (
    <>
      <Current />
      {showTab && <BottomTab />}
    </>
  );
}

export default function App() {
  return (
    <SessionProvider>
      <GuardianProvider>
        <IconSprite />
        <a href="#main" className="skip-link">본문 바로가기</a>
        <div className="app">
          <ScreenHost />
        </div>
      </GuardianProvider>
    </SessionProvider>
  );
}
