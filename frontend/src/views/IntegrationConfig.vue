<template>
  <div class="integration-config-page">
    <!-- W4 v3：封面式彩色光晕（装饰层） -->
    <div class="page-glow" aria-hidden="true"><i></i><i></i><i></i></div>
    <!-- ===== 页头：标题 + 标签 ===== -->
    <div class="page-header tg-fade-up">
      <div>
        <el-button link class="page-header__back" @click="$router.back()">
          <el-icon><ArrowLeft /></el-icon> 返回
        </el-button>
        <p class="tg-kicker">Connections</p>
        <h2 class="page-header__title">集成连通测试</h2>
        <p class="page-header__desc">测试 Jira / 禅道 / 企业微信 / 钉钉连通性，支持缺陷状态入站同步</p>
      </div>
      <div class="page-header__actions">
        <span class="mini-pill mini-pill--slate">管理员</span>
      </div>
    </div>

    <!-- ===== 1. 概览统计条：4 格（富信息 W4 v5） ===== -->
    <div class="cfg-stats tg-fade-up" style="animation-delay: 0.05s">
      <div class="cfg-stat cfg-stat--rich">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--gold"><el-icon :size="17"><Connection /></el-icon></span>
          <span class="cfg-stat__label">已配置服务</span>
        </div>
        <div class="cfg-stat__num">
          <b>{{ overviewStats.serviceCount }}</b>
          <span class="cfg-stat__unit">个服务</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: (overviewStats.serviceCount ? Math.round(reachableCount / overviewStats.serviceCount * 100) : 0) + '%' }"></i></span>
          <span class="cfg-stat__ratio">{{ reachableCount }}/{{ overviewStats.serviceCount }} 已连通</span>
        </div>
      </div>
      <div class="cfg-stat cfg-stat--rich">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--blue"><el-icon :size="17"><Key /></el-icon></span>
          <span class="cfg-stat__label">已配置凭据</span>
        </div>
        <div class="cfg-stat__num">
          <b>{{ overviewStats.credCount }}</b>
          <span class="cfg-stat__unit">项</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: (overviewStats.serviceCount ? Math.round(overviewStats.credCount / overviewStats.serviceCount * 100) : 0) + '%' }"></i></span>
          <span class="cfg-stat__ratio">{{ overviewStats.credCount }}/{{ overviewStats.serviceCount }} 已配置</span>
        </div>
      </div>
      <div class="cfg-stat cfg-stat--rich">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--violet"><el-icon :size="17"><Switch /></el-icon></span>
          <span class="cfg-stat__label">入站同步开关</span>
        </div>
        <div class="cfg-stat__num">
          <b :class="{ 'is-on': inboundEnabled }">
            {{ inboundEnabled ? '已启用' : '未启用' }}
          </b>
          <span class="cfg-stat__unit">入站</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: (inboundEnabled ? 100 : 0) + '%' }"></i></span>
          <span class="cfg-stat__ratio">{{ inboundEnabled ? '每 ' + inboundIntervalLabel : '未启用' }}</span>
        </div>
      </div>
      <div class="cfg-stat cfg-stat--rich">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--rose"><el-icon :size="17"><Timer /></el-icon></span>
          <span class="cfg-stat__label">今日同步次数</span>
        </div>
        <div class="cfg-stat__num">
          <b>{{ overviewStats.todaySync }}</b>
          <span class="cfg-stat__unit">次</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: (scannedCount ? 100 : 0) + '%' }"></i></span>
          <span class="cfg-stat__ratio">已扫描 {{ scannedCount }} 条</span>
        </div>
      </div>
    </div>

    <!-- ===== 集成健康可视化：信息可视化（W4 v4） ===== -->
    <section class="cfg-section tg-fade-up" style="animation-delay: 0.08s">
      <div class="cfg-section__head">
        <div class="cfg-section__title">
          <h3>
            <span class="cfg-section__ic cfg-section__ic--rose"><el-icon :size="16"><Odometer /></el-icon></span>
            集成健康可视化
          </h3>
          <p>入站同步结果 · 服务就绪度 · 连通健康分，实时可视化</p>
        </div>
      </div>
      <div class="cfg-viz">
        <div class="cfg-viz__card">
          <div class="cfg-viz__head"><span class="cfg-viz__dot dot--sage"></span><b>入站同步结果</b></div>
          <p class="cfg-viz__sub">最近一次入站同步：变更 / 失败 / 未变化分布</p>
          <div ref="inboundDonutEl" class="cfg-viz__chart"></div>
        </div>
        <div class="cfg-viz__card">
          <div class="cfg-viz__head"><span class="cfg-viz__dot dot--blue"></span><b>服务就绪度</b></div>
          <p class="cfg-viz__sub">凭据已配置 + 连通状态，综合 0-100</p>
          <div ref="readyBarEl" class="cfg-viz__chart"></div>
        </div>
        <div class="cfg-viz__card">
          <div class="cfg-viz__head"><span class="cfg-viz__dot dot--gold"></span><b>连通健康分</b></div>
          <p class="cfg-viz__sub">4 个集成服务的平均就绪水平</p>
          <div ref="healthGaugeEl" class="cfg-viz__chart"></div>
        </div>
      </div>
    </section>

    <!-- ===== 2. 出站服务：精美卡片网格 ===== -->
    <section class="cfg-section tg-fade-up" style="animation-delay: 0.1s">
      <div class="cfg-section__head">
        <div class="cfg-section__title">
          <h3>
            <span class="cfg-section__ic cfg-section__ic--blue"><el-icon :size="16"><Promotion /></el-icon></span>
            出站推送服务
          </h3>
          <p>Webhook 通知与连通性测试：Jira / 禅道 / 企业微信 / 钉钉</p>
        </div>
      </div>

      <div class="tool-grid">
        <div
          v-for="(tool, i) in tools"
          :key="tool.key"
          class="tool-card glass-card tg-fade-up"
          :style="staggerDelay(i)"
        >
          <div class="tool-card__top">
            <div class="tool-card__id">
              <span
                class="tg-avatar tg-avatar--sm"
                :style="{ background: tool.tone, boxShadow: `0 6px 14px ${tool.tone}40` }"
              >{{ tool.name.slice(0, 1) }}</span>
              <div class="tool-card__name">
                <strong>{{ tool.name }}</strong>
                <span class="tool-card__sub">{{ tool.keyLabel }}</span>
              </div>
            </div>
            <!-- 呼吸状态灯 -->
            <span
              class="status-dot"
              :class="dotClass(tool)"
              :title="tool.statusLabel"
            ></span>
          </div>

          <div class="tool-card__meta">
            <div class="meta-line">
              <span class="meta-key">服务地址</span>
              <span class="meta-val" :class="{ 'is-empty': !tool.baseUrl }">{{ tool.baseUrl || '未配置' }}</span>
            </div>
            <div class="meta-line">
              <span class="meta-key">{{ tool.keyLabel }}</span>
              <span class="meta-val" :class="{ 'is-empty': !tool.projectKey }">{{ tool.projectKey || '未配置' }}</span>
            </div>
            <div class="meta-line">
              <span class="meta-key">凭据</span>
              <span class="meta-val">
                <span class="mini-pill" :class="tool.tokenConfigured ? 'mini-pill--green' : 'mini-pill--amber'">
                  {{ tool.tokenConfigured ? '已配置' : '未配置' }}
                </span>
              </span>
            </div>
          </div>

          <div class="tool-card__actions">
            <el-button
              type="primary"
              round
              plain
              :loading="tool.loading"
              @click="testConnection(tool)"
            >
              <template v-if="!tool.loading && tool.result">
                <span v-if="tool.reachable" class="test-badge test-badge--ok">
                  <el-icon :size="13"><CircleCheck /></el-icon>连通成功
                </span>
                <span v-else class="test-badge test-badge--fail">
                  <el-icon :size="13"><Close /></el-icon>连通失败
                </span>
              </template>
              <template v-else>
                <el-icon style="margin-right: 4px"><Connection /></el-icon>测试连通
              </template>
            </el-button>
            <el-button
              v-if="tool.webhook"
              round
              class="notify-btn"
              :loading="tool.sending"
              @click="sendTestNotify(tool)"
            >
              <el-icon style="margin-right: 4px"><Bell /></el-icon>发送测试通知
            </el-button>
          </div>

          <transition name="result">
            <el-alert
              v-if="tool.result"
              :type="tool.reachable ? 'success' : 'error'"
              :closable="false"
              show-icon
              class="result-alert"
              :title="tool.reachable ? '连通成功' : '连通失败'"
              :description="tool.result"
            />
          </transition>
        </div>
      </div>
    </section>

    <!-- ===== 3. 入站同步面板 ===== -->
    <section class="cfg-section tg-fade-up" style="animation-delay: 0.15s; margin-bottom: 0">
      <div class="cfg-section__head inbound-head">
        <div class="cfg-section__title">
          <h3>
            <span class="cfg-section__ic cfg-section__ic--violet"><el-icon :size="16"><Refresh /></el-icon></span>
            缺陷状态入站同步
          </h3>
          <p>后端定时拉取已推送缺陷在 Jira/禅道的状态变化并回写本地，无需公网地址</p>
        </div>
        <span class="mini-pill" :class="inboundEnabled ? 'mini-pill--green' : 'mini-pill--slate'">
          {{ inboundEnabled ? '定时轮询中（每 ' + inboundIntervalLabel + '）' : '未启用' }}
        </span>
      </div>

      <div class="inbound-bar">
        <div class="inbound-bar__row">
          <span class="inbound-bar__label">入站同步开关</span>
          <el-switch :model-value="inboundEnabled" disabled />
          <span class="inbound-bar__hint">运行间隔：{{ inboundIntervalLabel }}</span>
        </div>
        <div class="inbound-bar__row">
          <span class="inbound-bar__label">手动触发</span>
          <div class="inbound-bar__btns">
            <el-button type="primary" round :loading="inboundLoading" @click="triggerInboundSync">
              <el-icon style="margin-right: 4px"><Refresh /></el-icon>立即同步
            </el-button>
            <el-button round @click="loadInboundStatus">查看同步状态</el-button>
          </div>
        </div>
      </div>

      <div class="inbound-metrics">
        <div class="inbound-metric">
          <b>{{ inboundStatusText?.scanned ?? '-' }}</b>
          <span>已扫描</span>
        </div>
        <div class="inbound-metric">
          <b>{{ inboundStatusText?.changed ?? '-' }}</b>
          <span>状态变更</span>
        </div>
        <div class="inbound-metric">
          <b>{{ inboundStatusText?.failed ?? '-' }}</b>
          <span>失败</span>
        </div>
        <div class="inbound-metric">
          <b>{{ inboundStatusText?.total ?? '-' }}</b>
          <span>远程缺陷总数</span>
        </div>
      </div>

      <template v-if="inboundStatusText">
        <div class="inbound-foot">
          <span class="inbound-foot__item">
            <el-icon :size="14"><Clock /></el-icon>最近执行：{{ inboundStatusText.timestamp || '-' }}
          </span>
          <span class="inbound-foot__item">
            <el-icon :size="14"><Timer /></el-icon>耗时：{{ inboundStatusText.costMs ?? '-' }} ms
          </span>
        </div>
      </template>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { chartColors as C, chartThemeName, chartText, chartFaint, chartAxisLine, chartSplitLine, chartTooltipBg, chartTitleColor, onChartThemeChange } from '@/utils/echartsTheme'
import { ArrowLeft, Connection, Bell, Refresh, Key, Switch, Timer, Promotion, CircleCheck, Close, Clock, Odometer } from '@element-plus/icons-vue'
import { integrationApi } from '@/api'

// 入站同步（远程->本地）状态
const inboundEnabled = ref(false)
const inboundIntervalLabel = ref('5 分钟')
const inboundLoading = ref(false)
const inboundStatusText = ref(null)

async function triggerInboundSync() {
  inboundLoading.value = true
  try {
    const stats = await integrationApi.triggerInboundSync()
    inboundStatusText.value = stats
    ElMessage.success(`入站同步完成：扫描 ${stats?.scanned ?? 0} 条，变更 ${stats?.changed ?? 0} 条，失败 ${stats?.failed ?? 0} 条`)
  } catch (e) {
    ElMessage.error(`入站同步失败：${e?.response?.data?.message || e?.message || '请检查 Jira/禅道集成是否启用'}`)
  } finally {
    inboundLoading.value = false
  }
}

async function loadInboundStatus() {
  try {
    const stats = await integrationApi.inboundSyncStatus()
    inboundStatusText.value = stats
    inboundEnabled.value = !!stats?.enabled
    inboundIntervalLabel.value = formatCron(stats?.cron)
    if (stats?.lastRun === 'never') {
      ElMessage.info('尚未执行过入站同步')
    }
  } catch (e) {
    ElMessage.error(`查询同步状态失败：${e?.response?.data?.message || e?.message || '未知错误'}`)
  }
}

/** 将常见 cron 表达式转为人类可读间隔（演示用，覆盖默认配置） */
function formatCron(cron) {
  if (!cron) return '5 分钟'
  // 每 N 秒：*/N * * * * *
  const secMatch = cron.match(/^\*\/(\d+)\s+\*\s+\*\s+\*\s+\*\s+\*$/)
  if (secMatch) return `${secMatch[1]} 秒`
  // 每 N 分钟：0 */N * * * *
  const minMatch = cron.match(/^0\s+\*\/(\d+)\s+\*\s+\*\s+\*\s+\*$/)
  if (minMatch) return `${minMatch[1]} 分钟`
  return cron
}

const tools = reactive([
  {
    key: 'JIRA',
    name: 'Jira',
    tone: '#8F6B22',
    keyLabel: '项目标识',
    webhook: false,
    baseUrl: '',
    projectKey: '',
    tokenConfigured: false,
    enabled: false,
    status: 'idle',
    statusLabel: '未测试',
    loading: false,
    sending: false,
    result: '',
    reachable: false
  },
  {
    key: 'ZENTAO',
    name: '禅道',
    tone: '#6B8E4E',
    keyLabel: '产品ID',
    webhook: false,
    baseUrl: '',
    projectKey: '',
    tokenConfigured: false,
    enabled: false,
    status: 'idle',
    statusLabel: '未测试',
    loading: false,
    sending: false,
    result: '',
    reachable: false
  },
  {
    key: 'WECOM',
    name: '企业微信',
    tone: '#E89B3C',
    keyLabel: '机器人Key',
    webhook: true,
    baseUrl: '',
    projectKey: '',
    tokenConfigured: false,
    enabled: false,
    status: 'idle',
    statusLabel: '未测试',
    loading: false,
    sending: false,
    result: '',
    reachable: false
  },
  {
    key: 'DINGTALK',
    name: '钉钉',
    tone: '#C2694F',
    keyLabel: 'AccessToken',
    webhook: true,
    baseUrl: '',
    projectKey: '',
    tokenConfigured: false,
    enabled: false,
    status: 'idle',
    statusLabel: '未测试',
    loading: false,
    sending: false,
    result: '',
    reachable: false
  }
])

/** 拉取集成状态总览，标记各工具是否已启用及配置摘要 */
async function loadStatus() {
  try {
    const list = await integrationApi.getStatus()
    const map = Object.fromEntries((list || []).map(r => [r.toolType, r]))
    for (const tool of tools) {
      const s = map[tool.key]
      tool.enabled = !!s?.reachable
      tool.baseUrl = s?.baseUrl || ''
      tool.projectKey = s?.projectKey || ''
      tool.tokenConfigured = !!s?.tokenConfigured
      if (tool.enabled) {
        tool.status = 'idle'
        tool.statusLabel = '已启用'
      } else {
        tool.status = 'idle'
        tool.statusLabel = '未配置'
      }
    }
  } catch (e) {
    // 忽略，页面仍可手动测试连通
  }
}

async function testConnection(tool) {
  tool.loading = true
  tool.result = ''
  try {
    const res = await integrationApi.testConnection(tool.key)
    tool.reachable = !!res?.reachable
    tool.status = tool.reachable ? 'success' : 'fail'
    tool.statusLabel = tool.reachable ? '已连通' : '未连通'
    const version = res?.version ? `（版本 ${res.version}）` : ''
    tool.result = res?.message ? `${res.message}${version}` : (tool.reachable ? '连通成功' : '连通失败')
    tool.tokenConfigured = true
    ElMessage[tool.reachable ? 'success' : 'warning'](`${tool.name}：${tool.result}`)
  } catch (e) {
    tool.reachable = false
    tool.status = 'fail'
    tool.statusLabel = '未连通'
    tool.result = e?.response?.data?.message || e?.message || '连接失败，请检查配置'
    ElMessage.error(`${tool.name} 连通失败：${tool.result}`)
  } finally {
    tool.loading = false
  }
}

/** 发送一条 Webhook 测试通知（企业微信/钉钉机器人） */
async function sendTestNotify(tool) {
  tool.sending = true
  try {
    await integrationApi.notify(tool.key, {
      title: 'TraceGuard 测试通知',
      msgType: 'markdown',
      content: '这是一条由 TraceGuard 发送的**测试通知**。\n\n- 需求-代码一致性校验已完成\n- 检出严重缺陷 3 个',
      url: `${location.origin}`
    })
    ElMessage.success(`${tool.name}：测试通知发送成功`)
  } catch (e) {
    ElMessage.error(`${tool.name} 发送失败：${e?.response?.data?.message || e?.message || '请检查机器人配置'}`)
  } finally {
    tool.sending = false
  }
}

onChartThemeChange(() => { inboundDonutChart?.dispose(); inboundDonutChart = null; readyBarChart?.dispose(); readyBarChart = null; healthGaugeChart?.dispose(); healthGaugeChart = null; renderVizCharts() })

onMounted(async () => {
  await Promise.all([loadStatus(), loadInboundStatus()])
  nextTick(renderVizCharts)
  window.addEventListener('resize', onVizResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onVizResize)
  if (inboundDonutChart) inboundDonutChart.dispose()
  if (readyBarChart) readyBarChart.dispose()
  if (healthGaugeChart) healthGaugeChart.dispose()
})

/* ============ W4 v4：集成健康可视化 ============ */
const inboundDonutEl = ref(null)
const readyBarEl = ref(null)
const healthGaugeEl = ref(null)
let inboundDonutChart = null
let readyBarChart = null
let healthGaugeChart = null

const VIZ_TOOLTIP = {
  backgroundColor: 'rgba(43,36,28,.92)',
  borderColor: 'rgba(201,155,63,.4)',
  textStyle: { color: '#f4ead6' }
}

/** 入站同步结果：变更 / 失败 / 未变化 */
const inboundDonutData = computed(() => {
  const s = inboundStatusText.value
  if (!s || !Number(s.scanned)) return []
  const scanned = Number(s.scanned || 0)
  const changed = Number(s.changed || 0)
  const failed = Number(s.failed || 0)
  const same = Math.max(0, scanned - changed - failed)
  return [
    { name: '状态变更', value: changed, color: C.warning },
    { name: '同步失败', value: failed, color: C.danger },
    { name: '未变化', value: same, color: C.success }
  ]
})

/** 每个服务的就绪度（凭据 50 + 连通 50），配色取各服务品牌色 */
const toolReadiness = computed(() => tools.map((t) => ({
  name: t.name,
  tone: t.tone,
  value: (t.tokenConfigured ? 50 : 0) + (t.enabled ? 50 : 0),
  ready: t.tokenConfigured && t.enabled
})))

/** 连通健康分：服务平均就绪度 */
const healthScore = computed(() => {
  const list = toolReadiness.value
  if (!list.length) return 0
  return Math.round(list.reduce((n, t) => n + t.value, 0) / list.length)
})

const renderVizCharts = () => {
  // 1) 入站同步结果环形图
  if (inboundDonutEl.value) {
    if (!inboundDonutChart) inboundDonutChart = echarts.init(inboundDonutEl.value, chartThemeName())
    const items = inboundDonutData.value
    const total = items.reduce((n, it) => n + it.value, 0)
    inboundDonutChart.setOption({
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
        text: items.length ? String(total) : '—',
        subtext: '已扫描',
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
        data: items.length > 0
          ? items.map((it) => ({ name: it.name, value: it.value, itemStyle: { color: it.color } }))
          : [{ name: '暂无执行', value: 1, itemStyle: { color: 'rgba(180,170,148,.22)' } }]
      }]
    }, true)
  }
  // 2) 服务就绪度横向条
  if (readyBarEl.value) {
    if (!readyBarChart) readyBarChart = echarts.init(readyBarEl.value, chartThemeName())
    const list = toolReadiness.value
    readyBarChart.setOption({
      animationDuration: 600,
      tooltip: {
        trigger: 'axis',
        axisPointer: { type: 'shadow' },
        formatter: (ps) => {
          const d = list[ps[0].dataIndex]
          const parts = [d.ready ? '已配置并连通' : '未就绪']
          return `<b>${d.name}</b><br/>${parts.join(' · ')} · ${d.value} 分`
        },
        ...VIZ_TOOLTIP
      },
      grid: { left: 6, right: 32, top: 8, bottom: 6, containLabel: true },
      xAxis: {
        type: 'value',
        max: 100,
        splitLine: { lineStyle: { color: 'rgba(201,155,63,.1)' } },
        axisLabel: { color: 'var(--tg-text-secondary)', fontSize: 11 }
      },
      yAxis: {
        type: 'category',
        data: list.map((d) => d.name),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: 'var(--tg-text-primary)', fontSize: 12, fontWeight: 500 }
      },
      series: [{
        type: 'bar',
        data: list.map((d) => ({
          value: d.value,
          itemStyle: {
            borderRadius: [0, 8, 8, 0],
            color: d.ready ? new echarts.graphic.LinearGradient(0, 0, 1, 0, [
              { offset: 0, color: d.tone },
              { offset: 1, color: d.tone + '99' }
            ]) : 'rgba(180,170,148,.32)'
          }
        })),
        barWidth: 13,
        showBackground: true,
        backgroundStyle: { color: 'rgba(201,155,63,.08)', borderRadius: [0, 8, 8, 0] },
        label: {
          show: true,
          position: 'right',
          formatter: '{c}',
          color: 'var(--tg-text-secondary)',
          fontSize: 11
        }
      }]
    }, true)
  }
  // 3) 连通健康分仪表盘
  if (healthGaugeEl.value) {
    if (!healthGaugeChart) healthGaugeChart = echarts.init(healthGaugeEl.value, chartThemeName())
    const score = healthScore.value
    healthGaugeChart.setOption({
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
        data: [{ value: score, name: '平均就绪度' }]
      }]
    }, true)
  }
}

const onVizResize = () => {
  if (inboundDonutChart) inboundDonutChart.resize()
  if (readyBarChart) readyBarChart.resize()
  if (healthGaugeChart) healthGaugeChart.resize()
}

watch(tools, () => nextTick(renderVizCharts), { deep: true })

/** 概览统计：由 tools 与 inboundStatusText 派生 */
const reachableCount = computed(() => tools.filter((t) => t.enabled).length)
const scannedCount = computed(() => Number(inboundStatusText.value?.scanned || 0))
const overviewStats = computed(() => ({
  serviceCount: tools.length,
  credCount: tools.filter(t => t.tokenConfigured).length,
  todaySync: computeTodaySync()
}))

function computeTodaySync() {
  const ts = inboundStatusText.value?.timestamp
  if (!ts) return 0
  try {
    const d = new Date(ts)
    const now = new Date()
    const sameDay = d.getFullYear() === now.getFullYear()
      && d.getMonth() === now.getMonth()
      && d.getDate() === now.getDate()
    return sameDay ? 1 : 0
  } catch (e) {
    return 0
  }
}

function staggerDelay(i) {
  return { animationDelay: `${0.16 + i * 0.07}s` }
}

function dotClass(tool) {
  if (tool.status === 'success') return 'status-dot--ok'
  if (tool.status === 'fail') return 'status-dot--fail'
  if (tool.enabled) return 'status-dot--active'
  return 'status-dot--off'
}
</script>

<style scoped>
/* 页面容器 */
.integration-config-page {
  position: relative;
  max-width: 1200px;
  margin: 0 auto;
  padding: 0;
}

/* ===== 概览统计条（由全局 cfg-stats 承载，此处微调） ===== */
.cfg-stat {
  animation: tg-fade-up 0.6s var(--tg-ease) both;
}

/* ===== 出站服务卡片网格 ===== */
.tool-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(330px, 1fr));
  gap: 20px;
}

.tool-card {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 20px 20px 16px;
  border-radius: 18px;
  overflow: hidden;
}

/* 顶部渐变高光线 */
.tool-card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 2px;
  background: var(--tg-accent-gradient);
  opacity: 0;
  transition: opacity 0.4s ease;
}

.tool-card:hover::before {
  opacity: 1;
}

.tool-card:hover {
  border-color: rgba(201, 155, 63, 0.42);
  box-shadow: var(--tg-shadow-float), 0 0 0 1px rgba(201, 155, 63, 0.1);
}

.tool-card__top {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
}

.tool-card__id {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.tg-avatar--sm {
  width: 42px;
  height: 42px;
  border-radius: 13px;
  font-size: 17px;
  flex-shrink: 0;
}

.tool-card__name {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.tool-card__name strong {
  font-size: 16px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--tg-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.tool-card__sub {
  font-size: 12px;
  color: var(--tg-text-secondary);
}

/* 呼吸状态灯 */
.status-dot {
  position: relative;
  width: 12px;
  height: 12px;
  flex-shrink: 0;
  margin-top: 4px;
  border-radius: 50%;
}

.status-dot::after {
  content: '';
  position: absolute;
  inset: -5px;
  border-radius: 50%;
  border: 2px solid currentColor;
  opacity: 0.5;
  animation: status-pulse 2s cubic-bezier(0, 0, 0.2, 1) infinite;
}

.status-dot--ok { background: var(--tg-success); color: var(--tg-success); box-shadow: 0 0 0 3px rgba(107, 142, 78, 0.18); }
.status-dot--fail { background: var(--tg-danger); color: var(--tg-danger); box-shadow: 0 0 0 3px rgba(194, 94, 76, 0.18); }
.status-dot--active { background: var(--tg-amber); color: var(--tg-amber); box-shadow: 0 0 0 3px rgba(232, 155, 60, 0.2); }
.status-dot--off { background: var(--tg-slate); color: var(--tg-slate); box-shadow: 0 0 0 3px rgba(154, 139, 114, 0.16); animation: none; }

@keyframes status-pulse {
  0%, 100% { transform: scale(0.55); opacity: 0.7; }
  80%, 100% { transform: scale(1.7); opacity: 0; }
}

.status-dot--off::after { animation: none; }

/* 元信息三行 */
.tool-card__meta {
  display: flex;
  flex-direction: column;
  gap: 9px;
  padding: 12px 13px;
  border-radius: 13px;
  background: var(--tg-gradient-soft);
  border: 1px solid rgba(255, 255, 255, 0.7);
}

.meta-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 12.5px;
}

.meta-key {
  color: var(--tg-text-secondary);
  flex-shrink: 0;
}

.meta-val {
  color: var(--tg-text-primary);
  font-weight: 500;
  font-family: var(--tg-font-mono);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  text-align: right;
}

.meta-val.is-empty {
  color: var(--tg-slate);
  font-family: var(--tg-font-family);
  font-weight: 400;
}

/* 操作区 */
.tool-card__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: auto;
}

.tool-card__actions .el-button {
  margin-left: 0;
}

.notify-btn {
  --el-button-bg-color: rgba(154, 156, 107, 0.14);
  --el-button-border-color: rgba(154, 156, 107, 0.3);
  --el-button-hover-bg-color: rgba(154, 156, 107, 0.24);
  --el-button-hover-border-color: rgba(154, 156, 107, 0.4);
  --el-button-text-color: #55682e;
  --el-button-hover-text-color: #55682e;
}

/* 测试连通 → 结果徽标过渡 */
.test-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  font-weight: 600;
}

.test-badge--ok { color: var(--tg-success); }
.test-badge--fail { color: var(--tg-danger); }

.result-alert {
  margin-top: 0;
}

.result-enter-active,
.result-leave-active {
  transition: opacity 0.3s ease, transform 0.3s var(--tg-ease);
}
.result-enter-from,
.result-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

/* ===== 入站同步面板 ===== */
.inbound-head {
  align-items: center;
}

.inbound-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding: 14px 16px;
  border-radius: 14px;
  background: var(--tg-gradient-soft);
  border: 1px solid rgba(255, 255, 255, 0.7);
}

.inbound-bar__row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.inbound-bar__label {
  font-size: 13px;
  font-weight: 600;
  color: var(--tg-text-primary);
}

.inbound-bar__hint {
  font-size: 12.5px;
  color: var(--tg-text-secondary);
}

.inbound-bar__btns {
  display: flex;
  align-items: center;
  gap: 8px;
}

.inbound-bar__btns .el-button {
  margin-left: 0;
}

/* 最近执行 4 项指标小卡 */
.inbound-metrics {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 14px;
  margin-top: 16px;
}

.inbound-metric {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 15px 16px;
  border-radius: 15px;
  background: linear-gradient(160deg, rgba(255, 255, 255, 0.82), rgba(255, 246, 230, 0.5));
  border: 1px solid rgba(201, 155, 63, 0.16);
  box-shadow: var(--tg-shadow-card);
  transition: transform 0.3s var(--tg-ease-spring), box-shadow 0.3s ease;
}

.inbound-metric:hover {
  transform: translateY(-2px);
  box-shadow: 0 10px 24px rgba(60, 45, 25, 0.1);
}

.inbound-metric b {
  font-size: 24px;
  font-weight: 700;
  color: var(--tg-text-primary);
  font-variant-numeric: tabular-nums;
}

.inbound-metric span {
  font-size: 12px;
  color: var(--tg-text-secondary);
}

/* 最近执行页脚 */
.inbound-foot {
  display: flex;
  align-items: center;
  gap: 18px;
  flex-wrap: wrap;
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px dashed rgba(201, 155, 63, 0.2);
}

.inbound-foot__item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--tg-text-secondary);
}

/* 响应式适配 */
@media (max-width: 900px) {
  .inbound-metrics {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 720px) {
  .tool-grid {
    grid-template-columns: 1fr;
  }
  .inbound-metrics {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>