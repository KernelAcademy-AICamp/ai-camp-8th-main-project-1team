/**
 * TX-01 거래 내역 — 연결한 모든 카드의 결제를 월별로 모아 본다(§13-11).
 * 결제에 실린 사업자등록번호로 가맹점 주소를 눌러서 조회할 수 있다(§13).
 * 상단 '동기화'는 마이데이터에서 새 결제를 당겨오고 지킴이 원장에도 반영한다.
 */
import { useEffect, useMemo, useRef, useState } from 'react';
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty } from '../components/ui';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { autoSyncMyData, POLL_MS } from '../state/autoSync';
import { useAsync } from '../state/useAsync';
import { api, catLabel, type MyMerchant, type MyPaymentHistory } from '../lib/api';
import { SpendCalendar } from '../components/SpendCalendar';
import { Icon } from '../components/Icons';
import { won, iconOf, inkColor } from '../lib/format';

/** 검색 기간 사다리 — 3 · 6 · 9 · 12개월(개편안 `SP_FROMS`). */
const SPANS = [3, 6, 9, 12];
/** `span` 칸이 훑는 구간의 시작일(YYYY-MM-DD). */
function spanFrom(asOf: string, span: number): string {
  const d = new Date(`${asOf.slice(0, 10)}T00:00:00`);
  d.setMonth(d.getMonth() - SPANS[Math.min(span, SPANS.length - 1)]);
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`;
}
/** "2026.04.25" — 기간 안내에 쓰는 표기. */
const dot = (ymd: string) => ymd.replace(/-/g, '.');
const DOW = ['일요일', '월요일', '화요일', '수요일', '목요일', '금요일', '토요일'];
/** "7월 30일 목요일" — 날짜 묶음 머리(개편안 `.day-t`). 요일을 줄이지 않는다. */
function dayLabel(ymd: string): string {
  const d = new Date(`${ymd}T00:00:00`);
  return `${d.getMonth() + 1}월 ${d.getDate()}일 ${DOW[d.getDay()]}`;
}

/**
 * 찾은 글자만 파랗게(개편안 `.hl`).
 *
 * 어디가 걸려서 이 줄이 나왔는지 보여야, 엉뚱해 보이는 결과도 이유가 설명된다.
 */
function highlight(name: string, q: string) {
  if (!q) return name;
  const i = name.toLowerCase().indexOf(q.toLowerCase());
  if (i < 0) return name;
  return (
    <>
      {name.slice(0, i)}<em className="hl">{name.slice(i, i + q.length)}</em>{name.slice(i + q.length)}
    </>
  );
}

/**
 * 줄에 굵게 적을 이름 — **서버가 결제 행에 적어 둔 값**을 그대로 쓴다(V44).
 *
 * 화면이 다시 계산하지 않는다. 표시명은 언제나 원문의 부분집합이라 지어낸 것이 아니고,
 * 그 판단은 `MerchantDisplayName` 한 곳에만 있다.
 */
function shownName(p: MyPaymentHistory): string | null {
  return p.displayName ?? p.brand ?? p.merchantName ?? null;
}
/** 결제 경로만 아는 줄인가 — 카드사가 준 정보가 "간편결제로 샀다" 뿐인 결제. */
const viaOnly = (p: MyPaymentHistory) => p.displayNameSource === 'AGENCY_ONLY';

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

/** '카테고리없음'인가 — 이름을 코드에 박지 않기 위해 한 곳에 둔다. */
const isNone = (c: string | null | undefined) => !c || c === '카테고리없음';
/** 사업자등록번호 10자리 → XXX-YY-ZZZZZ 표시. */
const bizFmt = (b: string) => (b.length === 10 ? `${b.slice(0, 3)}-${b.slice(3, 5)}-${b.slice(5)}` : b);

export function Transactions() {
  const { back, userId, analysis, view, setView } = useSession();
  const { home, reload: reloadGuardian } = useGuardian();
  // 12개월 — 6개월로 두면 실데이터(1월부터)의 앞부분이 통째로 안 보인다. 카드 명세서는
  // 보통 1년치를 내려받으므로 창이 그보다 짧으면 넣은 것을 못 보는 일이 생긴다(2026-08-05).
  const payments = useAsync(() => api.allPayments(userId, 12), [userId]);
  const [syncMsg, setSyncMsg] = useState<string | null>(null);
  const [merchantOf, setMerchantOf] = useState<Record<string, MyMerchant | 'loading'>>({});
  /**
   * 자세히를 펼친 줄.
   *
   * **평소에는 숨긴다.** 원문 상호·사업자번호·주소는 <b>따져 볼 때만</b> 필요한 값인데
   * 늘 적어 두면 보조줄이 잡동사니가 되고 줄인 이름이 무의미해진다(2026-08-26 화면 확인).
   * 여는 손잡이는 **카드사 이름**이다 — 새 버튼을 만들면 줄이 또 하나 늘어난다.
   */
  const [detailOpen, setDetailOpen] = useState<Record<string, boolean>>({});
  /** 달력에서 고른 날. null이면 전체 기간. */
  const [pickedDate, setPickedDate] = useState<string | null>(null);
  /**
   * <b>갈래는 주소가 정본이다</b>(`?filter=…`). `useState` 로 들면 뒤로가기가 이 자리를
   * 되살리지 못한다 — 리포트의 주간→월간이 그래서 이력에 한 칸도 안 쌓였고, 다른 화면에
   * 갔다 뒤로 오면 초기값으로 튕겼다(2026-08-25 신고).
   */
  const filter = (view.filter ?? 'all') as SpendFilter;
  const setFilter = (next: SpendFilter) => setView(next === 'all' ? {} : { filter: next });
  /**
   * 가맹점 이름 검색 (프로토타입_0806 `s-spend`). null 이면 검색 모드가 아니다.
   *
   * <b>검색 중에는 달력을 접는다.</b> 검색은 목록 전체를 다시 훑는 일인데 달력이 남아 있으면
   * "이 달 안에서만 찾나"로 읽힌다. 날짜 필터도 함께 푼다 — 두 필터가 겹치면 왜 안 나오는지 모른다.
   */
  const [query, setQuery] = useState<string | null>(null);
  /** 검색 입력칸 — 돋보기를 누르면 초점을 놓아 키보드를 접는다. */
  const inputRef = useRef<HTMLInputElement>(null);
  /**
   * 검색이 훑는 기간 — 0이 3개월, 한 칸 올릴 때마다 3개월씩 늘어난다(개편안 `SP_FROMS`).
   *
   * 처음부터 1년을 훑지 않는 이유: 찾는 가게는 대개 최근에 간 곳이고, 오래된 동명 가게가
   * 위에 섞이면 오히려 못 찾는다. 부족하면 '내역 더 보기'로 넓힌다.
   */
  const [span, setSpan] = useState(0);
  const scrollRef = useRef<HTMLDivElement>(null);
  const [scrolled, setScrolled] = useState(false);
  const [toast, setToast] = useState<string | null>(null);
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
  /** '오늘'은 서버가 정한다 — 데모 시계를 켜면 브라우저 시계와 다르다. */
  const asOf = home?.asOf?.slice(0, 10) ?? new Date().toISOString().slice(0, 10);

  /** 성역·고정지출 판정에 쓸 카테고리 집합 — 챌린지가 정한 것을 그대로 본다. */
  const sanctuary = useMemo(() => new Set(home?.challenge?.sanctuaryCategories ?? []), [home]);

  /**
   * 화면에 그릴 <b>날짜 묶음</b>.
   *
   * <b>월이 아니라 날로 묶는다</b>(개편안 `.day-t` + `.sp-card`). 달력에서 날짜를 누르면 그 줄로
   * 굴러가야 하는데, 월로 묶으면 굴러갈 자리가 달마다 하나뿐이라 날짜를 짚을 수가 없다.
   *
   * <b>검색은 이름만 본다.</b> 날짜·성격 필터를 함께 걸면, 찾는 가게가 안 나올 때 그 가게가
   * 없는 건지 필터에 걸린 건지 알 수가 없다. 검색어를 안 적었으면 <b>아무것도 안 보인다</b> —
   * 전체 목록을 다시 보여 주면 검색에 들어온 것인지 아닌지가 흐려진다.
   */
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

  const days = useMemo(() => {
    const all = payments.data ?? [];
    const q = (query ?? '').trim().toLowerCase().replace(/\s/g, '');
    if (query !== null && !q) return [];        // 검색 중인데 아직 안 적었다
    const limit = query !== null ? spanFrom(asOf, span) : null;
    const rows = all.filter((p) => {
      if (q) {
        if (limit && p.date.slice(0, 10) < limit) return false;
        // **브랜드도 찾는다.** 상호에 브랜드 글자가 안 들어 있는 결제가 있다 —
        // `에프알엘코리아 주식회사`(유니클로)·`비지에프리테일`(CU) 처럼 운영사 이름으로
        // 찍히는 자리다. 보이는 이름으로 검색이 안 되면 사용자는 없는 줄 안다.
        const hay = `${p.merchantName ?? ''}${p.brand ?? ''}${p.displayName ?? ''}`
          .toLowerCase().replace(/\s/g, '');
        return hay.includes(q);
      }
      if (filter === 'all') return true;
      const sanct = p.category ? sanctuary.has(p.category) : false;
      if (filter === 'sanct') return sanct;
      const fixed = fixedOf(p);
      if (filter === 'fixed') return fixed;
      return !sanct && !fixed;      // 재량 = 성역도 고정지출도 아닌 것
    });
    const byDay: Record<string, typeof rows> = {};
    for (const p of rows) (byDay[p.date.slice(0, 10)] ??= []).push(p);
    return Object.keys(byDay).sort((a, b) => b.localeCompare(a)).map((d) => ({
      key: d,
      rows: byDay[d].slice().sort((a, b) => b.date.localeCompare(a.date)),
    }));
  }, [payments.data, filter, sanctuary, fixedOf, query, span, asOf]);

  // 최신 갱신 함수를 타이머가 붙잡고 있게 한다. 타이머는 화면이 열려 있는 내내 살아 있어서,
  // 처음 렌더의 함수를 그대로 쥐고 있으면 그 사이 바뀐 것(reloadGuardian은 linked·userId에
  // 매여 있다)을 놓친다. payments.reload는 useAsync가 useCallback([])으로 고정해 두지만,
  // 둘을 같은 방식으로 다루는 편이 나중에 한쪽만 바뀌어도 안전하다.
  const reloadRef = useRef({ payments: payments.reload, guardian: reloadGuardian });
  reloadRef.current = { payments: payments.reload, guardian: reloadGuardian };

  // 화면을 보고 있는 동안 새 결제를 조용히 당겨온다. 목록을 먼저 그리고 결과가 오면 그때 다시 부른다 —
  // 상단 '동기화' 버튼은 결과 문구가 필요한 수동 경로라 그대로 둔다.
  //
  // **진입 때 한 번이 아니라 POLL_MS 간격으로 계속이다.** 예전에는 여기가 진입 1회뿐이라
  // 가만히 보고 있으면 목록이 영영 안 늘었다 — 마이데이터 커트오프가 지나 새 결제가 생겨도
  // 화면을 다시 열기 전에는 알 수 없었다. 서버 배치(5분)를 줄여도 이 화면은 안 바뀌므로,
  // 고칠 자리는 주기가 아니라 여기다.
  useEffect(() => {
    let alive = true;
    const pull = () => {
      void autoSyncMyData(userId).then((n) => {
        // 개편안에는 '동기화' 버튼이 없다. 조용히 당겨오되 **새로 들어온 것이 있으면 말해 준다** —
        // 목록이 소리 없이 늘어나면 사용자는 자기가 뭘 잘못 봤나 하게 된다.
        if (n > 0 && alive) {
          setSyncMsg(`새 결제 ${n}건을 불러왔어요`);
          reloadRef.current.payments(); void reloadRef.current.guardian();
        }
      });
    };
    pull();
    // **숨은 탭에서는 돌리지 않는다.** 안 보는 화면 때문에 외부 서버를 두드리는 것은 낭비고,
    // 모바일에서는 배터리로 돌아온다. 대신 돌아오는 순간 곧바로 한 번 당겨 기다리게 하지 않는다
    // (그때는 스로틀이 마지막 성공 시각을 보고 알아서 걸러 준다).
    const timer = setInterval(() => { if (!document.hidden) pull(); }, POLL_MS);
    const onVisible = () => { if (document.visibilityState === 'visible') pull(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => {
      alive = false;
      clearInterval(timer);
      document.removeEventListener('visibilitychange', onVisible);
    };
  }, [userId]);

  /**
   * 달력에서 날짜를 누르면 <b>그 날짜 줄로 굴러간다</b>.
   *
   * <b>거르지 않는다.</b> 예전에는 그날 것만 남겼는데, 그러면 앞뒤로 훑을 수가 없어
   * "그날 근처를 보고 싶다"는 원래 목적을 못 이룬다. 개편안도 목록은 통째로 두고 위치만 옮긴다.
   * 표적이 없으면(그날 결제가 없으면) 아무 데도 안 간다 — 엉뚱한 데로 굴러가는 것보다 낫다.
   */
  function goToDate(date: string | null) {
    setPickedDate(date);
    if (!date) return;
    // `CSS.escape` 로 셀렉터를 만들면 안 된다 — id 안의 "2026…"이 숫자로 시작해
    // `\32 026…` 으로 바뀌고, 멀쩡한 표적을 못 찾는다. id 로 직접 찾는다.
    const target = document.getElementById(`dg-${date}`);
    // 스크롤 컨테이너를 손으로 재지 않는다 — 브라우저가 알아서 조상 중 스크롤되는 것을 찾는다.
    // ref 로 컨테이너를 잡아 좌표를 계산하던 방식은 ref 가 비면 조용히 아무것도 안 했다.
    target?.scrollIntoView({ behavior: 'smooth', block: 'start' });
  }

  const toastTimer = useRef<number | undefined>(undefined);
  function say(msg: string) {
    setToast(msg);
    window.clearTimeout(toastTimer.current);
    toastTimer.current = window.setTimeout(() => setToast(null), 1800);
  }
  useEffect(() => () => window.clearTimeout(toastTimer.current), []);

  const total = payments.data?.length ?? 0;

  return (
    /* **이 화면만 흰 바탕이다**(개편안 `#s-spend{background:#fff}`). 다른 화면은 회색 바탕에
       흰 카드를 얹지만, 여기는 목록이 화면을 가득 채워 카드 경계가 뜻이 없다. 대신 달력과
       목록 사이를 `.sp-div`(옅은 그라데이션 띠)로 나눈다.

       동기화 버튼은 두지 않는다 — 개편안에 없고, 진입할 때마다 조용히 당겨오고 있다
       (`autoSyncMyData`). 손으로 누를 자리를 두면 "눌러야 최신"으로 읽힌다. */
    <Screen id="spend" title="소비 내역" hasTabBar background="var(--card)" className="sp-white">
      {query === null ? (
        <AppBar onBack={back} title="소비 내역" />
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
      {/* 달력 (개편안 `.cal`) — 날짜별 지출과 지킨 날.
          검색 중에는 접는다 — 달력이 남아 있으면 "이 달 안에서만 찾나"로 읽힌다. */}
      {/* **`home` 을 기다리지 않는다.** 달력은 결제 합계만 있으면 그릴 수 있고, 지킴이가 준
          것은 '지킨 날' 점뿐이다. 예전에는 챌린지가 없으면 달력이 통째로 사라져, 날짜를
          누를 곳도 없었다. */}
      {query === null && (
        <SpendCalendar
          today={asOf}
          totalsByDate={totalsByDate}
          keptDates={keptDates}
          selected={pickedDate}
          onSelect={goToDate}
        >
          <button type="button" className="cal-search" onClick={() => setQuery('')}
            aria-label="가맹점 이름으로 검색"><Icon id="i-search" /></button>
        </SpendCalendar>
      )}
      {/* 달력 아래 그림자 — 목록이 밀려 올라가는 중임을 알린다(개편안 `.sp-shadow`). */}
      <div className={`sp-shadow${scrolled ? ' on' : ''}`} aria-hidden="true" />
      <Scroll ref={scrollRef}
        onScroll={(e) => setScrolled((e.target as HTMLDivElement).scrollTop > 4)}>
        {/* 달력과 목록을 나누는 옅은 띠. 스크롤 안에 있어 함께 밀려 사라진다. */}
        <div className="sp-div" aria-hidden="true" />
        <div className="pad" style={{ paddingTop: 12 }}>
        {/* 필터 칩 (개편안 `.fchips`) — 성역·고정지출을 걷어내고 '내가 줄일 수 있는 것'만 보는 용도.
            검색 진입은 그 줄 끝에 둔다. 개편안은 달력 머리에 뒀는데, 이 앱은 달력이 접힐 수
            있어 거기 두면 접었을 때 검색까지 사라진다. */}
        {query === null && (
          <div className="fchips">
            {SPEND_FILTERS.map((f) => (
              <button key={f.key} type="button" className={filter === f.key ? 'on' : ''}
                aria-pressed={filter === f.key} onClick={() => setFilter(f.key)}>
                {f.label}
              </button>
            ))}
          </div>
        )}
        {/* 검색 중에는 머리글을 두지 않는다 — 개편안이 그렇고, 결과만 보러 들어온 화면이다. */}
        {query === null && (
          <p className="h-sub" style={{ margin: '0 0 12px' }}>
            연결한 모든 카드의 결제예요{total ? ` · 총 ${total.toLocaleString('ko-KR')}건` : ''}.
            {syncMsg && <span role="status" style={{ display: 'block', marginTop: 4, color: 'var(--blue-t)' }}>· {syncMsg}</span>}
          </p>
        )}

        <ErrorBox error={payments.error} onRetry={payments.reload} />
        {payments.loading && <Loading label="결제 내역을 불러오는 중" rows={6} />}
        {!payments.loading && total === 0 && !payments.error && query === null && (
          <div className="card"><Empty>불러온 결제내역이 없어요. 마이 &gt; 연결 관리에서 기관을 연결해 보세요.</Empty></div>
        )}
        {/* 찾았는데 없는 것과, 애초에 없는 것은 다른 말이다. */}
        {query !== null && query.trim() !== '' && days.length === 0 && !payments.loading && (
          <p className="sp-empty">찾는 소비 항목이 없어요<br />가맹점 이름을 다시 확인해 주세요</p>
        )}

        {days.map((m) => (
          <div key={m.key}>
            {/* 날짜만 적고 일별 총액은 두지 않는다(개편안) — 하루 합계는 달력에 이미 있다.
                id 는 달력에서 굴러올 표적이다. */}
            <div className="day-t" id={`dg-${m.key}`}>{dayLabel(m.key)}</div>
            <div className="sp-card">
            {m.rows.map((p) => {
              return (
                <div key={p.paymentId} className="txn-item">
                  {/* 개편안의 줄 구조: 왼쪽에 카테고리 아이콘, 가운데 위에 굵은 가맹점명,
                      그 아래 연한 글씨로 **시각과 사업자번호**, 오른쪽에 금액.
                      날짜는 바로 위 묶음 머리가 이미 말했으므로 줄에서는 시각만 적는다. */}
                  <div className="list-item">
                    <span className="ic" style={{ background: iconOf(catLabel(p.category2 ?? p.category)).bg }}>
                      <Icon id={iconOf(catLabel(p.category2 ?? p.category)).icon} />
                    </span>
                    <div className="tx">
                    {/* **이름 줄은 이름만 갖는다.** 배지를 나란히 두면 긴 상호가 밀려 잘린다 —
                        줄의 주인공이 가장 좁아지는 자리였다(2026-08-26 실측: 이름 48px).
                        카테고리·성역·고정·경유는 아래 줄, 카드사 오른쪽으로 내렸다. */}
                    <b>
                      <span className="nm">
                        {shownName(p)
                          ? highlight(shownName(p)!, (query ?? '').trim())
                          : catLabel(p.category2 ?? p.category)}
                      </span>
                    </b>
                    <span className="sub">
                      {/* **카드사만 적는다.** 상품명(`신한 Deep Dream`)은 13자를 먹는데
                          목록에서 알아야 할 것은 어디에 썼는가다. */}
                      {(p.companyName ?? p.cardName) && (
                        <button type="button" className="cd"
                          style={{ color: inkColor(p.cardColor) }}
                          aria-expanded={!!detailOpen[p.paymentId]}
                          aria-label={`${p.companyName ?? p.cardName} — 원문 가맹점명과 사업자번호 ${detailOpen[p.paymentId] ? '접기' : '보기'}`}
                          onClick={(e) => {
                            e.stopPropagation();
                            setDetailOpen((o) => ({ ...o, [p.paymentId]: !o[p.paymentId] }));
                            if (p.businessNumber) void lookupMerchant(p.businessNumber);
                          }}>
                          {p.companyName ?? p.cardName}
                        </button>
                      )}
                      {/* 중분류를 함께 보여준다 — 가맹점명만으로는 이 결제가 어느 카테고리로
                          집계됐는지 알 수 없어, 리포트 숫자와 목록을 맞춰 볼 방법이 없었다.
                          확정이 없고 추정만 있으면 **눌러서 확정**할 수 있게 한다 — 확정 화면을
                          따로 찾아가야만 고칠 수 있으면, 추정은 영영 '카테고리없음'으로 남는다. */}
                      {(() => {
                        const shown = fixed[p.paymentId] ?? p.category2 ?? p.category;
                        const guess = !fixed[p.paymentId] && isNone(shown) ? p.category2Llm : null;
                        const label = guess ?? shown;
                        if (!label) return null;
                        // **색과 글자가 같은 말을 해야 한다.** 모델도 모른다고 답한 줄은
                        // 'AI 추정'을 안 적으므로 호박색도 쓰지 않는다 — 색만 경고하고
                        // 글자는 아무 말도 안 하면 무슨 뜻인지 알 수 없다.
                        const guessed = !!guess && !isNone(guess);
                        return (
                          <button type="button"
                            onClick={(e) => { e.stopPropagation(); setEditing(editing === p.paymentId ? null : p.paymentId); }}
                            /* **칸을 두르지 않는다.** 배경과 안여백을 주면 열두 자짜리 문구가
                               커다란 알약이 되어 보조줄을 통째로 먹는다 — 글자 크기 그대로여야
                               한다(2026-08-26 화면 확인). 색만으로 충분히 구별된다.
                               **추정은 호박색이다.** 브랜드색(초록)은 "확인됨·좋음"으로 읽히는데
                               뜻은 정반대다 — <b>아직 확정이 아니니 봐 달라</b>. */
                            className="cat"
                            style={{ color: guessed ? 'var(--amber-t)' : 'var(--t3)' }}>
                            {/* **'AI 추정'을 적는다.** 색만으로는 무슨 색인지 배워야 알고,
                                색을 못 보는 사람에게는 아무 말도 안 한다.
                                단, 모델도 모른다고 답했으면 안 적는다 —
                                `AI 추정 · 카테고리없음` 은 열두 자를 먹으면서
                                <i>"AI 가 모른다고 추정했다"</i>는 말이 되어 아무 뜻이 없다. */}
                            {guessed ? `AI 추정 · ${catLabel(guess!)}` : catLabel(label)} ✎
                          </button>
                        );
                      })()}
                      {/* 성역·고정지출은 표시해 준다 — 왜 이 결제가 챌린지에서 빠지는지
                          목록에서 바로 보여야 사용자가 판정을 의심하지 않는다. */}
                      {p.category && sanctuary.has(p.category) && <span className="sp-tag tag-sanct">성역</span>}
                      {fixedOf(p) && <span className="sp-tag tag-fixed">고정</span>}
                      {/* **결제수단을 가게처럼 보여 주지 않는다.** 걷어내니 아무것도 안 남은
                          결제는 카드사가 준 정보가 "간편결제로 샀다" 뿐이다.
                          `경유` 라고만 적었더니 <b>무슨 뜻인지 알 수 없다</b>는 지적을 받았다
                          (2026-08-26) — 무엇을 모른다는 것인지 말해야 한다. */}
                      {viaOnly(p) && <span className="cat">결제처 미확인</span>}
                    </span>
                    {/* **자세히 — 평소에는 숨어 있다.** 원문 상호는 버리지 않는다. 어느 지점인지가
                        사라지면 안 되고, 표시명이 미심쩍을 때 확인할 자리가 있어야 한다. */}
                    {detailOpen[p.paymentId] && (
                      <span className="det">
                        {p.merchantName && (
                          <>{highlight(p.merchantName, (query ?? '').trim())}</>
                        )}
                        {p.viaAgency && <> · {p.viaAgency} 경유</>}
                        {p.businessNumber && <> · {bizFmt(p.businessNumber)}</>}
                        {(() => {
                          const f = p.businessNumber ? merchantOf[p.businessNumber] : undefined;
                          if (f === 'loading') return <> · 주소 조회중…</>;
                          if (f?.address) return <> · 📍 {f.address}{f.online ? ' (본사)' : ''}</>;
                          return null;
                        })()}
                      </span>
                    )}
                    </div>
                    {/* 테두리는 브랜드 원색, 글자는 흰 바탕에서 읽히도록 눌러 쓴다.
                        KB국민 노랑을 글자에 그대로 쓰면 1.69:1 이라 안 보인다(KWCAG 5.4.3). */}
                    {/* **카드 이름은 보조줄에 있다.** 여기 두었더니 배지가 93px 을 먹어
                        가맹점명에 48px(한글 세 자)밖에 안 남았다 — 줄의 주인공이 가장 좁았다.
                        개편안 줄 구조에도 카드 배지는 없다(파일 머리 주석). 색은 보조줄의
                        글자색으로 남아 어느 카드인지는 그대로 보인다. */}
                    <span className="amt">{won(p.amount)}</span>
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
                </div>
              );
            })}
            </div>
          </div>
        ))}

        {/* 검색 결과 아래 — 어느 기간을 훑었는지 밝히고, 넓힐 길을 준다(개편안 `.sp-foot`). */}
        {query !== null && query.trim() !== '' && (
          <div className="sp-foot">
            <p>{dot(spanFrom(asOf, span))}부터 {dot(asOf.slice(0, 10))}까지의 내역이에요</p>
            <button type="button" className="sp-more" onClick={() => {
              if (span >= SPANS.length - 1) { say('더 볼 내역이 없어요'); return; }
              setSpan((v) => v + 1);
            }}>
              내역 더 보기<span className="chev" aria-hidden="true">›</span>
            </button>
          </div>
        )}

        <div className="spacer" />
      </div></Scroll>
      {toast && <div className="mini-toast show" role="status">{toast}</div>}
    </Screen>
  );
}
