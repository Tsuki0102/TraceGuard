<template>
  <div class="page tk-page">
    <!-- 顶部工具条：非对称左右布局，区别于常规页头大卡 -->
    <header class="tk-topbar">
      <div class="tk-topbar__brand">
        <h1 class="tk-title">缺陷工单</h1>
        <p class="tk-sub">跨项目缺陷治理台 · 状态流转即闭环<span class="tk-sub__dot">·</span>误报就地标记</p>
      </div>
      <div class="tk-topbar__tools">
        <el-select v-model="filterProject" placeholder="全部项目" size="default" clearable class="tk-project-select">
          <el-option label="全部项目" :value="null" />
          <el-option v-for="p in projects" :key="p.id" :label="p.projectName" :value="p.id" />
        </el-select>
        <div class="tk-level-switch" role="group" aria-label="缺陷等级">
          <button type="button" :class="{ on: filterLevel === '' }" @click="filterLevel = ''">全部</button>
          <button type="button" :class="{ on: filterLevel === 'serious' }" @click="filterLevel = 'serious'">严重</button>
          <button type="button" :class="{ on: filterLevel === 'general' }" @click="filterLevel = 'general'">一般</button>
        </div>
        <el-input v-model="keyword" placeholder="搜索缺陷描述 / 类型" prefix-icon="Search" clearable class="tk-search" @keyup.enter="load" @clear="load" />
        <el-button circle icon="Refresh" aria-label="刷新" @click="load" />
      </div>
    </header>

    <!-- 度量带：地铁瓷砖 + 误报率环 -->
    <section class="tk-metrics" v-loading="loading">
      <div class="tk-tiles">
        <div v-for="t in tiles" :key="t.key" class="tk-tile" :class="'tk-tile--' + t.key">
          <b>{{ t.value }}</b>
          <span>{{ t.label }}</span>
        </div>
      </div>
      <div class="tk-ringzone">
        <div class="tk-ring" :style="{ background: ringBg }" role="img" :aria-label="`误报率 ${summary.fpRate}%`">
          <div class="tk-ring__hole">
            <b>{{ summary.fpRate }}<i>%</i></b>
            <span>误报率</span>
          </div>
        </div>
        <dl class="tk-ringzone__side">
          <div><dt>未决</dt><dd>{{ summary.unresolved }}</dd></div>
          <div><dt>已闭环</dt><dd>{{ (summary.resolved || 0) + (summary.ignored || 0) + (summary.falsePositive || 0) }}</dd></div>
          <div><dt>工单总量</dt><dd>{{ summary.total }}</dd></div>
        </dl>
      </div>
    </section>

    <!-- 近 14 天新增缺陷 sparkline 横幅（信息可视化） -->
    <section class="tk-spark">
      <span class="tk-spark__label">近 14 天新增</span>
      <div ref="sparkRef" class="tk-spark__chart"></div>
      <b class="tk-spark__total">{{ sparkTotal }}<small>个</small></b>
    </section>

    <!-- 看板：五列状态流 -->
    <section class="tk-board" v-loading="loading">
      <div v-for="col in columns" :key="col.key" class="tk-col" :class="'tk-col--' + col.key">
        <header class="tk-col__head">
          <i class="tk-col__dot" aria-hidden="true"></i>
          <span class="tk-col__name">{{ col.label }}</span>
          <em class="tk-col__count">{{ colCards(col.key).length }}</em>
        </header>
        <div class="tk-col__scroll">
          <article v-for="c in colCards(col.key)" :key="c.id" class="tk-card" :class="'is-' + c.level" @click="openDetail(c)">
            <span class="tk-card__spine" aria-hidden="true"></span>
            <div class="tk-card__head">
              <span class="tk-card__project">{{ c.projectName }}</span>
              <span class="tk-card__type">{{ c.type }}</span>
            </div>
            <p class="tk-card__reason">{{ c.reason || '（无描述）' }}</p>
            <footer class="tk-card__foot">
              <time>{{ fmtDay(c.createTime) }}</time>
              <el-dropdown trigger="click" @command="(cmd) => move(c, cmd)" @click.stop>
                <button type="button" class="tk-card__move" @click.stop>流转<el-icon :size="10"><ArrowDown /></el-icon></button>
                <template #dropdown>
                  <el-dropdown-menu>
                    <el-dropdown-item v-for="t in nextStatuses(c.status)" :key="t.code" :command="t.code">{{ t.label }}</el-dropdown-item>
                  </el-dropdown-menu>
                </template>
              </el-dropdown>
            </footer>
          </article>
          <p v-if="!colCards(col.key).length" class="tk-col__empty">空</p>
        </div>
      </div>
    </section>

    <!-- 详情抽屉 -->
    <el-drawer v-model="drawer" size="440px" :title="current ? '缺陷 #' + current.id : '缺陷详情'">
      <div v-if="current" class="tk-detail">
        <div class="tk-detail__meta">
          <span class="tk-detail__level" :class="'is-' + current.level">{{ current.level === 'serious' ? '严重' : '一般' }}</span>
          <span class="tk-detail__status">{{ statusLabel(current.status) }}</span>
          <span class="tk-detail__project">{{ current.projectName }}</span>
        </div>
        <h3 class="tk-detail__type">{{ current.type }}<template v-if="current.subType"> / {{ current.subType }}</template></h3>
        <section class="tk-detail__block">
          <h4>缺陷描述</h4>
          <p>{{ current.reason || '（无描述）' }}</p>
        </section>
        <section class="tk-detail__block">
          <h4>相关需求</h4>
          <p class="tk-detail__req">{{ current.requirement || '（未关联）' }}</p>
        </section>
        <section class="tk-detail__block">
          <h4>修复建议</h4>
          <p>{{ current.suggestion || '（无建议）' }}</p>
        </section>
        <section class="tk-detail__block">
          <h4>状态流转</h4>
          <div class="tk-detail__flows">
            <el-button v-for="t in nextStatuses(current.status)" :key="t.code" size="default" round
              :type="t.code === 'falsePositive' ? 'warning' : 'primary'" plain @click="move(current, t.code)">
              {{ t.label }}
            </el-button>
            <span v-if="!nextStatuses(current.status).length" class="tk-detail__terminal">当前状态无可用流转</span>
          </div>
        </section>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount } from 'vue'
import * as echarts from 'echarts'
import { chartThemeName, chartText, chartFaint, chartAxisLine, chartSplitLine, chartTooltipBg, chartTitleColor, onChartThemeChange } from '@/utils/echartsTheme'
import { ElMessage } from 'element-plus'
import { ArrowDown } from '@element-plus/icons-vue'
import { insightApi, projectApi } from '@/api'

const loading = ref(false)
const projects = ref([])
const filterProject = ref(null)
const filterLevel = ref('')
const keyword = ref('')
const summary = ref({ total: 0, pending: 0, processing: 0, resolved: 0, ignored: 0, falsePositive: 0, unresolved: 0, fpRate: 0 })
const records = ref([])
const drawer = ref(false)
const current = ref(null)

// 状态口径与 GAP-011 DefectStatus 枚举一致（W5 扩展误报）
const COLUMNS = [
  { key: 'pending', label: '待处理' },
  { key: 'processing', label: '处理中' },
  { key: 'resolved', label: '已解决' },
  { key: 'ignored', label: '已忽略' },
  { key: 'falsePositive', label: '误报' }
]
const LABELS = Object.fromEntries(COLUMNS.map(c => [c.key, c.label]))
const FLOW = {
  pending: [{ code: 'processing', label: '开始处理' }, { code: 'ignored', label: '忽略' }, { code: 'falsePositive', label: '标记误报' }],
  processing: [{ code: 'resolved', label: '标记解决' }, { code: 'ignored', label: '忽略' }, { code: 'pending', label: '退回待处理' }, { code: 'falsePositive', label: '标记误报' }],
  resolved: [{ code: 'processing', label: '重新打开' }],
  ignored: [{ code: 'processing', label: '重新打开' }],
  falsePositive: [{ code: 'processing', label: '重新打开' }, { code: 'pending', label: '退回待处理' }]
}

const columns = COLUMNS
const tiles = computed(() => [
  { key: 'pending', label: '待处理', value: summary.value.pending },
  { key: 'processing', label: '处理中', value: summary.value.processing },
  { key: 'resolved', label: '已解决', value: summary.value.resolved },
  { key: 'ignored', label: '已忽略', value: summary.value.ignored },
  { key: 'falsePositive', label: '误报', value: summary.value.falsePositive }
])
// conic-gradient 误报率环
const ringBg = computed(() => {
  const pct = Math.min(100, summary.value.fpRate)
  return `conic-gradient(var(--tg-danger) 0% ${pct}%, rgba(194, 94, 76, 0.12) ${pct}% 100%)`
})

const colCards = (key) => records.value.filter(r => (r.status || 'pending') === key)
const nextStatuses = (s) => FLOW[s || 'pending'] || []
const statusLabel = (s) => LABELS[s || 'pending'] || s
const fmtDay = (t) => (t ? String(t).replace('T', ' ').slice(5, 16) : '')

const openDetail = (c) => { current.value = c; drawer.value = true }

const move = async (c, code) => {
  try {
    await insightApi.updateTicketStatus(c.id, code)
    ElMessage.success(`已流转为「${statusLabel(code)}」`)
    drawer.value = false
    await load()
  } catch (e) {
    ElMessage.error(e?.message || '流转失败')
  }
}

const load = async () => {
  loading.value = true
  try {
    const data = await insightApi.board({
      pageNum: 1, pageSize: 200,
      projectId: filterProject.value || undefined,
      level: filterLevel.value || undefined,
      q: keyword.value || undefined
    })
    summary.value = data.summary || summary.value
    records.value = data.records || []
  } catch (e) {
    ElMessage.error(e?.message || '加载缺陷工单失败')
  } finally {
    loading.value = false
  }
}

onChartThemeChange(() => { sparkChart?.dispose(); sparkChart = null; loadSpark() })

onMounted(async () => {
  try {
    projects.value = await projectApi.list() || []
  } catch { /* 项目列表失败不阻塞看板 */ }
  await load()
  loadSpark()
  window.addEventListener('resize', onSparkResize)
})

// ===== 近 14 天新增 sparkline =====
const sparkRef = ref(null)
const sparkTotal = ref(0)
let sparkChart = null

const loadSpark = async () => {
  try {
    const t = await insightApi.trend(14)
    const daily = (t.days || []).map((d, i) => Number(t.serious?.[i] || 0) + Number(t.general?.[i] || 0))
    sparkTotal.value = daily.reduce((a, b) => a + b, 0)
    if (!sparkRef.value) return
    if (!sparkChart) sparkChart = echarts.init(sparkRef.value, chartThemeName())
    sparkChart.setOption({
      grid: { left: 2, right: 2, top: 5, bottom: 0 },
      tooltip: {
        trigger: 'axis',
        backgroundColor: chartTooltipBg(),
        borderColor: 'rgba(201, 155, 63, 0.3)',
        textStyle: { color: chartTitleColor(), fontSize: 11.5 },
        formatter: (ps) => `${ps[0].name}<br/>新增 ${ps[0].value} 个`
      },
      xAxis: { type: 'category', data: t.days || [], show: false },
      yAxis: { type: 'value', show: false },
      series: [{
        type: 'line', smooth: true, symbol: 'circle', symbolSize: 4, showSymbol: false,
        data: daily,
        lineStyle: { color: '#C99B3F', width: 2 },
        itemStyle: { color: '#8F6B22' },
        areaStyle: {
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: 'rgba(201, 155, 63, 0.32)' },
            { offset: 1, color: 'rgba(201, 155, 63, 0.02)' }
          ])
        }
      }]
    })
  } catch { /* sparkline 失败不阻塞看板 */ }
}
const onSparkResize = () => sparkChart && sparkChart.resize()

onBeforeUnmount(() => {
  window.removeEventListener('resize', onSparkResize)
  if (sparkChart) { sparkChart.dispose(); sparkChart = null }
})
</script>

<style scoped>
/* ===== 工单治理台：工具条 + 度量带 + 五列看板（区别于全站表格页版式） ===== */
.tk-page { display: flex; flex-direction: column; gap: 18px; }

.tk-topbar { display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; flex-wrap: wrap; }
.tk-title { margin: 0; font-size: 26px; font-weight: 700; letter-spacing: -0.02em; color: var(--tg-text-primary); }
.tk-sub { margin: 6px 0 0; font-size: 13px; color: var(--tg-text-secondary); }
.tk-sub__dot { margin: 0 8px; opacity: 0.5; }
.tk-topbar__tools { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.tk-project-select { width: 200px; }

/* 等级切换：分段按钮（非 el-radio-button，避免与既有页组件一致） */
.tk-level-switch { display: inline-flex; padding: 3px; border-radius: 999px; background: rgba(0, 0, 0, 0.045); }
.tk-level-switch button {
  border: none; background: transparent; padding: 6px 16px; border-radius: 999px;
  font-size: 12.5px; color: var(--tg-text-secondary); cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease, box-shadow 0.2s ease;
}
.tk-level-switch button.on {
  background: var(--tg-accent-gradient); color: #fff; font-weight: 500;
  box-shadow: var(--tg-glow-accent);
}
.tk-search { width: 220px; }

/* 度量带：暖金+陶土+蓝灰低透明光晕（区别于简报页的金橄榄组合） */
.tk-metrics {
  display: flex; align-items: stretch; gap: 26px; padding: 18px 22px;
  border-radius: 20px;
  background:
    radial-gradient(120% 170% at 0% 0%, rgba(232, 200, 119, 0.22), transparent 50%),
    radial-gradient(110% 160% at 100% 0%, rgba(194, 94, 76, 0.1), transparent 55%),
    radial-gradient(100% 150% at 50% 120%, rgba(110, 147, 176, 0.12), transparent 58%),
    var(--tg-card-highlight);
  border: 1px solid rgba(255, 255, 255, 0.72);
  box-shadow: var(--tg-shadow-card);
}
.tk-tiles { flex: 1; display: grid; grid-template-columns: repeat(5, 1fr); gap: 12px; }
.tk-tile { border-radius: 14px; padding: 14px 16px; display: flex; flex-direction: column; gap: 2px; }
.tk-tile b { font-size: 26px; font-weight: 700; line-height: 1.1; color: var(--tg-text-primary); font-variant-numeric: tabular-nums; }
.tk-tile span { font-size: 12px; color: var(--tg-text-secondary); }
.tk-tile--pending { background: rgba(232, 155, 60, 0.13); }
.tk-tile--processing { background: rgba(201, 155, 63, 0.15); }
.tk-tile--resolved { background: rgba(107, 142, 78, 0.13); }
.tk-tile--ignored { background: rgba(0, 0, 0, 0.045); }
.tk-tile--falsePositive { background: rgba(194, 94, 76, 0.12); }

.tk-ringzone { display: flex; align-items: center; gap: 18px; padding-left: 24px; border-left: 1px dashed var(--tg-border); }
.tk-ring { width: 96px; height: 96px; border-radius: 50%; display: grid; place-items: center; flex-shrink: 0; }
.tk-ring__hole {
  width: 68px; height: 68px; border-radius: 50%; background: var(--tg-bg-card);
  display: flex; flex-direction: column; align-items: center; justify-content: center;
}
.tk-ring__hole b { font-size: 19px; font-weight: 700; color: var(--tg-danger); line-height: 1; font-variant-numeric: tabular-nums; }
.tk-ring__hole b i { font-style: normal; font-size: 11px; margin-left: 1px; }
.tk-ring__hole span { font-size: 10.5px; color: var(--tg-text-secondary); margin-top: 3px; }
.tk-ringzone__side { margin: 0; display: flex; flex-direction: column; gap: 7px; }
.tk-ringzone__side div { display: flex; align-items: baseline; gap: 10px; }
.tk-ringzone__side dt { font-size: 11.5px; color: var(--tg-text-secondary); width: 52px; }
.tk-ringzone__side dd { margin: 0; font-size: 16px; font-weight: 700; color: var(--tg-text-primary); font-variant-numeric: tabular-nums; }

/* 看板：五列 */
.tk-board { display: grid; grid-template-columns: repeat(5, 1fr); gap: 14px; align-items: start; }
.tk-col { border-radius: 16px; background: rgba(255, 255, 255, 0.55); border: 1px solid var(--tg-border); overflow: hidden; }
.tk-col__head {
  display: flex; align-items: center; gap: 8px; padding: 12px 14px;
  border-bottom: 1px solid var(--tg-border); background: rgba(255, 255, 255, 0.5);
}
.tk-col__dot { width: 8px; height: 8px; border-radius: 50%; }
.tk-col--pending .tk-col__dot { background: var(--tg-warning); }
.tk-col--processing .tk-col__dot { background: var(--tg-gold); }
.tk-col--resolved .tk-col__dot { background: var(--tg-success); }
.tk-col--ignored .tk-col__dot { background: var(--tg-slate); }
.tk-col--falsePositive .tk-col__dot { background: var(--tg-danger); }
.tk-col__name { font-size: 13px; font-weight: 600; color: var(--tg-text-primary); }
.tk-col__count {
  margin-left: auto; font-style: normal; font-size: 11.5px; font-weight: 600;
  padding: 1px 8px; border-radius: 999px; background: rgba(0, 0, 0, 0.055); color: var(--tg-text-secondary);
  font-variant-numeric: tabular-nums;
}
.tk-col__scroll { padding: 10px; display: flex; flex-direction: column; gap: 9px; min-height: 120px; max-height: 56vh; overflow-y: auto; }
.tk-col__empty { margin: 20px 0; text-align: center; font-size: 12px; color: var(--tg-slate); }

/* 卡片：严重度左脊 + 项目名 + 描述两行截断 */
.tk-card {
  position: relative; padding: 11px 12px 9px 16px; border-radius: 12px;
  background: var(--tg-bg-card); border: 1px solid var(--tg-border);
  box-shadow: 0 1px 3px rgba(60, 45, 25, 0.05); cursor: pointer;
  transition: transform 0.25s var(--tg-ease), box-shadow 0.25s ease, border-color 0.2s ease;
}
.tk-card:hover { transform: translateY(-2px); border-color: rgba(201, 155, 63, 0.4); box-shadow: 0 8px 20px rgba(60, 45, 25, 0.1); }
.tk-card__spine { position: absolute; left: 8px; top: 10px; bottom: 10px; width: 3.5px; border-radius: 4px; background: var(--tg-slate); }
.tk-card.is-serious .tk-card__spine { background: var(--tg-danger); }
.tk-card.is-general .tk-card__spine { background: var(--tg-teal); }
.tk-card__head { display: flex; align-items: center; gap: 6px; margin-bottom: 5px; min-width: 0; }
.tk-card__project { font-size: 11px; color: var(--tg-text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.tk-card__type {
  flex-shrink: 0; margin-left: auto; font-size: 10.5px; padding: 1px 7px; border-radius: 999px;
  background: var(--el-color-primary-light-9); color: var(--tg-accent); max-width: 88px;
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.tk-card__reason {
  margin: 0; font-size: 12.5px; line-height: 1.5; color: var(--tg-text-primary);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.tk-card__foot { display: flex; align-items: center; justify-content: space-between; margin-top: 8px; }
.tk-card__foot time { font-size: 10.5px; color: var(--tg-slate); font-variant-numeric: tabular-nums; }
.tk-card__move {
  display: inline-flex; align-items: center; gap: 3px; border: none; cursor: pointer;
  font-size: 11.5px; color: var(--tg-accent); background: var(--el-color-primary-light-9);
  padding: 3px 10px; border-radius: 999px; transition: background 0.2s ease, transform 0.2s var(--tg-ease-spring);
}
.tk-card__move:hover { background: var(--el-color-primary-light-8); transform: translateY(-1px); }

/* 近 14 天 sparkline 横幅 */
.tk-spark {
  display: flex; align-items: center; gap: 16px;
  padding: 8px 20px; border-radius: 16px;
  background: rgba(255, 255, 255, 0.55); border: 1px solid var(--tg-border);
}
.tk-spark__label { flex-shrink: 0; font-size: 12px; font-weight: 600; color: var(--tg-text-secondary); letter-spacing: 0.04em; }
.tk-spark__chart { flex: 1; height: 54px; min-width: 0; }
.tk-spark__total { flex-shrink: 0; font-size: 20px; font-weight: 700; color: var(--tg-accent); font-variant-numeric: tabular-nums; }
.tk-spark__total small { font-size: 11px; font-weight: 400; color: var(--tg-slate); margin-left: 3px; }

/* 详情抽屉 */
.tk-detail { display: flex; flex-direction: column; gap: 16px; }
.tk-detail__meta { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.tk-detail__level { font-size: 11.5px; font-weight: 600; padding: 2px 10px; border-radius: 999px; }
.tk-detail__level.is-serious { background: rgba(194, 94, 76, 0.12); color: #9a3f30; }
.tk-detail__level.is-general { background: rgba(154, 156, 107, 0.16); color: #55682e; }
.tk-detail__status { font-size: 11.5px; padding: 2px 10px; border-radius: 999px; background: rgba(0, 0, 0, 0.05); color: var(--tg-text-secondary); }
.tk-detail__project { font-size: 12px; color: var(--tg-text-secondary); }
.tk-detail__type { margin: 0; font-size: 18px; font-weight: 700; color: var(--tg-text-primary); }
.tk-detail__block h4 { margin: 0 0 6px; font-size: 12px; font-weight: 600; color: var(--tg-text-secondary); letter-spacing: 0.04em; }
.tk-detail__block p { margin: 0; font-size: 13px; line-height: 1.7; color: var(--tg-text-primary); }
.tk-detail__req { background: var(--tg-bg-code); border-radius: 10px; padding: 10px 12px; }
.tk-detail__flows { display: flex; gap: 8px; flex-wrap: wrap; }
.tk-detail__terminal { font-size: 12px; color: var(--tg-slate); }

@media (max-width: 1280px) {
  .tk-board { grid-template-columns: repeat(3, 1fr); }
  .tk-tiles { grid-template-columns: repeat(5, 1fr); }
}
@media (max-width: 900px) {
  .tk-board { grid-template-columns: 1fr; }
  .tk-metrics { flex-direction: column; }
  .tk-ringzone { border-left: none; border-top: 1px dashed var(--tg-border); padding: 16px 0 0; }
}
</style>
