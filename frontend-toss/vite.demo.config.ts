import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

/**
 * 보여주기용 빌드 — 예시 소비를 채운 채로 열린다.
 *
 * 앱 빌드(`vite.config.ts`)와 갈라 둔 이유는 <b>`moaa.ait` 에 예시 데이터가 섞이면 안 되기</b>
 * 때문이다. 여기는 진입점만 다르고 코드는 같은 것을 쓴다. 앱인토스 devtools 도 안 붙인다.
 */
export default defineConfig({
  base: './',
  plugins: [react()],
  build: {
    outDir: 'demo-dist',
    target: 'es2020',
    sourcemap: false,
    rollupOptions: { input: 'demo.html' },
  },
});
