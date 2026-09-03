<template>
  <div class="login-shell">
    <div class="tg-aurora"></div>

    <!-- ===== 左侧品牌叙事面板（与登录/注册页同语言：深色暖金） ===== -->
    <aside class="login-hero">
      <i class="login-hero__ring login-hero__ring--a" aria-hidden="true"></i>
      <i class="login-hero__ring login-hero__ring--b" aria-hidden="true"></i>
      <i class="login-hero__glow" aria-hidden="true"></i>
      <div class="login-hero__inner">
        <div class="login-hero__brand">
          <img class="login-hero__logo" src="@/assets/logo/logo-gold.png" alt="TraceGuard 标志" />
          <div class="login-hero__wordmark"><span>Trace</span>Guard</div>
        </div>
        <h1 class="login-hero__title">安全，<br />从这里开始。</h1>
        <p class="login-hero__desc">
          设置新密码后即可进入工作台。强密码是保护项目资产与验证结果的第一道防线。
        </p>
        <ul class="login-hero__feats">
          <li>
            <span class="login-hero__feat-ic" aria-hidden="true">
              <svg viewBox="0 0 16 16" width="15" height="15" fill="none"><rect x="3" y="7" width="10" height="7" rx="2" stroke="currentColor" stroke-width="1.4"/><path d="M5.5 7V5a2.5 2.5 0 0 1 5 0v2" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>
            </span>
            <div><b>密码要求</b><small>至少 8 位，同时包含大写字母、小写字母与数字</small></div>
          </li>
          <li>
            <span class="login-hero__feat-ic" aria-hidden="true">
              <svg viewBox="0 0 16 16" width="15" height="15" fill="none"><path d="M3 8.5 6.5 12 13 4.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </span>
            <div><b>定期更换</b><small>系统会定期提醒更新密码，连续失败将临时锁定</small></div>
          </li>
        </ul>
      </div>
      <p class="login-hero__foot">TraceGuard · 需求-代码追溯平台</p>
    </aside>

    <!-- ===== 右侧改密表单 ===== -->
    <main class="login-pane">
      <section class="login-card tg-fade-up">
        <div class="login-card__lock" aria-hidden="true">
          <el-icon :size="26"><Lock /></el-icon>
        </div>
        <h2 class="login-card__title">{{ forced ? '首次登录，请修改密码' : '修改密码' }}</h2>
        <p class="login-card__sub">出于安全考虑，首次登录或管理员重置密码后需设置新密码</p>

        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="login-form auth-form--dense">
          <el-form-item label="新密码" prop="newPassword">
            <el-input v-model="form.newPassword" type="password" show-password placeholder="至少8位，含大小写字母和数字" prefix-icon="Lock" size="large" autocomplete="new-password" />
          </el-form-item>
          <el-form-item label="确认密码" prop="confirmPassword">
            <el-input v-model="form.confirmPassword" type="password" show-password placeholder="再次输入新密码" prefix-icon="Lock" size="large" autocomplete="new-password" @keyup.enter="handleSubmit" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" size="large" class="login-card__btn" :loading="submitting" @click="handleSubmit">确认修改</el-button>
          </el-form-item>
        </el-form>
      </section>
      <p class="login-pane__copyright">© 2026 TraceGuard</p>
    </main>
  </div>
</template>

<script setup>
import { ref, reactive, computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock } from '@element-plus/icons-vue'
import { authApi } from '@/api'

const route = useRoute()
const router = useRouter()
const formRef = ref(null)
const submitting = ref(false)
const forced = computed(() => route.query.forced === 'true')

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
/* =====================================================================
 * 改密页：与登录/注册页同构的分屏叙事布局
 * ===================================================================== */
.login-shell {
  position: relative;
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(440px, 47%) 1fr;
  overflow: hidden;
}
.login-shell > .tg-aurora { z-index: 0; }

.login-hero {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  padding: 52px 56px 30px;
  color: #EDE3D2;
  background:
    radial-gradient(120% 90% at 100% 0%, rgba(201, 155, 63, 0.22), transparent 55%),
    radial-gradient(90% 70% at 0% 100%, rgba(143, 107, 34, 0.28), transparent 60%),
    linear-gradient(160deg, #2E2416 0%, #1E1810 68%);
  overflow: hidden;
}
.login-hero::before {
  content: '';
  position: absolute;
  inset: 0;
  pointer-events: none;
  background-image:
    radial-gradient(rgba(232, 200, 119, 0.14) 1px, transparent 1.2px),
    radial-gradient(rgba(232, 200, 119, 0.08) 1px, transparent 1.3px);
  background-size: 34px 34px, 68px 68px;
  background-position: 0 0, 17px 17px;
  mask-image: radial-gradient(ellipse at 30% 20%, rgba(0, 0, 0, 0.7), transparent 75%);
  -webkit-mask-image: radial-gradient(ellipse at 30% 20%, rgba(0, 0, 0, 0.7), transparent 75%);
}
.login-hero__ring {
  position: absolute;
  border-radius: 50%;
  border: 1px solid rgba(232, 200, 119, 0.14);
  pointer-events: none;
}
.login-hero__ring--a {
  width: 560px;
  height: 560px;
  right: -180px;
  top: -160px;
  animation: login-ring-spin 70s linear infinite;
}
.login-hero__ring--b {
  width: 380px;
  height: 380px;
  right: -80px;
  top: -60px;
  border-style: dashed;
  border-color: rgba(232, 200, 119, 0.1);
  animation: login-ring-spin 50s linear infinite reverse;
}
@keyframes login-ring-spin {
  to { transform: rotate(360deg); }
}
.login-hero__glow {
  position: absolute;
  width: 300px;
  height: 300px;
  left: -90px;
  bottom: -110px;
  border-radius: 50%;
  background: radial-gradient(circle, rgba(201, 155, 63, 0.2), transparent 68%);
  filter: blur(8px);
  pointer-events: none;
}
.login-hero__inner {
  position: relative;
  z-index: 1;
  flex: 1;
  display: flex;
  flex-direction: column;
  justify-content: center;
  max-width: 470px;
}
.login-hero__brand { display: flex; align-items: center; gap: 13px; margin-bottom: 34px; }
.login-hero__logo { width: 44px; height: 44px; filter: drop-shadow(0 4px 14px rgba(232, 200, 119, 0.35)); }
.login-hero__wordmark { font-size: 21px; font-weight: 700; letter-spacing: 0.02em; color: #F5EDDD; }
.login-hero__wordmark span {
  background: linear-gradient(115deg, #E8C877, #C99B3F);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}
.login-hero__title {
  margin: 0;
  font-size: clamp(30px, 2.6vw, 38px);
  font-weight: 700;
  line-height: 1.32;
  letter-spacing: 0.01em;
  color: #F5EDDD;
}
.login-hero__desc {
  margin: 16px 0 0;
  font-size: 13.5px;
  line-height: 1.8;
  color: rgba(237, 227, 210, 0.55);
}
.login-hero__feats {
  list-style: none;
  margin: 30px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.login-hero__feats li { display: flex; align-items: flex-start; gap: 13px; }
.login-hero__feat-ic {
  width: 36px;
  height: 36px;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  border-radius: 11px;
  color: #E8C877;
  background: rgba(232, 200, 119, 0.1);
  border: 1px solid rgba(232, 200, 119, 0.16);
}
.login-hero__feats b { display: block; font-size: 13.5px; font-weight: 600; color: rgba(237, 227, 210, 0.92); }
.login-hero__feats small { display: block; margin-top: 2px; font-size: 11.5px; line-height: 1.6; color: rgba(237, 227, 210, 0.48); }
.login-hero__foot {
  position: relative;
  z-index: 1;
  margin: 26px 0 0;
  font-size: 10.5px;
  letter-spacing: 0.06em;
  color: rgba(237, 227, 210, 0.32);
}

.login-pane {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 40px 40px 24px;
  background:
    radial-gradient(900px 480px at 78% -8%, rgba(232, 200, 119, 0.16), transparent 66%),
    radial-gradient(700px 420px at 12% 108%, rgba(217, 169, 102, 0.1), transparent 64%),
    var(--tg-bg-page);
}
.login-card {
  width: 100%;
  max-width: 400px;
  text-align: center;
}
.login-card__lock {
  width: 56px;
  height: 56px;
  margin: 0 auto;
  border-radius: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--tg-accent);
  background: var(--el-color-primary-light-9);
  border: 1px solid rgba(143, 107, 34, 0.18);
  animation: tg-float 5s ease-in-out infinite;
}
.login-card__title { margin: 16px 0 0; font-size: 24px; font-weight: 700; letter-spacing: -0.01em; color: var(--tg-text-primary); }
.login-card__sub { margin: 7px 0 0; font-size: 12.5px; color: var(--tg-text-secondary); }
.auth-form--dense { margin-top: 24px; text-align: left; }
.auth-form--dense :deep(.el-form-item) { margin-bottom: 16px; }
.auth-form--dense :deep(.el-form-item__label) {
  font-size: 12.5px;
  font-weight: 600;
  color: var(--tg-text-secondary);
  padding-bottom: 4px;
}
.login-form :deep(.el-input__wrapper) {
  border-radius: 12px;
  padding: 3px 8px;
  background: var(--tg-bg-card);
  box-shadow: 0 0 0 1px rgba(60, 45, 25, 0.11) inset;
  transition: box-shadow 0.2s ease;
}
.login-form :deep(.el-input__wrapper:hover) { box-shadow: 0 0 0 1px rgba(143, 107, 34, 0.32) inset; }
.login-form :deep(.el-input__wrapper.is-focus) { box-shadow: var(--tg-ring-accent) !important; }
.login-form :deep(.el-input__inner) { height: 38px; }
.login-form :deep(input:-webkit-autofill) {
  -webkit-box-shadow: 0 0 0 1000px var(--tg-bg-card) inset;
  transition: background-color 9999s ease-in-out 0s;
}
.login-form :deep(.el-input__prefix) { color: var(--tg-slate); }
.login-card__btn {
  width: 100%;
  height: 44px;
  font-size: 15px;
  letter-spacing: 0.28em;
  text-indent: 0.28em;
}
.login-pane__copyright { margin: 22px 0 0; font-size: 10.5px; color: var(--tg-slate); }

@media (max-width: 960px) {
  .login-shell { grid-template-columns: 1fr; }
  .login-hero { display: none; }
  .login-pane { padding-top: 6vh; }
}
@media (max-width: 520px) {
  .login-pane { padding-left: 22px; padding-right: 22px; }
}
@media (prefers-reduced-motion: reduce) {
  .login-hero__ring--a,
  .login-hero__ring--b,
  .login-card__lock { animation: none !important; }
}
</style>
