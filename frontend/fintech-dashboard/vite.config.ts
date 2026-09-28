import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// Gateway + Keycloak are public endpoint addresses (never secrets) and come from
// environment so the same build runs against local Docker and any other env.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    strictPort: true,
  },
  preview: {
    port: 5173,
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
    globals: true,
  },
});
