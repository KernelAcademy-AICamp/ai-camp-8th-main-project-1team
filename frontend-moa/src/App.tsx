/**
 * 앱 셸 — 개발 보드(폰 목업) + 화면 레지스트리 + 하단 탭.
 * 완성: 스플래시·본인인증·자산연결·분석로딩·온보딩 4단계·완료·홈.
 * 남은 스텁(다음 턴): 마이룸·리포트·마이·월말 루프(monthend/settle/renew).
 */
import { IconSprite } from './components/Icons'
import { PhoneFrame, AppBar, Scroll } from './components/ui'
import { BottomTab } from './components/BottomTab'
import { Splash } from './screens/Splash'
import { Auth } from './screens/Auth'
import { Connect } from './screens/Connect'
import { Loading } from './screens/Loading'
import { Onboarding1 } from './screens/Onboarding1'
import { Onboarding2 } from './screens/Onboarding2'
import { Onboarding3 } from './screens/Onboarding3'
import { Done } from './screens/Done'
import { Home } from './screens/Home'
import { Myroom } from './screens/Myroom'
import { SessionProvider, useSession, isTab, type ScreenId } from './state/session'
import { mockKeepState } from './lib/mock'

/** 아직 안 만든 화면의 임시 자리(다음 턴에 구현). */
const NEXT: Partial<Record<ScreenId, { title: string; next?: ScreenId; nextLabel?: string }>> = {
  report: { title: '리포트' },
  my: { title: '마이' },
  monthend: { title: '한 달 완료', next: 'settle', nextLabel: '결산' },
  settle: { title: '월간 결산', next: 'renew', nextLabel: '다음 달' },
  renew: { title: '다음 달 정하기', next: 'home', nextLabel: '홈으로' },
}

function Stub({ id }: { id: ScreenId }) {
  const { go, back } = useSession()
  const meta = NEXT[id] ?? { title: id }
  const tab = isTab(id)
  return (
    <section className="screen">
      {!tab && <AppBar onBack={back} title={meta.title} />}
      <Scroll>
        <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 12, minHeight: 420, padding: '0 40px', textAlign: 'center' }}>
          <div style={{ fontSize: 18, fontWeight: 700 }}>{meta.title}</div>
          <p style={{ color: 'var(--t3)', fontSize: 14, lineHeight: 1.5, margin: 0 }}>
            이 화면은 다음 단계에서 구현돼요.<br />지금은 흐름 확인용 자리예요.
          </p>
        </div>
      </Scroll>
      {meta.next && (
        <div className="cta-fixed">
          <button className="btn btn-primary" onClick={() => go(meta.next!)}>다음: {meta.nextLabel}</button>
        </div>
      )}
    </section>
  )
}

function ScreenHost() {
  const { screen } = useSession()
  const render = () => {
    switch (screen) {
      case 'splash': return <Splash />
      case 'auth': return <Auth />
      case 'connect': return <Connect />
      case 'loading': return <Loading />
      case 'ob1': return <Onboarding1 />
      case 'ob2': return <Onboarding2 />
      case 'ob3': return <Onboarding3 />
      case 'done': return <Done />
      case 'home': return <Home />
      case 'myroom': return <Myroom />
      default: return <Stub id={screen} />
    }
  }
  return (
    <PhoneFrame>
      {render()}
      {isTab(screen) && <BottomTab />}
    </PhoneFrame>
  )
}

function DevBoard() {
  const { reset, go, setKeep } = useSession()
  // 데모: 홈 — 온보딩으로 시드된 값과 무관하게 항상 데모 상태(스트릭 6·340P)로 리셋해 보여준다.
  const demoHome = () => { setKeep(mockKeepState()); go('home') }
  return (
    <div className="board-head">
      <h1>MOA · 지킴이 프론트 <span style={{ fontSize: 12, color: '#9aa4b2', fontWeight: 600 }}>세로 슬라이스 (frontend-moa)</span></h1>
      <p>최초 1회(본인인증·동의·연결) → 매달(분석 → 줄일 지출 → 강도 → 홈) · 클릭해서 넘겨보세요</p>
      <div className="devbtns">
        <button onClick={reset}>↻ 최초 온보딩</button>
        <button onClick={() => go('loading')}>📅 이번 달 온보딩</button>
        <button onClick={demoHome}>🏠 데모: 홈</button>
        <button onClick={() => go('monthend')}>🎉 데모: 월말</button>
      </div>
    </div>
  )
}

export function App() {
  return (
    <SessionProvider>
      <IconSprite />
      <div className="board">
        <DevBoard />
        <ScreenHost />
      </div>
    </SessionProvider>
  )
}
