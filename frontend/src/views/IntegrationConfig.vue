<template>
  <div class="integration-config">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span>集成连通测试（Jira / 禅道 / 企业微信 / 钉钉）</span>
          <el-tag size="small" type="info">管理员</el-tag>
        </div>
      </template>
      <el-alert
        type="info"
        :closable="false"
        show-icon
        title="配置说明"
        description="后端按环境变量读取集成配置：Jira（JIRA_BASE_URL/JIRA_USER/JIRA_TOKEN/JIRA_PROJECT_KEY）、禅道（ZENTAO_BASE_URL/ZENTAO_TOKEN/ZENTAO_PRODUCT_ID）、企业微信（WECOM_BASE_URL/WECOM_KEY/WECOM_SECRET）、钉钉（DINGTALK_BASE_URL/DINGTALK_TOKEN/DINGTALK_SECRET）。未启用时点击「测试连通」将返回明确失败原因，属预期展示。企微/钉钉支持发送 Webhook 通知（测试连通即发送一条测试消息）。"
      />
      <div class="tool-grid">
        <el-card v-for="tool in tools" :key="tool.key" shadow="hover" class="tool-card">
          <template #header>
            <div class="tool-header">
              <span>{{ tool.name }}</span>
              <el-tag :type="tool.status === 'success' ? 'success' : tool.status === 'fail' ? 'danger' : tool.enabled ? 'warning' : 'info'" size="small">
                {{ tool.statusLabel }}
              </el-tag>
            </div>
          </template>
          <div class="tool-body">
            <el-descriptions :column="1" border size="small">
              <el-descriptions-item label="服务地址">{{ tool.baseUrl || '未配置' }}</el-descriptions-item>
              <el-descriptions-item :label="tool.keyLabel">{{ tool.projectKey || '未配置' }}</el-descriptions-item>
              <el-descriptions-item label="凭据">
                <el-tag v-if="tool.tokenConfigured" type="success" size="small">已配置</el-tag>
                <el-tag v-else type="warning" size="small">未配置</el-tag>
              </el-descriptions-item>
            </el-descriptions>
            <div class="btn-row">
              <el-button
                type="primary"
                :loading="tool.loading"
                class="test-btn"
                @click="testConnection(tool)"
              >
                <el-icon style="margin-right: 4px"><Connection /></el-icon>测试连通
              </el-button>
              <el-button
                v-if="tool.webhook"
                type="success"
                plain
                :loading="tool.sending"
                class="test-btn"
                @click="sendTestNotify(tool)"
              >
                <el-icon style="margin-right: 4px"><Bell /></el-icon>发送测试通知
              </el-button>
            </div>
            <el-alert
              v-if="tool.result"
              :type="tool.reachable ? 'success' : 'error'"
              :closable="false"
              show-icon
              class="result-alert"
              :title="tool.reachable ? '连通成功' : '连通失败'"
              :description="tool.result"
            />
          </div>
        </el-card>
      </div>

      <el-divider content-position="left">入站同步（远程 → 本地）</el-divider>
      <el-card shadow="never" class="inbound-card">
        <template #header>
          <div class="tool-header">
            <span>缺陷状态入站同步</span>
            <el-tag size="small" :type="inboundEnabled ? 'success' : 'info'">
              {{ inboundEnabled ? '定时轮询中（每 ' + inboundIntervalLabel + '）' : '未启用' }}
            </el-tag>
          </div>
        </template>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          class="result-alert"
          title="入站同步说明"
          description="后端定时（默认每 5 分钟）拉取已推送缺陷在 Jira/禅道的状态变化并回写本地缺陷状态，无需公网地址。点击「立即同步」可手动触发一次。"
        />
        <div class="btn-row" style="margin-top: 12px">
          <el-button type="primary" :loading="inboundLoading" @click="triggerInboundSync">
            <el-icon style="margin-right: 4px"><Refresh /></el-icon>立即同步
          </el-button>
          <el-button @click="loadInboundStatus">查看同步状态</el-button>
        </div>
        <el-descriptions v-if="inboundStatusText" :column="2" border size="small" class="result-alert">
          <el-descriptions-item label="最近执行">{{ inboundStatusText.timestamp || '-' }}</el-descriptions-item>
          <el-descriptions-item label="已扫描">{{ inboundStatusText.scanned ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="状态变更">{{ inboundStatusText.changed ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="失败">{{ inboundStatusText.failed ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="耗时(ms)">{{ inboundStatusText.costMs ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="远程缺陷总数">{{ inboundStatusText.total ?? '-' }}</el-descriptions-item>
        </el-descriptions>
      </el-card>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { ElMessage } from 'element-plus'
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

import { onMounted } from 'vue'
onMounted(() => {
  loadStatus()
  loadInboundStatus()
})
</script>

<style scoped>
.integration-config {
  padding: 8px;
}
.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.tool-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(360px, 1fr));
  gap: 16px;
  margin-top: 16px;
}
.tool-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.tool-body {
  display: flex;
  flex-direction: column;
}
.test-btn {
  margin-top: 12px;
}
.result-alert {
  margin-top: 12px;
}
</style>
