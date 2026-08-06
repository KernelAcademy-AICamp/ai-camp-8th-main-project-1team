/**
 * TX-01 거래 내역 — 연결한 모든 카드의 결제를 월별로 모아 본다(§13-11).
 * 결제에 실린 사업자등록번호로 가맹점 주소를 눌러서 조회할 수 있다(§13).
 * 상단 '동기화'는 마이데이터에서 새 결제를 당겨오고 지킴이 원장에도 반영한다.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty } from '../components/ui';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { autoSyncMyData } from '../state/autoSync';
import { useAsync } from '../state/useAsync';
import { api, catLabel, type MyMerchant } from '../lib/api';
import { SpendCalendar } from '../components/SpendCalendar';
import { Icon } from '../components/Icons';
import { won, shortDate, monthLabel, inkColor } from '../lib/format';

type SpendFilter = 'all' | 'disc' | 'fixed' | 'sanct';
/** 개편안의 필터 4종. '재량'은 성역·고정지출을 뺀 나머지다 — 줄일 수 있는 것만 남긴다. */
const SPEND_FILTERS: { key: SpendFilter; label: string }[] = [
  { key: 'all', label: '전체' },
  { key: 'disc', label: '재량' },
  { key: 'fixed', label: '고정지출' },
  { key: 'sanct', label: '성역' },
];
/** 고정지출로 보는 중분류 — 달마다 같은 금액이 나가 줄이기 어려운 것들. */
// 고정지출 판정은 **서버가 한다**(`/api/analysis` 의 recurring). 예전에는 여기에
// `new Set(['주거/통신'])` 이 박혀 있었는데, 그러면 넷플릭스처럼 취미/여가로 분류된 구독은
// 매달 같은 날 같은 금액이 나가도 영영 '고정'이 안 붙는다(2026-08-05 실사용자에서 확인).
// 카테고리 이름을 화면에 박지 않는다 — 마스터 §4 원칙 4.

/** 사업자등록번호 10자리 → XXX-YY-ZZZZZ 표시. */
/** '카테고리없음'인가 — 이름을 코드에 박지 않기 위해 한 곳에 둔다. */
const isNone = (c: string | null | undefined) => !c || c === '카테고리없음';
const bizFmt = (b: string) => (b.length === 10 ? `${b.slice(0, 3)}-${b.slice(3, 5)}-${b.slice(5)}` : b);

export function Transactions() {
  const { back, userId, analysis } = useSession();
  const { home, reload: reloadGuardian } = useGuardian();
  // 12개월 — 6개월로 두면 실데이터(1월부터)의 앞부분이 통째로 안 보인다. 카드 명세서는
  // 보통 1년치를 내려받으므로 창이 그보다 짧으면 넣은 것을 못 보는 일이 생긴다(2026-08-05).
  const payments = useAsync(() => api.allPayments(userId, 12), [userId]);
  const [syncing, setSyncing] = useState(false);
  const [syncMsg, setSyncMsg] = useState<string | null>(null);
  const [merchantOf, setMerchantOf] = useState<Record<string, MyMerchant | 'loading'>>({});
  /** 달력에서 고른 날. null이면 전체 기간. */
  const [pickedDate, setPickedDate] = useState<string | null>(null);
  const [filter, setFilter] = useState<SpendFilter>('all');
  /**
   * 가맹점 이름 검색 (프로토타입_0806 `s-spend`). null 이면 검색 모드가 아니다.
   *
   * <b>검색 중에는 달력을 접는다.</b> 검색은 목록 전체를 다시 훑는 일인데 달력이 남아 있으면
   * "이 달 안에서만 찾나"로 읽힌다. 날짜 필터도 함께 푼다 — 두 필터가 겹치면 왜 안 나오는지 모른다.
   */
  const [query, setQuery] = useState<string | null>(null);
  /** 검색 입력칸 — 돋보기를 누르면 초점을 놓아 키보드를 접는다. */
  const inputRef = useRef<HTMLInputElement>(null);
  /** 카테고리를 고치는 중인 결제. 한 번에 한 줄만 연다. */
  const [editing, setEditing] = useState<string | null>(null);
  /** 이미 고친 것 — 목록을 다시 불러오기 전까지 화면에 바로 반영한다. */
  const [fixed, setFixed] = useState<Record<string, string>>({});
  // 고를 수 있는 중분류. **`/unclassified` 를 부르면 안 된다** — 그쪽은 들를 때마다 LLM 추정을
  // 돌리는 경로라, 목록 하나 얻자고 부르면 화면 진입마다 호출이 나간다.
  const cats = useAsync(() => api.categories().then((cs) => cs.map((c) => c.code)).catch(() => [] as string[]), []);
  // 세션에 분석이 없을 수도 있다(온보딩을 안 거치고 들어온 경우). 그때는 직접 부른다 —
  // 없으면 '고정' 태그가 통째로 안 나오는데, 화면은 그것을 오류로 보여주지 않으므로
  // 조용히 비어 버린다.
  const an = useAsync(
    () => (analysis ? Promise.resolve(analysis) : api.analysis(userId).catch(() => null)),
    [userId, analysis]);

  // 서버가 잡은 고정 결제 — 가맹점명(없으면 중분류)으로 맞춘다.
  const fixedOf = useMemo(() => {
    const set = new Set<string>();
    (an.data?.recurring ?? [])
      .filter((r) => r.type === 'FIXED')
      .forEach((r) => set.add(r.merchantName ?? r.category2));
    return (p: { merchantName: string | null; category: string; category2: string | null }) =>
      set.has(p.merchantName ?? '') || set.has(p.category2 ?? p.category);
  }, [an.data]);

  /** 사용자가 확정한다 — **이 한 번이 사전에 쌓여 다음부터 안 묻는다.** */
  async function confirmCategory(paymentId: string, category2: string) {
    setFixed((prev) => ({ ...prev, [paymentId]: category2 }));
    setEditing(null);
    try { await api.confirmCategory(userId, paymentId, category2); }
    catch { setFixed((prev) => { const n = { ...prev }; delete n[paymentId]; return n; }); }
  }

  // 달력에 얹을 값 — 날짜별 지출 합계와 '지킨 날'.
  // 지킨 날은 지킴이가 판정한 사실이라 여기서 다시 계산하지 않고 홈이 준 잔디를 그대로 쓴다.
  const totalsByDate = useMemo(() => {
    const out: Record<string, number> = {};
    for (const p of payments.data ?? []) {
      const d = p.date.slice(0, 10);
      out[d] = (out[d] ?? 0) + p.amount;
    }
    return out;
  }, [payments.data]);
  const keptDates = useMemo(
    () => new Set((home?.grass ?? [])
      .filter((g) => g.result === 'NO_SPEND_DAY' || g.result === 'ON_PACE_DAY')
      .map((g) => g.date)),
    [home],
  );
  /** 성역·고정지출 판정에 쓸 카테고리 집합 — 챌린지가 정한 것을 그대로 본다. */
  const sanctuary = useMemo(() => new Set(home?.challenge?.sanctuaryCategories ?? []), [home]);

  const months = useMemo(() => {
    const all = payments.data ?? [];
    const q = (query ?? '').trim().toLowerCase();
    const rows = all.filter((p) => {
      // **검색 중에는 이름만 본다.** 날짜·성격 필터를 함께 걸면, 찾는 가게가 안 나올 때
      // 그 가게가 없는 건지 필터에 걸린 건지 알 수가 없다.
      if (q) return (p.merchantName ?? '').toLowerCase().includes(q);
      if (pickedDate && p.date.slice(0, 10) !== pickedDate) return false;
      if (filter === 'all') return true;
      const sanct = p.category ? sanctuary.has(p.category) : false;
      if (filter === 'sanct') return sanct;
      const fixed = fixedOf(p);
      if (filter === 'fixed') return fixed;
      return !sanct && !fixed;      // 재량 = 성역도 고정지출도 아닌 것
    });
    const byMonth: Record<string, typeof rows> = {};
    for (const p of rows) (byMonth[p.date.slice(0, 7)] ??= []).push(p);
    return Object.keys(byMonth).sort((a, b) => b.localeCompare(a)).map((m) => ({
      key: m,
      rows: byMonth[m].slice().sort((a, b) => b.date.localeCompare(a.date)),
      total: byMonth[m].reduce((s, p) => s + p.amount, 0),
    }));
  }, [payments.data, pickedDate, filter, sanctuary, fixedOf, query]);

  // 화면에 들어오면 새 결제를 조용히 당겨온다. 목록을 먼저 그리고 결과가 오면 그때 다시 부른다 —
  // 상단 '동기화' 버튼은 결과 문구가 필요한 수동 경로라 그대로 둔다.
  useEffect(() => {
    let alive = true;
    void autoSyncMyData(userId).then((n) => {
      if (n > 0 && alive) { payments.reload(); void reloadGuardian(); }
    });
    return () => { alive = false; };
    // payments.reload는 useAsync가 매 렌더 새로 만들 수 있어 의존성에 넣지 않는다(진입당 한 번).
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [userId]);

  async function lookupMerchant(bizno: string) {
    if (merchantOf[bizno]) return;
    setMerchantOf((prev) => ({ ...prev, [bizno]: 'loading' }));
    try {
      const m = await api.merchant(bizno);
      setMerchantOf((prev) => {
        const next = { ...prev };
        if (m) next[bizno] = m; else delete next[bizno];
        return next;
      });
    } catch {
      setMerchantOf((prev) => { const next = { ...prev }; delete next[bizno]; return next; });
    }
  }

  async function doSync() {
    setSyncing(true); setSyncMsg(null);
    try {
      const r = await api.syncMyData(userId);
      setSyncMsg(r.newPayments > 0 ? `새 결제 ${r.newPayments}건을 불러왔어요` : '이미 최신 상태예요');
      if (r.newPayments > 0) { payments.reload(); await reloadGuardian(); }
    } catch (e) {
      setSyncMsg(e instanceof Error ? e.message : String(e));
    } finally {
      setSyncing(false);
    }
  }

  const total = payments.data?.length ?? 0;
  /** 지금 목록에 보이는 건수 — 검색 머리글이 말하는 수. */
  const hits = months.reduce((s, m) => s + m.rows.length, 0);

  return (
    <Screen title="거래 내역" hasTabBar>
      {query === null ? (
        <AppBar onBack={back} title="거래 내역" action={
          <button type="button" className="act" onClick={() => void doSync()} disabled={syncing}>
            {syncing ? '동기화 중…' : '동기화'}
          </button>
        } />
      ) : (
        <>
          {/* 검색 모드 — 앱바가 통째로 입력칸이 된다(개편안 `.sp-abar`). */}
          <div className="appbar sp-abar">
            <button type="button" className="back" onClick={() => setQuery(null)}
              aria-label="검색 닫기">‹</button>
            <input ref={inputRef} className="sp-ipt" type="text" placeholder="가맹점 이름"
              autoComplete="off" autoFocus value={query} onChange={(e) => setQuery(e.target.value)}
              aria-label="가맹점 이름으로 검색" />
            {query !== '' && (
              <button type="button" className="sp-clr" onClick={() => setQuery('')}
                aria-label="입력 지우기"><span><Icon id="i-x" /></span></button>
            )}
            {/* 오른쪽 끝 돋보기(개편안 `.sp-mag`) — 누르면 지금 적은 말로 다시 찾는다.
                입력할 때마다 걸러지므로 없어도 되지만, 키보드를 접고 결과를 보고 싶을 때
                누를 곳이 필요하다. */}
            <button type="button" className="sp-mag" aria-label="검색"
              onClick={() => inputRef.current?.blur()}>
              <Icon id="i-search" />
            </button>
          </div>
          <div className="sp-abar-line" />
        </>
      )}
      {/* 달력 (개편안 `.cal`) — 날짜별 지출과 지킨 날. 누르면 그날만 본다.
          검색 중에는 접는다 — 달력이 남아 있으면 "이 달 안에서만 찾나"로 읽힌다. */}
      {home && query === null && (
        <SpendCalendar
          today={home.asOf.slice(0, 10)}
          totalsByDate={totalsByDate}
          keptDates={keptDates}
          selected={pickedDate}
          onSelect={setPickedDate}
        />
      )}
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>
        {/* 필터 칩 (개편안 `.fchips`) — 성역·고정지출을 걷어내고 '내가 줄일 수 있는 것'만 보는 용도.
            검색 진입은 그 줄 끝에 둔다. 개편안은 달력 머리에 뒀는데, 이 앱은 달력이 접힐 수
            있어 거기 두면 접었을 때 검색까지 사라진다. */}
        {query === null && (
          <div style={{ display: 'flex', alignItems: 'center', gap: 8 }}>
            <div className="fchips" style={{ flex: 1, marginBottom: 0 }}>
              {SPEND_FILTERS.map((f) => (
                <button key={f.key} type="button" className={filter === f.key ? 'on' : ''}
                  aria-pressed={filter === f.key} onClick={() => setFilter(f.key)}>
                  {f.label}
                </button>
              ))}
            </div>
            <button type="button" className="cal-search" onClick={() => setQuery('')}
              aria-label="가맹점 이름으로 검색"><Icon id="i-search" /></button>
          </div>
        )}
        {pickedDate && (
          <button type="button" className="btn btn-ghost btn-sm" style={{ marginBottom: 12 }}
            onClick={() => setPickedDate(null)}>
            {shortDate(pickedDate)}만 보는 중 · 전체 보기
          </button>
        )}
        {/* 검색 중에는 전체 건수 대신 **찾은 건수**를 말한다 — 눈앞의 목록은 걸러진 것인데
            머리글만 총 2,398건이라 하면 목록이 잘려 보인 것처럼 읽힌다. */}
        <p className="h-sub" style={{ margin: '0 0 12px' }} role={query !== null ? 'status' : undefined}>
          {query !== null
            ? (query.trim()
              ? `'${query.trim()}' 검색 결과 ${hits.toLocaleString('ko-KR')}건이에요.`
              : '찾을 가맹점 이름을 적어주세요.')
            : <>연결한 모든 카드의 최근 6개월 결제예요{total ? ` · 총 ${total.toLocaleString('ko-KR')}건` : ''}.</>}
          {syncMsg && <span role="status" style={{ display: 'block', marginTop: 4, color: 'var(--blue-t)' }}>· {syncMsg}</span>}
        </p>

        <ErrorBox error={payments.error} onRetry={payments.reload} />
        {payments.loading && <Loading label="결제 내역을 불러오는 중" rows={6} />}
        {!payments.loading && total === 0 && !payments.error && (
          <div className="card"><Empty>불러온 결제내역이 없어요. 마이 &gt; 연결 관리에서 기관을 연결해 보세요.</Empty></div>
        )}
        {/* 찾았는데 없는 것과, 애초에 없는 것은 다른 말이다. */}
        {query !== null && query.trim() !== '' && hits === 0 && !payments.loading && (
          <div className="card"><Empty>그 이름으로는 결제내역이 없어요. 가게 이름의 일부만 적어도 찾아요.</Empty></div>
        )}

        {months.map((m) => (
          <div className="card" key={m.key} style={{ padding: '14px 18px' }}>
            <div className="month-head">
              <b>{monthLabel(m.key)}</b>
              <span className="muted small">{m.rows.length}건 · {won(m.total)}</span>
            </div>
            {m.rows.map((p) => {
              const found = p.businessNumber ? merchantOf[p.businessNumber] : undefined;
              return (
                <div key={p.paymentId} className="txn-item">
                  <div className="txn">
                    <span className="d">{shortDate(p.date)}</span>
                    <span className="m">
                      {p.merchantName ?? catLabel(p.category2 ?? p.category)}
                      {/* 중분류를 함께 보여준다 — 가맹점명만으로는 이 결제가 어느 카테고리로
                          집계됐는지 알 수 없어, 리포트 숫자와 목록을 맞춰 볼 방법이 없었다.
                          확정이 없고 추정만 있으면 **눌러서 확정**할 수 있게 한다 — 확정 화면을
                          따로 찾아가야만 고칠 수 있으면, 추정은 영영 '카테고리없음'으로 남는다. */}
                      {(() => {
                        const shown = fixed[p.paymentId] ?? p.category2 ?? p.category;
                        const guess = !fixed[p.paymentId] && isNone(shown) ? p.category2Llm : null;
                        const label = guess ?? shown;
                        if (!label) return null;
                        return (
                          <button type="button"
                            onClick={(e) => { e.stopPropagation(); setEditing(editing === p.paymentId ? null : p.paymentId); }}
                            className="sp-tag"
                            style={{ border: 'none', cursor: 'pointer', fontFamily: 'inherit',
                                     background: guess ? 'var(--blue-weak)' : 'var(--bg2)',
                                     color: guess ? 'var(--blue-t)' : 'var(--t3)' }}>
                            {guess ? `AI 추정 · ${catLabel(guess)}` : catLabel(label)} ✎
                          </button>
                        );
                      })()}
                      {/* 성역·고정지출은 표시해 준다(개편안 `.sp-tag`) — 왜 이 결제가 챌린지에서
                          빠지는지 목록에서 바로 보여야 사용자가 판정을 의심하지 않는다. */}
                      {p.category && sanctuary.has(p.category) && <span className="sp-tag tag-sanct">성역</span>}
                      {fixedOf(p) && <span className="sp-tag tag-fixed">고정</span>}
                    </span>
                    {/* 테두리는 브랜드 원색, 글자는 흰 바탕에서 읽히도록 눌러 쓴다.
                        KB국민 노랑을 글자에 그대로 쓰면 1.69:1 이라 안 보인다(KWCAG 5.4.3). */}
                    {p.cardName && (
                      <span className="c" style={{ border: `1px solid ${p.cardColor || 'var(--line)'}`, color: inkColor(p.cardColor), background: 'transparent' }}>
                        {p.cardName}
                      </span>
                    )}
                    <span className="a">{won(p.amount)}</span>
                  </div>
                  {editing === p.paymentId && (
                    <div style={{ padding: '6px 0 10px', display: 'flex', flexWrap: 'wrap', gap: 6 }}>
                      {p.category2Llm && (
                        <button type="button" onClick={() => void confirmCategory(p.paymentId, p.category2Llm!)}
                          style={{ padding: '6px 11px', borderRadius: 16, cursor: 'pointer', fontFamily: 'inherit',
                                   fontSize: 12, fontWeight: 700, border: '1px solid var(--blue)',
                                   background: 'var(--blue-weak)', color: 'var(--blue-t)' }}>
                          맞아요 · {catLabel(p.category2Llm)}
                        </button>
                      )}
                      {(cats.data ?? []).filter((c: string) => c !== p.category2Llm).map((c: string) => (
                        <button type="button" key={c} onClick={() => void confirmCategory(p.paymentId, c)}
                          style={{ padding: '6px 11px', borderRadius: 16, cursor: 'pointer', fontFamily: 'inherit',
                                   fontSize: 12, fontWeight: 600, border: '1px solid var(--line)',
                                   background: 'var(--card)', color: 'var(--t2)' }}>
                          {catLabel(c)}
                        </button>
                      ))}
                    </div>
                  )}
                  {p.businessNumber && (
                    <div className="txn-biz">
                      {/* 보이는 글자는 번호뿐이지만, 눌러서 주소를 조회하는 컨트롤이라
                          화면낭독기에는 무엇을 하는 버튼인지 aria-label로 알려준다. */}
                      <button type="button" className="biz-link"
                        onClick={() => void lookupMerchant(p.businessNumber!)}
                        aria-label={`사업자등록번호 ${bizFmt(p.businessNumber)} — 가맹점 주소 조회`}>
                        {bizFmt(p.businessNumber)}
                      </button>
                      {found === 'loading' && <span className="biz-addr">주소 조회중…</span>}
                      {found && found !== 'loading' && (
                        <span className="biz-addr">📍 {found.address}{found.online ? ' (본사)' : ''}</span>
                      )}
                    </div>
                  )}
                </div>
              );
            })}
          </div>
        ))}

        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
