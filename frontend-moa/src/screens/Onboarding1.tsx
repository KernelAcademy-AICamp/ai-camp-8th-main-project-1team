/**
 * 온보딩 1/4 — AN-01 소비 분석 요약 + (충돌#4) 가치 소비 칩(성역 흡수).
 * ①분석 결과를 "낙인 없이" 보여주고, 줄이고 싶지 않은 소비를 선택(선택사항)받는다.
 */
import { Icon } from '../components/Icons'
import { AppBar, ProgressBar, Cta, Scroll, won } from '../components/ui'
import { useSession } from '../state/session'
import { mockAnalysis, VALUE_CATS, iconFor, bgFor } from '../lib/mock'

const DOW_KR: Record<string, string> = {
  MONDAY: '월', TUESDAY: '화', WEDNESDAY: '수', THURSDAY: '목', FRIDAY: '금', SATURDAY: '토', SUNDAY: '일',
}
const topKey = (m: Record<string, number>) =>
  Object.entries(m).sort((a, b) => b[1] - a[1])[0]?.[0] ?? ''

export function Onboarding1() {
  const { go, analysis, draft, patchDraft } = useSession()
  const a = analysis ?? mockAnalysis()
  const fixed = a.recurring.filter((r) => r.type === 'FIXED')
  const topDow = DOW_KR[topKey(a.pattern.amountByDayOfWeek)] ?? '금'
  const topPart = topKey(a.pattern.amountByDaypart) || '저녁'

  const toggleValue = (key: string) => {
    const on = draft.valueCats.includes(key)
    patchDraft({ valueCats: on ? draft.valueCats.filter((k) => k !== key) : [...draft.valueCats, key] })
  }

  return (
    <section className="screen">
      <AppBar title="분석 완료" steps="1 / 4" />
      <ProgressBar value={0.25} />
      <Scroll><div className="pad">
        <div className="h-title">최근 소비를<br />이렇게 하고 있었어요</div>
        <div className="h-sub">지킴이가 그동안의 소비를 살펴봤어요. 이 중에서 함께 줄여볼 곳을 곧 골라요.</div>

        {/* 소비 요약 — 습관 소비(줄일 후보 재료) */}
        <div className="card" style={{ padding: '8px 20px' }}>
          {a.cutCandidates.map((c, i) => (
            <div key={c.category2}>
              <div className="list-item">
                <span className="ic" style={{ background: bgFor(iconFor(c.category2)) }}><Icon id={iconFor(c.category2)} /></span>
                <div className="tx"><b>{c.category2}</b><span>{c.reason}</span></div>
                <span className="amt">월 {won(c.monthlySpend)}</span>
              </div>
              {i < a.cutCandidates.length - 1 && <div className="divider" />}
            </div>
          ))}
        </div>

        {/* 패턴 한마디 */}
        <div className="pv" style={{ margin: '0 0 14px' }}>
          <b>{topDow}요일 {topPart}</b>에 소비가 몰려요. 이런 순간을 지킴이가 같이 지켜볼게요.
        </div>

        {/* 고정지출 — 못 줄이는 소비로 분리 */}
        {fixed.length > 0 && (
          <>
            <div className="label">그동안 매달 빠져나간 고정지출이에요 <span style={{ color: 'var(--t3)', fontWeight: 600 }}>(못 줄여요)</span></div>
            <div className="chips">
              {fixed.map((f) => (
                <span key={f.merchantName} className="chip" style={{ cursor: 'default' }}>
                  {f.merchantName} · {won(f.representativeAmount)}
                </span>
              ))}
            </div>
          </>
        )}

        {/* 가치 소비 칩(성역) — 선택 */}
        <div className="label">줄이고 싶지 않은 소비가 있나요? <span style={{ color: 'var(--green)' }}>(선택)</span></div>
        <div className="h-sub" style={{ margin: '0 0 12px', fontSize: 13.5 }}>고른 소비는 지킴이가 <b style={{ color: 'var(--green)' }}>먼저 침묵</b>해요. 줄일 후보에서 빠져요.</div>
        <div className="chips">
          {VALUE_CATS.map((v) => (
            <div key={v.key} className={`chip sanctuary${draft.valueCats.includes(v.key) ? ' on' : ''}`} onClick={() => toggleValue(v.key)}>
              <Icon id={v.icon} className="ci" />{v.name}
            </div>
          ))}
        </div>
        <div className="spacer" style={{ height: 96 }} />
      </div></Scroll>
      <Cta><button className="btn btn-primary" onClick={() => go('ob2')}>줄일 카테고리 고르기</button></Cta>
    </section>
  )
}
