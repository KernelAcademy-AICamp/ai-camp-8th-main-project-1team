/**
 * 신청 화면 진입점 — 사용자 앱과 <b>다른 번들</b>이다.
 *
 * 이렇게 갈라야 이 화면의 코드·경로가 사용자에게 배포되는 JS 에 들어가지 않는다.
 * (실측: 번들에서 화면 id 가 그대로 검색된다 — `boot`,`walk`,`auth`,…)
 */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { ApplyApp } from './ApplyApp';
import './ops.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode><ApplyApp /></StrictMode>,
);
