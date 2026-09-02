/**
 * ECharts 统一主题（蜂蜜琥珀暖色系）
 * 说明：全站图表配色/文字/坐标轴/提示框集中于此，禁止在页面中散落硬编码色值。
 * 页面使用方式：echarts.init(el, 'apple')
 */
import * as echarts from 'echarts'
import { getCurrentInstance, onUnmounted } from 'vue'

const appleTheme = {
  color: [
    '#8F6B22', // 深金（主）
    '#6B8E4E', // 橄榄绿（成功）
    '#E89B3C', // 暖琥珀（警示）
    '#C25E4C', // 陶土红（危险）
    '#C99B3F', // 蜜糖金
    '#E8C877', // 奶油金
    '#D9A966', // 蜜桃金
    '#B0653F'  // 陶土棕
  ],
  backgroundColor: 'transparent',
  textStyle: {
    fontFamily:
      'Inter, -apple-system, "PingFang SC", "Microsoft YaHei", sans-serif',
    color: '#7A6F5F'
  },
  title: {
    textStyle: { color: '#2B241C', fontWeight: 600 }
  },
  legend: {
    textStyle: { color: '#7A6F5F' }
  },
  categoryAxis: {
    axisLine: { lineStyle: { color: 'rgba(0,0,0,0.12)' } },
    axisTick: { show: false },
    axisLabel: { color: '#7A6F5F' }
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    splitLine: { lineStyle: { color: 'rgba(0,0,0,0.06)' } },
    axisLabel: { color: '#7A6F5F' }
  },
  tooltip: {
    backgroundColor: 'rgba(255,255,255,0.96)',
    borderColor: 'rgba(0,0,0,0.08)',
    textStyle: { color: '#2B241C' }
  }
}


/**
 * 图表语义色（集中管理，禁止在页面中散落魔法色值）
 * 页面使用：import { chartColors } from '@/utils/echartsTheme'
 */
export const chartColors = {
  primary: '#8F6B22',   // 深金（主色/选中）
  success: '#6B8E4E',   // 橄榄绿（成功/已覆盖/true）
  warning: '#E89B3C',   // 暖琥珀（警示/循环/一般缺陷）
  danger: '#C25E4C',    // 陶土红（危险/严重缺陷/false）
  purple: '#C99B3F',    // 蜜糖金
  sky: '#E8C877',       // 奶油金
  neutral: '#7A6F5F',   // 暖灰（次要文字）
  faint: '#A89B82'      // 弱暖灰
}

/** 深色主题（跟随 html[data-theme='dark']；文字/轴线/提示框取自深色令牌） */
const appleDarkTheme = {
  ...appleTheme,
  textStyle: {
    ...appleTheme.textStyle,
    color: '#B3A48C'
  },
  title: {
    textStyle: { color: '#EDE3D2', fontWeight: 600 }
  },
  legend: {
    textStyle: { color: '#B3A48C' }
  },
  categoryAxis: {
    axisLine: { lineStyle: { color: 'rgba(237,227,210,0.16)' } },
    axisTick: { show: false },
    axisLabel: { color: '#B3A48C' }
  },
  valueAxis: {
    axisLine: { show: false },
    axisTick: { show: false },
    splitLine: { lineStyle: { color: 'rgba(237,227,210,0.1)' } },
    axisLabel: { color: '#B3A48C' }
  },
  tooltip: {
    backgroundColor: 'rgba(38,32,25,0.96)',
    borderColor: 'rgba(237,227,210,0.14)',
    textStyle: { color: '#EDE3D2' }
  }
}

echarts.registerTheme('apple', appleTheme)
echarts.registerTheme('apple-dark', appleDarkTheme)

/** 当前是否深色（图表渲染前读取） */
export const isDarkTheme = () =>
  typeof document !== 'undefined' && document.documentElement.getAttribute('data-theme') === 'dark'

/** 当前应使用的 ECharts 主题名 */
export const chartThemeName = () => (isDarkTheme() ? 'apple-dark' : 'apple')

/** 深浅色自适应的文字/轴线/提示框取色（页面 option 内使用，替代硬编码） */
export const chartText = () => (isDarkTheme() ? '#B3A48C' : '#7A6F5F')
export const chartTitleColor = () => (isDarkTheme() ? '#EDE3D2' : '#2B241C')
export const chartFaint = () => (isDarkTheme() ? '#8A7C66' : '#A89B82')
export const chartAxisLine = () => (isDarkTheme() ? 'rgba(237,227,210,0.16)' : 'rgba(0,0,0,0.12)')
export const chartSplitLine = () => (isDarkTheme() ? 'rgba(237,227,210,0.1)' : 'rgba(0,0,0,0.06)')
export const chartTrack = () => (isDarkTheme() ? 'rgba(237,227,210,0.1)' : 'rgba(0, 0, 0, 0.06)')
export const chartTooltipBg = () => (isDarkTheme() ? 'rgba(38,32,25,0.96)' : 'rgba(255,255,255,0.96)')

/** 订阅主题变化（html[data-theme] 变更时回调 cb(isDark)；在 setup 中调用时自动随组件卸载清理） */
export function onChartThemeChange(cb) {
  if (typeof MutationObserver === 'undefined' || typeof document === 'undefined') return () => {}
  const obs = new MutationObserver(() => cb(isDarkTheme()))
  obs.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
  const stop = () => obs.disconnect()
  try {
    const { getCurrentInstance, onUnmounted } = vue
    if (getCurrentInstance()) onUnmounted(stop)
  } catch (e) { /* 非 setup 环境：手动调用返回的停止函数 */ }
  return stop
}

/** 深浅色两套系列色（深色下整体提亮一档） */
export const getChartColors = (dark = isDarkTheme()) =>
  dark
    ? {
        primary: '#D9A966',
        success: '#9AB27B',
        warning: '#E89B3C',
        danger: '#D97A66',
        purple: '#D9A966',
        sky: '#C9A94F',
        neutral: '#B3A48C',
        faint: '#8A7C66'
      }
    : chartColors

export default echarts
