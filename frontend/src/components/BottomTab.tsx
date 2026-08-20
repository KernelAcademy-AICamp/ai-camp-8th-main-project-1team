/**
 * 하단 탭 — 홈 · 리포트 · 마이 (IA §1.1 상시 탐색 3탭). 목업과 동일한 구성·아이콘·라벨.
 *
 * <b>탭 전환은 이력을 쌓지 않는다</b>(`replace`). 셋은 형제지 부모-자식이 아니라서, 홈↔리포트를
 * 세 번 오간 뒤 뒤로를 누르면 여섯 칸을 되짚느라 <b>앞 화면으로 못 간다</b> — 갇힌 것과 같다.
 * 네이티브 앱의 탭바가 뒤로가기 스택을 안 쌓는 것과 같은 규율이다.
 */
import { Icon } from './Icons';
import { useSession, type TabId } from '../state/session';

const TABS: { id: TabId; label: string; icon: string }[] = [
  { id: 'home', label: '홈', icon: 'i-home' },
  { id: 'report', label: '리포트', icon: 'i-chart' },
  { id: 'my', label: '마이', icon: 'i-user' },
];

export function BottomTab() {
  const { screen, replace } = useSession();
  return (
    <nav className="tabbar" aria-label="주요 화면">
      {TABS.map((t) => {
        const on = screen === t.id;
        return (
          <button key={t.id} type="button" className={on ? 'on' : ''}
            aria-current={on ? 'page' : undefined} onClick={() => replace(t.id)}>
            <Icon id={t.icon} className="" />
            <span className="tl">{t.label}</span>
          </button>
        );
      })}
    </nav>
  );
}
