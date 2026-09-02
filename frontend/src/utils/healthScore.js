/**
 * 项目健康分（W1-06 / O10）
 * 依据项目表面数据（覆盖率 / 缺陷数 / 状态）归一为 0-100 单一指标，
 * 便于项目列表一眼比较质量；无覆盖率且未开始分析时返回 null（显示“—”，避免误导）。
 * 变更时请同步更新此处与页面展示逻辑。
 */
const STATUS_SCORE = { analyzed: 100, created: 58, running: 52, failed: 34, archived: 66 }

function clamp(v, min = 0, max = 100) {
  return Math.min(max, Math.max(min, v))
}

/** 缺陷健康映射：0 缺陷满分，每 +1 缺陷 -8 分，下限 30 */
export function defectHealth(count) {
  if (count == null) return 100
  return Math.round(Math.max(30, 100 - count * 8))
}

/**
 * @param {{coverageRate?:number, defectCount?:number, status?:string}} p 项目对象
 * @returns {{ score:number|null, level:'优'|'良'|'中'|'待改进'|null, tone:'success'|'good'|'warn'|'danger'|null, label:string }} | null
 */
export function computeHealthScore(p) {
  if (!p) return null
  const analyzed = p.coverageRate != null
  const coverage = analyzed ? Math.round(clamp(p.coverageRate * 100)) : null
  const defect = defectHealth(p.defectCount)
  const statusScore = STATUS_SCORE[p.status] != null ? STATUS_SCORE[p.status] : 45

  // 未开始分析：无质量信号，不评分
  if (!analyzed && (p.status === 'created' || p.status == null)) {
    return { score: null, level: null, tone: null, label: '待分析' }
  }

  const score = analyzed
    ? Math.round(coverage * 0.55 + defect * 0.25 + statusScore * 0.2)
    : Math.round(defect * 0.55 + statusScore * 0.45)

  if (score >= 85) return { score, level: '优', tone: 'success', label: '优' }
  if (score >= 70) return { score, level: '良', tone: 'good', label: '良' }
  if (score >= 55) return { score, level: '中', tone: 'warn', label: '中' }
  return { score, level: '待改进', tone: 'danger', label: '待改进' }
}