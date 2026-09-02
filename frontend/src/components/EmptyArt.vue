<template>
  <div class="empty-art">
    <svg class="empty-art__svg" viewBox="0 0 240 150" aria-hidden="true">
      <defs>
        <linearGradient id="tg-empty-art-folder" x1="0" y1="0" x2="1" y2="1">
          <stop offset="0" stop-color="#F6E6C2" />
          <stop offset="1" stop-color="#E3C88F" />
        </linearGradient>
      </defs>
      <!-- 地面光斑 -->
      <ellipse cx="120" cy="120" rx="74" ry="14" fill="rgba(201,155,63,0.1)" />
      <!-- 文件夹 -->
      <g>
        <path
          d="M50 46 h40 l10 14 h88 a4 4 0 0 1 4 4 v36 a4 4 0 0 1 -4 4 H50 a4 4 0 0 1 -4 -4 V50 a4 4 0 0 1 4 -4 Z"
          fill="url(#tg-empty-art-folder)"
          stroke="rgba(201,155,63,0.4)"
          stroke-width="1.5"
        />
        <path d="M46 64 h152" stroke="rgba(255,255,255,0.75)" stroke-width="1.2" opacity="0.55" />
      </g>
      <!-- 放大镜 -->
      <g transform="translate(176,80)">
        <circle cx="0" cy="0" r="15" fill="none" stroke="rgba(217,169,102,0.85)" stroke-width="3.4" />
        <line x1="11" y1="11" x2="21" y2="21" stroke="rgba(217,169,102,0.85)" stroke-width="3.4" stroke-linecap="round" />
      </g>
      <!-- 漂浮数据点 -->
      <circle class="empty-art__dot empty-art__dot--a" cx="36" cy="42" r="3" fill="#C99B3F" />
      <circle class="empty-art__dot empty-art__dot--b" cx="212" cy="32" r="2.4" fill="#D9A966" />
      <circle class="empty-art__dot empty-art__dot--c" cx="216" cy="118" r="2" fill="#A38C5C" />
    </svg>
    <p class="empty-art__text">{{ text }}</p>
  </div>
</template>

<script setup>
defineProps({
  text: { type: String, default: '暂无数据' }
})
</script>

<style scoped>
.empty-art {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 2px;
  padding: 26px 0 18px;
}

.empty-art__svg {
  width: 220px;
  max-width: 80%;
}

.empty-art__text {
  margin: 10px 0 0;
  font-size: 13px;
  color: var(--tg-text-secondary);
}

/* 数据点缓慢漂浮（尊重全局 reduced-motion 降级） */
.empty-art__dot {
  transform-origin: center;
  animation: tg-empty-float 3.4s ease-in-out infinite;
}

.empty-art__dot--a {
  animation-delay: 0.2s;
}

.empty-art__dot--b {
  animation-delay: 1.1s;
}

.empty-art__dot--c {
  animation-delay: 2s;
}

@keyframes tg-empty-float {
  0%,
  100% {
    transform: translateY(0);
    opacity: 0.7;
  }
  50% {
    transform: translateY(-5px);
    opacity: 1;
  }
}
</style>