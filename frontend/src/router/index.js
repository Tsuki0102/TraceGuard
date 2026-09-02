import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  // 产品封面页（Landing）：未登录可访问，已登录自动进入工作台
  {
    path: '/home',
    name: 'Landing',
    component: () => import('@/views/Landing.vue')
  },
  {
    path: '/login',
    name: 'Login',
    component: () => import('@/views/Login.vue')
  },
  // 3.8 整改：注册账号 UI 入口（手册 2.3 宣称支持注册，此前缺少前端页面）
  {
    path: '/register',
    name: 'Register',
    component: () => import('@/views/Register.vue')
  },
  // GAP-027：强制改密页（独立于 Layout，无需正常登录后可达）
  {
    path: '/change-password',
    name: 'ChangePassword',
    component: () => import('@/views/ChangePassword.vue'),
    meta: { requiresAuth: true }
  },
  {
    path: '/',
    name: 'Layout',
    component: () => import('@/views/Layout.vue'),
    redirect: '/home',
    children: [
      {
        path: 'dashboard',
        name: 'Dashboard',
        component: () => import('@/views/Dashboard.vue')
      },
      {
        path: 'projects',
        name: 'Projects',
        component: () => import('@/views/Projects.vue')
      },
      {
        path: 'project/:id',
        name: 'ProjectDetail',
        component: () => import('@/views/ProjectDetail.vue')
      },
      {
        path: 'results/:id',
        name: 'Results',
        component: () => import('@/views/Results.vue')
      },
      {
        path: 'requirements/:id',
        name: 'Requirements',
        component: () => import('@/views/Requirements.vue')
      },
      {
        path: 'code/:id',
        name: 'CodeView',
        component: () => import('@/views/CodeView.vue')
      },
      {
        path: 'defects/:id',
        name: 'Defects',
        component: () => import('@/views/Defects.vue')
      },
      {
        path: 'traceability/:id',
        name: 'Traceability',
        component: () => import('@/views/Traceability.vue')
      },
      // ===== W5 质量洞察波：跨项目分析模块 =====
      {
        path: 'insight/trends',
        name: 'DefectTrends',
        component: () => import('@/views/DefectTrends.vue')
      },
      {
        path: 'insight/portfolio',
        name: 'PortfolioBrief',
        component: () => import('@/views/PortfolioBrief.vue')
      },
      {
        path: 'tickets',
        name: 'DefectTickets',
        component: () => import('@/views/DefectTickets.vue')
      },
      {
        path: 'patterns',
        name: 'DefectPatterns',
        component: () => import('@/views/DefectPatterns.vue')
      },
      {
        path: 'reports',
        name: 'ReportsHub',
        component: () => import('@/views/ReportsHub.vue')
      },
      // ===== W6 二期：阈值实验室 / 评测中心 / Alloy 工作台 =====
      {
        path: 'lab/threshold',
        name: 'ThresholdLab',
        component: () => import('@/views/ThresholdLab.vue')
      },
      {
        path: 'eval',
        name: 'EvalCenter',
        component: () => import('@/views/EvalCenter.vue')
      },
      {
        path: 'alloy-lab',
        name: 'AlloyLab',
        component: () => import('@/views/AlloyLab.vue')
      },
      // SEC-11：以下为管理员专属路由（守卫校验 role，菜单隐藏对普通用户不可见）
      {
        path: 'backup',
        name: 'Backup',
        component: () => import('@/views/Backup.vue'),
        meta: { requiresAdmin: true }
      },
      {
        path: 'audit',
        name: 'Audit',
        component: () => import('@/views/Audit.vue'),
        meta: { requiresAdmin: true }
      },
      {
        path: 'profile',
        name: 'Profile',
        component: () => import('@/views/Profile.vue')
      },
      {
        path: 'users',
        name: 'Users',
        component: () => import('@/views/Users.vue'),
        meta: { requiresAdmin: true }
      },
      // GAP-033：大模型配置/状态页（仅管理员，菜单置于"系统设置"分组）
      {
        path: 'llm-config',
        name: 'LlmConfig',
        component: () => import('@/views/LlmConfig.vue'),
        meta: { requiresAdmin: true }
      },
      // AUD-07：需求解析规则可视化配置（仅管理员）
      {
        path: 'system-config',
        name: 'SystemConfig',
        component: () => import('@/views/SystemConfig.vue'),
        meta: { requiresAdmin: true }
      },
      // AUD-11：Jira/禅道连通测试配置（仅管理员）
      {
        path: 'integration-config',
        name: 'IntegrationConfig',
        component: () => import('@/views/IntegrationConfig.vue'),
        meta: { requiresAdmin: true }
      },
      // 2.8 整改：数据变更日志（字段级 diff，仅管理员）
      {
        path: 'data-change-log',
        name: 'DataChangeLog',
        component: () => import('@/views/DataChangeLog.vue'),
        meta: { requiresAdmin: true }
      },
      // W2-07/R11：系统运行状态（仅管理员）
      {
        path: 'system-status',
        name: 'SystemStatus',
        component: () => import('@/views/SystemStatus.vue'),
        meta: { requiresAdmin: true }
      },
      // W3-02：菜单配置化（仅管理员；仅隐藏入口，不改权限模型）
      {
        path: 'menu-config',
        name: 'MenuConfig',
        component: () => import('@/views/MenuConfig.vue'),
        meta: { requiresAdmin: true }
      }
    ]
  },
  // W1-02/R7：403/404/500 定制结果页 + 通用成功反馈页（注册成功等）
  {
    path: '/403',
    name: 'Forbidden',
    component: () => import('@/views/ResultPage.vue')
  },
  {
    path: '/404',
    name: 'NotFound',
    component: () => import('@/views/ResultPage.vue')
  },
  {
    path: '/500',
    name: 'ServerError',
    component: () => import('@/views/ResultPage.vue')
  },
  {
    path: '/success',
    name: 'SuccessPage',
    component: () => import('@/views/ResultPage.vue')
  },
  // 兜底 404（未匹配路由统一走定制页）
  {
    path: '/:pathMatch(.*)*',
    name: 'CatchAll',
    component: () => import('@/views/ResultPage.vue')
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to, from, next) => {
  // SEC-10①：登录态由服务端 HttpOnly Cookie 承载，前端仅通过非敏感标志 cookie 判断是否已登录
  const loggedIn = document.cookie.split(';').some(c => c.trim().startsWith('tg_logged_in='))
  // 产品封面页：统一作为开始界面，无论是否登录均展示封面；已登录用户通过"进入工作台"进入系统
  if (to.path === '/home') {
    next()
    return
  }
  // 登录/注册页：未登录可访问；已登录访问则进入工作台
  if (to.path === '/login' || to.path === '/register') {
    if (loggedIn) next('/dashboard')
    else next()
    return
  }
  // 注册成功反馈页可匿名访问
  if (to.path !== '/success' && to.path !== '/login' && !loggedIn) {
    next('/login')
    return
  }
  if (loggedIn && to.path !== '/change-password') {
    try {
      const userInfo = JSON.parse(localStorage.getItem('userInfo') || '{}')
      // GAP-027：已登录用户但 mustChangePassword 为 true 时，拦截到改密页
      if (userInfo.mustChangePassword) {
        next('/change-password')
        return
      }
      // SEC-11：管理员路由角色校验（role 来自登录时服务端返回，后端 403 仍为最终兜底）
      if (to.meta.requiresAdmin && userInfo.role !== 'admin') {
        next('/403')
        return
      }
    } catch (e) {
      // 本地 userInfo 解析失败时按无权限处理，避免绕过管理员路由
      if (to.meta.requiresAdmin) {
        next('/403')
        return
      }
    }
  }
  next()
})

export default router
