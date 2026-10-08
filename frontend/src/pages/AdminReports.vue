<template>
  <main class="min-w-0 space-y-4">
    <header class="u-card flex flex-col gap-4 p-5 sm:flex-row sm:items-start sm:justify-between">
      <div>
        <p class="text-xs font-semibold uppercase tracking-[0.16em] text-brandDay-600 dark:text-brandNight-300">管理员工作台</p>
        <h1 class="mt-1 text-2xl font-bold text-gray-900 dark:text-gray-100">举报审核</h1>
        <p class="mt-2 max-w-2xl text-sm leading-6 text-gray-600 dark:text-gray-300">
          只显示服务器确认可见的待处理举报。处理前会再次确认，避免误操作。
        </p>
      </div>
      <button
        v-if="adminState === 'allowed'"
        type="button"
        class="inline-flex shrink-0 items-center justify-center rounded-lg border border-gray-200 bg-white px-3 py-2 text-sm font-medium text-gray-700 transition-colors hover:border-brandDay-300 hover:text-brandDay-700 disabled:cursor-not-allowed disabled:opacity-50 dark:border-gray-700 dark:bg-gray-800 dark:text-gray-200 dark:hover:border-brandNight-500 dark:hover:text-brandNight-200"
        :disabled="loading || pendingReportId !== null"
        @click="refreshReports"
      >
        刷新列表
      </button>
    </header>

    <section v-if="adminState === 'checking'" class="u-card p-8 text-center text-sm text-gray-600 dark:text-gray-300" role="status" aria-live="polite">
      正在验证管理员权限...
    </section>

    <section v-else-if="adminState === 'denied'" class="u-card border-red-100 bg-red-50/80 p-6 dark:border-red-900/60 dark:bg-red-950/30" role="alert" aria-live="assertive">
      <h2 class="text-lg font-semibold text-red-800 dark:text-red-200">无权访问举报审核</h2>
      <p class="mt-2 text-sm leading-6 text-red-700 dark:text-red-300">
        当前账号没有管理员权限。服务器已拒绝访问，登录状态不会因此被退出。
      </p>
      <button
        type="button"
        class="mt-4 rounded-lg border border-red-200 bg-white px-3 py-2 text-sm font-medium text-red-700 hover:bg-red-50 dark:border-red-800 dark:bg-gray-900/40 dark:text-red-200 dark:hover:bg-red-950/50"
        @click="verifyAccess"
      >
        重新验证
      </button>
    </section>

    <section v-else-if="adminState === 'error'" class="u-card border-red-100 bg-red-50/80 p-6 dark:border-red-900/60 dark:bg-red-950/30" role="alert" aria-live="assertive">
      <h2 class="text-lg font-semibold text-red-800 dark:text-red-200">管理员权限验证失败</h2>
      <p class="mt-2 text-sm leading-6 text-red-700 dark:text-red-300">{{ accessError }}</p>
      <button
        type="button"
        class="mt-4 rounded-lg border border-red-200 bg-white px-3 py-2 text-sm font-medium text-red-700 hover:bg-red-50 dark:border-red-800 dark:bg-gray-900/40 dark:text-red-200 dark:hover:bg-red-950/50"
        @click="verifyAccess"
      >
        重试
      </button>
    </section>

    <template v-else-if="adminState === 'allowed'">
      <p v-if="feedback" class="rounded-lg border border-green-200 bg-green-50 px-4 py-3 text-sm text-green-700 dark:border-green-900/60 dark:bg-green-950/30 dark:text-green-300" role="status" aria-live="polite">
        {{ feedback }}
      </p>
      <p v-if="actionError" class="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700 dark:border-red-900/60 dark:bg-red-950/30 dark:text-red-300" role="alert" aria-live="assertive">
        {{ actionError }}
      </p>

      <section class="u-card overflow-hidden">
        <div v-if="loading" class="p-8 text-center text-sm text-gray-600 dark:text-gray-300" role="status" aria-live="polite">
          正在加载举报...
        </div>

        <div v-else-if="reportsError" class="m-4 rounded-lg border border-red-100 bg-red-50/80 p-5 dark:border-red-900/60 dark:bg-red-950/30" role="alert" aria-live="assertive">
          <p class="text-sm text-red-700 dark:text-red-300">{{ reportsError }}</p>
          <button
            type="button"
            class="mt-3 rounded-lg border border-red-200 bg-white px-3 py-2 text-sm font-medium text-red-700 hover:bg-red-50 dark:border-red-800 dark:bg-gray-900/40 dark:text-red-200 dark:hover:bg-red-950/50"
            @click="refreshReports"
          >
            重试
          </button>
        </div>

        <div v-else-if="reports.length === 0" class="p-10 text-center" role="status" aria-live="polite">
          <p class="text-base font-medium text-gray-700 dark:text-gray-200">当前没有待处理举报</p>
          <p class="mt-1 text-sm text-gray-500 dark:text-gray-400">新的举报会在这里出现。</p>
        </div>

        <ul v-else class="divide-y divide-gray-100 dark:divide-gray-700/70">
          <li v-for="report in reports" :key="String(report.id)" class="p-4 sm:p-5">
            <div class="flex flex-col gap-4 lg:flex-row lg:items-start lg:justify-between">
              <div class="min-w-0 space-y-3">
                <div class="flex flex-wrap items-center gap-2 text-xs text-gray-500 dark:text-gray-400">
                  <span class="rounded-full bg-brandDay-50 px-2.5 py-1 font-semibold text-brandDay-700 dark:bg-brandNight-900/40 dark:text-brandNight-200">
                    {{ targetTypeLabel(report.targetType) }}
                  </span>
                  <span class="rounded-full bg-gray-100 px-2.5 py-1 font-medium text-gray-600 dark:bg-gray-700 dark:text-gray-200">
                    {{ statusLabel(report.status) }}
                  </span>
                  <span>举报编号 #{{ report.id }}</span>
                </div>

                <div class="flex flex-wrap items-center gap-x-4 gap-y-1 text-sm text-gray-700 dark:text-gray-200">
                  <span>原因：{{ reasonLabel(report.reason) }}</span>
                  <span class="text-gray-500 dark:text-gray-400">举报人 #{{ report.reporterId }}</span>
                  <time class="text-gray-500 dark:text-gray-400" :datetime="report.createdAt">{{ formatDate(report.createdAt) }}</time>
                </div>

                <p class="text-sm leading-6 text-gray-700 dark:text-gray-300">
                  {{ report.detail || '未提供补充说明' }}
                </p>

                <div class="flex flex-wrap items-center gap-3 text-xs">
                  <RouterLink
                    v-if="threadPath(report)"
                    :to="threadPath(report) || ''"
                    class="font-medium text-brandDay-600 underline decoration-brandDay-200 underline-offset-2 hover:text-brandDay-800 dark:text-brandNight-300 dark:decoration-brandNight-700 dark:hover:text-brandNight-100"
                  >
                    查看相关帖子
                  </RouterLink>
                  <span class="text-gray-500 dark:text-gray-400">
                    目标 #{{ report.targetId }}
                  </span>
                </div>
              </div>

              <div v-if="report.status === 'PENDING'" class="flex shrink-0 flex-col gap-2 sm:flex-row lg:flex-col xl:flex-row">
                <button
                  type="button"
                  class="rounded-lg border border-gray-200 px-3 py-2 text-sm font-medium text-gray-700 transition-colors hover:border-gray-400 hover:bg-gray-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-gray-600 dark:text-gray-200 dark:hover:bg-gray-700"
                  :disabled="pendingReportId !== null"
                  @click="resolveReport(report, 'DISMISS')"
                >
                  驳回举报
                </button>
                <button
                  type="button"
                  class="rounded-lg border border-red-200 px-3 py-2 text-sm font-medium text-red-700 transition-colors hover:bg-red-50 disabled:cursor-not-allowed disabled:opacity-50 dark:border-red-900/70 dark:text-red-300 dark:hover:bg-red-950/40"
                  :disabled="pendingReportId !== null"
                  @click="resolveReport(report, 'HIDE')"
                >
                  隐藏内容
                </button>
              </div>
            </div>
          </li>
        </ul>

        <nav v-if="!loading && !reportsError && totalPages > 1" class="flex flex-col gap-3 border-t border-gray-100 px-4 py-4 text-sm dark:border-gray-700/70 sm:flex-row sm:items-center sm:justify-between" aria-label="举报列表分页">
          <span class="text-gray-500 dark:text-gray-400">共 {{ total }} 条举报</span>
          <div class="flex items-center gap-2">
            <button
              type="button"
              class="rounded-lg border border-gray-200 px-3 py-2 text-gray-700 hover:border-brandDay-300 disabled:cursor-not-allowed disabled:opacity-50 dark:border-gray-700 dark:text-gray-200 dark:hover:border-brandNight-500"
              :disabled="currentPage <= 1 || loading || pendingReportId !== null"
              @click="goToPage(currentPage - 1)"
            >
              上一页
            </button>
            <span class="min-w-20 text-center text-gray-600 dark:text-gray-300">第 {{ currentPage }} / {{ totalPages }} 页</span>
            <button
              type="button"
              class="rounded-lg border border-gray-200 px-3 py-2 text-gray-700 hover:border-brandDay-300 disabled:cursor-not-allowed disabled:opacity-50 dark:border-gray-700 dark:text-gray-200 dark:hover:border-brandNight-500"
              :disabled="currentPage >= totalPages || loading || pendingReportId !== null"
              @click="goToPage(currentPage + 1)"
            >
              下一页
            </button>
          </div>
        </nav>
      </section>
    </template>
  </main>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import {
  getAdminStatus,
  listAdminReports,
  resolveAdminReport,
  type AdminReport,
  type AdminReportAction,
} from '@/api/admin'
import type { Id } from '@/types'

type AdminViewState = 'checking' | 'allowed' | 'denied' | 'error'

const PAGE_SIZE = 20

const targetTypeLabels: Record<string, string> = {
  THREAD: '帖子',
  POST: '评论',
}

const reasonLabels: Record<string, string> = {
  SPAM: '垃圾内容',
  HARASSMENT: '骚扰或辱骂',
  ABUSE: '辱骂或骚扰',
  ILLEGAL: '违规内容',
  ADULT: '不当内容',
  COPYRIGHT: '侵权内容',
  OTHER: '其他',
}

const statusLabels: Record<string, string> = {
  PENDING: '待处理',
  DISMISSED: '已驳回',
  HIDDEN: '已隐藏',
  RESOLVED: '已处理',
}

const adminState = ref<AdminViewState>('checking')
const accessError = ref('暂时无法确认管理员权限，请稍后重试。')
const reports = ref<AdminReport[]>([])
const reportsError = ref('')
const loading = ref(false)
const currentPage = ref(1)
const pageSize = ref(PAGE_SIZE)
const total = ref(0)
const feedback = ref('')
const actionError = ref('')
const pendingReportId = ref<Id | null>(null)

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

let lifecycle = 0
let reportsRequestSeq = 0

function errorStatus(error: unknown): number | undefined {
  const raw = (error as { response?: { status?: unknown } } | null)?.response?.status
  const status = Number(raw)
  return Number.isFinite(status) ? status : undefined
}

function clearReportList(): void {
  reports.value = []
  total.value = 0
}

function targetTypeLabel(value: string | null | undefined): string {
  const key = String(value || '').toUpperCase()
  return targetTypeLabels[key] || String(value || '未知目标')
}

function reasonLabel(value: string | null | undefined): string {
  const key = String(value || '').toUpperCase()
  return reasonLabels[key] || String(value || '未说明')
}

function statusLabel(value: string | null | undefined): string {
  const key = String(value || '').toUpperCase()
  return statusLabels[key] || String(value || '未知状态')
}

function formatDate(value: string | null | undefined): string {
  if (!value) return '时间未知'
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return value
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  }).format(date)
}

function hasId(value: Id | null | undefined): value is Id {
  return value !== null && value !== undefined && String(value).trim() !== ''
}

function threadPath(report: AdminReport): string | null {
  const targetType = String(report.targetType || '').toUpperCase()
  if (targetType === 'THREAD' && hasId(report.targetId)) {
    return `/threads/${encodeURIComponent(String(report.targetId))}`
  }
  if (targetType === 'POST') {
    const threadId = report.threadId ?? report.targetThreadId
    if (hasId(threadId)) {
      return `/threads/${encodeURIComponent(String(threadId))}#post-${encodeURIComponent(String(report.targetId))}`
    }
  }
  return null
}

async function loadReports(run = lifecycle): Promise<boolean> {
  const requestSeq = ++reportsRequestSeq
  loading.value = true
  reportsError.value = ''
  try {
    const result = await listAdminReports({ status: 'PENDING', page: currentPage.value, size: PAGE_SIZE })
    if (run !== lifecycle || requestSeq !== reportsRequestSeq) return false

    reports.value = result.items || []
    currentPage.value = Number(result.page) > 0 ? Number(result.page) : currentPage.value
    pageSize.value = Number(result.size) > 0 ? Number(result.size) : PAGE_SIZE
    total.value = Number(result.total) >= 0 ? Number(result.total) : 0
    return true
  } catch (_) {
    if (run === lifecycle && requestSeq === reportsRequestSeq) {
      clearReportList()
      reportsError.value = '举报列表加载失败，请重试。'
    }
    return false
  } finally {
    if (run === lifecycle && requestSeq === reportsRequestSeq) {
      loading.value = false
    }
  }
}

async function verifyAccess(): Promise<void> {
  const run = ++lifecycle
  reportsRequestSeq += 1
  pendingReportId.value = null
  feedback.value = ''
  actionError.value = ''
  reportsError.value = ''
  clearReportList()
  currentPage.value = 1
  pageSize.value = PAGE_SIZE
  loading.value = false
  adminState.value = 'checking'

  try {
    const allowed = await getAdminStatus()
    if (run !== lifecycle) return
    if (!allowed) {
      adminState.value = 'denied'
      return
    }

    adminState.value = 'allowed'
    await loadReports(run)
  } catch (error) {
    if (run !== lifecycle) return
    clearReportList()
    if (errorStatus(error) === 403) {
      adminState.value = 'denied'
      return
    }
    adminState.value = 'error'
    accessError.value = '管理员权限验证失败，请重试。'
  }
}

function refreshReports(): void {
  feedback.value = ''
  actionError.value = ''
  void loadReports(lifecycle)
}

function goToPage(page: number): void {
  const nextPage = Math.min(Math.max(1, page), totalPages.value)
  if (nextPage === currentPage.value || pendingReportId.value !== null) return
  currentPage.value = nextPage
  feedback.value = ''
  void loadReports(lifecycle)
}

async function resolveReport(report: AdminReport, action: AdminReportAction): Promise<void> {
  if (report.status !== 'PENDING' || pendingReportId.value !== null) return

  const actionLabel = action === 'HIDE' ? '隐藏内容' : '驳回举报'
  if (!window.confirm(`确认${actionLabel} #${report.id} 吗？`)) return

  pendingReportId.value = report.id
  feedback.value = ''
  actionError.value = ''
  try {
    await resolveAdminReport(report.id, action)
    const refreshed = await loadReports(lifecycle)
    feedback.value = refreshed
      ? `${actionLabel}成功，列表已刷新。`
      : `${actionLabel}成功，但列表刷新失败，请重试。`
  } catch (_) {
    actionError.value = `${actionLabel}失败，请重试。`
  } finally {
    if (pendingReportId.value === report.id) {
      pendingReportId.value = null
    }
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
  window.removeEventListener('auth-state-changed', onAuthStateChanged)
})
</script>
