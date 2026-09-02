<template>
  <div class="page rh-page">
    <!-- 档案馆页头 -->
    <header class="rh-head">
      <div>
        <h1 class="rh-title">报告中心</h1>
        <p class="rh-sub">项目档案柜 · 选择左侧档案脊，右侧格架内取用报告</p>
      </div>
    </header>

    <div class="rh-body">
      <!-- 左：项目档案脊（竖排标签，选中项外扩） -->
      <aside class="rh-shelf" v-loading="loadingProjects">
        <button v-for="p in projects" :key="p.id" type="button" class="rh-spine"
          :class="{ on: current?.id === p.id }" @click="selectProject(p)">
          <span class="rh-spine__label">{{ p.projectName }}</span>
          <span class="rh-spine__meta">{{ p.status === 'analyzed' ? '已分析' : p.status === 'created' ? '待分析' : p.status === 'running' ? '分析中' : p.status === 'failed' ? '失败' : p.status }}</span>
        </button>
        <div v-if="!loadingProjects && !projects.length" class="rh-shelf__empty">暂无项目档案</div>
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
          <section class="rh-grid">
            <div v-for="f in folders" :key="f.key" class="rh-folder" :class="'rh-folder--' + f.tone">
              <div class="rh-folder__top">
                <span class="rh-folder__badge">{{ f.badge }}</span>
                <button type="button" class="rh-folder__get" :disabled="busy === f.key" @click="download(f)">
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
                  :class="{ on: compareIds.includes(p.id) }" @click="toggleCompare(p.id)">
                  {{ p.projectName }}
                </button>
              </div>
              <div class="rh-compare__actions">
                <el-button type="primary" round :disabled="compareIds.length < 2 || busy === 'compare'" @click="downloadCompare">
                  {{ busy === 'compare' ? '生成中…' : '导出对比 Excel' }}
                </el-button>
                <el-button v-if="compareIds.length" round text @click="compareIds = []">清空选择</el-button>
              </div>
            </div>
            <div class="rh-compare__viz">
              <span class="rh-compare__vizlabel">已选项目需求覆盖率对比</span>
              <div ref="covRef" class="rh-compare__chart"></div>
            </div>
          </section>
        </template>

        <div v-else class="rh-cabinet__placeholder">
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
import { chartThemeName, chartText, chartFaint, chartAxisLine, chartSplitLine, chartTooltipBg, chartTitleColor, onChartThemeChange } from '@/utils/echartsTheme'
import { ElMessage } from 'element-plus'
import { projectApi, exportApi } from '@/api'

const loadingProjects = ref(false)
const loadingArtifacts = ref(false)
const projects = ref([])
const current = ref(null)
const busy = ref('')
const compareIds = ref([])

// 档案夹清单：key 对应既有导出端点，tone 决定徽标色
const folders = [
  { key: 'word', badge: 'DOC', tone: 'blue', name: '综合报告（Word）', desc: '一致性校验与缺陷检测完整报告，可编辑', call: () => exportApi.reportWord(current.value.id) },
  { key: 'pdf', badge: 'PDF', tone: 'rose', name: '综合报告（PDF）', desc: '排版定稿的正式报告，适合提交归档', call: () => exportApi.reportPdf(current.value.id) },
  { key: 'defects', badge: 'XLS', tone: 'gold', name: '缺陷清单', desc: '需求一致性缺陷全量明细', call: () => exportApi.defectsExcel(current.value.id) },
  { key: 'trace', badge: 'XLS', tone: 'gold', name: '正向追溯矩阵', desc: '需求 → 代码 双向追溯（正向）', call: () => exportApi.traceabilityExcel(current.value.id) },
  { key: 'traceRev', badge: 'XLS', tone: 'gold', name: '逆向追溯矩阵', desc: '代码 → 需求 覆盖与孤儿实现', call: () => exportApi.traceabilityReverseExcel(current.value.id) },
  { key: 'codeDefects', badge: 'XLS', tone: 'sage', name: '代码质量缺陷', desc: 'SQL 注入 / 死循环 / 资源泄露等基础缺陷', call: () => exportApi.codeDefectsExcel(current.value.id) },
  { key: 'statistics', badge: 'XLS', tone: 'sage', name: '统计报表', desc: '项目级统计汇总数据', call: () => exportApi.statisticsExcel(current.value.id) }
]

const selectProject = (p) => {
  current.value = p
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

onMounted(async () => {
  loadingProjects.value = true
  try {
    projects.value = await projectApi.list() || []
    if (projects.value.length) current.value = projects.value[0]
  } catch (e) {
    ElMessage.error(e?.message || '加载项目档案失败')
  } finally {
    loadingProjects.value = false
  }
  window.addEventListener('resize', onCovResize)
})

// ===== 已选项目覆盖率对比图 =====
const covRef = ref(null)
let covChart = null

watch(compareIds, async () => {
  await nextTick()
  renderCov()
}, { deep: true })

const renderCov = () => {
  if (!covRef.value) return
  if (!covChart) covChart = echarts.init(covRef.value, chartThemeName())
  const picked = projects.value.filter(p => compareIds.value.includes(p.id))
  covChart.setOption({
    grid: { left: 8, right: 40, top: 6, bottom: 6, containLabel: true },
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
      data: picked.map(p => p.projectName.length > 9 ? p.projectName.slice(0, 9) + '…' : p.projectName),
      axisLine: { show: false }, axisTick: { show: false },
      axisLabel: { color: chartText(), fontSize: 11 }
    },
    series: [{
      type: 'bar', barWidth: 12,
      data: picked.map(p => p.coverageRate == null ? 0 : Math.round(p.coverageRate * 100)),
      itemStyle: {
        borderRadius: [0, 7, 7, 0],
        color: new echarts.graphic.LinearGradient(1, 0, 0, 0, [
          { offset: 0, color: '#C99B3F' },
          { offset: 1, color: 'rgba(232, 200, 119, 0.45)' }
        ])
      },
      label: { show: true, position: 'right', color: '#8F6B22', fontSize: 11, formatter: '{c}%' }
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
.rh-title { margin: 0; font-size: 26px; font-weight: 700; letter-spacing: -0.02em; color: var(--tg-text-primary); }
.rh-sub { margin: 6px 0 0; font-size: 13px; color: var(--tg-text-secondary); }

.rh-body { display: flex; gap: 18px; align-items: flex-start; }

/* 档案脊：窄竖排标签 */
.rh-shelf {
  width: 196px; flex-shrink: 0; display: flex; flex-direction: column; gap: 7px;
  max-height: calc(100vh - 220px); overflow-y: auto; padding: 12px 10px;
  border-radius: 18px; background: linear-gradient(180deg, rgba(255, 255, 255, 0.75), rgba(255, 246, 230, 0.45));
  border: 1px solid rgba(201, 155, 63, 0.16); box-shadow: var(--tg-shadow-card);
}
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
.rh-spine.on {
  background: rgba(255, 255, 255, 0.9); border-color: rgba(201, 155, 63, 0.35);
  box-shadow: 0 6px 16px rgba(60, 45, 25, 0.08);
}
.rh-spine.on::before { background: var(--tg-accent-gradient); }
.rh-spine__label { font-size: 13px; font-weight: 600; color: var(--tg-text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.rh-spine__meta { font-size: 10.5px; color: var(--tg-slate); }
.rh-shelf__empty { padding: 30px 0; text-align: center; font-size: 12px; color: var(--tg-slate); }

/* 档案柜：暖金+藤紫+蓝灰光晕（第三种色调组合） */
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
.rh-cabinet__head { display: flex; align-items: center; gap: 12px; margin-bottom: 16px; flex-wrap: wrap; }
.rh-cabinet__name { margin: 0; font-size: 18px; font-weight: 700; color: var(--tg-text-primary); }
.rh-cabinet__tag { font-size: 11.5px; padding: 3px 12px; border-radius: 999px; background: rgba(232, 155, 60, 0.13); color: #9a5d12; }
.rh-cabinet__tag.is-ready { background: rgba(107, 142, 78, 0.13); color: #55682e; }

/* 档案夹格架 */
.rh-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(226px, 1fr)); gap: 13px; }
.rh-folder {
  border-radius: 14px; padding: 14px 15px; border: 1px solid var(--tg-border);
  background: linear-gradient(170deg, rgba(255, 255, 255, 0.9), rgba(250, 246, 239, 0.6));
  transition: transform 0.25s var(--tg-ease), box-shadow 0.25s ease, border-color 0.2s ease;
}
.rh-folder:hover { transform: translateY(-3px); border-color: rgba(201, 155, 63, 0.38); box-shadow: 0 12px 26px rgba(60, 45, 25, 0.1); }
.rh-folder__top { display: flex; align-items: center; justify-content: space-between; margin-bottom: 9px; }
.rh-folder__badge {
  font-family: var(--tg-font-mono); font-size: 10px; font-weight: 700; letter-spacing: 0.06em;
  padding: 2px 8px; border-radius: 6px; color: #fff;
}
.rh-folder--blue .rh-folder__badge { background: #6E93B0; }
.rh-folder--rose .rh-folder__badge { background: #C97F8A; }
.rh-folder--gold .rh-folder__badge { background: #C99B3F; }
.rh-folder--sage .rh-folder__badge { background: #9A9C6B; }
.rh-folder__get {
  border: none; cursor: pointer; font-size: 11.5px; font-weight: 500;
  color: var(--tg-accent); background: var(--el-color-primary-light-9);
  padding: 4px 13px; border-radius: 999px; transition: background 0.2s ease, transform 0.2s var(--tg-ease-spring);
}
.rh-folder__get:hover:not(:disabled) { background: var(--el-color-primary-light-8); transform: translateY(-1px); }
.rh-folder__get:disabled { opacity: 0.6; cursor: wait; }
.rh-folder__name { margin: 0 0 4px; font-size: 13.5px; font-weight: 600; color: var(--tg-text-primary); }
.rh-folder__desc { margin: 0; font-size: 11.5px; line-height: 1.55; color: var(--tg-text-secondary); }

/* 跨项目对比：左选择 + 右可视化 */
.rh-compare { margin-top: 22px; padding-top: 18px; border-top: 1px dashed var(--tg-border); display: flex; gap: 24px; align-items: flex-start; }
.rh-compare__left { flex: 1; min-width: 0; }
.rh-compare__viz {
  width: 320px; flex-shrink: 0; display: flex; flex-direction: column; gap: 6px;
  padding: 12px 14px; border-radius: 14px;
  background: rgba(255, 255, 255, 0.6); border: 1px dashed rgba(201, 155, 63, 0.3);
}
.rh-compare__vizlabel { font-size: 11px; font-weight: 600; letter-spacing: 0.06em; color: var(--tg-slate); }
.rh-compare__chart { height: 150px; }
.rh-compare__head { display: flex; align-items: baseline; gap: 12px; margin-bottom: 12px; flex-wrap: wrap; }
.rh-compare__title { margin: 0; font-size: 14.5px; font-weight: 600; color: var(--tg-text-primary); }
.rh-compare__hint { font-size: 11.5px; color: var(--tg-slate); font-variant-numeric: tabular-nums; }
.rh-compare__chips { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 14px; }
.rh-chip {
  border: 1px solid var(--tg-border); background: rgba(255, 255, 255, 0.6); cursor: pointer;
  padding: 6px 14px; border-radius: 999px; font-size: 12px; color: var(--tg-text-secondary);
  transition: all 0.2s var(--tg-ease-spring);
}
.rh-chip:hover { border-color: rgba(201, 155, 63, 0.4); color: var(--tg-accent); }
.rh-chip.on {
  background: var(--tg-accent-gradient); border-color: transparent; color: #fff; font-weight: 500;
  box-shadow: var(--tg-glow-accent);
}
.rh-compare__actions { display: flex; align-items: center; gap: 8px; }

.rh-cabinet__placeholder { display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 6px; padding: 130px 0; }
.rh-cabinet__placeholder p { margin: 0; font-size: 15px; font-weight: 600; color: var(--tg-text-secondary); }
.rh-cabinet__placeholder small { font-size: 12px; color: var(--tg-slate); }

@media (max-width: 900px) {
  .rh-body { flex-direction: column; }
  .rh-shelf { width: 100%; flex-direction: row; overflow-x: auto; max-height: none; }
  .rh-spine { min-width: 150px; }
}
</style>

@media (max-width: 900px) {
  .rh-compare { flex-direction: column; }
  .rh-compare__viz { width: 100%; }
}
