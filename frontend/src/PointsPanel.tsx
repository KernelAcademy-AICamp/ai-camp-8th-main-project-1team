import { useEffect, useState } from 'react';
import {
  api, catLabel,
  type CategoryView, type PointSnapshot, type PointEventView, type LookupResult, type GoalGain,
  type SavingsCompare, type GoalView, type GoalRecommendation,
} from './api';
import { GiftBox } from './GiftBox';

const won = (n: number) => Math.round(n).toLocaleString('ko-KR') + '원';
const man = (n: number) => (Math.round((n / 10000) * 10) / 10).toLocaleString('ko-KR') + '만원';
const pct = (x: number) => Math.round(x * 100);

const EMOJIS = ['✈️', '💻', '📱', '🏠', '🚗', '🎓', '💍', '🎮', '📷', '🛟', '🎁', '⌚'];

function eventText(e: PointEventView): string {
  if (e.type === 'WITHDRAWAL') return '🔄 목표에서 잠깐 빌림';
  return '💪 참았어요 → 목표 입금';
}

/**
 * 참는 순간의 '획득 프레이밍' 문구 — "돈을 안 썼다"가 아니라 "내 목표에 다가갔다"로 번역한다.
 * 예: ✨ 참았어요! ✈️ 도쿄 여행 65% → 68% · 🗓 12일 앞당겨졌어요
 */
function gainText(g: GoalGain | null): string {
  if (!g) return '✨ 참았어요! 목표에 바로 저축됐어요';
  const days = g.daysAdded > 0 ? ` · 🗓 ${g.daysAdded}일 앞당겨졌어요` : '';
  return `✨ 참았어요! ${g.emoji} ${g.goalName} ${pct(g.progressBefore)}% → ${pct(g.progressAfter)}%${days}`;
}

/**
 * 게임화 저축 루프 (문서 §5-5, Qapital + 치팅데이 쿠폰) — 서비스의 주인공.
 * 살 뻔했다 → 즉시 랜덤 목표 입금 · 소비/필요없는 소비 구분 · 선물상자 · 예산 초과 강제차감 · 치팅데이 쿠폰.
 */
export function PointsPanel({ userId, onChanged }: { userId: number; onChanged?: () => void }) {
  const [snap, setSnap] = useState<PointSnapshot | null>(null);
  const [cats, setCats] = useState<CategoryView[]>([]);
  const [loading, setLoading] = useState(true);
  const [err, setErr] = useState<string | null>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [actionKey, setActionKey] = useState(0);

  const [spendCat, setSpendCat] = useState('');
  const [spendAmt, setSpendAmt] = useState('');
  const [spendNeed, setSpendNeed] = useState(true);
  const [gName, setGName] = useState('');
  const [gEmoji, setGEmoji] = useState('✈️');
  const [gTarget, setGTarget] = useState('');

  // 목표 마일스톤 추가 폼 (열려있는 목표 id)
  const [mlGoal, setMlGoal] = useState<number | null>(null);
  const [mlName, setMlName] = useState('');
  const [mlEmoji, setMlEmoji] = useState('');
  const [mlCost, setMlCost] = useState('');

  // 고민 목록(폴센트 응용)
  const [wlUrl, setWlUrl] = useState('');
  const [wlName, setWlName] = useState('');
  const [wlPrice, setWlPrice] = useState('');
  const [wlImage, setWlImage] = useState<string | null>(null);
  const [wlSource, setWlSource] = useState<'URL' | 'IMAGE' | 'MANUAL'>('MANUAL');
  const [wlSourceUrl, setWlSourceUrl] = useState('');
  const [looking, setLooking] = useState(false);
  const [wlHint, setWlHint] = useState<string | null>(null);

  // 통장 비교 (정보성) — 스냅샷과 독립적으로 로드(외부 조회라 느릴 수 있어 화면을 막지 않는다)
  const [compare, setCompare] = useState<SavingsCompare | null>(null);
  useEffect(() => { api.compareSavings(3).then(setCompare).catch(() => undefined); }, []);

  // 목표별 추천 통장 (계획 기간 기반, 중복 없이) — 계획을 바꾸면 다시 불러온다
  const [recs, setRecs] = useState<GoalRecommendation[]>([]);
  const [planGoal, setPlanGoal] = useState<number | null>(null);
  const refreshRecs = () => api.goalRecommendations(userId).then(setRecs).catch(() => undefined);
  useEffect(() => { void refreshRecs(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  function togglePlanCut(g: GoalView, code: string) {
    const cur = new Set(g.planCutCategories);
    if (cur.has(code)) cur.delete(code); else cur.add(code);
    void run(api.setGoalPlan(userId, g.id, [...cur])).then(() => refreshRecs());
  }

  async function load() {
    setLoading(true); setErr(null);
    try {
      const [s, c] = await Promise.all([api.points(userId), api.categories()]);
      setSnap(s); setCats(c);
      if (c.length && !spendCat) setSpendCat(c[0].code);
    } catch (e) { setErr(e instanceof Error ? e.message : String(e)); }
    finally { setLoading(false); }
  }
  useEffect(() => { void load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  function announce(s: PointSnapshot) {
    setActionKey((k) => k + 1);
    switch (s.lastAction) {
      case 'OVERSPEND':
        setFeedback(s.forcedWithdrawal
          ? `이번 달 예산을 조금 넘었어요. ${s.forcedWithdrawal.goalName}에서 ${won(s.forcedWithdrawal.amount)}을 잠깐 빌려왔어요 — 다음 참기로 다시 채워봐요 🙂`
          : '이번 달 예산을 조금 넘었어요 — 다음 참기로 만회할 수 있어요 🙂');
        break;
      case 'UNNECESSARY': setFeedback('괜찮아요, 이런 날도 있죠. 다음 한 번은 목표에 담아볼까요? 🌱'); break;
      case 'SAVED': setFeedback(gainText(s.gain)); break;
      case 'SPEND': setFeedback('소비를 기록했어요'); break;
      case 'COUPON_USED': setFeedback('😎 오늘은 치팅데이! 즐겁게 쓰세요'); break;
      case 'COUPON_DECLINED': setFeedback('👏 잘 참았어요! 목표에 더 가까워졌어요'); break;
      default: setFeedback(null);
    }
  }

  /** 직전 스냅샷 대비 새로 획득한 마일스톤(축하용). */
  function newlyAcquired(prev: PointSnapshot | null, next: PointSnapshot) {
    if (!prev) return null;
    const was = new Map<number, boolean>();
    prev.goals.forEach((g) => g.milestones.forEach((m) => was.set(m.id, m.acquired)));
    for (const g of next.goals) {
      for (const m of g.milestones) {
        if (m.acquired && was.get(m.id) === false) return { m, goal: g.name };
      }
    }
    return null;
  }

  async function run(p: Promise<PointSnapshot>) {
    setErr(null);
    const prev = snap;
    try {
      const s = await p;
      setSnap(s);
      announce(s);
      const got = newlyAcquired(prev, s);
      if (got) { setActionKey((k) => k + 1); setFeedback(`🎉 '${got.m.emoji} ${got.m.name}'을(를) 얻었어요! (${got.goal})`); }
      onChanged?.();
      return s;
    } catch (e) { setErr(e instanceof Error ? e.message : String(e)); return null; }
  }

  // ── 고민 목록 유입 ──
  function fillFromLookup(r: LookupResult, source: 'URL' | 'IMAGE') {
    if (r.name) setWlName(r.name);
    if (r.price != null && r.price > 0) setWlPrice(String(Math.round(r.price)));
    if (r.imageUrl) setWlImage(r.imageUrl);
    setWlSource(source);
    setWlHint(r.note ? r.note
      : (r.name && r.price ? '불러왔어요! 확인하고 담으세요.' : '일부만 읽었어요 — 나머지는 직접 채워 주세요.'));
  }
  async function doLookupUrl() {
    if (!wlUrl.trim()) return;
    setLooking(true); setWlHint(null); setErr(null);
    try {
      const r = await api.lookupProductUrl(wlUrl.trim());
      setWlSourceUrl(wlUrl.trim());
      fillFromLookup(r, 'URL');
    } catch { setWlHint('URL을 못 읽었어요 — 스크린샷이나 직접 입력을 써주세요.'); }
    finally { setLooking(false); }
  }
  async function onScreenshot(e: React.ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    if (!file) return;
    setLooking(true); setWlHint(null); setErr(null);
    try {
      const dataUrl = await new Promise<string>((res, rej) => {
        const fr = new FileReader();
        fr.onload = () => res(String(fr.result));
        fr.onerror = () => rej(fr.error);
        fr.readAsDataURL(file);
      });
      const base64 = dataUrl.split(',')[1] ?? '';
      const r = await api.lookupProductImage(base64, file.type || 'image/png');
      if (!r.imageUrl) setWlImage(dataUrl); // 업로드한 스샷을 미리보기로
      fillFromLookup(r, 'IMAGE');
      if (!r.name && !r.price) setWlHint('AI가 못 읽었어요(키 없음/실패) — 직접 입력해 주세요.');
    } catch { setWlHint('이미지 분석 실패 — 직접 입력해 주세요.'); }
    finally { setLooking(false); e.target.value = ''; }
  }
  async function doAddWishlist() {
    if (!wlName || !wlPrice) { setWlHint('이름과 가격을 확인해 주세요.'); return; }
    const s = await run(api.addWishlist(userId, {
      name: wlName, price: Number(wlPrice),
      imageUrl: wlImage ?? undefined, sourceUrl: wlSourceUrl || undefined, source: wlSource,
    }));
    if (s) {
      setWlUrl(''); setWlName(''); setWlPrice(''); setWlImage(null);
      setWlSourceUrl(''); setWlSource('MANUAL'); setWlHint(null);
    }
  }

  if (!snap) {
    return (
      <section className="card points"><div className="card-pad">
        {err ? <div className="error"><code>{err}</code></div> : <div className="loading-bar" />}
      </div></section>
    );
  }

  const overBudget = snap.pointsRemaining < 0;

  return (
    <section className={`card points ${loading ? 'is-loading' : ''}`}>
      <div className="card-pad">
        <div className="pts-top">
          <div>
            <p className="eyebrow">가상 포인트 저축 · Qapital 스타일</p>
            <h2 className="pts-title">아낀 돈이 목표로 쌓여요</h2>
          </div>
          <span className="pts-month">{snap.month}</span>
        </div>

        {err && <div className="error"><code>{err}</code></div>}
        {feedback && <div className={`pts-feedback ${overBudget ? 'warn' : ''}`} key={actionKey} role="status" aria-live="polite">{feedback}</div>}

        {/* 선물상자 + 요약 */}
        <div className="gift-summary">
          <GiftBox fill={snap.giftFill} totalSavings={snap.totalSavings}
            lastAction={snap.lastAction} actionKey={actionKey} />
          <div className="gs-stats">
            <div className="gs-row"><span>이번 달 예산</span><b className="num">{won(snap.monthlyBudget)}</b></div>
            <div className="gs-row"><span>쓸 수 있는 돈</span>
              <b className={`num ${overBudget ? 'neg' : ''}`}>{won(snap.pointsRemaining)}</b></div>
            <div className="gs-row big"><span>목표에 모인 돈</span><b className="num sav">{won(snap.totalSavings)}</b></div>
            <div className="budget-track mini">
              <span className="budget-fill" style={{ width: `${Math.max(0, Math.min(100, (snap.pointsRemaining / snap.monthlyBudget) * 100))}%` }} />
            </div>
            <span className="tiny muted">쓴 돈 {won(snap.thisMonthSpent)} · 저축 {won(snap.thisMonthSaved)}</span>
          </div>
        </div>

        {/* 소비 건전성 (FDS 재프레이밍) */}
        <div className="health-strip">
          <div className="hs-score">
            <span className="hs-label">소비 건전성 지수</span>
            <span className={`hs-num grade-${snap.healthGrade}`}>{snap.healthScore}<small>점 · {snap.healthGrade}</small></span>
          </div>
          {snap.behaviorAlerts.length > 0 && (
            <ul className="hs-alerts">
              {snap.behaviorAlerts.map((a, i) => <li key={i}>💬 {a}</li>)}
            </ul>
          )}
        </div>

        {/* 치팅데이 쿠폰 */}
        {snap.coupon && (
          <div className="coupon-banner" key={`c${snap.coupon.id}`}>
            <div className="cp-left">
              <span className="cp-icon">🎟️</span>
              <div>
                <div className="cp-title">치팅데이 쿠폰이 도착했어요!</div>
                <div className="cp-desc">
                  그동안 잘 참았어요. <b>{catLabel(snap.coupon.categoryCode ?? '')} {won(snap.coupon.benefitAmount)} 자유이용권</b> — 쓸까요?
                </div>
              </div>
            </div>
            <div className="cp-actions">
              <button className="btn btn-ghost btn-sm" onClick={() => void run(api.useCoupon(userId, snap.coupon!.id))}>오늘은 쓴다 😎</button>
              <button className="btn btn-primary btn-sm" onClick={() => void run(api.declineCoupon(userId, snap.coupon!.id))}>계속 모은다 💪</button>
            </div>
          </div>
        )}

        {/* 목표 버킷 */}
        <div className="pts-section">
          <h3>내 목표 <span className="muted tiny">— 아낀 돈이 자동으로 여기로 쌓여요</span></h3>
          <div className="goal-list">
            {snap.goals.map((g) => (
              <div className="goal-card" key={g.id}>
                <div className="goal-head">
                  <span className="goal-emoji">{g.emoji}</span>
                  <span className="goal-name">{g.name}</span>
                  <button className={`goal-star ${g.priority ? 'on' : ''}`} title="우선 채우기"
                    onClick={() => void run(api.updateGoal(userId, g.id, { priority: !g.priority }))}>★</button>
                  <button className="goal-del" title="삭제"
                    onClick={() => void run(api.deleteGoal(userId, g.id))}>✕</button>
                </div>
                <div className="goal-track">
                  <span className="goal-fill" style={{ width: `${Math.min(100, Math.round(g.progress * 100))}%` }} />
                </div>
                <div className="goal-meta">
                  <b>{won(g.balance)}</b> / {man(g.targetAmount)} · {Math.round(g.progress * 100)}%
                  {g.fundedDays > 0 && <span className="goal-days">🗓 {g.fundedDays}일 앞당겨짐</span>}
                </div>
                {g.accountNumber && (
                  <div className="muted small" style={{ marginTop: 4 }}>
                    🏦 {g.accountBank} {g.accountProduct} · <span style={{ fontVariantNumeric: 'tabular-nums' }}>{g.accountNumber}</span> <span className="muted">(자유입출금)</span>
                  </div>
                )}

                {g.milestones.length > 0 && (
                  <div className="ms-row">
                    {g.milestones.map((m) => (
                      <span key={m.id} className={`ms-chip ${m.acquired ? 'got' : ''}`}
                        title={m.acquired ? `${m.name} · ${won(m.cost)} ✓` : `${m.name} · ${won(m.remaining)} 남음`}>
                        <span className="ms-emoji">{m.acquired ? m.emoji : '⚪'}</span>
                        <span className="ms-name">{m.name}</span>
                        {mlGoal === g.id && (
                          <button className="ms-del" type="button" title="단계 삭제"
                            onClick={() => void run(api.deleteMilestone(userId, m.id))}>✕</button>
                        )}
                      </span>
                    ))}
                  </div>
                )}

                {mlGoal === g.id ? (
                  <form className="ms-form" onSubmit={(e) => {
                    e.preventDefault();
                    if (!mlName || !mlCost) return;
                    void run(api.addMilestone(userId, g.id, { name: mlName, emoji: mlEmoji || '⭐', cost: Number(mlCost) }))
                      .then(() => { setMlName(''); setMlCost(''); setMlEmoji(''); });
                  }}>
                    <input className="ms-emoji-in" placeholder="🎯" maxLength={2} value={mlEmoji} onChange={(e) => setMlEmoji(e.target.value)} aria-label="단계 이모지" />
                    <input placeholder="단계 이름" value={mlName} onChange={(e) => setMlName(e.target.value)} aria-label="단계 이름" />
                    <input type="number" min={1} placeholder="금액" value={mlCost} onChange={(e) => setMlCost(e.target.value)} aria-label="단계 금액" />
                    <button className="btn btn-primary btn-sm" type="submit">추가</button>
                    <button className="btn btn-ghost btn-sm" type="button" onClick={() => setMlGoal(null)}>닫기</button>
                  </form>
                ) : (
                  <button className="ms-add-btn" type="button"
                    onClick={() => { setMlGoal(g.id); setMlName(''); setMlEmoji(''); setMlCost(''); }}>+ 단계 추가</button>
                )}

                {/* 저축 계획 — 줄일 소비 → 개월수 → 추천 통장 */}
                <div className="goal-plan">
                  {g.planMonths > 0 ? (
                    <div className="gp-summary">🎯 이 소비 줄이면 <b>월 {won(g.planMonthlySaving)}</b> → <b>{g.planMonths}개월</b>이면 달성</div>
                  ) : (
                    <div className="gp-summary muted">줄일 소비를 고르면 며칠 만에 모을지 계산돼요</div>
                  )}
                  {(() => {
                    const rec = recs.find((r) => r.goalId === g.id);
                    return rec?.productName ? (
                      <div className="gp-rec">💳 추천 통장 <b>{rec.company} {rec.productName}</b> · 기본 {rec.baseRate.toFixed(2)}%
                        <span className="muted tiny"> ({rec.periodMonths}개월)</span></div>
                    ) : null;
                  })()}
                  {planGoal === g.id ? (
                    <div className="gp-options">
                      {snap.cutOptions.length === 0 && <span className="muted tiny">줄일 만한 습관 소비 기록이 아직 없어요</span>}
                      {snap.cutOptions.map((o) => {
                        const on = g.planCutCategories.includes(o.categoryCode);
                        return (
                          <button type="button" key={o.categoryCode} className={`gp-chip ${on ? 'on' : ''}`}
                            onClick={() => togglePlanCut(g, o.categoryCode)}>
                            {on ? '✓ ' : ''}{catLabel(o.categoryCode, o.displayName)}
                            <span className="gp-amt">월 {won(o.monthlyAmount)}</span>
                          </button>
                        );
                      })}
                      <button type="button" className="btn btn-ghost btn-sm gp-close" onClick={() => setPlanGoal(null)}>닫기</button>
                    </div>
                  ) : (
                    <button type="button" className="gp-edit" onClick={() => setPlanGoal(g.id)}>
                      {g.planCutCategories.length ? '✏️ 계획 수정' : '+ 저축 계획 세우기'}
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
          <form className="goal-form" onSubmit={(e) => {
            e.preventDefault();
            if (!gName || !gTarget) return;
            void run(api.createGoal(userId, gName, gEmoji, Number(gTarget))).then(() => { setGName(''); setGTarget(''); });
          }}>
            <select value={gEmoji} onChange={(e) => setGEmoji(e.target.value)} aria-label="이모지">
              {EMOJIS.map((em) => <option key={em} value={em}>{em}</option>)}
            </select>
            <input placeholder="목표 이름 (예: 파리 여행)" value={gName} onChange={(e) => setGName(e.target.value)} aria-label="목표 이름" />
            <input type="number" min={1} placeholder="목표 금액" value={gTarget} onChange={(e) => setGTarget(e.target.value)} aria-label="목표 금액" />
            <button className="btn btn-ghost btn-sm" type="submit">목표 추가</button>
          </form>
        </div>

        {/* 고민 목록 (폴센트 응용) */}
        <div className="pts-section">
          <h3>고민 목록 <span className="muted tiny">— 담아둔 걸 안 사면 그 돈이 아낀 돈으로</span></h3>
          {snap.savedByNotBuying > 0 && (
            <div className="wl-saved">🙌 안 사서 아낀 돈 <b>{won(snap.savedByNotBuying)}</b></div>
          )}

          <div className="wl-add">
            <div className="wl-ingest">
              <input placeholder="상품 URL 붙여넣기 (쿠팡 등)" value={wlUrl} aria-label="상품 URL"
                onChange={(e) => setWlUrl(e.target.value)} />
              <button className="btn btn-ghost btn-sm" type="button" disabled={looking || !wlUrl.trim()}
                onClick={() => void doLookupUrl()}>{looking ? '불러오는 중…' : '불러오기'}</button>
              <label className="btn btn-ghost btn-sm wl-shot">
                📷 스크린샷
                <input type="file" accept="image/*" hidden onChange={(e) => void onScreenshot(e)} />
              </label>
            </div>
            {wlHint && <div className="wl-hint">{wlHint}</div>}
            <div className="wl-fields">
              {wlImage && <img className="wl-thumb" src={wlImage} alt="" />}
              <input placeholder="상품 이름" value={wlName} onChange={(e) => setWlName(e.target.value)} aria-label="상품 이름" />
              <input type="number" min={1} placeholder="가격(원)" value={wlPrice} aria-label="가격(원)"
                onChange={(e) => setWlPrice(e.target.value)} />
              <button className="btn btn-primary btn-sm" type="button" onClick={() => void doAddWishlist()}>고민 목록에 담기</button>
            </div>
          </div>

          {snap.wishlist.length > 0 ? (
            <div className="wl-list">
              {snap.wishlist.map((w) => (
                <div className="wl-card" key={w.id}>
                  {w.imageUrl && <img className="wl-thumb" src={w.imageUrl} alt="" />}
                  <div className="wl-info">
                    <div className="wl-name">{w.name}</div>
                    <div className="wl-price">{won(w.price)}</div>
                  </div>
                  <div className="wl-actions">
                    <button className="btn btn-primary btn-sm" onClick={() => void run(api.wishlistNotBought(userId, w.id))}>안 샀어요 💪</button>
                    <button className="btn btn-ghost btn-sm" onClick={() => void run(api.wishlistBought(userId, w.id))}>샀어요</button>
                    <button className="wl-del" title="삭제" onClick={() => void run(api.deleteWishlist(userId, w.id))}>✕</button>
                  </div>
                </div>
              ))}
            </div>
          ) : <p className="muted small">사고 싶은 상품을 담아두고, 안 사면 그만큼 저축해 보세요.</p>}
        </div>

        {/* 통장 비교 (정보성) — 절감액을 실제로 담을 통장 */}
        {compare && compare.accounts.length > 0 && (
          <div className="pts-section">
            <h3>이 돈, 어디에 모을까? <span className="muted tiny">— 통장 비교</span></h3>
            <div className="acct-cards">
              {compare.accounts.slice(0, 3).map((a, i) => (
                <div className={`acct-card ${i === 0 ? 'best' : ''}`} key={`${a.company}-${a.name}-${i}`}>
                  <span className="acct-badge">{i + 1}위</span>
                  <div className="acct-bank2">{a.company}</div>
                  <div className="acct-name2">{a.name}</div>
                  <div className="acct-rate2">{a.baseRate.toFixed(2)}<small>%</small></div>
                  <div className="acct-rate-label">기본금리</div>
                </div>
              ))}
            </div>
          </div>
        )}

        {/* 소비 기록 */}
        <div className="pts-section">
          <h3>소비했어요 <span className="muted tiny">— 솔직하게 기록만 해도 충분해요</span></h3>
          <form className="spend-form" onSubmit={(e) => {
            e.preventDefault();
            if (!spendAmt) return;
            void run(api.spend(userId, spendCat, Number(spendAmt), spendNeed)).then(() => setSpendAmt(''));
          }}>
            <select value={spendCat} onChange={(e) => setSpendCat(e.target.value)} aria-label="카테고리">
              {cats.map((c) => <option key={c.code} value={c.code}>{c.displayName}</option>)}
            </select>
            <input type="number" min={1} placeholder="금액" value={spendAmt}
              onChange={(e) => setSpendAmt(e.target.value)} aria-label="금액" />
            <div className="need-toggle">
              <button type="button" className={spendNeed ? 'on' : ''} onClick={() => setSpendNeed(true)}>가치 소비</button>
              <button type="button" className={!spendNeed ? 'on danger' : ''} onClick={() => setSpendNeed(false)}>습관 소비</button>
            </div>
            <button className="btn btn-ghost btn-sm" type="submit">기록</button>
          </form>
        </div>

        {/* 최근 활동 */}
        {snap.recentEvents.length > 0 && (
          <div className="pts-section">
            <h3>최근 활동</h3>
            <ul className="event-feed">
              {snap.recentEvents.map((e, i) => (
                <li key={i} className={e.type.toLowerCase()}>
                  <span className="ev-type">{eventText(e)}</span>
                  {e.categoryCode && <span className="ev-cat">{catLabel(e.categoryCode)}</span>}
                  <span className="ev-amt">{e.type === 'WITHDRAWAL' ? '−' : '+'}{won(e.amount)}</span>
                  <time>{e.occurredAt.replace('T', ' ').slice(5, 16)}</time>
                </li>
              ))}
            </ul>
          </div>
        )}
      </div>
    </section>
  );
}
