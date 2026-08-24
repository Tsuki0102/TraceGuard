<template>
  <div class="dashboard">
    <div class="welcome-card">
      <h2>欢迎使用 TraceGuard</h2>
      <p>基于形式化需求规约与大模型融合的软件需求-代码一致性验证与缺陷自动检测系统</p>
    </div>

    <el-row :gutter="20" class="stats-cards">
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-icon" style="background: #409EFF">
              <el-icon :size="32" color="white"><Folder /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ projectCount }}</div>
              <div class="stat-label">项目总数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-icon" style="background: #67C23A">
              <el-icon :size="32" color="white"><CircleCheck /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ analyzedCount }}</div>
              <div class="stat-label">已分析项目</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-icon" style="background: #E6A23C">
              <el-icon :size="32" color="white"><Warning /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ totalDefects }}</div>
              <div class="stat-label">累计发现缺陷</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-item">
            <div class="stat-icon" style="background: #F56C6C">
              <el-icon :size="32" color="white"><DataLine /></el-icon>
            </div>
            <div class="stat-info">
              <div class="stat-value">{{ avgCoverage }}%</div>
              <div class="stat-label">平均需求覆盖率</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="16">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>多项目对比</span>
              <div class="compare-controls">
                <el-select
                  v-model="compareIds"
                  multiple
                  collapse-tags
                  collapse-tags-tooltip
                  placeholder="选择2个及以上项目进行对比"
                  style="width: 320px; margin-right: 10px"
                >
                  <el-option
                    v-for="p in allProjects"
                    :key="p.id"
                    :label="p.projectName"
                    :value="p.id"
                    :disabled="compareIds.length >= 5 && !compareIds.includes(p.id)"
                  />
                </el-select>
                <el-button type="primary" :disabled="compareIds.length < 2" :loading="comparing" @click="doCompare">
                  生成对比
                </el-button>
                <el-button type="success" :disabled="compareIds.length < 2" :loading="exportingCompare" @click="handleExportCompare">
                  导出对比Excel
                </el-button>
              </div>
            </div>
          </template>
          <div v-show="compareData.length > 0" ref="compareChartRef" class="compare-chart"></div>
          <el-empty v-if="compareData.length === 0" description="选择项目后生成覆盖率 / 质量评分 / 缺陷数对比图" :image-size="80" />
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <template #header>
            <span>对比明细</span>
          </template>
          <el-table :data="compareData" size="small" v-show="compareData.length > 0">
            <el-table-column prop="projectName" label="项目" min-width="100" show-overflow-tooltip />
            <el-table-column label="覆盖率" width="80">
              <template #default="{ row }">{{ row.coverageRate != null ? row.coverageRate.toFixed(1) + '%' : '-' }}</template>
            </el-table-column>
            <el-table-column prop="codeQualityScore" label="质量分" width="70">
              <template #default="{ row }">{{ row.codeQualityScore ?? '-' }}</template>
            </el-table-column>
            <el-table-column prop="totalDefects" label="缺陷数" width="70" />
          </el-table>
          <el-empty v-if="compareData.length === 0" description="暂无对比数据" :image-size="60" />
        </el-card>
      </el-col>
    </el-row>

    <el-row :gutter="20" style="margin-top: 20px">
      <el-col :span="16">
        <el-card>
          <template #header>
            <div class="card-header">
              <span>最近项目</span>
              <el-button type="primary" link @click="$router.push('/projects')">查看全部</el-button>
            </div>
          </template>
          <el-table :data="projects" style="width: 100%">
            <el-table-column prop="projectName" label="项目名称" />
            <el-table-column prop="industryType" label="行业类型" width="120" />
            <el-table-column prop="status" label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="coverageRate" label="覆盖率" width="100">
              <template #default="{ row }">
                {{ row.coverageRate ? (row.coverageRate * 100).toFixed(1) + '%' : '-' }}
              </template>
            </el-table-column>
            <el-table-column prop="createTime" label="创建时间" width="180" />
            <el-table-column label="操作" width="260">
              <template #default="{ row }">
                <el-button type="primary" link size="small" @click="goToProject(row)">查看</el-button>
                <el-button type="warning" link size="small" @click="goToProject(row)">发起分析</el-button>
                <el-button v-if="row.status === 'analyzed'" type="success" link size="small" @click="goToResults(row)">查看报告</el-button>
                <el-button v-if="row.status === 'analyzed'" type="info" link size="small" @click="goToTraceability(row)">追溯查询</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card>
          <template #header>
            <span>快速开始</span>
          </template>
          <div class="quick-actions">
            <el-button type="primary" size="large" style="width: 100%; margin-bottom: 15px" @click="showCreateDialog = true">
              <el-icon><Plus /></el-icon> 创建新项目
            </el-button>
            <el-alert
              v-if="guideProject"
              :title="`当前进行：${guideProject.projectName}`"
              :description="guideActiveText"
              type="info"
              :closable="false"
              show-icon
              style="margin-bottom: 15px"
            />
            <el-steps direction="vertical" :active="guideActive" class="guide-steps">
              <el-step v-for="(step, idx) in guideSteps" :key="idx">
                <template #title>
                  <span class="step-clickable" @click="handleStepClick(idx)">{{ step.title }}</span>
                </template>
                <template #description>
                  <span class="step-clickable" @click="handleStepClick(idx)">{{ step.description }}</span>
                </template>
              </el-step>
            </el-steps>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-dialog v-model="showCreateDialog" title="创建新项目" width="500px">
      <el-form :model="newProject" label-width="100px">
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
        <el-button type="primary" @click="createProject">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, nextTick, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import * as echarts from 'echarts'
import { projectApi, resultApi, exportApi } from '@/api'

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

// ===== 多项目对比（FR-PLAT-003） =====
const compareIds = ref([])
const compareData = ref([])
const comparing = ref(false)
const compareChartRef = ref(null)
let compareChart = null

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
    compareChart = echarts.init(compareChartRef.value)
  }
  const names = compareData.value.map(d => d.projectName)
  compareChart.setOption({
    tooltip: { trigger: 'axis' },
    toolbox: {
      show: true,
      right: 20,
      feature: { saveAsImage: { title: '导出图片', name: '多项目对比' } }
    },
    legend: { data: ['需求覆盖率(%)', '代码质量评分', '缺陷总数'] },
    grid: { left: 50, right: 20, top: 40, bottom: 30 },
    xAxis: { type: 'category', data: names, axisLabel: { interval: 0, rotate: names.length > 3 ? 15 : 0 } },
    yAxis: { type: 'value' },
    series: [
      { name: '需求覆盖率(%)', type: 'bar', data: compareData.value.map(d => d.coverageRate ?? 0), itemStyle: { color: '#409EFF' } },
      { name: '代码质量评分', type: 'bar', data: compareData.value.map(d => d.codeQualityScore ?? 0), itemStyle: { color: '#67C23A' } },
      { name: '缺陷总数', type: 'bar', data: compareData.value.map(d => d.totalDefects ?? 0), itemStyle: { color: '#E6A23C' } }
    ]
  })
}

const handleResize = () => {
  compareChart?.resize()
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
  } catch (e) {
    console.error(e)
  }
}

const statusType = (status) => {
  if (status === 'analyzed') return 'success'
  if (status === 'failed') return 'danger'
  if (status === 'running') return 'warning'
  return 'info'
}

const statusText = (status) => {
  const map = { created: '已创建', running: '分析中', analyzed: '已完成', failed: '失败' }
  return map[status] || status
}

// ===== 快速开始新手引导 =====
const guideSteps = [
  { title: '创建项目', description: '填写项目基本信息' },
  { title: '上传需求文档', description: '支持Word/PDF/Markdown' },
  { title: '上传代码工程', description: '支持Maven/Gradle项目zip包' },
  { title: '启动分析', description: '一键执行全流程分析' },
  { title: '查看报告', description: '查看缺陷与一致性报告' }
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

const guideActiveText = computed(() => {
  if (guideActive.value === 0) return '从创建第一个项目开始'
  return `下一步：${guideSteps[Math.min(guideActive.value, 4)].title}`
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

onMounted(() => {
  loadProjects()
  window.addEventListener('resize', handleResize)
})

onUnmounted(() => {
  window.removeEventListener('resize', handleResize)
  compareChart?.dispose()
  compareChart = null
})
</script>

<style scoped>
.welcome-card {
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  color: white;
  padding: 30px;
  border-radius: 8px;
  margin-bottom: 20px;
}

.welcome-card h2 {
  margin: 0 0 10px 0;
  font-size: 24px;
}

.welcome-card p {
  margin: 0;
  opacity: 0.9;
}

.stats-cards .stat-item {
  display: flex;
  align-items: center;
  gap: 15px;
}

.stat-icon {
  width: 60px;
  height: 60px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.stat-value {
  font-size: 28px;
  font-weight: bold;
  color: #333;
}

.stat-label {
  color: #999;
  font-size: 14px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.compare-controls {
  display: flex;
  align-items: center;
}

.compare-chart {
  width: 100%;
  height: 320px;
}

.quick-actions {
  padding: 10px 0;
}

.guide-steps {
  margin-top: 20px;
}

.step-clickable {
  cursor: pointer;
}

.step-clickable:hover {
  color: #409EFF;
}
</style>
