import { useEffect, useRef, useState } from 'react';
import { api, type MyDataCompany } from './api';

/**
 * 마이데이터 온보딩 (§13-6). 진입 필수: 약관 → 가상 본인인증 → 금융사 선택 → 적재.
 * 데모 경로(§13-11 엔드투엔드): 생성 마이데이터(11M)의 SERVICE 사용자 CI를 직접 연결한다 —
 * 생성 CI는 GenSeed 해시라 정상 verify(Ci.of)로 못 맞추므로, 데모에선 CI 주입으로 링크만 재현한다.
 * 숨은 간편 건너뛰기: 시작 화면 로고를 길게 누르면 그 데모 신원으로 전 카드사 자동 연동 후 진입.
 */
const DEMO_CI = (import.meta.env.VITE_DEMO_CI as string | undefined) ?? '';

type Step = 'start' | 'terms' | 'auth' | 'company' | 'loading';

export function Onboarding({ userId, onDone }: { userId: number; onDone: () => void }) {
  const [step, setStep] = useState<Step>('start');
  const [agree, setAgree] = useState(false);
  const [name, setName] = useState('');
  const [social7, setSocial7] = useState('');
  const [phone, setPhone] = useState('');
  const [err, setErr] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [companies, setCompanies] = useState<MyDataCompany[]>([]);
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [demo, setDemo] = useState(false);
  const pressTimer = useRef<number | null>(null);
  const headingRef = useRef<HTMLHeadingElement>(null);

  // 금융사 선택 단계 진입 시 목록 로드
  useEffect(() => {
    if (step === 'company' && companies.length === 0) {
      api.mydataCompanies().then(setCompanies).catch((e) => setErr(String(e)));
    }
  }, [step, companies.length]);

  // 단계 전환 시 새 화면 제목으로 초점 이동 (KWCAG 4.1.3 — 보조기술이 화면 변화를 인지)
  useEffect(() => { headingRef.current?.focus(); }, [step]);

  function finish() {
    try { localStorage.setItem('mydata_onboarded', 'true'); } catch { /* noop */ }
    onDone();
  }

  async function link(companyIds: number[]) {
    setStep('loading'); setErr(null);
    try {
      // 데모: 생성 CI 직접 연결(가상 인증 우회) / 일반: 정상 마이데이터 링크
      if (demo && DEMO_CI) await api.linkSynthetic(DEMO_CI, companyIds);
      else await api.mydataLink(userId, companyIds);
      finish();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
      setStep('company');
    }
  }

  // 숨은 건너뛰기: 로고 롱프레스 → 생성 마이데이터 CI로 전 카드사 자동 연동
  function startSkip() {
    pressTimer.current = window.setTimeout(async () => {
      setBusy(true);
      try {
        const list = await api.mydataCompanies();
        if (DEMO_CI) await api.linkSynthetic(DEMO_CI, list.map((c) => c.id));
        else await api.mydataLink(userId, list.map((c) => c.id));
        finish();
      } catch (e) {
        setErr(e instanceof Error ? e.message : String(e));
        setBusy(false);
      }
    }, 650);
  }
  function cancelSkip() {
    if (pressTimer.current) { clearTimeout(pressTimer.current); pressTimer.current = null; }
  }

  async function verify() {
    setBusy(true); setErr(null);
    try {
      const result = await api.verify(userId, name.trim(), social7.trim(), phone.trim());
      if (!result.existsInMyData) {
        setErr('마이데이터에 없는 신원이에요. 아래 “생성 마이데이터로 연결 (데모)”로 진행해 보세요.');
        setBusy(false);
        return;
      }
      setStep('company');
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    } finally {
      setBusy(false);
    }
  }

  function toggle(id: number) {
    setPicked((prev) => {
      const next = new Set(prev);
      next.has(id) ? next.delete(id) : next.add(id);
      return next;
    });
  }

  return (
    <main className="onb" aria-label="마이데이터 연결 온보딩">
      <div className="onb-card">
        {step !== 'start' && step !== 'loading' && (
          <div className="onb-steps" aria-hidden="true">
            <span className={step === 'terms' ? 'on' : 'done'}>1</span>
            <span className={step === 'auth' ? 'on' : step === 'company' ? 'done' : ''}>2</span>
            <span className={step === 'company' ? 'on' : ''}>3</span>
          </div>
        )}

        {err && <div className="error" role="alert"><code>{err}</code></div>}

        {step === 'start' && (
          <div className="onb-start">
            {/* 로고 = 장식(aria-hidden) + 숨은 테스트용 롱프레스 건너뛰기. 키보드 초점에서 제외해 혼동을 막는다. */}
            <div className="onb-logo"
              onPointerDown={startSkip} onPointerUp={cancelSkip} onPointerLeave={cancelSkip}
              aria-hidden="true">
              <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M3 17l5-5 4 3 6-7" /><path d="M14 8h4v4" />
              </svg>
            </div>
            <h1 ref={headingRef} tabIndex={-1}>소비·저축 어드바이저</h1>
            <p className="muted">마이데이터를 연결하면 카드 사용내역을 한 곳에서 보고,<br />줄인 만큼 모이는 걸 보여드려요.</p>
            <button type="button" className="btn btn-primary onb-cta" disabled={busy}
              onClick={() => setStep('terms')}>마이데이터 연결하고 시작하기</button>
            {busy && <p className="muted small" role="status">간편 진입 중…</p>}
          </div>
        )}

        {step === 'terms' && (
          <div className="onb-body">
            <h1 ref={headingRef} tabIndex={-1} className="onb-h">이용약관 확인</h1>
            <p className="muted small">본 서비스는 학습용 프로토타입이며, 표시되는 금융상품은 모두 더미입니다.
              마이데이터로 불러오는 카드·소비내역은 <b>가상 데이터</b>이고, 본인인증 CI는 실 신용정보가 아닌 <b>가상 생성값</b>입니다.</p>
            <label className="onb-check">
              <input type="checkbox" checked={agree} onChange={(e) => setAgree(e.target.checked)} />
              <span>위 내용에 동의합니다.</span>
            </label>
            <button type="button" className="btn btn-primary onb-cta" disabled={!agree}
              onClick={() => setStep('auth')}>다음</button>
          </div>
        )}

        {step === 'auth' && (
          <div className="onb-body">
            <h1 ref={headingRef} tabIndex={-1} className="onb-h">본인인증</h1>
            <p className="muted small">입력한 신원으로 <b>가상 CI</b>를 만들어 마이데이터에 회원이 있는지 확인해요. (실 SMS 발송 없음)</p>
            <div className="onb-form">
              <label className="field"><span>이름</span>
                <input value={name} onChange={(e) => setName(e.target.value)} placeholder="홍길동" autoComplete="off" /></label>
              <label className="field"><span>주민번호 앞 7자리 (생년월일6+성별1)</span>
                <input value={social7} onChange={(e) => setSocial7(e.target.value)} placeholder="0101073" inputMode="numeric" maxLength={7} /></label>
              <label className="field"><span>휴대폰 번호</span>
                <input value={phone} onChange={(e) => setPhone(e.target.value)} placeholder="01012345678" inputMode="numeric" /></label>
            </div>
            <div className="onb-actions">
              <button type="button" className="btn btn-ghost btn-sm"
                onClick={() => { setDemo(true); setErr(null); setStep('company'); }}>
                생성 마이데이터로 연결 (데모)
              </button>
              <button type="button" className="btn btn-primary onb-cta" disabled={busy || !name || !social7 || !phone}
                onClick={() => void verify()}>{busy ? '확인 중…' : '인증하고 다음'}</button>
            </div>
          </div>
        )}

        {step === 'company' && (
          <div className="onb-body">
            <h1 ref={headingRef} tabIndex={-1} className="onb-h">연결할 카드사를 선택하세요</h1>
            <p className="muted small">선택한 카드사의 카드·소비내역을 마이데이터로 불러옵니다.</p>
            <div className="onb-companies">
              {companies.map((c) => (
                <button type="button" key={c.id} className={`onb-co ${picked.has(c.id) ? 'on' : ''}`}
                  aria-pressed={picked.has(c.id)} onClick={() => toggle(c.id)}>
                  <span className="onb-co-badge" aria-hidden="true">{picked.has(c.id) ? '✓' : '+'}</span>
                  <span className="onb-co-name">{c.name}</span>
                </button>
              ))}
              {companies.length === 0 && <p className="muted small">불러오는 중…</p>}
            </div>
            <button type="button" className="btn btn-primary onb-cta" disabled={picked.size === 0}
              onClick={() => void link([...picked])}>
              {picked.size === 0 ? '카드사를 선택해주세요' : `${picked.size}개 연결하기`}
            </button>
          </div>
        )}

        {step === 'loading' && (
          <div className="onb-start">
            <div className="loading-bar" role="status" aria-label="마이데이터 불러오는 중" />
            <h1 ref={headingRef} tabIndex={-1}>마이데이터를 불러오고 있어요</h1>
            <p className="muted">카드와 소비내역을 정리하는 중… 잠시만요.</p>
          </div>
        )}
      </div>
    </main>
  );
}
