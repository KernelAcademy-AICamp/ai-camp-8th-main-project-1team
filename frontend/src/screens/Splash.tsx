/**
 * ON-01 스플래시·시작 (IA §5.1). 핵심 가치 한 문장 + 연결 시작.
 * 목업과 동일한 구성이고, 데모 CI가 설정돼 있을 때만 개발용 건너뛰기가 아래에 작게 붙는다.
 */
import { useState } from 'react';
import { Orb, Screen, ErrorBox } from '../components/ui';
import { useSession } from '../state/session';
import { api } from '../lib/api';
import { DEMO_CI, DEMO_ENABLED } from '../lib/config';

export function Splash() {
  const { go, setUserId, setLinked } = useSession();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  /** 개발용 — 로그인·전화번호 인증을 건너뛰고 생성 마이데이터 CI로 바로 연결한다. */
  async function skipWithDemoCi() {
    setBusy(true); setError(null);
    try {
      const companies = await api.mydataCompanies();
      const r = await api.linkSynthetic(DEMO_CI, companies.map((c) => c.id));
      setUserId(r.userId);
      setLinked(true);
      go('loading');
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  }

  return (
    <Screen title="MOA 시작하기" background="linear-gradient(160deg,#EAF2FF,#F2F4F6)">
      <div style={{ flex: 1, display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center', gap: 16, padding: '40px 24px' }}>
        <Orb size={92} bob />
        <div style={{ fontSize: 26, fontWeight: 800, letterSpacing: '-.5px' }}>
          지킴이<span style={{ color: 'var(--t3)', fontWeight: 600, fontSize: 15 }}> · MOA</span>
        </div>
        <p style={{ fontSize: 16, color: 'var(--t2)', textAlign: 'center', lineHeight: 1.5, margin: 0 }}>
          아낄 수 있는 돈을,<br /><b style={{ color: 'var(--blue-t)' }}>지킴이</b>와 함께 지켜봐요.
        </p>
      </div>

      <div className="pad" style={{ paddingBottom: 40 }}>
        <ErrorBox error={error} />
        <button type="button" className="btn btn-primary" disabled={busy} onClick={() => go('auth')}>시작하기</button>
        <p style={{ textAlign: 'center', fontSize: 13, color: 'var(--t3)', margin: '16px 0 0' }}>
          본인인증으로 가입까지 한 번에 · 아이디·비밀번호 없음
        </p>

        {DEMO_ENABLED && (
          <p style={{ textAlign: 'center', margin: '18px 0 0' }}>
            <button type="button" className="btn btn-ghost btn-sm" disabled={busy}
              onClick={() => void skipWithDemoCi()}
              title="개발용 — 본인인증을 건너뛰고 생성 마이데이터로 바로 연결">
              {busy ? '연결 중…' : '🧪 개발용 건너뛰기 (인증 없이 연결)'}
            </button>
          </p>
        )}
      </div>
    </Screen>
  );
}
