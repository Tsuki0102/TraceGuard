<template>
  <div class="notif">
    <el-popover
      placement="bottom-end"
      :width="360"
      trigger="click"
      popper-class="notif-pop"
      @show="markAllRead"
    >
      <template #reference>
        <button type="button" class="notif__btn" aria-label="通知中心">
          <el-icon :size="16"><Bell /></el-icon>
          <span v-if="unread > 0" class="notif__badge">{{ unread > 9 ? '9+' : unread }}</span>
        </button>
      </template>

      <div class="notif__panel">
        <div class="notif__head">
          <b>通知中心</b>
          <span class="notif__sub">待办 &amp; 分析任务动态</span>
        </div>

        <div v-if="todos.length" class="notif__group">
          <div class="notif__group-title">待办事项</div>
          <button
            v-for="t in todos.slice(0, 5)"
            :key="'t' + t.projectId + t.action"
            type="button"
            class="notif__item"
            @click="goTodo(t)"
          >
            <span class="notif__dot" :class="'is-' + t.level"></span>
            <span class="notif__title">{{ t.projectName }}</span>
            <span class="notif__meta">{{ t.action }}</span>
          </button>
        </div>

        <div class="notif__group">
          <div class="notif__group-title">
            分析任务
            <span v-if="wsOnline" class="notif__ws"><span class="tg-live-dot"></span>实时</span>
          </div>
          <template v-if="taskEvents.length">
            <button
              v-for="(e, i) in taskEvents.slice(0, 8)"
              :key="'e' + i"
              type="button"
              class="notif__item"
              @click="goProject(e)"
            >
              <span class="notif__icon" :class="'is-' + taskTone(e.status)">
                <el-icon :size="13"><component :is="taskIcon(e.status)" /></el-icon>
              </span>
              <span class="notif__body">
                <span class="notif__title">{{ e.projectName }}</span>
                <span class="notif__meta">{{ taskLabel(e.status) }} · {{ e.taskName }}</span>
              </span>
              <span v-if="e.isNew" class="notif__new">新</span>
            </button>
            <div v-if="!wsOnline" class="notif__hint">未连接实时推送，展示最近记录</div>
          </template>
          <div v-else class="notif__empty">暂无任务动态</div>
        </div>
      </div>
    </el-popover>
  </div>
</template>

<script setup>
/**
 * W2-04 / R4：通知铃铛
 * 数据：dashboard/notifications（待办 + 最近任务）+ 全局 WebSocket 实时推送（完成/失败 → 未读角标）。
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { Bell } from '@element-plus/icons-vue'
import { ElNotification } from 'element-plus'
import { dashboardApi, wsApi } from '@/api'

const router = useRouter()

const todos = ref([])
const wsEvents = ref([])
const unread = ref(0)
const wsOnline = ref(false)
let ws = null

const taskEvents = computed(() => {
  const base = (window.__tgTaskEvents || []).slice(0, 8)
  // wsEvents 为最新实时事件，置于顶部；重复任务（同 taskId+status）去重
  const keys = new Set(wsEvents.value.map((e) => e.taskId + ':' + e.status))
  return [...wsEvents.value.slice(0, 8), ...base.filter((e) => !keys.has(e.taskId + ':' + e.status))]
})

const taskLabel = (s) => {
  const map = { completed: '分析完成', failed: '分析失败', finished: '分析完成', running: '分析中', pending: '任务创建', paused: '已暂停' }
  return map[s] || s
}

const taskTone = (s) => {
  if (s === 'completed' || s === 'finished') return 'ok'
  if (s === 'failed') return 'bad'
  return 'warn'
}

const taskIcon = (s) => {
  if (s === 'completed' || s === 'finished') return 'CircleCheckFilled'
  if (s === 'failed') return 'CircleCloseFilled'
  return 'Clock'
}

const goTodo = (t) => router.push(`/project/${t.projectId}`)
const goProject = (e) => router.push(`/project/${e.projectId}`)

const markAllRead = () => {
  unread.value = 0
}

// 加载待办 + 最近任务（作为离线兜底展示）
const loadNotifications = async () => {
  try {
    const data = await dashboardApi.notifications()
    todos.value = (data && data.todos) || []
    window.__tgTaskEvents = (data && data.tasks) || []
  } catch (e) {
    console.warn('加载通知失败', e)
  }
}

// 全局 WS：监听分析任务完成/失败，实时叠加未读
const connectWs = async () => {
  try {
    // 后端返回 {ticket}，取字符串避免拼接成 [object Object]
    const ticket = await wsApi.getTicket()
    const tk = (ticket && ticket.ticket) || ticket
    if (!tk) return
    const proto = location.protocol === 'https:' ? 'wss' : 'ws'
    ws = new WebSocket(`${proto}://${location.host}/api/ws/progress?ticket=${tk}`)
    ws.onopen = () => { wsOnline.value = true }
    ws.onclose = () => { wsOnline.value = false }
    ws.onerror = () => { wsOnline.value = false }
    ws.onmessage = (ev) => {
      if (!ev.data) return
      try {
        const msg = JSON.parse(ev.data)
        if (msg.type === 'progress' && (msg.status === 'finished' || msg.status === 'completed' || msg.status === 'failed')) {
          // 个性化增强 BATCH-3：任务终态实时 Toast（右上角，失败常驻更久）
          const ok = msg.status !== 'failed'
          ElNotification({
            title: ok ? '分析完成' : '分析失败',
            message: `分析任务 #${msg.taskId} ${ok ? '已完成，可在通知中心与报告中心查看结果' : '执行失败，请到通知中心查看详情'}`,
            type: ok ? 'success' : 'error',
            duration: ok ? 6000 : 10000,
            offset: 64,
            position: 'top-right'
          })
          // 归属自己的任务才提示（消息仅推给归属者/管理员，此处记录即可）
          wsEvents.value.push({
            taskId: msg.taskId,
            projectId: null, // 实时消息不携带项目展示名，用后端轮询数据补充上下文（此处仅角标 + 描述）
            status: msg.status,
            taskName: '分析任务 #' + msg.taskId,
            isNew: true
          })
          unread.value += 1
        }
      } catch (e) {
        /* ignore */
      }
    }
  } catch (e) {
    wsOnline.value = false
  }
}

onMounted(() => {
  loadNotifications()
  connectWs()
  // 轮询兜底：每 60s 拉一次待办与最近任务
  window.__tgNotifTimer = setInterval(loadNotifications, 60000)
})

onUnmounted(() => {
  if (ws) ws.close()
  if (window.__tgNotifTimer) clearInterval(window.__tgNotifTimer)
})
</script>

<style scoped>
.notif__btn {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  margin-right: 6px;
  border: 1px solid var(--tg-border);
  border-radius: 50%;
  background: rgba(255, 255, 255, 0.6);
  color: var(--tg-text-secondary);
  cursor: pointer;
  transition: color 0.2s ease, border-color 0.2s ease, box-shadow 0.25s ease, transform 0.25s var(--tg-ease);
}

.notif__btn:hover {
  color: var(--tg-accent);
  border-color: rgba(143, 107, 34, 0.35);
  box-shadow: var(--tg-shadow-card);
}

.notif__btn:focus-visible {
  outline: 2px solid var(--tg-accent);
  outline-offset: 2px;
}

.notif__badge {
  position: absolute;
  top: -3px;
  right: -3px;
  min-width: 17px;
  height: 17px;
  padding: 0 4px;
  border-radius: 999px;
  background: var(--tg-danger);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  line-height: 17px;
  text-align: center;
  box-shadow: 0 0 0 2px var(--tg-bg-page);
}

.notif__panel {
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 440px;
  overflow-y: auto;
}

.notif__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 2px 4px 8px;
  border-bottom: 1px solid var(--tg-border);
}

.notif__head b {
  font-size: 14px;
  color: var(--tg-text-primary);
}

.notif__sub {
  font-size: 12px;
  color: var(--tg-slate);
}

.notif__group {
  margin-top: 6px;
}

.notif__group-title {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 6px 4px 4px;
  font-size: 11.5px;
  font-weight: 600;
  letter-spacing: 0.06em;
  color: var(--tg-slate);
}

.notif__ws {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  margin-left: auto;
  color: var(--tg-success);
  font-weight: 500;
  letter-spacing: 0;
}

.notif__item {
  display: flex;
  align-items: center;
  gap: 9px;
  width: 100%;
  padding: 8px 8px;
  border: none;
  border-radius: 10px;
  background: transparent;
  text-align: left;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.2s ease;
}

.notif__item:hover {
  background: var(--el-color-primary-light-9);
}

.notif__dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
}

.notif__dot.is-created { background: var(--tg-indigo); }
.notif__dot.is-failed { background: var(--tg-danger); }

.notif__icon {
  width: 26px;
  height: 26px;
  border-radius: 8px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
}

.notif__icon.is-ok { background: rgba(154, 156, 107, 0.16); color: var(--tg-success); }
.notif__icon.is-bad { background: rgba(194, 94, 76, 0.12); color: var(--tg-danger); }
.notif__icon.is-warn { background: rgba(232, 155, 60, 0.14); color: var(--tg-warning); }

.notif__body {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.notif__title {
  font-size: 13px;
  font-weight: 500;
  color: var(--tg-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.notif__meta {
  font-size: 12px;
  color: var(--tg-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.notif__new {
  padding: 1px 6px;
  border-radius: 999px;
  background: rgba(194, 94, 76, 0.12);
  color: var(--tg-danger);
  font-size: 11px;
  flex-shrink: 0;
}

.notif__empty {
  padding: 14px 8px;
  text-align: center;
  font-size: 12.5px;
  color: var(--tg-text-secondary);
}

.notif__hint {
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--tg-slate);
  text-align: center;
}
</style>