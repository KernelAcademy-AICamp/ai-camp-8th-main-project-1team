/**
 * 앱 셸 — 화면 레지스트리 + 진입 분기 + 하단 탭.
 *
 * 초기 목업 앱(2026-07 폐기)의 개발 보드(폰 목업 + 검정 버튼 4개)는 걷어냈다. 어느 화면을 처음 보여줄지는
 * 이제 버튼이 아니라 **상태**가 정한다:
 *   연결 안 함        → 최초 온보딩(스플래시부터)
 *   연결됨·챌린지 없음 → 이번 챌린지 정하기(ob1~ob4)  ← IA의 '월초 인터럽트'와 같은 자리
 *   연결됨·챌린지 있음 → 홈
 */
import { useEffect, type ComponentType } from 'react';
import { IconSprite } from './components/Icons';
import { BottomTab } from './components/BottomTab';
import { SessionProvider, useSession, tabOf, historyDepth, type ScreenId } from './state/session';
import { GuardianProvider } from './state/guardian';

import { Boot } from './screens/Boot';
import { Walk } from './screens/Walk';
import { Auth } from './screens/Auth';
import { Connect } from './screens/Connect';
import { Loading } from './screens/Loading';
import { Onboarding } from './screens/Onboarding';
import { Done } from './screens/Done';
import { Home } from './screens/Home';
import { Myroom } from './screens/Myroom';
import { Collection } from './screens/Collection';
import { Shop } from './screens/Shop';
import { BudgetOver } from './screens/BudgetOver';
import { WeeklyWrap } from './screens/WeeklyWrap';
import { NoData } from './screens/NoData';
import { Settle } from './screens/Settle';
import { Renew } from './screens/Renew';
import { Notifications } from './screens/Notifications';
import { Transactions } from './screens/Transactions';
import { Report } from './screens/Report';
import { ReportSpending } from './screens/ReportSpending';
import { ReportAnalysis } from './screens/ReportAnalysis';
import { ReportAccount } from './screens/ReportAccount';
import { ReportCards } from './screens/ReportCards';
import { Compare } from './screens/Compare';
import { MyProducts } from './screens/MyProducts';
import { MySanctuary } from './screens/MySanctuary';
import { MyChallenge } from './screens/MyChallenge';
import { MyChallengeNew } from './screens/MyChallengeNew';
import { MyVoice } from './screens/MyVoice';
import { ReportRank } from './screens/ReportRank';
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
import { MyStances } from './screens/MyStances';
import { MyUnclassified } from './screens/MyUnclassified';
import { MyParked } from './screens/MyParked';

/** 최초 온보딩(마이데이터 연결 전)에만 열리는 화면. */
const LINK_FLOW: ScreenId[] = ['boot', 'walk', 'auth', 'connect'];
/** 이번 챌린지를 정하는 흐름 — 이 화면들에서는 하단 탭을 감춘다(중간에 빠져나가면 흐름이 끊긴다). */
const SETUP_FLOW: ScreenId[] = ['loading', 'ob', 'done',
  // 월말 사이클도 같은 성격의 흐름이다 — 축하→결산→갱신을 중간에 끊으면 다음 달 목표가 안 정해진다.
  'settle', 'renew',
  // 0818 예외 화면도 같은 성격이다 — 흐름 도중에 탭으로 새면 하던 일이 끊긴다.
  'over', 'weekly', 'nodata'];

const SCREENS: Record<ScreenId, ComponentType> = {
  boot: Boot, walk: Walk, auth: Auth, connect: Connect, loading: Loading,
  ob: Onboarding, done: Done,
  home: Home, report: Report, my: My,
  myroom: Myroom, notifications: Notifications, transactions: Transactions,
  collection: Collection, shop: Shop,
  settle: Settle, renew: Renew,
  over: BudgetOver, weekly: WeeklyWrap, nodata: NoData,
  'r-spending': ReportSpending, 'r-analysis': ReportAnalysis, 'r-cards': ReportCards,
  'r-compare': Compare,
  'm-products': MyProducts,
  'm-sanctuary': MySanctuary,
  'm-challenge': MyChallenge,
  'm-challenge-new': MyChallengeNew,
  'm-voice': MyVoice,
  'r-account': ReportAccount,
  'r-rank': ReportRank,
  'r-waste': ReportWaste, 'r-savings': ReportSavings,
  'm-impulse': MyImpulse, 'm-goals': MyGoals, 'm-connections': MyConnections,
  'm-record': MyRecord, 'm-policy': MyPolicy, 'm-survey': MySurvey, 'm-demo': MyDemo,
  'm-stances': MyStances,
  'm-unclassified': MyUnclassified,
  'm-parked': MyParked,
};

function ScreenHost() {
  const { screen, linked, replace } = useSession();

  /**
   * 강제 이동은 딱 하나뿐이다 — 마이데이터 연결 전에는 연결 흐름 밖으로 못 나가고, 연결 뒤에는 홈으로 온다.
   * 챌린지가 없을 때 온보딩으로 **밀어내지 않는** 이유: 지킴이 API가 없거나 실패해도 리포트·마이는 멀쩡히
   * 동작해야 하고, 챌린지 정하기는 홈의 시작 카드에서 사용자가 눌러서 들어가는 편이 빠져나오기도 쉽다.
   *
   * **`go`가 아니라 `replace`다.** 사용자가 누른 이동이 아니므로 이력에 새 칸을 만들면 안 된다.
   * 온보딩을 마친 사람의 이력에는 `connect` 같은 연결 흐름 화면이 그대로 남아 있어서, 뒤로
   * 누르면 여기로 pop 해 오고 이 effect 가 즉시 `#/home`을 **밀어 넣어** 방금 밟고 온 칸을
   * 파괴했다. 그때부터 뒤로를 아무리 눌러도 두 칸을 오갈 뿐 못 빠져나갔다 —
   * 앱에서는 강제종료 말고 방법이 없는 상태다(2026-08-20 재현, `scripts/check-back-nav.mjs`).
   * `replace`면 도착한 칸을 덮어쓰기만 하므로 뒤로 한 번에 한 칸씩 줄어 결국 앱을 벗어난다.
   *
   * **연결 뒤에는 아예 한 칸 더 물러난다.** 덮어쓰기만 하면 갇히지는 않지만, 연결 흐름 칸이
   * 셋(walk·auth·connect) 남아 있어 <b>뒤로를 세 번 누르는 동안 홈이 그대로</b>다 —
   * 사용자 눈에는 여전히 안 먹는 버튼이다. 이 칸들은 연결을 마친 사람에게 갈 곳이 없으므로
   * 지나쳐 준다. 칸마다 깊이가 줄어들고 맨 아래 칸(깊이 0, 주소로 바로 들어온 자리)은
   * 홈으로 덮어쓰므로 **반드시 멈춘다** — 무한 루프가 아니다.
   */
  useEffect(() => {
    if (!linked) {
      if (!LINK_FLOW.includes(screen)) replace('boot');
    } else if (LINK_FLOW.includes(screen)) {
      if (historyDepth() > 0) window.history.back();
      else replace('home');
    }
  }, [linked, screen, replace]);

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
        {/* 도착지는 화면 제목이다. `main` 요소의 id 는 프로토타입의 화면 id(`#s-report` 등)가
            쓰므로 — 그것이 없으면 디자인 규칙 310줄이 안 걸린다(`components/ui.tsx` Screen). */}
        <a href="#screen-title" className="skip-link">본문 바로가기</a>
        <div className="app">
          <ScreenHost />
        </div>
      </GuardianProvider>
    </SessionProvider>
  );
}
