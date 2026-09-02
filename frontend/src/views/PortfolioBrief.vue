<template>
  <div class="page pb-page" v-loading="loading">
    <!-- 杂志报头：双细线 + 衬线刊名 + 期号（全站唯一报刊版式） -->
    <header class="pb-masthead">
      <div class="pb-masthead__rule" aria-hidden="true"></div>
      <div class="pb-masthead__row">
        <div class="pb-masthead__left">
          <span class="pb-masthead__kicker">TRACEGUARD QUALITY GAZETTE</span>
          <h1 class="pb-masthead__name">组合质量简报</h1>
        </div>
        <div class="pb-masthead__right">
          <span class="pb-masthead__date">{{ issueDate }}</span>
          <span class="pb-masthead__issue">据 {{ totals.projects }} 个在管项目汇编</span>
        </div>
      </div>
      <div class="pb-masthead__rule pb-masthead__rule--thick" aria-hidden="true"></div>
    </header>

    <!-- 头版要闻：巨号平均覆盖率 + 三栏竖线分隔统计 -->
    <section class="pb-hero">
      <div class="pb-hero__main">
        <span class="pb-hero__label">平均需求覆盖率</span>
        <div class="pb-hero__figure">
          <b>{{ Math.round(totals.avgCoverage * 1000) / 10 }}</b><i>%</i>
          <svg class="pb-hero__spark" viewBox="0 0 120 36" aria-hidden="true">
            <defs>
              <linearGradient id="pbSpark" x1="0" y1="0" x2="1" y2="0">
                <stop offset="0" stop-color="#8F6B22" /><stop offset="1" stop-color="#E8C877" />
              </linearGradient>
              <linearGradient id="pbSparkFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0" stop-color="rgba(201, 155, 63, 0.28)" /><stop offset="1" stop-color="rgba(201, 155, 63, 0)" />
              </linearGradient>
            </defs>
            <polygon :points="sparkArea" fill="url(#pbSparkFill)" />
            <polyline :points="sparkPoints" fill="none" stroke="url(#pbSpark)" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round" />
          </svg>
        </div>
        <p class="pb-hero__note">按已分析项目的最新覆盖率算术平均</p>
      </div>
      <div class="pb-hero__cells">
        <div class="pb-hero__cell"><b>{{ totals.projects }}</b><span>在管项目</span></div>
        <div class="pb-hero__cell"><b :class="{ 'is-warn': totals.totalOpen > 0 }">{{ totals.totalOpen }}</b><span>未决缺陷</span></div>
        <div class="pb-hero__cell"><b>{{ totals.totalDefects }}</b><span>累计缺陷</span></div>
      </div>
    </section>

    <!-- 索引账：点线引导的项目行（区别于表格） -->
    <section class="pb-ledger">
      <h2 class="pb-section-title">项目索引 <span class="pb-section-title__en">PROJECT LEDGER</span></h2>
      <div class="pb-ledger__rows">
        <button v-for="(p, i) in rows" :key="p.id" type="button" class="pb-row" @click="goProject(p.id)">
          <span class="pb-row__idx">{{ String(i + 1).padStart(2, '0') }}</span>
          <span class="pb-row__name">
            <b>{{ p.projectName }}</b>
            <small>{{ p.industryType || '未分类' }} · {{ p.requirementCount || 0 }} 条需求</small>
          </span>
          <span class="pb-row__leader" aria-hidden="true"></span>
          <span class="pb-row__metric">
            <small>覆盖</small>
            <em>{{ p.coverageRate == null ? '—' : Math.round(p.coverageRate * 100) + '%' }}</em>
            <i class="pb-row__covbar"><span :style="{ width: covPct(p) + '%' }" :class="{ 'is-none': p.coverageRate == null }"></span></i>
          </span>
          <span class="pb-row__metric pb-row__metric--num">
            <small>缺陷</small><em>{{ p.defectCount ?? 0 }}</em>
          </span>
          <span class="pb-row__open" :class="{ 'is-open': p.openDefects > 0 }">
            {{ p.openDefects > 0 ? `未决 ${p.openDefects}` : '已清零' }}
            <i v-if="p.seriousOpen > 0" class="pb-row__serious">严重 {{ p.seriousOpen }}</i>
          </span>
        </button>
        <p v-if="!rows.length" class="pb-empty">暂无可见项目</p>
      </div>
    </section>

    <!-- 近期动态：竖向时间线 -->
    <section class="pb-timeline-sec">
      <h2 class="pb-section-title">近期动态 <span class="pb-section-title__en">RECENT EVENTS</span></h2>
      <ol class="pb-timeline">
        <li v-for="(a, i) in activities" :key="i" class="pb-timeline__item">
          <i class="pb-timeline__dot" :class="{ 'is-fail': a.success === false }" aria-hidden="true"></i>
          <div class="pb-timeline__body">
            <p class="pb-timeline__text"><b>{{ a.username }}</b> {{ briefOp(a.operation) }}</p>
            <time class="pb-timeline__time">{{ a.createTime }}</time>
          </div>
        </li>
        <li v-if="!activities.length" class="pb-empty">暂无动态</li>
      </ol>
    </section>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { insightApi, dashboardApi } from '@/api'

const router = useRouter()
const loading = ref(false)
const totals = ref({ projects: 0, avgCoverage: 0, totalOpen: 0, totalDefects: 0 })
const rows = ref([])
const activities = ref([])

const issueDate = computed(() => {
  const d = new Date()
  return `${d.getFullYear()} 年 ${d.getMonth() + 1} 月 ${d.getDate()} 日`
})
// 头版装饰折线：由项目覆盖率序列生成
const sparkPoints = computed(() => {
  const list = rows.value.filter(r => r.coverageRate != null).slice(0, 12)
  if (list.length < 2) return '0,30 120,6'
  const pts = list.map((r, i) => {
    const x = Math.round((i * 120) / (list.length - 1))
    const y = Math.round(32 - Math.min(1, r.coverageRate) * 28)
    return `${x},${y}`
  })
  return pts.join(' ')
})
const sparkArea = computed(() => {
  const pts = sparkPoints.value
  if (!pts.includes(',')) return pts
  return pts + ' 120,36 0,36'
})
const covPct = (p) => (p.coverageRate == null ? 0 : Math.round(Math.min(1, p.coverageRate) * 100))
const briefOp = (op) => {
  const s = String(op || '')
  return s.length > 46 ? s.slice(0, 46) + '…' : s
}
const goProject = (id) => router.push(`/project/${id}`)

onMounted(async () => {
  loading.value = true
  try {
    const [pf, acts] = await Promise.all([
      insightApi.portfolio(),
      dashboardApi.activities(14).catch(() => [])
    ])
    totals.value = pf.totals || totals.value
    rows.value = pf.projects || []
    activities.value = acts || []
  } catch (e) {
    ElMessage.error(e?.message || '加载组合简报失败')
  } finally {
    loading.value = false
  }
})
</script>

<style scoped>
/* ===== 报刊版式：双线报头 + 巨号头版 + 点线索引账 + 竖向时间线 ===== */
.pb-page { display: flex; flex-direction: column; gap: 30px; max-width: 1080px; }

.pb-masthead__rule { height: 1px; background: var(--tg-text-primary); opacity: 0.55; }
.pb-masthead__rule--thick { height: 3px; }
.pb-masthead__row { display: flex; align-items: flex-end; justify-content: space-between; gap: 16px; padding: 14px 2px; flex-wrap: wrap; }
.pb-masthead__kicker { font-size: 10.5px; letter-spacing: 0.32em; color: var(--tg-slate); font-weight: 600; }
.pb-masthead__name {
  margin: 4px 0 0; font-family: Georgia, 'Times New Roman', 'Songti SC', SimSun, serif;
  font-size: 40px; font-weight: 700; letter-spacing: 0.06em; color: var(--tg-text-primary); line-height: 1.1;
}
.pb-masthead__right { text-align: right; display: flex; flex-direction: column; gap: 3px; }
.pb-masthead__date { font-size: 13px; font-weight: 600; color: var(--tg-text-primary); font-variant-numeric: tabular-nums; }
.pb-masthead__issue { font-size: 11.5px; color: var(--tg-slate); }

/* 头版要闻：金斑+橄榄+蜜桃组合光晕（与趋势页蓝金组合区分） */
.pb-hero {
  display: flex; align-items: stretch; gap: 30px; padding: 26px 30px;
  border-radius: 24px;
  background:
    radial-gradient(130% 190% at 0% 0%, rgba(232, 200, 119, 0.32), transparent 52%),
    radial-gradient(120% 170% at 100% 0%, rgba(168, 185, 138, 0.2), transparent 55%),
    radial-gradient(100% 150% at 50% 115%, rgba(217, 169, 102, 0.18), transparent 58%),
    linear-gradient(160deg, rgba(255, 250, 240, 0.96), rgba(255, 246, 230, 0.7));
  border: 1px solid rgba(201, 155, 63, 0.22);
  box-shadow: var(--tg-shadow-card);
}
.pb-hero__main { flex: 1; }
.pb-hero__label { font-size: 12px; letter-spacing: 0.12em; color: var(--tg-text-secondary); }
.pb-hero__figure { display: flex; align-items: flex-end; gap: 4px; }
.pb-hero__figure b {
  font-size: 84px; font-weight: 700; line-height: 0.95; letter-spacing: -0.03em;
  background: var(--tg-accent-gradient); -webkit-background-clip: text; background-clip: text; color: transparent;
  font-variant-numeric: tabular-nums;
}
.pb-hero__figure i { font-style: normal; font-size: 26px; font-weight: 700; color: var(--tg-gold); padding-bottom: 10px; }
.pb-hero__spark { width: 120px; height: 36px; margin-left: 18px; margin-bottom: 10px; opacity: 0.9; }
.pb-hero__note { margin: 8px 0 0; font-size: 11.5px; color: var(--tg-slate); }
.pb-hero__cells { display: flex; gap: 0; align-items: stretch; }
.pb-hero__cell {
  display: flex; flex-direction: column; justify-content: center; gap: 4px;
  padding: 0 26px; border-left: 1px solid var(--tg-border); min-width: 118px;
}
.pb-hero__cell b { font-size: 30px; font-weight: 700; color: var(--tg-text-primary); line-height: 1; font-variant-numeric: tabular-nums; }
.pb-hero__cell b.is-warn { color: var(--tg-danger); }
.pb-hero__cell span { font-size: 11.5px; color: var(--tg-text-secondary); }

/* 章节标题（衬线小型大写英文陪衬） */
.pb-section-title {
  margin: 0 0 14px; font-size: 17px; font-weight: 700; color: var(--tg-text-primary);
  display: flex; align-items: baseline; gap: 10px;
}
.pb-section-title__en { font-family: Georgia, serif; font-size: 10.5px; font-weight: 400; letter-spacing: 0.22em; color: var(--tg-slate); }

/* 点线索引账 */
.pb-ledger__rows { display: flex; flex-direction: column; }
.pb-row {
  display: flex; align-items: center; gap: 14px; width: 100%;
  padding: 13px 10px; border: none; background: transparent; cursor: pointer; text-align: left;
  border-bottom: 1px dashed var(--tg-border); transition: background 0.2s ease;
}
.pb-row:hover { background: rgba(201, 155, 63, 0.06); }
.pb-row__idx { font-family: var(--tg-font-mono); font-size: 12px; color: var(--tg-slate); width: 24px; flex-shrink: 0; }
.pb-row__name { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
.pb-row__name b { font-size: 14px; font-weight: 600; color: var(--tg-text-primary); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.pb-row__name small { font-size: 11px; color: var(--tg-slate); }
.pb-row__leader { flex: 1; min-width: 24px; border-bottom: 1px dotted rgba(60, 45, 25, 0.22); align-self: center; height: 1px; }
.pb-row__metric { display: flex; align-items: center; gap: 8px; flex-shrink: 0; }
.pb-row__metric small { font-size: 10.5px; color: var(--tg-slate); }
.pb-row__metric em { font-style: normal; font-size: 13px; font-weight: 600; color: var(--tg-text-primary); font-variant-numeric: tabular-nums; }
.pb-row__covbar { width: 72px; height: 5px; border-radius: 999px; background: rgba(201, 155, 63, 0.12); overflow: hidden; }
.pb-row__covbar span { display: block; height: 100%; border-radius: 999px; background: var(--tg-accent-gradient); }
.pb-row__covbar span.is-none { background: rgba(0, 0, 0, 0.08); }
.pb-row__open {
  flex-shrink: 0; display: inline-flex; align-items: center; gap: 6px;
  font-size: 11px; font-weight: 600; padding: 3px 11px; border-radius: 999px;
  background: rgba(107, 142, 78, 0.12); color: #55682e; font-variant-numeric: tabular-nums;
}
.pb-row__open.is-open { background: rgba(194, 94, 76, 0.1); color: #9a3f30; }
.pb-row__serious { font-style: normal; font-size: 10px; padding: 0 6px; border-radius: 999px; background: rgba(194, 94, 76, 0.16); }

/* 竖向时间线 */
.pb-timeline { list-style: none; margin: 0; padding: 4px 0 0 6px; position: relative; }
.pb-timeline::before { content: ''; position: absolute; left: 11px; top: 10px; bottom: 10px; width: 1.5px; background: var(--tg-border); }
.pb-timeline__item { position: relative; padding: 0 0 16px 30px; }
.pb-timeline__dot {
  position: absolute; left: 7px; top: 4px; width: 9px; height: 9px; border-radius: 50%;
  background: var(--tg-gold); box-shadow: 0 0 0 3px rgba(201, 155, 63, 0.15);
}
.pb-timeline__dot.is-fail { background: var(--tg-danger); box-shadow: 0 0 0 3px rgba(194, 94, 76, 0.15); }
.pb-timeline__text { margin: 0; font-size: 13px; color: var(--tg-text-secondary); }
.pb-timeline__text b { color: var(--tg-text-primary); font-weight: 600; }
.pb-timeline__time { font-size: 11px; color: var(--tg-slate); font-variant-numeric: tabular-nums; }

.pb-empty { margin: 12px 0; font-size: 12.5px; color: var(--tg-slate); text-align: center; }

@media (max-width: 860px) {
  .pb-hero { flex-direction: column; gap: 20px; }
  .pb-hero__cells { border-top: 1px solid var(--tg-border); padding-top: 16px; }
  .pb-hero__cell:first-child { border-left: none; padding-left: 0; }
  .pb-row__metric--num, .pb-row__leader { display: none; }
}
</style>
