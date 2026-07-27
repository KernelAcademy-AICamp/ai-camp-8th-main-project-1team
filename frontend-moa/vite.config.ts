import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 새 앱은 5174에서 돈다(기존 frontend 5173과 병렬). 백엔드 8080은 VITE_API_BASE로.
// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: { port: 5174 },
})
