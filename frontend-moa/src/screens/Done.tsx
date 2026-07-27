/**
 * ON-03 온보딩 완료·첫 챕터. 알림만 뜨고 2초 뒤 자동으로 홈으로(버튼 안 눌러도).
 * 강도 화면에서 이미 지킬 돈을 시드했으므로 여기선 응원 연출만.
 */
import { useEffect } from 'react'
import { Orb, won } from '../components/ui'
import { useSession } from '../state/session'
import { deriveKeep } from '../lib/mock'

export function Done() {
  const { go, keep } = useSession()
  const { savingGoal } = deriveKeep(keep.categories)

  useEffect(() => {
    const t = window.setTimeout(() => go('home'), 2000)
    return () => clearTimeout(t)
  }, [go])

  return (
    <section className="screen" style={{ background: 'linear-gradient(160deg,#EAF2FF,#F2F4F6)' }}>
      <div className="done-hero">
        <Orb size={84} bob />
        <div style={{ fontSize: 23, fontWeight: 800 }}>{keep.chapterLabel}이 시작됐어요</div>
        <p style={{ fontSize: 15, color: 'var(--t2)', lineHeight: 1.6, margin: 0 }}>
          이번 달 <b style={{ color: 'var(--blue)' }}>{won(savingGoal)}</b>, 지킴이와 함께 지켜봐요.<br />
          잠시 후 홈으로 이동해요 · 무리 안 하게 옆에서 챙길게요 💪
        </p>
      </div>
    </section>
  )
}
