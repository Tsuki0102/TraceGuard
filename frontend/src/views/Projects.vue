<template>
  <div class="projects-page">
    <!-- ===== 页头：标题 + 操作 ===== -->
    <div class="page-header tg-fade-up">
      <div>
        <div class="page-header__greet">
          <el-icon class="page-header__greet-icon"><FolderOpened /></el-icon>
          {{ showRecycle ? '回收站' : '项目管理' }}
        </div>
        <h2 class="page-header__title">{{ showRecycle ? '已删除项目' : '项目列表' }}</h2>
        <p class="page-header__desc">
          {{ showRecycle ? '已删除的项目，可恢复或彻底清除' : '管理软件项目，跟踪需求-代码一致性与缺陷' }}
        </p>
      </div>
      <div class="page-header__actions">
        <el-button size="large" round class="page-header__btn-ghost" @click="toggleRecycle">
          <el-icon style="margin-right: 6px"><component :is="showRecycle ? 'Back' : 'Delete'" /></el-icon>
          {{ showRecycle ? '返回项目列表' : '回收站' }}
        </el-button>
        <el-button v-if="!showRecycle" type="primary" size="large" round @click="openCreate">
          <el-icon style="margin-right: 6px"><Plus /></el-icon>新建项目
        </el-button>
      </div>
    </div>

    <!-- ===== KPI 统计行（回收站模式不展示） ===== -->
    <div v-if="!showRecycle" class="kpi-row">
      <div
        v-for="(k, i) in kpis"
        :key="k.key"
        class="kpi-card tg-fade-up"
        :style="{ animationDelay: i * 0.06 + 's' }"
      >
        <div class="kpi-card__tile" :class="'kpi-card__tile--' + k.tone">
          <el-icon :size="22"><component :is="k.icon" /></el-icon>
        </div>
        <div class="kpi-card__body">
          <div class="kpi-card__num">
            <span class="tg-count">{{ k.value }}</span>
            <span v-if="k.suffix" class="kpi-card__suffix">{{ k.suffix }}</span>
          </div>
          <div class="kpi-card__label">{{ k.label }}</div>
          <div class="kpi-card__meta">{{ k.meta }}</div>
        </div>
      </div>
    </div>

    <!-- ===== 项目表格面板 ===== -->
    <section class="table-panel tg-fade-up">
      <div class="table-panel__head">
        <div class="table-panel__title">
          <h3><el-icon class="section-title__ic" :size="17"><Tickets /></el-icon>{{ showRecycle ? '回收站项目' : '全部项目' }}</h3>
          <p>共 {{ projects.length }} 个{{ showRecycle ? '已删除项目' : '项目' }}</p>
        </div>
        <div v-if="!showRecycle" class="table-panel__tools">
          <el-input
            v-model="keyword"
            placeholder="搜索项目名称"
            clearable
            class="search-input"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <!-- W3-01/R13：列显隐设置 -->
          <ColumnSetting :columns="colDefs" v-model="visibleCols" />
        </div>
      </div>

      <!-- W3-06/R19：分组导航（状态 / 行业双层 chip 树，替代单选下拉） -->
      <div v-if="!showRecycle" class="group-bar">
        <div class="group-row">
          <span class="group-row__label"><el-icon :size="13"><Menu /></el-icon>状态</span>
          <div class="group-row__chips">
            <button
              v-for="g in groupByStatus"
              :key="g.value"
              type="button"
              class="tg-chip"
              :class="{ 'is-active': statusFilter === g.value }"
              @click="switchStatus(g.value)"
            >{{ g.label }}<em v-if="g.count">{{ g.count }}</em></button>
          </div>
        </div>
        <div class="group-row">
          <span class="group-row__label"><el-icon :size="13"><OfficeBuilding /></el-icon>行业</span>
          <div class="group-row__chips">
            <button
              v-for="g in groupByIndustry"
              :key="g.value"
              type="button"
              class="tg-chip"
              :class="{ 'is-active': industryFilter === g.value }"
              @click="switchIndustry(g.value)"
            >{{ g.value }}<em v-if="g.count">{{ g.count }}</em></button>
          </div>
        </div>
      </div>

      <!-- W4：双栏主区：行业分布环形图（联动筛选） + 项目表格 -->
      <div class="explore-row">
        <aside v-if="!showRecycle" class="industry-card tg-fade-up">
          <div class="industry-card__head">
            <h4><el-icon :size="15" class="section-title__ic"><PieChart /></el-icon>行业分布</h4>
            <span class="live-badge">{{ industryDistribution.total }} 项</span>
          </div>
          <div ref="industryChartEl" class="industry-chart"></div>
          <div class="industry-legend">
            <button
              v-for="g in industryDistribution.items"
              :key="g.name"
              type="button"
              class="industry-legend__item"
              :class="{ 'is-active': industryFilter === g.name }"
              @click="switchIndustry(industryFilter === g.name ? '' : g.name)"
            >
              <i :style="{ background: g.color }"></i>
              <span>{{ g.name }}</span>
              <em>{{ g.count }}</em>
            </button>
          </div>
        </aside>

        <div class="explore-row__table">
      <!-- 个性化增强 BATCH-4：批量操作工具条（选中后浮现） -->
      <transition name="tg-fade">
        <div v-if="selectedProjects.length" class="batch-bar">
          <span class="batch-bar__count">已选 <b>{{ selectedProjects.length }}</b> 个项目</span>
          <el-button size="small" round class="op-btn op-btn--edit" :disabled="batchBusy" @click="doBatch('archive')">批量归档</el-button>
          <el-button size="small" round class="op-btn op-btn--result" :disabled="batchBusy" @click="doBatch('delete')">批量删除</el-button>
          <el-button size="small" round link @click="clearSelection">取消选择</el-button>
        </div>
      </transition>
      <el-table ref="projectTableRef" :data="filteredProjects" v-loading="loading" class="project-table" @selection-change="onSelectionChange">
        <template #empty>
          <EmptyArt :text="showRecycle ? '回收站空空如也' : '没有符合条件的项目，换个筛选试试'" />
        </template>
        <el-table-column type="selection" width="44" :selectable="() => !batchBusy" />
        <el-table-column v-if="visibleCols.includes('projectName')" label="项目名称" min-width="200">
          <template #default="{ row }">
            <div class="project-name">
              <span class="project-name__tile"><el-icon :size="16"><FolderOpened /></el-icon></span>
              <span class="project-name__text">
                <b>{{ row.projectName }}</b>
                <small v-if="row.techStack">{{ row.techStack }}</small>
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column v-if="visibleCols.includes('industry')" prop="industryType" label="行业类型" width="120" />
        <el-table-column v-if="visibleCols.includes('status')" prop="status" label="状态" width="110">
          <template #default="{ row }">
            <span class="status-pill" :class="'status-pill--' + row.status">{{ statusText(row.status) }}</span>
          </template>
        </el-table-column>
        <!-- W1-06/O10：项目健康分（0-100 单一指标，可排序） -->
        <el-table-column v-if="visibleCols.includes('health')" label="健康分" width="100" align="center">
          <template #default="{ row }">
            <div v-if="health(row)" class="health-cell" :class="'health-cell--' + health(row).tone" :title="health(row).level + ' · ' + health(row).label">
              <span class="health-cell__score">{{ health(row).score }}</span>
              <span class="health-cell__level">{{ health(row).level }}</span>
            </div>
            <span v-else class="health-cell health-cell--empty">—</span>
          </template>
        </el-table-column>
        <el-table-column v-if="visibleCols.includes('requirementCount')" label="需求数" width="90" align="center">
          <template #default="{ row }">
            <span class="num-cell">{{ row.requirementCount || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="visibleCols.includes('defectCount')" label="缺陷数" width="90" align="center">
          <template #default="{ row }">
            <span class="num-cell" :class="{ 'is-warn': row.defectCount > 0 }">{{ row.defectCount || 0 }}</span>
          </template>
        </el-table-column>
        <el-table-column v-if="visibleCols.includes('coverageRate')" label="覆盖率" width="150">
          <template #default="{ row }">
            <div v-if="row.coverageRate != null" class="rate-cell">
              <div class="rate-cell__track">
                <i :style="{ width: (row.coverageRate * 100).toFixed(1) + '%' }"></i>
              </div>
              <span>{{ (row.coverageRate * 100).toFixed(1) }}%</span>
            </div>
            <span v-else class="rate-cell__empty">-</span>
          </template>
        </el-table-column>
        <el-table-column v-if="visibleCols.includes('createTime')" prop="createTime" label="创建时间" width="170" />
        <!-- W4：操作折叠为一个"操作"下拉菜单，避免文字与排版冲突 -->
        <el-table-column label="操作" width="120" fixed="right" align="center">
          <template #default="{ row }">
            <el-dropdown trigger="click" placement="bottom-end" @command="(cmd) => onOp(row, cmd)">
              <button type="button" class="op-btn op-btn--more">
                <el-icon><MoreFilled /></el-icon>操作
              </button>
              <template #dropdown>
                <el-dropdown-menu class="op-menu">
                  <template v-if="showRecycle">
                    <el-dropdown-item command="restore"><el-icon><RefreshLeft /></el-icon>恢复</el-dropdown-item>
                    <el-dropdown-item command="purge" class="op-menu__danger"><el-icon><DeleteFilled /></el-icon>彻底删除</el-dropdown-item>
                  </template>
                  <template v-else>
                    <el-dropdown-item command="detail"><el-icon><View /></el-icon>详情</el-dropdown-item>
                    <el-dropdown-item command="edit"><el-icon><EditPen /></el-icon>编辑</el-dropdown-item>
                    <el-dropdown-item v-if="row.status === 'analyzed'" command="result"><el-icon><DataAnalysis /></el-icon>查看报告</el-dropdown-item>
                    <el-dropdown-item v-if="row.status !== 'archived'" command="archive"><el-icon><FolderChecked /></el-icon>归档</el-dropdown-item>
                    <el-dropdown-item v-else command="restore"><el-icon><RefreshLeft /></el-icon>恢复</el-dropdown-item>
                    <el-dropdown-item divided command="delete" class="op-menu__danger">
                      <el-icon><Delete /></el-icon><span>删除</span>
                    </el-dropdown-item>
                  </template>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </el-table-column>
      </el-table>
        </div>
      </div>

      <!-- FUN-15：回收站真分页（>100 条可见） -->
      <div v-if="showRecycle" class="table-panel__pager">
        <el-pagination
          v-model:current-page="recyclePage"
          v-model:page-size="recycleSize"
          :total="recycleTotal"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="loadRecycle"
          @size-change="loadRecycle"
        />
      </div>
    </section>

    <el-dialog
      v-model="showCreateDialog"
      :title="editingProject ? '编辑项目' : '创建新项目'"
      width="550px"
      class="create-dialog"
    >
      <el-form :model="newProject" label-width="100px" ref="formRef" :rules="rules">
        <!-- W6：内置样例一键导入（评测基准，含标注缺陷集） -->
        <template v-if="!editingProject && sampleAssets.length">
          <div class="create-sample-strip">
            <span class="create-sample-strip__label">样例一键导入</span>
            <button v-for="a in sampleAssets" :key="a.key" type="button" class="create-sample-chip"
              :disabled="importingSample === a.key" @click="doImportSample(a)">
              <b>{{ a.key }}</b>
              <small>{{ a.requirementLines }} 需求行 · {{ a.defectCount }} 标注缺陷</small>
              <em>{{ importingSample === a.key ? '导入中…' : '导入' }}</em>
            </button>
          </div>
          <el-divider style="margin: 4px 0 16px">或手动填写</el-divider>
        </template>
        <el-form-item label="项目名称" prop="projectName">
          <el-input v-model="newProject.projectName" placeholder="请输入项目名称" />
        </el-form-item>
        <el-form-item label="行业类型" prop="industryType">
          <el-select v-model="newProject.industryType" placeholder="请选择" style="width: 100%">
            <el-option label="电商" value="电商" />
            <el-option label="金融" value="金融" />
            <el-option label="教育" value="教育" />
            <el-option label="医疗" value="医疗" />
            <el-option label="企业管理" value="企业管理" />
            <el-option label="其他" value="其他" />
          </el-select>
        </el-form-item>
        <el-form-item label="技术栈">
          <el-input v-model="newProject.techStack" placeholder="技术栈" />
        </el-form-item>
        <el-form-item label="项目描述">
          <el-input v-model="newProject.description" type="textarea" :rows="3" placeholder="项目描述" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" round @click="submitProject">{{ editingProject ? '保存' : '创建' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onBeforeUnmount, watch, nextTick } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { confirmDanger, confirmReversible } from '@/utils/confirmAction'
import * as echarts from 'echarts'
import { chartThemeName, chartText, chartFaint, chartAxisLine, chartSplitLine, chartTooltipBg, chartTitleColor, onChartThemeChange } from '@/utils/echartsTheme'
import { projectApi, insightApi } from '@/api'
import { useCountUp } from '@/composables/useCountUp'
import { computeHealthScore } from '@/utils/healthScore'
import ColumnSetting from '@/components/ColumnSetting.vue'
import EmptyArt from '@/components/EmptyArt.vue'

const router = useRouter()
const projects = ref([])
const loading = ref(false)

/** 回收站 / 列表切换 */
const showRecycle = ref(false)

// ===== 搜索与筛选 =====
const keyword = ref('')
const statusFilter = ref('')
const industryFilter = ref('')
const statusOptions = [
  { label: '已创建', value: 'created' },
  { label: '分析中', value: 'running' },
  { label: '已完成', value: 'analyzed' },
  { label: '失败', value: 'failed' },
  { label: '已归档', value: 'archived' }
]

// W3-06/R19：分组导航（状态 / 行业双层组）
const groupByStatus = computed(() => [
  { value: '', label: '全部', count: projects.value.length },
  ...statusOptions.map((s) => ({
    value: s.value,
    label: s.label,
    count: projects.value.filter((p) => p.status === s.value).length
  }))
])

const groupByIndustry = computed(() => {
  const counts = {}
  projects.value.forEach((p) => {
    const t = p.industryType || '其他'
    counts[t] = (counts[t] || 0) + 1
  })
  return [
    { value: '', count: projects.value.length },
    ...Object.keys(counts).map((t) => ({ value: t, count: counts[t] }))
  ]
})

const switchStatus = (v) => {
  statusFilter.value = v
}

const switchIndustry = (v) => {
  industryFilter.value = v
}

/** W4：操作列折叠菜单命令分发（复用原有确认与跳转逻辑） */
const onOp = (row, cmd) => {
  const actions = {
    detail: () => goToDetail(row),
    edit: () => openEdit(row),
    result: () => goToResults(row),
    archive: () => archiveProject(row),
    restore: () => (showRecycle.value ? restoreDeleted(row) : restoreProject(row)),
    purge: () => purgeDeleted(row),
    delete: () => deleteProject(row)
  }
  const fn = actions[cmd]
  if (fn) fn()
}

// ===== W4：行业分布环形图（点击扇区/图例联动行业筛选） =====
const industryChartEl = ref(null)
let industryChart = null
const INDUSTRY_COLORS = ['#C99B3F', '#D9A966', '#A38C5C', '#E8C877', '#8F7A5C', '#BF8B4E']

const industryDistribution = computed(() => {
  const counts = {}
  projects.value.forEach((p) => {
    const t = p.industryType || '其他'
    counts[t] = (counts[t] || 0) + 1
  })
  const items = Object.entries(counts)
    .sort((a, b) => b[1] - a[1])
    .map(([name, count], i) => ({
      name,
      count,
      color: INDUSTRY_COLORS[i % INDUSTRY_COLORS.length]
    }))
  return { total: projects.value.length, items }
})

const renderIndustryChart = () => {
  if (!industryChartEl.value) return
  if (!industryChart) industryChart = echarts.init(industryChartEl.value, chartThemeName())
  const { total, items } = industryDistribution.value
  industryChart.setOption(
    {
      animationDuration: 600,
      tooltip: {
        trigger: 'item',
        formatter: '{b}：{c} 项（{d}%）',
        backgroundColor: 'rgba(43,36,28,.92)',
        borderColor: 'rgba(201,155,63,.4)',
        textStyle: { color: '#f4ead6' }
      },
      title: {
        text: String(total),
        subtext: '项目总数',
        left: 'center',
        top: '34%',
        textStyle: { fontSize: 24, fontWeight: 700, color: 'var(--tg-text-primary)', lineHeight: 30 },
        subtextStyle: { fontSize: 11, color: 'var(--tg-text-secondary)' }
      },
      series: [
        {
          type: 'pie',
          radius: ['58%', '82%'],
          center: ['50%', '50%'],
          padAngle: 3,
          itemStyle: { borderRadius: 8, borderColor: 'rgba(255,244,224,.9)', borderWidth: 2 },
          label: { show: false },
          emphasis: {
            scaleSize: 6,
            itemStyle: { shadowBlur: 18, shadowColor: 'rgba(201,155,63,.45)' }
          },
          data: items.map((i) => ({ name: i.name, value: i.count }))
        }
      ]
    },
    true
  )
}

const onChartResize = () => industryChart && industryChart.resize()

watch(projects, () => nextTick(renderIndustryChart))
watch(showRecycle, () => nextTick(renderIndustryChart))

onChartThemeChange(() => { industryChart?.dispose(); industryChart = null; renderIndustryChart() })

onMounted(() => {
  window.addEventListener('resize', onChartResize)
  nextTick(renderIndustryChart)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', onChartResize)
  if (industryChart) industryChart.dispose()
})
// W4：行业分布联动（触发 dev 热更新回归校验）

// W3-01/R13：列显隐配置（操作列常驻不参与隐藏）
const colDefs = [
  { key: 'projectName', label: '项目名称' },
  { key: 'industry', label: '行业类型' },
  { key: 'status', label: '状态' },
  { key: 'health', label: '健康分' },
  { key: 'requirementCount', label: '需求数' },
  { key: 'defectCount', label: '缺陷数' },
  { key: 'coverageRate', label: '覆盖率' },
  { key: 'createTime', label: '创建时间' }
]
const visibleCols = ref(colDefs.map((c) => c.key))

const filteredProjects = computed(() => {
  let list = projects.value
  if (showRecycle.value) return list
  if (statusFilter.value) {
    list = list.filter((p) => p.status === statusFilter.value)
  }
  if (industryFilter.value) {
    list = list.filter((p) => (p.industryType || '其他') === industryFilter.value)
  }
  if (keyword.value.trim()) {
    const kw = keyword.value.trim().toLowerCase()
    list = list.filter((p) => (p.projectName || '').toLowerCase().includes(kw))
  }
  return list
})

/** 统计：已分析项目数 / 累计缺陷 / 平均覆盖率 */
const analyzedCount = computed(
  () => projects.value.filter((p) => p.status === 'analyzed').length
)
const totalDefects = computed(() =>
  projects.value.reduce((sum, p) => sum + (p.defectCount || 0), 0)
)
const avgCoverage = computed(() => {
  const list = projects.value.filter((p) => p.coverageRate != null)
  if (!list.length) return 0
  const avg = list.reduce((s, p) => s + p.coverageRate, 0) / list.length
  return Math.round(avg * 100)
})

// ===== KPI（数字平滑滚动） =====
const projectCountDisp = useCountUp(computed(() => projects.value.length))
const analyzedCountDisp = useCountUp(analyzedCount)
const totalDefectsDisp = useCountUp(totalDefects)
const avgCoverageDisp = useCountUp(avgCoverage)

const kpis = computed(() => [
  {
    key: 'project', tone: 'gold', icon: 'FolderOpened', value: projectCountDisp.value, suffix: '',
    label: '项目总数', meta: '当前可见项目'
  },
  {
    key: 'analyzed', tone: 'green', icon: 'CircleCheck', value: analyzedCountDisp.value, suffix: '',
    label: '已分析项目', meta: '已完成一致性验证'
  },
  {
    key: 'defects', tone: 'coral', icon: 'Warning', value: totalDefectsDisp.value, suffix: '',
    label: '累计缺陷', meta: '需求-代码偏差项'
  },
  {
    key: 'coverage', tone: 'amber', icon: 'DataLine', value: avgCoverageDisp.value, suffix: '%',
    label: '平均覆盖率', meta: '需求覆盖比例'
  }
])

const showCreateDialog = ref(false)
const formRef = ref(null)
/** 当前编辑的项目（null 表示创建模式） */
const editingProject = ref(null)

const newProject = ref({
  projectName: '',
  industryType: '',
  techStack: 'Java 8 / SpringBoot',
  description: ''
})

const rules = {
  projectName: [{ required: true, message: '请输入项目名称', trigger: 'blur' }]
}

// ===== W6：内置样例一键导入 =====
const sampleAssets = ref([])
const importingSample = ref('')
const doImportSample = async (a) => {
  importingSample.value = a.key
  try {
    const p = await insightApi.importSample(a.key, a.key)
    ElMessage.success(`已从样例创建项目「${p.projectName}」`)
    showCreateDialog.value = false
    await loadProjects()
  } catch (e) {
    ElMessage.error(e?.message || '样例导入失败')
  } finally {
    importingSample.value = ''
  }
}

// ===== 个性化增强 BATCH-4：项目批量操作（归档/删除，逐条归属校验） =====
const projectTableRef = ref(null)
const selectedProjects = ref([])
const batchBusy = ref(false)
const onSelectionChange = (rows) => { selectedProjects.value = rows }
const clearSelection = () => {
  projectTableRef.value?.clearSelection?.()
  selectedProjects.value = []
}
const doBatch = async (action) => {
  const ids = selectedProjects.value.map((r) => r.id)
  if (!ids.length) return
  const labelMap = { archive: '批量归档', delete: '批量删除（进入回收站，可恢复）' }
  try {
    await confirmDanger({
      title: '批量操作确认',
      message: `确定对选中的 ${ids.length} 个项目执行${labelMap[action]}？`,
      confirmText: '确定执行'
    })
  } catch (e) { return }
  batchBusy.value = true
  try {
    const res = await projectApi.batch(action, ids)
    const ok = res?.success ?? ids.length
    const failed = res?.failed || []
    if (failed.length) {
      ElMessage.warning(`${labelMap[action]}：成功 ${ok} 个，失败 ${failed.length} 个（无权限或状态冲突）`)
    } else {
      ElMessage.success(`${labelMap[action]}成功（${ok} 个）`)
    }
    clearSelection()
    await loadProjects()
  } catch (e) {
    ElMessage.error(e?.message || '批量操作失败')
  } finally {
    batchBusy.value = false
  }
}

const loadProjects = async () => {
  loading.value = true
  try {
    // 不传 userId：后端数据隔离（普通用户强制只查自己，管理员返回全部项目）
    projects.value = await projectApi.list()
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

/** FUN-15：回收站分页状态 */
const recyclePage = ref(1)
const recycleSize = ref(10)
const recycleTotal = ref(0)

/** GAP-012：加载回收站项目（FUN-15：真分页，替代硬编码 page:1,size:100） */
const loadRecycle = async () => {
  loading.value = true
  try {
    const res = await projectApi.listDeleted({ page: recyclePage.value, size: recycleSize.value })
    projects.value = (res && res.records) || res || []
    // total 强转 Number，避免 ElPagination "Expected Number, got String" 告警
    recycleTotal.value = (res && res.total) != null ? Number(res.total) : projects.value.length
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

const toggleRecycle = () => {
  showRecycle.value = !showRecycle.value
  if (showRecycle.value) {
    recyclePage.value = 1 // FUN-15：进入回收站回到第一页
    loadRecycle()
  } else {
    loadProjects()
  }
}

/** GAP-012：恢复回收站项目 */
const restoreDeleted = async (row) => {
  try {
    await projectApi.restoreDeleted(row.id)
    ElMessage.success('已恢复到项目列表')
    loadRecycle()
  } catch (e) {
    if (e?.message) ElMessage.error(e.message)
  }
}

/** GAP-012：彻底删除回收站项目（二次确认） */
const purgeDeleted = async (row) => {
  try {
    await confirmDanger({
      title: '危险操作 · 不可恢复',
      message: `彻底删除项目"${row.projectName}"后，其数据（需求/代码/分析结果）将不可恢复，确定继续吗？`,
      confirmText: '彻底删除'
    })
    await projectApi.purgeDeleted(row.id)
    ElMessage.success('已彻底删除')
    loadRecycle()
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

const statusText = (status) => {
  const map = { created: '已创建', running: '分析中', analyzed: '已完成', failed: '失败', archived: '已归档' }
  return map[status] || status
}

/** W1-06/O10：项目健康分（单一聚合指标 0-100） */
const health = (row) => computeHealthScore(row)

const archiveProject = async (row) => {
  try {
    await confirmReversible({ message: `确定要归档项目"${row.projectName}"吗？归档后可随时恢复。`, confirmText: '归档' })
    await projectApi.archive(row.id)
    ElMessage.success('归档成功')
    loadProjects()
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

const restoreProject = async (row) => {
  try {
    await projectApi.restore(row.id)
    ElMessage.success('恢复成功')
    loadProjects()
  } catch (e) {
    if (e?.message) ElMessage.error(e.message)
  }
}

const goToDetail = (row) => {
  router.push(`/project/${row.id}`)
}

const goToResults = (row) => {
  router.push(`/results/${row.id}`)
}

const deleteProject = async (row) => {
  try {
    await confirmReversible({ message: `确定要删除项目"${row.projectName}"吗？删除后将移入回收站，可在回收站中恢复。`, confirmText: '删除' })
    await projectApi.delete(row.id)
    ElMessage.success('已移入回收站')
    loadProjects()
  } catch (e) {
    if (e !== 'cancel') console.error(e)
  }
}

/** 打开创建弹窗：重置表单 */
const openCreate = () => {
  editingProject.value = null
  newProject.value = { projectName: '', industryType: '', techStack: 'Java 8 / SpringBoot', description: '' }
  showCreateDialog.value = true
}

/** 打开编辑弹窗：回填项目当前信息 */
const openEdit = (row) => {
  editingProject.value = row
  newProject.value = {
    projectName: row.projectName,
    industryType: row.industryType,
    techStack: row.techStack || '',
    description: row.description || ''
  }
  showCreateDialog.value = true
}

const submitProject = async () => {
  try {
    await formRef.value.validate()
    if (editingProject.value) {
      await projectApi.update({ ...editingProject.value, ...newProject.value })
      ElMessage.success('保存成功')
    } else {
      const userStr = localStorage.getItem('userInfo')
      if (userStr) newProject.value.createUserId = JSON.parse(userStr).id
      await projectApi.create(newProject.value)
      ElMessage.success('创建成功')
    }
    showCreateDialog.value = false
    loadProjects()
  } catch (e) {
    if (e?.message) ElMessage.error(e.message)
    console.error(e)
  }
}

onMounted(() => {
  loadProjects()
  // W6：样例资产清单（导入失败静默，不影响主列表）
  insightApi.evalAssets().then(a => { sampleAssets.value = a || [] }).catch(() => {})
})
</script>

<style scoped>
/* ===== 页头 ===== */
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
  margin-bottom: 26px;
}

.page-header__greet {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13.5px;
  font-weight: 500;
  color: var(--tg-accent);
  margin-bottom: 8px;
}

.page-header__greet-icon {
  font-size: 15px;
}

.page-header__title {
  margin: 0;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.15;
  color: var(--tg-text-primary);
  background: linear-gradient(115deg, #6E521A 0%, #8F6B22 50%, #B98A2F 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.page-header__desc {
  margin: 8px 0 0;
  font-size: 14px;
  color: var(--tg-text-secondary);
}

.page-header__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

.page-header__btn-ghost {
  border-color: rgba(143, 107, 34, 0.22);
  color: var(--tg-accent);
  background: rgba(255, 255, 255, 0.6);
}

/* ===== KPI 统计行（与 Dashboard 同款） ===== */
.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 18px;
  margin-bottom: 26px;
}

.kpi-card {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 22px;
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(var(--tg-blur)) saturate(1.4);
  -webkit-backdrop-filter: blur(var(--tg-blur)) saturate(1.4);
  border: 1px solid var(--tg-border);
  border-radius: 20px;
  box-shadow: var(--tg-shadow-card);
  transition: transform 0.35s var(--tg-ease), box-shadow 0.35s ease, border-color 0.35s ease;
}

.kpi-card:hover {
  transform: translateY(-4px);
  border-color: rgba(201, 155, 63, 0.4);
  box-shadow: 0 18px 44px rgba(60, 45, 25, 0.12), 0 0 0 1px rgba(201, 155, 63, 0.1);
}

.kpi-card__tile {
  width: 52px;
  height: 52px;
  border-radius: 16px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: transform 0.35s var(--tg-ease-spring);
}

.kpi-card:hover .kpi-card__tile {
  transform: scale(1.08) rotate(-4deg);
}

.kpi-card__tile--gold {
  background: linear-gradient(135deg, rgba(201, 155, 63, 0.16), rgba(232, 200, 119, 0.22));
  color: var(--tg-accent);
}

.kpi-card__tile--green {
  background: linear-gradient(135deg, rgba(154, 156, 107, 0.16), rgba(168, 185, 138, 0.2));
  color: var(--tg-success);
}

.kpi-card__tile--amber {
  background: linear-gradient(135deg, rgba(232, 155, 60, 0.16), rgba(232, 200, 119, 0.2));
  color: #b5731f;
}

.kpi-card__tile--coral {
  background: linear-gradient(135deg, rgba(176, 101, 63, 0.14), rgba(194, 94, 76, 0.16));
  color: var(--tg-pink);
}

.kpi-card__body {
  min-width: 0;
}

.kpi-card__num {
  display: flex;
  align-items: baseline;
  gap: 4px;
  font-size: 34px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.1;
  color: var(--tg-text-primary);
  font-variant-numeric: tabular-nums;
}

.kpi-card__suffix {
  font-size: 15px;
  font-weight: 600;
  color: var(--tg-text-secondary);
}

.kpi-card__label {
  margin-top: 4px;
  font-size: 14px;
  font-weight: 500;
  color: var(--tg-text-primary);
}

.kpi-card__meta {
  margin-top: 2px;
  font-size: 12px;
  color: var(--tg-text-secondary);
}

/* ===== 表格面板 ===== */
.table-panel {
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(var(--tg-blur)) saturate(1.4);
  -webkit-backdrop-filter: blur(var(--tg-blur)) saturate(1.4);
  border: 1px solid var(--tg-border);
  border-radius: 20px;
  padding: 24px;
  box-shadow: var(--tg-shadow-card);
}

.table-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 18px;
}

.table-panel__title h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--tg-text-primary);
  display: flex;
  align-items: center;
  gap: 8px;
}

.table-panel__title p {
  margin: 3px 0 0;
  font-size: 13px;
  color: var(--tg-text-secondary);
}

.section-title__ic {
  color: var(--tg-accent);
  background: var(--el-color-primary-light-9);
  border-radius: 8px;
  padding: 4px;
  box-sizing: content-box;
}

.table-panel__tools {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.status-filter {
  width: 140px;
}

.search-input {
  width: 220px;
}

/* W3-06/R19：分组导航（状态 / 行业双层组） */
.group-bar {
  display: flex;
  flex-direction: column;
  gap: 8px;
  margin-bottom: 18px;
  padding: 12px 16px;
  border-radius: 16px;
  background: var(--tg-gradient-soft);
  border: 1px solid var(--tg-border);
}

.group-row {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.group-row__label {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  width: 64px;
  flex-shrink: 0;
  font-size: 12px;
  font-weight: 600;
  color: var(--tg-text-secondary);
}

.group-row__label .el-icon {
  color: var(--tg-accent);
}

.group-row__chips {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  min-width: 0;
}

.group-row__chips .tg-chip {
  padding: 5px 13px;
  font-size: 12.5px;
}

.group-row__chips .tg-chip em {
  font-style: normal;
  font-size: 11px;
  opacity: 0.75;
  margin-left: 2px;
  font-variant-numeric: tabular-nums;
}

/* 表格美化 */
.project-table :deep(.el-table) {
  --el-table-border-color: rgba(0, 0, 0, 0.05);
  --el-table-header-bg-color: transparent;
  --el-table-row-hover-bg-color: rgba(201, 155, 63, 0.06);
  background: transparent;
}

/* 行 hover：阴影抬升 + 左侧金色指示条（不用 transform，避免破坏固定列 sticky 定位） */
.project-table :deep(.el-table__body tr) {
  transition: box-shadow 0.3s ease;
}

.project-table :deep(.el-table__body tr:hover) {
  box-shadow: 0 2px 0 rgba(201, 155, 63, 0.28), 0 8px 22px rgba(60, 45, 25, 0.1);
}

.project-table :deep(.el-table__body tr:hover > td.el-table__cell:first-child) {
  box-shadow: inset 3px 0 0 var(--tg-accent);
}

.project-table :deep(.el-table th.el-table__cell) {
  font-size: 12px;
  font-weight: 600;
  color: var(--tg-text-secondary);
  background: rgba(0, 0, 0, 0.02);
  transition: color 0.25s ease;
}

.project-table :deep(.el-table__cell) {
  padding: 12px 0;
}

/* ===== W4：操作列折叠为一个"操作"下拉按钮 ===== */
.op-btn {
  height: 30px;
  padding: 0 13px;
  font-size: 12.5px;
  font-weight: 500;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  white-space: nowrap;
  transition: transform 0.25s var(--tg-ease), box-shadow 0.25s ease, background 0.25s ease;
}

.op-btn :deep(.el-icon) {
  font-size: 14px;
}

.op-btn:hover {
  transform: translateY(-1px);
  box-shadow: 0 4px 12px rgba(60, 45, 25, 0.14);
}

.op-btn--more {
  background: rgba(201, 155, 63, 0.12);
  border: 1px solid rgba(201, 155, 63, 0.3);
  color: var(--tg-accent);
}

.op-btn--more:hover {
  background: rgba(201, 155, 63, 0.2);
  border-color: rgba(201, 155, 63, 0.42);
  color: var(--tg-accent-strong);
}

/* 下拉菜单（teleport 到 body，须全局选择器） */
:global(.op-menu) {
  padding: 6px;
  border-radius: 14px;
  border: 1px solid rgba(201, 155, 63, 0.22);
  background: rgba(255, 250, 240, 0.97);
  box-shadow: 0 14px 36px rgba(43, 36, 28, 0.16);
  backdrop-filter: blur(10px);
}

:global(.op-menu .el-dropdown-menu__item) {
  display: flex;
  align-items: center;
  gap: 8px;
  height: 36px;
  line-height: 36px;
  padding: 0 14px;
  border-radius: 9px;
  font-size: 13px;
  font-weight: 500;
  color: var(--tg-text-primary);
}

:global(.op-menu .el-dropdown-menu__item .el-icon) {
  font-size: 15px;
  color: var(--tg-slate);
}

:global(.op-menu .el-dropdown-menu__item:hover:not(.is-disabled)) {
  background: rgba(201, 155, 63, 0.1);
  color: var(--tg-accent-strong);
}

:global(.op-menu .op-menu__danger) {
  color: #c25e4c;
}

:global(.op-menu .op-menu__danger .el-icon) {
  color: #c25e4c;
}

:global(.op-menu .op-menu__danger:hover:not(.is-disabled)) {
  background: rgba(194, 94, 76, 0.1);
  color: #9a3f30;
}

.project-name {
  display: flex;
  align-items: center;
  gap: 12px;
}

.project-name__tile {
  width: 34px;
  height: 34px;
  border-radius: 11px;
  background: var(--el-color-primary-light-9);
  color: var(--tg-accent);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: transform 0.3s var(--tg-ease-spring), box-shadow 0.3s ease, background 0.3s ease;
}

.project-name__text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.project-name__text b {
  font-size: 14px;
  font-weight: 600;
  color: var(--tg-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.25s ease;
}

.project-name__text small {
  font-size: 11.5px;
  color: var(--tg-slate);
  margin-top: 1px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* 行 hover：项目名图标高亮放大 + 名称变金 */
.project-name:hover .project-name__tile {
  transform: scale(1.12) rotate(-6deg);
  box-shadow: var(--tg-glow-accent);
  background: var(--el-color-primary-light-8);
}

.project-name:hover .project-name__text b {
  color: var(--tg-accent);
}

/* 状态胶囊（与 Dashboard 同款） */
.status-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 11px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 500;
  transition: transform 0.25s var(--tg-ease-spring), box-shadow 0.25s ease;
}

.status-pill:hover {
  transform: translateY(-1px) scale(1.05);
  box-shadow: 0 4px 12px rgba(60, 45, 25, 0.12);
}

.status-pill::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.status-pill--analyzed {
  background: rgba(154, 156, 107, 0.14);
  color: #55682e;
}
.status-pill--analyzed::before {
  background: var(--tg-success);
}

.status-pill--running {
  background: rgba(232, 155, 60, 0.14);
  color: #9a5d12;
}
.status-pill--running::before {
  background: var(--tg-amber);
}

.status-pill--failed {
  background: rgba(194, 94, 76, 0.12);
  color: #9a3f30;
}
.status-pill--failed::before {
  background: var(--tg-danger);
}

.status-pill--created {
  background: rgba(0, 0, 0, 0.05);
  color: var(--tg-text-secondary);
}
.status-pill--created::before {
  background: var(--tg-slate);
}

.status-pill--archived {
  background: rgba(154, 155, 128, 0.12);
  color: #6a6b56;
}
.status-pill--archived::before {
  background: var(--tg-slate);
}

/* ===== 健康分徽章（W1-06/O10） ===== */
.health-cell {
  display: inline-flex;
  align-items: baseline;
  gap: 5px;
  padding: 5px 12px;
  border-radius: 999px;
  border: 1px solid transparent;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  line-height: 1;
  transition: transform 0.25s var(--tg-ease-spring), box-shadow 0.25s ease;
}

.health-cell:hover {
  transform: translateY(-1px) scale(1.06);
  box-shadow: 0 4px 12px rgba(60, 45, 25, 0.12);
}

.health-cell__score {
  font-size: 14px;
  font-weight: 700;
  line-height: 1;
}

.health-cell__level {
  font-size: 11px;
  font-weight: 500;
  opacity: 0.85;
  white-space: nowrap;
  letter-spacing: 0.02em;
}

.health-cell--success {
  background: rgba(154, 156, 107, 0.16);
  border-color: rgba(154, 156, 107, 0.3);
  color: #55682e;
}

.health-cell--good {
  background: rgba(217, 169, 102, 0.16);
  border-color: rgba(217, 169, 102, 0.32);
  color: #8a651a;
}

.health-cell--warn {
  background: rgba(232, 155, 60, 0.16);
  border-color: rgba(232, 155, 60, 0.3);
  color: #9a5d12;
}

.health-cell--danger {
  background: rgba(194, 94, 76, 0.12);
  border-color: rgba(194, 94, 76, 0.26);
  color: #9a3f30;
}

.health-cell--empty {
  padding: 4px 8px;
  background: rgba(0, 0, 0, 0.04);
  border-color: transparent;
  color: var(--tg-slate);
}

/* 数字单元格 */
.num-cell {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 28px;
  padding: 2px 8px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--tg-text-primary);
  font-variant-numeric: tabular-nums;
  background: rgba(0, 0, 0, 0.035);
  transition: background 0.25s ease, color 0.25s ease, transform 0.25s var(--tg-ease);
}

.num-cell:hover {
  background: var(--el-color-primary-light-9);
  color: var(--tg-accent);
  transform: scale(1.08);
}

.num-cell.is-warn {
  color: var(--tg-pink);
}

.num-cell.is-warn:hover {
  background: rgba(194, 94, 76, 0.1);
  color: var(--tg-danger);
}

/* 覆盖率迷你进度条 */
.rate-cell {
  display: flex;
  align-items: center;
  gap: 10px;
}

.rate-cell__track {
  flex: 1;
  height: 6px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.06);
  overflow: hidden;
  min-width: 56px;
}

.rate-cell__track i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #9A9C6B, #6B8E4E);
  transform-origin: left;
  animation: rate-grow 1s var(--tg-ease) both;
  transition: filter 0.25s ease;
}

.rate-cell:hover .rate-cell__track i {
  filter: brightness(1.12);
}

.rate-cell span {
  font-size: 12.5px;
  color: var(--tg-text-secondary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  transition: color 0.25s ease;
}

.rate-cell:hover span {
  color: var(--tg-accent);
}

.rate-cell__empty {
  color: var(--tg-slate);
}

@keyframes rate-grow {
  from {
    transform: scaleX(0);
  }
  to {
    transform: scaleX(1);
  }
}

/* 分页 */
.table-panel__pager {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}

/* ===== W4：行业分布环形图 + 表格双栏主区 ===== */
.explore-row {
  display: flex;
  gap: 24px;
  align-items: flex-start;
}

.explore-row__table {
  flex: 1;
  min-width: 0;
}

.industry-card {
  width: 296px;
  flex-shrink: 0;
  padding: 18px 20px 14px;
  background: linear-gradient(160deg, rgba(255, 255, 255, 0.82), rgba(255, 246, 230, 0.5));
  border: 1px solid rgba(201, 155, 63, 0.18);
  border-radius: 18px;
  box-shadow: var(--tg-shadow-soft);
  backdrop-filter: blur(12px);
}

.industry-card__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
}

.industry-card__head h4 {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--tg-text-primary);
}

.industry-chart {
  height: 216px;
}

.industry-legend {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  justify-content: center;
  margin-top: 4px;
}

.industry-legend__item {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  border: 1px solid transparent;
  border-radius: 999px;
  background: transparent;
  cursor: pointer;
  font-size: 12px;
  color: var(--tg-text-secondary);
  transition: all 0.22s ease;
}

.industry-legend__item i {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.industry-legend__item em {
  font-style: normal;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.industry-legend__item:hover {
  background: rgba(201, 155, 63, 0.1);
  color: var(--tg-text-primary);
}

.industry-legend__item.is-active {
  border-color: rgba(201, 155, 63, 0.4);
  background: rgba(201, 155, 63, 0.12);
  color: var(--tg-accent-strong);
  font-weight: 600;
}

/* 窄屏：行业图与表格改为上下堆叠 */
@media (max-width: 1280px) {
  .explore-row {
    flex-direction: column;
    gap: 18px;
  }

  .industry-card {
    width: 100%;
  }

  .industry-chart {
    height: 200px;
  }
}

/* 对话框 */
.create-dialog :deep(.el-dialog) {
  border-radius: 20px;
}

/* 窄屏 */
@media (max-width: 1280px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 720px) {
  .kpi-row {
    grid-template-columns: 1fr;
  }

  .page-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .table-panel__tools {
    width: 100%;
  }

  .search-input {
    flex: 1;
  }
}
/* ===== W6：样例一键导入条（创建对话框内） ===== */
.create-sample-strip { display: flex; align-items: stretch; gap: 9px; flex-wrap: wrap; margin-bottom: 14px; }
.create-sample-strip__label {
  flex-basis: 100%; font-size: 11px; font-weight: 600; letter-spacing: 0.1em; color: var(--tg-slate);
}
.create-sample-chip {
  display: flex; flex-direction: column; align-items: flex-start; gap: 1px;
  flex: 1; min-width: 140px; cursor: pointer; text-align: left;
  border: 1px dashed rgba(201, 155, 63, 0.45); border-radius: 11px;
  background: rgba(201, 155, 63, 0.06); padding: 9px 12px;
  transition: all 0.2s var(--tg-ease-spring);
}
.create-sample-chip:hover:not(:disabled) {
  border-style: solid; border-color: rgba(201, 155, 63, 0.6);
  background: rgba(201, 155, 63, 0.12); transform: translateY(-1px);
}
.create-sample-chip:disabled { opacity: 0.6; cursor: wait; }
.create-sample-chip b { font-size: 12.5px; color: var(--tg-text-primary); }
.create-sample-chip small { font-size: 10.5px; color: var(--tg-text-secondary); }
.create-sample-chip em {
  font-style: normal; font-size: 10.5px; font-weight: 600; color: var(--tg-accent);
}
/* ===== 个性化增强 BATCH-4：批量操作工具条 ===== */
.batch-bar {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 14px;
  margin-bottom: 10px;
  border-radius: 12px;
  border: 1px solid rgba(143, 107, 34, 0.28);
  background: var(--el-color-primary-light-9);
}
.batch-bar__count { font-size: 13px; color: var(--tg-text-secondary); }
.batch-bar__count b { color: var(--tg-accent); font-weight: 700; }
</style>