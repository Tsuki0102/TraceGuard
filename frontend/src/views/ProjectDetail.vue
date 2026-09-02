<template>
  <div class="project-detail">
    <!-- ===== 页头 ===== -->
    <div class="page-header tg-fade-up">
      <div>
        <div class="page-header__back">
          <el-button link class="page-header__back-btn" @click="$router.back()">
            <el-icon><ArrowLeft /></el-icon> 返回
          </el-button>
        </div>
        <div class="page-header__greet">
          <el-icon class="page-header__greet-icon"><FolderOpened /></el-icon>
          Project Overview
        </div>
        <h2 class="page-header__title">{{ project?.projectName || '项目详情' }}</h2>
        <p class="page-header__desc">上传需求与代码工程，配置分析参数并启动一致性分析</p>
      </div>
      <div class="page-header__actions">
        <span v-if="project" class="status-pill" :class="'status-pill--' + project.status">{{ statusText(project.status) }}</span>
        <el-button type="primary" size="large" round @click="viewResults" :disabled="project?.status !== 'analyzed'">
          <el-icon style="margin-right: 6px"><DataAnalysis /></el-icon>查看分析结果
        </el-button>
      </div>
    </div>

    <!-- W3-11：扫描健康提示（距上次分析超阈值建议重扫） -->
    <div v-if="rescanHint" class="rescan-hint tg-fade-up">
      <el-icon :size="16" class="rescan-hint__icon"><AlarmClock /></el-icon>
      <span class="rescan-hint__text">{{ rescanHint }}</span>
      <el-button size="small" type="primary" round plain @click="startAnalysis">重新分析</el-button>
    </div>

    <!-- ===== KPI 统计行 ===== -->
    <div v-if="project" class="kpi-row">
      <div class="kpi-card tg-fade-up">
        <div class="kpi-card__tile kpi-card__tile--gold"><el-icon :size="22"><Files /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num"><span class="tg-count">{{ reqCountDisp }}</span></div>
          <div class="kpi-card__label">需求文档</div>
          <div class="kpi-card__meta">已解析条目</div>
        </div>
      </div>
      <div class="kpi-card tg-fade-up">
        <div class="kpi-card__tile kpi-card__tile--coral"><el-icon :size="22"><Warning /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num"><span class="tg-count">{{ defectCountDisp }}</span></div>
          <div class="kpi-card__label">缺陷数</div>
          <div class="kpi-card__meta">需求-代码偏差项</div>
        </div>
      </div>
      <div class="kpi-card tg-fade-up">
        <div class="kpi-card__tile kpi-card__tile--amber"><el-icon :size="22"><DataLine /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num">
            <span class="tg-count">{{ coverageDisp }}</span><span class="kpi-card__suffix">%</span>
          </div>
          <div class="kpi-card__label">需求覆盖率</div>
          <div class="kpi-card__meta">需求覆盖比例</div>
        </div>
      </div>
      <div class="kpi-card tg-fade-up">
        <div class="kpi-card__tile kpi-card__tile--green"><el-icon :size="22"><Histogram /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num"><span class="tg-count">{{ taskCountDisp }}</span></div>
          <div class="kpi-card__label">分析任务</div>
          <div class="kpi-card__meta">历史任务总数</div>
        </div>
      </div>
    </div>

    <!-- ===== 主网格 ===== -->
    <div class="detail-grid">
      <!-- 左栏 -->
      <div class="detail-main">
        <!-- 项目信息卡 -->
        <section class="glass-card info-card tg-fade-up" v-loading="loading">
          <div class="section-title">
            <div class="section-title__left">
              <h3><el-icon class="section-title__ic" :size="17"><InfoFilled /></el-icon>项目信息</h3>
              <p>基础信息与关键指标</p>
            </div>
          </div>
          <el-descriptions v-if="project" :column="2" class="info-desc">
            <el-descriptions-item label="项目名称">{{ project.projectName }}</el-descriptions-item>
            <el-descriptions-item label="行业类型">{{ project.industryType }}</el-descriptions-item>
            <el-descriptions-item label="技术栈">{{ project.techStack }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <span class="status-pill" :class="'status-pill--' + project.status">{{ statusText(project.status) }}</span>
            </el-descriptions-item>
            <el-descriptions-item label="需求数">{{ project.requirementCount || 0 }}</el-descriptions-item>
            <el-descriptions-item label="缺陷数">{{ project.defectCount || 0 }}</el-descriptions-item>
            <el-descriptions-item label="覆盖率" :span="2">
              <div class="desc-rate">
                <div class="desc-rate__track"><i :style="{ width: (project.coverageRate || 0) * 100 + '%' }"></i></div>
                <span>{{ project.coverageRate != null ? (project.coverageRate * 100).toFixed(1) + '%' : '-' }}</span>
              </div>
            </el-descriptions-item>
            <el-descriptions-item label="项目描述" :span="2">{{ project.description }}</el-descriptions-item>
          </el-descriptions>
          <!-- W1-06/O10：健康维度五维分解（全部由现有字段派生） -->
          <div class="health-dims">
            <div class="health-dims__head">
              <span class="health-dims__title">
                <el-icon :size="15"><Odometer /></el-icon>健康维度
              </span>
              <span
                v-if="healthOf(project)"
                class="health-dims__score"
                :class="'health-dims__score--' + healthOf(project).tone"
              >{{ healthOf(project).score }}<small>/100</small></span>
            </div>
            <div v-for="d in healthDims" :key="d.label" class="dim-row">
              <span class="dim-row__label">{{ d.label }}</span>
              <div class="dim-row__track">
                <i :style="{ width: d.value + '%', background: d.color }"></i>
              </div>
              <span class="dim-row__value">{{ d.text }}</span>
            </div>
          </div>
        </section>

        <!-- 分析步骤卡 -->
        <section class="glass-card steps-card tg-fade-up" v-loading="loading">
          <div class="section-title">
            <div class="section-title__left">
              <h3><el-icon class="section-title__ic" :size="17"><MagicStick /></el-icon>分析步骤</h3>
              <p>按顺序完成上传与配置</p>
            </div>
          </div>
          <template v-if="project">
            <div class="timeline">
              <div
                v-for="(s, idx) in guideSteps"
                :key="idx"
                class="timeline__step"
                :class="{ 'is-done': idx < currentStep, 'is-current': idx === currentStep }"
              >
                <div class="timeline__track">
                  <span class="timeline__dot">{{ s.num }}</span>
                  <i
                    v-if="idx < guideSteps.length - 1"
                    class="timeline__rail"
                    :class="{ 'is-active': idx < currentStep }"
                  ></i>
                </div>
                <div class="timeline__text">
                  <b>{{ s.title }}</b>
                  <small>{{ s.desc }}</small>
                  <em
                    v-if="idx === 0 && project?.createTime"
                    class="timeline__time"
                  >创建于 {{ project.createTime }}</em>
                  <em
                    v-else-if="idx < currentStep"
                    class="timeline__time is-done-text"
                  >已完成</em>
                  <em v-else-if="idx === currentStep" class="timeline__time is-current-text">进行中</em>
                  <em v-else class="timeline__time">待开始</em>
                </div>
              </div>
            </div>

            <div class="step-content">
              <!-- 第一步：需求文档 -->
              <div v-if="currentStep === 0 || !project.requirementFilePath" class="upload-section">
                <div class="upload-section__head">
                  <span class="upload-section__step">01</span>
                  <h3>上传需求文档</h3>
                </div>
                <p class="tip">支持 Word (.docx)、PDF、Markdown、TXT 格式，可批量选择多个文档；超过10MB的大文件自动分片上传并支持断点续传</p>
                <el-upload
                  class="upload-demo"
                  drag
                  multiple
                  :auto-upload="false"
                  :on-change="handleReqFileChange"
                  :on-remove="handleReqFileRemove"
                  :file-list="reqFileList"
                  accept=".docx,.pdf,.md,.txt"
                >
                  <el-icon class="el-icon--upload"><upload-filled /></el-icon>
                  <div class="el-upload__text">
                    将文件拖到此处，或<em>点击上传</em>（支持多选批量导入）
                  </div>
                </el-upload>
                <el-button type="primary" round :loading="uploadingReq" :disabled="reqFiles.length === 0" @click="uploadReq" style="margin-top: 15px">
                  <el-icon style="margin-right: 4px"><Upload /></el-icon>
                  上传需求文档{{ reqFiles.length > 1 ? `（${reqFiles.length}个）` : '' }}
                </el-button>
                <div v-if="chunkUploading && uploadingReq" class="chunk-progress">
                  <span class="chunk-name">{{ chunkFileName }}</span>
                  <el-progress :percentage="chunkPercent" :stroke-width="10" />
                  <span class="chunk-tip">分片上传中，网络波动将自动重试并续传已传分片</span>
                </div>
                <el-alert v-if="project.requirementFilePath" type="success" :closable="false" style="margin-top: 15px">
                  需求文档已上传
                </el-alert>
              </div>

              <!-- 第二步：代码工程 -->
              <div v-if="currentStep >= 1" class="upload-section">
                <div class="upload-section__head">
                  <span class="upload-section__step">02</span>
                  <h3>上传代码工程</h3>
                </div>
                <p class="tip">请将Java Maven/Gradle项目打包为ZIP格式后上传</p>
                <el-upload
                  class="upload-demo"
                  drag
                  :auto-upload="false"
                  :limit="1"
                  :on-change="handleCodeFileChange"
                  :file-list="codeFileList"
                  accept=".zip"
                >
                  <el-icon class="el-icon--upload"><upload-filled /></el-icon>
                  <div class="el-upload__text">
                    将ZIP文件拖到此处，或<em>点击上传</em>
                  </div>
                </el-upload>
                <el-button type="primary" round :loading="uploadingCode" :disabled="!codeFile" @click="uploadCode" style="margin-top: 15px">
                  <el-icon style="margin-right: 4px"><Upload /></el-icon>
                  上传代码工程
                </el-button>
                <div v-if="chunkUploading && uploadingCode" class="chunk-progress">
                  <span class="chunk-name">{{ chunkFileName }}</span>
                  <el-progress :percentage="chunkPercent" :stroke-width="10" />
                  <span class="chunk-tip">分片上传中，网络波动将自动重试并续传已传分片</span>
                </div>
                <el-alert v-if="project.codeProjectPath" type="success" :closable="false" style="margin-top: 15px">
                  代码工程已上传并解压
                </el-alert>
                <!-- FR-CODE-001 规则4（2.4 整改项）：单 Java 文件批量导入入口（零散源文件场景） -->
                <el-divider content-position="left">或批量导入单个 Java 文件</el-divider>
                <p class="tip">适用于零散 Java 源文件场景；导入后将替换当前代码工程</p>
                <el-upload
                  class="upload-demo"
                  multiple
                  :auto-upload="false"
                  :on-change="handleCodeFilesChange"
                  :on-remove="handleCodeFilesRemove"
                  :file-list="codeFilesList"
                  accept=".java"
                >
                  <el-button><el-icon><Upload /></el-icon> 选择 .java 源文件</el-button>
                  <template #tip>
                    <div class="el-upload__tip">支持多选批量导入（最多 50 个）；解析范围可在系统设置中按包/类/方法配置。</div>
                  </template>
                </el-upload>
                <el-button type="primary" plain round :loading="uploadingCodeFiles" :disabled="codeFilesList.length === 0" @click="uploadCodeFiles" style="margin-top: 10px">
                  导入 Java 文件
                </el-button>
              </div>

              <!-- 第三步：配置参数 -->
              <div v-if="currentStep >= 2" class="config-section">
                <div class="upload-section__head">
                  <span class="upload-section__step">03</span>
                  <h3>配置分析参数</h3>
                </div>
                <el-form :model="taskConfig" label-width="130px" class="config-form">
                  <el-form-item label="任务名称">
                    <el-input v-model="taskConfig.taskName" placeholder="为本次分析任务命名" />
                  </el-form-item>
                  <el-divider content-position="left">权重配置（α+β+γ=1）</el-divider>
                  <el-form-item label="语义相似度 α">
                    <el-slider v-model="taskConfig.alpha" :min="0" :max="1" :step="0.05" show-input />
                  </el-form-item>
                  <el-form-item label="约束匹配度 β">
                    <el-slider v-model="taskConfig.beta" :min="0" :max="1" :step="0.05" show-input />
                  </el-form-item>
                  <el-form-item label="不变量满足度 γ">
                    <el-slider v-model="taskConfig.gamma" :min="0" :max="1" :step="0.05" show-input />
                  </el-form-item>
                  <el-divider content-position="left">阈值配置</el-divider>
                  <el-form-item label="一致性阈值 T1">
                    <el-slider v-model="taskConfig.t1" :min="0.5" :max="1" :step="0.05" show-input />
                  </el-form-item>
                  <el-form-item label="不一致阈值 T2">
                    <el-slider v-model="taskConfig.t2" :min="0" :max="0.6" :step="0.05" show-input />
                  </el-form-item>
                  <el-form-item label=" ">
                    <el-button type="warning" plain round @click="resetConfigDefaults">恢复默认配置</el-button>
                  </el-form-item>
                </el-form>
              </div>
            </div>
          </template>
        </section>
      </div>

      <!-- 右栏 -->
      <aside class="detail-side">
        <!-- 操作卡 -->
        <section class="glass-card side-card tg-fade-up" v-loading="loading">
          <div class="section-title">
            <div class="section-title__left">
              <h3><el-icon class="section-title__ic" :size="17"><VideoPlay /></el-icon>操作</h3>
            </div>
          </div>
          <template v-if="project">
            <el-button
              type="primary"
              size="large"
              round
              class="side-card__btn"
              :disabled="!project.requirementFilePath || !project.codeProjectPath || analyzing"
              :loading="analyzing"
              @click="startAnalysis"
            >
              <el-icon><VideoPlay /></el-icon> 启动全流程分析
            </el-button>
            <el-button size="large" round class="side-card__btn side-card__btn--ghost" @click="viewResults" :disabled="project.status !== 'analyzed'">
              查看分析结果
            </el-button>

            <div v-if="currentTask" class="task-status">
              <h4>当前任务状态</h4>
              <!-- W2-09/O1：分析流水线可视化（六阶段） -->
              <div class="pipeline">
                <div
                  v-for="(st, i) in PIPELINE_STAGES"
                  :key="st.key"
                  class="pipeline__step"
                  :class="{
                    'is-done': i < pipelineActive,
                    'is-current': i === pipelineActive && !pipelineDone,
                    'is-all-done': pipelineDone
                  }"
                >
                  <div class="pipeline__track">
                    <span class="pipeline__dot"></span>
                    <i v-if="i < PIPELINE_STAGES.length - 1" class="pipeline__rail"></i>
                  </div>
                  <span class="pipeline__label">{{ st.title }}</span>
                </div>
              </div>
              <el-progress :percentage="currentTask.progress || 0" :status="taskProgressStatus()" />
              <p class="current-step">{{ currentTask.currentStep }}</p>
              <el-alert v-if="currentTask.status === 'failed'" type="error" :closable="false">
                {{ currentTask.errorMessage }}
              </el-alert>
              <el-alert v-if="currentTask.status === 'interrupted'" type="warning" :closable="false">
                {{ currentTask.errorMessage || '服务重启导致任务中断' }}
              </el-alert>
              <div v-if="currentTask.executionLog" class="log-box">
                <div class="log-box__title">
                  <span><span class="tg-live-dot" v-if="currentTask.status === 'running'"></span>实时日志终端</span>
                </div>
                <!-- GAP-013：对"规约校验失败："等告警/错误行做醒目标记（轻量样式增强，不新增接口） -->
                <div class="log-content">
                  <div
                    v-for="(line, idx) in logLines"
                    :key="idx"
                    :class="logLineClass(line)"
                  >{{ line || ' ' }}</div>
                </div>
              </div>
              <div class="task-controls">
                <el-button
                  v-if="currentTask.status === 'running'"
                  size="small" type="warning" round plain @click="pauseTask"
                >暂停</el-button>
                <el-button
                  v-if="currentTask.status === 'paused'"
                  size="small" type="success" round plain @click="resumeTask"
                >恢复</el-button>
                <el-button
                  v-if="currentTask.status === 'running' || currentTask.status === 'paused'"
                  size="small" type="danger" round plain @click="terminateTask"
                >终止</el-button>
                <el-button
                  v-if="currentTask.status === 'interrupted'"
                  size="small" type="warning" round plain @click="rerunTask(currentTask)"
                >续跑分析</el-button>
                <el-button size="small" type="primary" link @click="refreshTask">刷新状态</el-button>
              </div>
            </div>

            <div class="task-history">
              <h4>历史任务</h4>
              <el-table :data="taskHistory" size="small" v-loading="historyLoading" class="history-table">
                <el-table-column prop="taskName" label="任务" min-width="100" show-overflow-tooltip />
                <el-table-column label="状态" width="80">
                  <template #default="{ row }">
                    <span class="status-pill" :class="'status-pill--task-' + row.status">{{ taskStatusText(row.status) }}</span>
                  </template>
                </el-table-column>
                <el-table-column prop="progress" label="进度" width="60">
                  <template #default="{ row }">{{ row.progress }}%</template>
                </el-table-column>
                <el-table-column prop="createTime" label="创建时间" width="150" />
                <el-table-column label="操作" width="120">
                  <template #default="{ row }">
                    <el-button size="small" type="primary" link @click="viewTask(row)">查看</el-button>
                    <el-button size="small" :type="row.status === 'interrupted' ? 'warning' : 'success'" link :disabled="row.status === 'running' || row.status === 'paused'" @click="rerunTask(row)">{{ row.status === 'interrupted' ? '续跑' : '重新分析' }}</el-button>
                  </template>
                </el-table-column>
              </el-table>
            </div>
          </template>
        </section>
      </aside>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi, analysisApi, wsApi } from '@/api'
import { uploadInChunks, CHUNK_THRESHOLD } from '@/utils/chunkUpload'
import { useCountUp } from '@/composables/useCountUp'
import { computeHealthScore, defectHealth } from '@/utils/healthScore'
import { chartColors } from '@/utils/echartsTheme'

const route = useRoute()
const router = useRouter()
const projectId = route.params.id
const project = ref(null)
const loading = ref(false)
const codeFile = ref(null)
const reqFileList = ref([])
const codeFileList = ref([])
/** FR-CODE-001 2.4：单 Java 文件批量导入 */
const codeFilesList = ref([])
const uploadingCodeFiles = ref(false)
const uploadingReq = ref(false)
const uploadingCode = ref(false)
/** 大文件分片上传进度（仅超过阈值走分片时展示） */
const chunkUploading = ref(false)
const chunkPercent = ref(0)
const chunkFileName = ref('')
const analyzing = ref(false)
const currentTask = ref(null)
let timer = null

// ===== 历史任务（FR-PLAT-002） =====
const taskHistory = ref([])
const historyLoading = ref(false)

const taskConfig = ref({
  taskName: '',
  alpha: 0.4,
  beta: 0.35,
  gamma: 0.25,
  t1: 0.8,
  t2: 0.5
})

/** GAP-012：恢复默认权重/阈值配置（撤销配置修改的误操作） */
const resetConfigDefaults = () => {
  taskConfig.value.alpha = 0.4
  taskConfig.value.beta = 0.35
  taskConfig.value.gamma = 0.25
  taskConfig.value.t1 = 0.8
  taskConfig.value.t2 = 0.5
  ElMessage.success('已恢复默认权重/阈值配置')
}

const currentStep = computed(() => {
  let step = 0
  if (project.value?.requirementFilePath) step = 1
  if (project.value?.codeProjectPath) step = 2
  if (project.value?.status === 'analyzed') step = 4
  else if (project.value?.status === 'running' || currentTask.value?.status === 'running') step = 3
  return step
})

// ===== 顶部步骤条（与模板展示对应） =====
const guideSteps = [
  { num: '01', title: '上传需求文档', desc: 'Word/PDF/Markdown' },
  { num: '02', title: '上传代码工程', desc: 'Maven/Gradle zip' },
  { num: '03', title: '配置参数', desc: '权重与阈值' },
  { num: '04', title: '执行分析', desc: '全流程自动检测' },
  { num: '05', title: '查看结果', desc: '报告与追溯' }
]

// ===== KPI 数字滚动 =====
const reqCountDisp = useCountUp(computed(() => project.value?.requirementCount || 0))
const defectCountDisp = useCountUp(computed(() => project.value?.defectCount || 0))
const coverageDisp = useCountUp(computed(() =>
  project.value?.coverageRate != null ? Math.round(project.value.coverageRate * 100) : 0
))
const taskCountDisp = useCountUp(computed(() => taskHistory.value.length))

// ===== W1-06/O10：健康分与五维分解（数据全部由项目现有字段派生） =====
const healthOf = (p) => (p ? computeHealthScore(p) : null)

const statusColor = {
  analyzed: chartColors.success,
  created: chartColors.neutral,
  running: chartColors.warning,
  failed: chartColors.danger,
  archived: chartColors.purple
}

const healthDims = computed(() => {
  const p = project.value
  if (!p) return []
  const cov = p.coverageRate != null ? Math.round(p.coverageRate * 100) : 0
  const covColor = cov >= 70 ? chartColors.success : cov >= 50 ? chartColors.primary : chartColors.danger
  const dHealth = defectHealth(p.defectCount)
  const reqReady = (p.requirementCount || 0) > 0
  const codeReady = !!p.codeProjectPath
  return [
    {
      label: '覆盖率',
      value: cov,
      text: p.coverageRate != null ? cov + '%' : '—',
      color: covColor
    },
    {
      label: '缺陷健康',
      value: dHealth,
      text: dHealth + ' 分',
      color: dHealth >= 70 ? chartColors.success : dHealth >= 40 ? chartColors.warning : chartColors.danger
    },
    {
      label: '需求上传',
      value: reqReady ? 100 : 0,
      text: reqReady ? `已导入 ${p.requirementCount} 条` : '未上传',
      color: reqReady ? chartColors.primary : chartColors.neutral
    },
    {
      label: '代码上传',
      value: codeReady ? 100 : 0,
      text: codeReady ? '已上传' : '未上传',
      color: codeReady ? chartColors.primary : chartColors.neutral
    },
    {
      label: '分析状态',
      value: p.status === 'analyzed' ? 100 : p.status === 'running' ? 60 : p.status === 'failed' ? 30 : 10,
      text: statusText(p.status),
      color: statusColor[p.status] || chartColors.neutral
    }
  ]
})

// ===== W2-09/O1：分析流水线六阶段（映射任务 currentStep / progress） =====
const PIPELINE_STAGES = [
  { key: 'init', title: '初始化' },
  { key: 'req', title: '需求解析' },
  { key: 'code', title: '代码解析' },
  { key: 'vector', title: '向量匹配' },
  { key: 'cons', title: '一致性' },
  { key: 'report', title: '缺陷报告' }
]

const pipelineDone = computed(
  () => currentTask.value?.status === 'completed' || currentTask.value?.status === 'terminated'
)

// ===== W3-11：扫描健康提示（analyzed 且距上次任务超阈值建议重扫） =====
const RESCAN_DAYS = 7

const rescanHint = computed(() => {
  if (project.value?.status !== 'analyzed') return ''
  const times = (taskHistory.value || [])
    .map((t) => t.createTime)
    .filter(Boolean)
    .map((s) => new Date(String(s).replace(' ', 'T')))
    .filter((d) => !Number.isNaN(d.getTime()))
  if (!times.length) return '已分析但缺少任务记录，建议重新分析以生成最新报告'
  const last = Math.max(...times.map((d) => d.getTime()))
  const days = Math.floor((Date.now() - last) / 86400000)
  if (days >= RESCAN_DAYS) return `距上次分析已 ${days} 天，建议重新分析以获取最新质量结果`
  return ''
})

const pipelineActive = computed(() => {
  const t = currentTask.value
  if (!t) return 0
  if (pipelineDone.value) return PIPELINE_STAGES.length - 1
  const step = t.currentStep || ''
  const prog = t.progress || 0
  if (step.includes('初始化') || prog < 15) return 0
  if (step.includes('需求') || step.includes('规约')) return 1
  if (step.includes('Java')) return 2
  if (step.includes('向量')) return 3
  if (step.includes('一致性')) return 4
  if (step.includes('缺陷') || step.includes('统计') || step.includes('完成')) return 5
  return 0
})

// GAP-013：执行日志按行拆分，供"规约校验失败："等行醒目标记
const logLines = computed(() => {
  const log = currentTask.value?.executionLog
  return log ? log.split('\n') : []
})
const logLineClass = (line) => {
  if (!line) return ''
  if (line.includes('规约校验失败')) return 'log-line-error'
  if (line.includes('警告') || line.includes('解析失败') || line.includes('熔断')) return 'log-line-warn'
  return ''
}

const loadProject = async () => {
  loading.value = true
  try {
    project.value = await projectApi.getById(projectId)
    if (!taskConfig.value.taskName) {
      taskConfig.value.taskName = project.value.projectName + ' - 分析任务'
    }
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

const statusText = (s) => ({ created: '已创建', running: '分析中', analyzed: '已完成', failed: '失败' }[s] || s)

/** 需求文档多选（FR-REQ-001 批量导入） */
const reqFiles = ref([])
const handleReqFileChange = (file, fileList) => {
  reqFiles.value = fileList.map(f => f.raw).filter(Boolean)
}
const handleReqFileRemove = (file, fileList) => {
  reqFiles.value = fileList.map(f => f.raw).filter(Boolean)
}
const handleCodeFileChange = (file) => { codeFile.value = file.raw }

/** 上传单个需求文档：小文件直传，超过阈值走分片（含断点续传） */
const uploadSingleRequirement = async (file) => {
  if (file.size > CHUNK_THRESHOLD) {
    chunkUploading.value = true
    chunkFileName.value = file.name
    await uploadInChunks(file, {
      type: 'requirement',
      projectId,
      onProgress: (p) => { chunkPercent.value = p }
    })
    return
  }
  await analysisApi.uploadRequirement(projectId, file)
}

const uploadReq = async () => {
  if (reqFiles.value.length === 0) return
  uploadingReq.value = true
  try {
    if (reqFiles.value.length === 1) {
      await uploadSingleRequirement(reqFiles.value[0])
    } else {
      const small = reqFiles.value.filter(f => f.size <= CHUNK_THRESHOLD)
      const large = reqFiles.value.filter(f => f.size > CHUNK_THRESHOLD)
      if (small.length > 0) {
        await analysisApi.uploadRequirements(projectId, small)
      }
      for (const file of large) {
        chunkUploading.value = true
        chunkFileName.value = file.name
        await uploadInChunks(file, {
          type: 'requirement',
          projectId,
          onProgress: (p) => { chunkPercent.value = p }
        })
      }
    }
    ElMessage.success(`已上传${reqFiles.value.length}个需求文档`)
    reqFileList.value = []
    reqFiles.value = []
    await loadProject()
  } catch (e) {
    console.error(e)
  } finally {
    uploadingReq.value = false
    chunkUploading.value = false
    chunkPercent.value = 0
  }
}

const uploadCode = async () => {
  if (!codeFile.value) return
  uploadingCode.value = true
  try {
    if (codeFile.value.size > CHUNK_THRESHOLD) {
      chunkUploading.value = true
      chunkFileName.value = codeFile.value.name
      await uploadInChunks(codeFile.value, {
        type: 'code',
        projectId,
        onProgress: (p) => { chunkPercent.value = p }
      })
    } else {
      await analysisApi.uploadCode(projectId, codeFile.value)
    }
    ElMessage.success('代码工程上传成功')
    codeFileList.value = []
    codeFile.value = null
    await loadProject()
  } catch (e) {
    console.error(e)
  } finally {
    uploadingCode.value = false
    chunkUploading.value = false
    chunkPercent.value = 0
  }
}

/* ============ FR-CODE-001 规则4（2.4）：单 Java 文件批量导入 ============ */
const handleCodeFilesChange = (file, fileList) => {
  if (fileList.length > 50) {
    ElMessage.warning('单次最多导入 50 个 Java 文件')
    return false
  }
  codeFilesList.value = fileList.slice(-50)
  return true
}
const handleCodeFilesRemove = (file, fileList) => {
  codeFilesList.value = fileList
}
const uploadCodeFiles = async () => {
  if (!codeFilesList.value.length) return
  uploadingCodeFiles.value = true
  try {
    const files = codeFilesList.value.map(f => f.raw)
    await analysisApi.uploadCodeFiles(projectId, files)
    ElMessage.success('Java 文件导入成功')
    codeFilesList.value = []
    await loadProject()
  } catch (e) {
    ElMessage.error(e.message || '导入失败')
  } finally {
    uploadingCodeFiles.value = false
  }
}

const startAnalysis = async () => {
  // 前端参数校验（与后端 validateWeights 保持一致）
  const cfg = taskConfig.value
  const sum = Number(cfg.alpha) + Number(cfg.beta) + Number(cfg.gamma)
  if (Math.abs(sum - 1) > 0.01) {
    ElMessage.error(`权重之和必须等于 1（当前 α+β+γ=${sum.toFixed(2)}）`)
    return
  }
  if (Number(cfg.t1) <= Number(cfg.t2)) {
    ElMessage.error('阈值 T1（完全一致）必须大于 T2（严重不一致）')
    return
  }
  for (const v of [cfg.alpha, cfg.beta, cfg.gamma, cfg.t1, cfg.t2]) {
    if (v < 0 || v > 1) {
      ElMessage.error('权重与阈值必须在 0~1 范围内')
      return
    }
  }
  try {
    analyzing.value = true
    const task = await analysisApi.createTask({
      projectId: projectId,
      taskName: taskConfig.value.taskName,
      weightAlpha: taskConfig.value.alpha,
      weightBeta: taskConfig.value.beta,
      weightGamma: taskConfig.value.gamma,
      thresholdT1: taskConfig.value.t1,
      thresholdT2: taskConfig.value.t2
    })
    await analysisApi.runTask(task.id)
    currentTask.value = task
    ElMessage.success('分析任务已启动')
    connectProgressWs(task.id)
    startPolling(task.id)
  } catch (e) {
    console.error(e)
  } finally {
    analyzing.value = false
  }
}

/* ========== WebSocket实时进度（轮询作为兜底） ========== */
let ws = null
let wsTaskId = null

// SEC-10：先换取一次性 ticket 再建立 WS 连接（不再把 JWT 放 URL query，避免进代理/访问日志）
const connectProgressWs = async (taskId) => {
  closeProgressWs()
  wsTaskId = taskId
  let ticket = ''
  try {
    // 后端返回 {ticket}，取字符串避免拼接成 [object Object]（原实现对 WS 进度推送无效）
    const res = await wsApi.getTicket()
    ticket = (res && res.ticket) || res
  } catch (e) {
    // 票据获取失败则降级为纯轮询（不阻塞分析启动）
    return
  }
  if (!ticket) return
  const proto = location.protocol === 'https:' ? 'wss' : 'ws'
  ws = new WebSocket(`${proto}://${location.host}/api/ws/progress?ticket=${ticket}`)
  ws.onmessage = (ev) => {
    try {
      const msg = JSON.parse(ev.data)
      if (msg.type === 'progress' && msg.taskId === Number(wsTaskId) && currentTask.value) {
        currentTask.value.progress = msg.progress
        currentTask.value.status = msg.status
        currentTask.value.currentStep = msg.currentStep
        if (msg.status === 'completed' || msg.status === 'failed' || msg.status === 'terminated') {
          finishTask()
        }
      }
    } catch (e) { /* ignore */ }
  }
  ws.onclose = () => { ws = null }
  ws.onerror = () => { try { ws?.close() } catch (e) { /* ignore */ } }
}

const closeProgressWs = () => {
  if (ws) {
    try { ws.close() } catch (e) { /* ignore */ }
    ws = null
  }
}

const finishTask = async () => {
  closeProgressWs()
  if (timer) { clearInterval(timer); timer = null }
  await refreshTask()
  loadTaskHistory()
  if (currentTask.value?.status === 'completed') {
    ElMessage.success('分析完成！')
  }
}

const startPolling = (taskId) => {
  if (timer) clearInterval(timer)
  timer = setInterval(async () => {
    // WebSocket在线时降频为兜底校验（10倍间隔一次），否则保持实时轮询
    try {
      currentTask.value = await analysisApi.getTask(taskId)
      if (currentTask.value.status === 'completed' || currentTask.value.status === 'failed') {
        clearInterval(timer)
        timer = null
        closeProgressWs()
        await loadProject()
        if (currentTask.value.status === 'completed') {
          ElMessage.success('分析完成！')
        }
      }
    } catch (e) {
      console.error(e)
    }
  }, 2000)
}

const refreshTask = async () => {
  if (currentTask.value) {
    currentTask.value = await analysisApi.getTask(currentTask.value.id)
    await loadProject()
  }
}

const taskProgressStatus = () => {
  if (!currentTask.value) return ''
  if (currentTask.value.status === 'completed') return 'success'
  if (currentTask.value.status === 'failed' || currentTask.value.status === 'terminated') return 'exception'
  if (currentTask.value.status === 'interrupted') return 'warning'
  return ''
}

// ===== 任务控制（FR-PLAT-002：暂停/恢复/终止） =====
const pauseTask = async () => {
  try {
    await analysisApi.pauseTask(currentTask.value.id)
    ElMessage.success('暂停请求已发送')
    setTimeout(refreshTask, 600)
  } catch (e) {
    ElMessage.error(e.message || '暂停失败')
  }
}

const resumeTask = async () => {
  try {
    await analysisApi.resumeTask(currentTask.value.id)
    ElMessage.success('任务已恢复')
    refreshTask()
  } catch (e) {
    ElMessage.error(e.message || '恢复失败')
  }
}

const terminateTask = async () => {
  try {
    await ElMessageBox.confirm('确定要终止当前分析任务吗？终止后不可继续。', '警告', { type: 'warning' })
    await analysisApi.terminateTask(currentTask.value.id)
    ElMessage.success('终止请求已发送')
    setTimeout(refreshTask, 600)
    loadTaskHistory()
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}
const loadTaskHistory = async () => {
  historyLoading.value = true
  try {
    const res = await analysisApi.listTasks(projectId, { pageNum: 1, pageSize: 10 })
    taskHistory.value = res.records || []
    // 页面加载时恢复最近一次任务的状态展示（interrupted 为服务重启中断，展示并提供续跑入口）
    if (!currentTask.value && taskHistory.value.length > 0) {
      const latest = taskHistory.value[0]
      if (['running', 'paused', 'pending', 'interrupted'].includes(latest.status)) {
        currentTask.value = latest
        if (latest.status === 'running') {
          connectProgressWs(latest.id)
          startPolling(latest.id)
        }
      }
    }
  } catch (e) {
    console.error(e)
  } finally {
    historyLoading.value = false
  }
}

const viewTask = (row) => {
  currentTask.value = row
}

/** 重新分析/续跑：复用原任务配置创建新任务并执行（FR-PLAT-002 结果复现/重新分析；interrupted 为跨重启续跑） */
const rerunTask = async (row) => {
  const isResume = row.status === 'interrupted'
  const tip = isResume
    ? `任务「${row.taskName}」因服务重启而中断，续跑将自动跳过已完成的阶段，是否继续？`
    : `将复用任务「${row.taskName}」的权重/阈值配置发起重新分析，是否继续？`
  try {
    await ElMessageBox.confirm(tip, isResume ? '续跑分析' : '重新分析', { type: 'info' })
  } catch (e) {
    return
  }
  try {
    const res = await analysisApi.rerunTask(row.id)
    ElMessage.success(isResume ? '已发起续跑分析' : '已发起重新分析')
    currentTask.value = res
    connectProgressWs(res.id)
    startPolling(res.id)
    loadTaskHistory()
  } catch (e) {
    ElMessage.error(e.message || (isResume ? '续跑失败' : '重新分析失败'))
  }
}

const taskStatusText = (status) => {
  const map = { pending: '等待中', running: '运行中', paused: '已暂停', completed: '已完成', failed: '失败', terminated: '已终止', interrupted: '已中断' }
  return map[status] || status
}

const viewResults = () => {
  router.push(`/results/${projectId}`)
}

onMounted(() => {
  loadProject()
  loadTaskHistory()
})

onUnmounted(() => {
  if (timer) clearInterval(timer)
  closeProgressWs()
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

.page-header__back {
  margin-bottom: 6px;
}

.page-header__back-btn {
  color: var(--tg-text-secondary);
  padding: 0;
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

/* W3-11：扫描健康提示条 */
.rescan-hint {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 20px;
  padding: 12px 16px;
  border-radius: 14px;
  background: rgba(232, 155, 60, 0.1);
  border: 1px solid rgba(232, 155, 60, 0.32);
}

.rescan-hint__icon {
  color: var(--tg-warning);
  flex-shrink: 0;
}

.rescan-hint__text {
  flex: 1;
  font-size: 13px;
  color: var(--tg-text-primary);
}

.page-header__actions {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
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

/* ===== 主网格 ===== */
.detail-grid {
  display: grid;
  grid-template-columns: 1.7fr 1fr;
  gap: 24px;
  align-items: start;
}

.detail-main {
  display: flex;
  flex-direction: column;
  gap: 24px;
  min-width: 0;
}

.detail-side {
  min-width: 0;
}

/* 卡片基础 */
.glass-card {
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(var(--tg-blur)) saturate(1.4);
  -webkit-backdrop-filter: blur(var(--tg-blur)) saturate(1.4);
  border: 1px solid var(--tg-border);
  border-radius: 20px;
  padding: 26px;
  box-shadow: var(--tg-shadow-card);
  transition: transform 0.3s var(--tg-ease), box-shadow 0.3s ease;
}

.glass-card:hover {
  transform: translateY(-3px);
  box-shadow: var(--tg-shadow-card-hover);
}

/* 区块标题 */
.section-title {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  margin-bottom: 20px;
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

/* 项目信息描述 */
.info-desc :deep(.el-descriptions__label) {
  color: var(--tg-text-secondary);
  font-weight: 500;
  width: 96px;
}

.info-desc :deep(.el-descriptions__content) {
  color: var(--tg-text-primary);
}

.desc-rate {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 200px;
}

.desc-rate__track {
  flex: 1;
  height: 6px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

.desc-rate__track i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: linear-gradient(90deg, #9A9C6B, #6B8E4E);
  transform-origin: left;
  animation: rate-grow 1s var(--tg-ease) both;
}

.desc-rate span {
  font-size: 13px;
  color: var(--tg-text-secondary);
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

@keyframes rate-grow {
  from {
    transform: scaleX(0);
  }
  to {
    transform: scaleX(1);
  }
}

/* ===== 健康维度五维分解（W1-06/O10） ===== */
.health-dims {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px dashed rgba(60, 45, 25, 0.12);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.health-dims__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 2px;
}

.health-dims__title {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 600;
  color: var(--tg-text-primary);
}

.health-dims__title .el-icon {
  color: var(--tg-accent);
}

.health-dims__score {
  font-size: 20px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  line-height: 1;
}

.health-dims__score small {
  font-size: 11px;
  font-weight: 500;
  color: var(--tg-text-secondary);
  margin-left: 2px;
}

.health-dims__score--success { color: var(--tg-success); }
.health-dims__score--good { color: var(--tg-accent); }
.health-dims__score--warn { color: var(--tg-warning); }
.health-dims__score--danger { color: var(--tg-danger); }

.dim-row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
}

.dim-row__label {
  width: 66px;
  flex-shrink: 0;
  color: var(--tg-text-secondary);
}

.dim-row__track {
  flex: 1;
  height: 6px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

.dim-row__track i {
  display: block;
  height: 100%;
  border-radius: 999px;
  transform-origin: left;
  animation: rate-grow 0.9s var(--tg-ease) both;
}

.dim-row__value {
  width: 92px;
  flex-shrink: 0;
  text-align: right;
  color: var(--tg-text-primary);
  font-variant-numeric: tabular-nums;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

/* ===== 横向项目时间线（W1-07/O13） ===== */
.timeline {
  display: flex;
  padding: 12px 4px 26px;
  border-bottom: 1px solid var(--tg-border);
  margin-bottom: 24px;
}

.timeline__step {
  position: relative;
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  text-align: center;
}

.timeline__track {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
}

.timeline__dot {
  position: relative;
  z-index: 1;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  background: rgba(0, 0, 0, 0.05);
  border: 2px solid rgba(0, 0, 0, 0.06);
  color: var(--tg-text-secondary);
  flex-shrink: 0;
  transition: all 0.35s var(--tg-ease);
}

.timeline__rail {
  position: absolute;
  top: 50%;
  left: calc(50% + 16px);
  right: calc(-50% + 16px);
  height: 3px;
  border-radius: 3px;
  background: rgba(0, 0, 0, 0.08);
  transform: translateY(-50%);
  transition: background 0.4s var(--tg-ease);
}

.timeline__step.is-done .timeline__dot {
  background: var(--tg-accent-gradient);
  border-color: transparent;
  color: #fff;
  box-shadow: var(--tg-glow-accent);
}

.timeline__step.is-done .timeline__rail.is-active {
  background: linear-gradient(90deg, rgba(143, 107, 34, 0.55), var(--tg-accent));
}

.timeline__step.is-current .timeline__dot {
  background: rgba(201, 155, 63, 0.2);
  border-color: var(--tg-accent);
  color: var(--tg-accent);
  box-shadow: 0 0 0 5px rgba(201, 155, 63, 0.16);
  animation: tg-breath 2.6s ease-in-out infinite;
}

.timeline__text {
  display: flex;
  flex-direction: column;
  align-items: center;
  min-width: 0;
  padding: 0 4px;
}

.timeline__text b {
  font-size: 13px;
  font-weight: 600;
  color: var(--tg-text-primary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

.timeline__text small {
  font-size: 11px;
  color: var(--tg-text-secondary);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
  margin-top: 1px;
}

.timeline__time {
  margin-top: 3px;
  font-size: 11px;
  font-style: normal;
  color: var(--tg-slate);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

.timeline__time.is-done-text {
  color: var(--tg-success);
}

.timeline__time.is-current-text {
  color: var(--tg-accent);
  font-weight: 500;
}

/* 上传 / 配置区 */
.upload-section__head {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 12px;
}

.upload-section__step {
  font-size: 12px;
  font-weight: 700;
  color: var(--tg-accent);
  background: var(--el-color-primary-light-9);
  padding: 3px 10px;
  border-radius: 999px;
}

.upload-section h3 {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
  color: var(--tg-text-primary);
}

.tip {
  color: var(--tg-text-secondary);
  margin-bottom: 15px;
  font-size: 13px;
  line-height: 1.7;
}

.config-form {
  max-width: 640px;
  margin-top: 20px;
}

/* 上传拖拽区统一风格 */
.upload-section :deep(.el-upload-dragger) {
  background: rgba(255, 255, 255, 0.5);
  border: 1.5px dashed rgba(143, 107, 34, 0.28);
  border-radius: 14px;
  transition: border-color 0.25s ease, background 0.25s ease;
}

.upload-section :deep(.el-upload-dragger:hover) {
  border-color: var(--tg-accent);
  background: rgba(255, 255, 255, 0.8);
}

.upload-section :deep(.el-upload-dragger .el-icon--upload) {
  color: var(--tg-accent);
}

.chunk-progress {
  margin-top: 12px;
}

.chunk-name {
  font-size: 13px;
  color: var(--tg-text-secondary);
  display: block;
  margin-bottom: 6px;
  word-break: break-all;
}

.chunk-tip {
  display: block;
  font-size: 12px;
  color: var(--tg-text-secondary);
  margin-top: 4px;
}

/* ===== 右栏操作卡 ===== */
.side-card__btn {
  width: 100%;
  margin-bottom: 12px;
}

.side-card__btn--ghost {
  border-color: rgba(143, 107, 34, 0.22);
  color: var(--tg-accent);
  background: rgba(255, 255, 255, 0.6);
}

.task-status h4,
.task-history h4 {
  margin: 0 0 15px;
  font-size: 14px;
  font-weight: 600;
  color: var(--tg-text-primary);
}

.task-status {
  margin-top: 6px;
}

/* W2-09/O1：分析流水线（六阶段） */
.pipeline {
  display: flex;
  margin: 10px 0 14px;
  padding: 4px 2px;
}

.pipeline__step {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.pipeline__track {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 100%;
}

.pipeline__dot {
  position: relative;
  z-index: 1;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.05);
  border: 2px solid rgba(0, 0, 0, 0.07);
  flex-shrink: 0;
  transition: all 0.3s var(--tg-ease);
}

.pipeline__rail {
  position: absolute;
  top: 50%;
  left: calc(50% + 9px);
  right: calc(-50% + 9px);
  height: 3px;
  border-radius: 3px;
  background: rgba(0, 0, 0, 0.07);
  transform: translateY(-50%);
  transition: background 0.4s var(--tg-ease);
}

.pipeline__step.is-done .pipeline__dot {
  background: var(--tg-accent-gradient);
  border-color: transparent;
  box-shadow: var(--tg-glow-accent);
}

.pipeline__step.is-done .pipeline__rail {
  background: linear-gradient(90deg, rgba(143, 107, 34, 0.55), var(--tg-accent));
}

.pipeline__step.is-current .pipeline__dot {
  border-color: var(--tg-accent);
  background: rgba(201, 155, 63, 0.2);
  box-shadow: 0 0 0 4px rgba(201, 155, 63, 0.16);
  animation: tg-breath 2.6s ease-in-out infinite;
}

.pipeline__step.is-all-done .pipeline__dot {
  background: var(--tg-success);
  border-color: transparent;
  box-shadow: 0 0 0 3px rgba(107, 142, 78, 0.18);
}

.pipeline__step.is-all-done .pipeline__rail {
  background: linear-gradient(90deg, rgba(107, 142, 78, 0.6), var(--tg-success));
}

.pipeline__label {
  font-size: 10.5px;
  color: var(--tg-slate);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 100%;
}

.pipeline__step.is-done .pipeline__label,
.pipeline__step.is-current .pipeline__label {
  color: var(--tg-accent);
  font-weight: 500;
}

.pipeline__step.is-all-done .pipeline__label {
  color: var(--tg-success);
}

.log-box__title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  font-weight: 600;
  color: var(--tg-text-secondary);
  margin-bottom: 8px;
}

.current-step {
  margin-top: 10px;
  color: var(--tg-text-secondary);
  font-size: 13.5px;
}

.log-box {
  margin-top: 15px;
  background: var(--tg-bg-code);
  border: 1px solid var(--tg-border);
  border-radius: 12px;
  padding: 12px;
  max-height: 200px;
  overflow-y: auto;
}

.log-content {
  font-family: var(--tg-font-mono);
  font-size: 12px;
  white-space: pre-wrap;
  color: var(--tg-text-secondary);
  line-height: 1.7;
}

/* GAP-013：执行日志中"规约校验失败："等告警/错误行醒目标记 */
.log-line-error {
  color: var(--tg-danger);
  font-weight: 600;
}
.log-line-warn {
  color: var(--tg-warning);
}

.task-controls {
  margin-top: 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

/* 历史任务表格 */
.history-table :deep(.el-table) {
  --el-table-border-color: rgba(0, 0, 0, 0.05);
  --el-table-header-bg-color: transparent;
  --el-table-row-hover-bg-color: rgba(201, 155, 63, 0.05);
  background: transparent;
}

.history-table :deep(.el-table th.el-table__cell) {
  font-size: 11.5px;
  font-weight: 600;
  color: var(--tg-text-secondary);
  background: rgba(0, 0, 0, 0.02);
}

/* 状态胶囊（与 Dashboard/Projects 一致） */
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

/* 历史任务状态 */
.status-pill--task-completed {
  background: rgba(154, 156, 107, 0.14);
  color: #55682e;
}
.status-pill--task-completed::before {
  background: var(--tg-success);
}

.status-pill--task-running {
  background: rgba(232, 155, 60, 0.14);
  color: #9a5d12;
}
.status-pill--task-running::before {
  background: var(--tg-amber);
}

.status-pill--task-pending {
  background: rgba(0, 0, 0, 0.05);
  color: var(--tg-text-secondary);
}
.status-pill--task-pending::before {
  background: var(--tg-slate);
}

.status-pill--task-paused {
  background: rgba(232, 155, 60, 0.14);
  color: #9a5d12;
}
.status-pill--task-paused::before {
  background: var(--tg-amber);
}

.status-pill--task-failed,
.status-pill--task-interrupted {
  background: rgba(194, 94, 76, 0.12);
  color: #9a3f30;
}
.status-pill--task-failed::before,
.status-pill--task-interrupted::before {
  background: var(--tg-danger);
}

.status-pill--task-terminated {
  background: rgba(0, 0, 0, 0.05);
  color: var(--tg-text-secondary);
}
.status-pill--task-terminated::before {
  background: var(--tg-slate);
}

/* 窄屏 */
@media (max-width: 1280px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }

  .detail-grid {
    grid-template-columns: 1fr;
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

  .timeline {
    flex-direction: column;
    gap: 12px;
  }

  .timeline__step {
    flex-direction: row;
    align-items: center;
    justify-content: flex-start;
    gap: 12px;
    text-align: left;
  }

  .timeline__track {
    width: auto;
    flex-shrink: 0;
  }

  .timeline__rail {
    display: none;
  }

  .timeline__text {
    align-items: flex-start;
  }
}
</style>
