/**
 * 하단 탭 — 홈 · 리포트 · 내역 · 추가.
 *
 * 본 서비스는 홈·리포트·마이 셋을 두고 그 아래에 화면들이 달리지만, 여기는 넷이 전부라
 * 계층이 없다.
 *
 * <b>탭 전환은 이력을 쌓지 않는다</b>(`replace`). 넷은 형제지 부모-자식이 아니라서,
 * 오갈 때마다 칸을 쌓으면 뒤로가기가 그만큼 되짚느라 앱을 못 벗어난다. 토스가 뒤로가기를
 * 자기 네비게이션 바로 주므로 여기서 갇히면 빠져나갈 길이 없다.
 */
import { Icon } from './Icons';
import { useSession, type ScreenId } from '../state/session';

const TABS: { id: ScreenId; label: string; icon: string }[] = [
  { id: 'home', label: '홈', icon: 'i-home' },
  { id: 'report', label: '리포트', icon: 'i-chart' },
  { id: 'transactions', label: '내역', icon: 'i-doc' },
  { id: 'm-record', label: '추가', icon: 'i-plus' },
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
