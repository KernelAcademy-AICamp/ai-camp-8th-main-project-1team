/**
 * MD-03 자산 연결 — 전 업권 기관을 한 화면에 쭉 펼쳐 보여준다(스크롤).
 * 하단 2칸 버튼: [한 번에 연결하기](전체) · [기관 직접 선택](체크한 것만).
 * → 전송요구 동의(시트) → 통합인증(시트) → 소비분석.
 */
import { useState } from 'react'
import { AppBar, ProgressBar, Cta, Scroll } from '../components/ui'
import { Sheet } from '../components/Sheet'
import { useSession } from '../state/session'
import { INSTITUTIONS, ALL_INST_IDS, type Inst, type InstCategory } from '../lib/institutions'

const PROVIDERS = [
  { name: '카카오톡', bg: '#FFCD00', fg: '#3c1e1e', label: 'K', desc: '카카오 지갑 인증서' },
  { name: '네이버', bg: '#03C75A', fg: '#fff', label: 'N', desc: '네이버 인증서' },
  { name: 'PASS', bg: '#E6002D', fg: '#fff', label: 'P', desc: '통신사 인증' },
  { name: '토스', bg: '#3182F6', fg: '#fff', label: 't', desc: '토스 인증서' },
]

const Logo = ({ inst }: { inst: Inst }) => (
  <span style={{ width: 34, height: 34, borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center', fontWeight: 800, fontSize: 11, color: inst.fg ?? '#fff', background: inst.bg, flex: '0 0 auto' }}>{inst.label}</span>
)

/** 체크 표식 — all(✓)·some(–)·none(빈). 선택은 초록. */
function Check({ state }: { state: 'all' | 'some' | 'none' }) {
  const on = state !== 'none'
  return (
    <span style={{ width: 22, height: 22, borderRadius: '50%', flex: '0 0 auto', border: `2px solid ${on ? 'var(--green)' : '#D3D9DF'}`, background: on ? 'var(--green)' : 'transparent', color: '#fff', display: 'flex', alignItems: 'center', justifyContent: 'center', fontSize: 12, fontWeight: 800 }}>
      {state === 'all' ? '✓' : state === 'some' ? '–' : ''}
    </span>
  )
}

export function Connect() {
  const { go, back } = useSession()
  const [picked, setPicked] = useState<Set<number>>(new Set())
  const [transferOpen, setTransferOpen] = useState(false)
  const [easyOpen, setEasyOpen] = useState(false)
  const [waiting, setWaiting] = useState<string | null>(null)

  const allOn = picked.size === ALL_INST_IDS.length
  const toggle = (id: number) => setPicked((p) => { const n = new Set(p); n.has(id) ? n.delete(id) : n.add(id); return n })
  const toggleAll = () => setPicked(allOn ? new Set() : new Set(ALL_INST_IDS))
  const toggleCat = (cat: InstCategory) => {
    const ids = cat.items.map((i) => i.id)
    const allSel = ids.every((id) => picked.has(id))
    setPicked((p) => { const n = new Set(p); ids.forEach((id) => allSel ? n.delete(id) : n.add(id)); return n })
  }
  const catState = (cat: InstCategory): 'all' | 'some' | 'none' => {
    const sel = cat.items.filter((i) => picked.has(i.id)).length
    return sel === cat.items.length ? 'all' : sel ? 'some' : 'none'
  }

  const connectAll = () => { setPicked(new Set(ALL_INST_IDS)); setTransferOpen(true) }
  const connectPicked = () => { if (picked.size) setTransferOpen(true) }
  const agreeTransfer = () => { setTransferOpen(false); setEasyOpen(true) }
  const pickProvider = (name: string) => { setWaiting(name); setTimeout(() => { setEasyOpen(false); go('loading') }, 2000) }

  return (
    <section className="screen">
      <AppBar onBack={back} title="자산 연결" />
      <ProgressBar value={0.3} />

      <Scroll><div className="pad">
        <div className="h-title">연결할 기관을<br />선택해주세요</div>
        <div className="h-sub">쓰시는 금융사를 골라도 되고, 귀찮으면 <b>한 번에 연결</b>해도 돼요. 결제·송금 권한은 요구하지 않아요.</div>

        {/* 전체 선택 */}
        <div onClick={toggleAll} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 2px 12px', cursor: 'pointer' }}>
          <Check state={allOn ? 'all' : picked.size ? 'some' : 'none'} />
          <b style={{ fontSize: 15 }}>전체 선택</b>
          <span style={{ marginLeft: 'auto', fontSize: 12.5, color: 'var(--t3)', fontWeight: 600 }}>{picked.size}개 선택</span>
        </div>

        {/* 전 업권 · 모두 펼쳐서 노출 */}
        {INSTITUTIONS.map((cat) => (
          <div key={cat.key} style={{ background: 'var(--card)', borderRadius: 14, marginBottom: 8, padding: '4px 16px 8px' }}>
            <div onClick={() => toggleCat(cat)} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '12px 0 8px', cursor: 'pointer' }}>
              <Check state={catState(cat)} />
              <b style={{ fontSize: 15 }}>{cat.name}<span style={{ color: 'var(--t3)', fontWeight: 600, fontSize: 12.5, marginLeft: 6 }}>{cat.items.length}</span></b>
            </div>
            {cat.items.map((inst) => {
              const on = picked.has(inst.id)
              return (
                <div key={inst.id} onClick={() => toggle(inst.id)} style={{ display: 'flex', alignItems: 'center', gap: 12, padding: '11px 0', borderTop: '1px solid var(--bg)', cursor: 'pointer' }}>
                  <Logo inst={inst} />
                  <span style={{ flex: 1, fontSize: 14.5, fontWeight: 600, color: on ? 'var(--t1)' : 'var(--t2)' }}>{inst.name}</span>
                  <Check state={on ? 'all' : 'none'} />
                </div>
              )
            })}
          </div>
        ))}
        <div className="spacer" style={{ height: 96 }} />
      </div></Scroll>

      {/* 하단 2칸 버튼 */}
      <Cta>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          <button className="btn btn-primary" style={{ fontSize: 15 }} onClick={connectAll}>한 번에 연결하기</button>
          <button className="btn btn-ghost" style={{ fontSize: 15, opacity: picked.size ? 1 : 0.5 }} disabled={picked.size === 0} onClick={connectPicked}>기관 직접 선택</button>
        </div>
      </Cta>

      {/* 전송요구 동의 */}
      <Sheet open={transferOpen} onClose={() => setTransferOpen(false)}>
        <div className="sheet-title">데이터 전송을 요구할게요</div>
        <p className="sheet-sub">마이데이터 전송요구권에 따라, 아래 내용대로만 가져와요.</p>
        <div className="trow"><span className="k">전송 요구 항목</span><span className="v">카드 이용내역 · 승인내역</span></div>
        <div className="trow"><span className="k">보유·이용 기간</span><span className="v">서비스 해지 시까지</span></div>
        <div className="trow"><span className="k">정기 전송</span><span className="v">주 1회 + 승인내역 알림</span></div>
        <div className="trow" style={{ border: 'none' }}><span className="k">전송요구 만료일</span><span className="v">전송요구일로부터 1년</span></div>
        <div className="pv" style={{ marginTop: 10 }}>결제·송금 권한은 포함되지 않아요. 마이 &gt; 연결 관리에서 언제든 철회할 수 있어요.</div>
        <div style={{ height: 14 }} />
        <button className="btn btn-primary" onClick={agreeTransfer}>전송요구에 동의해요</button>
      </Sheet>

      {/* 통합인증 */}
      <Sheet open={easyOpen} onClose={waiting ? undefined : () => setEasyOpen(false)}>
        {!waiting ? (
          <>
            <div className="sheet-title">인증서로 한 번 더 확인할게요</div>
            <p className="sheet-sub">마이데이터 연결엔 통합인증이 필요해요. 쓰시는 걸로 골라주세요.</p>
            {PROVIDERS.map((p) => (
              <div key={p.name} className="provider" onClick={() => pickProvider(p.name)}>
                <span className="pl" style={{ background: p.bg, color: p.fg }}>{p.label}</span>
                <div><b>{p.name}</b><p>{p.desc}</p></div>
              </div>
            ))}
          </>
        ) : (
          <div style={{ textAlign: 'center', padding: '14px 0 6px' }}>
            <div className="spinner" />
            <div style={{ fontSize: 17, fontWeight: 700 }}>{waiting}에서 인증을 완료해주세요</div>
            <p style={{ fontSize: 13, color: 'var(--t3)', margin: '6px 0 0' }}>앱으로 인증 요청을 보냈어요</p>
          </div>
        )}
      </Sheet>
    </section>
  )
}
