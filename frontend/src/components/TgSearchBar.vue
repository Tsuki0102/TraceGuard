<template>
  <div class="tg-searchbar">
    <el-form class="tg-searchbar__form" inline @submit.prevent>
      <div class="tg-searchbar__fields">
        <el-form-item
          v-for="f in visibleFields"
          :key="f.key"
          class="tg-searchbar__item"
        >
          <el-select
            v-if="f.type === 'select'"
            v-model="model[f.key]"
            :placeholder="f.placeholder || '请选择' + f.label"
            :style="{ width: (f.width || 160) + 'px' }"
            clearable
            @change="onFieldChange"
          >
            <el-option v-for="o in f.options" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
          <el-date-picker
            v-else-if="f.type === 'dateRange'"
            v-model="model[f.key]"
            type="datetimerange"
            range-separator="至"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            :style="{ width: (f.width || 340) + 'px' }"
            @change="onFieldChange"
          />
          <el-input
            v-else
            v-model="model[f.key]"
            :placeholder="f.placeholder || '请输入' + f.label"
            :style="{ width: (f.width || 200) + 'px' }"
            clearable
            @keyup.enter="emitSearch"
            @clear="onFieldChange"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
        </el-form-item>
        <el-form-item v-if="fields.length > collapseCount" class="tg-searchbar__item tg-searchbar__toggle">
          <el-button type="primary" link class="tg-searchbar__more" @click="expanded = !expanded">
            {{ expanded ? '收起' : '更多筛选' }}
            <el-icon :class="{ 'is-open': expanded }"><ArrowDown /></el-icon>
          </el-button>
        </el-form-item>
      </div>
      <div class="tg-searchbar__actions">
        <el-button type="primary" round size="default" @click="emitSearch">
          <el-icon style="margin-right: 4px"><Search /></el-icon>查询
        </el-button>
        <el-button round size="default" @click="emitReset">
          <el-icon style="margin-right: 4px"><RefreshLeft /></el-icon>重置
        </el-button>
      </div>
    </el-form>
  </div>
</template>

<script setup>
/**
 * 通用搜索表单（W1-05 / R14）
 * 提供字段渲染 + 收起/展开 + 查询/重置；组件内部维护副本，
 * 搜索时通过事件把查询条件交给页面（后端查询与本地过滤由页面决定）。
 */
import { ref, computed, reactive, watch } from 'vue'

const props = defineProps({
  /** 字段配置：[{ key, label, type: 'input'|'select'|'dateRange', options?, placeholder?, width? }] */
  fields: { type: Array, required: true },
  /** 收起时保留的字段数量（其余进入"更多筛选"折叠区） */
  collapseCount: { type: Number, default: 1 },
  /** 初始查询条件 */
  defaults: { type: Object, default: () => ({}) }
})

const emit = defineEmits(['search', 'reset'])

const expanded = ref(false)
const model = reactive({})

// 由 defaults 初始化/回填模型
watch(
  () => props.defaults,
  (v) => {
    props.fields.forEach((f) => {
      model[f.key] = v && v[f.key] != null ? v[f.key] : ''
    })
  },
  { immediate: true }
)

const visibleFields = computed(() =>
  expanded.value ? props.fields : props.fields.slice(0, props.collapseCount)
)

const onFieldChange = () => emitSearch()

const emitSearch = () => {
  const params = {}
  props.fields.forEach((f) => {
    if (model[f.key] !== '' && model[f.key] != null) params[f.key] = model[f.key]
  })
  emit('search', params)
}

const emitReset = () => {
  props.fields.forEach((f) => {
    model[f.key] = ''
  })
  emit('reset')
}
</script>

<style scoped>
.tg-searchbar {
  margin-bottom: 16px;
}

.tg-searchbar__form {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 16px 18px;
  border-radius: 16px;
  background: var(--tg-gradient-soft);
  border: 1px solid var(--tg-border);
}

.tg-searchbar__fields {
  display: flex;
  align-items: center;
  gap: 4px;
  flex-wrap: wrap;
  min-width: 0;
}

.tg-searchbar__item {
  margin-right: 4px;
  margin-bottom: 0;
}

.tg-searchbar__item :deep(.el-form-item__content) {
  line-height: 1;
}

.tg-searchbar__item :deep(.el-input__wrapper),
.tg-searchbar__item :deep(.el-select__wrapper) {
  border-radius: var(--tg-radius-input);
}

.tg-searchbar__toggle {
  margin-left: 2px;
}

.tg-searchbar__more .el-icon {
  transition: transform 0.25s var(--tg-ease);
  margin-left: 2px;
}

.tg-searchbar__more .el-icon.is-open {
  transform: rotate(180deg);
}

.tg-searchbar__actions {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
</style>