/**
 * admin 관리 화면 (설계서 Phase 3).
 *
 * <h2>접근은 URL 직접 입력만</h2>
 *
 * 사용자 앱 어디에도 이 화면으로 가는 링크·버튼·매직 문자열이 없다. 별도 번들(`admin.html`)이라
 * 사용자에게 배포되는 JS 에 이 코드도 경로도 들어가지 않는다.
 * <b>다만 경로 숨김은 방어가 아니라 소음 감소다</b> — API 는 어차피 열려 있고, 방어는
 * Argon2id · TOTP · IP 지연 · HttpOnly 쿠키 · 감사가 진다.
 *
 * <h2>요약만 본다</h2>
 *
 * 건별 목록도, 원문을 여는 길도 없다. 승인이 판정하는 것은 "이 배치가 정상적인 명세서인가"지
 * "이 사람이 무엇을 샀는가"가 아니다. 이름도 마스킹된 채로 온다.
 */
import { useCallback, useEffect, useState } from 'react';

const API_BASE: string = (import.meta.env.VITE_API_BASE as string | undefined) ?? '';

/** admin 토큰은 HttpOnly 쿠키다 — JS 가 못 읽는다. 그래서 `credentials`만 실어 보낸다. */
async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${API_BASE}/api/admin${path}`, {
    ...init,
    credentials: 'include',
    headers: init?.body ? { 'Content-Type': 'application/json' } : undefined,
  });
  const text = await res.text();
  const body = text ? JSON.parse(text) : null;
  if (!res.ok) throw new Error(body?.message ?? `요청 실패 (${res.status})`);
  return body as T;
}

interface Me { username: string; mustChangePassword: boolean; totpEnrolled: boolean; ready: boolean }

interface Intake {
  id: number; ticket: string; maskedName: string; submittedAt: string; submittedIp: string;
  expiresAt: string; cardCount: number; rowCount: number; rejectedRowCount: number;
  totalAmount: number; refundCount: number; refundAmount: number;
  withBusinessNumber: number; distinctMerchants: number;
  periodFrom: string | null; periodTo: string | null;
}

const won = (value: number) => `${value.toLocaleString('ko-KR')}원`;

export function AdminApp() {
  const [me, setMe] = useState<Me | null>(null);
  const [checked, setChecked] = useState(false);

  const refreshMe = useCallback(async () => {
    try { setMe(await call<Me>('/me')); } catch { setMe(null); } finally { setChecked(true); }
  }, []);

  useEffect(() => { void refreshMe(); }, [refreshMe]);

  if (!checked) return <main className="admin"><p>확인 중…</p></main>;
  if (!me) return <main className="admin"><Login onDone={() => void refreshMe()} /></main>;
  if (!me.ready) return <main className="admin"><Setup me={me} onDone={() => void refreshMe()} /></main>;
  return <main className="admin"><Queue me={me} onLogout={() => void refreshMe()} /></main>;
}

/**
 * 로그인 — 비밀번호와 인증번호를 <b>한 화면에서</b> 받는다.
 * 단계를 나누면 "비밀번호는 맞았다"를 알려주는 셈이다.
 */
function Login({ onDone }: { onDone: () => void }) {
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    setBusy(true); setError(null);
    try {
      await call('/login', { method: 'POST', body: JSON.stringify({ username, password, code }) });
      onDone();
    } catch (e) {
      // 서버가 무엇이 틀렸든 같은 문구를 준다 — 계정 열거를 막는다.
      setError(e instanceof Error ? e.message : '로그인할 수 없습니다.');
    } finally {
      setBusy(false);
    }
  }

  return (
    <form className="card login" onSubmit={submit}>
      <h1>MOA 운영</h1>
      <label>계정<input value={username} onChange={(e) => setUsername(e.target.value)} autoComplete="username" /></label>
      <label>비밀번호<input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" /></label>
      <label>인증번호 <span className="muted">앱의 6자리 또는 복구 코드</span>
        <input value={code} onChange={(e) => setCode(e.target.value)} autoComplete="one-time-code" />
      </label>
      {error && <p className="error" role="alert">{error}</p>}
      <button type="submit" className="primary" disabled={busy}>{busy ? '확인 중…' : '로그인'}</button>
    </form>
  );
}

/** 첫 로그인 — 비밀번호를 바꾸고 2단계 인증을 등록해야 승인할 수 있다. */
function Setup({ me, onDone }: { me: Me; onDone: () => void }) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [totp, setTotp] = useState<{ secret: string; uri: string } | null>(null);
  const [code, setCode] = useState('');
  const [recovery, setRecovery] = useState<string[] | null>(null);
  const [error, setError] = useState<string | null>(null);

  async function changePassword() {
    setError(null);
    try {
      await call('/password', {
        method: 'POST',
        body: JSON.stringify({ currentPassword: current, newPassword: next }),
      });
      // 비밀번호를 바꾸면 세션이 끊긴다 — 다시 로그인해야 한다.
      onDone();
    } catch (e) { setError(e instanceof Error ? e.message : '바꾸지 못했어요.'); }
  }

  async function beginTotp() {
    setError(null);
    try { setTotp(await call('/totp/begin', { method: 'POST' })); }
    catch (e) { setError(e instanceof Error ? e.message : '시작하지 못했어요.'); }
  }

  async function confirmTotp() {
    setError(null);
    try {
      const result = await call<{ recoveryCodes: string[] }>('/totp/confirm', {
        method: 'POST', body: JSON.stringify({ code }),
      });
      setRecovery(result.recoveryCodes);
    } catch (e) { setError(e instanceof Error ? e.message : '확인하지 못했어요.'); }
  }

  if (recovery) {
    return (
      <div className="card">
        <h1>복구 코드</h1>
        <p className="warn">
          <b>이 코드는 다시 볼 수 없어요.</b> 종이에 적어 보관하세요.
          폰을 잃어버렸을 때 들어올 수 있는 유일한 길이에요.
        </p>
        <ul className="codes">{recovery.map((c) => <li key={c}>{c}</li>)}</ul>
        <button type="button" className="primary" onClick={onDone}>적어 뒀어요</button>
      </div>
    );
  }

  return (
    <div className="card">
      <h1>{me.username} — 처음 설정</h1>
      {me.mustChangePassword && (
        <section>
          <h2>1. 비밀번호 바꾸기</h2>
          <label>지금 비밀번호<input type="password" value={current} onChange={(e) => setCurrent(e.target.value)} /></label>
          <label>새 비밀번호 <span className="muted">12자 이상</span>
            <input type="password" value={next} onChange={(e) => setNext(e.target.value)} />
          </label>
          <button type="button" onClick={() => void changePassword()}>바꾸기</button>
        </section>
      )}
      {!me.mustChangePassword && !me.totpEnrolled && (
        <section>
          <h2>2. 2단계 인증 등록</h2>
          {!totp && <button type="button" onClick={() => void beginTotp()}>시작</button>}
          {totp && (
            <>
              <p className="muted">인증 앱(Google Authenticator 등)에 아래 값을 넣으세요.</p>
              <p className="secret">{totp.secret}</p>
              <p className="muted small">{totp.uri}</p>
              <label>지금 뜬 6자리<input value={code} onChange={(e) => setCode(e.target.value)} /></label>
              <button type="button" className="primary" onClick={() => void confirmTotp()}>확인</button>
            </>
          )}
        </section>
      )}
      {error && <p className="error" role="alert">{error}</p>}
    </div>
  );
}

/** 대기 목록 — 요약만 보고 승인하거나 반려한다. */
function Queue({ me, onLogout }: { me: Me; onLogout: () => void }) {
  const [items, setItems] = useState<Intake[]>([]);
  const [reasons, setReasons] = useState<{ code: string; label: string }[]>([]);
  const [message, setMessage] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<number | null>(null);

  const load = useCallback(async () => {
    try {
      setItems(await call<Intake[]>('/intake'));
      setReasons(await call('/intake/reject-reasons'));
    } catch (e) { setError(e instanceof Error ? e.message : '불러오지 못했어요.'); }
  }, []);

  useEffect(() => { void load(); }, [load]);

  async function approve(id: number) {
    setBusy(id); setError(null); setMessage(null);
    try {
      const result = await call<Record<string, unknown>>(`/intake/${id}/approve`, { method: 'POST' });
      setMessage(`반영 완료 — 신규 ${result.accepted}건 · 거부 ${result.rejected}건. ${result.notice ?? ''}`);
      await load();
    } catch (e) { setError(e instanceof Error ? e.message : '승인하지 못했어요.'); }
    finally { setBusy(null); }
  }

  async function reject(id: number, reason: string) {
    if (!reason) return;
    setBusy(id); setError(null); setMessage(null);
    try {
      await call(`/intake/${id}/reject`, { method: 'POST', body: JSON.stringify({ reason }) });
      setMessage('반려했어요.');
      await load();
    } catch (e) { setError(e instanceof Error ? e.message : '반려하지 못했어요.'); }
    finally { setBusy(null); }
  }

  return (
    <>
      <header className="bar">
        <b>대기 중 {items.length}건</b>
        <span>
          {me.username}
          <button type="button" className="link" onClick={async () => {
            await call('/logout', { method: 'POST' }); onLogout();
          }}>로그아웃</button>
        </span>
      </header>

      {message && <p className="ok" role="status">{message}</p>}
      {error && <p className="error" role="alert">{error}</p>}
      {items.length === 0 && <p className="muted">대기 중인 신청이 없어요.</p>}

      {items.map((item) => (
        <article className="card" key={item.id}>
          <h2>#{item.id} {item.maskedName} <span className="muted">{item.ticket}</span></h2>
          <dl className="summary">
            <div><dt>접수</dt><dd>{item.submittedAt?.replace('T', ' ').slice(0, 16)} · {item.submittedIp}</dd></div>
            <div><dt>카드</dt><dd>{item.cardCount}장</dd></div>
            <div><dt>결제</dt><dd>{item.rowCount.toLocaleString('ko-KR')}건 · {item.periodFrom} ~ {item.periodTo}</dd></div>
            <div><dt>합계</dt><dd>{won(item.totalAmount)}</dd></div>
            <div><dt>취소·환불</dt><dd>{item.refundCount}건 {won(item.refundAmount)}</dd></div>
            <div>
              <dt>사업자번호</dt>
              <dd>
                {item.withBusinessNumber.toLocaleString('ko-KR')}건
                ({item.rowCount ? Math.round((item.withBusinessNumber / item.rowCount) * 100) : 0}%)
                · 고유 {item.distinctMerchants}곳
              </dd>
            </div>
            <div><dt>못 읽은 줄</dt><dd>{item.rejectedRowCount}건</dd></div>
            <div><dt>만료</dt><dd>{item.expiresAt?.replace('T', ' ').slice(0, 16)}</dd></div>
          </dl>
          <div className="actions">
            <button type="button" className="primary" disabled={busy === item.id}
              onClick={() => void approve(item.id)}>승인</button>
            <select disabled={busy === item.id} defaultValue=""
              onChange={(e) => void reject(item.id, e.target.value)}>
              <option value="">반려 사유 고르기…</option>
              {reasons.map((reason) => (
                <option key={reason.code} value={reason.code}>{reason.label}</option>
              ))}
            </select>
          </div>
        </article>
      ))}
    </>
  );
}
