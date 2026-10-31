import './assets/main.css'

import { createApp } from 'vue'
import { createPinia } from 'pinia'
import App from './App.vue'
import router from './router'
import { initRecentVisitsTracking } from './composables/useRecentVisits'
import { useAuthStore } from './stores/auth'
import { initializeTheme } from './utils/theme'

// Import highlight.js themes as URLs for dynamic switching
import hljsThemeDarkUrl from 'highlight.js/styles/atom-one-dark.css?url'
import hljsThemeLightUrl from 'highlight.js/styles/atom-one-light.css?url'

function applyHljsTheme(): void {
  const hasDarkClass = document.documentElement.classList.contains('dark')
  const isDark = hasDarkClass

  const href = isDark ? hljsThemeDarkUrl : hljsThemeLightUrl
  const id = 'hljs-theme'
  let link = document.getElementById(id) as HTMLLinkElement | null
  if (!link) {
    link = document.createElement('link')
    link.id = id
    link.rel = 'stylesheet'
    document.head.appendChild(link)
  }
  if (link.href !== href) {
    link.href = href
  }
}

initializeTheme()
applyHljsTheme()

const observer = new MutationObserver(applyHljsTheme)
observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
if (import.meta.hot) import.meta.hot.dispose(() => observer.disconnect())

const app = createApp(App)
const pinia = createPinia()
app.use(pinia)
const auth = useAuthStore(pinia)
try {
  initRecentVisitsTracking(router, () => ({
    token: auth.token,
    ownerId: auth.user?.id ?? auth.user?.userId ?? null,
  }))
} catch (_) {}
app.use(router)
app.mount('#app')
