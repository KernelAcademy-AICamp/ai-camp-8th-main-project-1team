/**
 * 관리 화면 진입점 — 사용자 앱과 <b>다른 번들</b>이다.
 *
 * 사용자 앱 어디에도 이 화면으로 가는 링크가 없고, 번들이 갈려 있어 코드도 경로도
 * 사용자에게 전달되지 않는다. 다만 그것은 소음 감소이지 방어가 아니다 —
 * 방어는 Argon2id · TOTP · IP 지연 · HttpOnly 쿠키 · 감사가 진다.
 */
import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { AdminApp } from './AdminApp';
import '../apply/ops.css';

createRoot(document.getElementById('root')!).render(
  <StrictMode><AdminApp /></StrictMode>,
);
