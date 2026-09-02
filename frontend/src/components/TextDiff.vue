<template>
  <div class="text-diff">
    <div class="text-diff__col">
      <div class="text-diff__head">需求原文</div>
      <div class="text-diff__body">
        <div
          v-for="(l, i) in leftLines"
          :key="'l' + i"
          class="diff-line"
          :class="l.type"
        ><span class="diff-line__no">{{ i + 1 }}</span><span class="diff-line__text">{{ l.text || ' ' }}</span></div>
      </div>
    </div>
    <div class="text-diff__col">
      <div class="text-diff__head">形式化规约</div>
      <div class="text-diff__body">
        <div
          v-for="(l, i) in rightLines"
          :key="'r' + i"
          class="diff-line"
          :class="l.type"
        ><span class="diff-line__no">{{ i + 1 }}</span><span class="diff-line__text">{{ l.text || ' ' }}</span></div>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * W3-07/08（轻量）：需求-规约双栏 diff
 * 行级最长公共子序列（LCS）：左侧红色=需求独有的表述；右侧绿色=规约新增/差异化的承诺；灰色=两者一致。
 */
import { computed } from 'vue'

const props = defineProps({
  left: { type: String, default: '' },
  right: { type: String, default: '' },
  /** 单栏行数上限，超过则不再对齐，仅按行标记共同/独有 */
  maxLines: { type: Number, default: 400 }
})

const splitLines = (t) => String(t || '').split('\n')

/** 简化 LCS：对齐相同行，标记 removed(左独有) / added(右独有) / same */
function diffPair(a, b) {
  const n = a.length
  const m = b.length
  // 行数超限：降级为按行原样标记（提升可读性，不做对齐）
  if (n > 4000 || m > 4000) {
    const left = a.map((t) => ({ text: t, type: 'removed' }))
    const right = b.map((t) => ({ text: t, type: 'added' }))
    return { left, right }
  }
  // DP 求 LCS 长度表
  const dp = Array.from({ length: n + 1 }, () => new Int32Array(m + 1))
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = a[i] === b[j] ? dp[i + 1][j + 1] + 1 : Math.max(dp[i + 1][j], dp[i][j + 1])
    }
  }
  const left = []
  const right = []
  let i = 0
  let j = 0
  while (i < n && j < m) {
    if (a[i] === b[j]) {
      left.push({ text: a[i], type: 'same' })
      right.push({ text: b[j], type: 'same' })
      i++
      j++
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      left.push({ text: a[i], type: 'removed' })
      i++
    } else {
      right.push({ text: b[j], type: 'added' })
      j++
    }
  }
  while (i < n) {
    left.push({ text: a[i], type: 'removed' })
    i++
  }
  while (j < m) {
    right.push({ text: b[j], type: 'added' })
    j++
  }
  return { left, right }
}

const computedDiff = computed(() => diffPair(splitLines(props.left), splitLines(props.right)))
const leftLines = computed(() => computedDiff.value.left)
const rightLines = computed(() => computedDiff.value.right)
</script>

<style scoped>
.text-diff {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
  min-height: 0;
}

.text-diff__col {
  min-width: 0;
  border: 1px solid var(--tg-border);
  border-radius: 12px;
  overflow: hidden;
  display: flex;
  flex-direction: column;
  max-height: 460px;
}

.text-diff__head {
  padding: 8px 14px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--tg-accent);
  background: var(--el-color-primary-light-9);
  border-bottom: 1px solid var(--tg-border);
  flex-shrink: 0;
}

.text-diff__body {
  overflow-y: auto;
  flex: 1;
  font-family: var(--tg-font-mono);
  font-size: 12px;
  line-height: 1.6;
}

.diff-line {
  display: flex;
  gap: 0;
  min-height: 19px;
}

.diff-line__no {
  width: 34px;
  flex-shrink: 0;
  color: var(--tg-slate);
  text-align: right;
  padding-right: 8px;
  user-select: none;
  background: rgba(0, 0, 0, 0.025);
}

.diff-line__text {
  padding: 0 10px;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--tg-text-primary);
  min-width: 0;
}

.diff-line.same .diff-line__text {
  color: var(--tg-text-secondary);
}

.diff-line.removed {
  background: rgba(194, 94, 76, 0.09);
}

.diff-line.removed .diff-line__text {
  color: var(--tg-danger);
  text-decoration: line-through;
  text-decoration-color: rgba(194, 94, 76, 0.4);
}

.diff-line.added {
  background: rgba(154, 156, 107, 0.12);
}

.diff-line.added .diff-line__text {
  color: var(--tg-success);
}

@keyframes diff-flash {
  from { background: rgba(217, 169, 102, 0.25); }
  to { background: transparent; }
}
</style>