/**
 * GAP-038：本地打印机接口 / 直接打印工具
 *
 * 复用既有报告预览端点（/api/export/report/pdf/{projectId}）拉取 PDF Blob，
 * 通过隐藏 iframe 触发浏览器原生打印对话框，实现「系统内直接打印」，
 * 后端零改动（与「预览PDF」共用同一端点）。
 */
import { exportApi } from '@/api'

/**
 * 拉取 PDF Blob 并触发打印对话框。
 * @param {string|number} projectId 项目ID
 * @param {string|number} [templateId] 报告模板ID（缺省由后端选默认模板）
 * @returns {Promise<void>}
 */
export async function printReportPdf(projectId, templateId) {
  const blob = await exportApi.previewBlob(projectId, 'pdf', templateId)
  const url = URL.createObjectURL(blob)
  return new Promise((resolve, reject) => {
    const iframe = document.createElement('iframe')
    iframe.style.position = 'fixed'
    iframe.style.right = '0'
    iframe.style.bottom = '0'
    iframe.style.width = '0'
    iframe.style.height = '0'
    iframe.style.border = '0'
    iframe.onload = () => {
      try {
        iframe.contentWindow.focus()
        iframe.contentWindow.print()
      } catch (e) {
        reject(e)
      } finally {
        // 延迟释放，确保打印任务已提交
        setTimeout(() => {
          document.body.removeChild(iframe)
          URL.revokeObjectURL(url)
          resolve()
        }, 1000)
      }
    }
    iframe.onerror = (e) => {
      document.body.removeChild(iframe)
      URL.revokeObjectURL(url)
      reject(e)
    }
    iframe.src = url
    document.body.appendChild(iframe)
  })
}
