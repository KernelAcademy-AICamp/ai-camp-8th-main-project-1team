/**
 * 마이 &gt; 목표와 고민 목록 (§5-5 게임화 저축 루프).
 * 참으면 목표에 바로 쌓이고, 예산을 넘기면 목표에서 잠깐 빌려온다. 판단·금액은 전부 서버가 한다.
 * 고민 목록(폴센트 응용)은 URL·스크린샷에서 상품을 읽어와 담고, 안 사면 그 돈이 아낀 돈이 된다.
 */
import { useEffect, useState } from 'react';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty, SectionTitle } from '../components/ui';
import { GiftBox } from '../components/GiftBox';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import {
  api, catLabel,
  type CategoryView, type GoalGain, type GoalRecommendation, type GoalView,
  type LookupResult, type PointEventView, type PointSnapshot,
} from '../lib/api';
import { won, man, pctNum, shortDateTime } from '../lib/format';

const EMOJIS = ['✈️', '💻', '📱', '🏠', '🚗', '🎓', '💍', '🎮', '📷', '🛟', '🎁', '⌚'];

const eventText = (e: PointEventView) =>
  e.type === 'WITHDRAWAL' ? '🔄 목표에서 잠깐 빌림' : '💪 참았어요 → 목표 입금';

/** 참는 순간을 '돈을 안 썼다'가 아니라 '목표에 다가갔다'로 번역한다(획득 프레이밍). */
function gainText(g: GoalGain | null): string {
  if (!g) return '✨ 참았어요! 목표에 바로 저축됐어요';
  const days = g.daysAdded > 0 ? ` · 🗓 ${g.daysAdded}일 앞당겨졌어요` : '';
  return `✨ 참았어요! ${g.emoji} ${g.goalName} ${pctNum(g.progressBefore)}% → ${pctNum(g.progressAfter)}%${days}`;
}

export function MyGoals() {
  const { back, userId } = useSession();
  const snap = useAsync(() => api.points(userId), [userId]);
  const [cats, setCats] = useState<CategoryView[]>([]);
  const [recs, setRecs] = useState<GoalRecommendation[]>([]);
  const [error, setError] = useState<unknown>(null);
  const [feedback, setFeedback] = useState<string | null>(null);
  const [actionKey, setActionKey] = useState(0);

  // 목표 추가 · 마일스톤 · 계획
  const [gName, setGName] = useState('');
  const [gEmoji, setGEmoji] = useState('✈️');
  const [gTarget, setGTarget] = useState('');
  const [mlGoal, setMlGoal] = useState<number | null>(null);
  const [mlName, setMlName] = useState('');
  const [mlEmoji, setMlEmoji] = useState('');
  const [mlCost, setMlCost] = useState('');
  const [planGoal, setPlanGoal] = useState<number | null>(null);

  // 고민 목록
  const [wlUrl, setWlUrl] = useState('');
  const [wlName, setWlName] = useState('');
  const [wlPrice, setWlPrice] = useState('');
  const [wlImage, setWlImage] = useState<string | null>(null);
  const [wlSource, setWlSource] = useState<'URL' | 'IMAGE' | 'MANUAL'>('MANUAL');
  const [wlSourceUrl, setWlSourceUrl] = useState('');
  const [looking, setLooking] = useState(false);
  const [wlHint, setWlHint] = useState<string | null>(null);

  // 소비 기록
  const [spendCat, setSpendCat] = useState('');
  const [spendAmt, setSpendAmt] = useState('');
  const [spendNeed, setSpendNeed] = useState(true);

  useEffect(() => {
    api.categories().then((c) => { setCats(c); if (c.length) setSpendCat((v) => v || c[0].code); }).catch(() => undefined);
  }, [userId]);
  const refreshRecs = () => api.goalRecommendations(userId).then(setRecs).catch(() => undefined);
  useEffect(() => { void refreshRecs(); }, [userId]); // eslint-disable-line react-hooks/exhaustive-deps

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
      for (const m of g.milestones) if (m.acquired && was.get(m.id) === false) return { m, goal: g.name };
    }
    return null;
  }

  async function run(p: Promise<PointSnapshot>) {
    setError(null);
    const prev = snap.data;
    try {
      const s = await p;
      snap.set(s);
      announce(s);
      const got = newlyAcquired(prev, s);
      if (got) { setActionKey((k) => k + 1); setFeedback(`🎉 '${got.m.emoji} ${got.m.name}'을(를) 얻었어요! (${got.goal})`); }
      return s;
    } catch (e) { setError(e); return null; }
  }

  function togglePlanCut(g: GoalView, code: string) {
    const cur = new Set(g.planCutCategories);
    if (cur.has(code)) cur.delete(code); else cur.add(code);
    void run(api.setGoalPlan(userId, g.id, [...cur])).then(() => refreshRecs());
  }

  function fillFromLookup(r: LookupResult, source: 'URL' | 'IMAGE') {
    if (r.name) setWlName(r.name);
    if (r.price != null && r.price > 0) setWlPrice(String(Math.round(r.price)));
    if (r.imageUrl) setWlImage(r.imageUrl);
    setWlSource(source);
    setWlHint(r.note ?? (r.name && r.price ? '불러왔어요! 확인하고 담으세요.' : '일부만 읽었어요 — 나머지는 직접 채워 주세요.'));
  }
  async function doLookupUrl() {
    if (!wlUrl.trim()) return;
    setLooking(true); setWlHint(null);
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
    setLooking(true); setWlHint(null);
    try {
      const dataUrl = await new Promise<string>((res, rej) => {
        const fr = new FileReader();
        fr.onload = () => res(String(fr.result));
        fr.onerror = () => rej(fr.error);
        fr.readAsDataURL(file);
      });
      const base64 = dataUrl.split(',')[1] ?? '';
      const r = await api.lookupProductImage(base64, file.type || 'image/png');
      if (!r.imageUrl) setWlImage(dataUrl);
      fillFromLookup(r, 'IMAGE');
      if (!r.name && !r.price) setWlHint('AI가 못 읽었어요 — 직접 입력해 주세요.');
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

  const s = snap.data;
  const overBudget = (s?.pointsRemaining ?? 0) < 0;

  return (
    <Screen title="목표와 고민 목록" hasTabBar>
      <AppBar onBack={back} title="목표와 고민 목록" />
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>
        <ErrorBox error={snap.error ?? error} onRetry={snap.reload} />
        {snap.loading && <Loading label="목표를 불러오는 중" rows={5} />}
        {feedback && (
          <p className={overBudget ? 'error' : 'notice-ok'} role="status" key={actionKey}>{feedback}</p>
        )}

        {s && (
          <>
            {/* 요약 */}
            <div className="card">
              <div className="gift-summary">
                <GiftBox fill={s.giftFill} totalSavings={s.totalSavings} lastAction={s.lastAction} actionKey={actionKey} />
                <div className="gs-stats">
                  <div className="gs-row"><span>이번 달 예산</span><b>{won(s.monthlyBudget)}</b></div>
                  <div className="gs-row"><span>쓸 수 있는 돈</span>
                    <b className={overBudget ? 'neg' : ''}>{won(s.pointsRemaining)}</b></div>
                  <div className="gs-row big"><span>목표에 모인 돈</span><b className="sav">{won(s.totalSavings)}</b></div>
                  <span className="empty" style={{ margin: 0 }}>
                    쓴 돈 {won(s.thisMonthSpent)} · 저축 {won(s.thisMonthSaved)}
                  </span>
                </div>
              </div>
            </div>

            {/* 소비 건전성 */}
            <div className="mcard">
              <div className="mtop">
                <span className="mic" style={{ background: 'var(--blue-weak)' }} aria-hidden="true">🩺</span>
                <span className="mtx"><b>소비 건전성 지수</b><span>미계획 소비가 이어지면 내려가요</span></span>
                <span className={`mchip ${s.healthGrade === 'A' ? 'c-green' : s.healthGrade === 'D' ? 'c-red' : 'c-blue'}`}>
                  {s.healthScore}점 · {s.healthGrade}
                </span>
              </div>
              {s.behaviorAlerts.length > 0 && (
                <ul style={{ margin: '10px 0 0', paddingLeft: 18, fontSize: 13, color: 'var(--t2)', lineHeight: 1.6 }}>
                  {s.behaviorAlerts.map((a, i) => <li key={i}>{a}</li>)}
                </ul>
              )}
            </div>

            {/* 치팅데이 쿠폰 */}
            {s.coupon && (
              <div className="card" style={{ background: 'var(--blue-weak)' }}>
                <div className="mtop">
                  <span className="mic" style={{ background: '#fff' }} aria-hidden="true">🎟️</span>
                  <span className="mtx">
                    <b>치팅데이 쿠폰이 도착했어요!</b>
                    <span>그동안 잘 참았어요 · {catLabel(s.coupon.categoryCode ?? '')} {won(s.coupon.benefitAmount)} 자유이용권</span>
                  </span>
                </div>
                <div style={{ display: 'flex', gap: 8, marginTop: 12 }}>
                  <button type="button" className="btn btn-ghost btn-sm" style={{ flex: 1 }}
                    onClick={() => void run(api.useCoupon(userId, s.coupon!.id))}>오늘은 쓴다 😎</button>
                  <button type="button" className="btn btn-primary btn-sm" style={{ flex: 1 }}
                    onClick={() => void run(api.declineCoupon(userId, s.coupon!.id))}>계속 모은다 💪</button>
                </div>
              </div>
            )}

            {/* 목표 */}
            <SectionTitle aux="아낀 돈이 자동으로 쌓여요">내 목표</SectionTitle>
            {s.goals.length === 0 && <div className="card"><Empty>아직 목표가 없어요. 아래에서 하나 만들어 볼까요?</Empty></div>}
            {s.goals.map((g) => {
              const rec = recs.find((r) => r.goalId === g.id);
              return (
                <div className="goal-item" key={g.id}>
                  <div className="goal-head">
                    <span className="goal-emoji" aria-hidden="true">{g.emoji}</span>
                    <span className="goal-name">{g.name}</span>
                    <button type="button" className={`icon-btn${g.priority ? ' on' : ''}`}
                      aria-label={`${g.name} 우선 채우기 ${g.priority ? '해제' : '설정'}`}
                      onClick={() => void run(api.updateGoal(userId, g.id, { priority: !g.priority }))}>★</button>
                    <button type="button" className="icon-btn" aria-label={`${g.name} 삭제`}
                      onClick={() => void run(api.deleteGoal(userId, g.id))}>✕</button>
                  </div>
                  <div className="bar" style={{ height: 7 }}>
                    <i style={{ width: `${Math.min(100, pctNum(g.progress))}%`, background: 'var(--blue)' }} />
                  </div>
                  <div className="goal-meta">
                    <b>{won(g.balance)}</b> / {man(g.targetAmount)} · {pctNum(g.progress)}%
                    {g.fundedDays > 0 && <span className="aux-badge green">🗓 {g.fundedDays}일 앞당겨짐</span>}
                  </div>
                  {g.accountNumber && (
                    <p className="empty" style={{ margin: '6px 0 0' }}>
                      🏦 {g.accountBank} {g.accountProduct} · <span className="num">{g.accountNumber}</span> (자유입출금)
                    </p>
                  )}

                  {g.milestones.length > 0 && (
                    <div className="mile-chips">
                      {g.milestones.map((m) => (
                        <span key={m.id} className={`ms-chip${m.acquired ? ' got' : ''}`}
                          title={m.acquired ? `${m.name} · ${won(m.cost)} ✓` : `${m.name} · ${won(m.remaining)} 남음`}>
                          <span aria-hidden="true">{m.acquired ? m.emoji : '⚪'}</span>
                          {m.name}
                          {mlGoal === g.id && (
                            <button type="button" className="icon-btn" style={{ width: 20, height: 20, fontSize: 11 }}
                              aria-label={`${m.name} 단계 삭제`}
                              onClick={() => void run(api.deleteMilestone(userId, m.id))}>✕</button>
                          )}
                        </span>
                      ))}
                    </div>
                  )}

                  {mlGoal === g.id ? (
                    <form className="form-inline" style={{ marginTop: 10 }} onSubmit={(e) => {
                      e.preventDefault();
                      if (!mlName || !mlCost) return;
                      void run(api.addMilestone(userId, g.id, { name: mlName, emoji: mlEmoji || '⭐', cost: Number(mlCost) }))
                        .then(() => { setMlName(''); setMlCost(''); setMlEmoji(''); });
                    }}>
                      <input className="inp" style={{ width: 56 }} placeholder="🎯" maxLength={2}
                        value={mlEmoji} onChange={(e) => setMlEmoji(e.target.value)} aria-label="단계 이모지" />
                      <input className="inp" style={{ flex: 1, minWidth: 110 }} placeholder="단계 이름"
                        value={mlName} onChange={(e) => setMlName(e.target.value)} aria-label="단계 이름" />
                      <input className="inp" style={{ width: 110 }} type="number" min={1} placeholder="금액"
                        value={mlCost} onChange={(e) => setMlCost(e.target.value)} aria-label="단계 금액" />
                      <button className="btn btn-primary btn-sm" type="submit">추가</button>
                      <button className="btn btn-ghost btn-sm" type="button" onClick={() => setMlGoal(null)}>닫기</button>
                    </form>
                  ) : (
                    <button type="button" className="btn btn-ghost btn-sm" style={{ marginTop: 10 }}
                      onClick={() => { setMlGoal(g.id); setMlName(''); setMlEmoji(''); setMlCost(''); }}>
                      + 단계 추가
                    </button>
                  )}

                  {/* 저축 계획 — 줄일 소비 → 개월수 → 추천 통장 */}
                  <div className="pv">
                    {g.planMonths > 0
                      ? <>🎯 이 소비를 줄이면 <b>월 {won(g.planMonthlySaving)}</b> → <b>{g.planMonths}개월</b>이면 달성</>
                      : <>줄일 소비를 고르면 며칠 만에 모을지 계산돼요</>}
                    {rec?.productName && (
                      <div style={{ marginTop: 6 }}>
                        💳 추천 통장 <b>{rec.company} {rec.productName}</b> · 기본 {rec.baseRate.toFixed(2)}%
                        <span className="muted small"> ({rec.periodMonths}개월)</span>
                      </div>
                    )}
                    {planGoal === g.id ? (
                      <div className="chips" style={{ marginTop: 10 }}>
                        {s.cutOptions.length === 0 && <span className="muted small">줄일 만한 습관 소비 기록이 아직 없어요</span>}
                        {s.cutOptions.map((o) => {
                          const on = g.planCutCategories.includes(o.categoryCode);
                          return (
                            <button type="button" key={o.categoryCode} className={`chip${on ? ' on' : ''}`}
                              aria-pressed={on} onClick={() => togglePlanCut(g, o.categoryCode)}>
                              {catLabel(o.categoryCode, o.displayName)}
                              <span className="aux-badge">월 {won(o.monthlyAmount)}</span>
                            </button>
                          );
                        })}
                        <button type="button" className="btn btn-ghost btn-sm" onClick={() => setPlanGoal(null)}>닫기</button>
                      </div>
                    ) : (
                      <button type="button" className="btn btn-ghost btn-sm" style={{ marginTop: 10 }}
                        onClick={() => setPlanGoal(g.id)}>
                        {g.planCutCategories.length ? '✏️ 계획 수정' : '+ 저축 계획 세우기'}
                      </button>
                    )}
                  </div>
                </div>
              );
            })}

            <form className="card" onSubmit={(e) => {
              e.preventDefault();
              if (!gName || !gTarget) return;
              void run(api.createGoal(userId, gName, gEmoji, Number(gTarget))).then(() => { setGName(''); setGTarget(''); });
            }}>
              <p className="label" style={{ marginTop: 0 }}>목표 추가</p>
              <div className="form-inline">
                <select className="inp" style={{ width: 78 }} value={gEmoji}
                  onChange={(e) => setGEmoji(e.target.value)} aria-label="목표 이모지">
                  {EMOJIS.map((em) => <option key={em} value={em}>{em}</option>)}
                </select>
                <input className="inp" style={{ flex: 1, minWidth: 130 }} placeholder="목표 이름 (예: 파리 여행)"
                  value={gName} onChange={(e) => setGName(e.target.value)} aria-label="목표 이름" />
                <input className="inp" style={{ width: 120 }} type="number" min={1} placeholder="목표 금액"
                  value={gTarget} onChange={(e) => setGTarget(e.target.value)} aria-label="목표 금액" />
                <button className="btn btn-primary btn-sm" type="submit">추가</button>
              </div>
            </form>

            {/* 고민 목록 */}
            <SectionTitle aux="안 사면 그 돈이 아낀 돈으로">고민 목록</SectionTitle>
            {s.savedByNotBuying > 0 && (
              <p className="notice-ok">🙌 안 사서 아낀 돈 <b>{won(s.savedByNotBuying)}</b></p>
            )}
            <div className="card">
              <div className="form-inline">
                <input className="inp" style={{ flex: 1, minWidth: 150 }} placeholder="상품 URL 붙여넣기"
                  value={wlUrl} onChange={(e) => setWlUrl(e.target.value)} aria-label="상품 URL" />
                <button type="button" className="btn btn-ghost btn-sm" disabled={looking || !wlUrl.trim()}
                  onClick={() => void doLookupUrl()}>{looking ? '불러오는 중…' : '불러오기'}</button>
                <label className="btn btn-ghost btn-sm" style={{ cursor: 'pointer' }}>
                  📷 스크린샷
                  <input type="file" accept="image/*" hidden onChange={(e) => void onScreenshot(e)} />
                </label>
              </div>
              {wlHint && <p className="empty">{wlHint}</p>}
              <div className="form-inline" style={{ marginTop: 10 }}>
                {wlImage && <img src={wlImage} alt="" style={{ width: 44, height: 44, borderRadius: 10, objectFit: 'cover' }} />}
                <input className="inp" style={{ flex: 1, minWidth: 130 }} placeholder="상품 이름"
                  value={wlName} onChange={(e) => setWlName(e.target.value)} aria-label="상품 이름" />
                <input className="inp" style={{ width: 120 }} type="number" min={1} placeholder="가격(원)"
                  value={wlPrice} onChange={(e) => setWlPrice(e.target.value)} aria-label="가격(원)" />
                <button type="button" className="btn btn-primary btn-sm" onClick={() => void doAddWishlist()}>담기</button>
              </div>
            </div>

            {s.wishlist.length > 0 ? s.wishlist.map((w) => (
              <div className="mcard" key={w.id}>
                <div className="mtop">
                  {w.imageUrl
                    ? <img src={w.imageUrl} alt="" className="mic" style={{ objectFit: 'cover' }} />
                    : <span className="mic" style={{ background: 'var(--bg)' }} aria-hidden="true">🛒</span>}
                  <span className="mtx"><b>{w.name}</b><span>{won(w.price)}</span></span>
                  <button type="button" className="icon-btn" aria-label={`${w.name} 삭제`}
                    onClick={() => void run(api.deleteWishlist(userId, w.id))}>✕</button>
                </div>
                <div style={{ display: 'flex', gap: 8, marginTop: 11 }}>
                  <button type="button" className="btn btn-primary btn-sm" style={{ flex: 1 }}
                    onClick={() => void run(api.wishlistNotBought(userId, w.id))}>안 샀어요 💪</button>
                  <button type="button" className="btn btn-ghost btn-sm" style={{ flex: 1 }}
                    onClick={() => void run(api.wishlistBought(userId, w.id))}>샀어요</button>
                </div>
              </div>
            )) : <div className="card"><Empty>사고 싶은 상품을 담아두고, 안 사면 그만큼 저축해 보세요.</Empty></div>}

            {/* 소비 기록 · 참기 */}
            <SectionTitle aux="솔직하게 기록만 해도 충분해요">소비 기록</SectionTitle>
            <form className="card" onSubmit={(e) => {
              e.preventDefault();
              if (!spendAmt) return;
              void run(api.spend(userId, spendCat, Number(spendAmt), spendNeed)).then(() => setSpendAmt(''));
            }}>
              <div className="form-inline">
                <select className="inp" style={{ width: 140 }} value={spendCat}
                  onChange={(e) => setSpendCat(e.target.value)} aria-label="카테고리">
                  {cats.map((c) => <option key={c.code} value={c.code}>{c.displayName}</option>)}
                </select>
                <input className="inp" style={{ flex: 1, minWidth: 110 }} type="number" min={1} placeholder="금액"
                  value={spendAmt} onChange={(e) => setSpendAmt(e.target.value)} aria-label="금액" />
              </div>
              <div className="seg" style={{ marginTop: 10 }} role="group" aria-label="소비 성격">
                <button type="button" className={spendNeed ? 'on' : ''} aria-pressed={spendNeed}
                  onClick={() => setSpendNeed(true)}>가치 소비</button>
                <button type="button" className={!spendNeed ? 'on' : ''} aria-pressed={!spendNeed}
                  onClick={() => setSpendNeed(false)}>습관 소비</button>
              </div>
              <div style={{ display: 'flex', gap: 8, marginTop: 10 }}>
                <button className="btn btn-ghost btn-sm" type="submit" style={{ flex: 1 }}>소비 기록</button>
                <button className="btn btn-primary btn-sm" type="button" style={{ flex: 1 }}
                  disabled={!spendAmt}
                  onClick={() => void run(api.avoid(userId, spendCat, Number(spendAmt))).then(() => setSpendAmt(''))}>
                  살 뻔했다 💪
                </button>
              </div>
            </form>

            {/* 최근 활동 */}
            {s.recentEvents.length > 0 && (
              <>
                <SectionTitle>최근 활동</SectionTitle>
                <div className="card" style={{ padding: '10px 18px' }}>
                  {s.recentEvents.map((e, i) => (
                    <div className="txn" key={i}>
                      <span className="m">{eventText(e)}</span>
                      {e.categoryCode && <span className="c">{catLabel(e.categoryCode)}</span>}
                      <span className="a" style={{ color: e.type === 'WITHDRAWAL' ? 'var(--red)' : 'var(--green)' }}>
                        {e.type === 'WITHDRAWAL' ? '−' : '+'}{won(e.amount)}
                      </span>
                      <span className="d" style={{ width: 78, textAlign: 'right' }}>{shortDateTime(e.occurredAt)}</span>
                    </div>
                  ))}
                </div>
              </>
            )}
          </>
        )}
        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
