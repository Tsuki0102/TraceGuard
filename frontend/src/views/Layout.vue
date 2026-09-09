<template>
  <div class="app-shell">
    <header class="topbar">
      <div class="topbar__left">
        <!-- 移动端汉堡菜单入口（≤768px 显示；桌面由 CSS 隐藏） -->
        <button
          type="button"
          class="hamburger"
          aria-label="打开导航菜单"
          :aria-expanded="drawerOpen"
          @click="drawerOpen = true"
        >
          <el-icon :size="21"><Menu /></el-icon>
        </button>
        <router-link to="/dashboard" class="logo">
          <div class="logo-icon"><img src="@/assets/logo/logo-gold.png" alt="TraceGuard 标志" /></div>
          <div class="logo-text"><span>Trace</span>Guard</div>
        </router-link>
        <el-menu
          mode="horizontal"
          :default-active="activeMenu"
          router
          class="top-menu"
          :ellipsis="false"
        >
          <el-menu-item index="/dashboard">工作台</el-menu-item>
          <el-menu-item index="/projects">项目管理</el-menu-item>
          <!-- W5：报告中心（常驻一级，与工作台/项目管理同级的第三个固定入口） -->
          <el-menu-item v-if="!hiddenItems.includes('/reports')" index="/reports">报告中心</el-menu-item>
          <!-- W5 波：质量洞察（跨项目分析智能）；W3-02 支持按配置隐藏 -->
          <el-sub-menu v-if="!hiddenTop.includes('insight')" index="insight">
            <template #title>质量洞察</template>
            <el-menu-item v-if="!hiddenItems.includes('/insight/trends')" index="/insight/trends">缺陷趋势对比</el-menu-item>
            <el-menu-item v-if="!hiddenItems.includes('/insight/portfolio')" index="/insight/portfolio">组合质量简报</el-menu-item>
            <el-menu-item v-if="!hiddenItems.includes('/lab/threshold')" index="/lab/threshold">阈值实验室</el-menu-item>
            <el-menu-item v-if="!hiddenItems.includes('/eval')" index="/eval">评测中心</el-menu-item>
            <el-menu-item v-if="!hiddenItems.includes('/alloy-lab')" index="/alloy-lab">Alloy 规约工作台</el-menu-item>
          </el-sub-menu>
          <!-- W5 波：缺陷治理（检测到闭环）；误报治理并入缺陷工单 -->
          <el-sub-menu v-if="!hiddenTop.includes('govern')" index="govern">
            <template #title>缺陷治理</template>
            <el-menu-item v-if="!hiddenItems.includes('/tickets')" index="/tickets">缺陷工单</el-menu-item>
            <el-menu-item v-if="!hiddenItems.includes('/patterns')" index="/patterns">缺陷模式库</el-menu-item>
          </el-sub-menu>
          <!-- 注：Alloy 规约工作台归入"质量洞察"下拉（按需求不单独占位）；实验作业管理功能取消 -->
          <!-- SEC-11：数据备份等管理功能仅管理员可见，与路由 requiresAdmin 口径一致；W3-02 支持按配置隐藏 -->
          <el-sub-menu v-if="isAdmin && !hiddenTop.includes('manage')" index="manage">
            <template #title>维护中心</template>
            <el-menu-item v-if="!hiddenItems.includes('/backup')" index="/backup">数据备份</el-menu-item>
            <el-menu-item v-if="!hiddenItems.includes('/audit')" index="/audit">审计日志</el-menu-item>
            <el-menu-item v-if="!hiddenItems.includes('/users')" index="/users">用户管理</el-menu-item>
            <!-- W2-07/R11：系统运行状态 -->
            <el-menu-item v-if="!hiddenItems.includes('/system-status')" index="/system-status">系统状态</el-menu-item>
          </el-sub-menu>
          <!-- GAP-033：系统设置分组（大模型配置等）；W3-02 支持按配置隐藏 -->
          <el-sub-menu v-if="isAdmin && !hiddenTop.includes('sys')" index="sys">
            <template #title>系统配置</template>
            <el-menu-item v-if="!hiddenItems.includes('/llm-config')" index="/llm-config">大模型配置</el-menu-item>
            <!-- AUD-07：需求解析规则可视化配置 -->
            <el-menu-item v-if="!hiddenItems.includes('/system-config')" index="/system-config">需求解析规则</el-menu-item>
            <!-- AUD-11：Jira/禅道连通测试配置（admin 专用） -->
            <el-menu-item v-if="!hiddenItems.includes('/integration-config')" index="/integration-config">集成连通测试</el-menu-item>
            <!-- 2.8 整改：数据变更日志（字段级 diff，admin 专用） -->
            <el-menu-item v-if="!hiddenItems.includes('/data-change-log')" index="/data-change-log">数据变更日志</el-menu-item>
            <!-- W3-02：菜单配置化（管理员） -->
            <el-menu-item v-if="!hiddenItems.includes('/menu-config')" index="/menu-config">菜单配置</el-menu-item>
          </el-sub-menu>
        </el-menu>
      </div>
      <div class="topbar__right">
        <!-- W1-08/O14：命令面板入口（⌘/Ctrl + K 唤起，导航搜索） -->
        <button type="button" class="cmd-trigger" title="搜索页面（⌘K / 按 ? 查看全部快捷键）" @click="openCommand">
          <el-icon :size="14"><Search /></el-icon>
          <span class="cmd-trigger__label">搜索页面</span>
          <kbd class="cmd-trigger__kbd">⌘K</kbd>
        </button>
        <!-- W3-03/R16：主题切换（亮色 / 深色） -->
        <button type="button" class="cmd-trigger" :aria-label="theme === 'dark' ? '切换亮色' : '切换深色'" @click="toggleTheme">
          <el-icon :size="15"><component :is="theme === 'dark' ? 'Sunny' : 'Moon'" /></el-icon>
        </button>
        <!-- 个性化增强 BATCH-1：外观设置（主题色变体 / 界面密度） -->
        <el-popover placement="bottom-end" :width="236" trigger="click" popper-class="appearance-popper">
          <template #reference>
            <button type="button" class="cmd-trigger cmd-trigger--appearance" aria-label="外观设置">
              <el-icon :size="15"><Brush /></el-icon>
            </button>
          </template>
          <div class="appearance-panel">
            <div class="appearance-panel__title">主题色</div>
            <div class="appearance-panel__swatches">
              <button
                v-for="a in ACCENTS"
                :key="a.key"
                type="button"
                class="appearance-panel__swatch"
                :class="{ 'is-active': accent === a.key }"
                :style="{ background: a.color }"
                :aria-label="a.label"
                :title="a.label"
                @click="setAccent(a.key)"
              >
                <el-icon v-if="accent === a.key" :size="13" color="#fff"><Check /></el-icon>
              </button>
            </div>
            <div class="appearance-panel__title">界面密度</div>
            <div class="appearance-panel__density">
              <button
                v-for="d in DENSITY_OPTIONS"
                :key="d[0]"
                type="button"
                class="appearance-panel__density-chip"
                :class="{ 'is-active': density === d[0] }"
                @click="setDensity(d[0])"
              >{{ d[1] }}</button>
            </div>
          </div>
        </el-popover>
        <!-- W2-04/R4：通知铃铛（待办 + WS 实时任务动态） -->
        <NotificationBell />
        <!-- W2-08/R12：AI 助手（仅管理员，防配额滥用） -->
        <button
          v-if="isAdmin"
          type="button"
          class="cmd-trigger cmd-trigger--ai"
          aria-label="AI 助手"
          @click="openAi"
        >
          <el-icon :size="15"><MagicStick /></el-icon>
          <span class="cmd-trigger__label">AI 助手</span>
        </button>
        <el-dropdown @command="handleCommand" trigger="click">
          <span class="user-info">
            <el-avatar :size="30" :src="avatarUrl || undefined" icon="UserFilled" />
            <span class="username">{{ userInfo?.realName || userInfo?.username }}</span>
            <el-icon><ArrowDown /></el-icon>
          </span>
          <template #dropdown>
            <el-dropdown-menu>
              <el-dropdown-item command="profile">个人中心</el-dropdown-item>
              <!-- 个性化增强 BATCH-3：快捷键速查表（? 唤起） -->
    <el-dialog v-model="hotkeySheet" title="键盘快捷键" width="430px" class="hotkey-dialog" append-to-body>
      <div class="hotkey-sheet">
        <div class="hotkey-sheet__group">
          <div class="hotkey-sheet__title">导航 · 先按 G 再按字母</div>
          <div v-for="n in HOTKEY_NAV" :key="n.path" class="hotkey-sheet__row">
            <span class="hotkey-sheet__keys"><kbd v-for="k in n.keys" :key="k">{{ k }}</kbd></span>
            <span class="hotkey-sheet__label">{{ n.label }}</span>
          </div>
        </div>
        <div class="hotkey-sheet__group">
          <div class="hotkey-sheet__title">全局</div>
          <div class="hotkey-sheet__row">
            <span class="hotkey-sheet__keys"><kbd>/</kbd></span>
            <span class="hotkey-sheet__label">打开命令面板（等同 ⌘K）</span>
          </div>
          <div class="hotkey-sheet__row">
            <span class="hotkey-sheet__keys"><kbd>?</kbd></span>
            <span class="hotkey-sheet__label">显示本速查表</span>
          </div>
          <div class="hotkey-sheet__row">
            <span class="hotkey-sheet__keys"><kbd>Esc</kbd></span>
            <span class="hotkey-sheet__label">关闭弹层</span>
          </div>
        </div>
      </div>
    </el-dialog>
    <!-- W3-04/R17：锁屏 -->
              <el-dropdown-item command="lock" divided>锁屏</el-dropdown-item>
              <el-dropdown-item command="logout">退出登录</el-dropdown-item>
            </el-dropdown-menu>
          </template>
        </el-dropdown>
      </div>
    </header>

    <!-- ===== 移动端抽屉导航（由汉堡按钮唤起；桌面不显示触发入口） ===== -->
    <el-drawer
      v-model="drawerOpen"
      direction="ltr"
      size="86%"
      title="TraceGuard"
      class="mobile-nav-drawer"
    >
      <el-menu
        mode="vertical"
        :default-active="activeMenu"
        router
        class="mobile-menu"
        @select="drawerOpen = false"
      >
        <el-menu-item index="/dashboard">工作台</el-menu-item>
        <el-menu-item index="/projects">项目管理</el-menu-item>
        <el-menu-item v-if="!hiddenItems.includes('/reports')" index="/reports">报告中心</el-menu-item>

        <el-sub-menu v-if="!hiddenTop.includes('insight')" index="m-insight">
          <template #title>质量洞察</template>
          <el-menu-item v-if="!hiddenItems.includes('/insight/trends')" index="/insight/trends">缺陷趋势对比</el-menu-item>
          <el-menu-item v-if="!hiddenItems.includes('/insight/portfolio')" index="/insight/portfolio">组合质量简报</el-menu-item>
          <el-menu-item v-if="!hiddenItems.includes('/lab/threshold')" index="/lab/threshold">阈值实验室</el-menu-item>
          <el-menu-item v-if="!hiddenItems.includes('/eval')" index="/eval">评测中心</el-menu-item>
          <el-menu-item v-if="!hiddenItems.includes('/alloy-lab')" index="/alloy-lab">Alloy 规约工作台</el-menu-item>
        </el-sub-menu>

        <el-sub-menu v-if="!hiddenTop.includes('govern')" index="m-govern">
          <template #title>缺陷治理</template>
          <el-menu-item v-if="!hiddenItems.includes('/tickets')" index="/tickets">缺陷工单</el-menu-item>
          <el-menu-item v-if="!hiddenItems.includes('/patterns')" index="/patterns">缺陷模式库</el-menu-item>
        </el-sub-menu>

        <el-sub-menu v-if="isAdmin && !hiddenTop.includes('manage')" index="m-manage">
          <template #title>维护中心</template>
          <el-menu-item v-if="!hiddenItems.includes('/backup')" index="/backup">数据备份</el-menu-item>
          <el-menu-item v-if="!hiddenItems.includes('/audit')" index="/audit">审计日志</el-menu-item>
          <el-menu-item v-if="!hiddenItems.includes('/users')" index="/users">用户管理</el-menu-item>
          <el-menu-item v-if="!hiddenItems.includes('/system-status')" index="/system-status">系统状态</el-menu-item>
        </el-sub-menu>

        <el-sub-menu v-if="isAdmin && !hiddenTop.includes('sys')" index="m-sys">
          <template #title>系统配置</template>
          <el-menu-item v-if="!hiddenItems.includes('/llm-config')" index="/llm-config">大模型配置</el-menu-item>
          <el-menu-item v-if="!hiddenItems.includes('/system-config')" index="/system-config">需求解析规则</el-menu-item>
          <el-menu-item v-if="!hiddenItems.includes('/integration-config')" index="/integration-config">集成连通测试</el-menu-item>
          <el-menu-item v-if="!hiddenItems.includes('/data-change-log')" index="/data-change-log">数据变更日志</el-menu-item>
          <el-menu-item v-if="!hiddenItems.includes('/menu-config')" index="/menu-config">菜单配置</el-menu-item>
        </el-sub-menu>

        <!-- 抽屉底部：常用快捷操作（顶栏在移动端做了精简，入口下沉到此处） -->
        <div class="mobile-menu__foot">
          <button type="button" class="mobile-menu__act" @click="goMobile('/profile')">
            <el-icon :size="17"><User /></el-icon>
            <span>个人中心</span>
          </button>
          <button type="button" class="mobile-menu__act" @click="toggleTheme">
            <el-icon :size="17"><component :is="theme === 'dark' ? 'Sunny' : 'Moon'" /></el-icon>
            <span>{{ theme === 'dark' ? '切换亮色' : '切换深色' }}</span>
          </button>
          <button type="button" class="mobile-menu__act" @click="openCommand">
            <el-icon :size="17"><Search /></el-icon>
            <span>搜索页面</span>
          </button>
          <button type="button" class="mobile-menu__act" @click="openAi">
            <el-icon :size="17"><MagicStick /></el-icon>
            <span>智能助手</span>
          </button>
          <button type="button" class="mobile-menu__act mobile-menu__act--danger" @click="handleCommand('logout')">
            <el-icon :size="17"><SwitchButton /></el-icon>
            <span>退出登录</span>
          </button>
        </div>
      </el-menu>
    </el-drawer>

    <!-- W1-01/R6：多标签页导航（固定标签常驻，业务标签可关闭/右键管理） -->
    <TabsNav />
    <main class="main-content">
      <router-view v-slot="{ Component }">
        <transition name="tg-page" mode="out-in">
          <!-- :key 绑定 refreshTick：标签"刷新"时重建当前页面 -->
          <component :is="Component" :key="viewKey" />
        </transition>
      </router-view>
    </main>
    <!-- W1-08/O14：⌘/Ctrl+K 命令面板 -->
    <GlobalCommand ref="commandRef" />
    <!-- W2-08/R12：AI 助手抽屉 -->
    <AiAssistant ref="aiRef" />
    <!-- W3-04/R17：锁屏 -->
    <LockScreen ref="lockRef" />
    <!-- W3-13/O15：首次登录新手引导 -->
    <Onboarding />
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { authApi, dashboardApi } from '@/api'
import TabsNav from '@/components/TabsNav.vue'
import GlobalCommand from '@/components/GlobalCommand.vue'
import NotificationBell from '@/components/NotificationBell.vue'
import AiAssistant from '@/components/AiAssistant.vue'
import LockScreen from '@/components/LockScreen.vue'
import Onboarding from '@/components/Onboarding.vue'
import { useTabs } from '@/composables/useTabs'
import { useTheme, ACCENTS } from '@/composables/useTheme'
import { useHotkeys, HOTKEY_NAV } from '@/composables/useHotkeys'
import { applyRemotePreference } from '@/utils/preferenceSync'

const route = useRoute()
const router = useRouter()
const userInfo = ref(null)
const commandRef = ref(null)
const aiRef = ref(null)
const lockRef = ref(null)
const { theme, toggleTheme, setTheme, accent, setAccent, density, setDensity } = useTheme()
const DENSITY_OPTIONS = [
  ['cozy', '舒适'],
  ['compact', '紧凑']
]

const activeMenu = computed(() => route.path)
const isAdmin = computed(() => userInfo.value?.role === 'admin')

// ===== 移动端导航（汉堡菜单 + 抽屉） =====
const drawerOpen = ref(false)
const isMobile = ref(false)

/** 视口断点同步：回到桌面宽度时自动收起抽屉，避免遮罩/焦点残留 */
const syncViewport = () => {
  isMobile.value = window.innerWidth <= 768
  if (!isMobile.value) drawerOpen.value = false
}

/** 抽屉内跳转：先收起抽屉再跳转，避免动画遮挡与重复路由 */
const goMobile = (path) => {
  drawerOpen.value = false
  router.push(path)
}

// W3-02：菜单配置化（管理员可隐藏「管理/系统」下的菜单项）
const hiddenTop = ref([])
const hiddenItems = ref([])

const loadMenuConfig = async () => {
  if (!isAdmin.value) return
  try {
    const cfg = (await dashboardApi.menuConfig()) || {}
    hiddenTop.value = Array.isArray(cfg.hiddenTop) ? cfg.hiddenTop : []
    hiddenItems.value = Array.isArray(cfg.hiddenItems) ? cfg.hiddenItems : []
  } catch (e) {
    hiddenTop.value = []
    hiddenItems.value = []
  }
}

// W1-01/R6：多标签页状态（单例响应式，label 与跳转共享）
const { addTab, setActive, restore, refreshTick } = useTabs()

// 标签"刷新"时 refreshTick 自增 → key 变化 → 当前页面重建
const viewKey = computed(() => route.path + ':' + refreshTick.value)

// 路由变化：同步激活标签 + 追加/激活标签
watch(
  () => route.path,
  (path) => {
    setActive(path)
    addTab(path, route.name)
    // 移动端：任何路由变化都收起抽屉（含抽屉内点击菜单项）
    drawerOpen.value = false
  }
)

onMounted(() => {
  window.addEventListener('tg:avatar', onAvatarUpdated)
  // 移动端：初始化断点并监听视口变化（回到桌面宽度自动收起抽屉）
  syncViewport()
  window.addEventListener('resize', syncViewport)
  // 个性化增强 BATCH-4：登录后拉取云端偏好（主题/密度/工作台布局）并应用
  applyRemotePreference(() => {
    setTheme(localStorage.getItem('tg_theme') === 'dark' ? 'dark' : 'light')
    setAccent(localStorage.getItem('tg_accent') || 'gold')
    setDensity(localStorage.getItem('tg_density') === 'compact' ? 'compact' : 'cozy')
  })
  const userStr = localStorage.getItem('userInfo')
  if (userStr) {
    userInfo.value = JSON.parse(userStr)
  }
  loadMenuConfig()
  // 还原历史标签（固定标签以常量为准），再同步当前路由
  restore()
  setActive(route.path)
  addTab(route.path, route.name)
})

/** W1-08/O14：顶栏按钮/抽屉唤起命令面板（先收起抽屉，避免层级冲突） */
const openCommand = () => {
  drawerOpen.value = false
  commandRef.value?.open()
}

// ===== 个性化增强 BATCH-3：全局快捷键（G 序列导航 / ? 速查表 / / 命令面板） =====
// 个性化增强 BATCH-5：顶栏自定义头像（Profile 上传后经事件同步）
const avatarUrl = computed(() => userInfo.value?.avatarDataUrl || '')
const onAvatarUpdated = (e) => {
  if (userInfo.value) {
    userInfo.value = { ...userInfo.value, avatarDataUrl: e.detail }
  } else {
    userInfo.value = { avatarDataUrl: e.detail }
  }
}

const { hotkeySheet } = useHotkeys({
  onNavigate: (path) => router.push(path),
  onCommandPalette: openCommand
})
// ⌘K 按钮提示快捷键

/** W2-08/R12：顶栏按钮/抽屉唤起 AI 助手（先收起抽屉再展开助手） */
const openAi = () => {
  drawerOpen.value = false
  aiRef.value?.open()
}

const handleCommand = (cmd) => {
  if (cmd === 'profile') {
    router.push('/profile')
  } else if (cmd === 'lock') {
    lockRef.value?.open()
  } else if (cmd === 'logout') {
    // SEC-16：先通知后端吊销当前 JWT（失败不阻断登出），再清理本地凭证
    authApi.logout().finally(() => {
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      router.push('/home')
    })
  }
}

onBeforeUnmount(() => {
  window.removeEventListener('tg:avatar', onAvatarUpdated)
  window.removeEventListener('resize', syncViewport)
})
</script>

<style scoped>
.app-shell {
  height: 100vh;
  display: flex;
  flex-direction: column;
  /* 多色氛围光斑：深金/蜜糖/奶油/陶土 四向柔和晕染（蜂蜜琥珀暖调） */
  background:
    radial-gradient(1100px 500px at 50% -8%, rgba(143, 107, 34, 0.07), transparent 70%),
    radial-gradient(760px 520px at 6% 40%, rgba(201, 155, 63, 0.06), transparent 65%),
    radial-gradient(820px 560px at 96% 20%, rgba(143, 107, 34, 0.05), transparent 65%),
    radial-gradient(700px 520px at 80% 96%, rgba(168, 185, 138, 0.05), transparent 65%),
    radial-gradient(560px 420px at 20% 92%, rgba(176, 101, 63, 0.04), transparent 65%),
    var(--tg-bg-page);
}

/* ===== 顶部轻量导航：磨砂玻璃，非固定侧栏 ===== */
.topbar {
  height: 64px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
  padding: 0 32px;
  background: rgba(255, 255, 255, 0.66);
  backdrop-filter: blur(24px) saturate(1.4);
  -webkit-backdrop-filter: blur(24px) saturate(1.4);
  border-bottom: 1px solid var(--tg-border);
  position: sticky;
  top: 0;
  z-index: 100;
}

.topbar__left {
  display: flex;
  align-items: center;
  gap: 32px;
  min-width: 0;
}

.topbar__right {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
  text-decoration: none;
  flex-shrink: 0;
}

.logo-icon {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: transform 0.25s var(--tg-ease), box-shadow 0.25s var(--tg-ease);
}

.logo-icon img {
  width: 100%;
  height: 100%;
  display: block;
  filter: drop-shadow(0 2px 8px rgba(143, 107, 34, 0.28));
}

.logo:hover .logo-icon {
  transform: scale(1.05) rotate(-3deg);
  box-shadow: 0 8px 22px rgba(143, 107, 34, 0.3);
}

.logo-text {
  font-size: 17px;
  font-weight: 700;
  color: var(--tg-text-primary);
  letter-spacing: 0.3px;
  white-space: nowrap;
}

.logo-text span {
  background: var(--tg-accent-gradient);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

/* W5：下拉项"规划中"角标（二期模块占位） */
.menu-soon {
  margin-left: 8px;
  font-size: 10px;
  padding: 1px 7px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.05);
  color: var(--tg-text-secondary);
  vertical-align: 1px;
}

/* W5：一级菜单扩到 8 项后的响应式收缩（有效规则见基础样式之后的媒体查询区；
   此处不再重复声明——媒体查询不提升优先级，置于基础规则之前会被同特异性后置规则覆盖而失效） */

/* ===== 顶部菜单：轻量胶囊（无下划线、无重色底） ===== */
.top-menu {
  border-bottom: none;
  background: transparent;
  height: 64px;
  display: flex;
  align-items: center;
  /* 溢出保护：极端缩放下仅裁剪自身，绝不叠加到右侧操作区 */
  min-width: 0;
  overflow: hidden;
}

.top-menu :deep(.el-menu-item),
.top-menu :deep(.el-sub-menu__title) {
  border-bottom: none !important;
  border-radius: var(--tg-radius-pill);
  height: 38px;
  line-height: 38px;
  margin: 0 2px;
  color: var(--tg-text-secondary);
  font-size: 14px;
  transition: background-color 0.2s ease, color 0.2s ease;
}

/* 普通菜单项：等宽留白 */
.top-menu :deep(.el-menu-item) {
  padding: 0 16px;
}

/* 子菜单标题：右侧为下拉小箭头预留更多空间，箭头与文字明显分离 */
.top-menu :deep(.el-sub-menu__title) {
  padding: 0 40px 0 16px;
}

.top-menu :deep(.el-menu-item:hover),
.top-menu :deep(.el-sub-menu__title:hover) {
  background: rgba(0, 0, 0, 0.04);
  color: var(--tg-text-primary);
}

.top-menu :deep(.el-menu-item.is-active) {
  background: var(--el-color-primary-light-9);
  color: var(--tg-accent);
  font-weight: 500;
  border-bottom: none !important;
}

/* 水平子菜单弹出层：恢复普通下拉样式 */
.top-menu :deep(.el-menu--popup) {
  min-width: 180px;
  padding: 6px;
  border: 1px solid var(--tg-border);
  border-radius: 14px;
  box-shadow: var(--tg-shadow-card);
}

.top-menu :deep(.el-menu--popup .el-menu-item) {
  height: 36px;
  line-height: 36px;
  margin: 2px 0;
  padding: 0 14px;
  border-radius: 10px;
  background: transparent;
  color: var(--tg-text-primary);
}

/* ===== 响应式收缩（必须置于基础规则之后：媒体查询不提升优先级） ===== */
@media (max-width: 1560px) {
  .top-menu :deep(.el-menu-item),
  .top-menu :deep(.el-sub-menu__title) {
    padding: 0 11px;
    font-size: 13.5px;
  }

  /* 子菜单标题：箭头为绝对定位（EP 默认 right:20px），收窄 padding 时须同步右移箭头，否则压住文字 */
  .top-menu :deep(.el-sub-menu__title) {
    padding: 0 30px 0 11px;
  }

  .top-menu :deep(.el-sub-menu__icon-arrow) {
    right: 11px;
  }
}

@media (max-width: 1460px) {
  .top-menu :deep(.el-menu-item),
  .top-menu :deep(.el-sub-menu__title) {
    padding: 0 9px;
    font-size: 13px;
  }

  .top-menu :deep(.el-sub-menu__title) {
    padding: 0 26px 0 9px;
  }

  .top-menu :deep(.el-sub-menu__icon-arrow) {
    right: 9px;
  }

  /* 搜索/AI 助手按钮只留图标 */
  .cmd-trigger .cmd-trigger__label {
    display: none;
  }
}

/* 中窄屏（浏览器高倍缩放 / 半屏窗口）：横向菜单收进抽屉，从根源避免与右侧操作区重叠；
   抽屉导航（汉堡 + el-drawer）与本就 ≤768px 的移动端方案同一套，无新增状态 */
@media (max-width: 1280px) {
  .hamburger {
    display: inline-flex;
  }

  .top-menu {
    display: none !important;
  }
}

/* ===== 右上角命令面板入口（W1-08/O14） ===== */
.cmd-trigger {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-right: 14px;
  padding: 6px 12px;
  border: 1px solid var(--tg-border);
  border-radius: var(--tg-radius-pill);
  background: rgba(255, 255, 255, 0.6);
  color: var(--tg-text-secondary);
  font-size: 13px;
  font-family: inherit;
  cursor: pointer;
  transition: border-color 0.2s ease, color 0.2s ease, background 0.2s ease,
    box-shadow 0.25s ease;
}

.cmd-trigger:hover {
  border-color: rgba(143, 107, 34, 0.35);
  color: var(--tg-accent);
  box-shadow: var(--tg-shadow-card);
}

.cmd-trigger__kbd {
  padding: 1px 6px;
  border: 1px solid var(--tg-border);
  border-radius: 6px;
  background: var(--tg-bg-page);
  font-size: 11px;
  font-family: var(--tg-font-mono);
  color: var(--tg-slate);
  line-height: 1.4;
}

/* ===== 右上角用户悬浮操作 ===== */
.user-info {
  display: flex;
  align-items: center;
  gap: 10px;
  cursor: pointer;
  padding: 6px 12px;
  border-radius: var(--tg-radius-pill);
  transition: background-color 0.2s ease;
}

.user-info:hover {
  background: rgba(0, 0, 0, 0.04);
}

.user-info :deep(.el-avatar) {
  background: var(--el-color-primary-light-8);
  color: var(--tg-accent);
  font-weight: 600;
}

.username {
  color: var(--tg-text-primary);
  font-size: 14px;
}

/* ===== 内容区：大留白 + 限宽居中 ===== */
.main-content {
  flex: 1;
  overflow-y: auto;
  width: 100%;
  max-width: 1560px;
  margin: 0 auto;
  padding: 20px var(--tg-space-page-x) 48px;
}

/* =====================================================================
 * 移动端导航适配（汉堡菜单 + 抽屉）
 * 断点与全局令牌一致：≤768px 手机 / ≤480px 小屏手机
 * 桌面（>768px）：汉堡隐藏、横向菜单正常、右侧按钮完整
 * ===================================================================== */

/* 汉堡按钮：桌面不显示（移动端由媒体查询启用） */
.hamburger {
  display: none;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  padding: 0;
  flex-shrink: 0;
  border: 1px solid var(--tg-border);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.6);
  color: var(--tg-text-primary);
  cursor: pointer;
  transition: background 0.2s ease, border-color 0.2s ease, color 0.2s ease;
}

.hamburger:hover {
  border-color: rgba(143, 107, 34, 0.35);
  color: var(--tg-accent);
}

/* ===================== 手机 ≤768px ===================== */
@media (max-width: 768px) {
  .topbar {
    height: 56px;
    padding: 0 12px;
    gap: 8px;
  }

  .topbar__left {
    gap: 10px;
    min-width: 0;
  }

  /* 汉堡入口显示；横向菜单隐藏，导航下沉到抽屉 */
  .hamburger {
    display: inline-flex;
  }

  .top-menu {
    display: none !important;
  }

  /* 右侧：仅保留图标，收缩间距，保证 40px 触控区 */
  .cmd-trigger {
    min-height: 40px;
    margin-right: 8px;
    padding: 8px 10px;
  }

  .cmd-trigger__label,
  .cmd-trigger__kbd {
    display: none;
  }

  /* AI 助手 / 外观设置：小屏下沉到抽屉底部操作区 */
  .cmd-trigger--ai,
  .cmd-trigger--appearance {
    display: none;
  }

  /* 用户区：保留头像，隐藏用户名与箭头 */
  .user-info {
    gap: 4px;
    padding: 4px 6px;
  }

  .user-info .username,
  .user-info > .el-icon {
    display: none;
  }

  .logo-text {
    font-size: 15px;
  }

  .logo-icon {
    width: 30px;
    height: 30px;
  }

  /* 内容区：压缩上下留白，给小屏更多可视高度 */
  .main-content {
    padding-top: 12px;
    padding-bottom: 32px;
  }
}

/* ===================== 小屏手机 ≤480px ===================== */
@media (max-width: 480px) {
  .topbar {
    padding: 0 8px;
  }

  /* 极窄屏：搜索按钮也隐藏（抽屉底部「搜索页面」可达） */
  .cmd-trigger {
    display: none;
  }

  .logo-text {
    display: none;
  }
}

/* ===================== 抽屉内导航 ===================== */
.mobile-menu {
  border-right: none;
  background: transparent;
}

.mobile-menu :deep(.el-menu-item),
.mobile-menu :deep(.el-sub-menu__title) {
  height: 48px;
  line-height: 48px;
  font-size: 15px;
  border-radius: 12px;
  margin: 2px 0;
}

.mobile-menu :deep(.el-menu-item.is-active) {
  background: var(--el-color-primary-light-9);
  color: var(--tg-accent);
  font-weight: 600;
}

.mobile-menu :deep(.el-menu--inline .el-menu-item) {
  height: 44px;
  line-height: 44px;
  font-size: 14px;
  padding-left: 34px !important;
}

/* 抽屉底部：常用操作（顶栏精简后入口下沉于此） */
.mobile-menu__foot {
  margin-top: 18px;
  padding-top: 14px;
  border-top: 1px solid var(--tg-border);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.mobile-menu__act {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  min-height: 46px;
  padding: 0 14px;
  border: 1px solid transparent;
  border-radius: 12px;
  background: transparent;
  color: var(--tg-text-primary);
  font-family: inherit;
  font-size: 15px;
  cursor: pointer;
  transition: background 0.2s ease, color 0.2s ease;
}

.mobile-menu__act:hover {
  background: rgba(0, 0, 0, 0.04);
}

.mobile-menu__act--danger {
  color: var(--tg-danger);
}

.mobile-menu__act--danger:hover {
  background: rgba(194, 94, 76, 0.08);
}
</style>
