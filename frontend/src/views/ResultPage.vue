<template>
  <div class="result-page">
    <div class="tg-aurora"></div>
    <div class="result-card tg-fade-up">
      <div class="result-card__icon" :class="'result-card__icon--' + tone">
        <el-icon :size="40"><component :is="icon" /></el-icon>
      </div>
      <h2 v-if="code" class="result-card__code">{{ code }}</h2>
      <h3 class="result-card__title">{{ title }}</h3>
      <p class="result-card__desc">{{ desc }}</p>
      <div class="result-card__actions">
        <el-button type="primary" size="large" round @click="go(primaryTo, primaryText)">
          {{ primaryText }}
        </el-button>
        <el-button v-if="secondaryText" size="large" round plain @click="go(secondaryTo)">
          {{ secondaryText }}
        </el-button>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * 通用结果反馈页（W1-02 / R7）
 * 依据路由 name → status 映射出：403 / 404 / 500 / success / fail 五类结果页。
 * 注册 "操作成功反馈页" 场景兜底，避免为每种结果新建页面。
 */
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()

const STATUS_MAP = {
  Forbidden: { code: '403', title: '无权访问', desc: '当前账号没有权限访问该页面，如需开通请联系管理员。', tone: 'warn', icon: 'Lock' },
  NotFound: { code: '404', title: '页面不存在', desc: '你访问的地址可能已失效或输入有误，请核对后重试。', tone: 'primary', icon: 'QuestionFilled' },
  ServerError: { code: '500', title: '服务器开小差了', desc: '请求处理时发生异常，错误已记录到审计日志，请稍后重试。', tone: 'danger', icon: 'WarningFilled' },
  SuccessPage: { code: '', title: '操作成功', desc: '你的操作已顺利完成。', tone: 'success', icon: 'CircleCheckFilled' },
  CatchAll: { code: '404', title: '页面不存在', desc: '你访问的地址可能已失效或输入有误，请核对后重试。', tone: 'primary', icon: 'QuestionFilled' }
}

const meta = computed(() => STATUS_MAP[route.name] || STATUS_MAP.CatchAll)
const code = computed(() => meta.value.code)
const title = computed(() => meta.value.title)
const tone = computed(() => meta.value.tone)
const icon = computed(() => meta.value.icon)

// 成功反馈页支持 query 定制（如注册成功）：type=register
const desc = computed(() => {
  if (route.name !== 'SuccessPage') return meta.value.desc
  if (route.query.type === 'register') {
    return '注册成功，请使用初始口令登录，首次登录后需修改密码。'
  }
  return meta.value.desc
})

const primaryText = computed(() => {
  if (route.name === 'SuccessPage') return route.query.type === 'register' ? '去登录' : '返回工作台'
  if (code.value === '403' || code.value === '404') return '返回工作台'
  return '重新加载'
})

const primaryTo = computed(() => {
  if (route.name === 'SuccessPage') {
    if (route.query.type === 'register') return '/login'
    return '/dashboard'
  }
  if (code.value === '500') return '' // 500 走整页刷新
  return '/dashboard'
})

const secondaryText = computed(() => {
  if (route.name === 'SuccessPage' && route.query.type === 'register') return '返回首页'
  if (code.value === '403' || code.value === '404') return '返回首页'
  return ''
})

const secondaryTo = computed(() => {
  if (route.name === 'SuccessPage' && route.query.type === 'register') return '/home'
  return '/home'
})

const go = (to, label) => {
  if (label === '重新加载') {
    window.location.reload()
    return
  }
  if (typeof to === 'string' && to.startsWith('/')) {
    router.push(to)
  }
}
</script>

<style scoped>
.result-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  position: relative;
  background:
    radial-gradient(1100px 520px at 50% -10%, rgba(143, 107, 34, 0.1), transparent 70%),
    var(--tg-bg-page);
}

.result-page > .tg-aurora {
  z-index: 0;
}

.result-card {
  position: relative;
  z-index: 1;
  width: 460px;
  max-width: 100%;
  text-align: center;
  padding: 48px 40px;
  border-radius: var(--tg-radius-modal);
  background: var(--tg-card-highlight);
  -webkit-backdrop-filter: blur(var(--tg-blur-strong)) saturate(1.4);
  backdrop-filter: blur(var(--tg-blur-strong)) saturate(1.4);
  border: 1px solid rgba(255, 255, 255, 0.72);
  box-shadow: var(--tg-shadow-modal);
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.result-card__icon {
  width: 84px;
  height: 84px;
  border-radius: 28px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 14px;
  transition: transform 0.35s var(--tg-ease-spring);
}

.result-card:hover .result-card__icon {
  transform: scale(1.06) rotate(-4deg);
}

.result-card__icon--success { background: rgba(154, 156, 107, 0.16); color: var(--tg-success); }
.result-card__icon--primary { background: var(--el-color-primary-light-9); color: var(--tg-accent); }
.result-card__icon--warn { background: rgba(232, 155, 60, 0.16); color: var(--tg-warning); }
.result-card__icon--danger { background: rgba(194, 94, 76, 0.14); color: var(--tg-danger); }

.result-card__code {
  margin: 0;
  font-size: 46px;
  font-weight: 700;
  letter-spacing: -0.03em;
  line-height: 1.1;
  background: var(--tg-gradient);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.result-card__title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  color: var(--tg-text-primary);
}

.result-card__desc {
  margin: 8px 0 4px;
  font-size: 14px;
  line-height: 1.7;
  color: var(--tg-text-secondary);
  max-width: 340px;
}

.result-card__actions {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  margin-top: 22px;
  flex-wrap: wrap;
}
</style>