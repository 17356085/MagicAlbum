/// <reference types="vite/client" />

declare module '*.vue' {
  import type { DefineComponent } from 'vue'

  const component: DefineComponent<Record<string, never>, Record<string, never>, unknown>
  export default component
}

interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string
  readonly VITE_USE_API_MOCK?: string
  readonly VITE_AUTH_VERIFY_MODE?: string
  readonly VITE_TURNSTILE_SITE_KEY?: string
  readonly VITE_AUTH_OAUTH_GITHUB_ENABLED?: string
  readonly VITE_AUTH_OAUTH_GOOGLE_ENABLED?: string
  readonly VITE_AUTH_OAUTH_APPLE_ENABLED?: string
  readonly VITE_AUTH_OAUTH_WECHAT_ENABLED?: string
  readonly VITE_AUTH_OAUTH_GITHUB_AUTHORIZE_URL?: string
  readonly VITE_AUTH_OAUTH_GOOGLE_AUTHORIZE_URL?: string
  readonly VITE_AUTH_OAUTH_APPLE_AUTHORIZE_URL?: string
  readonly VITE_AUTH_OAUTH_WECHAT_AUTHORIZE_URL?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
