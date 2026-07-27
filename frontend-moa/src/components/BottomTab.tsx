/** 하단 탭 — 홈 · 리포트 · 마이 (IA §1.1 상시 탐색 3탭). */
import { Icon } from './Icons'
import { useSession, type TabId } from '../state/session'

const TABS: { id: TabId; label: string; icon: string }[] = [
  { id: 'home', label: '홈', icon: 'i-home' },
  { id: 'report', label: '리포트', icon: 'i-chart' },
  { id: 'my', label: '마이', icon: 'i-user' },
]

export function BottomTab() {
  const { screen, go } = useSession()
  return (
    <div className="tabbar">
      {TABS.map((t) => (
        <button key={t.id} className={screen === t.id ? 'on' : ''} onClick={() => go(t.id)}>
          <Icon id={t.icon} className="" />
          <span className="tl">{t.label}</span>
        </button>
      ))}
    </div>
  )
}
