import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig, type Plugin } from 'vite'

const mobileUserAgent = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini|Mobile/i

function deviceRedirectPlugin(): Plugin {
  return {
    name: 'mobile-to-h5',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const isNavigation = (req.method === 'GET' || req.method === 'HEAD')
          && (req.headers.accept ?? '').includes('text/html')
        if (!isNavigation || !mobileUserAgent.test(req.headers['user-agent'] ?? '')) {
          next()
          return
        }
        const hostname = (req.headers.host ?? 'localhost').replace(/:\d+$/, '')
        res.statusCode = 302
        res.setHeader('Cache-Control', 'no-store')
        res.setHeader('Location', `http://${hostname}:5174${req.url ?? '/'}`)
        res.end()
      })
    },
  }
}

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue(), deviceRedirectPlugin()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'http://localhost:8087',
        changeOrigin: true,
      },
    },
  },
})
