import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus from 'element-plus'
import 'element-plus/dist/index.css'
import * as ElementPlusIconsVue from '@element-plus/icons-vue'
// Apple 风格设计令牌（须在 element-plus 样式之后引入以便覆盖）
import '@/styles/index.css'
// 注册 ECharts 统一主题（副作用：全局 registerTheme）
import '@/utils/echartsTheme'
import App from './App.vue'
import router from './router'
// 主题初始化（W3-03：挂载前设置 html[data-theme]，避免暗色模式闪烁）
import { initTheme } from '@/composables/useTheme'
// 全局 ECharts 视口自适应（移动端旋转屏 / 地址栏伸缩时重绘未自行监听 resize 的图表）
import { installChartAutoResize } from '@/utils/chartResize'

initTheme()
installChartAutoResize()

const app = createApp(App)

for (const [key, component] of Object.entries(ElementPlusIconsVue)) {
  app.component(key, component)
}

app.use(createPinia())
app.use(router)
app.use(ElementPlus)

// 全局 v-reveal 指令：元素进入视口时添加 .is-visible，配合 .tg-reveal 实现滚动出现
const revealObserver = new IntersectionObserver(
  (entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        entry.target.classList.add('is-visible')
        revealObserver.unobserve(entry.target)
      }
    })
  },
  { threshold: 0.12 }
)

app.directive('reveal', {
  mounted(el) {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      el.classList.add('is-visible')
      return
    }
    revealObserver.observe(el)
  },
  unmounted(el) {
    revealObserver.unobserve(el)
  }
})

// 全局 v-countup 指令：元素进入视口后，将 :value 从 0 滚动到目标值（配合 .tg-count）
// 支持 updated：:value 变化时从当前显示值平滑滚动到新值（Dashboard 异步加载的统计数字）
const countObserver = new IntersectionObserver(
  (entries) => {
    entries.forEach((entry) => {
      if (!entry.isIntersecting) return
      const el = entry.target
      countObserver.unobserve(el)
      const target = Number(el.getAttribute('data-count') || el.textContent || 0)
      const duration = 1200
      const start = performance.now()
      const step = (now) => {
        const p = Math.min((now - start) / duration, 1)
        const eased = 1 - Math.pow(1 - p, 3)
        el.textContent = Math.round(target * eased)
        if (p < 1) requestAnimationFrame(step)
      }
      requestAnimationFrame(step)
    })
  },
  { threshold: 0.4 }
)

app.directive('countup', {
  mounted(el, binding) {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      el.textContent = binding.value
      return
    }
    el.setAttribute('data-count', binding.value)
    countObserver.observe(el)
  },
  updated(el, binding) {
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) {
      el.textContent = binding.value
      return
    }
    const target = Number(binding.value)
    const prev = Number(el.getAttribute('data-count'))
    if (target === prev || Number.isNaN(target)) return
    el.setAttribute('data-count', target)
    const from = Number(el.textContent) || 0
    if (from === target) return
    const duration = 900
    const start = performance.now()
    const step = (now) => {
      const p = Math.min((now - start) / duration, 1)
      const eased = 1 - Math.pow(1 - p, 3)
      el.textContent = Math.round(from + (target - from) * eased)
      if (p < 1) requestAnimationFrame(step)
    }
    requestAnimationFrame(step)
  },
  unmounted(el) {
    countObserver.unobserve(el)
  }
})

app.mount('#app')
