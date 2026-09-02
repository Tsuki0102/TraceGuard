<template>
  <div class="defects-page">
    <div class="page-header">
      <div>
        <el-button link class="page-header__back" @click="$router.back()">
          <el-icon><ArrowLeft /></el-icon> 返回
        </el-button>
        <p class="tg-kicker">Quality Assurance</p>
        <h2 class="page-header__title">缺陷报告</h2>
        <p class="page-header__desc">缺陷报告与第三方平台推送</p>
      </div>
      <div class="page-header__actions">
        <el-button type="success" :loading="exporting" @click="handleExport">
          <el-icon><Download /></el-icon> 导出Excel
        </el-button>
        <el-button type="info" plain :loading="printing" @click="handlePrint">
          <el-icon><Printer /></el-icon> 打印报告
        </el-button>
      </div>
    </div>

    <el-card shadow="never">
      <div class="filter-bar">
        <el-radio-group v-model="filterLevel" @change="loadDefects">
          <el-radio-button label="">全部</el-radio-button>
          <el-radio-button label="serious">严重缺陷</el-radio-button>
          <el-radio-button label="general">一般缺陷</el-radio-button>
        </el-radio-group>
        <!-- GAP-020：主类型下拉（4类口径） -->
        <el-select v-model="filterType" placeholder="缺陷类型" clearable style="width: 160px" @change="onTypeChange">
          <el-option v-for="t in mainTypes" :key="t" :label="t" :value="t" />
        </el-select>
        <!-- GAP-020：子类型二级联动下拉 -->
        <el-select v-model="filterSubType" placeholder="子类型" clearable style="width: 150px" :disabled="!filterType" @change="loadDefects">
          <el-option v-for="s in subTypeOptions" :key="s" :label="s" :value="s" />
        </el-select>
        <!-- GAP-011：状态筛选 -->
        <el-select v-model="filterStatus" placeholder="状态" clearable style="width: 110px" @change="loadDefects">
          <el-option v-for="o in statusOptions.filter(o => o.code)" :key="o.code" :label="o.label" :value="o.code" />
        </el-select>
        <span class="count-info">共 {{ defects.length }} 个缺陷</span>
        <!-- GAP-009：第三方平台选择与批量推送 -->
        <el-select v-model="integrationTool" style="width: 120px" size="small">
          <el-option v-for="tool in availableTools" :key="tool" :label="tool" :value="tool" />
        </el-select>
        <el-button type="warning" :loading="pushing" :disabled="selectedDefects.length === 0" @click="pushToIntegration">
          <el-icon><Upload /></el-icon> 推送到{{ integrationTool }}({{ selectedDefects.length }})
        </el-button>
        <!-- AUD-11：集成连通测试快捷按钮 -->
        <el-button type="info" plain size="small" :loading="testingConn" @click="testIntegrationConnection">
          <el-icon><Connection /></el-icon> 测试连通
        </el-button>
      </div>

      <!-- W1-03/R8：缺陷类型×等级堆叠图 + 处理状态分布（随筛选条件实时联动） -->
      <div class="defect-charts">
        <div class="chart-box">
          <div class="chart-box__title">
            <el-icon :size="15" class="chart-box__icon"><Histogram /></el-icon>类型 × 等级分布
          </div>
          <div id="typeStackChart" style="height: 220px"></div>
        </div>
        <div class="chart-box">
          <div class="chart-box__title">
            <el-icon :size="15" class="chart-box__icon"><PieChart /></el-icon>处理状态分布
          </div>
          <div id="statusChart" style="height: 220px"></div>
        </div>
      </div>

      <!-- W2-13/O9：规则命中风险等级占比 -->
      <div class="risk-strip">
        <div class="risk-strip__cell">
          <span class="risk-strip__label">严重缺陷命中</span>
          <b class="risk-strip__num is-bad">{{ riskStats.serious }}</b>
        </div>
        <div class="risk-strip__cell">
          <span class="risk-strip__label">一般缺陷命中</span>
          <b class="risk-strip__num">{{ riskStats.general }}</b>
        </div>
        <div class="risk-strip__cell risk-strip__track-cell">
          <span class="risk-strip__label">严重占比</span>
          <div class="risk-strip__track">
            <i :style="{ width: riskStats.seriousPct + '%' }" :class="{ 'is-high': riskStats.seriousPct > 50 }"></i>
          </div>
          <b class="risk-strip__num" :class="{ 'is-bad': riskStats.seriousPct > 50 }">{{ riskStats.seriousPct }}%</b>
        </div>
        <div class="risk-strip__hint">按当前筛选条件下的命中分布实时计算</div>
      </div>

      <!-- W3-09：表格 / 状态看板视图切换 -->
      <div class="view-switch">
        <el-radio-group v-model="viewMode" size="small">
          <el-radio-button label="table">表格视图</el-radio-button>
          <el-radio-button label="kanban">状态看板</el-radio-button>
        </el-radio-group>
      </div>

      <template v-if="viewMode === 'table'">
      <el-table :data="defects" style="margin-top: 15px" @selection-change="handleSelectionChange">
        <el-table-column type="selection" width="55" fixed />
        <el-table-column prop="defectId" label="缺陷ID" width="110" fixed />
        <el-table-column prop="defectType" label="缺陷类型" width="130" />
        <el-table-column label="远程Issue" width="130">
          <template #default="{ row }">
            <el-tag v-if="row.remoteIssueKey" type="success" size="small">{{ row.remoteIssueKey }}</el-tag>
            <el-tag v-else type="info" size="small">未推送</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="子类型" width="130">
          <template #default="{ row }">
            <span v-if="row.subType && row.subType !== row.defectType">{{ row.subType }}</span>
            <span v-else style="color: var(--tg-text-secondary)">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="defectLevel" label="等级" width="90">
          <template #default="{ row }">
            <el-tag :type="row.defectLevel === 'serious' ? 'danger' : 'warning'" size="small">
              {{ row.defectLevel === 'serious' ? '严重' : '一般' }}
            </el-tag>
          </template>
        </el-table-column>
        <!-- GAP-011：状态列 -->
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="statusTagType[row.status] || statusTagType.pending" size="small">
              {{ formatStatus(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="相关需求" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.requirementText">{{ row.requirementText }}</span>
            <span v-else style="color: var(--tg-text-secondary)">-</span>
          </template>
        </el-table-column>
        <el-table-column label="缺陷原因" min-width="250" show-overflow-tooltip>
          <template #default="{ row }">{{ row.defectReason }}</template>
        </el-table-column>
        <el-table-column label="操作" width="320" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="viewDetail(row)">详情</el-button>
            <!-- GAP-009：推送单个缺陷到第三方平台 -->
            <el-divider direction="vertical" />
            <el-button type="info" link size="small" :loading="pushing" @click="pushSingleDefect(row)">
              推送到{{ integrationTool }}
            </el-button>
            <!-- GAP-011：状态流转按钮 -->
            <template v-for="t in getRowTransitions(row)" :key="t.code">
              <el-divider direction="vertical" />
              <el-button type="warning" link size="small" @click="changeStatus(row, t.code)">{{ t.label }}</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>
      </template>

      <!-- W3-09：状态看板（待处理/处理中/已解决/已忽略） -->
      <div v-else class="kanban">
        <div v-for="col in kanbanCols" :key="col.status" class="kanban-col" :class="'is-' + col.status">
          <div class="kanban-col__head">
            <span class="kanban-col__dot"></span>
            <b>{{ col.label }}</b>
            <em>{{ defectsFor(col.status).length }}</em>
          </div>
          <div class="kanban-col__body">
            <div
              v-for="d in defectsFor(col.status)"
              :key="d.id"
              class="kanban-card"
              @click="viewDetail(d)"
            >
              <div class="kanban-card__head">
                <span class="kanban-card__id">{{ d.defectId }}</span>
                <el-tag size="small" :type="d.defectLevel === 'serious' ? 'danger' : 'warning'">
                  {{ d.defectLevel === 'serious' ? '严重' : '一般' }}
                </el-tag>
              </div>
              <div class="kanban-card__type">{{ d.defectType }}{{ d.subType && d.subType !== d.defectType ? ' · ' + d.subType : '' }}</div>
              <div class="kanban-card__reason">{{ d.defectReason }}</div>
              <div class="kanban-card__actions" @click.stop>
                <template v-for="t in getRowTransitions(d)" :key="t.code">
                  <el-button size="small" round plain @click="changeStatus(d, t.code)">{{ t.label }}</el-button>
                </template>
              </div>
            </div>
            <div v-if="!defectsFor(col.status).length" class="kanban-col__empty">暂无</div>
          </div>
        </div>
      </div>
    </el-card>

    <el-dialog v-model="showDetail" title="缺陷详情" width="960px" top="6vh">
      <template v-if="currentDefect">
        <el-descriptions :column="3" border size="small">
          <el-descriptions-item label="缺陷ID">{{ currentDefect.defectId }}</el-descriptions-item>
          <el-descriptions-item label="缺陷类型">{{ currentDefect.defectType }}<span v-if="currentDefect.subType && currentDefect.subType !== currentDefect.defectType" style="color: var(--tg-text-secondary); margin-left: 6px">({{ currentDefect.subType }})</span></el-descriptions-item>
          <el-descriptions-item label="缺陷等级">
            <el-tag :type="currentDefect.defectLevel === 'serious' ? 'danger' : 'warning'" size="small">
              {{ currentDefect.defectLevel === 'serious' ? '严重' : '一般' }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="缺陷行号">
            <span v-if="currentDefect.defectLine && currentDefect.defectLine > 0" class="defect-line-badge">
              第 {{ currentDefect.defectLine }} 行
            </span>
            <span v-else style="color: var(--tg-text-secondary)">未定位（方法级）</span>
          </el-descriptions-item>
        </el-descriptions>

        <div class="compare-pane">
          <div class="pane">
            <div class="pane-title">需求原文</div>
            <!-- GAP-015：需求侧逐行渲染，与代码行双向高亮联动 -->
            <div class="requirement-box" v-if="reqLines.length">
              <div
                v-for="(line, idx) in reqLines"
                :key="idx"
                class="req-line"
                :class="{ 'link-highlight': activeLine === idx }"
                :data-idx="idx"
                @mouseenter="onLineHover(idx)"
                @click="onLineClick(idx, 'req')"
              >{{ line }}</div>
            </div>
            <div v-else class="requirement-box">
              <span class="placeholder">该缺陷无关联需求（代码超范围实现）</span>
            </div>
          </div>
          <div class="pane">
            <div class="pane-title">
              代码片段
              <span v-if="codeStartLine > 0" class="line-hint">起始行：第 {{ codeStartLine }} 行</span>
              <span v-if="defectLineOffset >= 0" class="line-hint defect-hint">缺陷行：第 {{ currentDefect.defectLine }} 行</span>
            </div>
            <!-- GAP-015：代码行增加高亮与点击/悬浮联动 -->
            <div class="code-box" v-if="codeLines.length">
              <div
                v-for="(line, idx) in codeLines"
                :key="idx"
                class="code-line"
                :class="{ 'link-highlight': activeLine === idx, 'defect-line': idx === defectLineOffset }"
                :data-idx="idx"
                @mouseenter="onLineHover(idx)"
                @click="onLineClick(idx, 'code')"
              >
                <span class="line-no">{{ codeStartLine > 0 ? codeStartLine + idx : idx + 1 }}</span>
                <span class="line-text">{{ line }}</span>
              </div>
            </div>
            <div v-else class="placeholder-box">该缺陷无关联代码（需求缺失）</div>
          </div>
        </div>

        <el-descriptions :column="1" border size="small" style="margin-top: 12px">
          <el-descriptions-item label="缺陷原因分析">
            <div style="white-space: pre-wrap">{{ currentDefect.defectReason }}</div>
          </el-descriptions-item>
          <el-descriptions-item label="修复建议">
            <div style="white-space: pre-wrap; color: var(--tg-success)">{{ currentDefect.repairSuggestion }}</div>
          </el-descriptions-item>
        </el-descriptions>

        <!-- FUN-11：AI 智能解释入口（大模型分析缺陷原因与修复建议；未启用大模型时后端返回提示） -->
        <div style="margin-top: 12px; display: flex; align-items: center; gap: 8px">
          <el-button type="warning" size="small" :loading="explaining" @click="explainCurrentDefect">AI 解释</el-button>
          <span style="color: var(--tg-text-secondary); font-size: 12px">大模型分析缺陷原因与修复建议</span>
        </div>
        <el-alert v-if="explainResult" type="info" :closable="false" show-icon style="margin-top: 8px">
          <template #title>AI 解释</template>
          <div style="white-space: pre-wrap">{{ explainResult }}</div>
        </el-alert>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, ElSelect, ElOption } from 'element-plus'
import { resultApi, exportApi, integrationApi, llmApi } from '@/api'
import { printReportPdf } from '@/utils/print'
import * as echarts from 'echarts'
import { chartColors, chartThemeName, chartText, chartFaint, chartAxisLine, chartSplitLine, chartTooltipBg, chartTitleColor, onChartThemeChange } from '@/utils/echartsTheme'

const route = useRoute()
const projectId = route.params.id
const defects = ref([])

// ===== W3-09：状态看板 =====
const viewMode = ref('table')
const kanbanCols = [
  { status: 'pending', label: '待处理' },
  { status: 'processing', label: '处理中' },
  { status: 'resolved', label: '已解决' },
  { status: 'ignored', label: '已忽略' }
]
const defectsFor = (status) => defects.value.filter((d) => (d.status || 'pending') === status)

// ===== W2-13/O9：规则命中风险等级占比（随筛选联动） =====
const riskStats = computed(() => {
  const serious = defects.value.filter((d) => d.defectLevel === 'serious').length
  const general = defects.value.length - serious
  const total = defects.value.length || 0
  const seriousPct = total ? Math.round((serious / total) * 100) : 0
  return { serious, general, seriousPct }
})
const filterLevel = ref('')
const filterType = ref('')
const filterSubType = ref('')
const filterStatus = ref('')
const showDetail = ref(false)
const currentDefect = ref(null)
const exporting = ref(false)
const printing = ref(false)
/** FUN-11：AI 智能解释（大模型分析缺陷原因与修复建议） */
const explaining = ref(false)
const explainResult = ref('')
/** 代码单元ID -> 起始行映射，用于代码片段按真实行号展示 */
const unitStartLineMap = ref({})

/** GAP-009：第三方工具推送 */
const integrationTool = ref('JIRA') // 默认JIRA
const availableTools = ['JIRA', 'ZENTAO']
const pushing = ref(false)
const testingConn = ref(false)
const selectedDefects = ref([]) // 选中的缺陷ID列表

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

/** GAP-020：4类主类型常量 */
const mainTypes = ['需求缺失', '代码超范围实现', '业务逻辑不一致', '约束条件不满足']

/** GAP-011：缺陷状态选项（code -> label） */
const statusOptions = [
  { code: '', label: '全部' },
  { code: 'pending', label: '待处理' },
  { code: 'processing', label: '处理中' },
  { code: 'resolved', label: '已解决' },
  { code: 'ignored', label: '已忽略' }
]

/** GAP-011：状态标签类型映射 */
const statusTagType = {
  pending: 'info',
  processing: 'warning',
  resolved: 'success',
  ignored: 'info'
}

/** GAP-011：当前状态下可流转的目标状态 */
const allowedTransitions = computed(() => {
  const s = currentDefect.value?.status || 'pending'
  const map = {
    pending: [{ code: 'processing', label: '开始处理' }, { code: 'ignored', label: '忽略此缺陷' }],
    processing: [{ code: 'resolved', label: '标记为已解决' }, { code: 'ignored', label: '忽略此缺陷' }, { code: 'pending', label: '回退' }],
    resolved: [{ code: 'processing', label: '重新打开' }],
    ignored: [{ code: 'processing', label: '重新处理' }]
  }
  return map[s] || []
})

/** GAP-020：子类型选项（按当前主类型过滤已知子类型） */
const subTypeMap = {
  '需求缺失': ['需求缺失', '缺失实现'],
  '代码超范围实现': ['代码超范围实现', '超范围实现', '冗余实现'],
  '业务逻辑不一致': ['逻辑偏离', '数据一致性风险', '安全风险', '需求代码不匹配'],
  '约束条件不满足': ['约束条件不满足', '不变量不满足', '异常处理缺失', '资源管理缺失', '需求代码不匹配']
}
const subTypeOptions = computed(() => {
  if (!filterType.value) return []
  return subTypeMap[filterType.value] || []
})

const onTypeChange = () => {
  filterSubType.value = ''
  loadDefects()
}

/** GAP-011：状态码转中文 */
const formatStatus = (status) => {
  const o = statusOptions.find(s => s.code === status)
  return o ? o.label : '待处理'
}

/** GAP-011：获取某行可执行的状态流转操作 */
const getRowTransitions = (row) => {
  const s = row.status || 'pending'
  const map = {
    pending: [{ code: 'processing', label: '开始处理' }, { code: 'ignored', label: '忽略此缺陷' }],
    processing: [{ code: 'resolved', label: '标记为已解决' }, { code: 'ignored', label: '忽略此缺陷' }, { code: 'pending', label: '回退' }],
    resolved: [{ code: 'processing', label: '重新打开' }],
    ignored: [{ code: 'processing', label: '重新处理' }]
  }
  return map[s] || []
}

/** GAP-011：执行状态流转 */
const changeStatus = async (row, newStatus) => {
  try {
    await ElMessageBox.confirm(
      `确定将缺陷 [${row.defectId}] 从 ${formatStatus(row.status)} 改为 ${formatStatus(newStatus)}？`,
      '确认状态变更',
      { confirmButtonText: '确定', cancelButtonText: '取消', type: 'warning' }
    )
    await resultApi.updateDefectStatus(row.defectId, newStatus)
    ElMessage.success('状态已更新')
    loadDefects()
  } catch (e) {
    if (e !== 'cancel') {
      ElMessage.error(e.message || '状态更新失败')
    }
  }
}

const loadDefects = async () => {
  defects.value = await resultApi.getDefects(projectId, null, filterLevel.value, filterType.value || null, filterSubType.value || null, filterStatus.value || null)
  await nextTick()
  renderCharts()
}

// ===== W1-03/R8：类型×等级堆叠 + 状态分布图表（随筛选联动） =====
let typeStackChart = null
let statusChart = null

const TYPE_ORDER = ['需求缺失', '代码超范围实现', '业务逻辑不一致', '约束条件不满足']
const TYPE_COLORS = {
  '需求缺失': chartColors.danger,
  '代码超范围实现': chartColors.warning,
  '业务逻辑不一致': chartColors.primary,
  '约束条件不满足': chartColors.faint
}

const renderCharts = () => {
  const elStack = document.getElementById('typeStackChart')
  const elStatus = document.getElementById('statusChart')
  if (!elStack || !elStatus) return
  if (!typeStackChart) typeStackChart = echarts.init(elStack, chartThemeName())
  if (!statusChart) statusChart = echarts.init(elStatus, chartThemeName())

  // 类型 × 等级堆叠（未知类型归入"其他"）
  const typeCount = {}
  defects.value.forEach((d) => {
    const t = TYPE_ORDER.includes(d.defectType) ? d.defectType : '其他'
    if (!typeCount[t]) typeCount[t] = { serious: 0, general: 0 }
    if (d.defectLevel === 'serious') typeCount[t].serious += 1
    else typeCount[t].general += 1
  })
  const types = [...TYPE_ORDER, '其他'].filter((t) => typeCount[t])
  typeStackChart.setOption({
    title: { text: '类型 × 等级', left: 'center' },
    tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' } },
    legend: { bottom: 0, data: ['严重', '一般'] },
    grid: { left: 36, right: 16, top: 40, bottom: 42 },
    xAxis: { type: 'category', data: types.length ? types : ['暂无数据'], axisLabel: { rotate: 20, fontSize: 11 } },
    yAxis: { type: 'value', splitLine: { lineStyle: { color: chartSplitLine() } } },
    series: [
      { name: '严重', type: 'bar', stack: 'total', barMaxWidth: 26, itemStyle: { color: chartColors.danger }, data: types.map((t) => typeCount[t].serious) },
      { name: '一般', type: 'bar', stack: 'total', barMaxWidth: 26, itemStyle: { color: chartColors.warning }, data: types.map((t) => typeCount[t].general) }
    ]
  }, true)

  // 状态分布环形
  const statusCount = { pending: 0, processing: 0, resolved: 0, ignored: 0 }
  defects.value.forEach((d) => {
    const s = d.status || 'pending'
    if (statusCount[s] != null) statusCount[s] += 1
  })
  const STATUS_COLORS = {
    pending: chartColors.neutral,
    processing: chartColors.warning,
    resolved: chartColors.success,
    ignored: chartColors.faint
  }
  statusChart.setOption({
    title: { text: '处理状态', left: 'center' },
    tooltip: { trigger: 'item', formatter: '{b}: {c}（{d}%）' },
    legend: { bottom: 0 },
    series: [{
      type: 'pie',
      radius: ['42%', '68%'],
      center: ['50%', '46%'],
      label: { show: false },
      itemStyle: { borderColor: '#fff', borderWidth: 2 },
      data: [
        { value: statusCount.pending, name: '待处理', itemStyle: { color: STATUS_COLORS.pending } },
        { value: statusCount.processing, name: '处理中', itemStyle: { color: STATUS_COLORS.processing } },
        { value: statusCount.resolved, name: '已解决', itemStyle: { color: STATUS_COLORS.resolved } },
        { value: statusCount.ignored, name: '已忽略', itemStyle: { color: STATUS_COLORS.ignored } }
      ].filter((x) => x.value > 0)
    }]
  }, true)
}

const loadCodeUnits = async () => {
  try {
    const units = await resultApi.getCodeUnits(projectId)
    unitStartLineMap.value = {}
    units.forEach(u => { unitStartLineMap.value[u.id] = u.startLine })
  } catch (e) {
    console.error(e)
  }
}

/** 当前缺陷代码片段的行列表 */
const codeLines = computed(() =>
  currentDefect.value?.codeSnippet ? currentDefect.value.codeSnippet.split('\n') : []
)

/** 当前缺陷关联代码单元的起始行（无关联时返回 -1，行号回退为从1计） */
const codeStartLine = computed(() => {
  const start = unitStartLineMap.value[currentDefect.value?.codeUnitId]
  return Number.isInteger(start) && start > 0 ? start : -1
})

/** 2.6 整改：缺陷命中行在代码片段中的相对偏移（0-based）；无法定位或超出片段范围返回 -1 */
const defectLineOffset = computed(() => {
  const dl = currentDefect.value?.defectLine
  if (!Number.isInteger(dl) || dl <= 0 || codeStartLine.value <= 0) return -1
  const offset = dl - codeStartLine.value
  if (offset < 0 || offset >= codeLines.value.length) return -1
  return offset
})

/* ========== GAP-015：需求-代码双向高亮联动 ========== */

/** 当前缺陷需求文本按行拆分（无行号，与代码行按索引一一对应） */
const reqLines = computed(() =>
  currentDefect.value?.requirementText ? currentDefect.value.requirementText.split('\n') : []
)

/** 当前高亮的行索引（-1=无）。需求侧与代码侧共用同一索引，实现双向联动 */
const activeLine = ref(-1)

/** hover/点击：仅高亮两侧对应行（不滚动，避免打断阅读） */
const onLineHover = (idx) => {
  activeLine.value = idx
}

/** 点击：高亮另一侧对应行并 scrollIntoView 定位（左右滚动容器互不干扰） */
const onLineClick = (idx, side) => {
  activeLine.value = idx
  const target = document.querySelector(`.${side === 'code' ? 'req-line' : 'code-line'}[data-idx="${idx}"]`)
  target?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}

const handleExport = async () => {
  exporting.value = true
  try {
    await exportApi.defectsExcel(projectId)
    ElMessage.success('导出成功')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

/** GAP-038：直接打印缺陷报告 */
const handlePrint = async () => {
  printing.value = true
  try {
    await printReportPdf(projectId)
    ElMessage.success('已唤起打印对话框')
  } catch (e) {
    ElMessage.error(e.message || '打印失败')
  } finally {
    printing.value = false
  }
}

/** GAP-009：处理表格选中变化 */
const handleSelectionChange = (selection) => {
  selectedDefects.value = selection.map(d => d.defectId)
}

const viewDetail = (row) => {
  currentDefect.value = row
  activeLine.value = -1 // GAP-015：打开详情时重置高亮
  explainResult.value = '' // 切换缺陷时清空上次 AI 解释
  showDetail.value = true
}

/** FUN-11：调用后端大模型对当前缺陷做智能解释 */
const explainCurrentDefect = async () => {
  if (!currentDefect.value) return
  explaining.value = true
  explainResult.value = ''
  try {
    const res = await llmApi.explainDefect({
      requirementText: currentDefect.value.requirementText || '',
      codeSnippet: currentDefect.value.codeSnippet || '',
      defectType: currentDefect.value.defectType || ''
    })
    explainResult.value = (typeof res === 'string') ? res : JSON.stringify(res, null, 2)
  } catch (e) {
    explainResult.value = ''
    ElMessage.error(e.message || 'AI 解释调用失败')
  } finally {
    explaining.value = false
  }
}

/** GAP-009：推送选中的缺陷到第三方平台 */
const pushToIntegration = async () => {
  if (selectedDefects.value.length === 0) {
    ElMessage.warning('请先选择要推送的缺陷')
    return
  }
  try {
    pushing.value = true
    const result = await integrationApi.pushDefects(projectId, selectedDefects.value, integrationTool.value)
    ElMessage.success(`成功推送 ${result.length} 个缺陷到${integrationTool.value}`)
    selectedDefects.value = []
    loadDefects()
  } catch (e) {
    ElMessage.error(e.message || '推送失败')
  } finally {
    pushing.value = false
  }
}

/** GAP-009：推送单个缺陷 */
const pushSingleDefect = async (row) => {
  try {
    pushing.value = true
    const result = await integrationApi.pushDefects(projectId, [row.defectId], integrationTool.value)
    ElMessage.success(`成功推送到${integrationTool.value}：${result[0]?.remoteKey || '未知'}`)
    loadDefects()
  } catch (e) {
    ElMessage.error(e.message || '推送失败')
  } finally {
    pushing.value = false
  }
}

onChartThemeChange(() => { typeStackChart?.dispose(); typeStackChart = null; statusChart?.dispose(); statusChart = null; renderCharts() })

onMounted(() => {
  loadDefects()
  loadCodeUnits()
})

onUnmounted(() => {
  typeStackChart?.dispose()
  statusChart?.dispose()
  typeStackChart = null
  statusChart = null
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 20px;
}

/* ===== W1-03/R8：缺陷统计图表区 ===== */
.defect-charts {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 16px;
  margin-top: 16px;
}

.chart-box {
  border-radius: 16px;
  border: 1px solid var(--tg-border);
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.92), rgba(255, 255, 255, 0.6));
  padding: 14px 18px 6px;
}

.chart-box__title {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--tg-text-primary);
}

.chart-box__icon {
  color: var(--tg-accent);
  background: var(--el-color-primary-light-9);
  border-radius: 8px;
  padding: 4px;
  box-sizing: content-box;
}

/* ===== W2-13/O9：规则命中风险等级占比 ===== */
.risk-strip {
  display: flex;
  align-items: center;
  gap: 24px;
  margin-top: 12px;
  padding: 12px 18px;
  border-radius: 14px;
  border: 1px dashed var(--tg-border);
  background: rgba(255, 255, 255, 0.5);
  flex-wrap: wrap;
}

.risk-strip__cell {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.risk-strip__label {
  font-size: 12px;
  color: var(--tg-text-secondary);
}

.risk-strip__num {
  font-size: 16px;
  font-weight: 700;
  color: var(--tg-text-primary);
  font-variant-numeric: tabular-nums;
}

.risk-strip__num.is-bad {
  color: var(--tg-danger);
}

.risk-strip__track-cell {
  flex: 1;
  min-width: 180px;
}

.risk-strip__track {
  flex: 1;
  height: 7px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

.risk-strip__track i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--tg-accent-gradient);
  transition: width 0.6s var(--tg-ease);
}

.risk-strip__track i.is-high {
  background: linear-gradient(90deg, var(--tg-warning), var(--tg-danger));
}

.risk-strip__hint {
  font-size: 11.5px;
  color: var(--tg-slate);
  margin-left: auto;
}

/* ===== W3-09：状态看板 ===== */
.view-switch {
  margin: 14px 0 4px;
}

.kanban {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-top: 14px;
  align-items: start;
}

.kanban-col {
  border-radius: 16px;
  border: 1px solid var(--tg-border);
  background: rgba(255, 255, 255, 0.5);
  overflow: hidden;
  min-width: 0;
}

.kanban-col__head {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 14px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--tg-text-primary);
  border-bottom: 1px solid var(--tg-border);
}

.kanban-col__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.kanban-col.is-pending .kanban-col__dot { background: var(--tg-slate); box-shadow: 0 0 0 3px rgba(154, 139, 114, 0.16); }
.kanban-col.is-processing .kanban-col__dot { background: var(--tg-warning); box-shadow: 0 0 0 3px rgba(232, 155, 60, 0.16); }
.kanban-col.is-resolved .kanban-col__dot { background: var(--tg-success); box-shadow: 0 0 0 3px rgba(107, 142, 78, 0.16); }
.kanban-col.is-ignored .kanban-col__dot { background: var(--tg-slate); box-shadow: 0 0 0 3px rgba(154, 139, 114, 0.12); }

.kanban-col__head em {
  margin-left: auto;
  font-style: normal;
  font-size: 11.5px;
  font-weight: 600;
  color: var(--tg-text-secondary);
  background: rgba(0, 0, 0, 0.05);
  padding: 1px 8px;
  border-radius: 999px;
  font-variant-numeric: tabular-nums;
}

.kanban-col__body {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 10px;
  max-height: 520px;
  overflow-y: auto;
}

.kanban-card {
  padding: 10px 12px;
  border-radius: 12px;
  background: var(--el-color-primary-light-9);
  border: 1px solid rgba(143, 107, 34, 0.12);
  cursor: pointer;
  transition: transform 0.25s var(--tg-ease), box-shadow 0.25s ease;
}

.kanban-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--tg-shadow-card-hover);
}

.kanban-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.kanban-card__id {
  font-family: var(--tg-font-mono);
  font-size: 12px;
  font-weight: 600;
  color: var(--tg-accent);
}

.kanban-card__type {
  margin-top: 6px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--tg-text-primary);
}

.kanban-card__reason {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.6;
  color: var(--tg-text-secondary);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.kanban-card__actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}

.kanban-card__actions :deep(.el-button) {
  margin-left: 0;
  height: 26px;
  font-size: 12px;
  padding: 0 10px;
}

.kanban-col__empty {
  padding: 20px 0;
  text-align: center;
  font-size: 12px;
  color: var(--tg-slate);
}

@media (max-width: 1280px) {
  .kanban {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 720px) {
  .kanban {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 1100px) {
  .defect-charts {
    grid-template-columns: 1fr;
  }
}

.count-info {
  color: var(--tg-text-secondary);
}

/* 需求-代码分栏对比 */
.compare-pane {
  display: flex;
  gap: 12px;
  margin-top: 12px;
}

.pane {
  flex: 1;
  min-width: 0;
  border: 1px solid #e4e7ed;
  border-radius: 4px;
  overflow: hidden;
}

.pane-title {
  padding: 8px 12px;
  background: #f5f7fa;
  border-bottom: 1px solid #e4e7ed;
  font-weight: 600;
  font-size: 13px;
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.line-hint {
  font-weight: 400;
  color: var(--tg-text-secondary);
  font-size: 12px;
}

.requirement-box {
  padding: 12px;
  white-space: pre-wrap;
  line-height: 1.8;
  max-height: 360px;
  overflow: auto;
  font-size: 13px;
}

.placeholder {
  color: var(--tg-text-secondary);
}

.placeholder-box {
  padding: 24px;
  text-align: center;
  color: var(--tg-text-secondary);
}

.code-box {
  background: #1e1e1e;
  color: #d4d4d4;
  font-family: 'Consolas', monospace;
  font-size: 12px;
  max-height: 360px;
  overflow: auto;
  padding: 10px 0;
}

.code-line {
  display: flex;
  line-height: 1.7;
  white-space: pre;
}

/* GAP-015：需求侧逐行渲染与悬浮反馈 */
.req-line {
  padding: 0 6px;
  border-radius: 3px;
  cursor: pointer;
  transition: background 0.15s;
}
.req-line:hover {
  background: var(--tg-bg-page);
}

/* GAP-015：双向高亮统一样式（需求侧浅色高亮、代码侧深色高亮） */
.req-line.link-highlight {
  background: #fdf6ec;
  color: #b88230;
}
.code-line:hover {
  background: #2a2f3a;
  cursor: pointer;
}
.code-line.link-highlight {
  background: #2f4050;
  color: #ffffff;
}

/* 2.6 整改：缺陷命中行高亮（红色醒目标记，区别于双向联动高亮） */
.code-line.defect-line {
  background: #5a1d1d;
  color: #ffd2d2;
  box-shadow: inset 3px 0 0 var(--tg-danger);
}
.code-line.defect-line:hover {
  background: #6e2424;
}

/* 缺陷行号徽标与片段标题提示 */
.defect-line-badge {
  color: var(--tg-danger);
  font-weight: 600;
}
.line-hint.defect-hint {
  color: var(--tg-danger);
  font-weight: 600;
}

.line-no {
  flex: none;
  width: 52px;
  text-align: right;
  padding-right: 12px;
  color: #858585;
  user-select: none;
  border-right: 1px solid var(--tg-text-secondary);
  margin-right: 12px;
}

.line-text {
  flex: 1;
  padding-right: 10px;
}

/* ===== 移动端适配（≤768px） ===== */
@media (max-width: 768px) {
  /* 需求-代码对比：并排会挤成两条极窄栏（代码几乎不可读），改为上下堆叠 */
  .compare-pane {
    flex-direction: column;
    gap: 10px;
  }

  /* 纵向堆叠后限制单块高度，避免弹窗内滚动过长 */
  .requirement-box,
  .code-box {
    max-height: 260px;
    font-size: 12.5px;
  }

  /* 代码行号列收窄，把宽度让给代码正文 */
  .line-no {
    width: 42px;
    padding-right: 8px;
    margin-right: 8px;
  }

  /* 筛选条：允许换行，避免溢出 */
  .filter-bar {
    flex-wrap: wrap;
    gap: 12px;
  }
}
</style>
