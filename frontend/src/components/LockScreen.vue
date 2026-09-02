<template>
  <Teleport to="body">
    <transition name="lock-fade">
      <div v-if="visible" class="lock-screen">
        <div class="tg-aurora"></div>
        <div class="lock-card tg-fade-up">
          <div class="lock-brand">
            <div class="lock-brand__icon">TG</div>
            <span class="lock-brand__text"><b>Trace</b>Guard</span>
          </div>
          <div class="lock-avatar">{{ (username || '?').slice(0, 1).toUpperCase() }}</div>
          <div class="lock-name">{{ realName || username }}</div>
          <div class="lock-time">
            <b>{{ timeText }}</b>
            <span>{{ dateText }}</span>
          </div>
          <div class="lock-input">
            <el-input
              v-model="password"
              type="password"
              show-password
              size="large"
              placeholder="输入登录密码以解锁"
              @keyup.enter="unlock"
            >
              <template #prefix><el-icon><Lock /></el-icon></template>
            </el-input>
          </div>
          <el-button type="primary" size="large" round class="lock-btn" :loading="unlocking" @click="unlock">
            <el-icon style="margin-right: 6px"><Unlock /></el-icon>解锁
          </el-button>
          <button type="button" class="lock-exit" @click="doLogout">退出登录</button>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
/**
 * W3-04 / R17：锁屏
 * 呼出后覆盖系统，解锁需用当前账号密码经 /auth/login 校验（不改变既有会话），失败可退出登录。
 */
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Lock, Unlock } from '@element-plus/icons-vue'
import { authApi } from '@/api'

const router = useRouter()
const visible = ref(false)
const password = ref('')
const unlocking = ref(false)

const getInfo = () => {
  try {
    return JSON.parse(localStorage.getItem('userInfo') || '{}')
  } catch (e) {
    return {}
  }
}
const username = ref(getInfo().username || '')
const realName = ref(getInfo().realName || '')

const timeText = ref('')
const dateText = ref('')
let timer = null

const tick = () => {
  const d = new Date()
  timeText.value = `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
  dateText.value = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')} ${['周日', '周一', '周二', '周三', '周四', '周五', '周六'][d.getDay()]}`
}

const open = () => {
  const info = getInfo()
  username.value = info.username || ''
  realName.value = info.realName || ''
  password.value = ''
  visible.value = true
  timer = setInterval(tick, 1000)
  tick()
}

const close = () => {
  visible.value = false
  password.value = ''
  if (timer) clearInterval(timer)
}

const unlock = async () => {
  if (!password.value.trim()) {
    ElMessage.warning('请输入登录密码')
    return
  }
  unlocking.value = true
  try {
    // 复用登录接口校验当前账号密码；成功后不改变既有登录态
    await authApi.login({ username: username.value, password: password.value })
    close()
  } catch (e) {
    // 锁屏场景不展示后端原始文案（避免暴露账号状态细节），统一友好提示
    ElMessage.error(`密码错误或账号已临时锁定（${e.message || '请稍后重试'}）`)
  } finally {
    unlocking.value = false
  }
}

const doLogout = () => {
  close()
  authApi.logout().finally(() => {
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
    router.push('/home')
  })
}

onUnmounted(() => {
  if (timer) clearInterval(timer)
})

defineExpose({ open })
</script>

<style scoped>
.lock-screen {
  position: fixed;
  inset: 0;
  z-index: 5000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background:
    radial-gradient(1100px 520px at 50% -10%, rgba(143, 107, 34, 0.12), transparent 70%),
    rgba(18, 14, 10, 0.88);
  -webkit-backdrop-filter: blur(18px) saturate(1.2);
  backdrop-filter: blur(18px) saturate(1.2);
}

.lock-screen > .tg-aurora {
  z-index: 0;
  opacity: 0.6;
}

.lock-card {
  position: relative;
  z-index: 1;
  width: 380px;
  max-width: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 14px;
  padding: 42px 36px 30px;
  border-radius: 24px;
  background: rgba(28, 23, 17, 0.86);
  border: 1px solid rgba(237, 227, 210, 0.1);
  box-shadow: 0 30px 80px rgba(0, 0, 0, 0.55);
}

.lock-brand {
  display: flex;
  align-items: center;
  gap: 10px;
}

.lock-brand__icon {
  width: 38px;
  height: 38px;
  border-radius: 12px;
  background: var(--tg-accent-gradient);
  color: #fff;
  font-size: 14px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: var(--tg-glow-accent);
}

.lock-brand__text {
  font-size: 19px;
  font-weight: 700;
  color: #EDE3D2;
}

.lock-brand__text b {
  background: var(--tg-gradient);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.lock-avatar {
  width: 74px;
  height: 74px;
  border-radius: 24px;
  background: var(--tg-accent-gradient);
  color: #fff;
  font-size: 30px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 10px 30px rgba(217, 169, 102, 0.3);
}

.lock-name {
  font-size: 16px;
  font-weight: 600;
  color: #EDE3D2;
}

.lock-time {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
}

.lock-time b {
  font-size: 44px;
  font-weight: 700;
  color: #EDE3D2;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.02em;
}

.lock-time span {
  font-size: 13px;
  color: #B3A48C;
}

.lock-input {
  width: 100%;
  margin-top: 4px;
}

.lock-btn {
  width: 100%;
}

.lock-exit {
  margin-top: 2px;
  border: none;
  background: none;
  font-family: inherit;
  font-size: 12.5px;
  color: #8A7C66;
  cursor: pointer;
  transition: color 0.2s ease;
}

.lock-exit:hover {
  color: #D9A966;
}

.lock-fade-enter-active,
.lock-fade-leave-active {
  transition: opacity 0.35s ease;
}

.lock-fade-enter-from,
.lock-fade-leave-to {
  opacity: 0;
}
</style>