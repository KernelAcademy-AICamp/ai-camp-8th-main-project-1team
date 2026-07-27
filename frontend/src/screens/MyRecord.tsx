/**
 * 마이 &gt; 소비 기록과 동의 (§5-3) — 직접 기록 + 동의 플로우 + 정보주체 권리(열람·삭제·철회).
 * 수집 항목은 카테고리·금액·일시·계획 여부 넷뿐이다. 실명·계좌번호·카드번호 입력란은 아예 없다.
 */
import { useState } from 'react';
import { AppBar, Scroll, Screen, ErrorBox, Loading, SectionTitle } from '../components/ui';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, type UserView } from '../lib/api';
import { toLocalInputValue } from '../lib/format';

export function MyRecord() {
  const { back, userId, go } = useSession();
  const user = useAsync(() => api.getUser(userId), [userId]);
  const cats = useAsync(() => api.categories().catch(() => []), [userId]);

  const [categoryCode, setCategoryCode] = useState('');
  const [amount, setAmount] = useState('');
  // toISOString()은 UTC라 datetime-local(로컬 벽시계)에 그대로 넣으면 KST 기준 9시간 어긋난다.
  const [occurredAt, setOccurredAt] = useState(() => toLocalInputValue(new Date()));
  const [planned, setPlanned] = useState(true);
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);

  const consented = user.data?.consentGiven ?? false;
  const options = cats.data ?? [];
  const currentCat = categoryCode || options[0]?.code || '';

  async function toggleConsent(next: boolean) {
    setError(null); setMsg(null);
    try {
      const updated: UserView = await api.setConsent(userId, next);
      user.set(updated);
      void api.track(next ? 'consent_granted' : 'consent_withdrawn', userId);
      setMsg(next
        ? '동의하셨습니다. 이제 소비내역을 기록할 수 있어요.'
        : '동의를 철회했습니다. 기록하신 소비내역은 즉시 파기되었습니다.');
    } catch (e) { setError(e); }
  }

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null); setMsg(null);
    try {
      await api.addConsumption({
        userId,
        categoryCode: currentCat,
        amount: Number(amount),
        occurredAt: occurredAt.length === 16 ? `${occurredAt}:00` : occurredAt,
        planned,
      });
      void api.track('consumption_added', userId, { categoryCode: currentCat, planned });
      setMsg('기록했습니다.');
      setAmount('');
    } catch (e) { setError(e); }
  }

  async function eraseAll() {
    setError(null); setMsg(null);
    try {
      const r = await api.eraseMyData(userId);
      void api.track('data_erased', userId, { deletedCount: r.deletedCount });
      setMsg(`${r.deletedCount}건을 삭제했습니다. 삭제 사실이 감사로그에 기록되었습니다.`);
    } catch (e) { setError(e); }
  }

  async function exportAll() {
    setError(null); setMsg(null);
    try {
      const r = await api.exportMyData(userId);
      setMsg(`내 기록 ${r.recordCount.toLocaleString('ko-KR')}건을 조회했어요.`);
    } catch (e) { setError(e); }
  }

  return (
    <Screen title="소비 기록과 동의" hasTabBar>
      <AppBar onBack={back} title="소비 기록과 동의" />
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>
        <p className="h-sub" style={{ margin: '0 0 12px' }}>
          수집 항목은 <b>카테고리 · 금액 · 일시 · 계획 여부</b> 넷뿐이에요.
          실명·계좌번호·카드번호 입력란은 아예 없습니다.
        </p>

        <ErrorBox error={user.error ?? error} onRetry={user.reload} />
        {msg && <p className="notice-ok" role="status">{msg}</p>}
        {user.loading && <Loading label="동의 상태를 불러오는 중" rows={3} />}

        {user.data && !consented && (
          <div className="card">
            <p style={{ margin: '0 0 12px', fontSize: 14.5, lineHeight: 1.6 }}>
              소비내역을 직접 기록하려면 개인정보 수집·이용에 동의해야 합니다.
              <b> 동의하지 않아도</b> 마이데이터로 불러온 분석과 지킴 기능은 그대로 쓸 수 있어요.
            </p>
            <div style={{ display: 'flex', gap: 8 }}>
              <button type="button" className="btn btn-primary btn-sm" style={{ flex: 1 }}
                onClick={() => void toggleConsent(true)}>동의하고 기록 시작</button>
              <button type="button" className="btn btn-ghost btn-sm" style={{ flex: 1 }}
                onClick={() => go('m-policy')}>처리방침 보기</button>
            </div>
          </div>
        )}

        {consented && (
          <>
            <SectionTitle>직접 기록하기</SectionTitle>
            <form className="card" onSubmit={(e) => void submit(e)}>
              <label className="form-row">
                <span>카테고리</span>
                <select className="inp" value={currentCat} onChange={(e) => setCategoryCode(e.target.value)}>
                  {options.map((c) => <option key={c.code} value={c.code}>{c.displayName}</option>)}
                </select>
              </label>
              <label className="form-row">
                <span>금액(원)</span>
                <input className="inp" type="number" min={1} required value={amount}
                  onChange={(e) => setAmount(e.target.value)} placeholder="15000" />
              </label>
              <label className="form-row">
                <span>일시</span>
                <input className="inp" type="datetime-local" required value={occurredAt}
                  onChange={(e) => setOccurredAt(e.target.value)} />
              </label>
              <div className="seg" role="group" aria-label="소비 성격">
                <button type="button" className={planned ? 'on' : ''} aria-pressed={planned}
                  onClick={() => setPlanned(true)}>계획한 소비</button>
                <button type="button" className={!planned ? 'on' : ''} aria-pressed={!planned}
                  onClick={() => setPlanned(false)}>계획에 없던 소비</button>
              </div>
              <button className="btn btn-primary" type="submit" style={{ marginTop: 12 }}>기록</button>
            </form>

            <SectionTitle>정보주체 권리</SectionTitle>
            <div className="card">
              <p className="empty" style={{ marginTop: 0 }}>
                언제든 열람·삭제·철회할 수 있어요. 삭제 사실은 감사로그에 남습니다.
              </p>
              <div className="form-inline">
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => void exportAll()}>내 기록 열람</button>
                <button type="button" className="btn btn-danger btn-sm" onClick={() => void eraseAll()}>내 기록 전부 삭제</button>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => void toggleConsent(false)}>동의 철회</button>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => go('m-policy')}>처리방침</button>
              </div>
            </div>
          </>
        )}

        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
