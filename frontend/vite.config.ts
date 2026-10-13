import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// The browser talks to one origin; Vite forwards API calls to the Spring Boot backend.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': 'http://localhost:8080',
      '/dev': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
  preview: {
    proxy: {
      '/api': 'http://localhost:8080',
      '/dev': 'http://localhost:8080',
      '/actuator': 'http://localhost:8080',
    },
  },
});
