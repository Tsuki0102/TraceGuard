<template>
  <div class="page tl-page">
    <!-- 公式横幅：参数实时渲染进公式（全站唯一数学版式） -->
    <header class="tl-formula">
      <span class="tl-formula__id">SIM FORMULA</span>
      <div class="tl-formula__math">
        <em>Sim(R<sub>i</sub>, C<sub>j</sub>)</em>
        <i>=</i>
        <b class="tl-var tl-var--a">{{ params.alpha.toFixed(2) }}</b><span>·Cos</span>
        <i>+</i>
        <b class="tl-var tl-var--b">{{ params.beta.toFixed(2) }}</b><span>·Con</span>
        <i>+</i>
        <b class="tl-var tl-var--c">{{ params.gamma.toFixed(2) }}</b><span>·Inv</span>
      </div>
      <p class="tl-formula__note">Sim &gt; T1 判「完全一致」· T2 ≤ Sim ≤ T1 判「一般不一致」· Sim &lt; T2 判「严重不一致」（SRS FR-CHECK-002）</p>
    </header>

    <div class="tl-body">
      <!-- 左：调参台 -->
      <aside class="tl-console">
        <div class="tl-console__block">
          <h3 class="tl-console__label">实验对象</h3>
          <el-select v-model="projectId" placeholder="选择已分析项目" style="width: 100%" @change="onProjectChange">
            <el-option v-for="p in analyzedProjects" :key="p.id" :label="p.projectName" :value="p.id" />
          </el-select>
        </div>
        <div class="tl-console__block">
          <h3 class="tl-console__label">三维权重 <small>自动归一化</small></h3>
          <div v-for="w in weightDefs" :key="w.key" class="tl-slider">
            <div class="tl-slider__head">
              <span class="tl-slider__name" :class="'is-' + w.key">{{ w.name }}</span>
              <b>{{ params[w.key].toFixed(2) }}</b>
            </div>
            <el-slider v-model="sliders[w.key]" :min="0" :max="100" :show-tooltip="false" size="small" />
          </div>
        </div>
        <div class="tl-console__block">
          <h3 class="tl-console__label">判定阈值</h3>
          <div class="tl-slider">
            <div class="tl-slider__head"><span class="tl-slider__name">T1 完全一致线</span><b>{{ params.t1.toFixed(2) }}</b></div>
            <el-slider v-model="sliders.t1" :min="60" :max="95" :show-tooltip="false" size="small" />
          </div>
          <div class="tl-slider">
            <div class="tl-slider__head"><span class="tl-slider__name">T2 一般不一致线</span><b>{{ params.t2.toFixed(2) }}</b></div>
            <el-slider v-model="sliders.t2" :min="20" :max="70" :show-tooltip="false" size="small" />
          </div>
        </div>
        <el-button round plain @click="resetDefaults">恢复默认参数</el-button>
        <p class="tl-console__hint">拖动滑块即时重放库内 {{ replay ? replay.count : '—' }} 条判定，不触发重新分析</p>
      </aside>

      <!-- 右：读数区 -->
      <main class="tl-readout">
        <section class="tl-compare" v-loading="loading">
          <div class="tl-compare__col">
            <h3>基线 · 默认参数</h3>
            <div v-for="(b, i) in bucketRows" :key="'b' + i" class="tl-brow">
              <span class="tl-brow__name">{{ b.name }}</span>
              <div class="tl-brow__track"><i :style="{ width: barPct(baseline[i]) + '%' }" :class="'is-' + i"></i></div>
              <b>{{ baseline[i] }}</b>
            </div>
          </div>
          <div class="tl-compare__divider" aria-hidden="true"><span>VS</span></div>
          <div class="tl-compare__col tl-compare__col--custom">
            <h3>当前参数</h3>
            <div v-for="(b, i) in bucketRows" :key="'c' + i" class="tl-brow">
              <span class="tl-brow__name">{{ b.name }}</span>
              <div class="tl-brow__track"><i :style="{ width: barPct(custom[i]) + '%' }" :class="'is-' + i"></i></div>
              <b>{{ custom[i] }}</b>
              <em v-if="custom[i] - baseline[i] !== 0" class="tl-delta" :class="{ 'is-up': custom[i] > baseline[i] }">
                {{ custom[i] > baseline[i] ? '+' : '' }}{{ custom[i] - baseline[i] }}
              </em>
            </div>
          </div>
        </section>

        <section class="tl-flips">
          <header class="tl-flips__head">
            <h3>判定翻转</h3>
            <span class="tl-flips__count">{{ replay ? replay.flipCount : 0 }} <small>对</small></span>
          </header>
          <div class="tl-flips__list">
            <div v-for="(f, i) in (replay ? replay.flips : [])" :key="i" class="tl-flip">
              <code>需求 #{{ f.requirementId }}</code>
              <span class="tl-flip__sim">{{ f.storedSim }} → <b>{{ f.newSim }}</b></span>
              <span class="tl-flip__path">{{ f.from }} <i>→</i> {{ f.to }}</span>
            </div>
            <p v-if="replay && !replay.flipCount" class="tl-flips__none">当前参数下无判定翻转 —— 与基线口径一致</p>
            <p v-if="!projectId" class="tl-flips__none">请先在左侧选择一个已分析的项目</p>
          </div>
        </section>

        <section class="tl-sweep">
          <header class="tl-sweep__head">
            <h3>T1 阈值扫描 <small>T2 按 0.5/0.8 比例联动</small></h3>
            <span class="tl-sweep__tag">当前 T1 = {{ params.t1.toFixed(2) }}</span>
          </header>
          <div ref="sweepRef" class="tl-sweep__chart"></div>
        </section>
      </main>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount, nextTick } from 'vue'
import * as echarts from 'echarts'
import { chartThemeName, chartText, chartFaint, chartAxisLine, chartSplitLine, chartTooltipBg, chartTitleColor, onChartThemeChange } from '@/utils/echartsTheme'
import { ElMessage } from 'element-plus'
import { insightApi, projectApi } from '@/api'

const projects = ref([])
const projectId = ref(null)
const loading = ref(false)
const replay = ref(null)
const sweepData = ref([])
const sweepRef = ref(null)
let sweepChart = null

const sliders = reactive({ alpha: 40, beta: 35, gamma: 25, t1: 80, t2: 50 })
const weightDefs = [
  { key: 'alpha', name: 'α 语义相似度' },
  { key: 'beta', name: 'β 约束匹配度' },
  { key: 'gamma', name: 'γ 不变量满足度' }
]
const bucketRows = [
  { name: '完全一致' },
  { name: '一般不一致' },
  { name: '严重不一致' }
]

const params = computed(() => {
  const raw = { a: sliders.alpha, b: sliders.beta, g: sliders.gamma }
  const sum = raw.a + raw.b + raw.g
  const k = sum > 0 ? 100 / sum : 1
  return {
    alpha: (raw.a * k) / 100,
    beta: (raw.b * k) / 100,
    gamma: (raw.g * k) / 100,
    t1: sliders.t1 / 100,
    t2: sliders.t2 / 100
  }
})
const analyzedProjects = computed(() => projects.value.filter(p => p.status === 'analyzed'))
const baseline = computed(() => (replay.value ? replay.value.baseline : [0, 0, 0]))
const custom = computed(() => (replay.value ? replay.value.custom : [0, 0, 0]))
const barMax = computed(() => Math.max(...baseline.value, ...custom.value, 1))
const barPct = (v) => Math.round((v * 100) / barMax.value)

const resetDefaults = () => {
  sliders.alpha = 40
  sliders.beta = 35
  sliders.gamma = 25
  sliders.t1 = 80
  sliders.t2 = 50
}

const renderSweep = () => {
  if (!sweepRef.value || !sweepData.value.length) return
  if (!sweepChart) sweepChart = echarts.init(sweepRef.value, chartThemeName())
  sweepChart.setOption({
    grid: { left: 44, right: 20, top: 30, bottom: 30 },
    tooltip: { trigger: 'axis', valueFormatter: (v) => v + ' 对' },
    legend: { top: 0, right: 0, textStyle: { color: chartText(), fontSize: 11 }, itemWidth: 14 },
    xAxis: {
      type: 'category',
      data: sweepData.value.map(d => d.t1.toFixed(2)),
      axisLine: { lineStyle: { color: 'rgba(60,45,25,0.18)' } },
      axisLabel: { color: chartText(), fontSize: 10.5 }
    },
    yAxis: { type: 'value', minInterval: 1, splitLine: { lineStyle: { color: 'rgba(60,45,25,0.08)', type: 'dashed' } }, axisLabel: { color: chartText(), fontSize: 10.5 } },
    series: [
      { name: '完全一致', type: 'line', smooth: true, symbol: 'circle', symbolSize: 5, data: sweepData.value.map(d => d.pass), itemStyle: { color: '#6B8E4E' }, lineStyle: { width: 2.5 }, markLine: { silent: true, symbol: 'none', label: { formatter: '默认 0.80', fontSize: 10 }, lineStyle: { color: '#8F6B22', type: 'dashed' }, data: [{ xAxis: '0.80' }] } },
      { name: '一般不一致', type: 'line', smooth: true, symbol: 'none', data: sweepData.value.map(d => d.mild), itemStyle: { color: '#E89B3C' }, lineStyle: { width: 2 } },
      { name: '严重不一致', type: 'line', smooth: true, symbol: 'none', data: sweepData.value.map(d => d.severe), itemStyle: { color: '#C25E4C' }, lineStyle: { width: 2 } }
    ]
  })
}

let timer = null
const runReplay = () => {
  if (!projectId.value) return
  loading.value = true
  insightApi.replay({
    projectId: projectId.value,
    alpha: params.value.alpha,
    beta: params.value.beta,
    gamma: params.value.gamma,
    t1: params.value.t1,
    t2: params.value.t2
  }).then(data => {
    replay.value = data
  }).catch(e => ElMessage.error(e?.message || '重放失败'))
    .finally(() => { loading.value = false })
}
const debouncedReplay = () => {
  clearTimeout(timer)
  timer = setTimeout(runReplay, 350)
}

const onProjectChange = async () => {
  replay.value = null
  if (!projectId.value) return
  try {
    sweepData.value = await insightApi.sweep(projectId.value) || []
    await nextTick()
    renderSweep()
  } catch (e) {
    ElMessage.error(e?.message || '扫描失败')
  }
  runReplay()
}

watch(sliders, debouncedReplay, { deep: true })

onChartThemeChange(() => { sweepChart?.dispose(); sweepChart = null; renderSweep() })

onMounted(async () => {
  try {
    projects.value = await projectApi.list() || []
    if (analyzedProjects.value.length) {
      projectId.value = analyzedProjects.value[0].id
      await onProjectChange()
    }
  } catch (e) {
    ElMessage.error(e?.message || '加载项目失败')
  }
  window.addEventListener('resize', () => sweepChart && sweepChart.resize())
})

onBeforeUnmount(() => {
  if (sweepChart) { sweepChart.dispose(); sweepChart = null }
  clearTimeout(timer)
})
</script>

<style scoped>
/* ===== 调参台：公式横幅 + 左滑块架 + 右 A/B 读数（全站唯一数学仪器版式） ===== */
.tl-page { display: flex; flex-direction: column; gap: 18px; }

.tl-formula {
  border-radius: 20px; padding: 20px 28px;
  background:
    radial-gradient(130% 190% at 0% 0%, rgba(232, 200, 119, 0.34), transparent 52%),
    radial-gradient(120% 170% at 100% 0%, rgba(110, 147, 176, 0.16), transparent 55%),
    radial-gradient(100% 150% at 50% 115%, rgba(154, 127, 176, 0.14), transparent 58%),
    linear-gradient(160deg, rgba(255, 250, 240, 0.96), rgba(255, 246, 230, 0.72));
  border: 1px solid rgba(201, 155, 63, 0.22);
  box-shadow: var(--tg-shadow-card);
}
.tl-formula__id { font-family: var(--tg-font-mono); font-size: 10px; letter-spacing: 0.28em; color: var(--tg-slate); }
.tl-formula__math { display: flex; align-items: baseline; gap: 9px; margin-top: 8px; flex-wrap: wrap; }
.tl-formula__math em {
  font-family: Georgia, 'Times New Roman', serif; font-style: italic; font-size: 27px; color: var(--tg-text-primary);
}
.tl-formula__math em sub { font-size: 12px; }
.tl-formula__math i { font-style: normal; font-size: 21px; color: rgba(60, 45, 25, 0.38); }
.tl-formula__math span { font-size: 17px; color: var(--tg-text-secondary); margin-right: 6px; }
.tl-var {
  font-family: Georgia, serif; font-style: italic; font-size: 27px; line-height: 1;
  padding: 1px 7px 3px; border-radius: 8px;
}
.tl-var--a { color: #6F5318; background: rgba(201, 155, 63, 0.2); }
.tl-var--b { color: #8F6B22; background: rgba(217, 169, 102, 0.18); }
.tl-var--c { color: #55682E; background: rgba(154, 156, 107, 0.2); }
.tl-formula__note { margin: 10px 0 0; font-size: 11.5px; color: var(--tg-text-secondary); }

.tl-body { display: flex; gap: 18px; align-items: flex-start; }

/* 左侧调参台 */
.tl-console {
  width: 268px; flex-shrink: 0; display: flex; flex-direction: column; gap: 16px;
  padding: 20px 18px; border-radius: 20px;
  background: linear-gradient(170deg, rgba(255, 255, 255, 0.85), rgba(255, 246, 230, 0.5));
  border: 1px solid rgba(201, 155, 63, 0.18); box-shadow: var(--tg-shadow-card);
}
.tl-console__label { margin: 0 0 10px; font-size: 11px; font-weight: 600; letter-spacing: 0.1em; color: var(--tg-slate); }
.tl-console__label small { font-weight: 400; margin-left: 6px; letter-spacing: 0; }
.tl-slider { margin-bottom: 13px; }
.tl-slider__head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 3px; }
.tl-slider__name { font-size: 12px; color: var(--tg-text-secondary); }
.tl-slider__name.is-alpha { color: #8F6B22; font-weight: 600; }
.tl-slider__name.is-beta { color: #B98A2F; font-weight: 600; }
.tl-slider__name.is-gamma { color: #6E7350; font-weight: 600; }
.tl-slider__head b { font-size: 14px; font-weight: 700; color: var(--tg-text-primary); font-variant-numeric: tabular-nums; }
.tl-console__hint { margin: 2px 0 0; font-size: 11px; line-height: 1.6; color: var(--tg-slate); }

/* 右侧读数 */
.tl-readout { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 16px; }
.tl-compare {
  display: grid; grid-template-columns: 1fr 56px 1fr; align-items: stretch; gap: 0;
  border-radius: 20px; background: var(--tg-card-highlight);
  border: 1px solid rgba(255, 255, 255, 0.72); box-shadow: var(--tg-shadow-card); padding: 20px 24px;
}
.tl-compare__col h3 { margin: 0 0 14px; font-size: 13px; font-weight: 600; color: var(--tg-text-secondary); }
.tl-compare__col--custom h3 { color: var(--tg-accent); }
.tl-compare__divider { display: flex; align-items: center; justify-content: center; }
.tl-compare__divider span {
  font-family: Georgia, serif; font-style: italic; font-size: 13px; color: var(--tg-slate);
  border: 1px solid var(--tg-border); border-radius: 50%; width: 38px; height: 38px;
  display: inline-flex; align-items: center; justify-content: center; background: rgba(0, 0, 0, 0.02);
}
.tl-brow { display: grid; grid-template-columns: 76px 1fr 44px 40px; align-items: center; gap: 10px; margin-bottom: 12px; }
.tl-brow:last-child { margin-bottom: 0; }
.tl-brow__name { font-size: 12px; color: var(--tg-text-secondary); }
.tl-brow__track { height: 14px; border-radius: 7px; background: rgba(0, 0, 0, 0.05); overflow: hidden; }
.tl-brow__track i { display: block; height: 100%; border-radius: 7px; transition: width 0.5s var(--tg-ease); }
.tl-brow__track i.is-0 { background: linear-gradient(90deg, #6B8E4E, #A8B98A); }
.tl-brow__track i.is-1 { background: linear-gradient(90deg, #B98A2F, #E8C877); }
.tl-brow__track i.is-2 { background: linear-gradient(90deg, #C25E4C, #D97A66); }
.tl-brow b { text-align: right; font-size: 15px; font-weight: 700; color: var(--tg-text-primary); font-variant-numeric: tabular-nums; }
.tl-delta {
  font-style: normal; font-size: 11px; font-weight: 700; text-align: center;
  padding: 1px 7px; border-radius: 999px; background: rgba(194, 94, 76, 0.12); color: #9a3f30;
  font-variant-numeric: tabular-nums;
}
.tl-delta.is-up { background: rgba(107, 142, 78, 0.14); color: #55682e; }

/* 翻转清单 */
.tl-flips {
  border-radius: 18px; padding: 16px 20px;
  background: var(--tg-card-highlight); border: 1px solid rgba(255, 255, 255, 0.72);
  box-shadow: var(--tg-shadow-card);
}
.tl-flips__head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 10px; }
.tl-flips__head h3 { margin: 0; font-size: 13.5px; font-weight: 600; color: var(--tg-text-primary); }
.tl-flips__count { font-size: 22px; font-weight: 700; color: var(--tg-danger); font-variant-numeric: tabular-nums; }
.tl-flips__count small { font-size: 11px; font-weight: 400; color: var(--tg-slate); margin-left: 3px; }
.tl-flips__list { display: flex; flex-direction: column; gap: 7px; }
.tl-flip {
  display: grid; grid-template-columns: 110px 1fr auto; align-items: center; gap: 14px;
  padding: 8px 12px; border-radius: 10px; background: rgba(194, 94, 76, 0.06);
}
.tl-flip code { font-family: var(--tg-font-mono); font-size: 11px; color: var(--tg-text-secondary); }
.tl-flip__sim { font-size: 12px; color: var(--tg-text-secondary); font-variant-numeric: tabular-nums; }
.tl-flip__sim b { color: var(--tg-text-primary); }
.tl-flip__path { font-size: 11.5px; color: #9a3f30; font-weight: 500; }
.tl-flip__path i { font-style: normal; margin: 0 4px; color: var(--tg-slate); }
.tl-flips__none { margin: 6px 0; text-align: center; font-size: 12.5px; color: var(--tg-slate); }

/* 扫描曲线 */
.tl-sweep {
  border-radius: 18px; padding: 16px 20px 8px;
  background: var(--tg-card-highlight); border: 1px solid rgba(255, 255, 255, 0.72);
  box-shadow: var(--tg-shadow-card);
}
.tl-sweep__head { display: flex; align-items: baseline; justify-content: space-between; margin-bottom: 4px; }
.tl-sweep__head h3 { margin: 0; font-size: 13.5px; font-weight: 600; color: var(--tg-text-primary); }
.tl-sweep__head small { font-weight: 400; font-size: 11px; color: var(--tg-slate); margin-left: 8px; }
.tl-sweep__tag { font-size: 11.5px; color: var(--tg-accent); font-variant-numeric: tabular-nums; }
.tl-sweep__chart { height: 240px; }

@media (max-width: 1024px) {
  .tl-body { flex-direction: column; }
  .tl-console { width: 100%; }
  .tl-compare { grid-template-columns: 1fr; }
  .tl-compare__divider { padding: 8px 0; }
}
</style>
