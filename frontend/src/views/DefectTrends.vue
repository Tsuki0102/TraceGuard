<template>
  <div class="page dt-page">
    <!-- 控制室布局：左侧窄控制轨 + 右侧深色主屏（全站唯一深色面板页） -->
    <aside class="dt-rail">
      <div class="dt-rail__block">
        <h3 class="dt-rail__label">时间窗</h3>
        <div class="dt-range">
          <button v-for="d in [7, 30, 90]" :key="d" type="button"
            :class="{ on: range === d }" @click="range = d; load()">{{ d }}天</button>
        </div>
      </div>
      <div class="dt-rail__block dt-rail__block--grow">
        <h3 class="dt-rail__label">观察对象</h3>
        <div class="dt-projects">
          <button type="button" class="dt-proj" :class="{ on: projectId === null }" @click="projectId = null; load()">
            <span class="dt-proj__all">◈</span>全部项目
          </button>
          <button v-for="p in projects" :key="p.id" type="button" class="dt-proj"
            :class="{ on: projectId === p.id }" @click="projectId = p.id; load()">
            <span class="dt-proj__name">{{ p.projectName }}</span>
            <span class="dt-proj__meta">{{ p.defectCount || 0 }} 缺陷</span>
          </button>
        </div>
      </div>
      <div class="dt-rail__block dt-dial">
        <span class="dt-dial__label">窗口内缺陷</span>
        <b class="dt-dial__num">{{ data.total }}</b>
        <div class="dt-dial__split">
          <i><em style="width: seriousPct + '%'"></em></i>
          <span>严重占比 {{ seriousPct }}%</span>
        </div>
      </div>
    </aside>

    <main class="dt-main">
      <!-- 深色图表主屏 -->
      <section class="dt-screen" v-loading="loading">
        <header class="dt-screen__head">
          <div>
            <h2 class="dt-screen__title">{{ scopeName }} · 缺陷时间轴</h2>
            <p class="dt-screen__sub">近 {{ range }} 天按日新增缺陷 · 严重/一般双轨</p>
          </div>
          <div class="dt-legend" aria-hidden="true">
            <span class="dt-legend__item"><i class="is-serious"></i>严重</span>
            <span class="dt-legend__item"><i class="is-general"></i>一般</span>
          </div>
        </header>
        <div ref="chartRef" class="dt-chart"></div>
      </section>

      <!-- 类型 Top：手写内联条（不用表格/不用图表库，与主屏形成材质对比） -->
      <section class="dt-types">
        <h3 class="dt-types__title">缺陷类型 Top 6</h3>
        <div class="dt-types__rows">
          <div v-for="(t, i) in topTypes" :key="t.type" class="dt-type-row">
            <span class="dt-type-row__rank">{{ String(i + 1).padStart(2, '0') }}</span>
            <span class="dt-type-row__name">{{ t.type }}</span>
            <span class="dt-type-row__barwrap"><i :style="{ width: typeBarPct(t.cnt) + '%' }"></i></span>
            <b class="dt-type-row__num">{{ t.cnt }}</b>
          </div>
          <p v-if="!topTypes.length" class="dt-types__empty">窗口内暂无缺陷记录</p>
        </div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import { chartThemeName, chartText, chartFaint, chartAxisLine, chartSplitLine, chartTooltipBg, chartTitleColor, onChartThemeChange } from '@/utils/echartsTheme'
import { ElMessage } from 'element-plus'
import { insightApi, projectApi } from '@/api'

const range = ref(30)
const projectId = ref(null)
const projects = ref([])
const loading = ref(false)
const chartRef = ref(null)
let chart = null

const data = ref({ days: [], serious: [], general: [], total: 0, byType: [] })

const scopeName = computed(() => {
  if (!projectId.value) return '全部项目'
  const p = projects.value.find(x => x.id === projectId.value)
  return p ? p.projectName : '项目'
})
const seriousPct = computed(() => {
  const s = (data.value.serious || []).reduce((a, b) => a + b, 0)
  return data.value.total ? Math.round((s * 100) / data.value.total) : 0
})
const topTypes = computed(() => data.value.byType || [])
const typeBarPct = (cnt) => {
  const max = Math.max(...topTypes.value.map(t => t.cnt), 1)
  return Math.round((cnt * 100) / max)
}

const render = () => {
  if (!chartRef.value) return
  if (!chart) chart = echarts.init(chartRef.value, chartThemeName())
  chart.setOption({
    grid: { left: 44, right: 18, top: 26, bottom: 30 },
    tooltip: {
      trigger: 'axis',
      backgroundColor: chartTooltipBg(),
      borderColor: 'rgba(201, 155, 63, 0.3)',
      textStyle: { color: chartTitleColor(), fontSize: 12 },
      valueFormatter: (v) => v + ' 个'
    },
    xAxis: {
      type: 'category',
      data: data.value.days,
      axisLine: { lineStyle: { color: 'rgba(60, 45, 25, 0.3)' } },
      axisTick: { show: false },
      axisLabel: { color: chartText(), fontSize: 10.5, interval: Math.ceil(data.value.days.length / 12) }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: 'rgba(60, 45, 25, 0.18)', width: 1, type: 'dashed' } },
      axisLabel: { color: chartText(), fontSize: 10.5 }
    },
    series: [
      {
        name: '严重', type: 'line', stack: 'defect', smooth: true, symbol: 'none',
        lineStyle: { width: 0 }, emphasis: { focus: 'series' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(194, 94, 76, 0.55)' },
            { offset: 1, color: 'rgba(194, 94, 76, 0.08)' }
          ])
        },
        data: data.value.serious
      },
      {
        name: '一般', type: 'line', stack: 'defect', smooth: true, symbol: 'none',
        lineStyle: { width: 0 }, emphasis: { focus: 'series' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(201, 155, 63, 0.5)' },
            { offset: 1, color: 'rgba(201, 155, 63, 0.06)' }
          ])
        },
        data: data.value.general
      }
    ]
  })
}

const load = async () => {
  loading.value = true
  try {
    data.value = await insightApi.trend(range.value, projectId.value) || data.value
    await nextTick()
    render()
  } catch (e) {
    ElMessage.error(e?.message || '加载缺陷趋势失败')
  } finally {
    loading.value = false
  }
}

const onResize = () => chart && chart.resize()

onChartThemeChange(() => { chart?.dispose(); chart = null; render() })

onMounted(async () => {
  try {
    projects.value = await projectApi.list() || []
  } catch { /* 静默 */ }
  await load()
  window.addEventListener('resize', onResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onResize)
  if (chart) { chart.dispose(); chart = null }
})
</script>

<style scoped>
/* ===== 控制室：左侧控制轨（操作全部在轨上） + 右侧深色主屏 ===== */
.dt-page { display: flex; gap: 20px; align-items: stretch; min-height: calc(100vh - 140px); }

.dt-rail {
  width: 224px; flex-shrink: 0; display: flex; flex-direction: column; gap: 14px;
  padding: 18px 14px; border-radius: 20px;
  background: linear-gradient(170deg, rgba(255, 255, 255, 0.8), rgba(255, 246, 230, 0.5));
  border: 1px solid rgba(201, 155, 63, 0.18); box-shadow: var(--tg-shadow-card);
}
.dt-rail__label { margin: 0 0 10px; font-size: 11px; font-weight: 600; letter-spacing: 0.1em; color: var(--tg-slate); }
.dt-rail__block--grow { flex: 1; min-height: 0; }

.dt-range { display: flex; flex-direction: column; gap: 6px; }
.dt-range button {
  border: 1px solid transparent; background: rgba(0, 0, 0, 0.04); cursor: pointer;
  padding: 9px 14px; border-radius: 11px; font-size: 13px; text-align: left;
  color: var(--tg-text-secondary); transition: all 0.2s var(--tg-ease);
}
.dt-range button:hover { background: rgba(201, 155, 63, 0.1); }
.dt-range button.on {
  background: var(--tg-accent-gradient); color: #fff; font-weight: 600;
  box-shadow: var(--tg-glow-accent);
}

.dt-projects { display: flex; flex-direction: column; gap: 5px; max-height: 40vh; overflow-y: auto; padding-right: 2px; }
.dt-proj {
  display: flex; flex-direction: column; align-items: flex-start; gap: 1px;
  border: 1px solid transparent; background: transparent; cursor: pointer; text-align: left;
  padding: 8px 11px; border-radius: 10px; transition: all 0.2s var(--tg-ease);
}
.dt-proj:hover { background: rgba(201, 155, 63, 0.08); }
.dt-proj.on { background: rgba(201, 155, 63, 0.14); border-color: rgba(201, 155, 63, 0.35); }
.dt-proj__all { font-size: 13px; font-weight: 600; color: var(--tg-text-primary); }
.dt-proj__name { font-size: 12.5px; font-weight: 500; color: var(--tg-text-primary); max-width: 100%; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dt-proj__meta { font-size: 10.5px; color: var(--tg-slate); font-variant-numeric: tabular-nums; }

.dt-dial {
  border-top: 1px dashed var(--tg-border); padding-top: 14px;
  display: flex; flex-direction: column; gap: 4px;
}
.dt-dial__label { font-size: 11px; color: var(--tg-slate); letter-spacing: 0.08em; }
.dt-dial__num { font-size: 34px; font-weight: 700; line-height: 1.05; color: var(--tg-text-primary); font-variant-numeric: tabular-nums; }
.dt-dial__split { display: flex; flex-direction: column; gap: 4px; margin-top: 4px; }
.dt-dial__split i { display: block; height: 5px; border-radius: 999px; background: rgba(194, 94, 76, 0.14); overflow: hidden; }
.dt-dial__split i em { display: block; height: 100%; border-radius: 999px; background: var(--tg-danger); }
.dt-dial__split span { font-size: 10.5px; color: var(--tg-text-secondary); }

.dt-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 16px; }

/* 主屏：浅暖多彩光斑（复用工作台页头设计语言，蓝金藤紫三色斑） */
.dt-screen {
  flex: 1; min-height: 380px; border-radius: 22px; padding: 20px 22px 10px;
  background:
    radial-gradient(130% 190% at 0% 0%, rgba(232, 200, 119, 0.3), transparent 52%),
    radial-gradient(120% 170% at 100% 0%, rgba(110, 147, 176, 0.18), transparent 55%),
    radial-gradient(100% 150% at 50% 115%, rgba(154, 127, 176, 0.14), transparent 58%),
    linear-gradient(160deg, rgba(255, 250, 240, 0.96), rgba(255, 246, 230, 0.7));
  border: 1px solid rgba(201, 155, 63, 0.2);
  box-shadow: var(--tg-shadow-card);
}
.dt-screen__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 6px; }
.dt-screen__title { margin: 0; font-size: 17px; font-weight: 700; color: var(--tg-text-primary); }
.dt-screen__sub { margin: 4px 0 0; font-size: 12px; color: var(--tg-text-secondary); }
.dt-legend { display: flex; gap: 14px; padding-top: 4px; }
.dt-legend__item { display: inline-flex; align-items: center; gap: 6px; font-size: 11.5px; color: var(--tg-text-secondary); }
.dt-legend__item i { width: 10px; height: 10px; border-radius: 3px; }
.dt-legend__item i.is-serious { background: #C25E4C; }
.dt-legend__item i.is-general { background: #C99B3F; }
.dt-chart { height: 320px; }

/* 类型 Top：内联条 */
.dt-types {
  border-radius: 18px; padding: 16px 20px;
  background: var(--tg-card-highlight); border: 1px solid rgba(255, 255, 255, 0.72);
  box-shadow: var(--tg-shadow-card);
}
.dt-types__title { margin: 0 0 12px; font-size: 13.5px; font-weight: 600; color: var(--tg-text-primary); }
.dt-types__rows { display: flex; flex-direction: column; gap: 9px; }
.dt-type-row { display: grid; grid-template-columns: 30px 150px 1fr 46px; align-items: center; gap: 12px; }
.dt-type-row__rank { font-family: var(--tg-font-mono); font-size: 11px; color: var(--tg-slate); }
.dt-type-row__name { font-size: 12.5px; color: var(--tg-text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.dt-type-row__barwrap { height: 8px; border-radius: 999px; background: rgba(201, 155, 63, 0.1); overflow: hidden; }
.dt-type-row__barwrap i { display: block; height: 100%; border-radius: 999px; background: var(--tg-accent-gradient); transition: width 0.6s var(--tg-ease); }
.dt-type-row__num { text-align: right; font-size: 13px; font-weight: 700; color: var(--tg-text-primary); font-variant-numeric: tabular-nums; }
.dt-types__empty { margin: 8px 0; text-align: center; font-size: 12.5px; color: var(--tg-slate); }

@media (max-width: 1024px) {
  .dt-page { flex-direction: column; }
  .dt-rail { width: 100%; flex-direction: row; flex-wrap: wrap; }
  .dt-rail__block--grow { flex: 1; min-width: 220px; }
  .dt-projects { max-height: 160px; }
}
</style>
