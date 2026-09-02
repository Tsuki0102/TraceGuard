<template>
  <div class="login-shell">
    <div class="tg-aurora"></div>

    <!-- ===== 左侧品牌叙事面板（深色暖金，与右侧浅色表单形成对比） ===== -->
    <aside class="login-hero">
      <i class="login-hero__ring login-hero__ring--a" aria-hidden="true"></i>
      <i class="login-hero__ring login-hero__ring--b" aria-hidden="true"></i>
      <i class="login-hero__glow" aria-hidden="true"></i>
      <div class="login-hero__inner">
        <div class="login-hero__brand tg-fade-up">
          <img class="login-hero__logo" src="@/assets/logo/logo-gold.png" alt="TraceGuard 标志" />
          <div class="login-hero__wordmark"><span>Trace</span>Guard</div>
        </div>
        <h1 class="login-hero__title tg-fade-up" style="animation-delay: 0.1s">让代码，<br />回应每一行需求。</h1>
        <p class="login-hero__desc tg-fade-up" style="animation-delay: 0.18s">
          形式化规约 × 大模型融合的需求-代码一致性验证与缺陷自动检测平台
        </p>
        <ul class="login-hero__feats">
          <li class="tg-fade-up" style="animation-delay: 0.26s">
            <span class="login-hero__feat-ic" aria-hidden="true">
              <svg viewBox="0 0 16 16" width="15" height="15" fill="none"><path d="M6 2C4.5 2 5.5 5 4 6.5 5.5 8 4.5 11 6 14M10 2c1.5 0 .5 3 2 4.5C10.5 8 11.5 11 10 14" stroke="currentColor" stroke-width="1.5" stroke-linecap="round"/></svg>
            </span>
            <div><b>形式化需求建模</b><small>文档解析 → Kripke 语义 → Alloy 规约自动生成</small></div>
          </li>
          <li class="tg-fade-up" style="animation-delay: 0.34s">
            <span class="login-hero__feat-ic" aria-hidden="true">
              <svg viewBox="0 0 16 16" width="15" height="15" fill="none"><path d="M2 5.5 8 2.5l6 3-6 3-6-3Z" stroke="currentColor" stroke-width="1.4" stroke-linejoin="round"/><path d="m2 8.5 6 3 6-3M2 11.5l6 3 6-3" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round"/></svg>
            </span>
            <div><b>三维一致性校验</b><small>语义相似度 · 约束匹配度 · 不变量满足度</small></div>
          </li>
          <li class="tg-fade-up" style="animation-delay: 0.42s">
            <span class="login-hero__feat-ic" aria-hidden="true">
              <svg viewBox="0 0 16 16" width="15" height="15" fill="none"><circle cx="4.5" cy="8" r="2.2" stroke="currentColor" stroke-width="1.4"/><circle cx="11.5" cy="8" r="2.2" stroke="currentColor" stroke-width="1.4"/><path d="M6.7 8h2.6M13.7 8h1.6M.7 8h1.6" stroke="currentColor" stroke-width="1.4" stroke-linecap="round"/></svg>
            </span>
            <div><b>缺陷定位与双向追溯</b><small>追溯矩阵自动生成 · 智能修复建议</small></div>
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

    <!-- ===== 右侧登录表单 ===== -->
    <main class="login-pane">
      <section class="login-card tg-fade-up">
        <img class="login-card__mark" src="@/assets/logo/logo-gold.png" alt="" aria-hidden="true" />
        <h2 class="login-card__title">欢迎回来</h2>
        <p class="login-card__sub">登录以继续使用一致性验证工作台</p>
        <span class="login-card__badge"><span class="tg-live-dot"></span>一致性验证 · 缺陷检测</span>

        <el-form :model="loginForm" :rules="rules" ref="loginFormRef" class="login-form" @submit.prevent>
          <el-form-item prop="username">
            <el-input v-model="loginForm.username" placeholder="用户名" prefix-icon="User" size="large" autocomplete="username" />
          </el-form-item>
          <el-form-item prop="password">
            <el-input v-model="loginForm.password" type="password" placeholder="密码" prefix-icon="Lock" size="large" show-password autocomplete="current-password" @keyup.enter="handleLogin" />
          </el-form-item>
          <el-form-item>
            <el-button type="primary" size="large" class="login-card__btn" :loading="loading" @click="handleLogin">登 录</el-button>
          </el-form-item>
        </el-form>

        <div class="login-card__links">
          <el-link type="primary" :underline="false" @click="goRegister">没有账号？注册账号</el-link>
          <el-divider direction="vertical" />
          <el-link :underline="false" @click="router.push('/home')">返回首页</el-link>
        </div>
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
/* =====================================================================
 * 登录页：分屏叙事布局（左深色品牌面板 + 右浅色表单）
 * 版式独立于全站其他页面；深色面板配色沿用暗色主题令牌口径
 * ===================================================================== */
.login-shell {
  position: relative;
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(440px, 47%) 1fr;
  overflow: hidden;
}
.login-shell > .tg-aurora { z-index: 0; }

/* ---- 左：品牌叙事面板 ---- */
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
/* 星点纹理（呼应全站 .page::before） */
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
/* 装饰双环（logo 环的抽象延伸，慢速旋转） */
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
  margin: 34px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 18px;
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

/* 真实评测指标（源自 docs/03-报告/评测报告-综合.md 2026-08-27 批次） */
.login-hero__metrics {
  margin-top: 36px;
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

/* ---- 右：表单区 ---- */
.login-pane {
  position: relative;
  z-index: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 44px 40px 24px;
  background:
    radial-gradient(900px 480px at 78% -8%, rgba(232, 200, 119, 0.16), transparent 66%),
    radial-gradient(700px 420px at 12% 108%, rgba(217, 169, 102, 0.1), transparent 64%),
    var(--tg-bg-page);
}
.login-card {
  width: 100%;
  max-width: 380px;
  text-align: center;
}
.login-card__mark {
  width: 56px;
  height: 56px;
  filter: drop-shadow(0 6px 16px rgba(143, 107, 34, 0.24));
  animation: tg-float 5s ease-in-out infinite;
}
.login-card__title { margin: 16px 0 0; font-size: 24px; font-weight: 700; letter-spacing: -0.01em; color: var(--tg-text-primary); }
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

.login-form { margin-top: 26px; text-align: left; }
.login-form :deep(.el-form-item) { margin-bottom: 18px; }
.login-form :deep(.el-input__wrapper) {
  border-radius: 12px;
  padding: 3px 8px;
  background: #FFFFFF;
  box-shadow: 0 0 0 1px rgba(60, 45, 25, 0.11) inset;
  transition: box-shadow 0.2s ease;
}
.login-form :deep(.el-input__wrapper:hover) { box-shadow: 0 0 0 1px rgba(143, 107, 34, 0.32) inset; }
.login-form :deep(.el-input__wrapper.is-focus) { box-shadow: var(--tg-ring-accent) !important; }
.login-form :deep(.el-input__inner) { height: 40px; }
/* Chrome 自动填充的淡蓝底修正为暖白 */
.login-form :deep(input:-webkit-autofill) {
  -webkit-box-shadow: 0 0 0 1000px #ffffff inset;
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
  margin-top: 4px;
  font-size: 13px;
}
.login-pane__copyright { margin: 26px 0 0; font-size: 10.5px; color: var(--tg-slate); }

/* ---- 窄屏：单栏（隐藏品牌面板，顶部保留紧凑品牌行） ---- */
@media (max-width: 960px) {
  .login-shell { grid-template-columns: 1fr; }
  .login-hero { display: none; }
  .login-pane { padding-top: 8vh; }
}
@media (max-width: 520px) {
  .login-pane { padding-left: 22px; padding-right: 22px; }
}
/* 尊重系统减少动效偏好（全局已有 override，此处补充环动画的类名兜底） */
@media (prefers-reduced-motion: reduce) {
  .login-hero__ring--a,
  .login-hero__ring--b,
  .login-card__mark { animation: none !important; }
}
</style>
