import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import aitDevtools from '@apps-in-toss/devtools/unplugin';

/**
 * SSR 을 쓰지 않는다 — 앱인토스는 CSR·SSG 만 받는다(검수 체크리스트).
 * `base: './'` 인 이유는 번들이 파일로 말려 기기에서 열리기 때문이다. 절대경로면 못 찾는다.
 */
export default defineConfig({
  base: './',
  plugins: [aitDevtools.vite(), react()],
  build: { outDir: 'dist', target: 'es2020', sourcemap: false },
  server: { host: 'localhost', port: 5173 },
});
