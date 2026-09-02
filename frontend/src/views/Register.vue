<template>
  <div class="login-shell">
    <div class="tg-aurora"></div>

    <!-- ===== 左侧品牌叙事面板（与登录页同语言：深色暖金） ===== -->
    <aside class="login-hero">
      <i class="login-hero__ring login-hero__ring--a" aria-hidden="true"></i>
      <i class="login-hero__ring login-hero__ring--b" aria-hidden="true"></i>
      <i class="login-hero__glow" aria-hidden="true"></i>
      <div class="login-hero__inner">
        <div class="login-hero__brand tg-fade-up">
          <img class="login-hero__logo" src="@/assets/logo/logo-gold.png" alt="TraceGuard 标志" />
          <div class="login-hero__wordmark"><span>Trace</span>Guard</div>
        </div>
        <h1 class="login-hero__title tg-fade-up" style="animation-delay: 0.1s">开启你的<br />一致性验证工作台。</h1>
        <p class="login-hero__desc tg-fade-up" style="animation-delay: 0.18s">
          五步完成首次分析：创建项目 → 上传需求 → 上传代码 → 一键分析 → 查看报告
        </p>
        <ul class="login-hero__feats">
          <li class="tg-fade-up" style="animation-delay: 0.26s">
            <span class="login-hero__feat-ic" aria-hidden="true">
              <svg viewBox="0 0 16 16" width="15" height="15" fill="none"><path d="M3 8.5 6.5 12 13 4.5" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </span>
            <div><b>全流程可视化</b><small>需求解析、代码解析、一致性校验、报告导出一站式</small></div>
          </li>
          <li class="tg-fade-up" style="animation-delay: 0.34s">
            <span class="login-hero__feat-ic" aria-hidden="true">
              <svg viewBox="0 0 16 16" width="15" height="15" fill="none"><path d="M8 2v12M2 8h12" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/><circle cx="8" cy="8" r="5.5" stroke="currentColor" stroke-width="1.4"/></svg>
            </span>
            <div><b>形式化 × 大模型双引擎</b><small>Alloy 规约的严谨性与 LLM 语义理解互补覆盖</small></div>
          </li>
          <li class="tg-fade-up" style="animation-delay: 0.42s">
            <span class="login-hero__feat-ic" aria-hidden="true">
              <svg viewBox="0 0 16 16" width="15" height="15" fill="none"><rect x="2.5" y="3" width="11" height="10" rx="2" stroke="currentColor" stroke-width="1.4"/><path d="M5.5 6.5h5M5.5 9.5h3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>
            </span>
            <div><b>专业级报告</b><small>缺陷定位到行，追溯矩阵与修复建议直接可执行</small></div>
          </li>
        </ul>
        <div class="login-hero__metrics tg-fade-up" style="animation-delay: 0.5s">
          <div><b>96.4%</b><span>缺陷检测准确率</span></div>
          <div><b>3.8%</b><span>漏检率</span></div>
          <div><b>3.4%</b><span>误报率</span></div>
        </div>
      </div>
      <p class="login-hero__foot">TraceGuard · 需求-代码追溯平台</p>
    </aside>

    <!-- ===== 右侧注册表单 ===== -->
    <main class="login-pane">
      <section class="login-card tg-fade-up">
        <img class="login-card__mark" src="@/assets/logo/logo-gold.png" alt="" aria-hidden="true" />
        <h2 class="login-card__title">创建账号</h2>
        <p class="login-card__sub">注册后即可创建项目并运行一致性分析</p>
        <span class="login-card__badge"><span class="tg-live-dot"></span>免费开始 · 无需绑定</span>

        <el-form :model="form" :rules="rules" ref="formRef" class="login-form auth-form--dense">
          <el-form-item prop="username">
            <el-input v-model="form.username" placeholder="用户名" prefix-icon="User" size="large" autocomplete="username" />
          </el-form-item>
          <el-form-item prop="realName">
            <el-input v-model="form.realName" placeholder="姓名" prefix-icon="UserFilled" size="large" autocomplete="name" />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="form.password" type="password" placeholder="密码（至少 8 位，含大小写与数字）" prefix-icon="Lock" size="large" show-password autocomplete="new-password" />
          </el-form-item>
          <el-form-item prop="confirmPassword">
            <el-input v-model="form.confirmPassword" type="password" placeholder="确认密码" prefix-icon="Lock" size="large" show-password autocomplete="new-password" @keyup.enter="handleRegister" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" size="large" class="login-card__btn" :loading="loading" @click="handleRegister">注 册</el-button>
          </el-form-item>
          <div class="login-card__links">
            <el-link type="primary" :underline="false" @click="goLogin">已有账号？返回登录</el-link>
            <el-divider direction="vertical" />
            <el-link :underline="false" @click="router.push('/home')">返回首页</el-link>
          </div>
        </el-form>
      </section>
      <p class="login-pane__copyright">© 2026 TraceGuard · 郑州轻工业大学</p>
    </main>
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
    router.push('/success?type=register')
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
/* =====================================================================
 * 注册页：与登录页同构的分屏叙事布局（左侧品牌面板样式同源复用）
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
.login-hero__metrics {
  margin-top: 32px;
  padding-top: 22px;
  border-top: 1px solid rgba(237, 227, 210, 0.1);
  display: flex;
  gap: 34px;
}
.login-hero__metrics div { display: flex; flex-direction: column; gap: 3px; }
.login-hero__metrics b {
  font-size: 21px;
  font-weight: 700;
  background: linear-gradient(115deg, #E8C877, #C99B3F);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
  font-variant-numeric: tabular-nums;
}
.login-hero__metrics span { font-size: 10.5px; color: rgba(237, 227, 210, 0.45); }
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
.login-card__mark {
  width: 52px;
  height: 52px;
  filter: drop-shadow(0 6px 16px rgba(143, 107, 34, 0.24));
  animation: tg-float 5s ease-in-out infinite;
}
.login-card__title { margin: 14px 0 0; font-size: 24px; font-weight: 700; letter-spacing: -0.01em; color: var(--tg-text-primary); }
.login-card__sub { margin: 7px 0 0; font-size: 12.5px; color: var(--tg-text-secondary); }
.login-card__badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-top: 14px;
  padding: 5px 14px;
  border-radius: var(--tg-radius-pill);
  background: var(--el-color-primary-light-9);
  color: var(--tg-accent);
  font-size: 11.5px;
  font-weight: 500;
}
.auth-form--dense { margin-top: 20px; text-align: left; }
.auth-form--dense :deep(.el-form-item) { margin-bottom: 14px; }
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
.login-card__links {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 14px;
  margin-top: 2px;
  font-size: 13px;
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
  .login-card__mark { animation: none !important; }
}
</style>
