<template>
  <div class="projects-page">
    <el-card>
      <template #header>
        <div class="page-header">
          <h2>{{ showRecycle ? '回收站' : '项目管理' }}</h2>
          <div>
            <el-button @click="toggleRecycle" style="margin-right: 8px">
              {{ showRecycle ? '返回项目列表' : '回收站' }}
            </el-button>
            <el-button v-if="!showRecycle" type="primary" @click="openCreate">
              <el-icon><Plus /></el-icon> 新建项目
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="projects" v-loading="loading" stripe>
        <el-table-column prop="projectName" label="项目名称" min-width="180" />
        <el-table-column prop="industryType" label="行业类型" width="120" />
        <el-table-column prop="techStack" label="技术栈" width="150" />
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="需求数" width="90" prop="requirementCount" />
        <el-table-column label="缺陷数" width="90" prop="defectCount" />
        <el-table-column label="覆盖率" width="100">
          <template #default="{ row }">
            <span v-if="row.coverageRate != null">{{ (row.coverageRate * 100).toFixed(1) }}%</span>
            <span v-else>-</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="180" />
        <el-table-column label="操作" width="330" fixed="right">
          <template #default="{ row }">
            <template v-if="showRecycle">
              <el-button type="success" link size="small" @click="restoreDeleted(row)">恢复</el-button>
              <el-button type="danger" link size="small" @click="purgeDeleted(row)">彻底删除</el-button>
            </template>
            <template v-else>
              <el-button type="primary" link size="small" @click="goToDetail(row)">详情</el-button>
              <el-button type="primary" link size="small" @click="openEdit(row)">编辑</el-button>
              <el-button v-if="row.status === 'analyzed'" type="success" link size="small" @click="goToResults(row)">查看结果</el-button>
              <el-button v-if="row.status !== 'archived'" type="warning" link size="small" @click="archiveProject(row)">归档</el-button>
              <el-button v-else type="success" link size="small" @click="restoreProject(row)">恢复</el-button>
              <el-button type="danger" link size="small" @click="deleteProject(row)">删除</el-button>
            </template>
          </template>
        </el-table-column>
      </el-table>

      <!-- FUN-15：回收站真分页（>100 条可见） -->
      <div v-if="showRecycle" style="display: flex; justify-content: flex-end; margin-top: 12px">
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
    </el-card>

    <el-dialog v-model="showCreateDialog" :title="editingProject ? '编辑项目' : '创建新项目'" width="550px">
      <el-form :model="newProject" label-width="100px" ref="formRef" :rules="rules">
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
        <el-button type="primary" @click="submitProject">{{ editingProject ? '保存' : '创建' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { projectApi } from '@/api'

const router = useRouter()
const projects = ref([])
const loading = ref(false)
const showCreateDialog = ref(false)
const formRef = ref(null)
/** 当前编辑的项目（null 表示创建模式） */
const editingProject = ref(null)
/** GAP-012：是否展示回收站 */
const showRecycle = ref(false)

const newProject = ref({
  projectName: '',
  industryType: '',
  techStack: 'Java 8 / SpringBoot',
  description: ''
})

const rules = {
  projectName: [{ required: true, message: '请输入项目名称', trigger: 'blur' }]
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
    recycleTotal.value = (res && res.total) != null ? res.total : projects.value.length
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
    await ElMessageBox.confirm(
      `彻底删除项目"${row.projectName}"后，其数据（需求/代码/分析结果）将不可恢复，确定继续吗？`,
      '危险操作',
      { type: 'warning', confirmButtonText: '彻底删除', cancelButtonText: '取消' }
    )
    await projectApi.purgeDeleted(row.id)
    ElMessage.success('已彻底删除')
    loadRecycle()
  } catch (e) {
    if (e !== 'cancel' && e?.message) ElMessage.error(e.message)
  }
}

const statusType = (status) => {
  const map = { created: 'info', running: 'warning', analyzed: 'success', failed: 'danger', archived: 'info' }
  return map[status] || 'info'
}

const statusText = (status) => {
  const map = { created: '已创建', running: '分析中', analyzed: '已完成', failed: '失败', archived: '已归档' }
  return map[status] || status
}

const archiveProject = async (row) => {
  try {
    await ElMessageBox.confirm(`确定要归档项目"${row.projectName}"吗？归档后可随时恢复。`, '提示', { type: 'warning' })
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
    await ElMessageBox.confirm(`确定要删除项目"${row.projectName}"吗？删除后将移入回收站，可在回收站中恢复。`, '提示', { type: 'warning' })
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
})
</script>

<style scoped>
.page-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.page-header h2 {
  margin: 0;
}
</style>
