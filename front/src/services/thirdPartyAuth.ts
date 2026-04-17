import { setPendingAuthRedirect } from '@/utils/authStorage'
import type { OAuthProvider } from '@/types'

export interface OAuthProviderConfig {
  id: OAuthProvider
  label: string
  enabled: boolean
  authorizeUrl: string
  status: 'ready' | 'planned'
}

function isEnabled(value: string | undefined, fallback = false): boolean {
  if (value == null || value === '') return fallback
  return String(value).trim().toLowerCase() === 'true'
}

function normalizeBaseUrl(url: string | undefined): string {
  return String(url || '').replace(/\/+$/, '')
}

function buildDefaultAuthorizeUrl(provider: OAuthProvider): string {
  const apiBase = normalizeBaseUrl(import.meta.env.VITE_API_BASE_URL)
  return apiBase ? `${apiBase}/auth/oauth/authorize?provider=${provider}` : ''
}

function getAuthorizeUrl(provider: OAuthProvider): string {
  if (provider === 'github') {
    return String(import.meta.env.VITE_AUTH_OAUTH_GITHUB_AUTHORIZE_URL || '').trim() || buildDefaultAuthorizeUrl(provider)
  }
  if (provider === 'google') {
    return String(import.meta.env.VITE_AUTH_OAUTH_GOOGLE_AUTHORIZE_URL || '').trim() || buildDefaultAuthorizeUrl(provider)
  }
  if (provider === 'apple') {
    return String(import.meta.env.VITE_AUTH_OAUTH_APPLE_AUTHORIZE_URL || '').trim() || buildDefaultAuthorizeUrl(provider)
  }
  return String(import.meta.env.VITE_AUTH_OAUTH_WECHAT_AUTHORIZE_URL || '').trim() || buildDefaultAuthorizeUrl(provider)
}

export function getOAuthProviderConfigs(): OAuthProviderConfig[] {
  const githubEnabled = isEnabled(import.meta.env.VITE_AUTH_OAUTH_GITHUB_ENABLED, true)
  const googleEnabled = isEnabled(import.meta.env.VITE_AUTH_OAUTH_GOOGLE_ENABLED, false)
  const appleEnabled = isEnabled(import.meta.env.VITE_AUTH_OAUTH_APPLE_ENABLED, false)
  const wechatEnabled = isEnabled(import.meta.env.VITE_AUTH_OAUTH_WECHAT_ENABLED, false)

  return [
    {
      id: 'github',
      label: 'GitHub',
      enabled: githubEnabled,
      authorizeUrl: getAuthorizeUrl('github'),
      status: githubEnabled ? 'ready' : 'planned',
    },
    {
      id: 'google',
      label: 'Google',
      enabled: googleEnabled,
      authorizeUrl: getAuthorizeUrl('google'),
      status: googleEnabled ? 'ready' : 'planned',
    },
    {
      id: 'apple',
      label: 'Apple',
      enabled: appleEnabled,
      authorizeUrl: getAuthorizeUrl('apple'),
      status: appleEnabled ? 'ready' : 'planned',
    },
    {
      id: 'wechat',
      label: '微信',
      enabled: wechatEnabled,
      authorizeUrl: getAuthorizeUrl('wechat'),
      status: wechatEnabled ? 'ready' : 'planned',
    },
  ]
}

export function getOAuthProviderConfig(provider: OAuthProvider): OAuthProviderConfig | undefined {
  return getOAuthProviderConfigs().find((item) => item.id === provider)
}

export function startOAuthAuthorize(provider: OAuthProvider, redirectPath: string): { ok: boolean; message: string } {
  const config = getOAuthProviderConfig(provider)
  if (!config) {
    return { ok: false, message: '未找到对应的第三方登录配置' }
  }

  if (!config.enabled) {
    return { ok: false, message: `${config.label} 登录准备中` }
  }

  if (!config.authorizeUrl) {
    return { ok: false, message: `${config.label} 登录未配置授权地址` }
  }

  setPendingAuthRedirect(redirectPath)
  window.location.assign(config.authorizeUrl)
  return { ok: true, message: `正在跳转到 ${config.label} 授权页` }
}
