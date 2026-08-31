/**
 * 앱 셸 — 화면 넷과 하단 탭.
 *
 * <b>화면·컴포넌트·상태는 본 서비스의 것을 그대로 쓴다.</b> 여기서 하는 일은 45개 중
 * 넷만 등록하는 것뿐이다. 나머지는 등록하지 않으므로 갈 길이 없다.
 *
 * 본 서비스의 `ScreenHost` 에 있던 <b>연결 흐름 강제 이동은 뺐다</b> — 마이데이터 연결이
 * 없으므로 연결 전/후를 가를 것이 없고, 그 분기가 남아 있으면 첫 화면에서 `boot` 로 밀린다.
 */
import type { ComponentType } from 'react';
import { IconSprite } from './components/Icons';
import { BottomTab } from './components/BottomTab';
import { SessionProvider, useSession, type ScreenId } from './state/session';
import { GuardianProvider } from './state/guardian';

import { Home } from './screens/Home';
import { Report } from './screens/Report';
import { Transactions } from './screens/Transactions';
import { MyRecord } from './screens/MyRecord';

const SCREENS: Partial<Record<ScreenId, ComponentType>> = {
  home: Home,
  report: Report,
  transactions: Transactions,
  'm-record': MyRecord,
};

function ScreenHost() {
  const { screen } = useSession();
  const Current = SCREENS[screen] ?? Home;
  return (
    <>
      <Current />
      <BottomTab />
    </>
  );
}

export default function App() {
  return (
    <SessionProvider>
      <GuardianProvider>
        <IconSprite />
        <a href="#screen-title" className="skip-link">본문 바로가기</a>
        <div className="app">
          <ScreenHost />
        </div>
      </GuardianProvider>
    </SessionProvider>
  );
}
