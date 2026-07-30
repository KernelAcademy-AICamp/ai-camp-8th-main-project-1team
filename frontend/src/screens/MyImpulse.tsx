/**
 * 마이 &gt; 충동예산 절약통 (§5-5) — 수동 '살 뻔했다'를 대체한 자동 성장 모델.
 * ① 카드내역에서 충동 카테고리를 고르면 그 월 평균이 예산이 된다.
 * ② 들어올 때마다 시간에 따라 저절로 자란다. ③ 충동소비를 기록하면 균열이 간다.
 * ④ 다음달 카드내역(CSV)을 올리면 정말 줄었는지 재검증한다.
 */
import { useState } from 'react';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty, SectionTitle } from '../components/ui';
import { GiftBox } from '../components/GiftBox';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, catLabel, type ImpulseSnapshot } from '../lib/api';
import { won } from '../lib/format';

export function MyImpulse() {
  const { back, userId } = useSession();
  const snap = useAsync(() => api.impulse(userId), [userId]);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [actionKey, setActionKey] = useState(0);
  const [error, setError] = useState<unknown>(null);
  const [csv, setCsv] = useState('');
  const [uploading, setUploading] = useState(false);

  async function run(p: Promise<ImpulseSnapshot>) {
    setError(null);
    try {
      const s = await p;
      snap.set(s);
      setActionKey((k) => k + 1);
      if (s.lastAction === 'UNNECESSARY') setFeedback('💥 충동소비를 기록했어요 — 절약통에 금이 갔어요');
      else if (s.uploaded > 0) setFeedback(`📥 카드내역 ${s.uploaded}건을 반영했어요 — 아래 재검증 결과를 확인하세요`);
      else setFeedback(null);
      return s;
    } catch (e) { setError(e); return null; }
  }

  function toggleCategory(code: string) {
    const s = snap.data;
    if (!s) return;
    const cur = new Set(s.impulseCategories);
    if (cur.has(code)) cur.delete(code); else cur.add(code);
    void run(api.setImpulseCategories(userId, [...cur]));
  }

  const s = snap.data;
  const noBudget = !s || s.budget <= 0;

  return (
    <Screen title="충동예산 절약통" hasTabBar>
      <AppBar onBack={back} title="충동예산 절약통" />
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>
        <p className="h-title" style={{ marginTop: 0 }}>참을수록 저절로 커져요</p>
        <p className="h-sub">
          들어올 때마다 하루치를 50 → 30 → 20%씩, 안 온 날은 다음에 합쳐서 채워요.
        </p>

        <ErrorBox error={snap.error ?? error} onRetry={snap.reload} />
        {snap.loading && <Loading label="절약통을 불러오는 중" rows={4} />}
        {feedback && <p className="notice-ok" role="status" key={actionKey}>{feedback}</p>}

        {s && (
          <>
            <div className="card">
              <div className="gift-summary">
                <GiftBox fill={s.giftFill} totalSavings={s.giftBalance}
                  lastAction={s.lastAction === 'GROW' ? 'SAVED' : s.lastAction} actionKey={actionKey} />
                <div className="gs-stats">
                  <div className="gs-row"><span>충동예산(월)</span><b>{noBudget ? '—' : won(s.budget)}</b></div>
                  <div className="gs-row big"><span>지금까지 모임</span><b className="sav">{won(s.giftBalance)}</b></div>
                  <div className="gs-row"><span>하루 할당량</span><b>{noBudget ? '—' : won(s.dailyQuota)}</b></div>
                </div>
              </div>
            </div>

            {/* ① 충동 카테고리 지정 → 예산 */}
            {/* 창 길이는 서버 설정(finntech.analysis.baseline-months)이 정한다. 화면에 숫자를 박으면
            설정을 바꿨을 때 문구만 옛말이 된다 — 예전에 "3개월"이라 적어두고 실제로는 전 기간을
            평균하던 것이 그 사례다. */}
        <SectionTitle aux="최근 소비 평균이 예산이 돼요">어떤 소비가 충동이었나요?</SectionTitle>
            <div className="card">
              {s.options.length === 0 ? (
                <Empty>소비 이력이 아직 없어요. 카드를 연결하거나 소비를 기록해 보세요.</Empty>
              ) : (
                <div className="chips">
                  {s.options.slice(0, 8).map((o) => {
                    const on = s.impulseCategories.includes(o.categoryCode);
                    return (
                      <button type="button" key={o.categoryCode} className={`chip${on ? ' on' : ''}`}
                        aria-pressed={on} onClick={() => toggleCategory(o.categoryCode)}>
                        {catLabel(o.categoryCode, o.displayName)}
                        <span className="aux-badge">월 {won(o.monthlyAmount)}</span>
                      </button>
                    );
                  })}
                </div>
              )}
            </div>

            {/* ④ 재검증 */}
            <SectionTitle aux="정말 줄었는지 확인">다음달 카드내역으로 재검증</SectionTitle>
            <div className="card">
              <p className="empty" style={{ marginTop: 0 }}>
                형식: <code>날짜,카테고리코드,금액</code> (한 줄에 한 건). 예: <code>2026-08-03,CAFE,5500</code>
              </p>
              <label className="form-row">
                <span>카드내역 CSV</span>
                <textarea className="inp" rows={4} value={csv} onChange={(e) => setCsv(e.target.value)}
                  placeholder={'2026-08-03,CAFE,5500\n2026-08-05,SHOPPING,32000'} />
              </label>
              <button type="button" className="btn btn-primary btn-sm" disabled={uploading || !csv.trim()}
                onClick={() => {
                  setUploading(true);
                  void run(api.impulseUpload(userId, csv))
                    .then((r) => { if (r) setCsv(''); })
                    .finally(() => setUploading(false));
                }}>
                {uploading ? '반영 중…' : '업로드 · 재검증'}
              </button>

              {s.verify.length > 0 && (
                <div style={{ marginTop: 14 }}>
                  {s.verify.map((v) => {
                    const dropped = Math.round(Math.abs(v.changePct) * 100);
                    return (
                      <div className="txn" key={v.categoryCode}>
                        <span className="m">{catLabel(v.categoryCode, v.displayName)}</span>
                        <span className="muted small">{won(v.baseline)} → {won(v.latest)}</span>
                        <span className={`mchip ${v.improved ? 'c-green' : 'c-red'}`}>
                          {v.improved ? `▼ ${dropped}% 줄었어요` : v.changePct > 0 ? `▲ ${dropped}% 늘었어요` : '변화 없음'}
                        </span>
                      </div>
                    );
                  })}
                </div>
              )}
              {s.hasUpload && s.verify.length === 0 && (
                <Empty>충동 카테고리를 먼저 지정하면 재검증 결과가 나와요.</Empty>
              )}
            </div>
          </>
        )}
        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
