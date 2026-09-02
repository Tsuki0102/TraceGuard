<template>
  <div class="wb-fx" aria-hidden="true">
    <canvas ref="cv"></canvas>
    <div ref="dotEl" class="wb-cursor-dot"></div>
    <div ref="ringEl" class="wb-cursor-ring"></div>
  </div>
</template>

<script setup>
/**
 * WorkbenchFx —— 主工作台全局动效层
 * 「链路巡检」主题:追踪数据包沿链路流动 + 斜向扫描光带 + 鼠标探针/点击波前 + 准星光标
 * 全部绘制于内容层之下(z-index:-1);自定义光标环在最上层(9999)。
 */
import { ref, onMounted, onUnmounted } from 'vue'

const cv = ref(null)
const dotEl = ref(null)
const ringEl = ref(null)
let cleanup = null

onMounted(() => {
  const canvas = cv.value
  if (!canvas) return
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const ctx = canvas.getContext('2d')
  const dpr = Math.min(window.devicePixelRatio || 1, 2)

  // ===== 主题色板(亮 / 深,随 html[data-theme] 切换) =====
  const PAL_LIGHT = {
    grid: [143, 107, 34],
    probe: [184, 138, 47],
    gold: [201, 155, 63],
    green: [107, 142, 78],
    clay: [176, 101, 63],
    scan: [201, 155, 63],
    boost: 1
  }
  const PAL_DARK = {
    grid: [232, 200, 119],
    probe: [232, 200, 119],
    gold: [232, 200, 119],
    green: [155, 191, 110],
    clay: [255, 155, 124],
    scan: [232, 200, 119],
    boost: 1.5
  }
  let pal = PAL_LIGHT
  const applyPalette = () => {
    pal = document.documentElement.getAttribute('data-theme') === 'dark' ? PAL_DARK : PAL_LIGHT
  }
  applyPalette()
  const themeOb = new MutationObserver(applyPalette)
  themeOb.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })

  let cw = 0
  let ch = 0
  let nodes = []
  let packets = []
  let clicks = []
  let ripples = [] // 扩散波纹(自动 + 鼠标轨迹)
  let pendingAuto = [] // 待触发的自动波纹源(错峰)
  let lastAuto = 0
  let lastTrail = null // 上一个鼠标涟漪落点
  let raf = 0
  let running = false
  let ioVisible = true
  let clock = 0
  let last = 0
  let mouse = { x: -9999, y: -9999, vx: 0, vy: 0, down: false, seen: false }
  let ring = { x: innerWidth / 2, y: innerHeight / 2 }
  const SPACING = 150
  const PROBE_R = 150
  const AUTO_EVERY = 5000 // 每 5 秒一批自动波纹源
  const AUTO_SPEED = 12 * 0.8 // 自动波纹扩散 px/s(0.8x 速率)
  const MOUSE_SPEED = 18 * 0.8 // 鼠标涟漪扩散 px/s(0.8x 速率)
  const TRAIL_STEP = 15 // 鼠标路径每 15px 一个涟漪源
  const MAX_RIPPLES = 12 // 同屏波纹上限

  const rgba = (c, a) => 'rgba(' + c[0] + ',' + c[1] + ',' + c[2] + ',' + a.toFixed(3) + ')'

  const build = () => {
    nodes = []
    const cols = Math.ceil(cw / SPACING) + 1
    const rows = Math.ceil(ch / SPACING) + 1
    const idx = (c, r) => r * cols + c
    for (let r = 0; r < rows; r++) {
      for (let c = 0; c < cols; c++) {
        nodes.push({
          x: c * SPACING + (Math.random() - 0.5) * 20,
          y: r * SPACING + (Math.random() - 0.5) * 20,
          act: 0
        })
      }
    }
    // 链路数据包:随机游走生成折线路径,沿路匀速流动
    packets = []
    for (let i = 0; i < 12; i++) {
      packets.push(makePacket(true))
    }
  }

  const makePacket = (anywhere) => {
    const cols = Math.ceil(cw / SPACING) + 1
    let c = Math.floor(Math.random() * cols)
    let r = Math.floor(Math.random() * Math.ceil(ch / SPACING) + 1)
    if (!anywhere) {
      c = Math.floor(Math.random() * cols)
      r = Math.floor(Math.random() * Math.ceil(ch / SPACING) + 1)
    }
    const path = [nodeAt(c, r)]
    const steps = 3 + Math.floor(Math.random() * 4)
    let pc = c
    let pr = r
    let pcPrev = -1
    let prPrev = -1
    for (let s = 0; s < steps; s++) {
      const dirs = [
        [pc + 1, pr],
        [pc - 1, pr],
        [pc, pr + 1],
        [pc, pr - 1]
      ].filter(([cc, rr]) => cc >= 0 && rr >= 0 && !(cc === pcPrev && rr === prPrev))
      const [nc, nr] = dirs[Math.floor(Math.random() * dirs.length)] || [pc, pr]
      pcPrev = pc
      prPrev = pr
      pc = nc
      pr = nr
      path.push(nodeAt(pc, pr))
    }
    // 缺陷包概率 22%,其余金(追踪)/绿(校验)各半
    const roll = Math.random()
    const kind = roll < 0.22 ? 'clay' : roll < 0.61 ? 'gold' : 'green'
    return {
      path,
      seg: 0,
      t: anywhere ? Math.random() : 0,
      speed: (55 + Math.random() * 45) * 0.9, // px/s,整体略慢
      kind,
      wait: 0
    }
  }

  const nodeAt = (c, r) => {
    const cols = Math.ceil(cw / SPACING) + 1
    return nodes[Math.min(idxSafe(c, r, cols), nodes.length - 1)] || { x: 0, y: 0 }
  }
  const idxSafe = (c, r, cols) => Math.max(0, r * cols + c)

  const resize = () => {
    cw = window.innerWidth
    ch = window.innerHeight
    canvas.width = cw * dpr
    canvas.height = ch * dpr
    canvas.style.width = cw + 'px'
    canvas.style.height = ch + 'px'
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    build()
  }

  const segPoint = (path, seg, t) => {
    const a = path[seg]
    const b = path[seg + 1]
    return { x: a.x + (b.x - a.x) * t, y: a.y + (b.y - a.y) * t }
  }

  const spawnRipple = (x, y, isMouse) => {
    if (ripples.length >= MAX_RIPPLES) return
    ripples.push({
      x,
      y,
      r: 0,
      speed: isMouse ? MOUSE_SPEED : AUTO_SPEED,
      maxR: isMouse ? 80 : 60,
      a0: isMouse ? 0.7 : 0.5,
      width: isMouse ? 1 : 0.8,
      col: isMouse ? pal.probe : pal.gold
    })
  }

  const draw = (dt) => {
    ctx.clearRect(0, 0, cw, ch)
    clock += Math.min(dt, 50)

    // 1) 追踪格网 + 鼠标探针(吸引 + 连线)
    for (let i = 0; i < nodes.length; i++) {
      const n = nodes[i]
      let target = 0
      if (mouse.seen) {
        const d = Math.hypot(mouse.x - n.x, mouse.y - n.y)
        if (d < PROBE_R) target = 1 - d / PROBE_R
      }
      n.act += (target - n.act) * (1 - Math.exp(-dt / 180))
      // 点击波前经过时提亮
      for (const c of clicks) {
        const wr = (clock - c.t0) * 0.26
        const d = Math.hypot(n.x - c.x, n.y - c.y)
        if (Math.abs(d - wr) < 26) n.act = Math.min(1, n.act + 0.55)
      }
      const ox = mouse.seen ? ((mouse.x - n.x) / (Math.hypot(mouse.x - n.x, mouse.y - n.y) || 1)) * n.act * 7 : 0
      const oy = mouse.seen ? ((mouse.y - n.y) / (Math.hypot(mouse.x - n.x, mouse.y - n.y) || 1)) * n.act * 7 : 0
      ctx.beginPath()
      ctx.arc(n.x + ox, n.y + oy, 1.2 + n.act * 0.9, 0, Math.PI * 2)
      ctx.fillStyle = rgba(pal.grid, (0.14 + n.act * 0.5) * pal.boost > 1 ? 1 : (0.14 + n.act * 0.5) * pal.boost)
      ctx.fill()
    }

    // 2) 链路数据包:沿线流动 + 尾迹,到端点闪烁后换路
    for (const p of packets) {
      if (p.wait > 0) {
        p.wait -= dt
        continue
      }
      const a = p.path[p.seg]
      const b = p.path[p.seg + 1]
      const len = Math.hypot(b.x - a.x, b.y - a.y) || 1
      p.t += ((p.speed * dt) / 1000) / len
      if (p.t >= 1) {
        p.t = 0
        p.seg++
        if (p.seg >= p.path.length - 1) {
          // 到达端点:闪烁光环后重新出发
          ctx.beginPath()
          ctx.arc(b.x, b.y, 9, 0, Math.PI * 2)
          ctx.strokeStyle = rgba(pal[p.kind], 0.5)
          ctx.lineWidth = 1.2
          ctx.stroke()
          Object.assign(p, makePacket(false), { wait: 400 + Math.random() * 1600 })
          continue
        }
      }
      const pt = segPoint(p.path, p.seg, p.t)
      const back = segPoint(p.path, p.seg, Math.max(0, p.t - 14 / len))
      // 尾迹
      ctx.beginPath()
      ctx.moveTo(back.x, back.y)
      ctx.lineTo(pt.x, pt.y)
      ctx.strokeStyle = rgba(pal[p.kind], 0.35)
      ctx.lineWidth = 2
      ctx.lineCap = 'round'
      ctx.stroke()
      // 包体
      ctx.beginPath()
      ctx.arc(pt.x, pt.y, 2.4, 0, Math.PI * 2)
      ctx.fillStyle = rgba(pal[p.kind], 0.9)
      ctx.fill()
    }

    // 3) 斜向扫描光带(26s 周期,极淡)
    const bandPeriod = 26000
    const span = cw + ch + 600
    const bx = ((clock % bandPeriod) / bandPeriod) * span - 300
    ctx.save()
    ctx.translate(cw / 2, ch / 2)
    ctx.rotate(-0.32)
    const g = ctx.createLinearGradient(bx - 220, 0, bx + 220, 0)
    g.addColorStop(0, rgba(pal.scan, 0))
    g.addColorStop(0.5, rgba(pal.scan, 0.05))
    g.addColorStop(1, rgba(pal.scan, 0))
    ctx.fillStyle = g
    ctx.fillRect(-span, -ch, 440, ch * 2)
    ctx.restore()

    // 4) 点击波前:扩散环 + 波前扫描
    for (let i = clicks.length - 1; i >= 0; i--) {
      const c = clicks[i]
      const age = clock - c.t0
      if (age > 950) {
        clicks.splice(i, 1)
        continue
      }
      const r = age * 0.26
      ctx.beginPath()
      ctx.arc(c.x, c.y, r, 0, Math.PI * 2)
      ctx.strokeStyle = rgba(pal.gold, 0.4 * (1 - age / 950))
      ctx.lineWidth = 1.4
      ctx.stroke()
    }

    // 4b) 自动波纹:每 5 秒随机 6-8 个源错峰触发;鼠标路径每 15px 一个涟漪源
    if (clock - lastAuto > AUTO_EVERY) {
      lastAuto = clock
      const n = 6 + Math.floor(Math.random() * 3)
      for (let i = 0; i < n; i++) {
        pendingAuto.push({ t: clock + Math.random() * 2200, x: Math.random() * cw, y: Math.random() * ch })
      }
    }
    for (let i = pendingAuto.length - 1; i >= 0; i--) {
      if (pendingAuto[i].t <= clock) {
        spawnRipple(pendingAuto[i].x, pendingAuto[i].y, false)
        pendingAuto.splice(i, 1)
      }
    }
    for (let i = ripples.length - 1; i >= 0; i--) {
      const rp = ripples[i]
      rp.r += (rp.speed * dt) / 1000
      if (rp.r >= rp.maxR) {
        ripples.splice(i, 1)
        continue
      }
      ctx.beginPath()
      ctx.arc(rp.x, rp.y, rp.r, 0, Math.PI * 2)
      ctx.strokeStyle = rgba(rp.col, rp.a0 * (1 - rp.r / rp.maxR))
      ctx.lineWidth = rp.width
      ctx.stroke()
    }

    // 5) 探针连线:鼠标到周围节点
    if (mouse.seen) {
      for (let i = 0; i < nodes.length; i++) {
        const n = nodes[i]
        if (n.act < 0.08) continue
        const d = Math.hypot(mouse.x - n.x, mouse.y - n.y)
        if (d > PROBE_R) continue
        ctx.beginPath()
        ctx.moveTo(mouse.x, mouse.y)
        ctx.lineTo(n.x, n.y)
        ctx.strokeStyle = rgba(pal.probe, 0.22 * (1 - d / PROBE_R) * n.act)
        ctx.lineWidth = 0.7
        ctx.stroke()
      }
    }

    if (running) raf = requestAnimationFrame(loop)
  }

  // ===== 自定义光标:准星点 + 滞后环(速度拉伸 + 按压缩放) =====
  let cRAF = 0
  const cursorTick = () => {
    if (ringEl.value && mouse.seen) {
      const dx = mouse.x - ring.x
      const dy = mouse.y - ring.y
      ring.x += dx * 0.16
      ring.y += dy * 0.16
      const v = Math.hypot(dx, dy)
      const stretch = Math.min(1 + v * 0.012, 1.45)
      const ang = Math.atan2(dy, dx)
      const press = mouse.down ? 0.72 : 1
      ringEl.value.style.transform =
        'translate(' + (ring.x - 17) + 'px,' + (ring.y - 17) + 'px) rotate(' + ang + 'rad) scale(' + (stretch * press) + ',' + ((2 - stretch) * press) + ')'
    }
    cRAF = requestAnimationFrame(cursorTick)
  }

  const onPointerMove = (e) => {
    mouse.vx = e.clientX - mouse.x
    mouse.vy = e.clientY - mouse.y
    mouse.x = e.clientX
    mouse.y = e.clientY
    // 鼠标涟漪:沿移动路径每 15px 一个源,形成扫描轨迹
    if (!lastTrail) {
      lastTrail = { x: e.clientX, y: e.clientY }
    } else {
      let dx = e.clientX - lastTrail.x
      let dy = e.clientY - lastTrail.y
      let dist = Math.hypot(dx, dy)
      while (dist >= TRAIL_STEP) {
        const k = TRAIL_STEP / dist
        lastTrail.x += dx * k
        lastTrail.y += dy * k
        spawnRipple(lastTrail.x, lastTrail.y, true)
        dx = e.clientX - lastTrail.x
        dy = e.clientY - lastTrail.y
        dist = Math.hypot(dx, dy)
      }
    }
    if (!mouse.seen) {
      mouse.seen = true
      ring.x = e.clientX
      ring.y = e.clientY
      if (dotEl.value) dotEl.value.style.opacity = '1'
      if (ringEl.value) ringEl.value.style.opacity = '1'
    }
    if (dotEl.value) dotEl.value.style.transform = 'translate(' + (e.clientX - 3) + 'px,' + (e.clientY - 3) + 'px)'
  }
  const onDown = (e) => {
    mouse.down = true
    clicks.push({ x: e.clientX, y: e.clientY, t0: clock })
  }
  const onUp = () => {
    mouse.down = false
  }
  const onLeaveWin = () => {
    mouse.seen = false
    lastTrail = null
    if (dotEl.value) dotEl.value.style.opacity = '0'
    if (ringEl.value) ringEl.value.style.opacity = '0'
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

  resize()
  if (!reduced) {
    start()
    window.addEventListener('pointermove', onPointerMove, { passive: true })
    window.addEventListener('pointerdown', onDown, { passive: true })
    window.addEventListener('pointerup', onUp, { passive: true })
    document.documentElement.addEventListener('mouseleave', onLeaveWin)
    cRAF = requestAnimationFrame(cursorTick)
  } else {
    draw(0)
  }

  let ro = null
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(() => resize())
    ro.observe(document.documentElement)
  }
  let io = null
  if ('IntersectionObserver' in window) {
    io = new IntersectionObserver(
      ([entry]) => {
        ioVisible = entry.isIntersecting
        if (entry.isIntersecting) start()
        else stop()
      },
      { threshold: 0 }
    )
    io.observe(canvas)
  }
  // 看门狗:渲染冻结恢复后自愈
  const watchdog = setInterval(() => {
    if (!running && ioVisible && document.visibilityState === 'visible' && !reduced) start()
  }, 1000)

  cleanup = () => {
    stop()
    clearInterval(watchdog)
    cancelAnimationFrame(cRAF)
    if (io) io.disconnect()
    if (ro) ro.disconnect()
    themeOb.disconnect()
    window.removeEventListener('pointermove', onPointerMove)
    window.removeEventListener('pointerdown', onDown)
    window.removeEventListener('pointerup', onUp)
    document.documentElement.removeEventListener('mouseleave', onLeaveWin)
  }
})

onUnmounted(() => {
  if (cleanup) cleanup()
})
</script>

<style scoped>
.wb-fx {
  position: fixed;
  inset: 0;
  z-index: -1;
  pointer-events: none;
}

.wb-fx canvas {
  position: absolute;
  inset: 0;
}

/* 准星光标:即时小点 + 滞后形变环(双层,速度拉伸、按压收缩) */
.wb-cursor-dot {
  position: fixed;
  left: 0;
  top: 0;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #b88a2f;
  opacity: 0;
  z-index: 9999;
  pointer-events: none;
  transition: opacity 0.4s ease;
  will-change: transform;
}

.wb-cursor-ring {
  position: fixed;
  left: 0;
  top: 0;
  width: 34px;
  height: 34px;
  border-radius: 50%;
  border: 1.5px solid rgba(184, 138, 47, 0.55);
  opacity: 0;
  z-index: 9999;
  pointer-events: none;
  transition: opacity 0.4s ease, width 0.3s var(--tg-ease), height 0.3s var(--tg-ease), border-color 0.3s ease;
  will-change: transform;
}

@media (prefers-reduced-motion: reduce) {
  .wb-cursor-dot,
  .wb-cursor-ring {
    display: none;
  }
}
</style>
