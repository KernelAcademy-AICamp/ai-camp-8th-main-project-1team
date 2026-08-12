/**
 * 신청 화면 진입점 — 사용자 앱과 <b>다른 번들</b>이다.
 *
 * 이렇게 갈라야 이 화면의 코드·경로가 사용자에게 배포되는 JS 에 들어가지 않는다.
 * (실측: 번들에서 화면 id 가 그대로 검색된다 — `boot`,`walk`,`auth`,…)
 */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { ApplyApp } from './ApplyApp';
// 사용자 앱과 **같은 스타일**을 쓴다 — 같은 서비스인데 겉모습이 다르면 여기가 어디인지 모른다.
// `ops.css` 는 admin 화면이 계속 쓰므로 그대로 두고, 이 화면만 갈아탄다(클래스 이름이 겹친다).
import '../styles/tokens.css';
import '../styles/app.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode><ApplyApp /></StrictMode>,
);
