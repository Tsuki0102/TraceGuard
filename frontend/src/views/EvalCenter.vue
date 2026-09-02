<template>
  <div class="page ec-page">
    <!-- 评测档案单：纸质表单版式（全站唯一表单纸+印章页） -->
    <div class="ec-sheet" v-loading="loading">
      <header class="ec-sheet__head">
        <div>
          <span class="ec-sheet__code">FORM TG-EVAL-{{ sheetNo }}</span>
          <h1 class="ec-sheet__title">评测中心 · 标注资产与阈值敏感性档案</h1>
          <p class="ec-sheet__sub">本单数据全部取自仓库真实样例与库内真实分析结果，不含任何演示造假数据</p>
        </div>
        <div class="ec-stamp" aria-hidden="true">QUALITY<br />EVAL<br />ARCHIVE</div>
      </header>

      <!-- 第一栏：标注资产标本卡 -->
      <section class="ec-section">
        <h2 class="ec-section__title">一、内置标注资产 <small>SPECIMEN INVENTORY</small></h2>
        <div class="ec-specimens">
          <article v-for="a in assets" :key="a.key" class="ec-specimen">
            <header class="ec-specimen__head">
              <code class="ec-specimen__key">{{ a.key }}</code>
              <span class="ec-specimen__tag" :class="{ 'is-ready': a.hasCode }">{{ a.hasCode ? '可导入' : '缺代码包' }}</span>
            </header>
            <p class="ec-specimen__desc">{{ a.description || '（无描述）' }}</p>
            <div class="ec-specimen__stats">
              <div class="ec-specimen__stat"><b>{{ a.requirementLines }}</b><span>需求行</span></div>
              <div class="ec-specimen__stat"><b>{{ a.defectCount }}</b><span>标注缺陷</span></div>
            </div>
            <div class="ec-specimen__cats">
              <div v-for="(cnt, cat) in a.categories" :key="cat" class="ec-cat">
                <span class="ec-cat__name">{{ cat }}</span>
                <span class="ec-cat__bar"><i :style="{ width: catPct(a, cnt) + '%' }"></i></span>
                <b>{{ cnt }}</b>
              </div>
            </div>
            <footer class="ec-specimen__foot">
              <el-button size="small" round type="primary" plain :disabled="!a.hasCode || importing === a.key" @click="importSample(a)">
                {{ importing === a.key ? '导入中…' : '一键导入体验' }}
              </el-button>
            </footer>
          </article>
          <p v-if="!loading && !assets.length" class="ec-empty">仓库 samples 目录中未发现可盘点资产</p>
        </div>
      </section>

      <div class="ec-sheet__rule" aria-hidden="true"></div>

      <!-- 第二栏：阈值敏感性扫描 -->
      <section class="ec-section">
        <h2 class="ec-section__title">二、判定阈值敏感性扫描 <small>THRESHOLD SENSITIVITY</small></h2>
        <div class="ec-scan">
          <aside class="ec-scan__side">
            <el-select v-model="projectId" placeholder="选择已分析项目" style="width: 100%" @change="loadSweep">
              <el-option v-for="p in analyzedProjects" :key="p.id" :label="p.projectName" :value="p.id" />
            </el-select>
            <dl class="ec-scan__conclusion">
              <dt>自动结论</dt>
              <dd v-if="conclusion">{{ conclusion }}</dd>
              <dd v-else class="is-muted">选择项目后自动生成</dd>
            </dl>
            <el-button size="default" round @click="goLab">进阈值实验室调参 →</el-button>
          </aside>
          <div ref="sweepRef" class="ec-scan__chart"></div>
        </div>
      </section>

      <footer class="ec-sheet__foot">
        <span>本单由系统自动汇编 · 汇编时间 {{ compiledAt }}</span>
        <span>样例资产：仓库 samples/ 目录 · 扫描口径：库内 total_similarity</span>
      </footer>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import { chartThemeName, chartText, chartFaint, chartAxisLine, chartSplitLine, chartTooltipBg, chartTitleColor, onChartThemeChange } from '@/utils/echartsTheme'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useRouter } from 'vue-router'
import { insightApi, projectApi } from '@/api'

const router = useRouter()
const loading = ref(false)
const assets = ref([])
const projects = ref([])
const projectId = ref(null)
const sweepData = ref([])
const sweepRef = ref(null)
let sweepChart = null
const importing = ref('')

const pad = (n) => String(n).padStart(2, '0')
const now = new Date()
const sheetNo = `${now.getFullYear()}${pad(now.getMonth() + 1)}${pad(now.getDate())}`
const compiledAt = `${now.getFullYear()}-${pad(now.getMonth() + 1)}-${pad(now.getDate())} ${pad(now.getHours())}:${pad(now.getMinutes())}`

const analyzedProjects = computed(() => projects.value.filter(p => p.status === 'analyzed'))
const catPct = (a, cnt) => Math.round((cnt * 100) / Math.max(1, ...Object.values(a.categories || {})))

// 自动结论：找「完全一致」占比变化最平缓的 T1 区段 + 默认 0.80 的位置评价
const conclusion = computed(() => {
  if (!sweepData.value.length) return ''
  let best = null
  for (const d of sweepData.value) {
    const total = d.pass + d.mild + d.severe
    if (!total) continue
    const stable = 1 - Math.min(d.mild + d.severe, total) / total
    if (!best || stable > best.stable) best = { t1: d.t1, stable }
  }
  if (!best) return ''
  const near = sweepData.value.find(d => Math.abs(d.t1 - 0.8) < 0.001)
  return `扫描区间内 T1=${best.t1.toFixed(2)} 时「完全一致」判定占比最高；默认 T1=0.80 ` +
    (near ? `将 ${near.pass} 对判定为完全一致、${near.mild} 对一般不一致、${near.severe} 对严重不一致。` : '')
})

const renderSweep = () => {
  if (!sweepRef.value || !sweepData.value.length) return
  if (!sweepChart) sweepChart = echarts.init(sweepRef.value, chartThemeName())
  sweepChart.setOption({
    grid: { left: 44, right: 18, top: 34, bottom: 30 },
    tooltip: { trigger: 'axis', valueFormatter: (v) => v + ' 对' },
    legend: { top: 0, right: 0, textStyle: { color: chartText(), fontSize: 11 } },
    xAxis: {
      type: 'category',
      data: sweepData.value.map(d => d.t1.toFixed(2)),
      axisLine: { lineStyle: { color: 'rgba(60,45,25,0.18)' } },
      axisLabel: { color: chartText(), fontSize: 10.5 }
    },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: 'rgba(60,45,25,0.08)', type: 'dashed' } }, axisLabel: { color: chartText(), fontSize: 10.5 } },
    series: [
      { name: '完全一致', type: 'bar', stack: 'b', barWidth: '52%', data: sweepData.value.map(d => d.pass), itemStyle: { color: '#A8B98A' }, markLine: { silent: true, symbol: 'none', label: { formatter: '默认 0.80', fontSize: 10 }, lineStyle: { color: '#8F6B22', type: 'dashed' }, data: [{ xAxis: '0.80' }] } },
      { name: '一般不一致', type: 'bar', stack: 'b', data: sweepData.value.map(d => d.mild), itemStyle: { color: '#E8C877' } },
      { name: '严重不一致', type: 'bar', stack: 'b', data: sweepData.value.map(d => d.severe), itemStyle: { color: '#D97A66' }, borderRadius: [4, 4, 0, 0] }
    ]
  })
}

const loadSweep = async () => {
  if (!projectId.value) return
  try {
    sweepData.value = await insightApi.sweep(projectId.value) || []
    await nextTick()
    renderSweep()
  } catch (e) {
    ElMessage.error(e?.message || '扫描失败')
  }
}

const importSample = async (a) => {
  try {
    const { value } = await ElMessageBox.prompt(
      `将从样例「${a.key}」创建独立项目（含需求文件与已解压代码，导入后可直接执行分析）`,
      '一键导入体验',
      { inputValue: a.key, inputPlaceholder: '新项目名称', confirmButtonText: '导入', cancelButtonText: '取消' }
    )
    importing.value = a.key
    const p = await insightApi.importSample(a.key, value)
    ElMessage.success(`已创建项目「${p.projectName}」，可在项目管理中查看`)
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  } finally {
    importing.value = ''
  }
}

const goLab = () => router.push('/lab/threshold')

onChartThemeChange(() => { sweepChart?.dispose(); sweepChart = null; renderSweep() })

onMounted(async () => {
  loading.value = true
  try {
    const [a, p] = await Promise.all([insightApi.evalAssets(), projectApi.list().catch(() => [])])
    assets.value = a || []
    projects.value = p || []
    if (analyzedProjects.value.length) {
      projectId.value = analyzedProjects.value[0].id
      await loadSweep()
    }
  } catch (e) {
    ElMessage.error(e?.message || '加载评测档案失败')
  } finally {
    loading.value = false
  }
  window.addEventListener('resize', () => sweepChart && sweepChart.resize())
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', () => sweepChart && sweepChart.resize())
  if (sweepChart) { sweepChart.dispose(); sweepChart = null }
})
</script>

<style scoped>
/* ===== 评测档案单：纸质表单 + 印章 + 标本卡（全站唯一档案纸版式） ===== */
.ec-page { max-width: 1080px; }

.ec-sheet {
  position: relative;
  border-radius: 6px; padding: 30px 36px 22px;
  background:
    repeating-linear-gradient(0deg, transparent, transparent 31px, rgba(60, 45, 25, 0.035) 31px, rgba(60, 45, 25, 0.035) 32px),
    #FFFDF8;
  border: 1px solid rgba(60, 45, 25, 0.14);
  box-shadow: 0 24px 60px rgba(60, 45, 25, 0.12), 0 2px 6px rgba(60, 45, 25, 0.06);
}
.ec-sheet__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }
.ec-sheet__code { font-family: var(--tg-font-mono); font-size: 10.5px; letter-spacing: 0.2em; color: var(--tg-slate); }
.ec-sheet__title { margin: 6px 0 0; font-size: 23px; font-weight: 700; letter-spacing: 0.02em; color: var(--tg-text-primary); }
.ec-sheet__sub { margin: 8px 0 0; font-size: 12px; color: var(--tg-text-secondary); }

/* 印章 */
.ec-stamp {
  flex-shrink: 0; transform: rotate(9deg);
  font-family: var(--tg-font-mono); font-size: 10px; font-weight: 700; letter-spacing: 0.18em; text-align: center;
  color: rgba(194, 94, 76, 0.62); border: 2.5px solid rgba(194, 94, 76, 0.5); border-radius: 8px;
  padding: 9px 13px; line-height: 1.7; user-select: none;
  mask-image: radial-gradient(circle at 30% 40%, rgba(0,0,0,0.9), rgba(0,0,0,0.72));
}

.ec-section { margin-top: 26px; }
.ec-section__title {
  margin: 0 0 16px; font-size: 15.5px; font-weight: 700; color: var(--tg-text-primary);
  border-bottom: 1px dashed rgba(60, 45, 25, 0.22); padding-bottom: 9px;
}
.ec-section__title small { font-family: Georgia, serif; font-size: 10px; font-weight: 400; letter-spacing: 0.2em; color: var(--tg-slate); margin-left: 10px; }

/* 标本卡 */
.ec-specimens { display: grid; grid-template-columns: repeat(auto-fill, minmax(292px, 1fr)); gap: 14px; }
.ec-specimen {
  border: 1px solid rgba(60, 45, 25, 0.16); border-radius: 10px; padding: 14px 15px 12px;
  background: rgba(255, 255, 255, 0.72);
  display: flex; flex-direction: column; gap: 9px;
}
.ec-specimen__head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.ec-specimen__key { font-family: var(--tg-font-mono); font-size: 13px; font-weight: 700; color: var(--tg-text-primary); }
.ec-specimen__tag { font-size: 10.5px; padding: 1px 9px; border-radius: 999px; background: rgba(0, 0, 0, 0.05); color: var(--tg-slate); }
.ec-specimen__tag.is-ready { background: rgba(107, 142, 78, 0.14); color: #55682e; }
.ec-specimen__desc {
  margin: 0; font-size: 11.5px; line-height: 1.55; color: var(--tg-text-secondary);
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; min-height: 35px;
}
.ec-specimen__stats { display: flex; gap: 0; border: 1px dashed rgba(60, 45, 25, 0.18); border-radius: 8px; }
.ec-specimen__stat { flex: 1; display: flex; align-items: baseline; gap: 7px; padding: 8px 12px; }
.ec-specimen__stat + .ec-specimen__stat { border-left: 1px dashed rgba(60, 45, 25, 0.18); }
.ec-specimen__stat b { font-size: 19px; font-weight: 700; color: var(--tg-text-primary); font-variant-numeric: tabular-nums; }
.ec-specimen__stat span { font-size: 10.5px; color: var(--tg-slate); }
.ec-specimen__cats { display: flex; flex-direction: column; gap: 5px; }
.ec-cat { display: grid; grid-template-columns: 88px 1fr 24px; align-items: center; gap: 8px; }
.ec-cat__name { font-size: 10.5px; color: var(--tg-text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ec-cat__bar { height: 5px; border-radius: 999px; background: rgba(60, 45, 25, 0.07); overflow: hidden; }
.ec-cat__bar i { display: block; height: 100%; border-radius: 999px; background: var(--tg-accent-gradient); }
.ec-cat b { text-align: right; font-size: 11px; color: var(--tg-text-primary); font-variant-numeric: tabular-nums; }
.ec-specimen__foot { display: flex; justify-content: flex-end; }
.ec-empty { margin: 10px 0; text-align: center; font-size: 12.5px; color: var(--tg-slate); }

.ec-sheet__rule { margin: 26px 0 0; border-top: 1px dashed rgba(60, 45, 25, 0.22); }

/* 扫描区 */
.ec-scan { display: flex; gap: 20px; align-items: stretch; }
.ec-scan__side { width: 240px; flex-shrink: 0; display: flex; flex-direction: column; gap: 14px; }
.ec-scan__conclusion { margin: 0; flex: 1; border-left: 3px solid var(--tg-gold); padding-left: 12px; }
.ec-scan__conclusion dt { font-size: 11px; letter-spacing: 0.1em; color: var(--tg-slate); }
.ec-scan__conclusion dd { margin: 8px 0 0; font-size: 12.5px; line-height: 1.75; color: var(--tg-text-primary); }
.ec-scan__conclusion dd.is-muted { color: var(--tg-slate); }
.ec-scan__chart { flex: 1; min-width: 0; height: 280px; }

.ec-sheet__foot {
  margin-top: 28px; padding-top: 12px; border-top: 1px dashed rgba(60, 45, 25, 0.22);
  display: flex; justify-content: space-between; gap: 12px; flex-wrap: wrap;
  font-size: 10.5px; color: var(--tg-slate);
}

@media (max-width: 860px) {
  .ec-sheet { padding: 22px 18px; }
  .ec-scan { flex-direction: column; }
  .ec-scan__side { width: 100%; }
}
</style>
