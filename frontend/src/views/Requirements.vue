<template>
  <div class="requirements-page">
    <el-page-header @back="$router.back()" content="需求分析" style="margin-bottom: 20px" />

    <el-card>
      <el-alert type="info" :closable="false" style="margin-bottom: 20px">
        共提取到 {{ requirements.length }} 条需求语义单元，已完成自动化分析与形式化建模。
      </el-alert>

      <div class="filter-bar">
        <!-- GAP-019：标题/优先级筛选 -->
        <el-input v-model="filterKeyword" placeholder="搜索标题/原文" clearable style="width: 200px" size="small">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-select v-model="filterPriority" placeholder="优先级" clearable style="width: 110px" size="small">
          <el-option label="必须" value="must" />
          <el-option label="重要" value="important" />
          <el-option label="可选" value="optional" />
          <el-option label="普通" value="normal" />
        </el-select>
        <!-- GAP-009：第三方平台选择与导出 -->
        <el-select v-model="integrationTool" style="width: 120px" size="small">
          <el-option v-for="tool in availableTools" :key="tool" :label="tool" :value="tool" />
        </el-select>
        <el-button type="primary" :loading="exportingReq" :disabled="selectedRequirements.length === 0" @click="exportToIntegration">
          <el-icon><Upload /></el-icon> 导出为Issue到{{ integrationTool }}({{ selectedRequirements.length }})
        </el-button>
        <!-- AUD-11：集成连通测试快捷按钮 -->
        <el-button type="info" plain size="small" :loading="testingConn" @click="testIntegrationConnection">
          <el-icon><Connection /></el-icon> 测试连通
        </el-button>
        <!-- 2.2：导出需求质量报告（结构化歧义检测，审计用） -->
        <el-button type="warning" plain size="small" :loading="exportingQuality" @click="exportQualityReport">
          <el-icon><Download /></el-icon> 导出质量报告
        </el-button>
      </div>

      <el-table :data="pagedRequirements" stripe border @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" fixed />
        <el-table-column prop="requirementId" label="需求ID" width="110" fixed />
        <!-- GAP-019：标题/优先级/来源 -->
        <el-table-column prop="title" label="标题" min-width="170" show-overflow-tooltip />
        <el-table-column label="远程Issue" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.remoteIssueKey" type="success" size="small">{{ row.remoteIssueKey }}</el-tag>
            <el-tag v-else type="info" size="small">未导出</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="priority" label="优先级" width="85">
          <template #default="{ row }">
            <el-tag size="small" :type="priorityType(row.priority)">{{ priorityLabel(row.priority) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="sourceFile" label="来源" width="150" show-overflow-tooltip />
        <el-table-column prop="requirementType" label="类型" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="row.requirementType === 'functional' ? 'primary' : 'warning'">
              {{ {functional:'功能', non_functional:'非功能', constraint:'约束'}[row.requirementType] || row.requirementType }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="originalText" label="需求原文" min-width="280" show-overflow-tooltip />
        <!-- 2.2：质量检测列（结构化歧义/矛盾/边界问题计数） -->
        <el-table-column label="质量检测" width="170">
          <template #default="{ row }">
            <el-tag v-if="qualityIssueCount(row) > 0" type="warning" size="small">
              存在 {{ qualityIssueCount(row) }} 个问题
            </el-tag>
            <el-tag v-else type="success" size="small">正常</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">
            <el-tag type="success" size="small">已建模</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="210" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="viewDetail(row)">详情</el-button>
            <el-button type="success" link size="small" @click="viewSpec(row)">查看规约</el-button>
            <el-button v-if="row.remoteIssueKey" type="warning" link size="small" :loading="row.syncing" @click="syncReqToRemote(row)">同步到远程</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- FUN-12：需求列表分页（万行级仅渲染当前页） -->
      <div style="display: flex; justify-content: flex-end; margin-top: 12px">
        <el-pagination
          v-model:current-page="reqPageNo"
          v-model:page-size="reqPageSize"
          :total="filteredRequirements.length"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
        />
      </div>
    </el-card>

    <el-dialog v-model="showDetail" title="需求详情" width="700px">
      <el-descriptions v-if="currentReq" :column="1" border>
        <el-descriptions-item label="需求ID">{{ currentReq.requirementId }}</el-descriptions-item>
        <!-- GAP-019：标题/优先级/来源详情 -->
        <el-descriptions-item label="标题">{{ currentReq.title || '—' }}</el-descriptions-item>
        <el-descriptions-item label="优先级">{{ priorityLabel(currentReq.priority) }}</el-descriptions-item>
        <el-descriptions-item label="来源文档">{{ currentReq.sourceFile || '—' }}</el-descriptions-item>
        <el-descriptions-item label="需求类型">
          {{ {functional:'功能需求', non_functional:'非功能需求', constraint:'约束条件'}[currentReq.requirementType] }}
        </el-descriptions-item>
        <el-descriptions-item label="需求原文">
          <div style="white-space: pre-wrap; max-height: 200px; overflow-y: auto">{{ currentReq.originalText }}</div>
        </el-descriptions-item>
        <!-- 2.2：结构化质量检测报告 -->
        <el-descriptions-item label="质量检测">
          <div v-if="qualityIssues(currentReq).length > 0">
            <el-table :data="qualityIssues(currentReq)" size="small" border>
              <el-table-column label="类型" width="100">
                <template #default="{ row }">
                  <el-tag size="small" :type="row.type === 'contradiction' ? 'danger' : 'warning'">{{ row.typeLabel }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="severityLabel" label="严重度" width="80" />
              <el-table-column prop="keyword" label="关键词/原因" width="150" show-overflow-tooltip />
              <el-table-column prop="excerpt" label="原文片段" min-width="150" show-overflow-tooltip />
              <el-table-column prop="suggestion" label="修复建议" min-width="200" show-overflow-tooltip />
            </el-table>
          </div>
          <el-text v-else type="success">未检测到明显歧义</el-text>
        </el-descriptions-item>
        <el-descriptions-item label="状态集合">{{ currentReq.stateSet }}</el-descriptions-item>
        <el-descriptions-item label="初始状态">{{ currentReq.initialState }}</el-descriptions-item>
        <el-descriptions-item label="状态转移">{{ currentReq.stateTransitions }}</el-descriptions-item>
        <el-descriptions-item label="约束规则">
          <div style="white-space: pre-wrap">{{ currentReq.constraintRules }}</div>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <el-dialog v-model="showSpec" title="Alloy形式化规约" width="880px" top="5vh">
      <div v-if="currentSpec" class="spec-content">
        <el-descriptions :column="2" border style="margin-bottom: 15px">
          <el-descriptions-item label="规约ID">{{ currentSpec.specId }}</el-descriptions-item>
          <el-descriptions-item label="校验状态">
            <el-tag :type="currentSpec.verificationStatus === 'passed' ? 'success' : currentSpec.verificationStatus === 'warning' ? 'warning' : 'danger'" size="small">{{ { passed: '校验通过', warning: '告警通过', failed: '校验失败' }[currentSpec.verificationStatus] || currentSpec.verificationStatus }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="校验结果" :span="2">{{ currentSpec.verificationResult }}</el-descriptions-item>
        </el-descriptions>
        <!-- 6.2 整改：Alloy 反例（counterexample）与 Kripke 状态迁移图可视化 -->
        <el-divider content-position="left">反例 / Kripke 状态迁移图</el-divider>
        <el-alert
          v-if="counterexampleText"
          type="error"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
        >
          <template #title>Alloy 求解反例（counterexample）</template>
          <pre class="suggestion-block counterexample-block">{{ counterexampleText }}</pre>
        </el-alert>
        <el-alert
          v-else
          type="success"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
          title="未发现反例"
          description="该规约求解未产生反例（可满足 / 未执行 Alloy 求解）。"
        />
        <div v-if="kripkeGraphData" id="kripkeChart" class="kripke-chart"></div>
        <el-alert
          v-else
          type="info"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
          title="无 Kripke 状态数据"
          description="该需求未提取到可渲染的状态迁移结构（stateSet 为空）。"
        />
        <!-- 2.3：优化建议展示（后端针对校验 errors/warnings 生成的具体修复指令） -->
        <el-alert
          v-if="currentSpec.optimizationSuggestion && !editing"
          type="warning"
          :closable="false"
          show-icon
          style="margin-bottom: 12px"
        >
          <template #title>优化建议</template>
          <pre class="suggestion-block">{{ currentSpec.optimizationSuggestion }}</pre>
        </el-alert>
        <pre v-if="!editing" class="code-block" v-html="highlightAlloy(currentSpec.alloyCode)"></pre>
        <el-input
          v-else
          v-model="editCode"
          type="textarea"
          :rows="18"
          class="code-editor"
          spellcheck="false"
        />
        <div v-if="editing" class="edit-actions" style="margin-top: 12px; text-align: right">
          <el-button size="small" @click="formatSpec">格式化</el-button>
          <el-button size="small" @click="cancelEdit">取消</el-button>
          <el-button size="small" type="primary" :loading="savingSpec" @click="saveSpec">校验并保存</el-button>
        </div>
        <div v-else class="edit-actions" style="margin-top: 12px; text-align: right">
          <el-button size="small" type="primary" plain @click="startEdit">编辑规约</el-button>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { resultApi, integrationApi } from '@/api'

const route = useRoute()
const projectId = route.params.id
const requirements = ref([])
const showDetail = ref(false)
const showSpec = ref(false)
const currentReq = ref(null)
const currentSpec = ref(null)
const editing = ref(false)
const editCode = ref('')
const savingSpec = ref(false)

/** GAP-019：标题/优先级筛选 */
const filterKeyword = ref('')
const filterPriority = ref('')

/** GAP-019：优先级展示映射 */
const priorityLabel = (p) => ({ must: '必须', important: '重要', optional: '可选', normal: '普通' }[p] || '普通')
const priorityType = (p) => ({ must: 'danger', important: 'warning', optional: 'info', normal: 'success' }[p] || 'success')

/** GAP-019：客户端筛选（标题/原文关键字 + 优先级下拉） */
const filteredRequirements = computed(() => {
  let list = requirements.value
  if (filterPriority.value) {
    list = list.filter(r => r.priority === filterPriority.value)
  }
  const kw = filterKeyword.value.trim().toLowerCase()
  if (kw) {
    list = list.filter(r =>
      (r.title || '').toLowerCase().includes(kw) ||
      (r.originalText || '').toLowerCase().includes(kw)
    )
  }
  return list
})

/** FUN-12：前端分页切片——万行级需求列表仅渲染当前页，避免 DOM 卡顿 */
const reqPageNo = ref(1)
const reqPageSize = ref(20)
const pagedRequirements = computed(() => {
  const list = filteredRequirements.value
  const start = (reqPageNo.value - 1) * reqPageSize.value
  return list.slice(start, start + reqPageSize.value)
})
watch(filteredRequirements, () => { reqPageNo.value = 1 })
// 过滤控件变化时回到第一页
watch([filterKeyword, filterPriority], () => { reqPageNo.value = 1 })

/** GAP-009：第三方工具导出 */
const integrationTool = ref('JIRA')
const availableTools = ['JIRA', 'ZENTAO']
const exportingReq = ref(false)
const testingConn = ref(false)
const selectedRequirements = ref([])

/** 2.2：需求质量报告导出状态 */
const exportingQuality = ref(false)

/** 2.2：解析结构化质量检测报告 JSON（兼容旧版单条文本） */
const parseQualityReport = (row) => {
  if (!row || !row.ambiguityReport) return null
  try {
    const obj = JSON.parse(row.ambiguityReport)
    return obj && typeof obj === 'object' && Array.isArray(obj.issues) ? obj : null
  } catch (e) {
    return null
  }
}
const issueTypeLabel = (t) => ({ ambiguity: '模糊表述', contradiction: '矛盾冲突', missing_boundary: '边界缺失' }[t] || t)
const issueSeverityLabel = (s) => ({ high: '高', medium: '中', low: '低' }[s] || s)
const qualityIssueCount = (row) => parseQualityReport(row)?.issueCount || 0
const qualityIssues = (row) => {
  const r = parseQualityReport(row)
  if (!r) return []
  return (r.issues || []).map(i => ({ ...i, typeLabel: issueTypeLabel(i.type), severityLabel: issueSeverityLabel(i.severity) }))
}

/** 2.2：导出需求质量报告（结构化歧义检测 JSON，审计用） */
const exportQualityReport = async () => {
  exportingQuality.value = true
  try {
    await resultApi.exportRequirementQuality(projectId)
    ElMessage.success('需求质量报告已导出')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exportingQuality.value = false
  }
}

/** AUD-11：集成连通测试快捷入口（复用 integrationApi.testConnection） */
const testIntegrationConnection = async () => {
  testingConn.value = true
  try {
    const res = await integrationApi.testConnection(integrationTool.value)
    if (res?.reachable) {
      ElMessage.success(`${integrationTool.value} 连通成功：${res.message || ''}${res.version ? '（版本 ' + res.version + '）' : ''}`)
    } else {
      ElMessage.warning(`${integrationTool.value} 未连通：${res?.message || '未知原因'}`)
    }
  } catch (e) {
    ElMessage.error(`${integrationTool.value} 连通失败：${e?.response?.data?.message || e?.message || '请检查配置'}`)
  } finally {
    testingConn.value = false
  }
}

const loadData = async () => {
  requirements.value = await resultApi.getRequirements(projectId)
}

const viewDetail = async (row) => {
  currentReq.value = row
  showDetail.value = true
}

const viewSpec = async (row) => {
  currentReq.value = row
  currentSpec.value = await resultApi.getSpec(row.id)
  editing.value = false
  showSpec.value = true
  // 拉取需求详情中的 Kripke 结构（stateSet/stateTransitions/initialState）用于渲染状态迁移图
  try {
    const detail = await resultApi.getRequirement(row.id)
    if (detail) currentReq.value = detail
  } catch (e) {
    // 忽略，保留列表行数据
  }
  await nextTick()
  renderKripke()
}

/* ========== 6.2 整改：反例 / Kripke 状态迁移图可视化 ========== */
let kripkeChart = null

/** 从 verificationDetail JSON 中提取 counterexample 文本 */
const counterexampleText = computed(() => {
  const detail = currentSpec.value?.verificationDetail
  if (!detail) return ''
  try {
    const obj = JSON.parse(detail)
    const ce = obj?.counterexample
    return ce ? String(ce) : ''
  } catch (e) {
    return ''
  }
})

/** 解析需求 Kripke 结构为图数据 { nodes, links } */
const kripkeGraphData = computed(() => {
  if (!currentReq.value) return null
  const states = parseKripkeStates(currentReq.value.stateSet)
  if (!states.length) return null
  const idSet = new Set(states)
  const nodes = states.map((s, i) => ({
    id: s,
    name: s,
    symbolSize: 46,
    itemStyle: { color: i === 0 ? '#67C23A' : '#409EFF' },
    label: { show: true, fontSize: 11, color: '#fff' }
  }))
  const links = parseKripkeTransitions(currentReq.value.stateTransitions)
    .filter(t => idSet.has(t.from) && idSet.has(t.to))
    .map(t => ({
      source: t.from,
      target: t.to,
      value: t.condition || '',
      lineStyle: { color: t.from === t.to ? '#E6A23C' : '#a0a6ad', type: t.from === t.to ? 'dashed' : 'solid', curveness: 0.12 }
    }))
  return { nodes, links }
})

/** 解析状态集：支持 ["a","b"] JSON 数组 与 [a, b] 简易列表 */
const parseKripkeStates = (stateSet) => {
  if (!stateSet) return []
  try {
    const arr = JSON.parse(stateSet)
    if (Array.isArray(arr)) {
      return arr.map(s => (typeof s === 'string' ? s : (s?.name || '')).trim()).filter(Boolean)
    }
  } catch (e) { /* fallthrough */ }
  const m = String(stateSet).match(/\[(.*)\]/s)
  if (m) {
    return m[1].split(',').map(s => s.replace(/['"]/g, '').trim()).filter(Boolean)
  }
  return String(stateSet).split(',').map(s => s.replace(/[\[\]'"]/g, '').trim()).filter(Boolean)
}

/** 解析转移：支持 {"from","to","condition"} 数组 与 ["a->b"] 简易列表 */
const parseKripkeTransitions = (stateTransitions) => {
  if (!stateTransitions) return []
  try {
    const arr = JSON.parse(stateTransitions)
    if (Array.isArray(arr)) {
      return arr.map(t => {
        if (typeof t === 'string') {
          const idx = t.indexOf('->')
          if (idx <= 0) return null
          return { from: t.substring(0, idx).trim(), to: t.substring(idx + 2).trim(), condition: '' }
        }
        return { from: (t.from || '').trim(), to: (t.to || '').trim(), condition: (t.condition || '').trim() }
      }).filter(Boolean)
    }
  } catch (e) { /* fallthrough */ }
  return []
}

/** 渲染 Kripke 状态迁移图（ECharts graph） */
const renderKripke = () => {
  const el = document.getElementById('kripkeChart')
  if (!el || !kripkeGraphData.value) return
  if (!kripkeChart) kripkeChart = echarts.init(el)
  kripkeChart.setOption({
    tooltip: {
      formatter: (p) => p.dataType === 'edge'
        ? `${p.data.source} → ${p.data.target}${p.data.value ? '（' + p.data.value + '）' : ''}`
        : p.name
    },
    series: [{
      type: 'graph',
      layout: 'force',
      data: kripkeGraphData.value.nodes,
      links: kripkeGraphData.value.links,
      roam: true,
      draggable: true,
      edgeLabel: { show: true, formatter: '{c}', fontSize: 10, color: '#606266' },
      force: { repulsion: 260, edgeLength: [80, 150], gravity: 0.1, layoutAnimation: false },
      labelLayout: { hideOverlap: true }
    }]
  }, true)
  kripkeChart.resize()
}

/** 关闭规约弹窗时销毁图表实例 */
watch(showSpec, (v) => {
  if (!v && kripkeChart) {
    kripkeChart.dispose()
    kripkeChart = null
  }
})

/** 规约在线编辑（FR-REQ-003 手动优化） */
const startEdit = () => {
  editCode.value = currentSpec.value.alloyCode
  specSnapshot.value = currentSpec.value.alloyCode // 4.2：记录编辑前快照，用于保存提示变更
  editing.value = true
}

const cancelEdit = () => {
  editing.value = false
}

/** 4.2 整改：轻量编辑历史提示——统计相对快照的变更行数 */
const specSnapshot = ref('')

const countDiffLines = (a, b) => {
  const la = (a || '').split('\n')
  const lb = (b || '').split('\n')
  let diff = 0
  const n = Math.max(la.length, lb.length)
  for (let i = 0; i < n; i++) {
    if ((la[i] || '') !== (lb[i] || '')) diff++
  }
  return diff
}

/** 4.2 整改：Alloy 规约轻量语法高亮（先转义防 XSS，再包裹关键字/注释/字符串） */
const ALLOY_KEYWORDS = ['sig','one','lone','some','abstract','extends','fact','pred','fun','assert','check','run','for','module','open','let','in','and','or','not','implies','else','then','if','sum','set','seq','Int','String','univ','iden','this','var','private','enum']
const highlightAlloy = (code) => {
  if (!code) return ''
  let s = code
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
  // 注释 -- ... 与 // ...
  s = s.replace(/(--[^\n]*|#[^\n]*|\/\/[^\n]*)/g, '<span class="al-comment">$1</span>')
  // 字符串 "..."
  s = s.replace(/("[^"\n]*")/g, '<span class="al-string">$1</span>')
  // 关键字（边界匹配）
  s = s.replace(new RegExp('\\b(' + ALLOY_KEYWORDS.join('|') + ')\\b', 'g'), '<span class="al-kw">$1</span>')
  return s
}

/** 4.2 整改：规约简单格式化（按 { } 缩进） */
const formatSpec = () => {
  const lines = (editCode.value || '').split('\n')
  let indent = 0
  const out = []
  for (const raw of lines) {
    const line = raw.trim()
    if (!line) { out.push(''); continue }
    if (line.startsWith('}')) indent = Math.max(0, indent - 1)
    out.push('  '.repeat(indent) + line)
    if (line.endsWith('{')) indent++
  }
  editCode.value = out.join('\n')
  ElMessage.info('已格式化（按大括号缩进）')
}

const saveSpec = async () => {
  savingSpec.value = true
  try {
    const before = specSnapshot.value
    currentSpec.value = await resultApi.updateSpec(currentSpec.value.id, editCode.value)
    editing.value = false
    const diff = countDiffLines(before, editCode.value)
    ElMessage.success(`规约已校验并保存（相对上次修改 ${diff} 行变更）`)
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    savingSpec.value = false
  }
}

/** GAP-009：导出选中的需求到第三方平台 */
const exportToIntegration = async () => {
  if (selectedRequirements.value.length === 0) {
    ElMessage.warning('请先选择要导出的需求')
    return
  }
  try {
    exportingReq.value = true
    const result = await integrationApi.exportRequirements(projectId, selectedRequirements.value, integrationTool.value)
    ElMessage.success(`成功导出 ${result.length} 个需求到${integrationTool.value}`)
    selectedRequirements.value = []
    loadData()
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exportingReq.value = false
  }
}

/** GAP-009：需求变更联动——把已导出需求的最新内容同步到远程 issue（幂等 update） */
const syncReqToRemote = async (row) => {
  if (!row.remoteIssueKey) return
  row.syncing = true
  try {
    const ref = await integrationApi.syncRequirement(projectId, row.id, integrationTool.value)
    ElMessage.success(`需求 ${row.requirementId} 已同步到${integrationTool.value}（${ref.remoteKey || row.remoteIssueKey}）`)
  } catch (e) {
    ElMessage.error(e?.response?.data?.message || e.message || '同步失败，请检查集成配置')
  } finally {
    row.syncing = false
  }
}

/** GAP-009：处理表格选中变化 */
const handleSelectionChange = (selection) => {
  selectedRequirements.value = selection.map(r => r.id)
}

onMounted(() => {
  loadData()
})

onUnmounted(() => {
  // CQ-08：组件卸载销毁 Kripke 状态图实例
  kripkeChart?.dispose()
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 16px;
}

.spec-content {
  max-height: 70vh;
  overflow-y: auto;
}

.code-block {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 20px;
  border-radius: 6px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-x: auto;
}

/* 4.2 整改：Alloy 规约轻量语法高亮配色 */
:deep(.al-kw) { color: #569cd6; font-weight: 600; }
:deep(.al-comment) { color: #6a9955; font-style: italic; }
:deep(.al-string) { color: #ce9178; }

:deep(.code-editor textarea) {
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  line-height: 1.6;
  background: #1e1e1e;
  color: #d4d4d4;
}

.suggestion-block {
  margin: 6px 0 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 13px;
  line-height: 1.7;
  color: #b45309;
  max-height: 220px;
  overflow-y: auto;
}

/* 6.2 整改：反例 / Kripke 状态迁移图可视化 */
.counterexample-block {
  color: #cf1322;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 12px;
  max-height: 260px;
}
.kripke-chart {
  width: 100%;
  height: 340px;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  margin-bottom: 12px;
  background: #fafafa;
}
</style>
