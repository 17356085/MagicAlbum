<template>
  <section class="ui-page">
    <header class="ui-page-header">
      <div>
        <p class="text-xs font-semibold uppercase tracking-[0.16em] text-[var(--ui-accent)]">社区协作</p>
        <h1 class="ui-title mt-1">举报审核</h1>
        <p class="ui-description max-w-2xl">
          {{ roleDescription }}举报内容会按服务器确认的分区范围显示；每次处理都需要留下理由。
        </p>
      </div>
      <button v-if="adminState === 'allowed'" type="button" class="ui-button shrink-0" :disabled="loading || pendingReportId !== null" @click="refreshReports">
        刷新列表
      </button>
    </header>

    <section v-if="adminState === 'checking'" class="ui-panel ui-empty" role="status" aria-live="polite">正在验证审核权限...</section>

    <section v-else-if="adminState === 'denied'" class="ui-error" role="alert" aria-live="assertive">
      <h2>无权访问举报审核</h2>
      <p class="mt-2">当前账号没有可用的审核范围。服务器已拒绝访问，登录状态不会因此被退出。</p>
      <button type="button" class="ui-button mt-4 border-red-200 text-red-700 dark:border-red-800 dark:text-red-200" @click="verifyAccess">重新验证</button>
    </section>

    <section v-else-if="adminState === 'error'" class="ui-error" role="alert" aria-live="assertive">
      <h2>审核权限验证失败</h2>
      <p class="mt-2">{{ accessError }}</p>
      <button type="button" class="ui-button mt-4 border-red-200 text-red-700 dark:border-red-800 dark:text-red-200" @click="verifyAccess">重试</button>
    </section>

    <template v-else-if="adminState === 'allowed'">
      <p class="ui-panel mb-4 px-4 py-3 text-sm text-[var(--ui-muted)]" role="status">
        {{ roleSummary }}
      </p>
      <p v-if="feedback" class="mb-4 rounded-lg border border-green-200 bg-green-50 px-4 py-3 text-sm text-green-700 dark:border-green-900/60 dark:bg-green-950/30 dark:text-green-300" role="status" aria-live="polite">{{ feedback }}</p>
      <p v-if="actionError" class="ui-error mb-4" role="alert" aria-live="assertive">{{ actionError }}</p>

      <section class="ui-panel overflow-hidden">
        <form class="ui-toolbar" @submit.prevent="submitFilters">
          <label for="report-status" class="text-sm font-semibold text-[var(--ui-ink)]">显示状态</label>
          <select id="report-status" v-model="selectedStatus" class="ui-field max-w-xs" :disabled="loading || pendingReportId !== null">
            <option v-for="status in statusOptions" :key="status.value" :value="status.value">{{ status.label }}</option>
          </select>
          <button type="submit" class="ui-button-primary" :disabled="loading || pendingReportId !== null">应用筛选</button>
        </form>

        <div v-if="loading" class="space-y-3 p-4" role="status" aria-live="polite">
          <div v-for="index in 3" :key="index" class="ui-skeleton h-24 w-full"></div>
        </div>

        <div v-else-if="reportsError" class="ui-error m-4" role="alert" aria-live="assertive">
          <p>{{ reportsError }}</p>
          <button type="button" class="ui-button mt-3" @click="refreshReports">重试</button>
        </div>

        <div v-else-if="reports.length === 0" class="ui-empty" role="status" aria-live="polite">
          <h2>{{ selectedStatus === 'PENDING' ? '当前没有待处理举报' : '没有符合条件的举报' }}</h2>
          <p>{{ selectedStatus === 'PENDING' ? '新的举报会在这里出现。' : '可以切换状态查看其他审核记录。' }}</p>
        </div>

        <ul v-else>
          <li v-for="report in reports" :key="String(report.id)" class="ui-list-row">
            <div class="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
              <div class="min-w-0 space-y-3">
                <div class="flex flex-wrap items-center gap-2 text-xs text-[var(--ui-muted)]">
                  <span class="ui-tag">{{ targetTypeLabel(report.targetType) }}</span>
                  <span class="ui-tag">{{ statusLabel(report.status) }}</span>
                  <span>举报编号 #{{ report.id }}</span>
                </div>
                <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-[var(--ui-ink)]">
                  <span>原因：{{ reasonLabel(report.reason) }}</span>
                  <span class="text-[var(--ui-muted)]">举报人 #{{ report.reporterId }}</span>
                  <time class="text-[var(--ui-muted)]" :datetime="report.createdAt">{{ formatDate(report.createdAt) }}</time>
                </div>
                <p class="text-sm leading-6 text-[var(--ui-ink)]">{{ report.detail || '未提供补充说明' }}</p>
                <p v-if="report.resolutionReason" class="text-sm leading-6 text-[var(--ui-muted)]">处理理由：{{ report.resolutionReason }}</p>
                <div class="flex flex-wrap items-center gap-3 text-xs">
                  <RouterLink v-if="threadPath(report)" :to="threadPath(report) || ''" class="font-medium text-[var(--ui-accent)] underline underline-offset-2 hover:no-underline">查看相关帖子</RouterLink>
                  <span class="text-[var(--ui-muted)]">目标 #{{ report.targetId }}</span>
                  <span v-if="report.reviewerId !== null && report.reviewerId !== undefined" class="text-[var(--ui-muted)]">处理人 #{{ report.reviewerId }}</span>
                </div>
              </div>

              <div v-if="canResolve(report)" class="flex shrink-0 flex-col gap-2 border-t border-[var(--ui-border)] pt-3 sm:flex-row sm:border-t-0 sm:pt-0 lg:flex-col xl:flex-row">
                <button v-if="report.status === 'PENDING'" type="button" class="ui-button" :disabled="pendingReportId !== null" @click="openResolution(report, 'DISMISS')">驳回举报</button>
                <button v-if="report.status === 'PENDING'" type="button" class="ui-button border-red-200 text-red-700 hover:bg-red-50 dark:border-red-900/70 dark:text-red-300 dark:hover:bg-red-950/40" :disabled="pendingReportId !== null" @click="openResolution(report, 'HIDE')">隐藏内容</button>
                <button v-if="report.status === 'HIDDEN'" type="button" class="ui-button-primary" :disabled="pendingReportId !== null" @click="openResolution(report, 'RESTORE')">恢复内容</button>
              </div>
            </div>

            <form v-if="activeResolution?.id === report.id" class="mt-4 rounded-lg border border-[var(--ui-border)] bg-[var(--ui-subtle)] p-4" @submit.prevent="submitResolution(report)">
              <label :for="`resolution-reason-${report.id}`" class="text-sm font-semibold text-[var(--ui-ink)]">{{ actionLabel(activeResolution.action) }}理由（必填）</label>
              <textarea :id="`resolution-reason-${report.id}`" v-model="resolutionReason" class="ui-field mt-2 min-h-24 resize-y" maxlength="500" required aria-describedby="resolution-reason-help" placeholder="说明审核依据，1–500 个字符"></textarea>
              <p id="resolution-reason-help" class="mt-1 text-xs text-[var(--ui-muted)]">理由会写入审核记录，提交失败时会保留。</p>
              <p v-if="reasonError" class="mt-2 text-sm text-red-700 dark:text-red-300" role="alert">{{ reasonError }}</p>
              <div class="mt-3 flex flex-wrap gap-2">
                <button type="submit" class="ui-button-primary" :disabled="pendingReportId !== null">确认{{ actionLabel(activeResolution.action) }}</button>
                <button type="button" class="ui-button" :disabled="pendingReportId !== null" @click="cancelResolution">取消</button>
              </div>
            </form>
          </li>
        </ul>

        <nav v-if="!loading && !reportsError && totalPages > 1" class="flex flex-col gap-3 border-t border-[var(--ui-border)] px-4 py-4 text-sm sm:flex-row sm:items-center sm:justify-between" aria-label="举报列表分页">
          <span class="text-[var(--ui-muted)]">共 {{ total }} 条举报</span>
          <div class="flex flex-wrap items-center gap-2">
            <button type="button" class="ui-button" :disabled="currentPage <= 1 || loading || pendingReportId !== null" @click="goToPage(currentPage - 1)">上一页</button>
            <span class="min-w-20 text-center text-[var(--ui-muted)]">第 {{ currentPage }} / {{ totalPages }} 页</span>
            <button type="button" class="ui-button" :disabled="currentPage >= totalPages || loading || pendingReportId !== null" @click="goToPage(currentPage + 1)">下一页</button>
          </div>
        </nav>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  listAdminReports,
  resolveAdminReport,
  validateAdminReportReason,
  type AdminReport,
  type AdminReportAction,
  type AdminReportStatus,
} from '@/api/admin'
import { getGovernanceMe, type GovernanceMe } from '@/api/governance'
import { useAuthStore } from '@/stores/auth'
import { getStoredAccessToken } from '@/utils/authStorage'
import type { Id } from '@/types'

type AdminViewState = 'checking' | 'allowed' | 'denied' | 'error'
type GovernanceRole = 'admin' | 'moderator'
interface ResolutionDraft { id: Id; action: AdminReportAction }
interface SessionSnapshot { token: string; generation: number }

const PAGE_SIZE = 20
const statusOptions: Array<{ value: AdminReportStatus; label: string }> = [
  { value: 'PENDING', label: '待处理' },
  { value: 'HIDDEN', label: '已隐藏' },
  { value: 'DISMISSED', label: '已驳回' },
  { value: 'RESTORED', label: '已恢复' },
]
const targetTypeLabels: Record<string, string> = { THREAD: '帖子', POST: '评论' }
const reasonLabels: Record<string, string> = { SPAM: '垃圾内容', HARASSMENT: '骚扰或辱骂', ABUSE: '辱骂或骚扰', ILLEGAL: '违规内容', ADULT: '不当内容', COPYRIGHT: '侵权内容', OTHER: '其他' }
const statusLabels: Record<string, string> = { PENDING: '待处理', DISMISSED: '已驳回', HIDDEN: '已隐藏', RESTORED: '已恢复', RESOLVED: '已处理' }

const adminState = ref<AdminViewState>('checking')
const role = ref<GovernanceRole | null>(null)
const governance = ref<GovernanceMe | null>(null)
const accessError = ref('暂时无法确认审核权限，请稍后重试。')
const reports = ref<AdminReport[]>([])
const reportsError = ref('')
const loading = ref(false)
const selectedStatus = ref<AdminReportStatus>('PENDING')
const currentPage = ref(1)
const pageSize = ref(PAGE_SIZE)
const total = ref(0)
const feedback = ref('')
const actionError = ref('')
const pendingReportId = ref<Id | null>(null)
const activeResolution = ref<ResolutionDraft | null>(null)
const resolutionReason = ref('')
const reasonError = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const roleDescription = computed(() => role.value === 'admin' ? '你是管理员。' : role.value === 'moderator' ? '你是版主。' : '')
const roleSummary = computed(() => {
  if (role.value === 'admin') return '管理员可以按服务器返回的权限处理举报；处理理由会进入社区审核记录。'
  const count = governance.value?.moderatedSectionIds.length || 0
  return `你可以审核 ${count} 个授权分区及其父线程范围内的举报；看不到范围外内容。`
})

let lifecycle = 0
let reportsRequestSeq = 0
let activeSession: SessionSnapshot = { token: '', generation: 0 }

function authStoreOrNull() {
  try { return useAuthStore() } catch (_) { return null }
}

function captureSession(): SessionSnapshot {
  const store = authStoreOrNull()
  return { token: getStoredAccessToken(), generation: store?.getAuthGeneration() ?? 0 }
}

function sessionCurrent(snapshot: SessionSnapshot, run: number, requestSeq?: number): boolean {
  if (run !== lifecycle || (requestSeq !== undefined && requestSeq !== reportsRequestSeq)) return false
  if (getStoredAccessToken() !== snapshot.token) return false
  const store = authStoreOrNull()
  return !store || store.isAuthGenerationCurrent(snapshot.generation)
}

function errorStatus(error: unknown): number | undefined {
  const raw = (error as { response?: { status?: unknown } } | null)?.response?.status
  const status = Number(raw)
  return Number.isFinite(status) ? status : undefined
}

function clearReportList(): void { reports.value = []; total.value = 0 }
function targetTypeLabel(value: string | null | undefined): string { const key = String(value || '').toUpperCase(); return targetTypeLabels[key] || String(value || '未知目标') }
function reasonLabel(value: string | null | undefined): string { const key = String(value || '').toUpperCase(); return reasonLabels[key] || String(value || '未说明') }
function statusLabel(value: string | null | undefined): string { const key = String(value || '').toUpperCase(); return statusLabels[key] || String(value || '未知状态') }
function actionLabel(action: AdminReportAction): string { return action === 'HIDE' ? '隐藏内容' : action === 'RESTORE' ? '恢复内容' : '驳回举报' }
function canResolve(report: AdminReport): boolean { return report.status === 'PENDING' || report.status === 'HIDDEN' }

function formatDate(value: string | null | undefined): string {
  if (!value) return '时间未知'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date)
}

function hasId(value: Id | null | undefined): value is Id { return value !== null && value !== undefined && String(value).trim() !== '' }
function threadPath(report: AdminReport): string | null {
  const type = String(report.targetType || '').toUpperCase()
  if (type === 'THREAD' && hasId(report.targetId)) return `/threads/${encodeURIComponent(String(report.targetId))}`
  if (type === 'POST') {
    const threadId = report.threadId ?? report.targetThreadId
    if (hasId(threadId)) return `/threads/${encodeURIComponent(String(threadId))}#post-${encodeURIComponent(String(report.targetId))}`
  }
  return null
}

async function loadReports(run = lifecycle, snapshot = activeSession): Promise<boolean> {
  const requestSeq = ++reportsRequestSeq
  loading.value = true
  reportsError.value = ''
  try {
    const result = await listAdminReports({ status: selectedStatus.value, page: currentPage.value, size: PAGE_SIZE })
    if (!sessionCurrent(snapshot, run, requestSeq)) return false
    reports.value = result.items || []
    currentPage.value = Number(result.page) > 0 ? Number(result.page) : currentPage.value
    pageSize.value = Number(result.size) > 0 ? Number(result.size) : PAGE_SIZE
    total.value = Number(result.total) >= 0 ? Number(result.total) : 0
    return true
  } catch (error) {
    if (sessionCurrent(snapshot, run, requestSeq)) {
      clearReportList()
      if (errorStatus(error) === 403) {
        adminState.value = 'denied'
        accessError.value = '当前账号的审核范围已变化，请重新验证。'
      } else {
        reportsError.value = '举报列表加载失败，请重试。'
      }
    }
    return false
  } finally {
    if (sessionCurrent(snapshot, run, requestSeq)) loading.value = false
  }
}

async function verifyAccess(): Promise<void> {
  const run = ++lifecycle
  reportsRequestSeq += 1
  const snapshot = captureSession()
  activeSession = snapshot
  pendingReportId.value = null
  feedback.value = ''
  actionError.value = ''
  reportsError.value = ''
  activeResolution.value = null
  resolutionReason.value = ''
  reasonError.value = ''
  clearReportList()
  currentPage.value = 1
  pageSize.value = PAGE_SIZE
  loading.value = false
  role.value = null
  governance.value = null
  adminState.value = 'checking'

  try {
    const me = await getGovernanceMe()
    if (!sessionCurrent(snapshot, run)) return
    governance.value = me
    if (!me.admin && me.moderatedSectionIds.length === 0) {
      adminState.value = 'denied'
      return
    }
    role.value = me.admin ? 'admin' : 'moderator'
    adminState.value = 'allowed'
    await loadReports(run, snapshot)
  } catch (error) {
    if (!sessionCurrent(snapshot, run)) return
    clearReportList()
    if (errorStatus(error) === 403) {
      adminState.value = 'denied'
      return
    }
    adminState.value = 'error'
    accessError.value = '审核权限验证失败，请重试。'
  }
}

function refreshReports(): void {
  feedback.value = ''
  actionError.value = ''
  void loadReports(lifecycle, activeSession)
}

function submitFilters(): void {
  if (pendingReportId.value !== null) return
  currentPage.value = 1
  activeResolution.value = null
  resolutionReason.value = ''
  reasonError.value = ''
  feedback.value = ''
  actionError.value = ''
  void loadReports(lifecycle, activeSession)
}

function goToPage(page: number): void {
  const nextPage = Math.min(Math.max(1, page), totalPages.value)
  if (nextPage === currentPage.value || pendingReportId.value !== null) return
  currentPage.value = nextPage
  feedback.value = ''
  void loadReports(lifecycle, activeSession)
}

function openResolution(report: AdminReport, action: AdminReportAction): void {
  if (!canResolve(report) || pendingReportId.value !== null) return
  activeResolution.value = { id: report.id, action }
  resolutionReason.value = ''
  reasonError.value = ''
  actionError.value = ''
}

function cancelResolution(): void {
  if (pendingReportId.value !== null) return
  activeResolution.value = null
  resolutionReason.value = ''
  reasonError.value = ''
}

async function submitResolution(report: AdminReport): Promise<void> {
  const active = activeResolution.value
  if (!active || String(active.id) !== String(report.id) || pendingReportId.value !== null) return
  let reason: string
  try { reason = validateAdminReportReason(resolutionReason.value) } catch (error) {
    reasonError.value = error instanceof Error ? error.message : '请填写处理理由。'
    return
  }
  const run = lifecycle
  const snapshot = activeSession
  pendingReportId.value = report.id
  feedback.value = ''
  actionError.value = ''
  reasonError.value = ''
  try {
    await resolveAdminReport(report.id, active.action, reason)
    const refreshed = await loadReports(run, snapshot)
    if (!sessionCurrent(snapshot, run)) return
    activeResolution.value = null
    resolutionReason.value = ''
    feedback.value = refreshed ? `${actionLabel(active.action)}成功，列表已刷新。` : `${actionLabel(active.action)}成功，但列表刷新失败，请重试。`
  } catch (_) {
    if (sessionCurrent(snapshot, run)) actionError.value = `${actionLabel(active.action)}失败，请重试。`
  } finally {
    if (sessionCurrent(snapshot, run) && String(pendingReportId.value) === String(report.id)) pendingReportId.value = null
  }
}

function onAuthStateChanged(event: Event): void {
  lifecycle += 1
  reportsRequestSeq += 1
  pendingReportId.value = null
  clearReportList()
  feedback.value = ''
  actionError.value = ''
  reportsError.value = ''
  activeResolution.value = null
  resolutionReason.value = ''
  reasonError.value = ''
  role.value = null
  governance.value = null
  const loggedIn = (event as CustomEvent<{ loggedIn?: boolean }>).detail?.loggedIn
  if (loggedIn === false) {
    adminState.value = 'denied'
    accessError.value = '请先登录后访问举报审核。'
    return
  }
  void verifyAccess()
}

onMounted(() => {
  window.addEventListener('auth-state-changed', onAuthStateChanged)
  void verifyAccess()
})

onBeforeUnmount(() => {
  lifecycle += 1
  reportsRequestSeq += 1
  pendingReportId.value = null
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
})
</script>
