/**
 * 온보딩 3/4 — CT-02 절약 강도. 프리셋 20/50/100%(사용자 확정) + 카테고리별 스테퍼 미세조정.
 * 지킬 돈 = 기준소비 × 강도. 기준소비·사용한도 같은 내부값은 노출하지 않는다(강도로 생기는 지킬 돈만).
 */
import { useEffect, useRef } from 'react'
import { Icon } from '../components/Icons'
import { AppBar, ProgressBar, Cta, Scroll, won } from '../components/ui'
import { useSession } from '../state/session'
import { mockAnalysis, iconFor, bgFor, INTENSITY_TIERS, DEFAULT_INTENSITY } from '../lib/mock'
import { buildKeepFromDraft } from '../lib/data'

const STEP = 0.1, MIN = 0.1, MAX = 1.0
const round1 = (n: number) => Math.round(n * 10) / 10

export function Onboarding3() {
  const { go, back, analysis, draft, patchDraft, setKeep } = useSession()
  const a = analysis ?? mockAnalysis()
  const baseOf = (name: string) => a.cutCandidates.find((c) => c.category2 === name)?.monthlySpend ?? 100_000
  const inited = useRef(false)

  useEffect(() => {
    if (inited.current) return
    inited.current = true
    const next = { ...draft.intensities }
    let changed = false
    draft.cutCats.forEach((n) => { if (next[n] == null) { next[n] = DEFAULT_INTENSITY; changed = true } })
    if (changed) patchDraft({ intensities: next })
  }, [draft.cutCats, draft.intensities, patchDraft])

  const setAll = (value: number) => {
    const next: Record<string, number> = { ...draft.intensities }
    draft.cutCats.forEach((n) => { next[n] = value })
    patchDraft({ intensities: next })
  }
  const bump = (name: string, dir: number) => {
    const cur = draft.intensities[name] ?? DEFAULT_INTENSITY
    patchDraft({ intensities: { ...draft.intensities, [name]: round1(Math.min(MAX, Math.max(MIN, cur + dir * STEP))) } })
  }

  const activeTier = INTENSITY_TIERS.find((t) =>
    draft.cutCats.length > 0 && draft.cutCats.every((n) => (draft.intensities[n] ?? DEFAULT_INTENSITY) === t.value))
  const total = draft.cutCats.reduce((s, n) => s + Math.round(baseOf(n) * (draft.intensities[n] ?? DEFAULT_INTENSITY)), 0)

  return (
    <section className="screen">
      <AppBar onBack={back} steps="3 / 4" />
      <ProgressBar value={0.75} />
      <Scroll><div className="pad">
        <div className="h-title">얼마나<br />줄여볼까요?</div>
        <div className="h-sub">강도를 고르면 이번 달 지킬 돈이 정해져요. 카테고리별로 다르게 잡아도 돼요.</div>

        <div className="int-seg">
          {INTENSITY_TIERS.map((t) => (
            <button key={t.key} className={activeTier?.key === t.key ? 'on' : ''} onClick={() => setAll(t.value)}>
              <b>{t.label}</b><span>{Math.round(t.value * 100)}%</span>
            </button>
          ))}
        </div>
        <div className="int-caption">{activeTier ? activeTier.caption : '카테고리별로 직접 맞췄어요'}</div>

        <div className="goal-card">
          <div className="gh-head">
            <div className="gh-cap">이번 달 지킬 돈</div>
            <div className="gh-num">{won(total)}</div>
          </div>
          {draft.cutCats.map((n) => {
            const inten = draft.intensities[n] ?? DEFAULT_INTENSITY
            const goal = Math.round(baseOf(n) * inten)
            return (
              <div key={n}>
                <div className="list-item">
                  <span className="ic" style={{ background: bgFor(iconFor(n)) }}><Icon id={iconFor(n)} /></span>
                  <div className="tx"><b>{n}</b><span>강도 {Math.round(inten * 100)}% · 지킬 돈 {won(goal)}</span></div>
                  <div className="stepper">
                    <button disabled={inten <= MIN} onClick={() => bump(n, -1)}>–</button>
                    <b>{Math.round(inten * 100)}%</b>
                    <button disabled={inten >= MAX} onClick={() => bump(n, +1)}>+</button>
                  </div>
                </div>
                <div className="divider" />
              </div>
            )
          })}
          <div className="pv" style={{ margin: '6px 0 12px' }}>강도가 높을수록 지킬 돈도, 난도도 함께 커져요. 무리 없이 시작해보세요.</div>
        </div>
        <div className="spacer" style={{ height: 40 }} />
      </div></Scroll>
      <Cta><button className="btn btn-primary" onClick={() => {
        setKeep(buildKeepFromDraft(draft.cutCats, draft.intensities, a.cutCandidates))
        go('done')
      }}>이 강도로 지키기</button></Cta>
    </section>
  )
}
