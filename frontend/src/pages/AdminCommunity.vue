<template>
  <section class="ui-page">
    <header class="ui-page-header">
      <div>
        <p class="text-xs font-semibold uppercase tracking-[0.16em] text-[var(--ui-accent)]">社区治理</p>
        <h1 class="ui-title mt-1">社区管理</h1>
        <p class="ui-description max-w-2xl">管理成员权限、版主分区和社区分区。每次变更都需要说明理由，并会留下可追溯记录。</p>
      </div>
      <button v-if="viewState === 'allowed'" type="button" class="ui-button shrink-0" :disabled="usersLoading || pendingKey !== null" @click="refreshAll">刷新数据</button>
    </header>

    <section v-if="viewState === 'checking'" class="ui-panel ui-empty" role="status" aria-live="polite">正在验证社区管理权限...</section>

    <section v-else-if="viewState === 'denied'" class="ui-error" role="alert" aria-live="assertive">
      <h2>无权访问社区管理</h2>
      <p class="mt-2">社区管理只对管理员开放。服务器已拒绝访问，登录状态不会因此被退出。</p>
      <button type="button" class="ui-button mt-4 border-red-200 text-red-700 dark:border-red-800 dark:text-red-200" @click="verifyAccess">重新验证</button>
    </section>

    <section v-else-if="viewState === 'error'" class="ui-error" role="alert" aria-live="assertive">
      <h2>权限验证失败</h2>
      <p class="mt-2">{{ accessError }}</p>
      <button type="button" class="ui-button mt-4 border-red-200 text-red-700 dark:border-red-800 dark:text-red-200" @click="verifyAccess">重试</button>
    </section>

    <template v-else-if="viewState === 'allowed'">
      <p v-if="feedback" class="mb-4 rounded-lg border border-green-200 bg-green-50 px-4 py-3 text-sm text-green-700 dark:border-green-900/60 dark:bg-green-950/30 dark:text-green-300" role="status" aria-live="polite">{{ feedback }}</p>
      <p v-if="operationError" class="ui-error mb-4" role="alert" aria-live="assertive">{{ operationError }}</p>

      <section class="ui-panel mb-6 overflow-hidden">
        <div class="ui-toolbar">
          <div>
            <h2 class="font-semibold text-[var(--ui-ink)]">成员权限</h2>
            <p class="mt-1 text-sm text-[var(--ui-muted)]">搜索成员后分别保存管理员权限、版主分区和发帖限制。</p>
          </div>
        </div>
        <form class="ui-toolbar" @submit.prevent="submitUserSearch">
          <label for="governance-user-search" class="sr-only">搜索用户名</label>
          <input id="governance-user-search" v-model="searchQuery" class="ui-field flex-1" type="search" maxlength="128" placeholder="搜索用户名" autocomplete="off">
          <button type="submit" class="ui-button-primary" :disabled="usersLoading || pendingKey !== null">搜索</button>
        </form>

        <div v-if="usersLoading" class="space-y-3 p-4" role="status" aria-live="polite">
          <div v-for="index in 3" :key="index" class="ui-skeleton h-48 w-full"></div>
        </div>
        <div v-else-if="usersError" class="ui-error m-4" role="alert" aria-live="assertive">
          <p>{{ usersError }}</p>
          <button type="button" class="ui-button mt-3" @click="loadUsers()">重试</button>
        </div>
        <div v-else-if="users.length === 0" class="ui-empty" role="status" aria-live="polite">
          <h2>没有找到成员</h2>
          <p>换一个用户名或清空搜索条件再试。</p>
        </div>
        <ul v-else>
          <li v-for="user in users" :key="String(user.id)" class="ui-list-row">
            <details class="governance-member">
            <summary class="flex flex-wrap items-center justify-between gap-3">
              <div>
                <h3 class="font-semibold text-[var(--ui-ink)]">{{ user.username }}</h3>
                <p class="text-xs text-[var(--ui-muted)]">成员 #{{ user.id }}<span v-if="user.admin"> · 管理员</span></p>
              </div>
              <span class="flex flex-wrap items-center gap-2">
                <span v-if="user.postingRestricted" class="ui-tag text-red-700 dark:text-red-300">发帖受限</span>
                <span v-else class="ui-tag">正常发帖</span>
                <span class="text-sm font-medium text-[var(--ui-accent)]">管理权限 <span aria-hidden="true">⌄</span></span>
              </span>
            </summary>

            <div class="governance-user-grid mt-5">
              <section class="governance-control">
                <h4>管理员权限</h4>
                <p class="governance-help">管理员可以管理全站社区规则与成员权限。</p>
                <label :for="`admin-reason-${user.id}`">变更理由</label>
                <textarea :id="`admin-reason-${user.id}`" v-model="ensureUserDraft(user).adminReason" class="ui-field min-h-20 resize-y" maxlength="500" placeholder="1–500 个字符"></textarea>
                <button type="button" class="ui-button mt-3" :disabled="pendingKey !== null" @click="saveAdminRole(user)">{{ user.admin ? '撤销管理员' : '授予管理员' }}</button>
              </section>

              <section class="governance-control">
                <h4>版主管理分区</h4>
                <p class="governance-help">版主只能审核已授权分区及其父线程范围内的内容。</p>
                <fieldset class="mt-3 space-y-2">
                  <legend class="sr-only">{{ user.username }} 可管理的分区</legend>
                  <label v-for="section in sections" :key="String(section.id)" class="governance-check">
                    <input type="checkbox" :checked="hasSection(ensureUserDraft(user).sectionIds, section.id)" :disabled="pendingKey !== null" @change="toggleUserSection(user, section.id, $event)">
                    <span>{{ section.name }}</span>
                  </label>
                  <p v-if="sections.length === 0" class="text-sm text-[var(--ui-muted)]">暂无可管理分区。</p>
                </fieldset>
                <label :for="`moderator-reason-${user.id}`" class="mt-3">变更理由</label>
                <textarea :id="`moderator-reason-${user.id}`" v-model="ensureUserDraft(user).moderatorReason" class="ui-field min-h-20 resize-y" maxlength="500" placeholder="1–500 个字符"></textarea>
                <button type="button" class="ui-button mt-3" :disabled="pendingKey !== null" @click="saveModeratorSections(user)">保存分区授权</button>
              </section>

              <section class="governance-control">
                <h4>发帖限制</h4>
                <p class="governance-help">解除时间到期后自动恢复；留空表示持续限制，直到手动解除。</p>
                <label class="governance-check mt-3">
                  <input v-model="ensureUserDraft(user).restricted" type="checkbox" :disabled="pendingKey !== null">
                  <span>限制发帖</span>
                </label>
                <label v-if="ensureUserDraft(user).restricted" :for="`restriction-until-${user.id}`" class="mt-3">解除时间</label>
                <input v-if="ensureUserDraft(user).restricted" :id="`restriction-until-${user.id}`" v-model="ensureUserDraft(user).until" class="ui-field" type="datetime-local" :disabled="pendingKey !== null">
                <label :for="`restriction-reason-${user.id}`" class="mt-3">变更理由</label>
                <textarea :id="`restriction-reason-${user.id}`" v-model="ensureUserDraft(user).restrictionReason" class="ui-field min-h-20 resize-y" maxlength="500" placeholder="1–500 个字符"></textarea>
                <button type="button" class="ui-button mt-3" :disabled="pendingKey !== null" @click="savePostingRestriction(user)">保存发帖限制</button>
              </section>
            </div>
            </details>
          </li>
        </ul>

        <nav v-if="!usersLoading && !usersError && userTotalPages > 1" class="flex flex-col gap-3 border-t border-[var(--ui-border)] px-4 py-4 text-sm sm:flex-row sm:items-center sm:justify-between" aria-label="成员列表分页">
          <span class="text-[var(--ui-muted)]">共 {{ userTotal }} 位成员</span>
          <div class="flex flex-wrap items-center gap-2">
            <button type="button" class="ui-button" :disabled="userPage <= 1 || pendingKey !== null" @click="goToUserPage(userPage - 1)">上一页</button>
            <span class="min-w-20 text-center text-[var(--ui-muted)]">第 {{ userPage }} / {{ userTotalPages }} 页</span>
            <button type="button" class="ui-button" :disabled="userPage >= userTotalPages || pendingKey !== null" @click="goToUserPage(userPage + 1)">下一页</button>
          </div>
        </nav>
      </section>

      <section class="ui-panel mb-6 overflow-hidden">
        <div class="ui-toolbar">
          <div>
            <h2 class="font-semibold text-[var(--ui-ink)]">社区分区</h2>
            <p class="mt-1 text-sm text-[var(--ui-muted)]">调整分区显示、规则和归档状态。</p>
          </div>
          <button type="button" class="ui-button" :disabled="pendingKey !== null" @click="startNewSection">新建分区</button>
        </div>
        <form class="governance-section-form border-b border-[var(--ui-border)] p-4 sm:p-5" @submit.prevent="saveSection">
          <div class="grid gap-4 sm:grid-cols-2">
            <div>
              <label for="section-name">名称</label>
              <input id="section-name" v-model="sectionForm.name" class="ui-field" maxlength="128" required>
            </div>
            <div>
              <label for="section-slug">Slug</label>
              <input id="section-slug" v-model="sectionForm.slug" class="ui-field" maxlength="128" required>
            </div>
          </div>
          <label for="section-description" class="mt-4">简介</label>
          <textarea id="section-description" v-model="sectionForm.description" class="ui-field min-h-20 resize-y" maxlength="10000"></textarea>
          <label for="section-rules" class="mt-4">分区规则</label>
          <textarea id="section-rules" v-model="sectionForm.rules" class="ui-field min-h-24 resize-y" maxlength="10000"></textarea>
          <div class="mt-4 flex flex-wrap gap-4">
            <label class="governance-check"><input v-model="sectionForm.visible" type="checkbox"> <span>在导航显示</span></label>
            <label class="governance-check"><input v-model="sectionForm.archived" type="checkbox"> <span>已归档</span></label>
          </div>
          <label for="section-reason" class="mt-4">变更理由（必填）</label>
          <textarea id="section-reason" v-model="sectionForm.reason" class="ui-field min-h-20 resize-y" maxlength="500" required placeholder="1–500 个字符"></textarea>
          <p v-if="sectionFormError" class="mt-2 text-sm text-red-700 dark:text-red-300" role="alert">{{ sectionFormError }}</p>
          <div class="mt-4 flex flex-wrap gap-2">
            <button type="submit" class="ui-button-primary" :disabled="pendingKey !== null">{{ sectionForm.id === null ? '创建分区' : '保存分区' }}</button>
            <button v-if="sectionForm.id !== null" type="button" class="ui-button" :disabled="pendingKey !== null" @click="startNewSection">取消编辑</button>
          </div>
        </form>
        <div v-if="sectionsLoading" class="ui-empty" role="status" aria-live="polite">正在加载分区...</div>
        <div v-else-if="sectionsError" class="ui-error m-4" role="alert">{{ sectionsError }}<button type="button" class="ui-button mt-3" @click="loadSections()">重试</button></div>
        <ul v-else-if="sections.length" class="divide-y divide-[var(--ui-border)]">
          <li v-for="section in sections" :key="String(section.id)" class="flex flex-col gap-3 px-4 py-4 sm:flex-row sm:items-start sm:justify-between sm:px-5">
            <div class="min-w-0">
              <h3 class="font-semibold text-[var(--ui-ink)]">{{ section.name }} <span class="text-xs font-normal text-[var(--ui-muted)]">/{{ section.slug }}</span></h3>
              <p class="mt-1 text-sm text-[var(--ui-muted)]">{{ section.description || '暂无简介' }}</p>
              <div class="mt-2 flex flex-wrap gap-2 text-xs"><span class="ui-tag">{{ section.visible ? '导航显示' : '不在导航显示' }}</span><span class="ui-tag">{{ section.archived ? '已归档' : '正常' }}</span></div>
            </div>
            <button type="button" class="ui-button shrink-0" :disabled="pendingKey !== null" @click="editSection(section)">编辑</button>
          </li>
        </ul>
        <div v-else class="ui-empty" role="status">暂无分区。</div>
      </section>

      <section class="ui-panel overflow-hidden">
        <div class="ui-toolbar">
          <div>
            <h2 class="font-semibold text-[var(--ui-ink)]">变更记录</h2>
            <p class="mt-1 text-sm text-[var(--ui-muted)]">记录管理员对成员和分区权限的操作。</p>
          </div>
        </div>
        <div v-if="auditLoading" class="ui-empty" role="status" aria-live="polite">正在加载记录...</div>
        <div v-else-if="auditError" class="ui-error m-4" role="alert">{{ auditError }}<button type="button" class="ui-button mt-3" @click="loadAudit()">重试</button></div>
        <div v-else-if="audit.length === 0" class="ui-empty" role="status">暂无变更记录。</div>
        <ul v-else>
          <li v-for="entry in audit" :key="String(entry.id)" class="ui-list-row">
            <div class="flex flex-col gap-2 sm:flex-row sm:items-start sm:justify-between">
              <div class="min-w-0">
                <p class="font-medium text-[var(--ui-ink)]">{{ auditActionLabel(entry.action) }} · {{ entry.targetType }} #{{ entry.targetId }}</p>
                <p class="mt-1 break-words text-sm text-[var(--ui-muted)]">{{ entry.reason }}</p>
              </div>
              <div class="shrink-0 text-xs text-[var(--ui-muted)]"><span>操作者 #{{ entry.actorId }}</span><time class="ml-2" :datetime="entry.occurredAt">{{ formatDate(entry.occurredAt) }}</time></div>
            </div>
          </li>
        </ul>
        <nav v-if="!auditLoading && !auditError && auditTotalPages > 1" class="flex flex-col gap-3 border-t border-[var(--ui-border)] px-4 py-4 text-sm sm:flex-row sm:items-center sm:justify-between" aria-label="变更记录分页">
          <span class="text-[var(--ui-muted)]">共 {{ auditTotal }} 条记录</span>
          <div class="flex flex-wrap items-center gap-2"><button type="button" class="ui-button" :disabled="auditPage <= 1 || pendingKey !== null" @click="goToAuditPage(auditPage - 1)">上一页</button><span class="min-w-20 text-center text-[var(--ui-muted)]">第 {{ auditPage }} / {{ auditTotalPages }} 页</span><button type="button" class="ui-button" :disabled="auditPage >= auditTotalPages || pendingKey !== null" @click="goToAuditPage(auditPage + 1)">下一页</button></div>
        </nav>
      </section>
    </template>
  </section>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import {
  createGovernanceSection,
  getGovernanceMe,
  listGovernanceAudit,
  listGovernanceSections,
  listGovernanceUsers,
  requireGovernanceReason,
  updateGovernanceModeratorSections,
  updateGovernancePostingRestriction,
  updateGovernanceSection,
  updateGovernanceUserAdmin,
  type GovernanceAuditEntry,
  type GovernanceMe,
  type GovernanceSection,
  type GovernanceSectionPayload,
  type GovernanceUser,
} from '@/api/governance'
import { useAuthStore } from '@/stores/auth'
import { getStoredAccessToken } from '@/utils/authStorage'
import type { Id } from '@/types'

type ViewState = 'checking' | 'allowed' | 'denied' | 'error'
interface SessionSnapshot { token: string; generation: number }
interface UserDraft { adminReason: string; sectionIds: Id[]; moderatorReason: string; restricted: boolean; until: string; restrictionReason: string }
interface SectionForm extends GovernanceSectionPayload { id: Id | null }

const PAGE_SIZE = 20
const viewState = ref<ViewState>('checking')
const accessError = ref('暂时无法确认社区管理权限，请稍后重试。')
const me = ref<GovernanceMe | null>(null)
const users = ref<GovernanceUser[]>([])
const sections = ref<GovernanceSection[]>([])
const audit = ref<GovernanceAuditEntry[]>([])
const drafts = reactive<Record<string, UserDraft>>({})
const searchQuery = ref('')
const userPage = ref(1)
const userTotal = ref(0)
const usersLoading = ref(false)
const usersError = ref('')
const sectionsLoading = ref(false)
const sectionsError = ref('')
const auditPage = ref(1)
const auditTotal = ref(0)
const auditLoading = ref(false)
const auditError = ref('')
const feedback = ref('')
const operationError = ref('')
const pendingKey = ref<string | null>(null)
const sectionFormError = ref('')
const sectionForm = reactive<SectionForm>({ id: null, name: '', slug: '', description: '', rules: '', visible: true, archived: false, reason: '' })

const userTotalPages = computed(() => Math.max(1, Math.ceil(userTotal.value / PAGE_SIZE)))
const auditTotalPages = computed(() => Math.max(1, Math.ceil(auditTotal.value / PAGE_SIZE)))

let lifecycle = 0
let usersSeq = 0
let sectionsSeq = 0
let auditSeq = 0
let activeSession: SessionSnapshot = { token: '', generation: 0 }

function authStoreOrNull() { try { return useAuthStore() } catch (_) { return null } }
function captureSession(): SessionSnapshot { const store = authStoreOrNull(); return { token: getStoredAccessToken(), generation: store?.getAuthGeneration() ?? 0 } }
function sessionCurrent(snapshot: SessionSnapshot, run: number, seq: number, currentSeq: number): boolean {
  if (run !== lifecycle || seq !== currentSeq || getStoredAccessToken() !== snapshot.token) return false
  const store = authStoreOrNull()
  return !store || store.isAuthGenerationCurrent(snapshot.generation)
}
function errorStatus(error: unknown): number | undefined { const raw = (error as { response?: { status?: unknown } } | null)?.response?.status; const status = Number(raw); return Number.isFinite(status) ? status : undefined }
function userKey(id: Id): string { return String(id) }
function hasSection(ids: Id[], sectionId: Id): boolean { return ids.some((id) => String(id) === String(sectionId)) }
function formatDate(value: string | null | undefined): string { if (!value) return '时间未知'; const date = new Date(value); if (Number.isNaN(date.getTime())) return value; return new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(date) }
function auditActionLabel(action: string): string { const labels: Record<string, string> = { ADMIN_ROLE_CHANGED: '管理员权限变更', MODERATOR_SECTIONS_CHANGED: '版主分区变更', POSTING_RESTRICTION_CHANGED: '发帖限制变更', SECTION_CREATED: '创建分区', SECTION_UPDATED: '编辑分区' }; return labels[action] || action }
function untilInputValue(value: string | null): string { if (!value) return ''; const date = new Date(value); if (Number.isNaN(date.getTime())) return ''; const pad = (n: number) => String(n).padStart(2, '0'); return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}` }
function untilIso(value: string): string | null { if (!value) return null; const date = new Date(value); if (Number.isNaN(date.getTime())) throw new Error('请选择有效的解除时间。'); return date.toISOString() }

function ensureUserDraft(user: GovernanceUser): UserDraft {
  const key = userKey(user.id)
  if (!drafts[key]) drafts[key] = { adminReason: '', sectionIds: [...user.moderatedSectionIds], moderatorReason: '', restricted: user.postingRestricted, until: untilInputValue(user.restrictedUntil), restrictionReason: '' }
  return drafts[key]
}
function syncUser(user: GovernanceUser): void {
  const index = users.value.findIndex((item) => String(item.id) === String(user.id))
  if (index >= 0) users.value[index] = user
  const draft = ensureUserDraft(user)
  draft.sectionIds = [...user.moderatedSectionIds]
  draft.restricted = user.postingRestricted
  draft.until = untilInputValue(user.restrictedUntil)
}
function toggleUserSection(user: GovernanceUser, sectionId: Id, event: Event): void {
  const draft = ensureUserDraft(user)
  const checked = (event.target as HTMLInputElement).checked
  draft.sectionIds = checked ? [...draft.sectionIds.filter((id) => String(id) !== String(sectionId)), sectionId] : draft.sectionIds.filter((id) => String(id) !== String(sectionId))
}

async function loadUsers(run = lifecycle, snapshot = activeSession): Promise<boolean> {
  const seq = ++usersSeq
  usersLoading.value = true
  usersError.value = ''
  try {
    const result = await listGovernanceUsers({ q: searchQuery.value, page: userPage.value, size: PAGE_SIZE })
    if (!sessionCurrent(snapshot, run, seq, usersSeq)) return false
    users.value = result.items
    userPage.value = result.page
    userTotal.value = result.total
    result.items.forEach(ensureUserDraft)
    return true
  } catch (error) {
    if (sessionCurrent(snapshot, run, seq, usersSeq)) { users.value = []; userTotal.value = 0; usersError.value = errorStatus(error) === 403 ? '管理员权限已变化，请重新验证。' : '成员列表加载失败，请重试。' }
    return false
  } finally { if (sessionCurrent(snapshot, run, seq, usersSeq)) usersLoading.value = false }
}

async function loadSections(run = lifecycle, snapshot = activeSession): Promise<boolean> {
  const seq = ++sectionsSeq
  sectionsLoading.value = true
  sectionsError.value = ''
  try {
    const result = await listGovernanceSections()
    if (!sessionCurrent(snapshot, run, seq, sectionsSeq)) return false
    sections.value = result
    return true
  } catch (_) {
    if (sessionCurrent(snapshot, run, seq, sectionsSeq)) { sections.value = []; sectionsError.value = '分区加载失败，请重试。' }
    return false
  } finally { if (sessionCurrent(snapshot, run, seq, sectionsSeq)) sectionsLoading.value = false }
}

async function loadAudit(run = lifecycle, snapshot = activeSession): Promise<boolean> {
  const seq = ++auditSeq
  auditLoading.value = true
  auditError.value = ''
  try {
    const result = await listGovernanceAudit({ page: auditPage.value, size: PAGE_SIZE })
    if (!sessionCurrent(snapshot, run, seq, auditSeq)) return false
    audit.value = result.items
    auditPage.value = result.page
    auditTotal.value = result.total
    return true
  } catch (_) {
    if (sessionCurrent(snapshot, run, seq, auditSeq)) { audit.value = []; auditTotal.value = 0; auditError.value = '变更记录加载失败，请重试。' }
    return false
  } finally { if (sessionCurrent(snapshot, run, seq, auditSeq)) auditLoading.value = false }
}

async function verifyAccess(): Promise<void> {
  const run = ++lifecycle
  usersSeq += 1; sectionsSeq += 1; auditSeq += 1
  const snapshot = captureSession()
  activeSession = snapshot
  viewState.value = 'checking'; me.value = null; feedback.value = ''; operationError.value = ''; usersError.value = ''; sectionsError.value = ''; auditError.value = ''
  try {
    const access = await getGovernanceMe()
    if (!sessionCurrent(snapshot, run, 0, 0)) return
    me.value = access
    if (!access.admin) { viewState.value = 'denied'; return }
    viewState.value = 'allowed'
    await Promise.all([loadUsers(run, snapshot), loadSections(run, snapshot), loadAudit(run, snapshot)])
  } catch (error) {
    if (!sessionCurrent(snapshot, run, 0, 0)) return
    viewState.value = errorStatus(error) === 403 ? 'denied' : 'error'
    accessError.value = errorStatus(error) === 403 ? '当前账号不是管理员，无法进入社区管理。' : '社区管理权限验证失败，请重试。'
  }
}

function refreshAll(): void { feedback.value = ''; operationError.value = ''; void Promise.all([loadUsers(), loadSections(), loadAudit()]) }
function submitUserSearch(): void { if (pendingKey.value !== null) return; userPage.value = 1; void loadUsers() }
function goToUserPage(page: number): void { if (pendingKey.value !== null) return; userPage.value = Math.min(Math.max(1, page), userTotalPages.value); void loadUsers() }
function goToAuditPage(page: number): void { if (pendingKey.value !== null) return; auditPage.value = Math.min(Math.max(1, page), auditTotalPages.value); void loadAudit() }

async function runOperation<T>(key: string, operation: () => Promise<T>, success: (result: T) => void, successMessage: string): Promise<void> {
  if (pendingKey.value !== null) return
  const run = lifecycle
  const snapshot = captureSession()
  pendingKey.value = key; operationError.value = ''; feedback.value = ''
  const current = () => key === pendingKey.value && sessionCurrent(snapshot, run, 0, 0)
  try { const result = await operation(); if (!current()) return; success(result); feedback.value = successMessage; void loadAudit(run, snapshot) }
  catch (_) { if (current()) operationError.value = '操作失败，请检查理由和权限后重试。' }
  finally { if (current()) pendingKey.value = null }
}

async function saveAdminRole(user: GovernanceUser): Promise<void> {
  const draft = ensureUserDraft(user)
  await runOperation(`admin:${user.id}`, () => updateGovernanceUserAdmin(user.id, { enabled: !user.admin, reason: draft.adminReason }), (result) => syncUser(result), `${user.admin ? '撤销' : '授予'}管理员成功。`)
}
async function saveModeratorSections(user: GovernanceUser): Promise<void> {
  const draft = ensureUserDraft(user)
  await runOperation(`moderator:${user.id}`, () => updateGovernanceModeratorSections(user.id, { sectionIds: draft.sectionIds, reason: draft.moderatorReason }), (result) => syncUser(result), '版主分区授权已保存。')
}
async function savePostingRestriction(user: GovernanceUser): Promise<void> {
  const draft = ensureUserDraft(user)
  try {
    const until = draft.restricted ? untilIso(draft.until) : null
    await runOperation(`restriction:${user.id}`, () => updateGovernancePostingRestriction(user.id, { restricted: draft.restricted, until, reason: draft.restrictionReason }), (result) => syncUser(result), '发帖限制已保存。')
  } catch (error) { operationError.value = error instanceof Error ? error.message : '请选择有效的解除时间。' }
}

function startNewSection(): void { sectionForm.id = null; sectionForm.name = ''; sectionForm.slug = ''; sectionForm.description = ''; sectionForm.rules = ''; sectionForm.visible = true; sectionForm.archived = false; sectionForm.reason = ''; sectionFormError.value = '' }
function editSection(section: GovernanceSection): void { sectionForm.id = section.id; sectionForm.name = section.name; sectionForm.slug = section.slug; sectionForm.description = section.description || ''; sectionForm.rules = section.rules || ''; sectionForm.visible = section.visible; sectionForm.archived = section.archived; sectionForm.reason = ''; sectionFormError.value = '' }
async function saveSection(): Promise<void> {
  try { requireGovernanceReason(sectionForm.reason) } catch (error) { sectionFormError.value = error instanceof Error ? error.message : '请填写变更理由。'; return }
  const payload: GovernanceSectionPayload = { name: sectionForm.name.trim(), slug: sectionForm.slug.trim(), description: sectionForm.description, rules: sectionForm.rules, visible: sectionForm.visible, archived: sectionForm.archived, reason: sectionForm.reason }
  if (!payload.name || !payload.slug) { sectionFormError.value = '名称和 Slug 不能为空。'; return }
  const editingId = sectionForm.id
  await runOperation(editingId === null ? 'section:create' : `section:${editingId}`, () => editingId === null ? createGovernanceSection(payload) : updateGovernanceSection(editingId, payload), (result) => { const index = sections.value.findIndex((item) => String(item.id) === String(result.id)); if (index >= 0) sections.value[index] = result; else sections.value = [...sections.value, result]; startNewSection() }, editingId === null ? '分区已创建。' : '分区已保存。')
}

function onAuthStateChanged(event: Event): void {
  lifecycle += 1; usersSeq += 1; sectionsSeq += 1; auditSeq += 1; pendingKey.value = null; users.value = []; sections.value = []; audit.value = []; me.value = null
  Object.keys(drafts).forEach((key) => delete drafts[key])
  startNewSection()
  const loggedIn = (event as CustomEvent<{ loggedIn?: boolean }>).detail?.loggedIn
  if (loggedIn === false) { viewState.value = 'denied'; accessError.value = '请先登录后访问社区管理。'; return }
  void verifyAccess()
}

onMounted(() => { window.addEventListener('auth-state-changed', onAuthStateChanged); void verifyAccess() })
onBeforeUnmount(() => { lifecycle += 1; usersSeq += 1; sectionsSeq += 1; auditSeq += 1; pendingKey.value = null; window.removeEventListener('auth-state-changed', onAuthStateChanged) })
</script>

<style scoped>
.governance-user-grid { display: grid; grid-template-columns: 1fr; gap: 1rem; }
.governance-member summary { min-height: 48px; list-style: none; cursor: pointer; }
.governance-member summary::-webkit-details-marker { display: none; }
.governance-member summary:focus-visible { outline: 2px solid var(--ui-accent); outline-offset: 4px; border-radius: 4px; }
.governance-control { min-width: 0; padding: 1rem; border: 1px solid var(--ui-border); border-radius: 10px; background: color-mix(in srgb, var(--ui-subtle) 45%, var(--ui-surface)); }
.governance-control h4 { color: var(--ui-ink); font-size: .95rem; font-weight: 650; }
.governance-control label, .governance-section-form label { display: block; margin-bottom: .4rem; color: var(--ui-ink); font-size: .875rem; font-weight: 600; }
.governance-control .governance-check, .governance-section-form .governance-check { display: flex; align-items: center; gap: .5rem; min-height: 44px; margin: 0; font-weight: 500; cursor: pointer; }
.governance-help { margin-top: .35rem; color: var(--ui-muted); font-size: .8rem; line-height: 1.5; }
.governance-check input { width: 1.1rem; height: 1.1rem; accent-color: var(--ui-accent); }
@media (min-width: 900px) { .governance-user-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
</style>
