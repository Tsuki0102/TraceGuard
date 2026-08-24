<template>
  <div class="register-container">
    <div class="register-box">
      <div class="register-header">
        <h1>TraceGuard</h1>
        <p>注册新账号</p>
      </div>
      <el-form :model="form" :rules="rules" ref="formRef" class="register-form">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" prefix-icon="User" size="large" />
        </el-form-item>
        <el-form-item prop="realName">
          <el-input v-model="form.realName" placeholder="请输入姓名" prefix-icon="UserFilled" size="large" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" prefix-icon="Lock" size="large" show-password />
        </el-form-item>
        <el-form-item prop="confirmPassword">
          <el-input v-model="form.confirmPassword" type="password" placeholder="请再次输入密码" prefix-icon="Lock" size="large" show-password @keyup.enter="handleRegister" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="handleRegister">注 册</el-button>
        </el-form-item>
        <div class="register-tip">
          <el-link type="primary" :underline="false" @click="goLogin">已有账号？返回登录</el-link>
        </div>
      </el-form>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '@/api'

const router = useRouter()
const formRef = ref(null)
const loading = ref(false)

const form = reactive({
  username: '',
  realName: '',
  password: '',
  confirmPassword: ''
})

// 与后端 PasswordValidator 一致：至少 8 位，含大写、小写、数字
const passwordValidator = (rule, value, callback) => {
  if (!value) return callback(new Error('请输入密码'))
  if (value.length < 8) return callback(new Error('密码至少 8 位'))
  if (!/[A-Z]/.test(value)) return callback(new Error('需包含大写字母'))
  if (!/[a-z]/.test(value)) return callback(new Error('需包含小写字母'))
  if (!/[0-9]/.test(value)) return callback(new Error('需包含数字'))
  callback()
}

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  realName: [{ required: true, message: '请输入姓名', trigger: 'blur' }],
  password: [{ validator: passwordValidator, trigger: 'blur' }],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      validator: (rule, value, callback) => {
        if (value !== form.password) callback(new Error('两次密码输入不一致'))
        else callback()
      },
      trigger: 'blur'
    }
  ]
}

const handleRegister = async () => {
  try {
    await formRef.value.validate()
    loading.value = true
    // 注册后强制首次改密（GAP-027）：后端 register 置 mustChangePassword=true
    await authApi.register({
      username: form.username,
      realName: form.realName,
      password: form.password
    })
    ElMessage.success('注册成功，请使用初始口令登录并修改密码')
    router.push('/login')
  } catch (e) {
    if (e && e.message) ElMessage.error(e.message)
    console.error(e)
  } finally {
    loading.value = false
  }
}

const goLogin = () => router.push('/login')
</script>

<style scoped>
.register-container {
  width: 100%;
  height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  display: flex;
  align-items: center;
  justify-content: center;
}
.register-box {
  width: 420px;
  background: white;
  border-radius: 12px;
  padding: 40px;
  box-shadow: 0 20px 60px rgba(0, 0, 0, 0.3);
}
.register-header {
  text-align: center;
  margin-bottom: 30px;
}
.register-header h1 {
  font-size: 32px;
  color: #667eea;
  margin-bottom: 10px;
}
.register-header p {
  color: #666;
  font-size: 14px;
}
.register-form {
  margin-top: 20px;
}
.register-tip {
  text-align: center;
  margin-top: 10px;
}
</style>
