<template>
  <div class="system-config-page">
    <!-- W4 v3：封面式彩色光晕（装饰层） -->
    <div class="page-glow" aria-hidden="true"><i></i><i></i><i></i></div>

    <!-- ===== 页头：渐变标题 + 操作按钮 ===== -->
    <div class="page-header tg-fade-up">
      <div>
        <p class="tg-kicker">Rule Engine</p>
        <h2 class="page-header__title">系统配置</h2>
        <p class="page-header__desc">配置需求解析规则（歧义/模糊词、互斥词对）与代码解析范围，保存后立即热生效</p>
      </div>
      <div class="page-header__actions">
        <el-button :loading="saving" type="primary" round @click="handleSave">
          <el-icon><Check /></el-icon> 保存规则
        </el-button>
        <el-button round @click="handleReset">
          <el-icon><RefreshLeft /></el-icon> 恢复默认
        </el-button>
      </div>
    </div>

    <!-- ===== 提示信息 ===== -->
    <el-alert
      type="info"
      :closable="false"
      show-icon
      class="cfg-tip"
      title="需求解析规则 FR-REQ-002 + 代码解析范围 FR-CODE-001"
      description="上方配置歧义/模糊词清单与互斥词对；下方配置代码解析范围（按包/类/方法过滤）；保存后立即热生效，新建分析任务将使用最新规则。仅管理员可修改。"
    />

    <!-- ===== 规则资产概览：富信息统计条（W4 v5） ===== -->
    <div class="cfg-stats">
      <div class="cfg-stat cfg-stat--rich tg-fade-up">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--gold"><el-icon :size="17"><MagicStick /></el-icon></span>
          <span class="cfg-stat__label">歧义 / 模糊词</span>
        </div>
        <div class="cfg-stat__num">
          <b>{{ ambiguityKeywords.length }}</b>
          <span class="cfg-stat__unit">个词</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: kwLenShortRatio + '%' }"></i></span>
          <span class="cfg-stat__ratio">{{ kwLenText }}</span>
        </div>
      </div>
      <div class="cfg-stat cfg-stat--rich tg-fade-up" style="animation-delay: 60ms">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--rose"><el-icon :size="17"><Switch /></el-icon></span>
          <span class="cfg-stat__label">互斥词对</span>
        </div>
        <div class="cfg-stat__num">
          <b>{{ contradictionPairs.length }}</b>
          <span class="cfg-stat__unit">对</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: (contradictionPairs.length ? 100 : 0) + '%' }"></i></span>
          <span class="cfg-stat__ratio">{{ contradictionPairs.length ? '待人工复核' : '暂无词对' }}</span>
        </div>
      </div>
      <div class="cfg-stat cfg-stat--rich tg-fade-up" style="animation-delay: 120ms">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--sage"><el-icon :size="17"><Aim /></el-icon></span>
          <span class="cfg-stat__label">解析范围分组</span>
        </div>
        <div class="cfg-stat__num">
          <b>{{ scopeGroups.length }}</b>
          <span class="cfg-stat__unit">组</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: (scopeGroups.length ? Math.round(scopeConfiguredGroups / scopeGroups.length * 100) : 0) + '%' }"></i></span>
          <span class="cfg-stat__ratio">共 {{ scopeTotalRules }} 条规则</span>
        </div>
      </div>
      <div class="cfg-stat cfg-stat--rich tg-fade-up" style="animation-delay: 180ms">
        <div class="cfg-stat__top">
          <span class="cfg-stat__tile tile--violet"><el-icon :size="17"><DataBoard /></el-icon></span>
          <span class="cfg-stat__label">质量门槛指标</span>
        </div>
        <div class="cfg-stat__num">
          <b>3</b>
          <span class="cfg-stat__unit">项</span>
        </div>
        <div class="cfg-stat__foot">
          <span class="cfg-stat__bar"><i :style="{ width: gateForm.coverage + '%' }"></i></span>
          <span class="cfg-stat__ratio">覆盖 {{ gateForm.coverage }}% · 一致 {{ gateForm.consistency }}%</span>
        </div>
      </div>
    </div>

    <!-- ===== 规则资产可视化：信息可视化（W4 v4） ===== -->
    <section class="cfg-section tg-fade-up">
      <div class="cfg-section__head">
        <div class="cfg-section__title">
          <h3><span class="cfg-section__ic cfg-section__ic--violet"><el-icon :size="15"><TrendCharts /></el-icon></span>规则资产可视化</h3>
          <p>质量门槛 · 解析范围 · 词库构成，实时可视化</p>
        </div>
      </div>
      <div class="cfg-viz">
        <div class="cfg-viz__card">
          <div class="cfg-viz__head"><span class="cfg-viz__dot dot--rose"></span><b>质量门槛雷达</b></div>
          <p class="cfg-viz__sub">覆盖率 / 一致率 / 严重缺陷容忍度（阈值越高越严格）</p>
          <div ref="gateRadarEl" class="cfg-viz__chart"></div>
        </div>
        <div class="cfg-viz__card">
          <div class="cfg-viz__head"><span class="cfg-viz__dot dot--sage"></span><b>解析范围构成</b></div>
          <p class="cfg-viz__sub">6 类过滤规则的数量分布</p>
          <div ref="scopeDonutEl" class="cfg-viz__chart"></div>
        </div>
        <div class="cfg-viz__card">
          <div class="cfg-viz__head"><span class="cfg-viz__dot dot--gold"></span><b>歧义词长度分布</b></div>
          <p class="cfg-viz__sub">按字数统计歧义 / 模糊词</p>
          <div ref="kwLenEl" class="cfg-viz__chart"></div>
        </div>
      </div>
    </section>

    <!-- ===== 歧义 / 模糊词库 ===== -->
    <section class="cfg-section tg-fade-up">
      <div class="cfg-section__head">
        <div class="cfg-section__title">
          <h3><span class="cfg-section__ic"><el-icon :size="15"><MagicStick /></el-icon></span>歧义 / 模糊词</h3>
          <p>命中出现即建议明确化</p>
        </div>
      </div>
      <div class="kw-chips">
        <transition-group name="tg-chip">
          <span v-for="(kw, idx) in ambiguityKeywords" :key="'amb-' + kw + idx" class="kw-chip">
            {{ kw }}
            <button type="button" class="kw-chip__del" :aria-label="'移除 ' + kw" @click="removeAmbiguity(idx)">×</button>
          </span>
        </transition-group>
      </div>
      <div class="kw-add">
        <el-input
          v-model="ambInput"
          placeholder="输入后回车添加，如：等 / 适当 / 方便"
          clearable
          @keyup.enter="addAmbiguity"
        >
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" round plain @click="addAmbiguity">
          <el-icon style="margin-right: 4px"><Plus /></el-icon>添加
        </el-button>
      </div>
    </section>

    <!-- ===== 互斥词对 ===== -->
    <section class="cfg-section tg-fade-up">
      <div class="cfg-section__head">
        <div class="cfg-section__title">
          <h3><span class="cfg-section__ic cfg-section__ic--rose"><el-icon :size="15"><Switch /></el-icon></span>互斥词对</h3>
          <p>同一需求内同时出现提示潜在矛盾，须人工复核</p>
        </div>
        <el-button round plain @click="addPair">
          <el-icon style="margin-right: 4px"><Plus /></el-icon>新增词对
        </el-button>
      </div>
      <div class="pair-list">
        <transition-group name="tg-pair">
          <div v-for="(pair, idx) in contradictionPairs" :key="idx" class="pair-row">
            <span class="pair-row__badge">{{ String(idx + 1).padStart(2, '0') }}</span>
            <div class="pair-row__side">
              <label>词 A</label>
              <el-input v-model="contradictionPairs[idx][0]" placeholder="如：必须" />
            </div>
            <span class="pair-row__vs"><el-icon :size="15"><Switch /></el-icon></span>
            <div class="pair-row__side">
              <label>词 B（支持 (?&lt;!不) 前缀否定）</label>
              <el-input v-model="contradictionPairs[idx][1]" placeholder="如：禁止 / (?<!不)允许" />
            </div>
            <button type="button" class="pair-row__del" :aria-label="'删除第 ' + (idx + 1) + ' 对'" @click="removePair(idx)">
              <el-icon><Delete /></el-icon>
            </button>
          </div>
        </transition-group>
      </div>
      <div v-if="!contradictionPairs.length" class="pair-empty">暂无互斥词对，点击右上「新增词对」</div>
    </section>

    <!-- ===== 代码解析范围 ===== -->
    <section class="cfg-section tg-fade-up">
      <div class="cfg-section__head">
        <div class="cfg-section__title">
          <h3><span class="cfg-section__ic cfg-section__ic--sage"><el-icon :size="15"><Aim /></el-icon></span>代码解析范围</h3>
          <p>按包 / 类 / 方法过滤解析（FR-CODE-001），include* 优先于 exclude*</p>
        </div>
      </div>
      <div class="scope-grid">
        <div v-for="g in scopeGroups" :key="g.key" class="scope-card tg-pop-in">
          <div class="scope-card__head">
            <span class="scope-card__tile"><el-icon :size="14"><component :is="scopeIcon(g.key)" /></el-icon></span>
            <b>{{ groupLabel(g.key) }}</b>
          </div>
          <p class="scope-card__hint">{{ g.label }}</p>
          <div class="scope-card__chips">
            <span v-for="(v, vi) in scopeLists[g.key]" :key="vi" class="kw-chip kw-chip--sm">
              {{ v }}
              <button type="button" class="kw-chip__del" :aria-label="'移除 ' + v" @click="removeScope(g.key, vi)">×</button>
            </span>
            <span v-if="!scopeLists[g.key].length" class="scope-card__none">未配置（不限制）</span>
          </div>
          <div class="scope-card__add">
            <el-input
              v-model="scopeInputs[g.key]"
              :placeholder="g.placeholder"
              size="small"
              @keyup.enter="addScope(g.key)"
            />
            <el-button size="small" round @click="addScope(g.key)">添加</el-button>
          </div>
        </div>
      </div>
      <div class="scope-save">
        <el-button type="primary" round :loading="savingScope" @click="handleSaveScope">
          <el-icon style="margin-right: 4px"><Check /></el-icon>保存代码解析范围
        </el-button>
      </div>
    </section>

    <!-- ===== 质量门槛（Quality Gate） ===== -->
    <section class="cfg-section tg-fade-up">
      <div class="cfg-section__head">
        <div class="cfg-section__title">
          <h3><span class="cfg-section__ic cfg-section__ic--violet"><el-icon :size="15"><Aim /></el-icon></span>质量门槛（Quality Gate）</h3>
          <p>分析结果页据此判定 PASS / FAIL，阈值可在结果页回看</p>
        </div>
        <transition name="tg-fade">
          <el-tag v-if="gateSaved" type="success" effect="light">已保存并生效</el-tag>
        </transition>
      </div>
      <div class="gate-grid">
        <div class="gate-card">
          <span class="gate-card__icon tile--sage"><el-icon :size="18"><TrendCharts /></el-icon></span>
          <div class="gate-card__meta">
            <label>覆盖率阈值</label>
            <b>{{ gateForm.coverage }}<em>%</em></b>
          </div>
          <el-slider v-model="gateForm.coverage" :min="50" :max="100" :step="5" />
        </div>
        <div class="gate-card">
          <span class="gate-card__icon tile--gold"><el-icon :size="18"><Connection /></el-icon></span>
          <div class="gate-card__meta">
            <label>一致率阈值</label>
            <b>{{ gateForm.consistency }}<em>%</em></b>
          </div>
          <el-slider v-model="gateForm.consistency" :min="50" :max="100" :step="5" />
        </div>
        <div class="gate-card">
          <span class="gate-card__icon tile--coral"><el-icon :size="18"><WarningFilled /></el-icon></span>
          <div class="gate-card__meta">
            <label>严重缺陷上限</label>
            <b>{{ gateForm.seriousLimit }}<em>个</em></b>
          </div>
          <div class="gate-card__limit">
            <el-input-number v-model="gateForm.seriousLimit" :min="0" :max="50" />
            <span>超过则判定不通过</span>
          </div>
        </div>
      </div>
      <div class="gate-save">
        <el-button type="primary" round :loading="savingGate" @click="handleSaveGate">
          <el-icon style="margin-right: 4px"><Check /></el-icon>保存质量门槛
        </el-button>
      </div>
    </section>

    <!-- ===== 安全运维 · 密钥轮换 ===== -->
    <section class="cfg-section cfg-section--warn tg-fade-up">
      <div class="cfg-section__head">
        <div class="cfg-section__title">
          <h3><span class="cfg-section__ic cfg-section__ic--warn"><el-icon :size="15"><Lock /></el-icon></span>安全运维 · 密钥轮换</h3>
          <p>将加密密钥从旧密钥重加密为新密钥（GAP-028），仅管理员可操作</p>
        </div>
      </div>
      <div class="rotate-wrap">
        <div class="rotate-form">
          <div class="rotate-form__field">
            <label>旧密钥</label>
            <el-input v-model="rotateForm.oldKey" type="password" show-password placeholder="当前存储加密密钥" />
          </div>
          <div class="rotate-form__field">
            <label>新密钥</label>
            <el-input v-model="rotateForm.newKey" type="password" show-password placeholder="轮换后的新密钥（≥16 位）" />
          </div>
          <el-button type="warning" round :loading="rotating" @click="handleRotate">
            <el-icon style="margin-right: 4px"><Key /></el-icon>执行密钥轮换
          </el-button>
        </div>
        <ul class="rotate-tips">
          <li>轮换期间请避免并发上传 / 备份</li>
          <li>新密钥建议 ≥ 16 位，含大小写字母与数字</li>
          <li>将同步重加密上传文件与备份文件</li>
        </ul>
      </div>
    </section>

    <!-- ===== 当前生效 JSON ===== -->
    <section class="cfg-section tg-fade-up">
      <div class="cfg-section__head">
        <div class="cfg-section__title">
          <h3><span class="cfg-section__ic cfg-section__ic--blue"><el-icon :size="15"><Document /></el-icon></span>当前生效 JSON</h3>
          <p>保存即写入后端</p>
        </div>
      </div>
      <pre class="json-terminal">{{ previewJson }}</pre>
    </section>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { chartThemeName, chartText, chartFaint, chartAxisLine, chartSplitLine, chartTooltipBg, chartTitleColor, onChartThemeChange } from '@/utils/echartsTheme'
import {
  Check, RefreshLeft, Delete, Plus, Lock, Switch, MagicStick, Aim, DataBoard,
  Search, TrendCharts, WarningFilled, Key, Document,
  Box, Folder, Grid
} from '@element-plus/icons-vue'
import { systemConfigApi, securityApi } from '@/api'
import { UserContext } from '@/store/user'

/** 解析范围分组 → 门户图标 */
const scopeIcon = (key) => ({
  includePackages: Box,
  excludePackages: Folder,
  includeClasses: Grid,
  excludeClasses: Aim,
  includeMethods: MagicStick,
  excludeMethods: Delete
}[key] || Grid)

/** 解析范围分组 → 短标题 */
const groupLabel = (key) => ({
  includePackages: '包含包',
  excludePackages: '排除包',
  includeClasses: '包含类',
  excludeClasses: '排除类',
  includeMethods: '包含方法',
  excludeMethods: '排除方法'
}[key] || key)

const router = useRouter()
const KEY = 'req_parse_rules'

// ===== W2-10/O3：质量门槛（Quality Gate）阈值配置 =====
const GATE_KEY = 'quality_gate'
const gateForm = reactive({ coverage: 80, consistency: 80, seriousLimit: 0 })
const savingGate = ref(false)
const gateSaved = ref(false)

const loadGateConfig = async () => {
  try {
    const cfg = await systemConfigApi.get(GATE_KEY)
    if (cfg && cfg.configValue) {
      const parsed = JSON.parse(cfg.configValue)
      if (parsed.coverage != null) gateForm.coverage = Number(parsed.coverage)
      if (parsed.consistency != null) gateForm.consistency = Number(parsed.consistency)
      if (parsed.seriousLimit != null) gateForm.seriousLimit = Number(parsed.seriousLimit)
    }
  } catch (e) {
    console.warn('加载质量门槛配置失败', e)
  }
}

const handleSaveGate = async () => {
  savingGate.value = true
  try {
    await systemConfigApi.save(GATE_KEY, JSON.stringify({
      coverage: gateForm.coverage,
      consistency: gateForm.consistency,
      seriousLimit: gateForm.seriousLimit
    }), '质量门槛阈值（覆盖率/一致率百分制，严重缺陷上限）')
    gateSaved.value = true
    ElMessage.success('质量门槛已保存并生效')
    setTimeout(() => { gateSaved.value = false }, 2500)
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    savingGate.value = false
  }
}

const loading = ref(false)
const saving = ref(false)
const ambiguityKeywords = ref([])
const contradictionPairs = reactive([])
const ambInput = ref('')

const previewJson = computed(() =>
  JSON.stringify({ ambiguityKeywords: ambiguityKeywords.value, contradictionPairs: contradictionPairs }, null, 2)
)

const goBack = () => router.push('/dashboard')

const addAmbiguity = () => {
  const v = (ambInput.value || '').trim()
  if (v && !ambiguityKeywords.value.includes(v)) {
    ambiguityKeywords.value.push(v)
  }
  ambInput.value = ''
}
const removeAmbiguity = (idx) => ambiguityKeywords.value.splice(idx, 1)

const addPair = () => contradictionPairs.push(['', ''])
const removePair = (idx) => contradictionPairs.splice(idx, 1)

const load = async () => {
  loading.value = true
  try {
    const res = await systemConfigApi.get(KEY)
    // GET 返回包装对象（{configKey, configValue}），configValue 为规则 JSON 字符串
    const raw = res && res.configValue != null ? res.configValue : res
    const val = typeof raw === 'string' ? (JSON.parse(raw) || {}) : (raw || {})
    ambiguityKeywords.value = Array.isArray(val.ambiguityKeywords) ? [...val.ambiguityKeywords] : []
    contradictionPairs.splice(0, contradictionPairs.length, ...(Array.isArray(val.contradictionPairs) ? val.contradictionPairs : []))
    await loadScope()
  } catch (e) {
    ElMessage.error(e.message || '加载配置失败')
  } finally {
    loading.value = false
  }
}

/* ============ FR-CODE-001 规则3/4（2.4）：代码解析范围配置 ============ */
const CODE_SCOPE_KEY = 'code_parse_scope'
const savingScope = ref(false)
const scopeGroups = [
  { key: 'includePackages', label: '包含包（空=不限，支持前缀通配 com.example.*）', placeholder: '如：com.example.biz' },
  { key: 'excludePackages', label: '排除包', placeholder: '如：com.example.test' },
  { key: 'includeClasses', label: '包含类（简单类名或 包.类 全限定名）', placeholder: '如：OrderService' },
  { key: 'excludeClasses', label: '排除类（支持通配 *Test）', placeholder: '如：*Test' },
  { key: 'includeMethods', label: '包含方法名（空=不限）', placeholder: '如：createOrder' },
  { key: 'excludeMethods', label: '排除方法名', placeholder: '如：toString' }
]
const scopeLists = reactive({
  includePackages: [], excludePackages: [], includeClasses: [], excludeClasses: [], includeMethods: [], excludeMethods: []
})
const scopeInputs = reactive({})
scopeGroups.forEach(g => { scopeInputs[g.key] = '' })

const addScope = (key) => {
  const v = (scopeInputs[key] || '').trim()
  if (v && !scopeLists[key].includes(v)) scopeLists[key].push(v)
  scopeInputs[key] = ''
}
const removeScope = (key, idx) => scopeLists[key].splice(idx, 1)

const loadScope = async () => {
  try {
    const res = await systemConfigApi.get(CODE_SCOPE_KEY)
    const raw = res && res.configValue != null ? res.configValue : res
    const val = typeof raw === 'string' ? (JSON.parse(raw) || {}) : (raw || {})
    scopeGroups.forEach(g => {
      scopeLists[g.key].splice(0, scopeLists[g.key].length, ...(Array.isArray(val[g.key]) ? val[g.key] : []))
    })
  } catch (e) {
    // 未配置时保持空列表（不过滤）
  }
}

const handleSaveScope = async () => {
  if (!UserContext.isAdmin()) {
    ElMessage.warning('仅管理员可修改系统配置')
    return
  }
  savingScope.value = true
  try {
    const payload = {}
    scopeGroups.forEach(g => { payload[g.key] = scopeLists[g.key] })
    await systemConfigApi.save(CODE_SCOPE_KEY, JSON.stringify(payload, null, 2), '代码解析范围（FR-CODE-001：按包/类/方法过滤解析）')
    ElMessage.success('代码解析范围已保存并热生效')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    savingScope.value = false
  }
}

const handleSave = async () => {
  if (!UserContext.isAdmin()) {
    ElMessage.warning('仅管理员可修改系统配置')
    return
  }
  saving.value = true
  try {
    await systemConfigApi.save(KEY, previewJson.value, '需求解析规则（歧义/模糊词 + 互斥词对）')
    ElMessage.success('需求解析规则已保存并热生效')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    saving.value = false
  }
}

const handleReset = () => {
  ambiguityKeywords.value = ['等', '等等', '适当', '合适', '合理', '可能', '也许', '若干', '一些', '方便', '友好']
  contradictionPairs.splice(0, contradictionPairs.length,
    ['必须', '禁止'], ['必须', '不得'], ['禁止', '(?<!不)允许'], ['不得', '(?<!不)允许'], ['启用', '禁用'])
  ElMessage.info('已恢复为内置默认规则（保存后生效）')
}

/* ============ 4.9 整改：密钥轮换（GAP-028，仅管理员） ============ */
const rotating = ref(false)
const rotateForm = reactive({ oldKey: '', newKey: '' })
const handleRotate = async () => {
  if (!UserContext.isAdmin()) {
    ElMessage.warning('仅管理员可执行密钥轮换')
    return
  }
  if (!rotateForm.oldKey || !rotateForm.newKey) {
    ElMessage.warning('请填写旧密钥与新密钥')
    return
  }
  if (rotateForm.newKey.length < 16) {
    ElMessage.warning('新密钥建议长度 ≥ 16 位')
    return
  }
  try {
    await ElMessageBox.confirm('确认执行密钥轮换？轮换期间请避免并发上传/备份操作。', '安全确认', {
      type: 'warning', confirmButtonText: '确认轮换', cancelButtonText: '取消'
    })
  } catch {
    return
  }
  rotating.value = true
  try {
    const res = await securityApi.rotateKeys(rotateForm.oldKey, rotateForm.newKey)
    ElMessage.success('密钥轮换完成：' + (Array.isArray(res) ? res.length + ' 个文件已重加密' : '成功'))
    rotateForm.oldKey = ''
    rotateForm.newKey = ''
  } catch (e) {
    ElMessage.error(e.message || '密钥轮换失败')
  } finally {
    rotating.value = false
  }
}

onChartThemeChange(() => { gateRadarChart?.dispose(); gateRadarChart = null; scopeDonutChart?.dispose(); scopeDonutChart = null; kwLenChart?.dispose(); kwLenChart = null; renderSysViz() })

onMounted(async () => {
  await load()
  await loadGateConfig()
  nextTick(renderSysViz)
  window.addEventListener('resize', onSysResize)
})

onBeforeUnmount(() => {
  window.removeEventListener('resize', onSysResize)
  if (gateRadarChart) gateRadarChart.dispose()
  if (scopeDonutChart) scopeDonutChart.dispose()
  if (kwLenChart) kwLenChart.dispose()
})

/* ============ W4 v4：规则资产可视化 ============ */
const gateRadarEl = ref(null)
const scopeDonutEl = ref(null)
const kwLenEl = ref(null)
let gateRadarChart = null
let scopeDonutChart = null
let kwLenChart = null

const VIZ_TOOLTIP = {
  backgroundColor: 'rgba(43,36,28,.92)',
  borderColor: 'rgba(201,155,63,.4)',
  textStyle: { color: '#f4ead6' }
}

/** 严重缺陷上限（0-50）→ 容忍度得分（0-100，越小越严格） */
const gateTolerance = computed(() => Math.max(0, 100 - gateForm.seriousLimit * 2))

/** 解析范围构成：6 组规则数量 */
const scopeDonutData = computed(() => scopeGroups.map((g, i) => ({
  name: groupLabel(g.key),
  value: scopeLists[g.key].length,
  color: ['#6E93B0', '#9A7FB0', '#C97F8A', '#6B8E4E', '#3F6B64', '#E89B3C'][i % 6]
})))

/** 歧义词长度分布：1 字 / 2 字 / 3 字+ */
const kwLenDist = computed(() => {
  const buckets = { 1: 0, 2: 0, 3: 0 }
  ambiguityKeywords.value.forEach((k) => {
    const len = [...(k || '')].length
    if (len <= 1) buckets[1]++
    else if (len === 2) buckets[2]++
    else buckets[3]++
  })
  return [
    { name: '1 字', value: buckets[1] },
    { name: '2 字', value: buckets[2] },
    { name: '3 字+', value: buckets[3] }
  ]
})

/** 歧义词长度分布文本 + 短词占比（副行展示） */
const kwLenText = computed(() => {
  const [a, b, c] = kwLenDist.value
  return `1字 ${a.value} · 2字 ${b.value} · 3字+ ${c.value}`
})
const kwLenShortRatio = computed(() => {
  const total = kwLenDist.value.reduce((n, d) => n + d.value, 0)
  if (!total) return 0
  return Math.round((kwLenDist.value[0].value + kwLenDist.value[1].value) / total * 100)
})

/** 解析范围：规则总数 + 已配置分组数 */
const scopeTotalRules = computed(() => scopeGroups.reduce((n, g) => n + scopeLists[g.key].length, 0))
const scopeConfiguredGroups = computed(() => scopeGroups.filter((g) => scopeLists[g.key].length > 0).length)

const renderSysViz = () => {
  // 1) 质量门槛雷达
  if (gateRadarEl.value) {
    if (!gateRadarChart) gateRadarChart = echarts.init(gateRadarEl.value, chartThemeName())
    const maxSerious = Math.max(20, gateForm.seriousLimit * 2.5)
    gateRadarChart.setOption({
      animationDuration: 600,
      tooltip: { trigger: 'item', ...VIZ_TOOLTIP },
      radar: {
        indicator: [
          { name: '覆盖率', max: 100 },
          { name: '一致率', max: 100 },
          { name: '缺陷容忍', max: Math.max(100, Math.ceil(maxSerious)) }
        ],
        radius: '68%',
        center: ['50%', '54%'],
        axisName: { color: 'var(--tg-text-secondary)', fontSize: 11 },
        splitArea: { areaStyle: { color: ['rgba(201,155,63,.04)', 'rgba(201,155,63,.09)'] } },
        splitLine: { lineStyle: { color: 'rgba(201,155,63,.18)' } },
        axisLine: { lineStyle: { color: 'rgba(201,155,63,.25)' } }
      },
      series: [{
        type: 'radar',
        symbolSize: 6,
        data: [{
          value: [gateForm.coverage, gateForm.consistency, gateTolerance.value],
          name: '当前阈值',
          areaStyle: { color: 'rgba(201,127,138,.22)' },
          lineStyle: { color: '#C97F8A', width: 2 },
          itemStyle: { color: '#C97F8A' }
        }]
      }]
    }, true)
  }
  // 2) 解析范围构成环形图
  if (scopeDonutEl.value) {
    if (!scopeDonutChart) scopeDonutChart = echarts.init(scopeDonutEl.value, chartThemeName())
    const items = scopeDonutData.value
    const total = items.reduce((n, it) => n + it.value, 0)
    scopeDonutChart.setOption({
      tooltip: { trigger: 'item', formatter: '{b}：{c} 条（{d}%）', ...VIZ_TOOLTIP },
      legend: {
        bottom: 0,
        left: 'center',
        icon: 'circle',
        itemWidth: 8,
        itemHeight: 8,
        textStyle: { color: 'var(--tg-text-secondary)', fontSize: 10.5 }
      },
      title: {
        text: String(total),
        subtext: '规则总数',
        left: 'center',
        top: '34%',
        textStyle: { fontSize: 20, fontWeight: 700, color: 'var(--tg-text-primary)' },
        subtextStyle: { fontSize: 10.5, color: 'var(--tg-text-secondary)' }
      },
      series: [{
        type: 'pie',
        radius: ['46%', '70%'],
        center: ['50%', '42%'],
        padAngle: 3,
        itemStyle: { borderRadius: 7, borderColor: 'rgba(255,244,224,.9)', borderWidth: 2 },
        label: { show: false },
        emphasis: { scaleSize: 5, itemStyle: { shadowBlur: 14, shadowColor: 'rgba(201,155,63,.4)' } },
        data: total > 0
          ? items.map((it) => ({ name: it.name, value: it.value, itemStyle: { color: it.color } }))
          : [{ name: '未配置', value: 1, itemStyle: { color: 'rgba(180,170,148,.22)' } }]
      }]
    }, true)
  }
  // 3) 歧义词长度分布（横向条）
  if (kwLenEl.value) {
    if (!kwLenChart) kwLenChart = echarts.init(kwLenEl.value, chartThemeName())
    const dist = kwLenDist.value
    const total = dist.reduce((n, d) => n + d.value, 0)
    kwLenChart.setOption({
      animationDuration: 600,
      tooltip: { trigger: 'axis', axisPointer: { type: 'shadow' }, ...VIZ_TOOLTIP },
      grid: { left: 6, right: 28, top: 8, bottom: 6, containLabel: true },
      xAxis: {
        type: 'value',
        minInterval: 1,
        splitLine: { lineStyle: { color: 'rgba(201,155,63,.1)' } },
        axisLabel: { color: 'var(--tg-text-secondary)', fontSize: 11 }
      },
      yAxis: {
        type: 'category',
        data: dist.map((d) => d.name),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: 'var(--tg-text-primary)', fontSize: 12, fontWeight: 500 }
      },
      series: [{
        type: 'bar',
        data: dist.map((d, i) => ({
          value: d.value,
          itemStyle: {
            borderRadius: [0, 8, 8, 0],
            color: ['#6E93B0', '#9A7FB0', '#C97F8A'][i % 3]
          }
        })),
        barWidth: 16,
        showBackground: true,
        backgroundStyle: { color: 'rgba(201,155,63,.08)', borderRadius: [0, 8, 8, 0] },
        label: {
          show: true,
          position: 'right',
          formatter: (p) => (total > 0 ? `${p.value} 词` : '无'),
          color: 'var(--tg-text-secondary)',
          fontSize: 11
        }
      }]
    }, true)
  }
}

const onSysResize = () => {
  if (gateRadarChart) gateRadarChart.resize()
  if (scopeDonutChart) scopeDonutChart.resize()
  if (kwLenChart) kwLenChart.resize()
}

watch([ambiguityKeywords, scopeLists, gateForm], () => nextTick(renderSysViz), { deep: true })
</script>

<style scoped>
.system-config-page {
  position: relative;
  max-width: 1200px;
  margin: 0 auto;
}

.cfg-tip {
  margin-bottom: 22px;
  border-radius: 12px;
}

/* ===== 关键词 chips ===== */
.kw-chips {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.kw-chip {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border-radius: 999px;
  background: linear-gradient(135deg, rgba(232, 200, 119, 0.16), rgba(201, 155, 63, 0.1));
  border: 1px solid rgba(201, 155, 63, 0.28);
  color: #8a651a;
  font-size: 13px;
  font-weight: 500;
  transition: transform 0.25s var(--tg-ease-spring), box-shadow 0.25s ease, border-color 0.25s ease;
}

.kw-chip:hover {
  transform: translateY(-2px);
  border-color: rgba(201, 155, 63, 0.5);
  box-shadow: 0 6px 14px rgba(60, 45, 25, 0.1);
}

.kw-chip--sm {
  font-size: 12px;
  padding: 3px 10px;
}

.kw-chip__del {
  background: transparent;
  border: 0;
  color: rgba(138, 101, 26, 0.55);
  cursor: pointer;
  font-size: 15px;
  line-height: 1;
  padding: 0 2px;
  border-radius: 50%;
  transition: color 0.2s ease, background 0.2s ease;
}

.kw-chip__del:hover {
  color: #9a3f30;
  background: rgba(194, 94, 76, 0.12);
}

.kw-add {
  display: flex;
  gap: 10px;
  max-width: 540px;
}

/* chips 进出场 */
.tg-chip-enter-active,
.tg-chip-leave-active {
  transition: all 0.3s var(--tg-ease);
}

.tg-chip-enter-from,
.tg-chip-leave-to {
  opacity: 0;
  transform: scale(0.82) translateY(5px);
}

/* ===== 互斥词对 ===== */
.pair-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin-bottom: 8px;
}

.pair-row {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.55);
  border: 1px solid rgba(180, 170, 148, 0.16);
  transition: border-color 0.3s ease, background 0.3s ease, transform 0.3s var(--tg-ease-spring);
}

.pair-row:hover {
  border-color: rgba(201, 155, 63, 0.32);
  background: rgba(255, 255, 255, 0.78);
  transform: translateX(4px);
}

.pair-row__badge {
  width: 30px;
  height: 30px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: rgba(201, 155, 63, 0.14);
  color: var(--tg-accent-strong);
  font-weight: 700;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.pair-row__side {
  flex: 1;
  min-width: 140px;
}

.pair-row__side label {
  display: block;
  font-size: 11px;
  color: var(--tg-slate);
  margin-bottom: 5px;
}

.pair-row__vs {
  flex-shrink: 0;
  color: var(--tg-accent);
  animation: tg-pulse-arrow 2.2s ease-in-out infinite;
}

.pair-row__del {
  width: 32px;
  height: 32px;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  border: 0;
  background: transparent;
  color: var(--tg-slate);
  cursor: pointer;
  transition: all 0.2s ease;
}

.pair-row__del:hover {
  background: rgba(194, 94, 76, 0.12);
  color: #9a3f30;
}

.pair-empty {
  text-align: center;
  padding: 6px 0 12px;
  color: var(--tg-slate);
  font-size: 13px;
}

.tg-pair-enter-active,
.tg-pair-leave-active {
  transition: all 0.35s var(--tg-ease);
}

.tg-pair-enter-from,
.tg-pair-leave-to {
  opacity: 0;
  transform: translateX(-12px);
}

/* ===== 代码解析范围：副卡网格 ===== */
.scope-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 14px;
  margin-bottom: 18px;
}

.scope-card {
  padding: 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.6);
  border: 1px solid rgba(180, 170, 148, 0.18);
  transition: transform 0.3s var(--tg-ease-spring), border-color 0.3s ease, box-shadow 0.3s ease;
}

.scope-card:hover {
  transform: translateY(-3px);
  border-color: rgba(201, 155, 63, 0.34);
  box-shadow: 0 12px 26px rgba(60, 45, 25, 0.09);
}

.scope-card__head {
  display: flex;
  align-items: center;
  gap: 9px;
  margin-bottom: 6px;
}

.scope-card__tile {
  width: 28px;
  height: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 9px;
  background: rgba(201, 155, 63, 0.13);
  color: var(--tg-accent-strong);
}

.scope-card__head b {
  font-size: 13.5px;
  color: var(--tg-text-primary);
}

.scope-card__hint {
  margin: 0 0 10px;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--tg-slate);
}

.scope-card__chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  min-height: 27px;
  margin-bottom: 12px;
}

.scope-card__none {
  font-size: 11.5px;
  color: var(--tg-slate);
  padding: 4px 0;
  font-style: italic;
}

.scope-card__add {
  display: flex;
  gap: 8px;
}

.scope-save {
  text-align: right;
}

.tg-pop-in {
  animation: tg-pop-in 0.5s var(--tg-ease-spring) both;
}

@keyframes tg-pop-in {
  from {
    opacity: 0;
    transform: translateY(10px) scale(0.97);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

/* ===== 质量门槛：三指标卡 ===== */
.gate-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;
  margin-bottom: 18px;
}

.gate-card {
  padding: 16px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.6);
  border: 1px solid rgba(180, 170, 148, 0.18);
  transition: transform 0.3s var(--tg-ease-spring), border-color 0.3s ease;
}

.gate-card:hover {
  transform: translateY(-2px);
  border-color: rgba(201, 155, 63, 0.34);
}

.gate-card__icon {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 11px;
}

.tile--sage {
  background: rgba(154, 156, 107, 0.16);
  color: #55682e;
}

.tile--gold {
  background: rgba(201, 155, 63, 0.16);
  color: #8a651a;
}

.tile--coral {
  background: rgba(194, 94, 76, 0.12);
  color: #9a3f30;
}

.gate-card__meta {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin: 12px 0 10px;
}

.gate-card__meta label {
  font-size: 12px;
  color: var(--tg-text-secondary);
}

.gate-card__meta b {
  font-size: 26px;
  font-weight: 700;
  color: var(--tg-text-primary);
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.gate-card__meta b em {
  font-style: normal;
  font-size: 13px;
  font-weight: 600;
  color: var(--tg-slate);
  margin-left: 2px;
}

.gate-card__limit {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-top: 14px;
}

.gate-card__limit span {
  font-size: 12px;
  color: var(--tg-text-secondary);
}

.gate-save {
  text-align: right;
}

/* ===== 密钥轮换 ===== */
.cfg-section--warn {
  border-color: rgba(232, 155, 60, 0.34);
}

.cfg-section__ic--warn {
  background: rgba(232, 155, 60, 0.16);
  color: #9a5d12;
}

.rotate-wrap {
  display: flex;
  gap: 28px;
  align-items: flex-start;
  flex-wrap: wrap;
}

.rotate-form {
  display: flex;
  flex-direction: column;
  gap: 14px;
  flex: 1;
  min-width: 280px;
  max-width: 520px;
}

.rotate-form__field label {
  display: block;
  font-size: 12px;
  color: var(--tg-text-secondary);
  margin-bottom: 6px;
}

.rotate-tips {
  margin: 0;
  padding: 12px 18px 12px 36px;
  flex-shrink: 0;
  border-radius: 14px;
  background: rgba(232, 155, 60, 0.08);
  border: 1px dashed rgba(232, 155, 60, 0.32);
}

.rotate-tips li {
  font-size: 12.5px;
  line-height: 1.9;
  color: var(--tg-text-secondary);
}

/* ===== JSON 终端面板 ===== */
.json-terminal {
  margin: 0;
  padding: 18px 20px;
  border-radius: 14px;
  background: linear-gradient(160deg, #2b241c, #1c1711);
  color: #e8d9b5;
  font-family: var(--tg-font-mono);
  font-size: 12.5px;
  line-height: 1.7;
  overflow-x: auto;
  border: 1px solid rgba(201, 155, 63, 0.22);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.05);
}

@keyframes tg-pulse-arrow {
  0%, 100% { opacity: 0.45; transform: translateX(0); }
  50% { opacity: 1; transform: translateX(3px); }
}

/* ===== 窄屏适配 ===== */
@media (max-width: 960px) {
  .scope-grid {
    grid-template-columns: repeat(2, 1fr);
  }

  .gate-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 720px) {
  .scope-grid {
    grid-template-columns: 1fr;
  }

  .pair-row {
    flex-wrap: wrap;
  }

  .kw-add {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>
