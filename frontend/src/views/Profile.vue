<template>
  <div class="profile-page">
    <el-alert
      v-if="passwordExpiringSoon"
      title="您的密码即将过期"
      :description="passwordExpiryText"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    />
    <el-row :gutter="20">
      <el-col :span="8">
        <el-card>
          <template #header><span>个人信息</span></template>
          <div class="avatar-area">
            <el-avatar :size="72" icon="UserFilled" />
          </div>
          <el-descriptions :column="1" border style="margin-top: 16px">
            <el-descriptions-item label="用户名">{{ user.username }}</el-descriptions-item>
            <el-descriptions-item label="姓名">{{ user.realName || '-' }}</el-descriptions-item>
            <el-descriptions-item label="角色">
              <el-tag :type="user.role === 'admin' ? 'danger' : 'info'" size="small">
                {{ user.role === 'admin' ? '管理员' : '普通用户' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="邮箱">{{ user.email || '-' }}</el-descriptions-item>
            <el-descriptions-item label="最近登录">{{ user.lastLoginTime || '-' }}</el-descriptions-item>
            <el-descriptions-item label="密码状态">
              <el-tag v-if="passwordExpiringSoon" type="warning" size="small">即将过期</el-tag>
              <el-tag v-else type="success" size="small">正常</el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :span="16">
        <el-card>
          <template #header><span>修改密码</span></template>
          <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="100px" style="max-width: 480px">
            <el-form-item label="原密码" prop="oldPassword">
              <el-input v-model="pwdForm.oldPassword" type="password" show-password autocomplete="off" />
            </el-form-item>
            <el-form-item label="新密码" prop="newPassword">
              <el-input v-model="pwdForm.newPassword" type="password" show-password autocomplete="off" placeholder="至少8位，含大小写字母和数字" />
            </el-form-item>
            <el-form-item label="确认新密码" prop="confirmPassword">
              <el-input v-model="pwdForm.confirmPassword" type="password" show-password autocomplete="off" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="submitting" @click="handleChangePassword">确认修改</el-button>
              <el-button @click="resetForm">重置</el-button>
            </el-form-item>
          </el-form>
          <el-alert
            title="修改成功后需使用新密码重新登录"
            type="warning"
            :closable="false"
            show-icon
            style="max-width: 480px"
          />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api'

const router = useRouter()
const user = ref({})
const pwdFormRef = ref(null)
const submitting = ref(false)

// AUD-03 密码定期提醒：基于后端返回的密码修改时间计算剩余有效期与是否即将过期
const PASSWORD_EXPIRE_DAYS = 90
const PASSWORD_EXPIRE_WARN_DAYS = 15

const passwordExpiringSoon = computed(() => {
  const updated = user.value && user.value.passwordUpdatedAt
  if (!updated) return false
  const updatedMs = new Date(updated.replace(/-/g, '/')).getTime()
  const daysSince = Math.floor((Date.now() - updatedMs) / (1000 * 60 * 60 * 24))
  const daysLeft = PASSWORD_EXPIRE_DAYS - daysSince
  return daysLeft <= PASSWORD_EXPIRE_WARN_DAYS
})

const passwordExpiryText = computed(() => {
  const updated = user.value && user.value.passwordUpdatedAt
  if (!updated) return '建议定期修改密码以保障账号安全。'
  const updatedMs = new Date(updated.replace(/-/g, '/')).getTime()
  const daysSince = Math.floor((Date.now() - updatedMs) / (1000 * 60 * 60 * 24))
  const daysLeft = PASSWORD_EXPIRE_DAYS - daysSince
  if (daysLeft <= 0) {
    return `密码已过期（超过 ${PASSWORD_EXPIRE_DAYS} 天未修改），请尽快修改密码。`
  }
  return `密码将于 ${daysLeft} 天后过期（建议每 ${PASSWORD_EXPIRE_DAYS} 天修改一次），请尽快修改密码。`
})

const pwdForm = reactive({
  oldPassword: '',
  newPassword: '',
  confirmPassword: ''
})

const validateConfirm = (rule, value, callback) => {
  if (value !== pwdForm.newPassword) {
    callback(new Error('两次输入的新密码不一致'))
  } else {
    callback()
  }
}

/** 密码复杂度校验（5.2.2 访问安全）：至少8位，且包含大写字母、小写字母和数字 */
const validatePasswordComplexity = (rule, value, callback) => {
  if (!value) {
    callback(new Error('请输入新密码'))
  } else if (value.length < 8) {
    callback(new Error('密码长度不能少于8位'))
  } else if (!/[a-z]/.test(value) || !/[A-Z]/.test(value) || !/[0-9]/.test(value)) {
    callback(new Error('密码需同时包含大写字母、小写字母和数字'))
  } else {
    callback()
  }
}

const pwdRules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { validator: validatePasswordComplexity, trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' }
  ]
}

onMounted(async () => {
  const res = await authApi.getCurrentUserInfo()
  user.value = res || {}
})

const handleChangePassword = () => {
  pwdFormRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      await authApi.changePassword({
        oldPassword: pwdForm.oldPassword,
        newPassword: pwdForm.newPassword
      })
      ElMessage.success('密码修改成功，请重新登录')
      setTimeout(() => {
        localStorage.removeItem('token')
        localStorage.removeItem('userInfo')
        router.push('/login')
      }, 1500)
    } catch (e) {
      ElMessage.error(e.message || '修改失败')
    } finally {
      submitting.value = false
    }
  })
}

const resetForm = () => {
  pwdFormRef.value.resetFields()
}
</script>

<style scoped>
.avatar-area {
  display: flex;
  justify-content: center;
}
</style>
