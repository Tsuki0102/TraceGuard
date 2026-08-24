<template>
  <div class="llm-config-page">
    <el-page-header content="大模型配置" style="margin-bottom: 20px" />

    <el-alert type="info" :closable="false" style="margin-bottom: 20px">
      配置大模型服务（GAP-021 多 provider 编排）。保存后即时生效，连通测试仅验证网络与密钥可达性，不触发分析任务。
    </el-alert>

    <el-card v-loading="loading" style="margin-bottom: 20px">
      <template #header>
        <div class="card-header">
          <span>基础设置</span>
          <el-tag size="small" :type="cfg.source === 'db' ? 'warning' : 'info'" v-if="cfg.source">
            {{ cfg.source === 'db' ? '数据库配置' : '默认配置(yml)' }}
          </el-tag>
        </div>
      </template>
      <el-form label-width="140px" label-position="left">
        <el-form-item label="启用大模型">
          <el-switch v-model="cfg.enabled" active-text="启用" inactive-text="关闭" />
          <span class="form-tip">关闭后主流程回退规则模式（词典/结构校验兜底）</span>
        </el-form-item>
        <el-form-item label="并发上限" v-if="cfg.maxConcurrentCalls != null">
          <span>{{ cfg.maxConcurrentCalls }}</span>
          <span class="form-tip">单任务最大并发调用数（配置于 application.yml）</span>
        </el-form-item>
        <el-form-item label="单环节调用上限" v-if="cfg.maxCallsPerStage != null">
          <span>{{ cfg.maxCallsPerStage }}</span>
          <span class="form-tip">单个分析任务单环节最大调用次数</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="handleSave">
            <el-icon><Check /></el-icon> 保存配置
          </el-button>
          <el-button @click="loadData">刷新</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card v-loading="loading" style="margin-bottom: 20px">
      <template #header>
        <div class="card-header">
          <span>服务商（Provider）配置</span>
          <el-button size="small" type="primary" plain @click="addProvider">
            <el-icon><Plus /></el-icon> 新增服务商
          </el-button>
        </div>
      </template>
      <el-form label-width="140px" label-position="left">
        <div v-for="(p, idx) in providerForm" :key="p.provider || 'idx-' + idx" class="provider-row">
          <div class="provider-header">
            <span class="provider-label">服务商标识</span>
            <el-input
              v-model="p.provider"
              size="small"
              class="provider-key-input"
              placeholder="如 openai / kimi / qwen / ollama"
              :disabled="!p.isNew"
            />
            <el-button size="small" type="danger" plain :disabled="providerForm.length <= 1" @click="removeProvider(idx)">
              <el-icon><Delete /></el-icon> 删除
            </el-button>
          </div>
          <el-form-item label="Base URL">
            <el-input v-model="p.baseUrl" placeholder="https://api.example.com" style="max-width: 520px" />
          </el-form-item>
          <el-form-item label="API Key">
            <el-input
              v-model="p.apiKey"
              type="password"
              show-password
              :placeholder="p.apiKeyConfigured ? '已配置（留空保持不变）' : '未配置（必填方可启用）'"
              style="max-width: 520px"
            />
          </el-form-item>
          <el-form-item label="连通测试">
            <el-button :loading="testingKey === p.provider" size="small" @click="handleTest(p.provider)">
              <el-icon><Connection /></el-icon> 测试 {{ providerName(p.provider) }}
            </el-button>
            <span v-if="testResult && testResult.provider === p.provider" class="test-reply" :class="testResult.success ? 'ok' : 'err'">
              {{ testResult.reply }}
            </span>
          </el-form-item>
          <el-divider v-if="idx < providerForm.length - 1" />
        </div>
        <el-empty v-if="!providerForm.length" description="暂无服务商，点击「新增服务商」添加" :image-size="60" />
      </el-form>
    </el-card>

    <el-card v-loading="loading">
      <template #header>
        <span>按环节路由与模型</span>
      </template>
      <el-form label-width="140px" label-position="left">
        <div v-for="stage in stageList" :key="stage.key" class="stage-row">
          <el-form-item :label="stage.label">
            <el-select v-model="routingForm[stage.key]" style="width: 160px" :placeholder="stage.label">
              <el-option v-for="p in availableProviders" :key="p" :label="providerName(p)" :value="p" />
            </el-select>
            <el-select
              v-model="modelsForm[stage.key]"
              filterable
              allow-create
              default-first-option
              placeholder="选择或输入模型名"
              style="width: 260px; margin-left: 12px"
            >
              <el-option
                v-for="m in suggestedModels(routingForm[stage.key])"
                :key="m"
                :label="m"
                :value="m"
              />
            </el-select>
          </el-form-item>
        </div>
      </el-form>
      <div class="save-bar">
        <el-button type="primary" :loading="saving" @click="handleSave">
          <el-icon><Check /></el-icon> 保存配置
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
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

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.form-tip {
  margin-left: 12px;
  font-size: 12px;
  color: #909399;
}

.provider-row .el-divider {
  color: #409EFF;
  font-weight: bold;
}

.provider-header {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px dashed #e4e7ed;
}

.provider-label {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  width: 60px;
  flex-shrink: 0;
}

.provider-key-input {
  max-width: 260px;
}

.test-reply {
  margin-left: 12px;
  font-size: 12px;
}
.test-reply.ok {
  color: #67C23A;
}
.test-reply.err {
  color: #F56C6C;
}

.stage-row {
  margin-bottom: 8px;
}

.save-bar {
  text-align: right;
  margin-top: 8px;
}
</style>
