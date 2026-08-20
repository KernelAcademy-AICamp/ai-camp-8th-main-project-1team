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
 *
 * <h2>화면은 쉽게 입력할수잇게 만든다</h2>
 *
 * 제목·설명 → 왼쪽 폼 / 오른쪽 안내 → 라벨 위 칸 아래 → 전체 너비 확인 버튼.
 * 처음 오는 사람이 <b>무엇을 넣어야 하는지 헷갈리지 않는 것</b>이 이 화면의 값이다 —
 * 실제로 첫 사용자가 "인증번호를 아직 등록도 안 했는데 뭘 넣나"에서 막혔다(2026-08-12).
 */
import { useCallback, useEffect, useState } from 'react';
import { UsageStats } from './UsageStats';

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
const when = (iso: string | null) => (iso ? iso.replace('T', ' ').slice(0, 16) : '—');

/** 비밀번호 칸 — 12자 이상을 오타 없이 넣으려면 확인할 길이 있어야 한다. */
function PasswordField({ label, hint, value, autoComplete, onChange }: {
  label: string; hint?: string; value: string;
  autoComplete: 'current-password' | 'new-password'; onChange: (v: string) => void;
}) {
  const [shown, setShown] = useState(false);
  return (
    <div className="field">
      <label>{label}{hint && <span className="hint">{hint}</span>}</label>
      <div className="with-toggle">
        <input type={shown ? 'text' : 'password'} value={value} autoComplete={autoComplete}
          placeholder="비밀번호를 입력하세요." onChange={(e) => onChange(e.target.value)} />
        <button type="button" onClick={() => setShown((s) => !s)}
          aria-label={shown ? '비밀번호 가리기' : '비밀번호 보기'}>
          {shown ? '가리기' : '보기'}
        </button>
      </div>
    </div>
  );
}

export function AdminApp() {
  const [me, setMe] = useState<Me | null>(null);
  const [checked, setChecked] = useState(false);
  /**
   * 로그인 화면으로 돌아갈 때 전할 말.
   *
   * 비밀번호를 바꾸면 **세션이 끊겨** 로그인 화면으로 돌아간다(바꾼 이유가 유출이라면 옛 세션이
   * 살아 있는 것이 곧 구멍이다). 그런데 아무 설명이 없으면 **바꾸기가 실패한 것처럼 보인다**.
   */
  const [notice, setNotice] = useState<string | null>(null);

  const refreshMe = useCallback(async () => {
    try { setMe(await call<Me>('/me')); } catch { setMe(null); } finally { setChecked(true); }
  }, []);

  useEffect(() => { void refreshMe(); }, [refreshMe]);

  if (!checked) return <main className="narrow"><p className="muted">확인 중…</p></main>;
  if (!me) return <Login notice={notice} onDone={() => { setNotice(null); void refreshMe(); }} />;
  if (!me.ready) return <Setup me={me} onDone={(m) => { setNotice(m ?? null); void refreshMe(); }} />;
  return <Queue me={me} onLogout={() => void refreshMe()} />;
}

/**
 * 로그인 — 비밀번호와 인증번호를 <b>한 화면에서</b> 받는다.
 * 단계를 나누면 "비밀번호는 맞았다"를 알려주는 셈이다.
 */
function Login({ notice, onDone }: { notice: string | null; onDone: () => void }) {
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
    <main className="narrow">
      <div className="page-title">
        <h1>MOA 운영 로그인</h1>
        <p>실사용자 명세서 신청을 검토하고 승인하는 화면입니다.</p>
      </div>

      <div className="cols">
        <form onSubmit={submit}>
          {notice && <p className="notice ok" role="status">{notice}</p>}

          <div className="field">
            <label htmlFor="adm-id">계정</label>
            <input id="adm-id" type="text" value={username} autoComplete="username"
              placeholder="계정을 입력하세요." onChange={(e) => setUsername(e.target.value)} />
          </div>

          <PasswordField label="비밀번호" value={password}
            autoComplete="current-password" onChange={setPassword} />

          {/*
            **등록 전에는 비워 둔다.** 서버가 2차 인증을 건너뛴다 — 등록도 안 했는데 코드를
            요구하면 첫 로그인 자체가 불가능해지기 때문이다. 그런데 화면이 그 사실을 말해주지
            않아 첫 사용자가 무엇을 넣어야 할지 몰라 막혔다(2026-08-12 운영).
          */}
          <div className="field">
            <label htmlFor="adm-code">인증번호 <span className="hint">앱의 6자리 또는 복구 코드</span></label>
            <input id="adm-code" type="text" value={code} autoComplete="one-time-code"
              placeholder="아직 등록 전이면 비워 두세요."
              onChange={(e) => setCode(e.target.value)} />
            <p className="help">2단계 인증을 아직 등록하지 않았다면 <b>비워 두고</b> 로그인하세요.</p>
          </div>

          {error && <p className="notice error" role="alert">{error}</p>}

          {/* 계정·비밀번호가 비면 못 누르게 — 자동완성이 한쪽만 채우는 일이 흔하다. */}
          <button type="submit" className="primary" disabled={busy || !username || !password}>
            {busy ? '확인 중…' : '로그인'}
          </button>
        </form>

        <aside className="guide">
          <h2>안내</h2>
          <ul>
            <li>이 화면은 <b>운영자 전용</b>입니다. 서비스 화면에는 이곳으로 가는 링크가 없습니다.</li>
            <li>처음 로그인하면 <b>비밀번호를 바꾸고 2단계 인증을 등록</b>해야 승인을 할 수 있습니다.</li>
            <li>등록을 마치기 전에는 인증번호 칸을 비워 두세요.</li>
            <li>비밀번호를 여러 번 틀리면 <b>응답이 점점 느려집니다.</b> 계정이 잠기지는 않습니다.</li>
            <li>자리를 비울 때는 반드시 로그아웃하세요. 세션은 30분 뒤 저절로 끊깁니다.</li>
          </ul>
        </aside>
      </div>
    </main>
  );
}

/** 첫 로그인 — 비밀번호를 바꾸고 2단계 인증을 등록해야 승인할 수 있다. */
function Setup({ me, onDone }: { me: Me; onDone: (notice?: string) => void }) {
  const [current, setCurrent] = useState('');
  const [next, setNext] = useState('');
  const [totp, setTotp] = useState<{ secret: string; uri: string } | null>(null);
  const [code, setCode] = useState('');
  const [recovery, setRecovery] = useState<string[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function changePassword() {
    setBusy(true); setError(null);
    try {
      await call('/password', {
        method: 'POST',
        body: JSON.stringify({ currentPassword: current, newPassword: next }),
      });
      // 비밀번호를 바꾸면 **세션이 끊긴다**(바꾼 이유가 유출이라면 옛 세션이 살아 있는 것이
      // 곧 구멍이다). 아무 말 없이 로그인 화면으로 돌아가면 실패로 보이므로 문구를 들고 간다.
      onDone('비밀번호를 바꿨어요. 새 비밀번호로 다시 로그인해 주세요.');
    } catch (e) {
      setError(e instanceof Error ? e.message : '바꾸지 못했어요.');
    } finally { setBusy(false); }
  }

  async function beginTotp() {
    setBusy(true); setError(null);
    try { setTotp(await call('/totp/begin', { method: 'POST' })); }
    catch (e) { setError(e instanceof Error ? e.message : '시작하지 못했어요.'); }
    finally { setBusy(false); }
  }

  async function confirmTotp() {
    setBusy(true); setError(null);
    try {
      const result = await call<{ recoveryCodes: string[] }>('/totp/confirm', {
        method: 'POST', body: JSON.stringify({ code }),
      });
      setRecovery(result.recoveryCodes);
    } catch (e) { setError(e instanceof Error ? e.message : '확인하지 못했어요.'); }
    finally { setBusy(false); }
  }

  if (recovery) {
    return (
      <main className="narrow">
        <div className="page-title">
          <h1>복구 코드</h1>
          <p>폰을 잃어버렸을 때 들어올 수 있는 유일한 길입니다.</p>
        </div>
        <div className="cols">
          <div>
            <p className="notice warn">
              <b>이 코드는 다시 볼 수 없습니다.</b> 지금 종이에 적어 보관하세요.
            </p>
            <ul className="codes">{recovery.map((c) => <li key={c}>{c}</li>)}</ul>
            {/* onClick 을 그대로 넘기면 클릭 이벤트 객체가 문구 자리에 들어간다. 감싸서 끊는다. */}
            <button type="button" className="primary" onClick={() => onDone()}>적어 뒀습니다</button>
          </div>
          <aside className="guide">
            <h2>안내</h2>
            <ul>
              <li>코드 하나는 <b>한 번만</b> 쓸 수 있습니다. 쓰면 사라집니다.</li>
              <li>인증 앱을 못 쓰게 됐을 때 로그인 화면의 <b>인증번호 칸에 이 코드를</b> 넣으세요.</li>
              <li>화면 캡처보다 종이가 낫습니다 — 캡처는 클라우드로 새어 나갑니다.</li>
            </ul>
          </aside>
        </div>
      </main>
    );
  }

  const step = me.mustChangePassword ? 1 : 2;
  return (
    <main className="narrow">
      <div className="page-title">
        <h1>처음 설정 — {me.username}</h1>
        <p>비밀번호를 바꾸고 2단계 인증을 등록해야 승인을 할 수 있습니다. ({step}/2 단계)</p>
      </div>

      <div className="cols">
        <div>
          {me.mustChangePassword && (
            <section className="section">
              <h2>1. 비밀번호 바꾸기</h2>
              <p className="sub">발급받은 임시 비밀번호는 이 단계에서만 씁니다.</p>
              {/*
                **autoComplete 를 명시한다.** 없으면 브라우저 비밀번호 관리자가 두 칸을 임의로
                채운다 — '지금 비밀번호' 자리에 엉뚱한 저장값이 들어가면 사람은 점(●)만 보고
                맞게 넣은 줄 안다. 그 상태로 누르면 400 이 나는데 화면에는 사유가 안 떴다.
              */}
              <PasswordField label="지금 비밀번호" value={current}
                autoComplete="current-password" onChange={setCurrent} />
              <PasswordField label="새 비밀번호" hint="12자 이상" value={next}
                autoComplete="new-password" onChange={setNext} />
              {next.length > 0 && next.length < 12 && (
                <p className="notice warn">새 비밀번호가 {12 - next.length}자 더 필요합니다.</p>
              )}
              {error && <p className="notice error" role="alert">{error}</p>}
              {/* 빈 칸으로는 아예 못 누르게 — 눌러 보고 나서야 알게 하지 않는다. */}
              <button type="button" className="primary" disabled={busy || !current || next.length < 12}
                onClick={() => void changePassword()}>
                {busy ? '바꾸는 중…' : '비밀번호 바꾸기'}
              </button>
            </section>
          )}

          {!me.mustChangePassword && !me.totpEnrolled && (
            <section className="section">
              <h2>2. 2단계 인증 등록</h2>
              <p className="sub">
                비밀번호가 새어도 폰이 없으면 못 들어옵니다. 이것이 유일한 2차 방어입니다.
              </p>
              {!totp && (
                <>
                  {error && <p className="notice error" role="alert">{error}</p>}
                  <button type="button" className="primary" disabled={busy}
                    onClick={() => void beginTotp()}>등록 시작</button>
                </>
              )}
              {totp && (
                <>
                  <div className="field">
                    <span className="label">① 인증 앱에 아래 값을 넣으세요</span>
                    <p className="secret">{totp.secret}</p>
                    <p className="help">
                      Google Authenticator 등에서 <b>직접 입력(수동 입력)</b>을 고르고 붙여 넣습니다.
                    </p>
                  </div>
                  <div className="field">
                    <label htmlFor="totp-code">② 앱에 뜬 6자리를 넣으세요</label>
                    <input id="totp-code" type="text" value={code} inputMode="numeric"
                      autoComplete="one-time-code" maxLength={6} placeholder="000000"
                      onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />
                    <p className="help warn">
                      확인까지 마쳐야 등록됩니다 — 지금 화면을 닫으면 처음부터 다시 해야 합니다.
                    </p>
                  </div>
                  {error && <p className="notice error" role="alert">{error}</p>}
                  {/* 6자리가 아니면 못 누르게 — 서버까지 갔다 오게 할 이유가 없다. */}
                  <button type="button" className="primary" disabled={busy || code.length !== 6}
                    onClick={() => void confirmTotp()}>
                    {busy ? '확인 중…' : '등록 확인'}
                  </button>
                </>
              )}
            </section>
          )}
        </div>

        <aside className="guide">
          <h2>안내</h2>
          {me.mustChangePassword ? (
            <ul>
              <li>새 비밀번호는 <b>12자 이상</b>이어야 합니다.</li>
              <li>바꾸면 <b>모든 세션이 끊깁니다.</b> 새 비밀번호로 다시 로그인하세요.</li>
              <li>임시 비밀번호는 그 즉시 못 쓰게 됩니다.</li>
              <li>「보기」를 눌러 오타를 확인할 수 있습니다.</li>
            </ul>
          ) : (
            <ul>
              <li>인증 앱은 서버와 <b>통신하지 않습니다.</b> 같은 비밀과 시각으로 각자 계산합니다.</li>
              <li>그래서 폰이 <b>비행기모드여도</b> 코드가 나옵니다.</li>
              <li>비밀 값은 <b>이 화면에서만</b> 볼 수 있습니다. 등록을 마치면 다시 못 봅니다.</li>
              <li>확인이 끝나면 <b>복구 코드 8개</b>가 나옵니다 — 종이에 적어 두세요.</li>
            </ul>
          )}
        </aside>
      </div>
    </main>
  );
}

/** 대기 목록 — 요약만 보고 승인하거나 반려한다. */
/**
 * 관리 화면의 본체 — 탭 둘.
 *
 * 신청 대기가 먼저다. 그쪽은 <b>사람이 기다리는 일</b>이고 통계는 언제 봐도 되는 일이라,
 * 열었을 때 보이는 것이 처리할 일이라야 한다.
 */
function Queue({ me, onLogout }: { me: Me; onLogout: () => void }) {
  const [tab, setTab] = useState<'intake' | 'usage' | 'purge'>('intake');
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

  // 통계 탭에서는 대기 목록을 다시 묻지 않는다 — 안 보이는 것을 새로 고칠 이유가 없다.
  useEffect(() => { if (tab === 'intake') void load(); }, [load, tab]);

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
      setMessage('반려했습니다.');
      await load();
    } catch (e) { setError(e instanceof Error ? e.message : '반려하지 못했어요.'); }
    finally { setBusy(null); }
  }

  return (
    <main>
      <header className="bar">
        <b>{tab === 'intake' ? `대기 중 ${items.length}건`
          : tab === 'usage' ? '이용 통계' : '이용자 파기'}</b>
        <span>
          {me.username}
          <button type="button" className="link" onClick={async () => {
            await call('/logout', { method: 'POST' }); onLogout();
          }}>로그아웃</button>
        </span>
      </header>

      <nav className="tabs">
        <button type="button" aria-current={tab === 'intake' ? 'page' : undefined}
          onClick={() => setTab('intake')}>신청 대기</button>
        <button type="button" aria-current={tab === 'usage' ? 'page' : undefined}
          onClick={() => setTab('usage')}>이용 통계</button>
        <button type="button" aria-current={tab === 'purge' ? 'page' : undefined}
          onClick={() => setTab('purge')}>이용자 파기</button>
      </nav>

      {tab === 'usage' && <UsageStats call={call} />}
      {tab === 'purge' && <Purge />}

      {tab === 'intake' && <>
      {message && <p className="notice ok" role="status">{message}</p>}
      {error && <p className="notice error" role="alert">{error}</p>}
      {items.length === 0 && <p className="muted">대기 중인 신청이 없습니다.</p>}

      {items.map((item) => (
        <article className="card" key={item.id}>
          <h2>#{item.id} {item.maskedName} <span className="muted small">{item.ticket}</span></h2>
          <dl className="summary">
            <div><dt>접수</dt><dd>{when(item.submittedAt)} · {item.submittedIp}</dd></div>
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
            <div><dt>만료</dt><dd>{when(item.expiresAt)}</dd></div>
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
      </>}
    </main>
  );
}

/**
 * 이용자 파기 — <b>CI 64자를 통째로 넣어야만</b> 그 한 사람을 지운다.
 *
 * <p><b>왜 검색창이 없나.</b> 이름·전화로 찾게 하면 지우는 일 때문에 관리자가 개인식별정보를
 * 보게 된다 — 앞뒤가 바뀐 것이다. CI 는 되돌릴 수 없는 해시라 그 자체로는 누구인지 말해 주지
 * 않고, 목록도 부분일치도 없으니 <b>이미 그 값을 아는 사람만</b> 지울 수 있다. 신청 목록이
 * 이름을 {@code 홍○동} 으로 마스킹하는 것과 같은 태도다.
 *
 * <p>되돌릴 수 없는 일이라 확인 문구를 손으로 치게 한다. 붙여넣기로 지나가는 것을 조금이라도
 * 늦추려는 것이다.
 */
function Purge() {
  const [ci, setCi] = useState('');
  const [confirm, setConfirm] = useState('');
  const [result, setResult] = useState<Record<string, unknown> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // 화면에서도 형식을 본다. 서버가 다시 보지만(그쪽이 권위다), 64자가 아닌 것을
  // 굳이 보내 놓고 오류를 받을 이유가 없다.
  const normalized = ci.trim().toLowerCase();
  const ciLooksRight = /^[0-9a-f]{64}$/.test(normalized);
  const ready = ciLooksRight && confirm === CONFIRM_PHRASE;

  async function submit() {
    setBusy(true); setError(null); setResult(null);
    try {
      setResult(await call<Record<string, unknown>>('/users/purge', {
        method: 'POST',
        body: JSON.stringify({ ci: normalized, confirm }),
      }));
      setCi(''); setConfirm('');
    } catch (e) {
      setError(e instanceof Error ? e.message : '파기하지 못했어요.');
    } finally { setBusy(false); }
  }

  return (
    <>
      <article className="card">
        <h2>이용자 파기</h2>
        <p className="muted small">
          그 사람의 것을 <b>본체와 제공자 양쪽에서</b> 지웁니다 — 소비·리포트·지킴이·행태와
          제공자의 신원·카드·결제까지. <b>되돌릴 수 없습니다.</b>
        </p>

        <label htmlFor="purge-ci">CI (64자)</label>
        <input id="purge-ci" value={ci} onChange={(e) => setCi(e.target.value)}
          spellCheck={false} autoComplete="off" placeholder="64자를 그대로 붙여 넣으세요"
          aria-describedby="purge-ci-help" />
        <p id="purge-ci-help" className="muted small">
          {ci.trim() === '' ? `현재 ${normalized.length} / 64자`
            : ciLooksRight ? '형식이 맞습니다.'
            : `${normalized.length} / 64자 — 영문 소문자와 숫자만 들어갑니다.`}
        </p>

        <label htmlFor="purge-confirm">확인</label>
        <input id="purge-confirm" value={confirm} onChange={(e) => setConfirm(e.target.value)}
          autoComplete="off" placeholder={CONFIRM_PHRASE} />
        <p className="muted small">확인란에 <b>{CONFIRM_PHRASE}</b> 라고 적어야 진행합니다.</p>

        <div className="actions">
          <button type="button" className="primary" disabled={!ready || busy}
            onClick={() => void submit()}>
            {busy ? '지우는 중…' : '파기'}
          </button>
        </div>
      </article>

      {error && <p className="notice error" role="alert">{error}</p>}
      {result && <PurgeReport result={result} />}
    </>
  );
}

/** 서버가 정한 확인 문구. 바꾸려면 {@code AdminUserPurgeController.CONFIRM} 과 함께 바꾼다. */
const CONFIRM_PHRASE = '파기합니다';

/**
 * 무엇이 몇 건 지워졌는지 그대로 보인다.
 *
 * <p><b>"완료"만 띄우지 않는다.</b> 0건이 지워진 것과 1,042건이 지워진 것이 화면에서 같아 보이면,
 * 잘못된 CI 를 넣어 아무것도 안 지운 날에도 다 됐다고 믿게 된다.
 */
function PurgeReport({ result }: { result: Record<string, unknown> }) {
  const provider = result.provider as Record<string, unknown> | string | undefined;
  const found = result.found === true;
  return (
    <article className="card" role="status">
      <h2>{found ? '파기했습니다' : '그 CI 로는 아무것도 없었습니다'}</h2>
      <dl className="summary">
        <div><dt>CI</dt><dd>{String(result.ci ?? '')}</dd></div>
        <div><dt>본체 계정</dt><dd>{String(result.appUser ?? '없음')}</dd></div>
        <div><dt>본체 소비</dt><dd>{Number(result.erasedConsumptions ?? 0).toLocaleString('ko-KR')}건</dd></div>
        {typeof provider === 'object' && provider !== null ? (
          <>
            <div><dt>제공자 신원</dt><dd>{provider.found ? '지움' : '없음'}</dd></div>
            <div><dt>제공자 결제</dt><dd>{Number(provider.payments ?? 0).toLocaleString('ko-KR')}건</dd></div>
            <div><dt>제공자 카드</dt><dd>{Number(provider.cards ?? 0)}장</dd></div>
            <div><dt>제공자 계좌</dt><dd>{Number(provider.accounts ?? 0)}개 · 입출금 {Number(provider.accountTxns ?? 0).toLocaleString('ko-KR')}건</dd></div>
          </>
        ) : (
          <div><dt>제공자</dt><dd>{String(provider ?? '응답 없음')}</dd></div>
        )}
        <div><dt>처리자</dt><dd>{String(result.decidedBy ?? '')}</dd></div>
      </dl>
      {!found && (
        <p className="muted small">
          CI 를 다시 확인해 주세요 — 64자가 하나라도 다르면 다른 사람입니다.
        </p>
      )}
    </article>
  );
}
