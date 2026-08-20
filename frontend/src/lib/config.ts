/**
 * 앱 설정 — 전부 환경변수(frontend/.env*)에서. 시크릿은 프론트에 두지 않는다(백엔드 소관).
 * 값 예시는 frontend/.env.example.
 *
 * ⚠️ `VITE_*`는 빌드 산출물에 그대로 박혀 공개된다. 여기 오는 값은 전부 공개 전제다.
 */
const env = import.meta.env;

/**
 * 브라우저 → 백엔드 주소.
 *
 * <b>앱(Capacitor)으로 감쌀 때 주의</b> — 웹 자산이 기기 안에서 실행되므로 `localhost`는
 * 개발 PC가 아니라 **기기 자신**을 가리킨다. 앱 빌드는 반드시 실제 도메인을 넣어야 하고,
 * 안드로이드가 API 28+에서 평문 HTTP를 기본 차단하므로 **HTTPS**여야 한다.
 * 백엔드 쪽 짝 설정은 `CORS_ALLOWED_ORIGINS`(앱 origin 허용).
 */
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

/**
 * 화면 바닥에 적는 앱 판(0818 마이 바닥글).
 *
 * <b>손으로 적지 않는다</b> — 빌드가 `package.json` 의 판을 넣는다(`vite.config.ts` 의 define).
 * 손으로 적으면 배포할 때마다 잊고, 잊은 채로 남은 숫자는 거짓말이 된다.
 */
export const APP_VERSION: string =
  (import.meta.env.VITE_APP_VERSION as string | undefined) ?? '1.0';
