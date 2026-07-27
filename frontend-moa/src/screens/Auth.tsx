/**
 * ON-02 본인인증 (최초 1회) — 스택형 입력 흐름.
 * 이름 → 주민번호 → 통신사 → 휴대폰(인증요청) → [개인정보 활용동의 시트] → 인증번호 → 자산연결.
 * 실 SMS 발송/검증은 백엔드 Solapi 연동 시 api.smsRequest/smsVerify로 교체(프론트는 키 미보유).
 */
import { useState } from 'react'
import { AppBar, ProgressBar, Cta, Scroll } from '../components/ui'
import { Sheet } from '../components/Sheet'
import { useSession } from '../state/session'

type Kind = 'text' | 'social' | 'carrier' | 'phone' | 'code'
interface Step { key: string; title: string; sub: string; label: string; kind: Kind; cta: string; ok: (v: string) => boolean }
const STEPS: Step[] = [
  { key: 'name', title: '이름을\n알려주세요', sub: '본인 확인이 끝나면 바로 가입돼요.', label: '이름', kind: 'text', cta: '다음', ok: (v) => v.trim().length >= 2 },
  { key: 'social', title: '주민등록번호\n앞 7자리를 입력해주세요', sub: '증명 확인에만 쓰고 저장하지 않아요.', label: '주민등록번호', kind: 'social', cta: '다음', ok: (v) => v.replace(/\D/g, '').length >= 7 },
  { key: 'carrier', title: '통신사를\n골라주세요', sub: '', label: '통신사', kind: 'carrier', cta: '다음', ok: (v) => !!v },
  { key: 'phone', title: '휴대폰 번호를\n입력해주세요', sub: '', label: '휴대폰 번호', kind: 'phone', cta: '인증요청', ok: (v) => v.replace(/\D/g, '').length >= 10 },
  { key: 'code', title: '문자로 받은\n인증번호를 입력해주세요', sub: '', label: '인증번호', kind: 'code', cta: '인증완료', ok: (v) => v.replace(/\D/g, '').length >= 6 },
]
const CARRIERS = ['SKT', 'KT', 'LG U+', '알뜰폰']

/** 숫자만 저장하고, 표시는 010-0000-0000 형태로 자동 하이픈. */
function formatPhone(digits: string): string {
  const n = digits.replace(/\D/g, '').slice(0, 11)
  if (n.length <= 3) return n
  if (n.length <= 7) return `${n.slice(0, 3)}-${n.slice(3)}`
  return `${n.slice(0, 3)}-${n.slice(3, 7)}-${n.slice(7)}`
}

const TERMS = [
  { id: 't1', label: '서비스 이용약관', req: true },
  { id: 't2', label: '개인(신용)정보 수집·이용 동의', req: true, desc: '소비 분석 목적으로만 사용, 제3자 제공 안 함' },
  { id: 't3', label: '고유식별정보 처리 동의', req: true },
  { id: 't4', label: '지킴이 알림·혜택 수신', req: false },
]

export function Auth() {
  const { go, back } = useSession()
  const [step, setStep] = useState(0)
  const [vals, setVals] = useState<Record<string, string>>({})
  const [consentOpen, setConsentOpen] = useState(false)
  const [consented, setConsented] = useState(false)
  const [checked, setChecked] = useState<Set<string>>(new Set())

  const cur = STEPS[step]
  const setVal = (v: string) => setVals((p) => ({ ...p, [cur.key]: v }))
  const curVal = vals[cur.key] ?? ''
  const curOk = cur.kind === 'social'
    ? (vals.social?.length ?? 0) >= 6 && (vals.socialG?.length ?? 0) >= 1
    : cur.ok(curVal)

  function next() {
    if (cur.key === 'phone' && !consented) { setConsentOpen(true); return }
    if (step >= STEPS.length - 1) { go('connect'); return }
    setStep(step + 1)
  }
  function confirmConsent() {
    setConsented(true); setConsentOpen(false)
    setTimeout(() => setStep(STEPS.findIndex((s) => s.key === 'code')), 200)
  }
  const reqOk = TERMS.filter((t) => t.req).every((t) => checked.has(t.id))
  const allOn = TERMS.every((t) => checked.has(t.id))
  const toggle = (id: string) => setChecked((p) => { const n = new Set(p); n.has(id) ? n.delete(id) : n.add(id); return n })
  const toggleAll = () => setChecked(allOn ? new Set() : new Set(TERMS.map((t) => t.id)))

  const done = STEPS.slice(0, step) // 이미 지난 단계(스택 아래에 회색으로)

  function field(s: Step, active: boolean) {
    const v = vals[s.key] ?? ''
    // 주민번호: 생년월일 6 + 성별 1 + 뒤 6자리 마스킹(●●●●●●)
    if (s.kind === 'social') {
      const b = vals.social ?? '', g = vals.socialG ?? ''
      if (!active) return <div className="field" style={{ pointerEvents: 'none' }}>{b ? `${b} - ${g || '•'} ●●●●●●` : '—'}</div>
      return (
        <div className="row2">
          <input className="field" style={{ flex: 1.3 }} autoFocus value={b} placeholder="생년월일 6자리" inputMode="numeric" maxLength={6}
            onChange={(e) => setVals((p) => ({ ...p, social: e.target.value.replace(/\D/g, '').slice(0, 6) }))} />
          <span style={{ alignSelf: 'center', color: 'var(--t3)' }}>-</span>
          <input className="field" style={{ flex: 0.35, textAlign: 'center' }} value={g} placeholder="0" inputMode="numeric" maxLength={1}
            onChange={(e) => setVals((p) => ({ ...p, socialG: e.target.value.replace(/\D/g, '').slice(0, 1) }))} />
          <span className="masked">●●●●●●</span>
        </div>
      )
    }
    const display = s.kind === 'phone' ? formatPhone(v) : v
    if (!active) return <div className="field" style={{ pointerEvents: 'none' }}>{display || '—'}</div>
    if (s.kind === 'carrier') return (
      <div className="seg">
        {CARRIERS.map((c) => (
          <button key={c} className={v === c ? 'on' : ''} onClick={() => setVal(c)}>{c}</button>
        ))}
      </div>
    )
    const num = s.kind !== 'text'
    return (
      <input className="field" autoFocus value={display}
        placeholder={s.kind === 'phone' ? '010-0000-0000' : s.kind === 'code' ? '6자리 입력' : '이름'}
        inputMode={num ? 'numeric' : 'text'} maxLength={s.kind === 'phone' ? 13 : s.kind === 'code' ? 6 : undefined}
        onChange={(e) => {
          const digits = e.target.value.replace(/\D/g, '')
          setVal(num ? (s.kind === 'phone' ? digits.slice(0, 11) : digits) : e.target.value)
        }} />
    )
  }

  return (
    <section className="screen">
      <AppBar onBack={step > 0 ? () => setStep(step - 1) : back} />
      <ProgressBar value={0.1 + step * 0.03} />
      <Scroll><div className="pad">
        <div className="h-title" style={{ whiteSpace: 'pre-line' }}>{cur.title}</div>
        {cur.sub && <div className="h-sub">{cur.sub}</div>}
        <div className="stack">
          <div className="fgroup"><div className="label">{cur.label}{cur.kind === 'code' && <span style={{ color: 'var(--blue)' }}> 03:00</span>}</div>{field(cur, true)}</div>
          {done.slice().reverse().map((s) => (
            <div key={s.key} className="fgroup done"><div className="label">{s.label}</div>{field(s, false)}</div>
          ))}
        </div>
        <div className="spacer" style={{ height: 20 }} />
      </div></Scroll>
      <Cta><button className="btn btn-primary" disabled={!curOk} onClick={next}>{cur.cta}</button></Cta>

      <Sheet open={consentOpen} onClose={() => setConsentOpen(false)}>
        <div className="sheet-title">가입하려면 동의가 필요해요</div>
        <p className="sheet-sub">소비 분석에 꼭 필요한 것만 받을게요.</p>
        <label className={`chk${allOn ? ' on' : ''}`} onClick={toggleAll}>
          <span className="box">✓</span><span className="ct"><b>전체 동의하기</b></span>
        </label>
        <div className="divider" />
        {TERMS.map((t) => (
          <label key={t.id} className={`chk${checked.has(t.id) ? ' on' : ''}`} onClick={() => toggle(t.id)}>
            <span className="box">✓</span>
            <span className="ct"><b>{t.label}</b> <span className="req" style={!t.req ? { color: 'var(--blue)' } : undefined}>({t.req ? '필수' : '선택'})</span>
              {t.desc && <p>{t.desc}</p>}</span>
          </label>
        ))}
        <div style={{ height: 10 }} />
        <button className="btn btn-primary" disabled={!reqOk} onClick={confirmConsent}>동의하고 인증번호 받기</button>
      </Sheet>
    </section>
  )
}
