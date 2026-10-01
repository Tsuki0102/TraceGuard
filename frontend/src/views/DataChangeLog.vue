<template>
  <div class="dcl-page">
    <!-- W4 v3：封面式彩色光晕（装饰层） -->
    <div class="page-glow" aria-hidden="true"><i></i><i></i><i></i></div>
    <!-- ===== 页头：返回 + 渐变标题 ===== -->
    <div class="page-header tg-fade-up">
      <div>
        <el-button link class="page-header__back" @click="$router.back()">
          <el-icon><ArrowLeft /></el-icon> 返回
        </el-button>
        <p class="tg-kicker">Audit Trail</p>
        <h2 class="page-header__title">数据变更日志</h2>
        <p class="page-header__desc">按项目或实体追踪数据变更历史</p>
      </div>
    </div>

    <!-- ===== 统计条：4 格概览（富信息 W4 v5，由 records 前端派生） ===== -->
    <div class="cfg-stats dcl-stats tg-fade-up" style="animation-delay: 80ms">
      <div class="cfg-stat cfg-stat--rich">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--gold"><el-icon :size="17"><CircleCheck /></el-icon></span>
          <span class="cfg-stat__label">记录总数</span>
        </div>
        <div class="cfg-stat__num">
          <b>{{ total }}</b>
          <span class="cfg-stat__unit">条</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: (total ? Math.round(records.length / total * 100) : 0) + '%' }"></i></span>
          <span class="cfg-stat__ratio">第 {{ pageNum }} 页 · 每页 {{ pageSize }}</span>
        </div>
      </div>
      <div class="cfg-stat cfg-stat--rich">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--blue"><el-icon :size="17"><DataLine /></el-icon></span>
          <span class="cfg-stat__label">本次查询</span>
        </div>
        <div class="cfg-stat__num">
          <b>{{ records.length }}</b>
          <span class="cfg-stat__unit">条</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: (records.length ? Math.round(opDist[1].value / records.length * 100) : 0) + '%' }"></i></span>
          <span class="cfg-stat__ratio">新增 {{ opDist[0].value }} · 修改 {{ opDist[1].value }} · 删除 {{ opDist[2].value }}</span>
        </div>
      </div>
      <div class="cfg-stat cfg-stat--rich">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--sage"><el-icon :size="17"><Box /></el-icon></span>
          <span class="cfg-stat__label">实体种类</span>
        </div>
        <div class="cfg-stat__num">
          <b>{{ entityKindCount }}</b>
          <span class="cfg-stat__unit">种</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: '100%' }"></i></span>
          <span class="cfg-stat__ratio">{{ queryMode === 'all' ? '全部项目 · 系统级记录' : queryMode === 'project' ? '项目 #' + (projectId ?? '—') : '实体 #' + (entityId || '—') }}</span>
        </div>
      </div>
      <div class="cfg-stat cfg-stat--rich">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--rose"><el-icon :size="17"><UserFilled /></el-icon></span>
          <span class="cfg-stat__label">操作人种类</span>
        </div>
        <div class="cfg-stat__num">
          <b>{{ operatorCount }}</b>
          <span class="cfg-stat__unit">人</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: (records.length ? Math.round(operatorCount / records.length * 100) : 0) + '%' }"></i></span>
          <span class="cfg-stat__ratio">{{ operatorCount }} 位操作人</span>
        </div>
      </div>
    </div>

    <!-- ===== 变更分析：信息可视化（W4 v4） ===== -->
    <section class="cfg-section tg-fade-up" style="animation-delay: 110ms">
      <div class="cfg-section__head">
        <div class="cfg-section__title">
          <h3><span class="cfg-section__ic cfg-section__ic--blue"><el-icon :size="15"><DataAnalysis /></el-icon></span>变更分析</h3>
          <p>操作类型 · 变更字段 · 时间趋势，随查询结果实时联动</p>
        </div>
      </div>
      <div class="cfg-viz">
        <div class="cfg-viz__card">
          <div class="cfg-viz__head"><span class="cfg-viz__dot dot--rose"></span><b>操作类型分布</b></div>
          <p class="cfg-viz__sub">新增 / 修改 / 删除占比</p>
          <div ref="opDonutEl" class="cfg-viz__chart"></div>
        </div>
        <div class="cfg-viz__card">
          <div class="cfg-viz__head"><span class="cfg-viz__dot dot--gold"></span><b>变更字段 Top</b></div>
          <p class="cfg-viz__sub">发生变更最多的字段</p>
          <div ref="fieldBarEl" class="cfg-viz__chart"></div>
        </div>
        <div class="cfg-viz__card">
          <div class="cfg-viz__head"><span class="cfg-viz__dot dot--violet"></span><b>变更时间趋势</b></div>
          <p class="cfg-viz__sub">按日期聚合的变更数量</p>
          <div ref="trendLineEl" class="cfg-viz__chart"></div>
        </div>
      </div>
    </section>

    <!-- ===== 查询区：精致查询条卡片 ===== -->
    <section class="query-card tg-fade-up" style="animation-delay: 140ms">
      <div class="query-card__inner">
        <el-radio-group v-model="queryMode" @change="onModeChange" class="query-card__mode">
          <el-radio-button label="all">全部记录</el-radio-button>
          <el-radio-button label="project">按项目</el-radio-button>
          <el-radio-button label="entity">按实体</el-radio-button>
        </el-radio-group>

        <template v-if="queryMode === 'project'">
          <el-select v-model="projectId" placeholder="选择项目" filterable class="query-card__proj" @change="loadData">
            <el-option v-for="p in projects" :key="p.id" :label="p.projectName" :value="p.id" />
          </el-select>
        </template>
        <template v-else-if="queryMode === 'entity'">
          <el-select v-model="entityType" placeholder="实体类型" class="query-card__type" @change="loadData">
            <el-option label="项目(project)" value="project" />
            <el-option label="需求(requirement)" value="requirement" />
            <el-option label="缺陷(defect)" value="defect" />
            <el-option label="用户(user)" value="user" />
            <el-option label="系统配置(system_config)" value="system_config" />
            <el-option label="大模型配置(llm_config)" value="llm_config" />
          </el-select>
          <el-input
            v-model="entityId"
            placeholder="实体ID"
            class="query-card__id"
            clearable
            @keyup.enter="loadData"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </template>

        <el-button type="primary" round class="query-card__go" @click="loadData">
          <el-icon style="margin-right: 5px"><Search /></el-icon>查询
        </el-button>
      </div>
    </section>

    <!-- ===== 结果区：时间线 / 表格 切换 ===== -->
    <section class="result-card tg-fade-up" style="animation-delay: 200ms">
      <div class="result-card__toolbar">
        <div class="result-card__view">
          <button type="button" class="view-toggle" :class="{ 'is-active': viewMode === 'timeline' }" @click="viewMode = 'timeline'">
            <el-icon :size="15"><Clock /></el-icon>时间线
          </button>
          <button type="button" class="view-toggle" :class="{ 'is-active': viewMode === 'table' }" @click="viewMode = 'table'">
            <el-icon :size="15"><Grid /></el-icon>表格
          </button>
        </div>
        <span v-if="viewMode === 'timeline' && timelineItems.length" class="result-card__hint">
          时间线展示最近 {{ timelineItems.length }} 条变更
        </span>
      </div>

      <!-- ===== 表格视图（原 el-table 复用） ===== -->
      <template v-if="viewMode === 'table'">
        <el-table :data="records" class="manage-table" v-loading="loading">
          <el-table-column prop="changeTime" label="变更时间" width="170" />
          <el-table-column prop="entityType" label="实体类型" width="110">
            <template #default="{ row }">
              <span class="mini-pill mini-pill--slate">{{ row.entityType }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="entityId" label="实体ID" width="110" />
          <el-table-column prop="operation" label="动作" width="90" align="center">
            <template #default="{ row }">
              <span class="mini-pill" :class="opMiniPill[row.operation]">
                {{ opLabel[row.operation] || row.operation }}
              </span>
            </template>
          </el-table-column>
          <el-table-column prop="fieldName" label="字段" width="130" />
          <el-table-column label="变更前" min-width="180" show-overflow-tooltip>
            <template #default="{ row }"><span class="tg-diff-old">{{ row.oldValue || '-' }}</span></template>
          </el-table-column>
          <el-table-column label="变更后" min-width="180" show-overflow-tooltip>
            <template #default="{ row }"><span class="tg-diff-new">{{ row.newValue || '-' }}</span></template>
          </el-table-column>
          <el-table-column prop="operatorName" label="操作人" width="120">
            <template #default="{ row }">
              <span class="tg-avatar tg-avatar--xs">{{ (row.operatorName || '?').slice(0, 1).toUpperCase() }}</span>
              <span style="margin-left: 6px">{{ row.operatorName || '-' }}</span>
            </template>
          </el-table-column>
        </el-table>

        <el-empty v-if="!loading && records.length === 0" :image-size="0" description=" ">
          <EmptyArt text="没有找到相关数据变更记录" />
        </el-empty>

        <!-- 分页（保留在表格视图） -->
        <el-pagination
          v-if="records.length"
          v-model:current-page="pageNum"
          v-model:page-size="pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          style="margin-top: 20px; justify-content: flex-end"
          @size-change="loadData"
          @current-change="loadData"
        />
      </template>

      <!-- ===== 时间线视图（竖线 + 节点图标 + 变更气泡卡） ===== -->
      <template v-else>
        <div v-loading="loading" class="timeline">
          <div
            v-for="(r, i) in timelineItems"
            :key="i"
            class="timeline__item"
            :class="`is-${r.operation || 'other'}`"
            :style="{ animationDelay: i * 45 + 'ms' }"
          >
            <div class="timeline__axis">
              <span class="timeline__node" :class="opNodeCls[r.operation]">
                <el-icon :size="15"><component :is="opIcon[r.operation]" /></el-icon>
              </span>
            </div>
            <div class="timeline__card">
              <div class="tl-card__head">
                <div class="tl-card__entity">
                  <span class="mini-pill mini-pill--slate">{{ r.entityType }}</span>
                  <span class="tl-card__eid">#{{ r.entityId }}</span>
                </div>
                <span class="mini-pill" :class="opMiniPill[r.operation]">
                  {{ opLabel[r.operation] || r.operation }}
                </span>
              </div>
              <div class="tl-card__diff">
                <span class="tl-chip">{{ r.fieldName }}</span>
                <span class="tl-chip-old">{{ r.oldValue || '-' }}</span>
                <span class="tl-chip-arrow">→</span>
                <span class="tl-chip-new">{{ r.newValue || '-' }}</span>
              </div>
              <div class="tl-card__meta">
                <span class="tg-avatar tg-avatar--xs">{{ (r.operatorName || '?').slice(0, 1).toUpperCase() }}</span>
                <span class="tl-card__op">{{ r.operatorName || '-' }}</span>
                <span class="tl-card__time">{{ r.changeTime }}</span>
              </div>
            </div>
          </div>

          <div v-if="!loading && records.length === 0" class="timeline__empty">
            <EmptyArt text="时间线暂无数据，请尝试查询" />
          </div>
        </div>
      </template>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { chartColors as C, chartThemeName, chartText, chartFaint, chartAxisLine, chartSplitLine, chartTooltipBg, chartTitleColor, onChartThemeChange } from '@/utils/echartsTheme'
import {
  ArrowLeft,
  Search,
  CircleCheck,
  DataLine,
  DataAnalysis,
  Box,
  UserFilled,
  Clock,
  Grid,
  Plus,
  EditPen,
  Delete
} from '@element-plus/icons-vue'
import EmptyArt from '@/components/EmptyArt.vue'
import { dataChangeLogApi, projectApi } from '@/api'

const queryMode = ref('all')
const projectId = ref(null)
const entityType = ref('user')
const entityId = ref('')
const projects = ref([])
const records = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const loading = ref(false)
/* 视图切换：时间线（默认）/ 表格 */
const viewMode = ref('timeline')

const opLabel = { create: '新增', update: '修改', delete: '删除' }
const opMiniPill = {
  create: 'mini-pill--green',
  update: 'mini-pill--amber',
  delete: 'mini-pill--coral'
}

/* 时间线节点图标 + 状态颜色 */
const opIcon = { create: 'Plus', update: 'EditPen', delete: 'Delete' }
const opNodeCls = { create: 'is-create', update: 'is-update', delete: 'is-delete' }

/* 统计条：由当前 records 前端派生 */
const entityKindCount = computed(() => new Set(records.value.map((r) => r.entityType).filter(Boolean)).size)
const operatorCount = computed(() => new Set(records.value.map((r) => r.operatorName).filter(Boolean)).size)
/* 时间线：当前 records 前 50 条，随分页联动 */
const timelineItems = computed(() => records.value.slice(0, 50))

const loadProjects = async () => {
  try {
    projects.value = await projectApi.list()
    if (queryMode.value === 'project' && projects.value.length && !projectId.value) {
      projectId.value = projects.value[0].id
    }
  } catch (e) {
    console.warn('加载项目列表失败', e)
  }
}

const onModeChange = () => {
  pageNum.value = 1
  loadData()
}

const loadData = async () => {
  loading.value = true
  try {
    let res
    if (queryMode.value === 'all') {
      res = await dataChangeLogApi.pageAll({ pageNum: pageNum.value, pageSize: pageSize.value })
    } else if (queryMode.value === 'project') {
      if (!projectId.value) {
        records.value = []
        total.value = 0
        return
      }
      res = await dataChangeLogApi.pageByProject(projectId.value, { pageNum: pageNum.value, pageSize: pageSize.value })
    } else {
      if (!entityId.value) {
        ElMessage.warning('请输入实体ID')
        return
      }
      res = await dataChangeLogApi.pageByEntity({ entityType: entityType.value, entityId: entityId.value, pageNum: pageNum.value, pageSize: pageSize.value })
    }
    records.value = res.records || []
    total.value = Number(res.total || 0)
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

onChartThemeChange(() => { opDonutChart?.dispose(); opDonutChart = null; fieldBarChart?.dispose(); fieldBarChart = null; trendLineChart?.dispose(); trendLineChart = null; renderDclCharts() })

onMounted(() => {
  loadProjects().then(() => {
    loadData()
    nextTick(renderDclCharts)
  })
  window.addEventListener('resize', onDclResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onDclResize)
  if (opDonutChart) opDonutChart.dispose()
  if (fieldBarChart) fieldBarChart.dispose()
  if (trendLineChart) trendLineChart.dispose()
})

/* ============ W4 v4：变更分析可视化 ============ */
const opDonutEl = ref(null)
const fieldBarEl = ref(null)
const trendLineEl = ref(null)
let opDonutChart = null
let fieldBarChart = null
let trendLineChart = null

const VIZ_TOOLTIP = {
  backgroundColor: 'rgba(43,36,28,.92)',
  borderColor: 'rgba(201,155,63,.4)',
  textStyle: { color: '#f4ead6' }
}

/** 操作类型分布：新增 / 修改 / 删除 */
const opDist = computed(() => {
  const counts = { 新增: 0, 修改: 0, 删除: 0 }
  records.value.forEach((r) => {
    const k = opLabel[r.operation] || r.operation || '未知'
    if (k in counts) counts[k]++
  })
  return [
    { name: '新增', value: counts['新增'], color: C.success },
    { name: '修改', value: counts['修改'], color: C.warning },
    { name: '删除', value: counts['删除'], color: C.danger }
  ]
})

/** 变更字段 Top 6 */
const fieldDist = computed(() => {
  const counts = {}
  records.value.forEach((r) => {
    const f = r.fieldName || '其他'
    counts[f] = (counts[f] || 0) + 1
  })
  return Object.entries(counts).sort((a, b) => b[1] - a[1]).slice(0, 6)
})

/** 变更时间趋势：按日期聚合 */
const trendData = computed(() => {
  const counts = {}
  records.value.forEach((r) => {
    const day = (r.changeTime || '').slice(0, 10)
    if (day) counts[day] = (counts[day] || 0) + 1
  })
  const days = Object.keys(counts).sort()
  return { days, counts: days.map((d) => counts[d]) }
})

const renderDclCharts = () => {
  // 1) 操作类型环形图
  if (opDonutEl.value) {
    if (!opDonutChart) opDonutChart = echarts.init(opDonutEl.value, chartThemeName())
    const items = opDist.value
    const total = items.reduce((n, it) => n + it.value, 0)
    opDonutChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}：{c} 条（{d}%）', ...VIZ_TOOLTIP },
      legend: {
        bottom: 0,
        left: 'center',
        icon: 'circle',
        itemWidth: 8,
        itemHeight: 8,
        textStyle: { color: 'var(--tg-text-secondary)', fontSize: 10.5 }
      },
      title: {
        text: String(total),
        subtext: '操作总数',
        left: 'center',
        top: '34%',
        textStyle: { fontSize: 20, fontWeight: 700, color: 'var(--tg-text-primary)' },
        subtextStyle: { fontSize: 10.5, color: 'var(--tg-text-secondary)' }
      },
      series: [{
        type: 'pie',
        radius: ['46%', '70%'],
        center: ['50%', '42%'],
        padAngle: 3,
        itemStyle: { borderRadius: 7, borderColor: 'rgba(255,244,224,.9)', borderWidth: 2 },
        label: { show: false },
        emphasis: { scaleSize: 5, itemStyle: { shadowBlur: 14, shadowColor: 'rgba(201,155,63,.4)' } },
        data: total > 0
          ? items.map((it) => ({ name: it.name, value: it.value, itemStyle: { color: it.color } }))
          : [{ name: '暂无数据', value: 1, itemStyle: { color: 'rgba(180,170,148,.22)' } }]
      }]
    }, true)
  }
  // 2) 变更字段 Top 横向条
  if (fieldBarEl.value) {
    if (!fieldBarChart) fieldBarChart = echarts.init(fieldBarEl.value, chartThemeName())
    const dist = fieldDist.value
    fieldBarChart.setOption({
      animationDuration: 600,
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, ...VIZ_TOOLTIP },
      grid: { left: 6, right: 30, top: 8, bottom: 6, containLabel: true },
      xAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: 'rgba(201,155,63,.1)' } },
        axisLabel: { color: 'var(--tg-text-secondary)', fontSize: 11 }
      },
      yAxis: {
        type: 'category',
        data: dist.map((d) => d[0]),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: 'var(--tg-text-primary)', fontSize: 11.5, fontWeight: 500 }
      },
      series: [{
        type: 'bar',
        data: dist.map((d, i) => ({
          value: d[1],
          itemStyle: {
            borderRadius: [0, 8, 8, 0],
            color: ['#6E93B0', '#9A7FB0', '#C97F8A', '#6B8E4E', '#E89B3C', '#C99B3F'][i % 6]
          }
        })),
        barWidth: 12,
        showBackground: true,
        backgroundStyle: { color: 'rgba(201,155,63,.08)', borderRadius: [0, 8, 8, 0] },
        label: { show: true, position: 'right', formatter: '{c}', color: 'var(--tg-text-secondary)', fontSize: 11 }
      }]
    }, true)
  }
  // 3) 变更时间趋势（面积折线）
  if (trendLineEl.value) {
    if (!trendLineChart) trendLineChart = echarts.init(trendLineEl.value, chartThemeName())
    const { days, counts } = trendData.value
    trendLineChart.setOption({
      animationDuration: 600,
      tooltip: { trigger: 'axis', ...VIZ_TOOLTIP },
      grid: { left: 6, right: 16, top: 16, bottom: 4, containLabel: true },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: days,
        axisLine: { lineStyle: { color: 'rgba(0,0,0,.12)' } },
        axisTick: { show: false },
        axisLabel: { color: 'var(--tg-text-secondary)', fontSize: 10.5 }
      },
      yAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: 'rgba(201,155,63,.1)' } },
        axisLabel: { color: 'var(--tg-text-secondary)', fontSize: 11 }
      },
      series: [{
        type: 'line',
        smooth: true,
        symbolSize: 6,
        data: counts,
        lineStyle: { color: '#9A7FB0', width: 2.5 },
        itemStyle: { color: '#9A7FB0' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(154,127,176,.32)' },
            { offset: 1, color: 'rgba(154,127,176,.02)' }
          ])
        }
      }]
    }, true)
  }
}

const onDclResize = () => {
  if (opDonutChart) opDonutChart.resize()
  if (fieldBarChart) fieldBarChart.resize()
  if (trendLineChart) trendLineChart.resize()
}

watch(records, () => nextTick(renderDclCharts))
</script>

<style scoped>
/* 页面容器 */
.dcl-page {
  position: relative;
  max-width: 1200px;
  margin: 0 auto;
  padding: 0;
}

/* ===== 统计条：间距微调 ===== */
.dcl-stats {
  gap: 16px;
}

/* ===== 查询条卡片：玻璃白渐变 + 16 圆角 + 细腻阴影 ===== */
.query-card {
  position: relative;
  margin-bottom: 24px;
  padding: 18px 20px;
  border-radius: 18px;
  background: linear-gradient(160deg, rgba(255, 255, 255, 0.85), rgba(255, 247, 233, 0.55));
  border: 1px solid rgba(201, 155, 63, 0.18);
  box-shadow: var(--tg-shadow-card);
  backdrop-filter: blur(14px);
}

.query-card__inner {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.query-card__mode .el-radio-button__inner {
  border-radius: 12px;
}

.query-card__proj { width: 220px; }
.query-card__type { width: 130px; }
.query-card__id { width: 190px; }
.query-card__go { flex-shrink: 0; }

/* ===== 结果卡片 ===== */
.result-card {
  position: relative;
  padding: 22px 24px;
  border-radius: 20px;
  background: linear-gradient(160deg, rgba(255, 255, 255, 0.8), rgba(255, 250, 240, 0.45));
  border: 1px solid rgba(201, 155, 63, 0.16);
  box-shadow: var(--tg-shadow-card);
  backdrop-filter: blur(14px);
}

.result-card__toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.result-card__view {
  display: inline-flex;
  gap: 4px;
  padding: 4px;
  border-radius: 14px;
  background: rgba(0, 0, 0, 0.045);
}

.view-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 14px;
  border: none;
  border-radius: 11px;
  background: transparent;
  font-size: 13px;
  font-weight: 500;
  color: var(--tg-text-secondary);
  cursor: pointer;
  transition: background 0.25s ease, color 0.25s ease, box-shadow 0.25s ease, transform 0.25s var(--tg-ease);
}

.view-toggle:hover {
  color: var(--tg-text-primary);
  background: rgba(255, 255, 255, 0.7);
}

.view-toggle.is-active {
  background: #fff;
  color: var(--tg-accent);
  font-weight: 600;
  box-shadow: var(--tg-shadow-card);
}

.result-card__hint {
  font-size: 12.5px;
  color: var(--tg-text-secondary);
}

/* ===== 时间线 ===== */
.timeline {
  position: relative;
  min-height: 120px;
}

.timeline__item {
  position: relative;
  display: flex;
  gap: 18px;
  padding-bottom: 18px;
  opacity: 0;
  transform: translateY(10px) scale(0.98);
  animation: tl-pop 0.45s var(--tg-ease-spring) both;
}

/* 竖向连接线 */
.timeline__axis {
  position: relative;
  flex-shrink: 0;
  width: 36px;
  display: flex;
  justify-content: center;
}

.timeline__axis::before {
  content: '';
  position: absolute;
  top: 36px;
  bottom: -18px;
  left: 50%;
  width: 2px;
  transform: translateX(-50%);
  background: linear-gradient(180deg, rgba(201, 155, 63, 0.28), rgba(201, 155, 63, 0.06));
}

.timeline__item:last-child .timeline__axis::before {
  display: none;
}

/* 节点图标：state 徽标（语义配色） */
.timeline__node {
  position: relative;
  z-index: 1;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  flex-shrink: 0;
  box-shadow: 0 6px 16px rgba(60, 45, 25, 0.14);
}

.timeline__node::after {
  content: '';
  position: absolute;
  inset: -5px;
  border-radius: 50%;
  border: 2px solid currentColor;
  opacity: 0.22;
}

.timeline__node.is-create { background: var(--tg-success); }
.timeline__node.is-update { background: var(--tg-accent); box-shadow: 0 6px 18px rgba(143, 107, 34, 0.3); }
.timeline__node.is-delete { background: var(--tg-danger); }
.timeline__node.is-create::after { border-color: var(--tg-success); color: var(--tg-success); }
.timeline__node.is-update::after { border-color: var(--tg-accent); color: var(--tg-accent); }
.timeline__node.is-delete::after { border-color: var(--tg-danger); color: var(--tg-danger); }

/* 变更气泡卡 */
.timeline__card {
  flex: 1;
  min-width: 0;
  padding: 14px 18px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.82);
  border: 1px solid rgba(201, 155, 63, 0.16);
  box-shadow: var(--tg-shadow-card);
  transition: transform 0.28s var(--tg-ease), box-shadow 0.28s ease, border-color 0.28s ease;
}

.timeline__card:hover {
  transform: translateY(-2px);
  border-color: rgba(201, 155, 63, 0.34);
  box-shadow: var(--tg-shadow-card-hover);
}

.tl-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.tl-card__entity {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

.tl-card__eid {
  font-size: 13px;
  font-weight: 600;
  color: var(--tg-text-primary);
  font-family: var(--tg-font-mono);
}

/* 字段 chip + diff 高亮 */
.tl-card__diff {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 10px 12px;
  border-radius: 12px;
  background: rgba(0, 0, 0, 0.028);
}

.tl-chip {
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--el-color-primary-light-9);
  color: var(--tg-accent);
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.tl-chip-old {
  padding: 3px 9px;
  border-radius: 8px;
  background: rgba(194, 94, 76, 0.1);
  color: var(--tg-danger);
  text-decoration: line-through;
  text-decoration-color: rgba(194, 94, 76, 0.45);
  font-size: 12.5px;
  word-break: break-all;
}

.tl-chip-new {
  padding: 3px 9px;
  border-radius: 8px;
  background: rgba(107, 142, 78, 0.12);
  color: var(--tg-success);
  font-weight: 600;
  font-size: 12.5px;
  word-break: break-all;
}

.tl-chip-arrow {
  color: var(--tg-text-secondary);
  font-size: 13px;
  font-weight: 600;
}

/* 操作人 + 时间小字 */
.tl-card__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 11px;
  flex-wrap: wrap;
}

.tl-card__op {
  font-size: 12.5px;
  font-weight: 500;
  color: var(--tg-text-primary);
}

.tl-card__time {
  margin-left: auto;
  font-size: 12px;
  color: var(--tg-text-secondary);
}

.timeline__empty {
  display: flex;
  justify-content: center;
}

/* 节点 pop 入场 */
@keyframes tl-pop {
  to {
    opacity: 1;
    transform: none;
  }
}

/* 窄屏适配 */
@media (max-width: 720px) {
  .query-card__inner {
    flex-direction: column;
    align-items: stretch;
  }
  .query-card__proj,
  .query-card__type,
  .query-card__id {
    width: 100% !important;
  }
  .dcl-stats {
    grid-template-columns: repeat(2, 1fr);
  }
  .tl-card__time {
    margin-left: 0;
  }
}
</style>