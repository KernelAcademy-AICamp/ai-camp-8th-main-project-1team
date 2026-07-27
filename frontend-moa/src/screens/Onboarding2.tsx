/**
 * 온보딩 2/4 — CT-01 줄일 카테고리 선택. ①추천 후보를 보여주고 사용자가 1~2개 확정.
 * 'AI 추천'은 참고용 배지 · 최종 선택은 사용자.
 */
import { useEffect, useRef } from 'react'
import { Icon } from '../components/Icons'
import { AppBar, ProgressBar, Cta, Scroll, won } from '../components/ui'
import { useSession } from '../state/session'
import { mockAnalysis, iconFor, bgFor } from '../lib/mock'

export function Onboarding2() {
  const { go, back, analysis, draft, patchDraft } = useSession()
  const a = analysis ?? mockAnalysis()
  const candidates = a.cutCandidates
  const recommended = candidates.slice(0, 2).map((c) => c.category2)
  const inited = useRef(false)

  // 첫 진입 시 AI 추천 상위 2개 미리 선택(사용자가 해제 가능)
  useEffect(() => {
    if (inited.current) return
    inited.current = true
    if (draft.cutCats.length === 0) patchDraft({ cutCats: recommended })
  }, [draft.cutCats.length, patchDraft, recommended])

  const toggle = (name: string) => {
    const on = draft.cutCats.includes(name)
    patchDraft({ cutCats: on ? draft.cutCats.filter((k) => k !== name) : [...draft.cutCats, name] })
  }

  return (
    <section className="screen">
      <AppBar onBack={back} steps="2 / 4" />
      <ProgressBar value={0.5} />
      <Scroll><div className="pad">
        <div className="h-title">뭘 줄여볼까요?</div>
        <div className="h-sub">지킴이가 <b style={{ color: 'var(--blue)' }}>AI 추천</b>으로 골라봤어요. 1~2개 권장. 금액은 <b>완전히 끊었을 때(최대)</b>예요 — 다음에서 강도로 실제 지킬 돈을 정해요.</div>
        <div style={{ display: 'flex', flexDirection: 'column', gap: 10 }}>
          {candidates.map((c) => {
            const on = draft.cutCats.includes(c.category2)
            const rec = recommended.includes(c.category2)
            return (
              <div key={c.category2} onClick={() => toggle(c.category2)}
                className="card" style={{
                  margin: 0, padding: 16, cursor: 'pointer', position: 'relative',
                  border: `1.5px solid ${on ? 'var(--blue)' : 'var(--line)'}`,
                  background: on ? 'var(--blue-weak)' : 'var(--card)',
                }}>
                {rec && <span className="badge" style={{ position: 'absolute', top: -8, right: 14, fontSize: 10, fontWeight: 700, background: 'var(--blue)', color: '#fff', padding: '2px 8px', borderRadius: 20 }}>AI 추천</span>}
                <div className="list-item" style={{ padding: 0 }}>
                  <span className="ic" style={{ background: bgFor(iconFor(c.category2)) }}><Icon id={iconFor(c.category2)} /></span>
                  <div className="tx">
                    <b>{c.category2} <span style={{ fontSize: 12, color: 'var(--t3)', fontWeight: 600 }}>{c.type === 'REMOVABLE' ? '제거 가능' : '줄이기 가능'}</span></b>
                    <span>{c.reason}</span>
                  </div>
                  <div style={{ textAlign: 'right' }}>
                    <b style={{ color: 'var(--green)', fontSize: 15 }}>−{won(c.monthlySpend)}</b>
                    <div style={{ fontSize: 11, color: 'var(--t3)' }}>최대(100%)</div>
                  </div>
                </div>
              </div>
            )
          })}
        </div>
        <div className="spacer" style={{ height: 96 }} />
      </div></Scroll>
      <Cta>
        <button className="btn btn-primary" disabled={draft.cutCats.length === 0} onClick={() => go('ob3')}>
          {draft.cutCats.length === 0 ? '줄일 소비를 골라주세요' : `${draft.cutCats.length}개로 시작하기`}
        </button>
      </Cta>
    </section>
  )
}
