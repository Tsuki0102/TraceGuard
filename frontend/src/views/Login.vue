<template>
  <div class="login-container">
    <div class="login-box">
      <div class="login-header">
        <h1>TraceGuard</h1>
        <p>软件需求-代码一致性验证与缺陷自动检测系统</p>
      </div>
      <el-form :model="loginForm" :rules="rules" ref="loginFormRef" class="login-form">
        <el-form-item prop="username">
          <el-input v-model="loginForm.username" placeholder="请输入用户名" prefix-icon="User" size="large" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="loginForm.password" type="password" placeholder="请输入密码" prefix-icon="Lock" size="large" show-password @keyup.enter="handleLogin" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" size="large" style="width: 100%" :loading="loading" @click="handleLogin">登 录</el-button>
        </el-form-item>
        <div class="login-register">
          <el-link type="primary" :underline="false" @click="goRegister">没有账号？注册账号</el-link>
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
const loginFormRef = ref(null)
const loading = ref(false)

// SEC-10：不预填默认账号（默认 admin/admin123 仅在首次初始化时存在，须由管理员及时改密）
const loginForm = reactive({
  username: '',
  password: ''
})

const rules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
}

const handleLogin = async () => {
  try {
    await loginFormRef.value.validate()
    loading.value = true
    const res = await authApi.login(loginForm)
    // SEC-10①：JWT 由后端经 HttpOnly Cookie 下发，前端不落 localStorage；仅保存用户信息供守卫角色判断
    localStorage.removeItem('token')
    localStorage.setItem('userInfo', JSON.stringify(res.user))
    // 4.6 整改（GAP-027 闭环）：强制改密账号登录后必须先修改密码，禁止进入业务页面
    if (res.user && res.user.mustChangePassword) {
      ElMessage.warning('您使用的是初始口令，请先修改密码')
      router.push('/change-password?forced=true')
      return
    }
    // AUD-03 密码定期提醒：登录后若密码临近过期，提示用户及时修改密码
    if (res.passwordExpiringSoon) {
      ElMessage.warning('登录成功，但您的密码即将过期，请尽快修改密码')
    } else {
      ElMessage.success('登录成功')
    }
    router.push('/dashboard')
  } catch (e) {
    console.error(e)
  } finally {
    loading.value = false
  }
}

const goRegister = () => router.push('/register')
</script>

<style scoped>
.login-container {
  width: 100%;
  height: 100vh;
  background: linear-gradient(135deg, #667eea 0%, #764ba2 100%);
  display: flex;
  align-items: center;
  justify-content: center;
}

.login-box {
  width: 420px;
  background: white;
  border-radius: 12px;
  padding: 40px;
  box-shadow: 0 20px 60px rgba(0,0,0,0.3);
}

.login-header {
  text-align: center;
  margin-bottom: 30px;
}

.login-header h1 {
  font-size: 32px;
  color: #667eea;
  margin-bottom: 10px;
}

.login-header p {
  color: #666;
  font-size: 14px;
}

.login-form {
  margin-top: 20px;
}

.login-tip {
  text-align: center;
  margin-top: 10px;
}
.login-register {
  text-align: center;
  margin-top: 8px;
}
</style>
