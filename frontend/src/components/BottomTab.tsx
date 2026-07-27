/** 하단 탭 — 홈 · 리포트 · 마이 (IA §1.1 상시 탐색 3탭). 목업과 동일한 구성·아이콘·라벨. */
import { Icon } from './Icons';
import { useSession, type TabId } from '../state/session';

const TABS: { id: TabId; label: string; icon: string }[] = [
  { id: 'home', label: '홈', icon: 'i-home' },
  { id: 'report', label: '리포트', icon: 'i-chart' },
  { id: 'my', label: '마이', icon: 'i-user' },
];

export function BottomTab() {
  const { screen, go } = useSession();
  return (
    <nav className="tabbar" aria-label="주요 화면">
      {TABS.map((t) => {
        const on = screen === t.id;
        return (
          <button key={t.id} type="button" className={on ? 'on' : ''}
            aria-current={on ? 'page' : undefined} onClick={() => go(t.id)}>
            <Icon id={t.icon} className="" />
            <span className="tl">{t.label}</span>
          </button>
        );
      })}
    </nav>
  );
}
