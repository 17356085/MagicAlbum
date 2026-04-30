import type { RecentVisit } from './index'

declare global {
  interface Window {
    __authExpiredAlertShown?: boolean
    __recent_visits_hook_installed?: boolean
    turnstile?: {
      render: (
        container: string | HTMLElement,
        options: {
          sitekey: string
          action?: string
          theme?: 'auto' | 'light' | 'dark'
          size?: 'normal' | 'compact' | 'flexible'
          callback?: (token: string) => void
          'error-callback'?: (errorCode?: string) => void
          'expired-callback'?: () => void
        }
      ) => string
      reset: (widgetId?: string) => void
      remove: (widgetId?: string) => void
      getResponse: (widgetId?: string) => string
    }
    __turnstileOnLoad?: () => void
  }

  interface WindowEventMap {
    'auth-state-changed': CustomEvent<{ loggedIn: boolean }>
    'threads-updated': CustomEvent<{ reason?: 'created' | 'updated' | 'deleted' | string; threadId?: import('./index').Id; sectionId?: import('./index').Id; tags?: string[] }>
    'thread-tags-updated': CustomEvent<{ reason?: 'created' | 'updated' | string; threadId?: import('./index').Id; sectionId?: import('./index').Id; tags?: string[] }>
    'recent-visits-updated': CustomEvent<void>
    'open-ai-chat': CustomEvent<void>
    'open-login-modal': CustomEvent<{ source?: string }>
    'profile-updated': CustomEvent<import('./index').ProfileUpdatedDetail>
  }

  interface RecentVisitsStorageShape extends Array<RecentVisit> {}

  interface AuthStateChangedDetail {
    loggedIn: boolean
  }
}

export {}
