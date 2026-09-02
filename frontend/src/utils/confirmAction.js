/**
 * 统一危险操作确认（个性化增强 BATCH-5 #19）
 * 全站危险操作（删除/归档/清空/重置等）走同一确认弹窗语言：
 * 统一标题措辞、确认按钮红色语义、输入框 none、Esc 可取消。
 * 用法：await confirmDanger({ title, message, confirmText })
 * 取消/关闭抛出 'cancel'/'close'，与 ElMessageBox 一致，调用方 catch 忽略即可。
 */
import { ElMessageBox } from 'element-plus'

export function confirmDanger({ title = '危险操作确认', message, confirmText = '确认执行', type = 'warning' } = {}) {
  return ElMessageBox.confirm(message, title, {
    confirmButtonText: confirmText,
    cancelButtonText: '取消',
    type,
    confirmButtonClass: 'tg-btn-danger',
    customStyle: { borderRadius: '14px' },
    autofocus: false
  })
}

/** 归档/恢复等"可逆操作"确认（非红色语义，仅统一文案结构） */
export function confirmReversible({ title = '操作确认', message, confirmText = '确定' } = {}) {
  return ElMessageBox.confirm(message, title, {
    confirmButtonText: confirmText,
    cancelButtonText: '取消',
    type: 'warning',
    customStyle: { borderRadius: '14px' },
    autofocus: false
  })
}

export default confirmDanger
