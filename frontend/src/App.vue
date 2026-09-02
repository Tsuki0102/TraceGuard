<template>
  <router-view v-slot="{ Component }">
    <transition name="tg-page" mode="out-in">
      <component :is="Component" />
    </transition>
  </router-view>
</template>

<script setup>
</script>

<style>
* {
  margin: 0;
  padding: 0;
  box-sizing: border-box;
}

/* 全局字体/背景统一由 src/styles/index.css 设计令牌管理 */
#app {
  width: 100%;
  min-height: 100vh;
}
</style>
