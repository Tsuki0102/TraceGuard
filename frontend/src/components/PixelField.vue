<template>
  <div class="px-fx" aria-hidden="true">
    <canvas ref="cv"></canvas>
  </div>
</template>

<script setup>
/**
 * PixelField —— Landing 数码像素场(复刻 base.org hero 背景,暖金高对比版)
 *
 * 生命周期为「密度守恒」模型,保证像素场永不空窗:
 *  1. 柱体过期 → 同簇邻域原位重生(单簇密度不变);
 *  2. 簇到龄迁徙 → 900ms 渐隐,同时在别处立即生成替代簇(总量恒定);
 *  3. 兜底:存续簇数低于目标时强制补簇(标签页恢复/异常自愈);
 *  4. ResizeObserver 仅在视口尺寸真正变化时重建,避免内容高度抖动反复清场。
 * 鼠标:双层视差 + 磁场吸偏增饱和 + 网格拖尾(方块/小柱混排)
 *      + 甩动火花 + 静置声呐环 + 点击水波/迸发。
 */
import { ref, onMounted, onUnmounted } from 'vue'

const cv = ref(null)
let cleanup = null

onMounted(() => {
  const canvas = cv.value
  if (!canvas) return
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const ctx = canvas.getContext('2d')
  const dpr = Math.min(window.devicePixelRatio || 1, 2)

  // ===== 暖金色板(高对比:饱和蜜糖/深金为主,淡沙做底) =====
  const PAL = [
    { c: [166, 124, 34], w: 14 },
    { c: [201, 155, 63], w: 16 },
    { c: [130, 96, 28], w: 8 },
    { c: [228, 187, 92], w: 15 },
    { c: [226, 205, 148], w: 14 },
    { c: [122, 150, 80], w: 13 },
    { c: [186, 108, 74], w: 10 },
    { c: [196, 178, 142], w: 10 }
  ]
  const PAL_SUM = PAL.reduce((s, p) => s + p.w, 0)
  const pick = () => {
    let r = Math.random() * PAL_SUM
    for (const p of PAL) {
      if ((r -= p.w) <= 0) return p.c
    }
    return PAL[1].c
  }

  let alphaBoost = 1
  const applyTheme = () => {
    alphaBoost = document.documentElement.getAttribute('data-theme') === 'dark' ? 1.4 : 1
  }
  applyTheme()
  const themeOb = new MutationObserver(applyTheme)
  themeOb.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })

  // ===== 几何参数 =====
  const CELL = 8
  const BAR_W = 7
  const R = 2.2
  const CELL_TIME = 260

  let cw = 0
  let ch = 0
  let clusters = []
  let circle = null
  let nextCircleAt = 8000
  let ripples = []
  let sparks = []
  let trails = [] // 网格拖尾(方块/小柱混排)
  let raf = 0
  let running = false
  let ioVisible = true
  let clock = 0
  let last = 0
  let lastSpawnAt = 0
  let lastMoveAt = 0
  let lastSonarAt = 0
  let lastSparkAt = 0
  const mouse = { x: -9999, y: -9999, seen: false }
  const par = { x: 0, y: 0 }
  let lastTrail = null

  const inCenterZone = (x, y) => {
    const dx = (x - cw * 0.5) / (cw * 0.19)
    const dy = (y - ch * 0.44) / (ch * 0.2)
    return dx * dx + dy * dy < 1
  }

  const makeColumn = (x, y) => {
    const roll = Math.random()
    const units = roll < 0.32 ? 1 : roll < 0.58 ? 2 : roll < 0.78 ? 3 : roll < 0.9 ? 4 : roll < 0.97 ? 6 : 8
    const gx = Math.max(2, Math.min(cw / CELL - 2, Math.round(x / CELL)))
    const gy = Math.max(2, Math.min(ch / CELL - 2, Math.round(y / CELL)))
    return {
      gx,
      gy,
      units,
      targetUnits: units,
      drawnUnits: units,
      color: pick(),
      a0: 0.34 + Math.random() * 0.6,
      phase: Math.random() * Math.PI * 2,
      twk: 0.4 + Math.random() * 0.9,
      born: clock + Math.random() * 700,
      life: 5500 + Math.random() * 5500,
      glow: 0
    }
  }

  const spawnCluster = () => {
    let ax = 0
    let ay = 0
    for (let i = 0; i < 12; i++) {
      ax = cw * (0.04 + Math.random() * 0.92)
      ay = ch * (0.07 + Math.random() * 0.86)
      if (!inCenterZone(ax, ay)) break
    }
    const n = 10 + Math.floor(Math.random() * 13)
    const slope = (Math.random() - 0.5) * 1.7
    const depth = Math.random() < 0.45 ? 0 : 1
    const cols = []
    for (let i = 0; i < n; i++) {
      const k = i - n / 2
      const x = ax + k * CELL * (1.7 + Math.random() * 1.2) + (Math.random() - 0.5) * CELL
      const y = ay + k * CELL * slope + (Math.random() - 0.5) * CELL * 2.2
      if (x < 8 || x > cw - 8 || y < 8 || y > ch - 8) continue
      cols.push(makeColumn(x, y))
    }
    if (cols.length) {
      clusters.push({
        cols,
        depth,
        cx: ax,
        cy: ay,
        spread: n * CELL * 1.9,
        migrateAt: clock + 16000 + Math.random() * 16000,
        dead: false,
        deadAt: 0
      })
    }
  }

  const spawnCircle = () => {
    let cx = 0
    let cy = 0
    for (let i = 0; i < 12; i++) {
      cx = cw * (0.1 + Math.random() * 0.8)
      cy = ch * (0.14 + Math.random() * 0.72)
      if (!inCenterZone(cx, cy)) break
    }
    const bars = 24 + Math.floor(Math.random() * 10)
    const rad = 40 + Math.random() * 32
    const cols = []
    for (let b = 0; b < bars; b++) {
      const ang = (b / bars) * Math.PI * 2
      const c = makeColumn(cx + Math.cos(ang) * rad, cy + Math.sin(ang) * rad)
      c.ang = ang
      c.rad = rad
      c.a0 = 0.3 + Math.random() * 0.45
      c.life = 6500 + Math.random() * 4500
      cols.push(c)
    }
    circle = { cx, cy, cols, depth: 0, born: clock }
  }

  const retargetHeights = () => {
    for (const cl of clusters) {
      if (cl.dead) continue
      for (const c of cl.cols) {
        if (Math.random() < 0.003 && c.targetUnits > 0) {
          c.targetUnits = Math.max(1, Math.min(8, c.targetUnits + (Math.random() < 0.5 ? -1 : 1)))
        }
      }
    }
    if (circle) {
      for (const c of circle.cols) {
        if (Math.random() < 0.002 && c.targetUnits > 0) {
          c.targetUnits = Math.max(1, Math.min(6, c.targetUnits + (Math.random() < 0.5 ? -1 : 1)))
        }
      }
    }
  }

  const resize = () => {
    cw = window.innerWidth
    ch = window.innerHeight
    canvas.width = cw * dpr
    canvas.height = ch * dpr
    canvas.style.width = cw + 'px'
    canvas.style.height = ch + 'px'
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    clusters = []
    circle = null
    const target = Math.max(5, Math.round((cw * ch) / 215000))
    for (let i = 0; i < target; i++) spawnCluster()
    if (!reduced) nextCircleAt = clock + 5000 + Math.random() * 7000
  }

  const drawPix = (x, y, w, h, c, a) => {
    ctx.fillStyle = 'rgba(' + c[0] + ',' + c[1] + ',' + c[2] + ',' + Math.min(1, a * alphaBoost).toFixed(3) + ')'
    ctx.beginPath()
    if (ctx.roundRect) ctx.roundRect(x - w / 2, y - h / 2, w, h, R)
    else ctx.rect(x - w / 2, y - h / 2, w, h)
    ctx.fill()
  }

  const columnAlpha = (c, age) => {
    if (age < 0) return 0
    const IN = 600
    const OUT = 900
    let k = age < IN ? age / IN : age > c.life - OUT ? Math.max(0, (c.life - age) / OUT) : 1
    k *= 0.75 + 0.25 * Math.sin(clock * 0.001 * c.twk + c.phase)
    return k
  }

  const burst = (x, y) => {
    for (let i = 0; i < 12; i++) {
      const ang = Math.random() * Math.PI * 2
      const v = 70 + Math.random() * 170
      const life = 520 + Math.random() * 320
      const tall = Math.random() < 0.45
      sparks.push({
        x,
        y,
        vx: Math.cos(ang) * v,
        vy: Math.sin(ang) * v - 60,
        w: BAR_W,
        h: tall ? BAR_W * (1.6 + Math.random() * 2.2) : BAR_W,
        color: pick(),
        life,
        max: life,
        spin: (Math.random() - 0.5) * 2
      })
    }
  }

  const draw = (dt) => {
    ctx.clearRect(0, 0, cw, ch)
    clock += Math.min(dt, 50)

    // 视差:近/远双层反向轻移
    const px = mouse.seen ? mouse.x / cw - 0.5 : 0
    const py = mouse.seen ? mouse.y / ch - 0.5 : 0
    par.x += (px - par.x) * (1 - Math.exp(-dt / 320))
    par.y += (py - par.y) * (1 - Math.exp(-dt / 320))

    // ===== 密度守恒调度 =====
    const targetCount = Math.max(5, Math.round((cw * ch) / 215000))
    for (const cl of clusters) {
      if (cl.dead) continue
      // 1) 柱体过期 → 同簇邻域原位重生(单簇密度不变)
      for (let i = 0; i < cl.cols.length; i++) {
        const c = cl.cols[i]
        if (clock - c.born > c.life + 80) {
          cl.cols[i] = makeColumn(
            cl.cx + (Math.random() - 0.5) * cl.spread,
            cl.cy + (Math.random() - 0.5) * cl.spread * 0.9
          )
        }
      }
      // 2) 簇迁徙:到龄整簇 900ms 渐隐,同时立刻生成替代簇(总量恒定)
      if (clock > cl.migrateAt) {
        cl.dead = true
        cl.deadAt = clock
        cl.cols.forEach((c) => {
          c.life = Math.min(c.life, clock - c.born + 900)
        })
        spawnCluster()
      }
    }
    clusters = clusters.filter((cl) => !cl.dead || clock - cl.deadAt < 1600)
    // 3) 兜底:存续簇不足时强制补簇(标签页恢复/异常清空自愈)
    if (clusters.filter((cl) => !cl.dead).length < targetCount && clock - lastSpawnAt > 600) {
      spawnCluster()
      lastSpawnAt = clock
    }
    // 4) 圆环图腾:过期回收,循环重生
    if (circle) {
      const c0 = circle.cols[0]
      if (!c0 || clock - c0.born > c0.life + 1200) circle = null
    }
    if (!reduced && clock > nextCircleAt && !circle) {
      spawnCircle()
      nextCircleAt = clock + 14000 + Math.random() * 10000
    }
    if (Math.random() < 0.06 * (dt / 16)) retargetHeights()
    // 静置声呐:光标停驻 2.4s 后,每 2.6s 从光标处发出一圈柔和波
    if (mouse.seen && clock - lastMoveAt > 2400 && clock - lastSonarAt > 2600) {
      lastSonarAt = clock
      ripples.push({ x: mouse.x, y: mouse.y, t0: clock, soft: true })
      if (ripples.length > 6) ripples.splice(0, ripples.length - 6)
    }
    ripples = ripples.filter((rp) => clock - rp.t0 < 1400)

    const HOVER_R = 160

    const drawLayer = (depth) => {
      // 近层反向位移更大,远层同向小幅 → 双层视差
      const off = depth === 1 ? { x: -par.x * 36, y: -par.y * 24 } : { x: par.x * 13, y: par.y * 9 }
      const scale = depth === 1 ? 1 : 0.85

      const paint = (c, owner) => {
        const age = clock - c.born
        const base = columnAlpha(c, age)
        c.drawnUnits += (c.targetUnits - c.drawnUnits) * (1 - Math.exp(-dt / CELL_TIME))
        const u = c.drawnUnits
        if (u < 0.05 || base < 0.015) return
        let x = c.gx * CELL + off.x
        let y = c.gy * CELL + off.y
        let alpha = base * c.a0 * 2.35
        let col = c.color
        let glow = 0
        if (owner === 'circle') {
          x = circle.cx + Math.cos(c.ang) * c.rad + off.x
          y = circle.cy + Math.sin(c.ang) * c.rad + off.y
        }
        // 鼠标磁场:吸偏 + 增饱和 + 放大(金色能量晕)
        if (mouse.seen) {
          const dx = mouse.x - x
          const dy = mouse.y - y
          const d = Math.hypot(dx, dy)
          if (d < HOVER_R) {
            glow = (1 - d / HOVER_R) ** 1.6
            x += dx * glow * 0.18
            y += dy * glow * 0.18
            col = [
              Math.round(col[0] + (201 - col[0]) * glow * 0.85),
              Math.round(col[1] + (155 - col[1]) * glow * 0.85),
              Math.round(col[2] + (63 - col[2]) * glow * 0.85)
            ]
            alpha = Math.min(1, alpha + glow * 0.75)
          }
        }
        // 点击水波/静置声呐:波前经过提亮(声呐更柔)
        for (const rp of ripples) {
          const t = (clock - rp.t0) / 1400
          if (t < 0) continue
          const rr = t * (rp.soft ? 240 : 420)
          const amp = rp.soft ? 0.38 : 0.85
          const d = Math.abs(Math.hypot(x - rp.x, y - rp.y) - rr)
          if (d < 60) {
            const g = (1 - d / 60) * (1 - t) * amp
            if (g > glow) {
              glow = g
              col = [
                Math.round(col[0] + (201 - col[0]) * g * 0.7),
                Math.round(col[1] + (155 - col[1]) * g * 0.7),
                Math.round(col[2] + (63 - col[2]) * g * 0.7)
              ]
              alpha = Math.min(1, alpha + g * 0.7)
            }
          }
        }
        const w = (BAR_W + glow * 3) * scale
        const h = (u * CELL - 2) * scale * (1 + glow * 0.22)
        drawPix(x, y, w, h, col, alpha)
      }

      clusters.forEach((cl) => {
        if (cl.depth !== depth) return
        cl.cols.forEach((c) => paint(c))
      })
      if (circle && depth === circle.depth) circle.cols.forEach((c) => paint(c, 'circle'))
    }
    drawLayer(0)
    drawLayer(1)

    // 网格拖尾:方块与小柱混排的数码尾迹
    for (let i = trails.length - 1; i >= 0; i--) {
      const t = trails[i]
      t.life -= dt
      if (t.life <= 0) {
        trails.splice(i, 1)
        continue
      }
      const k = t.life / t.max
      drawPix(t.x, t.y, t.w * (0.45 + k * 0.55), t.h * (0.45 + k * 0.55), t.color, 0.85 * k)
    }

    // 点击迸发:小柱体抛洒 + 微重力
    for (let i = sparks.length - 1; i >= 0; i--) {
      const b = sparks[i]
      b.life -= dt
      if (b.life <= 0) {
        sparks.splice(i, 1)
        continue
      }
      b.x += (b.vx * dt) / 1000
      b.y += (b.vy * dt) / 1000
      b.vx *= Math.exp(-dt / 460)
      b.vy = b.vy * Math.exp(-dt / 460) + dt * 0.06
      const k = b.life / b.max
      drawPix(b.x, b.y, b.w * (0.45 + k * 0.55), b.h * (0.45 + k * 0.55), b.color, 0.9 * k)
    }

    if (running) raf = requestAnimationFrame(loop)
  }

  const loop = (ts) => {
    const dt = last ? ts - last : 16
    last = ts
    draw(dt)
  }
  const start = () => {
    if (running) return
    running = true
    last = 0
    raf = requestAnimationFrame(loop)
  }
  const stop = () => {
    running = false
    cancelAnimationFrame(raf)
  }

  const onMove = (e) => {
    const nx = e.clientX
    const ny = e.clientY
    const ox = mouse.x
    const oy = mouse.y
    if (!mouse.seen) {
      mouse.seen = true
      lastTrail = { x: nx, y: ny }
    }
    const dx = nx - ox
    const dy = ny - oy
    const dist = Math.hypot(dx, dy)
    mouse.x = nx
    mouse.y = ny
    lastMoveAt = clock
    // 甩动火花:高速移动时沿途迸出小火花
    if (dist > 2 && clock - lastSparkAt > 70 && dist / 16 > 2.2) {
      lastSparkAt = clock
      const ang = Math.atan2(dy, dx) + Math.PI + (Math.random() - 0.5) * 1.2
      const v = 40 + Math.random() * 90
      const life = 300 + Math.random() * 220
      sparks.push({
        x: nx,
        y: ny,
        vx: Math.cos(ang) * v,
        vy: Math.sin(ang) * v - 30,
        w: 5,
        h: Math.random() < 0.5 ? 5 : 12,
        color: pick(),
        life,
        max: life,
        spin: 0
      })
    }
    // 网格拖尾:每 18px 落一枚,方块与小柱混排,偶尔金色闪块
    if (lastTrail) {
      let tx = nx - lastTrail.x
      let ty = ny - lastTrail.y
      let d = Math.hypot(tx, ty)
      let guard = 0
      while (d >= 18 && guard < 12) {
        guard++
        const k = 18 / d
        lastTrail.x += tx * k
        lastTrail.y += ty * k
        const bar = Math.random() < 0.34
        trails.push({
          x: Math.round(lastTrail.x / CELL) * CELL,
          y: Math.round(lastTrail.y / CELL) * CELL,
          w: BAR_W,
          h: bar ? BAR_W * (1.8 + Math.random() * 1.6) : BAR_W,
          color: Math.random() < 0.22 ? [232, 200, 119] : pick(),
          life: 460 + Math.random() * 160,
          max: 620
        })
        if (trails.length > 110) trails.shift()
        tx = nx - lastTrail.x
        ty = ny - lastTrail.y
        d = Math.hypot(tx, ty)
      }
    } else {
      lastTrail = { x: nx, y: ny }
    }
  }
  const onDown = (e) => {
    // 双重水波 + 迸发
    ripples.push({ x: e.clientX, y: e.clientY, t0: clock })
    ripples.push({ x: e.clientX, y: e.clientY, t0: clock + 140 })
    if (ripples.length > 6) ripples.splice(0, ripples.length - 6)
    burst(e.clientX, e.clientY)
    burst(e.clientX, e.clientY)
  }
  const onLeaveWin = () => {
    mouse.seen = false
    lastTrail = null
  }

  resize()
  if (!reduced) {
    start()
    window.addEventListener('pointermove', onMove, { passive: true })
    window.addEventListener('pointerdown', onDown, { passive: true })
    document.documentElement.addEventListener('mouseleave', onLeaveWin)
  } else {
    draw(0)
  }

  let ro = null
  if (typeof ResizeObserver !== 'undefined') {
    // 仅在视口尺寸真正变化时重建,内容高度抖动(字体加载/公告开合)不再反复清场
    // (html 元素宽度随视口变化,窗口缩放同样会被捕获)
    ro = new ResizeObserver(() => {
      if (window.innerWidth !== cw || window.innerHeight !== ch) resize()
    })
    ro.observe(document.documentElement)
  }
  const io = new IntersectionObserver(
    ([entry]) => {
      ioVisible = entry.isIntersecting
      if (entry.isIntersecting) start()
      else stop()
    },
    { threshold: 0 }
  )
  io.observe(canvas)
  // 页签恢复时立即续跑(不等 1s 看门狗)
  const onVisChange = () => {
    if (!document.hidden && !reduced) start()
  }
  document.addEventListener('visibilitychange', onVisChange)
  const watchdog = setInterval(() => {
    if (!running && ioVisible && document.visibilityState === 'visible' && !reduced) start()
  }, 1000)

  cleanup = () => {
    stop()
    clearInterval(watchdog)
    io.disconnect()
    if (ro) ro.disconnect()
    themeOb.disconnect()
    document.removeEventListener('visibilitychange', onVisChange)
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerdown', onDown)
    document.documentElement.removeEventListener('mouseleave', onLeaveWin)
  }
})

onUnmounted(() => {
  if (cleanup) cleanup()
})
</script>

<style scoped>
.px-fx {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
}

.px-fx canvas {
  position: absolute;
  inset: 0;
}
</style>
