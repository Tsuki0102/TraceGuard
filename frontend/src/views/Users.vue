<template>
  <div class="users-page">
    <!-- ===== 页头：标题 + 搜索 + 新增 ===== -->
    <div class="page-header tg-fade-up">
      <div>
        <div class="page-header__greet">
          <el-icon class="page-header__greet-icon"><User /></el-icon>
          Identity &amp; Access
        </div>
        <h2 class="page-header__title">用户管理</h2>
        <p class="page-header__desc">管理系统用户、角色分配与密码重置</p>
      </div>
      <div class="page-header__actions">
        <el-button type="primary" size="large" round @click="openCreate">
          <el-icon style="margin-right: 6px"><Plus /></el-icon> 新增用户
        </el-button>
      </div>
    </div>

    <!-- ===== KPI 统计行 ===== -->
    <div class="kpi-row">
      <div class="kpi-card tg-fade-up">
        <div class="kpi-card__tile kpi-card__tile--gold"><el-icon :size="22"><User /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num"><span class="tg-count">{{ totalDisp }}</span></div>
          <div class="kpi-card__label">用户总数</div>
          <div class="kpi-card__meta">全部系统账号</div>
        </div>
      </div>
      <div class="kpi-card tg-fade-up" style="animation-delay: 60ms">
        <div class="kpi-card__tile kpi-card__tile--amber"><el-icon :size="22"><UserFilled /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num"><span class="tg-count">{{ adminDisp }}</span></div>
          <div class="kpi-card__label">管理员</div>
          <div class="kpi-card__meta">当前页 · 管理权限</div>
        </div>
      </div>
      <div class="kpi-card tg-fade-up" style="animation-delay: 120ms">
        <div class="kpi-card__tile kpi-card__tile--coral"><el-icon :size="22"><Lock /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num"><span class="tg-count">{{ pendingDisp }}</span></div>
          <div class="kpi-card__label">待改密</div>
          <div class="kpi-card__meta">强制改密标记</div>
        </div>
      </div>
      <div class="kpi-card tg-fade-up" style="animation-delay: 180ms">
        <div class="kpi-card__tile kpi-card__tile--green"><el-icon :size="22"><Avatar /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num kpi-card__num--sm">
            <span class="tg-count">{{ userDisp }}</span>
            <span class="kpi-card__meta-inline">普通用户</span>
          </div>
          <div class="kpi-card__label">角色分布</div>
          <div class="kpi-card__meta">当前页 · 普通账号</div>
        </div>
      </div>
    </div>

    <!-- ===== 用户列表面板 ===== -->
    <section class="table-panel tg-fade-up">
      <div class="table-panel__head">
        <div class="table-panel__title">
          <h3><el-icon class="section-title__ic" :size="17"><User /></el-icon>系统账号</h3>
          <p>共 {{ total }} 个用户账号</p>
        </div>
      </div>

      <!-- W1-05/R14：统一搜索表单（keyword 走后端查询；角色先按当前页本地过滤） -->
      <TgSearchBar
        :fields="searchFields"
        :collapse-count="1"
        @search="onSearch"
        @reset="onResetSearch"
      />

      <el-table :data="filteredRecords" v-loading="loading" class="manage-table">
        <el-table-column label="用户" min-width="190">
          <template #default="{ row }">
            <div class="tg-user-cell">
              <span class="tg-avatar">{{ avatarText(row.username) }}</span>
              <span class="tg-user-cell__main">
                <span class="tg-user-cell__name">{{ row.username }}</span>
                <span class="tg-user-cell__sub">{{ row.realName || '未填写姓名' }}</span>
              </span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="email" label="邮箱" show-overflow-tooltip>
          <template #default="{ row }">{{ row.email || '-' }}</template>
        </el-table-column>
        <el-table-column label="角色" width="120">
          <template #default="{ row }">
            <span class="mini-pill" :class="row.role === 'admin' ? 'mini-pill--gold' : 'mini-pill--green'">
              {{ row.role === 'admin' ? '管理员' : '普通用户' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column label="强制改密" width="100" align="center">
          <template #default="{ row }">
            <span class="mini-pill" :class="row.mustChangePassword ? 'mini-pill--amber' : 'mini-pill--green'">
              {{ row.mustChangePassword ? '待改密' : '正常' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="lastLoginTime" label="最近登录" width="170">
          <template #default="{ row }">{{ row.lastLoginTime || '从未登录' }}</template>
        </el-table-column>
        <el-table-column prop="createTime" label="创建时间" width="170" />
        <el-table-column label="操作" width="280" fixed="right" align="right">
          <template #default="{ row }">
            <div class="op-actions">
              <el-button class="op-btn op-btn--view" round @click="openEdit(row)">
                <el-icon><EditPen /></el-icon> 编辑
              </el-button>
              <el-button class="op-btn op-btn--warn" round @click="openResetPwd(row)">
                <el-icon><Key /></el-icon> 重置密码
              </el-button>
              <el-button
                class="op-btn op-btn--danger"
                round
                :disabled="row.username === 'admin' || row.id === currentUserId"
                @click="handleDelete(row)"
              >
                <el-icon><Delete /></el-icon> 删除
              </el-button>
            </div>
          </template>
        </el-table-column>
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
    </section>

    <!-- 新增/编辑对话框 -->
    <el-dialog v-model="dialogVisible" :title="isEdit ? '编辑用户' : '新增用户'" width="480px">
      <el-form ref="formRef" :model="form" :rules="rules" label-width="80px">
        <el-form-item label="用户名" prop="username">
          <el-input v-model="form.username" :disabled="isEdit" placeholder="登录账号" />
        </el-form-item>
        <el-form-item v-if="!isEdit" label="密码" prop="password">
          <el-input v-model="form.password" type="password" show-password placeholder="至少8位，含大小写字母和数字" />
        </el-form-item>
        <el-form-item label="姓名" prop="realName">
          <el-input v-model="form.realName" />
        </el-form-item>
        <el-form-item label="邮箱" prop="email">
          <el-input v-model="form.email" />
        </el-form-item>
        <el-form-item label="角色" prop="role">
          <el-select v-model="form.role" style="width: 100%">
            <el-option label="普通用户" value="user" />
            <el-option label="管理员" value="admin" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" round :loading="saving" @click="handleSave">保存</el-button>
      </template>
    </el-dialog>

    <!-- 重置密码对话框 -->
    <el-dialog v-model="resetVisible" title="重置密码" width="420px">
      <el-form ref="resetFormRef" :model="resetForm" :rules="resetRules" label-width="80px">
        <el-form-item label="用户">
          <span>{{ resetTarget?.username }}</span>
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="resetForm.newPassword" type="password" show-password placeholder="至少8位，含大小写字母和数字" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resetVisible = false">取消</el-button>
        <el-button type="primary" round :loading="resetting" @click="handleResetPassword">确认重置</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { userApi } from '@/api'
import { useCountUp } from '@/composables/useCountUp'
import TgSearchBar from '@/components/TgSearchBar.vue'

const records = ref([])
const total = ref(0)
const pageNum = ref(1)
const pageSize = ref(10)
const keyword = ref('')
const loading = ref(false)

// ===== W1-05/R14：统一搜索表单（keyword 走后端查询；角色先按当前页本地过滤） =====
const searchParams = ref({})
const searchFields = [
  { key: 'keyword', label: '用户名/姓名', type: 'input', width: 220 },
  {
    key: 'role',
    label: '角色',
    type: 'select',
    width: 130,
    options: [
      { label: '管理员', value: 'admin' },
      { label: '普通用户', value: 'user' }
    ]
  }
]

const onSearch = (params) => {
  searchParams.value = params
  keyword.value = params.keyword || ''
  pageNum.value = 1
  loadData()
}

const onResetSearch = () => {
  searchParams.value = {}
  keyword.value = ''
  pageNum.value = 1
  loadData()
}

const filteredRecords = computed(() => {
  let list = records.value
  const role = searchParams.value.role
  if (role) list = list.filter((r) => r.role === role)
  return list
})

const dialogVisible = ref(false)
const isEdit = ref(false)
const saving = ref(false)
const formRef = ref(null)
const form = reactive({ id: null, username: '', password: '', realName: '', email: '', role: 'user' })

const resetVisible = ref(false)
const resetting = ref(false)
const resetFormRef = ref(null)
const resetTarget = ref(null)
const resetForm = reactive({ newPassword: '' })

const currentUserId = computed(() => {
  const userStr = localStorage.getItem('userInfo')
  return userStr ? JSON.parse(userStr).id : null
})

const adminCount = computed(() => records.value.filter(r => r.role === 'admin').length)
const pendingPwdCount = computed(() => records.value.filter(r => r.mustChangePassword).length)
const userCount = computed(() => records.value.length - adminCount.value)
const avatarText = (name) => (name || '?').slice(0, 1).toUpperCase()

// ===== KPI 数字滚动 =====
const totalDisp = useCountUp(computed(() => total.value))
const adminDisp = useCountUp(computed(() => adminCount.value))
const pendingDisp = useCountUp(computed(() => pendingPwdCount.value))
const userDisp = useCountUp(computed(() => userCount.value))

/** 密码复杂度校验（与个人中心、后端规则统一：至少8位，含大小写字母和数字） */
const validatePasswordComplexity = (rule, value, callback) => {
  if (!value) {
    callback(new Error('请输入密码'))
  } else if (value.length < 8) {
    callback(new Error('密码长度不能少于8位'))
  } else if (!/[a-z]/.test(value) || !/[A-Z]/.test(value) || !/[0-9]/.test(value)) {
    callback(new Error('密码需同时包含大写字母、小写字母和数字'))
  } else {
    callback()
  }
}

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { validator: validatePasswordComplexity, trigger: 'blur' }
  ],
  role: [{ required: true, message: '请选择角色', trigger: 'change' }]
}

const resetRules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { validator: validatePasswordComplexity, trigger: 'blur' }
  ]
}

const loadData = async () => {
  loading.value = true
  try {
    const res = await userApi.page({
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

const openCreate = () => {
  isEdit.value = false
  Object.assign(form, { id: null, username: '', password: '', realName: '', email: '', role: 'user' })
  dialogVisible.value = true
}

const openEdit = (row) => {
  isEdit.value = true
  Object.assign(form, { id: row.id, username: row.username, password: '', realName: row.realName, email: row.email, role: row.role })
  dialogVisible.value = true
}

const handleSave = () => {
  formRef.value.validate(async (valid) => {
    if (!valid) return
    saving.value = true
    try {
      if (isEdit.value) {
        await userApi.update({ id: form.id, realName: form.realName, email: form.email, role: form.role })
        ElMessage.success('更新成功')
      } else {
        await userApi.create({ ...form })
        ElMessage.success('创建成功')
      }
      dialogVisible.value = false
      loadData()
    } catch (e) {
      ElMessage.error(e.message || '保存失败')
    } finally {
      saving.value = false
    }
  })
}

const openResetPwd = (row) => {
  resetTarget.value = row
  resetForm.newPassword = ''
  resetVisible.value = true
}

const handleResetPassword = () => {
  resetFormRef.value.validate(async (valid) => {
    if (!valid) return
    resetting.value = true
    try {
      await userApi.resetPassword({ userId: resetTarget.value.id, newPassword: resetForm.newPassword })
      ElMessage.success('密码已重置')
      resetVisible.value = false
    } catch (e) {
      ElMessage.error(e.message || '重置失败')
    } finally {
      resetting.value = false
    }
  })
}

const handleDelete = (row) => {
  ElMessageBox.confirm(`确定删除用户 "${row.username}" 吗？`, '警告', { type: 'warning' })
    .then(async () => {
      try {
        await userApi.delete(row.id)
        ElMessage.success('删除成功')
        loadData()
      } catch (e) {
        ElMessage.error(e.message || '删除失败')
      }
    })
    .catch(() => {})
}

onMounted(() => {
  loadData()
})
</script>

<style scoped>
.users-page {
  max-width: 1200px;
  margin: 0 auto;
}

/* ===== 页头（与已优化页面统一） ===== */
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

/* 角色分布卡：数字旁的内联标签 */
.kpi-card__meta-inline {
  font-size: 13px;
  font-weight: 500;
  color: var(--tg-text-secondary);
  white-space: nowrap;
}

/* 窄屏 */
@media (max-width: 720px) {
  .page-header {
    flex-direction: column;
    align-items: flex-start;
  }

  .page-header__actions {
    width: 100%;
    flex-wrap: wrap;
  }

  .search-input {
    flex: 1;
  }
}
</style>
