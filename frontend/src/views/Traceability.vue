<template>
  <div class="traceability-page">
    <el-page-header @back="$router.back()" content="需求-代码双向追溯矩阵" style="margin-bottom: 20px" />

    <el-card>
      <div style="display: flex; justify-content: space-between; align-items: center; margin-bottom: 15px">
        <el-alert type="info" :closable="false" style="flex: 1; margin-right: 15px">
          追溯矩阵实现需求条目到代码实现的双向关联追溯，支持需求正向追溯和代码反向追溯。
        </el-alert>
        <el-radio-group v-model="direction" style="margin-right: 12px">
          <el-radio-button label="forward">正向：需求 → 代码</el-radio-button>
          <el-radio-button label="reverse">反向：代码 → 需求</el-radio-button>
        </el-radio-group>
        <el-radio-group v-model="viewMode" style="margin-right: 12px">
          <el-radio-button label="table">表格视图</el-radio-button>
          <el-radio-button label="heatmap">热力图</el-radio-button>
          <!-- GAP-034：三维分项得分雷达图（语义相似度/约束匹配度/不变量满足度） -->
          <el-radio-button label="radar">三维雷达图</el-radio-button>
        </el-radio-group>
        <el-button type="success" :loading="exporting" @click="handleExport">
          <el-icon><Download /></el-icon> 导出Excel
        </el-button>
        <el-button type="info" plain :loading="printing" @click="handlePrint">
          <el-icon><Printer /></el-icon> 打印报告
        </el-button>
        <!-- 4.3 整改：追溯矩阵独立 PDF 导出入口 -->
        <el-button type="info" plain :loading="printingMatrix" @click="handlePrintMatrix">
          <el-icon><Printer /></el-icon> 导出矩阵PDF
        </el-button>
      </div>

      <div v-if="viewMode === 'table' && direction === 'forward'">
      <el-table :data="matrix" stripe border :loading="loadingPage">
        <el-table-column type="index" label="序号" width="60" :index="globalIndex" />
        <el-table-column prop="requirementId" label="需求ID" width="110" fixed />
        <el-table-column prop="requirementText" label="需求原文" min-width="280" show-overflow-tooltip />
        <el-table-column label="一致性状态" width="120">
          <template #default="{ row }">
            <el-tag v-if="row.status === 'covered' && row.consistencyStatus === 'consistent'" type="success" size="small">完全一致</el-tag>
            <el-tag v-else-if="row.status === 'covered' && row.consistencyStatus === 'general_inconsistent'" type="warning" size="small">一般不一致</el-tag>
            <el-tag v-else-if="row.status === 'covered' && row.consistencyStatus === 'serious_inconsistent'" type="danger" size="small">严重不一致</el-tag>
            <el-tag v-else-if="row.status === 'missing'" type="danger" size="small">需求缺失</el-tag>
            <el-tag v-else type="info" size="small">未覆盖</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="相似度" width="100">
          <template #default="{ row }">
            <span v-if="row.similarity != null">{{ (row.similarity * 100).toFixed(1) }}%</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="匹配代码位置" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.filePath">{{ row.className }}.{{ row.methodName }}（{{ row.filePath }} 第{{ row.startLine }}行）</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column label="缺陷等级" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.defectLevel === 'serious'" type="danger" size="small">严重</el-tag>
            <el-tag v-else-if="row.defectLevel === 'general'" type="warning" size="small">一般</el-tag>
            <span v-else style="color: #67C23A">无</span>
          </template>
        </el-table-column>
        <el-table-column label="修复建议" min-width="250" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.repairSuggestion || '-' }}
          </template>
        </el-table-column>
      </el-table>
      <div v-if="totalRecords > pageSize" class="pagination-wrapper">
        <el-pagination
          v-model:current-page="currentPage"
          v-model:page-size="pageSize"
          :total="totalRecords"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="handleSizeChange"
          @current-change="handlePageChange"
        />
      </div>
      </div>

      <div v-if="viewMode === 'table' && direction === 'reverse'">
        <el-table :data="matrix" stripe border :loading="loadingPage">
          <el-table-column type="index" label="序号" width="60" :index="globalIndex" />
          <el-table-column prop="filePath" label="文件路径" min-width="200" show-overflow-tooltip fixed />
          <el-table-column label="代码单元" width="200">
            <template #default="{ row }">{{ row.className }}.{{ row.methodName }}</template>
          </el-table-column>
          <el-table-column label="位置" width="90">
            <template #default="{ row }">第 {{ row.startLine || '-' }} 行</template>
          </el-table-column>
          <el-table-column label="追溯状态" width="120">
            <template #default="{ row }">
              <el-tag v-if="row.status === 'covered' && row.consistencyStatus === 'consistent'" type="success" size="small">完全一致</el-tag>
              <el-tag v-else-if="row.status === 'covered' && row.consistencyStatus === 'general_inconsistent'" type="warning" size="small">一般不一致</el-tag>
              <el-tag v-else-if="row.status === 'covered' && row.consistencyStatus === 'serious_inconsistent'" type="danger" size="small">严重不一致</el-tag>
              <el-tag v-else-if="row.status === 'extra'" type="danger" size="small">超范围实现</el-tag>
              <el-tag v-else type="info" size="small">未关联</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="最佳匹配需求" width="110">
            <template #default="{ row }">
              <span v-if="row.requirementId">{{ row.requirementId }}</span>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="需求原文" min-width="260" show-overflow-tooltip>
            <template #default="{ row }">{{ row.requirementText || '-' }}</template>
          </el-table-column>
          <el-table-column label="相似度" width="100">
            <template #default="{ row }">
              <span v-if="row.similarity != null">{{ (row.similarity * 100).toFixed(1) }}%</span>
              <span v-else>-</span>
            </template>
          </el-table-column>
          <el-table-column label="修复建议" min-width="250" show-overflow-tooltip>
            <template #default="{ row }">
              {{ row.repairSuggestion || '-' }}
            </template>
          </el-table-column>
        </el-table>
        <div v-if="totalRecords > pageSize" class="pagination-wrapper">
          <el-pagination
            v-model:current-page="currentPage"
            v-model:page-size="pageSize"
            :total="totalRecords"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @size-change="handleSizeChange"
            @current-change="handlePageChange"
          />
        </div>
      </div>

      <div v-else-if="viewMode === 'heatmap'" class="heatmap-wrapper">
        <div id="traceHeatmap" class="heatmap-chart" />
        <div v-if="!hasHeatmapData" class="heatmap-empty">暂无一致性校验数据，无法生成热力图</div>
        <el-alert v-if="hasHeatmapData && heatmapSampled" type="warning" :closable="false" show-icon style="margin-bottom: 8px">
          数据量较大，热力图已进行采样展示（最多 8000 个点位），如需完整明细请使用表格视图或导出 Excel。
        </el-alert>
        <div class="heatmap-legend">
          <span>相似度低(不一致)</span>
          <span class="gradient-bar"></span>
          <span>相似度高(一致)</span>
        </div>
      </div>

      <!-- GAP-034：三维分项得分雷达图（α/β/γ）+ 明细表 -->
      <div v-if="viewMode === 'radar'" class="radar-wrapper">
        <el-alert type="info" :closable="false" style="margin-bottom: 12px">
          三维分项得分雷达图展示需求-代码配对在语义相似度(α)/约束匹配度(β)/不变量满足度(γ)三个维度的得分；
          点击下方明细表行切换展示记录，虚线圆环为一致性分级阈值 T1（完全一致）/T2（严重不一致）。
        </el-alert>
        <div class="radar-chart-wrapper">
          <div id="radarChart" class="radar-chart" />
          <div v-if="!radarHasData" class="radar-empty">暂无一致性校验数据，无法生成雷达图</div>
          <div v-else-if="!selectedRadar.available" class="radar-empty">当前记录三维分项得分缺失（GAP-005 未落地或已降级），暂无可展示分项得分，请选择其他记录</div>
        </div>
        <div v-if="radarHasData && selectedRadar.available" class="radar-legend">
          <span><span class="legend-line solid"></span>当前选中（{{ selectedRadar.reqLabel }} × {{ selectedRadar.unitLabel }}）</span>
          <span><span class="legend-line dashed-green"></span>T1 完全一致阈值（{{ thresholdT1 }}%）</span>
          <span><span class="legend-line dashed-red"></span>T2 严重不一致阈值（{{ thresholdT2 }}%）</span>
        </div>
        <el-table :data="radarPageRecords" stripe border size="small" highlight-current-row style="margin-top: 12px" :row-class-name="radarRowClassName" @row-click="onRadarRowClick">
          <el-table-column type="index" label="序号" width="60" :index="radarGlobalIndex" />
          <el-table-column prop="reqLabel" label="需求ID" width="110" />
          <el-table-column prop="unitLabel" label="代码单元" min-width="200" show-overflow-tooltip />
          <el-table-column label="语义相似度(α)" width="120">
            <template #default="{ row }">{{ formatScore(row.semanticSimilarity) }}</template>
          </el-table-column>
          <el-table-column label="约束匹配度(β)" width="120">
            <template #default="{ row }">{{ formatScore(row.constraintMatchDegree) }}</template>
          </el-table-column>
          <el-table-column label="不变量满足度(γ)" width="130">
            <template #default="{ row }">{{ formatScore(row.invariantSatisfaction) }}</template>
          </el-table-column>
          <el-table-column label="综合相似度" width="110">
            <template #default="{ row }">{{ formatScore(row.totalSimilarity) }}</template>
          </el-table-column>
          <el-table-column label="一致性状态" width="120">
            <template #default="{ row }">
              <el-tag :type="statusTagType(row.consistencyStatus)" size="small">{{ statusLabel(row.consistencyStatus) }}</el-tag>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="radarTotal > radarPageSize" class="pagination-wrapper">
          <el-pagination
            v-model:current-page="radarPage"
            v-model:page-size="radarPageSize"
            :total="radarTotal"
            :page-sizes="[10, 20, 50, 100]"
            layout="total, sizes, prev, pager, next, jumper"
            background
            @size-change="handleRadarSizeChange"
            @current-change="handleRadarPageChange"
          />
        </div>
      </div>

      <el-divider />

      <div class="legend">
        <h4>图例说明：</h4>
        <div class="legend-items">
          <span><el-tag type="success" size="small">完全一致</el-tag> 需求与代码实现匹配度高，满足要求</span>
          <span><el-tag type="warning" size="small">一般不一致</el-tag> 需求与代码存在一定偏差，建议检查</span>
          <span><el-tag type="danger" size="small">严重不一致</el-tag> 需求与代码存在明显偏差，需要修复</span>
          <span><el-tag type="danger" size="small">需求缺失</el-tag> 需求在代码中未找到对应实现（正向追溯）</span>
          <span><el-tag type="danger" size="small">超范围实现</el-tag> 代码方法未对应到任何需求条目（反向追溯）</span>
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, nextTick, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { resultApi, exportApi, analysisApi } from '@/api'
import { printReportPdf } from '@/utils/print'

const route = useRoute()
const projectId = route.params.id
const printing = ref(false)
const matrix = ref([])
const exporting = ref(false)
const viewMode = ref('table')
// 追溯方向：forward=需求->代码（正向） reverse=代码->需求（反向）
const direction = ref('forward')
const hasHeatmapData = ref(false)
const heatmapSampled = ref(false)
let heatmapChart = null
// 热力图原始数据缓存 {reqAxis, unitAxis, data}
let heatmapDataset = null

// GAP-034：三维分项得分雷达图状态
const radarRecords = ref([])          // 明细表全量记录 {reqLabel, unitLabel, semanticSimilarity, constraintMatchDegree, invariantSatisfaction, totalSimilarity, consistencyStatus}
const radarPage = ref(1)              // 明细表当前页
const radarPageSize = ref(20)         // 明细表每页条数
const radarTotal = ref(0)             // 明细表总条数
const selectedRadarIdx = ref(0)       // 雷达图当前选中记录下标（对应 radarRecords）
const radarHasData = ref(false)       // 是否存在一致性记录
const thresholdT1 = ref(80)           // 一致性分级阈值 T1（百分比，默认 0.8）
const thresholdT2 = ref(50)           // 一致性分级阈值 T2（百分比，默认 0.5）
let radarChart = null

// GAP-026：分页配置（仅表格视图使用远程分页）
const currentPage = ref(1)
const pageSize = ref(20)
const totalRecords = ref(0)
const loadingPage = ref(false)

/** 按当前方向加载矩阵数据（表格视图使用远程分页） */
const loadMatrix = async () => {
  if (viewMode.value === 'heatmap') {
    // 热力图需要全量数据，不走分页
    matrix.value = direction.value === 'reverse'
      ? await resultApi.getReverseTraceability(projectId)
      : await resultApi.getTraceability(projectId)
    return
  }
  // GAP-034：雷达图仅需一致性分项数据，无需加载矩阵
  if (viewMode.value === 'radar') {
    return
  }
  
  // GAP-026：表格视图使用远程分页
  loadingPage.value = true
  try {
    let res
    if (direction.value === 'reverse') {
      res = await resultApi.getReverseTraceabilityPage(projectId, currentPage.value, pageSize.value)
    } else {
      res = await resultApi.getTraceabilityPage(projectId, currentPage.value, pageSize.value)
    }
    // Element Plus IPage 格式: {records, total, current, size, pages}
    matrix.value = (res.records || res.list || res) || []
    totalRecords.value = res.total || 0
  } catch (e) {
    console.error('加载追溯矩阵失败:', e)
    ElMessage.error('加载追溯矩阵失败')
    matrix.value = []
    totalRecords.value = 0
  } finally {
    loadingPage.value = false
  }
}

/** 处理页码变化 */
const handlePageChange = (page) => {
  currentPage.value = page
  loadMatrix()
}

/** 处理每页条数变化 */
const handleSizeChange = (size) => {
  pageSize.value = size
  currentPage.value = 1
  loadMatrix()
}

/** GAP-026：分页后序号显示为全局序号 */
const globalIndex = (index) => (currentPage.value - 1) * pageSize.value + index + 1

const loadData = async () => {
  await loadMatrix()
  // GAP-034：一次拉取需求/代码/一致性数据，供热力图与雷达图共用
  const [reqs, units, consistency] = await Promise.all([
    resultApi.getRequirements(projectId),
    resultApi.getCodeUnits(projectId),
    resultApi.getConsistency(projectId, null)
  ])
  heatmapDataset = buildHeatmapData(reqs, units, consistency)
  hasHeatmapData.value = heatmapDataset.data.length > 0
  heatmapSampled.value = !!heatmapDataset.sampled
  // 当前已处于热力图视图则直接渲染
  if (viewMode.value === 'heatmap') renderHeatmap(heatmapDataset)

  // GAP-034：构建雷达图明细数据并加载分级阈值
  buildRadarRecords(reqs, units, consistency)
  await loadRadarThresholds()
  if (viewMode.value === 'radar') renderRadar()
}

watch(viewMode, async (mode) => {
  if (mode === 'heatmap' && heatmapDataset) {
    await nextTick()
    if (!heatmapChart) renderHeatmap(heatmapDataset)
  }
  // GAP-034：切到雷达视图时渲染
  if (mode === 'radar') {
    await nextTick()
    renderRadar()
  }
})

// GAP-026：方向切换时重置分页
watch(direction, () => {
  if (viewMode.value === 'table') {
    currentPage.value = 1
    totalRecords.value = 0
    loadMatrix()
  }
})

/** GAP-026：构建热力图数据集，支持大数据量采样（数据由 loadData 统一拉取） */
const buildHeatmapData = (reqs, units, consistency) => {
  const reqNameMap = {}
  ;(reqs || []).forEach(r => { reqNameMap[r.id] = r.requirementId })
  const unitNameMap = {}
  ;(units || []).forEach(u => { unitNameMap[u.id] = `${u.className}.${u.methodName}` })

  // y轴：需求编号（按出现顺序去重），x轴：代码单元名
  const reqAxis = []
  const unitAxis = []
  const reqIndex = {}
  const unitIndex = {}
  const rawData = []
  ;(consistency || []).forEach(c => {
    if (c.totalSimilarity == null) return
    const reqLabel = reqNameMap[c.requirementId] || `REQ#${c.requirementId}`
    const unitLabel = unitNameMap[c.codeUnitId] || `CODE#${c.codeUnitId}`
    if (reqIndex[reqLabel] === undefined) {
      reqIndex[reqLabel] = reqAxis.length
      reqAxis.push(reqLabel)
    }
    if (unitIndex[unitLabel] === undefined) {
      unitIndex[unitLabel] = unitAxis.length
      unitAxis.push(unitLabel)
    }
    rawData.push([unitIndex[unitLabel], reqIndex[reqLabel], Number((c.totalSimilarity * 100).toFixed(1))])
  })
  
  // GAP-026：大数据量采样（最多保留 8000 个点，按值分桶随机抽样）
  const MAX_DATA_POINTS = 8000
  let data = rawData
  if (rawData.length > MAX_DATA_POINTS) {
    // 使用步长均匀采样（保证 x,y 轴分布覆盖）
    const step = Math.ceil(rawData.length / MAX_DATA_POINTS)
    data = rawData.filter((_, i) => i % step === 0)
    console.warn(`GAP-026: 热力图数据量 ${rawData.length} 超阈值，已采样至 ${data.length} 个点`)
  }
  
  return { reqAxis, unitAxis, data, sampled: data.length < rawData.length }
}

/** 渲染热力图 */
const renderHeatmap = ({ reqAxis, unitAxis, data, sampled }) => {
  const el = document.getElementById('traceHeatmap')
  if (!el) return
  heatmapChart = echarts.init(el)
  heatmapChart.setOption({
    tooltip: {
      position: 'top',
      formatter: (p) => `${reqAxis[p.value[1]]} × ${unitAxis[p.value[0]]}<br/>综合相似度: ${p.value[2]}%`
    },
    toolbox: {
      show: true,
      right: 20,
      feature: { saveAsImage: { title: '导出图片', name: '追溯矩阵热力图' } }
    },
    grid: { top: 10, left: 120, right: 30, bottom: 90 },
    xAxis: {
      type: 'category',
      data: unitAxis,
      axisLabel: { rotate: 45, fontSize: 10, interval: 0 },
      splitArea: { show: true }
    },
    yAxis: {
      type: 'category',
      data: reqAxis,
      axisLabel: { fontSize: 10 },
      splitArea: { show: true }
    },
    visualMap: {
      min: 0,
      max: 100,
      calculable: true,
      orient: 'horizontal',
      left: 'center',
      bottom: 0,
      inRange: { color: ['#F56C6C', '#E6A23C', '#67C23A'] }
    },
    dataZoom: [
      { type: 'slider', xAxisIndex: 0, height: 16, bottom: 36 },
      { type: 'inside', xAxisIndex: 0 }
    ],
    series: [{
      name: '综合相似度',
      type: 'heatmap',
      data,
      label: { show: false },
      emphasis: { itemStyle: { shadowBlur: 6, shadowColor: 'rgba(0,0,0,0.4)' } }
    }]
  })
}

// ==================== GAP-034：三维分项得分雷达图 ====================

/** 构建雷达图明细记录（α/β/γ 三维得分 + 综合相似度 + 一致性状态） */
const buildRadarRecords = (reqs, units, consistency) => {
  const reqNameMap = {}
  ;(reqs || []).forEach(r => { reqNameMap[r.id] = r.requirementId })
  const unitNameMap = {}
  ;(units || []).forEach(u => { unitNameMap[u.id] = `${u.className}.${u.methodName}` })
  const records = []
  ;(consistency || []).forEach(c => {
    records.push({
      reqLabel: reqNameMap[c.requirementId] || `REQ#${c.requirementId}`,
      unitLabel: unitNameMap[c.codeUnitId] || `CODE#${c.codeUnitId}`,
      semanticSimilarity: c.semanticSimilarity,
      constraintMatchDegree: c.constraintMatchDegree,
      invariantSatisfaction: c.invariantSatisfaction,
      totalSimilarity: c.totalSimilarity,
      consistencyStatus: c.consistencyStatus
    })
  })
  radarRecords.value = records
  radarTotal.value = records.length
  radarHasData.value = records.length > 0
  // 默认选中第一条含分项得分的记录（无分项时留 0，由 selectedRadar.available 提示）
  const firstAvailable = records.findIndex(r =>
    r.semanticSimilarity != null || r.constraintMatchDegree != null || r.invariantSatisfaction != null)
  selectedRadarIdx.value = firstAvailable >= 0 ? firstAvailable : 0
}

/** 从最新分析任务读取 T1/T2 分级阈值（失败降级为系统默认 80%/50%） */
const loadRadarThresholds = async () => {
  try {
    const res = await analysisApi.listTasks(projectId, { pageNum: 1, pageSize: 1 })
    const latest = (res.records || res.list || [])[0]
    if (latest && latest.thresholdT1 != null) thresholdT1.value = Number((latest.thresholdT1 * 100).toFixed(1))
    if (latest && latest.thresholdT2 != null) thresholdT2.value = Number((latest.thresholdT2 * 100).toFixed(1))
  } catch (e) {
    console.warn('GAP-034: 加载任务阈值失败，使用系统默认值 T1=80%/T2=50%', e)
  }
}

/** 明细表当前页数据（前端分页切片） */
const radarPageRecords = computed(() => {
  const start = (radarPage.value - 1) * radarPageSize.value
  return radarRecords.value.slice(start, start + radarPageSize.value)
})

/** 当前选中记录（含百分比分项得分；available 标识是否有分项数据） */
const selectedRadar = computed(() => {
  const rec = radarRecords.value[selectedRadarIdx.value] || null
  if (!rec) return { available: false, reqLabel: '-', unitLabel: '-' }
  const hasAny = rec.semanticSimilarity != null || rec.constraintMatchDegree != null || rec.invariantSatisfaction != null
  return {
    ...rec,
    available: hasAny,
    alpha: rec.semanticSimilarity != null ? Number((rec.semanticSimilarity * 100).toFixed(1)) : null,
    beta: rec.constraintMatchDegree != null ? Number((rec.constraintMatchDegree * 100).toFixed(1)) : null,
    gamma: rec.invariantSatisfaction != null ? Number((rec.invariantSatisfaction * 100).toFixed(1)) : null
  }
})

/** 明细表分页后全局序号 */
const radarGlobalIndex = (index) => (radarPage.value - 1) * radarPageSize.value + index + 1

/** 明细表行点击：切换雷达图展示记录 */
const onRadarRowClick = (row) => {
  const idx = radarRecords.value.indexOf(row)
  if (idx >= 0) {
    selectedRadarIdx.value = idx
    renderRadar()
  }
}

/** 明细表选中行高亮样式 */
const radarRowClassName = ({ row }) => {
  return radarRecords.value[selectedRadarIdx.value] === row ? 'radar-selected-row' : ''
}

const handleRadarPageChange = (page) => {
  radarPage.value = page
}

const handleRadarSizeChange = (size) => {
  radarPageSize.value = size
  radarPage.value = 1
}

/** 得分格式化（0-1 转百分比；空值显示 -） */
const formatScore = (v) => v == null ? '-' : `${(v * 100).toFixed(1)}%`

/** 一致性状态标签类型 */
const statusTagType = (status) => {
  if (status === 'consistent') return 'success'
  if (status === 'general_inconsistent') return 'warning'
  if (status === 'serious_inconsistent') return 'danger'
  return 'info'
}

/** 一致性状态中文标签 */
const statusLabel = (status) => {
  if (status === 'consistent') return '完全一致'
  if (status === 'general_inconsistent') return '一般不一致'
  if (status === 'serious_inconsistent') return '严重不一致'
  return status || '-'
}

/** 雷达图配置（α/β/γ 三维 + T1/T2 阈值虚线环） */
const buildRadarOption = () => {
  const s = selectedRadar.value
  const t1 = thresholdT1.value
  const t2 = thresholdT2.value
  return {
    tooltip: {
      trigger: 'item',
      formatter: (params) => {
        if (!params || !params.name) return ''
        if (params.name === '当前选中') {
          return `${s.reqLabel} × ${s.unitLabel}<br/>`
            + `语义相似度(α): ${s.alpha}%<br/>`
            + `约束匹配度(β): ${s.beta}%<br/>`
            + `不变量满足度(γ): ${s.gamma}%`
        }
        return `${params.name}: ${params.value[0]}%`
      }
    },
    legend: { bottom: 0, data: ['当前选中', 'T1 阈值', 'T2 阈值'] },
    radar: {
      indicator: [
        { name: '语义相似度', max: 100 },
        { name: '约束匹配度', max: 100 },
        { name: '不变量满足度', max: 100 }
      ],
      radius: '65%',
      axisName: { color: '#606266' },
      splitArea: { areaStyle: { color: ['rgba(64,158,255,0.03)', 'rgba(64,158,255,0.06)'] } }
    },
    series: [
      {
        type: 'radar',
        symbolSize: 5,
        data: [{
          value: [s.alpha, s.beta, s.gamma],
          name: '当前选中',
          lineStyle: { color: '#409EFF', width: 2 },
          itemStyle: { color: '#409EFF' },
          areaStyle: { color: 'rgba(64,158,255,0.25)' }
        }]
      },
      {
        type: 'radar',
        data: [
          { value: [t1, t1, t1], name: 'T1 阈值', symbol: 'none', lineStyle: { type: 'dashed', color: '#67C23A' }, areaStyle: { opacity: 0 } },
          { value: [t2, t2, t2], name: 'T2 阈值', symbol: 'none', lineStyle: { type: 'dashed', color: '#F56C6C' }, areaStyle: { opacity: 0 } }
        ]
      }
    ]
  }
}

/** 渲染雷达图；分项数据缺失时置灰提示不报错 */
const renderRadar = () => {
  const el = document.getElementById('radarChart')
  if (!el) return
  if (!selectedRadar.value.available) {
    if (radarChart) {
      radarChart.dispose()
      radarChart = null
    }
    return
  }
  if (!radarChart) radarChart = echarts.init(el)
  radarChart.setOption(buildRadarOption(), true)
}

const handleExport = async () => {
  exporting.value = true
  try {
    if (direction.value === 'reverse') {
      await exportApi.traceabilityReverseExcel(projectId)
    } else {
      await exportApi.traceabilityExcel(projectId)
    }
    ElMessage.success('导出成功')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  }
}

/** GAP-038：直接打印追溯报告 */
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

/** 4.3 整改：追溯矩阵独立 PDF 导出（矩阵为报告主体，独立入口） */
const printingMatrix = ref(false)
const handlePrintMatrix = async () => {
  printingMatrix.value = true
  try {
    await printReportPdf(projectId)
    ElMessage.success('已唤起打印对话框（追溯矩阵）')
  } catch (e) {
    ElMessage.error(e.message || '打印失败')
  } finally {
    printingMatrix.value = false
  }
}

onMounted(() => {
  loadData()
})

onUnmounted(() => {
  // CQ-08：组件卸载销毁热力图/雷达图实例
  heatmapChart?.dispose()
  radarChart?.dispose()
})
</script>

<style scoped>
.heatmap-wrapper {
  position: relative;
  min-height: 420px;
}

.heatmap-chart {
  width: 100%;
  height: 420px;
}

.heatmap-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
}

.heatmap-legend {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  color: #666;
  font-size: 12px;
  margin-top: 8px;
}

.gradient-bar {
  display: inline-block;
  width: 160px;
  height: 10px;
  border-radius: 5px;
  background: linear-gradient(to right, #F56C6C, #E6A23C, #67C23A);
}

/* GAP-034：三维分项得分雷达图样式 */
.radar-wrapper {
  position: relative;
  min-height: 420px;
}

.radar-chart-wrapper {
  position: relative;
  min-height: 380px;
}

.radar-chart {
  width: 100%;
  height: 380px;
}

.radar-empty {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #909399;
  background: rgba(0, 0, 0, 0.02);
}

.radar-legend {
  display: flex;
  align-items: center;
  justify-content: center;
  flex-wrap: wrap;
  gap: 16px;
  color: #666;
  font-size: 12px;
  margin-top: 8px;
}

.legend-line {
  display: inline-block;
  width: 24px;
  height: 2px;
  vertical-align: middle;
  margin-right: 4px;
}

.legend-line.solid {
  background: #409EFF;
}

.legend-line.dashed-green {
  border-top: 2px dashed #67C23A;
  background: transparent;
}

.legend-line.dashed-red {
  border-top: 2px dashed #F56C6C;
  background: transparent;
}

.radar-selected-row {
  background: #ecf5ff !important;
}

.legend h4 {
  margin: 0 0 10px 0;
}

.legend-items {
  display: flex;
  flex-direction: column;
  gap: 8px;
  color: #666;
}

/* GAP-026：分页组件样式 */
.pagination-wrapper {
  display: flex;
  justify-content: flex-end;
  padding: 16px 0 0;
}
</style>
