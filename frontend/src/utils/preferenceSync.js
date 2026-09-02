/**
 * 用户偏好跨设备同步（个性化增强 BATCH-4）
 * 偏好以 localStorage 为本地事实源（tg_theme / tg_accent / tg_density / tg_dash_cards），
 * 任何变更经 schedulePreferenceSave 防抖后整包 PUT 到后端；登录前静默跳过。
 */

const PREF_KEY = 'tg_pref_local'
let timer = null

export function readLocalPreference() {
  try {
    return {
      theme: localStorage.getItem('tg_theme') || 'light',
      accent: localStorage.getItem('tg_accent') || 'gold',
      density: localStorage.getItem('tg_density') || 'cozy',
      dashCards: JSON.parse(localStorage.getItem('tg_dash_cards') || 'null') || undefined
    }
  } catch (e) {
    return { theme: 'light', accent: 'gold', density: 'cozy' }
  }
}

function isLoggedIn() {
  try {
    return !!localStorage.getItem('userInfo')
  } catch (e) {
    return false
  }
}

/** 防抖整包上传（fire-and-forget，失败静默；未登录不发起） */
export function schedulePreferenceSave() {
  if (timer) clearTimeout(timer)
  timer = setTimeout(async () => {
    if (!isLoggedIn()) return
    try {
      const { preferenceApi } = await import('@/api')
      await preferenceApi.save(readLocalPreference())
      try { localStorage.setItem(PREF_KEY, String(Date.now())) } catch (e) { /* ignore */ }
    } catch (e) {
      /* 同步失败不影响本地体验 */
    }
  }, 800)
}

/** 登录后拉取远端偏好并落地本地 + 广播（Layout 挂载时调用；返回是否命中远端数据） */
export async function applyRemotePreference(applyFns) {
  if (!isLoggedIn()) return false
  try {
    const { preferenceApi } = await import('@/api')
    const data = await preferenceApi.get()
    if (!data || !Object.keys(data).length) return false
    try {
      if (data.theme) localStorage.setItem('tg_theme', data.theme)
      if (data.accent) localStorage.setItem('tg_accent', data.accent)
      if (data.density) localStorage.setItem('tg_density', data.density)
      if (data.dashCards) localStorage.setItem('tg_dash_cards', JSON.stringify(data.dashCards))
    } catch (e) { /* ignore */ }
    // 应用到 DOM 与 Vue 状态
    applyFns?.()
    // 通知已挂载页面（如 Dashboard）重读卡片布局
    window.dispatchEvent(new CustomEvent('tg:pref-dash'))
    return true
  } catch (e) {
    return false
  }
}

export default { schedulePreferenceSave, applyRemotePreference, readLocalPreference }
