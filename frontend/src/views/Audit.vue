<template>
  <div class="audit-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>操作审计日志</span>
          <div class="header-actions">
            <el-input
              v-model="keyword"
              placeholder="搜索用户/操作/路径"
              clearable
              style="width: 220px; margin-right: 10px"
              @keyup.enter="loadData"
              @clear="loadData"
            >
              <template #prefix><el-icon><Search /></el-icon></template>
            </el-input>
            <el-button type="primary" @click="loadData">
              <el-icon><Refresh /></el-icon> 刷新
            </el-button>
            <el-button type="warning" :loading="verifying" @click="verifyChain">
              <el-icon><Lock /></el-icon> 校验哈希链
            </el-button>
            <el-button type="success" :loading="exporting" @click="exportExcel">
              <el-icon><Download /></el-icon> 导出Excel
            </el-button>
          </div>
        </div>
      </template>

      <el-alert
        v-if="verifyResult"
        :title="verifyResult.valid ? '哈希链校验通过：未发现篡改' : '哈希链校验异常：检测到记录被篡改'"
        :description="verifyDescription"
        :type="verifyResult.valid ? 'success' : 'error'"
        :closable="true"
        show-icon
        style="margin-bottom: 16px"
      />

      <el-table :data="records" stripe v-loading="loading">
        <el-table-column prop="createTime" label="时间" width="170" />
        <el-table-column prop="username" label="用户" width="110">
          <template #default="{ row }">{{ row.username || '-' }}</template>
        </el-table-column>
        <el-table-column prop="operation" label="操作" width="110">
          <template #default="{ row }">
            <el-tag size="small" :type="opTagType(row.operation)">{{ row.operation }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="请求" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <el-tag size="small" :type="methodTagType(row.method)" style="margin-right: 6px">{{ row.method }}</el-tag>
            <span>{{ row.path }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="statusCode" label="状态码" width="90">
          <template #default="{ row }">
            <span :style="{ color: row.statusCode < 400 ? '#67C23A' : '#F56C6C' }">{{ row.statusCode }}</span>
          </template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="row.success ? 'success' : 'danger'" size="small">
              {{ row.success ? '成功' : '失败' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="costMs" label="耗时" width="90">
          <template #default="{ row }">{{ row.costMs }}ms</template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.ip || '-' }}</template>
        </el-table-column>
        <el-table-column prop="errorMsg" label="错误信息" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.errorMsg || '-' }}</template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="pageNum"
        v-model:page-size="pageSize"
        :total="total"
        :page-sizes="[10, 20, 50, 100]"
        layout="total, sizes, prev, pager, next, jumper"
        style="margin-top: 16px; justify-content: flex-end"
        @size-change="loadData"
        @current-change="loadData"
      />
    </el-card>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Download, Lock } from '@element-plus/icons-vue'
import { auditApi } from '@/api'
import { UserContext } from '@/store/user'

const records = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(20)
const keyword = ref('')
const loading = ref(false)
const exporting = ref(false)
const verifying = ref(false)
const verifyResult = ref(null)
const isAdmin = computed(() => UserContext.isAdmin())
const verifyDescription = computed(() => {
  if (!verifyResult.value) return ''
  const r = verifyResult.value
  if (r.valid) return `共校验 ${r.total} 条审计记录，哈希链完整无篡改。`
  return `共校验 ${r.total} 条记录，首条异常位于第 ${r.firstBrokenIndex} 条（ID=${r.firstBrokenId}，时间=${r.firstBrokenTime}）。`
})

const exportExcel = async () => {
  exporting.value = true
  try {
    await auditApi.exportExcel(keyword.value || undefined)
    ElMessage.success('导出成功')
  } catch (e) {
    ElMessage.error(e.message || '导出失败')
  } finally {
    exporting.value = false
  }
}

/** AUD-08：校验审计日志哈希链完整性 */
const verifyChain = async () => {
  if (!isAdmin.value) {
    ElMessage.warning('仅管理员可校验审计日志')
    return
  }
  verifying.value = true
  try {
    const res = await auditApi.verify()
    verifyResult.value = res
    if (res.valid) {
      ElMessage.success('哈希链校验通过')
    } else {
      ElMessage.error('检测到审计记录被篡改，请查看详情')
    }
  } catch (e) {
    ElMessage.error(e.message || '校验失败')
  } finally {
    verifying.value = false
  }
}

const loadData = async () => {
  loading.value = true
  try {
    const res = await auditApi.page({
      pageNum: pageNum.value,
      pageSize: pageSize.value,
      keyword: keyword.value || undefined
    })
    records.value = res.records || []
    total.value = Number(res.total || 0)
  } catch (e) {
    ElMessage.error(e.message || '加载失败')
  } finally {
    loading.value = false
  }
}

const opTagType = (op) => {
  if (!op) return 'info'
  if (op.includes('删除') || op.includes('终止')) return 'danger'
  if (op.includes('创建') || op.includes('上传') || op.includes('启动')) return 'success'
  if (op.includes('登录') || op.includes('注册')) return 'primary'
  return 'warning'
}

const methodTagType = (method) => {
  if (method === 'POST') return 'success'
  if (method === 'PUT') return 'warning'
  if (method === 'DELETE') return 'danger'
  return 'info'
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.header-actions {
  display: flex;
  align-items: center;
}
</style>
