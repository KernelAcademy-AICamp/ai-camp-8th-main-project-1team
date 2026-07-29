/**
 * ON-02 본인인증 (최초 1회) — 목업의 스택형 입력 흐름 그대로.
 * 이름 → 주민번호 → 통신사 → 휴대폰(인증요청) → [개인정보 활용동의 시트] → 인증번호 → 자산연결.
 *
 * 실제 검증은 `/api/mydata/verify` — 입력한 신원으로 **가상 CI**를 만들어 마이데이터에 회원이 있는지
 * 확인한다(실 SMS 발송 없음, §13). 그래서 통신사·인증번호 단계는 표준 연결 경험을 재현하는 연출이고,
 * 서버가 실제로 보는 값은 이름·주민번호 앞 7자리·휴대폰 번호 셋이다.
 */
import { useState } from 'react';
import { AppBar, ProgressBar, Cta, Scroll, Screen, ErrorBox } from '../components/ui';
import { Sheet } from '../components/Sheet';
import { useSession } from '../state/session';
import { api } from '../lib/api';
import type { VerifyResult } from '../lib/api';
import { DEMO_CI, DEMO_ENABLED } from '../lib/config';

type Kind = 'text' | 'social' | 'carrier' | 'phone' | 'code';
interface Step { key: string; title: string; sub: string; label: string; kind: Kind; cta: string; ok: (v: string) => boolean }

const STEPS: Step[] = [
  { key: 'name', title: '이름을\n알려주세요', sub: '본인 확인이 끝나면 바로 가입돼요.', label: '이름', kind: 'text', cta: '다음', ok: (v) => v.trim().length >= 2 },
  { key: 'social', title: '주민등록번호\n앞 7자리를 입력해주세요', sub: '증명 확인에만 쓰고 저장하지 않아요.', label: '주민등록번호', kind: 'social', cta: '다음', ok: (v) => v.replace(/\D/g, '').length >= 7 },
  { key: 'carrier', title: '통신사를\n골라주세요', sub: '', label: '통신사', kind: 'carrier', cta: '다음', ok: (v) => !!v },
  { key: 'phone', title: '휴대폰 번호를\n입력해주세요', sub: '', label: '휴대폰 번호', kind: 'phone', cta: '인증요청', ok: (v) => v.replace(/\D/g, '').length >= 10 },
  { key: 'code', title: '문자로 받은\n인증번호를 입력해주세요', sub: '', label: '인증번호', kind: 'code', cta: '인증완료', ok: (v) => v.replace(/\D/g, '').length >= 6 },
];
const CARRIERS = ['SKT', 'KT', 'LG U+', '알뜰폰'];

/**
 * 인증 실패 사유를 사람이 읽을 문장으로 옮긴다.
 *
 * 판정은 서버가 한다 — 국번 대역표를 화면에도 두면 반드시 어긋나기 때문이다.
 * 여기서는 사유에 맞는 문장을 고르기만 한다.
 */
function failureMessage(r: VerifyResult, selected: string): string {
  const KTOA = '[한국통신사업자연합회]';
  switch (r.reason) {
    // 번호 자체가 실존하지 않는다 — 신원 대조에 들어가기도 전이다.
    case 'UNASSIGNED_EXCHANGE':
      return `${KTOA} 실존하지 않는 번호입니다. 휴대폰 번호를 다시 확인해 주세요.`;
    // 통신사만 다르다 — 입력한 번호 자체의 성질이라 짚어줘도 남의 신원을 캐는 데 쓸 수 없다.
    case 'CARRIER_MISMATCH':
      return `${KTOA} 입력하신 번호는 ${selected}가 아닌 ${r.actualCarrier} 관리 대역입니다.`;
    // 이름·주민번호는 맞는데 번호가 어긋난 경우. 남의 명의든 미등록이든 사용자가 할 일은 같다.
    case 'PHONE_OWNED_BY_OTHER':
    case 'PHONE_MISMATCH':
      return `${KTOA} 등록된 전화번호가 불일치합니다. 휴대폰 번호를 다시 확인해 주세요.`;
    // 이름·주민번호가 어긋난 경우. **무엇을 고치라는 말도 하지 않는다** —
    // 어느 항목을 지목하든 나머지는 맞다는 뜻이 되어, 남의 신원을 한 항목씩 맞춰볼 수 있게 된다.
    default:
      return `${KTOA} 신원 정보가 불일치합니다.`;
  }
}

/** 숫자만 저장하고, 표시는 010-0000-0000 형태로 자동 하이픈. */
function formatPhone(digits: string): string {
  const n = digits.replace(/\D/g, '').slice(0, 11);
  if (n.length <= 3) return n;
  if (n.length <= 7) return `${n.slice(0, 3)}-${n.slice(3)}`;
  return `${n.slice(0, 3)}-${n.slice(3, 7)}-${n.slice(7)}`;
}

const TERMS = [
  { id: 't1', label: '서비스 이용약관', req: true },
  { id: 't2', label: '개인(신용)정보 수집·이용 동의', req: true, desc: '소비 분석 목적으로만 사용, 제3자 제공 안 함' },
  { id: 't3', label: '고유식별정보 처리 동의', req: true },
  { id: 't4', label: '지킴이 알림·혜택 수신', req: false },
];

export function Auth() {
  const { go, back, userId, setUserId, setLinked } = useSession();
  const [step, setStep] = useState(0);
  const [vals, setVals] = useState<Record<string, string>>({});
  const [consentOpen, setConsentOpen] = useState(false);
  const [consented, setConsented] = useState(false);
  const [checked, setChecked] = useState<Set<string>>(new Set());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<unknown>(null);
  /** 인증 실패 사유. 서버가 판정해 내려준 것을 문장으로만 옮긴다. */
  const [failure, setFailure] = useState<string | null>(null);

  const cur = STEPS[step];

  /**
   * 단계 이동은 여기 한 곳으로 모은다. 예전에는 `setStep`을 세 군데에서 직접 불러서,
   * **뒤로 가도 실패 문구가 그대로 남았다.** 화면이 바뀌면 지난 단계의 경고는 지운다.
   */
  const goStep = (n: number) => { setFailure(null); setStep(n); };

  const setVal = (v: string) => { setFailure(null); setVals((p) => ({ ...p, [cur.key]: v })); };
  const curVal = vals[cur.key] ?? '';
  const curOk = cur.kind === 'social'
    ? (vals.social?.length ?? 0) >= 6 && (vals.socialG?.length ?? 0) >= 1
    : cur.ok(curVal);

  const social7 = `${vals.social ?? ''}${vals.socialG ?? ''}`;

  /**
   * 서버에 신원을 보내 판정을 받는다. 통과하면 CI가 연결된다(서버는 통과했을 때만 저장한다).
   *
   * <b>인증요청 단계에서도 부른다.</b> 번호가 실존하지 않거나 통신사가 다르면 실제 인증에서는
   * **문자 자체가 가지 않는다.** 인증번호를 받아 적은 뒤에야 "그 번호는 없습니다"라고 하는 것은
   * 순서가 틀렸다. 그래서 `인증요청`을 누르는 그 자리에서 먼저 판정한다.
   *
   * @returns 통과 여부. 실패하면 사유를 화면에 띄운 채 false를 돌려준다.
   */
  async function requestVerify(): Promise<boolean> {
    setBusy(true); setError(null); setFailure(null);
    try {
      const result = await api.verify(
        userId, (vals.name ?? '').trim(), social7, vals.phone ?? '', vals.carrier);
      if (!result.verified) { setFailure(failureMessage(result, vals.carrier ?? '')); return false; }
      return true;
    } catch (e) {
      setError(e);
      return false;
    } finally {
      setBusy(false);
    }
  }

  /** 개발용 — 인증을 건너뛰고 생성 마이데이터 CI로 바로 연결한다. */
  async function skipWithDemoCi() {
    setBusy(true); setError(null);
    try {
      const companies = await api.mydataCompanies();
      const r = await api.linkSynthetic(DEMO_CI, companies.map((c) => c.id));
      setUserId(r.userId);
      setLinked(true);
      go('loading');
    } catch (e) {
      setError(e);
      setBusy(false);
    }
  }

  async function next() {
    // 인증요청 — 문자를 보내기 전에 번호가 실존하는지, 통신사가 맞는지 먼저 판정한다.
    if (cur.key === 'phone') {
      if (!(await requestVerify())) return;              // 실패 사유는 이 화면에 그대로 남는다
      if (!consented) { setConsentOpen(true); return; }
      goStep(STEPS.findIndex((x) => x.key === 'code'));
      return;
    }
    // 인증완료 — 앞에서 이미 통과한 신원이므로 여기서는 연결로 넘어간다.
    if (step >= STEPS.length - 1) { go('connect'); return; }
    goStep(step + 1);
  }
  function confirmConsent() {
    setConsented(true); setConsentOpen(false);
    setTimeout(() => goStep(STEPS.findIndex((s) => s.key === 'code')), 200);
  }
  const reqOk = TERMS.filter((t) => t.req).every((t) => checked.has(t.id));
  const allOn = TERMS.every((t) => checked.has(t.id));
  const toggle = (id: string) => setChecked((p) => {
    const n = new Set(p);
    if (n.has(id)) n.delete(id); else n.add(id);
    return n;
  });
  const toggleAll = () => setChecked(allOn ? new Set() : new Set(TERMS.map((t) => t.id)));

  const done = STEPS.slice(0, step); // 이미 지난 단계(스택 아래에 회색으로)

  function field(s: Step, active: boolean) {
    const v = vals[s.key] ?? '';
    const id = `f-${s.key}`;
    // 주민번호: 생년월일 6 + 성별 1 + 뒤 6자리 마스킹(●●●●●●)
    if (s.kind === 'social') {
      const b = vals.social ?? '', g = vals.socialG ?? '';
      if (!active) return <div className="field" style={{ pointerEvents: 'none' }}>{b ? `${b} - ${g || '•'} ●●●●●●` : '—'}</div>;
      return (
        <div className="row2">
          <input className="field" style={{ flex: 1.3 }} id={id} autoFocus value={b} placeholder="생년월일 6자리"
            inputMode="numeric" maxLength={6} aria-label="생년월일 6자리" autoComplete="off"
            onChange={(e) => setVals((p) => ({ ...p, social: e.target.value.replace(/\D/g, '').slice(0, 6) }))} />
          <span style={{ alignSelf: 'center', color: 'var(--t3)' }} aria-hidden="true">-</span>
          <input className="field" style={{ flex: 0.35, textAlign: 'center' }} value={g} placeholder="0"
            inputMode="numeric" maxLength={1} aria-label="주민등록번호 성별 자리" autoComplete="off"
            onChange={(e) => setVals((p) => ({ ...p, socialG: e.target.value.replace(/\D/g, '').slice(0, 1) }))} />
          <span className="masked" aria-hidden="true">●●●●●●</span>
        </div>
      );
    }
    const display = s.kind === 'phone' ? formatPhone(v) : v;
    if (!active) return <div className="field" style={{ pointerEvents: 'none' }}>{display || '—'}</div>;
    if (s.kind === 'carrier') return (
      <div className="seg" role="group" aria-label="통신사 선택">
        {CARRIERS.map((c) => (
          <button type="button" key={c} className={v === c ? 'on' : ''} aria-pressed={v === c}
            onClick={() => setVal(c)}>{c}</button>
        ))}
      </div>
    );
    const num = s.kind !== 'text';
    // 반복 입력을 줄이도록 자동입력을 허용한다(KWCAG 2.2 검사항목 31 · guide2 3.3.4-T2).
    // 인증번호는 one-time-code 로 두면 OS가 문자에서 바로 채워준다(3.3.3-T2, 접근 가능한 인증).
    // 주민등록번호만 off 다 — 브라우저에 남길 값이 아니다.
    const auto = s.kind === 'phone' ? 'tel' : s.kind === 'code' ? 'one-time-code' : 'name';
    return (
      <input className="field" id={id} autoFocus value={display} autoComplete={auto}
        placeholder={s.kind === 'phone' ? '010-0000-0000' : s.kind === 'code' ? '6자리 입력' : '이름'}
        inputMode={num ? 'numeric' : 'text'}
        maxLength={s.kind === 'phone' ? 13 : s.kind === 'code' ? 6 : undefined}
        onChange={(e) => {
          const digits = e.target.value.replace(/\D/g, '');
          setVal(num ? (s.kind === 'phone' ? digits.slice(0, 11) : digits) : e.target.value);
        }} />
    );
  }

  return (
    <Screen title="본인인증">
      <AppBar onBack={step > 0 ? () => goStep(step - 1) : back} />
      <ProgressBar value={0.1 + step * 0.03} />
      <Scroll><div className="pad">
        <p className="h-title" style={{ whiteSpace: 'pre-line' }}>{cur.title}</p>
        {cur.sub && <p className="h-sub">{cur.sub}</p>}

        <div className="stack">
          <div className="fgroup">
            {/* 남은 시간을 적지 않는다 — 실제 카운트다운이 없는데 '03:00'을 띄우면
                시간 제한이 있는 것처럼 오해시키고, 그 순간 응답시간 조절 수단이 필요해진다
                (KWCAG 2.2 검사항목 14). 실 SMS를 붙일 때 함께 넣을 자리다. */}
            <label className="label" htmlFor={`f-${cur.key}`}>{cur.label}</label>
            {field(cur, true)}
          </div>
          {done.slice().reverse().map((s) => (
            <div key={s.key} className="fgroup done">
              <div className="label">{s.label}</div>
              {field(s, false)}
            </div>
          ))}
        </div>

        {failure && <div className="error" role="alert">{failure}</div>}
        <ErrorBox error={error} />

        {DEMO_ENABLED && (
          <p style={{ textAlign: 'center', margin: '20px 0 0' }}>
            <button type="button" className="btn btn-ghost btn-sm" disabled={busy}
              onClick={() => void skipWithDemoCi()}
              title="개발용 — 본인인증을 건너뛰고 생성 마이데이터로 바로 연결">
              🧪 개발용 건너뛰기 (인증 없이 연결)
            </button>
          </p>
        )}
        <div className="spacer" style={{ height: 20 }} />
      </div></Scroll>

      <Cta>
        <button type="button" className="btn btn-primary" disabled={!curOk || busy} onClick={() => void next()}>
          {busy ? '확인 중…' : cur.cta}
        </button>
      </Cta>

      <Sheet open={consentOpen} onClose={() => setConsentOpen(false)} title="가입하려면 동의가 필요해요">
        <p className="sheet-title">가입하려면 동의가 필요해요</p>
        <p className="sheet-sub">소비 분석에 꼭 필요한 것만 받을게요.</p>
        <button type="button" className={`chk${allOn ? ' on' : ''}`} aria-pressed={allOn} onClick={toggleAll}>
          <span className="box" aria-hidden="true">✓</span><span className="ct"><b>전체 동의하기</b></span>
        </button>
        <div className="divider" />
        {TERMS.map((t) => {
          const on = checked.has(t.id);
          return (
            <button type="button" key={t.id} className={`chk${on ? ' on' : ''}`} aria-pressed={on} onClick={() => toggle(t.id)}>
              <span className="box" aria-hidden="true">✓</span>
              <span className="ct">
                <b>{t.label}</b>{' '}
                <span className="req" style={!t.req ? { color: 'var(--blue-t)' } : undefined}>({t.req ? '필수' : '선택'})</span>
                {t.desc && <span className="desc">{t.desc}</span>}
              </span>
            </button>
          );
        })}
        <div style={{ height: 10 }} />
        <button type="button" className="btn btn-primary" disabled={!reqOk} onClick={confirmConsent}>
          동의하고 인증번호 받기
        </button>
      </Sheet>
    </Screen>
  );
}
