<template>
  <div ref="rootEl" class="ic" :class="{ 'ic--on': stage >= 1, 'ic--compact': props.compact }" aria-hidden="true">
    <div class="ic__win">
      <!-- 顶栏:工作区 / 代理名 / 状态 / 运行按钮 / 工具图标 -->
      <div class="ic__bar">
        <div class="ic__ws">
          <span class="ic__ws-logo">TG</span>
          <b>order-system</b>
          <svg width="10" height="10" viewBox="0 0 10 10" fill="none"><path d="M2.5 4l2.5 2.5L7.5 4" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" /></svg>
          <svg class="ic__ws-panel" width="13" height="13" viewBox="0 0 14 14" fill="none"><rect x="1.5" y="2.5" width="11" height="9" rx="1.6" stroke="currentColor" stroke-width="1.2" /><line x1="5.6" y1="2.5" x2="5.6" y2="11.5" stroke="currentColor" stroke-width="1.2" /></svg>
        </div>
        <div class="ic__meta">
          <span class="ic__dot-logo"></span>
          <b>Consistency Verification Agent</b>
          <span class="ic__link-ic">
            <svg width="12" height="12" viewBox="0 0 14 14" fill="none"><path d="M5.8 8.2a2.6 2.6 0 0 0 3.7 0l2-2a2.6 2.6 0 1 0-3.7-3.7l-.9.9" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" /><path d="M8.2 5.8a2.6 2.6 0 0 0-3.7 0l-2 2a2.6 2.6 0 1 0 3.7 3.7l.9-.9" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" /></svg>
          </span>
          <em class="ic__tag">Multi-Engine</em>
        </div>
        <div class="ic__state">
          <span class="ic__draft">
            <svg width="11" height="11" viewBox="0 0 14 14" fill="none"><path d="M9.7 2.1l2.2 2.2L5 11.2l-2.9.7.7-2.9 6.9-6.9z" stroke="currentColor" stroke-width="1.2" stroke-linejoin="round" /></svg>
            draft
          </span>
          <span class="ic__upd"><i></i>{{ updatedText }}</span>
        </div>
        <button class="ic__run" type="button" tabindex="-1">
          <svg width="11" height="11" viewBox="0 0 14 14" fill="none"><path d="M4.5 7.6l1.9 1.9 3.6-4" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" stroke-linejoin="round" /><circle cx="7" cy="7" r="5.6" stroke="currentColor" stroke-width="1.2" /></svg>
          Run Check
          <svg width="10" height="10" viewBox="0 0 10 10" fill="none"><path d="M2.5 4l2.5 2.5L7.5 4" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" /></svg>
        </button>
        <div class="ic__tools">
          <svg width="13" height="13" viewBox="0 0 14 14" fill="none"><path d="M7 3.5v7M3.5 7h7" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" /><path d="M4.8 2.2h4.4a1 1 0 0 1 1 1v7.6a1 1 0 0 1-1 1H4.8a1 1 0 0 1-1-1V3.2a1 1 0 0 1 1-1z" stroke="currentColor" stroke-width="1.1" /></svg>
          <svg width="13" height="13" viewBox="0 0 14 14" fill="none"><path d="M11.5 7a4.5 4.5 0 1 1-1.3-3.2M11.5 2.6v2.2H9.3" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" /></svg>
          <svg width="13" height="13" viewBox="0 0 14 14" fill="none"><path d="M7 1.8l1.2 3 3 1.2-3 1.2L7 10.2 5.8 7.2l-3-1.2 3-1.2L7 1.8zM11.2 9.4l.6 1.4 1.4.6-1.4.6-.6 1.4-.6-1.4-1.4-.6 1.4-.6.6-1.4z" stroke="currentColor" stroke-width="1.1" stroke-linejoin="round" /></svg>
        </div>
      </div>

      <div class="ic__body">
        <!-- 左侧栏:搜索 / 导航 / 场景树 / 支持文档 -->
        <aside class="ic__side">
          <div class="ic__search">
            <svg width="12" height="12" viewBox="0 0 14 14" fill="none"><circle cx="6" cy="6" r="4.4" stroke="currentColor" stroke-width="1.4" /><path d="M9.4 9.4L12.6 12.6" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" /></svg>
            Search...
            <span class="ic__kbd">⌘ K</span>
          </div>
          <div class="ic__nav">
            <span class="ic__nav-item ic__nav-item--on">
              <i class="ic__nav-ic">
                <svg width="12" height="12" viewBox="0 0 14 14" fill="none"><rect x="2.2" y="1.8" width="9.6" height="10.4" rx="1.8" stroke="currentColor" stroke-width="1.2" /><path d="M4.8 4.6h4.4M4.8 7h4.4M4.8 9.4h2.6" stroke="currentColor" stroke-width="1.15" stroke-linecap="round" /></svg>
              </i>Requirements
            </span>
            <span v-for="n in navs" :key="n.t" class="ic__nav-item">
              <i class="ic__nav-ic" v-html="n.i"></i>{{ n.t }}
            </span>
          </div>
          <div class="ic__group">
            <span class="ic__group-h">
              <i class="ic__fold">
                <svg width="10" height="10" viewBox="0 0 10 10" fill="none"><path d="M2 6.2L5 3.4l3 2.8" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" /></svg>
              </i>Scenarios
            </span>
            <span class="ic__tree-item ic__tree-item--on">
              <i class="ic__t-ic ic__t-ic--gold">
                <svg width="9" height="9" viewBox="0 0 10 10" fill="none"><path d="M2.2 5.2l1.9 1.9 3.7-4" stroke="currentColor" stroke-width="1.4" stroke-linecap="round" stroke-linejoin="round" /></svg>
              </i>Null-Guard Consistency
              <em>···</em>
            </span>
            <span class="ic__tree-item" v-for="s in scenes" :key="s">
              <i class="ic__t-ic">
                <svg width="9" height="9" viewBox="0 0 10 10" fill="none"><path d="M2.6 1.4h3.4l2 2v5.2h-5.4V1.4z" stroke="currentColor" stroke-width="1.1" stroke-linejoin="round" /></svg>
              </i>{{ s }}
            </span>
          </div>
          <div class="ic__group">
            <span class="ic__group-h">
              <i class="ic__fold">
                <svg width="10" height="10" viewBox="0 0 10 10" fill="none"><path d="M2 6.2L5 3.4l3 2.8" stroke="currentColor" stroke-width="1.3" stroke-linecap="round" stroke-linejoin="round" /></svg>
              </i>Supporting Docs
            </span>
            <span class="ic__tree-item">
              <i class="ic__t-ic ic__t-ic--doc"></i>requirements-spec.docx
            </span>
          </div>
        </aside>

        <!-- 主编辑器:场景标题(打字) + 规约块逐条浮现 -->
        <div class="ic__main">
          <h3 class="ic__title">{{ typedTitle }}<span class="ic__caret" v-if="!stage"></span></h3>

          <div class="ic__block ic__d1">
            <span class="ic__node"></span>
            <div class="ic__block-body">
              <b>Scenario 1: "Order Creation" Inquiry<span class="ic__caret ic__caret--sm" v-if="stage"></span></b>
              <div class="ic__say">
                <span class="ic__chip-label">
                  <i class="ic__chip-ic">
                    <svg width="10" height="10" viewBox="0 0 12 12" fill="none"><path d="M3.4 2.2h5.2a1.4 1.4 0 0 1 1.4 1.4v5.2a1.4 1.4 0 0 1-1.4 1.4H3.4a1.4 1.4 0 0 1-1.4-1.4V3.6a1.4 1.4 0 0 1 1.4-1.4z" stroke="currentColor" stroke-width="1.1" /><path d="M6 2.2v7.6" stroke="currentColor" stroke-width="1.1" /></svg>
                  </i>Spec
                </span>
                <p>"Batch import must reject null orders before processing. We validate the entry to prevent a NullPointerException at runtime."</p>
              </div>
              <div class="ic__cond">
                <span class="ic__arr">↳</span>
                <span>Static rule hit</span>
                <code class="ic__code ic__code--gold">NPE_RISK · :42</code>
                <span>proceed to</span>
                <span class="ic__to"><i class="ic__node ic__node--sm"></i>Defect List</span>
              </div>
              <div class="ic__cond">
                <span class="ic__arr">↳</span>
                <span>LLM confirms</span>
                <code class="ic__code ic__code--olive">severity · high</code>
                <span>proceed to</span>
                <span class="ic__to"><i class="ic__node ic__node--sm"></i>Fix Hints</span>
              </div>
            </div>
          </div>

          <div class="ic__block ic__d2">
            <span class="ic__node"></span>
            <div class="ic__block-body">
              <b>Scenario 2: Transaction Boundary</b>
              <div class="ic__say">
                <span class="ic__chip-label">
                  <i class="ic__chip-ic">
                    <svg width="10" height="10" viewBox="0 0 12 12" fill="none"><path d="M3.4 2.2h5.2a1.4 1.4 0 0 1 1.4 1.4v5.2a1.4 1.4 0 0 1-1.4 1.4H3.4a1.4 1.4 0 0 1-1.4-1.4V3.6a1.4 1.4 0 0 1 1.4-1.4z" stroke="currentColor" stroke-width="1.1" /><path d="M6 2.2v7.6" stroke="currentColor" stroke-width="1.1" /></svg>
                  </i>Spec
                </span>
                <p>"Order and inventory writes must share a single transaction. Partial commits would silently break data consistency."</p>
              </div>
              <div class="ic__cond">
                <span class="ic__arr">↳</span>
                <span>Call-graph mismatch</span>
                <code class="ic__code ic__code--clay">TX_MISSING · :88</code>
                <span>proceed to</span>
                <span class="ic__to"><i class="ic__node ic__node--sm"></i>Defect List</span>
              </div>
              <div class="ic__cond">
                <span class="ic__arr">↳</span>
                <span>Retry exceeded 2 times</span>
                <code class="ic__code ic__code--gold">@tx_rollback</code>
                <span>if still failing, proceed to</span>
                <span class="ic__to"><i class="ic__node ic__node--sm"></i>Escalation</span>
              </div>
            </div>
          </div>

          <div class="ic__block ic__d3">
            <span class="ic__node"></span>
            <div class="ic__block-body">
              <b>Scenario 3: Log Completeness</b>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
/**
 * IdeCanvas —— Agent-Canvas 式 IDE 编排器(全英文,暖金改色,借鉴 giga.ai/agent-canvas)
 * 结构:顶栏(工作区/代理名/状态/Run Check) + 左侧栏(搜索/导航/场景树/文档) + 场景编辑器
 * 动画:标题打字机 → 规约块逐条浮现(stagger) → 光标移至 Scenario 1 行闪烁 → 停留后循环重写
 */
import { ref, onMounted, onUnmounted } from 'vue'

const rootEl = ref(null)

/** compact:嵌于 walk 场景位的紧凑形态(隐藏侧栏,收紧排版) */
const props = defineProps({ compact: { type: Boolean, default: false } })

const TITLE = 'Scenario: Null-Guard Consistency'
const typedTitle = ref('')
const stage = ref(0)
const updatedText = ref('updated a min ago')

const navs = [
  { t: 'Code Model', i: '<svg width="12" height="12" viewBox="0 0 14 14" fill="none"><path d="M4.6 4.4L2 7l2.6 2.6M9.4 4.4L12 7l-2.6 2.6" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" stroke-linejoin="round" /></svg>' },
  { t: 'Specs · Alloy', i: '<svg width="12" height="12" viewBox="0 0 14 14" fill="none"><path d="M2.2 4h9.6M2.2 7h9.6M2.2 10h6.2" stroke="currentColor" stroke-width="1.25" stroke-linecap="round" /></svg>' },
  { t: 'Evaluation', i: '<svg width="12" height="12" viewBox="0 0 14 14" fill="none"><circle cx="7" cy="7" r="5.2" stroke="currentColor" stroke-width="1.2" /><circle cx="7" cy="7" r="2.2" stroke="currentColor" stroke-width="1.2" /></svg>' },
  { t: 'Advanced', i: '<svg width="12" height="12" viewBox="0 0 14 14" fill="none"><path d="M8.4 2.4a2.6 2.6 0 0 0-3.4 3.3L2.2 8.5a1.3 1.3 0 0 0 0 1.8l1.5 1.5a1.3 1.3 0 0 0 1.8 0l2.8-2.8a2.6 2.6 0 0 0 3.3-3.4L9.8 7.4 8.2 5.8l1.8-1.8-1.6-1.6z" stroke="currentColor" stroke-width="1.15" stroke-linejoin="round" /></svg>' }
]
const scenes = ['Transaction Boundary', 'Log Completeness', 'Access Control']

const reduced = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches
let stop = false
let timer = null
let played = false

const sleep = (ms) => new Promise((r) => (timer = setTimeout(r, ms)))

// 进场动画:首次进入视口播放一次,不循环
const play = async () => {
  if (reduced()) {
    typedTitle.value = TITLE
    stage.value = 1
    return
  }
  stage.value = 0
  typedTitle.value = ''
  await sleep(400)
  // 打字机
  for (let i = 1; i <= TITLE.length; i++) {
    if (stop) return
    typedTitle.value = TITLE.slice(0, i)
    await sleep(62)
  }
  // 规约块逐条浮现
  await sleep(320)
  stage.value = 1
  // 状态文案一次性更新
  await sleep(2800)
  if (stop) return
  updatedText.value = 'updated just now'
}

onMounted(() => {
  const io = new IntersectionObserver(
    ([entry]) => {
      if (entry.isIntersecting && !played) {
        played = true
        io.disconnect()
        play()
      }
    },
    { threshold: 0.3 }
  )
  if (rootEl.value) io.observe(rootEl.value)
})

onUnmounted(() => {
  stop = true
  if (timer) clearTimeout(timer)
})
</script>

<style scoped>
.ic {
  position: relative;
}

/* 中焙暖棕:比旧版明显更浅的 IDE 底色 */
.ic__win {
  border-radius: 18px;
  border: 1px solid rgba(232, 200, 119, 0.24);
  background: linear-gradient(180deg, #4c3f27, #40341f 62%, #463923);
  box-shadow: 0 34px 90px rgba(43, 30, 10, 0.3), inset 0 1px 0 rgba(232, 200, 119, 0.16);
  overflow: hidden;
}

/* ===== 顶栏 ===== */
.ic__bar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 11px 16px;
  border-bottom: 1px solid rgba(232, 200, 119, 0.14);
  background: rgba(255, 244, 220, 0.03);
}

.ic__ws {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #f5ebd8;
  font-size: 13px;
  flex-shrink: 0;
}

.ic__ws-logo {
  width: 24px;
  height: 24px;
  border-radius: 7px;
  background: linear-gradient(135deg, #8f6b22, #c99b3f);
  color: #fff;
  font-size: 10px;
  font-weight: 800;
  display: flex;
  align-items: center;
  justify-content: center;
}

.ic__ws b {
  font-weight: 650;
}

.ic__ws-panel {
  color: #a3957b;
}

.ic__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #f5ebd8;
  font-size: 13px;
  min-width: 0;
}

.ic__dot-logo {
  width: 15px;
  height: 15px;
  border-radius: 50%;
  background: radial-gradient(circle at 32% 30%, #f0d9a0, #c99b3f 55%, #8f6b22);
  box-shadow: 0 0 9px rgba(201, 155, 63, 0.75);
  flex-shrink: 0;
}

.ic__meta b {
  font-weight: 650;
  white-space: nowrap;
}

.ic__link-ic {
  color: #c99b3f;
  display: inline-flex;
}

.ic__tag {
  font-style: normal;
  font-family: var(--tg-font-mono);
  font-size: 10.5px;
  color: #f0d9a0;
  border: 1px solid rgba(232, 200, 119, 0.32);
  border-radius: 6px;
  padding: 2px 7px;
  white-space: nowrap;
}

.ic__state {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 12px;
  font-family: var(--tg-font-mono);
  font-size: 11px;
  color: #b3a48c;
  flex-shrink: 0;
}

.ic__draft {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.ic__upd {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #9bbf6e;
  white-space: nowrap;
}

.ic__upd i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #9bbf6e;
  box-shadow: 0 0 7px rgba(155, 191, 110, 0.8);
  animation: ic-blink-dot 2.4s ease-in-out infinite;
}

@keyframes ic-blink-dot {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.35; }
}

.ic__run {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  border: none;
  cursor: default;
  border-radius: 999px;
  padding: 8px 16px;
  background: linear-gradient(135deg, #8f6b22, #c99b3f 60%, #b88a2f);
  color: #fff;
  font-size: 12.5px;
  font-weight: 650;
  font-family: inherit;
  box-shadow: 0 6px 18px rgba(143, 107, 34, 0.4);
  white-space: nowrap;
}

.ic__tools {
  display: flex;
  align-items: center;
  gap: 12px;
  color: #a3957b;
  flex-shrink: 0;
}

/* ===== 主体两栏 ===== */
.ic__body {
  display: grid;
  grid-template-columns: 240px 1fr;
  min-height: 408px;
}

.ic__side {
  border-right: 1px solid rgba(232, 200, 119, 0.12);
  padding: 14px 12px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  background: rgba(255, 244, 220, 0.025);
}

.ic__search {
  display: flex;
  align-items: center;
  gap: 8px;
  border: 1px solid rgba(232, 200, 119, 0.16);
  border-radius: 9px;
  padding: 7px 11px;
  color: #b3a48c;
  font-size: 12px;
}

.ic__kbd {
  margin-left: auto;
  font-family: var(--tg-font-mono);
  font-size: 9.5px;
  border: 1px solid rgba(232, 200, 119, 0.2);
  border-radius: 4px;
  padding: 1px 5px;
  color: #8f8069;
  white-space: nowrap;
}

.ic__nav {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ic__nav-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
  color: #cfc2a6;
  padding: 7px 10px;
  border-radius: 8px;
}

.ic__nav-item--on {
  background: rgba(232, 200, 119, 0.1);
  color: #f5ebd8;
  font-weight: 600;
}

.ic__nav-item:hover {
  background: rgba(232, 200, 119, 0.07);
  color: #f5ebd8;
}

.ic__nav-ic {
  font-style: normal;
  width: 16px;
  display: inline-flex;
  justify-content: center;
  color: #bfa87a;
}

.ic__nav-ic :deep(svg) {
  display: block;
}

.ic__group {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.ic__group-h {
  display: flex;
  align-items: center;
  gap: 7px;
  font-size: 11.5px;
  font-weight: 650;
  color: #e8dcc2;
  padding: 5px 8px;
}

.ic__fold {
  font-style: normal;
  color: #8f8069;
  display: inline-flex;
}

.ic__tree-item {
  display: flex;
  align-items: center;
  gap: 9px;
  font-size: 12.5px;
  color: #b3a48c;
  padding: 7px 10px 7px 20px;
  border-radius: 8px;
  position: relative;
  white-space: nowrap;
  overflow: hidden;
}

.ic__tree-item em {
  margin-left: auto;
  font-style: normal;
  color: #8f8069;
  font-size: 11px;
}

.ic__tree-item--on {
  background: rgba(201, 155, 63, 0.16);
  color: #f5ebd8;
  font-weight: 600;
}

.ic__t-ic {
  width: 14px;
  height: 14px;
  border-radius: 4px;
  border: 1.2px solid #8f8069;
  flex-shrink: 0;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  color: transparent;
}

.ic__tree-item--on .ic__t-ic {
  border-color: #c99b3f;
  background: rgba(201, 155, 63, 0.3);
  color: #f0d9a0;
}

.ic__t-ic--doc {
  border-radius: 3px;
  background: rgba(232, 200, 119, 0.12);
}

/* ===== 编辑器 ===== */
.ic__main {
  padding: 22px 30px 26px;
  min-width: 0;
  overflow: hidden;
  font-family: 'Consolas', 'JetBrains Mono', 'SFMono-Regular', 'Courier New', monospace;
}

.ic__title {
  margin: 0 0 20px;
  font-size: 19px;
  font-weight: 700;
  color: #f7efdd;
  letter-spacing: 0;
  min-height: 30px;
}

.ic__caret {
  display: inline-block;
  width: 2px;
  height: 20px;
  margin-left: 3px;
  background: #e8c877;
  vertical-align: -3px;
  animation: ic-caret 1.05s steps(1) infinite;
}

.ic__caret--sm {
  height: 15px;
  vertical-align: -2px;
}

@keyframes ic-caret {
  50% { opacity: 0; }
}

/* 场景块:staggered 浮现 */
.ic__block {
  position: relative;
  display: flex;
  gap: 16px;
  padding: 14px 0 18px 6px;
  opacity: 0;
  transform: translateY(14px);
  transition: opacity 0.7s var(--tg-ease), transform 0.7s var(--tg-ease);
}

.ic--on .ic__block {
  opacity: 1;
  transform: none;
}

.ic--on .ic__d1 { transition-delay: 0.25s; }
.ic--on .ic__d2 { transition-delay: 0.8s; }
.ic--on .ic__d3 { transition-delay: 1.35s; }

/* 块左侧节点 + 连接线 */
.ic__node {
  width: 13px;
  height: 13px;
  border-radius: 50%;
  border: 2px solid #c99b3f;
  background: #40341f;
  flex-shrink: 0;
  margin-top: 3px;
  position: relative;
  z-index: 1;
}

.ic__node--sm {
  width: 9px;
  height: 9px;
  margin-top: 0;
  border-width: 1.6px;
}

.ic__block::before {
  content: '';
  position: absolute;
  left: 12px;
  top: 8px;
  bottom: -6px;
  width: 1.5px;
  background: linear-gradient(180deg, rgba(232, 200, 119, 0.4), rgba(232, 200, 119, 0.12));
}

.ic__block-body {
  flex: 1;
  min-width: 0;
}

.ic__block-body > b {
  display: block;
  font-size: 13.5px;
  color: #f5ebd8;
  font-weight: 650;
  margin-bottom: 10px;
}

/* 规约(Say 式引文) */
.ic__say {
  display: flex;
  gap: 14px;
  margin: 0 0 10px 2px;
}

.ic__chip-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
  height: 22px;
  padding: 0 10px;
  border-radius: 999px;
  border: 1px solid rgba(232, 200, 119, 0.3);
  color: #f0d9a0;
  font-size: 11px;
  font-family: var(--tg-font-mono);
  background: rgba(201, 155, 63, 0.12);
}

.ic__chip-ic {
  font-style: normal;
  display: inline-flex;
}

.ic__say p {
  margin: 0;
  font-size: 12.5px;
  line-height: 1.7;
  color: #d5c9ad;
  font-style: italic;
}

/* 条件行 */
.ic__cond {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  font-size: 12px;
  color: #ddd1b7;
  padding: 3px 0 3px 2px;
}

.ic__arr {
  color: #a89468;
  font-family: var(--tg-font-mono);
}

.ic__code {
  font-family: var(--tg-font-mono);
  font-size: 11px;
  padding: 2.5px 8px;
  border-radius: 6px;
  white-space: nowrap;
}

.ic__code--gold {
  color: #f4dfae;
  background: rgba(201, 155, 63, 0.2);
  border: 1px solid rgba(201, 155, 63, 0.4);
}

.ic__code--olive {
  color: #d8ecc0;
  background: rgba(155, 191, 110, 0.16);
  border: 1px solid rgba(155, 191, 110, 0.34);
}

.ic__code--clay {
  color: #f8cdb6;
  background: rgba(217, 138, 106, 0.16);
  border: 1px solid rgba(217, 138, 106, 0.36);
}

.ic__to {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  color: #f5ebd8;
  font-weight: 600;
}

/* ===== compact:嵌于 walk 场景位 ===== */
.ic--compact .ic__win {
  border-radius: 16px;
}

.ic--compact .ic__body {
  grid-template-columns: 1fr;
  min-height: 0;
}

.ic--compact .ic__side {
  display: none;
}

.ic--compact .ic__bar {
  flex-wrap: wrap;
  gap: 8px 12px;
  padding: 9px 14px;
}

.ic--compact .ic__tools {
  display: none;
}

.ic--compact .ic__main {
  padding: 16px 20px 18px;
}

.ic--compact .ic__title {
  font-size: 17px;
  margin-bottom: 12px;
  min-height: 24px;
}

.ic--compact .ic__caret {
  height: 16px;
}

.ic--compact .ic__block {
  padding: 9px 0 11px 4px;
}

.ic--compact .ic__block-body > b {
  font-size: 13px;
  margin-bottom: 7px;
}

.ic--compact .ic__say {
  gap: 10px;
  margin-bottom: 7px;
}

.ic--compact .ic__say p {
  font-size: 12.5px;
  line-height: 1.65;
}

.ic--compact .ic__cond {
  font-size: 12px;
}

/* ===== 响应式 ===== */
@media (max-width: 960px) {
  .ic__side {
    display: none;
  }

  .ic__body {
    grid-template-columns: 1fr;
    min-height: 0;
  }

  .ic__bar {
    flex-wrap: wrap;
    gap: 10px;
  }

  .ic__state,
  .ic__tools {
    display: none;
  }

  .ic__main {
    padding: 18px 18px 22px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .ic__caret {
    animation: none;
  }
}
</style>
