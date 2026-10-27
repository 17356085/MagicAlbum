<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import AppHeader from '@/components/AppHeader.vue'
import SidebarLeft from '@/components/SidebarLeft.vue'
import AiChat from '@/components/AiChat.vue'
import MobileNavigation from '@/components/MobileNavigation.vue'
import { useLiveNotifications } from '@/composables/useLiveNotifications'
import { useAuthStorageSync } from '@/composables/useAuthStorageSync'
import MediaProcessingStatus from '@/components/MediaProcessingStatus.vue'
import { useMediaProcessing } from '@/composables/useMediaProcessing'
import { useMobileViewport } from '@/composables/useMobileViewport'
import { useUIStore } from '@/stores/ui'
import { storeToRefs } from 'pinia'
import { SITE_NAME } from '@/config/brand'

interface SnowParticle {
  x: number
  y: number
  w: number
  h: number
  angle: number
  spin: number
  vy: number
  vx: number
  swayAmp: number
  swayFreq: number
  swayPhase: number
  alpha: number
}

interface StarParticle {
  x: number
  y: number
  r: number
  base: number
  amp: number
  phase: number
  speed: number
}

// UI 设置（动态背景开关）
const uiStore = useUIStore()
useAuthStorageSync()
useLiveNotifications()
useMediaProcessing()
const { dynamicBackgroundEnabled } = storeToRefs(uiStore)
const authRenderKey = ref(0)
const route = useRoute()
// Only pages that guard their own route and viewer requests opt into reuse.
const routeViewKey = computed(() => typeof route.meta?.publicViewKey === 'string'
  ? route.meta.publicViewKey
  : `${route.fullPath}:${authRenderKey.value}`)
const { inputActive, viewportStyle } = useMobileViewport()
const showMobileCreate = computed(() => ['/discover', '/sections', '/ranking', '/feed', '/my/threads'].includes(route.path))

function onAuthStateChanged(): void {
  authRenderKey.value += 1
}

// 监听暗色模式（检测 html.dark 与系统偏好）
const isDark = ref(false)
function updateIsDark(): void {
  const hasDarkClass = document.documentElement.classList.contains('dark')
  isDark.value = hasDarkClass
}
let themeObserver: MutationObserver | null = null
let mounted = false
const mediaListenerCleanups: Array<() => void> = []

function listenToMedia(query: string, handler: () => void): void {
  if (!window.matchMedia) return
  const media = window.matchMedia(query)
  if (typeof media.addEventListener === 'function') {
    media.addEventListener('change', handler)
    mediaListenerCleanups.push(() => media.removeEventListener('change', handler))
  } else if (typeof media.addListener === 'function') {
    media.addListener(handler)
    mediaListenerCleanups.push(() => media.removeListener(handler))
  }
}

// 减少动效偏好
const prefersReducedMotion = ref(false)
function updateReducedMotion(): void {
  prefersReducedMotion.value = !!(window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches)
}

// Day 模式下雪：Canvas 粒子（限帧 + 空闲启动）
const snowCanvas = ref<HTMLCanvasElement | null>(null)
let snowCtx: CanvasRenderingContext2D | null = null
let snowParticles: SnowParticle[] = []
let snowRAF: number | null = null
let snowIdle: number | null = null
let snowTimeout: number | null = null
let snowStartGeneration = 0
let snowLastTime = 0
const snowTargetFPS = 28
const snowFrameInterval = 1000 / snowTargetFPS

function makeParticles(width: number, height: number, count = 100): SnowParticle[] {
  const ps: SnowParticle[] = []
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
function drawSnow(ts: number): void {
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
function startSnow(): void {
  if (!mounted || !dynamicBackgroundEnabled.value || isDark.value || prefersReducedMotion.value || snowRAF !== null) return
  const canvas = snowCanvas.value
  if (!canvas) return
  const dpr = window.devicePixelRatio || 1
  const w = canvas.clientWidth, h = canvas.clientHeight
  canvas.width = w * dpr; canvas.height = h * dpr
  snowCtx = canvas.getContext('2d')
  if (!snowCtx) return
  snowCtx.setTransform(dpr, 0, 0, dpr, 0, 0)
  snowParticles = makeParticles(w, h, 60)
  snowLastTime = 0
  snowRAF = requestAnimationFrame(drawSnow)
}
function stopSnow(): void {
  cancelScheduledSnow()
  try { if (snowRAF !== null) cancelAnimationFrame(snowRAF) } catch {}
  snowRAF = null
  snowCtx = null
  snowParticles = []
}
function refreshSnow(): void { stopSnow(); startSnow() }

function scheduleStartSnow(): void {
  cancelScheduledSnow()
  const generation = snowStartGeneration
  const start = () => {
    if (generation !== snowStartGeneration) return
    snowIdle = null
    snowTimeout = null
    startSnow()
  }
  if (typeof window.requestIdleCallback === 'function' && typeof window.cancelIdleCallback === 'function') {
    try { snowIdle = window.requestIdleCallback(start); return } catch {}
  }
  snowTimeout = window.setTimeout(start, 200)
}

function cancelScheduledSnow(): void {
  snowStartGeneration += 1
  if (snowIdle !== null) window.cancelIdleCallback(snowIdle)
  if (snowTimeout !== null) window.clearTimeout(snowTimeout)
  snowIdle = null
  snowTimeout = null
}

function onResize(): void { if (snowCtx) refreshSnow() }

// 依据开关与主题决定是否启用下雪
watch([dynamicBackgroundEnabled, isDark, prefersReducedMotion], () => {
  if (dynamicBackgroundEnabled.value && !isDark.value && !prefersReducedMotion.value) scheduleStartSnow()
  else stopSnow()
}, { flush: 'post' })

onMounted(() => {
  mounted = true
  updateIsDark(); updateReducedMotion()
  // 监听系统动效偏好变化
  listenToMedia('(prefers-reduced-motion: reduce)', updateReducedMotion)
  // 监听暗色模式变化
  listenToMedia('(prefers-color-scheme: dark)', updateIsDark)
  themeObserver = new MutationObserver(updateIsDark)
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  // 初始根据状态启用下雪
  if (dynamicBackgroundEnabled.value && !isDark.value && !prefersReducedMotion.value) scheduleStartSnow()
  window.addEventListener('resize', onResize)
})

onUnmounted(() => {
  mounted = false
  window.removeEventListener('resize', onResize)
  stopSnow()
  mediaListenerCleanups.splice(0).forEach(cleanup => cleanup())
  if (themeObserver) { try { themeObserver.disconnect() } catch {}; themeObserver = null }
})
// Night：星光 Canvas（更自然、非规则稀疏的点点微光）
const starsCanvas = ref<HTMLCanvasElement | null>(null)
let starsCtx: CanvasRenderingContext2D | null = null
let stars: StarParticle[] = []
let starsRAF: number | null = null
let starsLastTime = 0
const starsTargetFPS = 20
const starsFrameInterval = 1000 / starsTargetFPS

function makeStars(w: number, h: number, count = 40): StarParticle[] {
  const arr: StarParticle[] = []
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
function drawStars(ts: number): void {
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
function startStars(): void {
  if (!mounted || !dynamicBackgroundEnabled.value || !isDark.value || prefersReducedMotion.value || starsRAF !== null) return
  const canvas = starsCanvas.value
  if (!canvas) return
  const dpr = window.devicePixelRatio || 1
  const w = canvas.clientWidth, h = canvas.clientHeight
  canvas.width = w * dpr; canvas.height = h * dpr
  starsCtx = canvas.getContext('2d')
  if (!starsCtx) return
  starsCtx.setTransform(dpr, 0, 0, dpr, 0, 0)
  stars = makeStars(w, h, 68)
  starsLastTime = 0
  starsRAF = requestAnimationFrame(drawStars)
}
function stopStars(): void {
  try { if (starsRAF !== null) cancelAnimationFrame(starsRAF) } catch {}
  starsRAF = null
  starsCtx = null
  stars = []
}
function refreshStars(): void { stopStars(); startStars() }

// 启停逻辑：夜间且启用开关时展示星光
watch([dynamicBackgroundEnabled, isDark, prefersReducedMotion], () => {
  if (dynamicBackgroundEnabled.value && isDark.value && !prefersReducedMotion.value) startStars()
  else stopStars()
}, { flush: 'post' })

// 复用 resize 钩子
function onResizeStars(): void { if (starsCtx) refreshStars() }
onMounted(() => {
  window.addEventListener('resize', onResizeStars)
  window.addEventListener('auth-state-changed', onAuthStateChanged)
})
onUnmounted(() => {
  window.removeEventListener('resize', onResizeStars)
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
  stopStars()
})

</script>
<template>
  <div class="community-app relative flex min-h-screen min-w-0 flex-col dark:text-gray-100" :class="{ 'mobile-input-active': inputActive }" :style="viewportStyle">
    <a href="#main-content" class="skip-link">跳到主要内容</a>
    <!-- Day/Night 背景质感层：Day 为雪、Night 为星光；可选轻微闪烁 -->
    <div :class="[
      'pointer-events-none absolute inset-0 z-0 motion-safe:transition-opacity motion-safe:duration-300',
      isDark ? 'stars-overlay' : 'snow-overlay',
      'opacity-20',
    ]"></div>
    <!-- Day 模式可选下雪 Canvas：仅在启用开关且非减少动效时运行 -->
    <canvas v-show="dynamicBackgroundEnabled && !isDark && !prefersReducedMotion" ref="snowCanvas" class="pointer-events-none absolute inset-0 z-0"></canvas>
    <canvas v-show="dynamicBackgroundEnabled && isDark && !prefersReducedMotion" ref="starsCanvas" class="pointer-events-none absolute inset-0 z-0"></canvas>
    <!-- 内容层：置于背景纹理之上，占满剩余空间 -->
    <div class="relative flex-1">
      <AppHeader />

    <main id="main-content" tabindex="-1" class="community-layout mx-auto grid w-full min-w-0 max-w-7xl grid-cols-1 items-start gap-8 px-4 py-6 sm:px-6 md:py-8">
      <div tabindex="0" aria-label="社区侧栏" class="community-sidebar-scroll ui-scroll hidden min-w-0 lg:sticky lg:top-24 lg:block lg:max-h-[calc(100vh-7rem)] lg:overflow-y-auto">
        <SidebarLeft />
      </div>
      <div class="min-w-0">
        <div class="space-y-4">
          <router-view :key="routeViewKey" />
        </div>
      </div>
    </main>
    </div>
    <!-- 页脚置于外层 flex 列容器中，通过 mt-auto 固定在底部 -->
    <footer class="community-footer mt-auto w-full">
      <div class="mx-auto flex max-w-7xl flex-wrap items-center justify-between gap-2 px-6 py-6 text-xs ui-muted"><span>© {{ new Date().getFullYear() }} {{ SITE_NAME }}</span><span>分享所爱，遇见同好。</span></div>
    </footer>
    <router-link v-if="showMobileCreate && !inputActive" to="/threads/new" class="mobile-create" aria-label="发帖">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" aria-hidden="true"><path d="M12 5v14M5 12h14" stroke-linecap="round" /></svg>
      <span>发帖</span>
    </router-link>
    <MobileNavigation v-show="!inputActive" />
    <MediaProcessingStatus v-show="!inputActive" :raised="showMobileCreate" />
    <AiChat />
  </div>
</template>

<style scoped>
.community-app { background: var(--ui-bg); color: var(--ui-ink); padding-top: calc(56px + env(safe-area-inset-top, 0px)); padding-bottom: calc(64px + env(safe-area-inset-bottom, 0px)); }
.mobile-create { position: fixed; z-index: var(--ui-z-floating); right: 16px; bottom: calc(80px + env(safe-area-inset-bottom, 0px)); display: inline-flex; gap: 6px; align-items: center; justify-content: center; min-height: 48px; padding: 0 18px; border-radius: 12px; background: #1d4ed8; color: white; font-weight: 600; }
.mobile-create svg { width: 20px; height: 20px; }
@media (min-width: 1024px) { .community-app { padding-top: 80px; padding-bottom: 0; } .mobile-create { display: none; } }
@media (max-width: 1023px) { .community-footer { display: none; } .community-layout { padding-top: 16px; padding-bottom: 80px; } }
.community-footer { border-top: 1px solid var(--ui-border); }
.skip-link { position: fixed; top: 8px; left: 16px; z-index: var(--ui-z-skip-link); transform: translateY(-150%); padding: 8px 16px; border-radius: 8px; color: var(--ui-surface); background: var(--ui-ink); }
.skip-link:focus { transform: translateY(0); }
@media (min-width: 1024px) { .community-layout { grid-template-columns: 220px minmax(0, 1fr); } }
.community-sidebar-scroll { padding-inline-end: 8px; }
.community-sidebar-scroll:focus-visible {
  outline: 2px solid var(--ui-accent);
  outline-offset: 3px;
}
@media (forced-colors: active) {
  .community-sidebar-scroll:focus-visible { outline-color: Highlight; }
}
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
