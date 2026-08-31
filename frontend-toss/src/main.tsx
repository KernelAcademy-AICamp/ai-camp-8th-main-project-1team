import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import App from './App';
import './styles/tokens.css';
import './styles/app.css';

/**
 * 첫 화면은 소비내역이다.
 *
 * 본 서비스는 마이데이터 연결 여부로 첫 화면을 갈랐다. 여기는 연결이 없으므로 가를 것이
 * 없고, 넣은 것이 없으면 소비내역이 스스로 빈 상태를 보여준다 — 빈 화면을 따로 만들면
 * 그 화면만 뒤처진다.
 */
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
