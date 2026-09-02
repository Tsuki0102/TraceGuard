<template>
  <div class="sys-status">
    <!-- ===== 页头 ===== -->
    <div class="page-header tg-fade-up">
      <div>
        <div class="page-header__greet">
          <el-icon class="page-header__greet-icon"><Platform /></el-icon>
          Operations
        </div>
        <h2 class="page-header__title">系统运行状态</h2>
        <p class="page-header__desc">数据库、磁盘、分析队列与实时连接的健康概览</p>
      </div>
      <div class="page-header__actions">
        <el-button type="primary" round :loading="loading" @click="load">
          <el-icon style="margin-right: 6px"><Refresh /></el-icon>刷新状态
        </el-button>
      </div>
    </div>

    <!-- ===== 状态指标行 ===== -->
    <div v-if="status" class="kpi-row">
      <div class="kpi-card">
        <div class="kpi-card__tile" :class="status.db ? 'kpi-card__tile--green' : 'kpi-card__tile--coral'">
          <el-icon :size="22"><Coin /></el-icon>
        </div>
        <div class="kpi-card__body">
          <div class="kpi-card__num kpi-card__num--sm">
            <span class="status-pill" :class="status.db ? 'status-pill--analyzed' : 'status-pill--failed'">
              {{ status.db ? '正常' : '异常' }}
            </span>
          </div>
          <div class="kpi-card__label">数据库连接</div>
          <div class="kpi-card__meta">MySQL 连通性检测</div>
        </div>
      </div>

      <div class="kpi-card">
        <div class="kpi-card__tile kpi-card__tile--amber">
          <el-icon :size="22"><Histogram /></el-icon>
        </div>
        <div class="kpi-card__body">
          <div class="kpi-card__num kpi-card__num--sm">
            <span class="tg-count">{{ status.runningTasks }}</span>
          </div>
          <div class="kpi-card__label">运行中任务</div>
          <div class="kpi-card__meta">分析队列活跃数</div>
        </div>
      </div>

      <div class="kpi-card">
        <div class="kpi-card__tile kpi-card__tile--gold">
          <el-icon :size="22"><Connection /></el-icon>
        </div>
        <div class="kpi-card__body">
          <div class="kpi-card__num kpi-card__num--sm">
            <span class="tg-count">{{ status.wsOnline }}</span>
          </div>
          <div class="kpi-card__label">实时连接</div>
          <div class="kpi-card__meta">WebSocket 在线会话</div>
        </div>
      </div>

      <div class="kpi-card">
        <div class="kpi-card__tile kpi-card__tile--green">
          <el-icon :size="22"><Files /></el-icon>
        </div>
        <div class="kpi-card__body">
          <div class="kpi-card__num kpi-card__num--sm">
            <span>{{ status.lastBackupTime || '—' }}</span>
          </div>
          <div class="kpi-card__label">最近备份</div>
          <div class="kpi-card__meta">数据备份目录最新文件</div>
        </div>
      </div>
    </div>

    <!-- ===== 磁盘与队列卡 ===== -->
    <div v-if="status" class="detail-grid">
      <section class="glass-card detail-card tg-fade-up">
        <div class="section-title">
          <div class="section-title__left">
            <h3><el-icon class="section-title__ic" :size="17"><Odometer /></el-icon>磁盘占用</h3>
            <p>上传存储目录所在盘</p>
          </div>
          <span class="live-badge">{{ status.diskUsedPct >= 0 ? '已用 ' + status.diskUsedPct + '%' : '未知' }}</span>
        </div>
        <template v-if="status.diskUsedPct >= 0">
          <div class="disk-track">
            <i :style="{ width: status.diskUsedPct + '%' }" :class="{ 'is-high': status.diskUsedPct >= 80 }"></i>
          </div>
          <div class="disk-meta">
            <span>剩余 {{ status.diskFreeMb }} MB</span>
            <span>共 {{ status.diskTotalMb }} MB</span>
          </div>
        </template>
        <div v-else class="detail-empty">无法读取磁盘信息</div>
      </section>

      <section class="glass-card detail-card tg-fade-up">
        <div class="section-title">
          <div class="section-title__left">
            <h3><el-icon class="section-title__ic" :size="17"><Clock /></el-icon>分析任务队列</h3>
            <p>运行中 / 已暂停</p>
          </div>
        </div>
        <div class="queue-row">
          <div class="queue-cell">
            <b class="queue-cell__num">{{ status.runningTasks }}</b>
            <span class="queue-cell__label">运行中</span>
          </div>
          <div class="queue-cell">
            <b class="queue-cell__num">{{ status.pausedTasks }}</b>
            <span class="queue-cell__label">已暂停</span>
          </div>
          <div class="queue-cell">
            <b class="queue-cell__num">{{ status.wsOnline }}</b>
            <span class="queue-cell__label">实时会话</span>
          </div>
        </div>
        <p class="detail-tip">任务超过阈值会被熔断保护，服务重启后由恢复任务自动续跑（详见分析服务）。</p>
      </section>
    </div>

    <div v-else class="tg-empty" v-loading="loading">加载中…</div>
  </div>
</template>

<script setup>
/**
 * W2-07 / R11：系统运行状态页
 * 复用 /dashboard/system-status 聚合接口（数据库/磁盘/队列/WS 在线/最近备份）。
 */
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { dashboardApi } from '@/api'

const status = ref(null)
const loading = ref(false)

const load = async () => {
  loading.value = true
  try {
    status.value = await dashboardApi.systemStatus()
  } catch (e) {
    ElMessage.error(e.message || '加载系统状态失败')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.sys-status {
  max-width: 1200px;
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 24px;
}

.detail-card {
  padding: 24px;
}

.disk-track {
  height: 8px;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.06);
  overflow: hidden;
  margin-top: 8px;
}

.disk-track i {
  display: block;
  height: 100%;
  border-radius: 999px;
  background: var(--tg-accent-gradient);
  transform-origin: left;
  animation: disk-grow 0.9s var(--tg-ease) both;
}

.disk-track i.is-high {
  background: linear-gradient(90deg, var(--tg-warning), var(--tg-danger));
}

@keyframes disk-grow {
  from { transform: scaleX(0); }
  to { transform: scaleX(1); }
}

.disk-meta {
  display: flex;
  justify-content: space-between;
  margin-top: 10px;
  font-size: 12.5px;
  color: var(--tg-text-secondary);
  font-variant-numeric: tabular-nums;
}

.queue-row {
  display: flex;
  gap: 12px;
  margin-top: 4px;
}

.queue-cell {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 14px 10px;
  border-radius: 16px;
  background: var(--tg-gradient-soft);
  border: 1px solid var(--tg-border);
}

.queue-cell__num {
  font-size: 26px;
  font-weight: 700;
  color: var(--tg-accent);
  font-variant-numeric: tabular-nums;
}

.queue-cell__label {
  font-size: 12px;
  color: var(--tg-text-secondary);
}

.detail-tip {
  margin: 14px 0 0;
  font-size: 12px;
  line-height: 1.7;
  color: var(--tg-slate);
}

.detail-empty {
  padding: 20px 0;
  text-align: center;
  color: var(--tg-text-secondary);
  font-size: 13px;
}

@media (max-width: 1100px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>