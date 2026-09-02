<template>
  <div class="page dp-page">
    <!-- 图鉴册页头：左标题 + 右收录进度印章 -->
    <header class="dp-head">
      <div>
        <h1 class="dp-title">缺陷模式图鉴</h1>
        <p class="dp-sub">内置代码检测规则 × 真实命中数据 · 点击卡片查看最近命中</p>
      </div>
      <div class="dp-progress">
        <div class="dp-progress__seal">
          <b>{{ patterns.length }}</b>
          <span>已收录类型</span>
        </div>
        <div class="dp-progress__seal dp-progress__seal--hit">
          <b>{{ totalHits }}</b>
          <span>累计命中</span>
        </div>
      </div>
    </header>

    <!-- 命中分布带：100% 堆叠占比（纯 CSS，各类型主题色循环） -->
    <section v-if="patterns.length" class="dp-band">
      <div class="dp-band__track">
        <span v-for="(p, i) in patterns" :key="p.type" class="dp-band__seg"
          :style="{ width: bandPct(p.hits) + '%', background: BAND_COLORS[i % BAND_COLORS.length] }"
          :title="`${p.name} · ${p.hits} 次（${bandPct(p.hits)}%）`"></span>
      </div>
      <div class="dp-band__legend">
        <span v-for="(p, i) in patterns" :key="'k' + p.type" class="dp-band__key">
          <i :style="{ background: BAND_COLORS[i % BAND_COLORS.length] }" aria-hidden="true"></i>{{ p.name }} {{ bandPct(p.hits) }}%
        </span>
      </div>
    </section>

    <!-- 卡牌墙：severity 色带 + 命中数 + 严重占比微条 + 悬停取卡 -->
    <section class="dp-wall" v-loading="loading">
      <button v-for="p in patterns" :key="p.type" type="button" class="dp-card" :class="'is-' + p.severity" @click="openCard(p)">
        <i class="dp-card__band" aria-hidden="true"></i>
        <div class="dp-card__head">
          <h3 class="dp-card__name">{{ p.name }}</h3>
          <code class="dp-card__key">{{ p.type }}</code>
        </div>
        <p class="dp-card__desc">{{ p.desc }}</p>
        <div class="dp-card__hits">
          <div class="dp-card__hitsnum">
            <b>{{ p.hits }}</b><span>次命中</span>
          </div>
          <div class="dp-card__split" :title="`严重 ${p.seriousHits} / 一般 ${p.hits - p.seriousHits}`">
            <i :style="{ width: seriousPct(p) + '%' }"></i>
          </div>
          <span class="dp-card__splitlabel">严重 {{ seriousPct(p) }}%</span>
        </div>
        <footer class="dp-card__foot">
          <span class="dp-card__advice">{{ p.advice }}</span>
          <span v-if="p.example" class="dp-card__file">{{ shortFile(p.example.filePath) }}<template v-if="p.example.lineNumber">:{{ p.example.lineNumber }}</template></span>
        </footer>
      </button>
      <div v-if="!loading && !patterns.length" class="dp-empty">
        <p>还没有代码缺陷记录</p>
        <small>执行一次项目分析后，检测规则的命中图鉴会在这里生成</small>
      </div>
    </section>

    <!-- 卡牌放大镜 -->
    <el-dialog v-model="dialog" width="560px" :title="current ? current.name : '模式详情'">
      <div v-if="current" class="dp-detail">
        <div class="dp-detail__chips">
          <span class="dp-detail__chip" :class="'is-' + current.severity">{{ current.severity === 'serious' ? '严重规则' : '一般规则' }}</span>
          <code class="dp-detail__type">{{ current.type }}</code>
          <span class="dp-detail__hits">命中 {{ current.hits }} 次</span>
        </div>
        <p class="dp-detail__desc">{{ current.desc }}</p>
        <div class="dp-detail__advice">
          <h4>修复建议</h4>
          <p>{{ current.advice }}</p>
        </div>
        <div v-if="current.example" class="dp-detail__example">
          <h4>最近命中</h4>
          <code class="dp-detail__file">{{ current.example.filePath }}<template v-if="current.example.methodName"> · {{ current.example.methodName }}()</template><template v-if="current.example.lineNumber"> :{{ current.example.lineNumber }}</template></code>
          <p v-if="current.example.description">{{ current.example.description }}</p>
        </div>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { insightApi } from '@/api'

const loading = ref(false)
const patterns = ref([])
const totalHits = ref(0)
const dialog = ref(false)
const current = ref(null)

const seriousPct = (p) => (p.hits ? Math.round((p.seriousHits * 100) / p.hits) : 0)

// 命中分布带：主题色循环（金/蜜糖/奶油/橄榄/陶土/蓝灰/藤紫）
const BAND_COLORS = ['#8F6B22', '#C99B3F', '#E8C877', '#9A9C6B', '#C25E4C', '#6E93B0', '#9A7FB0']
const bandPct = (hits) => (totalHits.value ? Math.round((hits * 100) / totalHits.value) : 0)
const shortFile = (f) => {
  if (!f) return ''
  const parts = String(f).split(/[\\/]/)
  return parts.length > 2 ? '…/' + parts.slice(-2).join('/') : f
}
const openCard = (p) => { current.value = p; dialog.value = true }

onMounted(async () => {
  loading.value = true
  try {
    const data = await insightApi.patterns()
    patterns.value = data.patterns || []
    totalHits.value = data.totalHits || 0
  } catch (e) {
    ElMessage.error(e?.message || '加载缺陷模式库失败')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
/* ===== 图鉴卡牌墙：severity 色带卡 + 悬停取卡微倾斜（区别于表格/看板/报刊） ===== */
.dp-page { display: flex; flex-direction: column; gap: 24px; }

.dp-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 18px; flex-wrap: wrap; }
.dp-title { margin: 0; font-size: 26px; font-weight: 700; letter-spacing: -0.02em; color: var(--tg-text-primary); }
.dp-sub { margin: 6px 0 0; font-size: 13px; color: var(--tg-text-secondary); }
.dp-progress { display: flex; gap: 12px; }
.dp-progress__seal {
  display: flex; flex-direction: column; align-items: center; gap: 2px;
  padding: 12px 20px; border-radius: 16px;
  border: 1.5px dashed rgba(201, 155, 63, 0.45); background: rgba(201, 155, 63, 0.07);
}
.dp-progress__seal b { font-size: 22px; font-weight: 700; color: var(--tg-accent); line-height: 1; font-variant-numeric: tabular-nums; }
.dp-progress__seal span { font-size: 10.5px; color: var(--tg-text-secondary); }
.dp-progress__seal--hit { border-color: rgba(194, 94, 76, 0.4); background: rgba(194, 94, 76, 0.06); }
.dp-progress__seal--hit b { color: var(--tg-danger); }

.dp-wall { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 18px; min-height: 200px; }
.dp-card {
  position: relative; overflow: hidden; text-align: left; cursor: pointer;
  display: flex; flex-direction: column; gap: 10px;
  padding: 20px 20px 16px; border-radius: 18px;
  background: var(--tg-card-highlight); border: 1px solid rgba(255, 255, 255, 0.72);
  box-shadow: var(--tg-shadow-card);
  transition: transform 0.3s var(--tg-ease-spring), box-shadow 0.3s ease, border-color 0.25s ease;
}
.dp-card:hover {
  transform: translateY(-5px) rotate(-0.6deg);
  border-color: rgba(201, 155, 63, 0.42);
  box-shadow: 0 20px 44px rgba(60, 45, 25, 0.14);
}
.dp-card__band { position: absolute; top: 0; left: 0; right: 0; height: 4px; }
.dp-card.is-serious .dp-card__band { background: linear-gradient(90deg, #C25E4C, #D97A66); }
.dp-card.is-general .dp-card__band { background: linear-gradient(90deg, #9A9C6B, #A8B98A); }
.dp-card__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.dp-card__name { margin: 0; font-size: 17px; font-weight: 700; color: var(--tg-text-primary); }
.dp-card__key {
  flex-shrink: 0; font-family: var(--tg-font-mono); font-size: 10px;
  padding: 2px 8px; border-radius: 6px; background: rgba(0, 0, 0, 0.055); color: var(--tg-text-secondary);
  max-width: 120px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}
.dp-card__desc { margin: 0; font-size: 12.5px; line-height: 1.6; color: var(--tg-text-secondary); min-height: 40px; }
.dp-card__hits { display: flex; align-items: center; gap: 12px; padding: 10px 12px; border-radius: 12px; background: rgba(0, 0, 0, 0.03); }
.dp-card__hitsnum { display: flex; align-items: baseline; gap: 4px; }
.dp-card__hitsnum b { font-size: 22px; font-weight: 700; color: var(--tg-text-primary); line-height: 1; font-variant-numeric: tabular-nums; }
.dp-card__hitsnum span { font-size: 10.5px; color: var(--tg-slate); }
.dp-card__split { flex: 1; height: 6px; border-radius: 999px; background: rgba(154, 156, 107, 0.2); overflow: hidden; }
.dp-card__split i { display: block; height: 100%; border-radius: 999px; background: linear-gradient(90deg, #C25E4C, #D97A66); transition: width 0.6s var(--tg-ease); }
.dp-card__splitlabel { flex-shrink: 0; font-size: 10.5px; color: var(--tg-text-secondary); font-variant-numeric: tabular-nums; }
.dp-card__foot { display: flex; flex-direction: column; gap: 4px; }
.dp-card__advice { font-size: 11.5px; line-height: 1.55; color: var(--tg-accent); }
.dp-card__file {
  font-family: var(--tg-font-mono); font-size: 10.5px; color: var(--tg-slate);
  white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
}

.dp-empty {
  grid-column: 1 / -1; display: flex; flex-direction: column; align-items: center; gap: 6px;
  padding: 60px 0; text-align: center;
}
.dp-empty p { margin: 0; font-size: 15px; font-weight: 600; color: var(--tg-text-secondary); }
.dp-empty small { font-size: 12px; color: var(--tg-slate); }

.dp-detail { display: flex; flex-direction: column; gap: 14px; }
.dp-detail__chips { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.dp-detail__chip { font-size: 11.5px; font-weight: 600; padding: 2px 10px; border-radius: 999px; }
.dp-detail__chip.is-serious { background: rgba(194, 94, 76, 0.12); color: #9a3f30; }
.dp-detail__chip.is-general { background: rgba(154, 156, 107, 0.16); color: #55682e; }
.dp-detail__type { font-family: var(--tg-font-mono); font-size: 11px; background: rgba(0, 0, 0, 0.055); padding: 2px 8px; border-radius: 6px; color: var(--tg-text-secondary); }
.dp-detail__hits { font-size: 12px; color: var(--tg-text-secondary); font-variant-numeric: tabular-nums; }
.dp-detail__desc { margin: 0; font-size: 13.5px; line-height: 1.7; color: var(--tg-text-primary); }
.dp-detail__advice h4, .dp-detail__example h4 { margin: 0 0 6px; font-size: 12px; color: var(--tg-text-secondary); }
.dp-detail__advice p { margin: 0; font-size: 13px; line-height: 1.65; color: var(--tg-accent); background: var(--el-color-primary-light-9); border-radius: 10px; padding: 10px 12px; }
.dp-detail__file { display: block; font-family: var(--tg-font-mono); font-size: 11.5px; color: var(--tg-text-secondary); background: var(--tg-bg-code); border-radius: 10px; padding: 10px 12px; word-break: break-all; }
.dp-detail__example p { margin: 8px 0 0; font-size: 12.5px; line-height: 1.6; color: var(--tg-text-primary); }
/* 命中分布带 */
.dp-band { display: flex; flex-direction: column; gap: 9px; padding: 15px 18px; border-radius: 16px; background: rgba(255, 255, 255, 0.55); border: 1px solid var(--tg-border); }
.dp-band__track { display: flex; height: 14px; border-radius: 999px; overflow: hidden; background: rgba(0, 0, 0, 0.04); }
.dp-band__seg { display: block; height: 100%; transition: width 0.6s var(--tg-ease), filter 0.2s ease; }
.dp-band__seg:hover { filter: brightness(1.12); }
.dp-band__legend { display: flex; flex-wrap: wrap; gap: 5px 16px; }
.dp-band__key { display: inline-flex; align-items: center; gap: 6px; font-size: 11px; color: var(--tg-text-secondary); }
.dp-band__key i { width: 8px; height: 8px; border-radius: 3px; }
</style>