<template>
  <div class="project-detail">
    <el-page-header @back="$router.back()" :content="project?.projectName || '项目详情'" style="margin-bottom: 20px" />

    <el-row :gutter="20">
      <el-col :span="16">
        <el-card>
          <template #header>
            <span>项目信息</span>
          </template>
          <el-descriptions :column="2" border v-if="project">
            <el-descriptions-item label="项目名称">{{ project.projectName }}</el-descriptions-item>
            <el-descriptions-item label="行业类型">{{ project.industryType }}</el-descriptions-item>
            <el-descriptions-item label="技术栈">{{ project.techStack }}</el-descriptions-item>
            <el-descriptions-item label="状态">
              <el-tag :type="statusType(project.status)">{{ statusText(project.status) }}</el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="需求数">{{ project.requirementCount || 0 }}</el-descriptions-item>
            <el-descriptions-item label="缺陷数">{{ project.defectCount || 0 }}</el-descriptions-item>
            <el-descriptions-item label="覆盖率" :span="2">
              {{ project.coverageRate != null ? (project.coverageRate * 100).toFixed(1) + '%' : '-' }}
            </el-descriptions-item>
            <el-descriptions-item label="项目描述" :span="2">{{ project.description }}</el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-card style="margin-top: 20px" v-loading="loading">
          <template #header>
            <span>分析步骤</span>
          </template>
          <template v-if="project">
          <el-steps :active="currentStep" finish-status="success" align-center>
            <el-step title="上传需求文档" />
            <el-step title="上传代码工程" />
            <el-step title="配置参数" />
            <el-step title="执行分析" />
            <el-step title="查看结果" />
          </el-steps>

          <div class="step-content" style="margin-top: 30px">
            <div v-if="currentStep === 0 || !project.requirementFilePath" class="upload-section">
              <h3>第一步：上传需求文档</h3>
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
              <el-button type="primary" :loading="uploadingReq" :disabled="reqFiles.length === 0" @click="uploadReq" style="margin-top: 15px">
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

            <div v-if="currentStep >= 1" class="upload-section">
              <h3>第二步：上传代码工程</h3>
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
              <el-button type="primary" :loading="uploadingCode" :disabled="!codeFile" @click="uploadCode" style="margin-top: 15px">
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
              <el-button type="primary" plain :loading="uploadingCodeFiles" :disabled="codeFilesList.length === 0" @click="uploadCodeFiles" style="margin-top: 10px">
                导入 Java 文件
              </el-button>
            </div>

            <div v-if="currentStep >= 2" class="config-section">
              <h3>第三步：配置分析参数</h3>
              <el-form :model="taskConfig" label-width="120px" style="max-width: 600px; margin-top: 20px">
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
                  <el-button type="warning" plain @click="resetConfigDefaults">恢复默认配置</el-button>
                </el-form-item>
              </el-form>
            </div>
          </div>
          </template>
        </el-card>
      </el-col>

      <el-col :span="8">
        <el-card v-loading="loading">
          <template #header>
            <span>操作</span>
          </template>
          <template v-if="project">
          <el-button
            type="primary"
            size="large"
            style="width: 100%; margin-bottom: 15px"
            :disabled="!project.requirementFilePath || !project.codeProjectPath || analyzing"
            :loading="analyzing"
            @click="startAnalysis"
          >
            <el-icon><VideoPlay /></el-icon> 启动全流程分析
          </el-button>

          <el-button style="width: 100%; margin-bottom: 15px" @click="viewResults" :disabled="project.status !== 'analyzed'">
            查看分析结果
          </el-button>

          <el-divider />

          <div v-if="currentTask" class="task-status">
            <h4>当前任务状态</h4>
            <el-progress :percentage="currentTask.progress || 0" :status="taskProgressStatus()" />
            <p class="current-step">{{ currentTask.currentStep }}</p>
            <el-alert v-if="currentTask.status === 'failed'" type="error" :closable="false">
              {{ currentTask.errorMessage }}
            </el-alert>
            <el-alert v-if="currentTask.status === 'interrupted'" type="warning" :closable="false">
              {{ currentTask.errorMessage || '服务重启导致任务中断' }}
            </el-alert>
            <div v-if="currentTask.executionLog" class="log-box">
              <!-- GAP-013：对"规约校验失败："等告警/错误行做醒目标记（轻量样式增强，不新增接口） -->
              <div class="log-content">
                <div
                  v-for="(line, idx) in logLines"
                  :key="idx"
                  :class="logLineClass(line)"
                >{{ line || ' ' }}</div>
              </div>
            </div>
            <div class="task-controls" style="margin-top: 10px">
              <el-button
                v-if="currentTask.status === 'running'"
                size="small" type="warning" plain @click="pauseTask"
              >暂停</el-button>
              <el-button
                v-if="currentTask.status === 'paused'"
                size="small" type="success" plain @click="resumeTask"
              >恢复</el-button>
              <el-button
                v-if="currentTask.status === 'running' || currentTask.status === 'paused'"
                size="small" type="danger" plain @click="terminateTask"
              >终止</el-button>
              <el-button
                v-if="currentTask.status === 'interrupted'"
                size="small" type="warning" plain @click="rerunTask(currentTask)"
              >续跑分析</el-button>
              <el-button size="small" type="primary" link @click="refreshTask">刷新状态</el-button>
            </div>
          </div>

          <el-divider />

          <div class="task-history">
            <h4>历史任务</h4>
            <el-table :data="taskHistory" size="small" v-loading="historyLoading">
              <el-table-column prop="taskName" label="任务" min-width="110" show-overflow-tooltip />
              <el-table-column label="状态" width="85">
                <template #default="{ row }">
                  <el-tag size="small" :type="taskStatusType(row.status)">{{ taskStatusText(row.status) }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column prop="progress" label="进度" width="70">
                <template #default="{ row }">{{ row.progress }}%</template>
              </el-table-column>
              <el-table-column prop="createTime" label="创建时间" width="160" />
              <el-table-column label="操作" width="130">
                <template #default="{ row }">
                  <el-button size="small" type="primary" link @click="viewTask(row)">查看</el-button>
                  <el-button size="small" :type="row.status === 'interrupted' ? 'warning' : 'success'" link :disabled="row.status === 'running' || row.status === 'paused'" @click="rerunTask(row)">{{ row.status === 'interrupted' ? '续跑' : '重新分析' }}</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
          </template>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi, analysisApi, wsApi } from '@/api'
import { uploadInChunks, CHUNK_THRESHOLD } from '@/utils/chunkUpload'

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

const statusType = (s) => ({ created: 'info', running: 'warning', analyzed: 'success', failed: 'danger' }[s] || 'info')
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
    ticket = await wsApi.getTicket()
  } catch (e) {
    // 票据获取失败则降级为纯轮询（不阻塞分析启动）
    return
  }
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

// ===== 历史任务（FR-PLAT-002） =====
const taskHistory = ref([])
const historyLoading = ref(false)

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

const taskStatusType = (status) => {
  const map = { pending: 'info', running: 'warning', paused: 'warning', completed: 'success', failed: 'danger', terminated: 'info', interrupted: 'danger' }
  return map[status] || 'info'
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
.tip {
  color: #999;
  margin-bottom: 15px;
}

.upload-section h3 {
  margin-bottom: 10px;
}

.chunk-progress {
  margin-top: 12px;
}

.chunk-name {
  font-size: 13px;
  color: #606266;
  display: block;
  margin-bottom: 6px;
  word-break: break-all;
}

.chunk-tip {
  display: block;
  font-size: 12px;
  color: #999;
  margin-top: 4px;
}

.task-status h4 {
  margin: 0 0 15px 0;
}

.current-step {
  margin-top: 10px;
  color: #666;
  font-size: 14px;
}

.log-box {
  margin-top: 15px;
  background: #f5f7fa;
  border-radius: 4px;
  padding: 10px;
  max-height: 200px;
  overflow-y: auto;
}

.log-content {
  font-family: monospace;
  font-size: 12px;
  white-space: pre-wrap;
  color: #606266;
}

/* GAP-013：执行日志中"规约校验失败："等告警/错误行醒目标记 */
.log-line-error {
  color: #f56c6c;
  font-weight: 600;
}
.log-line-warn {
  color: #e6a23c;
}
</style>
