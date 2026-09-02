/**
 * 全局 ECharts 视口自适应（resize 兜底）
 *
 * 背景：部分页面（Results / Defects / Traceability / CodeView / Requirements 等）创建了
 * ECharts 实例但未监听 window resize。移动端横竖屏切换、iOS 地址栏收起/展开时，
 * 这些图表不会重绘，表现为图表溢出容器或与容器宽度错位。
 *
 * 方案：统一在视口变化时遍历所有图表挂载点（ECharts 会在容器 DOM 上写入
 * `_echarts_instance_` 属性）并调用实例 resize()。对已自行监听 resize 的页面
 * 无副作用——resize() 幂等，重复调用不会重排数据。
 *
 * 加载优化：echarts 采用动态 import，且仅在页面上确实存在图表实例时才加载。
 * 这样登录/个人中心等无图表页面不会因本模块而下载 echarts（gzip 约 344KB），
 * 移动端首屏体积不受影响；有图表时动态导入会命中已加载的模块，无额外请求。
 */
let echartsPromise = null
let timer = null

/** 惰性加载 echarts（模块级缓存，避免重复请求） */
function loadEcharts() {
  if (!echartsPromise) echartsPromise = import('echarts')
  return echartsPromise
}

/** 重绘当前页面内所有 ECharts 实例 */
async function resizeAllCharts() {
  let nodes
  try {
    nodes = document.querySelectorAll('[_echarts_instance_]')
  } catch (e) {
    return
  }
  // 当前页面无图表：直接返回，不加载 echarts（保持首屏轻量）
  if (!nodes || nodes.length === 0) return

  const echarts = await loadEcharts()
  nodes.forEach((el) => {
    try {
      // 实例可能已 dispose 但 DOM 属性残留，故容错处理
      echarts.getInstanceByDom(el)?.resize()
    } catch (e) {
      /* 单个图表重绘失败不阻断其余图表 */
    }
  })
}

/** 防抖调度：避免 resize 高频触发导致连续重排 */
function scheduleResize() {
  clearTimeout(timer)
  timer = setTimeout(resizeAllCharts, 160)
}

/**
 * 注册全局图表自适应。
 * 幂等：内部以 __tgChartResizeInstalled 标记，重复调用不会重复注册。
 */
export function installChartAutoResize() {
  if (window.__tgChartResizeInstalled) return
  window.__tgChartResizeInstalled = true

  window.addEventListener('resize', scheduleResize)

  // 旋转屏幕：部分 iOS Safari 上 orientationchange 早于 resize，需延时等视口稳定
  window.addEventListener('orientationchange', () => setTimeout(resizeAllCharts, 280))

  // 视觉视口（VisualViewport）：iOS 地址栏收起/展开时高度变化，不一定触发 window.resize
  if (window.visualViewport) {
    window.visualViewport.addEventListener('resize', scheduleResize)
  }
}
