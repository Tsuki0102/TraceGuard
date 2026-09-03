<template>
  <div class="page rh-page">
    <!-- 档案馆页头：标题 + 档案概览统计 -->
    <header class="rh-head">
      <div>
        <h1 class="rh-title">报告中心</h1>
        <p class="rh-sub">项目档案柜 · 选择左侧档案脊，右侧格架内取用报告</p>
      </div>
      <div v-if="projects.length" class="rh-head__stats" aria-label="档案概览统计">
        <div class="rh-stat">
          <span class="rh-stat__num">{{ projects.length }}</span>
          <span class="rh-stat__label">档案总数</span>
        </div>
        <div class="rh-stat">
          <span class="rh-stat__num">{{ analyzedCount }}</span>
          <span class="rh-stat__label">已归档</span>
        </div>
        <div class="rh-stat">
          <span class="rh-stat__num">{{ pendingCount }}</span>
          <span class="rh-stat__label">待分析</span>
        </div>
      </div>
    </header>

    <div class="rh-body">
      <!-- 左：项目档案脊（竖排标签，选中项外扩） -->
      <aside class="rh-shelf" :aria-busy="loadingProjects">
        <template v-if="loadingProjects">
          <div v-for="i in 5" :key="'sk' + i" class="rh-spine-skel" aria-hidden="true"></div>
        </template>
        <template v-else>
          <button v-for="p in projects" :key="p.id" type="button" class="rh-spine"
            :class="{ on: current?.id === p.id }" :aria-pressed="current?.id === p.id"
            @click="selectProject(p)">
            <span class="rh-spine__label">{{ p.projectName }}</span>
            <span class="rh-spine__meta">
              <i class="rh-dot" :class="'rh-dot--' + statusTone(p.status)" aria-hidden="true"></i>{{ statusText(p.status) }}
            </span>
          </button>
          <div v-if="!projects.length" class="rh-shelf__empty">
            <p>暂无项目档案</p>
            <button type="button" class="rh-retry" @click="loadProjects">重新加载</button>
          </div>
        </template>
      </aside>

      <!-- 右：格架 -->
      <main class="rh-cabinet" v-loading="loadingArtifacts">
        <template v-if="current">
          <div class="rh-cabinet__head">
            <h2 class="rh-cabinet__name">{{ current.projectName }}</h2>
            <span class="rh-cabinet__tag" :class="{ 'is-ready': current.status === 'analyzed' }">
              {{ current.status === 'analyzed' ? '档案完整可取' : '建议先完成一次分析' }}
            </span>
          </div>

          <!-- 报告格架：档案夹卡片 -->
          <section class="rh-grid" aria-label="可导出报告列表">
            <div v-for="(f, idx) in folders" :key="f.key" class="rh-folder"
              :class="'rh-folder--' + f.tone" :style="{ '--i': idx }">
              <div class="rh-folder__top">
                <span class="rh-folder__badge">
                  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"
                    stroke-linecap="round" stroke-linejoin="round" aria-hidden="true" v-html="f.icon"></svg>
                  {{ f.badge }}
                </span>
                <button type="button" class="rh-folder__get" :disabled="busy === f.key"
                  :aria-busy="busy === f.key" @click="download(f)">
                  {{ busy === f.key ? '取档中…' : '取档' }}
                </button>
              </div>
              <h3 class="rh-folder__name">{{ f.name }}</h3>
              <p class="rh-folder__desc">{{ f.desc }}</p>
            </div>
          </section>

          <!-- 跨项目对比区：左选择 + 右覆盖率对比图 -->
          <section class="rh-compare">
            <div class="rh-compare__left">
              <div class="rh-compare__head">
                <h3 class="rh-compare__title">跨项目对比档案</h3>
                <span class="rh-compare__hint">已选 {{ compareIds.length }} / 6 · 生成一张对比统计表</span>
              </div>
              <div class="rh-compare__chips">
                <button v-for="p in projects" :key="p.id" type="button" class="rh-chip"
                  :class="{ on: compareIds.includes(p.id) }"
                  :aria-pressed="compareIds.includes(p.id)" @click="toggleCompare(p.id)">
                  {{ p.projectName }}
                </button>
              </div>
              <div class="rh-compare__actions">
                <el-button type="primary" round :disabled="compareIds.length < 2 || busy === 'compare'"
                  :aria-busy="busy === 'compare'" @click="downloadCompare">
                  {{ busy === 'compare' ? '生成中…' : '导出对比 Excel' }}
                </el-button>
                <el-button v-if="compareIds.length" round text @click="compareIds = []">清空选择</el-button>
              </div>
            </div>
            <div class="rh-compare__viz">
              <span class="rh-compare__vizlabel">已选项目需求覆盖率对比</span>
              <div v-if="sortedPicked.length" ref="covRef" class="rh-compare__chart"
                :style="{ height: covHeight }" role="img"
                :aria-label="`需求覆盖率对比图，共 ${sortedPicked.length} 个项目，按覆盖率从高到低排列`"></div>
              <div v-else class="rh-compare__empty">在左侧选择 2 个及以上项目后，此处展示覆盖率对比</div>
            </div>
          </section>
        </template>

        <div v-else-if="!loadingArtifacts" class="rh-cabinet__placeholder">
          <svg class="rh-cabinet__placeholder-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor"
            stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
            <rect x="2" y="3" width="20" height="5" rx="1" />
            <path d="M4 8v11a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8" />
            <path d="M10 12h4" />
          </svg>
          <p>从左侧档案脊选择一个项目</p>
          <small>综合报告（Word / PDF）与各类明细档案将出现在这里</small>
        </div>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import * as echarts from 'echarts'
import { chartThemeName, chartText, chartTitleColor, chartTrack, chartTooltipBg, onChartThemeChange } from '@/utils/echartsTheme'
import { ElMessage } from 'element-plus'
import { projectApi, exportApi } from '@/api'

const loadingProjects = ref(false)
const loadingArtifacts = ref(false)
const projects = ref([])
const current = ref(null)
const busy = ref('')
const compareIds = ref([])

// 档案夹清单：key 对应既有导出端点，tone 决定徽标色，icon 为 Lucide 线性图标路径
const folders = [
  { key: 'word', badge: 'DOC', tone: 'blue', name: '综合报告（Word）', desc: '一致性校验与缺陷检测完整报告，可编辑',
    icon: '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="M10 9H8"/><path d="M16 13H8"/><path d="M16 17H8"/>',
    call: () => exportApi.reportWord(current.value.id) },
  { key: 'pdf', badge: 'PDF', tone: 'rose', name: '综合报告（PDF）', desc: '排版定稿的正式报告，适合提交归档',
    icon: '<path d="M15 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V7Z"/><path d="M14 2v4a2 2 0 0 0 2 2h4"/><path d="m9 15 2 2 4-4"/>',
    call: () => exportApi.reportPdf(current.value.id) },
  { key: 'defects', badge: 'XLS', tone: 'gold', name: '缺陷清单', desc: '需求一致性缺陷全量明细',
    icon: '<path d="m21.73 18-8-14a2 2 0 0 0-3.48 0l-8 14A2 2 0 0 0 4 21h16a2 2 0 0 0 1.73-3Z"/><path d="M12 9v4"/><path d="M12 17h.01"/>',
    call: () => exportApi.defectsExcel(current.value.id) },
  { key: 'trace', badge: 'XLS', tone: 'gold', name: '正向追溯矩阵', desc: '需求 → 代码 双向追溯（正向）',
    icon: '<path d="M5 12h14"/><path d="m12 5 7 7-7 7"/>',
    call: () => exportApi.traceabilityExcel(current.value.id) },
  { key: 'traceRev', badge: 'XLS', tone: 'gold', name: '逆向追溯矩阵', desc: '代码 → 需求 覆盖与孤儿实现',
    icon: '<path d="M19 12H5"/><path d="m12 19-7-7 7-7"/>',
    call: () => exportApi.traceabilityReverseExcel(current.value.id) },
  { key: 'codeDefects', badge: 'XLS', tone: 'sage', name: '代码质量缺陷', desc: 'SQL 注入 / 死循环 / 资源泄露等基础缺陷',
    icon: '<path d="m8 2 1.88 1.88"/><path d="M14.12 3.88 16 2"/><path d="M9 7.13v-1a3.003 3.003 0 1 1 6 0v1"/><path d="M12 20c-3.3 0-6-2.7-6-6v-3a4 4 0 0 1 4-4h4a4 4 0 0 1 4 4v3c0 3.3-2.7 6-6 6Z"/><path d="M12 20v-9"/><path d="M6.53 9C4.6 8.8 3 7.1 3 5"/><path d="M6 13H2"/><path d="M3 21c0-2.1 1.7-3.9 3.8-4"/><path d="M20.97 5c0 2.1-1.6 3.8-3.5 4"/><path d="M22 13h-4"/><path d="M17.2 17c2.1.1 3.8 1.9 3.8 4"/>',
    call: () => exportApi.codeDefectsExcel(current.value.id) },
  { key: 'statistics', badge: 'XLS', tone: 'sage', name: '统计报表', desc: '项目级统计汇总数据',
    icon: '<path d="M3 3v16a2 2 0 0 0 2 2h16"/><path d="M18 17V9"/><path d="M13 17V5"/><path d="M8 17v-3"/>',
    call: () => exportApi.statisticsExcel(current.value.id) }
]

// ===== 状态元信息 =====
const statusText = (s) => ({ analyzed: '已分析', created: '待分析', running: '分析中', failed: '失败' }[s] || s)
const statusTone = (s) => ({ analyzed: 'sage', running: 'gold', created: 'slate', failed: 'rose' }[s] || 'slate')

const analyzedCount = computed(() => projects.value.filter(p => p.status === 'analyzed').length)
const pendingCount = computed(() => projects.value.filter(p => p.status !== 'analyzed').length)

const selectProject = (p) => { current.value = p }

const loadProjects = async () => {
  loadingProjects.value = true
  try {
    projects.value = await projectApi.list() || []
    if (projects.value.length && !current.value) current.value = projects.value[0]
  } catch (e) {
    ElMessage.error(e?.message || '加载项目档案失败')
  } finally {
    loadingProjects.value = false
  }
}

const download = async (f) => {
  busy.value = f.key
  try {
    await f.call()
    ElMessage.success('「' + f.name + '」已开始下载')
  } catch (e) {
    ElMessage.error(e?.message || '取档失败：请确认项目已完成分析')
  } finally {
    busy.value = ''
  }
}

const toggleCompare = (id) => {
  const i = compareIds.value.indexOf(id)
  if (i >= 0) compareIds.value.splice(i, 1)
  else if (compareIds.value.length < 6) compareIds.value.push(id)
  else ElMessage.warning('最多同时对比 6 个项目')
}

const downloadCompare = async () => {
  busy.value = 'compare'
  try {
    await exportApi.compareExcel(compareIds.value)
    ElMessage.success('对比统计 Excel 已开始下载')
  } catch (e) {
    ElMessage.error(e?.message || '生成对比档案失败')
  } finally {
    busy.value = ''
  }
}

onChartThemeChange(() => { covChart?.dispose(); covChart = null; renderCov() })

onMounted(() => {
  loadProjects()
  window.addEventListener('resize', onCovResize)
})

// ===== 已选项目覆盖率对比图（按覆盖率降序，分档着色 + 直接数值标签） =====
const covRef = ref(null)
let covChart = null

const picked = computed(() => projects.value.filter(p => compareIds.value.includes(p.id)))
const sortedPicked = computed(() =>
  [...picked.value].sort((a, b) => (b.coverageRate ?? 0) - (a.coverageRate ?? 0))
)
const covHeight = computed(() => Math.max(120, sortedPicked.value.length * 42 + 20) + 'px')

watch(compareIds, async () => {
  await nextTick()
  renderCov()
}, { deep: true })

const covColor = (v) => v >= 80
  ? new echarts.graphic.LinearGradient(1, 0, 0, 0, [
      { offset: 0, color: '#C99B3F' }, { offset: 1, color: 'rgba(232, 200, 119, 0.5)' }])
  : v >= 50
    ? new echarts.graphic.LinearGradient(1, 0, 0, 0, [
        { offset: 0, color: '#A98E56' }, { offset: 1, color: 'rgba(201, 168, 92, 0.4)' }])
    : new echarts.graphic.LinearGradient(1, 0, 0, 0, [
        { offset: 0, color: '#9A9C6B' }, { offset: 1, color: 'rgba(154, 156, 107, 0.35)' }])

const renderCov = () => {
  if (!covRef.value || !sortedPicked.value.length) return
  // v-if 切换会重建图表容器 DOM，旧实例绑定的节点已分离，需重建
  if (covChart && covChart.getDom() !== covRef.value) {
    covChart.dispose()
    covChart = null
  }
  if (!covChart) covChart = echarts.init(covRef.value, chartThemeName())
  const list = sortedPicked.value
  covChart.setOption({
    grid: { left: 8, right: 44, top: 6, bottom: 6, containLabel: true },
    tooltip: {
      trigger: 'axis', axisPointer: { type: 'shadow' },
      backgroundColor: chartTooltipBg(),
      borderColor: 'rgba(201, 155, 63, 0.3)',
      textStyle: { color: chartTitleColor(), fontSize: 11.5 },
      valueFormatter: (v) => v + '%'
    },
    xAxis: { type: 'value', max: 100, show: false },
    yAxis: {
      type: 'category',
      inverse: true,
      data: list.map(p => p.projectName.length > 9 ? p.projectName.slice(0, 9) + '…' : p.projectName),
      axisLine: { show: false }, axisTick: { show: false },
      axisLabel: { color: chartText(), fontSize: 11 }
    },
    series: [{
      type: 'bar', barWidth: 12,
      data: list.map(p => {
        const v = p.coverageRate == null ? 0 : Math.round(p.coverageRate * 100)
        return { value: v, itemStyle: { color: covColor(v) } }
      }),
      showBackground: true,
      backgroundStyle: { color: chartTrack(), borderRadius: [0, 7, 7, 0] },
      itemStyle: { borderRadius: [0, 7, 7, 0] },
      label: {
        show: true, position: 'right', fontSize: 11, fontWeight: 600,
        color: chartTitleColor(), formatter: '{c}%', fontFamily: 'inherit'
      },
      animationDuration: 500, animationDelay: (i) => i * 60
    }]
  }, true)
}
const onCovResize = () => covChart && covChart.resize()

onBeforeUnmount(() => {
  window.removeEventListener('resize', onCovResize)
  if (covChart) { covChart.dispose(); covChart = null }
})
</script>

<style scoped>
/* ===== 档案馆：左脊右柜（区别于看板/图鉴/报刊/控制室） ===== */
.rh-page { display: flex; flex-direction: column; gap: 20px; }

/* ---- 页头：标题 + 概览统计 ---- */
.rh-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; flex-wrap: wrap; }
.rh-title { margin: 0; font-size: 26px; font-weight: 700; letter-spacing: -0.02em; color: var(--tg-text-primary); }
.rh-sub { margin: 6px 0 0; font-size: 13px; color: var(--tg-text-secondary); }
.rh-head__stats { display: flex; gap: 10px; }
.rh-stat {
  display: flex; flex-direction: column; align-items: center; gap: 1px;
  padding: 8px 16px; border-radius: 14px;
  background: var(--tg-card-highlight); border: 1px solid var(--tg-border);
  min-width: 72px;
}
.rh-stat__num { font-size: 18px; font-weight: 700; color: var(--tg-text-primary); font-variant-numeric: tabular-nums; line-height: 1.2; }
.rh-stat__label { font-size: 10.5px; letter-spacing: 0.04em; color: var(--tg-slate); }

.rh-body { display: flex; gap: 18px; align-items: flex-start; }

/* ---- 档案脊：窄竖排标签 ---- */
.rh-shelf {
  width: 196px; flex-shrink: 0; display: flex; flex-direction: column; gap: 7px;
  max-height: calc(100vh - 220px); overflow-y: auto; padding: 12px 10px;
  border-radius: 18px; background: linear-gradient(180deg, rgba(255, 255, 255, 0.75), rgba(255, 246, 230, 0.45));
  border: 1px solid rgba(201, 155, 63, 0.16); box-shadow: var(--tg-shadow-card);
}
:root[data-theme='dark'] .rh-shelf { background: rgba(255, 255, 255, 0.04); border-color: rgba(237, 227, 210, 0.1); }
.rh-spine {
  position: relative; border: 1px solid transparent; background: transparent; cursor: pointer; text-align: left;
  padding: 11px 13px; border-radius: 12px; transition: all 0.22s var(--tg-ease);
  display: flex; flex-direction: column; gap: 2px;
}
.rh-spine::before {
  content: ''; position: absolute; left: 0; top: 12px; bottom: 12px; width: 3px; border-radius: 3px;
  background: transparent; transition: background 0.2s ease;
}
.rh-spine:hover { background: rgba(201, 155, 63, 0.08); }
.rh-spine:active { transform: scale(0.985); }
.rh-spine:focus-visible { outline: 2px solid var(--tg-accent); outline-offset: 2px; }
.rh-spine.on {
  background: rgba(255, 255, 255, 0.9); border-color: rgba(201, 155, 63, 0.35);
  box-shadow: 0 6px 16px rgba(60, 45, 25, 0.08);
}
:root[data-theme='dark'] .rh-spine.on { background: rgba(255, 255, 255, 0.06); }
.rh-spine.on::before { background: var(--tg-accent-gradient); }
.rh-spine__label { font-size: 13px; font-weight: 600; color: var(--tg-text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rh-spine__meta { display: inline-flex; align-items: center; gap: 5px; font-size: 10.5px; color: var(--tg-slate); }
.rh-dot { width: 6px; height: 6px; border-radius: 50%; flex-shrink: 0; }
.rh-dot--sage { background: #6B8E4E; }
.rh-dot--gold { background: #C99B3F; animation: rh-pulse 1.6s ease-in-out infinite; }
.rh-dot--slate { background: var(--tg-slate); }
.rh-dot--rose { background: #C97F8A; }
@keyframes rh-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.35; } }

/* 骨架屏（加载态） */
.rh-spine-skel {
  height: 52px; border-radius: 12px;
  background: linear-gradient(100deg, rgba(201, 155, 63, 0.07) 40%, rgba(201, 155, 63, 0.16) 50%, rgba(201, 155, 63, 0.07) 60%);
  background-size: 200% 100%; animation: rh-shimmer 1.3s linear infinite;
}
@keyframes rh-shimmer { from { background-position: 130% 0; } to { background-position: -70% 0; } }

.rh-shelf__empty { padding: 24px 0 18px; text-align: center; }
.rh-shelf__empty p { margin: 0 0 10px; font-size: 12px; color: var(--tg-slate); }
.rh-retry {
  border: 1px solid rgba(201, 155, 63, 0.4); background: transparent; cursor: pointer;
  color: var(--tg-accent); font-size: 12px; padding: 5px 14px; border-radius: 999px;
  transition: background 0.2s ease, transform 0.2s var(--tg-ease-spring);
}
.rh-retry:hover { background: rgba(201, 155, 63, 0.1); }
.rh-retry:focus-visible { outline: 2px solid var(--tg-accent); outline-offset: 2px; }

/* ---- 档案柜：暖金+藤紫+蓝灰光晕（第三种色调组合） ---- */
.rh-cabinet {
  flex: 1; min-width: 0; border-radius: 20px; padding: 22px 24px;
  background:
    radial-gradient(120% 160% at 0% 0%, rgba(232, 200, 119, 0.22), transparent 50%),
    radial-gradient(110% 150% at 100% 0%, rgba(154, 127, 176, 0.14), transparent 55%),
    radial-gradient(100% 140% at 50% 115%, rgba(110, 147, 176, 0.12), transparent 58%),
    var(--tg-card-highlight);
  border: 1px solid rgba(255, 255, 255, 0.72);
  box-shadow: var(--tg-shadow-card); min-height: 420px;
}
:root[data-theme='dark'] .rh-cabinet { border-color: rgba(237, 227, 210, 0.1); }
.rh-cabinet__head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.rh-cabinet__name { margin: 0; font-size: 18px; font-weight: 700; color: var(--tg-text-primary); }
.rh-cabinet__tag {
  font-size: 11.5px; padding: 3px 12px; border-radius: 999px;
  background: rgba(232, 155, 60, 0.13); color: #9a5d12; font-weight: 500;
}
:root[data-theme='dark'] .rh-cabinet__tag { color: #E5B96E; }
.rh-cabinet__tag.is-ready { background: rgba(107, 142, 78, 0.13); color: #55682e; }
:root[data-theme='dark'] .rh-cabinet__tag.is-ready { color: #A9C083; }

/* ---- 档案夹格架 ---- */
.rh-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(226px, 1fr)); gap: 13px; }
.rh-folder {
  position: relative; overflow: hidden;
  border-radius: 14px; padding: 14px 15px 13px 17px; border: 1px solid var(--tg-border);
  background: linear-gradient(170deg, rgba(255, 255, 255, 0.9), rgba(250, 246, 239, 0.6));
  transition: transform 0.25s var(--tg-ease), box-shadow 0.25s ease, border-color 0.2s ease;
  animation: rh-rise 0.45s var(--tg-ease) backwards;
  animation-delay: calc(var(--i, 0) * 45ms);
}
:root[data-theme='dark'] .rh-folder { background: linear-gradient(170deg, rgba(255, 255, 255, 0.055), rgba(255, 255, 255, 0.025)); }
@keyframes rh-rise { from { opacity: 0; transform: translateY(10px); } to { opacity: 1; transform: none; } }
.rh-folder::before {
  content: ''; position: absolute; left: 0; top: 10px; bottom: 10px; width: 3px; border-radius: 3px;
  background: var(--folder-tone, var(--tg-slate)); opacity: 0.75; transition: opacity 0.2s ease;
}
.rh-folder:hover { transform: translateY(-3px); border-color: rgba(201, 155, 63, 0.38); box-shadow: 0 12px 26px rgba(60, 45, 25, 0.1); }
.rh-folder:hover::before { opacity: 1; }
.rh-folder--blue { --folder-tone: #6E93B0; }
.rh-folder--rose { --folder-tone: #C97F8A; }
.rh-folder--gold { --folder-tone: #C99B3F; }
.rh-folder--sage { --folder-tone: #9A9C6B; }
.rh-folder__top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 9px; gap: 8px; }
.rh-folder__badge {
  display: inline-flex; align-items: center; gap: 5px;
  font-family: var(--tg-font-mono); font-size: 10px; font-weight: 700; letter-spacing: 0.06em;
  padding: 3px 9px 3px 7px; border-radius: 7px; color: #fff;
  background: color-mix(in srgb, var(--folder-tone, var(--tg-slate)) 88%, transparent);
}
.rh-folder__badge svg { width: 12px; height: 12px; }
.rh-folder__get {
  border: none; cursor: pointer; font-size: 11.5px; font-weight: 500;
  color: var(--tg-accent); background: var(--el-color-primary-light-9);
  padding: 4px 13px; border-radius: 999px; transition: background 0.2s ease, transform 0.2s var(--tg-ease-spring);
}
.rh-folder__get:hover:not(:disabled) { background: var(--el-color-primary-light-8); transform: translateY(-1px); }
.rh-folder__get:active:not(:disabled) { transform: scale(0.95); }
.rh-folder__get:focus-visible { outline: 2px solid var(--tg-accent); outline-offset: 2px; }
.rh-folder__get:disabled { opacity: 0.6; cursor: wait; }
.rh-folder__name { margin: 0 0 4px; font-size: 14px; font-weight: 600; color: var(--tg-text-primary); }
.rh-folder__desc {
  margin: 0; font-size: 11.5px; line-height: 1.55; color: var(--tg-text-secondary);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}

/* ---- 跨项目对比：左选择 + 右可视化 ---- */
.rh-compare { margin-top: 22px; padding-top: 18px; border-top: 1px dashed var(--tg-border); display: flex; gap: 24px; align-items: flex-start; }
.rh-compare__left { flex: 1; min-width: 0; }
.rh-compare__viz {
  width: 320px; flex-shrink: 0; display: flex; flex-direction: column; gap: 6px;
  padding: 12px 14px; border-radius: 14px;
  background: rgba(255, 255, 255, 0.6); border: 1px dashed rgba(201, 155, 63, 0.3);
}
:root[data-theme='dark'] .rh-compare__viz { background: rgba(255, 255, 255, 0.04); }
.rh-compare__vizlabel { font-size: 11px; font-weight: 600; letter-spacing: 0.06em; color: var(--tg-slate); }
.rh-compare__chart { transition: height 0.3s var(--tg-ease); }
.rh-compare__empty {
  display: flex; align-items: center; justify-content: center; text-align: center;
  min-height: 120px; padding: 12px; font-size: 11.5px; line-height: 1.6; color: var(--tg-slate);
}
.rh-compare__head { display: flex; align-items: baseline; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.rh-compare__title { margin: 0; font-size: 15px; font-weight: 600; color: var(--tg-text-primary); }
.rh-compare__hint { font-size: 11.5px; color: var(--tg-slate); font-variant-numeric: tabular-nums; }
.rh-compare__chips { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 14px; }
.rh-chip {
  border: 1px solid var(--tg-border); background: rgba(255, 255, 255, 0.6); cursor: pointer;
  padding: 6px 14px; border-radius: 999px; font-size: 12px; color: var(--tg-text-secondary);
  transition: all 0.2s var(--tg-ease-spring);
}
.rh-chip:hover { border-color: rgba(201, 155, 63, 0.4); color: var(--tg-accent); transform: translateY(-1px); }
:root[data-theme='dark'] .rh-chip { background: rgba(255, 255, 255, 0.06); border-color: rgba(237, 227, 210, 0.16); }
.rh-chip:active { transform: scale(0.96); }
.rh-chip:focus-visible { outline: 2px solid var(--tg-accent); outline-offset: 2px; }
.rh-chip.on {
  background: var(--tg-accent-gradient); border-color: transparent; color: #fff; font-weight: 500;
  box-shadow: var(--tg-glow-accent);
}
.rh-compare__actions { display: flex; align-items: center; gap: 8px; }

/* ---- 空态占位 ---- */
.rh-cabinet__placeholder { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; padding: 110px 0; }
.rh-cabinet__placeholder-icon { width: 44px; height: 44px; color: var(--tg-slate); opacity: 0.55; margin-bottom: 6px; }
.rh-cabinet__placeholder p { margin: 0; font-size: 15px; font-weight: 600; color: var(--tg-text-secondary); }
.rh-cabinet__placeholder small { font-size: 12px; color: var(--tg-slate); }

/* ---- 动效偏好：减弱动画 ---- */
@media (prefers-reduced-motion: reduce) {
  .rh-folder { animation: none; }
  .rh-spine-skel { animation: none; opacity: 0.6; }
  .rh-dot--gold { animation: none; }
  .rh-folder, .rh-spine, .rh-chip, .rh-folder__get, .rh-retry, .rh-compare__chart { transition: none; }
}

/* ---- 响应式：1100px 对比区堆叠 / 900px 双栏转单栏 / 640px 紧凑 ---- */
@media (max-width: 1100px) {
  .rh-compare { flex-direction: column; }
  .rh-compare__viz { width: 100%; }
}

@media (max-width: 900px) {
  .rh-body { flex-direction: column; }
  .rh-shelf { width: 100%; flex-direction: row; overflow-x: auto; max-height: none; padding: 10px; }
  .rh-spine { min-width: 150px; }
  .rh-spine-skel { width: 150px; flex-shrink: 0; height: 100%; min-height: 52px; }
}

@media (max-width: 640px) {
  .rh-page { gap: 14px; }
  .rh-head { align-items: flex-start; flex-direction: column; }
  .rh-cabinet { padding: 16px 14px; min-height: 320px; }
  .rh-grid { grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 10px; }
  .rh-stat { padding: 6px 12px; min-width: 60px; }
  .rh-cabinet__placeholder { padding: 70px 0; }
}
</style>
