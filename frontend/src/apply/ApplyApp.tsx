/**
 * 실사용자 신청 화면 — <b>비로그인</b> 공개 페이지 (설계서 Phase 3).
 *
 * <h2>왜 로그인이 없는가</h2>
 *
 * 순서 때문이다. 사용자가 로그인하려면 제공자에 신원이 있어야 하는데, 신원 등록이 승인을
 * 기다리면 <b>로그인을 못 해 업로드도 못 한다.</b> 그래서 로그인 전에 신원과 명세서를 한 번에 받는다.
 *
 * <h2>원본 파일은 서버로 가지 않는다</h2>
 *
 * 여기서 파싱해 5칸으로 줄인 텍스트만 보낸다. 받지 않은 것은 지울 필요도 없다.
 *
 * <h2>사용자 앱과 다른 번들이다</h2>
 *
 * `apply.html`이 진입점이라, 이 화면의 코드·경로가 사용자 앱 JS 에 들어가지 않는다.
 *
 * <h2>디자인은 앱 것을 그대로 쓴다 (2026-08-12 개편)</h2>
 *
 * <p>예전에는 이 화면만의 `ops.css` 로 공공 서비스 폼처럼 그렸다. 같은 서비스인데 겉모습이 달라
 * 여기가 어디인지 알기 어려웠다. <b>새 디자인을 만들지 않는다</b> — `components/ui` 와
 * `styles/app.css` 를 그대로 쓰고 이 화면 전용 클래스는 하나도 만들지 않는다.
 *
 * <p><b>단계로 쪼개지 않는다.</b> 온보딩은 화면마다 하나씩 묻지만 그것은 되돌아올 일이 없는
 * 인증이라 가능한 것이고, 여기는 파일을 고르다 신원을 고치는 일이 흔하다. 그래서
 * <b>한 화면에 전부 놓고 내려가며</b> 채운다 — 빌려 오는 것은 생김새(`.field`·`.label`·`.h-title`)다.
 *
 * <h2>묻는 것만 둔다</h2>
 *
 * <p>설명·안내·당부는 두지 않고, 안 받아도 되는 칸도 두지 않는다(2026-08-12 사용자 결정).
 * 카드사와 카드는 <b>한 칸으로 합쳤고</b>(회사별로 묶은 목록), 표시 이름은 없앴다 —
 * 없으면 카드 이름을 쓰므로 받을 이유가 없었다.
 */
import { useEffect, useState } from 'react';
import { Cta, ErrorBox, Screen, Scroll } from '../components/ui';
import {
  headerCandidates, parseStatement, readTextFile,
  type ColumnOverride, type ParseResult,
} from '../lib/statement';

const API_BASE: string = (import.meta.env.VITE_API_BASE as string | undefined) ?? '';

interface CatalogRow { cardCode: number; cardName: string; companyId: number; companyName: string }

interface CardEntry {
  key: number;
  cardCode: number | null;
  fileName: string;
  parsed: ParseResult | null;
  /** 별칭표가 실패해 모델에게 칸을 묻는 중. 몇 초 걸리므로 화면이 멈춘 것처럼 보이면 안 된다. */
  asking: boolean;
}

const EMPTY_CARD = (key: number): CardEntry =>
  ({ key, cardCode: null, fileName: '', parsed: null, asking: false });

/** `.label` 의 기본 여백(28px)이 한 화면에 다 놓기엔 넓다. 좁힌다. */
const LABEL: React.CSSProperties = { margin: '18px 0 8px' };

const won = (value: number) => `${value.toLocaleString('ko-KR')}원`;

/** 숫자만 저장하고, 표시는 010-0000-0000 형태로 자동 하이픈 — 온보딩과 같은 규칙이다. */
function formatPhone(digits: string): string {
  const n = digits.replace(/\D/g, '').slice(0, 11);
  if (n.length <= 3) return n;
  if (n.length <= 7) return `${n.slice(0, 3)}-${n.slice(3)}`;
  return `${n.slice(0, 3)}-${n.slice(3, 7)}-${n.slice(7)}`;
}

/**
 * 별칭표가 실패했을 때 <b>칸 이름만</b> 서버에 보내 연결을 물어 온다.
 *
 * <p>보내는 것은 카드사가 정한 머리글 낱말들뿐이다 — 날짜·금액·사업자번호가 든 줄은
 * {@link headerCandidates} 가 빼고, 서버가 같은 검사를 한 번 더 한다.
 *
 * <p><b>실패는 조용하다.</b> 못 찾든 통로가 막혔든, 화면은 원래의 "칸을 못 찾았어요"로 돌아간다.
 */
async function askColumns(text: string): Promise<ColumnOverride | null> {
  const candidates = headerCandidates(text);
  if (candidates.length === 0) return null;
  try {
    const res = await fetch(`${API_BASE}/api/apply/columns`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ rows: candidates.map((row) => row.cells) }),
    });
    if (!res.ok) return null;
    const body = await res.json() as {
      found: boolean; row: number;
      date: number; merchant: number; amount: number; biz: number; source: string;
    };
    if (!body.found || body.row < 0 || body.row >= candidates.length) return null;
    return {
      // 서버가 준 번호는 **우리가 보낸 목록** 기준이다. 격자 번호로 되돌린다.
      at: candidates[body.row].at,
      cols: { date: body.date, merchant: body.merchant, amount: body.amount, biz: body.biz },
      source: body.source,
    };
  } catch {
    return null;
  }
}

let nextKey = 1;

export function ApplyApp() {
  const [catalog, setCatalog] = useState<CatalogRow[]>([]);
  const [name, setName] = useState('');
  const [social, setSocial] = useState('');
  const [socialGender, setSocialGender] = useState('');
  const [phone, setPhone] = useState('');
  const [consent, setConsent] = useState(false);
  const [cards, setCards] = useState<CardEntry[]>([EMPTY_CARD(0)]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  const [ticket, setTicket] = useState<string | null>(null);
  const [lookup, setLookup] = useState('');
  const [lookupResult, setLookupResult] = useState<string | null>(null);

  useEffect(() => {
    void fetch(`${API_BASE}/api/apply/card-catalog`)
      .then(async (res) => {
        if (res.ok) return res.json() as Promise<CatalogRow[]>;
        // 목록이 없으면 카드를 고를 수 없어 신청 자체가 불가능하다.
        // **말없이 빈 화면을 두지 않는다** — 왜 못 하는지는 알려줘야 한다.
        const body = await res.json().catch(() => null);
        throw new Error(body?.message ?? '카드 목록을 불러오지 못했어요.');
      })
      .then((rows) => setCatalog(rows))
      .catch((e: Error) => setError(e));
  }, []);

  /** 카드사별로 묶은 목록 — 칸 하나로 고르게 해서 입력을 하나 줄인다. */
  const grouped = [...new Set(catalog.map((row) => row.companyName))].sort()
    .map((company) => ({ company, rows: catalog.filter((row) => row.companyName === company) }));

  const patch = (key: number, next: Partial<CardEntry>) =>
    setCards((prev) => prev.map((card) => (card.key === key ? { ...card, ...next } : card)));

  /**
   * 파일을 읽어 미리보기를 만든다 — <b>별칭표가 먼저, 모델은 실패했을 때만</b>.
   *
   * <p>순서가 설계다. 아는 카드사는 표가 즉시·공짜로·늘 같은 답을 낸다. 늘 모델에게 물으면
   * 같은 파일이 매번 다르게 읽힐 수 있고, 그것은 이 저장소가 지키는 재현성과 어긋난다.
   */
  async function pickFile(key: number, file: File | undefined) {
    if (!file) return;
    const text = await readTextFile(file);
    const first = parseStatement(text);
    if (first.ok || first.rows.length > 0) {
      patch(key, { fileName: file.name, parsed: first, asking: false });
      return;
    }
    patch(key, { fileName: file.name, parsed: first, asking: true });
    const override = await askColumns(text);
    patch(key, {
      parsed: override ? parseStatement(text, new Date(), override) : first, asking: false,
    });
  }

  const ready = name.trim().length >= 2
    && social.length === 6 && socialGender.length === 1
    && phone.replace(/\D/g, '').length >= 10
    && cards.every((card) => card.parsed?.ok && card.cardCode && !card.asking)
    && consent;

  async function submit() {
    setBusy(true); setError(null);
    try {
      const res = await fetch(`${API_BASE}/api/apply`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: name.trim(),
          social7: social + socialGender,
          phone,
          consent,
          cards: cards.map((card) => ({
            cardCode: card.cardCode, displayName: null, csv: card.parsed?.csv ?? '',
          })),
        }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body?.message ?? '신청하지 못했어요.');
      setTicket(body.ticket);
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  }

  async function checkTicket() {
    setLookupResult(null);
    try {
      const res = await fetch(`${API_BASE}/api/apply/${encodeURIComponent(lookup.trim())}`);
      const body = await res.json();
      const label: Record<string, string> = {
        RECEIVED: '검토 중이에요.',
        IMPORTED: '반영됐어요.',
        REJECTED: '반려됐어요.',
        DONE_OR_UNKNOWN: body?.message ?? '처리가 끝났거나 없는 접수증이에요.',
      };
      setLookupResult(label[body?.status as string] ?? '알 수 없어요.');
    } catch {
      setLookupResult('확인하지 못했어요.');
    }
  }

  if (ticket) {
    return (
      <Screen title="접수 완료">
        <Scroll><div className="pad">
          <p className="h-title">접수됐습니다</p>
          <div className="label" style={LABEL}>접수증 번호</div>
          <div className="card"><b>{ticket}</b></div>
        </div></Scroll>
      </Screen>
    );
  }

  return (
    <Screen title="명세서 신청">
      <Scroll><div className="pad">
        <p className="h-title">명세서 신청</p>

        <div className="label" style={LABEL}>이름</div>
        <input className="field" value={name} placeholder="이름" autoComplete="name" maxLength={40}
          onChange={(e) => setName(e.target.value)} />

        <div className="label" style={LABEL}>주민등록번호 앞 7자리</div>
        <div className="row2">
          <input className="field" style={{ flex: 1.3 }} value={social} placeholder="생년월일 6자리"
            inputMode="numeric" maxLength={6} aria-label="생년월일 6자리" autoComplete="off"
            onChange={(e) => setSocial(e.target.value.replace(/\D/g, '').slice(0, 6))} />
          <span style={{ alignSelf: 'center', color: 'var(--t3)' }} aria-hidden="true">-</span>
          <input className="field" style={{ flex: 0.35, textAlign: 'center' }} value={socialGender}
            placeholder="0" inputMode="numeric" maxLength={1}
            aria-label="주민등록번호 성별 자리" autoComplete="off"
            onChange={(e) => setSocialGender(e.target.value.replace(/\D/g, '').slice(0, 1))} />
          <span className="masked" aria-hidden="true">●●●●●●</span>
        </div>

        <div className="label" style={LABEL}>휴대전화번호</div>
        <input className="field" value={formatPhone(phone)} inputMode="numeric"
          placeholder="010-0000-0000" autoComplete="tel" maxLength={13}
          onChange={(e) => setPhone(e.target.value.replace(/\D/g, '').slice(0, 11))} />

        {cards.map((card, index) => (
          <div key={card.key}>
            <div className="label" style={LABEL}>
              카드{cards.length > 1 ? ` ${index + 1}` : ''}
              {cards.length > 1 && (
                <button type="button" className="chk-more" style={{ float: 'right', minHeight: 0, padding: '2px 8px' }}
                  onClick={() => setCards((prev) => prev.filter((c) => c.key !== card.key))}>빼기</button>
              )}
            </div>
            <select className="field" value={card.cardCode ?? ''}
              onChange={(e) => patch(card.key, { cardCode: Number(e.target.value) })}>
              <option value="">카드를 고르세요</option>
              {grouped.map((group) => (
                <optgroup key={group.company} label={group.company}>
                  {group.rows.map((row) => (
                    <option key={row.cardCode} value={row.cardCode}>{row.cardName}</option>
                  ))}
                </optgroup>
              ))}
            </select>
            {/* 파일 고르기는 앱의 `.btn` 을 그대로 쓴다 — 기본 파일 입력은 숨긴다. */}
            <label className="btn btn-ghost btn-sm" style={{ display: 'block', marginTop: 10 }}>
              <input type="file" accept=".csv,text/csv" style={{ display: 'none' }}
                onChange={(e) => void pickFile(card.key, e.target.files?.[0])} />
              {card.fileName || 'CSV 파일'}
            </label>
            {card.parsed && <Preview parsed={card.parsed} asking={card.asking} />}
          </div>
        ))}

        <button type="button" className="btn btn-ghost btn-sm" style={{ marginTop: 12 }}
          onClick={() => setCards((prev) => [...prev, EMPTY_CARD(nextKey++)])}>카드 추가</button>

        <button type="button" className={`chk${consent ? ' on' : ''}`} aria-pressed={consent}
          style={{ marginTop: 8 }} onClick={() => setConsent((v) => !v)}>
          <span className="box" aria-hidden="true">✓</span>
          <span className="ct"><b>개인(신용)정보 수집·이용 동의</b> <span className="req">(필수)</span></span>
        </button>

        <ErrorBox error={error} />

        <div className="divider" style={{ margin: '24px 0 12px' }} />
        <div className="row2">
          <input className="field" style={{ flex: 1 }} value={lookup} placeholder="접수증 번호"
            onChange={(e) => setLookup(e.target.value)} />
          <button type="button" className="btn btn-ghost btn-sm" disabled={!lookup.trim()}
            onClick={() => void checkTicket()}>조회</button>
        </div>
        {lookupResult && <p className="muted" style={{ marginTop: 8 }}>{lookupResult}</p>}

        {/* 아래 고정 버튼(`.cta-fixed`)이 마지막 줄을 덮는다. 그만큼 비워 둔다. */}
        <div style={{ height: 48 }} />
      </div></Scroll>

      <Cta>
        <button type="button" className="btn btn-primary" disabled={!ready || busy} onClick={() => void submit()}>
          {busy ? '보내는 중…' : '신청하기'}
        </button>
      </Cta>
    </Screen>
  );
}

/**
 * 미리보기 — <b>자동 인식이 성공했을 때도 무엇을 어느 칸으로 읽었는지 보여준다.</b>
 * 조용히 맞히는 것과 조용히 틀리는 것이 화면에서 똑같아 보이면 안 된다.
 */
function Preview({ parsed, asking }: { parsed: ParseResult; asking: boolean }) {
  // 칸 이름이 우리 표에 없는 카드사다. 몇 초 걸리므로 **기다리는 중이라고 말한다** —
  // 아무 말 없이 "못 찾았어요"만 떠 있으면 사용자는 이미 끝난 줄 알고 창을 닫는다.
  if (asking) {
    return <p className="muted" aria-busy="true" style={{ margin: '10px 0 0' }}>확인하고 있어요…</p>;
  }
  if (!parsed.ok) {
    return <div className="error" role="alert" style={{ margin: '10px 0 0' }}>{parsed.error}</div>;
  }
  return (
    <div className="card" style={{ margin: '10px 0 0', padding: 16 }}>
      {Object.entries(parsed.mapping).map(([key, source]) => (
        <div className="list-item" key={key} style={{ padding: '6px 0' }}>
          <div className="tx"><b>{key}</b><span>{source}</span></div>
        </div>
      ))}
      {/* **추정이라고 밝힌다.** 우리 표에 없는 칸 이름이라 모델이 연결한 것이고, 맞는지
          판단하는 것은 사람이다 — 확정과 추정이 화면에서 똑같아 보이면 안 된다. */}
      {parsed.guessedBy && (
        <div className="error" role="status">자동 추정한 연결이에요. 맞는지 확인해 주세요.</div>
      )}
      <div className="divider" />
      <div className="list-item" style={{ padding: '6px 0' }}>
        <div className="tx">
          <b>{parsed.rows.length.toLocaleString('ko-KR')}건</b>
          <span>{parsed.from} ~ {parsed.to} · 사업자번호 {parsed.withBiz}건</span>
        </div>
        <div className="amt">{won(parsed.totalAmount)}</div>
      </div>
      {parsed.problems.length > 0 && (
        <details>
          <summary className="muted">못 읽은 줄 {parsed.problems.length}개</summary>
          <div className="doc">
            {parsed.problems.slice(0, 20).map((problem) => (
              <p key={problem.line}>{problem.line}번째 줄 — {problem.reason}</p>
            ))}
          </div>
        </details>
      )}
    </div>
  );
}
