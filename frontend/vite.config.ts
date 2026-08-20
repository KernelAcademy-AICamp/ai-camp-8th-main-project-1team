import { readFileSync } from 'node:fs';
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'node:path'

// https://vite.dev/config/
export default defineConfig({
  /* 마이 바닥글의 '앱 버전' — package.json 을 단일 출처로 둔다(손으로 적으면 잊는다). */
  define: {
    'import.meta.env.VITE_APP_VERSION': JSON.stringify(
      JSON.parse(readFileSync(new URL('./package.json', import.meta.url), 'utf8')).version),
  },
  plugins: [react()],
  // **개발 서버에서만 쓰는 프록시.** 빌드 산출물에는 안 들어간다.
  //
  // 왜 필요한가: `admin.html`·`apply.html` 번들은 API 주소를 **상대 경로**로 부른다
  // (`/api/admin/...`). 운영에서는 nginx 뒤 **동일 출처**라 그게 맞는데, 로컬에서는 그 요청이
  // 백엔드가 아니라 vite 자신에게 가서 화면에 'Load failed' 만 떴다.
  //
  // CORS 를 여는 것으로도 뚫리긴 한다. 그러지 않는 이유는 admin 토큰이 **HttpOnly 쿠키**라
  // 교차 출처로 보내려면 `allowCredentials` 까지 켜야 하고, 그러면 <b>운영에는 없는 경로</b>를
  // 하나 만들어 두는 셈이 된다. 프록시는 반대로 로컬을 운영과 **같은 모양**(동일 출처)으로
  // 만든다 — 로컬에서 되는 것이 운영에서도 되는 이유가 우연이 아니게 된다.
  server: {
    proxy: {
      '/api': { target: 'http://localhost:8080', changeOrigin: false },
    },
  },
  build: {
    rollupOptions: {
      // **번들을 셋으로 가른다** (설계서 Phase 3).
      //
      // 왜: 프론트 코드는 브라우저가 실행해야 하므로 반드시 사용자에게 전달된다. 하나의 SPA에
      // 관리 화면을 라우트로 넣으면 그 경로 이름과 코드가 **모든 방문자의 JS에 들어간다** —
      // 실측으로 지금 번들에서 화면 id 배열(`boot`,`walk`,`auth`,…)과 소스 문자열이 그대로
      // 검색된다(minify해도 리터럴은 남는다).
      //
      //   index.html  사용자 앱      (지금 그대로)
      //   apply.html  실사용자 신청  — 비로그인 공개
      //   admin.html  운영 관리      — 사용자에게 전달되지 않는다
      //
      // 데모가 끝나면 nginx에서 두 location만 빼면 사라진다.
      input: {
        index: resolve(__dirname, 'index.html'),
        apply: resolve(__dirname, 'apply.html'),
        admin: resolve(__dirname, 'admin.html'),
      },
    },
  },
})
