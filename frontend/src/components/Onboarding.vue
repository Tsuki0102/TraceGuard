<template>
  <Teleport to="body">
    <transition name="onb-fade">
      <div v-if="visible" class="onb" role="dialog" aria-modal="true" aria-label="新手引导">
        <div class="tg-aurora"></div>
        <div class="onb-card tg-fade-up">
          <button type="button" class="onb-skip" @click="finish">跳过引导</button>

          <div class="onb-stage" :key="step">
            <div class="onb-art" :class="'onb-art--' + step">
              <el-icon :size="46"><component :is="steps[step].icon" /></el-icon>
            </div>
            <h3 class="onb-title">{{ steps[step].title }}</h3>
            <p class="onb-desc">{{ steps[step].desc }}</p>
          </div>

          <div class="onb-nav">
            <div class="onb-dots">
              <button
                v-for="(s, i) in steps"
                :key="i"
                type="button"
                class="onb-dot"
                :class="{ 'is-active': i === step }"
                :aria-label="'第 ' + (i + 1) + ' 步：' + s.title"
                @click="step = i"
              ></button>
            </div>
            <div class="onb-btns">
              <el-button v-if="step > 0" round @click="step -= 1">上一步</el-button>
              <el-button
                v-if="step < steps.length - 1"
                type="primary"
                round
                @click="step += 1"
              >下一步</el-button>
              <el-button
                v-else
                type="primary"
                round
                @click="finish"
              >
                <el-icon style="margin-right: 4px"><Check /></el-icon>开始使用
              </el-button>
            </div>
          </div>
        </div>
      </div>
    </transition>
  </Teleport>
</template>

<script setup>
/**
 * W3-13 / O15：首次登录新手引导（3 步轮播，完成/跳过写入 localStorage）。
 */
import { ref, onMounted } from 'vue'
import { Check } from '@element-plus/icons-vue'

const STORAGE_KEY = 'tg_onboarded'

const steps = [
  {
    icon: 'FolderOpened',
    title: '创建并上传',
    desc: '创建项目，上传需求文档（Word/PDF/Markdown）与代码工程（Maven/Gradle ZIP），大数据文件自动分片续传。'
  },
  {
    icon: 'MagicStick',
    title: '一键分析',
    desc: '配置权重与阈值后启动全流程分析：需求解析、代码解析、形式化规约、语义向量匹配与一致性校验全程可视化。'
  },
  {
    icon: 'DataAnalysis',
    title: '报告交付',
    desc: '查看质量雷达与缺陷报告，追源到具体代码行；可导出 Word/PDF/Excel 报告，并通过质量门槛把关交付。'
  }
]

const visible = ref(false)
const step = ref(0)

onMounted(() => {
  let done = false
  try {
    done = localStorage.getItem(STORAGE_KEY) === '1'
  } catch (e) {
    /* ignore */
  }
  if (!done) {
    step.value = 0
    visible.value = true
  }
})

const finish = () => {
  try {
    localStorage.setItem(STORAGE_KEY, '1')
  } catch (e) {
    /* ignore */
  }
  visible.value = false
}
</script>

<style scoped>
.onb {
  position: fixed;
  inset: 0;
  z-index: 4000;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: rgba(20, 16, 12, 0.55);
  -webkit-backdrop-filter: blur(10px) saturate(1.1);
  backdrop-filter: blur(10px) saturate(1.1);
}

.onb > .tg-aurora {
  opacity: 0.75;
}

.onb-card {
  position: relative;
  z-index: 1;
  width: 480px;
  max-width: 100%;
  padding: 40px 40px 26px;
  border-radius: 26px;
  background: var(--tg-card-highlight);
  border: 1px solid rgba(255, 255, 255, 0.7);
  box-shadow: var(--tg-shadow-modal);
}

.onb-skip {
  position: absolute;
  top: 16px;
  right: 20px;
  border: none;
  background: none;
  font-family: inherit;
  font-size: 12.5px;
  color: var(--tg-slate);
  cursor: pointer;
  transition: color 0.2s ease;
}

.onb-skip:hover {
  color: var(--tg-accent);
}

.onb-stage {
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  gap: 12px;
}

.onb-art {
  width: 108px;
  height: 108px;
  border-radius: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 6px;
  transition: transform 0.4s var(--tg-ease-spring);
}

.onb-art--0 { background: linear-gradient(135deg, rgba(201, 155, 63, 0.2), rgba(232, 200, 119, 0.28)); color: var(--tg-accent); }
.onb-art--1 { background: linear-gradient(135deg, rgba(154, 156, 107, 0.2), rgba(168, 185, 138, 0.26)); color: var(--tg-success); }
.onb-art--2 { background: linear-gradient(135deg, rgba(217, 169, 102, 0.18), rgba(143, 107, 34, 0.14)); color: var(--tg-violet); }

.onb-stage:hover .onb-art {
  transform: scale(1.05) rotate(-4deg);
}

.onb-title {
  margin: 0;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.01em;
  color: var(--tg-text-primary);
}

.onb-desc {
  margin: 0 auto;
  max-width: 360px;
  font-size: 14px;
  line-height: 1.8;
  color: var(--tg-text-secondary);
  min-height: 76px;
}

.onb-nav {
  margin-top: 26px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
}

.onb-dots {
  display: flex;
  gap: 8px;
}

.onb-dot {
  width: 8px;
  height: 8px;
  border: none;
  border-radius: 999px;
  background: rgba(0, 0, 0, 0.12);
  cursor: pointer;
  transition: all 0.25s var(--tg-ease);
}

.onb-dot.is-active {
  width: 22px;
  background: var(--tg-accent-gradient);
  box-shadow: var(--tg-glow-accent);
}

.onb-btns {
  display: flex;
  gap: 8px;
}

.onb-fade-enter-active,
.onb-fade-leave-active {
  transition: opacity 0.35s ease;
}

.onb-fade-enter-from,
.onb-fade-leave-to {
  opacity: 0;
}
</style>