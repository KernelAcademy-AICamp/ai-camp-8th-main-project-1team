/**
 * 앱 설정 — 전부 환경변수(.env)에서. 시크릿은 프론트에 두지 않는다(백엔드 소관).
 * 값 변경은 frontend-moa/.env 에서. 예시는 .env.example.
 */
const env = import.meta.env

/** 백엔드 API 주소 (기존 frontend와 동일한 연결 규약). */
export const API_BASE: string = env.VITE_API_BASE ?? 'http://localhost:8080'

/** 데모 사용자 id (마이데이터 시드). */
export const DEMO_USER_ID: number = Number(env.VITE_DEMO_USER_ID ?? 1)

/**
 * 본인인증 모드 (충돌#7 결정).
 * - 'virtual': 내부 테스트 — 입력필드 없이 '가상 인증 통과' 버튼 1회
 * - 'sms'    : 외부 시연 — 입력필드 연출 + 백엔드 SMS(Solapi) 발송/검증 API 호출
 */
export type AuthMode = 'virtual' | 'sms'
export const AUTH_MODE: AuthMode = (env.VITE_AUTH_MODE ?? 'virtual') as AuthMode

/**
 * ②지킴·성장 / ③리포트는 아직 백엔드(에이전트) 미구현이라 mock으로 구동한다.
 * true = mock, false = 실제 엔드포인트. ①소비분석은 이 값과 무관하게 실제 API가 있으면 붙는다.
 */
export const USE_MOCK: boolean = (env.VITE_USE_MOCK ?? 'true') !== 'false'
