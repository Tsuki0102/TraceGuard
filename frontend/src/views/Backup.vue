<template>
  <div class="backup-page">
    <!-- ===== 页头：返回 + 标题 + 立即备份 ===== -->
    <div class="page-header tg-fade-up">
      <div>
        <div class="page-header__back">
          <el-button link class="page-header__back-btn" @click="$router.back()">
            <el-icon><ArrowLeft /></el-icon> 返回
          </el-button>
        </div>
        <div class="page-header__greet">
          <el-icon class="page-header__greet-icon"><Files /></el-icon>
          Data Protection
        </div>
        <h2 class="page-header__title">数据备份与恢复</h2>
        <p class="page-header__desc">创建加密备份、配置自动备份频率，支持下载与恢复</p>
      </div>
      <div class="page-header__actions">
        <el-button type="primary" size="large" round :loading="creating" @click="handleCreate">
          <el-icon style="margin-right: 6px"><Plus /></el-icon> 立即备份
        </el-button>
      </div>
    </div>

    <!-- ===== KPI 统计行 ===== -->
    <div class="kpi-row">
      <div class="kpi-card tg-fade-up">
        <div class="kpi-card__tile kpi-card__tile--gold"><el-icon :size="22"><Files /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num"><span class="tg-count">{{ backupCountDisp }}</span></div>
          <div class="kpi-card__label">备份文件</div>
          <div class="kpi-card__meta">历史备份总数</div>
        </div>
      </div>
      <div class="kpi-card tg-fade-up" style="animation-delay: 60ms">
        <div class="kpi-card__tile kpi-card__tile--green"><el-icon :size="22"><Clock /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num kpi-card__num--sm">{{ latestBackup }}</div>
          <div class="kpi-card__label">最近备份</div>
          <div class="kpi-card__meta">按创建时间取最新</div>
        </div>
      </div>
      <div class="kpi-card tg-fade-up" style="animation-delay: 120ms">
        <div class="kpi-card__tile kpi-card__tile--amber"><el-icon :size="22"><AlarmClock /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num">{{ frequencyLabel }}</div>
          <div class="kpi-card__label">自动备份</div>
          <div class="kpi-card__meta">每日凌晨 4 点检查</div>
        </div>
      </div>
      <div class="kpi-card tg-fade-up" style="animation-delay: 180ms">
        <div class="kpi-card__tile kpi-card__tile--coral"><el-icon :size="22"><Key /></el-icon></div>
        <div class="kpi-card__body">
          <div class="kpi-card__num" :style="{ color: passphraseSet ? 'var(--tg-success)' : 'var(--tg-warning)' }">
            {{ passphraseSet ? '已设置' : '未设置' }}
          </div>
          <div class="kpi-card__label">备份口令</div>
          <div class="kpi-card__meta">{{ passphraseSet ? '新备份将口令加密' : '使用系统配置密钥' }}</div>
        </div>
      </div>
    </div>

    <!-- ===== 备份策略面板：告警 + 口令 + 频率 ===== -->
    <section class="table-panel tg-fade-up">
      <div class="table-panel__head">
        <div class="table-panel__title">
          <h3><el-icon class="section-title__ic" :size="17"><Coin /></el-icon>备份策略</h3>
          <p>口令加密保护与自动备份调度</p>
        </div>
      </div>

      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="恢复操作将清空当前数据库并从备份文件还原全部数据，操作完成后需要重新登录，请谨慎执行！"
        style="margin-bottom: 16px"
      />

      <!-- GAP-017：备份口令设置 -->
      <div class="strategy-card">
        <div class="strategy-card__left">
          <div class="strategy-card__tile strategy-card__tile--amber"><el-icon :size="20"><Key /></el-icon></div>
          <div class="strategy-card__info">
            <b>备份口令</b>
            <p>设置后，新建备份将使用「口令派生密钥」加密（未设置时使用系统配置密钥，保持向后兼容）。</p>
            <p>恢复已设置口令的备份时必须输入口令，口令错误将拒绝恢复；<b class="tg-text-warn">口令丢失后旧备份将无法解密</b>，请妥善保管。</p>
            <span class="status-pill" :class="passphraseSet ? 'status-pill--done' : 'status-pill--wait'">
              {{ passphraseSet ? '已设置口令' : '未设置口令' }}
            </span>
          </div>
        </div>
        <el-button type="primary" round @click="openPassphraseDialog">
          <el-icon style="margin-right: 4px"><Key /></el-icon>
          {{ passphraseSet ? '修改口令' : '设置口令' }}
        </el-button>
      </div>

      <!-- 自动备份频率 -->
      <div class="freq-config">
        <div class="freq-config__left">
          <div class="strategy-card__tile strategy-card__tile--green"><el-icon :size="20"><AlarmClock /></el-icon></div>
          <div class="strategy-card__info">
            <b>自动备份频率</b>
            <p>每日凌晨 4 点检查，按所选频率自动创建加密备份</p>
          </div>
        </div>
        <div class="freq-config__right">
          <el-radio-group v-model="frequency" :loading="configLoading" class="freq-radio">
            <el-radio-button label="daily">每日</el-radio-button>
            <el-radio-button label="weekly">每周</el-radio-button>
            <el-radio-button label="monthly">每月</el-radio-button>
          </el-radio-group>
          <el-button type="primary" round :loading="savingConfig" @click="saveFrequency">保存</el-button>
        </div>
      </div>
    </section>

    <!-- ===== 备份文件面板 ===== -->
    <section class="table-panel tg-fade-up">
      <div class="table-panel__head">
        <div class="table-panel__title">
          <h3><el-icon class="section-title__ic" :size="17"><Tickets /></el-icon>备份文件</h3>
          <p>共 {{ backups.length }} 个备份，可下载 / 恢复 / 删除</p>
        </div>
      </div>

      <el-table :data="backups" v-loading="loading" class="manage-table">
        <el-table-column label="备份文件" min-width="260">
          <template #default="{ row }">
            <div class="file-cell">
              <span class="file-cell__tile"><el-icon :size="16"><Document /></el-icon></span>
              <span class="file-cell__name">{{ row.filename }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="110">
          <template #default="{ row }">
            <span class="num-cell">{{ row.sizeText }}</span>
          </template>
        </el-table-column>
        <el-table-column label="备份时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right" align="right">
          <template #default="{ row }">
            <div class="op-actions">
              <el-button class="op-btn op-btn--view" round @click="handleDownload(row.filename)">
                <el-icon><Download /></el-icon> 下载
              </el-button>
              <el-button class="op-btn op-btn--restore" round @click="handleRestore(row.filename)">
                <el-icon><RefreshLeft /></el-icon> 恢复
              </el-button>
              <el-button class="op-btn op-btn--danger" round @click="handleDelete(row.filename)">
                <el-icon><Delete /></el-icon> 删除
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>
    </section>

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
      <p style="margin: 0 0 12px; color: var(--tg-text-secondary)">
        若该备份创建时设置了口令，请输入备份口令；未设置口令请留空（使用系统配置密钥恢复）。
      </p>
      <el-input v-model="restorePassphrase" type="password" show-password
        placeholder="备份口令（可选）" @keyup.enter="confirmRestore" />
      <template #footer>
        <el-button @click="showRestoreDialog = false">取消</el-button>
        <el-button type="danger" :loading="restoring" @click="confirmRestore">确认恢复</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { backupApi } from '@/api'
import { useCountUp } from '@/composables/useCountUp'

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

// ===== KPI 数字滚动 =====
const backupCountDisp = useCountUp(computed(() => backups.value.length))

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

const latestBackup = computed(() => {
  if (!backups.value.length) return '暂无备份'
  const sorted = [...backups.value].sort((a, b) => new Date(b.createTime) - new Date(a.createTime))
  return sorted[0] && sorted[0].createTime ? formatTime(sorted[0].createTime) : '暂无备份'
})

const frequencyLabel = computed(() => ({ daily: '每日', weekly: '每周', monthly: '每月' }[frequency.value] || '每日'))

onMounted(() => {
  loadBackups()
  loadConfig()
})
</script>

<style scoped>
.backup-page {
  max-width: 1200px;
  margin: 0 auto;
}

/* ===== 页头（与已优化页面统一） ===== */
.page-header {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  flex-wrap: wrap;
  margin-bottom: 26px;
}

.page-header__back {
  margin-bottom: 6px;
}

.page-header__back-btn {
  color: var(--tg-text-secondary);
  padding: 0;
}

.page-header__greet {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 13.5px;
  font-weight: 500;
  color: var(--tg-accent);
  margin-bottom: 8px;
}

.page-header__greet-icon {
  font-size: 15px;
}

.page-header__title {
  margin: 0;
  font-size: 30px;
  font-weight: 700;
  letter-spacing: -0.02em;
  line-height: 1.15;
  color: var(--tg-text-primary);
  background: linear-gradient(115deg, #6E521A 0%, #8F6B22 50%, #B98A2F 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.page-header__desc {
  margin: 8px 0 0;
  font-size: 14px;
  color: var(--tg-text-secondary);
}

.page-header__actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-shrink: 0;
}

/* ===== 策略卡（口令 / 频率） ===== */
.strategy-card {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding: 18px 20px;
  border-radius: 16px;
  background: var(--tg-gradient-soft);
  border: 1px solid rgba(255, 255, 255, 0.7);
  box-shadow: var(--tg-shadow-card);
  margin-bottom: 16px;
  transition: box-shadow 0.3s var(--tg-ease), transform 0.3s var(--tg-ease);
}

.strategy-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--tg-shadow-float);
}

.strategy-card__left {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
  flex: 1;
}

.strategy-card__tile {
  width: 44px;
  height: 44px;
  border-radius: 13px;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  transition: transform 0.3s var(--tg-ease-spring);
}

.strategy-card:hover .strategy-card__tile {
  transform: scale(1.08) rotate(-4deg);
}

.strategy-card__tile--amber {
  background: linear-gradient(135deg, rgba(232, 155, 60, 0.16), rgba(232, 200, 119, 0.2));
  color: #b5731f;
}

.strategy-card__tile--green {
  background: linear-gradient(135deg, rgba(154, 156, 107, 0.16), rgba(168, 185, 138, 0.2));
  color: var(--tg-success);
}

.strategy-card__info {
  min-width: 0;
}

.strategy-card__info b {
  font-size: 14.5px;
  font-weight: 600;
  color: var(--tg-text-primary);
}

.strategy-card__info p {
  margin: 3px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--tg-text-secondary);
}

.strategy-card__info .status-pill {
  margin-top: 8px;
}

.tg-text-warn {
  color: var(--tg-warning);
}

/* 频率配置行 */
.freq-config {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
  padding: 18px 20px;
  border-radius: 16px;
  background: rgba(255, 255, 255, 0.55);
  border: 1px solid var(--tg-border);
}

.freq-config__left {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
  flex: 1;
}

.freq-config__right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.freq-radio :deep(.el-radio-button__inner) {
  border-radius: 999px !important;
  padding: 6px 16px;
}

.freq-radio :deep(.el-radio-button:first-child .el-radio-button__inner) {
  border-radius: 999px 0 0 999px !important;
  border-left: 1px solid var(--el-border-color);
}

.freq-radio :deep(.el-radio-button:last-child .el-radio-button__inner) {
  border-radius: 0 999px 999px 0 !important;
}

.freq-radio :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) {
  background: var(--tg-accent-gradient);
  border-color: transparent;
  box-shadow: var(--tg-glow-accent);
  color: #fff;
}

/* 窄屏 */
@media (max-width: 720px) {
  .page-header {
    flex-direction: column;
    align-items: flex-start;
  }
}
</style>
