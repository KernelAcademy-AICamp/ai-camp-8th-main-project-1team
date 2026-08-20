/**
 * MD-03 자산 연결 — 인증 먼저, 기관은 찾아서 보여준다 (프로토타입_0806 `s-connect`).
 *
 * <b>흐름이 뒤집혔다.</b> 예전에는 기관을 사용자가 먼저 골랐다. 그러면 자기가 어느 카드를 쓰는지
 * 기억해 내야 하고, 빠뜨린 곳은 영영 연결되지 않는다. 이제 인증을 먼저 받고
 * <b>"N곳을 찾았어요"</b>를 보여준 뒤 뺄 것만 해제하게 한다 — 실제 마이데이터 통합인증이 그렇다.
 *
 * <pre>
 *   ① 동의    통합인증으로 조회 + 카드 이용내역 전송요구 (둘 다 필수)
 *   ② 탐색    인증서 앱 대기 → 금융결제원에 보유 기관 조회 (`/api/mydata/discover`)
 *   ③ 확인    찾은 곳을 보여주고 뺄 것만 해제 → `/api/mydata/link`
 * </pre>
 *
 * <b>탐색은 연결하지 않는다.</b> 보여주려고 먼저 연결해 두면 해제가 '되돌리기'가 되고, 그 사이에
 * 동기화가 돌면 지우려던 데이터가 이미 퍼진다. 찾아만 놓고 사용자가 확인해야 붙인다.
 */
import { useState } from 'react';
import { AppBar, ProgressBar, Cta, Scroll, Screen, ErrorBox } from '../components/ui';
import { Sheet } from '../components/Sheet';
import { Icon } from '../components/Icons';
import { useSession } from '../state/session';
import { api, type MyDataDiscovered } from '../lib/api';
import { brandOf, logoOf, logoBackdrop } from '../lib/institutions';

const PROVIDERS = [
  { name: '카카오톡', bg: '#FFCD00', fg: '#3c1e1e', label: 'K', desc: '카카오 지갑 인증서', logo: '/logo/cert-kakao.png' },
  { name: '네이버', bg: '#03C75A', fg: '#fff', label: 'N', desc: '네이버 인증서', logo: '/logo/cert-naver.jpeg' },
  { name: 'PASS', bg: '#E6002D', fg: '#fff', label: 'P', desc: '통신사 인증', logo: '/logo/cert-pass.png' },
  { name: '토스', bg: '#3182F6', fg: '#fff', label: 't', desc: '토스 인증서', logo: '/logo/cert-toss.jpg' },
];

/** 동의 항목 — 둘 다 필수다. 선택 동의를 섞으면 무엇이 없어도 되는지 사용자가 판단해야 한다. */
const AGREEMENTS = [
  { key: 'lookup', title: '통합인증으로 금융기관 조회', desc: '금융결제원에서 보유하신 카드와 계좌를 찾아요' },
  { key: 'transfer', title: '카드 이용내역 전송요구', desc: '잔액, 투자, 보험은 가져오지 않아요. 1년 뒤 자동 만료' },
];

type Stage = 'agree' | 'scan' | 'result';

/** 찾은 기관 한 줄. 눌러서 켜고 끈다. */
function FoundRow({ name, sub, on, onToggle }:
  { name: string; sub?: string; on: boolean; onToggle: () => void }) {
  const b = brandOf(name);
  const logo = logoOf(name);
  // 흰 단색 CI 는 흰 원 안에서 사라진다 — 그런 곳만 뒤에 브랜드색을 깐다.
  const backdrop = logoBackdrop(name);
  return (
    <button type="button" className={`fi${on ? ' on' : ' off'}`} onClick={onToggle} aria-pressed={on}>
      <span className="logo"
        style={logo ? (backdrop ? { background: backdrop } : undefined)
          : { background: b.bg, color: b.fg ?? '#fff' }}
        aria-hidden="true">
        {logo ? <img src={logo} alt="" loading="lazy" /> : b.label}
      </span>
      <span className="tx"><b>{name}</b>{sub && <span>{sub}</span>}</span>
      <span className="box" aria-hidden="true"><Icon id="i-check" /></span>
    </button>
  );
}

export function Connect() {
  const { go, back, userId, setLinked } = useSession();
  const [stage, setStage] = useState<Stage>('agree');
  const [agreed, setAgreed] = useState<Set<string>>(new Set());
  const [certOpen, setCertOpen] = useState(false);
  /** 인증서 앱 대기 → 기관 조회. 두 문구가 순서대로 바뀐다. */
  const [scanText, setScanText] = useState<[string, string]>(['', '']);
  const [found, setFound] = useState<MyDataDiscovered>({ cards: [], banks: [] });
  const [off, setOff] = useState<Set<string>>(new Set());   // 해제한 것만 기억한다
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);

  const allAgreed = agreed.size === AGREEMENTS.length;
  const toggleAgree = (k: string) => setAgreed((p) => {
    const n = new Set(p); n.has(k) ? n.delete(k) : n.add(k); return n;
  });
  const toggleAll = () => setAgreed(allAgreed ? new Set() : new Set(AGREEMENTS.map((a) => a.key)));

  const key = (kind: 'c' | 'b', id: number) => `${kind}${id}`;
  const isOn = (kind: 'c' | 'b', id: number) => !off.has(key(kind, id));
  const toggleFound = (kind: 'c' | 'b', id: number) => setOff((p) => {
    const n = new Set(p); const k = key(kind, id); n.has(k) ? n.delete(k) : n.add(k); return n;
  });
  const total = found.cards.length + found.banks.length;
  /** 이름으로 가른다 — 제공자는 은행과 페이를 한 목록으로 준다. */
  const isPay = (name: string) => /페이|pay/i.test(name);
  const banksOnly = found.banks.filter((b) => !isPay(b.name));
  const paysOnly = found.banks.filter((b) => isPay(b.name));
  const onCount = found.cards.filter((c) => isOn('c', c.id)).length
    + found.banks.filter((b) => isOn('b', b.id)).length;

  /** 인증서를 고르면 대기 문구를 보이며 실제로 탐색한다. */
  async function pickProvider(name: string) {
    setCertOpen(false);
    setStage('scan');
    setError(null);
    setScanText([`${name}에서 인증을 완료해주세요`, '앱으로 인증 요청을 보냈어요']);
    // 문구는 요청과 별개로 바뀐다 — 응답이 빨라도 "인증하세요"가 한 박자는 보여야 읽힌다.
    const turn = setTimeout(
      () => setScanText(['카드와 계좌를 찾고 있어요', '금융결제원에 보유 기관을 물어보는 중이에요']), 1600);
    try {
      const d = await api.mydataDiscover(userId);
      setFound(d);
      setStage('result');
    } catch (e) {
      setError(e);
      setStage('agree');
    } finally {
      clearTimeout(turn);
    }
  }

  /** 켜 둔 곳만 실제로 붙인다. */
  async function link() {
    setBusy(true); setError(null);
    try {
      await api.mydataLink(userId,
        found.cards.filter((c) => isOn('c', c.id)).map((c) => c.id),
        found.banks.filter((b) => isOn('b', b.id)).map((b) => b.id));
      setLinked(true);
      go('loading');
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  }

  return (
    <Screen id="connect" title="자산 연결">
      <AppBar onBack={back} title="자산 연결" />
      <ProgressBar value={stage === 'result' ? 0.44 : 0.36} />

      {/* ① 동의 */}
      {stage === 'agree' && (
        <>
          <Scroll><div className="pad">
            <p className="h-title">쓰시는 카드와 계좌를<br />MOA가 찾아드릴게요</p>
            <ErrorBox error={error} />

            <div className="card" style={{ padding: '8px 20px' }}>
              <button type="button" className={`chk${allAgreed ? ' on' : ''}`} aria-pressed={allAgreed}
                onClick={toggleAll}>
                <span className="box" aria-hidden="true">✓</span>
                <span className="ct"><b>전체 동의하기</b></span>
              </button>
              <div className="divider" />
              {AGREEMENTS.map((a) => (
                <button type="button" key={a.key} className={`chk conn-item${agreed.has(a.key) ? ' on' : ''}`}
                  aria-pressed={agreed.has(a.key)} onClick={() => toggleAgree(a.key)}>
                  <span className="box" aria-hidden="true">✓</span>
                  <span className="ct"><b>{a.title}</b> <span className="req">(필수)</span><p>{a.desc}</p></span>
                </button>
              ))}
            </div>
            <div className="spacer" style={{ height: 120 }} />
          </div></Scroll>
          <Cta>
            <button type="button" className="btn btn-primary" disabled={!allAgreed}
              onClick={() => setCertOpen(true)}>인증하고 찾아보기</button>
          </Cta>
        </>
      )}

      {/* ② 탐색 */}
      {stage === 'scan' && (
        <div className="conn-scan" role="status" aria-live="polite">
          <div className="spinner" />
          <div className="load-title">{scanText[0]}</div>
          <div className="load-step">{scanText[1]}</div>
        </div>
      )}

      {/* ③ 확인 */}
      {stage === 'result' && (
        <>
          <Scroll><div className="pad">
            <p className="h-title">{total}곳을 찾았어요</p>
            <p className="h-sub">모두 연결할수록 분석이 정확해져요.</p>
            <ErrorBox error={error} />

            {found.cards.length > 0 && (
              <>
                <div className="label">카드</div>
                <div className="card" style={{ padding: '4px 20px' }}>
                  {found.cards.map((c) => (
                    <FoundRow key={c.id} name={c.name} on={isOn('c', c.id)}
                      onToggle={() => toggleFound('c', c.id)} />
                  ))}
                </div>
              </>
            )}
            {/* **은행과 페이를 가른다**(프로토타입_0818). 한 묶음이던 것을 나눈 이유는 고르는
                기준이 다르기 때문이다 — 은행은 '내 계좌가 어디 있나'이고 페이는 '무엇으로
                결제하나'다. 제공자는 둘을 한 목록으로 주므로 이름으로 가른다. */}
            {banksOnly.length > 0 && (
              <>
                <div className="label" style={{ marginTop: 24 }}>은행</div>
                <div className="card" style={{ padding: '4px 20px' }}>
                  {banksOnly.map((b) => (
                    <FoundRow key={b.id} name={b.name} on={isOn('b', b.id)}
                      onToggle={() => toggleFound('b', b.id)} />
                  ))}
                </div>
              </>
            )}
            {paysOnly.length > 0 && (
              <>
                <div className="label" style={{ marginTop: 24 }}>페이</div>
                <div className="card" style={{ padding: '4px 20px' }}>
                  {paysOnly.map((b) => (
                    <FoundRow key={b.id} name={b.name} on={isOn('b', b.id)}
                      onToggle={() => toggleFound('b', b.id)} />
                  ))}
                </div>
              </>
            )}
            {total === 0 && (
              <p className="empty">연결할 카드나 계좌를 찾지 못했어요. 마이데이터 서버가 켜져 있는지 확인해 주세요.</p>
            )}

            <div className="label" style={{ marginTop: 32 }}>동의하신 전송요구 내용</div>
            <div className="card" style={{ padding: '4px 20px' }}>
              <div className="trow"><span className="k">가져오는 정보</span><span className="v">카드 이용내역, 승인내역</span></div>
              <div className="trow"><span className="k">가져오지 않는 정보</span><span className="v">잔액, 투자, 보험</span></div>
              <div className="trow"><span className="k">정보 보유 기간</span><span className="v">서비스 해지 시까지</span></div>
              <div className="trow" style={{ border: 'none' }}><span className="k">전송요구 만료일</span><span className="v">전송요구일로부터 1년</span></div>
            </div>
            <div className="pv">결제나 송금 권한은 포함되지 않아요. 마이 &gt; 연결 관리에서 언제든 철회할 수 있어요.</div>
            <div className="spacer" style={{ height: 96 }} />
          </div></Scroll>
          <Cta>
            <button type="button" className="btn btn-primary" disabled={onCount === 0 || busy} onClick={() => void link()}>
              {busy ? '연결 중…'
                : onCount === 0 ? '연결할 곳을 골라주세요'
                  : onCount === total ? `${onCount}곳 모두 연결하기` : `${onCount}곳 연결하기`}
            </button>
          </Cta>
        </>
      )}

      {/* 인증서 선택 — 개편안의 `#sheet-cert` */}
      <Sheet open={certOpen} onClose={() => setCertOpen(false)} title="인증서를 골라주세요">
        <p className="sheet-title">인증서를 골라주세요</p>
        <p className="sheet-sub">통합인증으로 보유 기관을 한 번에 찾아요.</p>
        {PROVIDERS.map((p) => (
          <button type="button" key={p.name} className="provider" onClick={() => void pickProvider(p.name)}>
            {p.logo
              ? <span className="pl pl-img" aria-hidden="true"><img src={p.logo} alt="" /></span>
              : <span className="pl" style={{ background: p.bg, color: p.fg }} aria-hidden="true">{p.label}</span>}
            <span><b>{p.name}</b><span className="sub">{p.desc}</span></span>
          </button>
        ))}
      </Sheet>
    </Screen>
  );
}
