import { createRouter, createWebHistory } from 'vue-router'
import SectionsList from '@/pages/SectionsList.vue'
import ThreadCreate from '@/pages/ThreadCreate.vue'
import Discover from '@/pages/Discover.vue'
import ThreadDetail from '@/pages/ThreadDetail.vue'
import UsersSearch from '@/pages/UsersSearch.vue'
import UserProfile from '@/pages/UserProfile.vue'
import Settings from '@/pages/Settings.vue'
import MyThreads from '@/pages/MyThreads.vue'
import MyPosts from '@/pages/MyPosts.vue'

const routes = [
  { path: '/', redirect: '/sections' },
  { path: '/sections', name: 'sections', component: SectionsList },
  { path: '/discover', name: 'discover', component: Discover },
  { path: '/users', name: 'users', component: UsersSearch },
  { path: '/users/:id', name: 'user-profile', component: UserProfile },
  { path: '/threads/new', name: 'thread-create', component: ThreadCreate },
  { path: '/threads/:id', name: 'thread-detail', component: ThreadDetail },
  { path: '/settings', name: 'settings', component: Settings },
  { path: '/my/threads', name: 'my-threads', component: MyThreads },
  { path: '/my/posts', name: 'my-posts', component: MyPosts },
]

const router = createRouter({
  history: createWebHistory(),
  routes,
})

export default router