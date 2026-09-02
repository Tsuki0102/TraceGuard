<template>
  <div class="audit-page">
    <!-- ===== 页头：标题 + 搜索 + 操作 ===== -->
    <div class="page-header tg-fade-up">
      <div>
        <div class="page-header__back">
          <el-button link class="page-header__back-btn" @click="$router.back()">
            <el-icon><ArrowLeft /></el-icon> 返回
          </el-button>
        </div>
        <div class="page-header__greet">
          <el-icon class="page-header__greet-icon"><Lock /></el-icon>
          Security &amp; Compliance
        </div>
        <h2 class="page-header__title">操作审计日志</h2>
        <p class="page-header__desc">查看用户操作记录，支持搜索、导出与哈希链完整性校验</p>
      </div>
      <div class="page-header__actions">
        <el-button round @click="loadData">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon> 刷新
        </el-button>
        <el-button type="warning" round :loading="verifying" @click="verifyChain">
          <el-icon style="margin-right: 4px"><Lock /></el-icon> 校验哈希链
        </el-button>
        <el-button type="success" round :loading="exporting" @click="exportExcel">
          <el-icon style="margin-right: 4px"><Download /></el-icon> 导出Excel
        </el-button>
      </div>
    </div>

    <!-- ===== KPI 统计行 ===== -->
    <div class="kpi-row">
      <div class="kpi-card tg-fade-up">
        <div class="kpi-card__tile kpi-card__tile--gold"><el-icon :size="22"><Tickets /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num"><span class="tg-count">{{ totalDisp }}</span></div>
          <div class="kpi-card__label">记录总数</div>
          <div class="kpi-card__meta">审计事件（当前页 {{ records.length }}）</div>
        </div>
      </div>
      <div class="kpi-card tg-fade-up" style="animation-delay: 60ms">
        <div class="kpi-card__tile kpi-card__tile--green"><el-icon :size="22"><CircleCheck /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num"><span class="tg-count">{{ successDisp }}</span></div>
          <div class="kpi-card__label">成功</div>
          <div class="kpi-card__meta">当前页 · 请求成功</div>
        </div>
      </div>
      <div class="kpi-card tg-fade-up" style="animation-delay: 120ms">
        <div class="kpi-card__tile kpi-card__tile--coral"><el-icon :size="22"><CircleClose /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num"><span class="tg-count">{{ failDisp }}</span></div>
          <div class="kpi-card__label">失败</div>
          <div class="kpi-card__meta">当前页 · 请求失败</div>
        </div>
      </div>
      <div class="kpi-card tg-fade-up" style="animation-delay: 180ms">
        <div class="kpi-card__tile kpi-card__tile--amber"><el-icon :size="22"><Warning /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num"><span class="tg-count">{{ errorDisp }}</span></div>
          <div class="kpi-card__label">异常状态码</div>
          <div class="kpi-card__meta">响应状态 ≥ 400</div>
        </div>
      </div>
    </div>

    <!-- ===== W4：操作分布双图（基于当前页记录实时统计） ===== -->
    <div class="audit-charts">
      <section class="chart-card tg-fade-up">
        <div class="chart-card__head">
          <h4><el-icon :size="15" class="section-title__ic"><Histogram /></el-icon>操作类型分布</h4>
          <span class="live-badge">操作 {{ opDist.length }} 类</span>
        </div>
        <div ref="opChartEl" class="chart-card__body"></div>
      </section>
      <section class="chart-card tg-fade-up">
        <div class="chart-card__head">
          <h4><el-icon :size="15" class="section-title__ic"><Connection /></el-icon>请求方法分布</h4>
          <span class="live-badge">方法 {{ methodDist.length }} 种</span>
        </div>
        <div ref="methodChartEl" class="chart-card__body"></div>
      </section>
    </div>

    <!-- ===== 审计记录面板 ===== -->
    <section ref="panelRef" class="table-panel tg-fade-up">
      <div class="table-panel__head">
        <div class="table-panel__title">
          <h3><el-icon class="section-title__ic" :size="17"><DataLine /></el-icon>审计记录</h3>
          <p>共 {{ total }} 条记录，哈希链防篡改</p>
        </div>
        <div class="table-panel__tools">
          <span v-if="isAdmin" class="mini-pill mini-pill--green">哈希链已启用</span>
        </div>
      </div>

      <el-alert
        v-if="verifyResult"
        :title="verifyResult.valid ? '哈希链校验通过：未发现篡改' : '哈希链校验异常：检测到记录被篡改'"
        :description="verifyDescription"
        :type="verifyResult.valid ? 'success' : 'error'"
        :closable="true"
        show-icon
        style="margin-bottom: 16px"
      />

      <!-- W1-05/R14：统一搜索表单（展开/收起；操作类型、结果先按当前页本地过滤） -->
      <TgSearchBar
        :fields="searchFields"
        :collapse-count="2"
        @search="onSearch"
        @reset="onResetSearch"
      />

      <el-table :data="filteredRecords" v-loading="loading" class="manage-table">
        <template #empty>
          <EmptyArt text="暂无审计记录" />
        </template>
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column label="用户" width="120">
          <template #default="{ row }">
            <div class="audit-user">
              <span class="tg-avatar tg-avatar--xs">{{ (row.username || '?').slice(0, 1).toUpperCase() }}</span>
              <span class="audit-user__name">{{ row.username || '-' }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="110">
          <template #default="{ row }">
            <span class="mini-pill" :class="'mini-pill--' + opTone(row.operation)">{{ row.operation }}</span>
          </template>
        </el-table-column>
        <el-table-column label="请求" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <span class="mini-pill" :class="'mini-pill--' + methodTone(row.method)" style="margin-right: 6px">{{ row.method }}</span>
            <span class="audit-path">{{ row.path }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态码" width="90" align="center">
          <template #default="{ row }">
            <span
              class="status-code"
              :class="row.statusCode < 400 ? 'status-code--ok' : 'status-code--err'"
            >{{ row.statusCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="90" align="center">
          <template #default="{ row }">
            <span class="mini-pill" :class="row.success ? 'mini-pill--green' : 'mini-pill--coral'">
              {{ row.success ? '成功' : '失败' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="90">
          <template #default="{ row }"><span class="num-cell">{{ row.costMs }}ms</span></template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.ip || '-' }}</template>
        </el-table-column>
        <el-table-column prop="errorMsg" label="错误信息" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.errorMsg || '-' }}</template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        style="margin-top: 16px; justify-content: flex-end"
        @size-change="loadData"
        @current-change="loadData"
      />
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Lock } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { chartThemeName, chartText, chartFaint, chartAxisLine, chartSplitLine, chartTooltipBg, chartTitleColor, onChartThemeChange } from '@/utils/echartsTheme'
import { auditApi } from '@/api'
import { UserContext } from '@/store/user'
import { useCountUp } from '@/composables/useCountUp'
import TgSearchBar from '@/components/TgSearchBar.vue'
import EmptyArt from '@/components/EmptyArt.vue'
import { setupWatermark } from '@/composables/useWatermark'

const records = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const loading = ref(false)
const exporting = ref(false)
const verifying = ref(false)
const verifyResult = ref(null)
const isAdmin = computed(() => UserContext.isAdmin())

// ===== W1-05/R14：统一搜索表单（keyword 走后端查询；类型/结果先按当前页本地过滤） =====
const searchParams = ref({})
const searchFields = [
  { key: 'keyword', label: '用户/操作/路径', type: 'input', width: 220 },
  {
    key: 'operation',
    label: '操作类型',
    type: 'select',
    width: 150,
    options: ['登录', '注册', '创建', '上传', '分析', '导出', '备份', '更新', '删除', '终止'].map((v) => ({ label: v, value: v }))
  },
  {
    key: 'success',
    label: '结果',
    type: 'select',
    width: 120,
    options: [
      { label: '成功', value: 'success' },
      { label: '失败', value: 'fail' }
    ]
  }
]

const onSearch = (params) => {
  searchParams.value = params
  keyword.value = params.keyword || ''
  pageNum.value = 1
  loadData()
}

const onResetSearch = () => {
  searchParams.value = {}
  keyword.value = ''
  pageNum.value = 1
  loadData()
}

/** 当前页本地过滤（操作类型/结果），keyword 仍走后端查询 */
const filteredRecords = computed(() => {
  let list = records.value
  const op = searchParams.value.operation
  if (op) list = list.filter((r) => (r.operation || '').includes(op))
  const ok = searchParams.value.success
  if (ok === 'success') list = list.filter((r) => r.success)
  if (ok === 'fail') list = list.filter((r) => !r.success)
  return list
})

const successCount = computed(() => records.value.filter(r => r.success).length)
const failCount = computed(() => records.value.filter(r => !r.success).length)

// ===== W4：操作分布双图（基于当前页过滤后记录实时统计） =====
const opChartEl = ref(null)
const methodChartEl = ref(null)
let opChart = null
let methodChart = null

const opDist = computed(() => {
  const counts = {}
  filteredRecords.value.forEach((r) => {
    const op = r.operation || '未知'
    counts[op] = (counts[op] || 0) + 1
  })
  return Object.entries(counts)
    .sort((a, b) => b[1] - a[1])
    .slice(0, 6)
})

const methodDist = computed(() => {
  const counts = {}
  filteredRecords.value.forEach((r) => {
    const m = r.method || 'GET'
    counts[m] = (counts[m] || 0) + 1
  })
  return Object.entries(counts).sort((a, b) => b[1] - a[1])
})

const CHART_TOOLTIP = {
  backgroundColor: 'rgba(43,36,28,.92)',
  borderColor: 'rgba(201,155,63,.4)',
  textStyle: { color: '#f4ead6' }
}

const renderAuditCharts = () => {
  // 操作类型：横向渐变条形
  if (opChartEl.value) {
    if (!opChart) opChart = echarts.init(opChartEl.value, chartThemeName())
    opChart.setOption(
      {
        animationDuration: 500,
        tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, ...CHART_TOOLTIP },
        grid: { left: 6, right: 28, top: 6, bottom: 6, containLabel: true },
        xAxis: {
          type: 'value',
          splitLine: { lineStyle: { color: 'rgba(201,155,63,.1)' } },
          axisLabel: { color: 'var(--tg-text-secondary)', fontSize: 11 }
        },
        yAxis: {
          type: 'category',
          data: opDist.value.map((d) => d[0]),
          axisLine: { show: false },
          axisTick: { show: false },
          axisLabel: { color: 'var(--tg-text-primary)', fontSize: 12, fontWeight: 500 }
        },
        series: [
          {
            type: 'bar',
            data: opDist.value.map((d) => d[1]),
            barWidth: 12,
            showBackground: true,
            backgroundStyle: { color: 'rgba(201,155,63,.08)', borderRadius: [0, 8, 8, 0] },
            itemStyle: {
              borderRadius: [0, 8, 8, 0],
              color: new echarts.graphic.LinearGradient(0, 0, 1, 0, [
                { offset: 0, color: '#D9A966' },
                { offset: 1, color: '#C99B3F' }
              ])
            }
          }
        ]
      },
      true
    )
  }
  // 请求方法：环形图
  if (methodChartEl.value) {
    if (!methodChart) methodChart = echarts.init(methodChartEl.value, chartThemeName())
    methodChart.setOption(
      {
        animationDuration: 500,
        tooltip: { trigger: 'item', formatter: '{b}：{c} 次（{d}%）', ...CHART_TOOLTIP },
        legend: {
          bottom: 0,
          left: 'center',
          icon: 'circle',
          itemWidth: 8,
          itemHeight: 8,
          textStyle: { color: 'var(--tg-text-secondary)', fontSize: 11 }
        },
        series: [
          {
            type: 'pie',
            radius: ['50%', '76%'],
            center: ['50%', '44%'],
            padAngle: 2,
            itemStyle: { borderRadius: 6, borderColor: 'rgba(255,244,224,.9)', borderWidth: 2 },
            label: { show: false },
            emphasis: { scaleSize: 5, itemStyle: { shadowBlur: 14, shadowColor: 'rgba(201,155,63,.4)' } },
            data: methodDist.value.map(([name, count]) => ({ name, value: count }))
          }
        ]
      },
      true
    )
  }
}

const onChartsResize = () => {
  if (opChart) opChart.resize()
  if (methodChart) methodChart.resize()
}

watch(filteredRecords, () => nextTick(renderAuditCharts))
const errorCount = computed(() => records.value.filter(r => Number(r.statusCode || 0) >= 400).length)
const verifyDescription = computed(() => {
  if (!verifyResult.value) return ''
  const r = verifyResult.value
  if (r.valid) return `共校验 ${r.total} 条审计记录，哈希链完整无篡改。`
  return `共校验 ${r.total} 条记录，首条异常位于第 ${r.firstBrokenIndex} 条（ID=${r.firstBrokenId}，时间=${r.firstBrokenTime}）。`
})

// ===== KPI 数字滚动 =====
const totalDisp = useCountUp(computed(() => total.value))
const successDisp = useCountUp(computed(() => successCount.value))
const failDisp = useCountUp(computed(() => failCount.value))
const errorDisp = useCountUp(computed(() => errorCount.value))

const exportExcel = async () => {
  exporting.value = true
  try {
    await auditApi.exportExcel(keyword.value || undefined)
    ElMessage.success('导出成功')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

/** AUD-08：校验审计日志哈希链完整性 */
const verifyChain = async () => {
  if (!isAdmin.value) {
    ElMessage.warning('仅管理员可校验审计日志')
    return
  }
  verifying.value = true
  try {
    const res = await auditApi.verify()
    verifyResult.value = res
    if (res.valid) {
      ElMessage.success('哈希链校验通过')
    } else {
      ElMessage.error('检测到审计记录被篡改，请查看详情')
    }
  } catch (e) {
    ElMessage.error(e.message || '校验失败')
  } finally {
    verifying.value = false
  }
}

const loadData = async () => {
  loading.value = true
  try {
    const res = await auditApi.page({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: keyword.value || undefined
    })
    records.value = res.records || []
    total.value = Number(res.total || 0)
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

/** 操作语义 → 迷你胶囊色调（展示层映射，替代原 el-tag） */
const opTone = (op) => {
  if (!op) return 'slate'
  if (op.includes('删除') || op.includes('终止')) return 'coral'
  if (op.includes('创建') || op.includes('上传') || op.includes('启动')) return 'green'
  if (op.includes('登录') || op.includes('注册')) return 'gold'
  return 'amber'
}

/** 请求方法 → 迷你胶囊色调 */
const methodTone = (method) => {
  if (method === 'POST') return 'green'
  if (method === 'PUT') return 'amber'
  if (method === 'DELETE') return 'coral'
  return 'slate'
}

const panelRef = ref(null)
// W1-04/R9：审计面板水印（登录名 · 时间）
let removeWm = null

onChartThemeChange(() => { opChart?.dispose(); opChart = null; methodChart?.dispose(); methodChart = null; renderAuditCharts() })

onMounted(() => {
  loadData()
  removeWm = setupWatermark(panelRef.value, `TraceGuard 审计面板`)
  window.addEventListener('resize', onChartsResize)
})

onUnmounted(() => {
  if (removeWm) removeWm()
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onChartsResize)
  if (opChart) opChart.dispose()
  if (methodChart) methodChart.dispose()
})
</script>

<style scoped>
.audit-page {
  max-width: 1200px;
  margin: 0 auto;
}

/* ===== W4：操作分布双图卡 ===== */
.audit-charts {
  display: grid;
  grid-template-columns: 1.2fr 1fr;
  gap: 20px;
  margin-bottom: 24px;
}

.chart-card {
  padding: 18px 20px;
  background: linear-gradient(160deg, rgba(255, 255, 255, 0.82), rgba(255, 246, 230, 0.5));
  border: 1px solid rgba(201, 155, 63, 0.18);
  border-radius: 18px;
  box-shadow: var(--tg-shadow-soft);
  backdrop-filter: blur(12px);
}

.chart-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 8px;
}

.chart-card__head h4 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--tg-text-primary);
}

.chart-card__body {
  height: 196px;
}

@media (max-width: 860px) {
  .audit-charts {
    grid-template-columns: 1fr;
  }
}

/* ===== 页头（与已优化页面统一） ===== */
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
  margin-bottom: 26px;
}

.page-header__back {
  margin-bottom: 6px;
}

.page-header__back-btn {
  color: var(--tg-text-secondary);
  padding: 0;
}

.page-header__greet {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13.5px;
  font-weight: 500;
  color: var(--tg-accent);
  margin-bottom: 8px;
}

.page-header__greet-icon {
  font-size: 15px;
}

.page-header__title {
  margin: 0;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.15;
  color: var(--tg-text-primary);
  background: linear-gradient(115deg, #6E521A 0%, #8F6B22 50%, #B98A2F 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.page-header__desc {
  margin: 8px 0 0;
  font-size: 14px;
  color: var(--tg-text-secondary);
}

.page-header__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
  flex-wrap: wrap;
}

/* 请求路径（方法胶囊后的浅色路径文本） */
.audit-path {
  font-size: 12.5px;
  color: var(--tg-text-secondary);
  font-family: var(--tg-font-mono);
}

/* 状态码：胶囊底色 + 语义色 */
.status-code {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 34px;
  padding: 2px 9px;
  border-radius: 8px;
  font-size: 12.5px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
}

.status-code--ok {
  background: rgba(154, 156, 107, 0.14);
  color: #55682e;
}

.status-code--err {
  background: rgba(194, 94, 76, 0.12);
  color: #9a3f30;
}

/* 窄屏 */
@media (max-width: 720px) {
  .page-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .page-header__actions {
    justify-content: flex-start;
  }

  .search-input {
    width: 100%;
  }
}
</style>
