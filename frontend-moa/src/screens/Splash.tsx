/** ON-01 스플래시·시작 (IA §5.1). 핵심 가치 한 문장 + 연결 시작. */
import { Orb } from '../components/ui'
import { useSession } from '../state/session'

export function Splash() {
  const { go } = useSession()
  return (
    <section className="screen" style={{ background: 'linear-gradient(160deg,#EAF2FF,#F2F4F6)' }}>
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 16 }}>
        <Orb size={92} bob />
        <div style={{ fontSize: 26, fontWeight: 800, letterSpacing: '-.5px' }}>
          지킴이<span style={{ color: 'var(--t3)', fontWeight: 600, fontSize: 15 }}> · MOA</span>
        </div>
        <div style={{ fontSize: 16, color: 'var(--t2)', textAlign: 'center', lineHeight: 1.5 }}>
          아낄 수 있는 돈을,<br /><b style={{ color: 'var(--blue)' }}>지킴이</b>와 함께 지켜봐요.
        </div>
      </div>
      <div style={{ padding: '0 24px 40px' }}>
        <button className="btn btn-primary" onClick={() => go('auth')}>시작하기</button>
        <p style={{ textAlign: 'center', fontSize: 13, color: 'var(--t3)', marginTop: 16 }}>
          본인인증으로 가입까지 한 번에 · 아이디·비밀번호 없음
        </p>
      </div>
    </section>
  )
}
