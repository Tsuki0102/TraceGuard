<template>
  <div class="page al-page">
    <!-- IDE 工具栏 -->
    <header class="al-toolbar">
      <div class="al-toolbar__left">
        <span class="al-mark">SPEC WORKBENCH</span>
        <el-select v-model="projectId" placeholder="选择项目" style="width: 220px" @change="loadSpecs">
          <el-option v-for="p in analyzedProjects" :key="p.id" :label="p.projectName" :value="p.id" />
        </el-select>
      </div>
      <div class="al-toolbar__stats">
        <span class="al-stat al-stat--pass"><i></i>通过 {{ stats.passed }}</span>
        <span class="al-stat al-stat--fail"><i></i>未过 {{ stats.failed }}</span>
        <span class="al-stat al-stat--unknown"><i></i>未校验 {{ stats.unknown }}</span>
      </div>
    </header>

    <!-- 三栏主体：规约列表 / 代码编辑器 / 元数据与校验 -->
    <div class="al-ide" v-loading="loading">
      <!-- 左：规约列表 -->
      <aside class="al-list">
        <button v-for="s in specs" :key="s.id" type="button" class="al-item"
          :class="{ on: current && current.id === s.id }" @click="selectSpec(s)">
          <i class="al-item__dot" :class="dotClass(s.verificationStatus)" aria-hidden="true"></i>
          <span class="al-item__body">
            <span class="al-item__text">{{ s.requirementText || '（无需求摘要）' }}</span>
            <code class="al-item__id">{{ s.specId }}</code>
          </span>
        </button>
        <p v-if="!loading && !specs.length" class="al-list__empty">该项目暂无形式化规约<br /><small>执行一次分析后自动生成</small></p>
      </aside>

      <!-- 中：代码编辑器 -->
      <section class="al-editor">
        <header class="al-editor__bar">
          <span class="al-editor__file"><i aria-hidden="true">◈</i> {{ current ? current.specId + '.als' : '未选择规约' }}</span>
          <div class="al-editor__actions">
            <el-button size="small" round :disabled="!current || saving" :loading="saving" @click="save">
              保存并校验
            </el-button>
          </div>
        </header>
        <div class="al-editor__canvas">
          <div ref="gutterRef" class="al-editor__gutter" aria-hidden="true"><span v-for="n in lineCount" :key="n">{{ n }}</span></div>
          <textarea ref="codeRef" class="al-editor__code" spellcheck="false"
            :value="code" :disabled="!current"
            @input="code = $event.target.value; syncGutter()"
            @scroll="syncScroll" />
        </div>
      </section>

      <!-- 右：元数据 + 在线试算 -->
      <aside class="al-meta">
        <template v-if="current">
          <div class="al-meta__block">
            <h3>需求原文</h3>
            <p class="al-meta__req">{{ current.requirementText || '（无）' }}</p>
          </div>
          <div class="al-meta__block">
            <h3>档案校验状态</h3>
            <span class="al-status" :class="dotClass(current.verificationStatus)">
              {{ current.verificationStatus || '未校验' }}
            </span>
          </div>
          <div class="al-meta__block">
            <div class="al-meta__verifyhead">
              <h3>在线试算</h3>
              <el-button size="small" round :loading="verifying" @click="verify">运行求解器</el-button>
            </div>
            <p class="al-meta__hint">真实 Alloy 求解（不写入档案状态）</p>
            <div v-if="verifyResult" class="al-verify">
              <div class="al-verify__row"><span>引擎</span><b>{{ verifyResult.engine }}</b></div>
              <div class="al-verify__row"><span>状态</span><b :class="verifyResult.passed ? 'is-ok' : 'is-bad'">{{ verifyResult.status }}</b></div>
              <div class="al-verify__row"><span>耗时</span><b>{{ verifyResult.elapsedMs }} ms</b></div>
              <div v-if="verifyResult.instanceCount" class="al-verify__row"><span>实例数</span><b>{{ verifyResult.instanceCount }}</b></div>
              <p v-if="verifyResult.message" class="al-verify__msg">{{ verifyResult.message }}</p>
              <pre v-if="verifyResult.counterexample" class="tg-pre al-verify__ce">{{ verifyResult.counterexample }}</pre>
            </div>
          </div>
        </template>
        <div v-else class="al-meta__placeholder">从左侧选择一条规约</div>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, nextTick } from 'vue'
import { ElMessage } from 'element-plus'
import { insightApi, projectApi } from '@/api'

const loading = ref(false)
const saving = ref(false)
const verifying = ref(false)
const projects = ref([])
const projectId = ref(null)
const specs = ref([])
const stats = ref({ passed: 0, failed: 0, unknown: 0 })
const current = ref(null)
const code = ref('')
const verifyResult = ref(null)
const gutterRef = ref(null)
const codeRef = ref(null)

const analyzedProjects = computed(() => projects.value.filter(p => p.status === 'analyzed'))
const lineCount = computed(() => Math.max(code.value.split('\n').length, 1))

const dotClass = (st) => {
  const s = String(st || '').toLowerCase()
  if (s.includes('pass') || (s.includes('sat') && !s.includes('unsat'))) return 'is-pass'
  if (s.includes('fail') || s.includes('unsat')) return 'is-fail'
  return 'is-unknown'
}

const selectSpec = (s) => {
  current.value = s
  code.value = s.alloyCode || ''
  verifyResult.value = null
  nextTick(syncGutter)
}

const syncGutter = () => {
  if (gutterRef.value) gutterRef.value.scrollTop = codeRef.value ? codeRef.value.scrollTop : 0
}
const syncScroll = () => {
  if (gutterRef.value && codeRef.value) gutterRef.value.scrollTop = codeRef.value.scrollTop
}

const loadSpecs = async () => {
  if (!projectId.value) return
  loading.value = true
  try {
    const data = await insightApi.alloySpecs(projectId.value, 60)
    specs.value = data.specs || []
    stats.value = { passed: data.passed || 0, failed: data.failed || 0, unknown: data.unknown || 0 }
    current.value = specs.value.length ? specs.value[0] : null
    code.value = current.value ? current.value.alloyCode || '' : ''
    verifyResult.value = null
    nextTick(syncGutter)
  } catch (e) {
    ElMessage.error(e?.message || '加载规约失败')
  } finally {
    loading.value = false
  }
}

const save = async () => {
  if (!current.value) return
  saving.value = true
  try {
    const updated = await insightApi.saveAlloySpec(current.value.id, code.value)
    current.value.alloyCode = code.value
    current.value.verificationStatus = updated.verificationStatus
    ElMessage.success('已保存（后端已通过规约校验）')
    await loadSpecs()
  } catch (e) {
    ElMessage.error(e?.message || '保存失败：规约未通过校验')
  } finally {
    saving.value = false
  }
}

const verify = async () => {
  if (!current.value) return
  verifying.value = true
  try {
    verifyResult.value = await insightApi.verifyAlloy(current.value.id)
  } catch (e) {
    ElMessage.error(e?.message || '试算失败')
  } finally {
    verifying.value = false
  }
}

onMounted(async () => {
  try {
    projects.value = await projectApi.list() || []
    if (analyzedProjects.value.length) {
      projectId.value = analyzedProjects.value[0].id
      await loadSpecs()
    }
  } catch (e) {
    ElMessage.error(e?.message || '加载项目失败')
  }
})
</script>

<style scoped>
/* ===== 三栏 IDE：规约列表 / 行号编辑器 / 元数据面板（全站唯一 IDE 版式） ===== */
.al-page { display: flex; flex-direction: column; gap: 14px; height: calc(100vh - 170px); }

.al-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 14px; flex-wrap: wrap; }
.al-toolbar__left { display: flex; align-items: center; gap: 14px; }
.al-mark { font-family: var(--tg-font-mono); font-size: 10.5px; letter-spacing: 0.26em; color: var(--tg-slate); }
.al-toolbar__stats { display: flex; gap: 10px; }
.al-stat { display: inline-flex; align-items: center; gap: 6px; font-size: 12px; color: var(--tg-text-secondary); }
.al-stat i { width: 8px; height: 8px; border-radius: 50%; }
.al-stat--pass i { background: var(--tg-success); }
.al-stat--fail i { background: var(--tg-danger); }
.al-stat--unknown i { background: var(--tg-slate); }

.al-ide { flex: 1; min-height: 0; display: grid; grid-template-columns: 250px 1fr 272px; gap: 12px; }

/* 左：规约列表 */
.al-list {
  overflow-y: auto; display: flex; flex-direction: column; gap: 6px; padding: 10px;
  border-radius: 14px; background: rgba(255, 255, 255, 0.6); border: 1px solid var(--tg-border);
}
.al-item {
  display: flex; gap: 9px; align-items: flex-start; text-align: left; cursor: pointer;
  border: 1px solid transparent; background: transparent; border-radius: 10px; padding: 9px 10px;
  transition: background 0.2s ease, border-color 0.2s ease;
}
.al-item:hover { background: rgba(201, 155, 63, 0.08); }
.al-item.on { background: rgba(201, 155, 63, 0.13); border-color: rgba(201, 155, 63, 0.35); }
.al-item__dot { width: 8px; height: 8px; border-radius: 50%; margin-top: 5px; flex-shrink: 0; }
.al-item__dot.is-pass { background: var(--tg-success); }
.al-item__dot.is-fail { background: var(--tg-danger); }
.al-item__dot.is-unknown { background: var(--tg-slate); }
.al-item__body { min-width: 0; display: flex; flex-direction: column; gap: 2px; }
.al-item__text { font-size: 12px; line-height: 1.45; color: var(--tg-text-primary); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.al-item__id { font-family: var(--tg-font-mono); font-size: 10px; color: var(--tg-slate); }
.al-list__empty { margin: 26px 0; text-align: center; font-size: 12.5px; color: var(--tg-text-secondary); line-height: 1.8; }
.al-list__empty small { font-size: 11px; color: var(--tg-slate); }

/* 中：编辑器（暖米纸感浅色 IDE） */
.al-editor {
  display: flex; flex-direction: column; min-width: 0; border-radius: 14px; overflow: hidden;
  background: linear-gradient(175deg, #F1E8D6, #E6D9BE);
  border: 1px solid rgba(143, 107, 34, 0.28);
  box-shadow: var(--tg-shadow-card);
}
.al-editor__bar {
  display: flex; align-items: center; justify-content: space-between; gap: 10px;
  padding: 9px 14px; background: rgba(143, 107, 34, 0.14); border-bottom: 1px solid rgba(143, 107, 34, 0.22);
}
.al-editor__file { font-family: var(--tg-font-mono); font-size: 12px; color: #8F6B22; display: inline-flex; align-items: center; gap: 7px; }
.al-editor__canvas { flex: 1; display: flex; min-height: 0; }
.al-editor__gutter {
  width: 44px; flex-shrink: 0; padding: 12px 8px 12px 0; overflow: hidden;
  font-family: var(--tg-font-mono); font-size: 12px; line-height: 1.65; text-align: right;
  color: rgba(60, 45, 25, 0.38); background: rgba(143, 107, 34, 0.1);
  display: flex; flex-direction: column; user-select: none;
}
.al-editor__code {
  flex: 1; border: none; outline: none; resize: none; padding: 12px 16px;
  background: transparent; color: #3C3428;
  font-family: var(--tg-font-mono); font-size: 12px; line-height: 1.65;
  white-space: pre; overflow: auto;
}
.al-editor__code:disabled { opacity: 0.4; }

/* 右：元数据面板 */
.al-meta {
  overflow-y: auto; display: flex; flex-direction: column; gap: 14px; padding: 14px;
  border-radius: 14px; background: rgba(255, 255, 255, 0.6); border: 1px solid var(--tg-border);
}
.al-meta__block h3 { margin: 0 0 7px; font-size: 11px; font-weight: 600; letter-spacing: 0.1em; color: var(--tg-slate); }
.al-meta__req { margin: 0; font-size: 12px; line-height: 1.6; color: var(--tg-text-primary); background: var(--tg-bg-code); border-radius: 9px; padding: 9px 11px; }
.al-status {
  display: inline-flex; align-items: center; gap: 6px; font-size: 12px; font-weight: 500;
  padding: 3px 12px; border-radius: 999px; background: rgba(0, 0, 0, 0.05); color: var(--tg-text-secondary);
}
.al-status::before { content: ''; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.al-status.is-pass { background: rgba(107, 142, 78, 0.13); color: #55682e; }
.al-status.is-fail { background: rgba(194, 94, 76, 0.12); color: #9a3f30; }
.al-meta__verifyhead { display: flex; align-items: center; justify-content: space-between; margin-bottom: 4px; }
.al-meta__verifyhead h3 { margin: 0; }
.al-meta__hint { margin: 0 0 8px; font-size: 10.5px; color: var(--tg-slate); }
.al-verify { display: flex; flex-direction: column; gap: 6px; }
.al-verify__row { display: flex; justify-content: space-between; font-size: 12px; color: var(--tg-text-secondary); }
.al-verify__row b { color: var(--tg-text-primary); font-variant-numeric: tabular-nums; }
.al-verify__row b.is-ok { color: var(--tg-success); }
.al-verify__row b.is-bad { color: var(--tg-danger); }
.al-verify__msg { margin: 2px 0 0; font-size: 11.5px; line-height: 1.6; color: var(--tg-text-secondary); }
.al-verify__ce { max-height: 180px; font-size: 10.5px; }
.al-meta__placeholder { margin: auto; font-size: 12.5px; color: var(--tg-slate); }

@media (max-width: 1100px) {
  .al-page { height: auto; }
  .al-ide { grid-template-columns: 1fr; }
  .al-editor__canvas { min-height: 320px; }
}
</style>
