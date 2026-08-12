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
 * <h2>화면은 공공 서비스 폼의 관행을 따른다</h2>
 *
 * 제목·설명 → 왼쪽 폼 / 오른쪽 안내 → 라벨 위 칸 아래 → 전체 너비 확인 버튼.
 * 개인정보를 넣는 화면이라 <b>무엇을 왜 넣는지</b>가 폼 옆에 늘 보여야 한다.
 */
import { useEffect, useState } from 'react';
import { parseStatement, readTextFile, type ParseResult } from '../lib/statement';

const API_BASE: string = (import.meta.env.VITE_API_BASE as string | undefined) ?? '';

interface CatalogRow { cardCode: number; cardName: string; companyId: number; companyName: string }

interface CardEntry {
  key: number;
  companyName: string;
  cardCode: number | null;
  displayName: string;
  fileName: string;
  parsed: ParseResult | null;
}

const won = (value: number) => `${value.toLocaleString('ko-KR')}원`;

let nextKey = 1;

export function ApplyApp() {
  const [catalog, setCatalog] = useState<CatalogRow[]>([]);
  const [catalogError, setCatalogError] = useState<string | null>(null);
  const [name, setName] = useState('');
  const [social, setSocial] = useState('');
  const [socialGender, setSocialGender] = useState('');
  const [phone, setPhone] = useState('');
  const [consent, setConsent] = useState(false);
  const [cards, setCards] = useState<CardEntry[]>([
    { key: 0, companyName: '', cardCode: null, displayName: '', fileName: '', parsed: null },
  ]);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
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
      .catch((e: Error) => setCatalogError(e.message));
  }, []);

  const companies = [...new Set(catalog.map((row) => row.companyName))].sort();
  const productsOf = (company: string) => catalog.filter((row) => row.companyName === company);

  const patch = (key: number, nextValue: Partial<CardEntry>) =>
    setCards((prev) => prev.map((card) => (card.key === key ? { ...card, ...nextValue } : card)));

  async function pickFile(key: number, file: File | undefined) {
    if (!file) return;
    const text = await readTextFile(file);
    patch(key, { fileName: file.name, parsed: parseStatement(text) });
  }

  function randomCard(key: number, company: string) {
    const products = productsOf(company);
    if (products.length === 0) return;
    const picked = products[Math.floor(Math.random() * products.length)];
    patch(key, { cardCode: picked.cardCode });
  }

  const ready = cards.every((card) => card.parsed?.ok && card.cardCode)
    && name.trim().length >= 2
    && social.replace(/\D/g, '').length === 6 && socialGender.length === 1
    && phone.replace(/\D/g, '').length >= 10
    && consent;

  async function submit() {
    setBusy(true); setError(null);
    try {
      const res = await fetch(`${API_BASE}/api/apply`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          name: name.trim(),
          social7: social.replace(/\D/g, '') + socialGender,
          phone,
          consent,
          cards: cards.map((card) => ({
            cardCode: card.cardCode,
            displayName: card.displayName.trim() || null,
            csv: card.parsed?.csv ?? '',
          })),
        }),
      });
      const body = await res.json();
      if (!res.ok) throw new Error(body?.message ?? '신청하지 못했어요.');
      setTicket(body.ticket);
    } catch (e) {
      setError(e instanceof Error ? e.message : '신청하지 못했어요.');
    } finally {
      setBusy(false);
    }
  }

  async function checkTicket() {
    const res = await fetch(`${API_BASE}/api/apply/${encodeURIComponent(lookup.trim())}`);
    const body = await res.json();
    const label: Record<string, string> = {
      RECEIVED: '검토 중이에요.',
      IMPORTED: '반영됐어요. 앱에서 본인인증 후 카드사를 연결해 주세요.',
      REJECTED: '반려됐어요.',
      DONE_OR_UNKNOWN: body?.message ?? '처리가 끝났거나 없는 접수증이에요.',
    };
    setLookupResult(label[body?.status as string] ?? '알 수 없어요.');
  }

  if (ticket) {
    return (
      <main className="narrow">
        <div className="page-title">
          <h1>신청이 접수됐습니다</h1>
          <p>검토 후 반영되면 알려드립니다.</p>
        </div>
        <div className="cols">
          <div>
            <p className="ticket">{ticket}</p>
            <p className="notice warn">
              <b>이 번호를 적어 두세요.</b> 진행 상태를 확인할 수 있는 유일한 방법입니다.
            </p>
          </div>
          <aside className="guide">
            <h2>다음 순서</h2>
            <ul>
              <li>운영자가 요약을 확인하고 승인합니다.</li>
              <li>반영된 뒤 앱에서 <b>평소대로 본인인증</b>을 하세요 — 방금 넣으신 이름·주민등록번호
                앞 7자리·휴대전화번호 그대로입니다.</li>
              <li>인증 후 <b>카드사 연결</b>까지 마쳐야 화면에 소비내역이 나옵니다.</li>
            </ul>
          </aside>
        </div>
      </main>
    );
  }

  return (
    <main>
      <div className="page-title">
        <h1>내 카드 명세서로 MOA 써보기</h1>
        <p>
          실제 카드 사용내역으로 소비 분석을 받아볼 수 있습니다.
          올려주신 명세서는 검토 후 반영되며, <b>원본 파일은 서버로 전송되지 않습니다.</b>
        </p>
      </div>

      <div className="cols">
        <div>
          <section className="section">
            <h2>본인 정보</h2>
            <p className="sub">이 셋으로 신원을 확인합니다. 다른 용도로 쓰지 않습니다.</p>

            <div className="field">
              <label htmlFor="ap-name">이름</label>
              <input id="ap-name" type="text" value={name} maxLength={40}
                placeholder="이름을 입력하세요." onChange={(e) => setName(e.target.value)} />
            </div>

            <div className="field">
              <span className="label">주민등록번호 앞 7자리</span>
              <div className="row">
                <input className="grow" type="text" value={social} inputMode="numeric"
                  placeholder="생년월일 6자리"
                  onChange={(e) => setSocial(e.target.value.replace(/\D/g, '').slice(0, 6))} />
                <span className="sep" aria-hidden="true">–</span>
                <input className="one" type="text" value={socialGender} inputMode="numeric"
                  placeholder="1" aria-label="주민등록번호 뒤 첫 자리"
                  onChange={(e) => setSocialGender(e.target.value.replace(/\D/g, '').slice(0, 1))} />
                <span className="masked" aria-hidden="true">●●●●●●</span>
              </div>
              <p className="help">뒤 6자리는 받지 않습니다.</p>
            </div>

            <div className="field">
              <label htmlFor="ap-phone">휴대전화번호</label>
              <input id="ap-phone" type="text" value={phone} inputMode="numeric"
                placeholder="010-0000-0000" onChange={(e) => setPhone(e.target.value)} />
            </div>
          </section>

          <section className="section">
            <h2>카드 명세서</h2>
            <p className="sub">카드마다 파일을 하나씩 올립니다. 카드사가 여러 곳이면 카드를 추가하세요.</p>
            {catalogError && <p className="notice error" role="alert">{catalogError}</p>}

            {cards.map((card, index) => (
              <div className="entry" key={card.key}>
                <div className="entry-head">
                  <b>{index + 1}번째 카드</b>
                  {cards.length > 1 && (
                    <button type="button" className="link"
                      onClick={() => setCards((prev) => prev.filter((c) => c.key !== card.key))}>
                      빼기
                    </button>
                  )}
                </div>

                <div className="field">
                  <span className="label">카드사</span>
                  <select value={card.companyName}
                    onChange={(e) => patch(card.key, { companyName: e.target.value, cardCode: null })}>
                    <option value="">고르세요</option>
                    {companies.map((company) => <option key={company} value={company}>{company}</option>)}
                  </select>
                </div>

                <div className="field">
                  <span className="label">카드</span>
                  <div className="row">
                    <select className="grow" value={card.cardCode ?? ''} disabled={!card.companyName}
                      onChange={(e) => patch(card.key, { cardCode: Number(e.target.value) })}>
                      <option value="">고르세요</option>
                      {productsOf(card.companyName).map((product) => (
                        <option key={product.cardCode} value={product.cardCode}>{product.cardName}</option>
                      ))}
                    </select>
                    <button type="button" disabled={!card.companyName}
                      onClick={() => randomCard(card.key, card.companyName)}>무작위</button>
                  </div>
                </div>

                <div className="field">
                  <label htmlFor={`ap-nick-${card.key}`}>
                    표시 이름 <span className="hint">비우면 카드 이름을 씁니다</span>
                  </label>
                  <input id={`ap-nick-${card.key}`} type="text" value={card.displayName} maxLength={60}
                    placeholder="예: 주력카드" onChange={(e) => patch(card.key, { displayName: e.target.value })} />
                </div>

                <div className="field">
                  <label htmlFor={`ap-file-${card.key}`}>명세서 파일 (CSV)</label>
                  <input id={`ap-file-${card.key}`} type="file" accept=".csv,text/csv"
                    onChange={(e) => void pickFile(card.key, e.target.files?.[0])} />
                </div>

                {card.parsed && <Preview parsed={card.parsed} fileName={card.fileName} />}
              </div>
            ))}

            <button type="button" className="ghost" onClick={() => setCards((prev) => [...prev,
              { key: nextKey++, companyName: '', cardCode: null, displayName: '', fileName: '', parsed: null }])}>
              + 카드 추가
            </button>
          </section>

          <section className="section">
            <h2>동의</h2>
            <div className="field">
              <label>
                <input type="checkbox" checked={consent} onChange={(e) => setConsent(e.target.checked)} />
                개인(신용)정보 수집·이용에 동의합니다
              </label>
              <p className="help">
                카드 승인 상세정보 · 가맹점명 · 사업자등록번호 · 거래내역을 소비 분석 목적으로
                처리합니다. 회원 탈퇴 또는 동의 철회 시까지 보유하며, 언제든 삭제를 요청할 수
                있습니다. 자세한 내용은 개인정보 처리방침 3항에 있습니다.
              </p>
            </div>
          </section>

          {error && <p className="notice error" role="alert">{error}</p>}
          <button type="button" className="primary" disabled={!ready || busy}
            onClick={() => void submit()}>
            {busy ? '보내는 중…' : '신청하기'}
          </button>

          <section className="section" style={{ marginTop: 48 }}>
            <h2>접수증으로 상태 보기</h2>
            <div className="row">
              <input className="grow" type="text" value={lookup} placeholder="A7F3-2K91"
                onChange={(e) => setLookup(e.target.value.toUpperCase())} />
              <button type="button" onClick={() => void checkTicket()}>확인</button>
            </div>
            {lookupResult && <p className="help">{lookupResult}</p>}
          </section>
        </div>

        <aside className="guide">
          <h2>명세서 준비하기</h2>
          <ul>
            <li>카드사 홈페이지에서 <b>이용내역을 CSV 로 내려받아</b> 그대로 올리세요.</li>
            <li>받을 때 <b>사업자번호 칸을 포함</b>해 주세요 — 없으면 어디에 썼는지 분류가 되지 않습니다.</li>
            <li>거래일 · 가맹점명 · 이용금액 세 칸은 반드시 있어야 합니다.</li>
            <li>엑셀로 받으셨다면 <b>다른 이름으로 저장 → CSV</b> 로 바꿔 주세요.</li>
            <li>취소·환불(음수)도 그대로 두세요. 합계에서 알아서 상쇄됩니다.</li>
          </ul>

          <h2>개인정보 안내</h2>
          <ul>
            <li><b>원본 파일은 서버로 전송되지 않습니다.</b> 이 화면에서 필요한 다섯 칸만 뽑아 보냅니다.</li>
            <li>주민등록번호는 <b>앞 7자리만</b> 받습니다.</li>
            <li>신원 정보와 명세서는 <b>암호화해서</b> 보관하고, 반영이 끝나면 대기열에서 지웁니다.</li>
            <li>7일 안에 처리되지 않은 신청은 자동으로 파기됩니다.</li>
          </ul>
        </aside>
      </div>
    </main>
  );
}

/**
 * 미리보기 — <b>자동 인식이 성공했을 때도 무엇을 어느 칸으로 읽었는지 보여준다.</b>
 * 조용히 맞히는 것과 조용히 틀리는 것이 화면에서 똑같아 보이면 안 된다.
 */
function Preview({ parsed, fileName }: { parsed: ParseResult; fileName: string }) {
  if (!parsed.ok) {
    return (
      <div className="preview bad" role="alert">
        <b>{fileName}</b>
        <p style={{ margin: '0 0 8px' }}>{parsed.error}</p>
        <p className="muted small" style={{ margin: 0 }}>
          필요한 칸: <b>거래일 · 가맹점명 · 이용금액</b>
          (사업자번호가 있으면 분류가 훨씬 좋아집니다)
        </p>
      </div>
    );
  }
  const bizRatio = parsed.rows.length === 0 ? 0
    : Math.round((parsed.withBiz / parsed.rows.length) * 100);
  return (
    <div className="preview">
      <b>{fileName}</b>
      <dl className="mapping">
        {Object.entries(parsed.mapping).map(([key, source]) => (
          <div key={key}><dt>{key}</dt><dd>← “{source}”</dd></div>
        ))}
      </dl>
      <ul className="stats">
        <li>결제 <b>{parsed.rows.length.toLocaleString('ko-KR')}건</b></li>
        <li>기간 {parsed.from} ~ {parsed.to}</li>
        <li>합계 <b>{won(parsed.totalAmount)}</b></li>
        {parsed.refundCount > 0 && (
          <li>취소·환불 {parsed.refundCount}건 {won(parsed.refundAmount)}</li>
        )}
        <li className={bizRatio < 50 ? 'warn' : undefined}>
          사업자번호 <b>{parsed.withBiz}건 ({bizRatio}%)</b> · 고유 {parsed.merchants}곳
          {bizRatio < 50 && ' — 낮으면 분류가 잘 안 됩니다'}
        </li>
      </ul>
      {parsed.problems.length > 0 && (
        <details>
          <summary>못 읽은 줄 {parsed.problems.length}건</summary>
          <ul className="problems">
            {parsed.problems.slice(0, 20).map((problem) => (
              <li key={problem.line}>{problem.line}행 — {problem.reason}</li>
            ))}
            {parsed.problems.length > 20 && <li>… 그 외 {parsed.problems.length - 20}건</li>}
          </ul>
        </details>
      )}
    </div>
  );
}
