import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173, strictPort: true,
    proxy: {
      '/api/auth': process.env.DEMAND_API_TARGET || 'http://localhost:8082',
      '/api/waitlists': process.env.DEMAND_API_TARGET || 'http://localhost:8082',
      '/api/customers': process.env.DEMAND_API_TARGET || 'http://localhost:8082',
      '/api/analytics': process.env.DEMAND_API_TARGET || 'http://localhost:8082',
      '/api/products': process.env.CATALOG_API_TARGET || 'http://localhost:8081',
      '/api/variants': process.env.CATALOG_API_TARGET || 'http://localhost:8081',
    },
  },
  test: { environment: 'jsdom', setupFiles: './src/test/setup.ts' },
});

