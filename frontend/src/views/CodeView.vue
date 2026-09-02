<template>
  <div class="code-view">
    <div class="page-header">
      <div>
        <el-button link class="page-header__back" @click="$router.back()">
          <el-icon><ArrowLeft /></el-icon> 返回
        </el-button>
        <p class="tg-kicker">Code Analysis</p>
        <h2 class="page-header__title">代码分析</h2>
        <p class="page-header__desc">代码单元对比、控制流图分析与基础缺陷检测</p>
      </div>
      <div class="page-header__actions">
        <!-- FR-CODE-003 规则3（2.5 整改项）：导出语义向量（需求+代码单元，按项目） -->
        <el-radio-group v-model="vectorFormat" size="small">
          <el-radio-button label="json">JSON</el-radio-button>
          <el-radio-button label="csv">CSV</el-radio-button>
        </el-radio-group>
        <el-button type="success" plain :loading="exportingVectors" @click="handleExportVectors">
          <el-icon><Download /></el-icon> 导出语义向量
        </el-button>
      </div>
    </div>

    <el-row :gutter="20">
      <el-col :span="8">
        <el-card shadow="never">
          <template #header>
            <span>代码单元列表 ({{ codeUnits.length }}个方法)</span>
          </template>
          <el-input v-model="searchKey" placeholder="搜索类名/方法名" style="margin-bottom: 10px" clearable>
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <div class="code-list">
            <div
              v-for="unit in filteredUnits"
              :key="unit.id"
              class="code-item"
              :class="{ active: currentUnit?.id === unit.id }"
              @click="selectUnit(unit)"
            >
              <div class="class-name">{{ unit.className }}</div>
              <div class="method-name">
                <!-- FR-CODE-001 规则3（2.4）：字段清单单元标识 -->
                <template v-if="unit.methodName === '[字段清单]'">
                  <el-tag size="small" type="info">字段清单（{{ unit.logicDescription }}）</el-tag>
                </template>
                <template v-else>
                  {{ unit.methodName }}
                  <!-- GAP-016：圈复杂度展示 -->
                  <el-tag v-if="unit.cyclomaticComplexity != null" size="small" :type="complexityType(unit.cyclomaticComplexity)" class="complexity-tag">
                    圈复杂度 {{ unit.cyclomaticComplexity }}
                  </el-tag>
                </template>
              </div>
              <div class="file-path">{{ unit.filePath }}</div>
            </div>
          </div>
        </el-card>
      </el-col>

      <el-col :span="16">
        <el-card v-if="currentUnit" shadow="never">
          <template #header>
            <div class="code-header">
              <span>{{ currentUnit.className }}.{{ currentUnit.methodName }}()</span>
              <el-tag size="small">行 {{ currentUnit.startLine }}-{{ currentUnit.endLine }}</el-tag>
            </div>
          </template>

          <el-tabs v-model="activeTab">
            <el-tab-pane label="对比视图" name="compare">
              <div class="compare-pane">
                <div class="compare-col">
                  <div class="compare-title">源代码实现</div>
                  <div class="code-lines code-block">
                    <div
                      v-for="ln in codeLines"
                      :key="ln.no"
                      class="code-line"
                      :class="{ 'defect-line': isDefectLine(ln.no) }"
                    >
                      <span class="line-no">{{ ln.no }}</span>
                      <span class="line-text code-hl" v-html="highlightJava(ln.text)"></span>
                    </div>
                  </div>
                </div>
                <div class="compare-col">
                  <div class="compare-title">需求逻辑还原</div>
                  <div class="logic-text compare-logic">{{ currentUnit.logicDescription || '暂无逻辑描述' }}</div>
                  <div class="compare-tip">
                    对比说明：右侧为系统从需求文档提取并形式化还原的实现逻辑，与左侧实际代码逐条比对，
                    语义偏差将体现为一致性校验结果中的不一致项。
                  </div>
                </div>
              </div>
            </el-tab-pane>
            <el-tab-pane label="源代码" name="source">
              <div class="code-lines code-block">
                <div
                  v-for="ln in codeLines"
                  :key="ln.no"
                  class="code-line"
                  :class="{ 'defect-line': isDefectLine(ln.no) }"
                >
                  <span class="line-no">{{ ln.no }}</span>
                  <span class="line-text code-hl" v-html="highlightJava(ln.text)"></span>
                </div>
              </div>
            </el-tab-pane>
            <el-tab-pane label="控制流图(CFG)" name="cfg">
              <div v-if="cfgData" class="cfg-wrapper">
                <div class="cfg-legend">
                  <span class="legend-item"><i class="dot dot-start"></i>入口/出口</span>
                  <span class="legend-item"><i class="dot dot-if"></i>条件分支</span>
                  <span class="legend-item"><i class="dot dot-loop"></i>循环</span>
                  <span class="legend-item"><i class="dot dot-catch"></i>异常捕获</span>
                  <span class="legend-item"><i class="dot dot-jump"></i>跳转</span>
                  <span class="legend-item"><i class="dot dot-stmt"></i>语句</span>
                </div>
                <div id="cfgChart" class="cfg-chart"></div>
              </div>
              <el-empty v-else description="该代码单元暂无CFG数据，请重新上传代码项目后分析生成" />
            </el-tab-pane>
            <el-tab-pane label="逻辑还原" name="logic">
              <div class="logic-text">{{ currentUnit.logicDescription }}</div>
            </el-tab-pane>
            <el-tab-pane label="语义信息" name="semantic">
              <el-descriptions :column="1" border>
                <el-descriptions-item label="代码ID">{{ currentUnit.codeId }}</el-descriptions-item>
                <el-descriptions-item label="文件路径">{{ currentUnit.filePath }}</el-descriptions-item>
                <el-descriptions-item label="类名">{{ currentUnit.className }}</el-descriptions-item>
                <el-descriptions-item label="方法名">{{ currentUnit.methodName }}</el-descriptions-item>
                <el-descriptions-item label="行号范围">{{ currentUnit.startLine }} - {{ currentUnit.endLine }}</el-descriptions-item>
                <!-- GAP-016：圈复杂度详情 -->
                <el-descriptions-item label="圈复杂度">
                  <el-tag size="small" :type="complexityType(currentUnit.cyclomaticComplexity)">
                    {{ currentUnit.cyclomaticComplexity != null ? currentUnit.cyclomaticComplexity : '—' }}
                  </el-tag>
                  <span class="complexity-tip">
                    {{ complexityLevel(currentUnit.cyclomaticComplexity) }}
                  </span>
                </el-descriptions-item>
              </el-descriptions>
            </el-tab-pane>
          </el-tabs>
        </el-card>

        <el-empty v-else description="请从左侧选择一个代码单元查看" />
      </el-col>
    </el-row>

    <el-card shadow="never" style="margin-top: 20px">
      <template #header>
        <div style="display: flex; justify-content: space-between; align-items: center">
          <span>代码基础缺陷检测结果 ({{ defectTotal }}个潜在缺陷)</span>
          <el-button type="success" size="small" :loading="exporting" @click="handleExport">
            <el-icon><Download /></el-icon> 导出Excel
          </el-button>
        </div>
      </template>
      <el-table :data="codeDefects">
        <el-table-column prop="filePath" label="文件" show-overflow-tooltip />
        <el-table-column prop="className" label="类名" width="150" />
        <el-table-column prop="methodName" label="方法" width="150" />
        <el-table-column prop="lineNumber" label="行号" width="80" />
        <el-table-column prop="defectType" label="缺陷类型" width="130" />
        <el-table-column prop="severity" label="严重度" width="90">
          <template #default="{ row }">
            <el-tag :type="row.severity === 'high' ? 'danger' : 'warning'" size="small">
              {{ {high:'高', medium:'中', low:'低'}[row.severity] || row.severity }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="description" label="描述" show-overflow-tooltip />
        <el-table-column prop="repairSuggestion" label="修复建议" show-overflow-tooltip />
      </el-table>
      <!-- FUN-12：缺陷列表分页 -->
      <div style="display: flex; justify-content: flex-end; margin-top: 12px">
        <el-pagination
          v-model:current-page="defectPage"
          v-model:page-size="defectSize"
          :total="defectTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="loadDefects"
          @size-change="loadDefects"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, watch, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { highlightJava } from '@/utils/highlightJava'
import { chartColors, chartThemeName, chartText, chartFaint, chartAxisLine, chartSplitLine, chartTooltipBg, chartTitleColor, onChartThemeChange } from '@/utils/echartsTheme'
import { resultApi, exportApi } from '@/api'

const route = useRoute()
const projectId = route.params.id
const codeUnits = ref([])
const codeDefects = ref([])
const currentUnit = ref(null)
/** FUN-12：代码缺陷列表后端分页（万行级避免全量拉取卡顿） */
const defectPage = ref(1)
const defectSize = ref(20)
const defectTotal = ref(0)

/** 4.8 整改：按行渲染代码，命中基础缺陷行号的高亮（行内红标） */
const codeLines = computed(() => {
  const u = currentUnit.value
  if (!u || !u.codeContent) return []
  const base = u.startLine || 1
  return String(u.codeContent).split('\n').map((text, i) => ({
    no: base + i,
    text
  }))
})
const defectLineSet = computed(() => {
  const s = new Set()
  codeDefects.value.forEach((d) => { if (d.lineNumber) s.add(d.lineNumber) })
  return s
})
const isDefectLine = (no) => defectLineSet.value.has(no)
const searchKey = ref('')
const activeTab = ref('compare')
const exporting = ref(false)
/** FR-CODE-003 规则3（2.5 整改项）：导出语义向量（JSON/CSV） */
const vectorFormat = ref('json')
const exportingVectors = ref(false)
const handleExportVectors = async () => {
  exportingVectors.value = true
  try {
    await exportApi.semanticVectors(projectId, vectorFormat.value)
    ElMessage.success('语义向量导出成功')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exportingVectors.value = false
  }
}

const handleExport = async () => {
  exporting.value = true
  try {
    await exportApi.codeDefectsExcel(projectId)
    ElMessage.success('导出成功')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

const filteredUnits = computed(() => {
  if (!searchKey.value) return codeUnits.value
  const key = searchKey.value.toLowerCase()
  return codeUnits.value.filter(u =>
    u.className?.toLowerCase().includes(key) ||
    u.methodName?.toLowerCase().includes(key) ||
    u.filePath?.toLowerCase().includes(key)
  )
})

/** GAP-016：圈复杂度分级配色与说明（McCabe：≤10 简单 / ≤20 复杂 / >20 极高） */
const complexityType = (c) => {
  if (c == null) return 'info'
  if (c <= 10) return 'success'
  if (c <= 20) return 'warning'
  return 'danger'
}
const complexityLevel = (c) => {
  if (c == null) return ''
  if (c <= 10) return '（简单，建议 ≤10）'
  if (c <= 20) return '（较复杂，建议拆分）'
  return '（极高，强烈建议重构拆分）'
}

/** FUN-12：代码缺陷分页加载 */
const loadDefects = async () => {
  const res = await resultApi.getCodeDefectsPage(projectId, defectPage.value, defectSize.value)
  codeDefects.value = (res && res.records) || res || []
  // total 强转 Number，避免 ElPagination "Expected Number, got String" 告警
  defectTotal.value = (res && res.total) != null ? Number(res.total) : codeDefects.value.length
}

const loadData = async () => {
  codeUnits.value = await resultApi.getCodeUnits(projectId)
  if (codeUnits.value.length > 0) {
    currentUnit.value = codeUnits.value[0]
  }
  await loadDefects()
}

const selectUnit = (unit) => {
  currentUnit.value = unit
}

/* ========== CFG 可视化 ========== */
let cfgChart = null

/** CFG节点类型配色与图形 */
const CFG_NODE_STYLE = {
  start:  { color: chartColors.faint, symbol: 'roundRect', size: 46 },
  end:    { color: chartColors.faint, symbol: 'roundRect', size: 46 },
  if:     { color: chartColors.primary, symbol: 'diamond', size: [56, 40] },
  loop:   { color: chartColors.warning, symbol: 'diamond', size: [56, 40] },
  switch: { color: chartColors.purple, symbol: 'diamond', size: [56, 40] },
  catch:  { color: chartColors.danger, symbol: 'roundRect', size: 52 },
  return: { color: chartColors.success, symbol: 'circle', size: 34 },
  throw:  { color: chartColors.danger, symbol: 'triangle', size: 36 },
  break:  { color: '#B88230', symbol: 'triangle', size: 34 },
  continue: { color: '#B88230', symbol: 'triangle', size: 34 },
  merge:  { color: chartColors.faint, symbol: 'circle', size: 22 },
  stmt:   { color: chartColors.sky, symbol: 'roundRect', size: 50 }
}

/** 边标签样式：true/false实线着色，back/loop_exit虚线 */
const edgeStyle = (label) => {
  if (label === 'true') return { color: chartColors.success, type: 'solid', width: 2 }
  if (label === 'false') return { color: chartColors.danger, type: 'solid', width: 2 }
  if (label === 'back' || label === 'loop_exit') return { color: chartColors.warning, type: 'dashed', width: 2 }
  if (label === 'catch') return { color: chartColors.danger, type: 'dotted', width: 1.5 }
  return { color: chartColors.neutral, type: 'solid', width: 1.2 }
}

const cfgData = computed(() => {
  if (!currentUnit.value?.cfgData) return null
  try {
    const parsed = JSON.parse(currentUnit.value.cfgData)
    return parsed.nodes?.length ? parsed : null
  } catch (e) {
    return null
  }
})

const renderCfg = () => {
  const el = document.getElementById('cfgChart')
  if (!el || !cfgData.value) return
  if (!cfgChart) cfgChart = echarts.init(el, chartThemeName())

  const nodes = cfgData.value.nodes.map((n) => {
    const style = CFG_NODE_STYLE[n.type] || CFG_NODE_STYLE.stmt
    return {
      id: String(n.id),
      name: n.label,
      symbol: style.symbol,
      symbolSize: style.size,
      itemStyle: { color: style.color },
      label: { show: true, formatter: '{b}', fontSize: 10, color: '#fff' }
    }
  })
  const links = cfgData.value.edges.map((e) => {
    const st = edgeStyle(e.label)
    return {
      source: String(e.from),
      target: String(e.to),
      value: e.label === 'seq' ? '' : e.label,
      lineStyle: { color: st.color, type: st.type, width: st.width, curveness: 0.15 }
    }
  })

  cfgChart.setOption({
    tooltip: { formatter: (p) => p.dataType === 'edge' ? `${p.data.source} → ${p.data.target} ${p.data.value}` : p.name },
    series: [{
      type: 'graph',
      layout: 'force',
      data: nodes,
      links: links,
      roam: true,
      draggable: true,
      edgeLabel: {
        show: true,
        formatter: '{c}',
        fontSize: 10,
        color: chartColors.neutral
      },
      force: {
        repulsion: 320,
        edgeLength: [70, 130],
        gravity: 0.08,
        layoutAnimation: false
      },
      labelLayout: { hideOverlap: true }
    }]
  }, true)
  cfgChart.resize()
}

watch(activeTab, async (tab) => {
  if (tab === 'cfg' && cfgData.value) {
    await nextTick()
    renderCfg()
  }
})

watch(currentUnit, () => {
  if (cfgChart) {
    cfgChart.dispose()
    cfgChart = null
  }
})

onChartThemeChange(() => { cfgChart?.dispose(); cfgChart = null; renderCfg() })

onMounted(() => {
  loadData()
})

onUnmounted(() => {
  // CQ-08：组件卸载销毁 CFG 图表实例
  cfgChart?.dispose()
})
</script>

<style scoped>
.code-list {
  max-height: 600px;
  overflow-y: auto;
}

.code-item {
  padding: 12px;
  border-radius: 4px;
  cursor: pointer;
  margin-bottom: 8px;
  border: 1px solid var(--tg-border);
  transition: all 0.2s;
}

.code-item:hover {
  background: var(--tg-bg-page);
}

.code-item.active {
  background: var(--el-color-primary-light-9);
  border-color: var(--tg-accent);
}

.class-name {
  font-weight: bold;
  color: var(--tg-text-primary);
}

.method-name {
  color: var(--tg-accent);
  margin-top: 4px;
}

.complexity-tag {
  margin-left: 6px;
}

.complexity-tip {
  margin-left: 8px;
  font-size: 12px;
  color: var(--tg-text-secondary);
}

.file-path {
  font-size: 12px;
  color: var(--tg-text-secondary);
  margin-top: 4px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.code-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.code-block {
  background: #1e1e1e;
  color: #d4d4d4;
  padding: 20px;
  border-radius: 6px;
  font-family: 'Consolas', 'Monaco', monospace;
  font-size: 13px;
  line-height: 1.6;
  max-height: 500px;
  overflow: auto;
  white-space: pre-wrap;
}

/* 4.8 整改：代码按行渲染 + 基础缺陷行内红标 */
.code-lines {
  margin: 0;
  padding: 12px 0;
}
.code-line {
  display: flex;
  padding: 0 16px;
  white-space: pre;
}
.code-line:hover {
  background: rgba(255, 255, 255, 0.04);
}
.code-line.defect-line {
  background: color-mix(in srgb, var(--tg-danger) 18%, transparent);
  box-shadow: inset 3px 0 0 var(--tg-danger);
}
.line-no {
  flex-shrink: 0;
  width: 48px;
  margin-right: 12px;
  text-align: right;
  color: var(--tg-text-secondary);
  user-select: none;
}
.defect-line .line-no {
  color: var(--tg-danger);
  font-weight: 600;
}
.line-text {
  flex: 1;
  white-space: pre;
}

.logic-text {
  padding: 15px;
  background: var(--tg-bg-page);
  border-radius: 4px;
  line-height: 1.8;
  white-space: pre-wrap;
}

.compare-pane {
  display: flex;
  gap: 12px;
}

.compare-col {
  flex: 1;
  min-width: 0;
}

.compare-col .code-block {
  max-height: 480px;
}

.compare-logic {
  max-height: 400px;
  overflow-y: auto;
  font-size: 14px;
}

.compare-title {
  font-weight: bold;
  font-size: 13px;
  color: var(--tg-text-secondary);
  margin-bottom: 8px;
  padding-left: 8px;
  border-left: 3px solid var(--tg-accent);
}

.compare-tip {
  margin-top: 10px;
  padding: 10px 12px;
  background: #fdf6ec;
  border-radius: 4px;
  color: #b88230;
  font-size: 12px;
  line-height: 1.6;
}

.cfg-wrapper {
  padding: 4px 0;
}

.cfg-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 14px;
  margin-bottom: 10px;
  padding: 8px 12px;
  background: var(--tg-bg-page);
  border-radius: 4px;
  font-size: 12px;
  color: var(--tg-text-secondary);
}

.legend-item {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
}

.dot-start { background: var(--tg-text-secondary); }
.dot-if { background: var(--tg-accent); }
.dot-loop { background: var(--tg-warning); }
.dot-catch { background: var(--tg-danger); }
.dot-jump { background: #B88230; }
.dot-stmt { background: #5470C6; }

.cfg-chart {
  width: 100%;
  height: 520px;
  border: 1px solid var(--tg-border);
  border-radius: 6px;
}

/* ===== 移动端适配（≤768px） ===== */
@media (max-width: 768px) {
  /* 需求-代码对比：并排会挤成两条极窄栏（内容不可读），改为上下堆叠 */
  .compare-pane {
    flex-direction: column;
    gap: 12px;
  }

  /* CFG 图高度降低，避免小屏上过长 */
  .cfg-chart {
    height: 360px;
  }

  /* 代码块高度收敛，配合弹窗内滚动 */
  .compare-col .code-block {
    max-height: 320px;
  }
}
</style>
