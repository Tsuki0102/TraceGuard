<template>
  <div class="dcl-page">
    <el-page-header @back="$router.back()" content="数据变更日志" style="margin-bottom: 20px" />

    <el-card>
      <div class="filter-bar">
        <el-radio-group v-model="queryMode" @change="onModeChange">
          <el-radio-button label="project">按项目</el-radio-button>
          <el-radio-button label="entity">按实体</el-radio-button>
        </el-radio-group>
        <template v-if="queryMode === 'project'">
          <el-select v-model="projectId" placeholder="选择项目" style="width: 220px" filterable @change="loadData">
            <el-option v-for="p in projects" :key="p.id" :label="p.projectName" :value="p.id" />
          </el-select>
        </template>
        <template v-else>
          <el-select v-model="entityType" placeholder="实体类型" style="width: 130px" @change="loadData">
            <el-option label="用户(user)" value="user" />
            <el-option label="项目(project)" value="project" />
          </el-select>
          <el-input v-model="entityId" placeholder="实体ID" style="width: 160px" @keyup.enter="loadData" />
          <el-button type="primary" @click="loadData">查询</el-button>
        </template>
      </div>

      <el-table :data="records" stripe border style="margin-top: 15px" v-loading="loading">
        <el-table-column prop="changeTime" label="变更时间" width="170" />
        <el-table-column prop="entityType" label="实体类型" width="110" />
        <el-table-column prop="entityId" label="实体ID" width="110" />
        <el-table-column prop="operation" label="动作" width="90">
          <template #default="{ row }">
            <el-tag :type="opTagType[row.operation] || 'info'" size="small">{{ opLabel[row.operation] || row.operation }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="fieldName" label="字段" width="130" />
        <el-table-column label="变更前" min-width="180" show-overflow-tooltip>
          <template #default="{ row }"><span class="old-val">{{ row.oldValue || '-' }}</span></template>
        </el-table-column>
        <el-table-column label="变更后" min-width="180" show-overflow-tooltip>
          <template #default="{ row }"><span class="new-val">{{ row.newValue || '-' }}</span></template>
        </el-table-column>
        <el-table-column prop="operatorName" label="操作人" width="120" />
      </el-table>

      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50]"
        layout="total, sizes, prev, pager, next, jumper"
        style="margin-top: 16px; justify-content: flex-end"
        @size-change="loadData"
        @current-change="loadData"
      />
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { dataChangeLogApi, projectApi } from '@/api'

const queryMode = ref('project')
const projectId = ref(null)
const entityType = ref('user')
const entityId = ref('')
const projects = ref([])
const records = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const loading = ref(false)

const opLabel = { create: '新增', update: '修改', delete: '删除' }
const opTagType = { create: 'success', update: 'warning', delete: 'danger' }

const loadProjects = async () => {
  try {
    projects.value = await projectApi.list()
    if (projects.value.length && !projectId.value) {
      projectId.value = projects.value[0].id
    }
  } catch (e) {
    console.warn('加载项目列表失败', e)
  }
}

const onModeChange = () => {
  pageNum.value = 1
  loadData()
}

const loadData = async () => {
  loading.value = true
  try {
    let res
    if (queryMode.value === 'project') {
      if (!projectId.value) {
        records.value = []
        total.value = 0
        return
      }
      res = await dataChangeLogApi.pageByProject(projectId.value, { pageNum: pageNum.value, pageSize: pageSize.value })
    } else {
      if (!entityId.value) {
        ElMessage.warning('请输入实体ID')
        return
      }
      res = await dataChangeLogApi.pageByEntity({ entityType: entityType.value, entityId: entityId.value, pageNum: pageNum.value, pageSize: pageSize.value })
    }
    records.value = res.records || []
    total.value = Number(res.total || 0)
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  loadProjects().then(loadData)
})
</script>

<style scoped>
.filter-bar {
  display: flex;
  align-items: center;
  gap: 16px;
}
.old-val {
  color: #f56c6c;
}
.new-val {
  color: #67c23a;
}
</style>
