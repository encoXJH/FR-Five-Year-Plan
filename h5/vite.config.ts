import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig, type Plugin } from 'vite'

const mobileUserAgent = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini|Mobile/i

function deviceRedirectPlugin(): Plugin {
  return {
    name: 'desktop-to-pc',
    configureServer(server) {
      server.middlewares.use((req, res, next) => {
        const isNavigation = (req.method === 'GET' || req.method === 'HEAD')
          && (req.headers.accept ?? '').includes('text/html')
        const isMobile = mobileUserAgent.test(req.headers['user-agent'] ?? '')
        if (!isNavigation || isMobile) {
          next()
          return
        }
        const hostname = (req.headers.host ?? 'localhost').replace(/:\d+$/, '')
        res.statusCode = 302
        res.setHeader('Cache-Control', 'no-store')
        res.setHeader('Location', `http://${hostname}:5173${req.url ?? '/'}`)
        res.end()
      })
    },
  }
}

export default defineConfig(({ command }) => ({
  base: command === 'build' ? '/h5/' : '/',
  plugins: [vue(), deviceRedirectPlugin()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    host: true,
    proxy: {
      '/api': {
        target: 'http://localhost:8087',
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            // 开发代理与后端同源转发，去掉浏览器 Origin，避免后端 CORS 白名单只含 PC 端口
            proxyReq.removeHeader('origin')
          })
        },
      },
    },
  },
  preview: {
    proxy: {
      '/api': {
        target: 'http://localhost:8087',
        changeOrigin: true,
        configure: (proxy) => {
          proxy.on('proxyReq', (proxyReq) => {
            proxyReq.removeHeader('origin')
          })
        },
      },
    },
  },
}))
