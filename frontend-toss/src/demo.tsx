/**
 * 보여주기용 진입점 — <b>앱 자체는 손대지 않는다.</b>
 *
 * 처음 열면 당연히 비어 있어서 화면들이 무엇을 그리는지 볼 수가 없다. 저장소가 비었을 때만
 * 예시를 채우고, 그 다음은 진짜 앱이 그대로 돈다 — 분류도 판정도 실제 표가 한다.
 */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import { add, rows } from './engine/store';
import { seedRows } from './demoData';
import './styles/tokens.css';
import './styles/app.css';

if (rows().length === 0) add(seedRows());
/*
 * <b>`location.hash = …` 를 쓰면 안 된다.</b> 그건 이력에 칸을 하나 쌓아서, 앱을 열자마자
 * 뒤로가기를 눌러도 안 나가지고 주소만 빈 해시로 되돌아온다. 안드로이드 뒤로가기로
 * 미니앱을 나갈 수 있어야 한다는 검수 항목에 정면으로 걸린다. 첫 칸을 덮어쓴다.
 */
/*
 * <b>연결 단계가 없다.</b> 본 서비스는 마이데이터를 연결해야 화면이 열리고, 그 여부를
 * `mydata_onboarded` 로 들고 다닌다(`state/session.tsx`). 이 앱에는 연결할 것이 없는데
 * 그 값이 비어 있으면 <b>지킴이 상태를 아예 안 불러와서</b> 홈이 "소비를 넣어볼까요?" 빈
 * 화면에 머문다 — 소비가 있어도 그렇다. 열 때 한 번 켠다.
 *
 * 세션 코드를 안 고치는 이유: 그 파일은 본 서비스와 같은 것이라 손대면 두 앱이 갈라진다.
 */
try { localStorage.setItem('mydata_onboarded', 'true'); } catch { /* 저장소가 막힌 기기 */ }

if (!location.hash) history.replaceState(history.state, '', '#/home');

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
);
