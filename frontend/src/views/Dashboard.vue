<template>
  <div class="dashboard">
    <!-- ===== 页头：问候 + 标题 + 操作 ===== -->
    <div class="page-header tg-fade-up">
      <div class="page-header__main">
        <div class="page-header__greet">
          <el-icon class="page-header__greet-icon"><Sunny /></el-icon>
          {{ greeting }}，{{ displayName }}
        </div>
        <h2 class="page-header__title">项目工作台</h2>
        <p class="page-header__desc">需求-代码一致性验证与缺陷自动检测，项目全景一览</p>
      </div>
      <div class="page-header__actions">
        <!-- 个性化增强 BATCH-1：工作台布局自定义（卡片显隐 + 排序） -->
        <el-popover placement="bottom-end" :width="228" trigger="click" popper-class="appearance-popper">
          <template #reference>
            <el-button size="large" round class="page-header__btn-ghost">
              <el-icon style="margin-right: 6px"><Setting /></el-icon>自定义
            </el-button>
          </template>
          <div class="dash-customize">
            <div
              v-for="c in sortedDashCards"
              :key="c.key"
              class="dash-customize__row"
              :class="{ 'is-dragging': dragKey === c.key }"
              draggable="true"
              @dragstart="onDashDragStart(c.key)"
              @dragover.prevent
              @drop.prevent="onDashDrop(c.key)"
              @dragend="dragKey = ''"
            >
              <el-checkbox
                :model-value="!dashHidden.includes(c.key)"
                @change="toggleDashCard(c.key)"
              >{{ c.label }}</el-checkbox>
              <span class="dash-customize__ops">
                <button type="button" class="dash-customize__op" aria-label="上移" @click="moveDashCard(c.key, -1)">
                  <el-icon :size="13"><Top /></el-icon>
                </button>
                <button type="button" class="dash-customize__op" aria-label="下移" @click="moveDashCard(c.key, 1)">
                  <el-icon :size="13"><Bottom /></el-icon>
                </button>
              </span>
            </div>
            <el-button link size="small" class="dash-customize__reset" @click="resetDashCards">恢复默认布局</el-button>
          </div>
        </el-popover>
        <el-button size="large" round class="page-header__btn-ghost" @click="$router.push('/projects')">
          <el-icon style="margin-right: 6px"><FolderOpened /></el-icon>项目管理
        </el-button>
        <el-button type="primary" size="large" round @click="showCreateDialog = true">
          <el-icon style="margin-right: 6px"><Plus /></el-icon>新建项目
        </el-button>
      </div>
    </div>

    <!-- ===== KPI 统计行（首屏骨架 → 数据就绪后入场） ===== -->
    <div v-if="pageLoading" class="kpi-row">
      <div v-for="i in 4" :key="i" class="kpi-card kpi-card--skeleton">
        <el-skeleton animated>
          <template #template>
            <div class="kpi-skeleton">
              <el-skeleton-item variant="circle" style="width: 46px; height: 46px" />
              <div class="kpi-skeleton__lines">
                <el-skeleton-item variant="h3" style="width: 58%; height: 22px" />
                <el-skeleton-item variant="text" style="width: 42%" />
              </div>
            </div>
          </template>
        </el-skeleton>
      </div>
    </div>
    <div v-else class="kpi-row">
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
          <div class="kpi-card__meta">
            <span v-if="k.trend" class="kpi-card__trend" :class="'is-' + k.trendDir">
              <el-icon :size="12"><component :is="k.trendDir === 'up' ? 'Top' : 'Bottom'" /></el-icon>
              {{ k.trend }}
            </span>
            <span v-else>{{ k.meta }}</span>
          </div>
        </div>
      </div>
    </div>

    <!-- ===== 主视觉网格：图表优先（项目总览 / 使用趋势） ===== -->
    <div class="dashboard-grid">
      <!-- 主视觉：项目状态 + 覆盖率（玻璃大卡，纯图表可视化） -->
      <section v-show="!dashHidden.includes('overview')" :style="{ order: dashOrder.overview }" class="glass-card overview-card tg-fade-up">
        <div class="section-title">
          <div class="section-title__left">
            <h3><el-icon class="section-title__ic" :size="17"><DataAnalysis /></el-icon>项目总览</h3>
            <p>项目状态分布与平均覆盖率</p>
          </div>
          <span class="live-badge"><span class="tg-live-dot"></span>实时</span>
        </div>
        <div class="overview-body">
          <!-- 左：项目状态分布环形图 -->
          <div ref="statusChartRef" class="status-chart" aria-label="项目状态分布图"></div>
          <!-- 中：状态图例 + 百分比条，填满中间留白 -->
          <div class="status-legend">
            <div v-for="s in statusDist" :key="s.label" class="status-legend__item">
              <span class="status-legend__head">
                <i :style="{ background: s.color }"></i>
                <span>{{ s.label }}</span>
                <b>{{ s.count }} 个</b>
              </span>
              <div class="status-legend__track">
                <i :style="{ width: s.pct + '%', background: s.color }"></i>
              </div>
            </div>
          </div>
          <!-- 右：覆盖率环形图 + 摘要 -->
          <div class="overview-coverage">
            <div ref="coverageChartRef" class="overview-ring" aria-label="平均覆盖率环形图"></div>
            <div class="coverage-summary">
              <div class="coverage-summary__head">
                <el-icon :size="15"><DataLine /></el-icon>
                <span>质量摘要</span>
              </div>
              <div class="coverage-summary__item">
                <span>需求条目</span>
                <b>{{ totalRequirements }}</b>
              </div>
              <div class="coverage-summary__item">
                <span>已上传代码工程</span>
                <b>{{ totalCodeProjects }}</b>
              </div>
              <div class="coverage-summary__item">
                <span>平均缺陷 / 项目</span>
                <b>{{ avgDefectPerProject }}</b>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- 副卡：当前进行（宽卡：项目信息 + 横向流程管线 + 下一步直达） -->
      <section v-show="!dashHidden.includes('current')" :style="{ order: dashOrder.current }" class="glass-card current-card tg-fade-up">
        <div class="section-title">
          <div class="section-title__left">
            <h3><el-icon class="section-title__ic" :size="17"><Refresh /></el-icon>当前进行</h3>
            <p>最近活跃项目的进展</p>
          </div>
        </div>
        <div v-if="guideProject" class="current-info">
          <div class="current-head">
            <div class="current-name">
              <span class="tg-live-dot tg-live-dot--indigo"></span>
              <span class="current-name__text">{{ guideProject.projectName }}</span>
            </div>
            <div class="current-meta">
              <span v-if="guideProject.industryType" class="current-meta__chip">{{ guideProject.industryType }}</span>
              <span class="status-pill" :class="'status-pill--' + guideProject.status">{{ statusText(guideProject.status) }}</span>
            </div>
          </div>
          <div class="current-pipeline" role="list" aria-label="分析流程进度">
            <button
              v-for="(step, idx) in guideSteps"
              :key="idx"
              type="button"
              class="pipe-step"
              :class="{ 'is-done': idx < guideActive, 'is-current': idx === guideActive }"
              :title="step.title + '：' + step.description"
              @click="handleStepClick(idx)"
            >
              <span class="pipe-step__dot">
                <el-icon v-if="idx < guideActive" :size="11"><Check /></el-icon>
                <template v-else>{{ idx + 1 }}</template>
              </span>
              <span class="pipe-step__label">{{ step.title }}</span>
            </button>
          </div>
          <div class="current-progress">
            <div class="current-progress__head">
              <span>{{ guideActiveText }}</span>
              <b>{{ currentPct }}%</b>
            </div>
            <div class="current-progress__track">
              <i :style="{ width: currentPct + '%' }"></i>
            </div>
          </div>
          <el-button type="primary" round class="current-info__btn" @click="handleStepClick(guideActive)">
            {{ guideActive === 5 ? '查看报告' : '继续项目' }} <el-icon style="margin-left: 4px"><Right /></el-icon>
          </el-button>
        </div>
        <div v-else class="current-empty">
          <div class="current-empty__tile"><el-icon :size="26"><FolderAdd /></el-icon></div>
          <p>从创建第一个项目开始</p>
          <el-button type="primary" round @click="showCreateDialog = true">立即创建</el-button>
        </div>
      </section>

      <!-- 无卡片图表：多项目对比（宽扁，浅色底 + 细分割线） -->
      <section v-show="!dashHidden.includes('chart')" :style="{ order: dashOrder.chart }" class="chart-panel tg-fade-up">
        <div class="section-title">
          <div class="section-title__left">
            <h3><el-icon class="section-title__ic" :size="17"><Histogram /></el-icon>多项目对比</h3>
            <p>覆盖率 / 质量评分 / 缺陷数</p>
          </div>
          <div class="compare-controls">
            <el-select
              v-model="compareIds"
              multiple
              collapse-tags
              collapse-tags-tooltip
              placeholder="选择 2 个及以上项目"
              class="compare-select"
            >
              <el-option
                v-for="p in allProjects"
                :key="p.id"
                :label="p.projectName"
                :value="p.id"
                :disabled="compareIds.length >= 5 && !compareIds.includes(p.id)"
              />
            </el-select>
            <el-button type="primary" round :disabled="compareIds.length < 2" :loading="comparing" @click="doCompare">
              生成对比
            </el-button>
            <el-button type="success" round :disabled="compareIds.length < 2" :loading="exportingCompare" @click="handleExportCompare">
              导出
            </el-button>
          </div>
        </div>
        <div v-show="compareData.length > 0" ref="compareChartRef" class="compare-chart"></div>
        <div v-if="compareData.length === 0" class="compare-empty">
          <div class="compare-empty__tile"><el-icon :size="26"><PieChart /></el-icon></div>
          <p>选择项目后生成覆盖率 / 质量评分 / 缺陷数对比图</p>
        </div>
      </section>

      <!-- W2-06/R10：使用趋势（7/30 天切换，分析任务 / 登录次数） -->
      <section v-show="!dashHidden.includes('trend')" :style="{ order: dashOrder.trend }" class="trend-panel tg-fade-up">
        <div class="section-title">
          <div class="section-title__left">
            <h3><el-icon class="section-title__ic" :size="17"><TrendCharts /></el-icon>使用趋势</h3>
            <p>分析任务与登录按天统计</p>
          </div>
          <div class="compare-controls">
            <button
              v-for="d in [7, 30]"
              :key="d"
              type="button"
              class="tg-chip"
              :class="{ 'is-active': trendDays === d }"
              @click="switchTrend(d)"
            >{{ d }} 天</button>
          </div>
        </div>
        <div ref="trendPanelRef" class="trend-chart" aria-label="使用趋势图"></div>
      </section>

      <!-- 副卡：快速开始（紧凑步骤清单，进度徽章替代底部重复 CTA） -->
      <section v-show="!dashHidden.includes('quick')" :style="{ order: dashOrder.quick }" class="glass-card quick-card tg-fade-up">
        <div class="section-title">
          <div class="section-title__left">
            <h3><el-icon class="section-title__ic" :size="17"><MagicStick /></el-icon>快速开始</h3>
            <p>一步步完成分析</p>
          </div>
          <span class="quick-chip" :class="{ 'is-full': guideActive >= 5 }">
            <el-icon v-if="guideActive >= 5" :size="12"><Check /></el-icon>
            {{ guideActive }}/5
          </span>
        </div>
        <div class="guide-list">
          <button
            v-for="(step, idx) in guideSteps"
            :key="idx"
            class="guide-item"
            :class="{ 'is-done': idx < guideActive, 'is-current': idx === guideActive }"
            type="button"
            @click="handleStepClick(idx)"
          >
            <span class="guide-item__dot">
              <el-icon v-if="idx < guideActive" :size="13"><Check /></el-icon>
              <template v-else>{{ step.num }}</template>
            </span>
            <span class="guide-item__text">
              <b>{{ step.title }}</b>
              <small>{{ step.description }}</small>
            </span>
            <el-icon class="guide-item__arrow"><Right /></el-icon>
          </button>
        </div>
      </section>

      <!-- 最近项目：极简表格（跨两列） -->
      <section v-show="!dashHidden.includes('recent')" :style="{ order: dashOrder.recent }" class="recent-panel tg-fade-up">
        <div class="section-title">
          <div class="section-title__left">
            <h3><el-icon class="section-title__ic" :size="17"><Clock /></el-icon>最近项目</h3>
            <p>最近创建与更新的项目</p>
          </div>
          <el-button type="primary" link @click="$router.push('/projects')">
            查看全部 <el-icon style="margin-left: 2px"><ArrowRight /></el-icon>
          </el-button>
        </div>
        <el-table :data="projects" style="width: 100%" class="recent-table">
          <el-table-column prop="projectName" label="项目名称" min-width="180">
            <template #default="{ row }">
              <div class="recent-table__name">
                <span class="recent-table__tile"><el-icon :size="15"><FolderOpened /></el-icon></span>
                <span class="recent-table__name-text">{{ row.projectName }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column prop="industryType" label="行业类型" width="110" />
          <el-table-column prop="status" label="状态" width="105">
            <template #default="{ row }">
              <span class="status-pill" :class="'status-pill--' + row.status">{{ statusText(row.status) }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="coverageRate" label="覆盖率" width="150">
            <template #default="{ row }">
              <div v-if="row.coverageRate != null" class="recent-table__rate">
                <div class="recent-table__rate-track">
                  <i :style="{ width: (row.coverageRate * 100).toFixed(1) + '%' }"></i>
                </div>
                <span>{{ (row.coverageRate * 100).toFixed(1) }}%</span>
              </div>
              <span v-else class="recent-table__rate-empty">-</span>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" width="170">
            <template #default="{ row }">
              <span class="recent-table__time">{{ row.createTime }}</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="330" align="right">
            <template #default="{ row }">
              <div class="op-actions">
                <el-button class="op-btn op-btn--view" round @click="goToProject(row)">
                  <el-icon><View /></el-icon>查看
                </el-button>
                <el-button class="op-btn op-btn--edit" round @click="goToProject(row)">
                  <el-icon><Cpu /></el-icon>发起分析
                </el-button>
                <el-button v-if="row.status === 'analyzed'" class="op-btn op-btn--result" round @click="goToResults(row)">
                  <el-icon><DataAnalysis /></el-icon>查看报告
                </el-button>
                <el-button v-if="row.status === 'analyzed'" class="op-btn op-btn--archive" round @click="goToTraceability(row)">
                  <el-icon><Share /></el-icon>追溯
                </el-button>
              </div>
            </template>
          </el-table-column>
        </el-table>
      </section>
    </div>

    <!-- ===== 底部次要区：待办事项 / 最近动态（骨架 → 就绪） ===== -->
    <div v-if="pageLoading" class="dynamic-row dynamic-row--compact">
      <div class="glass-card todo-dyn-card todo-card dash-skeleton-card">
        <el-skeleton animated :rows="3" />
      </div>
      <div class="glass-card todo-dyn-card activity-card dash-skeleton-card">
        <el-skeleton animated :rows="5" />
      </div>
    </div>
    <div v-else class="dynamic-row dynamic-row--compact">
      <section class="glass-card todo-dyn-card todo-card tg-fade-up">
        <div class="section-title">
          <div class="section-title__left">
            <h3><el-icon class="section-title__ic" :size="17"><AlarmClock /></el-icon>待办事项</h3>
            <p>由项目状态自动派生，点击直达</p>
          </div>
          <span v-if="todos.length" class="live-badge">{{ todos.length }} 项</span>
        </div>
        <div v-if="todos.length" class="todo-list">
          <button
            v-for="t in todos"
            :key="t.projectId + ':' + t.action"
            type="button"
            class="todo-item"
            @click="goTodo(t)"
          >
            <span class="todo-item__dot" :class="'is-' + t.level"></span>
            <span class="todo-item__text">
              <b>{{ t.projectName }}</b>
              <small>{{ t.action }}</small>
            </span>
            <el-icon class="todo-item__arrow"><Right /></el-icon>
          </button>
        </div>
        <div v-else class="dynamic-empty">
          <el-icon :size="24" color="var(--tg-success)"><CircleCheck /></el-icon>
          <p>暂无待办，所有项目进展正常</p>
        </div>
      </section>

      <section class="glass-card todo-dyn-card activity-card tg-fade-up">
        <div class="section-title">
          <div class="section-title__left">
            <h3><el-icon class="section-title__ic" :size="17"><Bell /></el-icon>最近动态</h3>
            <p>操作审计实时同步</p>
          </div>
        </div>
        <div v-if="activities.length" class="activity-list">
          <div v-for="(a, i) in visibleActivities" :key="i" class="activity-item">
            <span class="activity-item__op" :class="'is-' + opTone(a.operation)">
              <el-icon :size="13"><component :is="opIcon(a.operation)" /></el-icon>
            </span>
            <div class="activity-item__body">
              <div class="activity-item__text"><b>{{ a.username }}</b> {{ a.operation }}</div>
              <div class="activity-item__time" :title="a.createTime">{{ fmtTime(a.createTime) }}</div>
            </div>
          </div>
          <div v-if="activities.length > activityLimit" class="activity-more">
            <el-button type="primary" link @click="$router.push('/audit')">
              查看全部动态 <el-icon style="margin-left: 2px"><ArrowRight /></el-icon>
            </el-button>
          </div>
        </div>
        <div v-else class="dynamic-empty">
          <el-icon :size="24"><Bell /></el-icon>
          <p>还没有动态记录</p>
        </div>
      </section>
    </div>

    <el-dialog v-model="showCreateDialog" title="创建新项目" width="520px" class="create-dialog">
      <div class="create-dialog__hint">填写项目基本信息，即可开始需求-代码一致性验证</div>
      <el-form :model="newProject" label-width="90px">
        <el-form-item label="项目名称">
          <el-input v-model="newProject.projectName" placeholder="请输入项目名称" />
        </el-form-item>
        <el-form-item label="行业类型">
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
          <el-input v-model="newProject.techStack" value="Java 8 / SpringBoot" />
        </el-form-item>
        <el-form-item label="项目描述">
          <el-input v-model="newProject.description" type="textarea" :rows="3" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showCreateDialog = false">取消</el-button>
        <el-button type="primary" round @click="createProject">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { chartColors, chartThemeName, chartText, chartTitleColor, chartFaint, chartAxisLine, chartSplitLine, chartTrack, chartTooltipBg, getChartColors, isDarkTheme } from '@/utils/echartsTheme'
import { projectApi, resultApi, exportApi, dashboardApi } from '@/api'
import { useCountUp } from '@/composables/useCountUp'
import { useTheme } from '@/composables/useTheme'
import { schedulePreferenceSave } from '@/utils/preferenceSync'

const router = useRouter()
const projects = ref([])
const allProjects = ref([])
const projectCount = ref(0)
const analyzedCount = ref(0)
const totalDefects = ref(0)
const avgCoverage = ref(0)
const showCreateDialog = ref(false)
const newProject = ref({
  projectName: '',
  industryType: '',
  techStack: 'Java 8 / SpringBoot',
  description: ''
})

// ===== W2 波：工作台聚合数据 =====
const overview = ref({ weekDelta: '', thisWeekTasks: 0, lastWeekTasks: 0 })
const todos = ref([])
const activities = ref([])
/** 最近动态最多展示条数（其余折叠到审计日志页） */
const activityLimit = 8
const visibleActivities = computed(() => activities.value.slice(0, activityLimit))

/** 操作类型 → 图标 + 色调（按 operation 文案模糊匹配，降级为 Bell） */
const opMetaMap = [
  ['启动分析', 'VideoPlay', 'analyze'],
  ['上传', 'UploadFilled', 'upload'],
  ['登录', 'User', 'login'],
  ['创建', 'Plus', 'create'],
  ['更新', 'EditPen', 'update'],
  ['导出', 'Download', 'export'],
  ['删除', 'Delete', 'remove'],
  ['备份', 'Box', 'backup'],
  ['恢复', 'RefreshRight', 'backup']
]
const opMeta = (op) => opMetaMap.find(([k]) => (op || '').includes(k)) || [null, 'Bell', 'other']
const opIcon = (op) => opMeta(op)[1]
const opTone = (op) => opMeta(op)[2]
/** 完整时间太占位，列表内只显示 MM-DD HH:mm（悬停可见全量） */
const fmtTime = (t) => (t && t.length >= 16 ? t.slice(5, 16) : t || '')
const trendDays = ref(7)
const trendData = ref({ days: [] })
const trendPanelRef = ref(null)
let trendChart = null

// ===== 页头问候 =====
const userInfo = ref(null)
const greeting = computed(() => {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 12) return '上午好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  return '晚上好'
})
const displayName = computed(() => userInfo.value?.realName || userInfo.value?.username || '朋友')

const projectCountDisp = useCountUp(projectCount)
const analyzedCountDisp = useCountUp(analyzedCount)
const avgCoverageDisp = useCountUp(avgCoverage)
const totalDefectsDisp = useCountUp(totalDefects)

// ===== KPI 统计 =====
const kpis = computed(() => {
  const delta = overview.value.weekDelta || ''
  const trendDir = delta.startsWith('-') ? 'down' : 'up'
  return [
    {
      key: 'project', tone: 'gold', icon: 'FolderOpened', value: projectCountDisp.value, suffix: '',
      label: '项目总数', meta: '全部项目'
    },
    {
      key: 'analyzed', tone: 'green', icon: 'CircleCheck', value: analyzedCountDisp.value, suffix: '',
      label: '已分析项目', meta: '完成全流程分析',
      trend: delta ? `本周分析 ${overview.value.thisWeekTasks} 次 · 较上周 ${delta}%` : '',
      trendDir
    },
    {
      key: 'coverage', tone: 'amber', icon: 'DataLine', value: avgCoverageDisp.value, suffix: '%',
      label: '平均覆盖率', meta: '需求-代码对齐'
    },
    {
      key: 'defects', tone: 'coral', icon: 'Warning', value: totalDefectsDisp.value, suffix: '',
      label: '累计发现缺陷', meta: '交付前拦截'
    }
  ]
})

// ===== 项目状态分布 =====
const statusDist = computed(() => {
  const total = projectCount.value || 1
  const cnt = (s) => allProjects.value.filter((p) => p.status === s).length
  const mk = (label, key, count, color) => ({
    label, key, count, color,
    pct: Math.round((count / total) * 100)
  })
  return [
    mk('已完成', 'analyzed', cnt('analyzed'), '#6B8E4E'),
    mk('分析中', 'running', cnt('running'), '#E89B3C'),
    mk('失败', 'failed', cnt('failed'), '#C25E4C'),
    mk('已创建', 'created', cnt('created'), '#C99B3F')
  ]
})

// ===== 覆盖率卡右侧摘要（真实数据派生） =====
const totalRequirements = computed(() =>
  allProjects.value.reduce((sum, p) => sum + (p.requirementCount || 0), 0)
)
const totalCodeProjects = computed(() =>
  allProjects.value.filter((p) => p.codeProjectPath).length
)
const avgDefectPerProject = computed(() =>
  analyzedCount.value > 0 ? (totalDefects.value / analyzedCount.value).toFixed(1) : '0.0'
)

// ===== 多项目对比（FR-PLAT-003） =====
const compareIds = ref([])
const compareData = ref([])
const comparing = ref(false)
const compareChartRef = ref(null)
let compareChart = null

// ===== 覆盖率环形图（主视觉小趋势） =====
const coverageChartRef = ref(null)
let coverageChart = null

const doCompare = async () => {
  comparing.value = true
  try {
    const data = await resultApi.compareProjects(compareIds.value)
    compareData.value = data || []
    await nextTick()
    renderCompareChart()
  } catch (e) {
    ElMessage.error(e.message || '对比查询失败')
  } finally {
    comparing.value = false
  }
}

const exportingCompare = ref(false)

/** 导出多项目对比统计Excel（FR-PLAT-003） */
const handleExportCompare = async () => {
  exportingCompare.value = true
  try {
    await exportApi.compareExcel(compareIds.value)
    ElMessage.success('对比Excel导出成功')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exportingCompare.value = false
  }
}

const renderCompareChart = () => {
  if (!compareChartRef.value || compareData.value.length === 0) return
  if (!compareChart) {
    compareChart = echarts.init(compareChartRef.value, chartThemeName())
  }
  const names = compareData.value.map(d => d.projectName)
  compareChart.setOption({
    animationDuration: 700,
    animationEasing: 'cubicOut',
    tooltip: {
      trigger: 'axis',
      axisPointer: { type: 'shadow', shadowStyle: { color: 'rgba(143,107,34,0.05)' } },
      backgroundColor: chartTooltipBg(),
      borderColor: 'rgba(0,0,0,0.08)',
      padding: [10, 14],
      textStyle: { color: '#2B241C' }
    },
    toolbox: {
      show: true,
      right: 20,
      itemSize: 15,
      iconStyle: { borderColor: '#9A8B72' },
      feature: { saveAsImage: { title: '导出图片', name: '多项目对比' } }
    },
    legend: {
      data: ['需求覆盖率(%)', '代码质量评分', '缺陷总数'],
      top: 4,
      itemWidth: 12,
      itemHeight: 12,
      textStyle: { color: chartText() }
    },
    grid: { left: 50, right: 20, top: 44, bottom: 30 },
    xAxis: {
      type: 'category',
      data: names,
      axisLabel: { interval: 0, rotate: names.length > 3 ? 15 : 0, color: chartText() },
      axisLine: { lineStyle: { color: chartAxisLine() } },
      axisTick: { show: false }
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: chartText() },
      splitLine: { lineStyle: { color: chartSplitLine() } }
    },
    series: [
      {
        name: '需求覆盖率(%)',
        type: 'bar',
        barMaxWidth: 22,
        itemStyle: {
          borderRadius: [6, 6, 0, 0],
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#C99B3F' },
            { offset: 1, color: '#8F6B22' }
          ])
        },
        data: compareData.value.map(d => d.coverageRate ?? 0)
      },
      {
        name: '代码质量评分',
        type: 'bar',
        barMaxWidth: 22,
        itemStyle: {
          borderRadius: [6, 6, 0, 0],
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#9A9C6B' },
            { offset: 1, color: '#6B8E4E' }
          ])
        },
        data: compareData.value.map(d => d.codeQualityScore ?? 0)
      },
      {
        name: '缺陷总数',
        type: 'bar',
        barMaxWidth: 22,
        itemStyle: {
          borderRadius: [6, 6, 0, 0],
          color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#E89B3C' },
            { offset: 1, color: '#C25E4C' }
          ])
        },
        data: compareData.value.map(d => d.totalDefects ?? 0)
      }
    ]
  })
}

/** 覆盖率环形图（柔和占比，中心显示百分比） */
const renderCoverageChart = () => {
  if (!coverageChartRef.value) return
  if (!coverageChart) {
    coverageChart = echarts.init(coverageChartRef.value, chartThemeName())
  }
  const v = avgCoverage.value
  coverageChart.setOption({
    animationDuration: 900,
    animationEasing: 'cubicOut',
    title: {
      text: `${v}%`,
      subtext: '平均覆盖率',
      left: 'center',
      top: '42%',
      textStyle: {
        fontSize: 28,
        fontWeight: 700,
        color: chartTitleColor(),
        fontFamily: 'inherit'
      },
      subtextStyle: {
        fontSize: 11,
        color: chartFaint()
      }
    },
    series: [
      {
        type: 'pie',
        radius: ['76%', '92%'],
        center: ['50%', '50%'],
        silent: true,
        label: { show: false },
        emphasis: { scale: false },
        itemStyle: {
          shadowBlur: 18,
          shadowColor: 'rgba(143, 107, 34, 0.18)'
        },
        data: [
          { value: v, itemStyle: { color: new echarts.graphic.LinearGradient(0, 0, 0, 1, [
            { offset: 0, color: '#E8C877' },
            { offset: 1, color: '#8F6B22' }
          ]) } },
          { value: Math.max(100 - v, 0), itemStyle: { color: chartTrack() } }
        ]
      }
    ]
  })
}

const statusChartRef = ref(null)
let statusChart = null

/** 项目状态分布环形图（4 状态：已完成/分析中/失败/已创建） */
const renderStatusChart = () => {
  if (!statusChartRef.value) return
  if (!statusChart) {
    statusChart = echarts.init(statusChartRef.value, chartThemeName())
  }
  const list = statusDist.value
  const total = list.reduce((s, x) => s + x.count, 0) || 1
  const donePct = Math.round((statusDist.value.find((x) => x.key === 'analyzed')?.count || 0) / total * 100)
  statusChart.setOption({
    animationDuration: 900,
    animationEasing: 'cubicOut',
    tooltip: {
      trigger: 'item',
      formatter: '{b}：{c} 个（{d}%）',
      backgroundColor: chartTooltipBg(),
      borderColor: 'rgba(0,0,0,0.08)',
      textStyle: { color: '#2B241C' }
    },
    title: {
      text: `${donePct}%`,
      subtext: '已完成',
      left: 'center',
      top: '38%',
      textStyle: {
        fontSize: 24,
        fontWeight: 700,
        color: chartTitleColor(),
        fontFamily: 'inherit'
      },
      subtextStyle: {
        fontSize: 11,
        color: chartFaint()
      }
    },
    series: [
      {
        type: 'pie',
        radius: ['62%', '84%'],
        center: ['50%', '50%'],
        label: { show: false },
        itemStyle: {
          borderColor: '#fff',
          borderWidth: 3,
          borderRadius: 4
        },
        data: list.map((x) => ({
          name: x.label,
          value: x.count,
          itemStyle: { color: x.color }
        }))
      }
    ]
  })
}

const handleResize = () => {
  compareChart?.resize()
  coverageChart?.resize()
  statusChart?.resize()
  trendChart?.resize()
}

// ===== 个性化增强 BATCH-1：深浅色切换时重建图表（主题在 init 时烙定，需 dispose 重建） =====
const { isDark } = useTheme()

const rerenderCharts = () => {
  nextTick(() => {
    compareChart?.dispose()
    compareChart = null
    coverageChart?.dispose()
    coverageChart = null
    statusChart?.dispose()
    statusChart = null
    trendChart?.dispose()
    trendChart = null
    renderCoverageChart()
    renderStatusChart()
    renderTrendChart()
    if (compareData.value.length > 0) renderCompareChart()
  })
}

watch(isDark, rerenderCharts)

// ===== W2 波：工作台聚合（KPI 趋势 / 待办 / 动态流 / 使用趋势） =====
const goTodo = (t) => router.push(`/project/${t.projectId}`)

const switchTrend = (d) => {
  if (trendDays.value === d) return
  trendDays.value = d
  loadTrend()
}

const loadOverview = async () => {
  try {
    overview.value = await dashboardApi.overview()
  } catch (e) {
    console.warn('加载工作台概览失败', e)
  }
}

const loadActivities = async () => {
  try {
    activities.value = await dashboardApi.activities()
  } catch (e) {
    console.warn('加载最近动态失败', e)
  }
}

const loadTodos = async () => {
  try {
    const data = await dashboardApi.notifications()
    todos.value = (data && data.todos) || []
  } catch (e) {
    console.warn('加载待办失败', e)
  }
}

const loadTrend = async () => {
  try {
    trendData.value = (await dashboardApi.trend(trendDays.value)) || { days: [] }
    await nextTick()
    renderTrendChart()
  } catch (e) {
    console.warn('加载使用趋势失败', e)
  }
}

const renderTrendChart = () => {
  if (!trendPanelRef.value) return
  if (!trendChart) {
    trendChart = echarts.init(trendPanelRef.value, chartThemeName())
  }
  const palette = getChartColors()
  const days = trendData.value.days || []
  trendChart.setOption({
    animationDuration: 600,
    animationEasing: 'cubicOut',
    tooltip: { trigger: 'axis' },
    legend: { top: 4, data: ['分析任务', '完成', '登录'], textStyle: { color: chartText() } },
    grid: { left: 44, right: 20, top: 40, bottom: 28 },
    xAxis: {
      type: 'category',
      data: days.map((d) => d.day),
      boundaryGap: false,
      axisLabel: { color: chartText(), fontSize: 11 }
    },
    yAxis: {
      type: 'value',
      minInterval: 1,
      splitLine: { lineStyle: { color: chartSplitLine() } }
    },
    series: [
      {
        name: '分析任务', type: 'line', smooth: true, symbolSize: 6,
        data: days.map((d) => d.tasks),
        lineStyle: { width: 2.5, color: palette.primary },
        itemStyle: { color: palette.primary },
        areaStyle: { color: isDarkTheme() ? 'rgba(217,169,102,0.14)' : 'rgba(143,107,34,0.10)' }
      },
      {
        name: '完成', type: 'line', smooth: true, symbolSize: 6,
        data: days.map((d) => d.completed),
        lineStyle: { width: 2, color: palette.success },
        itemStyle: { color: palette.success }
      },
      {
        name: '登录', type: 'line', smooth: true, symbolSize: 5,
        data: days.map((d) => d.logins),
        lineStyle: { width: 1.5, color: palette.sky, type: 'dashed' },
        itemStyle: { color: palette.sky }
      }
    ]
  }, true)
}

const loadProjects = async () => {
  try {
    // 不传 userId：后端数据隔离（普通用户强制只查自己，管理员返回全部项目）
    const data = await projectApi.list()
    allProjects.value = data
    projects.value = data.slice(0, 5)
    projectCount.value = data.length
    analyzedCount.value = data.filter(p => p.status === 'analyzed').length
    const analyzed = data.filter(p => p.coverageRate)
    avgCoverage.value = analyzed.length > 0
      ? Math.round(analyzed.reduce((sum, p) => sum + (p.coverageRate || 0) * 100, 0) / analyzed.length)
      : 0
    totalDefects.value = data.reduce((sum, p) => sum + (p.defectCount || 0), 0)
    await nextTick()
    renderCoverageChart()
    renderStatusChart()
  } catch (e) {
    console.error(e)
  }
}

const statusText = (status) => {
  const map = { created: '已创建', running: '分析中', analyzed: '已完成', failed: '失败' }
  return map[status] || status
}

// ===== 快速开始新手引导 =====
const guideSteps = [
  { num: '01', title: '创建项目', description: '填写项目基本信息' },
  { num: '02', title: '上传需求文档', description: '支持Word/PDF/Markdown' },
  { num: '03', title: '上传代码工程', description: '支持Maven/Gradle项目zip包' },
  { num: '04', title: '启动分析', description: '一键执行全流程分析' },
  { num: '05', title: '查看报告', description: '查看缺陷与一致性报告' }
]

/** 最近活跃（非归档）的项目，引导以其进度为准 */
const guideProject = computed(() => {
  const active = allProjects.value.filter(p => p.status !== 'archived')
  if (!active.length) return null
  return active.reduce((a, b) => (a.createTime > b.createTime ? a : b))
})

/** 引导当前进行到的步骤（0-5），依据项目状态与产物推断 */
const guideActive = computed(() => {
  const p = guideProject.value
  if (!p) return 0
  if (p.status === 'analyzed') return 5
  if (p.status === 'running' || p.status === 'failed') return 4
  if (p.codeProjectPath) return 3
  if (p.requirementCount > 0) return 2
  return 1
})

const currentPct = computed(() => Math.min(Math.round((guideActive.value / 5) * 100), 100))

const guideActiveText = computed(() => {
  if (guideActive.value === 0) return '从创建第一个项目开始'
  if (guideActive.value >= 5) return '分析已全部完成'
  return `下一步：${guideSteps[guideActive.value].title}`
})

/** 步骤点击直达：未完成的步骤跳转到对应操作页 */
const handleStepClick = (idx) => {
  if (idx === 0) {
    showCreateDialog.value = true
    return
  }
  const p = guideProject.value
  if (!p) {
    ElMessage.info('请先创建项目')
    showCreateDialog.value = true
    return
  }
  if (idx <= 3) {
    router.push(`/project/${p.id}`)
    return
  }
  if (p.status === 'analyzed') {
    router.push(`/results/${p.id}`)
  } else {
    ElMessage.info('当前项目尚未完成分析，完成分析后可查看报告')
  }
}

const goToProject = (row) => {
  router.push(`/project/${row.id}`)
}

const goToResults = (row) => {
  router.push(`/results/${row.id}`)
}

const goToTraceability = (row) => {
  router.push(`/traceability/${row.id}`)
}

const createProject = async () => {
  try {
    const userStr = localStorage.getItem('userInfo')
    if (userStr) {
      newProject.value.createUserId = JSON.parse(userStr).id
    }
    await projectApi.create(newProject.value)
    ElMessage.success('项目创建成功')
    showCreateDialog.value = false
    newProject.value = { projectName: '', industryType: '', techStack: 'Java 8 / SpringBoot', description: '' }
    loadProjects()
  } catch (e) {
    console.error(e)
  }
}

// ===== 个性化增强 BATCH-1：工作台卡片显隐与排序（localStorage 持久化） =====
const DASH_CARDS_KEY = 'tg_dash_cards'
const DASH_CARDS = [
  { key: 'overview', label: '项目总览' },
  { key: 'trend', label: '使用趋势' },
  { key: 'chart', label: '多项目对比' },
  { key: 'recent', label: '最近项目' },
  { key: 'current', label: '当前进行' },
  { key: 'quick', label: '快速开始' }
]
const DASH_DEFAULT_ORDER = { overview: 1, trend: 2, chart: 3, recent: 4, current: 5, quick: 6 }
const dashHidden = ref([])
const dashOrder = ref({ ...DASH_DEFAULT_ORDER })
try {
  const saved = JSON.parse(localStorage.getItem(DASH_CARDS_KEY) || 'null')
  if (saved) {
    if (Array.isArray(saved.hidden)) dashHidden.value = saved.hidden.filter((k) => DASH_DEFAULT_ORDER[k])
    if (saved.order && typeof saved.order === 'object') dashOrder.value = { ...DASH_DEFAULT_ORDER, ...saved.order }
  }
} catch (e) { /* 忽略损坏数据 */ }

const reloadDashCardsFromLocal = () => {
  try {
    const saved = JSON.parse(localStorage.getItem(DASH_CARDS_KEY) || 'null')
    dashHidden.value = saved && Array.isArray(saved.hidden) ? saved.hidden.filter((k) => DASH_DEFAULT_ORDER[k]) : []
    dashOrder.value = saved && saved.order ? { ...DASH_DEFAULT_ORDER, ...saved.order } : { ...DASH_DEFAULT_ORDER }
  } catch (e) { /* ignore */ }
}

const persistDashCards = () => {
  try {
    localStorage.setItem(DASH_CARDS_KEY, JSON.stringify({ hidden: dashHidden.value, order: dashOrder.value }))
    schedulePreferenceSave()
  } catch (e) { /* ignore */ }
}

const sortedDashCards = computed(() => [...DASH_CARDS].sort((a, b) => dashOrder.value[a.key] - dashOrder.value[b.key]))

const toggleDashCard = (key) => {
  dashHidden.value = dashHidden.value.includes(key)
    ? dashHidden.value.filter((k) => k !== key)
    : [...dashHidden.value, key]
  persistDashCards()
}

const moveDashCard = (key, dir) => {
  const sorted = sortedDashCards.value
  const idx = sorted.findIndex((c) => c.key === key)
  const swapIdx = idx + dir
  if (idx < 0 || swapIdx < 0 || swapIdx >= sorted.length) return
  const a = sorted[idx]
  const b = sorted[swapIdx]
  dashOrder.value = { ...dashOrder.value, [a.key]: dashOrder.value[b.key], [b.key]: dashOrder.value[a.key] }
  persistDashCards()
}

const resetDashCards = () => {
  dashHidden.value = []
  dashOrder.value = { ...DASH_DEFAULT_ORDER }
  persistDashCards()
}

// 个性化增强 BATCH-5：卡片拖拽排序（HTML5 DnD，保留上下移按钮作无障碍备选）
const dragKey = ref('')
const onDashDragStart = (key) => { dragKey.value = key }
const onDashDrop = (targetKey) => {
  const srcKey = dragKey.value
  dragKey.value = ''
  if (!srcKey || srcKey === targetKey) return
  const keys = sortedDashCards.value.map((c) => c.key)
  const from = keys.indexOf(srcKey)
  const to = keys.indexOf(targetKey)
  if (from < 0 || to < 0) return
  keys.splice(to, 0, keys.splice(from, 1)[0])
  const order = {}
  keys.forEach((k, i) => { order[k] = i + 1 })
  dashOrder.value = order
  persistDashCards()
}

// ===== 个性化增强 BATCH-1：首屏骨架屏（KPI 行 + 底部动态行） =====
const pageLoading = ref(true)

onMounted(() => {
  const userStr = localStorage.getItem('userInfo')
  if (userStr) {
    try { userInfo.value = JSON.parse(userStr) } catch (e) { /* ignore */ }
  }
  Promise.allSettled([loadProjects(), loadOverview(), loadActivities(), loadTodos(), loadTrend()])
    .finally(() => { pageLoading.value = false })
  window.addEventListener('resize', handleResize)
  // 个性化增强 BATCH-4：云端偏好到达后重读卡片布局
  window.addEventListener('tg:pref-dash', reloadDashCardsFromLocal)
})

onUnmounted(() => {
  window.removeEventListener('tg:pref-dash', reloadDashCardsFromLocal)
  window.removeEventListener('resize', handleResize)
  compareChart?.dispose()
  compareChart = null
  coverageChart?.dispose()
  coverageChart = null
  statusChart?.dispose()
  statusChart = null
  trendChart?.dispose()
  trendChart = null
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

/* ===== KPI 统计行 ===== */
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
  padding: 22px 22px;
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

/* W2-01：KPI 趋势标签（周同比） */
.kpi-card__trend {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 8px;
  border-radius: 999px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
}

.kpi-card__trend.is-up {
  background: rgba(154, 156, 107, 0.14);
  color: #55682e;
}

.kpi-card__trend.is-down {
  background: rgba(194, 94, 76, 0.1);
  color: #9a3f30;
}

/* ===== W2-02/W2-03：待办时间轴 + 最近动态流 ===== */
.dynamic-row {
  display: grid;
  grid-template-columns: 1.1fr 1fr;
  gap: 24px;
  margin-bottom: 26px;
  /* 两卡各自贴合内容高度，避免短列表卡被拉伸出大片空白 */
  align-items: start;
}

.todo-dyn-card {
  padding: 22px 24px;
  transition: transform 0.35s var(--tg-ease), box-shadow 0.35s ease, border-color 0.35s ease;
}

/* 待办卡：暖琥珀（提醒/关注度） */
.todo-card {
  --card-accent: #E89B3C;
  --card-accent-deep: #9A5D12;
  --card-accent-soft: rgba(232, 155, 60, 0.18);
  --card-accent-border: rgba(232, 155, 60, 0.55);
  background:
    linear-gradient(165deg, rgba(232, 155, 60, 0.15), transparent 62%),
    rgba(255, 255, 255, 0.62);
}

/* 动态卡：橄榄绿（运转/活力） */
.activity-card {
  --card-accent: #93A45F;
  --card-accent-deep: #4E6B2E;
  --card-accent-soft: rgba(147, 164, 95, 0.2);
  --card-accent-border: rgba(147, 164, 95, 0.55);
  background:
    linear-gradient(195deg, rgba(147, 164, 95, 0.16), transparent 62%),
    rgba(255, 255, 255, 0.62);
}

.todo-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.todo-item {
  display: flex;
  align-items: center;
  gap: 10px;
  width: 100%;
  padding: 9px 12px;
  border: 1px solid var(--tg-border);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.55);
  text-align: left;
  font-family: inherit;
  cursor: pointer;
  transition: background 0.25s ease, border-color 0.25s ease, box-shadow 0.25s ease, transform 0.3s var(--tg-ease);
  animation: todo-in 0.45s var(--tg-ease) both;
}

.todo-item:nth-child(1) { animation-delay: 0.06s; }
.todo-item:nth-child(2) { animation-delay: 0.13s; }
.todo-item:nth-child(3) { animation-delay: 0.2s; }
.todo-item:nth-child(4) { animation-delay: 0.27s; }
.todo-item:nth-child(5) { animation-delay: 0.34s; }
.todo-item:nth-child(n + 6) { animation-delay: 0.4s; }

@keyframes todo-in {
  from { opacity: 0; transform: translateY(8px); }
  to { opacity: 1; transform: none; }
}

.todo-item:hover {
  background: var(--el-color-primary-light-9);
  border-color: var(--card-accent-border, rgba(143, 107, 34, 0.22));
  box-shadow: 0 4px 14px rgba(60, 45, 25, 0.07);
  transform: translateX(4px);
}

.todo-item:focus-visible {
  outline: 2px solid var(--tg-accent);
  outline-offset: 1px;
}

.todo-item__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}

.todo-item__dot.is-created {
  background: var(--tg-indigo);
  box-shadow: 0 0 0 3px rgba(201, 155, 63, 0.16);
}

/* 待办卡内：圆点跟随琥珀卡色 */
.todo-card .todo-item__dot.is-created {
  background: var(--card-accent);
  box-shadow: 0 0 0 3px var(--card-accent-soft);
}

.todo-item__dot.is-failed {
  background: var(--tg-danger);
  box-shadow: 0 0 0 3px rgba(194, 94, 76, 0.16);
  animation: todo-alert 2s ease-in-out infinite;
}

/* 失败待办：红点脉冲告警 */
@keyframes todo-alert {
  0%, 100% { box-shadow: 0 0 0 3px rgba(194, 94, 76, 0.16); }
  50% { box-shadow: 0 0 0 7px rgba(194, 94, 76, 0.08); }
}

.todo-item__text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.todo-item__text b {
  font-size: 13px;
  font-weight: 600;
  color: var(--tg-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.todo-item__text small {
  font-size: 12px;
  color: var(--tg-text-secondary);
  margin-top: 1px;
}

.todo-item__arrow {
  color: var(--tg-text-secondary);
  opacity: 0;
  transform: translateX(-4px);
  transition: all 0.25s ease;
  flex-shrink: 0;
}

.todo-item:hover .todo-item__arrow {
  opacity: 1;
  transform: none;
  color: var(--tg-accent);
}

.activity-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

/* 动态项：操作类型图标瓦片（替代重复头像，一眼可辨操作种类） */
.activity-item__op {
  width: 27px;
  height: 27px;
  border-radius: 9px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: transform 0.3s var(--tg-ease-spring);
}

.activity-item:hover .activity-item__op {
  transform: scale(1.16) rotate(-8deg);
}

/* 动态流入场：从右错落浮现 */
.activity-list .activity-item {
  animation: activity-in 0.45s var(--tg-ease) both;
}

.activity-item:nth-child(1) { animation-delay: 0.04s; }
.activity-item:nth-child(2) { animation-delay: 0.1s; }
.activity-item:nth-child(3) { animation-delay: 0.16s; }
.activity-item:nth-child(4) { animation-delay: 0.22s; }
.activity-item:nth-child(5) { animation-delay: 0.28s; }
.activity-item:nth-child(6) { animation-delay: 0.34s; }
.activity-item:nth-child(7) { animation-delay: 0.4s; }
.activity-item:nth-child(8) { animation-delay: 0.46s; }

@keyframes activity-in {
  from { opacity: 0; transform: translateX(12px); }
  to { opacity: 1; transform: none; }
}

.activity-item__op.is-login { background: rgba(122, 111, 95, 0.12); color: #6d6355; }
.activity-item__op.is-create { background: rgba(201, 155, 63, 0.16); color: #8a651a; }
.activity-item__op.is-upload { background: rgba(217, 169, 102, 0.2); color: #96690f; }
.activity-item__op.is-analyze { background: rgba(154, 156, 107, 0.18); color: #55682e; }
.activity-item__op.is-update { background: rgba(232, 155, 60, 0.16); color: #9a5d12; }
.activity-item__op.is-export { background: rgba(168, 185, 138, 0.2); color: #55682e; }
.activity-item__op.is-remove { background: rgba(194, 94, 76, 0.13); color: #9a3f30; }
.activity-item__op.is-backup { background: rgba(143, 107, 34, 0.12); color: #8a651a; }
.activity-item__op.is-other { background: rgba(0, 0, 0, 0.05); color: var(--tg-text-secondary); }

.activity-more {
  display: flex;
  justify-content: flex-end;
  margin-top: 4px;
  padding-top: 8px;
  border-top: 1px dashed var(--tg-border);
}

.activity-more :deep(.el-button) {
  color: var(--card-accent-deep);
  font-weight: 600;
}

/* 最新一条动态：进入时短暂高亮，暗示"实时" */
.activity-item:first-child {
  animation: activity-in 0.45s var(--tg-ease) 0.04s both, activity-new 1.6s ease 0.6s;
}

@keyframes activity-new {
  0% { background: var(--card-accent-soft, rgba(154, 156, 107, 0.2)); }
  100% { background: transparent; }
}

.activity-item {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 7px 12px;
  border-radius: 12px;
  transition: background 0.25s ease;
}

.activity-item:hover {
  background: var(--card-accent-soft, rgba(0, 0, 0, 0.025));
}

.activity-item__body {
  min-width: 0;
}

.activity-item__text {
  font-size: 13px;
  color: var(--tg-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.activity-item__text b {
  color: var(--tg-text-primary);
  font-weight: 600;
}

.activity-item__time {
  font-size: 11px;
  color: var(--tg-slate);
  margin-top: 1px;
}

.dynamic-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  padding: 26px 0 10px;
  color: var(--tg-text-secondary);
  font-size: 13px;
}

.dynamic-empty p {
  margin: 0;
}

/* ===== W2-06：使用趋势面板 ===== */
.trend-panel {
  grid-column: 1 / -1;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.82), rgba(255, 255, 255, 0.5));
  border: 1px solid var(--tg-border);
  border-radius: 20px;
  padding: 24px;
  box-shadow: var(--tg-shadow-card);
}

.trend-chart {
  width: 100%;
  height: 260px;
}

/* ===== 非对称主网格 ===== */
.dashboard-grid {
  display: grid;
  grid-template-columns: 1.55fr 1fr;
  gap: 24px;
  align-items: start;
}

.overview-card {
  padding: 28px;
}

/* 区块标题（页内统一小标题） */
.section-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 18px;
}

.section-title__left {
  min-width: 0;
}

.section-title h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: -0.01em;
  color: var(--tg-text-primary);
  display: flex;
  align-items: center;
  gap: 8px;
}

.section-title__ic {
  color: var(--tg-accent);
  background: var(--el-color-primary-light-9);
  border-radius: 8px;
  padding: 4px;
  box-sizing: content-box;
}

.section-title p {
  margin: 3px 0 0;
  font-size: 13px;
  color: var(--tg-text-secondary);
}

/* 标题右侧呼吸状态徽章（实时 / 进行中） */
.live-badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--tg-text-secondary);
  flex-shrink: 0;
}

/* ===== 主视觉：项目总览（三栏可视化，填满宽度） ===== */
.overview-body {
  display: grid;
  grid-template-columns: 220px 1fr 260px;
  align-items: center;
  gap: 32px;
}

.status-chart {
  width: 220px;
  height: 220px;
  justify-self: center;
}

.status-legend {
  display: flex;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.status-legend__item {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.status-legend__head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--tg-text-secondary);
}

.status-legend__head i {
  width: 9px;
  height: 9px;
  border-radius: 3px;
  flex-shrink: 0;
}

.status-legend__head span {
  flex: 1;
  min-width: 0;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.status-legend__head b {
  color: var(--tg-text-primary);
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

.status-legend__track {
  height: 6px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.05);
  overflow: hidden;
}

.status-legend__track i {
  display: block;
  height: 100%;
  border-radius: 999px;
  transition: width 0.8s var(--tg-ease);
}

/* 右栏：覆盖率环形图 + 摘要 */
.overview-coverage {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding-left: 28px;
  border-left: 1px solid var(--tg-border);
}

.overview-ring {
  width: 168px;
  height: 168px;
  flex-shrink: 0;
}

.coverage-summary {
  width: 100%;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.coverage-summary__head {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  font-weight: 600;
  color: var(--tg-accent);
  margin-bottom: 2px;
}

.coverage-summary__item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  color: var(--tg-text-secondary);
}

.coverage-summary__item b {
  color: var(--tg-text-primary);
  font-variant-numeric: tabular-nums;
  font-weight: 600;
}

/* ===== 副卡：当前进行（蜜金主色；flex 纵排 + 横向流程管线，与快速开始等高拉伸） ===== */
.current-card {
  --card-accent: #C99B3F;
  --card-accent-deep: #8F6B22;
  --card-accent-soft: rgba(201, 155, 63, 0.16);
  --card-accent-border: rgba(201, 155, 63, 0.5);
  padding: 24px;
  display: flex;
  flex-direction: column;
  background:
    linear-gradient(155deg, rgba(201, 155, 63, 0.16), transparent 62%),
    var(--tg-bg-glass);
  transition: transform 0.35s var(--tg-ease), box-shadow 0.35s ease, border-color 0.35s ease;
}

.current-info {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  gap: 14px;
  min-height: 0;
}

.current-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.current-info .current-name {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 17px;
  font-weight: 600;
  color: var(--tg-text-primary);
  min-width: 0;
}

.current-name__text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.current-meta {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}

.current-meta__chip {
  font-size: 12px;
  color: var(--tg-text-secondary);
  background: rgba(0, 0, 0, 0.04);
  border-radius: 999px;
  padding: 3px 10px;
}

/* 横向五步流程管线：完成打勾 / 当前呼吸高亮 / 未开始置灰 */
.current-pipeline {
  display: flex;
  padding: 4px 0 2px;
}

.pipe-step {
  position: relative;
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 7px;
  padding: 0;
  border: 0;
  background: transparent;
  cursor: pointer;
  font-family: inherit;
}

.pipe-step::after {
  content: '';
  position: absolute;
  top: 13px;
  left: calc(50% + 21px);
  width: calc(100% - 42px);
  height: 2px;
  border-radius: 2px;
  background: rgba(0, 0, 0, 0.08);
  transform-origin: left center;
  transition: background 0.4s ease;
}

/* 连线随入场依次生长 */
.pipe-step::after {
  animation: pipe-line 0.5s var(--tg-ease) both;
}

.pipe-step:nth-child(1) { animation: pipe-in 0.45s var(--tg-ease) 0.05s both; }
.pipe-step:nth-child(2) { animation: pipe-in 0.45s var(--tg-ease) 0.11s both; }
.pipe-step:nth-child(3) { animation: pipe-in 0.45s var(--tg-ease) 0.17s both; }
.pipe-step:nth-child(4) { animation: pipe-in 0.45s var(--tg-ease) 0.23s both; }
.pipe-step:nth-child(5) { animation: pipe-in 0.45s var(--tg-ease) 0.29s both; }
.pipe-step:nth-child(1)::after { animation: pipe-line 0.5s var(--tg-ease) 0.2s both; }
.pipe-step:nth-child(2)::after { animation: pipe-line 0.5s var(--tg-ease) 0.26s both; }
.pipe-step:nth-child(3)::after { animation: pipe-line 0.5s var(--tg-ease) 0.32s both; }
.pipe-step:nth-child(4)::after { animation: pipe-line 0.5s var(--tg-ease) 0.38s both; }
.pipe-step:nth-child(5)::after { animation: pipe-line 0.5s var(--tg-ease) 0.44s both; }

@keyframes pipe-in {
  from { opacity: 0; transform: translateY(10px); }
  to { opacity: 1; transform: none; }
}

@keyframes pipe-line {
  from { transform: scaleX(0); }
  to { transform: scaleX(1); }
}

.pipe-step:last-child::after {
  display: none;
}

.pipe-step.is-done::after {
  background: linear-gradient(90deg, #C99B3F, #8F6B22);
}

.pipe-step:focus-visible {
  outline: 2px solid rgba(143, 107, 34, 0.45);
  outline-offset: 3px;
  border-radius: 8px;
}

.pipe-step__dot {
  position: relative;
  z-index: 1;
  width: 26px;
  height: 26px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11.5px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  background: rgba(0, 0, 0, 0.05);
  color: var(--tg-text-secondary);
  transition: background 0.3s var(--tg-ease), color 0.3s ease, box-shadow 0.3s ease, transform 0.3s var(--tg-ease-spring);
}

.pipe-step:hover .pipe-step__dot {
  transform: scale(1.18) rotate(-8deg);
}

.pipe-step.is-done .pipe-step__dot {
  background: var(--tg-accent-gradient);
  color: #fff;
  box-shadow: var(--tg-glow-accent);
}

.pipe-step.is-current .pipe-step__dot {
  background: rgba(201, 155, 63, 0.16);
  color: var(--tg-accent);
  animation: pipe-pulse 2.4s ease-out infinite;
}

@keyframes pipe-pulse {
  0% { box-shadow: 0 0 0 0 rgba(201, 155, 63, 0.32); }
  70% { box-shadow: 0 0 0 7px rgba(201, 155, 63, 0); }
  100% { box-shadow: 0 0 0 0 rgba(201, 155, 63, 0); }
}

.pipe-step__label {
  max-width: 100%;
  font-size: 12px;
  color: var(--tg-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.25s ease;
}

.pipe-step.is-done .pipe-step__label {
  color: var(--tg-text-primary);
}

.pipe-step.is-current .pipe-step__label {
  color: var(--tg-accent);
  font-weight: 600;
}

.pipe-step:hover .pipe-step__label {
  color: var(--tg-accent);
}

.current-progress {
  margin-bottom: 2px;
}

.current-progress__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12.5px;
  color: var(--tg-text-secondary);
  margin-bottom: 8px;
}

.current-progress__head b {
  color: var(--tg-accent);
  font-variant-numeric: tabular-nums;
}

.current-progress__track {
  height: 6px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

.current-progress__track i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #C99B3F, #8F6B22);
  transition: width 0.8s var(--tg-ease);
  position: relative;
  overflow: hidden;
}

/* 进度条高光扫过：暗示"持续推进中" */
.current-progress__track i::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(90deg, transparent, rgba(255, 255, 255, 0.5), transparent);
  transform: translateX(-100%);
  animation: bar-sheen 2.8s var(--tg-ease) infinite;
}

@keyframes bar-sheen {
  to { transform: translateX(100%); }
}

.current-info__btn {
  align-self: flex-start;
  min-width: 160px;
  transition: transform 0.25s var(--tg-ease-spring), box-shadow 0.25s ease;
}

.current-info__btn:hover {
  transform: translateY(-2px);
  box-shadow: var(--tg-glow-accent);
}

/* 卡片 hover 时步骤徽章轻弹 */
.quick-chip {
  transition: transform 0.3s var(--tg-ease-spring);
}

.quick-card:hover .quick-chip,
.todo-card:hover .live-badge {
  transform: translateY(-1px) scale(1.07);
}

.live-badge {
  transition: transform 0.3s var(--tg-ease-spring);
}

/* 待办卡计数徽章：琥珀卡色 */
.todo-card .live-badge {
  color: var(--card-accent-deep);
  font-weight: 600;
}

.current-empty {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  padding: 24px 0 8px;
  text-align: center;
  color: var(--tg-text-secondary);
}

.current-empty__tile {
  width: 64px;
  height: 64px;
  border-radius: 20px;
  background: var(--el-color-primary-light-9);
  color: var(--tg-accent);
  display: flex;
  align-items: center;
  justify-content: center;
}

.current-empty p {
  margin: 0;
  font-size: 13.5px;
}

/* ===== 无卡片图表面板 ===== */
.chart-panel {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.82), rgba(255, 255, 255, 0.5));
  border: 1px solid var(--tg-border);
  border-radius: 20px;
  padding: 24px;
  box-shadow: var(--tg-shadow-card);
}

.compare-controls {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 10px;
}

.compare-select {
  width: 280px;
  margin-right: 4px;
}

.compare-chart {
  width: 100%;
  height: 300px;
}

.compare-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 10px;
  min-height: 240px;
  color: var(--tg-text-secondary);
  text-align: center;
}

.compare-empty__tile {
  width: 64px;
  height: 64px;
  border-radius: 20px;
  background: rgba(154, 156, 107, 0.12);
  color: var(--tg-teal);
  display: flex;
  align-items: center;
  justify-content: center;
}

.compare-empty p {
  margin: 0;
  font-size: 13.5px;
}

/* ===== 副卡：快速开始（蜜桃金主色；步骤清单自适应分布，填满与当前进行等高的卡体） ===== */
.quick-card {
  --card-accent: #D0783C;
  --card-accent-deep: #9C5518;
  --card-accent-soft: rgba(208, 120, 60, 0.16);
  --card-accent-border: rgba(208, 120, 60, 0.5);
  padding: 24px;
  display: flex;
  flex-direction: column;
  background:
    linear-gradient(205deg, rgba(208, 120, 60, 0.14), transparent 62%),
    var(--tg-bg-glass);
  transition: transform 0.35s var(--tg-ease), box-shadow 0.35s ease, border-color 0.35s ease;
}

/* 标题右侧步骤进度徽章（n/5，完成转成功色） */
.quick-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 11px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--card-accent-deep);
  background: var(--card-accent-soft);
  border: 1px solid var(--card-accent-border);
  flex-shrink: 0;
}

.quick-chip.is-full {
  color: #55682e;
  background: rgba(154, 156, 107, 0.16);
  border-color: rgba(154, 156, 107, 0.3);
}

.guide-list {
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: space-evenly;
  gap: 4px;
}

.guide-item {
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 8px 10px;
  border: 1px solid transparent;
  border-radius: 12px;
  background: transparent;
  cursor: pointer;
  text-align: left;
  font-family: inherit;
  transition: background 0.25s ease, border-color 0.25s ease, transform 0.3s var(--tg-ease), box-shadow 0.3s ease;
  animation: guide-in 0.45s var(--tg-ease) both;
}

.guide-item:nth-child(1) { animation-delay: 0.05s; }
.guide-item:nth-child(2) { animation-delay: 0.11s; }
.guide-item:nth-child(3) { animation-delay: 0.17s; }
.guide-item:nth-child(4) { animation-delay: 0.23s; }
.guide-item:nth-child(5) { animation-delay: 0.29s; }

@keyframes guide-in {
  from { opacity: 0; transform: translateX(-14px); }
  to { opacity: 1; transform: none; }
}

.guide-item:hover {
  background: rgba(0, 0, 0, 0.03);
  border-color: var(--card-accent-border, var(--tg-border));
  transform: translateX(5px);
  box-shadow: 0 4px 14px rgba(60, 45, 25, 0.08);
}

.guide-item:focus-visible {
  outline: 2px solid rgba(143, 107, 34, 0.45);
  outline-offset: 1px;
}

.guide-item.is-current {
  background: var(--card-accent-soft);
  border-color: var(--card-accent-border);
  animation: guide-in 0.45s var(--tg-ease) both, guide-glow 2.6s ease-in-out 0.8s infinite;
}

/* 当前步骤：实色圆点 + 柔光呼吸，引导视线 */
.guide-item.is-current .guide-item__dot {
  background: var(--card-accent);
  color: #fff;
}

@keyframes guide-glow {
  0%, 100% { box-shadow: 0 0 0 0 rgba(208, 120, 60, 0); }
  50% { box-shadow: 0 0 0 5px rgba(208, 120, 60, 0.18); }
}

.guide-item__dot {
  width: 30px;
  height: 30px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 11.5px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  background: rgba(0, 0, 0, 0.05);
  color: var(--tg-text-secondary);
  flex-shrink: 0;
  transition: all 0.3s var(--tg-ease);
}

.guide-item.is-done .guide-item__dot {
  background: linear-gradient(135deg, var(--card-accent, #C99B3F), var(--card-accent-deep, #8F6B22));
  color: #fff;
  box-shadow: var(--tg-glow-accent);
}

.guide-item.is-current .guide-item__dot {
  background: var(--card-accent);
  color: #fff;
  box-shadow: 0 3px 10px var(--card-accent-soft);
}

.guide-item__text {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.guide-item__text b {
  font-size: 13.5px;
  font-weight: 600;
  color: var(--tg-text-primary);
}

.guide-item__text small {
  font-size: 11.5px;
  color: var(--tg-text-secondary);
  margin-top: 1px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.guide-item__arrow {
  color: var(--tg-text-secondary);
  opacity: 0;
  transform: translateX(-4px);
  transition: all 0.25s ease;
}

.guide-item:hover .guide-item__arrow {
  opacity: 1;
  transform: none;
  color: var(--tg-accent);
}

/* ===== 最近项目表格 ===== */
.recent-panel {
  grid-column: 1 / -1;
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(var(--tg-blur)) saturate(1.4);
  -webkit-backdrop-filter: blur(var(--tg-blur)) saturate(1.4);
  border: 1px solid var(--tg-border);
  border-radius: 20px;
  padding: 24px;
  box-shadow: var(--tg-shadow-card);
}

.recent-table :deep(.el-table) {
  --el-table-border-color: rgba(0, 0, 0, 0.05);
  --el-table-header-bg-color: transparent;
  --el-table-row-hover-bg-color: rgba(201, 155, 63, 0.06);
  background: transparent;
}

/* 行 hover：阴影抬升 + 左侧金色指示条（不用 transform，避免破坏固定列 sticky 定位） */
.recent-table :deep(.el-table__body tr) {
  transition: box-shadow 0.3s ease;
}

.recent-table :deep(.el-table__body tr:hover) {
  box-shadow: 0 2px 0 rgba(201, 155, 63, 0.28), 0 8px 22px rgba(60, 45, 25, 0.1);
}

.recent-table :deep(.el-table__body tr:hover > td.el-table__cell:first-child) {
  box-shadow: inset 3px 0 0 var(--tg-accent);
}

.recent-table :deep(.el-table th.el-table__cell) {
  font-size: 12px;
  font-weight: 600;
  color: var(--tg-text-secondary);
  text-transform: none;
  background: rgba(0, 0, 0, 0.02);
  transition: color 0.25s ease;
}

.recent-table :deep(.el-table__cell) {
  padding: 12px 0;
}

/* 创建时间整串不换行（修复单数字掉行） */
.recent-table__time {
  white-space: nowrap;
  font-variant-numeric: tabular-nums;
}

.recent-table__name {
  display: flex;
  align-items: center;
  gap: 11px;
  font-weight: 500;
  color: var(--tg-text-primary);
}

.recent-table__tile {
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
  color: var(--tg-accent);
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: transform 0.3s var(--tg-ease-spring), box-shadow 0.3s ease, background 0.3s ease;
}

.recent-table__name-text {
  color: var(--tg-text-primary);
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  transition: color 0.25s ease;
}

/* 行 hover：项目名图标高亮放大 + 名称变金 */
.recent-table__name:hover .recent-table__tile {
  transform: scale(1.12) rotate(-6deg);
  box-shadow: var(--tg-glow-accent);
  background: var(--el-color-primary-light-8);
}

.recent-table__name:hover .recent-table__name-text {
  color: var(--tg-accent);
}

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

.recent-table__rate {
  display: flex;
  align-items: center;
  gap: 10px;
}

.recent-table__rate-track {
  flex: 1;
  height: 6px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.06);
  overflow: hidden;
  min-width: 60px;
}

.recent-table__rate-track i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #9A9C6B, #6B8E4E);
  transform-origin: left;
  animation: rate-grow 1s var(--tg-ease) both;
  transition: filter 0.25s ease;
}

.recent-table__rate:hover .recent-table__rate-track i {
  filter: brightness(1.12);
}

.recent-table__rate span {
  font-size: 12.5px;
  color: var(--tg-text-secondary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  transition: color 0.25s ease;
}

.recent-table__rate:hover span {
  color: var(--tg-accent);
}

.recent-table__rate-empty {
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

/* 操作按钮组：统一小胶囊（图标 + 文字，语义配色，与项目管理界面一致） */
.op-actions {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 6px;
  flex-wrap: nowrap;
  min-width: 0;
}

.op-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.op-btn {
  height: 30px;
  padding: 0 11px;
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

.op-btn--view {
  background: var(--el-color-primary-light-9);
  border-color: rgba(143, 107, 34, 0.22);
  color: var(--tg-accent);
}
.op-btn--view:hover {
  background: var(--el-color-primary-light-8);
  color: var(--tg-accent);
}

.op-btn--edit {
  background: rgba(201, 155, 63, 0.14);
  border-color: rgba(201, 155, 63, 0.28);
  color: #8a651a;
}
.op-btn--edit:hover {
  background: rgba(201, 155, 63, 0.22);
  color: #8a651a;
}

.op-btn--result {
  background: rgba(154, 156, 107, 0.16);
  border-color: rgba(154, 156, 107, 0.3);
  color: #55682e;
}
.op-btn--result:hover {
  background: rgba(154, 156, 107, 0.26);
  color: #55682e;
}

.op-btn--archive {
  background: rgba(232, 155, 60, 0.14);
  border-color: rgba(232, 155, 60, 0.28);
  color: #9a5d12;
}
.op-btn--archive:hover {
  background: rgba(232, 155, 60, 0.24);
  color: #9a5d12;
}

/* ===== 创建项目对话框 ===== */
.create-dialog :deep(.el-dialog) {
  border-radius: 20px;
}

.create-dialog__hint {
  margin: -4px 0 16px;
  font-size: 12.5px;
  color: var(--tg-text-secondary);
  background: var(--el-color-primary-light-9);
  border-radius: 10px;
  padding: 10px 14px;
}

/* ===== 入场错落 ===== */
.kpi-row .tg-fade-up:nth-child(2) {
  animation-delay: 0.06s;
}

.dashboard-grid .tg-fade-up:nth-child(2) {
  animation-delay: 0.08s;
}

.dashboard-grid .tg-fade-up:nth-child(3) {
  animation-delay: 0.14s;
}

.dashboard-grid .tg-fade-up:nth-child(4) {
  animation-delay: 0.2s;
}

.dashboard-grid .tg-fade-up:nth-child(5) {
  animation-delay: 0.26s;
}

/* =====================================================================
 * W4：工作台主次布局 + 层次感与动效增强
 * 1) 图表优先的网格排序（项目总览|使用趋势 → 多项目对比 → 最近项目 → 操作区）
 * 2) 卡片层次：顶部高光线 + 内衬图区 + 悬停反馈
 * 3) 底部次要区（待办/动态）紧凑降权
 * ===================================================================== */

/* ---- 1) 主次排序：图表类前置，操作/引导类后置 ---- */
.dashboard-grid > .overview-card { order: 1; }
.dashboard-grid > .trend-panel { order: 2; grid-column: auto; }
.dashboard-grid > .chart-panel { order: 3; grid-column: 1 / -1; }
.dashboard-grid > .recent-panel { order: 4; grid-column: 1 / -1; }
.dashboard-grid > .current-card { order: 5; }
.dashboard-grid > .quick-card { order: 6; }

/* 首行主卡等高：项目总览与使用趋势同宽同高 */
.dashboard-grid > .overview-card,
.dashboard-grid > .trend-panel {
  align-self: stretch;
}

/* 操作区一行等高：当前进行（内容纵向分布）与快速开始（步骤均匀分布）填满同高 */
.dashboard-grid > .current-card,
.dashboard-grid > .quick-card {
  align-self: stretch;
}

/* ---- 2) 层次感：统一的顶部高光线 + 内衬图区 + 悬停金线 ---- */
.dashboard-grid > section,
.dynamic-row .todo-dyn-card {
  position: relative;
}

.dashboard-grid > section::before,
.dynamic-row .todo-dyn-card::before {
  content: '';
  position: absolute;
  inset: 0 0 auto 0;
  height: 2px;
  border-radius: 2px 2px 0 0;
  background: linear-gradient(90deg, transparent, var(--card-accent, rgba(201, 155, 63, 0.4)), transparent);
  opacity: 0;
  transition: opacity 0.45s ease;
}

/* 四张操作/动态卡：专属色顶线常显（低透明度），hover 全亮 */
.current-card::before,
.quick-card::before,
.todo-dyn-card::before {
  opacity: 0.55;
}

.dashboard-grid > section:hover::before,
.dynamic-row .todo-dyn-card:hover::before {
  opacity: 1;
}

/* 四卡统一 hover 节奏：抬升 + 加深投影 + 专属色描边 */
.current-card:hover,
.quick-card:hover,
.todo-dyn-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--tg-shadow-card-hover);
  border-color: var(--card-accent-border);
}

/* 四卡：专属色描边常显 + 实色图标瓦片 + 标题着色，色彩身份一目了然 */
.current-card,
.quick-card,
.todo-dyn-card {
  border-color: var(--card-accent-border);
}

.current-card .section-title__ic,
.quick-card .section-title__ic,
.todo-card .section-title__ic,
.activity-card .section-title__ic {
  color: #fff;
  background: var(--card-accent);
  box-shadow: 0 2px 8px var(--card-accent-soft);
}

.current-card .section-title h3,
.quick-card .section-title h3,
.todo-card .section-title h3,
.activity-card .section-title h3 {
  color: var(--card-accent-deep);
}

/* 图表区域内衬（温润衬底 + 细描边，增强"高级面板"质感） */
.chart-panel .compare-chart,
.trend-panel .trend-chart {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.6), rgba(255, 255, 255, 0.22));
  border-radius: 16px;
  border: 1px solid rgba(201, 155, 63, 0.12);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.6);
  padding: 8px 6px 2px;
}

/* KPI 数字悬停微交互 */
.kpi-card__num {
  transition: transform 0.3s var(--tg-ease), color 0.3s ease;
}

.kpi-card:hover .kpi-card__num {
  transform: translateY(-1px) scale(1.03);
}

/* 最近项目表头行内 hover 引导已有；补充整卡 hover 抬升过渡 */
.recent-panel,
.chart-panel,
.trend-panel,
.overview-card {
  transition: box-shadow 0.35s ease, border-color 0.35s ease, transform 0.35s var(--tg-ease);
}

.recent-panel:hover,
.chart-panel:hover,
.trend-panel:hover {
  border-color: rgba(201, 155, 63, 0.28);
}

/* ---- 3) 底部次要区（待办/动态）：紧凑降权横幅 ---- */
.dynamic-row--compact {
  margin-top: 26px;
}

.dynamic-row--compact .todo-dyn-card {
  padding: 16px 18px;
  /* 背景由 .todo-card / .activity-card 的专属色渐变提供，此处不再覆盖 */
}

.dynamic-row--compact .section-title {
  margin-bottom: 12px;
}

.dynamic-row--compact .section-title h3 {
  font-size: 14px;
}

.dynamic-row--compact .section-title p {
  font-size: 12px;
}

.dynamic-row--compact .todo-item {
  padding: 7px 10px;
}

.dynamic-row--compact .todo-item__text b {
  font-size: 12.5px;
}

.dynamic-row--compact .todo-item__text small {
  font-size: 11.5px;
}

.dynamic-row--compact .activity-item {
  padding: 5px 10px;
}

.dynamic-row--compact .activity-item__text {
  font-size: 12.5px;
}

.dynamic-row--compact .dynamic-empty {
  padding: 16px 0 6px;
}

.dynamic-row--compact .todo-item__arrow {
  opacity: 0;
  transform: translateX(-4px);
  transition: all 0.25s ease;
}

.dynamic-row--compact .todo-item:hover .todo-item__arrow {
  opacity: 1;
  transform: none;
}

/* 空状态（未创建项目时）悬停：引导瓦片微放大 */
.current-card:hover .current-empty__tile {
  transform: scale(1.06);
}

/* 尊重系统减弱动态偏好：关闭全部装饰性动画，保留最终语义状态
   （选择器与入场规则同强度，靠源码顺序靠后取胜） */
@media (prefers-reduced-motion: reduce) {
  .current-pipeline .pipe-step,
  .current-pipeline .pipe-step::after,
  .guide-item,
  .guide-item.is-current,
  .todo-list .todo-item,
  .activity-list .activity-item,
  .activity-item:first-child {
    animation: none;
  }

  .pipe-step.is-current .pipe-step__dot {
    animation: none;
    box-shadow: 0 0 0 4px rgba(201, 155, 63, 0.14);
  }

  .todo-item__dot.is-failed {
    animation: none;
    box-shadow: 0 0 0 3px rgba(194, 94, 76, 0.16);
  }

  .current-progress__track i::after {
    animation: none;
    display: none;
  }

  .current-card:hover,
  .quick-card:hover,
  .todo-dyn-card:hover,
  .guide-item:hover,
  .todo-item:hover,
  .pipe-step:hover .pipe-step__dot,
  .activity-item:hover .activity-item__op,
  .current-info__btn:hover,
  .quick-card:hover .quick-chip,
  .todo-card:hover .live-badge {
    transform: none;
  }
}

/* 窄屏降级为单列 */
@media (max-width: 1280px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 1100px) {
  .dashboard-grid {
    grid-template-columns: 1fr;
  }

  .dynamic-row {
    grid-template-columns: 1fr;
  }

  .overview-card,
  .recent-panel {
    grid-column: auto;
    grid-row: auto;
  }

  .overview-body {
    grid-template-columns: 1fr;
    gap: 24px;
  }

  .overview-coverage {
    flex-direction: row;
    justify-content: center;
    gap: 28px;
    padding-left: 0;
    border-left: none;
  }

  .coverage-summary {
    max-width: 220px;
  }

  .compare-controls {
    margin-top: 12px;
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

  .overview-coverage {
    flex-direction: column;
  }

  .compare-select {
    width: 100%;
  }
}
/* =====================================================================
 * 个性化增强 BATCH-1：首屏骨架屏
 * ===================================================================== */
.kpi-card--skeleton {
  padding: 22px 24px;
}

.kpi-skeleton {
  display: flex;
  align-items: center;
  gap: 16px;
  width: 100%;
}

.kpi-skeleton__lines {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.dash-skeleton-card {
  min-height: 150px;
}
/* 个性化增强 BATCH-5：自定义面板拖拽视觉 */
.dash-customize__row[draggable='true'] { cursor: grab; border-radius: 8px; }
.dash-customize__row.is-dragging { opacity: 0.45; }
</style>
