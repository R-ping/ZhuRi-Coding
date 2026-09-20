import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue2'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { existsSync } from 'node:fs'

const __dirname = path.dirname(fileURLToPath(import.meta.url))
const srcDir = path.resolve(__dirname, 'src').replace(/\\/g, '/')

export default defineConfig({
  plugins: [
    vue(),
    { 
      name: 'resolve-at-alias',
      resolveId(source) {
        if (!source.startsWith('@/')) return null
        const cleanPath = srcDir + '/' + source.slice(2)
        // Return exact path if it has an extension
        if (/\.\w+$/.test(cleanPath)) return cleanPath
        // Try common extensions
        for (const ext of ['.js', '.vue', '.json', '.less', '.css']) {
          const fullPath = cleanPath + ext
          if (existsSync(fullPath)) return fullPath.replace(/\\/g, '/')
        }
        return cleanPath + '.js'
      }
    }
  ],
  server: {
    port: 9901,
    allowedHosts: [
      '195b7e5b.r40.cpolar.top',
      '.cpolar.top',
      'localhost'
    ],
    proxy: {
      // 注：模板遗留的远程代理已移除（所有服务均走下方网关 51601 本地直连）
      '/content': {
        target: 'http://127.0.0.1:51601/',
        changeOrigin: true,
        bypass(req) {
          // 前端路由 /content 由 SPA 处理，不代理到后端
          // 注意：文章详情页路由为 /article/:id，保持原样
          if (req.url === '/content' || req.url.startsWith('/content?')) {
            return req.url;
          }
        }
      },
      '/user': {
        target: 'http://127.0.0.1:51601/',
        changeOrigin: true,
        bypass(req) {
          // 前端路由 /user/* 由 SPA 处理，不代理到后端
          // 但 /user/api/* 的 API 请求需要代理到后端（如 token 刷新）
          if (req.url && req.url.startsWith('/user/') && !req.url.includes('/api/')) {
            return req.url;
          }
        }
      },
      '/search': {
        target: 'http://127.0.0.1:51601/',
        changeOrigin: true,
        bypass(req) {
          // 前端路由 /search、/search_result 由 SPA 处理，不代理到后端
          // 但 /search/api/* 的 API 请求需要代理到后端
          if (req.url && (req.url === '/search' || req.url === '/search_result' || req.url.startsWith('/search_result?') || req.url.startsWith('/search_result/'))) {
            return req.url;
          }
        }
      },
      '/wemedia': {
        target: 'http://127.0.0.1:51601/',
        changeOrigin: true
      },
      '/notification': {
        target: 'http://127.0.0.1:51601/',
        changeOrigin: true,
        bypass(req) {
          // 前端路由 /notification 由 SPA 处理，不代理到后端
          if (req.url === '/notification' || req.url.startsWith('/notification?')) {
            return req.url;
          }
        }
      },
      '/reward': {
        target: 'http://127.0.0.1:51601/',
        changeOrigin: true
      },
      // 兼容旧的 checkin 路径，重定向到新的 sign 路径
      '/api/v1/checkin': {
        target: 'http://127.0.0.1:51601/reward/api/v1/sign',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/api\/v1\/checkin/, '')
      },
      '/minio-static': {
        target: 'http://127.0.0.1:9005',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/minio-static/, '/leadnews')
      }
    }
  },
  build: {
    // 目标现代浏览器，减少 polyfill 体积
    target: 'es2015',
    // chunk 大小警告阈值上调（动态导入后单个 chunk 可能仍较大）
    chunkSizeWarningLimit: 800,
    rollupOptions: {
      output: {
        manualChunks: {
          'vue-vendor': ['vue', 'vue-router', 'vuex'],
          'element-ui': ['element-ui'],
          'echarts': ['echarts']
        }
      }
    }
  }
})
