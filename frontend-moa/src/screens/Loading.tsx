/**
 * MD-04 조회 진행 + 분석 로딩. 마운트 시 ①분석(loadAnalysis)을 불러 세션에 저장하고,
 * 연출이 끝나면 온보딩 1로. (실 API가 있으면 실제 호출, 없으면 mock)
 *
 * StrictMode(개발 이중 실행) 대응: cleanup이 타이머를 확실히 지우고, 재실행 때 다시 건다.
 * ref 가드를 두면 재실행 때 타이머를 못 걸어 멈추므로 두지 않는다.
 */
import { useEffect, useState } from 'react'
import { Orb } from '../components/ui'
import { useSession } from '../state/session'
import { loadAnalysis } from '../lib/data'

const STEPS = [
  ['카드 내역을 불러오는 중…', '잠시만요, 그동안의 소비를 훑어보고 있어요'],
  ['소비 패턴을 분석하는 중…', '금요일 밤 배달, 매일 카페… 흥미롭네요?'],
  ['줄일 수 있는 지출을 찾는 중…', '무리 없이 지킬 수 있는 목표를 계산해요'],
  ['거의 다 됐어요…', '당신만의 절약 챌린지를 준비 중이에요'],
]

export function Loading() {
  const { userId, go, setAnalysis } = useSession()
  const [i, setI] = useState(0)

  useEffect(() => {
    // ① 분석 로드(실패해도 화면이 죽지 않게 — ob1에서 mock 폴백)
    loadAnalysis(userId).then(setAnalysis).catch(() => { /* noop */ })

    const timers: number[] = []
    STEPS.forEach((_, idx) => {
      if (idx > 0) timers.push(window.setTimeout(() => setI(idx), idx * 1000))
    })
    timers.push(window.setTimeout(() => go('ob1'), STEPS.length * 1000 + 500))
    return () => timers.forEach(clearTimeout)
  }, [userId, go, setAnalysis])

  return (
    <section className="screen">
      <div className="loadwrap">
        <Orb size={72} bob />
        <div className="load-title">{STEPS[i][0]}</div>
        <div className="load-bar"><i style={{ width: `${((i + 1) / STEPS.length) * 100}%` }} /></div>
        <div className="load-step">{STEPS[i][1]}</div>
      </div>
    </section>
  )
}
