import { useEffect, useState } from 'react';
import { api, type CategoryView, type PrivacyPolicy, type UserView } from './api';

/**
 * `<input type="datetime-local">`이 기대하는 **로컬 벽시계** 문자열(YYYY-MM-DDTHH:mm)을 만든다.
 * `toISOString()`은 UTC를 내보내므로 KST에서 9시간 어긋난 값이 서버로 간다.
 */
function toLocalInputValue(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    + `T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/**
 * 소비내역 수동 입력 + 동의 플로우 + 개인정보 처리방침 (문서 §5-3).
 *
 * 화면이 방침 문안을 하드코딩하지 않고 `/api/privacy/policy`를 읽는다 —
 * 방침을 고칠 때 문서·백엔드·화면이 따로 노는 것을 막기 위함이다.
 */
export function ConsumptionPanel({ userId, onChanged }: {
  userId: number;
  onChanged: () => void;
}) {
  const [user, setUser] = useState<UserView | null>(null);
  const [policy, setPolicy] = useState<PrivacyPolicy | null>(null);
  const [categories, setCategories] = useState<CategoryView[]>([]);
  const [showPolicy, setShowPolicy] = useState(false);
  const [msg, setMsg] = useState<string | null>(null);
  const [err, setErr] = useState<string | null>(null);

  const [categoryCode, setCategoryCode] = useState('');
  const [amount, setAmount] = useState('');
  // toISOString()은 UTC라 datetime-local(=로컬 벽시계)에 그대로 넣으면 KST 기준 9시간 어긋난다.
  // 그 값이 서버의 LocalDateTime으로 저장되므로 심야 룰·월 집계가 통째로 밀린다.
  const [occurredAt, setOccurredAt] = useState(() => toLocalInputValue(new Date()));
  const [planned, setPlanned] = useState(true);

  useEffect(() => {
    void (async () => {
      try {
        const [u, p, c] = await Promise.all([
          api.getUser(userId), api.privacyPolicy(), api.categories(),
        ]);
        setUser(u); setPolicy(p); setCategories(c);
        if (c.length > 0) setCategoryCode(c[0].code);
      } catch (e) {
        setErr(e instanceof Error ? e.message : String(e));
      }
    })();
  }, [userId]);

  async function toggleConsent(next: boolean) {
    setErr(null); setMsg(null);
    try {
      const updated = await api.setConsent(userId, next);
      setUser(updated);
      void api.track(next ? 'consent_granted' : 'consent_withdrawn', userId);
      setMsg(next
        ? '동의하셨습니다. 이제 소비내역을 기록할 수 있어요.'
        : '동의를 철회했습니다. 기록하신 소비내역은 즉시 파기되었습니다.');
      onChanged();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setErr(null); setMsg(null);
    try {
      await api.addConsumption({
        userId,
        categoryCode,
        amount: Number(amount),
        occurredAt: occurredAt.length === 16 ? `${occurredAt}:00` : occurredAt,
        planned,
      });
      void api.track('consumption_added', userId, { categoryCode, planned });
      setMsg('기록했습니다.');
      setAmount('');
      onChanged();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }

  async function eraseAll() {
    setErr(null); setMsg(null);
    try {
      const r = await api.eraseMyData(userId);
      void api.track('data_erased', userId, { deletedCount: r.deletedCount });
      setMsg(`${r.deletedCount}건을 삭제했습니다. 삭제 사실이 감사로그에 기록되었습니다.`);
      onChanged();
    } catch (e) {
      setErr(e instanceof Error ? e.message : String(e));
    }
  }

  const consented = user?.consentGiven ?? false;

  return (
    <section className="section card card-pad">
      <div className="section-head" style={{ marginBottom: 6 }}>
        <h2>소비내역 기록</h2>
        <span className="hint small">동의 시에만 저장됩니다</span>
      </div>
      <p className="muted small" style={{ marginTop: 0, marginBottom: 14 }}>
        수집 항목은 <strong>카테고리 · 금액 · 날짜 · 계획소비 여부</strong> 4개뿐이에요.
        실명 · 계좌번호 · 카드번호 입력란은 아예 없습니다.
      </p>

      {err && <div className="error"><code>{err}</code></div>}
      {msg && <div className="notice-ok">{msg}</div>}

      {!consented ? (
        <div className="consent-box">
          <p>
            소비내역을 기록하려면 개인정보 수집·이용에 동의해야 합니다.
            <strong> 동의하지 않아도</strong> 예시 데이터 기반 데모 모드로 모든 기능을 볼 수 있어요.
          </p>
          <div style={{ display: 'flex', gap: 10, justifyContent: 'center', flexWrap: 'wrap' }}>
            <button className="btn btn-primary" onClick={() => void toggleConsent(true)}>동의하고 기록 시작</button>
            <button className="btn btn-ghost" onClick={() => setShowPolicy((v) => !v)}>
              처리방침 {showPolicy ? '접기' : '전문 보기'}
            </button>
          </div>
        </div>
      ) : (
        <>
          <form className="form-grid" onSubmit={(e) => void submit(e)}>
            <div className="field">
              <label htmlFor="cat">카테고리</label>
              <select id="cat" value={categoryCode} onChange={(e) => setCategoryCode(e.target.value)}>
                {categories.map((c) => <option key={c.code} value={c.code}>{c.displayName}</option>)}
              </select>
            </div>
            <div className="field">
              <label htmlFor="amt">금액(원)</label>
              <input id="amt" type="number" min={1} required value={amount}
                onChange={(e) => setAmount(e.target.value)} placeholder="15000" />
            </div>
            <div className="field">
              <label htmlFor="when">일시</label>
              <input id="when" type="datetime-local" required value={occurredAt}
                onChange={(e) => setOccurredAt(e.target.value)} />
            </div>
            <div className="field check">
              <input id="planned" type="checkbox" checked={planned} onChange={(e) => setPlanned(e.target.checked)} />
              <label htmlFor="planned">계획한 소비</label>
            </div>
            <button className="btn btn-primary" type="submit">기록</button>
          </form>

          <div className="rights">
            <span className="r-label">정보주체 권리</span>
            <button className="btn btn-ghost btn-sm" onClick={() => void eraseAll()}>내 기록 전부 삭제</button>
            <button className="btn btn-ghost btn-sm" onClick={() => void toggleConsent(false)}>동의 철회</button>
            <button className="btn btn-ghost btn-sm" onClick={() => setShowPolicy((v) => !v)}>
              처리방침 {showPolicy ? '접기' : '보기'}
            </button>
          </div>
        </>
      )}

      {showPolicy && policy && (
        <div className="policy">
          <h3>{policy.title}</h3>
          {policy.clauses.map((c) => (
            <div key={c.title} className="clause">
              <strong>{c.title}</strong>
              <p>{c.body}</p>
            </div>
          ))}
          <p className="muted tiny">{policy.notice}</p>
        </div>
      )}
    </section>
  );
}
