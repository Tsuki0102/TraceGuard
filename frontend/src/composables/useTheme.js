/**
 * 主题切换（W3-03 / R16 / 个性化增强 BATCH-1）
 * 亮色 / 深色两预设 + 主题色 4 变体 + 密度（舒适/紧凑），
 * 全部持久化 localStorage，通过 html[data-theme]/[data-accent]/[data-density] 驱动设计令牌。
 * 使用：main.js 启动时 initTheme() 防闪烁；组件内 useTheme() 切换。
 */
import { ref, computed } from 'vue'
import { schedulePreferenceSave } from '@/utils/preferenceSync'

const THEME_KEY = 'tg_theme'
const ACCENT_KEY = 'tg_accent'
const DENSITY_KEY = 'tg_density'

/** 主题色变体（暖调协调；CSS 变体块见 index.css 底部 [data-accent] 区段） */
export const ACCENTS = [
  { key: 'gold', label: '蜜金', color: '#C99B3F' },
  { key: 'olive', label: '橄榄', color: '#8FAF6E' },
  { key: 'coral', label: '陶土', color: '#D0783C' },
  { key: 'teal', label: '青黛', color: '#5E8A99' }
]

function readLS(key, fallback, allowed) {
  try {
    const v = localStorage.getItem(key)
    return v && allowed.includes(v) ? v : fallback
  } catch (e) {
    return fallback
  }
}

function writeLS(key, value) {
  try {
    localStorage.setItem(key, value)
  } catch (e) {
    /* 存储不可用时仅本次会话生效 */
  }
}

const theme = ref(readLS(THEME_KEY, 'light', ['light', 'dark']))
const accent = ref(readLS(ACCENT_KEY, 'gold', ACCENTS.map((a) => a.key)))
const density = ref(readLS(DENSITY_KEY, 'cozy', ['cozy', 'compact']))

function applyTheme(t) {
  theme.value = t
  document.documentElement.setAttribute('data-theme', t)
  writeLS(THEME_KEY, t)
  schedulePreferenceSave()
}

function applyAccent(a) {
  accent.value = a
  document.documentElement.setAttribute('data-accent', a)
  writeLS(ACCENT_KEY, a)
  schedulePreferenceSave()
}

function applyDensity(d) {
  density.value = d
  document.documentElement.setAttribute('data-density', d)
  writeLS(DENSITY_KEY, d)
  schedulePreferenceSave()
}

/** 应用端启动时调用（早于页面渲染，避免闪烁） */
export function initTheme() {
  applyTheme(theme.value === 'dark' ? 'dark' : 'light')
  applyAccent(accent.value)
  applyDensity(density.value)
}

export function useTheme() {
  const toggleTheme = () => {
    applyTheme(theme.value === 'dark' ? 'light' : 'dark')
  }
  const setTheme = (t) => {
    applyTheme(t === 'dark' ? 'dark' : 'light')
  }
  const setAccent = (a) => {
    if (ACCENTS.some((x) => x.key === a)) applyAccent(a)
  }
  const setDensity = (d) => {
    if (d === 'compact' || d === 'cozy') applyDensity(d)
  }
  const isDark = computed(() => theme.value === 'dark')
  return { theme, isDark, toggleTheme, setTheme, accent, setAccent, density, setDensity }
}

export default useTheme
