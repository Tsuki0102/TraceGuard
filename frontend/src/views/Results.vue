<template>
  <div class="results-page">
    <el-page-header @back="$router.back()" content="分析结果" style="margin-bottom: 20px">
      <template #extra>
        <!-- GAP-010：模板选择（从后端动态加载） -->
        <el-select v-model="selectedTemplateId" style="width: 160px; margin-right: 12px" placeholder="报告模板" @change="onTemplateChange">
          <el-option v-for="t in templateList" :key="t.id" :label="t.templateName + (t.isDefault ? '（默认）' : '')" :value="t.id" />
        </el-select>
        <el-button @click="showTemplateDialog = true" style="margin-right: 12px">
          <el-icon><Setting /></el-icon> 管理模板
        </el-button>
        <el-button type="success" :loading="exportingStats" @click="handleExportStats">
          <el-icon><Download /></el-icon> 导出统计Excel
        </el-button>
        <el-button type="primary" :loading="exportingWord" @click="handleExportWord">
          <el-icon><Download /></el-icon> 导出Word报告
        </el-button>
        <el-button type="danger" :loading="exportingPdf" @click="handleExportPdf">
          <el-icon><Download /></el-icon> 导出PDF报告
        </el-button>
        <el-button type="info" plain :loading="printingReport" @click="handlePrintReport">
          <el-icon><Printer /></el-icon> 打印报告
        </el-button>
        <el-button type="primary" plain :loading="previewingPdf" @click="handlePreviewPdf">
          <el-icon><View /></el-icon> 预览PDF
        </el-button>
        <el-button type="warning" plain :loading="previewingWord" @click="handlePreviewWord">
          <el-icon><View /></el-icon> 预览Word
        </el-button>
      </template>
    </el-page-header>

    <!-- AUD-06：报告在线预览 -->
    <el-dialog v-model="showPreview" :title="previewTitle" width="90%" top="5vh" destroy-on-close @closed="onPreviewClosed">
      <div v-if="previewType === 'pdf'" style="height: 75vh">
        <iframe :src="previewUrl" type="application/pdf" style="width: 100%; height: 100%; border: none" />
      </div>
      <div v-else style="height: 75vh; display: flex; flex-direction: column; align-items: center; justify-content: center">
        <el-result icon="info" title="Word 文档预览" sub-title="当前浏览器不支持内嵌预览 .docx，请下载后查看，或点击「在新窗口打开」">
          <template #extra>
            <el-button type="primary" @click="openPreviewInNewTab">在新窗口打开</el-button>
          </template>
        </el-result>
      </div>
    </el-dialog>

    <!-- GAP-010：模板管理弹窗 -->
    <el-dialog v-model="showTemplateDialog" title="报告模板管理" width="720px" destroy-on-close>
      <div style="margin-bottom: 12px">
        <el-button type="primary" @click="openTemplateForm(null)">新建模板</el-button>
      </div>
      <el-table :data="templateList" stripe size="small">
        <el-table-column prop="templateName" label="模板名称" width="140" />
        <el-table-column label="章节" min-width="240">
          <template #default="{ row }">
            <el-tag v-for="(s, i) in parseSections(row.sections)" :key="i" size="small" style="margin: 2px">
              {{ s.title || sectionKeyLabel(s.key) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="默认" width="60" align="center">
          <template #default="{ row }">
            <el-tag v-if="row.isDefault" type="success" size="small">是</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="200" align="center">
          <template #default="{ row }">
            <el-button v-if="!row.isSystem" link type="primary" @click="openTemplateForm(row)">编辑</el-button>
            <!-- 细粒度数据权限：系统模板仅管理员可设默认（非管理员点击后端亦返回 403） -->
            <el-button v-if="!row.isSystem && !row.isDefault" link type="success" @click="handleSetDefault(row)">设为默认</el-button>
            <el-button v-if="!row.isSystem" link type="danger" @click="handleDeleteTemplate(row)">删除</el-button>
            <el-tag v-if="row.isSystem" size="small" type="info">系统</el-tag>
          </template>
        </el-table-column>
      </el-table>
    </el-dialog>

    <!-- GAP-010：模板编辑弹窗 -->
    <el-dialog v-model="showTemplateForm" :title="editingTemplate ? '编辑模板' : '新建模板'" width="560px" destroy-on-close>
      <el-form :model="templateForm" label-width="90px">
        <el-form-item label="模板名称" required>
          <el-input v-model="templateForm.templateName" placeholder="请输入模板名称" />
        </el-form-item>
        <el-form-item label="报告标题">
          <el-input v-model="templateForm.title" placeholder="留空使用系统默认标题" />
        </el-form-item>
        <!-- 4.4 整改：报告排版自定义（封面副标题 + 页眉文本） -->
        <el-form-item label="封面副标题">
          <el-input v-model="templateForm.subtitle" placeholder="留空不显示（如：2026 年度质量评审）" />
        </el-form-item>
        <el-form-item label="页眉文本">
          <el-input v-model="templateForm.headerText" placeholder="留空使用报告标题作为页眉" />
        </el-form-item>
        <el-form-item label="章节配置" required>
          <div v-for="(s, i) in templateForm.sectionItems" :key="i" style="display: flex; gap: 8px; margin-bottom: 6px; align-items: center">
            <el-select v-model="s.key" placeholder="选择章节" style="width: 200px">
              <el-option v-for="sk in allSectionKeys" :key="sk.key" :label="sk.label" :value="sk.key" :disabled="templateForm.sectionItems.some(x => x.key === sk.key && x !== s)" />
            </el-select>
            <el-input v-model="s.title" placeholder="自定义标题（留空用默认）" style="flex: 1" />
            <el-button link type="danger" @click="templateForm.sectionItems.splice(i, 1)" :disabled="templateForm.sectionItems.length <= 1">
              <el-icon><Delete /></el-icon>
            </el-button>
          </div>
          <el-button link type="primary" @click="templateForm.sectionItems.push({ key: '', title: '' })">+ 添加章节</el-button>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showTemplateForm = false">取消</el-button>
        <el-button type="primary" :loading="savingTemplate" @click="handleSaveTemplate">保存</el-button>
      </template>
    </el-dialog>

    <!-- GAP-014 / 4.12 整改：需求文档解析失败结构化修正建议视图 -->
    <el-card
      v-if="parseFailures.length"
      class="parse-failure-card"
      shadow="hover"
      style="margin-bottom: 16px"
    >
      <template #header>
        <div class="parse-failure-header">
          <el-icon color="#E6A23C"><WarningFilled /></el-icon>
          <span style="font-weight: 600">{{ parseFailures.length }} 个需求文档解析失败（已隔离，不影响已成功文档）</span>
          <el-button link type="primary" size="small" @click="goToUpload">前往重新上传</el-button>
        </div>
      </template>
      <el-collapse>
        <el-collapse-item v-for="(f, idx) in parseFailures" :key="idx" :name="idx">
          <template #title>
            <span class="pf-file">{{ f.fileName || '未知文件' }}</span>
            <el-tag size="small" type="warning" style="margin-left: 8px">解析异常</el-tag>
          </template>
          <div class="pf-detail">
            <div class="pf-row"><span class="pf-label">文件：</span><span>{{ f.fileName }}</span></div>
            <div class="pf-row"><span class="pf-label">原因：</span><span>{{ f.reason }}</span></div>
            <div class="pf-row" v-if="f.suggestion">
              <span class="pf-label">修正建议：</span><span class="pf-suggestion">{{ f.suggestion }}</span>
            </div>
          </div>
        </el-collapse-item>
      </el-collapse>
    </el-card>

    <el-row :gutter="20" v-if="stats">
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-card">
            <div class="stat-icon success">
              <el-icon :size="28" color="white"><CircleCheck /></el-icon>
            </div>
            <div class="stat-text">
              <div class="stat-value">{{ stats.coverageRate || 0 }}%</div>
              <div class="stat-label">需求覆盖率</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-card">
            <div class="stat-icon primary">
              <el-icon :size="28" color="white"><Document /></el-icon>
            </div>
            <div class="stat-text">
              <div class="stat-value">{{ stats.totalRequirements || 0 }}</div>
              <div class="stat-label">需求总数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-card">
            <div class="stat-icon danger">
              <el-icon :size="28" color="white"><Warning /></el-icon>
            </div>
            <div class="stat-text">
              <div class="stat-value">{{ stats.seriousDefects || 0 }}</div>
              <div class="stat-label">严重缺陷</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card shadow="hover">
          <div class="stat-card">
            <div class="stat-icon warning">
              <el-icon :size="28" color="white"><InfoFilled /></el-icon>
            </div>
            <div class="stat-text">
              <div class="stat-value">{{ stats.generalDefects || 0 }}</div>
              <div class="stat-label">一般缺陷</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card style="margin-top: 20px">
      <el-tabs v-model="activeTab">
        <el-tab-pane label="概览" name="overview">
          <el-row :gutter="20">
            <el-col :span="12">
              <div id="defectChart" style="height: 350px"></div>
            </el-col>
            <el-col :span="12">
              <div id="typeChart" style="height: 350px"></div>
            </el-col>
          </el-row>
          <el-row :gutter="20" style="margin-top: 20px">
            <!-- 4.11 整改：需求覆盖率环形图 -->
            <el-col :span="8">
              <div id="coverageChart" style="height: 320px"></div>
            </el-col>
            <!-- 2.7 整改：项目质量趋势折线图 -->
            <el-col :span="16">
              <div id="trendChart" style="height: 320px"></div>
            </el-col>
          </el-row>
        </el-tab-pane>

        <el-tab-pane label="需求列表" name="requirements">
          <el-button type="primary" @click="$router.push(`/requirements/${projectId}`)" style="margin-bottom: 15px">
            查看详细需求分析
          </el-button>
          <el-table :data="requirements" stripe>
            <el-table-column prop="requirementId" label="需求ID" width="100" />
            <el-table-column prop="originalText" label="需求原文" show-overflow-tooltip />
            <el-table-column prop="requirementType" label="类型" width="100">
              <template #default="{ row }">
                <el-tag size="small" :type="row.requirementType === 'functional' ? 'primary' : 'warning'">
                  {{ row.requirementType === 'functional' ? '功能需求' : '非功能需求' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="80">
              <template #default="{ row }">
                <el-tag size="small" type="success">已分析</el-tag>
              </template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-model:current-page="reqPage"
            :page-size="10"
            :total="reqTotal"
            layout="total, prev, pager, next"
            small
            style="margin-top: 12px; justify-content: flex-end"
            @current-change="loadRequirements"
          />
        </el-tab-pane>

        <el-tab-pane label="代码单元" name="code">
          <div style="margin-bottom: 15px; display: flex; gap: 8px; align-items: center">
            <el-button type="primary" @click="$router.push(`/code/${projectId}`)">
              查看详细代码分析
            </el-button>
            <!-- FR-CODE-003 规则3（2.5 整改项）：导出语义向量（需求+代码单元，按项目） -->
            <el-button type="success" plain :loading="exportingVectors" @click="handleExportVectors">
              <el-icon><Download /></el-icon> 导出语义向量
            </el-button>
          </div>
          <el-table :data="codeUnits" stripe>
            <el-table-column prop="className" label="类名" width="180" />
            <el-table-column prop="methodName" label="方法名" width="180" />
            <el-table-column prop="filePath" label="文件路径" show-overflow-tooltip />
            <el-table-column label="行号" width="100">
              <template #default="{ row }">{{ row.startLine }} - {{ row.endLine }}</template>
            </el-table-column>
          </el-table>
          <el-pagination
            v-model:current-page="codePage"
            :page-size="10"
            :total="codeTotal"
            layout="total, prev, pager, next"
            small
            style="margin-top: 12px; justify-content: flex-end"
            @current-change="loadCodeUnits"
          />
        </el-tab-pane>

        <el-tab-pane label="缺陷列表" name="defects">
          <el-button type="primary" @click="$router.push(`/defects/${projectId}`)" style="margin-bottom: 15px">
            查看详细缺陷报告
          </el-button>
          <el-table :data="defects" stripe>
            <el-table-column prop="defectId" label="缺陷ID" width="120" />
            <el-table-column prop="defectType" label="缺陷类型" width="130" />
            <el-table-column prop="defectLevel" label="等级" width="90">
              <template #default="{ row }">
                <el-tag :type="row.defectLevel === 'serious' ? 'danger' : 'warning'" size="small">
                  {{ row.defectLevel === 'serious' ? '严重' : '一般' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="defectReason" label="缺陷原因" show-overflow-tooltip />
          </el-table>
          <el-pagination
            v-model:current-page="defectPage"
            :page-size="10"
            :total="defectTotal"
            layout="total, prev, pager, next"
            small
            style="margin-top: 12px; justify-content: flex-end"
            @current-change="loadDefects"
          />
        </el-tab-pane>

        <el-tab-pane label="追溯矩阵" name="traceability">
          <el-button type="primary" @click="$router.push(`/traceability/${projectId}`)" style="margin-bottom: 15px">
            查看完整追溯矩阵
          </el-button>
          <el-alert type="info" :closable="false">
            追溯矩阵展示需求与代码的双向关联关系，可点击上方按钮查看完整矩阵。
          </el-alert>
        </el-tab-pane>
      </el-tabs>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { resultApi, exportApi, templateApi } from '@/api'
import { printReportPdf } from '@/utils/print'

const route = useRoute()
const router = useRouter()
const projectId = route.params.id
const activeTab = ref('overview')
const stats = ref(null)
const qualityTrend = ref([])
const requirements = ref([])
const codeUnits = ref([])
const defects = ref([])
// 服务端分页状态（万行级项目避免全量拉取）
const reqPage = ref(1)
const codePage = ref(1)
const defectPage = ref(1)
const reqTotal = ref(0)
const codeTotal = ref(0)
const defectTotal = ref(0)
const exportingWord = ref(false)
const exportingPdf = ref(false)
const exportingStats = ref(false)
const printingReport = ref(false)

// GAP-010：模板管理
const templateList = ref([])
const selectedTemplateId = ref(null)
const showTemplateDialog = ref(false)
const showTemplateForm = ref(false)
const editingTemplate = ref(null)
const savingTemplate = ref(false)
const templateForm = ref({ templateName: '', title: '', subtitle: '', headerText: '', sectionItems: [] })

/** 全部可用章节 key（与后端渲染器注册表对齐） */
const allSectionKeys = [
  { key: 'project-overview', label: '项目概况' },
  { key: 'stats-summary', label: '需求覆盖率与缺陷统计' },
  { key: 'defect-type-distribution', label: '缺陷类型分布' },
  { key: 'defect-detail', label: '需求-代码不一致缺陷明细' },
  { key: 'code-quality', label: '代码质量分析' },
  { key: 'traceability-matrix', label: '追溯矩阵' }
]

const sectionKeyLabel = (key) => {
  const found = allSectionKeys.find(s => s.key === key)
  return found ? found.label : key
}

const parseSections = (sectionsJson) => {
  if (!sectionsJson) return []
  try { return JSON.parse(sectionsJson) } catch { return [] }
}

const buildSectionsJson = (items) => {
  return JSON.stringify(items.filter(s => s.key).map(s => ({ key: s.key, title: s.title || '' })))
}

const loadTemplates = async () => {
  try {
    templateList.value = await templateApi.list()
    // 自动选中默认模板
    const def = templateList.value.find(t => t.isDefault)
    if (def) {
      selectedTemplateId.value = def.id
    } else if (templateList.value.length) {
      selectedTemplateId.value = templateList.value[0].id
    }
  } catch (e) {
    console.warn('加载模板列表失败', e)
  }
}

const onTemplateChange = () => {
  // 模板切换已自动绑定 selectedTemplateId
}

const openTemplateForm = (row) => {
  editingTemplate.value = row
  if (row) {
    templateForm.value = {
      templateName: row.templateName || '',
      title: row.title || '',
      subtitle: row.subtitle || '',
      headerText: row.headerText || '',
      sectionItems: parseSections(row.sections).map(s => ({ key: s.key, title: s.title || '' }))
    }
    if (templateForm.value.sectionItems.length === 0) {
      templateForm.value.sectionItems = [{ key: 'project-overview', title: '' }]
    }
  } else {
    templateForm.value = {
      templateName: '',
      title: '',
      subtitle: '',
      headerText: '',
      sectionItems: [{ key: 'project-overview', title: '' }, { key: 'stats-summary', title: '' }]
    }
  }
  showTemplateForm.value = true
}

const handleSaveTemplate = async () => {
  const form = templateForm.value
  if (!form.templateName.trim()) {
    ElMessage.warning('模板名称不能为空')
    return
  }
  const validItems = form.sectionItems.filter(s => s.key)
  if (validItems.length === 0) {
    ElMessage.warning('请至少选择一个章节')
    return
  }
  savingTemplate.value = true
  try {
    const payload = {
      templateName: form.templateName.trim(),
      title: form.title || '',
      subtitle: form.subtitle || '',
      headerText: form.headerText || '',
      sections: buildSectionsJson(validItems)
    }
    if (editingTemplate.value) {
      await templateApi.update(editingTemplate.value.id, payload)
      ElMessage.success('模板已更新')
    } else {
      await templateApi.create(payload)
      ElMessage.success('模板已创建')
    }
    showTemplateForm.value = false
    await loadTemplates()
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    savingTemplate.value = false
  }
}

const handleSetDefault = async (row) => {
  try {
    await templateApi.setDefault(row.id)
    ElMessage.success('已设为默认模板')
    await loadTemplates()
  } catch (e) {
    ElMessage.error(e.message || '操作失败')
  }
}

const handleDeleteTemplate = async (row) => {
  try {
    await ElMessageBox.confirm(`确定删除模板"${row.templateName}"？`, '删除确认', { type: 'warning' })
    await templateApi.delete(row.id)
    ElMessage.success('模板已删除')
    await loadTemplates()
  } catch (e) {
    if (e !== 'cancel') ElMessage.error(e.message || '删除失败')
  }
}

/** GAP-014：需求文档解析失败清单 */
const parseFailures = ref([])

/** 4.12 整改：跳转项目详情页重新上传需求文档 */
const goToUpload = () => {
  const pid = route.params.id || projectId
  router.push(`/project/${pid}`)
}

/** FR-CODE-003 规则3（2.5 整改项）：导出语义向量（按项目） */
const exportingVectors = ref(false)
const handleExportVectors = async () => {
  exportingVectors.value = true
  try {
    await exportApi.semanticVectors(projectId, 'json')
    ElMessage.success('语义向量导出成功')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exportingVectors.value = false
  }
}

const handleExportStats = async () => {
  exportingStats.value = true
  try {
    await exportApi.statisticsExcel(projectId)
    ElMessage.success('统计Excel导出成功')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exportingStats.value = false
  }
}

const handleExportWord = async () => {
  exportingWord.value = true
  try {
    await exportApi.reportWord(projectId, selectedTemplateId.value)
    ElMessage.success('Word报告导出成功')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exportingWord.value = false
  }
}

const handleExportPdf = async () => {
  exportingPdf.value = true
  try {
    await exportApi.reportPdf(projectId, selectedTemplateId.value)
    ElMessage.success('PDF报告导出成功')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exportingPdf.value = false
  }
}

/** GAP-038：直接打印报告（复用 PDF 预览端点，触发系统打印对话框） */
const handlePrintReport = async () => {
  printingReport.value = true
  try {
    await printReportPdf(projectId, selectedTemplateId.value)
    ElMessage.success('已唤起打印对话框')
  } catch (e) {
    ElMessage.error(e.message || '打印失败')
  } finally {
    printingReport.value = false
  }
}

// AUD-06：报告在线预览
const previewingPdf = ref(false)
const previewingWord = ref(false)
const showPreview = ref(false)
const previewType = ref('pdf')
const previewUrl = ref('')
const previewTitle = ref('')

/** 拉取报告 Blob 并生成可嵌入预览的 object URL */
const fetchPreviewUrl = async (type) => {
  const blob = await exportApi.previewBlob(projectId, type, selectedTemplateId.value)
  return URL.createObjectURL(blob)
}

const handlePreviewPdf = async () => {
  previewingPdf.value = true
  previewType.value = 'pdf'
  previewTitle.value = 'PDF 报告预览'
  try {
    previewUrl.value = await fetchPreviewUrl('pdf')
    showPreview.value = true
  } catch (e) {
    ElMessage.error(e.message || '预览失败')
  } finally {
    previewingPdf.value = false
  }
}

const handlePreviewWord = async () => {
  previewingWord.value = true
  previewType.value = 'word'
  previewTitle.value = 'Word 报告预览'
  try {
    previewUrl.value = await fetchPreviewUrl('word')
    showPreview.value = true
  } catch (e) {
    ElMessage.error(e.message || '预览失败')
  } finally {
    previewingWord.value = false
  }
}

const openPreviewInNewTab = () => {
  if (previewUrl.value) window.open(previewUrl.value, '_blank')
}

const onPreviewClosed = () => {
  if (previewUrl.value) {
    URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = ''
  }
}

const loadRequirements = async (page = 1) => {
  const data = await resultApi.getRequirementsPage(projectId, page, 10)
  requirements.value = data.records || []
  reqTotal.value = data.total || 0
  reqPage.value = page
}

const loadCodeUnits = async (page = 1) => {
  const data = await resultApi.getCodeUnitsPage(projectId, page, 10)
  codeUnits.value = data.records || []
  codeTotal.value = data.total || 0
  codePage.value = page
}

const loadDefects = async (page = 1) => {
  const data = await resultApi.getDefectsPage(projectId, page, 10)
  defects.value = data.records || []
  defectTotal.value = data.total || 0
  defectPage.value = page
}

const loadData = async () => {
  try {
    stats.value = await resultApi.getStatistics(projectId)
    // GAP-014：解析 parseFailures JSON 字符串
    if (stats.value.parseFailures) {
      try { parseFailures.value = JSON.parse(stats.value.parseFailures) } catch { parseFailures.value = [] }
    }
    await Promise.all([loadRequirements(1), loadCodeUnits(1), loadDefects(1)])
    try {
      qualityTrend.value = await resultApi.getQualityTrend(projectId)
    } catch (e) {
      console.warn('加载质量趋势失败', e)
      qualityTrend.value = []
    }
    await nextTick()
    initCharts()
  } catch (e) {
    console.error(e)
  }
}

/** 统计图表工具栏：支持导出为图片（FR-PLAT-003） */
const chartToolbox = {
  show: true,
  right: 20,
  feature: {
    saveAsImage: { title: '导出图片', name: '统计图表' }
  }
}

/** CQ-08：图表实例模块级持有——init 前 dispose 旧实例、组件卸载统一销毁（防长会话内存泄漏） */
let defectChart = null, typeChart = null, coverageChart = null, trendChart = null

const initCharts = () => {
  defectChart?.dispose()
  defectChart = echarts.init(document.getElementById('defectChart'))
  defectChart.setOption({
    title: { text: '缺陷等级分布', left: 'center' },
    tooltip: { trigger: 'item' },
    toolbox: chartToolbox,
    legend: { bottom: 10 },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      data: [
        { value: stats.value.seriousDefects || 0, name: '严重缺陷', itemStyle: { color: '#F56C6C' } },
        { value: stats.value.generalDefects || 0, name: '一般缺陷', itemStyle: { color: '#E6A23C' } },
        { value: (stats.value.totalRequirements || 0) - (stats.value.totalDefects || 0), name: '无缺陷需求', itemStyle: { color: '#67C23A' } }
      ]
    }]
  })

  typeChart?.dispose()
  typeChart = echarts.init(document.getElementById('typeChart'))
  const typeDist = stats.value.defectTypeDistribution || {}
  const types = Object.keys(typeDist)
  const values = Object.values(typeDist)
  // GAP-020：4类主类型配色
  const typeColors = { '需求缺失': '#F56C6C', '代码超范围实现': '#E6A23C', '业务逻辑不一致': '#409EFF', '约束条件不满足': '#909399' }
  typeChart.setOption({
    title: { text: '缺陷类型分布（4类口径）', left: 'center' },
    tooltip: {},
    toolbox: chartToolbox,
    xAxis: { type: 'category', data: types.length ? types : ['暂无数据'], axisLabel: { rotate: 30 } },
    yAxis: { type: 'value' },
    series: [{
      type: 'bar',
      data: values.length ? values.map((v, i) => ({ value: v, itemStyle: { color: typeColors[types[i]] || '#409EFF' } })) : [0],
    }]
  })

  // 4.11 整改：需求覆盖率环形图
  coverageChart?.dispose()
  coverageChart = echarts.init(document.getElementById('coverageChart'))
  const covRate = stats.value.coverageRate || 0
  coverageChart.setOption({
    title: { text: '需求覆盖率', left: 'center' },
    tooltip: { trigger: 'item', formatter: '{b}: {c}%' },
    toolbox: chartToolbox,
    legend: { bottom: 5 },
    series: [{
      type: 'pie',
      radius: ['45%', '70%'],
      center: ['50%', '48%'],
      label: { formatter: '{b}\n{c}%' },
      data: [
        { value: covRate, name: '已覆盖', itemStyle: { color: '#67C23A' } },
        { value: Math.max(0, 100 - covRate), name: '未覆盖', itemStyle: { color: '#F56C6C' } }
      ]
    }]
  })

  // 2.7 整改：项目质量趋势折线图（多指标时间序列）
  trendChart?.dispose()
  trendChart = echarts.init(document.getElementById('trendChart'))
  const trend = qualityTrend.value || []
  if (trend.length === 0) {
    trendChart.setOption({
      title: { text: '项目质量趋势（按分析任务时间序列）', left: 'center' },
      graphic: { type: 'text', left: 'center', top: 'middle', style: { text: '暂无已完成的分析任务数据', fill: '#909399', fontSize: 14 } }
    })
  } else {
    const times = trend.map(p => p.time)
    trendChart.setOption({
      title: { text: '项目质量趋势（按分析任务时间序列）', left: 'center' },
      tooltip: { trigger: 'axis' },
      legend: { bottom: 0, data: ['覆盖率(%)', '平均相似度(%)', '严重缺陷', '一般缺陷', '代码质量分'] },
      toolbox: chartToolbox,
      xAxis: { type: 'category', data: times, boundaryGap: false, axisLabel: { rotate: 20 } },
      yAxis: [
        { type: 'value', name: '比率/分数', min: 0, max: 100 },
        { type: 'value', name: '缺陷数', min: 0 }
      ],
      series: [
        { name: '覆盖率(%)', type: 'line', smooth: true, data: trend.map(p => p.coverageRate), itemStyle: { color: '#67C23A' } },
        { name: '平均相似度(%)', type: 'line', smooth: true, data: trend.map(p => p.avgSimilarity), itemStyle: { color: '#409EFF' } },
        { name: '代码质量分', type: 'line', smooth: true, data: trend.map(p => p.codeQualityScore), itemStyle: { color: '#909399' } },
        { name: '严重缺陷', type: 'line', smooth: true, yAxisIndex: 1, data: trend.map(p => p.seriousDefects), itemStyle: { color: '#F56C6C' } },
        { name: '一般缺陷', type: 'line', smooth: true, yAxisIndex: 1, data: trend.map(p => p.generalDefects), itemStyle: { color: '#E6A23C' } }
      ]
    })
  }
}

onMounted(() => {
  loadData()
  loadTemplates()
})

onUnmounted(() => {
  // CQ-08：组件卸载统一销毁图表实例，避免长会话多页面切换内存泄漏
  ;[defectChart, typeChart, coverageChart, trendChart].forEach(c => c && c.dispose())
})
</script>

<style scoped>
.stat-card {
  display: flex;
  align-items: center;
  gap: 15px;
}

.stat-icon {
  width: 55px;
  height: 55px;
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.stat-icon.success { background: #67C23A; }
.stat-icon.primary { background: #409EFF; }
.stat-icon.danger { background: #F56C6C; }
.stat-icon.warning { background: #E6A23C; }

/* 4.12 整改：解析异常结构化修正建议卡片 */
.parse-failure-header {
  display: flex;
  align-items: center;
  gap: 8px;
}
.parse-failure-header .el-button {
  margin-left: auto;
}
.pf-file { font-weight: 600; }
.pf-detail { font-size: 13px; line-height: 1.8; }
.pf-row { display: flex; gap: 6px; }
.pf-label { color: #909399; flex-shrink: 0; }
.pf-suggestion { color: #409EFF; }

.stat-value {
  font-size: 26px;
  font-weight: bold;
}

.stat-label {
  color: #999;
  font-size: 13px;
}
</style>
