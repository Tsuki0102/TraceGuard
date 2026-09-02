<template>
  <Teleport to="body">
    <transition name="cmd-fade">
      <div v-if="visible" class="cmd-mask" @click.self="close" @keydown.esc="close">
        <div class="cmd" role="dialog" aria-modal="true" aria-label="导航搜索">
          <div class="cmd__input-row">
            <el-icon :size="18" class="cmd__search-icon"><Search /></el-icon>
            <input
              ref="inputRef"
              v-model="keyword"
              class="cmd__input"
              type="text"
              placeholder="搜索页面或已打开标签…"
              :aria-label="'搜索页面或已打开标签'"
              @keydown.down.prevent="move(1)"
              @keydown.up.prevent="move(-1)"
              @keydown.enter.prevent="enter()"
            />
            <kbd class="cmd__kbd">ESC</kbd>
          </div>
          <div v-if="results.length || dataResults.total > 0 || dataSearching" class="cmd__list">
            <template v-if="pageResults.length">
              <div class="cmd__group">页面</div>
              <button
                v-for="(item, i) in pageResults"
                :key="'p' + item.path"
                type="button"
                class="cmd__item"
                :class="{ 'is-selected': i === cursor }"
                @mouseenter="cursor = i"
                @click="go(item)"
              >
                <el-icon :size="15" class="cmd__item-icon"><component :is="item.icon" /></el-icon>
                <span class="cmd__item-title">{{ item.title }}</span>
                <span class="cmd__item-path">{{ item.pathShort }}</span>
              </button>
            </template>
            <template v-if="tabResults.length">
              <div class="cmd__group">已打开标签</div>
              <button
                v-for="(item, i) in tabResults"
                :key="'t' + item.path"
                type="button"
                class="cmd__item"
                :class="{ 'is-selected': i + pageResults.length === cursor }"
                @mouseenter="cursor = i + pageResults.length"
                @click="go(item)"
              >
                <span class="cmd__item-dot" :class="{ 'is-fixed': item.fixed }"></span>
                <span class="cmd__item-title">{{ item.title }}</span>
                <span class="cmd__item-path">{{ item.path }}</span>
              </button>
            </template>
            <!-- W2-05/R5：全局数据搜索（项目/需求/缺陷） -->
            <template v-if="dataSearching || dataResults.total > 0">
              <div class="cmd__group">数据搜索</div>
              <div v-if="dataSearching" class="cmd__loading">搜索中…</div>
              <template v-else>
                <button
                  v-for="p in dataResults.projects"
                  :key="'dp' + p.id"
                  type="button"
                  class="cmd__item"
                  @click="goProject(p.id)"
                >
                  <el-icon :size="15" class="cmd__item-icon"><FolderOpened /></el-icon>
                  <span class="cmd__item-title">{{ p.name }}</span>
                  <span class="cmd__item-path">项目</span>
                </button>
                <button
                  v-for="r in dataResults.requirements"
                  :key="'dr' + r.requirementId"
                  type="button"
                  class="cmd__item"
                  @click="goProject(r.projectId)"
                >
                  <el-icon :size="15" class="cmd__item-icon"><Memo /></el-icon>
                  <span class="cmd__item-title">{{ r.title || r.text }}</span>
                  <span class="cmd__item-path">需求</span>
                </button>
                <button
                  v-for="d in dataResults.defects"
                  :key="'dd' + d.defectId"
                  type="button"
                  class="cmd__item"
                  @click="goProject(d.projectId)"
                >
                  <el-icon :size="15" class="cmd__item-icon"><Warning /></el-icon>
                  <span class="cmd__item-title">{{ d.defectId }} · {{ d.defectType }}</span>
                  <span class="cmd__item-path">缺陷</span>
                </button>
                <div v-if="!dataResults.total" class="cmd__loading">未找到匹配数据</div>
              </template>
            </template>
          </div>
          <div v-else class="cmd__empty">
            未找到匹配项。<span class="cmd__empty-hint">可搜索页面，或输入关键词搜索项目 / 需求 / 缺陷数据</span>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
import { ref, computed, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import UserContext from '@/store/user'
import { useTabs, ROUTE_TITLES } from '@/composables/useTabs'
import { dashboardApi } from '@/api'

const router = useRouter()
const { tabs } = useTabs()

const visible = ref(false)
const keyword = ref('')
const cursor = ref(0)
const inputRef = ref(null)

/** 页面候选：业务路由（排除认证/封面/改密与父布局），并按角色过滤管理员路由 */
const ICON_MAP = {
  Dashboard: 'DataBoard',
  Projects: 'FolderOpened',
  ProjectDetail: 'Folder',
  Results: 'DataAnalysis',
  Requirements: 'Memo',
  CodeView: 'Cpu',
  Defects: 'Warning',
  Traceability: 'Share',
  Profile: 'User',
  Backup: 'Coin',
  Audit: 'Notebook',
  Users: 'UserFilled',
  LlmConfig: 'MagicStick',
  SystemConfig: 'Setting',
  IntegrationConfig: 'Connection',
  DataChangeLog: 'EditPen'
}

const PAGES = (() => {
  const EXCLUDE = new Set(['Landing', 'Login', 'Register', 'ChangePassword', 'Layout'])
  const adminOk = UserContext.isAdmin()
  return router
    .getRoutes()
    .filter((r) => {
      if (!ROUTE_TITLES[r.name]) return false
      if (EXCLUDE.has(r.name)) return false
      if (r.meta?.requiresAdmin && !adminOk) return false
      return true
    })
    .map((r) => ({
      path: r.path,
      title: ROUTE_TITLES[r.name],
      icon: ICON_MAP[r.name] || 'Document',
      pathShort: r.path.replace(/\/:[^/]+/g, '/#')
    }))
})()

const match = (kw) => (s) => !kw || s.toLowerCase().includes(kw.toLowerCase())

const pageResults = computed(() => PAGES.filter((p) => match(keyword.value)(p.title + p.path)))
const tabResults = computed(() =>
  tabs.value.filter((t) => {
    const kw = keyword.value
    if (!kw) return false
    return t.path !== router.currentRoute.value.path && t.title.toLowerCase().includes(kw.toLowerCase())
  })
)
const results = computed(() => [...pageResults.value, ...tabResults.value])

// ===== W2-05/R5：全局数据搜索（防抖 300ms，仅关键词非空时请求） =====
const dataSearching = ref(false)
const dataResults = ref({ total: 0, projects: [], requirements: [], defects: [] })
let searchTimer = null

const fetchSearch = async (kw) => {
  if (!kw.trim()) {
    dataResults.value = { total: 0, projects: [], requirements: [], defects: [] }
    dataSearching.value = false
    return
  }
  dataSearching.value = true
  try {
    const res = (await dashboardApi.search(kw)) || {}
    const projects = res.projects || []
    const requirements = res.requirements || []
    const defects = res.defects || []
    dataResults.value = { projects, requirements, defects, total: projects.length + requirements.length + defects.length }
  } catch (e) {
    dataResults.value = { total: 0, projects: [], requirements: [], defects: [] }
  } finally {
    dataSearching.value = false
  }
}

watch(keyword, (kw) => {
  if (searchTimer) clearTimeout(searchTimer)
  searchTimer = setTimeout(() => fetchSearch(kw), 300)
})

const goProject = (id) => {
  close()
  router.push(`/project/${id}`)
}

const move = (dir) => {
  const len = results.value.length
  if (!len) return
  cursor.value = (cursor.value + dir + len) % len
}

const enter = () => {
  const item = results.value[cursor.value]
  if (item) go(item)
}

const go = (item) => {
  close()
  // 参数化路由（项目详情/结果/缺陷等）需从项目进入，直接跳转缺少 :id 参数
  if (item.path && item.path.includes(':')) {
    ElMessage.info('该页面需要先进入具体项目')
    return
  }
  router.push(item.path)
}

const open = () => {
  visible.value = true
  keyword.value = ''
  cursor.value = 0
  nextTick(() => inputRef.value?.focus())
}

const close = () => {
  visible.value = false
}

watch(visible, (v) => {
  if (!v) return
  keyword.value = ''
  cursor.value = 0
  nextTick(() => inputRef.value?.focus())
})

// ⌘/Ctrl+K 全局开关（W1-08 / O14）
const onKey = (e) => {
  if ((e.metaKey || e.ctrlKey) && (e.key === 'k' || e.key === 'K')) {
    e.preventDefault()
    visible.value ? close() : open()
  }
}

if (typeof window !== 'undefined') {
  window.addEventListener('keydown', onKey)
}

defineExpose({ open, close })
</script>

<style scoped>
.cmd-mask {
  position: fixed;
  inset: 0;
  z-index: 3000;
  display: flex;
  justify-content: center;
  align-items: flex-start;
  padding-top: 12vh;
  background: rgba(43, 36, 28, 0.32);
  -webkit-backdrop-filter: blur(6px);
  backdrop-filter: blur(6px);
}

.cmd {
  width: 560px;
  max-width: calc(100vw - 32px);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.94);
  -webkit-backdrop-filter: blur(32px) saturate(1.4);
  backdrop-filter: blur(32px) saturate(1.4);
  border: 1px solid rgba(255, 255, 255, 0.65);
  box-shadow: var(--tg-shadow-modal);
  overflow: hidden;
}

.cmd__input-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 20px;
  border-bottom: 1px solid var(--tg-border);
}

.cmd__search-icon {
  color: var(--tg-accent);
  flex-shrink: 0;
}

.cmd__input {
  flex: 1;
  min-width: 0;
  border: none;
  outline: none;
  background: transparent;
  font-size: 15px;
  font-family: inherit;
  color: var(--tg-text-primary);
}

.cmd__input::placeholder {
  color: var(--tg-slate);
}

.cmd__kbd {
  padding: 2px 8px;
  border-radius: 6px;
  border: 1px solid var(--tg-border);
  background: var(--tg-bg-page);
  font-size: 11px;
  color: var(--tg-text-secondary);
  font-family: var(--tg-font-mono);
  flex-shrink: 0;
}

.cmd__list {
  max-height: 380px;
  overflow-y: auto;
  padding: 8px;
}

.cmd__group {
  padding: 10px 12px 4px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.1em;
  color: var(--tg-slate);
  text-transform: uppercase;
}

.cmd__item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 10px 12px;
  border: none;
  border-radius: 12px;
  background: transparent;
  text-align: left;
  font-family: inherit;
  font-size: 14px;
  color: var(--tg-text-primary);
  cursor: pointer;
  transition: background 0.2s ease;
}

.cmd__item:hover,
.cmd__item.is-selected {
  background: var(--el-color-primary-light-9);
}

.cmd__item-icon {
  color: var(--tg-accent);
  flex-shrink: 0;
}

.cmd__item-title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.cmd__item-path {
  font-size: 12px;
  color: var(--tg-slate);
  font-family: var(--tg-font-mono);
  flex-shrink: 0;
}

.cmd__item-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--tg-indigo);
  box-shadow: 0 0 0 3px rgba(201, 155, 63, 0.16);
  flex-shrink: 0;
}

.cmd__item-dot.is-fixed {
  background: var(--tg-teal);
  box-shadow: 0 0 0 3px rgba(154, 156, 107, 0.16);
}

.cmd__empty {
  padding: 28px 20px;
  text-align: center;
  font-size: 13px;
  color: var(--tg-text-secondary);
}

.cmd__empty-hint {
  color: var(--tg-slate);
}

.cmd__loading {
  padding: 12px 14px;
  font-size: 12.5px;
  color: var(--tg-text-secondary);
}

.cmd-fade-enter-active,
.cmd-fade-leave-active {
  transition: opacity 0.25s ease;
}

.cmd-fade-enter-active .cmd,
.cmd-fade-leave-active .cmd {
  transition: transform 0.25s var(--tg-ease), opacity 0.25s ease;
}

.cmd-fade-enter-from,
.cmd-fade-leave-to {
  opacity: 0;
}

.cmd-fade-enter-from .cmd,
.cmd-fade-leave-to .cmd {
  transform: translateY(-12px) scale(0.98);
  opacity: 0;
}
</style>