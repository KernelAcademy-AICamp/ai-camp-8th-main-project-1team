/**
 * 마이 &gt; 데모 패널 (IA DM-01) — 시연·개발 전용. `VITE_DEMO_CI`가 있을 때만 마이 탭에 노출된다.
 *
 * 여기 모은 것들은 전부 실제 백엔드 기능이다:
 *  · 사람 교체 연결 — 생성 마이데이터(11M)의 다른 사람 CI로 갈아끼워 페르소나별 차이를 본다(§13-11)
 *  · 하루 넘기기 — 지킴이 가상 시계를 밀고 새벽 배치를 돌린다. 30일 챌린지를 5분에 시연한다(설계서 §7)
 *  · 소비 주입 — 마이데이터 위에 사용자 입력을 얹어 엔진이 반응하는지 확인한다
 */
import { useState } from 'react';
import { AppBar, Scroll, Screen, ErrorBox, SectionTitle } from '../components/ui';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { api } from '../lib/api';
import { DEMO_CI } from '../lib/config';
import { DEMO_USERS } from '../lib/demoUsers';
import { toLocalInputValue, won, CHALLENGE_STATE_LABEL, DAILY_RESULT_LABEL } from '../lib/format';

const PERSONA_ORDER = ['절약형', '균형형', '과소비형', '구독과다형', '외식형'];
// 우리 소비 중분류 16개. 백엔드 대조표(industry-mid.json)와 같은 목록이어야 한다.
const DEV_CATS = ['건강/피트니스', '교통/자동차', '금융/보험', '대형마트', '미용', '생활', '쇼핑', '술/유흥', '식비', '여행/숙박', '의료', '주거/통신', '취미/여가', '카테고리없음', '카페/간식', '편의점/잡화'];

export function MyDemo() {
  const { back, userId, setUserId, setLinked } = useSession();
  const { home, reload, setHome } = useGuardian();

  const [selectedCi, setSelectedCi] = useState<string>(() => {
    try { return localStorage.getItem('demo_ci') || DEMO_CI; } catch { return DEMO_CI; }
  });
  const [busy, setBusy] = useState<string | null>(null);
  const [msg, setMsg] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);

  const [devCat, setDevCat] = useState('식비');
  const [devAmt, setDevAmt] = useState('');
  const [devWhen, setDevWhen] = useState(() => toLocalInputValue(new Date()));

  async function linkCi(ci: string) {
    if (!ci) return;
    setBusy('link'); setError(null); setMsg(null);
    try {
      // 빈 배열이면 백엔드가 모든 카드사를 연결한다(생성 카드는 7개 실카드사에 분산, §13-11).
      const r = await api.linkSynthetic(ci, []);
      setSelectedCi(ci);
      try { localStorage.setItem('demo_ci', ci); } catch { /* noop */ }
      setLinked(true);
      setUserId(r.userId);
      setMsg(`카드 ${r.cardCount}장 · 결제 ${r.paymentCount.toLocaleString('ko-KR')}건 연결 (사용자 ${r.userId})`);
      await reload();
    } catch (e) { setError(e); } finally { setBusy(null); }
  }

  function randomSwitch() {
    if (busy || DEMO_USERS.length === 0) return;
    const u = DEMO_USERS[Math.floor(Math.random() * DEMO_USERS.length)];
    void linkCi(u.ci);
  }

  async function advance(days: number) {
    setBusy('advance'); setError(null); setMsg(null);
    try {
      const r = await api.guardian.advance(userId, days);
      setHome(r.home);
      const verdicts = r.batches.map((b) => DAILY_RESULT_LABEL[b.verdict.result] ?? b.verdict.result);
      const granted = r.batches.filter((b) => b.grantedObject).length;
      setMsg(`${days}일 이동 · 판정 ${verdicts.join(', ')} · 사물 ${granted}개 지급`);
    } catch (e) { setError(e); } finally { setBusy(null); }
  }

  async function runDaily() {
    setBusy('daily'); setError(null); setMsg(null);
    try {
      const r = await api.guardian.runDaily(userId);
      setMsg(`오늘 판정: ${DAILY_RESULT_LABEL[r.verdict.result] ?? r.verdict.result}`
        + (r.grantedObject ? ` · ${r.grantedObject.grade} 사물 지급` : '')
        + (r.stateTransition ? ` · 상태 ${r.stateTransition}` : ''));
      await reload();
    } catch (e) { setError(e); } finally { setBusy(null); }
  }

  async function addConsumption() {
    const amt = Math.round(Number(devAmt));
    if (!amt || amt <= 0) { setMsg('금액을 입력하세요'); return; }
    setBusy('spend'); setError(null); setMsg(null);
    try {
      await api.addConsumption({
        userId, categoryCode: devCat, amount: amt,
        occurredAt: devWhen.length === 16 ? `${devWhen}:00` : devWhen, planned: false,
      });
      setDevAmt('');
      await api.guardian.sync(userId).catch(() => undefined);
      await reload();
      setMsg(`${devCat} ${won(amt)} 추가 — 지킴이 원장·점수·리포트에 반영됐어요`);
    } catch (e) { setError(e); } finally { setBusy(null); }
  }

  async function syncAll() {
    setBusy('sync'); setError(null); setMsg(null);
    try {
      const md = await api.syncMyData(userId).catch(() => ({ newPayments: 0 }));
      const g = await api.guardian.sync(userId).catch(() => ({ added: 0 }));
      setMsg(`마이데이터 새 결제 ${md.newPayments}건 · 지킴이 원장 적재 ${g.added}건`);
      await reload();
    } catch (e) { setError(e); } finally { setBusy(null); }
  }

  const ch = home?.challenge;

  return (
    <Screen title="데모 패널" hasTabBar>
      <AppBar onBack={back} title="데모 패널" />
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>
        <p className="h-sub" style={{ margin: '0 0 12px' }}>
          시연·개발 전용 화면이에요. 실제 사용자 화면에는 노출되지 않아요.
        </p>

        <ErrorBox error={error} />
        {msg && <p className="notice-ok" role="status">{msg}</p>}

        {/* 현재 상태 */}
        <SectionTitle aux={home?.demoMode ? '가상 시계 켜짐' : '실제 시계'}>지금 상태</SectionTitle>
        <div className="card">
          <div className="trow"><span className="k">앱 사용자</span><span className="v num">{userId}</span></div>
          <div className="trow"><span className="k">서버 기준 시각</span><span className="v num">{home?.asOf?.replace('T', ' ').slice(0, 16) ?? '—'}</span></div>
          <div className="trow"><span className="k">챌린지</span>
            <span className="v">{ch ? `${CHALLENGE_STATE_LABEL[ch.state] ?? ch.state} · ${ch.categoryLabel}` : '없음'}</span></div>
          <div className="trow" style={{ border: 'none' }}><span className="k">지키는 중</span>
            <span className="v num">{ch ? `${won(ch.securedSaving)} / ${won(ch.targetSaving)}` : '—'}</span></div>
        </div>

        {/* 지킴이 가상 시계 */}
        <SectionTitle aux="30일 챌린지를 5분에">가상 시계</SectionTitle>
        <div className="card">
          <p className="empty" style={{ marginTop: 0 }}>
            시계를 밀면 새벽 배치가 즉시 돌아 일 판정·사물 지급·잔디가 갱신돼요.
          </p>
          <div className="form-inline">
            <button type="button" className="btn btn-primary btn-sm" disabled={busy !== null || !ch}
              onClick={() => void advance(1)}>📅 하루 넘기기</button>
            <button type="button" className="btn btn-ghost btn-sm" disabled={busy !== null || !ch}
              onClick={() => void advance(7)}>일주일 넘기기</button>
            <button type="button" className="btn btn-ghost btn-sm" disabled={busy !== null || !ch}
              onClick={() => void runDaily()}>오늘 배치만 실행</button>
          </div>
          {!ch && <p className="empty">진행 중인 챌린지가 있어야 시계를 밀 수 있어요.</p>}
        </div>

        {/* 사람 교체 연결 */}
        <SectionTitle aux={`${DEMO_USERS.length}명`}>사람 교체 연결</SectionTitle>
        <div className="card">
          <p className="empty" style={{ marginTop: 0 }}>
            페르소나마다 소비 성향이 달라 리포트·ML 판정이 어떻게 달라지는지 볼 수 있어요.
          </p>
          <label className="form-row">
            <span>연결할 생성 마이데이터 사용자</span>
            <select className="inp" value={selectedCi} disabled={busy !== null}
              onChange={(e) => void linkCi(e.target.value)}>
              {PERSONA_ORDER.map((persona) => {
                const users = DEMO_USERS.filter((u) => u.persona === persona);
                return users.length ? (
                  <optgroup key={persona} label={persona}>
                    {users.map((u) => (
                      <option key={u.ci} value={u.ci}>{u.name} · 결제 {u.visible}건</option>
                    ))}
                  </optgroup>
                ) : null;
              })}
            </select>
          </label>
          <div className="form-inline">
            <button type="button" className="btn btn-ghost btn-sm" disabled={busy !== null} onClick={randomSwitch}>
              🎲 랜덤 전환
            </button>
            <button type="button" className="btn btn-ghost btn-sm" disabled={busy !== null}
              onClick={() => void linkCi(selectedCi)}>
              {busy === 'link' ? '연결 중…' : '다시 연결'}
            </button>
            <button type="button" className="btn btn-ghost btn-sm" disabled={busy !== null}
              onClick={() => void syncAll()}>
              {busy === 'sync' ? '동기화 중…' : '전체 동기화'}
            </button>
          </div>
        </div>

        {/* 소비 주입 */}
        <SectionTitle aux="엔진 반응 확인">소비 수동 추가</SectionTitle>
        <div className="card">
          <div className="form-inline">
            <select className="inp" style={{ width: 130 }} value={devCat}
              onChange={(e) => setDevCat(e.target.value)} aria-label="소비 카테고리">
              {DEV_CATS.map((c) => <option key={c} value={c}>{c}</option>)}
            </select>
            <input className="inp" style={{ width: 120 }} type="number" inputMode="numeric" min={0}
              value={devAmt} onChange={(e) => setDevAmt(e.target.value)} placeholder="금액(원)" aria-label="금액" />
            <input className="inp" style={{ flex: 1, minWidth: 190 }} type="datetime-local" value={devWhen}
              onChange={(e) => setDevWhen(e.target.value)} aria-label="반영 시점" />
            <button type="button" className="btn btn-primary btn-sm" disabled={busy !== null}
              onClick={() => void addConsumption()}>추가 후 재계산</button>
          </div>
          <p className="empty">
            미래 시점으로 두면 그 시점 이후에 반영되는 소비를 테스트할 수 있어요.
          </p>
        </div>

        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
