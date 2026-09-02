<template>
  <div class="menu-config-page">
    <!-- W4 v3：封面式彩色光晕（装饰层） -->
    <div class="page-glow" aria-hidden="true"><i></i><i></i><i></i></div>
    <div class="page-header">
      <div>
        <p class="tg-kicker">Menu Permission</p>
        <h2 class="page-header__title">菜单配置</h2>
        <p class="page-header__desc">配置「管理 / 系统」分组下各菜单对管理员的可见性（不影响路由权限，后端仍校验）</p>
      </div>
      <div class="page-header__actions">
        <el-button round @click="resetAll">
          <el-icon style="margin-right: 4px"><RefreshLeft /></el-icon>恢复默认
        </el-button>
        <el-button type="primary" round :loading="saving" @click="save">
          <el-icon style="margin-right: 4px"><Check /></el-icon>保存配置
        </el-button>
      </div>
    </div>

    <el-alert
      class="mc-alert tg-fade-up"
      type="info"
      :closable="false"
      show-icon
      title="说明"
      description="这里的配置仅控制菜单入口的显示/隐藏；对应路由的后端权限校验（requiresAdmin）不受影响，直接访问 URL 仍会被后端拦截，安全模型不变。"
    />

    <div class="cfg-stats tg-fade-up" style="animation-delay: 90ms">
      <div class="cfg-stat cfg-stat--rich" style="animation-delay: 100ms">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--gold"><el-icon :size="17"><Operation /></el-icon></span>
          <span class="cfg-stat__label">菜单分组数</span>
        </div>
        <div class="cfg-stat__num">
          <b v-countup="groupCount">{{ groupCount }}</b>
          <span class="cfg-stat__unit">组</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: (groupCount ? Math.round(visibleGroupCount / groupCount * 100) : 0) + '%' }"></i></span>
          <span class="cfg-stat__ratio">维护中心 · 系统配置</span>
        </div>
      </div>
      <div class="cfg-stat cfg-stat--rich" style="animation-delay: 140ms">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--blue"><el-icon :size="17"><Rank /></el-icon></span>
          <span class="cfg-stat__label">顶栏菜单项数</span>
        </div>
        <div class="cfg-stat__num">
          <b v-countup="visibleGroupCount">{{ visibleGroupCount }}</b>
          <span class="cfg-stat__unit">项</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: (groupCount ? Math.round(visibleGroupCount / groupCount * 100) : 0) + '%' }"></i></span>
          <span class="cfg-stat__ratio">可见分组 {{ visibleGroupCount }}/{{ groupCount }}</span>
        </div>
      </div>
      <div class="cfg-stat cfg-stat--rich" style="animation-delay: 180ms">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--sage"><el-icon :size="17"><Grid /></el-icon></span>
          <span class="cfg-stat__label">业务菜单项数</span>
        </div>
        <div class="cfg-stat__num">
          <b v-countup="totalItems">{{ totalItems }}</b>
          <span class="cfg-stat__unit">项</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: (totalItems ? Math.round(visibleItems / totalItems * 100) : 0) + '%' }"></i></span>
          <span class="cfg-stat__ratio">可见 {{ visibleItems }} · 隐藏 {{ hiddenItemCount }}</span>
        </div>
      </div>
      <div class="cfg-stat cfg-stat--rich" style="animation-delay: 220ms">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--rose"><el-icon :size="17"><Hide /></el-icon></span>
          <span class="cfg-stat__label">隐藏项数</span>
        </div>
        <div class="cfg-stat__num">
          <b v-countup="hiddenItemCount">{{ hiddenItemCount }}</b>
          <span class="cfg-stat__unit">项</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: (totalItems ? Math.round(hiddenItemCount / totalItems * 100) : 0) + '%' }"></i></span>
          <span class="cfg-stat__ratio">{{ hiddenItemCount ? '建议复核' : '全部可见' }}</span>
        </div>
      </div>
    </div>

    <div class="menu-groups">
      <section
        v-for="group in groups"
        :key="group.key"
        class="cfg-section tg-fade-up"
      >
        <div class="cfg-section__head">
          <div class="cfg-section__title">
            <h3>
              <span
                class="cfg-section__ic"
                :class="group.key === 'manage' ? 'cfg-section__ic--blue' : 'cfg-section__ic--violet'"
              ><el-icon :size="16"><component :is="groupIcon(group.key)" /></el-icon></span>
              {{ group.label }}
            </h3>
            <p>{{ group.desc }}</p>
          </div>
          <div class="mc-group-toggle">
            <span class="mc-count">{{ visibleCount(group) }} / {{ group.items.length }}</span>
            <div class="mc-group-toggle__ctrl">
              <span class="mc-group-toggle__label">{{ groupHidden[group.key] ? '整组隐藏' : '整组启用' }}</span>
              <el-switch :model-value="!groupHidden[group.key]" @change="(v) => toggleGroup(group.key, v)" />
            </div>
          </div>
        </div>

        <transition-group
          v-if="!groupHidden[group.key]"
          name="mc-list"
          tag="div"
          class="menu-item-list"
        >
          <div
            v-for="(item, ii) in group.items"
            :key="item.path"
            class="menu-item-row"
            :style="{ '--i': ii }"
          >
            <span class="menu-item-row__ic"><el-icon :size="15"><component :is="resolveIcon(item.icon)" /></el-icon></span>
            <div class="menu-item-row__main">
              <span class="menu-item-row__label">{{ item.label }}</span>
            </div>
            <span class="menu-item-row__path">{{ item.path }}</span>
            <el-switch
              :model-value="!hiddenItems.includes(item.path)"
              @change="(v) => toggleItem(item.path, v)"
            />
          </div>
        </transition-group>
        <div v-else class="mc-group-empty">该分组已整组隐藏 · 展开后可逐项配置</div>
      </section>
    </div>
  </div>
</template>

<script setup>
/**
 * W3-02 / R15（轻量）：菜单配置化
 * 配置项写入 sys_config.menu_config（{hiddenTop:[], hiddenItems:[]}），Layout 据此过滤菜单。
 */
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  Check, RefreshLeft, Operation, Rank, Grid, Hide,
  Coin, Notebook, UserFilled, Platform, MagicStick, Setting, Connection, EditPen, Menu, SetUp
} from '@element-plus/icons-vue'
import { dashboardApi, systemConfigApi } from '@/api'

const CONFIG_KEY = 'menu_config'

const groups = [
  {
    key: 'manage',
    label: '维护中心',
    desc: '系统运维与账号管理',
    items: [
      { path: '/backup', label: '数据备份', icon: 'Coin' },
      { path: '/audit', label: '审计日志', icon: 'Notebook' },
      { path: '/users', label: '用户管理', icon: 'UserFilled' },
      { path: '/system-status', label: '系统状态', icon: 'Platform' }
    ]
  },
  {
    key: 'sys',
    label: '系统配置',
    desc: '引擎与集成配置',
    items: [
      { path: '/llm-config', label: '大模型配置', icon: 'MagicStick' },
      { path: '/system-config', label: '需求解析规则', icon: 'Setting' },
      { path: '/integration-config', label: '集成连通测试', icon: 'Connection' },
      { path: '/data-change-log', label: '数据变更日志', icon: 'EditPen' },
      { path: '/menu-config', label: '菜单配置', icon: 'Menu' }
    ]
  }
]

/** 图标名（字符串）→ 组件：动态对象映射，确保 <component :is> 正确渲染 */
const ICON_MAP = {
  Coin, Notebook, UserFilled, Platform, MagicStick,
  Setting, Connection, EditPen, Menu
}
const resolveIcon = (name) => ICON_MAP[name] || Menu

/** 分组图标（按 key 给色） */
const groupIcon = (key) => (key === 'manage' ? Operation : SetUp)

const hiddenTop = ref([])
const hiddenItems = ref([])
const saving = ref(false)

const groupHidden = reactive({ manage: false, sys: false })

/* ---- 统计（仅用于展示，不触碰数据绑定逻辑） ---- */
const groupCount = computed(() => groups.length)
const totalItems = computed(() => groups.reduce((n, g) => n + g.items.length, 0))
const visibleGroupCount = computed(() => groups.filter((g) => !hiddenTop.value.includes(g.key)).length)
const hiddenItemCount = computed(() => hiddenItems.value.length)
const visibleItems = computed(() => totalItems.value - hiddenItemCount.value)
const visibleCount = (group) =>
  group.items.length - group.items.filter((i) => hiddenItems.value.includes(i.path)).length

const load = async () => {
  try {
    const cfg = (await dashboardApi.menuConfig()) || {}
    hiddenTop.value = Array.isArray(cfg.hiddenTop) ? cfg.hiddenTop : []
    hiddenItems.value = Array.isArray(cfg.hiddenItems) ? cfg.hiddenItems : []
  } catch (e) {
    hiddenTop.value = []
    hiddenItems.value = []
  }
  groups.forEach((g) => {
    groupHidden[g.key] = hiddenTop.value.includes(g.key)
  })
}

const toggleGroup = (key, show) => {
  if (show) {
    hiddenTop.value = hiddenTop.value.filter((k) => k !== key)
  } else {
    if (!hiddenTop.value.includes(key)) hiddenTop.value.push(key)
  }
}

const toggleItem = (path, show) => {
  if (show) {
    hiddenItems.value = hiddenItems.value.filter((p) => p !== path)
  } else {
    if (!hiddenItems.value.includes(path)) hiddenItems.value.push(path)
  }
}

const save = async () => {
  saving.value = true
  try {
    await systemConfigApi.save(
      CONFIG_KEY,
      JSON.stringify({ hiddenTop: hiddenTop.value, hiddenItems: hiddenItems.value }),
      '菜单对管理员可见性配置（仅隐藏入口，不影响后端权限）'
    )
    ElMessage.success('菜单配置已保存，刷新页面生效')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const resetAll = async () => {
  saving.value = true
  try {
    await systemConfigApi.save(CONFIG_KEY, JSON.stringify({ hiddenTop: [], hiddenItems: [] }), '菜单可见性恢复默认（全部显示）')
    hiddenTop.value = []
    hiddenItems.value = []
    groups.forEach((g) => { groupHidden[g.key] = false })
    ElMessage.success('已恢复默认：全部菜单显示')
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.menu-config-page {
  position: relative;
  max-width: 1000px;
}

.mc-alert {
  --el-alert-padding: 14px 18px;
  border-radius: 14px;
  margin-bottom: 22px;
}
.mc-alert :deep(.el-alert__title) {
  font-weight: 600;
  color: var(--tg-text-primary);
}
.mc-alert :deep(.el-alert__description) {
  color: var(--tg-text-secondary);
  line-height: 1.65;
}

.menu-groups {
  display: flex;
  flex-direction: column;
  gap: 22px;
}

/* ---- 组头右侧：计数 + 整组开关 ---- */
.mc-group-toggle {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-shrink: 0;
}
.mc-count {
  display: inline-flex;
  align-items: center;
  padding: 3px 12px;
  border-radius: 999px;
  background: var(--tg-gradient-soft);
  border: 1px solid rgba(201, 155, 63, 0.2);
  color: var(--tg-accent);
  font-size: 12.5px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}
.mc-group-toggle__ctrl {
  display: flex;
  align-items: center;
  gap: 10px;
}
.mc-group-toggle__label {
  font-size: 12.5px;
  color: var(--tg-text-secondary);
}

/* ---- 组内子项行列表 ---- */
.menu-item-list {
  position: relative;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.menu-item-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 11px 14px;
  border-radius: 14px;
  border: 1px solid transparent;
  transition: transform 0.3s var(--tg-ease), box-shadow 0.3s ease,
    border-color 0.3s ease, background 0.3s ease;
}
.menu-item-row:hover {
  transform: translateY(-2px);
  background: linear-gradient(90deg, rgba(143, 107, 34, 0.06), rgba(232, 200, 119, 0.02) 70%, transparent);
  border-color: rgba(201, 155, 63, 0.28);
  box-shadow: 0 8px 22px rgba(60, 45, 25, 0.08), 0 0 0 1px rgba(201, 155, 63, 0.08);
}

.menu-item-row__ic {
  width: 34px;
  height: 34px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 11px;
  background: var(--el-color-primary-light-9);
  color: var(--tg-accent);
  transition: transform 0.3s var(--tg-ease-spring), background 0.3s ease, box-shadow 0.3s ease;
}
.menu-item-row:hover .menu-item-row__ic {
  transform: scale(1.1) rotate(-5deg);
  background: var(--el-color-primary-light-8);
  box-shadow: var(--tg-glow-accent);
}

.menu-item-row__main {
  flex: 1;
  min-width: 0;
  line-height: 1.3;
}
.menu-item-row__label {
  font-size: 14px;
  font-weight: 600;
  color: var(--tg-text-primary);
}
.menu-item-row__path {
  font-size: 11.5px;
  color: var(--tg-slate);
  font-family: var(--tg-font-mono);
  letter-spacing: 0.01em;
  white-space: nowrap;
}

.mc-group-empty {
  padding: 22px;
  text-align: center;
  border-radius: 14px;
  background: var(--tg-gradient-soft);
  border: 1px dashed rgba(201, 155, 63, 0.32);
  color: var(--tg-text-secondary);
  font-size: 12.5px;
}

/* ---- 切组收起/展开：行列表过渡 ---- */
.mc-list-enter-active,
.mc-list-leave-active {
  transition: all 0.4s var(--tg-ease);
}
.mc-list-enter-active {
  transition-delay: calc(var(--i, 0) * 32ms);
}
.mc-list-enter-from,
.mc-list-leave-to {
  opacity: 0;
  transform: translateY(-8px) scale(0.97);
}
.mc-list-leave-active {
  position: absolute;
  width: 100%;
  left: 0;
}
</style>