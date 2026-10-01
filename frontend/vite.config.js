import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import path from 'path'
import autoprefixer from 'autoprefixer'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src')
    }
  },
  css: {
    postcss: {
      plugins: [autoprefixer()]
    }
  },
  build: {
    // DOC-09：与 .browserslistrc / README 声明保持一致（Chrome/Edge/Firefox 100+）
    target: ['chrome100', 'edge100', 'firefox100'],
    // 移动端加载优化：拆分体积最大的三方库
    // 收益①：echarts（约 1MB）独立成块后，登录/个人中心等无图表页面不再下载它，首屏更快；
    // 收益②：三方库长期缓存——业务代码迭代不会使 vendor 缓存失效，二次访问近乎秒开；
    // 收益③：多个 vendor chunk 可并行下载，缓解移动网络单连接带宽瓶颈。
    rollupOptions: {
      output: {
        manualChunks(id) {
          if (!id.includes('node_modules')) return
          const p = id.replace(/\\/g, '/')
          if (p.includes('node_modules/echarts') || p.includes('node_modules/zrender')) {
            return 'vendor-echarts'
          }
          if (p.includes('node_modules/element-plus') || p.includes('@element-plus/icons-vue')) {
            return 'vendor-element'
          }
          if (p.includes('node_modules/vue') || p.includes('node_modules/@vue') ||
              p.includes('node_modules/vue-router') || p.includes('node_modules/pinia')) {
            return 'vendor-vue'
          }
          return 'vendor'
        }
      }
    },
    // 拆分后单块体积仍可能超默认 500kB 告警阈值（echarts 本身即 ~1MB），属预期
    chunkSizeWarningLimit: 1200
  },
  server: {
    // host: true → 监听 0.0.0.0，允许同 WiFi 下手机/平板通过电脑局域网 IP 访问
    // （默认仅监听 localhost，局域网设备无法访问）
    host: true,
    port: 3000,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
        configure: (proxy) => {
          // 局域网设备（手机）访问时，浏览器会带 Origin: http://<局域网IP>:3000，
          // 而后端 CORS 白名单只放行 localhost，登录 POST 会被 403 Invalid CORS request 拒绝。
          // dev 代理统一把 Origin 改写为白名单内的 localhost，后端安全白名单保持不变。
          const rewriteOrigin = (proxyReq) => {
            if (proxyReq.getHeader('origin')) {
              proxyReq.setHeader('origin', 'http://localhost:3000')
            }
          }
          proxy.on('proxyReq', rewriteOrigin)
          proxy.on('proxyReqWs', rewriteOrigin) // WebSocket 握手（通知推送）同样校验 Origin
        }
      }
    }
  }
})
