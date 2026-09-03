import request from '@/utils/request'
import axios from 'axios'

export const authApi = {
  login(data) {
    return request({ url: '/auth/login', method: 'post', data })
  },
  register(data) {
    return request({ url: '/auth/register', method: 'post', data })
  },
  getUserInfo(id) {
    return request({ url: `/auth/info/${id}`, method: 'get' })
  },
  getCurrentUserInfo() {
    return request({ url: '/auth/info', method: 'get' })
  },
  changePassword(data) {
    return request({ url: '/auth/change-password', method: 'post', data })
  },
  logout() {
    // SEC-16：登出调用后端吊销接口，使当前 JWT 立即失效（前端随后清本地凭证）
    return request({ url: '/auth/logout', method: 'post' })
  }
}

// SEC-10：WebSocket 一次性连接票据（避免 JWT 经 WS URL query 传递）
export const wsApi = {
  getTicket() {
    return request({ url: '/ws/ticket', method: 'get' })
  }
}

// 个性化增强 BATCH-4：用户界面偏好（跨设备同步，本地优先）
export const preferenceApi = {
  get() {
    return request({ url: '/preference', method: 'get' })
  },
  save(pref) {
    return request({ url: '/preference', method: 'put', data: pref })
  }
}

export const userApi = {
  page(params) {
    return request({ url: '/user/page', method: 'get', params })
  },
  create(data) {
    return request({ url: '/user/create', method: 'post', data })
  },
  update(data) {
    return request({ url: '/user/update', method: 'put', data })
  },
  resetPassword(data) {
    return request({ url: '/user/reset-password', method: 'post', data })
  },
  delete(id) {
    return request({ url: `/user/${id}`, method: 'delete' })
  }
}

export const projectApi = {
  page(params) {
    return request({ url: '/project/page', method: 'get', params })
  },
  list(userId) {
    return request({ url: '/project/list', method: 'get', params: { userId } })
  },
  getById(id) {
    return request({ url: `/project/${id}`, method: 'get' })
  },
  create(data) {
    return request({ url: '/project/create', method: 'post', data })
  },
  update(data) {
    return request({ url: '/project/update', method: 'put', data })
  },
  delete(id) {
    return request({ url: `/project/${id}`, method: 'delete' })
  },
  archive(id) {
    return request({ url: `/project/archive/${id}`, method: 'post' })
  },
  batch(action, ids) {
    return request({ url: '/project/batch', method: 'post', data: { action, ids } })
  },
  restore(id) {
    return request({ url: `/project/restore/${id}`, method: 'post' })
  },
  /** GAP-012：回收站分页查询 */
  listDeleted(params) {
    return request({ url: '/project/recycle/page', method: 'get', params })
  },
  /** GAP-012：恢复回收站项目 */
  restoreDeleted(id) {
    return request({ url: `/project/recycle/restore/${id}`, method: 'post' })
  },
  /** GAP-012：彻底删除回收站项目（物理删除） */
  purgeDeleted(id) {
    return request({ url: `/project/recycle/purge/${id}`, method: 'post' })
  }
}

export const analysisApi = {
  uploadRequirement(projectId, file) {
    const formData = new FormData()
    formData.append('file', file)
    return request({
      url: `/analysis/upload/requirement/${projectId}`,
      method: 'post',
      data: formData
    })
  },
  /** 批量导入需求文档（FR-REQ-001 多文档批量导入） */
  uploadRequirements(projectId, files) {
    const formData = new FormData()
    files.forEach(f => formData.append('files', f))
    return request({
      url: `/analysis/upload/requirements/${projectId}`,
      method: 'post',
      data: formData
    })
  },
  uploadCode(projectId, file) {
    const formData = new FormData()
    formData.append('file', file)
    return request({
      url: `/analysis/upload/code/${projectId}`,
      method: 'post',
      data: formData
    })
  },
  /** FR-CODE-001 2.4：单 Java 文件批量导入（零散 .java 源文件场景） */
  uploadCodeFiles(projectId, files) {
    const formData = new FormData()
    files.forEach(f => formData.append('files', f))
    return request({
      url: `/analysis/upload/code-files/${projectId}`,
      method: 'post',
      data: formData
    })
  },
  createTask(data) {
    return request({ url: '/analysis/task/create', method: 'post', data })
  },
  runTask(taskId) {
    return request({ url: `/analysis/task/run/${taskId}`, method: 'post' })
  },
  getTask(taskId) {
    return request({ url: `/analysis/task/${taskId}`, method: 'get' })
  },
  listTasks(projectId, params) {
    return request({ url: `/analysis/task/list/${projectId}`, method: 'get', params })
  },
  pauseTask(taskId) {
    return request({ url: `/analysis/task/pause/${taskId}`, method: 'post' })
  },
  resumeTask(taskId) {
    return request({ url: `/analysis/task/resume/${taskId}`, method: 'post' })
  },
  terminateTask(taskId) {
    return request({ url: `/analysis/task/terminate/${taskId}`, method: 'post' })
  },
  rerunTask(taskId) {
    return request({ url: `/analysis/task/rerun/${taskId}`, method: 'post' })
  },
  /** 分片上传：初始化（resumeUploadId 用于断点续传时复用旧会话） */
  chunkInit(filename, totalChunks, resumeUploadId) {
    return request({
      url: '/analysis/upload/chunk/init',
      method: 'post',
      params: { filename, totalChunks, uploadId: resumeUploadId || '' }
    })
  },
  /** 分片上传：上传单个分片 */
  chunkUpload(uploadId, chunkIndex, file) {
    const formData = new FormData()
    formData.append('file', file)
    return request({
      url: '/analysis/upload/chunk',
      method: 'post',
      params: { uploadId, chunkIndex },
      data: formData,
      timeout: 120000
    })
  },
  /** 分片上传：查询已上传分片（断点续传） */
  chunkStatus(uploadId) {
    return request({ url: '/analysis/upload/chunk/status', method: 'get', params: { uploadId } })
  },
  /** 分片上传：合并并落库（type=requirement|code） */
  chunkComplete(uploadId, projectId, type) {
    return request({
      url: '/analysis/upload/chunk/complete',
      method: 'post',
      params: { uploadId, projectId, type }
    })
  }
}

export const resultApi = {
  getRequirements(projectId) {
    return request({ url: `/result/requirements/${projectId}`, method: 'get' })
  },
  /** 2.2：导出需求质量报告（结构化歧义检测 JSON，含类型/严重度/原文片段/建议） */
  exportRequirementQuality(projectId) {
    return downloadFile(`/result/requirements/${projectId}/quality/export`, '需求质量报告.json')
  },
  /** 分页查询项目需求（结果页服务端分页） */
  getRequirementsPage(projectId, pageNum, pageSize) {
    return request({ url: `/result/requirements/page/${projectId}`, method: 'get', params: { pageNum, pageSize } })
  },
  /** FUN-11：分页查询一致性校验结果 */
  getConsistencyResultsPage(projectId, pageNum, pageSize) {
    return request({ url: `/result/consistency/page/${projectId}`, method: 'get', params: { pageNum, pageSize } })
  },
  /** FUN-11：分页查询代码质量缺陷 */
  getCodeDefectsPage(projectId, pageNum, pageSize) {
    return request({ url: `/result/code-defects/page/${projectId}`, method: 'get', params: { pageNum, pageSize } })
  },
  /** FUN-11：导出任务级语义向量 */
  exportTaskVectors(taskId, format = 'json') {
    const ext = format === 'csv' ? 'csv' : 'json'
    return downloadFile(`/result/${taskId}/vectors/export?format=${format}`, `task_vectors_${Date.now()}.${ext}`)
  },
  getRequirement(id) {
    return request({ url: `/result/requirement/${id}`, method: 'get' })
  },
  getSpec(requirementId) {
    return request({ url: `/result/spec/${requirementId}`, method: 'get' })
  },
  updateSpec(specId, alloyCode) {
    return request({ url: `/result/spec/update/${specId}`, method: 'post', data: { alloyCode } })
  },
  getCodeUnits(projectId) {
    return request({ url: `/result/codeunits/${projectId}`, method: 'get' })
  },
  /** 分页查询项目代码单元（结果页服务端分页） */
  getCodeUnitsPage(projectId, pageNum, pageSize) {
    return request({ url: `/result/codeunits/page/${projectId}`, method: 'get', params: { pageNum, pageSize } })
  },
  getConsistency(projectId, taskId) {
    return request({ url: `/result/consistency/${projectId}`, method: 'get', params: { taskId } })
  },
  /** GAP-020：支持按主类型 type（4类口径）与子类型 subType 过滤；GAP-011：增加 status 过滤 */
  getDefects(projectId, taskId, level, type, subType, status) {
    return request({ url: `/result/defects/${projectId}`, method: 'get', params: { taskId, level, type, subType, status } })
  },
  /** 分页查询项目缺陷（结果页服务端分页，GAP-020 支持主/子类型过滤，GAP-011 支持状态过滤） */
  getDefectsPage(projectId, pageNum, pageSize, type, subType, status) {
    return request({ url: `/result/defects/page/${projectId}`, method: 'get', params: { pageNum, pageSize, type, subType, status } })
  },
  getCodeDefects(projectId, taskId) {
    return request({ url: `/result/code-defects/${projectId}`, method: 'get', params: { taskId } })
  },
  getStatistics(projectId) {
    return request({ url: `/result/statistics/${projectId}`, method: 'get' })
  },
  /** 2.7：项目质量趋势时间序列（折线趋势图数据源） */
  getQualityTrend(projectId) {
    return request({ url: `/result/quality-trend/${projectId}`, method: 'get' })
  },
  getTraceability(projectId) {
    return request({ url: `/result/traceability/${projectId}`, method: 'get' })
  },
  /** GAP-026：分页查询正向追溯矩阵（远程分页） */
  getTraceabilityPage(projectId, pageNum, pageSize) {
    return request({ url: `/result/traceability/page/${projectId}`, method: 'get', params: { pageNum, pageSize } })
  },
  getReverseTraceability(projectId) {
    return request({ url: `/result/traceability/reverse/${projectId}`, method: 'get' })
  },
  /** GAP-026：分页查询反向追溯矩阵（远程分页） */
  getReverseTraceabilityPage(projectId, pageNum, pageSize) {
    return request({ url: `/result/traceability/reverse/page/${projectId}`, method: 'get', params: { pageNum, pageSize } })
  },
  compareProjects(projectIds) {
    return request({ url: '/result/compare', method: 'get', params: { projectIds: projectIds.join(',') } })
  },
  /** GAP-011：更新缺陷状态 */
  updateDefectStatus(defectId, status) {
    return request({ url: `/result/defect/${defectId}/status`, method: 'put', data: { status } })
  }
}

export const auditApi = {
  /** 审计日志分页查询（仅管理员） */
  page(params) {
    return request({ url: '/audit/page', method: 'get', params })
  },
  /** 导出审计日志Excel（仅管理员），支持按关键字过滤 */
  exportExcel(keyword) {
    return downloadFile(`/audit/export${keyword ? '?keyword=' + encodeURIComponent(keyword) : ''}`, '审计日志.xlsx')
  },
  /** 校验审计日志哈希链完整性（AUD-08 防篡改） */
  verify() {
    return request({ url: '/audit/verify', method: 'get' })
  },
  mine(limit = 20) {
    return request({ url: '/audit/mine', method: 'get', params: { limit } })
  }
}

export const dataChangeLogApi = {
  /** 2.8 整改：按项目查询字段级变更日志 */
  pageByProject(projectId, params) {
    return request({ url: `/data-change-log/project/${projectId}`, method: 'get', params })
  },
  /** 2.8 整改：按实体查询字段级变更日志（如 user / project） */
  pageByEntity(params) {
    return request({ url: '/data-change-log/entity', method: 'get', params })
  }
}

export const systemConfigApi = {
  /** AUD-07：查询全部系统配置 */
  list() {
    return request({ url: '/system/config/list', method: 'get' })
  },
  /** AUD-07：按 key 查询配置原始 JSON */
  get(key) {
    return request({ url: `/system/config/${key}`, method: 'get' })
  },
  /** AUD-07：保存/更新配置（管理员） */
  save(key, value, description) {
    return request({ url: `/system/config/${key}`, method: 'put', params: { value, description } })
  },
  /** P1-4：规则信号权重（全量信号 -> 权重，阈值实验室调参台） */
  getRiskWeights() {
    return request({ url: '/system/config/risk-weights', method: 'get' })
  }
}

/** GAP-028：密钥轮换（仅管理员） */
export const securityApi = {
  rotateKeys(oldKey, newKey) {
    return request({ url: '/security/rotate-keys', method: 'post', data: { oldKey, newKey } })
  }
}

/**
 * 文件导出下载（blob方式，SEC-10①：鉴权经 HttpOnly Cookie，fetch 携带 credentials）
 * @param {string} url 导出接口地址
 * @param {string} defaultName 默认文件名（后端响应头优先）
 */
export async function downloadFile(url, defaultName) {
  const resp = await fetch('/api' + url, {
    credentials: 'include'
  })
  if (!resp.ok) {
    let message = '导出失败'
    try {
      const err = await resp.json()
      message = err.message || message
    } catch (e) { /* ignore */ }
    throw new Error(message)
  }
  // 优先从响应头解析文件名
  let filename = defaultName
  const disposition = resp.headers.get('Content-Disposition')
  if (disposition) {
    const match = disposition.match(/filename\*=UTF-8''([^;]+)/i)
    if (match) {
      filename = decodeURIComponent(match[1])
    }
  }
  const blob = await resp.blob()
  const blobUrl = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = blobUrl
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  window.URL.revokeObjectURL(blobUrl)
}

export const exportApi = {
  /** 导出缺陷清单Excel */
  defectsExcel(projectId) {
    return downloadFile(`/export/defects/excel/${projectId}`, '缺陷清单.xlsx')
  },
  /** 导出追溯矩阵Excel（正向：需求到代码） */
  traceabilityExcel(projectId) {
    return downloadFile(`/export/traceability/excel/${projectId}`, '追溯矩阵.xlsx')
  },
  /** 导出反向追溯矩阵Excel（代码到需求） */
  traceabilityReverseExcel(projectId) {
    return downloadFile(`/export/traceability/reverse/excel/${projectId}`, '反向追溯矩阵.xlsx')
  },
  /** 导出代码质量缺陷Excel */
  codeDefectsExcel(projectId) {
    return downloadFile(`/export/code-defects/excel/${projectId}`, '代码质量缺陷.xlsx')
  },
  /** FR-CODE-003 2.5：导出语义向量（需求+代码单元，按项目） */
  semanticVectors(projectId, format = 'json') {
    const ext = format === 'csv' ? 'csv' : 'json'
    return downloadFile(`/result/vectors/export/${projectId}?format=${format}`, `语义向量_${Date.now()}.${ext}`)
  },
  /** 导出统计报表Excel（FR-PLAT-003） */
  statisticsExcel(projectId) {
    return downloadFile(`/export/statistics/excel/${projectId}`, '统计报表.xlsx')
  },
  /** 导出多项目对比统计Excel */
  compareExcel(projectIds) {
    return downloadFile(`/export/compare/excel?projectIds=${projectIds.join(',')}`, '多项目对比统计.xlsx')
  },
  /** 导出综合报告Word（templateId 优先，template 兼容旧接口） */
  reportWord(projectId, templateId, template) {
    const params = templateId ? `templateId=${templateId}` : `template=${template || 'FULL'}`
    return downloadFile(`/export/report/word/${projectId}?${params}`, '一致性校验与缺陷检测报告.docx')
  },
  /** 导出综合报告PDF（templateId 优先，template 兼容旧接口） */
  reportPdf(projectId, templateId, template) {
    const params = templateId ? `templateId=${templateId}` : `template=${template || 'FULL'}`
    return downloadFile(`/export/report/pdf/${projectId}?${params}`, '一致性校验与缺陷检测报告.pdf')
  },
  /**
   * AUD-06：报告在线预览——以 Blob 形式拉取报告字节，供前端嵌入预览（绕过 JSON 解析拦截器）。
   * @param type 'pdf' | 'word'
   * @returns Promise<Blob>
   */
  previewBlob(projectId, type, templateId, template) {
    const params = templateId ? `templateId=${templateId}` : `template=${template || 'FULL'}`
    const url = `/api/export/report/${type}/${projectId}?${params}`
    return axios.get(url, {
      responseType: 'blob',
      withCredentials: true // SEC-10①：鉴权经 HttpOnly Cookie
    }).then(res => res.data)
  }
}

/** GAP-010：报告模板管理接口 */
export const templateApi = {
  /** 查询所有模板 */
  list() {
    return request({ url: '/export/report-templates', method: 'get' })
  },
  /** 创建模板 */
  create(data) {
    return request({ url: '/export/report-templates', method: 'post', data })
  },
  /** 编辑模板 */
  update(id, data) {
    return request({ url: `/export/report-templates/${id}`, method: 'put', data })
  },
  /** 删除模板 */
  delete(id) {
    return request({ url: `/export/report-templates/${id}`, method: 'delete' })
  },
  /** 设为默认模板 */
  setDefault(id) {
    return request({ url: `/export/report-templates/${id}/default`, method: 'put' })
  }
}

export const backupApi = {
  /** 创建备份 */
  create() {
    return request({ url: '/backup/create', method: 'post' })
  },
  /** 备份列表 */
  list() {
    return request({ url: '/backup/list', method: 'get' })
  },
  /** 恢复备份 */
  restore(filename) {
    return request({ url: '/backup/restore', method: 'post', params: { filename } })
  },
  /** 删除备份 */
  delete(filename) {
    return request({ url: `/backup/${filename}`, method: 'delete' })
  },
  /** 下载备份 */
  download(filename) {
    return downloadFile(`/backup/download/${filename}`, filename)
  },
  /** 查询自动备份频率配置 */
  getConfig() {
    return request({ url: '/backup/config', method: 'get' })
  },
  /** 修改自动备份频率（daily/weekly/monthly） */
  updateConfig(frequency) {
    return request({ url: '/backup/config', method: 'put', data: { frequency } })
  },
  /** GAP-017：设置备份口令（派生密钥加密备份，≥8位） */
  setPassphrase(passphrase) {
    return request({ url: '/backup/passphrase', method: 'post', data: { passphrase } })
  },
  /** GAP-017：校验备份口令是否正确 */
  verifyPassphrase(passphrase) {
    return request({ url: '/backup/passphrase/verify', method: 'post', data: { passphrase } })
  }
}

/** GAP-009：第三方工具集成接口 */
export const integrationApi = {
  /** 连通性测试 */
  testConnection(tool) {
    return request({ url: '/integration/test', method: 'post', params: { tool } })
  },
  /** 需求导出为issue */
  exportRequirements(projectId, requirementIds, tool) {
    return request({
      url: '/integration/requirements/export',
      method: 'post',
      params: { projectId, tool },
      data: requirementIds
    })
  },
  /** 需求变更联动：把已导出需求的最新内容同步到远程 issue（幂等 update） */
  syncRequirement(projectId, requirementId, tool) {
    return request({
      url: `/integration/requirements/sync/${requirementId}`,
      method: 'put',
      params: { projectId, tool }
    })
  },
  /** 缺陷推送到第三方平台 */
  pushDefects(projectId, defectIds, tool) {
    return request({
      url: '/integration/defects/push',
      method: 'post',
      params: { projectId, tool },
      data: defectIds
    })
  },
  /** 集成状态总览（各工具是否启用） */
  getStatus() {
    return request({ url: '/integration/status', method: 'get' })
  },
  /** 发送 Webhook 通知（企业微信/钉钉机器人） */
  notify(tool, message) {
    return request({ url: '/integration/notify', method: 'post', params: { tool }, data: message })
  },
  /** 入站同步（远程->本地）：手动触发轮询已推送缺陷状态变化并回写 */
  triggerInboundSync() {
    return request({ url: '/integration/sync/inbound', method: 'post' })
  },
  /** 查询入站同步最近一次执行结果 */
  inboundSyncStatus() {
    return request({ url: '/integration/sync/inbound/status', method: 'get' })
  }
}

/** GAP-033：大模型（LLM）配置与状态接口 */
export const llmApi = {
  /** 运行时状态（enabled/providers/routing/models，api_key 脱敏） */
  getStatus() {
    return request({ url: '/llm/status', method: 'get' })
  },
  /** 获取大模型配置（仅管理员，api_key 脱敏） */
  getConfig() {
    return request({ url: '/llm/config', method: 'get' })
  },
  /** 保存大模型配置（仅管理员）：{enabled, providers:[{provider,baseUrl,apiKey}], routing, models} */
  saveConfig(data) {
    return request({ url: '/llm/config', method: 'put', data })
  },
  /** 连通测试（provider 可选，指定时仅测该 provider） */
  testConnection(provider) {
    return request({ url: '/llm/test', method: 'post', params: { provider } })
  },
  /** 缺陷解释（LLM 分析缺陷原因与修复建议） */
  explainDefect(data) {
    return request({ url: '/llm/explain-defect', method: 'post', data })
  },
  /** W2-08：AI 助手问答（仅管理员） */
  chat(message) {
    return request({ url: '/llm/chat', method: 'post', data: { message } })
  },
  /** W5：LLM 用量统计（大模型配置页用量区块，仅管理员） */
  usage(days = 30) {
    return request({ url: '/llm/usage', method: 'get', params: { days } })
  }
}

/** W5 波：质量洞察接口（缺陷趋势/工单看板/组合简报/模式库） */
export const insightApi = {
  /** 跨项目缺陷趋势：days=7..90，projectId 可选 */
  trend(days = 30, projectId = null) {
    return request({ url: '/insight/trend', method: 'get', params: { days, projectId: projectId || undefined } })
  },
  /** 跨项目缺陷工单看板：按状态汇总 + 分页明细 */
  board(params = {}) {
    return request({ url: '/insight/board', method: 'get', params })
  },
  /** 缺陷工单状态流转（含误报 falsePositive） */
  updateTicketStatus(id, status) {
    return request({ url: `/insight/defect/${id}/status`, method: 'put', data: { status } })
  },
  /** 项目组合质量简报 */
  portfolio() {
    return request({ url: '/insight/portfolio', method: 'get' })
  },
  /** 缺陷模式库（代码检测规则图鉴 + 命中统计） */
  patterns() {
    return request({ url: '/insight/patterns', method: 'get' })
  },
  // ===== W6 二期 =====
  /** 阈值重放：新权重/阈值下重算判定桶与翻转明细 */
  replay(params) {
    return request({ url: '/insight/threshold/replay', method: 'get', params })
  },
  /** T1 阈值扫描曲线 */
  sweep(projectId) {
    return request({ url: '/insight/threshold/sweep', method: 'get', params: { projectId } })
  },
  /** Alloy 规约清单 */
  alloySpecs(projectId, limit = 50) {
    return request({ url: '/insight/alloy/specs', method: 'get', params: { projectId, limit } })
  },
  /** Alloy 在线试算校验 */
  verifyAlloy(specId) {
    return request({ url: `/insight/alloy/verify/${specId}`, method: 'post' })
  },
  /** 标注资产盘点 */
  evalAssets() {
    return request({ url: '/insight/eval/assets', method: 'get' })
  },
  /** 样例一键导入 */
  importSample(key, projectName) {
    return request({ url: '/insight/samples/import', method: 'post', data: { key, projectName } })
  },
  /** 保存 Alloy 规约（后端自动校验，失败抛业务异常） */
  saveAlloySpec(specId, alloyCode) {
    return request({ url: `/result/spec/update/${specId}`, method: 'post', data: { alloyCode } })
  }
}

/** W2 波：工作台聚合接口（KPI 趋势/动态流/使用趋势/通知/搜索/系统状态/质量门槛） */
export const dashboardApi = {
  /** W2-01：KPI 概览与周同比 */
  overview() {
    return request({ url: '/dashboard/overview', method: 'get' })
  },
  /** W2-03：最近动态流 */
  activities(limit = 14) {
    return request({ url: '/dashboard/activities', method: 'get', params: { limit } })
  },
  /** W2-06：使用趋势（按天） */
  trend(days = 7) {
    return request({ url: '/dashboard/trend', method: 'get', params: { days } })
  },
  /** W2-04：通知（待办 + 最近任务） */
  notifications() {
    return request({ url: '/dashboard/notifications', method: 'get' })
  },
  /** W2-05：全局搜索 */
  search(q) {
    return request({ url: '/dashboard/search', method: 'get', params: { q } })
  },
  /** W2-07：系统运行状态 */
  systemStatus() {
    return request({ url: '/dashboard/system-status', method: 'get' })
  },
  /** W2-10：质量门槛配置 */
  gate() {
    return request({ url: '/dashboard/gate', method: 'get' })
  },
  /** W3-02：菜单配置（sys_config.menu_config） */
  menuConfig() {
    return request({ url: '/dashboard/menu-config', method: 'get' })
  }
}
