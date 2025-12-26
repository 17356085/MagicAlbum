import './assets/main.css'

import { createApp } from 'vue'
import App from './App.vue'
import router from './router'

// Import highlight.js themes as URLs for dynamic switching
import hljsThemeDarkUrl from 'highlight.js/styles/atom-one-dark.css?url'
import hljsThemeLightUrl from 'highlight.js/styles/atom-one-light.css?url'

function initThemeClassFromStorage() {
  const saved = localStorage.getItem('theme')
  const root = document.documentElement
  if (saved === 'dark') {
    root.classList.add('dark')
  } else if (saved === 'light') {
    root.classList.remove('dark')
  } else {
    const systemDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
    if (systemDark) root.classList.add('dark')
    else root.classList.remove('dark')
  }
}

function applyHljsTheme() {
  const prefersDark = window.matchMedia && window.matchMedia('(prefers-color-scheme: dark)').matches
  const hasDarkClass = document.documentElement.classList.contains('dark')
  const isDark = prefersDark || hasDarkClass

  const href = isDark ? hljsThemeDarkUrl : hljsThemeLightUrl
  const id = 'hljs-theme'
  let link = document.getElementById(id)
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

// Initialize theme class from storage and system preference
initThemeClassFromStorage()
// Initial apply and listeners for dark-mode changes
applyHljsTheme()

if (window.matchMedia) {
  const mq = window.matchMedia('(prefers-color-scheme: dark)')
  // Use addEventListener if available; fallback to addListener
  if (typeof mq.addEventListener === 'function') {
    mq.addEventListener('change', applyHljsTheme)
  } else if (typeof mq.addListener === 'function') {
    mq.addListener(applyHljsTheme)
  }
}

// Observe class changes on html element for frameworks toggling `.dark`
const observer = new MutationObserver(applyHljsTheme)
observer.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })

createApp(App).use(router).mount('#app')
