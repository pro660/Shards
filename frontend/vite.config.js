import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

const backendOrigin = process.env.SHARDS_BACKEND_ORIGIN || 'http://127.0.0.1:8080';

const adminProxy = {
  '^/api/orders$': {
    target: backendOrigin,
    rewrite: () => '/orders',
  },
  '^/api/sql-console$': {
    target: backendOrigin,
    rewrite: () => '/admin/sql-console',
  },
  // 브라우저는 Vite의 주소로 요청하고, Vite가 Spring Boot에 전달해.
  '^/api/topology$': {
    target: backendOrigin,
    rewrite: () => '/admin/migrations/topology',
  },
  // 재배치 등록, 미완료 조회, 숫자 ID 조회와 두 관리 동작만 전달합니다.
  '^/api/migrations(?:/unfinished|/[1-9][0-9]*(?:/(?:advance|cancel))?)?$': {
    target: backendOrigin,
    rewrite: path => path.replace(/^\/api/, '/admin'),
  },
};

export default defineConfig({
  plugins: [react()],
  server: { host: '127.0.0.1', strictPort: true, proxy: adminProxy },
  preview: { host: '127.0.0.1', strictPort: true, proxy: adminProxy },
});
