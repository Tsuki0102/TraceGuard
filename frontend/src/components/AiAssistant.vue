<template>
  <el-drawer
    v-model="visible"
    title="TraceGuard AI 助手"
    size="430px"
    direction="rtl"
    class="ai-drawer"
  >
    <div class="ai-chat">
      <div class="ai-chat__hint">
        基于已配置大模型，可解答需求解析、代码解析、缺陷检测、一致性验证、追溯矩阵与报告导出的相关问题。
      </div>

      <div ref="listRef" class="ai-chat__list">
        <div v-for="(m, i) in messages" :key="i" class="ai-msg" :class="'is-' + m.role">
          <span v-if="m.role === 'assistant'" class="ai-msg__avatar">
            <el-icon :size="14"><MagicStick /></el-icon>
          </span>
          <div class="ai-msg__bubble">{{ m.content }}</div>
        </div>
        <div v-if="loading" class="ai-msg is-assistant">
          <span class="ai-msg__avatar"><el-icon :size="14"><MagicStick /></el-icon></span>
          <div class="ai-msg__bubble ai-msg__typing">
            <i v-for="n in 3" :key="n"></i>
          </div>
        </div>
      </div>

      <div class="ai-chat__input">
        <el-input
          v-model="draft"
          placeholder="输入问题，Enter 发送"
          :disabled="loading"
          @keyup.enter="send"
        />
        <el-button type="primary" round :loading="loading" @click="send">发送</el-button>
      </div>
    </div>
  </el-drawer>
</template>

<script setup>
/**
 * W2-08 / R12：AI 助手抽屉
 * 复用已配置的大模型（POST /llm/chat，仅管理员入口），单轮问答，前端保留会话气泡。
 */
import { ref, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { MagicStick } from '@element-plus/icons-vue'
import { llmApi } from '@/api'

const visible = ref(false)
const draft = ref('')
const loading = ref(false)
const messages = ref([
  { role: 'assistant', content: '你好，我是 TraceGuard AI 助手。可以问我关于需求分析、缺陷检测、一致性验证或报告导出的问题。' }
])
const listRef = ref(null)

const open = () => {
  visible.value = true
}

const scrollBottom = () => {
  nextTick(() => {
    if (listRef.value) listRef.value.scrollTop = listRef.value.scrollHeight
  })
}

const send = async () => {
  const text = draft.value.trim()
  if (!text || loading.value) return
  messages.value.push({ role: 'user', content: text })
  draft.value = ''
  loading.value = true
  scrollBottom()
  try {
    const reply = await llmApi.chat(text)
    messages.value.push({ role: 'assistant', content: reply || '（未获得有效回答）' })
  } catch (e) {
    ElMessage.error(e.message || '对话失败')
    messages.value.push({ role: 'assistant', content: '抱歉，当前大模型调用失败：' + (e.message || '未知错误') })
  } finally {
    loading.value = false
    scrollBottom()
  }
}

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
  max-width: 88%;
}

.ai-msg.is-user {
  align-self: flex-end;
  flex-direction: row-reverse;
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

.ai-msg.is-user .ai-msg__bubble {
  background: var(--el-color-primary-light-9);
  border-color: rgba(143, 107, 34, 0.18);
}

.ai-msg__typing {
  display: inline-flex;
  gap: 4px;
  align-items: center;
}

.ai-msg__typing i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--tg-slate);
  animation: typing-bounce 1s ease-in-out infinite;
}

.ai-msg__typing i:nth-child(2) { animation-delay: 0.15s; }
.ai-msg__typing i:nth-child(3) { animation-delay: 0.3s; }

@keyframes typing-bounce {
  0%, 100% { transform: translateY(0); opacity: 0.4; }
  50% { transform: translateY(-3px); opacity: 1; }
}

.ai-chat__input {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
</style>