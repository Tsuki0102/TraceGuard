<template>
  <el-dropdown trigger="click" placement="bottom-end" :hide-on-click="false">
    <el-button size="default" round class="col-set-trigger">
      <el-icon style="margin-right: 4px"><Operation /></el-icon>列设置
    </el-button>
    <template #dropdown>
      <div class="col-set">
        <div class="col-set__head">显示列</div>
        <el-checkbox-group :model-value="modelValue" @update:model-value="onChange">
          <el-checkbox v-for="c in columns" :key="c.key" :label="c.key">
            <span class="col-set__label">{{ c.label }}</span>
          </el-checkbox>
        </el-checkbox-group>
      </div>
    </template>
  </el-dropdown>
</template>

<script setup>
/**
 * W3-01 / R13（轻量）：表格列显隐设置
 * columns [{key,label}]，v-model 为可见 key 数组；父级用 v-if 控制列渲染。
 */
defineProps({
  columns: { type: Array, required: true },
  modelValue: { type: Array, required: true }
})

const emit = defineEmits(['update:modelValue'])

const onChange = (v) => {
  emit('update:modelValue', v)
}
</script>

<style scoped>
.col-set-trigger {
  border-color: var(--tg-border);
  color: var(--tg-text-secondary);
  height: 30px;
  font-size: 12.5px;
  padding: 0 12px;
}

.col-set {
  width: 210px;
  padding: 8px 12px 10px;
}

.col-set__head {
  padding: 4px 2px 8px;
  border-bottom: 1px solid var(--tg-border);
  margin-bottom: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--tg-text-secondary);
}

.col-set :deep(.el-checkbox) {
  display: flex;
  height: 28px;
  margin-right: 0;
}

.col-set__label {
  font-size: 13px;
  color: var(--tg-text-primary);
}
</style>