<template>
  <div class="tabs-nav" role="tablist" aria-label="多标签页导航">
    <el-dropdown
      v-for="tab in tabs"
      :key="tab.path"
      trigger="contextmenu"
      :hide-on-click="true"
      @command="(cmd) => onCommand(cmd, tab)"
    >
      <button
        type="button"
        role="tab"
        class="tabs-nav__item"
        :class="{ 'is-active': activePath === tab.path }"
        :aria-selected="activePath === tab.path"
        @click="go(tab.path)"
      >
        <span v-if="tab.fixed" class="tabs-nav__dot" aria-hidden="true"></span>
        <span class="tabs-nav__title">{{ tab.title }}</span>
        <span
          v-if="!tab.fixed"
          class="tabs-nav__close"
          :class="{ 'is-active': activePath === tab.path }"
          aria-label="关闭标签"
          @click.stop="close(tab)"
        >
          <el-icon :size="10"><Close /></el-icon>
        </span>
      </button>
      <template #dropdown>
        <el-dropdown-menu class="tabs-nav__menu">
          <el-dropdown-item command="refresh" :icon="Refresh">刷新</el-dropdown-item>
          <el-dropdown-item
            v-if="!tab.fixed"
            command="close"
            :icon="Close"
            divided
          >
            关闭
          </el-dropdown-item>
          <el-dropdown-item command="closeOthers" :icon="CircleClose" divided>
            关闭其他
          </el-dropdown-item>
          <el-dropdown-item command="closeAll" :icon="FolderDelete">
            关闭全部
          </el-dropdown-item>
        </el-dropdown-menu>
      </template>
    </el-dropdown>
  </div>
</template>

<script setup>
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Close, CircleClose, FolderDelete, Refresh } from '@element-plus/icons-vue'
import { useTabs } from '@/composables/useTabs'

const router = useRouter()
const { tabs, activePath, removeTab, closeOthers, closeAll, bumpRefresh } = useTabs()

const go = (path) => {
  if (activePath.value === path) return
  router.push(path)
}

const close = (tab) => {
  const next = removeTab(tab.path)
  if (next && activePath.value === tab.path) {
    router.push(next)
  }
}

const onCommand = (cmd, tab) => {
  switch (cmd) {
    case 'refresh': {
      // 刷新非激活标签：先切过去再重建（激活标签直接重建）
      if (activePath.value !== tab.path) router.push(tab.path)
      setTimeout(() => bumpRefresh(), 60)
      break
    }
    case 'close':
      if (tab.fixed) {
        ElMessage.info('常驻标签不可关闭')
      } else {
        close(tab)
      }
      break
    case 'closeOthers': {
      closeOthers(tab.path)
      if (activePath.value !== tab.path) router.push(tab.path)
      break
    }
    case 'closeAll': {
      const next = closeAll()
      router.push(next)
      break
    }
    default:
      break
  }
}
</script>

<style scoped>
.tabs-nav {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px var(--tg-space-page-x) 2px;
  overflow-x: auto;
  scrollbar-width: none;
  -ms-overflow-style: none;
  flex-shrink: 0;
}

.tabs-nav::-webkit-scrollbar {
  display: none;
}

.tabs-nav :deep(.el-dropdown) {
  display: inline-flex;
  flex-shrink: 0;
}

.tabs-nav__item {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 220px;
  padding: 6px 15px;
  border: 1px solid var(--tg-border);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.55);
  color: var(--tg-text-primary);
  font-size: 13px;
  font-family: inherit;
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.25s ease, color 0.25s ease, border-color 0.25s ease,
    box-shadow 0.25s ease, transform 0.25s var(--tg-ease);
}

.tabs-nav__item:hover {
  background: rgba(255, 255, 255, 0.9);
  border-color: rgba(143, 107, 34, 0.25);
  box-shadow: 0 4px 12px rgba(60, 45, 25, 0.08);
}

.tabs-nav__item:focus-visible {
  outline: 2px solid var(--tg-accent);
  outline-offset: 2px;
}

.tabs-nav__item.is-active {
  background: var(--tg-accent-gradient);
  border-color: transparent;
  color: #fff;
  font-weight: 500;
  box-shadow: var(--tg-glow-accent);
}

.tabs-nav__dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--tg-indigo);
  box-shadow: 0 0 0 3px rgba(201, 155, 63, 0.16);
  flex-shrink: 0;
}

.tabs-nav__title {
  overflow: hidden;
  text-overflow: ellipsis;
}

.tabs-nav__close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  color: var(--tg-text-primary);
  background: rgba(0, 0, 0, 0.12);
  opacity: 0.85;
  flex-shrink: 0;
  transition: opacity 0.2s ease, background 0.2s ease, color 0.2s ease, transform 0.2s ease;
}

.tabs-nav__item:hover .tabs-nav__close {
  opacity: 1;
  background: rgba(0, 0, 0, 0.18);
}

.tabs-nav__close:hover {
  background: rgba(194, 94, 76, 0.2);
  color: var(--tg-danger);
  transform: scale(1.15);
}

.tabs-nav__close.is-active {
  color: #fff;
  background: rgba(255, 255, 255, 0.28);
  opacity: 1;
}

.tabs-nav__close.is-active:hover {
  background: rgba(255, 255, 255, 0.5);
  color: #fff;
}

/* 暗色主题：非激活关闭按钮改用亮底高对比 */
:root[data-theme='dark'] .tabs-nav__close {
  color: #fff;
  background: rgba(255, 255, 255, 0.16);
}

:root[data-theme='dark'] .tabs-nav__item:hover .tabs-nav__close {
  background: rgba(255, 255, 255, 0.26);
}

:root[data-theme='dark'] .tabs-nav__close:hover {
  background: rgba(217, 122, 102, 0.35);
  color: #fff;
}

.tabs-nav__menu :deep(.el-dropdown-menu__item) {
  font-size: 13px;
}
</style>