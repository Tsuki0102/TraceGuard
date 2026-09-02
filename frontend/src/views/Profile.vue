<template>
  <div class="profile-page">
    <div class="page-header">
      <div>
        <p class="tg-kicker">Account Center</p>
        <h2 class="page-header__title">个人中心</h2>
        <p class="page-header__desc">查看个人信息并修改登录密码</p>
      </div>
    </div>

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
        <el-card shadow="never" class="profile-id-card">
          <template #header><span>个人信息</span></template>
          <div class="profile-id">
            <span class="profile-avatar" :title="'点击更换头像'">
              <img v-if="avatarUrl" :src="avatarUrl" alt="头像" class="profile-avatar__img" />
              <span v-else class="tg-avatar tg-avatar--lg">{{ avatarText(user.username) }}</span>
              <span class="profile-avatar__mask" aria-hidden="true">
                <el-icon :size="16"><Camera /></el-icon>
              </span>
              <input
                ref="avatarInputRef"
                type="file"
                accept="image/png,image/jpeg,image/webp"
                class="profile-avatar__input"
                aria-label="上传头像"
                @change="onAvatarChange"
              />
            </span>
            <div class="profile-id__text">
              <div class="profile-id__name">{{ user.realName || user.username || '未知用户' }}</div>
              <div class="profile-id__user">@{{ user.username }}</div>
              <el-tag :type="user.role === 'admin' ? 'danger' : 'success'" size="small" effect="light">
                {{ user.role === 'admin' ? '管理员' : '普通用户' }}
              </el-tag>
            </div>
          </div>
          <el-descriptions :column="1" class="profile-desc">
            <el-descriptions-item label="邮箱">{{ user.email || '-' }}</el-descriptions-item>
            <el-descriptions-item label="最近登录">{{ user.lastLoginTime || '-' }}</el-descriptions-item>
            <el-descriptions-item label="密码状态">
              <span class="tg-dot" :class="passwordExpiringSoon ? 'tg-dot--warning' : 'tg-dot--success'"></span>
              <span>{{ passwordExpiringSoon ? '即将过期' : '正常' }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :span="16">
        <el-card shadow="never">
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

        <!-- 个性化增强 BATCH-5：我的操作足迹 -->
        <el-card shadow="never" class="profile-footprint" v-loading="footprintLoading">
          <template #header><span>我的操作足迹</span></template>
          <el-timeline v-if="footprint.length" class="footprint-timeline">
            <el-timeline-item
              v-for="(f, i) in footprint"
              :key="i"
              :type="f.statusCode >= 400 ? 'danger' : 'success'"
              :hollow="i > 0"
              :timestamp="f.createTime"
            >
              <b>{{ f.operation }}</b>
              <small>{{ f.method }} {{ f.path }}</small>
            </el-timeline-item>
          </el-timeline>
          <EmptyArt v-else text="暂无操作记录，去创建第一个项目吧" />
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi, auditApi, preferenceApi } from '@/api'
import { readLocalPreference } from '@/utils/preferenceSync'

const router = useRouter()
const user = ref({})
const avatarUrl = ref('')
const avatarInputRef = ref(null)
const footprint = ref([])
const footprintLoading = ref(false)

const applyAvatar = () => {
  avatarUrl.value = user.value?.avatarDataUrl || ''
}

const triggerAvatarUpload = () => avatarInputRef.value?.click()

/** 前端压缩到 96×96 JPEG，存入偏好 JSON（免改表）；同步广播给顶栏 */
const onAvatarChange = async (e) => {
  const file = e.target.files && e.target.files[0]
  e.target.value = ''
  if (!file) return
  if (!/^image\/(png|jpeg|webp)$/.test(file.type)) {
    ElMessage.error('仅支持 PNG / JPG / WebP 图片')
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    ElMessage.error('图片不能超过 5MB')
    return
  }
  const dataUrl = await new Promise((resolve, reject) => {
    const img = new Image()
    const url = URL.createObjectURL(file)
    img.onload = () => {
      const size = 96
      const canvas = document.createElement('canvas')
      canvas.width = size
      canvas.height = size
      const ctx = canvas.getContext('2d')
      const min = Math.min(img.width, img.height)
      ctx.drawImage(img, (img.width - min) / 2, (img.height - min) / 2, min, min, 0, 0, size, size)
      URL.revokeObjectURL(url)
      resolve(canvas.toDataURL('image/jpeg', 0.85))
    }
    img.onerror = reject
    img.src = url
  })
  try {
    const merged = { ...readLocalPreference(), avatarDataUrl: dataUrl }
    await preferenceApi.save(merged)
    user.value = { ...user.value, avatarDataUrl: dataUrl }
    try {
      const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
      info.avatarDataUrl = dataUrl
      localStorage.setItem('userInfo', JSON.stringify(info))
    } catch (err) { /* ignore */ }
    applyAvatar()
    window.dispatchEvent(new CustomEvent('tg:avatar', { detail: dataUrl }))
    ElMessage.success('头像已更新')
  } catch (err) {
    ElMessage.error(err?.message || '头像保存失败')
  }
}

const loadFootprint = async () => {
  footprintLoading.value = true
  try {
    const res = await auditApi.mine(12)
    footprint.value = (res && res.records) || []
  } catch (e) {
    footprint.value = []
  } finally {
    footprintLoading.value = false
  }
}
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
  try {
    const info = JSON.parse(localStorage.getItem('userInfo') || '{}')
    if (info.avatarDataUrl && !user.value.avatarDataUrl) user.value.avatarDataUrl = info.avatarDataUrl
  } catch (e) { /* ignore */ }
  applyAvatar()
  loadFootprint()
})

const avatarText = (name) => (name || '?').slice(0, 1).toUpperCase()

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
.profile-id {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 6px 0 2px;
}

.profile-id__text {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

.profile-id__name {
  font-size: 18px;
  font-weight: 700;
  color: var(--tg-text-primary);
  letter-spacing: -0.01em;
}

.profile-id__user {
  font-size: 12.5px;
  color: var(--tg-slate);
}

.profile-desc {
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--tg-border);
}
/* ===== 个性化增强 BATCH-5：头像上传 + 操作足迹 ===== */
.profile-avatar {
  position: relative;
  width: 56px;
  height: 56px;
  border-radius: 50%;
  overflow: hidden;
  cursor: pointer;
  flex-shrink: 0;
  box-shadow: var(--tg-shadow-card);
}
.profile-avatar__img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.profile-avatar__mask {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  background: rgba(43, 36, 28, 0.45);
  opacity: 0;
  transition: opacity 0.25s ease;
}
.profile-avatar:hover .profile-avatar__mask { opacity: 1; }
.profile-avatar__input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}
.profile-footprint { margin-top: 16px; }
.footprint-timeline { padding-left: 4px; max-height: 360px; overflow-y: auto; }
.footprint-timeline :deep(.el-timeline-item__content) b {
  font-size: 13px;
  color: var(--tg-text-primary);
}
.footprint-timeline :deep(.el-timeline-item__content) small {
  display: block;
  margin-top: 2px;
  font-size: 11.5px;
  color: var(--tg-slate);
  word-break: break-all;
}
</style>
