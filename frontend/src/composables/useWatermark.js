/**
 * 轻量水印（W1-04 / R9）
 * 给容器叠加"登录名 · TraceGuard"斜排水印（SVG data-URI 平铺，pointer-events 穿透）。
 * 用法：onMounted(() => { removeWm = setupWatermark(containerRef.value) })
 *       onUnmounted(() => removeWm && removeWm())
 */
import UserContext from '@/store/user'

const WM_CLASS = 'tg-watermark'

export function setupWatermark(el, text) {
  if (!el || typeof window === 'undefined') return null

  const user = UserContext.get()
  const content = text || `${user.realName || user.username || ''} · TraceGuard`

  const svg =
    `<svg xmlns="http://www.w3.org/2000/svg" width="280" height="220">` +
    `<g fill="rgba(143,107,34,0.06)" font-family="'PingFang SC','Microsoft YaHei',sans-serif" font-size="13" transform="rotate(-28 40 120)">` +
    `<text x="20" y="60">${content}</text>` +
    `<text x="20" y="140">${content}</text>` +
    `<text x="20" y="220">${content}</text>` +
    `</g></svg>`

  const wm = document.createElement('div')
  wm.className = WM_CLASS
  wm.style.cssText =
    'position:absolute;inset:0;pointer-events:none;z-index:40;overflow:hidden;' +
    `background-image:url("data:image/svg+xml;utf8,${encodeURIComponent(svg)}");`

  // 容器需为定位上下文；记录原值便于还原
  const prevPosition = getComputedStyle(el).position
  if (prevPosition === 'static') el.style.position = 'relative'

  el.appendChild(wm)

  return () => {
    if (wm.parentNode === el) el.removeChild(wm)
    if (prevPosition === 'static') el.style.position = ''
  }
}

export default setupWatermark