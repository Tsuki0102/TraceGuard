<template>
  <div class="backup-page">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>数据备份与恢复</span>
          <el-button type="primary" :loading="creating" @click="handleCreate">
            <el-icon><Plus /></el-icon> 立即备份
          </el-button>
        </div>
      </template>

      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="恢复操作将清空当前数据库并从备份文件还原全部数据，操作完成后需要重新登录，请谨慎执行！"
        style="margin-bottom: 16px"
      />

      <!-- GAP-017：备份口令设置 -->
      <el-card shadow="never" style="margin-bottom: 16px">
        <template #header>
          <div style="display: flex; justify-content: space-between; align-items: center">
            <span>备份口令（GAP-017）</span>
            <el-button type="primary" size="small" @click="openPassphraseDialog">
              {{ passphraseSet ? '修改口令' : '设置口令' }}
            </el-button>
          </div>
        </template>
        <div style="color: #909399; font-size: 13px; line-height: 1.7">
          <p>设置备份口令后，新建备份将使用「口令派生密钥」加密（未设置时使用系统配置密钥，保持向后兼容）。</p>
          <p>恢复已设置口令的备份时必须输入口令，口令错误将拒绝恢复；<b>口令丢失后旧备份将无法解密</b>，请妥善保管。</p>
          <p v-if="passphraseSet" style="color: #67C23A">当前已设置备份口令，新建备份将使用口令加密。</p>
          <p v-else style="color: #E6A23C">当前未设置备份口令，备份使用系统配置密钥加密。</p>
        </div>
      </el-card>

      <div class="freq-config" style="margin-bottom: 16px; display: flex; align-items: center">
        <span style="margin-right: 10px; font-weight: bold">自动备份频率（每日凌晨4点检查）：</span>
        <el-radio-group v-model="frequency" :loading="configLoading">
          <el-radio-button label="daily">每日</el-radio-button>
          <el-radio-button label="weekly">每周</el-radio-button>
          <el-radio-button label="monthly">每月</el-radio-button>
        </el-radio-group>
        <el-button type="primary" size="small" style="margin-left: 12px" :loading="savingConfig" @click="saveFrequency">保存</el-button>
      </div>

      <el-table :data="backups" v-loading="loading" stripe>
        <el-table-column prop="filename" label="备份文件" min-width="240" />
        <el-table-column prop="sizeText" label="大小" width="110" />
        <el-table-column prop="createTime" label="备份时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="handleDownload(row.filename)">
              <el-icon><Download /></el-icon> 下载
            </el-button>
            <el-button size="small" type="danger" @click="handleDelete(row.filename)">
              <el-icon><Delete /></el-icon> 删除
            </el-button>
            <el-button size="small" type="warning" @click="handleRestore(row.filename)">
              <el-icon><RefreshLeft /></el-icon> 恢复
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- GAP-017：设置备份口令对话框 -->
      <el-dialog v-model="showPassphraseDialog" title="设置备份口令" width="480px" @closed="resetPassphraseForm">
        <el-alert type="info" :closable="false" show-icon style="margin-bottom: 16px"
          title="口令至少 8 位。设置后新建备份使用口令派生密钥加密；口令丢失将无法解密旧备份。" />
        <el-form label-width="90px">
          <el-form-item label="备份口令" required>
            <el-input v-model="passphraseForm.passphrase" type="password" show-password
              placeholder="请输入至少 8 位的备份口令" />
          </el-form-item>
          <el-form-item label="确认口令" required>
            <el-input v-model="passphraseForm.confirm" type="password" show-password
              placeholder="请再次输入备份口令" />
          </el-form-item>
        </el-form>
        <template #footer>
          <el-button @click="showPassphraseDialog = false">取消</el-button>
          <el-button type="primary" :loading="savingPassphrase" @click="savePassphrase">确认设置</el-button>
        </template>
      </el-dialog>

      <!-- GAP-017：恢复备份口令校验对话框 -->
      <el-dialog v-model="showRestoreDialog" title="恢复备份" width="480px">
        <el-alert type="warning" :closable="false" show-icon style="margin-bottom: 16px"
          title="恢复将清空当前数据库并还原备份数据，完成后需重新登录。" />
        <p style="margin: 0 0 12px; color: #606266">
          若该备份创建时设置了口令，请输入备份口令；未设置口令请留空（使用系统配置密钥恢复）。
        </p>
        <el-input v-model="restorePassphrase" type="password" show-password
          placeholder="备份口令（可选）" @keyup.enter="confirmRestore" />
        <template #footer>
          <el-button @click="showRestoreDialog = false">取消</el-button>
          <el-button type="danger" :loading="restoring" @click="confirmRestore">确认恢复</el-button>
        </template>
      </el-dialog>
    </el-card>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { backupApi } from '@/api'

const router = useRouter()
const backups = ref([])
const loading = ref(false)
const creating = ref(false)
const frequency = ref('daily')
const configLoading = ref(false)
const savingConfig = ref(false)

// GAP-017：备份口令
const showPassphraseDialog = ref(false)
const passphraseForm = ref({ passphrase: '', confirm: '' })
const savingPassphrase = ref(false)
const passphraseSet = ref(false)
const showRestoreDialog = ref(false)
const restoring = ref(false)
const restorePassphrase = ref('')
const pendingRestoreFile = ref('')

const openPassphraseDialog = () => {
  passphraseForm.value = { passphrase: '', confirm: '' }
  showPassphraseDialog.value = true
}

const resetPassphraseForm = () => {
  passphraseForm.value = { passphrase: '', confirm: '' }
}

const savePassphrase = async () => {
  const { passphrase, confirm } = passphraseForm.value
  if (!passphrase || passphrase.length < 8) {
    ElMessage.warning('备份口令长度不能少于 8 位')
    return
  }
  if (passphrase !== confirm) {
    ElMessage.warning('两次输入的口令不一致')
    return
  }
  savingPassphrase.value = true
  try {
    await backupApi.setPassphrase(passphrase)
    passphraseSet.value = true
    ElMessage.success('备份口令已设置，后续备份将使用口令加密')
    showPassphraseDialog.value = false
  } catch (e) {
    ElMessage.error(e.message || '口令设置失败')
  } finally {
    savingPassphrase.value = false
  }
}

/** 自动备份频率配置与口令状态（需求6.3.1；SEC-13 口令状态由服务端返回，刷新后保持正确） */
const loadConfig = async () => {
  configLoading.value = true
  try {
    const res = await backupApi.getConfig()
    frequency.value = (res && res.frequency) || 'daily'
    passphraseSet.value = !!(res && res.passphraseSet === 'true')
  } catch (e) {
    console.error(e)
  } finally {
    configLoading.value = false
  }
}

const saveFrequency = async () => {
  savingConfig.value = true
  try {
    await backupApi.updateConfig(frequency.value)
    ElMessage.success('备份频率已更新')
  } catch (e) {
    ElMessage.error(e.message || '保存失败')
  } finally {
    savingConfig.value = false
  }
}

const loadBackups = async () => {
  loading.value = true
  try {
    const res = await backupApi.list()
    backups.value = res || []
  } catch (e) {
    ElMessage.error(e.message || '获取备份列表失败')
  } finally {
    loading.value = false
  }
}

const handleCreate = async () => {
  creating.value = true
  try {
    await backupApi.create()
    ElMessage.success('备份创建成功')
    await loadBackups()
  } catch (e) {
    ElMessage.error(e.message || '备份创建失败')
  } finally {
    creating.value = false
  }
}

const handleDownload = async (filename) => {
  try {
    await backupApi.download(filename)
  } catch (e) {
    ElMessage.error(e.message || '下载失败')
  }
}

const handleDelete = (filename) => {
  ElMessageBox.confirm(`确定删除备份 ${filename} 吗？`, '提示', { type: 'warning' })
    .then(async () => {
      try {
        await backupApi.delete(filename)
        ElMessage.success('删除成功')
        await loadBackups()
      } catch (e) {
        ElMessage.error(e.message || '删除失败')
      }
    })
    .catch(() => {})
}

const handleRestore = (filename) => {
  pendingRestoreFile.value = filename
  restorePassphrase.value = ''
  showRestoreDialog.value = true
}

const confirmRestore = async () => {
  const passphrase = restorePassphrase.value
  // GAP-017：口令错误拒绝恢复，不破坏备份文件
  if (passphrase && passphrase.length) {
    try {
      const ok = await backupApi.verifyPassphrase(passphrase)
      if (!ok) {
        ElMessage.error('备份口令错误，拒绝恢复')
        return
      }
    } catch (e) {
      ElMessage.error(e.message || '口令校验失败')
      return
    }
  }
  restoring.value = true
  try {
    await backupApi.restore(pendingRestoreFile.value)
    ElMessage.success('恢复成功，即将跳转登录页')
    showRestoreDialog.value = false
    localStorage.removeItem('token')
    localStorage.removeItem('userInfo')
    setTimeout(() => router.push('/login'), 1500)
  } catch (e) {
    ElMessage.error(e.message || '恢复失败')
  } finally {
    restoring.value = false
  }
}

const formatTime = (t) => {
  if (!t) return ''
  const d = new Date(t)
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

onMounted(() => {
  loadBackups()
  loadConfig()
})
</script>

<style scoped>
.backup-page {
  max-width: 1000px;
  margin: 0 auto;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
