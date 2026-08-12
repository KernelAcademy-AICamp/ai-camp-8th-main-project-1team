import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { resolve } from 'node:path'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
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
