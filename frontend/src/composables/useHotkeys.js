/**
 * 全局快捷键体系（个性化增强 BATCH-3 #15）
 * - G 序列导航：按 G 后 1.2s 内按字母跳转（d 工作台 / p 项目管理 / r 报告中心 / i 质量洞察 / a 审计日志）
 * - ? 唤起速查表；/ 唤起命令面板（⌘K）
 * - 输入焦点（input/textarea/contenteditable）与修饰键组合时自动忽略
 */
import { ref, onMounted, onBeforeUnmount } from 'vue'

export const HOTKEY_NAV = [
  { keys: ['G', 'D'], label: '工作台', path: '/dashboard' },
  { keys: ['G', 'P'], label: '项目管理', path: '/projects' },
  { keys: ['G', 'R'], label: '报告中心', path: '/reports' },
  { keys: ['G', 'I'], label: '质量洞察', path: '/insight/trends' },
  { keys: ['G', 'A'], label: '审计日志', path: '/audit' }
]

export function useHotkeys({ onNavigate, onCommandPalette, onCheatSheet }) {
  const hotkeySheet = ref(false)
  let gPending = false
  let gTimer = null

  const isTypingTarget = (e) => {
    const t = e.target
    return !!t && (
      t.tagName === 'INPUT' ||
      t.tagName === 'TEXTAREA' ||
      t.tagName === 'SELECT' ||
      t.isContentEditable ||
      !!t.closest?.('.el-input, .el-textarea, .el-select, [contenteditable="true"]')
    )
  }

  const handleKeydown = (e) => {
    if (e.ctrlKey || e.metaKey || e.altKey) return
    if (isTypingTarget(e)) return
    const k = e.key

    if (gPending) {
      gPending = false
      clearTimeout(gTimer)
      const target = HOTKEY_NAV.find((n) => n.keys[1].toLowerCase() === k.toLowerCase())
      if (target) {
        e.preventDefault()
        onNavigate?.(target.path)
      }
      return
    }

    if (k === 'g' || k === 'G') {
      gPending = true
      clearTimeout(gTimer)
      gTimer = setTimeout(() => { gPending = false }, 1200)
      return
    }
    if (k === '?') {
      e.preventDefault()
      hotkeySheet.value = true
      onCheatSheet?.()
      return
    }
    if (k === '/') {
      e.preventDefault()
      onCommandPalette?.()
    }
  }

  onMounted(() => window.addEventListener('keydown', handleKeydown))
  onBeforeUnmount(() => {
    window.removeEventListener('keydown', handleKeydown)
    clearTimeout(gTimer)
  })

  return { hotkeySheet }
}

export default useHotkeys
