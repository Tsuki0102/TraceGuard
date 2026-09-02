import { ref, watch, onUnmounted } from 'vue'

/**
 * 数字平滑滚动动画（从 0 滚动到目标值，支持目标值异步更新）
 * 使用：const disp = useCountUp(someRef)；模板 {{ disp }}
 * 兼容 prefers-reduced-motion：直接显示目标值
 */
export function useCountUp(targetRef, { duration = 900 } = {}) {
  const display = ref(0)
  let raf = 0
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches

  watch(
    targetRef,
    (target) => {
      target = Number(target) || 0
      if (reduce) {
        display.value = target
        return
      }
      cancelAnimationFrame(raf)
      const from = display.value
      const dur = duration
      const start = performance.now()
      const step = (now) => {
        const p = Math.min((now - start) / dur, 1)
        const eased = 1 - Math.pow(1 - p, 3)
        display.value = Math.round(from + (target - from) * eased)
        if (p < 1) raf = requestAnimationFrame(step)
      }
      raf = requestAnimationFrame(step)
    },
    { immediate: true }
  )

  onUnmounted(() => cancelAnimationFrame(raf))
  return display
}
