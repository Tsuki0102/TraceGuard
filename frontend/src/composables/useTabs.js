/**
 * 多标签页导航状态（全局单例，模块级响应式，各组件共享）
 * W1-01 / R6：工作台与项目管理为固定标签，其余访问后追加、可关闭。
 */
import { reactive, computed } from 'vue'
import UserContext from '@/store/user'

/** 路由 name -> 标签标题（固定标题映射；动态页可 setCurrentTitle 覆盖为项目名） */
const ROUTE_TITLES = {
  Dashboard: '工作台',
  Projects: '项目管理',
  ProjectDetail: '项目详情',
  Results: '分析结果',
  Requirements: '需求分析',
  CodeView: '代码分析',
  Defects: '缺陷报告',
  Traceability: '追溯矩阵',
  Profile: '个人中心',
  Backup: '数据备份',
  Audit: '审计日志',
  Users: '用户管理',
  LlmConfig: '大模型配置',
  SystemConfig: '需求解析规则',
  IntegrationConfig: '集成连通测试',
  DataChangeLog: '数据变更日志',
  SystemStatus: '系统状态',
  MenuConfig: '菜单配置',
  // W5 质量洞察波
  DefectTrends: '缺陷趋势对比',
  PortfolioBrief: '组合质量简报',
  DefectTickets: '缺陷工单',
  DefectPatterns: '缺陷模式库',
  ReportsHub: '报告中心',
  ThresholdLab: '阈值实验室',
  EvalCenter: '评测中心',
  AlloyLab: 'Alloy 规约工作台'
}

/** 固定标签（不可关闭），作为常驻导航入口 */
const FIXED_PATHS = ['/dashboard', '/projects']

const STORAGE_KEY = () => `tg_tabs_${UserContext.getUsername() || 'guest'}`

const state = reactive({
  tabs: [],       // [{ path, title, fixed }]
  activePath: '',
  refreshTick: 0  // 标签"刷新"时自增，驱动 Layout 的 router-view key 重建
})

function resolveTitle(name) {
  return ROUTE_TITLES[name] || name || '页面'
}

function persist() {
  try {
    const list = state.tabs.map((t) => ({ path: t.path, title: t.title, fixed: t.fixed }))
    localStorage.setItem(STORAGE_KEY(), JSON.stringify(list))
  } catch (e) {
    /* 私密/禁用存储时忽略，标签仅内存生效 */
  }
}

/** 读取固定标签（title 始终以常量表为准，路径以 caught 到的 name 反查） */
function fixedTabs() {
  return FIXED_PATHS.map((path) => {
    const name = path.slice(1).replace(/^./, (c) => c.toUpperCase())
    return { path, title: resolveTitle(name), fixed: true }
  })
}

function restore() {
  try {
    const raw = localStorage.getItem(STORAGE_KEY())
    const base = fixedTabs()
    state.tabs = [...base]
    if (!raw) return
    const list = JSON.parse(raw)
    if (!Array.isArray(list)) return
    // 仅还原非固定标签，并去重
    const seen = new Set(base.map((t) => t.path))
    list.forEach((t) => {
      if (!t || !t.path || typeof t.path !== 'string') return
      if (seen.has(t.path)) return
      seen.add(t.path)
      state.tabs.push({ path: t.path, title: t.title || t.path, fixed: false })
    })
  } catch (e) {
    /* 解析失败时回退为固定标签，由首访重建 */
  }
}

function addTab(path, name) {
  if (state.tabs.some((t) => t.path === path)) return
  const fixed = FIXED_PATHS.includes(path)
  state.tabs.push({
    path,
    title: resolveTitle(name),
    fixed
  })
  persist()
}

function removeTab(path) {
  const t = state.tabs.find((x) => x.path === path)
  if (!t || t.fixed) return null
  const idx = state.tabs.indexOf(t)
  state.tabs.splice(idx, 1)
  persist()
  // 关闭的是当前激活标签：优先落到右侧相邻（存在则取），否则取左侧最近
  if (state.activePath === path) {
    const next = state.tabs[idx] || state.tabs[idx - 1]
    return next ? next.path : FIXED_PATHS[0]
  }
  return null
}

function closeOthers(path) {
  const keeper = state.tabs.find((t) => t.path === path)
  state.tabs = [
    ...state.tabs.filter((t) => t.fixed),
    keeper && !keeper.fixed ? keeper : null
  ].filter(Boolean)
  persist()
}

function closeAll() {
  state.tabs = fixedTabs()
  persist()
  return state.tabs[0].path
}

function bumpRefresh() {
  state.refreshTick += 1
}

export function useTabs() {
  const tabs = computed(() => state.tabs)
  const activePath = computed(() => state.activePath)
  const refreshTick = computed(() => state.refreshTick)

  return {
    tabs,
    activePath,
    refreshTick,
    addTab,
    removeTab,
    closeOthers,
    closeAll,
    bumpRefresh,
    setActive(path) {
      state.activePath = path
    },
    /** 页面数据就绪后更新当前标签标题（如项目详情标签显示项目名） */
    setCurrentTitle(title) {
      if (!title) return
      const t = state.tabs.find((x) => x.path === state.activePath)
      if (t && t.title !== title) {
        t.title = title
        persist()
      }
    },
    restore
  }
}

export { ROUTE_TITLES }