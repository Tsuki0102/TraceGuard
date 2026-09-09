<template>
  <el-drawer
    v-model="visible"
    title="TraceGuard 智能助手"
    size="460px"
    direction="rtl"
    class="ai-drawer"
  >
    <div class="ai-chat">
      <div class="ai-chat__hint">
        <template v-if="agentEnabled">
          我可以查询你的项目、需求、缺陷、一致性判定与趋势数据，用自然语言回答质量问题。例："看看哪些项目的严重缺陷还没处理"。
        </template>
        <template v-else>
          智能助手依赖大模型能力，当前未启用。请联系管理员在「系统配置 → 大模型配置」中开启。
        </template>
        <div class="ai-chat__acts">
          <button v-if="agentEnabled && isAdmin" type="button" class="ai-chat__act" :disabled="patrolling" @click="runPatrol">
            {{ patrolling ? '巡检中…' : '质量巡检' }}
          </button>
          <button v-if="agentEnabled" type="button" class="ai-chat__act" @click="resetSession">清空会话</button>
        </div>
      </div>

      <div ref="listRef" class="ai-chat__list">
        <div v-for="(m, i) in messages" :key="i" class="ai-msg" :class="'is-' + m.role">
          <span v-if="m.role !== 'user'" class="ai-msg__avatar">
            <el-icon :size="14"><MagicStick /></el-icon>
          </span>
          <!-- 状态条（思考中 / 工具调用进度） -->
          <div v-if="m.role === 'status'" class="ai-msg__status">
            <i class="ai-msg__spin"></i>
            <span>{{ m.content }}</span>
          </div>
          <!-- 待确认动作卡片（human-in-the-loop） -->
          <div v-else-if="m.role === 'confirm'" class="ai-msg__confirm">
            <div class="ai-confirm__title">
              <el-icon :size="13"><Warning /></el-icon>
              <span>待确认操作</span>
            </div>
            <div class="ai-confirm__desc">{{ m.content }}</div>
            <div class="ai-confirm__acts">
              <template v-if="!m.settled">
                <el-button size="small" type="primary" round @click="decide(m, true)">确认执行</el-button>
                <el-button size="small" round plain @click="decide(m, false)">取消</el-button>
              </template>
              <span v-else class="ai-confirm__done" :class="{ 'is-cancel': m.outcome === 'cancelled' }">
                {{ m.outcome === 'approved' ? '已确认' : '已取消' }}
              </span>
            </div>
          </div>
          <!-- 气泡（用户 / 助手 / 错误） -->
          <div v-else class="ai-msg__bubble" :class="{ 'is-error': m.role === 'error' }" v-html="m.html || renderMarkdown(m.content)"></div>
        </div>
      </div>

      <div class="ai-chat__input">
        <el-input
          v-model="draft"
          :placeholder="agentEnabled ? '输入问题，Enter 发送' : '大模型未启用，暂不可用'"
          :disabled="!agentEnabled"
          @keyup.enter="send"
        />
        <el-button v-if="!loading" type="primary" round :disabled="!agentEnabled" @click="send">发送</el-button>
        <el-button v-else type="warning" round plain @click="stop">停止</el-button>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
/**
 * 智能体助手抽屉（升级自 W2-08 单轮 AI 助手）
 * 后端 /agent/chat/stream（SSE）：status（思考/工具进度）→ delta*（最终回答分片）→ done/error；
 * confirm 事件触发待确认动作卡片（human-in-the-loop），经 /agent/confirm 提交决策后执行。
 * 工具含只读查询与动作类（重跑分析/缺陷状态流转）；数据权限跟随登录用户；多轮上下文由服务端会话维护。
 */
import { ref, nextTick, onBeforeUnmount } from 'vue'
import { MagicStick, Warning } from '@element-plus/icons-vue'
import { agentApi } from '@/api'

const visible = ref(false)
const draft = ref('')
const loading = ref(false)
const agentEnabled = ref(true)
const isAdmin = ref(false)
const patrolling = ref(false)
const messages = ref([
  { role: 'assistant', content: '你好，我是 TraceGuard 智能助手。可以直接提问，例如：\n- 我有哪些项目？\n- 哪些项目的严重缺陷还没处理？\n- 最近 30 天缺陷趋势怎么样？' }
])
const listRef = ref(null)
let abortRef = null
let sessionId = localStorage.getItem('agent_session_id') || genId()

const TOOL_LABELS = {
  list_projects: '项目清单',
  get_overview: '全局概览',
  get_trend: '缺陷趋势',
  query_defects: '缺陷数据',
  query_requirements: '需求数据',
  query_consistency: '一致性结果',
  get_patterns: '缺陷模式',
  search: '全局搜索',
  explain_defect: '缺陷深度解释',
  list_tasks: '分析任务',
  rerun_analysis: '重新分析',
  update_defect_status: '缺陷状态变更'
}

function genId() {
  const id = 'ag-' + Math.random().toString(36).slice(2) + Date.now().toString(36)
  localStorage.setItem('agent_session_id', id)
  return id
}

const open = async () => {
  visible.value = true
  scrollBottom()
  try {
    const userInfo = JSON.parse(localStorage.getItem('userInfo') || '{}')
    isAdmin.value = userInfo.role === 'admin'
  } catch (e) {
    isAdmin.value = false
  }
  try {
    const st = await agentApi.status()
    agentEnabled.value = !!st.enabled
  } catch (e) {
    agentEnabled.value = false
  }
}

const scrollBottom = () => {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

/** 发送一条用户消息并消费 SSE 流 */
const send = async () => {
  const text = draft.value.trim()
  if (!text || loading.value || !agentEnabled.value) return
  messages.value.push({ role: 'user', content: text })
  draft.value = ''
  loading.value = true

  const statusMsg = reactiveStatus()
  let answer = null
  abortRef = new AbortController()
  scrollBottom()
  try {
    const res = await agentApi.stream(sessionId, text)
    if (!res.ok || !res.body) {
      throw new Error(res.status === 401 ? '登录已过期，请重新登录' : ('请求失败（HTTP ' + res.status + '）'))
    }
    const reader = res.body.getReader()
    const decoder = new TextDecoder('utf-8')
    let buf = ''
    let curEvent = 'message'
    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buf += decoder.decode(value, { stream: true })
      let sep
      while ((sep = buf.indexOf('\n\n')) >= 0) {
        const raw = buf.slice(0, sep)
        buf = buf.slice(sep + 2)
        const parsed = parseSseBlock(raw)
        if (!parsed) continue
        curEvent = parsed.event || curEvent
        const data = parsed.data
        if (curEvent === 'status') {
          statusMsg.content = statusText(data)
          scrollBottom()
        } else if (curEvent === 'confirm') {
          // 动作类工具：弹出待确认卡片，等待用户决策（流随后结束）
          removeStatus()
          messages.value.push({ role: 'confirm', token: data.token, content: data.summary || data.tool, settled: false })
          scrollBottom()
        } else if (curEvent === 'delta') {
          if (answer === null) {
            answer = { role: 'assistant', content: '' }
            messages.value.push(answer)
            removeStatus()
          }
          answer.content += data.text || ''
          scrollBottom()
        } else if (curEvent === 'error') {
          removeStatus()
          messages.value.push({ role: 'error', content: data.message || '智能体执行失败' })
          scrollBottom()
        }
      }
    }
    if (answer === null) removeStatus()
  } catch (e) {
    removeStatus()
    if (e.name === 'AbortError') {
      if (answer !== null) answer.content += '\n\n（已手动停止）'
      else messages.value.push({ role: 'error', content: '已停止本次请求' })
    } else {
      messages.value.push({ role: 'error', content: '对话失败：' + (e.message || '网络错误') })
    }
    scrollBottom()
  } finally {
    loading.value = false
    abortRef = null
  }
}

/** 解析一个 SSE 块（event + data 行），返回 {event, data} 或 null */
function parseSseBlock(raw) {
  let event = 'message'
  let data = null
  for (const line of raw.split('\n')) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    else if (line.startsWith('data:')) {
      const payload = line.slice(5).trim()
      try { data = JSON.parse(payload) } catch (e) { data = { text: payload } }
    }
  }
  return data === null ? null : { event, data }
}

function statusText(d) {
  if (!d) return '思考中…'
  if (d.phase === 'tool') {
    const label = TOOL_LABELS[d.tool] || d.tool || '数据'
    return '正在查询' + label + '…（第 ' + (d.step || '?') + ' 步）'
  }
  return d.note || '思考中…'
}

function reactiveStatus() {
  const m = { role: 'status', content: '思考中…' }
  messages.value.push(m)
  return m
}

function removeStatus() {
  const idx = messages.value.findIndex(m => m.role === 'status')
  if (idx >= 0) messages.value.splice(idx, 1)
}

/** 手动停止当前流 */
const stop = () => {
  if (abortRef) abortRef.abort()
}

/** 确认卡片决策：approve=true 执行动作，false 取消 */
const decide = async (m, approve) => {
  if (m.settled) return
  m.settled = true
  m.outcome = approve ? 'approved' : 'cancelled'
  scrollBottom()
  try {
    const res = await agentApi.confirm(sessionId, m.token, approve)
    messages.value.push({
      role: res.success ? 'assistant' : 'error',
      content: res.message || (approve ? '执行完成' : '已取消')
    })
  } catch (e) {
    messages.value.push({ role: 'error', content: '确认请求失败：' + (e.message || '网络错误') })
  }
  scrollBottom()
}

/** 质量巡检（仅管理员）：无头 Agent 循环产出巡检报告，以助手气泡展示 */
const runPatrol = async () => {
  if (patrolling.value || loading.value) return
  patrolling.value = true
  messages.value.push({ role: 'user', content: '执行一次质量巡检' })
  const statusMsg = reactiveStatus()
  statusMsg.content = '巡检中：正在检查项目、缺陷与趋势数据…（可能需要 1 分钟）'
  scrollBottom()
  try {
    const res = await agentApi.patrolRun()
    removeStatus()
    messages.value.push(res.success
      ? { role: 'assistant', content: res.report }
      : { role: 'error', content: res.message || '巡检失败' })
  } catch (e) {
    removeStatus()
    messages.value.push({ role: 'error', content: '巡检请求失败：' + (e.message || '网络错误') })
  }
  patrolling.value = false
  scrollBottom()
}

/** 清空会话（服务端历史 + 前端气泡） */
const resetSession = async () => {
  try { await agentApi.reset(sessionId) } catch (e) { /* 忽略：服务端不可用时仅清前端 */ }
  sessionId = genId()
  messages.value = [
    { role: 'assistant', content: '会话已清空。可以重新提问，例如："对比我所有项目的一致性得分"。' }
  ]
}

/** 轻量 Markdown 渲染（代码块/行内代码/加粗/标题/列表/换行；先转义 HTML 防注入） */
function renderMarkdown(src) {
  if (!src) return ''
  const esc = s => s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const lines = esc(src).split('\n')
  const out = []
  let inCode = false
  let codeBuf = []
  let listBuf = []
  const flushList = () => {
    if (listBuf.length) {
      out.push('<ul class="md-ul">' + listBuf.map(t => '<li>' + t + '</li>').join('') + '</ul>')
      listBuf = []
    }
  }
  for (const line of lines) {
    if (line.trim().startsWith('```')) {
      if (inCode) {
        out.push('<pre class="md-code"><code>' + codeBuf.join('\n') + '</code></pre>')
        codeBuf = []
        inCode = false
      } else {
        flushList()
        inCode = true
      }
      continue
    }
    if (inCode) { codeBuf.push(line); continue }
    const t = line.trim()
    if (/^[-*] /.test(t)) { listBuf.push(inline(t.slice(2))); continue }
    flushList()
    if (/^#{1,4} /.test(t)) out.push('<h4 class="md-h">' + inline(t.replace(/^#{1,4} /, '')) + '</h4>')
    else if (t === '') out.push('')
    else out.push('<p class="md-p">' + inline(t) + '</p>')
  }
  if (inCode && codeBuf.length) out.push('<pre class="md-code"><code>' + codeBuf.join('\n') + '</code></pre>')
  flushList()
  return out.join('')
  function inline(s) {
    return s
      .replace(/`([^`]+)`/g, '<code class="md-inline">$1</code>')
      .replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>')
  }
}

onBeforeUnmount(() => {
  if (abortRef) abortRef.abort()
})

defineExpose({ open })
</script>

<style scoped>
.ai-chat {
  display: flex;
  flex-direction: column;
  height: 100%;
  gap: 12px;
}

.ai-chat__hint {
  padding: 10px 14px;
  border-radius: 12px;
  background: var(--tg-gradient-soft);
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--tg-text-secondary);
  flex-shrink: 0;
}

.ai-chat__acts {
  /* 正常文档流：文字在上、按钮行在下，绝不悬浮遮盖提示文字 */
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 8px;
}

.ai-chat__act {
  border: none;
  background: none;
  color: var(--tg-text-tertiary, #9a8f7a);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}

.ai-chat__act:hover:not(:disabled) { color: var(--tg-accent, #8f6b22); }

.ai-chat__act:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.ai-chat__list {
  flex: 1;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 4px 2px;
}

.ai-msg {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  max-width: 92%;
}

.ai-msg.is-user {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.ai-msg.is-status,
.ai-msg.is-error,
.ai-msg.is-confirm {
  max-width: 100%;
}

.ai-msg__avatar {
  width: 28px;
  height: 28px;
  border-radius: 9px;
  background: var(--tg-accent-gradient);
  color: #fff;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  box-shadow: var(--tg-glow-accent);
}

.ai-msg__status {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 12px;
  border-radius: 999px;
  background: rgba(143, 107, 34, 0.08);
  border: 1px dashed rgba(143, 107, 34, 0.3);
  color: var(--tg-text-secondary);
  font-size: 12.5px;
}

.ai-msg__spin {
  width: 11px;
  height: 11px;
  border: 2px solid rgba(143, 107, 34, 0.35);
  border-top-color: var(--tg-accent, #8f6b22);
  border-radius: 50%;
  animation: agent-spin 0.8s linear infinite;
  flex-shrink: 0;
}

@keyframes agent-spin {
  to { transform: rotate(360deg); }
}

/* 待确认动作卡片（human-in-the-loop） */
.ai-msg__confirm {
  padding: 10px 14px;
  border-radius: 14px;
  background: rgba(217, 119, 6, 0.07);
  border: 1px solid rgba(217, 119, 6, 0.4);
  font-size: 13px;
  line-height: 1.6;
}

.ai-confirm__title {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-weight: 700;
  color: #b45309;
  margin-bottom: 4px;
}

.ai-confirm__desc {
  color: var(--tg-text-primary);
  white-space: pre-wrap;
  word-break: break-word;
}

.ai-confirm__acts {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.ai-confirm__done {
  font-size: 12px;
  color: #16a34a;
  font-weight: 600;
}

.ai-confirm__done.is-cancel {
  color: var(--tg-text-tertiary, #9a8f7a);
}

.ai-msg__bubble {
  padding: 10px 14px;
  border-radius: 14px;
  font-size: 13.5px;
  line-height: 1.7;
  color: var(--tg-text-primary);
  white-space: pre-wrap;
  word-break: break-word;
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid var(--tg-border);
}

.ai-msg__bubble.is-error {
  background: rgba(220, 38, 38, 0.06);
  border-color: rgba(220, 38, 38, 0.35);
  color: #b91c1c;
}

.ai-msg.is-user .ai-msg__bubble {
  background: var(--el-color-primary-light-9);
  border-color: rgba(143, 107, 34, 0.18);
}

/* 轻量 Markdown 元素 */
.ai-msg__bubble :deep(.md-p) { margin: 0 0 4px; }
.ai-msg__bubble :deep(.md-p:last-child) { margin-bottom: 0; }
.ai-msg__bubble :deep(.md-h) { margin: 6px 0 4px; font-size: 13.5px; font-weight: 700; }
.ai-msg__bubble :deep(.md-ul) { margin: 2px 0 4px; padding-left: 18px; }
.ai-msg__bubble :deep(.md-ul li) { margin: 2px 0; }
.ai-msg__bubble :deep(.md-code) {
  margin: 6px 0;
  padding: 8px 10px;
  border-radius: 8px;
  background: #0f172a;
  color: #e2e8f0;
  font-size: 12px;
  overflow-x: auto;
  white-space: pre;
}
.ai-msg__bubble :deep(.md-inline) {
  padding: 1px 5px;
  border-radius: 5px;
  background: rgba(15, 23, 42, 0.08);
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.ai-chat__input {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
</style>
