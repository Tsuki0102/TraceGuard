<template>
  <div class="results-page">
    <!-- ===== 页头：返回 + 标题 + 导出操作组 ===== -->
    <div class="page-header tg-fade-up">
      <div>
        <div class="page-header__back">
          <el-button link class="page-header__back-btn" @click="$router.back()">
            <el-icon><ArrowLeft /></el-icon> 返回
          </el-button>
        </div>
        <div class="page-header__greet">
          <el-icon class="page-header__greet-icon"><DataAnalysis /></el-icon>
          Analysis Report
        </div>
        <h2 class="page-header__title">分析结果</h2>
        <p class="page-header__desc">一致性分析统计图表与多格式报告导出</p>
      </div>
      <div class="page-header__actions">
        <!-- GAP-010：模板选择（从后端动态加载） -->
        <el-select v-model="selectedTemplateId" style="width: 160px" placeholder="报告模板" @change="onTemplateChange">
          <el-option v-for="t in templateList" :key="t.id" :label="t.templateName + (t.isDefault ? '（默认）' : '')" :value="t.id" />
        </el-select>
        <el-button round @click="showTemplateDialog = true">
          <el-icon><Setting /></el-icon> 管理模板
        </el-button>
        <el-button type="success" round :loading="exportingStats" @click="handleExportStats">
          <el-icon><Download /></el-icon> 导出统计Excel
        </el-button>
        <el-button type="primary" round :loading="exportingWord" @click="handleExportWord">
          <el-icon><Download /></el-icon> 导出Word报告
        </el-button>
        <el-button type="danger" round :loading="exportingPdf" @click="handleExportPdf">
          <el-icon><Download /></el-icon> 导出PDF报告
        </el-button>
        <el-button type="info" plain round :loading="printingReport" @click="handlePrintReport">
          <el-icon><Printer /></el-icon> 打印报告
        </el-button>
        <el-button type="primary" plain round :loading="previewingPdf" @click="handlePreviewPdf">
          <el-icon><View /></el-icon> 预览PDF
        </el-button>
        <el-button type="warning" plain round :loading="previewingWord" @click="handlePreviewWord">
          <el-icon><View /></el-icon> 预览Word
        </el-button>
      </div>
    </div>

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
      <el-table :data="templateList" size="small">
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
      shadow="never"
      style="margin-bottom: 16px"
    >
      <template #header>
        <div class="parse-failure-header">
          <el-icon color="var(--tg-warning)"><WarningFilled /></el-icon>
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

    <!-- ===== 统计 KPI 行 ===== -->
    <div v-if="stats" class="kpi-row">
      <div class="kpi-card tg-fade-up">
        <div class="kpi-card__tile kpi-card__tile--green"><el-icon :size="22"><CircleCheck /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num"><span class="tg-count">{{ coverageDisp }}</span><span class="kpi-card__suffix">%</span></div>
          <div class="kpi-card__label">需求覆盖率</div>
          <div class="kpi-card__meta">需求-代码对齐</div>
        </div>
      </div>
      <div class="kpi-card tg-fade-up">
        <div class="kpi-card__tile kpi-card__tile--gold"><el-icon :size="22"><Document /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num"><span class="tg-count">{{ totalReqDisp }}</span></div>
          <div class="kpi-card__label">需求总数</div>
          <div class="kpi-card__meta">已解析需求条目</div>
        </div>
      </div>
      <div class="kpi-card tg-fade-up">
        <div class="kpi-card__tile kpi-card__tile--coral"><el-icon :size="22"><Warning /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num"><span class="tg-count">{{ seriousDisp }}</span></div>
          <div class="kpi-card__label">严重缺陷</div>
          <div class="kpi-card__meta">需优先处理</div>
        </div>
      </div>
      <div class="kpi-card tg-fade-up">
        <div class="kpi-card__tile kpi-card__tile--amber"><el-icon :size="22"><InfoFilled /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num"><span class="tg-count">{{ generalDisp }}</span></div>
          <div class="kpi-card__label">一般缺陷</div>
          <div class="kpi-card__meta">可排期修复</div>
        </div>
      </div>
    </div>

    <!-- W2-10/O3：Quality Gate 质量门槛判定 -->
    <div v-if="gatePass != null" class="gate-banner" :class="gatePass ? 'is-pass' : 'is-fail'">
      <div class="gate-banner__icon">
        <el-icon :size="24"><component :is="gatePass ? 'CircleCheckFilled' : 'WarningFilled'" /></el-icon>
      </div>
      <div class="gate-banner__body">
        <b>{{ gatePass ? '质量门槛已通过' : '质量门槛未通过' }}</b>
        <span>阈值：覆盖率 ≥ {{ gateConfig.coverage ?? 80 }}% · 一致率 ≥ {{ gateConfig.consistency ?? 80 }} · 严重缺陷 ≤ {{ gateConfig.seriousLimit ?? 0 }}</span>
        <span v-if="!gatePass" class="gate-banner__detail">{{ gateFailedDims }}</span>
      </div>
    </div>

    <!-- W2-12/O8：分析批次时间轴（最近任务） -->
    <div v-if="batches.length" class="batches-strip">
      <div class="batches-strip__head">
        <span><el-icon :size="15"><Clock /></el-icon>分析批次</span>
        <small>最近 {{ batches.length }} 次分析任务</small>
      </div>
      <div class="batches-strip__list">
        <div
          v-for="b in batches"
          :key="b.id"
          class="batch-item"
          :class="'is-' + (b.status === 'completed' ? 'ok' : b.status === 'failed' ? 'bad' : 'run')"
          :title="b.taskName + ' · ' + b.statusText"
        >
          <el-icon :size="12"><component :is="b.status === 'completed' ? 'CircleCheck' : b.status === 'failed' ? 'CircleClose' : 'Loading'" /></el-icon>
          <span class="batch-item__text">{{ b.taskName || '任务' }}</span>
          <span class="batch-item__time">{{ b.shortTime }}</span>
        </div>
      </div>
    </div>

    <section ref="reportRef" class="glass-card report-card tg-fade-up">
      <el-tabs v-model="activeTab" class="report-tabs">
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
          <el-row :gutter="20" style="margin-top: 20px">
            <!-- W1-03/R8：质量雷达画像（覆盖率/一致性/代码质量/无缺陷需求/严重缺陷收敛） -->
            <el-col :span="12">
              <div id="qualityRadar" style="height: 340px"></div>
            </el-col>
            <el-col :span="12">
              <div class="radar-note">
                <div class="radar-note__head">
                  <el-icon :size="17" class="radar-note__icon"><Aim /></el-icon>质量画像解读
                </div>
                <div v-for="d in radarDims" :key="d.name" class="radar-note__item">
                  <span class="radar-note__name">{{ d.name }}</span>
                  <div class="radar-note__track">
                    <i :style="{ width: Math.min(d.value, 100) + '%' }"></i>
                  </div>
                  <span class="radar-note__value">{{ Math.round(d.value) }}</span>
                </div>
                <p class="radar-note__tip">雷达面积越大代表项目质量画像越好；维度低于 60 分时建议优先优化对应环节。</p>
              </div>
            </el-col>
          </el-row>
          <!-- W2-11/O5：覆盖"护栏"面板（孤儿需求/孤儿代码） -->
          <el-row :gutter="20" style="margin-top: 20px">
            <el-col :span="12">
              <div class="guard-panel">
                <div class="guard-panel__head">
                  <el-icon :size="17" class="guard-panel__icon"><Umbrella /></el-icon>覆盖护栏
                  <span class="guard-panel__sub">孤儿需求 · 无代码实现对应</span>
                  <span v-if="guardStats.orphanReqCount" class="guard-panel__count">{{ guardStats.orphanReqCount }}</span>
                </div>
                <div v-if="guard.orphanReqs.length" class="guard-list">
                  <div v-for="(r, i) in guard.orphanReqs" :key="i" class="guard-item">
                    <span class="guard-id">{{ r.requirementId }}</span>
                    <span class="guard-text">{{ r.title || r.originalText }}</span>
                  </div>
                  <div v-if="guardStats.orphanReqCount > guard.orphanReqs.length" class="guard-more">
                    等 {{ guardStats.orphanReqCount }} 条需求无实现
                  </div>
                </div>
                <div v-else class="guard-empty">全部需求均有代码实现对应</div>
              </div>
            </el-col>
            <el-col :span="12">
              <div class="guard-panel">
                <div class="guard-panel__head">
                  <el-icon :size="17" class="guard-panel__icon"><Box /></el-icon>覆盖护栏
                  <span class="guard-panel__sub">孤儿代码 · 无需求对应实现</span>
                  <span v-if="guardStats.orphanCodeCount" class="guard-panel__count guard-panel__count--warn">{{ guardStats.orphanCodeCount }}</span>
                </div>
                <div v-if="guard.orphanCodes.length" class="guard-list">
                  <div v-for="(u, i) in guard.orphanCodes" :key="i" class="guard-item">
                    <span class="guard-id">{{ u.className }}.{{ u.methodName }}</span>
                    <span class="guard-text">{{ u.filePath }}</span>
                  </div>
                  <div v-if="guardStats.orphanCodeCount > guard.orphanCodes.length" class="guard-more">
                    等 {{ guardStats.orphanCodeCount }} 个代码单元无需求对应
                  </div>
                </div>
                <div v-else class="guard-empty">全部代码单元均有需求对应</div>
              </div>
            </el-col>
          </el-row>
        </el-tab-pane>

        <el-tab-pane label="需求列表" name="requirements">
          <el-button type="primary" round @click="$router.push(`/requirements/${projectId}`)" style="margin-bottom: 15px">
            <el-icon style="margin-right: 4px"><View /></el-icon>查看详细需求分析
          </el-button>
          <el-table :data="requirements" class="report-table">
            <el-table-column prop="requirementId" label="需求ID" width="100" />
            <el-table-column prop="originalText" label="需求原文" show-overflow-tooltip />
            <el-table-column prop="requirementType" label="类型" width="100">
              <template #default="{ row }">
                <span class="mini-pill" :class="row.requirementType === 'functional' ? 'mini-pill--fun' : 'mini-pill--nonfun'">
                  {{ row.requirementType === 'functional' ? '功能需求' : '非功能需求' }}
                </span>
              </template>
            </el-table-column>
            <el-table-column prop="status" label="状态" width="80">
              <template #default="{ row }">
                <span class="mini-pill mini-pill--ok">已分析</span>
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
          <div class="tab-toolbar">
            <el-button type="primary" round @click="$router.push(`/code/${projectId}`)">
              查看详细代码分析
            </el-button>
            <!-- FR-CODE-003 规则3（2.5 整改项）：导出语义向量（需求+代码单元，按项目） -->
            <el-button type="success" plain round :loading="exportingVectors" @click="handleExportVectors">
              <el-icon><Download /></el-icon> 导出语义向量
            </el-button>
          </div>
          <el-table :data="codeUnits" class="report-table">
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
          <el-button type="primary" round @click="$router.push(`/defects/${projectId}`)" style="margin-bottom: 15px">
            <el-icon style="margin-right: 4px"><View /></el-icon>查看详细缺陷报告
          </el-button>
          <el-table :data="defects" class="report-table">
            <el-table-column prop="defectId" label="缺陷ID" width="120" />
            <el-table-column prop="defectType" label="缺陷类型" width="130" />
            <el-table-column prop="defectLevel" label="等级" width="90">
              <template #default="{ row }">
                <span class="mini-pill" :class="row.defectLevel === 'serious' ? 'mini-pill--danger' : 'mini-pill--warn'">
                  {{ row.defectLevel === 'serious' ? '严重' : '一般' }}
                </span>
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
          <el-button type="primary" round @click="$router.push(`/traceability/${projectId}`)" style="margin-bottom: 15px">
            <el-icon style="margin-right: 4px"><Share /></el-icon>查看完整追溯矩阵
          </el-button>
          <el-alert type="info" :closable="false">
            追溯矩阵展示需求与代码的双向关联关系，可点击上方按钮查看完整矩阵。
          </el-alert>
        </el-tab-pane>
      </el-tabs>
    </section>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, nextTick, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { WarningFilled } from '@element-plus/icons-vue'
import * as echarts from 'echarts'
import { chartColors, chartThemeName, chartText, chartFaint, chartAxisLine, chartSplitLine, chartTooltipBg, chartTitleColor, onChartThemeChange } from '@/utils/echartsTheme'
import { resultApi, exportApi, templateApi, dashboardApi, analysisApi } from '@/api'
import { printReportPdf } from '@/utils/print'
import { useCountUp } from '@/composables/useCountUp'
import { setupWatermark } from '@/composables/useWatermark'

const route = useRoute()
const router = useRouter()
const projectId = route.params.id
const activeTab = ref('overview')
const stats = ref(null)
const qualityTrend = ref([])
const requirements = ref([])
const codeUnits = ref([])
const defects = ref([])

// ===== 统计 KPI 数字滚动 =====
const coverageDisp = useCountUp(computed(() => stats.value?.coverageRate || 0))
const totalReqDisp = useCountUp(computed(() => stats.value?.totalRequirements || 0))
const seriousDisp = useCountUp(computed(() => stats.value?.seriousDefects || 0))
const generalDisp = useCountUp(computed(() => stats.value?.generalDefects || 0))
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
  reqTotal.value = Number(data.total || 0)
  reqPage.value = page
}

const loadCodeUnits = async (page = 1) => {
  const data = await resultApi.getCodeUnitsPage(projectId, page, 10)
  codeUnits.value = data.records || []
  codeTotal.value = Number(data.total || 0)
  codePage.value = page
}

const loadDefects = async (page = 1) => {
  const data = await resultApi.getDefectsPage(projectId, page, 10)
  defects.value = data.records || []
  defectTotal.value = Number(data.total || 0)
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
    loadGate()
  } catch (e) {
    console.error(e)
  }
}

// ===== W2-10/O3：Quality Gate 质量门槛判定 =====
const gateConfig = ref({})
const gatePass = ref(null)
const gateFailedDims = ref('')

const loadGate = async () => {
  try {
    gateConfig.value = (await dashboardApi.gate()) || {}
  } catch (e) {
    gateConfig.value = {}
  }
  const s = stats.value
  if (!s) return
  const last = qualityTrend.value.length ? qualityTrend.value[qualityTrend.value.length - 1] : null
  const cov = (s.coverageRate || 0) * 100
  const cons = last?.avgSimilarity ?? (s.coverageRate || 0) * 100
  const serious = s.seriousDefects || 0
  const cfg = gateConfig.value
  const fails = []
  if (cov < (cfg.coverage ?? 80)) fails.push(`覆盖率 ${Math.round(cov)}% < 阈值 ${cfg.coverage ?? 80}%`)
  if (cons < (cfg.consistency ?? 80)) fails.push(`一致率 ${Math.round(cons)} < 阈值 ${cfg.consistency ?? 80}`)
  if (serious > (cfg.seriousLimit ?? 0)) fails.push(`严重缺陷 ${serious} > 上限 ${cfg.seriousLimit ?? 0}`)
  gateFailedDims.value = fails.join('；')
  gatePass.value = fails.length === 0
}

// ===== W2-12/O8：分析批次时间轴 =====
const TASK_STATUS_TEXT = { pending: '待启动', running: '分析中', completed: '已完成', failed: '失败', paused: '已暂停', terminated: '已终止', interrupted: '已中断' }
const batches = ref([])

const loadBatches = async () => {
  try {
    // 兼容后端返回分页对象（records）与普通数组两种形态
    const res = await analysisApi.listTasks(projectId)
    const arr = Array.isArray(res) ? res : (((res && res.records) || []))
    batches.value = arr.slice(-8).reverse().map((t) => ({
      id: t.id,
      taskName: t.taskName,
      status: t.status,
      statusText: TASK_STATUS_TEXT[t.status] || t.status,
      shortTime: (t.createTime || '').slice(5, 16)
    }))
  } catch (e) {
    console.warn('加载分析批次失败', e)
  }
}

// ===== W2-11/O5：覆盖护栏（孤儿需求 / 孤儿代码） =====
const guard = ref({ orphanReqs: [], orphanCodes: [], reqTotal: 0, codeTotal: 0 })
const guardStats = computed(() => ({
  orphanReqCount: guard.value.reqTotal,
  orphanCodeCount: guard.value.codeTotal
}))

const loadGuard = async () => {
  try {
    const [reqList, codeList, cons] = await Promise.all([
      resultApi.getRequirements(projectId),
      resultApi.getCodeUnits(projectId),
      resultApi.getConsistency(projectId)
    ])
    const linkedReq = new Set((cons || []).map((c) => c.requirementId).filter(Boolean))
    const linkedCode = new Set((cons || []).map((c) => c.codeUnitId).filter(Boolean))
    const orphanReqs = (reqList || []).filter((r) => !linkedReq.has(r.requirementId))
    const orphanCodes = (codeList || []).filter((u) => !linkedCode.has(u.id))
    guard.value = {
      reqTotal: orphanReqs.length,
      codeTotal: orphanCodes.length,
      orphanReqs: orphanReqs.slice(0, 5),
      orphanCodes: orphanCodes.slice(0, 5)
    }
  } catch (e) {
    console.warn('加载覆盖护栏失败', e)
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
let defectChart = null, typeChart = null, coverageChart = null, trendChart = null, radarChart = null

// ===== W1-03/R8：质量雷达五维画像（0-100，值越高越好） =====
const radarDims = computed(() => {
  const s = stats.value
  const last = qualityTrend.value.length ? qualityTrend.value[qualityTrend.value.length - 1] : null
  const total = s?.totalRequirements || 0
  const defectTotal = s?.totalDefects || 0
  const noDefectRate = total ? Math.round(((total - defectTotal) / total) * 100) : 0
  const serious = s?.seriousDefects || 0
  const seriousConverge = serious === 0 ? 100 : Math.max(0, 100 - serious * 15)
  return [
    { name: '需求覆盖率', value: s?.coverageRate || 0 },
    { name: '语义一致率', value: last?.avgSimilarity ?? s?.coverageRate ?? 0 },
    { name: '代码质量', value: last?.codeQualityScore ?? 0 },
    { name: '无缺陷需求', value: noDefectRate },
    { name: '严重缺陷收敛', value: seriousConverge }
  ]
})

const initCharts = () => {
  defectChart?.dispose()
  defectChart = echarts.init(document.getElementById('defectChart'), chartThemeName())
  defectChart.setOption({
    title: { text: '缺陷等级分布', left: 'center' },
    tooltip: { trigger: 'item' },
    toolbox: chartToolbox,
    legend: { bottom: 10 },
    series: [{
      type: 'pie',
      radius: ['40%', '70%'],
      data: [
        { value: stats.value.seriousDefects || 0, name: '严重缺陷', itemStyle: { color: chartColors.danger } },
        { value: stats.value.generalDefects || 0, name: '一般缺陷', itemStyle: { color: chartColors.warning } },
        { value: (stats.value.totalRequirements || 0) - (stats.value.totalDefects || 0), name: '无缺陷需求', itemStyle: { color: chartColors.success } }
      ]
    }]
  })

  typeChart?.dispose()
  typeChart = echarts.init(document.getElementById('typeChart'), chartThemeName())
  const typeDist = stats.value.defectTypeDistribution || {}
  const types = Object.keys(typeDist)
  const values = Object.values(typeDist)
  // GAP-020：4类主类型配色
  const typeColors = { '需求缺失': chartColors.danger, '代码超范围实现': chartColors.warning, '业务逻辑不一致': chartColors.primary, '约束条件不满足': chartColors.faint }
  typeChart.setOption({
    title: { text: '缺陷类型分布（4类口径）', left: 'center' },
    tooltip: {},
    toolbox: chartToolbox,
    xAxis: { type: 'category', data: types.length ? types : ['暂无数据'], axisLabel: { rotate: 30 } },
    yAxis: { type: 'value' },
    series: [{
      type: 'bar',
      data: values.length ? values.map((v, i) => ({ value: v, itemStyle: { color: typeColors[types[i]] || chartColors.primary } })) : [0],
    }]
  })

  // 4.11 整改：需求覆盖率环形图
  coverageChart?.dispose()
  coverageChart = echarts.init(document.getElementById('coverageChart'), chartThemeName())
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
        { value: covRate, name: '已覆盖', itemStyle: { color: chartColors.success } },
        { value: Math.max(0, 100 - covRate), name: '未覆盖', itemStyle: { color: chartColors.danger } }
      ]
    }]
  })

  // 2.7 整改：项目质量趋势折线图（多指标时间序列）
  trendChart?.dispose()
  trendChart = echarts.init(document.getElementById('trendChart'), chartThemeName())
  const trend = qualityTrend.value || []
  if (trend.length === 0) {
    trendChart.setOption({
      title: { text: '项目质量趋势（按分析任务时间序列）', left: 'center' },
      graphic: { type: 'text', left: 'center', top: 'middle', style: { text: '暂无已完成的分析任务数据', fill: chartColors.faint, fontSize: 14 } }
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
        { name: '覆盖率(%)', type: 'line', smooth: true, data: trend.map(p => p.coverageRate), itemStyle: { color: chartColors.success } },
        { name: '平均相似度(%)', type: 'line', smooth: true, data: trend.map(p => p.avgSimilarity), itemStyle: { color: chartColors.primary } },
        { name: '代码质量分', type: 'line', smooth: true, data: trend.map(p => p.codeQualityScore), itemStyle: { color: chartColors.faint } },
        { name: '严重缺陷', type: 'line', smooth: true, yAxisIndex: 1, data: trend.map(p => p.seriousDefects), itemStyle: { color: chartColors.danger } },
        { name: '一般缺陷', type: 'line', smooth: true, yAxisIndex: 1, data: trend.map(p => p.generalDefects), itemStyle: { color: chartColors.warning } }
      ]
    })
  }

  // W1-03/R8：质量雷达画像（五维 0-100）
  radarChart?.dispose()
  radarChart = echarts.init(document.getElementById('qualityRadar'), chartThemeName())
  const dims = radarDims.value
  radarChart.setOption({
    title: { text: '质量雷达画像（五维）', left: 'center' },
    tooltip: { trigger: 'item' },
    toolbox: chartToolbox,
    radar: {
      indicator: dims.map((d) => ({ name: d.name, max: 100 })),
      radius: '62%',
      center: ['50%', '52%'],
      splitNumber: 4,
      axisName: { color: chartColors.neutral, fontSize: 12 },
      axisLine: { lineStyle: { color: 'rgba(0,0,0,0.08)' } },
      splitLine: { lineStyle: { color: 'rgba(0,0,0,0.08)' } },
      splitArea: { areaStyle: { color: ['rgba(0,0,0,0.02)', 'rgba(0,0,0,0.045)'] } }
    },
    series: [
      {
        type: 'radar',
        symbol: 'circle',
        symbolSize: 5,
        lineStyle: { width: 2, color: chartColors.primary },
        itemStyle: { color: chartColors.primary },
        areaStyle: { color: 'rgba(143,107,34,0.16)' },
        data: [{ value: dims.map((d) => Math.round(d.value)), name: '质量画像' }]
      }
    ]
  })
}

const reportRef = ref(null)
// W1-04/R9：分析报告面板水印（登录名 · 时间）
let removeWm = null

onChartThemeChange(() => initCharts())

onMounted(() => {
  loadData()
  loadTemplates()
  loadBatches()
  loadGuard()
  removeWm = setupWatermark(reportRef.value, 'TraceGuard 分析报告')
})

onUnmounted(() => {
  // CQ-08：组件卸载统一销毁图表实例，避免长会话多页面切换内存泄漏
  ;[defectChart, typeChart, coverageChart, trendChart, radarChart].forEach(c => c && c.dispose())
  if (removeWm) removeWm()
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

.page-header__actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  flex-shrink: 0;
  max-width: 100%;
}

.page-header__actions .el-select {
  height: 32px;
}

.page-header__actions .el-button {
  padding: 6px 12px;
  font-size: 13px;
}

/* ===== 统计 KPI 行 ===== */
.kpi-row {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 18px;
  margin-bottom: 24px;
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

/* ===== 报告卡片 + Tabs ===== */
.report-card {
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(var(--tg-blur)) saturate(1.4);
  -webkit-backdrop-filter: blur(var(--tg-blur)) saturate(1.4);
  border: 1px solid var(--tg-border);
  border-radius: 20px;
  padding: 24px 26px;
  box-shadow: var(--tg-shadow-card);
}

.report-tabs :deep(.el-tabs__header) {
  margin-bottom: 20px;
}

.report-tabs :deep(.el-tabs__item) {
  font-size: 14px;
  font-weight: 500;
  color: var(--tg-text-secondary);
  transition: color 0.25s ease;
}

.report-tabs :deep(.el-tabs__item:hover) {
  color: var(--tg-accent);
}

.report-tabs :deep(.el-tabs__item.is-active) {
  color: var(--tg-accent);
  font-weight: 600;
}

.report-tabs :deep(.el-tabs__active-bar) {
  background: var(--tg-accent-gradient);
  height: 3px;
  border-radius: 3px;
}

.report-tabs :deep(.el-tabs__nav-wrap::after) {
  background-color: var(--tg-border);
}

/* Tab 工具栏 */
.tab-toolbar {
  margin-bottom: 15px;
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

/* ===== 报告内表格 ===== */
.report-table :deep(.el-table) {
  --el-table-border-color: rgba(0, 0, 0, 0.05);
  --el-table-header-bg-color: transparent;
  --el-table-row-hover-bg-color: rgba(201, 155, 63, 0.05);
  background: transparent;
}

.report-table :deep(.el-table th.el-table__cell) {
  font-size: 12px;
  font-weight: 600;
  color: var(--tg-text-secondary);
  background: rgba(0, 0, 0, 0.02);
}

/* 迷你胶囊标签 */
.mini-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 500;
  white-space: nowrap;
}
.mini-pill::before {
  content: '';
  width: 6px;
  height: 6px;
  border-radius: 50%;
  flex-shrink: 0;
}

.mini-pill--fun {
  background: var(--el-color-primary-light-9);
  color: var(--tg-accent);
}
.mini-pill--fun::before {
  background: var(--tg-accent);
}

.mini-pill--nonfun {
  background: rgba(232, 155, 60, 0.14);
  color: #9a5d12;
}
.mini-pill--nonfun::before {
  background: var(--tg-amber);
}

.mini-pill--ok {
  background: rgba(154, 156, 107, 0.14);
  color: #55682e;
}
.mini-pill--ok::before {
  background: var(--tg-success);
}

.mini-pill--danger {
  background: rgba(194, 94, 76, 0.12);
  color: #9a3f30;
}
.mini-pill--danger::before {
  background: var(--tg-danger);
}

.mini-pill--warn {
  background: rgba(232, 155, 60, 0.14);
  color: #9a5d12;
}
.mini-pill--warn::before {
  background: var(--tg-amber);
}

/* 4.12 整改：解析异常结构化修正建议卡片 */
.parse-failure-card {
  border-radius: 20px;
  border-color: rgba(232, 155, 60, 0.25);
  margin-bottom: 16px;
}

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
.pf-label { color: var(--tg-text-secondary); flex-shrink: 0; }
.pf-suggestion { color: var(--tg-accent); }

/* ===== 质量雷达画像解读面板（W1-03/R8） ===== */
.radar-note {
  height: 340px;
  padding: 18px 22px;
  border-radius: 20px;
  background: var(--tg-gradient-soft);
  border: 1px solid var(--tg-border);
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.radar-note__head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--tg-text-primary);
  margin-bottom: 4px;
}

.radar-note__icon {
  color: var(--tg-accent);
  background: var(--el-color-primary-light-9);
  border-radius: 8px;
  padding: 4px;
  box-sizing: content-box;
}

.radar-note__item {
  display: flex;
  align-items: center;
  gap: 12px;
}

.radar-note__name {
  width: 92px;
  flex-shrink: 0;
  font-size: 13px;
  color: var(--tg-text-secondary);
}

.radar-note__track {
  flex: 1;
  height: 7px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.06);
  overflow: hidden;
}

.radar-note__track i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--tg-accent-gradient);
  transform-origin: left;
  animation: radar-grow 0.9s var(--tg-ease) both;
}

.radar-note__value {
  width: 34px;
  flex-shrink: 0;
  text-align: right;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--tg-text-primary);
  font-variant-numeric: tabular-nums;
}

.radar-note__tip {
  margin: auto 0 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--tg-slate);
  border-top: 1px dashed rgba(60, 45, 25, 0.12);
  padding-top: 12px;
}

@keyframes radar-grow {
  from { transform: scaleX(0); }
  to { transform: scaleX(1); }
}

/* ===== W2-10/O3：质量门槛横幅 ===== */
.gate-banner {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 18px;
  border-radius: 16px;
  border: 1px solid;
  margin-bottom: 16px;
  font-size: 13.5px;
}

.gate-banner.is-pass {
  background: rgba(154, 156, 107, 0.12);
  border-color: rgba(154, 156, 107, 0.35);
}

.gate-banner.is-fail {
  background: rgba(194, 94, 76, 0.08);
  border-color: rgba(194, 94, 76, 0.32);
}

.gate-banner__icon {
  width: 44px;
  height: 44px;
  border-radius: 14px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.6);
}

.gate-banner.is-pass .gate-banner__icon { color: var(--tg-success); }
.gate-banner.is-fail .gate-banner__icon { color: var(--tg-danger); }

.gate-banner__body {
  display: flex;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.gate-banner__body b {
  font-size: 14.5px;
  color: var(--tg-text-primary);
}

.gate-banner__body > span {
  font-size: 12.5px;
  color: var(--tg-text-secondary);
}

.gate-banner__detail {
  color: var(--tg-danger) !important;
}

/* ===== W2-12/O8：分析批次时间轴 ===== */
.batches-strip {
  display: flex;
  align-items: center;
  gap: 18px;
  margin-bottom: 16px;
  padding: 12px 16px;
  border-radius: 16px;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.85), rgba(255, 255, 255, 0.6));
  border: 1px solid var(--tg-border);
  overflow-x: auto;
  scrollbar-width: none;
}

.batches-strip::-webkit-scrollbar { display: none; }

.batches-strip__head {
  display: inline-flex;
  flex-direction: column;
  gap: 2px;
  flex-shrink: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--tg-text-primary);
}

.batches-strip__head span {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.batches-strip__head .el-icon { color: var(--tg-accent); }

.batches-strip__head small {
  font-size: 11.5px;
  font-weight: 400;
  color: var(--tg-slate);
}

.batches-strip__list {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
}

.batch-item {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 5px 12px;
  border-radius: 999px;
  border: 1px solid var(--tg-border);
  background: rgba(255, 255, 255, 0.7);
  font-size: 12px;
  color: var(--tg-text-primary);
  white-space: nowrap;
  transition: transform 0.25s var(--tg-ease), box-shadow 0.25s ease;
}

.batch-item:hover {
  transform: translateY(-1px);
  box-shadow: var(--tg-shadow-card);
}

.batch-item.is-ok { color: #55682e; }
.batch-item.is-ok .el-icon { color: var(--tg-success); }
.batch-item.is-bad { color: #9a3f30; }
.batch-item.is-bad .el-icon { color: var(--tg-danger); }
.batch-item.is-run .el-icon { color: var(--tg-warning); }

.batch-item__time {
  font-size: 11px;
  color: var(--tg-slate);
  font-variant-numeric: tabular-nums;
}

/* ===== W2-11/O5：覆盖护栏面板 ===== */
.guard-panel {
  height: 100%;
  min-height: 220px;
  padding: 18px 20px;
  border-radius: 20px;
  background: var(--tg-gradient-soft);
  border: 1px solid var(--tg-border);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.guard-panel__head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
  font-weight: 600;
  color: var(--tg-text-primary);
}

.guard-panel__icon {
  color: var(--tg-accent);
  background: var(--el-color-primary-light-9);
  border-radius: 8px;
  padding: 4px;
  box-sizing: content-box;
}

.guard-panel__sub {
  font-size: 12px;
  font-weight: 400;
  color: var(--tg-text-secondary);
  margin-left: 2px;
}

.guard-panel__count {
  margin-left: auto;
  padding: 1px 8px;
  border-radius: 999px;
  background: rgba(194, 94, 76, 0.12);
  color: var(--tg-danger);
  font-size: 12.5px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
}

.guard-panel__count--warn {
  background: rgba(232, 155, 60, 0.14);
  color: #9a5d12;
}

.guard-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  overflow-y: auto;
  max-height: 200px;
}

.guard-item {
  display: flex;
  align-items: baseline;
  gap: 8px;
  padding: 6px 10px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.7);
  border: 1px solid var(--tg-border);
}

.guard-id {
  font-size: 12px;
  font-weight: 600;
  color: var(--tg-accent);
  flex-shrink: 0;
  font-family: var(--tg-font-mono);
}

.guard-text {
  font-size: 12.5px;
  color: var(--tg-text-primary);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.guard-more {
  padding: 4px 10px;
  font-size: 12px;
  color: var(--tg-slate);
}

.guard-empty {
  padding: 24px 10px;
  text-align: center;
  font-size: 12.5px;
  color: var(--tg-success);
}

/* 窄屏 */
@media (max-width: 1280px) {
  .kpi-row {
    grid-template-columns: repeat(2, 1fr);
  }

  .page-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .page-header__actions {
    justify-content: flex-start;
  }
}

@media (max-width: 720px) {
  .kpi-row {
    grid-template-columns: 1fr;
  }
}
</style>
