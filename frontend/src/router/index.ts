import { createRouter, createWebHistory } from 'vue-router'
import type { RouteRecordRaw, RouterScrollBehavior } from 'vue-router'
import { getStoredAccessToken, hasRealToken, setPendingAuthRedirect } from '@/utils/authStorage'
import { formatPageTitle } from '@/config/brand'

const routes: RouteRecordRaw[] = [
  { path: '/', redirect: '/discover' },
  { path: '/sections', name: 'sections', component: () => import('@/pages/SectionsList.vue') },
  { path: '/discover', name: 'discover', component: () => import('@/pages/Discover.vue') },
  { path: '/feed', name: 'feed', component: () => import('@/pages/FollowingFeed.vue'), meta: { requiresAuth: true } },
  { path: '/ranking', name: 'ranking', component: () => import('@/pages/Ranking.vue') },
  { path: '/me', name: 'me', component: () => import('@/pages/MobileMe.vue') },
  { path: '/users', name: 'users', component: () => import('@/pages/UsersSearch.vue') },
  { path: '/users/:id/following', name: 'user-following', component: () => import('@/pages/UserRelations.vue'), meta: { publicViewKey: 'user-relations' } },
  { path: '/users/:id/followers', name: 'user-followers', component: () => import('@/pages/UserRelations.vue'), meta: { publicViewKey: 'user-relations' } },
  { path: '/users/:id', name: 'user-profile', component: () => import('@/pages/UserProfile.vue'), meta: { publicViewKey: 'user-profile' } },
  { path: '/auth/oauth/callback', name: 'auth-oauth-callback', component: () => import('@/pages/AuthOAuthCallback.vue') },
  { path: '/auth/qr/confirm', name: 'auth-qr-confirm', component: () => import('@/pages/AuthQrConfirm.vue') },
  { path: '/threads/new', name: 'thread-create', component: () => import('@/pages/ThreadCreate.vue'), meta: { requiresAuth: true } },
  { path: '/threads/:id/edit', name: 'thread-edit', component: () => import('@/pages/ThreadEdit.vue'), meta: { requiresAuth: true } },
  { path: '/threads/:id', name: 'thread-detail', component: () => import('@/pages/ThreadDetail.vue') },
  { path: '/settings', name: 'settings', component: () => import('@/pages/Settings.vue'), meta: { requiresAuth: true } },
  { path: '/settings/account', redirect: { path: '/settings', query: { tab: 'account' } } },
  { path: '/my/threads', name: 'my-threads', component: () => import('@/pages/MyThreads.vue'), meta: { requiresAuth: true } },
  { path: '/my/posts', name: 'my-posts', component: () => import('@/pages/MyPosts.vue'), meta: { requiresAuth: true } },
  { path: '/my/bookmarks', name: 'my-bookmarks', component: () => import('@/pages/MyBookmarks.vue'), meta: { requiresAuth: true } },
  { path: '/admin/reports', name: 'admin-reports', component: () => import('@/pages/AdminReports.vue'), meta: { requiresAuth: true } },
  { path: '/admin/community', name: 'admin-community', component: () => import('@/pages/AdminCommunity.vue'), meta: { requiresAuth: true } },
  { path: '/history', name: 'history', component: () => import('@/pages/RecentHistory.vue') },
]

const scrollBehavior: RouterScrollBehavior = (to, from, savedPosition) => {
  void from
  // 返回/前进时保留历史滚动位置
  if (savedPosition) return savedPosition
  // 哈希锚点定位（如评论 #post-123）
  if (to.hash) return { el: to.hash, behavior: 'smooth' }
  // 普通导航：滚动到页面顶部
  return { top: 0 }
}

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior,
})

router.beforeEach((to, from) => {
  const requiresAuth = to.matched.some((record) => record.meta?.requiresAuth)
  if (!requiresAuth) return true

  if (hasRealToken(getStoredAccessToken())) return true

  setPendingAuthRedirect(to.fullPath)

  if (from.matched.length > 0) {
    try {
      window.dispatchEvent(new CustomEvent('open-login-modal', { detail: { source: String(to.name || to.path || 'auth-guard') } }))
    } catch (_) {}
    return false
  }

  return { name: 'discover', query: { login: '1' } }
})

const pageTitles: Record<string, string> = {
  discover: '发现', sections: '分区', ranking: '排行榜', feed: '关注动态',
  users: '搜索用户', 'user-profile': '个人主页', 'user-following': '关注', 'user-followers': '粉丝',
  'thread-create': '发帖', 'thread-edit': '编辑帖子', 'thread-detail': '帖子',
  settings: '设置', 'my-threads': '我的帖子', 'my-posts': '我的评论', 'my-bookmarks': '我的收藏',
  'admin-reports': '举报审核', 'admin-community': '社区管理', history: '最近浏览', me: '我的',
  'auth-oauth-callback': '授权登录', 'auth-qr-confirm': '确认登录',
}

router.afterEach((to, _from, failure) => {
  if (!failure) document.title = formatPageTitle(pageTitles[String(to.name || '')])
})

export default router
