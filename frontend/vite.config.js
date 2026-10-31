import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  test: {
    environment: 'jsdom',
    setupFiles: './src/test/setup.js',
    // Les tests de bout en bout (e2e/, Playwright) tournent contre une vraie pile, pas sous Vitest.
    include: ['src/**/*.test.{js,jsx}'],
    coverage: {
      provider: 'v8',
      include: ['src/**/*.{js,jsx}'],
      // Types générés, point d'entrée et fichiers de test ne sont pas du code à couvrir.
      exclude: ['src/types/**', 'src/main.jsx', 'src/**/*.test.{js,jsx}', 'src/test/**'],
      reporter: ['text', 'json-summary', 'lcov'],
      // Seuils bloquants : le build échoue si la couverture passe en dessous.
      thresholds: {
        statements: 75,
        branches: 65,
        functions: 75,
        lines: 75,
      },
    },
  },
})
