<template>
  <el-container class="layout-container">
    <el-aside width="220px" class="sidebar">
      <div class="logo">
        <h2>TraceGuard</h2>
      </div>
      <el-menu
        :default-active="activeMenu"
        router
        background-color="#304156"
        text-color="#bfcbd9"
        active-text-color="#409EFF"
      >
        <el-menu-item index="/dashboard">
          <el-icon><DataBoard /></el-icon>
          <span>工作台</span>
        </el-menu-item>
        <el-menu-item index="/projects">
          <el-icon><Folder /></el-icon>
          <span>项目管理</span>
        </el-menu-item>
        <!-- SEC-11：数据备份为管理员功能，与路由 requiresAdmin 口径一致 -->
        <el-menu-item v-if="isAdmin" index="/backup">
          <el-icon><Box /></el-icon>
          <span>数据备份</span>
        </el-menu-item>
        <el-menu-item v-if="isAdmin" index="/audit">
          <el-icon><Document /></el-icon>
          <span>审计日志</span>
        </el-menu-item>
        <el-menu-item v-if="isAdmin" index="/users">
          <el-icon><User /></el-icon>
          <span>用户管理</span>
        </el-menu-item>
        <!-- GAP-033：系统设置分组（大模型配置） -->
        <el-sub-menu v-if="isAdmin" index="/system">
          <template #title>
            <el-icon><Setting /></el-icon>
            <span>系统设置</span>
          </template>
          <el-menu-item index="/llm-config">
            <el-icon><Cpu /></el-icon>
            <span>大模型配置</span>
          </el-menu-item>
          <!-- AUD-07：需求解析规则可视化配置 -->
          <el-menu-item index="/system-config">
            <el-icon><Setting /></el-icon>
            <span>需求解析规则</span>
          </el-menu-item>
          <!-- AUD-11：Jira/禅道连通测试配置（admin 专用） -->
          <el-menu-item index="/integration-config">
            <el-icon><Connection /></el-icon>
            <span>集成连通测试</span>
          </el-menu-item>
          <!-- 2.8 整改：数据变更日志（字段级 diff，admin 专用） -->
          <el-menu-item index="/data-change-log">
            <el-icon><DocumentCopy /></el-icon>
            <span>数据变更日志</span>
          </el-menu-item>
        </el-sub-menu>
      </el-menu>
    </el-aside>
    <el-container>
      <el-header class="header">
        <div class="header-left">
          <el-breadcrumb separator="/">
            <el-breadcrumb-item :to="{ path: '/dashboard' }">首页</el-breadcrumb-item>
            <el-breadcrumb-item v-if="pageTitle">{{ pageTitle }}</el-breadcrumb-item>
          </el-breadcrumb>
        </div>
        <div class="header-right">
          <el-dropdown @command="handleCommand">
            <span class="user-info">
              <el-avatar :size="32" icon="UserFilled" />
              <span class="username">{{ userInfo?.realName || userInfo?.username }}</span>
              <el-icon><ArrowDown /></el-icon>
            </span>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile">个人中心</el-dropdown-item>
                <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>
  </el-container>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi } from '@/api'

const route = useRoute()
const router = useRouter()
const userInfo = ref(null)

const activeMenu = computed(() => route.path)
const isAdmin = computed(() => userInfo.value?.role === 'admin')

/** FUN-15：面包屑标题——按当前路由解析页面名称（替代从未赋值的 currentProject 死逻辑） */
const pageTitle = computed(() => {
  const p = route.path
  if (p.startsWith('/project/')) return '项目详情'
  if (p.startsWith('/results/')) return '分析结果'
  if (p.startsWith('/requirements/')) return '需求分析'
  if (p.startsWith('/code/')) return '代码视图'
  if (p.startsWith('/defects/')) return '缺陷列表'
  if (p.startsWith('/traceability/')) return '追溯矩阵'
  const map = {
    '/dashboard': '仪表盘',
    '/projects': '项目管理',
    '/users': '用户管理',
    '/audit': '审计日志',
    '/llm-config': '大模型配置',
    '/system-config': '系统配置',
    '/integration-config': '集成配置',
    '/backup': '数据备份',
    '/data-change-log': '数据变更日志',
    '/profile': '个人中心'
  }
  return map[p] || ''
})

onMounted(() => {
  const userStr = localStorage.getItem('userInfo')
  if (userStr) {
    userInfo.value = JSON.parse(userStr)
  }
})

const handleCommand = (cmd) => {
  if (cmd === 'profile') {
    router.push('/profile')
  } else if (cmd === 'logout') {
    // SEC-16：先通知后端吊销当前 JWT（失败不阻断登出），再清理本地凭证
    authApi.logout().finally(() => {
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      router.push('/login')
    })
  }
}
</script>

<style scoped>
.layout-container {
  height: 100vh;
}

.sidebar {
  background-color: #304156;
  overflow-x: hidden;
}

.logo {
  height: 60px;
  display: flex;
  align-items: center;
  justify-content: center;
  background-color: #263445;
}

.logo h2 {
  color: white;
  font-size: 20px;
  margin: 0;
}

.sidebar :deep(.el-menu) {
  border-right: none;
}

.header {
  background: white;
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 0 20px;
  box-shadow: 0 1px 4px rgba(0,21,41,0.08);
}

.header-right {
  display: flex;
  align-items: center;
}

.user-info {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
}

.username {
  color: #333;
}

.main-content {
  background-color: #f0f2f5;
  padding: 20px;
}
</style>
