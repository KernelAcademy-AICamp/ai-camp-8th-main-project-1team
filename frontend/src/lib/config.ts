/**
 * 앱 설정 — 전부 환경변수(frontend/.env*)에서. 시크릿은 프론트에 두지 않는다(백엔드 소관).
 * 값 예시는 frontend/.env.example.
 */
const env = import.meta.env;

/** 브라우저 → 백엔드 주소. */
export const API_BASE: string = (env.VITE_API_BASE as string | undefined) ?? 'http://localhost:8080';

/**
 * 데모 시연용 생성 마이데이터 CI(§13-11). 비어 있으면 데모 패널·온보딩 건너뛰기가 아예 노출되지 않는다.
 * 실사용 화면에 개발 기능이 새지 않도록 하는 유일한 스위치다.
 */
export const DEMO_CI: string = (env.VITE_DEMO_CI as string | undefined) ?? '';
export const DEMO_ENABLED = DEMO_CI.length > 0;

/** 앱 사용자 id 기본값. 사람 교체 연결 시 localStorage로 덮인다. */
export const DEFAULT_USER_ID = 1;

/** 챌린지 기본 기간(일) — 지킴이 설계서 §1(30일 고정). */
export const CHALLENGE_DAYS = 30;
