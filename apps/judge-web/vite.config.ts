import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

const proxy = Object.fromEntries(
  ['/api', '/auth', '/oauth2', '/login/oauth2'].map((path) => [
    path,
    {
      target: process.env.JUDGE_WEB_API_TARGET ?? 'http://localhost:8081',
      changeOrigin: false,
    },
  ]),
)

export default defineConfig({
  plugins: [react()],
  server: { host: 'localhost', port: 5173, strictPort: true, proxy },
  preview: { host: 'localhost', port: 5173, strictPort: true, proxy },
})
