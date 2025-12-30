<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import HeaderBangumi from '@/components/HeaderBangumi.vue'
import SidebarLeft from '@/components/SidebarLeft.vue'
import AiChat from '@/components/AiChat.vue'
import { useUISettings } from '@/composables/useUISettings'

// UI 设置（动态背景开关）
const { dynamicBackgroundEnabled } = useUISettings()

// 监听暗色模式（检测 html.dark 与系统偏好）
const isDark = ref(false)
function updateIsDark() {
  const hasDarkClass = document.documentElement.classList.contains('dark')
  const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
  isDark.value = hasDarkClass || prefersDark
}
let themeObserver = null

// 减少动效偏好
const prefersReducedMotion = ref(false)
function updateReducedMotion() {
  prefersReducedMotion.value = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches)
}

// Day 模式下雪：Canvas 粒子（限帧 + 空闲启动）
const snowCanvas = ref(null)
let snowCtx = null
let snowParticles = []
let snowRAF = null
  let snowLastTime = 0
  const snowTargetFPS = 28
  const snowFrameInterval = 1000 / snowTargetFPS

function makeParticles(width, height, count = 100) {
  const ps = []
  for (let i = 0; i < count; i++) {
    // 片状雪花：更像“薄片”而非圆点；带轻微旋转与横向摆动
    const isLargeFlake = Math.random() < 0.30 // 更大：提升大雪片比例到 30%
    const w = isLargeFlake ? (2.0 + Math.random() * 1.0) : (1.2 + Math.random() * 0.8)
    const h = isLargeFlake ? (5.4 + Math.random() * 1.8) : (3.0 + Math.random() * 1.8) // 更大：大雪片高度上限至 7.2
    ps.push({
      x: Math.random() * width,
      y: Math.random() * height,
      w, h,
      angle: Math.random() * Math.PI,
      spin: (Math.random() - 0.5) * 0.02, // 轻微旋转速度
      vy: 0.35 + Math.random() * 0.55, // 更慢：下落速度范围调整为 0.35–0.9
      vx: (Math.random() - 0.5) * 0.12,
      swayAmp: 0.8 + Math.random() * 1.4, // 更慢：略增摆动幅度让下落更有“飘”的感觉（0.8–2.2）
      swayFreq: 0.6 + Math.random() * 0.8, // 横向摆动频率
      swayPhase: Math.random() * Math.PI * 2,
      alpha: 0.65 + Math.random() * 0.25
    })
  }
  return ps
}
function drawSnow(ts) {
  if (!snowCtx) return
  if (ts - snowLastTime < snowFrameInterval) { snowRAF = requestAnimationFrame(drawSnow); return }
  snowLastTime = ts
  const canvas = snowCanvas.value
  if (!canvas) return
  const w = canvas.clientWidth, h = canvas.clientHeight
  snowCtx.clearRect(0, 0, w, h)
  const tsec = ts / 1000
  for (const p of snowParticles) {
    // 横向轻微摆动 + 下落
    const sway = Math.sin((tsec * p.swayFreq) + p.swayPhase) * p.swayAmp
    p.x += p.vx + sway * 0.04
    p.y += p.vy
    p.angle += p.spin
    // 边界处理：底部重生在顶部，左右无缝环绕
    if (p.y > h + 6) { p.y = -6; p.x = Math.random() * w }
    if (p.x < -6) p.x = w + 6
    if (p.x > w + 6) p.x = -6
    // 绘制片状雪花（轻微模糊，避免明显几何感）
    snowCtx.save()
    snowCtx.translate(p.x, p.y)
    snowCtx.rotate(p.angle)
    snowCtx.globalAlpha = p.alpha
    snowCtx.shadowBlur = 1
    snowCtx.shadowColor = '#FFFFFF'
    snowCtx.fillStyle = '#FFFFFF'
    snowCtx.fillRect(-p.w / 2, -p.h / 2, p.w, p.h)
    snowCtx.restore()
  }
  snowRAF = requestAnimationFrame(drawSnow)
}
function startSnow() {
  if (prefersReducedMotion.value) return
  const canvas = snowCanvas.value
  if (!canvas) return
  const dpr = window.devicePixelRatio || 1
  const w = canvas.clientWidth, h = canvas.clientHeight
  canvas.width = w * dpr; canvas.height = h * dpr
  snowCtx = canvas.getContext('2d')
  snowCtx.setTransform(dpr, 0, 0, dpr, 0, 0)
  snowParticles = makeParticles(w, h, 60)
  snowLastTime = 0
  snowRAF = requestAnimationFrame(drawSnow)
}
function stopSnow() {
  try { if (snowRAF) cancelAnimationFrame(snowRAF) } catch {}
  snowRAF = null
  snowCtx = null
  snowParticles = []
}
function refreshSnow() { stopSnow(); startSnow() }

function scheduleStartSnow() {
  if ('requestIdleCallback' in window) {
    try { window.requestIdleCallback(() => startSnow()) } catch { setTimeout(startSnow, 200) }
  } else { setTimeout(startSnow, 200) }
}

  function onResize() { if (snowCtx) refreshSnow() }

// 依据开关与主题决定是否启用下雪
watch([dynamicBackgroundEnabled, isDark], () => {
  if (dynamicBackgroundEnabled.value && !isDark.value) scheduleStartSnow()
  else stopSnow()
})

  onMounted(() => {
  updateIsDark(); updateReducedMotion()
  // 监听系统动效偏好变化
  if (window.matchMedia) {
    const rmq = window.matchMedia('(prefers-reduced-motion: reduce)')
    const rmHandler = () => updateReducedMotion()
    if (typeof rmq.addEventListener === 'function') rmq.addEventListener('change', rmHandler)
    else if (typeof rmq.addListener === 'function') rmq.addListener(rmHandler)
  }
  // 监听暗色模式变化
  if (window.matchMedia) {
    const mq = window.matchMedia('(prefers-color-scheme: dark)')
    const handler = () => updateIsDark()
    if (typeof mq.addEventListener === 'function') mq.addEventListener('change', handler)
    else if (typeof mq.addListener === 'function') mq.addListener(handler)
  }
  themeObserver = new MutationObserver(updateIsDark)
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  // 初始根据状态启用下雪
  if (dynamicBackgroundEnabled.value && !isDark.value && !prefersReducedMotion.value) scheduleStartSnow()
  window.addEventListener('resize', onResize)
  })

  onUnmounted(() => {
  window.removeEventListener('resize', onResize)
  stopSnow()
  if (themeObserver) { try { themeObserver.disconnect() } catch {}; themeObserver = null }
  })
// Night：星光 Canvas（更自然、非规则稀疏的点点微光）
const starsCanvas = ref(null)
let starsCtx = null
let stars = []
let starsRAF = null
let starsLastTime = 0
const starsTargetFPS = 20
const starsFrameInterval = 1000 / starsTargetFPS

function makeStars(w, h, count = 40) {
  const arr = []
  for (let i = 0; i < count; i++) {
    arr.push({
      x: Math.random() * w,
      y: Math.random() * h,
      // 少数更大的星点以增强存在感，其余保持稀疏微光
      r: (Math.random() < 0.24) ? (2.2 + Math.random() * 0.6) : (0.8 + Math.random() * 0.8),
      base: 0.40 + Math.random() * 0.12, // 提升亮度范围至 0.40–0.52
      amp: 0.12 + Math.random() * 0.10,  // 轻微增幅，仍保持温和闪烁
      phase: Math.random() * Math.PI * 2,
      speed: 0.4 + Math.random() * 0.6   // 极慢闪烁
    })
  }
  return arr
}
function drawStars(ts) {
  if (!starsCtx) return
  if (ts - starsLastTime < starsFrameInterval) { starsRAF = requestAnimationFrame(drawStars); return }
  starsLastTime = ts
  const canvas = starsCanvas.value
  if (!canvas) return
  const w = canvas.clientWidth, h = canvas.clientHeight
  starsCtx.clearRect(0, 0, w, h)
  const t = ts / 1000
  // 轻柔发光：小范围阴影模拟微光
  starsCtx.shadowBlur = 4
  starsCtx.shadowColor = '#FFC247'
  for (const s of stars) {
    const tw = s.base + s.amp * Math.sin((t * (0.2 + s.speed * 0.05)) + s.phase)
    starsCtx.globalAlpha = Math.max(0, Math.min(1, tw))
    starsCtx.beginPath()
    starsCtx.arc(s.x, s.y, s.r, 0, Math.PI * 2)
    starsCtx.fillStyle = '#FFC247' // 更偏金黄的微光
    starsCtx.fill()
  }
  starsRAF = requestAnimationFrame(drawStars)
}
function startStars() {
  if (prefersReducedMotion.value) return
  const canvas = starsCanvas.value
  if (!canvas) return
  const dpr = window.devicePixelRatio || 1
  const w = canvas.clientWidth, h = canvas.clientHeight
  canvas.width = w * dpr; canvas.height = h * dpr
  starsCtx = canvas.getContext('2d')
  starsCtx.setTransform(dpr, 0, 0, dpr, 0, 0)
  stars = makeStars(w, h, 68)
  starsLastTime = 0
  starsRAF = requestAnimationFrame(drawStars)
}
function stopStars() {
  try { if (starsRAF) cancelAnimationFrame(starsRAF) } catch {}
  starsRAF = null
  starsCtx = null
  stars = []
}
function refreshStars() { stopStars(); startStars() }

// 启停逻辑：夜间且启用开关时展示星光
watch([dynamicBackgroundEnabled, isDark], () => {
  if (dynamicBackgroundEnabled.value && isDark.value) startStars()
  else stopStars()
})

// 复用 resize 钩子
function onResizeStars() { if (starsCtx) refreshStars() }
onMounted(() => { window.addEventListener('resize', onResizeStars) })
onUnmounted(() => { window.removeEventListener('resize', onResizeStars); stopStars() })

</script>
<template>
  <div class="relative min-h-screen flex flex-col bg-gradient-to-b from-brandDay-100 to-brandDay-200 dark:from-brandNight-900 dark:to-black dark:text-gray-100 pt-16 motion-safe:transition-colors motion-safe:duration-300 motion-reduce:transition-none">
    <!-- Day/Night 背景质感层：Day 为雪、Night 为星光；可选轻微闪烁 -->
    <div :class="[
      'pointer-events-none absolute inset-0 z-0 motion-safe:transition-opacity motion-safe:duration-300',
      isDark ? 'stars-overlay' : 'snow-overlay',
      'opacity-60', 'dark:opacity-60',
      (!dynamicBackgroundEnabled && isDark && !prefersReducedMotion) ? 'twinkle' : ''
    ]"></div>
    <!-- Day 模式可选下雪 Canvas：仅在启用开关且非减少动效时运行 -->
    <canvas v-show="dynamicBackgroundEnabled && !isDark && !prefersReducedMotion" ref="snowCanvas" class="pointer-events-none absolute inset-0 z-0"></canvas>
    <canvas v-show="dynamicBackgroundEnabled && isDark && !prefersReducedMotion" ref="starsCanvas" class="pointer-events-none absolute inset-0 z-0"></canvas>
    <!-- 内容层：置于背景纹理之上，占满剩余空间 -->
    <div class="relative z-10 flex-1">
      <HeaderBangumi />

    <main class="mx-auto max-w-7xl px-4 py-6 grid grid-cols-1 lg:grid-cols-12 gap-6 items-start w-full">
      <div class="sidebar-scroll hidden lg:block lg:col-span-3 lg:sticky lg:top-[calc(4rem+1.5rem)] lg:max-h-[calc(100vh-4rem-1.5rem)] lg:overflow-y-auto pr-1">
        <SidebarLeft />
      </div>
      <div class="lg:col-span-9">
        <div class="space-y-4">
          <router-view />
        </div>
      </div>
    </main>
    </div>
    <!-- 页脚置于外层 flex 列容器中，通过 mt-auto 固定在底部 -->
    <footer class="mt-auto border-t border-gray-200 bg-white dark:bg-gray-800 dark:border-gray-700 w-full motion-safe:transition-colors motion-safe:duration-300 motion-reduce:transition-none">
      <div class="mx-auto max-w-7xl px-4 py-6 text-xs text-gray-500 dark:text-gray-400">© 2025 MagicAlbum · 设计参考 bangumi.tv</div>
    </footer>
    <AiChat ref="aiChatRef" />
  </div>
</template>

<style scoped>
/* 左侧栏独立滚动但隐藏滚动条（仍可滚动） */
.sidebar-scroll { scrollbar-width: none; -ms-overflow-style: none; }
.sidebar-scroll::-webkit-scrollbar { width: 0; height: 0; display: none; }
</style>
<style scoped>
/* Day 模式：白雪质感（细小白点叠加，保持轻盈与低对比） */
.snow-overlay {
  background-image:
    radial-gradient(rgba(255, 255, 255, 0.85) 1px, rgba(255, 255, 255, 0) 2px),
    radial-gradient(rgba(255, 255, 255, 0.6) 0.8px, rgba(255, 255, 255, 0) 1.8px);
  background-size: 32px 32px, 56px 56px;
  background-position: 0 0, 16px 12px;
}

/* Night 模式：星光质感（多层不同大小与透明度的点状星光） */
.stars-overlay {
  background-image:
    radial-gradient(rgba(255, 194, 71, 0.34) 1px, rgba(255, 194, 71, 0) 4px),
    radial-gradient(rgba(255, 194, 71, 0.24) 1.2px, rgba(255, 194, 71, 0) 5px),
    radial-gradient(rgba(255, 194, 71, 0.12) 0.8px, rgba(255, 194, 71, 0) 3px);
  background-size: 140px 140px, 280px 280px, 220px 220px;
  background-position: 0 0, 70px 35px, 50px 20px;
}

/* Night：极慢闪烁动画（轻微透明度变化），尊重减少动效 */
@keyframes twinkleKF {
  0% { opacity: 0.40 }
  25% { opacity: 0.48 }
  50% { opacity: 0.42 }
  75% { opacity: 0.50 }
  100% { opacity: 0.40 }
}
.twinkle { animation: twinkleKF 6s ease-in-out infinite }
@media (prefers-reduced-motion: reduce) {
  .twinkle { animation: none }
}
</style>
