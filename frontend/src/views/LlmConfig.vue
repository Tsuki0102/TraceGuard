<template>
  <div class="llm-config-page">
    <!-- W4 v3：封面式彩色光晕（装饰层） -->
    <div class="page-glow" aria-hidden="true"><i></i><i></i><i></i></div>

    <!-- ===== 页头：眉题 + 状态徽章 + 操作 ===== -->
    <div class="page-header tg-fade-up">
      <div>
        <el-button link class="page-header__back" @click="$router.back()">
          <el-icon><ArrowLeft /></el-icon> 返回
        </el-button>
        <p class="tg-kicker">AI Integration · Model Mesh</p>
        <h2 class="page-header__title">大模型配置</h2>
        <p class="page-header__desc">编排多服务商、按环节路由模型，保存后即时生效</p>
      </div>
      <div class="page-header__actions">
        <span class="hero-pill" :class="cfg.enabled ? 'is-on' : 'is-off'">
          <em></em>{{ cfg.enabled ? '已启用' : '已停用' }}
        </span>
        <el-button round @click="loadData">
          <el-icon style="margin-right: 4px"><Refresh /></el-icon>刷新
        </el-button>
        <el-button type="warning" round :loading="saving" @click="handleSave">
          <el-icon style="margin-right: 4px"><Check /></el-icon>保存配置
        </el-button>
      </div>
    </div>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="cfg-tip tg-fade-up"
    >
      多服务商编排（OpenAI 兼容）。连通测试仅验证网络与密钥可达性，不触发分析任务。
    </el-alert>

    <!-- ===== 能力概览：富信息统计条（W4 v5） ===== -->
    <div class="cfg-stats">
      <div class="cfg-stat cfg-stat--rich tg-fade-up">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--gold"><el-icon :size="17"><Cpu /></el-icon></span>
          <span class="cfg-stat__label">大模型能力</span>
        </div>
        <div class="cfg-stat__num">
          <b :class="{ 'is-on': cfg.enabled }">{{ cfg.enabled ? '运行中' : '停用' }}</b>
          <span class="cfg-stat__unit">AI 解析</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: (cfg.enabled ? 100 : 0) + '%' }"></i></span>
          <span class="cfg-stat__ratio">{{ cfg.enabled ? '已启用' : '未启用' }}</span>
        </div>
      </div>
      <div class="cfg-stat cfg-stat--rich tg-fade-up" style="animation-delay: 60ms">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--blue"><el-icon :size="17"><Grid /></el-icon></span>
          <span class="cfg-stat__label">服务商渠道</span>
        </div>
        <div class="cfg-stat__num">
          <b>{{ providerForm.length }}</b>
          <span class="cfg-stat__unit">个渠道</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: (providerForm.length ? Math.round(llmKeyed / providerForm.length * 100) : 0) + '%' }"></i></span>
          <span class="cfg-stat__ratio">{{ llmKeyed }}/{{ providerForm.length }} 已配密钥</span>
        </div>
      </div>
      <div class="cfg-stat cfg-stat--rich tg-fade-up" style="animation-delay: 120ms">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--violet"><el-icon :size="17"><Share /></el-icon></span>
          <span class="cfg-stat__label">路由环节</span>
        </div>
        <div class="cfg-stat__num">
          <b>{{ stageList.length }}</b>
          <span class="cfg-stat__unit">个环节</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: (stageList.length ? Math.round(llmRouted / stageList.length * 100) : 0) + '%' }"></i></span>
          <span class="cfg-stat__ratio">{{ llmRouted }}/{{ stageList.length }} 已编排</span>
        </div>
      </div>
      <div class="cfg-stat cfg-stat--rich tg-fade-up" style="animation-delay: 180ms">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--rose"><el-icon :size="17"><DataBoard /></el-icon></span>
          <span class="cfg-stat__label">配置来源</span>
        </div>
        <div class="cfg-stat__num">
          <b>{{ cfg.source === 'db' ? '数据库' : 'yml' }}</b>
          <span class="cfg-stat__unit">来源</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: '100%' }"></i></span>
          <span class="cfg-stat__ratio">并发 {{ cfg.maxConcurrentCalls ?? '—' }}</span>
        </div>
      </div>
    </div>

    <!-- ===== 编排全景：信息可视化（W4 v4） ===== -->
    <section class="cfg-section tg-fade-up">
      <div class="cfg-section__head">
        <div class="cfg-section__title">
          <h3><span class="cfg-section__ic cfg-section__ic--blue"><el-icon :size="15"><Odometer /></el-icon></span>编排全景</h3>
          <p>就绪度 · 密钥覆盖 · 环节路由，实时可视化</p>
        </div>
      </div>
      <div class="cfg-viz">
        <div class="cfg-viz__card">
          <div class="cfg-viz__head"><span class="cfg-viz__dot dot--gold"></span><b>编排就绪度</b></div>
          <p class="cfg-viz__sub">能力开关 + 渠道密钥就绪综合评分</p>
          <div ref="llmGaugeEl" class="cfg-viz__chart"></div>
        </div>
        <div class="cfg-viz__card">
          <div class="cfg-viz__head"><span class="cfg-viz__dot dot--blue"></span><b>渠道密钥就绪</b></div>
          <p class="cfg-viz__sub">已配置 API Key 的服务商占比</p>
          <div ref="llmKeyEl" class="cfg-viz__chart"></div>
        </div>
        <div class="cfg-viz__card">
          <div class="cfg-viz__head"><span class="cfg-viz__dot dot--violet"></span><b>环节路由覆盖</b></div>
          <p class="cfg-viz__sub">每个分析环节是否完成「服务商 + 模型」编排</p>
          <div ref="llmRouteEl" class="cfg-viz__chart"></div>
        </div>
      </div>
    </section>

    <!-- ===== 基础设置：开关主卡 + 参数卡 ===== -->
    <section class="cfg-section tg-fade-up">
      <div class="cfg-section__head">
        <div class="cfg-section__title">
          <h3><span class="cfg-section__ic"><el-icon :size="15"><Setting /></el-icon></span>基础设置</h3>
          <p>全局开关与并发参数</p>
        </div>
      </div>
      <div class="cfg-basic">
        <div class="toggle-card">
          <span class="toggle-card__tile"><el-icon :size="22"><MagicStick /></el-icon></span>
          <div class="toggle-card__body">
            <b>启用大模型</b>
            <p>关闭后主流程回退规则模式（词典 / 结构校验兜底）</p>
          </div>
          <el-switch v-model="cfg.enabled" size="large" />
        </div>
        <div class="param-card">
          <span class="param-card__label">并发上限</span>
          <b>{{ cfg.maxConcurrentCalls ?? '—' }}</b>
          <p>单任务最大并发调用数（application.yml）</p>
        </div>
        <div class="param-card">
          <span class="param-card__label">单环节调用上限</span>
          <b>{{ cfg.maxCallsPerStage ?? '—' }}</b>
          <p>单个分析任务单环节最大调用次数</p>
        </div>
      </div>
    </section>

    <!-- ===== 服务商配置：卡片网格 ===== -->
    <section class="cfg-section tg-fade-up">
      <div class="cfg-section__head">
        <div class="cfg-section__title">
          <h3><span class="cfg-section__ic cfg-section__ic--blue"><el-icon :size="15"><Connection /></el-icon></span>服务商渠道</h3>
          <p>OpenAI 兼容服务商，密钥脱敏存储</p>
        </div>
        <el-button type="primary" round plain @click="addProvider">
          <el-icon style="margin-right: 4px"><Plus /></el-icon>新增服务商
        </el-button>
      </div>

      <div class="provider-grid">
        <div
          v-for="(p, idx) in providerForm"
          :key="p.provider || 'idx-' + idx"
          class="provider-card"
          :class="{ 'is-existing': !p.isNew }"
        >
          <div class="provider-card__top">
            <span class="provider-card__tile">{{ (p.provider || '?').slice(0, 1).toUpperCase() }}</span>
            <div class="provider-card__id">
              <label>服务商标识</label>
              <el-input
                v-model="p.provider"
                size="small"
                placeholder="如 qwen / deepseek"
                :disabled="!p.isNew"
              />
            </div>
            <span
              class="key-dot"
              :class="{ 'is-ready': p.apiKeyConfigured }"
              :title="p.apiKeyConfigured ? '已配置密钥' : '未配置密钥'"
            ></span>
            <el-button
              circle
              text
              class="provider-card__del"
              :disabled="providerForm.length <= 1"
              :aria-label="'删除服务商 ' + p.provider"
              @click="removeProvider(idx)"
            >
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
          <div class="provider-card__field">
            <label>Base URL</label>
            <el-input v-model="p.baseUrl" class="provider-card__mono" placeholder="https://api.example.com" />
          </div>
          <div class="provider-card__field">
            <label>API Key</label>
            <el-input
              v-model="p.apiKey"
              type="password"
              show-password
              class="provider-card__mono"
              :placeholder="p.apiKeyConfigured ? '已配置（留空保持不变）' : '未配置（必填方可启用）'"
            />
          </div>
          <div class="provider-card__foot">
            <el-button :loading="testingKey === p.provider" size="small" round @click="handleTest(p.provider)">
              <el-icon><Connection /></el-icon>连通测试
            </el-button>
            <transition name="tg-fade">
              <span
                v-if="testResult && testResult.provider === p.provider"
                class="test-reply"
                :class="testResult.success ? 'ok' : 'err'"
              >
                <el-icon :size="13">{{ testResult.success ? 'CircleCheckFilled' : 'CircleCloseFilled' }}</el-icon>
                {{ testResult.reply }}
              </span>
            </transition>
          </div>
        </div>
      </div>
      <div v-if="!providerForm.length" class="provider-empty">
        <el-icon :size="30" color="var(--tg-slate)"><Connection /></el-icon>
        <p>暂无服务商，点击「新增服务商」开始</p>
      </div>
    </section>

    <!-- ===== 按环节路由与模型：管线视图 ===== -->
    <section class="cfg-section tg-fade-up">
      <div class="cfg-section__head">
        <div class="cfg-section__title">
          <h3><span class="cfg-section__ic cfg-section__ic--violet"><el-icon :size="15"><Share /></el-icon></span>按环节路由与模型</h3>
          <p>不同分析环节可分配不同服务商与模型</p>
        </div>
      </div>
      <div class="route-pipeline">
        <div v-for="stage in stageList" :key="stage.key" class="route-row">
          <span class="route-row__tile"><el-icon :size="17"><component :is="stageIcon(stage.key)" /></el-icon></span>
          <div class="route-row__label">
            <b>{{ stage.label }}</b>
            <small>{{ stage.key }}</small>
          </div>
          <span class="route-row__arrow"><el-icon :size="14"><Right /></el-icon></span>
          <div class="route-row__sel">
            <label>服务商</label>
            <el-select v-model="routingForm[stage.key]" style="width: 168px" :placeholder="stage.label">
              <el-option v-for="p in availableProviders" :key="p" :label="providerName(p)" :value="p" />
            </el-select>
          </div>
          <div class="route-row__sel">
            <label>模型</label>
            <el-select
              v-model="modelsForm[stage.key]"
              filterable
              allow-create
              default-first-option
              placeholder="选择或输入模型名"
              style="width: 250px"
            >
              <el-option v-for="m in suggestedModels(routingForm[stage.key])" :key="m" :label="m" :value="m" />
            </el-select>
          </div>
        </div>
      </div>
    </section>

    <!-- W5：调用用量仪表带（真实调用落库 tg_llm_call_log；手写 CSS 柱条，与上方 ECharts 面板形成材质对比） -->
    <section class="cfg-section tg-fade-up usage-band">
      <div class="cfg-section__head">
        <div class="cfg-section__title">
          <h3><span class="cfg-section__ic cfg-section__ic--violet"><el-icon :size="15"><DataLine /></el-icon></span>调用用量</h3>
          <p>近 {{ usageRange }} 天真实调用统计 · 每次调用由执行治理层落库</p>
        </div>
        <el-radio-group v-model="usageRange" size="small" @change="loadUsage">
          <el-radio-button :value="7">7 天</el-radio-button>
          <el-radio-button :value="30">30 天</el-radio-button>
          <el-radio-button :value="90">90 天</el-radio-button>
        </el-radio-group>
      </div>
      <div class="usage-band__body" v-loading="usageLoading">
        <div class="usage-kpis">
          <div class="usage-kpi"><b>{{ usage.total }}</b><span>总调用</span></div>
          <div class="usage-kpi"><b class="is-ok">{{ usage.successRate }}%</b><span>成功率</span></div>
          <div class="usage-kpi"><b :class="{ 'is-fail': usage.failed > 0 }">{{ usage.failed }}</b><span>失败</span></div>
          <div class="usage-kpi"><b>{{ usage.avgLatencyMs }}<i>ms</i></b><span>平均耗时</span></div>
        </div>
        <div class="usage-days">
          <div v-for="d in usage.byDay" :key="d.day" class="usage-day" :title="`${d.day}：${d.total} 次（成功 ${d.success}）`">
            <i class="usage-day__bar" :style="{ height: dayBarH(d.total) + '%' }">
              <em v-if="d.total - d.success > 0" :style="{ height: ((d.total - d.success) / Math.max(d.total, 1)) * 100 + '%' }"></em>
            </i>
            <span>{{ String(d.day).slice(5) }}</span>
          </div>
          <p v-if="!usage.byDay || !usage.byDay.length" class="usage-days__empty">暂无调用记录 —— 启用大模型并触发分析 / 问答后这里会亮起来</p>
        </div>
        <div v-if="usage.byScene && usage.byScene.length" class="usage-scenes">
          <div v-for="s in usage.byScene" :key="s.scene" class="usage-scene">
            <code>{{ s.scene }}</code>
            <span class="usage-scene__bar"><i :style="{ width: scenePct(s) + '%' }"></i></span>
            <b>{{ s.total }}</b>
            <small v-if="s.failed" class="usage-scene__fail">败 {{ s.failed }}</small>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, nextTick } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import * as echarts from 'echarts'
import { chartColors as C, chartThemeName, chartText, chartFaint, chartAxisLine, chartSplitLine, chartTooltipBg, chartTitleColor, onChartThemeChange } from '@/utils/echartsTheme'
import {
  ArrowLeft, Check, Plus, Delete, Connection, Refresh, Cpu, Grid, Share,
  DataBoard, Setting, MagicStick, Document, Aim, Memo, WarningFilled, Right, Odometer, DataLine
} from '@element-plus/icons-vue'
import { llmApi } from '@/api'

const loading = ref(false)
const saving = ref(false)
const testingKey = ref('')
const testResult = ref(null)

/** 环节定义（GAP-021 路由键）：需求→GLM、Alloy/代码/缺陷→DeepSeek 为推荐默认 */
const stageList = [
  { key: 'requirement', label: '需求分析' },
  { key: 'alloy', label: 'Alloy 规约' },
  { key: 'code-explain', label: '代码解释' },
  { key: 'defect-explain', label: '缺陷解释' }
]

const cfg = reactive({
  source: '',
  enabled: false,
  maxConcurrentCalls: null,
  maxCallsPerStage: null
})

/** 可编辑 provider 表单（apiKey 留空表示保持不变） */
const providerForm = ref([])
const routingForm = reactive({})
const modelsForm = reactive({})

/** 当前表单中有效的服务商标识列表（供路由下拉选择） */
const availableProviders = computed(() =>
  providerForm.value.map(p => (p.provider || '').trim()).filter(Boolean)
)

const providerName = (p) => ({ deepseek: 'DeepSeek', glm: '智谱 GLM', local: '本地服务' }[p] || p || '未命名')

/** 已配置密钥的渠道数 */
const llmKeyed = computed(() => providerForm.value.filter(p => p.apiKeyConfigured).length)
/** 已完成「服务商+模型」编排的环节数 */
const llmRouted = computed(() =>
  stageList.filter(s => (routingForm[s.key] || '').trim() && (modelsForm[s.key] || '').trim()).length
)

/** 各分析环节的门户图标（W：管线视图视觉强化） */
const stageIcon = (key) => ({
  requirement: Document,
  alloy: Aim,
  'code-explain': Cpu,
  'defect-explain': WarningFilled
}[key] || Setting)

/** 按服务商给出常见模型建议（选中项仍可在下拉中自由输入自定义模型） */
const commonModels = {
  deepseek: ['deepseek-chat', 'deepseek-reasoner', 'deepseek-v4-flash'],
  glm: ['glm-5.3', 'glm-4.6', 'glm-4.5', 'glm-4-flash', 'glm-4-air'],
  qwen: ['qwen-plus', 'qwen-max', 'qwen-turbo', 'qwen3-235b-a22b', 'qwen2.5-72b-instruct'],
  kimi: ['moonshot-v1-8k', 'moonshot-v1-32k', 'moonshot-v1-128k'],
  openai: ['gpt-4o', 'gpt-4o-mini', 'gpt-4.1', 'gpt-4.1-mini', 'o3-mini'],
  ollama: ['qwen2.5:7b', 'deepseek-r1:7b', 'llama3.1:8b', 'mistral:7b']
}
const suggestedModels = (provider) => commonModels[provider] || commonModels.local || []

/** 新增服务商行（任意 OpenAI 兼容服务商均可，GAP-021 多 provider） */
const addProvider = () => {
  if (providerForm.value.some(p => !(p.provider || '').trim())) {
    ElMessage.warning('请先填写上一行的服务商标识')
    return
  }
  providerForm.value.push({ provider: '', baseUrl: '', apiKey: '', apiKeyConfigured: false, isNew: true })
}

/** 删除服务商行；若路由仍指向被删服务商则回退到剩余首个 */
const removeProvider = async (idx) => {
  const p = providerForm.value[idx]
  if (!p) return
  try {
    await ElMessageBox.confirm(
      `确定删除服务商「${p.provider || '未命名'}」？其 API Key 将从系统移除。`,
      '删除确认',
      { type: 'warning', confirmButtonText: '删除', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  providerForm.value.splice(idx, 1)
  const remaining = availableProviders.value
  Object.keys(routingForm).forEach(k => {
    if (!remaining.includes(routingForm[k])) {
      routingForm[k] = remaining.length ? remaining[0] : ''
    }
  })
}

const loadData = async () => {
  loading.value = true
  try {
    const data = await llmApi.getConfig()
    cfg.source = data.source
    cfg.enabled = !!data.enabled
    cfg.maxConcurrentCalls = data.maxConcurrentCalls
    cfg.maxCallsPerStage = data.maxCallsPerStage
    providerForm.value = Object.entries(data.providers || {}).map(([key, p]) => ({
      provider: key,
      baseUrl: p?.baseUrl || '',
      apiKey: '',
      apiKeyConfigured: !!p?.apiKeyConfigured,
      isNew: false
    }))
    Object.keys(routingForm).forEach(k => delete routingForm[k])
    Object.keys(modelsForm).forEach(k => delete modelsForm[k])
    Object.assign(routingForm, data.routing || {})
    Object.assign(modelsForm, data.models || {})
  } catch (e) {
    ElMessage.error(e.message || '加载配置失败')
  } finally {
    loading.value = false
  }
}

const handleSave = async () => {
  saving.value = true
  try {
    await llmApi.saveConfig({
      enabled: cfg.enabled,
      providers: providerForm.value
        .filter(p => (p.provider || '').trim())
        .map(p => ({ provider: p.provider.trim(), baseUrl: p.baseUrl, apiKey: p.apiKey || '' })),
      routing: { ...routingForm },
      models: { ...modelsForm }
    })
    ElMessage.success('大模型配置已保存')
    // 保存后重新加载，刷新脱敏状态
    await loadData()
    nextTick(renderLlmCharts)
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const handleTest = async (provider) => {
  if (!provider) {
    ElMessage.warning('请先填写服务商标识再测试')
    return
  }
  testingKey.value = provider
  testResult.value = null
  try {
    const data = await llmApi.testConnection(provider)
    // 后端返回 Result：success=200 时 data 为 reply，否则报错
    testResult.value = { provider, success: true, reply: typeof data === 'string' ? data : '连接成功' }
  } catch (e) {
    testResult.value = { provider, success: false, reply: e.message || '连通失败（网络/密钥）' }
  } finally {
    testingKey.value = ''
  }
}

onChartThemeChange(() => { llmGaugeChart?.dispose(); llmGaugeChart = null; llmKeyChart?.dispose(); llmKeyChart = null; llmRouteChart?.dispose(); llmRouteChart = null; renderLlmCharts() })

onMounted(() => {
  loadData().then(() => nextTick(renderLlmCharts))
  loadUsage()
  window.addEventListener('resize', onLlmResize)
})

/* ============ W5：调用用量仪表带 ============ */
const usageLoading = ref(false)
const usageRange = ref(30)
const usage = ref({ total: 0, success: 0, failed: 0, successRate: 0, avgLatencyMs: 0, byDay: [], byScene: [] })

const loadUsage = async () => {
  usageLoading.value = true
  try {
    usage.value = await llmApi.usage(usageRange.value)
  } catch { /* 用量统计失败不影响配置页主体 */ }
  finally { usageLoading.value = false }
}
const dayBarH = (t) => Math.round((t / Math.max(...usage.value.byDay.map(x => x.total), 1)) * 100)
const scenePct = (s) => Math.round((s.total / Math.max(...usage.value.byScene.map(x => x.total), 1)) * 100)

onBeforeUnmount(() => {
  window.removeEventListener('resize', onLlmResize)
  if (llmGaugeChart) llmGaugeChart.dispose()
  if (llmKeyChart) llmKeyChart.dispose()
  if (llmRouteChart) llmRouteChart.dispose()
})

/* ============ W4 v4：编排全景可视化 ============ */
const llmGaugeEl = ref(null)
const llmKeyEl = ref(null)
const llmRouteEl = ref(null)
let llmGaugeChart = null
let llmKeyChart = null
let llmRouteChart = null

const VIZ_TOOLTIP = {
  backgroundColor: 'rgba(43,36,28,.92)',
  borderColor: 'rgba(201,155,63,.4)',
  textStyle: { color: '#f4ead6' }
}

/** 就绪度评分：能力启用 40% + 渠道密钥覆盖 60% */
const llmReadiness = computed(() => {
  const provs = providerForm.value.filter(p => (p.provider || '').trim())
  if (!provs.length) return cfg.enabled ? 40 : 0
  const keyRatio = provs.filter(p => p.apiKeyConfigured).length / provs.length
  return Math.round((cfg.enabled ? 40 : 0) + keyRatio * 60)
})

const renderLlmCharts = () => {
  // 1) 编排就绪度仪表盘
  if (llmGaugeEl.value) {
    if (!llmGaugeChart) llmGaugeChart = echarts.init(llmGaugeEl.value, chartThemeName())
    const score = llmReadiness.value
    llmGaugeChart.setOption({
      animationDuration: 800,
      series: [{
        type: 'gauge',
        startAngle: 210,
        endAngle: -30,
        min: 0,
        max: 100,
        radius: '96%',
        center: ['50%', '58%'],
        progress: {
          show: true,
          width: 13,
          roundCap: true,
          itemStyle: { color: score >= 70 ? C.success : score >= 40 ? C.warning : C.danger }
        },
        axisLine: { lineStyle: { width: 13, color: [[1, 'rgba(201,155,63,.14)']] } },
        axisTick: { show: false },
        splitLine: { show: false },
        axisLabel: { show: false },
        pointer: { show: false },
        anchor: { show: false },
        title: { show: true, offsetCenter: [0, '44%'], color: 'var(--tg-text-secondary)', fontSize: 12 },
        detail: {
          valueAnimation: true,
          offsetCenter: [0, '0%'],
          formatter: '{value}%',
          fontSize: 28,
          fontWeight: 700,
          color: 'var(--tg-text-primary)'
        },
        data: [{ value: score, name: cfg.enabled ? '运行中' : '未启用' }]
      }]
    }, true)
  }
  // 2) 渠道密钥就绪环形图
  if (llmKeyEl.value) {
    if (!llmKeyChart) llmKeyChart = echarts.init(llmKeyEl.value, chartThemeName())
    const provs = providerForm.value.filter(p => (p.provider || '').trim())
    const ready = provs.filter(p => p.apiKeyConfigured).length
    const notReady = provs.length - ready
    const hasData = provs.length > 0
    llmKeyChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}：{c} 个（{d}%）', ...VIZ_TOOLTIP },
      title: {
        text: String(provs.length),
        subtext: '渠道总数',
        left: 'center',
        top: '36%',
        textStyle: { fontSize: 22, fontWeight: 700, color: 'var(--tg-text-primary)' },
        subtextStyle: { fontSize: 11, color: 'var(--tg-text-secondary)' }
      },
      series: [{
        type: 'pie',
        radius: ['56%', '80%'],
        center: ['50%', '50%'],
        padAngle: 3,
        itemStyle: { borderRadius: 8, borderColor: 'rgba(255,244,224,.9)', borderWidth: 2 },
        label: { show: false },
        emphasis: { scaleSize: 6, itemStyle: { shadowBlur: 16, shadowColor: 'rgba(201,155,63,.4)' } },
        data: hasData
          ? [
              { name: '已配置密钥', value: ready, itemStyle: { color: '#6E93B0' } },
              { name: '未配置密钥', value: notReady, itemStyle: { color: 'rgba(180,170,148,.35)' } }
            ]
          : [{ name: '暂无渠道', value: 1, itemStyle: { color: 'rgba(180,170,148,.22)' } }]
      }]
    }, true)
  }
  // 3) 环节路由覆盖（横向条）
  if (llmRouteEl.value) {
    if (!llmRouteChart) llmRouteChart = echarts.init(llmRouteEl.value, chartThemeName())
    const routed = stageList.map(s => ({
      label: s.label,
      done: !!((routingForm[s.key] || '').trim()) && !!((modelsForm[s.key] || '').trim()),
      provider: (routingForm[s.key] || '').trim(),
      model: (modelsForm[s.key] || '').trim()
    }))
    llmRouteChart.setOption({
      animationDuration: 600,
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (ps) => {
          const d = routed[ps[0].dataIndex]
          return `<b>${d.label}</b><br/>${d.done ? `${providerName(d.provider)} · ${d.model}` : '未完成编排'}`
        },
        ...VIZ_TOOLTIP
      },
      grid: { left: 6, right: 40, top: 8, bottom: 6, containLabel: true },
      xAxis: { type: 'value', max: 1, show: false },
      yAxis: {
        type: 'category',
        data: routed.map(d => d.label),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: 'var(--tg-text-primary)', fontSize: 12, fontWeight: 500 }
      },
      series: [{
        type: 'bar',
        data: routed.map(d => ({
          value: d.done ? 1 : 0,
          itemStyle: {
            color: d.done
              ? new echarts.graphic.LinearGradient(0, 0, 1, 0, [
                  { offset: 0, color: '#6E93B0' },
                  { offset: 1, color: '#9AB6CC' }
                ])
              : 'rgba(180,170,148,.3)',
            borderRadius: [0, 8, 8, 0]
          }
        })),
        barWidth: 14,
        showBackground: true,
        backgroundStyle: { color: 'rgba(201,155,63,.08)', borderRadius: [0, 8, 8, 0] },
        label: {
          show: true,
          position: 'right',
          formatter: (p) => (routed[p.dataIndex].done ? '✓ 已编排' : '未编排'),
          color: (p) => (routed[p.dataIndex].done ? '#3F6B8F' : 'var(--tg-slate)'),
          fontSize: 11
        }
      }]
    }, true)
  }
}

const onLlmResize = () => {
  if (llmGaugeChart) llmGaugeChart.resize()
  if (llmKeyChart) llmKeyChart.resize()
  if (llmRouteChart) llmRouteChart.resize()
}
</script>

<style scoped>
.llm-config-page {
  position: relative;
  max-width: 1200px;
  margin: 0 auto;
}

/* ===== 状态徽章（页头操作区） ===== */
.hero-pill {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 14px;
  border-radius: 999px;
  border: 1px solid;
  font-size: 12.5px;
  font-weight: 600;
}

.hero-pill em {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.hero-pill.is-on {
  border-color: rgba(154, 156, 107, 0.35);
  background: rgba(154, 156, 107, 0.14);
  color: #55682e;
}

.hero-pill.is-on em {
  background: #7d9158;
  box-shadow: 0 0 0 4px rgba(125, 145, 88, 0.18);
  animation: tg-pulse 2s ease-in-out infinite;
}

.hero-pill.is-off {
  border-color: rgba(180, 170, 148, 0.35);
  background: rgba(180, 170, 148, 0.12);
  color: var(--tg-text-secondary);
}

.hero-pill.is-off em {
  background: var(--tg-slate);
}

/* ===== 提示条 ===== */
.cfg-tip {
  margin-bottom: 22px;
  border-radius: 12px;
}

/* 能力概览（.cfg-stats / .cfg-stat 已提为全局公共类，见 index.css） */

/* ===== 基础设置：开关主卡 + 参数卡 ===== */
.cfg-basic {
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr;
  gap: 16px;
}

.toggle-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 18px;
  border-radius: 16px;
  background: linear-gradient(135deg, rgba(232, 200, 119, 0.12), rgba(143, 107, 34, 0.05));
  border: 1px solid rgba(201, 155, 63, 0.24);
}

.toggle-card__tile {
  width: 46px;
  height: 46px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 14px;
  background: var(--tg-accent-gradient);
  color: #fff;
  box-shadow: var(--tg-glow-accent);
}

.toggle-card__body {
  flex: 1;
  min-width: 0;
}

.toggle-card__body b {
  font-size: 15px;
  color: var(--tg-text-primary);
}

.toggle-card__body p {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--tg-text-secondary);
}

.param-card {
  padding: 16px 18px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.66);
  border: 1px solid rgba(180, 170, 148, 0.2);
  transition: transform 0.3s var(--tg-ease-spring), border-color 0.3s ease;
}

.param-card:hover {
  transform: translateY(-2px);
  border-color: rgba(201, 155, 63, 0.36);
}

.param-card__label {
  display: block;
  font-size: 12px;
  color: var(--tg-text-secondary);
  margin-bottom: 6px;
}

.param-card b {
  font-size: 22px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--tg-text-primary);
}

.param-card p {
  margin: 6px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--tg-slate);
}

/* 分区卡（.cfg-section 系列已提为全局公共类，见 index.css） */

/* ===== 服务商卡片网格 ===== */
.provider-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(340px, 1fr));
  gap: 16px;
}

.provider-card {
  position: relative;
  padding: 16px 18px 14px;
  border-radius: 16px;
  background: linear-gradient(165deg, rgba(255, 255, 255, 0.8), rgba(255, 246, 230, 0.45));
  border: 1px solid rgba(180, 170, 148, 0.2);
  box-shadow: var(--tg-shadow-card);
  transition: transform 0.3s var(--tg-ease-spring), box-shadow 0.3s ease, border-color 0.3s ease;
}

.provider-card:hover {
  transform: translateY(-3px);
  border-color: rgba(201, 155, 63, 0.34);
  box-shadow: 0 14px 30px rgba(60, 45, 25, 0.1);
}

.provider-card.is-existing {
  border-left: 3px solid rgba(201, 155, 63, 0.35);
}

.provider-card__top {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}

.provider-card__tile {
  width: 40px;
  height: 40px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 13px;
  font-size: 16px;
  font-weight: 700;
  color: #fff;
  background: var(--tg-accent-gradient);
  box-shadow: var(--tg-glow-accent);
}

.provider-card__id {
  flex: 1;
  min-width: 0;
}

.provider-card__id label {
  display: block;
  font-size: 11px;
  color: var(--tg-slate);
  margin-bottom: 4px;
}

.provider-card__id .el-input__wrapper {
  font-weight: 600;
}

.key-dot {
  width: 10px;
  height: 10px;
  flex-shrink: 0;
  border-radius: 50%;
  background: rgba(180, 170, 148, 0.5);
  box-shadow: 0 0 0 3px rgba(180, 170, 148, 0.14);
}

.key-dot.is-ready {
  background: #7d9158;
  box-shadow: 0 0 0 3px rgba(125, 145, 88, 0.18);
}

.provider-card__del {
  flex-shrink: 0;
  color: var(--tg-slate);
}

.provider-card__del:hover {
  color: var(--tg-danger);
  background: rgba(194, 94, 76, 0.1);
}

.provider-card__field {
  margin-bottom: 12px;
}

.provider-card__field label {
  display: block;
  font-size: 11px;
  color: var(--tg-slate);
  margin-bottom: 5px;
}

.provider-card__mono .el-input__wrapper {
  font-family: var(--tg-font-mono);
  font-size: 12.5px;
}

.provider-card__foot {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 32px;
}

.test-reply {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 12px;
}

.test-reply.ok {
  color: var(--tg-success);
}

.test-reply.err {
  color: var(--tg-danger);
}

.provider-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 34px 0 10px;
}

.provider-empty p {
  margin: 0;
  font-size: 13px;
  color: var(--tg-text-secondary);
}

/* ===== 路由管线 ===== */
.route-pipeline {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.route-row {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 14px 18px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.55);
  border: 1px solid rgba(180, 170, 148, 0.16);
  transition: border-color 0.3s ease, background 0.3s ease, transform 0.3s var(--tg-ease-spring);
}

.route-row:hover {
  border-color: rgba(201, 155, 63, 0.32);
  background: rgba(255, 255, 255, 0.78);
  transform: translateX(4px);
}

.route-row__tile {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 12px;
  background: linear-gradient(135deg, rgba(232, 200, 119, 0.22), rgba(143, 107, 34, 0.12));
  color: var(--tg-accent-strong);
}

.route-row__label {
  width: 132px;
  flex-shrink: 0;
}

.route-row__label b {
  display: block;
  font-size: 14px;
  font-weight: 600;
  color: var(--tg-text-primary);
}

.route-row__label small {
  font-size: 11px;
  color: var(--tg-slate);
  font-family: var(--tg-font-mono);
}

.route-row__arrow {
  flex-shrink: 0;
  color: var(--tg-accent);
  animation: tg-pulse-arrow 2.2s ease-in-out infinite;
}

.route-row__sel {
  min-width: 0;
}

.route-row__sel label {
  display: block;
  font-size: 11px;
  color: var(--tg-slate);
  margin-bottom: 5px;
}

@keyframes tg-pulse-arrow {
  0%, 100% { opacity: 0.45; transform: translateX(0); }
  50% { opacity: 1; transform: translateX(3px); }
}

/* ===== 窄屏适配 ===== */
@media (max-width: 960px) {
  .cfg-stats {
    grid-template-columns: repeat(2, 1fr);
  }

  .cfg-basic {
    grid-template-columns: 1fr;
  }

  .route-row {
    flex-wrap: wrap;
    gap: 10px;
  }

  .route-row__arrow {
    display: none;
  }
}

@media (max-width: 720px) {
  .cfg-stats {
    grid-template-columns: 1fr;
  }

  .route-row__label,
  .route-row__sel .el-select {
    width: 100% !important;
  }
}
/* ============ W5：调用用量仪表带（手写柱条 + 场景分布） ============ */
.usage-band__body { display: flex; flex-direction: column; gap: 16px; }
.usage-kpis { display: grid; grid-template-columns: repeat(4, 1fr); gap: 12px; }
.usage-kpi {
  display: flex; flex-direction: column; gap: 3px; padding: 13px 16px;
  border-radius: 13px; background: rgba(217, 169, 102, 0.08);
  border: 1px solid rgba(217, 169, 102, 0.14);
}
.usage-kpi b { font-size: 22px; font-weight: 700; line-height: 1.1; color: var(--tg-text-primary); font-variant-numeric: tabular-nums; }
.usage-kpi b i { font-style: normal; font-size: 11px; font-weight: 500; color: var(--tg-text-secondary); margin-left: 2px; }
.usage-kpi b.is-ok { color: var(--tg-success); }
.usage-kpi b.is-fail { color: var(--tg-danger); }
.usage-kpi span { font-size: 11.5px; color: var(--tg-text-secondary); }

.usage-days {
  display: flex; align-items: flex-end; gap: 6px; min-height: 110px;
  padding: 12px 14px 8px; border-radius: 13px;
  background:
    radial-gradient(120% 160% at 0% 0%, rgba(232, 200, 119, 0.2), transparent 55%),
    linear-gradient(180deg, rgba(255, 250, 240, 0.9), rgba(255, 246, 230, 0.65));
  border: 1px solid rgba(201, 155, 63, 0.18);
  overflow-x: auto;
}
.usage-day { display: flex; flex-direction: column; align-items: center; gap: 5px; min-width: 22px; flex: 1; }
.usage-day__bar {
  position: relative; display: block; width: 12px; min-height: 3px;
  border-radius: 5px 5px 2px 2px;
  background: linear-gradient(180deg, #E8C877, rgba(143, 107, 34, 0.75));
  transition: height 0.5s var(--tg-ease);
}
.usage-day__bar em {
  position: absolute; left: 0; right: 0; bottom: 0; display: block;
  border-radius: 0 0 2px 2px; background: #D97A66;
}
.usage-day span { font-size: 9.5px; color: var(--tg-slate); white-space: nowrap; }
.usage-days__empty { flex: 1; margin: 0; text-align: center; align-self: center; font-size: 12.5px; color: var(--tg-slate); }

.usage-scenes { display: flex; flex-direction: column; gap: 8px; }
.usage-scene { display: grid; grid-template-columns: 130px 1fr 40px 48px; align-items: center; gap: 12px; }
.usage-scene code { font-family: var(--tg-font-mono); font-size: 11px; color: var(--tg-text-secondary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.usage-scene__bar { height: 7px; border-radius: 999px; background: rgba(217, 169, 102, 0.12); overflow: hidden; }
.usage-scene__bar i { display: block; height: 100%; border-radius: 999px; background: var(--tg-accent-gradient); transition: width 0.5s var(--tg-ease); }
.usage-scene b { text-align: right; font-size: 12.5px; color: var(--tg-text-primary); font-variant-numeric: tabular-nums; }
.usage-scene__fail { font-size: 10.5px; color: var(--tg-danger); font-variant-numeric: tabular-nums; }

@media (max-width: 720px) {
  .usage-kpis { grid-template-columns: repeat(2, 1fr); }
  .usage-scene { grid-template-columns: 90px 1fr 36px 40px; }
}
</style>
