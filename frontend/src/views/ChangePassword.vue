<template>
  <div class="cp-container">
    <div class="cp-box">
      <div class="cp-header">
        <el-icon :size="48" color="#E6A23C"><Lock /></el-icon>
        <h2>首次登录，请修改密码</h2>
        <p>出于安全考虑，首次登录或管理员重置密码后需设置新密码</p>
      </div>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="form.newPassword" type="password" show-password placeholder="至少8位，含大小写字母和数字" />
        </el-form-item>
        <el-form-item label="确认密码" prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitting" @click="handleSubmit" style="width: 100%">确认修改</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock } from '@element-plus/icons-vue'
import { authApi } from '@/api'

const router = useRouter()
const formRef = ref(null)
const submitting = ref(false)

const form = reactive({
  newPassword: '',
  confirmPassword: ''
})

const validateConfirm = (rule, value, callback) => {
  if (value !== form.newPassword) {
    callback(new Error('两次输入的新密码不一致'))
  } else {
    callback()
  }
}

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

const rules = {
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { validator: validatePasswordComplexity, trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入新密码', trigger: 'blur' },
    { validator: validateConfirm, trigger: 'blur' }
  ]
}

const handleSubmit = () => {
  formRef.value.validate(async (valid) => {
    if (!valid) return
    submitting.value = true
    try {
      const userInfo = JSON.parse(localStorage.getItem('userInfo') || '{}')
      await authApi.changePassword({
        oldPassword: '',  // forced change doesn't require old password
        newPassword: form.newPassword
      })
      // 清除强制改密标志
      userInfo.mustChangePassword = false
      localStorage.setItem('userInfo', JSON.stringify(userInfo))
      ElMessage.success('密码修改成功')
      router.push('/dashboard')
    } catch (e) {
      ElMessage.error(e.message || '修改失败')
    } finally {
      submitting.value = false
    }
  })
}
</script>

<style scoped>
.cp-container {
  width: 100%;
  height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  display: flex;
  align-items: center;
  justify-content: center;
}
.cp-box {
  width: 460px;
  background: white;
  border-radius: 12px;
  padding: 40px;
  box-shadow: 0 20px 60px rgba(0,0,0,0.3);
}
.cp-header {
  text-align: center;
  margin-bottom: 24px;
}
.cp-header h2 {
  margin: 12px 0 8px;
  color: #303133;
}
.cp-header p {
  color: #909399;
  font-size: 13px;
}
</style>
