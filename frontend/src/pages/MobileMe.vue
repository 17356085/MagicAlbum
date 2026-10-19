<script setup lang="ts">
import { computed, ref } from 'vue'
import { storeToRefs } from 'pinia'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { useHeaderTheme } from '@/components/header/useHeaderTheme'
import { safeBack as navigateBackSafely } from '@/utils/router'
import { setPendingAuthRedirect } from '@/utils/authStorage'

const router = useRouter()
const authStore = useAuthStore()
const { isLoggedIn, user } = storeToRefs(authStore)
const { isDark, themeLabel, toggleTheme } = useHeaderTheme()
const logoutDialog = ref<HTMLDialogElement | null>(null)

const userId = computed(() => user.value?.id ?? user.value?.userId ?? null)
const displayName = computed(() => user.value?.nickname || user.value?.username || '我的账户')
const profileLink = computed(() => (
  userId.value == null
    ? { name: 'users' }
    : { name: 'user-profile', params: { id: userId.value } }
))

function safeBack(): void {
  navigateBackSafely(router)
}

function openLogin(): void {
  window.dispatchEvent(new CustomEvent('open-login-modal', { detail: { source: 'mobile-me' } }))
}

function openAiChat(): void {
  window.dispatchEvent(new CustomEvent('open-ai-chat'))
}

function openHistory(): void {
  if (isLoggedIn.value) {
    void router.push('/history')
  } else {
    setPendingAuthRedirect('/history')
    openLogin()
  }
}

function openLogoutDialog(): void {
  const dialog = logoutDialog.value
  if (!dialog || dialog.open) return
  if (typeof dialog.showModal === 'function') {
    dialog.showModal()
  } else {
    // jsdom and older embedded browsers may not expose dialog methods.
    dialog.setAttribute('open', '')
  }
}

function closeLogoutDialog(): void {
  logoutDialog.value?.close()
}

function confirmLogout(): void {
  authStore.logout()
  closeLogoutDialog()
}
</script>

<template>
  <section class="mobile-me ui-page">
    <header class="mobile-me__header ui-page-header">
      <div class="min-w-0">
        <h1 class="ui-title">我的</h1>
        <p class="ui-description">你的内容与日常足迹。</p>
      </div>
      <button type="button" class="mobile-me__back ui-button" aria-label="返回上一页" title="返回上一页" @click="safeBack">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
          <path d="m15 5-7 7 7 7" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
        <span>返回</span>
      </button>
    </header>

    <section class="mobile-me__panel ui-panel">
      <router-link v-if="isLoggedIn" :to="profileLink" class="mobile-me__identity">
        <span class="mobile-me__avatar" aria-hidden="true">{{ displayName.slice(0, 1).toUpperCase() }}</span>
        <span class="min-w-0">
          <strong class="block truncate text-base">{{ displayName }}</strong>
          <span class="block truncate text-sm text-[var(--ui-muted)]">查看个人主页</span>
        </span>
        <svg class="mobile-me__chevron" viewBox="0 0 24 24" fill="none" stroke="currentColor" aria-hidden="true">
          <path d="m9 5 7 7-7 7" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round" />
        </svg>
      </router-link>

      <div v-else class="mobile-me__guest">
        <div>
          <h2 class="text-base font-semibold">登录后管理你的账户</h2>
          <p class="mt-1 text-sm text-[var(--ui-muted)]">登录即可查看个人资料、帖子和收藏。</p>
        </div>
        <button type="button" class="mobile-me__primary ui-button-primary" @click="openLogin">登录</button>
      </div>

      <div v-if="isLoggedIn" class="mobile-me__links" aria-label="我的内容">
        <router-link to="/my/threads" class="mobile-me__link">我的帖子<span aria-hidden="true">›</span></router-link>
        <router-link to="/my/posts" class="mobile-me__link">我的评论<span aria-hidden="true">›</span></router-link>
        <router-link to="/my/bookmarks" class="mobile-me__link">我的收藏<span aria-hidden="true">›</span></router-link>
      </div>

      <div class="mobile-me__links" aria-label="常用入口">
        <button type="button" class="mobile-me__link" @click="openHistory">浏览历史<span aria-hidden="true">›</span></button>
        <router-link v-if="isLoggedIn" :to="{ name: 'settings' }" class="mobile-me__link">设置<span aria-hidden="true">›</span></router-link>
        <button type="button" class="mobile-me__link" @click="toggleTheme">
          <span>{{ themeLabel }}</span>
          <span aria-hidden="true">{{ isDark ? '🌙' : '☀️' }}</span>
        </button>
        <button type="button" class="mobile-me__link" @click="openAiChat">AI 助手<span aria-hidden="true">›</span></button>
      </div>

      <button v-if="isLoggedIn" type="button" class="mobile-me__logout" @click="openLogoutDialog">退出登录</button>
    </section>

    <dialog ref="logoutDialog" class="mobile-me__dialog" aria-labelledby="mobile-me-logout-title">
      <form method="dialog" class="mobile-me__dialog-content">
        <h2 id="mobile-me-logout-title" class="text-lg font-semibold">退出登录？</h2>
        <p class="mt-2 text-sm text-[var(--ui-muted)]">退出后仍可浏览公开内容，登录后可继续管理账户。</p>
        <div class="mobile-me__dialog-actions">
          <button type="submit" class="mobile-me__dialog-button">取消</button>
          <button type="button" class="mobile-me__dialog-button mobile-me__dialog-button--primary" @click="confirmLogout">确认退出</button>
        </div>
      </form>
    </dialog>
  </section>
</template>

<style scoped>
.mobile-me {
  --mobile-me-gap: 12px;
}

.mobile-me__header {
  align-items: center;
}

.mobile-me__back {
  min-height: 48px;
}

.mobile-me__back svg {
  width: 20px;
  height: 20px;
}

.mobile-me__panel {
  overflow: hidden;
}

.mobile-me__identity,
.mobile-me__guest,
.mobile-me__link,
.mobile-me__logout {
  display: flex;
  min-height: 48px;
  align-items: center;
  gap: var(--mobile-me-gap);
  padding: 12px 16px;
  color: var(--ui-ink);
  text-decoration: none;
}

.mobile-me__identity,
.mobile-me__guest,
.mobile-me__links + .mobile-me__links {
  border-bottom: 1px solid var(--ui-border);
}

.mobile-me__identity:hover,
.mobile-me__identity:focus-visible,
.mobile-me__link:hover,
.mobile-me__link:focus-visible {
  background: var(--ui-subtle);
}

.mobile-me__avatar {
  display: inline-flex;
  width: 40px;
  height: 40px;
  flex: 0 0 auto;
  align-items: center;
  justify-content: center;
  border-radius: 999px;
  background: var(--ui-accent-soft);
  color: var(--ui-accent);
  font-weight: 700;
}

.mobile-me__chevron,
.mobile-me__link > span:last-child {
  width: 20px;
  height: 20px;
  flex: 0 0 auto;
  margin-left: auto;
  color: var(--ui-muted);
  text-align: center;
}

.mobile-me__guest {
  justify-content: space-between;
  gap: 16px;
}

.mobile-me__primary,
.mobile-me__logout,
.mobile-me__dialog-button {
  min-height: 48px;
  border-radius: 10px;
  font-weight: 600;
}

.mobile-me__primary {
  flex: 0 0 auto;
  padding: 10px 20px;
}

.mobile-me__links {
  border-bottom: 1px solid var(--ui-border);
}

.mobile-me__link {
  width: 100%;
  justify-content: space-between;
  border: 0;
  background: transparent;
  font: inherit;
  text-align: left;
}

.mobile-me__logout {
  width: 100%;
  justify-content: center;
  border: 0;
  background: transparent;
  color: #b42318;
  font: inherit;
}

.mobile-me__logout:hover,
.mobile-me__logout:focus-visible {
  background: #fff1f0;
}

.dark .mobile-me__logout {
  color: #fca5a5;
}

.dark .mobile-me__logout:hover,
.dark .mobile-me__logout:focus-visible {
  background: #3b2030;
}

.mobile-me__dialog {
  width: min(420px, calc(100vw - 32px));
  margin: auto;
  border: 1px solid var(--ui-border);
  border-radius: 16px;
  padding: 0;
  background: var(--ui-surface);
  color: var(--ui-ink);
}

.mobile-me__dialog::backdrop {
  background: rgb(15 23 42 / 0.46);
}

.mobile-me__dialog-content {
  padding: 24px;
}

.mobile-me__dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 24px;
}

.mobile-me__dialog-button {
  padding: 10px 16px;
  border: 1px solid var(--ui-border);
  background: var(--ui-surface);
  color: var(--ui-ink);
}

.mobile-me__dialog-button--primary {
  border-color: transparent;
  background: #1d4ed8;
  color: white;
}

.dark .mobile-me__dialog-button--primary { background: #4f46e5; }

@media (max-width: 640px) {
  .mobile-me__header {
    align-items: flex-start;
  }

  .mobile-me__guest {
    align-items: flex-start;
    flex-direction: column;
  }

  .mobile-me__primary {
    width: 100%;
  }
}
</style>
