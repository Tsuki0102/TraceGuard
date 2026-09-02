<template>
  <div class="landing">
    <!-- ===== 多层动画背景:网格 + 光晕视差 ===== -->
    <div class="landing-bg" aria-hidden="true">
      <div class="landing-grid"></div>
      <div class="landing-orb landing-orb--1"></div>
      <div class="landing-orb landing-orb--2"></div>
      <div class="landing-orb landing-orb--3"></div>
    </div>
    <!-- 数码像素场:像素簇 / 圆环绽放 / 鼠标引力拖尾 / 准星环 / 扫描波(base.org 风) -->
    <PixelField />
    <div class="scroll-progress" aria-hidden="true"><i ref="progressBar"></i></div>

    <!-- ===== 公告条 ===== -->
    <div v-if="announceOn" class="announce">
      <span class="announce__dot"></span>
      <span class="announce__text">NEW · 报告导出升级:PDF / Excel 全格式嵌入中文字体</span>
      <a href="#demo" @click.prevent="scrollTo('#demo')">查看演示</a>
      <button class="announce__close" aria-label="关闭公告" @click="announceOn = false">×</button>
    </div>

    <!-- ===== 顶部导航 ===== -->
    <header class="landing-nav" :class="{ 'is-scrolled': scrolled }">
      <div class="landing-nav__inner">
        <div class="logo">
          <div class="logo-icon"><img src="@/assets/logo/logo-gold.png" alt="TraceGuard 标志" /></div>
          <div class="logo-text"><span>Trace</span>Guard</div>
        </div>
        <nav class="landing-nav__links">
          <a href="#demo" :class="{ on: spy === 'demo' }">01 · 演示</a>
          <a href="#build" :class="{ on: spy === 'build' }">02 · 能力</a>
          <a href="#insights" :class="{ on: spy === 'insights' }">03 · 洞察</a>
        </nav>
        <div class="landing-nav__actions">
          <template v-if="loggedIn">
            <button class="btn-gold btn-gold--sm magnet" @click="router.push('/dashboard')"><span>进入工作台</span></button>
          </template>
          <template v-else>
            <button class="nav-login" @click="router.push('/login')">登录</button>
            <button class="btn-gold btn-gold--sm magnet" @click="router.push('/login')"><span>免费开始</span></button>
          </template>
        </div>
      </div>
    </header>

    <main class="landing-main">
      <!-- ================= Hero:左文 + 右卡片集群(3D 倾斜) + 指标曲线 ================= -->
      <section class="hero">
        <div class="hero-copy">
          <div class="hero-pill tg-float">
            <span class="tg-live-dot" :class="'tg-live-dot--' + heroMetric.tone"></span>
            <transition name="metric-flip" mode="out-in">
              <span class="hero-pill__metric" :key="heroMetricIdx">
                <em class="dot" :class="'tone-text--' + heroMetric.tone">{{ heroMetric.val }}</em>{{ heroMetric.label }}
              </span>
            </transition>
          </div>
          <div class="hero-kicker dot" aria-hidden="true"><span ref="kickerText">TRACEGUARD — REQ-TO-CODE VERIFICATION</span></div>
          <h1 class="hero-title">
            <span class="tg-word" style="animation-delay: 0.2s">让代码,</span><br />
            <span class="tg-word" style="animation-delay: 0.7s">回应</span>
            <span class="tg-word tg-gradient-text" style="animation-delay: 1.2s">每一行需求。</span>
          </h1>
          <p class="hero-sub tg-fade-up" style="animation-delay: 1.8s">
            面向交付团队的一致性验证引擎。把需求文档、代码工程与测试结果串成一条可追溯的链路,
            自动发现「说了什么」与「做了什么」的偏差——缺陷在交付之前,就现形。
          </p>
          <div class="hero-actions tg-fade-up" style="animation-delay: 2.1s">
            <button class="btn-gold magnet" @click="router.push(primaryTarget())">
              <span>{{ loggedIn ? '进入工作台' : '开始使用' }}</span>
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M3 8h10M9 4l4 4-4 4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
            <button class="btn-ghost magnet" @click="scrollTo('#demo')"><span>观看产品演示</span></button>
          </div>
        </div>

        <!-- 卡片集群:终端 + 实时分析(3D 倾斜)+ 浮动徽章 -->
        <div class="hero-cluster" aria-hidden="true">
          <div class="term tilt">
            <div class="term__bar">
              <div class="term__dots"><i></i><i></i><i></i></div>
              <span class="term__file">traceguard</span>
              <span class="term__status"><span class="tg-live-dot"></span>分析中</span>
            </div>
            <div class="term__body">
              <div v-for="(l, i) in terminalLines" :key="i" class="term__line" :class="'term__line--' + l.cls">
                <span>{{ l.text }}</span>
                <span class="term__cursor" v-if="i === terminalLines.length - 1"></span>
              </div>
            </div>
            <div class="term__foot"><div class="term__progress"><i></i></div></div>
          </div>
          <div class="live tilt">
            <div class="live__head"><span class="tg-live-dot tg-live-dot--pink"></span>实时分析</div>
            <div class="live__ring">
              <svg viewBox="0 0 120 120">
                <circle cx="60" cy="60" r="46" class="live__ring-track" />
                <circle cx="60" cy="60" r="46" class="live__ring-bar" style="stroke-dasharray: 289; stroke-dashoffset: 289" />
              </svg>
              <div class="live__ring-center"><b>92</b><span>%</span></div>
            </div>
            <div class="live__bars">
              <div class="live__bar" v-for="(b, i) in liveBars" :key="i">
                <span class="live__bar-label">{{ b.label }}</span>
                <div class="live__bar-track"><i :style="{ height: b.v + '%', background: b.color, animationDelay: i * 0.15 + 's' }"></i></div>
                <span class="live__bar-val">{{ b.v }}%</span>
              </div>
            </div>
          </div>
          <div class="float-chip float-chip--1 tg-float"><span class="fc-dot fc-dot--ok"></span>#23 已验证</div>
          <div class="float-chip float-chip--2 tg-float" style="animation-delay: 1.2s"><span class="fc-dot fc-dot--warn"></span>3 处偏差已定位</div>
        </div>

        <!-- 指标曲线:三线(覆盖率/一致性/基线) + 面积阴影 + 交叉坐标标记 -->
        <div ref="curveEl" class="hero-curve" :class="{ 'is-inview': curveInView }" aria-hidden="true">
          <svg viewBox="0 0 1200 250" preserveAspectRatio="none">
            <defs>
              <linearGradient id="hc-grad" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="rgba(201, 155, 63, 0.22)" />
                <stop offset="100%" stop-color="rgba(201, 155, 63, 0)" />
              </linearGradient>
            </defs>
            <!-- 网格与坐标轴 -->
            <g class="hc-axis">
              <line x1="60" y1="40" x2="1140" y2="40" />
              <line x1="60" y1="115" x2="1140" y2="115" />
              <line x1="60" y1="190" x2="1140" y2="190" />
              <text class="hc-tick dot" x="46" y="44" text-anchor="end">100</text>
              <text class="hc-tick dot" x="46" y="119" text-anchor="end">75</text>
              <text class="hc-tick dot" x="46" y="194" text-anchor="end">50</text>
              <text class="hc-tick dot" x="46" y="244" text-anchor="end">25</text>
              <text class="hc-tick dot" x="60" y="246">APR</text>
              <text class="hc-tick dot" x="330" y="246" text-anchor="middle">MAY</text>
              <text class="hc-tick dot" x="600" y="246" text-anchor="middle">JUN</text>
              <text class="hc-tick dot" x="870" y="246" text-anchor="middle">JUL</text>
              <text class="hc-tick dot" x="1140" y="246" text-anchor="middle">AUG</text>
            </g>
            <!-- 目标线 -->
            <path class="hc-goal" d="M60 58 H1140" />
            <text class="hc-goal-label dot" x="66" y="52">GOAL · 96%</text>
            <!-- 基线(未校验) -->
            <path class="hc-base" d="M60 218 H1140" />
            <text class="hc-base-label dot" x="66" y="212">BASELINE · 61%</text>
            <!-- 主线面积阴影 -->
            <path class="hc-area" d="M60 206 C 180 214, 260 196, 360 184 S 560 152, 680 130 S 900 96, 1000 82 S 1100 72, 1140 70 L 1140 250 L 60 250 Z" fill="url(#hc-grad)" />
            <!-- 一致性得分线(橄榄) -->
            <path class="hc-line2" d="M60 224 C 190 230, 290 218, 400 208 S 620 180, 750 152 S 980 112, 1140 92" />
            <!-- 需求覆盖率主线(金) -->
            <path class="hc-line" d="M60 206 C 180 214, 260 196, 360 184 S 560 152, 680 130 S 900 96, 1000 82 S 1100 72, 1140 70" />
            <circle class="hc-pulse" cx="1140" cy="70" r="5.5" />
            <circle class="hc-halo" cx="1140" cy="70" r="5" />
            <!-- 末端坐标垂线 + 坐标 -->
            <g class="hc-drop">
              <line x1="1140" y1="76" x2="1140" y2="240" />
              <line x1="1132" y1="240" x2="1148" y2="240" />
              <text class="hc-coord dot" x="1130" y="228" text-anchor="end">92% · AUG</text>
            </g>
            <!-- 途中交叉坐标标记 -->
            <g class="hc-mark hc-mark--1">
              <circle class="hc-mark-halo" cx="360" cy="184" r="6" />
              <circle class="hc-mark-dot" cx="360" cy="184" r="5.5" />
              <text class="hc-coord dot" x="360" y="164" text-anchor="middle">REQ #07 · 62%</text>
            </g>
            <g class="hc-mark hc-mark--2">
              <circle class="hc-mark-halo" cx="750" cy="152" r="6" />
              <circle class="hc-mark-dot" cx="750" cy="152" r="5.5" />
              <text class="hc-coord dot" x="750" y="132" text-anchor="middle">REQ #15 · 81%</text>
            </g>
          </svg>
          <div class="hc-legend">
            <span class="hc-lg hc-lg--gold"><i></i>需求覆盖率</span>
            <span class="hc-lg hc-lg--olive"><i></i>一致性得分</span>
            <span class="hc-lg hc-lg--gray"><i></i>基线 · 未校验</span>
          </div>
        </div>

        <!-- 滚动提示 -->
        <div class="scroll-cue" aria-hidden="true">
          <span class="scroll-cue__label dot">SCROLL</span>
          <span class="scroll-cue__track"><i></i></span>
        </div>
      </section>

      <!-- ================= 信任条:跑马灯 ================= -->
      <div class="trustbar">
        <span class="trustbar__label">支持主流工程与格式</span>
        <div class="marquee">
          <div class="marquee__track">
            <template v-for="n in 2" :key="n">
              <span class="trust-chip">Word</span>
              <span class="trust-chip">PDF</span>
              <span class="trust-chip">Markdown</span>
              <span class="trust-chip trust-chip--dot"></span>
              <span class="trust-chip">Maven</span>
              <span class="trust-chip">Gradle</span>
              <span class="trust-chip trust-chip--dot"></span>
              <span class="trust-chip">Java 8+</span>
              <span class="trust-chip">SpringBoot</span>
              <span class="trust-chip trust-chip--dot"></span>
              <span class="trust-chip">Alloy</span>
              <span class="trust-chip">Soot CFG</span>
            </template>
          </div>
        </div>
      </div>

      <!-- ================= 四张技术示意图(base.org 风,暖金改色) ================= -->
      <section class="feat">
        <div class="feat__grid">
          <!-- 图一:一致性引擎仪表盘 -->
          <div class="feat__item rv-up tg-reveal" v-reveal>
            <div class="feat__art">
              <svg class="fd" viewBox="0 0 340 230" fill="none" aria-hidden="true">
                <rect x="18" y="46" width="54" height="54" rx="10" class="fda-panel" />
                <circle cx="45" cy="73" r="12" class="fda-ring fda-ring--olive" />
                <path d="M40 68h10M40 73h10M45 68v10" class="fda-glyph" />
                <rect x="18" y="116" width="54" height="54" rx="10" class="fda-panel" />
                <circle cx="45" cy="143" r="12" class="fda-ring fda-ring--dotted fda-spin" />
                <circle cx="45" cy="143" r="3.5" class="fda-fill-clay" />
                <rect x="268" y="46" width="54" height="54" rx="10" class="fda-panel" />
                <path d="M295 60l3.4 9.6L308 73l-9.6 3.4L295 86l-3.4-9.6L282 73l9.6-3.4z" class="fda-fill-gold" />
                <rect x="268" y="116" width="54" height="54" rx="10" class="fda-panel" />
                <g class="fda-ink">
                  <rect x="284" y="132" width="6" height="6" /><rect x="294" y="132" width="6" height="6" class="fda-blink" style="animation-delay:.6s" /><rect x="304" y="132" width="6" height="6" />
                  <rect x="284" y="142" width="6" height="6" class="fda-blink" /><rect x="294" y="142" width="6" height="6" class="fda-fill-gold" /><rect x="304" y="142" width="6" height="6" class="fda-blink" style="animation-delay:1.2s" />
                  <rect x="284" y="152" width="6" height="6" /><rect x="294" y="152" width="6" height="6" class="fda-blink" style="animation-delay:.3s" /><rect x="304" y="152" width="6" height="6" />
                </g>
                <rect x="92" y="24" width="156" height="158" rx="14" class="fda-panel fda-shadow" />
                <rect x="88" y="20" width="7" height="7" class="fda-handle" /><rect x="245" y="20" width="7" height="7" class="fda-handle" />
                <rect x="88" y="179" width="7" height="7" class="fda-handle" /><rect x="245" y="179" width="7" height="7" class="fda-handle" />
                <path d="M136 96a34 34 0 0 1 68 0" class="fda-track" />
                <path d="M136 96a34 34 0 0 1 68 0" class="fda-gauge" />
                <g transform="translate(170,96)"><g class="fda-needle"><line x1="0" y1="8" x2="0" y2="-30" class="fda-needle-line" /></g></g>
                <circle cx="170" cy="96" r="4" class="fda-fill-deep" />
                <g class="fda-ink">
                  <rect x="104" y="136" width="9" height="9" rx="1.5" /><rect x="117" y="136" width="9" height="9" rx="1.5" class="fda-fill-gold" /><rect x="130" y="136" width="9" height="9" rx="1.5" /><rect x="143" y="136" width="9" height="9" rx="1.5" class="fda-fill-olive fda-blink" style="animation-delay:.4s" /><rect x="156" y="136" width="9" height="9" rx="1.5" /><rect x="169" y="136" width="9" height="9" rx="1.5" class="fda-fill-cream" /><rect x="182" y="136" width="9" height="9" rx="1.5" class="fda-blink" style="animation-delay:.9s" /><rect x="195" y="136" width="9" height="9" rx="1.5" class="fda-fill-clay" /><rect x="208" y="136" width="9" height="9" rx="1.5" /><rect x="221" y="136" width="9" height="9" rx="1.5" class="fda-blink" style="animation-delay:1.5s" />
                  <rect x="104" y="149" width="9" height="9" rx="1.5" class="fda-fill-cream" /><rect x="117" y="149" width="9" height="9" rx="1.5" /><rect x="130" y="149" width="9" height="9" rx="1.5" class="fda-blink" style="animation-delay:1.1s" /><rect x="143" y="149" width="9" height="9" rx="1.5" class="fda-fill-gold" /><rect x="156" y="149" width="9" height="9" rx="1.5" /><rect x="169" y="149" width="9" height="9" rx="1.5" /><rect x="182" y="149" width="9" height="9" rx="1.5" class="fda-fill-olive" /><rect x="195" y="149" width="9" height="9" rx="1.5" /><rect x="208" y="149" width="9" height="9" rx="1.5" class="fda-blink" style="animation-delay:.2s" /><rect x="221" y="149" width="9" height="9" rx="1.5" class="fda-fill-cream" />
                </g>
                <rect x="104" y="166" width="132" height="10" rx="5" class="fda-track-h" />
                <rect x="104" y="166" width="52" height="10" rx="5" class="fda-strip" />
              </svg>
            </div>
            <h3>秒级、全自动、全天候</h3>
            <p>需求提交到一致性报告,分钟级完成。每日增量自动比对,无需人盯,永远在线,总是快速。</p>
          </div>

          <!-- 图二:文档到代码的汇聚管线 -->
          <div class="feat__item rv-up tg-reveal" v-reveal>
            <div class="feat__art">
              <svg class="fd" viewBox="0 0 340 230" fill="none" aria-hidden="true">
                <path d="M24 48H116C132 48 138 101 146 101" class="fda-line fda-flow" style="animation-delay:0s" />
                <path d="M24 88H120C136 88 140 109 146 109" class="fda-line fda-flow" style="animation-delay:.4s" />
                <path d="M24 142H120C136 142 140 121 146 121" class="fda-line fda-flow" style="animation-delay:.8s" />
                <path d="M24 182H116C132 182 138 129 146 129" class="fda-line fda-flow" style="animation-delay:1.2s" />
                <path d="M194 101C202 101 208 48 224 48H316" class="fda-line fda-flow" style="animation-delay:.2s" />
                <path d="M194 109C202 109 208 88 224 88H316" class="fda-line fda-flow" style="animation-delay:.6s" />
                <path d="M194 121C202 121 208 142 224 142H316" class="fda-line fda-flow" style="animation-delay:1s" />
                <path d="M194 129C202 129 208 182 224 182H316" class="fda-line fda-flow" style="animation-delay:1.4s" />
                <circle cx="24" cy="48" r="3" class="fda-node" /><circle cx="24" cy="88" r="3" class="fda-node" />
                <circle cx="24" cy="142" r="3" class="fda-node" /><circle cx="24" cy="182" r="3" class="fda-node" />
                <circle cx="316" cy="48" r="3" class="fda-node" /><circle cx="316" cy="88" r="3" class="fda-node" />
                <circle cx="316" cy="142" r="3" class="fda-node" /><circle cx="316" cy="182" r="3" class="fda-node" />
                <rect x="64" y="38" width="18" height="20" rx="3" class="fda-panel" />
                <path d="M68 44h10M68 49h10M68 54h6" class="fda-glyph" />
                <circle cx="76" cy="78" r="7" class="fda-ring fda-ring--olive" />
                <path d="M148 152l6 6-6 6-6-6z" class="fda-fill-clay" />
                <rect x="66" y="172" width="14" height="14" rx="2" class="fda-fill-cream fda-blink" style="animation-delay:.5s" />
                <circle cx="262" cy="38" r="7" class="fda-ring fda-ring--gold fda-pulse" />
                <rect x="256" y="80" width="14" height="14" rx="2" class="fda-panel fda-blink" style="animation-delay:1s" />
                <path d="M256 152l6 6-6 6-6-6z" class="fda-fill-olive" />
                <rect x="268" y="174" width="18" height="16" rx="3" class="fda-panel" />
                <path d="M272 180h10M272 185h7" class="fda-glyph" />
                <rect x="146" y="91" width="48" height="48" rx="9" class="fda-fill-gold" />
                <rect x="142" y="87" width="7" height="7" class="fda-handle fda-handle--on" /><rect x="191" y="87" width="7" height="7" class="fda-handle fda-handle--on" />
                <rect x="142" y="136" width="7" height="7" class="fda-handle fda-handle--on" /><rect x="191" y="136" width="7" height="7" class="fda-handle fda-handle--on" />
                <g fill="#fff">
                  <circle cx="160" cy="105" r="2.4" class="fda-blink" style="animation-delay:.2s" /><circle cx="170" cy="105" r="2.4" /><circle cx="180" cy="105" r="2.4" class="fda-blink" style="animation-delay:.8s" />
                  <circle cx="160" cy="115" r="2.4" class="fda-blink" style="animation-delay:.5s" /><circle cx="170" cy="115" r="2.8" /><circle cx="180" cy="115" r="2.4" class="fda-blink" style="animation-delay:1.1s" />
                  <circle cx="160" cy="125" r="2.4" class="fda-blink" style="animation-delay:.9s" /><circle cx="170" cy="125" r="2.4" /><circle cx="180" cy="125" r="2.4" class="fda-blink" style="animation-delay:.3s" />
                </g>
              </svg>
            </div>
            <h3>从文档到代码的直通管线</h3>
            <p>Word、PDF 与 Maven / Gradle 工程包一并吞下,经三维一致性引擎汇成一条可追溯的链路。</p>
          </div>

          <!-- 图三:需求-代码双向桥 -->
          <div class="feat__item rv-up tg-reveal" v-reveal>
            <div class="feat__art">
              <svg class="fd" viewBox="0 0 340 230" fill="none" aria-hidden="true">
                <line x1="28" y1="30" x2="312" y2="30" class="fda-line" />
                <rect x="46" y="27" width="6" height="6" class="fda-node-sq" /><circle cx="88" cy="30" r="3" class="fda-node" />
                <rect x="118" y="27" width="6" height="6" class="fda-node-sq fda-fill-gold" /><circle cx="160" cy="30" r="3" class="fda-node" />
                <rect x="188" y="27" width="6" height="6" class="fda-node-sq" /><circle cx="230" cy="30" r="3" class="fda-node fda-fill-olive" />
                <rect x="258" y="27" width="6" height="6" class="fda-node-sq" />
                <line x1="28" y1="200" x2="312" y2="200" class="fda-line" />
                <rect x="58" y="197" width="6" height="6" class="fda-node-sq" /><circle cx="100" cy="200" r="3" class="fda-node" />
                <rect x="142" y="197" width="6" height="6" class="fda-node-sq fda-fill-olive" /><circle cx="188" cy="200" r="3" class="fda-node" />
                <rect x="216" y="197" width="6" height="6" class="fda-node-sq" /><circle cx="258" cy="200" r="3" class="fda-node" />
                <rect x="292" y="197" width="6" height="6" class="fda-node-sq fda-fill-gold" />
                <circle r="3" class="fda-fill-deep"><animateMotion dur="5s" repeatCount="indefinite" path="M28 30H312" /></circle>
                <circle r="3" class="fda-fill-olive"><animateMotion dur="6s" repeatCount="indefinite" path="M312 200H28" /></circle>
                <rect x="30" y="102" width="58" height="52" rx="9" class="fda-panel fda-shadow" />
                <path d="M42 116h24M42 124h24M42 132h15" class="fda-glyph" />
                <path d="M58 112l8 0 0 8z" class="fda-fill-cream" />
                <path d="M88 128C102 100 138 100 152 112" class="fda-arc fda-flow" />
                <path d="M88 128C102 156 138 156 152 144" class="fda-arc fda-flow" style="animation-delay:.5s" />
                <rect x="152" y="94" width="64" height="68" rx="10" class="fda-fill-gold fda-shadow" />
                <g fill="#fff">
                  <rect x="162" y="106" width="9" height="9" rx="1.5" class="fda-blink" style="animation-delay:.3s" /><rect x="176" y="106" width="9" height="9" rx="1.5" /><rect x="190" y="106" width="9" height="9" rx="1.5" class="fda-blink" style="animation-delay:.7s" />
                  <rect x="162" y="120" width="9" height="9" rx="1.5" /><rect x="176" y="120" width="9" height="9" rx="1.5" class="fda-blink" style="animation-delay:.1s" /><rect x="190" y="120" width="9" height="9" rx="1.5" />
                  <rect x="162" y="134" width="9" height="9" rx="1.5" class="fda-blink" style="animation-delay:.9s" /><rect x="176" y="134" width="9" height="9" rx="1.5" /><rect x="190" y="134" width="9" height="9" rx="1.5" class="fda-blink" style="animation-delay:.5s" />
                </g>
                <path d="M216 128C230 100 266 100 280 112" class="fda-arc fda-flow" style="animation-delay:.8s" />
                <path d="M216 128C230 156 266 156 280 144" class="fda-arc fda-flow" style="animation-delay:.2s" />
                <rect x="280" y="102" width="58" height="52" rx="9" class="fda-panel fda-shadow" />
                <circle cx="309" cy="128" r="14" class="fda-ring fda-ring--deep" />
                <text x="309" y="134" text-anchor="middle" class="fda-glyph-text">C</text>
              </svg>
            </div>
            <h3>需求与代码的双向桥梁</h3>
            <p>每一条需求都对应到具体的类与方法,每一处代码改动都能回溯到需求源头,变更影响一目了然。</p>
          </div>

          <!-- 图四:双引擎检测矩阵 -->
          <div class="feat__item rv-up tg-reveal" v-reveal>
            <div class="feat__art">
              <svg class="fd" viewBox="0 0 340 230" fill="none" aria-hidden="true">
                <circle cx="74" cy="115" r="30" class="fda-ring fda-ring--ink" />
                <circle cx="74" cy="115" r="20" class="fda-ring fda-ring--gold fda-spin" />
                <circle cx="74" cy="115" r="5" class="fda-fill-deep fda-pulse" />
                <line x1="104" y1="115" x2="136" y2="115" class="fda-line fda-flow" />
                <g class="fda-ink">
                  <rect x="136" y="79" width="14" height="14" rx="2" /><rect x="154" y="79" width="14" height="14" rx="2" class="fda-fill-cream" /><rect x="172" y="79" width="14" height="14" rx="2" /><rect x="190" y="79" width="14" height="14" rx="2" class="fda-fill-gold" /><rect x="208" y="79" width="14" height="14" rx="2" class="fda-blink" style="animation-delay:.8s" />
                  <rect x="136" y="97" width="14" height="14" rx="2" class="fda-blink" style="animation-delay:.2s" /><rect x="154" y="97" width="14" height="14" rx="2" /><rect x="172" y="97" width="14" height="14" rx="2" class="fda-fill-olive" /><rect x="190" y="97" width="14" height="14" rx="2" class="fda-blink" style="animation-delay:1.2s" /><rect x="208" y="97" width="14" height="14" rx="2" />
                  <rect x="136" y="115" width="14" height="14" rx="2" /><rect x="154" y="115" width="14" height="14" rx="2" class="fda-fill-clay" /><rect x="172" y="115" width="14" height="14" rx="2" class="fda-blink" style="animation-delay:.5s" /><rect x="190" y="115" width="14" height="14" rx="2" /><rect x="208" y="115" width="14" height="14" rx="2" class="fda-fill-cream" />
                  <rect x="136" y="133" width="14" height="14" rx="2" class="fda-fill-cream" /><rect x="154" y="133" width="14" height="14" rx="2" class="fda-blink" style="animation-delay:1s" /><rect x="172" y="133" width="14" height="14" rx="2" /><rect x="190" y="133" width="14" height="14" rx="2" class="fda-fill-gold" /><rect x="208" y="133" width="14" height="14" rx="2" />
                </g>
                <line x1="222" y1="115" x2="236" y2="115" class="fda-line fda-flow" style="animation-delay:.6s" />
                <circle cx="266" cy="115" r="30" class="fda-ring fda-ring--ink" />
                <path d="M266 103l12 20h-24z" class="fda-fill-olive" />
                <circle cx="266" cy="115" r="22" class="fda-ring fda-ring--clay fda-spin-rev" />
                <rect x="34" y="52" width="7" height="7" class="fda-node-sq fda-fill-cream" />
                <rect x="296" y="60" width="7" height="7" class="fda-node-sq" />
                <rect x="46" y="176" width="7" height="7" class="fda-node-sq fda-fill-olive fda-blink" style="animation-delay:.4s" />
                <rect x="300" y="168" width="7" height="7" class="fda-node-sq fda-fill-cream" />
                <circle cx="120" cy="176" r="3" class="fda-node fda-fill-gold" />
                <circle cx="238" cy="58" r="3" class="fda-node" />
              </svg>
            </div>
            <h3>双引擎检测矩阵</h3>
            <p>128 条静态规则 × LLM 语义扫描互补覆盖,严重度自动分级,修复建议定位到具体行。</p>
          </div>
        </div>
      </section>

      <!-- ================= 全宽产品仪表盘(聚光 + 倾斜) ================= -->
      <section class="dash-sec" id="demo">
        <span class="ghost-num dot" aria-hidden="true">01</span>
        <div class="dash tilt">
          <div class="dash__bar">
            <div class="dash__brand">
              <span class="dash__logo"><img src="@/assets/logo/logo-gold.png" alt="" aria-hidden="true" /></span>
              <b>order-system</b>
              <span class="dash__env">● Pro</span>
            </div>
            <div class="dash__work">Work with ▸ <b>自动分析</b></div>
          </div>
          <div class="dash__body">
            <aside class="dash__side" aria-hidden="true">
              <span class="ds-item">总览</span>
              <span class="ds-item ds-item--on">KPIs</span>
              <span class="ds-item">需求</span>
              <span class="ds-item">代码</span>
              <span class="ds-item">缺陷</span>
              <span class="ds-item">追溯</span>
              <span class="ds-item">报告</span>
            </aside>
            <div class="dash__main">
              <div class="dash__crumb">
                KPIs <i>▸</i> <b>需求覆盖率</b>
                <span class="dash__ranges"><span class="is-on">1W</span><span>4W</span><span>3M</span><span>12M</span></span>
              </div>
              <div class="dash__head">
                <h3>需求覆盖率</h3>
                <span class="dash__trend">↗ Trending to goal</span>
              </div>
              <div class="dash__meta">Last measured 今日 09:40 · Windows 12/12 · Sample 87</div>
              <div class="dash__row">
                <div class="dash__kpi">
                  <span>当前</span>
                  <b><span class="dot tg-count" v-countup="78">0</span><span class="dot">%</span></b>
                  <em>↗ 15.8%</em>
                </div>
                <div class="dash__kpi">
                  <span>潜在</span>
                  <b><span class="dot tg-count" v-countup="96">0</span><span class="dot">%</span></b>
                  <em>+18 pts</em>
                </div>
                <div class="dash__auto">
                  <i class="dash__switch"></i>
                  <div>
                    <b>自动比对 · 每日增量</b>
                    <span>上次运行 今日 09:40 · 无变更</span>
                  </div>
                </div>
              </div>
              <div class="dash__chart">
                <svg viewBox="0 0 900 260" preserveAspectRatio="none" aria-hidden="true">
                  <defs>
                    <linearGradient id="dash-fill" x1="0" y1="0" x2="0" y2="1">
                      <stop offset="0%" stop-color="rgba(201, 155, 63, 0.22)" />
                      <stop offset="100%" stop-color="rgba(201, 155, 63, 0)" />
                    </linearGradient>
                  </defs>
                  <line class="dc-grid" x1="0" y1="52" x2="900" y2="52" />
                  <line class="dc-grid" x1="0" y1="130" x2="900" y2="130" />
                  <line class="dc-goal2" x1="0" y1="26" x2="900" y2="26" />
                  <path
                    class="dc-area"
                    d="M0 208 C 110 216, 190 190, 300 196 S 520 158, 640 128 S 820 74, 900 52 L 900 260 L 0 260 Z"
                    fill="url(#dash-fill)"
                  />
                  <path class="dc-line" d="M0 208 C 110 216, 190 190, 300 196 S 520 158, 640 128 S 820 74, 900 52" />
                  <circle class="dc-dot" cx="900" cy="52" r="4" />
                </svg>
                <div class="dc-x"><span>三月</span><span>四月</span><span>五月</span><span>六月</span><span>七月</span></div>
                <span class="dc-note">↑ 更高更好 · 目标 96%</span>
                <!-- HUD 蓝图标注:取景框周期巡航在曲线关键点上(追溯:需求↔代码) -->
                <div class="dash-hud" aria-hidden="true">
                  <transition name="hud-swap" mode="out-in">
                    <div class="hud-pin" :class="{ 'hud-pin--flip': hudPins[hudIdx].flip }" :key="hudIdx" :style="{ '--hx': hudPins[hudIdx].x, '--hy': hudPins[hudIdx].y, '--ldx': hudPins[hudIdx].lx, '--ldy': hudPins[hudIdx].ly }">
                      <span class="hud-frame"><i></i><i></i><i></i><i></i></span>
                      <span class="hud-leader"></span>
                      <span class="hud-label dot">{{ hudPins[hudIdx].label }}</span>
                    </div>
                  </transition>
                </div>
              </div>
              <div class="dash__chips">
                <span class="is-on">① 解析需求</span>
                <span>② 构建 Alloy 规约</span>
                <span>③ 三维一致性比对</span>
                <span>④ 生成报告</span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- ================= 01 BUILD:左粘性步骤手风琴(金) ================= -->
      <section class="walk" id="build" :ref="(el) => setWalkRef(el, 0)">
        <div class="walk__pin">
          <div class="walk__grid">
            <div class="walk__copy">
              <span class="ghost-num ghost-num--sm dot" aria-hidden="true">02</span>
              <span class="kicker"><i></i>BUILD · 构建链路</span>
              <h2>五分钟,<br />接入你的第一个工程。</h2>
              <p class="walk__desc">从需求文档到代码模型,解析全自动。Word、PDF、Maven、Gradle 一并吞下,产出可验证的结构化规约。</p>
              <div class="wsteps" :style="{ '--sp': stepProg }">
                <div class="wstep" :class="{ on: walk[0] === 0 }" role="button" tabindex="0" @click="setStep(0, 0)" @keyup.enter="setStep(0, 0)">
                  <h3><span class="dot">01</span>上传需求文档</h3>
                  <p>Word / PDF / Markdown 批量导入,自动抽取条目并编号。</p>
                </div>
                <div class="wstep" :class="{ on: walk[0] === 1 }" role="button" tabindex="0" @click="setStep(0, 1)" @keyup.enter="setStep(0, 1)">
                  <h3><span class="dot">02</span>上传代码工程</h3>
                  <p>Maven / Gradle 工程 zip 包,JavaParser + Soot 构建 AST 与控制流图。</p>
                </div>
                <div class="wstep" :class="{ on: walk[0] === 2 }" role="button" tabindex="0" @click="setStep(0, 2)" @keyup.enter="setStep(0, 2)">
                  <h3><span class="dot">03</span>生成形式化规约</h3>
                  <p>需求语义自动编译为 Alloy 规约,为一致性校验做好准备。</p>
                </div>
              </div>
            </div>
            <div class="walk__stage stage--gold">
              <div class="wvis" :class="{ on: walk[0] === 0 }">
                <div class="wv-card">
                  <div class="wv-head"><b>上传交付物</b><span class="wv-tag">3 个文件</span></div>
                  <div class="wv-row">
                    <i class="wv-ic ic-doc">W</i>
                    <div class="wv-row-body"><b>requirements.docx</b><span>128 KB · 已解析 12 条需求</span></div>
                    <em class="is-ok">✓</em>
                  </div>
                  <div class="wv-row">
                    <i class="wv-ic ic-zip">J</i>
                    <div class="wv-row-body">
                      <b>order-service.zip</b>
                      <span>4.2 MB · 34 个类</span>
                      <div class="wv-prog"><i style="width: 72%"></i></div>
                    </div>
                    <em class="is-run">···</em>
                  </div>
                  <div class="wv-row">
                    <i class="wv-ic ic-md">M</i>
                    <div class="wv-row-body"><b>acceptance.md</b><span>18 KB · 排队中</span></div>
                    <em class="is-wait">–</em>
                  </div>
                </div>
              </div>
              <div class="wvis" :class="{ on: walk[0] === 1 }">
                <div class="wv-card">
                  <div class="wv-head"><b>解析流水线</b><span class="wv-tag">自动</span></div>
                  <div class="wv-pipe">
                    <span>需求条目抽取</span>
                    <div class="wv-prog wv-prog--lg"><i style="width: 100%"></i></div>
                    <em>12 / 12</em>
                  </div>
                  <div class="wv-pipe">
                    <span>AST + CFG 构建</span>
                    <div class="wv-prog wv-prog--lg"><i style="width: 88%; animation-delay: 0.2s"></i></div>
                    <em>34 类</em>
                  </div>
                  <div class="wv-pipe">
                    <span>语义向量索引</span>
                    <div class="wv-prog wv-prog--lg"><i style="width: 64%; animation-delay: 0.4s"></i></div>
                    <em>512 d</em>
                  </div>
                </div>
              </div>
              <div class="wvis" :class="{ on: walk[0] === 2 }">
                <div class="wv-card wv-card--center">
                  <i class="wv-check">✓</i>
                  <b>工程已就绪</b>
                  <p>12 条需求 · 34 个类 · 92 个方法<br />Alloy 规约 12 条已生成</p>
                  <div class="wv-cta">启动一致性校验 →</div>
                </div>
              </div>
            </div>
          </div>
        </div>
      </section>

      <!-- ================= 02 VERIFY:可拖动三维权重 · 实时一致性演示 ================= -->
      <section class="lab" id="verify">
        <div class="lab__grid">
          <div class="lab__panel rv-left tg-reveal" v-reveal>
            <div class="lab__sliders">
              <div class="vslider" v-for="s in sliders" :key="s.key">
                <div class="vslider__head">
                  <span>{{ s.label }} <em class="dot">{{ norm(s.key) }}</em></span>
                  <b>{{ subScore[s.key].toFixed(2) }}</b>
                </div>
                <input
                  type="range"
                  min="0"
                  max="100"
                  v-model.number="w[s.key]"
                  :style="{ '--p': w[s.key] + '%' }"
                  :aria-label="s.label + '权重'"
                />
              </div>
            </div>
            <div class="lab__result">
              <div class="lab__score">
                <span class="lab__score-label">Sim 综合得分</span>
                <b class="dot">{{ sim.toFixed(3) }}</b>
              </div>
              <div class="lab__meter"><i :style="{ width: sim * 100 + '%' }" :class="verdict.cls"></i></div>
              <div class="lab__verdict" :class="verdict.cls">
                <span class="lab__verdict-dot"></span>
                判定:{{ verdict.label }} · 阈值 T1 = 0.90 / T2 = 0.80
              </div>
            </div>
          </div>
          
          <div class="lab__copy rv-right tg-reveal" v-reveal>
            <span class="ghost-num ghost-num--sm dot" aria-hidden="true">03</span>
            <span class="kicker kicker--teal"><i></i>VERIFY · 一致性实验台</span>
            <h2>权重由你定,<br />结论立刻算给你看。</h2>
            <p>
              拖动滑块调整语义、约束、不变量三项权重,
              Sim 得分与判定实时联动——这就是 TraceGuard 三维一致性的全部逻辑,没有黑箱。
            </p>
            <div class="lab__formula dot">Sim(R,C) = α·语义 + β·约束 + γ·不变量</div>
          </div>
        </div>
      </section>

      <!-- ================= 04 DETECT:缺陷检测(IDE 编排视图) ================= -->
      <section class="ide-sec" id="detect">
        <div class="ide-head">
          <span class="kicker kicker--coral"><i></i>DETECT · 缺陷检测</span>
          <h2>缺陷在交付之前,就现形。</h2>
          <p>
            静态规则与大模型语义双引擎扫描,缺陷按严重程度自动分级,
            并给出定位到行的修复建议。
          </p>
        </div>
        <div class="ide-body">
          <span class="ghost-num ghost-num--sm dot" aria-hidden="true">04</span>
          <IdeCanvas />
        </div>
      </section>

      <!-- ================= 数据洞察:点阵大数字 ================= -->
      <section class="stats" id="insights">
        <span class="ghost-num dot" aria-hidden="true">05</span>
        <span class="kicker"><i></i>INSIGHTS · 数据洞察</span>
        <h2>每一次交付,都有数据佐证。</h2>
        <div class="stats__row">
          <div class="stat stat--dark rv-up tg-reveal" v-reveal>
            <span class="stat__tag dot">MEDIAN · 30D</span>
            <b class="dot"><span class="tg-count" v-countup="92">0</span>%</b>
            <span>需求覆盖率中位数</span>
            <em>▲ 4.6% 较上季度</em>
            <svg class="stat__spark" viewBox="0 0 120 36" preserveAspectRatio="none" aria-hidden="true">
              <path class="stat__spark-line" d="M2 30 C 20 28, 32 22, 48 20 S 82 10, 118 4" />
              <circle class="stat__spark-dot" cx="118" cy="4" r="2.6" />
            </svg>
          </div>
          <div class="stat rv-up tg-reveal" v-reveal>
            <span class="stat__tag dot">EFFICIENCY</span>
            <b class="dot"><span class="tg-count" v-countup="3">0</span>×</b>
            <span>缺陷发现效率提升</span>
            <em>▲ 1.2× 较传统人工</em>
            <svg class="stat__spark" viewBox="0 0 120 36" preserveAspectRatio="none" aria-hidden="true">
              <path class="stat__spark-line" d="M2 32 C 24 30, 40 26, 56 22 S 94 12, 118 6" />
              <circle class="stat__spark-dot" cx="118" cy="6" r="2.6" />
            </svg>
          </div>
          <div class="stat rv-up tg-reveal" v-reveal>
            <span class="stat__tag dot">TIME SAVED</span>
            <b class="dot">-<span class="tg-count" v-countup="40">0</span>%</b>
            <span>平均追溯耗时</span>
            <em>▼ 40% · 交付提速</em>
            <svg class="stat__spark" viewBox="0 0 120 36" preserveAspectRatio="none" aria-hidden="true">
              <path class="stat__spark-line stat__spark-line--down" d="M2 6 C 22 8, 40 14, 58 18 S 96 28, 118 31" />
              <circle class="stat__spark-dot stat__spark-dot--down" cx="118" cy="31" r="2.6" />
            </svg>
          </div>
        </div>
      </section>

      <!-- ================= 全景航线:点阵地球自转(借鉴 giga 全球滚动) ================= -->
      <section class="globe-sec" id="global">
        <div class="globe__head">
          <div class="globe__copy rv-left tg-reveal" v-reveal>
            <span class="kicker kicker--teal"><i></i>GLOBAL CODING · 全球编程活跃度</span>
            <h2>全球的代码,<br />正在这张地图上生长。</h2>
            <p>
              金色越深的地方,开发者敲击键盘越频繁。从硅谷到深圳,每一次提交
              都是对「说了什么」与「做了什么」的一次考验——而 TraceGuard
              让分布在世界各地的代码,始终与它的需求同频。
            </p>
          </div>
          <div class="globe__stat rv-right tg-reveal" v-reveal>
            <b class="dot"><span class="tg-count" v-countup="18">0</span></b>
            <span>全球高频编程枢纽</span>
          </div>
        </div>
        <div class="globe__stage rv-up tg-reveal" v-reveal>
          <canvas ref="globeRef"></canvas>
          <div class="globe__legend">
            <span class="gl gl--hi"><i></i>高频编程区</span>
            <span class="gl gl--mid"><i></i>活跃</span>
            <span class="gl gl--low"><i></i>稀疏</span>
            <span class="gl-cap dot">LIVE · GLOBAL CODING INTENSITY</span>
          </div>
          <span class="globe__hint dot">DRAG · 可拖动 / 自动巡航</span>
        </div>
      </section>

      <!-- ================= 工作流程:滚动驱动进度线时间轴 ================= -->
      <section class="flow" id="flow">
        <span class="ghost-num dot" aria-hidden="true">06</span>
        <span class="kicker"><i></i>WORKFLOW · 五步交付</span>
        <h2>五步,从需求到可信交付。</h2>
        <div class="flowtrack" ref="flowEl">
          <div class="flowtrack__line">
            <i :style="{ '--fp': flowProgress }"></i>
            <span class="flowtrack__comet" :style="{ '--fp': flowProgress }" aria-hidden="true"></span>
          </div>
          <div class="fstep" v-for="(s, i) in steps" :key="i" :class="{ on: flowProgress >= i / (steps.length - 1) - 0.02 }">
            <div class="fstep__node dot">{{ s.num }}</div>
            <div class="fstep__card" :class="'tone--' + s.tone">
              <div class="fstep__tile"><el-icon :size="18"><component :is="s.icon" /></el-icon></div>
              <span class="fstep__step dot">STEP {{ s.num }}</span>
              <h3>{{ s.title }}</h3>
              <p>{{ s.desc }}</p>
            </div>
          </div>
        </div>
      </section>

      <!-- ================= CTA:代码字符雨 + 旋转流光边框 + 磁性按钮 ================= -->
      <section class="cta-sec">
        <div class="cta tg-reveal" v-reveal>
          <div class="cta__inner">
            <canvas ref="rainRef" class="cta-rain" aria-hidden="true"></canvas>
            <h2>准备好对齐下一个需求了吗?</h2>
            <p>免费开始,五分钟内跑通第一次一致性分析。</p>
            <button class="btn-gold btn-gold--lg magnet" @click="router.push(primaryTarget())">
              <span>{{ loggedIn ? '进入工作台' : '开始使用 TraceGuard' }}</span>
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M3 8h10M9 4l4 4-4 4" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" />
              </svg>
            </button>
            <div class="cta__stack dot">Spring Boot · Vue 3 · MyBatis-Plus · JavaParser · Soot CFG · Alloy · LLM</div>
          </div>
        </div>
      </section>
    </main>

    <!-- ===== 页脚 ===== -->
    <footer class="footer">
      <div class="footer__grid">
        <div class="footer__brand">
          <div class="logo">
            <div class="logo-icon"><img src="@/assets/logo/logo-gold.png" alt="TraceGuard 标志" /></div>
            <div class="logo-text"><span>Trace</span>Guard</div>
          </div>
          <p>需求-代码一致性验证与缺陷自动检测系统。</p>
          <div class="footer__badges">
            <span class="fbadge">JWT</span>
            <span class="fbadge">RBAC</span>
            <span class="fbadge">AUDIT LOG</span>
          </div>
        </div>
        <div class="footer__col">
          <h4>产品</h4>
          <a href="#build">需求一致性</a>
          <a href="#detect">缺陷检测</a>
          <a href="#demo">追溯矩阵</a>
          <a href="#demo">报告导出</a>
        </div>
        <div class="footer__col">
          <h4>资源</h4>
          <a href="#insights">数据洞察</a>
          <a href="#verify">一致性实验台</a>
          <a href="#demo">产品演示</a>
        </div>
        <div class="footer__col">
          <h4>系统</h4>
          <a @click="router.push('/login')">登录</a>
          <a @click="router.push('/register')">注册</a>
          <a @click="router.push(loggedIn ? '/dashboard' : '/login')">进入工作台</a>
        </div>
      </div>
      <div class="footer__bottom">
        <span>© 2026 TraceGuard</span>
        <span>交付之前,让缺陷现形。</span>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import PixelField from '@/components/PixelField.vue'
import IdeCanvas from '@/components/IdeCanvas.vue'

const router = useRouter()
const scrolled = ref(false)
const announceOn = ref(true)

// ===== Hero 实时指标轮换(借鉴 giga:指标词轮换 + 曲线颜色联动) =====
const HERO_METRICS = [
  { val: '92%', label: ' 需求覆盖率 · 实时', legend: '需求覆盖率 · 随迭代持续提升', tone: 'gold' },
  { val: '3.2天', label: ' 缺陷提前发现 · 均值', legend: '缺陷发现 · 较交付节点提前', tone: 'green' },
  { val: '100%', label: ' 需求追溯完整 · 全链路', legend: '追溯完整度 · 需求到代码', tone: 'clay' }
]
const heroMetricIdx = ref(0)
const heroMetric = computed(() => HERO_METRICS[heroMetricIdx.value])
let metricTimer = null

// ===== 导航 scrollspy(当前区块高亮) =====
const spy = ref('')

// ===== 仪表盘 HUD 取景框巡航点(需求↔代码 追溯标注) =====
const hudPins = [
  { x: '18%', y: '62%', lx: '30px', ly: '56px', label: 'REQ #03 ↔ OrderService:12' },
  { x: '52%', y: '52%', lx: '26px', ly: '-44px', label: 'REQ #07 ↔ OrderService:42' },
  { x: '82%', y: '20%', lx: '30px', ly: '10px', label: 'REQ #23 · RESOLVED ✓', flip: true }
]
const hudIdx = ref(0)
let hudTimer = null

// hero 三线曲线:入视口后开始绘制(状态驱动,避免与 Vue class patch 冲突)
const curveEl = ref(null)
const curveInView = ref(false)
const spySections = [
  { id: 'demo', el: null },
  { id: 'build', el: null },
  { id: 'insights', el: null }
]

// 封面为统一开始界面:已登录用户展示"进入工作台",未登录展示"登录/免费开始"
const loggedIn = document.cookie.split(';').some((c) => c.trim().startsWith('tg_logged_in='))
const primaryTarget = () => (loggedIn ? '/dashboard' : '/login')

const reducedMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches
const isDesktop = () => window.matchMedia('(min-width: 961px)').matches

// ===== Hero kicker 解码动画(逐字符从乱码敲定) =====
const kickerText = ref(null)

function startScramble() {
  const el = kickerText.value
  if (!el || reducedMotion()) return
  const target = el.textContent
  const chars = 'ABCDEFGHJKLMNPQRSTUVWXYZ0123456789—·'
  let frame = 0
  const total = 30
  const tick = () => {
    frame++
    const settled = Math.floor((frame / total) * target.length)
    let out = ''
    for (let i = 0; i < target.length; i++) {
      if (target[i] === ' ') { out += ' '; continue }
      out += i < settled ? target[i] : chars[Math.floor(Math.random() * chars.length)]
    }
    el.textContent = out
    if (frame < total) setTimeout(tick, 60)
    else el.textContent = target
  }
  requestAnimationFrame(tick)
}

// ===== 迷你终端打字流(自动循环) =====
const TERM_SCRIPT = [
  { text: '$ traceguard analyze --project order-system', cls: 'cmd' },
  { text: '✓ 提取 12 条需求 · Alloy 规约已生成', cls: 'ok' },
  { text: '● 三维一致性校验 Sim(Ri,Cj)', cls: 'run' },
  { text: '✗ #07 缺少空值校验 · 已定位:42 行', cls: 'warn' },
  { text: '✓ 报告已导出 traceguard-report.pdf', cls: 'ok' }
]
const terminalLines = ref([])
let termStop = false

function startTerminal() {
  const MAX_VISIBLE = 4
  let li = 0
  let ci = 0
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms))
  terminalLines.value = [{ text: '', cls: TERM_SCRIPT[0].cls }]
  ;(async () => {
    while (!termStop) {
      const line = TERM_SCRIPT[li]
      while (ci <= line.text.length) {
        if (termStop) return
        const snapshot = [...terminalLines.value]
        snapshot[snapshot.length - 1] = { text: line.text.slice(0, ci), cls: line.cls }
        terminalLines.value = snapshot
        ci++
        await sleep(45)
      }
      await sleep(700)
      li = (li + 1) % TERM_SCRIPT.length
      ci = 0
      if (li === 0) await sleep(2600)
      terminalLines.value = [
        ...terminalLines.value.slice(-(MAX_VISIBLE - 1)),
        { text: '', cls: TERM_SCRIPT[li].cls }
      ]
    }
  })()
}

const liveBars = [
  { label: '语义', v: 92, color: 'var(--tg-accent)' },
  { label: '约束', v: 78, color: 'var(--tg-teal)' },
  { label: '不变量', v: 88, color: 'var(--tg-pink)' }
]

// ===== 点阵地球(全景航线):大陆点阵缓慢自转 + 航线脉冲 =====
const globeRef = ref(null)
let globeCleanup = null

// 60 列等距圆柱投影世界地图('X' 为陆地,绘制时自动补齐行宽)
const WORLD_MAP = [
  '..............................XX............................',
  '..............XXXX...........XXXXXXXXXXXXXXXXXXXXXXXXX......',
  '.....XXX......XXXX.........XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX..',
  '....XXXX.....XXX..........XXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXXX.',
  '...XXXXXX.....X..........XXXXX.XXXXXXXXXXXXXXXXXXXXXXXXXXX..',
  '....XXXXXXX............XXXXXX..XXXXXXXXXXXXXXXXXXXXXXXXXX...',
  '.....XXXXXXXX.........XXXXXX...XXXXXXXXXXXXX.XXXXXXXXX......',
  '......XXXXXXXX.......XXXXX....XXXXXXXX.XXXXX..XXXX.XXX......',
  '.......XXXXXXX.........XXX...XXXXXXXX..XXXX....XX...XX..X...',
  '.......XXXXXX..........XXXX.XXXXXXX...XXXX.....X....X....X..',
  '........XXXXXX..........XXXXXXXXXX....XXX..................X',
  '.........XXXXXX..........XXXXXXXX..........................X',
  '..........XXXXXX.........XXXX.XXX..XX..................XXXX.',
  '..........XXXXXXX.........XXX..XXX...................XXXXXXX',
  '..........XXXXXX...........XX...XXX.................XXXXXXX.',
  '..........XXXXX.............X....XX................XXXXXX...',
  '..........XXXX..............X....XX...............XXXXX.....',
  '..........XXXX....................X...............XXXX......',
  '..........XXXX....................XX.............XXXX.......',
  '..........XXX.....................X..............XXX........',
  '..........XXX....................................X..........',
  '..........XX.....................................X..........',
  '..........XX.................................................',
  '...........X.................................................'
]

  // 全球编程活跃度枢纽:[经度0-1, 纬度0-1, 权重](硅谷/纽约/伦敦/北京/上海/深圳/班加罗尔/东京…)
  const HUBS = [
    [0.16, 0.292, 1], [0.16, 0.236, 0.65], [0.294, 0.274, 0.85], [0.28, 0.257, 0.55],
    [0.228, 0.331, 0.5], [0.371, 0.631, 0.5], [0.5, 0.214, 0.9], [0.507, 0.228, 0.6],
    [0.537, 0.208, 0.65], [0.597, 0.322, 0.5], [0.716, 0.428, 0.8], [0.788, 0.492, 0.6],
    [0.823, 0.278, 0.9], [0.837, 0.327, 0.95], [0.817, 0.376, 0.9], [0.853, 0.292, 0.7],
    [0.888, 0.301, 0.9], [0.92, 0.688, 0.5]
  ]

  // 编码频率强度:距枢纽越近越密集(高斯衰减 × 权重)
  const intensityAt = (u, v) => {
    let best = 0
    for (const h of HUBS) {
      let du = Math.abs(u - h[0])
      if (du > 0.5) du = 1 - du
      const dv = v - h[1]
      const f = h[2] * Math.exp(-(du * du + dv * dv) / 0.006)
      if (f > best) best = f
    }
    return Math.min(1, best + Math.random() * 0.1)
  }

  // 强度分级配色:0 稀疏(暖棕灰) / 1 活跃(青铜) / 2 高频(黑金)
  const TIER_COL = [[134, 116, 84], [156, 110, 20], [88, 60, 6]]

function initGlobe() {
  const canvas = globeRef.value
  if (!canvas) return
  if (reducedMotion()) return

  const ctx = canvas.getContext('2d')
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  let cw = 0
  let ch = 0
  let raf = 0
  let running = false
  let clock = 0
  let last = 0
  let dots = []
  let hubs = []
  let sea = [] // 海面参差点阵(背景底纹)
  let gm = { x: -9999, y: -9999, seen: false } // 地图鼠标悬停状态
  let drag = { active: false, x: 0 } // 拖拽平移
  let dragOff = 0 // 拖拽累计偏移(经度)
  let dragVel = 0 // 释放惯性速度
  const ROT_MS = 74000 // 一整圈 74 秒:慢到能看清每一片大陆

  const CHARS = 'o8$+=*#.'
  const build = () => {
    dots = []
    hubs = []
    const rows = WORLD_MAP.length
    const COLS = 60
    const SUB = 2 // 每格 2×2 子采样,接近 giga 的 ASCII 密度
    for (let r = 0; r < rows; r++) {
      const row = (WORLD_MAP[r] || '').padEnd(COLS, '.')
      for (let c = 0; c < COLS; c++) {
        if (row[c] !== 'X') continue
        for (let sr = 0; sr < SUB; sr++) {
          for (let sc = 0; sc < SUB; sc++) {
            if (Math.random() < 0.22) continue // 随机留白,保持 ASCII 透气感
            const u0 = (c + (sc + 0.5) / SUB) / COLS // 经度 0-1(可循环滚动)
            const v0 = (r + (sr + 0.5) / SUB) / rows // 纬度 0-1(顶→底)
            const it = intensityAt(u0, v0)
            const tier = it > 0.62 ? 2 : it > 0.3 ? 1 : 0
            dots.push({
              u0,
              v0,
              chr: CHARS[Math.floor(Math.random() * CHARS.length)],
              tier,
              a: tier === 2 ? 0.9 + Math.random() * 0.1 : tier === 1 ? 0.7 + Math.random() * 0.22 : 0.52 + Math.random() * 0.18,
              s: 0.85 + Math.random() * 0.3
            })
          }
        }
      }
    }
    // 脉冲枢纽:全球高频编程城市
    hubs.length = 0
    for (const h of HUBS) hubs.push({ u0: h[0], v0: h[1], w: h[2] })
    // 海面参差点阵:覆盖整幅带区(略超地图纬度),行间明暗交错形成 giga 式底纹
    sea = []
    const seaRows = 30
    for (let rr = 0; rr < seaRows; rr++) {
      const v0 = -0.08 + (rr / (seaRows - 1)) * 1.16
      const rowAlpha = 0.45 + 0.55 * Math.sin(rr * 1.93 + 1)
      for (let cc = 0; cc < 66; cc++) {
        if (Math.random() < 0.18) continue
        sea.push({
          u0: cc / 66,
          v0,
          a: 0.05 + rowAlpha * 0.085,
          big: Math.random() < 0.06
        })
      }
    }
  }

  const resize = () => {
    const parent = canvas.parentElement
    cw = parent.clientWidth || 900
    ch = parent.clientHeight || 420
    canvas.width = cw * dpr
    canvas.height = ch * dpr
    canvas.style.width = cw + 'px'
    canvas.style.height = ch + 'px'
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    build()
  }

  const draw = (dt) => {
    ctx.clearRect(0, 0, cw, ch)
    clock += Math.min(dt, 50)
    const cx = cw / 2
    const span = cw * 1.24 // 字符地图全宽(两端略出血)
    const top = ch * 0.17
    const bandH = ch * 0.66
    // 拖拽惯性:释放后衰减滑行
    if (!drag.active && Math.abs(dragVel) > 0.0008) {
      dragOff += dragVel * (dt / 1000)
      dragVel *= Math.exp(-dt / 500)
    }
    const rot01 = (((clock / ROT_MS) % 1) + dragOff % 1 + 1) % 1 // 自动巡航 + 拖拽偏移
    ctx.font = '9.5px "JetBrains Mono", Consolas, monospace'
    ctx.textBaseline = 'middle'

    // 宽幅全景投影:横向循环 + 抛物线弯曲(两端上翘) + 顶部行略宽的透视
    const proj = (d) => {
      const u = (d.u0 + rot01) % 1
      const du = u - 0.5
      const edge = Math.abs(du) * 2
      const rowScale = 1.05 - d.v0 * 0.28
      const sx = cx + du * span * rowScale
      const arc = du * du * 4 * ch * 0.085
      const wave = Math.sin(d.v0 * 9 + clock * 0.0004) * 2.2
      return { sx, sy: top + d.v0 * bandH - arc + wave, edge }
    }
    // 海面参差底纹:与地图同步滚动/弯曲的暗点行,衬托大陆轮廓
    for (let i = 0; i < sea.length; i++) {
      const s = sea[i]
      const p = proj(s)
      if (p.edge > 1.06) continue
      const fade = 1 - Math.pow(p.edge, 2.2)
      const a = s.a * (0.4 + fade * 0.6)
      if (a < 0.02) continue
      ctx.fillStyle = 'rgba(150, 128, 96, ' + a.toFixed(3) + ')'
      const sz = s.big ? 2 : 1.2
      ctx.fillRect(p.sx, p.sy, sz, sz)
    }

    for (let i = 0; i < dots.length; i++) {
      const d = dots[i]
      const p = proj(d)
      if (p.edge > 1.02) continue
      const fade = 1 - Math.pow(p.edge, 2.4)
      let a = d.a * (0.3 + fade * 0.7)
      let col = TIER_COL[d.tier]
      let size = 10.5
      // 鼠标悬停:邻域字符散开、提亮为陶土金并放大(类似 giga 地图的扫视反馈)
      if (gm.seen) {
        const dx = p.sx - gm.x
        const dy = p.sy - gm.y
        const dm = Math.hypot(dx, dy)
        if (dm < 90) {
          const f = 1 - dm / 90
          const push = f * f * 10
          p.sx += (dx / (dm || 1)) * push
          p.sy += (dy / (dm || 1)) * push
          a = Math.min(1, a + f * 0.55)
          col = [166, 124, 34]
          size = 10.5 + f * 2.5
        }
      }
      if (a < 0.05) continue
      ctx.font = size.toFixed(1) + 'px "JetBrains Mono", Consolas, monospace'
      ctx.fillStyle = 'rgba(' + col.join(',') + ', ' + a.toFixed(3) + ')'
      ctx.fillText(d.chr, p.sx, p.sy)
    }

    // 高频编程城市:呼吸 '#' 标记 + 权重扩散环(深金)
    for (let h = 0; h < hubs.length; h++) {
      const d = hubs[h]
      const p = proj(d)
      if (p.edge > 0.9) continue
      const beat = Math.sin(clock * 0.0011 + h * 1.7)
      if (beat > 0 && d.w >= 0.8) {
        ctx.fillStyle = 'rgba(88, 60, 6, ' + (beat * (0.6 + d.w * 0.4)).toFixed(3) + ')'
        ctx.font = 'bold ' + (10 + d.w * 3).toFixed(1) + 'px "JetBrains Mono", Consolas, monospace'
        ctx.fillText('#', p.sx, p.sy)
      } else if (beat > 0) {
        ctx.fillStyle = 'rgba(140, 102, 20, ' + (beat * 0.8).toFixed(3) + ')'
        ctx.beginPath()
        ctx.arc(p.sx, p.sy, 2.2 + d.w, 0, Math.PI * 2)
        ctx.fill()
      }
      // 周期扩散环
      const ringR = (clock * 0.014 + h * 9) % 26
      ctx.beginPath()
      ctx.arc(p.sx, p.sy, 4 + ringR * (0.5 + d.w * 0.7), 0, Math.PI * 2)
      ctx.strokeStyle = 'rgba(140, 102, 20, ' + ((1 - ringR / 26) * (0.14 + d.w * 0.24)).toFixed(3) + ')'
      ctx.lineWidth = 1
      ctx.stroke()
    }

    // 航线弧线 + 巡游包:枢纽之间虚线弧,金色包沿弧巡航
    const hubPts = hubs.map((d) => ({ p: proj(d) }))
    const pairs = [
      [0, 13],
      [6, 10],
      [2, 5]
    ]
    for (let i = 0; i < pairs.length; i++) {
      const A = hubPts[pairs[i][0]]
      const B = hubPts[pairs[i][1]]
      if (!A || !B || !A.p || !B.p || A.p.edge > 0.8 || B.p.edge > 0.8) continue
      const pulse = Math.max(0, Math.sin(clock * 0.0006 + i * 2.1))
      const mx = (A.p.sx + B.p.sx) / 2
      const my = Math.min(A.p.sy, B.p.sy) - 44
      ctx.beginPath()
      ctx.moveTo(A.p.sx, A.p.sy)
      ctx.quadraticCurveTo(mx, my, B.p.sx, B.p.sy)
      ctx.strokeStyle = 'rgba(146, 108, 28, ' + (pulse * 0.42).toFixed(3) + ')'
      ctx.lineWidth = 1
      ctx.setLineDash([2, 6])
      ctx.stroke()
      ctx.setLineDash([])
      const tt = (clock * 0.00011 + i * 0.37) % 1
      const qx = (1 - tt) * (1 - tt) * A.p.sx + 2 * (1 - tt) * tt * mx + tt * tt * B.p.sx
      const qy = (1 - tt) * (1 - tt) * A.p.sy + 2 * (1 - tt) * tt * my + tt * tt * B.p.sy
      ctx.beginPath()
      ctx.arc(qx, qy, 2, 0, Math.PI * 2)
      ctx.fillStyle = 'rgba(146, 108, 28, ' + (0.6 + pulse * 0.4).toFixed(3) + ')'
      ctx.fill()
    }
  }

  const loop = (ts) => {
    const dt = last ? ts - last : 16
    last = ts
    try {
      draw(dt)
    } catch (e) {
      console.error('globe draw', e)
    }
    if (running) raf = requestAnimationFrame(loop)
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
  // 地图悬停:字符散开提亮
  const onGlobeMove = (e) => {
    const r = canvas.getBoundingClientRect()
    gm.x = e.clientX - r.left
    gm.y = e.clientY - r.top
    gm.seen = true
  }
  const onGlobeLeave = () => {
    gm.seen = false
  }
  // 拖拽平移:按住拖动,释放带惯性滑行
  const onGlobeDown = (e) => {
    drag.active = true
    drag.x = e.clientX
    dragVel = 0
    canvas.style.cursor = 'grabbing'
  }
  const onWinDragMove = (e) => {
    if (!drag.active) return
    const dx = e.clientX - drag.x
    drag.x = e.clientX
    dragOff += dx / (cw * 1.1)
    dragVel = (dx / (cw * 1.1)) * 60
  }
  const onWinDragUp = () => {
    if (!drag.active) return
    drag.active = false
    canvas.style.cursor = 'grab'
  }
  if (!reducedMotion()) {
    canvas.addEventListener('pointermove', onGlobeMove, { passive: true })
    canvas.addEventListener('pointerleave', onGlobeLeave)
    canvas.addEventListener('pointerdown', onGlobeDown)
    window.addEventListener('pointermove', onWinDragMove, { passive: true })
    window.addEventListener('pointerup', onWinDragUp, { passive: true })
    canvas.style.cursor = 'grab'
  }
  if (reducedMotion()) {
    draw(0) // 静态一帧
  } else {
    start()
  }

  let ro = null
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(() => {
      resize()
    })
    ro.observe(canvas.parentElement)
  }
  let ioVisible = true
  const io = new IntersectionObserver(
    ([entry]) => {
      ioVisible = entry.isIntersecting
      if (entry.isIntersecting) start()
      else stop()
    },
    { threshold: 0 }
  )
  io.observe(canvas)
  // 看门狗:渲染冻结恢复后自动重启自转
  const watchdog = setInterval(() => {
    if (!running && ioVisible && document.visibilityState === 'visible' && !reducedMotion()) start()
  }, 1000)
  window.addEventListener('resize', resize)
  globeCleanup = () => {
    stop()
    clearInterval(watchdog)
    io.disconnect()
    if (ro) ro.disconnect()
    window.removeEventListener('resize', resize)
    canvas.removeEventListener('pointermove', onGlobeMove)
    canvas.removeEventListener('pointerleave', onGlobeLeave)
    canvas.removeEventListener('pointerdown', onGlobeDown)
    window.removeEventListener('pointermove', onWinDragMove)
    window.removeEventListener('pointerup', onWinDragUp)
  }
}

// ===== 滚动叙事:滚轮下拖按滚动进度切换三个子框(与最早版本一致),点击可手动 =====
const walkEls = [null, null]
const walk = ref([0, 0])
const stepProg = ref(0) // 当前步内进度 0-1(驱动进度线)
const setWalkRef = (el, i) => {
  if (el) walkEls[i] = el
}
const setStep = (g, s) => {
  walk.value[g] = s
  stepProg.value = 0
}

const flowEl = ref(null)
const flowProgress = ref(0)
const progressBar = ref(null)
const steps = [
  { num: '01', icon: 'Document', tone: 'gold', title: '创建项目', desc: '填写项目信息与行业类型' },
  { num: '02', icon: 'Files', tone: 'honey', title: '上传需求', desc: '支持 Word / PDF / Markdown' },
  { num: '03', icon: 'FolderOpened', tone: 'violet', title: '上传代码', desc: 'Maven / Gradle 工程 zip 包' },
  { num: '04', icon: 'Cpu', tone: 'olive', title: '启动分析', desc: '一键执行全流程自动检测' },
  { num: '05', icon: 'DocumentChecked', tone: 'terra', title: '查看报告', desc: '缺陷、一致性、追溯一页掌握' }
]

const clamp01 = (v) => Math.min(1, Math.max(0, v))

const onScroll = () => {
  scrolled.value = window.scrollY > 12
  const doc = document.documentElement
  if (progressBar.value) {
    const p = doc.scrollHeight > window.innerHeight ? window.scrollY / (doc.scrollHeight - window.innerHeight) : 0
    progressBar.value.style.transform = 'scaleX(' + clamp01(p).toFixed(4) + ')'
  }
  // 滚动叙事:滚动进度驱动三个子框切换(桌面端 pinned 区段)
  if (isDesktop() && walkEls[0]) {
    const rect = walkEls[0].getBoundingClientRect()
    const pinLen = rect.height - window.innerHeight
    if (pinLen > 0) {
      const p = clamp01(-rect.top / pinLen)
      walk.value[0] = Math.min(2, Math.floor(p * 3))
      stepProg.value = clamp01(p * 3 - walk.value[0])
    }
  }
  // scrollspy:当前视口内的章节(演示/能力/洞察)
  let cur = ''
  spySections.forEach((s) => {
    if (!s.el) s.el = document.getElementById(s.id)
    if (s.el) {
      const r = s.el.getBoundingClientRect()
      if (r.top <= window.innerHeight * 0.4 && r.bottom > window.innerHeight * 0.25) cur = s.id
    }
  })
  spy.value = cur
  if (flowEl.value) {
    const rect = flowEl.value.getBoundingClientRect()
    flowProgress.value = clamp01((window.innerHeight * 0.72 - rect.top) / (rect.height + window.innerHeight * 0.1))
  }
}

const scrollTo = (sel) => {
  document.querySelector(sel)?.scrollIntoView({ behavior: 'smooth' })
}

// ===== VERIFY 一致性实验台:权重滑块 → 实时 Sim =====
const w = reactive({ a: 50, b: 30, c: 20 })
const sliders = [
  { key: 'a', label: 'α · 语义相似' },
  { key: 'b', label: 'β · 约束满足' },
  { key: 'c', label: 'γ · 不变量' }
]
const subScore = { a: 0.92, b: 0.87, c: 0.95 }
const norm = (k) => {
  const s = w.a + w.b + w.c || 1
  return (w[k] / s).toFixed(2)
}
const sim = computed(() => {
  const s = w.a + w.b + w.c || 1
  return (subScore.a * w.a + subScore.b * w.b + subScore.c * w.c) / s
})
const verdict = computed(() => {
  if (sim.value >= 0.9) return { label: '一致 · 通过', cls: 'is-ok' }
  if (sim.value >= 0.8) return { label: '基本一致 · 建议复核', cls: 'is-warn' }
  return { label: '不一致 · 需人工复核', cls: 'is-bad' }
})

// ===== 磁性按钮 =====
let magnetCleanup = null

function initMagnet() {
  if (reducedMotion() || !isDesktop()) return
  const btns = Array.from(document.querySelectorAll('.magnet'))
  const cleanups = []
  btns.forEach((btn) => {
    const onMove = (e) => {
      const rect = btn.getBoundingClientRect()
      const dx = e.clientX - (rect.left + rect.width / 2)
      const dy = e.clientY - (rect.top + rect.height / 2)
      btn.style.transform = 'translate(' + dx * 0.18 + 'px,' + dy * 0.22 + 'px)'
    }
    const onLeave = () => {
      btn.style.transform = ''
    }
    btn.addEventListener('pointermove', onMove)
    btn.addEventListener('pointerleave', onLeave)
    cleanups.push(() => {
      btn.removeEventListener('pointermove', onMove)
      btn.removeEventListener('pointerleave', onLeave)
    })
  })
  magnetCleanup = () => cleanups.forEach((fn) => fn())
}

// ===== 卡片 3D 倾斜(集群 + 仪表盘) =====
let tiltCleanup = null

function initTilt() {
  if (reducedMotion() || !isDesktop()) return
  const cards = Array.from(document.querySelectorAll('.tilt'))
  const cleanups = []
  cards.forEach((card) => {
    const onMove = (e) => {
      const rect = card.getBoundingClientRect()
      const cx = (e.clientX - rect.left) / rect.width - 0.5
      const cy = (e.clientY - rect.top) / rect.height - 0.5
      card.style.transform =
        'perspective(900px) rotateX(' + (cy * -5).toFixed(2) + 'deg) rotateY(' + (cx * 7).toFixed(2) + 'deg) translateY(-4px)'
      card.style.setProperty('--gx', ((cx + 0.5) * 100).toFixed(1) + '%')
      card.style.setProperty('--gy', ((cy + 0.5) * 100).toFixed(1) + '%')
    }
    const onLeave = () => {
      card.style.transform = ''
    }
    card.addEventListener('pointermove', onMove)
    card.addEventListener('pointerleave', onLeave)
    cleanups.push(() => {
      card.removeEventListener('pointermove', onMove)
      card.removeEventListener('pointerleave', onLeave)
    })
  })
  tiltCleanup = () => cleanups.forEach((fn) => fn())
}

// ===== 光晕视差(背景 orb 跟随鼠标) =====
let parallaxCleanup = null

function initOrbParallax() {
  if (reducedMotion() || !isDesktop()) return
  const bg = document.querySelector('.landing-bg')
  if (!bg) return
  const onMove = (e) => {
    const nx = (e.clientX / window.innerWidth - 0.5) * 2
    const ny = (e.clientY / window.innerHeight - 0.5) * 2
    bg.style.setProperty('--orb-px', nx * 30 + 'px')
    bg.style.setProperty('--orb-py', ny * 22 + 'px')
  }
  const onLeave = () => {
    bg.style.setProperty('--orb-px', '0px')
    bg.style.setProperty('--orb-py', '0px')
  }
  window.addEventListener('mousemove', onMove, { passive: true })
  document.addEventListener('mouseleave', onLeave)
  parallaxCleanup = () => {
    window.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseleave', onLeave)
  }
}

// ===== CTA 代码字符雨:慢速列流 + 尾迹,暖灰为主、金/绿列点缀 =====
const rainRef = ref(null)
let rainCleanup = null

function initCodeRain() {
  const canvas = rainRef.value
  if (!canvas) return
  const reduced = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const ctx = canvas.getContext('2d')
  const dpr = Math.min(window.devicePixelRatio || 1, 2)
  let cw = 0
  let ch = 0
  let drops = []
  let raf = 0
  let running = false
  let last = 0
  const CHARS = '{}<>/=;+*01#$&|'
  const TRAIL = 7

  const resize = () => {
    const parent = canvas.parentElement
    cw = parent.clientWidth || 800
    ch = parent.clientHeight || 360
    canvas.width = cw * dpr
    canvas.height = ch * dpr
    canvas.style.width = cw + 'px'
    canvas.style.height = ch + 'px'
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    ctx.font = '11px "JetBrains Mono", Consolas, monospace'
    const colW = 17
    const cols = Math.ceil(cw / colW)
    drops = Array.from({ length: cols }, (_, i) => ({
      x: i * colW + 4,
      y: Math.random() * ch,
      lastEmit: 0,
      trail: [],
      v: 20 + Math.random() * 26, // 慢速下落
      gold: Math.random() < 0.12,
      green: Math.random() >= 0.12 && Math.random() < 0.19
    }))
  }

  const draw = (dt) => {
    ctx.clearRect(0, 0, cw, ch)
    for (const d of drops) {
      d.y += (d.v * dt) / 1000
      if (d.y - d.lastEmit > 13) {
        d.lastEmit = d.y
        d.trail.unshift({ y: d.y, c: CHARS[(Math.random() * CHARS.length) | 0] })
        if (d.trail.length > TRAIL) d.trail.pop()
      }
      if (d.y > ch + 40) {
        d.y = -20 - Math.random() * 120
        d.lastEmit = d.y
        d.trail = []
      }
      for (let i = 0; i < d.trail.length; i++) {
        const t = d.trail[i]
        const k = 1 - i / TRAIL
        const col = d.gold ? '184, 138, 47' : d.green ? '107, 142, 78' : '139, 122, 96'
        ctx.fillStyle = 'rgba(' + col + ', ' + ((d.gold ? 0.5 : d.green ? 0.45 : 0.28) * k).toFixed(3) + ')'
        ctx.fillText(t.c, d.x, t.y)
      }
      // 雨头稍亮
      if (d.trail.length) {
        const col = d.gold ? '201, 155, 63' : d.green ? '139, 178, 100' : '122, 105, 82'
        ctx.fillStyle = 'rgba(' + col + ', 0.75)'
        ctx.fillText(d.trail[0].c, d.x, d.trail[0].y)
      }
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

  resize()
  if (reduced) {
    draw(0) // 静态:一帧稀疏字符
  } else {
    start()
  }

  let ro = null
  if (typeof ResizeObserver !== 'undefined') {
    ro = new ResizeObserver(() => resize())
    ro.observe(canvas.parentElement)
  }
  let ioVisible = true
  const io = new IntersectionObserver(
    ([entry]) => {
      ioVisible = entry.isIntersecting
      if (entry.isIntersecting) start()
      else stop()
    },
    { threshold: 0 }
  )
  io.observe(canvas)
  const watchdog = setInterval(() => {
    if (!running && ioVisible && document.visibilityState === 'visible' && !reduced) start()
  }, 1000)

  rainCleanup = () => {
    stop()
    clearInterval(watchdog)
    io.disconnect()
    if (ro) ro.disconnect()
  }
}

onMounted(() => {
  window.addEventListener('scroll', onScroll, { passive: true })
  // 三线曲线入视口触发绘制
  if (curveEl.value) {
    const curveIo = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          curveInView.value = true
          curveIo.disconnect()
        }
      },
      { threshold: 0.25 }
    )
    curveIo.observe(curveEl.value)
  }
  startTerminal()
  startScramble()
  initGlobe()
  initCodeRain()
  initMagnet()
  initTilt()
  initOrbParallax()
  // 指标轮换 / HUD 巡航
  if (!reducedMotion()) {
    metricTimer = setInterval(() => {
      heroMetricIdx.value = (heroMetricIdx.value + 1) % HERO_METRICS.length
    }, 3400)
    hudTimer = setInterval(() => {
      hudIdx.value = (hudIdx.value + 1) % hudPins.length
    }, 4600)
  }
  onScroll()
})

onUnmounted(() => {
  window.removeEventListener('scroll', onScroll)
  termStop = true
  if (metricTimer) clearInterval(metricTimer)
  if (hudTimer) clearInterval(hudTimer)
  if (globeCleanup) globeCleanup()
  if (rainCleanup) rainCleanup()
  if (magnetCleanup) magnetCleanup()
  if (tiltCleanup) tiltCleanup()
  if (parallaxCleanup) parallaxCleanup()
})
</script>

<style>
/* 点阵字体(仅拉丁/数字),中文自动回退系统字体 */
@import url('https://fonts.googleapis.com/css2?family=Doto:wght@500;700;900&display=swap');
</style>

<style scoped>
.landing {
  position: relative;
  min-height: 100vh;
  scroll-behavior: smooth;
  background: var(--tg-bg-page);
  color: var(--tg-text-primary);

  --line: rgba(60, 45, 25, 0.1);
  --gold: #b88a2f;
  --gold-bright: #c99b3f;
  --gold-deep: #8f6b22;
  --teal: #5f8d7c;
  --coral: #b0653f;
  --panel-glass: rgba(255, 255, 255, 0.8);

  /* 可读性:landing 内次级文字加深(比全局令牌深一档) */
  --tg-text-secondary: #695e4d;
}

.dot {
  font-family: 'Doto', 'JetBrains Mono', 'Courier New', monospace;
  font-weight: 700;
}

/* ===== 多层背景 ===== */
.landing-bg {
  position: fixed;
  inset: 0;
  z-index: 0;
  overflow: hidden;
  pointer-events: none;
}

.landing-grid {
  position: absolute;
  inset: 0;
  background-image:
    linear-gradient(rgba(143, 107, 34, 0.05) 1px, transparent 1px),
    linear-gradient(90deg, rgba(143, 107, 34, 0.05) 1px, transparent 1px);
  background-size: 56px 56px;
  -webkit-mask-image: radial-gradient(ellipse 130% 100% at 50% 28%, #000 55%, transparent 100%);
  mask-image: radial-gradient(ellipse 130% 100% at 50% 28%, #000 55%, transparent 100%);
}

.landing-orb {
  position: absolute;
  border-radius: 50%;
  filter: blur(70px);
  will-change: transform;
}

.landing-orb--1 {
  width: 460px;
  height: 460px;
  left: -140px;
  top: -100px;
  background: radial-gradient(circle at 30% 30%, rgba(201, 155, 63, 0.26), transparent 70%);
  animation: orb-drift 36s ease-in-out infinite alternate;
}

.landing-orb--2 {
  width: 400px;
  height: 400px;
  right: -120px;
  top: 24%;
  background: radial-gradient(circle at 30% 30%, rgba(232, 200, 119, 0.3), transparent 70%);
  animation: orb-drift 44s ease-in-out infinite alternate-reverse;
}

.landing-orb--3 {
  width: 380px;
  height: 380px;
  left: 30%;
  bottom: -160px;
  background: radial-gradient(circle at 30% 30%, rgba(176, 101, 63, 0.14), transparent 70%);
  animation: orb-drift 40s ease-in-out infinite alternate;
}

@keyframes orb-drift {
  from {
    transform: translate3d(var(--orb-px, 0px), var(--orb-py, 0px), 0) scale(1);
  }
  to {
    transform: translate3d(calc(var(--orb-px, 0px) + 56px), calc(var(--orb-py, 0px) + 38px), 0) scale(1.12);
  }
}

/* 光标柔光晕 */
/* 顶部滚动进度条 */
.scroll-progress {
  position: fixed;
  left: 0;
  right: 0;
  top: 0;
  height: 2.5px;
  z-index: 90;
  background: rgba(60, 45, 25, 0.06);
  pointer-events: none;
}

.scroll-progress i {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #8f6b22, #c99b3f, #e8c877);
  transform-origin: left;
  transform: scaleX(0);
  will-change: transform;
}

/* ===== 公告条 ===== */
.announce {
  position: relative;
  z-index: 60;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 10px;
  padding: 9px 48px;
  background: linear-gradient(90deg, #fdf6e6, #faf1dc, #fdf6e6);
  border-bottom: 1px solid var(--line);
  font-size: 12.5px;
  color: var(--tg-text-secondary);
  text-align: center;
}

.announce__dot {
  width: 6px;
  height: 6px;
  border-radius: 2px;
  background: var(--gold-bright);
  box-shadow: 0 0 10px rgba(201, 155, 63, 0.7);
  flex-shrink: 0;
}

.announce a {
  color: var(--gold-deep);
  font-weight: 600;
  text-decoration: underline;
  text-underline-offset: 3px;
}

.announce__close {
  position: absolute;
  right: 14px;
  top: 50%;
  transform: translateY(-50%);
  background: none;
  border: none;
  color: var(--tg-text-secondary);
  font-size: 16px;
  cursor: pointer;
  padding: 4px 8px;
}

.announce__close:hover {
  color: var(--tg-text-primary);
}

/* ===== 导航 ===== */
.landing-nav {
  position: sticky;
  top: 0;
  z-index: 50;
  background: rgba(250, 246, 239, 0.55);
  backdrop-filter: blur(24px) saturate(1.4);
  -webkit-backdrop-filter: blur(24px) saturate(1.4);
  border-bottom: 1px solid transparent;
  transition: background-color 0.3s ease, border-color 0.3s ease, box-shadow 0.3s ease;
}

.landing-nav.is-scrolled {
  background: rgba(250, 246, 239, 0.85);
  border-bottom-color: var(--line);
  box-shadow: 0 4px 24px rgba(60, 45, 25, 0.05);
}

.landing-nav__inner {
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 40px;
  height: 64px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 24px;
}

.logo {
  display: flex;
  align-items: center;
  gap: 10px;
}

.logo-icon {
  width: 34px;
  height: 34px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.logo-icon img {
  width: 100%;
  height: 100%;
  display: block;
  filter: drop-shadow(0 3px 10px rgba(143, 107, 34, 0.25));
}

.logo-text {
  font-size: 17px;
  font-weight: 700;
  color: var(--tg-text-primary);
  letter-spacing: 0.3px;
}

.logo-text span {
  color: var(--gold-deep);
}

.landing-nav__links {
  display: flex;
  gap: 26px;
}

.landing-nav__links a {
  position: relative;
  text-decoration: none;
  color: var(--tg-text-secondary);
  font-size: 13.5px;
  font-weight: 500;
  transition: color 0.2s ease, transform 0.2s ease;
}

/* scrollspy 下划线:滑入式 */
.landing-nav__links a::after {
  content: '';
  position: absolute;
  left: 10%;
  right: 10%;
  bottom: -6px;
  height: 2px;
  border-radius: 2px;
  background: linear-gradient(90deg, #8f6b22, #e8c877);
  transform: scaleX(0);
  transform-origin: center;
  transition: transform 0.45s var(--tg-ease);
}

.landing-nav__links a:hover::after,
.landing-nav__links a.on::after {
  transform: scaleX(1);
}

.landing-nav__links a.on {
  color: var(--gold-deep);
  font-weight: 600;
}

.landing-nav__links a:hover {
  color: var(--gold-deep);
  transform: translateY(-1px);
}

.landing-nav__actions {
  display: flex;
  align-items: center;
  gap: 14px;
}

.nav-login {
  background: none;
  border: none;
  color: var(--tg-text-secondary);
  font-size: 13.5px;
  font-weight: 500;
  cursor: pointer;
  padding: 6px 2px;
  transition: color 0.2s ease;
}

.nav-login:hover {
  color: var(--gold-deep);
}

/* ===== 按钮(金渐变 / ghost / 磁性 / 扫光) ===== */
.btn-gold {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: linear-gradient(135deg, #8f6b22 0%, #c99b3f 55%, #b88a2f 100%);
  color: #fff;
  border: none;
  border-radius: 999px;
  padding: 13px 26px;
  font-size: 15px;
  font-weight: 650;
  font-family: inherit;
  cursor: pointer;
  overflow: hidden;
  box-shadow: 0 8px 24px rgba(143, 107, 34, 0.28);
  transition: transform 0.25s var(--tg-ease), box-shadow 0.25s ease;
  will-change: transform;
}

.btn-gold::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: -60%;
  width: 45%;
  background: linear-gradient(105deg, transparent, rgba(255, 255, 255, 0.45), transparent);
  transform: skewX(-20deg);
  pointer-events: none;
}

.btn-gold:hover::after {
  animation: btn-shine 1.2s ease;
}

@keyframes btn-shine {
  to {
    left: 130%;
  }
}

.btn-gold:hover {
  box-shadow: 0 14px 34px rgba(143, 107, 34, 0.36);
}

.btn-gold--sm {
  padding: 9px 18px;
  font-size: 13.5px;
}

.btn-gold--lg {
  padding: 15px 32px;
  font-size: 16px;
}

.btn-ghost {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  background: rgba(255, 255, 255, 0.7);
  color: var(--tg-text-primary);
  border: 1px solid rgba(60, 45, 25, 0.16);
  border-radius: 999px;
  padding: 12px 24px;
  font-size: 15px;
  font-weight: 550;
  font-family: inherit;
  cursor: pointer;
  transition: border-color 0.25s ease, color 0.25s ease, transform 0.25s var(--tg-ease), background 0.25s ease;
  will-change: transform;
}

.btn-ghost:hover {
  border-color: rgba(201, 155, 63, 0.6);
  color: var(--gold-deep);
  background: #fff;
  transform: translateY(-2px);
}

/* ===== 内容容器 ===== */
.landing-main {
  position: relative;
  z-index: 1;
  max-width: 1200px;
  margin: 0 auto;
  padding: 0 56px 72px;
}

/* ===== Hero ===== */
.hero {
  position: relative;
  padding: 88px 0 0;
}

.hero-copy {
  max-width: 640px;
}

.hero-pill {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 6px 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.8);
  border: 1px solid var(--line);
  color: var(--tg-text-secondary);
  font-size: 12.5px;
  font-weight: 500;
  box-shadow: var(--tg-shadow-card);
}

.hero-pill__metric {
  display: inline-flex;
  align-items: baseline;
  white-space: nowrap;
}

.hero-pill__metric em {
  font-style: normal;
  font-weight: 800;
  font-size: 13px;
  margin-right: 7px;
}

/* 指标轮换:纵向翻牌 + 轻模糊 */
.metric-flip-enter-active,
.metric-flip-leave-active {
  transition: opacity 0.5s var(--tg-ease), transform 0.5s var(--tg-ease), filter 0.5s var(--tg-ease);
}

.metric-flip-enter-from {
  opacity: 0;
  transform: translateY(12px);
  filter: blur(3px);
}

.metric-flip-leave-to {
  opacity: 0;
  transform: translateY(-12px);
  filter: blur(3px);
}

/* 指标色调(与曲线联动):金 / 橄榄 / 陶土 */
.tone-text--gold { color: var(--gold-deep); }
.tone-text--green { color: var(--tg-success); }
.tone-text--clay { color: var(--coral); }

.tg-live-dot--green {
  background: var(--tg-success);
  box-shadow: 0 0 10px rgba(107, 142, 78, 0.7);
}

.tg-live-dot--clay {
  background: var(--coral);
  box-shadow: 0 0 10px rgba(176, 101, 63, 0.7);
}

.hero-kicker {
  margin-top: 26px;
  font-size: 12px;
  letter-spacing: 0.22em;
  color: rgba(184, 138, 47, 0.75);
  min-height: 16px;
}

.hero-title {
  margin: 16px 0 0;
  font-size: clamp(40px, 6vw, 74px);
  font-weight: 800;
  letter-spacing: -0.015em;
  line-height: 1.16;
  color: var(--tg-text-primary);
}

.hero-title .tg-word {
  display: inline-block;
  animation: word-rise 1.5s var(--tg-ease) both;
}

@keyframes word-rise {
  from {
    opacity: 0;
    transform: translateY(34px) rotate(2deg);
  }
  to {
    opacity: 1;
    transform: none;
  }
}

.tg-gradient-text {
  background: linear-gradient(115deg, #8f6b22 0%, #c99b3f 55%, #d9a94e 100%);
  -webkit-background-clip: text;
  background-clip: text;
  color: transparent;
}

.hero-sub {
  margin: 24px 0 0;
  max-width: 560px;
  font-size: clamp(15px, 1.6vw, 17.5px);
  line-height: 1.8;
  color: var(--tg-text-secondary);
}

.hero-actions {
  margin-top: 34px;
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

/* 卡片集群 */
.hero-cluster {
  position: absolute;
  right: 0;
  top: 96px;
  width: 430px;
  height: 420px;
}

.tilt {
  transition: transform 0.7s var(--tg-ease), box-shadow 0.5s ease;
  will-change: transform;
}

.term {
  position: absolute;
  left: 0;
  top: 30px;
  width: 318px;
  border-radius: 18px;
  border: 1px solid rgba(232, 200, 119, 0.22);
  background: linear-gradient(180deg, #3a2f1d, #2a2216);
  box-shadow: 0 24px 60px rgba(43, 30, 10, 0.28), inset 0 1px 0 rgba(232, 200, 119, 0.12);
  overflow: hidden;
}

.term__bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 14px;
  border-bottom: 1px solid rgba(232, 200, 119, 0.16);
}

.term__dots {
  display: flex;
  gap: 6px;
}

.term__dots i {
  width: 9px;
  height: 9px;
  border-radius: 50%;
}

.term__dots i:nth-child(1) { background: #e8836a; }
.term__dots i:nth-child(2) { background: #e8c877; }
.term__dots i:nth-child(3) { background: #9bbf6e; }

.term__file {
  flex: 1;
  font-family: var(--tg-font-mono);
  font-size: 11.5px;
  color: #c2b493;
}

.term__status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: #f8f1df;
}

.term__body {
  padding: 12px 14px 14px;
  min-height: 148px;
  font-family: var(--tg-font-mono);
  font-size: 11px;
  line-height: 1.95;
}

.term__line {
  display: flex;
  align-items: baseline;
  gap: 6px;
  white-space: nowrap;
  overflow: hidden;
}

.term__line--cmd { color: #ffd88e; }
.term__line--ok { color: #c4e29c; }
.term__line--run { color: #e8c877; }
.term__line--warn { color: #ffab45; }

.term__cursor {
  width: 6px;
  height: 12px;
  background: #ffd88e;
  animation: cursor-blink 1s steps(1) infinite;
  flex-shrink: 0;
  transform: translateY(2px);
}

@keyframes cursor-blink {
  50% { opacity: 0; }
}

.term__foot {
  padding: 9px 14px;
  border-top: 1px solid rgba(232, 200, 119, 0.16);
}

.term__progress {
  height: 5px;
  border-radius: 5px;
  background: rgba(232, 200, 119, 0.12);
  overflow: hidden;
}

.term__progress i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #8f6b22, #e8c877);
  animation: term-progress 9s ease-in-out infinite;
}

@keyframes term-progress {
  0% { width: 12%; }
  50% { width: 84%; }
  100% { width: 26%; }
}

.live {
  position: absolute;
  right: 0;
  bottom: 24px;
  width: 236px;
  border-radius: 18px;
  background: var(--panel-glass);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  border: 1px solid rgba(60, 45, 25, 0.1);
  box-shadow: 0 20px 50px rgba(60, 45, 25, 0.12);
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.live__head {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--tg-text-primary);
}

.live__ring {
  position: relative;
  width: 104px;
  height: 104px;
  margin: 4px auto;
}

.live__ring svg {
  width: 100%;
  height: 100%;
  transform: rotate(-90deg);
}

.live__ring-track {
  fill: none;
  stroke: rgba(60, 45, 25, 0.08);
  stroke-width: 10;
}

.live__ring-bar {
  fill: none;
  stroke: url(#live-gold);
  stroke: #c99b3f;
  stroke-width: 10;
  stroke-linecap: round;
  animation: ring-draw 3.2s var(--tg-ease) 1s both;
}

@keyframes ring-draw {
  to { stroke-dashoffset: 23; }
}

.live__ring-center {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
}

.live__ring-center b {
  font-size: 28px;
  font-weight: 800;
  color: var(--tg-text-primary);
}

.live__ring-center span {
  font-size: 13px;
  color: var(--tg-text-secondary);
  margin: 8px 0 0 2px;
}

.live__bars {
  display: flex;
  justify-content: center;
  gap: 18px;
}

.live__bar {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
}

.live__bar-label {
  font-size: 10.5px;
  color: var(--tg-text-secondary);
}

.live__bar-track {
  width: 9px;
  height: 44px;
  border-radius: 5px;
  background: rgba(60, 45, 25, 0.07);
  overflow: hidden;
  display: flex;
  align-items: flex-end;
}

.live__bar-track i {
  display: block;
  width: 100%;
  border-radius: 5px;
  transform-origin: bottom;
  animation: bar-grow 1.8s var(--tg-ease) 1.6s both;
}

@keyframes bar-grow {
  from { transform: scaleY(0); }
  to { transform: scaleY(1); }
}

.live__bar-val {
  font-size: 11px;
  font-weight: 650;
  color: var(--tg-text-primary);
}

.float-chip {
  position: absolute;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 7px 13px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid rgba(60, 45, 25, 0.1);
  box-shadow: 0 10px 26px rgba(60, 45, 25, 0.12);
  font-size: 12px;
  font-weight: 600;
  color: var(--tg-text-primary);
}

.float-chip--1 {
  right: 12px;
  top: 6px;
}

.float-chip--2 {
  left: 26px;
  bottom: -8px;
}

.fc-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.fc-dot--ok {
  background: var(--tg-success);
  box-shadow: 0 0 8px rgba(107, 142, 78, 0.7);
}

.fc-dot--warn {
  background: #e89b3c;
  box-shadow: 0 0 8px rgba(232, 155, 60, 0.7);
}

/* ===== hero 三线曲线:覆盖率(金) / 一致性(橄榄) / 基线(虚) ===== */
.hero-curve {
  position: relative;
  margin-top: 112px;
}

.hero-curve svg {
  display: block;
  width: 100%;
  height: 226px;
  overflow: visible;
}

.hc-axis line {
  stroke: rgba(60, 45, 25, 0.07);
}

.hc-tick {
  font-size: 10px;
  letter-spacing: 0.08em;
  fill: rgba(90, 80, 64, 0.8);
}

.hc-goal {
  stroke: rgba(107, 142, 78, 0.45);
  stroke-width: 1;
  stroke-dasharray: 4 6;
}

.hc-goal-label {
  font-size: 9.5px;
  letter-spacing: 0.14em;
  fill: rgba(92, 122, 65, 0.8);
}

.hc-base {
  stroke: rgba(60, 45, 25, 0.2);
  stroke-width: 1;
  stroke-dasharray: 2 5;
}

.hc-base-label {
  font-size: 9.5px;
  letter-spacing: 0.14em;
  fill: rgba(122, 111, 95, 0.6);
}

/* 线下方面积阴影 */
.hc-area {
  opacity: 0;
  transition: opacity 2.1s ease 1.7s;
}

.hero-curve.is-inview .hc-area {
  opacity: 1;
}

.hc-line {
  fill: none;
  stroke: #c99b3f;
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-dasharray: 1500;
  stroke-dashoffset: 1500;
  filter: drop-shadow(0 6px 12px rgba(201, 155, 63, 0.28));
}

.hero-curve.is-inview .hc-line {
  animation: hc-draw 4s var(--tg-ease) 0.25s forwards;
}

.hc-line2 {
  fill: none;
  stroke: #9bbf6e;
  stroke-width: 1.7;
  stroke-linecap: round;
  stroke-dasharray: 1400;
  stroke-dashoffset: 1400;
  opacity: 0.85;
}

.hero-curve.is-inview .hc-line2 {
  animation: hc-draw 4.3s var(--tg-ease) 0.7s forwards;
}

@keyframes hc-draw {
  to { stroke-dashoffset: 0; }
}

.hc-pulse {
  fill: #c99b3f;
  stroke: #fffdf7;
  stroke-width: 2.4;
  filter: drop-shadow(0 2px 7px rgba(143, 107, 34, 0.5));
  opacity: 0;
}

.hero-curve.is-inview .hc-pulse {
  animation: hc-fadein 0.7s ease 3.6s forwards;
}

.hc-halo {
  fill: none;
  stroke: #c99b3f;
  stroke-width: 1.4;
  opacity: 0;
  transform-box: fill-box;
  transform-origin: center;
}

.hero-curve.is-inview .hc-halo {
  animation: halo-beat 2.6s ease-out 4s infinite;
}

@keyframes halo-beat {
  0% { opacity: 0.75; transform: scale(1); }
  70% { opacity: 0; transform: scale(3.4); }
  100% { opacity: 0; transform: scale(3.4); }
}

@keyframes hc-fadein {
  to { opacity: 1; }
}

/* 线上坐标:末端垂线 + 途中交叉标记 */
.hc-drop {
  opacity: 0;
}

.hero-curve.is-inview .hc-drop {
  animation: hc-fadein 0.7s ease 3.8s forwards;
}

.hc-drop line {
  stroke: rgba(184, 138, 47, 0.4);
  stroke-width: 1;
  stroke-dasharray: 3 4;
}

.hc-coord {
  font-size: 10.5px;
  letter-spacing: 0.1em;
  fill: #7a5c1e;
  font-weight: 650;
}

.hc-mark-dot {
  fill: #c99b3f;
  stroke: #fffdf7;
  stroke-width: 2.4;
  filter: drop-shadow(0 2px 7px rgba(143, 107, 34, 0.5));
  transform-box: fill-box;
  transform-origin: center;
  transform: scale(0);
}

.hero-curve.is-inview .hc-mark--1 .hc-mark-dot {
  animation: hc-pop 0.65s var(--tg-ease-spring) 2.7s forwards;
}

.hero-curve.is-inview .hc-mark--2 .hc-mark-dot {
  animation: hc-pop 0.65s var(--tg-ease-spring) 3.2s forwards;
}

@keyframes hc-pop {
  from { transform: scale(0.3); opacity: 0; }
  to { transform: scale(1); opacity: 1; }
}

.hc-mark-halo {
  fill: rgba(201, 155, 63, 0.22);
  transform-box: fill-box;
  transform-origin: center;
  animation: hc-halo-pulse 2.4s ease-out infinite;
}

@keyframes hc-halo-pulse {
  0% { transform: scale(1); opacity: 0.9; }
  70% { transform: scale(2.5); opacity: 0; }
  100% { transform: scale(2.5); opacity: 0; }
}

.hc-legend {
  position: absolute;
  right: 2px;
  top: -14px;
  display: flex;
  gap: 14px;
  flex-wrap: wrap;
}

.hc-lg {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--tg-text-secondary);
  font-family: var(--tg-font-mono);
}

.hc-lg i {
  width: 14px;
  height: 3px;
  border-radius: 2px;
}

.hc-lg--gold i { background: #c99b3f; }
.hc-lg--olive i { background: #9bbf6e; }
.hc-lg--gray i { background: repeating-linear-gradient(90deg, rgba(60, 45, 25, 0.3) 0 3px, transparent 3px 6px); }

/* ===== 04 DETECT:缺陷检测(IDE 编排视图,模块换模块) ===== */
.ide-sec {
  position: relative;
  padding: 184px 0 72px;
  scroll-margin-top: 90px;
}

.ide-head {
  position: relative;
  max-width: 620px;
}

.ide-head h2 {
  margin: 14px 0 0;
  font-size: clamp(26px, 3.2vw, 40px);
  font-weight: 750;
  letter-spacing: -0.01em;
  line-height: 1.25;
  color: var(--tg-text-primary);
}

.ide-head p {
  margin: 14px 0 0;
  font-size: 14.5px;
  line-height: 1.8;
  color: var(--tg-text-secondary);
}

.ide-body {
  margin-top: 26px;
}

/* Agent-Canvas 式 IDE 编排器 */
.hero-ide {
  margin-top: 26px;
}

/* 滚动提示:标签 + 下落光点轨道 */
.scroll-cue {
  margin-top: 26px;
  display: flex;
  align-items: center;
  gap: 10px;
}

.scroll-cue__label {
  font-size: 9.5px;
  letter-spacing: 0.3em;
  color: rgba(122, 111, 95, 0.6);
}

.scroll-cue__track {
  position: relative;
  width: 1px;
  height: 34px;
  background: rgba(60, 45, 25, 0.12);
  overflow: hidden;
}

.scroll-cue__track i {
  position: absolute;
  left: -1.5px;
  top: -10px;
  width: 4px;
  height: 10px;
  border-radius: 3px;
  background: linear-gradient(180deg, transparent, #b88a2f);
  animation: cue-drop 2.2s var(--tg-ease) infinite;
}

@keyframes cue-drop {
  0% { transform: translateY(0); opacity: 0; }
  18% { opacity: 1; }
  72% { transform: translateY(40px); opacity: 1; }
  100% { transform: translateY(46px); opacity: 0; }
}

/* ===== 跑马灯 ===== */
.trustbar {
  margin-top: 72px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 22px;
  flex-wrap: wrap;
}

.trustbar__label {
  font-size: 16.5px;
  font-weight: 600;
  color: var(--tg-text-secondary);
  flex-shrink: 0;
}

.marquee {
  max-width: 780px;
  flex: 1;
  min-width: 260px;
  overflow: hidden;
  -webkit-mask-image: linear-gradient(90deg, transparent, #000 5%, #000 95%, transparent);
  mask-image: linear-gradient(90deg, transparent, #000 5%, #000 95%, transparent);
}

.marquee__track {
  display: flex;
  align-items: center;
  gap: 13px;
  width: max-content;
  animation: marquee-run 46s linear infinite;
}

.marquee:hover .marquee__track {
  animation-play-state: paused;
}

@keyframes marquee-run {
  to { transform: translateX(-50%); }
}

.trust-chip {
  font-size: 16.5px;
  font-weight: 600;
  color: var(--tg-text-secondary);
  padding: 10px 22px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.72);
  border: 1px solid var(--line);
  white-space: nowrap;
}

.trust-chip--dot {
  width: 5px;
  height: 5px;
  padding: 0;
  border-radius: 50%;
  background: rgba(60, 45, 25, 0.2);
  border: none;
  flex-shrink: 0;
}

/* ===== 四张技术示意图(base.org 风,暖金改色) ===== */
.feat {
  position: relative;
  padding: 150px 0 0;
}

.feat__grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 44px 28px;
}

.feat__art {
  border: 1px solid var(--line);
  border-radius: 18px;
  background: rgba(255, 253, 247, 0.8);
  padding: 16px 12px;
  overflow: hidden;
  transition: transform 0.6s var(--tg-ease), box-shadow 0.5s ease, border-color 0.5s ease;
}

.feat__item:hover .feat__art {
  transform: translateY(-5px);
  border-color: rgba(201, 155, 63, 0.4);
  box-shadow: 0 18px 40px rgba(60, 45, 25, 0.1);
}

.feat__item h3 {
  margin: 18px 0 0;
  font-size: 17px;
  font-weight: 700;
  color: var(--tg-text-primary);
}

.feat__item p {
  margin: 8px 0 0;
  font-size: 14.5px;
  line-height: 1.8;
  color: var(--tg-text-secondary);
  max-width: 460px;
}

.fd {
  display: block;
  width: 100%;
  height: auto;
}

/* --- 图解公共元素 --- */
.fda-panel {
  fill: #fffdf7;
  stroke: #3a2f1d;
  stroke-width: 1.6;
}

.fda-shadow {
  filter: drop-shadow(0 6px 14px rgba(60, 45, 25, 0.1));
}

.fda-handle {
  fill: #fffdf7;
  stroke: #3a2f1d;
  stroke-width: 1.2;
}

.fda-handle--on {
  fill: #c99b3f;
  stroke: #8f6b22;
}

.fda-ink {
  stroke: #3a2f1d;
  stroke-width: 1.4;
  fill: #fffdf7;
}

.fda-line {
  stroke: #3a2f1d;
  stroke-width: 1.3;
}

.fda-node {
  fill: #fffdf7;
  stroke: #3a2f1d;
  stroke-width: 1.3;
}

.fda-node-sq {
  fill: #fffdf7;
  stroke: #3a2f1d;
  stroke-width: 1.2;
}

.fda-glyph {
  stroke: #8f6b22;
  stroke-width: 1.7;
  stroke-linecap: round;
}

.fda-glyph-text {
  font-family: var(--tg-font-mono);
  font-size: 15px;
  font-weight: 700;
  fill: #8f6b22;
  stroke: none;
}

.fda-track {
  stroke: #e5dac4;
  stroke-width: 7;
  stroke-linecap: round;
  fill: none;
}

.fda-track-h {
  fill: #f0e7d2;
}

.fda-gauge {
  stroke: #c99b3f;
  stroke-width: 7;
  stroke-linecap: round;
  fill: none;
  stroke-dasharray: 108;
  stroke-dashoffset: 108;
  animation: fda-draw 2.4s var(--tg-ease) 0.4s forwards;
}

@keyframes fda-draw {
  to { stroke-dashoffset: 30; }
}

.fda-needle-line {
  stroke: #3a2f1d;
  stroke-width: 2.4;
  stroke-linecap: round;
}

.fda-needle {
  transform-box: fill-box;
  transform-origin: 50% 100%;
  animation: fda-needle 5.2s ease-in-out infinite;
}

@keyframes fda-needle {
  0%, 100% { transform: rotate(-46deg); }
  50% { transform: rotate(34deg); }
}

.fda-strip {
  fill: #c99b3f;
  animation: fda-strip 4.5s ease-in-out infinite;
}

@keyframes fda-strip {
  0%, 100% { transform: translateX(0); }
  50% { transform: translateX(80px); }
}

.fda-ring {
  fill: none;
  stroke-width: 2;
}

.fda-ring--ink { stroke: #3a2f1d; }
.fda-ring--gold { stroke: #c99b3f; stroke-dasharray: 8 6; }
.fda-ring--olive { stroke: #6b8e4e; stroke-dasharray: 4 4; }
.fda-ring--clay { stroke: #b0653f; stroke-dasharray: 5 7; }
.fda-ring--deep { stroke: #8f6b22; stroke-width: 2.2; }
.fda-ring--dotted { stroke: #b0653f; stroke-dasharray: 2.5 4; }

.fda-fill-gold { fill: #c99b3f; stroke: none; }
.fda-fill-cream { fill: #e8c877; stroke: none; }
.fda-fill-olive { fill: #9bbf6e; stroke: none; }
.fda-fill-clay { fill: #d98a6a; stroke: none; }
.fda-fill-deep { fill: #8f6b22; stroke: none; }

.fda-arc {
  stroke: #b88a2f;
  stroke-width: 1.4;
  fill: none;
}

.fda-flow {
  stroke-dasharray: 5 6;
  animation: fda-flow 1.5s linear infinite;
}

@keyframes fda-flow {
  to { stroke-dashoffset: -22; }
}

.fda-blink {
  animation: fda-blink 4.2s infinite;
}

@keyframes fda-blink {
  0%, 86%, 100% { opacity: 1; }
  90%, 95% { opacity: 0.12; }
}

.fda-spin {
  animation: fda-spin 9s linear infinite;
  transform-box: fill-box;
  transform-origin: center;
}

.fda-spin-rev {
  animation: fda-spin 13s linear infinite reverse;
  transform-box: fill-box;
  transform-origin: center;
}

@keyframes fda-spin {
  to { transform: rotate(360deg); }
}

.fda-pulse {
  animation: fda-pulse 2.6s ease-in-out infinite;
  transform-box: fill-box;
  transform-origin: center;
}

@keyframes fda-pulse {
  0%, 100% { transform: scale(1); opacity: 1; }
  50% { transform: scale(1.45); opacity: 0.65; }
}

/* ===== 幽灵章节号(水印层:置于内容之下,避免压住标题) ===== */
.ghost-num {
  position: absolute;
  right: -8px;
  top: -34px;
  font-size: 132px;
  font-weight: 900;
  line-height: 1;
  color: rgba(74, 52, 16, 0.26);
  -webkit-text-stroke: 1.5px rgba(43, 30, 10, 0.6);
  mix-blend-mode: multiply;
  z-index: -1;
  pointer-events: none;
  user-select: none;
}

.ghost-num--sm {
  font-size: 96px;
  top: -72px;
  right: 6px;
}

/* 幽灵数字分布:各区块右上白色区域,互相拉开距离 */
/* 03:镜像后位于文案块左上(与原右上相对) */
.lab__copy .ghost-num--sm {
  right: auto;
  left: 6px;
  top: -88px;
}

/* 03旧基准:位于实验台文案块内(ghost-num--sm) */

/* 04:贴着 IDE 上边缘 */
.ide-body {
  position: relative;
}

.ide-body > .ghost-num--sm {
  top: -98px;
  right: -8px;
}

.stats > .ghost-num {
  top: 100px;
  right: -8px;
}

/* ===== 仪表盘 ===== */
.dash-sec {
  position: relative;
  padding: 184px 0 96px;
  scroll-margin-top: 90px;
}

.dash {
  position: relative;
  border-radius: 26px;
  border: 1px solid rgba(60, 45, 25, 0.11);
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  box-shadow: 0 30px 80px rgba(60, 45, 25, 0.12);
  overflow: hidden;
}

.dash::before {
  content: '';
  position: absolute;
  inset: 0;
  background: radial-gradient(360px 220px at var(--gx, 70%) var(--gy, 20%), rgba(201, 155, 63, 0.08), transparent 70%);
  pointer-events: none;
  opacity: 0;
  transition: opacity 0.8s ease;
}

.dash:hover::before {
  opacity: 1;
}

.dash__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  padding: 12px 18px;
  border-bottom: 1px solid var(--line);
  background: rgba(255, 251, 242, 0.7);
}

.dash__brand {
  display: flex;
  align-items: center;
  gap: 11px;
  font-size: 15px;
}

.dash__logo {
  width: 24px;
  height: 24px;
  display: flex;
  align-items: center;
  justify-content: center;
}

.dash__logo img {
  width: 100%;
  height: 100%;
  display: block;
}

.dash__env {
  font-size: 12.5px;
  color: var(--tg-success);
  font-family: var(--tg-font-mono);
}

.dash__work {
  font-size: 14px;
  color: var(--tg-text-secondary);
  font-family: var(--tg-font-mono);
}

.dash__work b {
  color: var(--tg-text-primary);
  font-weight: 600;
}

.dash__body {
  display: grid;
  grid-template-columns: 188px 1fr;
}

.dash__side {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 14px 10px;
  border-right: 1px solid var(--line);
}

.ds-item {
  font-size: 14px;
  color: var(--tg-text-secondary);
  padding: 8px 12px;
  border-radius: 8px;
}

.ds-item--on {
  color: var(--gold-deep);
  background: rgba(201, 155, 63, 0.12);
  font-weight: 600;
}

.dash__main {
  padding: 20px 24px 24px;
  min-width: 0;
}

.dash__crumb {
  display: flex;
  align-items: center;
  gap: 9px;
  font-family: var(--tg-font-mono);
  font-size: 14px;
  color: var(--tg-text-secondary);
}

.dash__crumb b {
  color: var(--tg-text-primary);
  font-weight: 600;
}

.dash__ranges {
  margin-left: auto;
  display: flex;
  gap: 4px;
}

.dash__ranges span {
  padding: 3px 9px;
  border-radius: 6px;
  font-size: 12px;
  color: var(--tg-text-secondary);
  border: 1px solid transparent;
}

.dash__ranges .is-on {
  color: var(--gold-deep);
  background: rgba(201, 155, 63, 0.12);
  border-color: rgba(201, 155, 63, 0.3);
}

.dash__head {
  margin-top: 16px;
  display: flex;
  align-items: center;
  gap: 12px;
}

.dash__head h3 {
  margin: 0;
  font-size: 32px;
  font-weight: 700;
  color: var(--tg-text-primary);
}

.dash__trend {
  font-size: 13px;
  color: var(--tg-success);
  background: rgba(107, 142, 78, 0.1);
  border: 1px solid rgba(107, 142, 78, 0.24);
  padding: 5px 12px;
  border-radius: 999px;
}

.dash__meta {
  margin-top: 9px;
  font-family: var(--tg-font-mono);
  font-size: 12.5px;
  color: var(--tg-text-secondary);
}

.dash__row {
  margin-top: 18px;
  display: grid;
  grid-template-columns: 1fr 1fr 1.5fr;
  gap: 12px;
  align-items: stretch;
}

.dash__kpi {
  border: 1px solid var(--line);
  border-radius: 14px;
  padding: 12px 16px;
  background: rgba(255, 255, 255, 0.7);
}

.dash__kpi > span {
  font-size: 12px;
  letter-spacing: 0.14em;
  color: var(--tg-text-secondary);
  text-transform: uppercase;
}

.dash__kpi b {
  display: block;
  margin-top: 4px;
  font-size: 56px;
  font-weight: 700;
  color: var(--tg-text-primary);
}

.dash__kpi em {
  display: inline-block;
  margin-left: 8px;
  font-style: normal;
  font-size: 13.5px;
  color: var(--tg-success);
}

.dash__auto {
  display: flex;
  align-items: center;
  gap: 12px;
  border: 1px solid rgba(107, 142, 78, 0.24);
  background: rgba(107, 142, 78, 0.07);
  border-radius: 14px;
  padding: 12px 16px;
}

.dash__switch {
  width: 38px;
  height: 22px;
  border-radius: 999px;
  background: var(--tg-success);
  position: relative;
  flex-shrink: 0;
}

.dash__switch::after {
  content: '';
  position: absolute;
  top: 4px;
  right: 4px;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #fff;
}

.dash__auto b {
  display: block;
  font-size: 15px;
  color: var(--tg-text-primary);
}

.dash__auto span {
  font-size: 12.5px;
  color: var(--tg-text-secondary);
  font-family: var(--tg-font-mono);
}

.dash__chart {
  position: relative;
  margin-top: 20px;
}

.dash__chart svg {
  display: block;
  width: 100%;
  height: 240px;
}

.dc-grid {
  stroke: rgba(60, 45, 25, 0.07);
  stroke-dasharray: 3 5;
}

.dc-goal2 {
  stroke: rgba(107, 142, 78, 0.4);
  stroke-width: 1;
  stroke-dasharray: 4 6;
}

.dc-line {
  fill: none;
  stroke: #c99b3f;
  stroke-width: 2.2;
  stroke-linecap: round;
  stroke-dasharray: 1200;
  stroke-dashoffset: 1200;
  animation: hc-draw 5s var(--tg-ease) 0.6s forwards;
}

.dc-area {
  opacity: 0;
  animation: dc-fade 1.4s ease 3.6s forwards;
}

@keyframes dc-fade {
  to { opacity: 1; }
}

.dc-dot {
  fill: #b88a2f;
  opacity: 0;
  animation: dc-fade 0.7s ease 5s forwards;
}

.dc-x {
  display: flex;
  justify-content: space-between;
  margin-top: 8px;
  font-size: 12px;
  color: var(--tg-text-secondary);
  font-family: var(--tg-font-mono);
}

.dc-note {
  position: absolute;
  right: 4px;
  top: 4px;
  font-size: 12px;
  color: var(--tg-text-secondary);
  font-family: var(--tg-font-mono);
}

/* ===== HUD 蓝图标注:四角取景框 + 引导线 + 点阵标签,巡航于曲线关键点 ===== */
.dash-hud {
  position: absolute;
  inset: 0 0 58px 0;
  pointer-events: none;
}

.hud-pin {
  position: absolute;
  left: var(--hx);
  top: var(--hy);
  transform: translate(-50%, -50%);
}

.hud-frame {
  position: relative;
  display: block;
  width: 52px;
  height: 40px;
}

.hud-frame i {
  position: absolute;
  width: 11px;
  height: 11px;
  border-color: #b88a2f;
  border-style: solid;
  border-width: 0;
}

.hud-frame i:nth-child(1) { left: 0; top: 0; border-left-width: 1.6px; border-top-width: 1.6px; }
.hud-frame i:nth-child(2) { right: 0; top: 0; border-right-width: 1.6px; border-top-width: 1.6px; }
.hud-frame i:nth-child(3) { left: 0; bottom: 0; border-left-width: 1.6px; border-bottom-width: 1.6px; }
.hud-frame i:nth-child(4) { right: 0; bottom: 0; border-right-width: 1.6px; border-bottom-width: 1.6px; }

.hud-frame::after {
  content: '';
  position: absolute;
  left: 50%;
  top: 50%;
  width: 6px;
  height: 6px;
  margin: -3px 0 0 -3px;
  border-radius: 50%;
  background: #c99b3f;
  box-shadow: 0 0 8px rgba(201, 155, 63, 0.8);
  animation: hud-beat 2.2s ease-in-out infinite;
}

@keyframes hud-beat {
  0%, 100% { transform: scale(1); opacity: 1; }
  50% { transform: scale(1.5); opacity: 0.6; }
}

.hud-leader {
  position: absolute;
  left: 100%;
  top: 50%;
  width: var(--ldx, 30px);
  height: 0;
  border-top: 1px dashed rgba(143, 107, 34, 0.45);
  transform-origin: left center;
}

.hud-label {
  position: absolute;
  left: calc(100% + var(--ldx, 30px));
  top: calc(50% + var(--ldy, 0px));
  transform: translateY(-50%);
  white-space: nowrap;
  font-size: 11px;
  letter-spacing: 0.12em;
  color: #8f6b22;
  background: rgba(255, 252, 244, 0.92);
  border: 1px solid rgba(184, 138, 47, 0.35);
  border-radius: 8px;
  padding: 5px 11px;
  box-shadow: 0 4px 14px rgba(60, 45, 25, 0.1);
}

.hud-pin--flip .hud-leader {
  left: auto;
  right: 100%;
}

.hud-pin--flip .hud-label {
  left: auto;
  right: calc(100% + var(--ldx, 30px));
}

.hud-swap-enter-active,
.hud-swap-leave-active {
  transition: opacity 0.6s var(--tg-ease), transform 0.6s var(--tg-ease), filter 0.6s var(--tg-ease);
}

.hud-swap-enter-from {
  opacity: 0;
  transform: translate(-50%, -50%) scale(0.82);
  filter: blur(4px);
}

.hud-swap-leave-to {
  opacity: 0;
  transform: translate(-50%, -50%) scale(1.12);
  filter: blur(4px);
}

.dash__chips {
  margin-top: 18px;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.dash__chips span {
  font-size: 13px;
  color: var(--tg-text-secondary);
  padding: 6px 14px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.7);
}

.dash__chips .is-on {
  color: var(--gold-deep);
  border-color: rgba(201, 155, 63, 0.4);
  background: rgba(201, 155, 63, 0.1);
}

/* ===== 滚动叙事(两用:正向 / 镜像) ===== */
.walk {
  position: relative;
  height: 300vh;
}

.walk__pin {
  position: sticky;
  top: 0;
  height: 100vh;
  display: flex;
  align-items: center;
  padding-top: 76px;
  box-sizing: border-box;
}

.walk__grid {
  width: 100%;
  display: grid;
  grid-template-columns: 0.9fr 1.1fr;
  gap: 56px;
  align-items: center;
}

.walk__grid--mirror {
  grid-template-columns: 1.1fr 0.9fr;
}

.kicker {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-family: var(--tg-font-mono);
  font-size: 11.5px;
  font-weight: 650;
  letter-spacing: 0.2em;
  text-transform: uppercase;
  color: var(--gold-deep);
}

.kicker i {
  width: 7px;
  height: 7px;
  border-radius: 2px;
  background: linear-gradient(135deg, #8f6b22, #c99b3f);
  box-shadow: 0 0 10px rgba(201, 155, 63, 0.55);
}

.kicker--teal {
  color: var(--teal);
}

.kicker--teal i {
  background: var(--teal);
  box-shadow: 0 0 10px rgba(95, 141, 124, 0.55);
}

.kicker--coral {
  color: var(--coral);
}

.kicker--coral i {
  background: var(--coral);
  box-shadow: 0 0 10px rgba(176, 101, 63, 0.55);
}

.walk__copy {
  position: relative;
}

.landing h2 {
  text-wrap: balance;
}

.walk__copy h2 {
  margin: 16px 0 0;
  font-size: clamp(26px, 3.2vw, 40px);
  font-weight: 750;
  letter-spacing: -0.01em;
  line-height: 1.25;
  color: var(--tg-text-primary);
}

.walk__desc {
  margin: 14px 0 0;
  font-size: 14.5px;
  line-height: 1.75;
  color: var(--tg-text-secondary);
  max-width: 460px;
}

.wsteps {
  margin-top: 34px;
}

.wstep {
  position: relative;
  padding: 16px 0;
  border-bottom: 1px solid var(--line);
  cursor: pointer;
  outline: none;
}

/* 激活步骤的自动进度线:走完即切换下一步(与 JS 定时同拍 5.6s) */
.wstep::after {
  content: '';
  position: absolute;
  left: 0;
  bottom: -1px;
  height: 2px;
  width: 100%;
  border-radius: 2px;
  background: linear-gradient(90deg, #8f6b22, #c99b3f, #e8c877);
  transform: scaleX(0);
  transform-origin: left;
  opacity: 0;
}

.wstep.on::after {
  opacity: 1;
  transform: scaleX(var(--sp, 1));
  transition: transform 0.2s linear, opacity 0.35s ease;
}

.walk--mirror .wstep.on::after {
  background: linear-gradient(90deg, #9a4a2e, #b0653f, #d98a6a);
}

.wstep h3 {
  margin: 0;
  display: flex;
  align-items: baseline;
  gap: 12px;
  font-size: 16.5px;
  font-weight: 600;
  color: rgba(122, 111, 95, 0.55);
  transition: color 0.6s ease, transform 0.6s var(--tg-ease);
}

.wstep h3 .dot {
  font-size: 12px;
  color: rgba(122, 111, 95, 0.4);
  transition: color 0.35s ease;
}

.wstep p {
  margin: 6px 0 0 34px;
  font-size: 13px;
  line-height: 1.7;
  color: var(--tg-text-secondary);
  max-height: 0;
  opacity: 0;
  overflow: hidden;
  transition: max-height 0.8s var(--tg-ease), opacity 0.8s ease;
}

.wstep.on h3 {
  color: var(--tg-text-primary);
  transform: translateX(4px);
}

.walk--mirror .wstep.on h3 {
  transform: translateX(-4px);
}

.wstep.on h3 .dot {
  color: var(--gold-deep);
}

.walk--mirror .wstep.on h3 .dot {
  color: var(--coral);
}

.wstep.on p {
  max-height: 90px;
  opacity: 1;
}

.walk__stage {
  position: relative;
  min-height: 500px;
  border-radius: 26px;
  border: 1px solid var(--line);
  overflow: hidden;
  background: linear-gradient(200deg, #fffdf8, #faf3e4);
  box-shadow: var(--tg-shadow-card);
}

.walk__stage::before {
  content: '';
  position: absolute;
  inset: 0;
  background: radial-gradient(70% 62% at 62% 40%, var(--tone), transparent 72%);
}

.stage--gold {
  --tone: rgba(201, 155, 63, 0.14);
}

.stage--coral {
  --tone: rgba(176, 101, 63, 0.12);
}

.wvis {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 32px;
  opacity: 0;
  transform: translateY(26px) scale(0.985);
  transition: opacity 0.9s var(--tg-ease), transform 0.9s var(--tg-ease);
  pointer-events: none;
}

.wvis.on {
  opacity: 1;
  transform: none;
  pointer-events: auto;
}

/* 场景卡交错入场:卡体上滑,行依次浮现 */
.wvis .wv-card {
  opacity: 0;
  transform: translateY(18px) scale(0.99);
  transition: opacity 0.8s var(--tg-ease) 0.12s, transform 0.8s var(--tg-ease) 0.12s;
}

.wvis.on .wv-card {
  opacity: 1;
  transform: none;
}

.wvis .wv-row,
.wvis .wv-pipe,
.wvis .wv-defect,
.wvis .wv-engine {
  opacity: 0;
  transform: translateY(8px);
  transition: opacity 0.6s var(--tg-ease), transform 0.6s var(--tg-ease);
}

.wvis.on .wv-row,
.wvis.on .wv-pipe,
.wvis.on .wv-defect,
.wvis.on .wv-engine {
  opacity: 1;
  transform: none;
}

.wvis.on .wv-row:nth-child(2),
.wvis.on .wv-pipe:nth-child(2),
.wvis.on .wv-defect:nth-child(2) { transition-delay: 0.28s; }

.wvis.on .wv-row:nth-child(3),
.wvis.on .wv-pipe:nth-child(3),
.wvis.on .wv-defect:nth-child(3) { transition-delay: 0.42s; }

.wvis.on .wv-row:nth-child(4) { transition-delay: 0.56s; }

.wv-card {
  width: min(560px, 100%);
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid rgba(60, 45, 25, 0.1);
  border-radius: 20px;
  padding: 26px 28px;
  box-shadow: 0 24px 60px rgba(60, 45, 25, 0.12);
  backdrop-filter: blur(8px);
}

.wv-card--center {
  text-align: center;
  padding: 40px 34px;
}

.wv-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  margin-bottom: 14px;
}

.wv-head b {
  font-size: 15.5px;
  color: var(--tg-text-primary);
}

.wv-tag {
  font-family: var(--tg-font-mono);
  font-size: 11px;
  color: var(--tg-text-secondary);
  padding: 3px 9px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.8);
}

.wv-tag--warn {
  color: #b5731f;
  border-color: rgba(232, 155, 60, 0.4);
  background: rgba(232, 155, 60, 0.1);
}

.wv-row {
  display: flex;
  align-items: center;
  gap: 13px;
  padding: 12px 0;
  border-bottom: 1px solid rgba(60, 45, 25, 0.06);
}

.wv-row:last-child {
  border-bottom: none;
}

.wv-ic {
  width: 36px;
  height: 36px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 12.5px;
  font-weight: 800;
  font-family: var(--tg-font-mono);
  font-style: normal;
  flex-shrink: 0;
}

.ic-doc {
  background: rgba(201, 155, 63, 0.14);
  color: #a87e26;
}

.ic-zip {
  background: rgba(95, 141, 124, 0.14);
  color: var(--teal);
}

.ic-md {
  background: rgba(176, 101, 63, 0.14);
  color: var(--coral);
}

.wv-row-body {
  flex: 1;
  min-width: 0;
}

.wv-row-body b {
  display: block;
  font-size: 14px;
  color: var(--tg-text-primary);
  font-family: var(--tg-font-mono);
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.wv-row-body > span {
  font-size: 11.5px;
  color: var(--tg-text-secondary);
}

.wv-row em {
  font-style: normal;
  font-size: 12px;
  flex-shrink: 0;
}

.is-ok { color: var(--tg-success); }
.is-run { color: #b5731f; }
.is-wait { color: var(--tg-text-secondary); }

.wv-prog {
  margin-top: 6px;
  height: 4px;
  border-radius: 4px;
  background: rgba(60, 45, 25, 0.08);
  overflow: hidden;
}

.wv-prog--lg {
  height: 5px;
}

.wv-prog i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #b88a2f, #e8c877);
  transform-origin: left;
  animation: grow-x 2.2s var(--tg-ease) both;
}

@keyframes grow-x {
  from { transform: scaleX(0); }
  to { transform: scaleX(1); }
}

.wv-pipe,
.wv-wrow {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 0;
}

.wv-pipe span,
.wv-wrow span {
  width: 108px;
  font-size: 13px;
  color: var(--tg-text-secondary);
  flex-shrink: 0;
}

.wv-pipe .wv-prog,
.wv-wrow .wv-prog {
  flex: 1;
  margin-top: 0;
}

.wv-pipe em,
.wv-wrow em {
  width: 52px;
  text-align: right;
  font-style: normal;
  font-family: var(--tg-font-mono);
  font-size: 11.5px;
  color: var(--tg-text-primary);
  flex-shrink: 0;
}

.wv-check {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 58px;
  height: 58px;
  border-radius: 50%;
  background: rgba(107, 142, 78, 0.12);
  border: 1px solid rgba(107, 142, 78, 0.35);
  color: var(--tg-success);
  font-size: 22px;
  font-style: normal;
}

.wv-card--center b {
  display: block;
  margin-top: 14px;
  font-size: 16px;
  color: var(--tg-text-primary);
}

.wv-card--center p {
  margin: 8px 0 0;
  font-size: 12.5px;
  line-height: 1.8;
  color: var(--tg-text-secondary);
}

.wv-cta {
  display: inline-flex;
  margin-top: 20px;
  padding: 11px 24px;
  border-radius: 999px;
  background: rgba(201, 155, 63, 0.12);
  border: 1px solid rgba(201, 155, 63, 0.35);
  color: var(--gold-deep);
  font-size: 13px;
  font-weight: 600;
}

.wv-engines {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.wv-engine {
  border: 1px solid var(--line);
  border-radius: 12px;
  padding: 12px 14px;
  background: rgba(255, 255, 255, 0.85);
}

.wv-engine b {
  display: block;
  font-size: 13px;
  color: var(--tg-text-primary);
}

.wv-engine span {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.65;
  color: var(--tg-text-secondary);
}

.wv-scanline {
  position: relative;
  margin-top: 16px;
  height: 3px;
  border-radius: 3px;
  background: rgba(176, 101, 63, 0.14);
  overflow: hidden;
}

.wv-scanline::after {
  content: '';
  position: absolute;
  top: 0;
  bottom: 0;
  left: -40%;
  width: 40%;
  border-radius: inherit;
  background: linear-gradient(90deg, transparent, #b0653f, transparent);
  animation: scan-x 4.5s ease-in-out infinite;
}

@keyframes scan-x {
  to { left: 100%; }
}

.wv-defect {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px solid rgba(60, 45, 25, 0.06);
}

.wv-defect:last-child {
  border-bottom: none;
}

.wv-defect em.sev {
  width: 34px;
  height: 24px;
  border-radius: 7px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 10.5px;
  font-weight: 700;
  font-style: normal;
  flex-shrink: 0;
}

.sev--high {
  background: rgba(176, 101, 63, 0.13);
  color: #9a4a2e;
}

.sev--mid {
  background: rgba(232, 155, 60, 0.15);
  color: #b5731f;
}

.sev--low {
  background: rgba(107, 142, 78, 0.13);
  color: var(--tg-success);
}

.wv-defect b {
  display: block;
  font-size: 14px;
  color: var(--tg-text-primary);
}

.wv-defect span {
  font-size: 11px;
  color: var(--tg-text-secondary);
  font-family: var(--tg-font-mono);
}

.wv-note {
  margin: 12px 0 0;
  font-size: 13.5px;
  line-height: 1.8;
  color: var(--tg-text-secondary);
}

.wv-code {
  margin: 12px 0 0;
  padding: 16px 18px;
  border-radius: 12px;
  background: #2a2216;
  border: 1px solid rgba(232, 200, 119, 0.2);
  font-family: var(--tg-font-mono);
  font-size: 12px;
  line-height: 1.7;
  color: #c4e29c;
  overflow-x: auto;
}

/* ===== VERIFY 一致性实验台 ===== */
.lab {
  position: relative;
  padding: 176px 0 96px;
  scroll-margin-top: 90px;
}

.lab__grid {
  display: grid;
  grid-template-columns: 1.1fr 0.9fr;
  gap: 56px;
  align-items: center;
}

.lab__copy {
  position: relative;
}

.lab__copy h2 {
  margin: 16px 0 0;
  font-size: clamp(26px, 3.2vw, 40px);
  font-weight: 750;
  letter-spacing: -0.01em;
  line-height: 1.28;
  color: var(--tg-text-primary);
}

.lab__copy p {
  margin: 16px 0 0;
  font-size: 14.5px;
  line-height: 1.8;
  color: var(--tg-text-secondary);
  max-width: 440px;
}

.lab__formula {
  display: inline-flex;
  margin-top: 22px;
  padding: 11px 16px;
  border-radius: 12px;
  background: rgba(95, 141, 124, 0.08);
  border: 1px solid rgba(95, 141, 124, 0.28);
  font-size: 14px;
  color: #4d7767;
  font-weight: 600;
  letter-spacing: 0.02em;
}

.lab__panel {
  position: relative;
  border-radius: 24px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.88);
  backdrop-filter: blur(12px);
  -webkit-backdrop-filter: blur(12px);
  box-shadow: 0 26px 70px rgba(60, 45, 25, 0.1);
  padding: 28px;
}

.lab__sliders {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.vslider__head {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  margin-bottom: 9px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--tg-text-primary);
}

.vslider__head em {
  font-style: normal;
  margin-left: 8px;
  font-size: 11.5px;
  color: var(--teal);
}

.vslider__head b {
  font-size: 14px;
  color: var(--gold-deep);
}

.vslider input {
  -webkit-appearance: none;
  appearance: none;
  width: 100%;
  height: 6px;
  border-radius: 6px;
  background: linear-gradient(90deg, #c99b3f var(--p, 50%), rgba(60, 45, 25, 0.1) var(--p, 50%));
  outline: none;
  cursor: pointer;
}

.vslider input::-webkit-slider-thumb {
  -webkit-appearance: none;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #fff;
  border: 2.5px solid #c99b3f;
  box-shadow: 0 3px 10px rgba(143, 107, 34, 0.3);
  cursor: grab;
  transition: transform 0.4s var(--tg-ease);
}

.vslider input::-webkit-slider-thumb:hover {
  transform: scale(1.15);
}

.vslider input::-moz-range-thumb {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #fff;
  border: 2.5px solid #c99b3f;
  box-shadow: 0 3px 10px rgba(143, 107, 34, 0.3);
  cursor: grab;
}

.lab__result {
  margin-top: 26px;
  padding-top: 22px;
  border-top: 1px dashed rgba(60, 45, 25, 0.14);
}

.lab__score {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
}

.lab__score-label {
  font-size: 13px;
  color: var(--tg-text-secondary);
  font-weight: 550;
}

.lab__score b {
  font-size: clamp(38px, 4vw, 52px);
  font-weight: 900;
  color: var(--gold-deep);
  font-variant-numeric: tabular-nums;
  transition: color 0.3s ease;
}

.lab__meter {
  margin-top: 14px;
  height: 10px;
  border-radius: 6px;
  background: rgba(60, 45, 25, 0.07);
  overflow: hidden;
  position: relative;
}

.lab__meter::after {
  content: '';
  position: absolute;
  left: 90%;
  top: -3px;
  bottom: -3px;
  width: 2px;
  background: rgba(60, 45, 25, 0.3);
}

.lab__meter i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #c99b3f, #b88a2f);
  transition: width 0.9s var(--tg-ease), background 0.5s ease;
}

.lab__meter i.is-warn {
  background: linear-gradient(90deg, #e8c877, #e89b3c);
}

.lab__meter i.is-bad {
  background: linear-gradient(90deg, #d98a6a, #b0653f);
}

.lab__verdict {
  margin-top: 14px;
  display: inline-flex;
  align-items: center;
  gap: 9px;
  padding: 9px 15px;
  border-radius: 12px;
  font-size: 13px;
  font-weight: 600;
  background: rgba(107, 142, 78, 0.09);
  border: 1px solid rgba(107, 142, 78, 0.26);
  color: var(--tg-success);
  transition: background 0.3s ease, border-color 0.3s ease, color 0.3s ease;
}

.lab__verdict-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: currentColor;
  box-shadow: 0 0 8px currentColor;
}

.lab__verdict.is-warn {
  background: rgba(232, 155, 60, 0.1);
  border-color: rgba(232, 155, 60, 0.32);
  color: #b5731f;
}

.lab__verdict.is-bad {
  background: rgba(176, 101, 63, 0.1);
  border-color: rgba(176, 101, 63, 0.32);
  color: #9a4a2e;
}

/* ===== 数据洞察 ===== */
.stats {
  position: relative;
  padding: 184px 0 0;
  scroll-margin-top: 90px;
}

.stats h2 {
  margin: 14px 0 0;
  font-size: clamp(26px, 3.4vw, 42px);
  font-weight: 750;
  color: var(--tg-text-primary);
}

.stats__row {
  margin-top: 54px;
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 22px;
}

.stat {
  position: relative;
  border: 1px solid var(--line);
  border-radius: 20px;
  padding: 30px 28px;
  background: rgba(255, 255, 255, 0.8);
  overflow: hidden;
  transition: transform 0.6s var(--tg-ease), box-shadow 0.6s ease, border-color 0.6s ease;
}

.stat::before {
  content: '';
  position: absolute;
  inset: 0;
  background: radial-gradient(240px 180px at var(--gx, 50%) var(--gy, 0%), rgba(201, 155, 63, 0.09), transparent 70%);
  opacity: 0;
  transition: opacity 0.8s ease;
  pointer-events: none;
}

.stat:hover {
  transform: translateY(-6px);
  border-color: rgba(201, 155, 63, 0.4);
  box-shadow: 0 20px 46px rgba(60, 45, 25, 0.12);
}

.stat:hover::before {
  opacity: 1;
}

.stat b {
  display: block;
  font-size: clamp(50px, 5vw, 72px);
  font-weight: 900;
  line-height: 1;
  color: var(--gold-deep);
}

.stat > span {
  display: block;
  margin-top: 14px;
  font-size: 14.5px;
  color: var(--tg-text-primary);
  font-weight: 600;
}

.stat em {
  display: block;
  margin-top: 6px;
  font-style: normal;
  font-size: 12.5px;
  color: var(--tg-text-secondary);
  font-family: var(--tg-font-mono);
}

/* 角标 mono 标签 */
.stat__tag {
  position: absolute;
  right: 16px;
  top: 14px;
  font-size: 10px;
  letter-spacing: 0.2em;
  color: rgba(122, 111, 95, 0.55);
}

/* 深色锚点卡:反差面板(借鉴 giga 大色块统计) */
.stat--dark {
  background: linear-gradient(160deg, #322918, #241d12 62%, #2e2415);
  border-color: rgba(232, 200, 119, 0.18);
  box-shadow: 0 24px 60px rgba(43, 30, 10, 0.32), inset 0 1px 0 rgba(232, 200, 119, 0.14);
}

.stat--dark b {
  color: #e8c877;
  text-shadow: 0 0 26px rgba(232, 200, 119, 0.35);
}

.stat--dark > span {
  color: #f2e7d0;
}

.stat--dark em {
  color: rgba(242, 231, 208, 0.62);
}

.stat--dark .stat__tag {
  color: rgba(232, 200, 119, 0.5);
}

/* 迷你趋势线:入视口后描线 */
.stat__spark {
  display: block;
  width: 100%;
  height: 34px;
  margin-top: 16px;
  overflow: visible;
}

.stat__spark-line {
  fill: none;
  stroke: #b88a2f;
  stroke-width: 1.8;
  stroke-linecap: round;
  stroke-dasharray: 140;
  stroke-dashoffset: 140;
}

.stat--dark .stat__spark-line {
  stroke: #e8c877;
}

.stat__spark-line--down {
  stroke: #6b8e4e;
}

.stat__spark-dot {
  fill: #b88a2f;
  opacity: 0;
}

.stat--dark .stat__spark-dot {
  fill: #e8c877;
}

.stat__spark-dot--down {
  fill: #6b8e4e;
}

.stat.is-visible .stat__spark-line {
  animation: spark-draw 1.8s var(--tg-ease) 0.5s forwards;
}

.stat.is-visible .stat__spark-dot {
  animation: dc-fade 0.5s ease 2.1s forwards;
}

@keyframes spark-draw {
  to { stroke-dashoffset: 0; }
}

/* ===== 全景航线:点阵地球 ===== */
.globe-sec {
  position: relative;
  padding: 184px 0 0;
}

.globe__head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 40px;
  flex-wrap: wrap;
}

.globe__copy {
  position: relative;
  max-width: 560px;
}

.globe__copy h2 {
  margin: 14px 0 0;
  font-size: clamp(26px, 3.4vw, 42px);
  font-weight: 750;
  line-height: 1.25;
  color: var(--tg-text-primary);
}

.globe__copy p {
  margin: 16px 0 0;
  font-size: 14.5px;
  line-height: 1.8;
  color: var(--tg-text-secondary);
}

.globe__stat {
  text-align: right;
  padding-bottom: 6px;
}

.globe__stat b {
  display: block;
  white-space: nowrap;
  font-size: clamp(56px, 6vw, 84px);
  font-weight: 900;
  line-height: 1;
  color: var(--gold-deep);
}

.globe__stat span {
  display: block;
  margin-top: 8px;
  font-size: 14px;
  color: var(--tg-text-secondary);
}

.globe__stage {
  position: relative;
  max-width: 980px;
  margin: 24px auto 0;
  border-radius: 22px;
  border: 1px solid var(--line);
  background: linear-gradient(200deg, #fffdf8, #f8f1e0);
  box-shadow: var(--tg-shadow-card);
  aspect-ratio: 21 / 8.6;
  overflow: hidden;
  cursor: grab;
  user-select: none;
}

.globe__stage::before {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(46% 68% at 50% 52%, rgba(201, 155, 63, 0.08), transparent 72%),
    radial-gradient(70% 100% at 50% 118%, rgba(176, 101, 63, 0.1), transparent 70%);
  pointer-events: none;
}

.globe__stage canvas {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
}

.globe__hint {
  position: absolute;
  right: 18px;
  bottom: 14px;
  font-size: 10px;
  letter-spacing: 0.2em;
  color: rgba(122, 111, 95, 0.55);
}

/* 编程活跃度图例 */
.globe__legend {
  position: absolute;
  left: 18px;
  bottom: 12px;
  display: flex;
  align-items: center;
  gap: 14px;
  flex-wrap: wrap;
}

.gl {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--tg-text-secondary);
}

.gl i {
  width: 10px;
  height: 10px;
  border-radius: 3px;
}

.gl--hi i {
  background: #583c06;
  box-shadow: 0 0 6px rgba(88, 60, 6, 0.55);
}

.gl--mid i {
  background: #9c6e14;
}

.gl--low i {
  background: #867454;
}

.gl-cap {
  font-size: 9.5px;
  letter-spacing: 0.18em;
  color: rgba(122, 111, 95, 0.6);
  margin-left: 4px;
}

/* ===== 工作流程时间轴 ===== */
.flow {
  position: relative;
  padding: 184px 0 0;
  scroll-margin-top: 90px;
}

.flow h2 {
  margin: 14px 0 0;
  font-size: clamp(26px, 3.4vw, 42px);
  font-weight: 750;
  color: var(--tg-text-primary);
}

.flowtrack {
  position: relative;
  margin-top: 70px;
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 20px;
  padding-top: 14px;
}

.flowtrack__line {
  position: absolute;
  top: 30px;
  left: 8%;
  right: 8%;
  height: 3px;
  border-radius: 3px;
  background: rgba(60, 45, 25, 0.08);
  overflow: hidden;
}

.flowtrack__line i {
  display: block;
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(90deg, #8f6b22, #c99b3f, #e8c877);
  transform: scaleX(var(--fp, 0));
  transform-origin: left;
  transition: transform 0.6s linear;
  will-change: transform;
}

/* 巡航光点:沿进度线移动,带拖尾光晕 */
.flowtrack__comet {
  position: absolute;
  left: calc(var(--fp, 0) * 100%);
  top: 50%;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #e8c877;
  box-shadow:
    0 0 0 3px rgba(201, 155, 63, 0.25),
    0 0 14px 3px rgba(201, 155, 63, 0.55),
    -14px 0 10px -2px rgba(201, 155, 63, 0.35);
  transform: translate(-50%, -50%);
  transition: left 0.6s linear;
  pointer-events: none;
  animation: comet-breath 2.4s ease-in-out infinite;
}

@keyframes comet-breath {
  0%, 100% { opacity: 0.75; }
  50% { opacity: 1; }
}

.fstep {
  position: relative;
  text-align: center;
}

.fstep__node {
  width: 46px;
  height: 46px;
  margin: 8px auto 0;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 13px;
  font-weight: 800;
  background: #fff;
  border: 2px solid rgba(60, 45, 25, 0.14);
  color: var(--tg-text-secondary);
  transition: transform 0.8s var(--tg-ease-spring), border-color 0.5s ease, color 0.5s ease, box-shadow 0.5s ease;
}

.fstep.on .fstep__node {
  border-color: #c99b3f;
  color: var(--gold-deep);
  transform: scale(1.12);
  box-shadow: 0 0 0 6px rgba(201, 155, 63, 0.14), 0 8px 20px rgba(143, 107, 34, 0.2);
}

.fstep__card {
  position: relative;
  margin-top: 18px;
  border-radius: 16px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.85);
  padding: 18px 14px 16px;
  overflow: hidden;
  transition: transform 0.8s var(--tg-ease), box-shadow 0.5s ease, border-color 0.5s ease;
}

/* 步骤卡片顶部色饰条(随调性,激活时展开) */
.fstep__card::before {
  content: '';
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 3px;
  background: var(--tone-solid);
  transform: scaleX(0);
  transform-origin: left;
  transition: transform 0.7s var(--tg-ease);
}

.fstep.on .fstep__card::before {
  transform: scaleX(1);
}

/* 五步调性:金 / 蜜 / 蜜桃 / 橄榄 / 陶土 */
.tone--gold {
  --tone: linear-gradient(90deg, #8f6b22, #c99b3f);
  --tone-solid: #b88a2f;
  --tone-bg: rgba(184, 138, 47, 0.13);
}

.tone--honey {
  --tone: linear-gradient(90deg, #c97a1f, #e89b3c);
  --tone-solid: #d98f2c;
  --tone-bg: rgba(232, 155, 60, 0.14);
}

.tone--violet {
  --tone: linear-gradient(90deg, #a87e26, #d9a966);
  --tone-solid: #bd9040;
  --tone-bg: rgba(189, 144, 64, 0.14);
}

.tone--olive {
  --tone: linear-gradient(90deg, #5c7a41, #9bbf6e);
  --tone-solid: #6b8e4e;
  --tone-bg: rgba(107, 142, 78, 0.14);
}

.tone--terra {
  --tone: linear-gradient(90deg, #9a4a2e, #d98a6a);
  --tone-solid: #b0653f;
  --tone-bg: rgba(176, 101, 63, 0.13);
}

.fstep.on .fstep__card {
  border-color: var(--tone-solid);
  transform: translateY(-4px);
  box-shadow: 0 14px 34px rgba(60, 45, 25, 0.1);
}

.fstep__tile {
  width: 38px;
  height: 38px;
  border-radius: 11px;
  display: flex;
  align-items: center;
  justify-content: center;
  margin: 0 auto 10px;
  background: var(--tone-bg);
  color: var(--tone-solid);
  transition: transform 0.5s var(--tg-ease-spring);
}

.fstep.on .fstep__tile {
  transform: scale(1.1) rotate(-5deg);
}

.fstep__step {
  display: block;
  font-size: 9.5px;
  font-weight: 700;
  letter-spacing: 0.2em;
  color: var(--tg-text-secondary);
  margin-bottom: 6px;
}

.fstep__card h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 650;
  color: var(--tg-text-primary);
}

.fstep__card p {
  margin: 6px 0 0;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--tg-text-secondary);
}

/* ===== CTA:旋转流光边框 ===== */
.cta-sec {
  padding: 184px 0 0;
}

.cta {
  position: relative;
  border-radius: 28px;
  padding: 2px;
  background: rgba(60, 45, 25, 0.12);
  overflow: hidden;
}

@property --rot {
  syntax: '<angle>';
  initial-value: 0deg;
  inherits: false;
}

.cta::before {
  content: '';
  position: absolute;
  inset: -60%;
  background: conic-gradient(
    from var(--rot),
    transparent 0deg,
    transparent 250deg,
    rgba(201, 155, 63, 0.85) 310deg,
    rgba(232, 200, 119, 0.9) 330deg,
    transparent 360deg
  );
  animation: rot-border 11s linear infinite;
}

@keyframes rot-border {
  to { --rot: 360deg; }
}

.cta__inner {
  position: relative;
  border-radius: 26px;
  background: linear-gradient(200deg, #fffdf8 0%, #faf3e2 100%);
  padding: 72px 40px;
  text-align: center;
  overflow: hidden;
}

/* 代码字符雨:铺在 CTA 底层,内容浮于其上 */
.cta-rain {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  pointer-events: none;
}

.cta__inner > *:not(.cta-rain) {
  position: relative;
  z-index: 1;
}

.cta h2 {
  margin: 0;
  font-size: clamp(28px, 4vw, 46px);
  font-weight: 800;
  color: var(--tg-text-primary);
}

.cta p {
  margin: 14px 0 0;
  font-size: 15px;
  color: var(--tg-text-secondary);
}

.cta .btn-gold {
  margin-top: 30px;
}

.cta__stack {
  margin-top: 32px;
  font-size: 12px;
  letter-spacing: 0.14em;
  color: var(--tg-text-secondary);
}

/* ===== 页脚 ===== */
.footer {
  position: relative;
  z-index: 1;
  border-top: 1px solid var(--line);
  background: rgba(250, 246, 239, 0.9);
  backdrop-filter: blur(10px);
  margin-top: 176px;
}

.footer__grid {
  max-width: 1200px;
  margin: 0 auto;
  padding: 60px 56px 40px;
  display: grid;
  grid-template-columns: 1.4fr 1fr 1fr 1fr;
  gap: 40px;
}

.footer__brand p {
  margin: 14px 0 0;
  font-size: 14px;
  line-height: 1.7;
  color: var(--tg-text-secondary);
  max-width: 260px;
}

.footer__badges {
  margin-top: 18px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.fbadge {
  font-family: var(--tg-font-mono);
  font-size: 10.5px;
  letter-spacing: 0.12em;
  color: var(--tg-text-secondary);
  padding: 5px 11px;
  border-radius: 999px;
  border: 1px solid var(--line);
  background: rgba(255, 255, 255, 0.8);
}

.footer__col {
  display: flex;
  flex-direction: column;
  gap: 11px;
}

.footer__col h4 {
  margin: 0 0 6px;
  font-family: var(--tg-font-mono);
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.18em;
  text-transform: uppercase;
  color: var(--tg-text-secondary);
}

.footer__col a {
  font-size: 14.5px;
  color: var(--tg-text-secondary);
  text-decoration: none;
  cursor: pointer;
  transition: color 0.2s ease, transform 0.2s ease;
}

.footer__col a:hover {
  color: var(--gold-deep);
  transform: translateX(2px);
}

.footer__bottom {
  max-width: 1200px;
  margin: 0 auto;
  padding: 24px 56px 36px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  border-top: 1px solid rgba(60, 45, 25, 0.07);
  font-size: 14px;
  color: var(--tg-text-secondary);
}

/* ===== 悬浮装饰减速 ===== */
.landing .tg-float {
  animation-duration: 9s;
}

/* ===== 入场动画(四种方向,不重复) ===== */
.landing .tg-reveal {
  opacity: 0;
  transition: opacity 1.4s var(--tg-ease), transform 1.4s var(--tg-ease), filter 1.4s var(--tg-ease);
  transform: translateY(34px);
  will-change: transform, opacity;
}

.landing .rv-left {
  transform: translateX(-44px);
}

.landing .rv-right {
  transform: translateX(44px);
}

.landing .rv-up {
  transform: translateY(34px) scale(0.97);
  filter: blur(4px);
}

.landing .tg-reveal.is-visible {
  opacity: 1;
  transform: none;
  filter: none;
}

/* ===== 进场/切换动画整体放慢(首屏 hero 模块除外) ===== */
.landing .tg-reveal {
  transition-duration: 2.4s;
}

.landing .wvis .wv-card {
  transition-duration: 1.3s;
}

.landing .wvis .wv-row,
.landing .wvis .wv-pipe,
.landing .wvis .wv-defect,
.landing .wvis .wv-engine {
  transition-duration: 1.05s;
}

.landing .wvis.on .wv-row:nth-child(2),
.landing .wvis.on .wv-pipe:nth-child(2),
.landing .wvis.on .wv-defect:nth-child(2) { transition-delay: 0.45s; }

.landing .wvis.on .wv-row:nth-child(3),
.landing .wvis.on .wv-pipe:nth-child(3),
.landing .wvis.on .wv-defect:nth-child(3) { transition-delay: 0.7s; }

.landing .wvis.on .wv-row:nth-child(4) { transition-delay: 0.95s; }

.landing .hud-swap-enter-active,
.landing .hud-swap-leave-active {
  transition-duration: 1s;
}

.landing .dc-line {
  animation-duration: 6.5s;
}

.landing .dc-area {
  animation-duration: 1.8s;
  animation-delay: 4.6s;
}

.landing .dc-dot {
  animation-delay: 6.4s;
}

.landing .stat.is-visible .stat__spark-line {
  animation-duration: 2.6s;
  animation-delay: 0.7s;
}

.landing .stat.is-visible .stat__spark-dot {
  animation-delay: 3s;
}

/* ===== 响应式 ===== */
@media (max-width: 1200px) {
  .hero-cluster {
    position: relative;
    width: 100%;
    height: 480px;
    margin-top: 44px;
  }

  .term {
    left: 0;
  }

  .live {
    right: 0;
  }

  .float-chip--1 {
    right: 6%;
  }
}

@media (max-width: 960px) {
  .landing-main {
    padding: 0 24px 56px;
  }

  .landing-nav__links {
    display: none;
  }

  .hero {
    padding-top: 64px;
  }

  .walk {
    height: auto;
  }

  .walk__pin {
    position: static;
    height: auto;
    padding: 90px 0 0;
  }

  .walk__grid,
  .walk__grid--mirror,
  .lab__grid {
    grid-template-columns: 1fr;
    gap: 32px;
  }

  .walk__stage {
    min-height: 430px;
  }

  .wvis {
    padding: 24px;
  }

  .stats__row {
    grid-template-columns: 1fr;
  }

  .feat {
    padding-top: 72px;
  }

  .feat__grid {
    grid-template-columns: 1fr;
    gap: 38px;
  }

  .globe__head {
    flex-direction: column;
    align-items: flex-start;
    gap: 24px;
  }

  .globe__stat {
    text-align: left;
  }

  .globe__stage {
    aspect-ratio: 4 / 3.2;
  }

  .flowtrack {
    grid-template-columns: 1fr;
    gap: 26px;
  }

  .flowtrack__line {
    left: 22px;
    right: auto;
    top: 8%;
    bottom: 8%;
    width: 3px;
    height: auto;
  }

  .flowtrack__line i {
    transform-origin: top;
    transform: scaleY(var(--fp, 0));
    width: 100%;
  }

  /* 移动端:光点改纵向巡航,HUD 标注收起(避免标签溢出) */
  .flowtrack__comet {
    left: 50%;
    top: calc(var(--fp, 0) * 100%);
    transform: translate(-50%, -50%);
    box-shadow:
      0 0 0 3px rgba(201, 155, 63, 0.25),
      0 0 14px 3px rgba(201, 155, 63, 0.55),
      0 -14px 10px -2px rgba(201, 155, 63, 0.35);
  }

  .dash-hud {
    display: none;
  }

  .dash__body {
    grid-template-columns: 1fr;
  }

  .dash__side {
    display: none;
  }

  .dash__row {
    grid-template-columns: 1fr;
  }

  .dash-hud {
    display: none;
  }

  .fstep {
    display: flex;
    align-items: flex-start;
    gap: 16px;
    text-align: left;
  }

  .fstep__node {
    margin: 0;
    flex-shrink: 0;
  }

  .fstep__card {
    flex: 1;
    margin-top: 0;
  }

  .footer__grid {
    grid-template-columns: 1fr 1fr;
    padding: 48px 24px 32px;
  }

  .footer__bottom {
    padding: 20px 24px 30px;
  }

  .ghost-num {
    display: none;
  }
}

/* ===== 小屏手机 ≤560px：导航收缩 + 区块进一步降噪 ===== */
@media (max-width: 560px) {
  .landing-nav__inner {
    padding: 0 14px;
  }

  /* 隐藏纯文字「登录」，保留主 CTA（登录仍可从 CTA / 页脚 / 底部区块进入，功能不缺失） */
  .nav-login {
    display: none;
  }

  .landing-main {
    padding: 0 16px 44px;
  }

  .hero {
    padding-top: 44px;
  }

  .hero-cluster {
    height: 360px;
  }

  .walk__stage {
    min-height: 340px;
  }

  .footer__grid {
    grid-template-columns: 1fr;
    gap: 26px;
    padding: 40px 18px 26px;
  }

  .footer__bottom {
    padding: 16px 18px 24px;
  }
}

/* ===== 降低动效偏好 ===== */
@media (prefers-reduced-motion: reduce) {
  .landing *,
  .landing *::before,
  .landing *::after {
    animation-duration: 0.01ms !important;
    animation-iteration-count: 1 !important;
    transition-duration: 0.01ms !important;
  }
}
</style>
