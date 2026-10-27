import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import api from '../client'
import { getMyNotificationSettings, updateMyNotificationSettings } from '../settings'
import {
  NotificationSettingsResponseError as CanonicalNotificationSettingsResponseError,
  getMyNotificationSettings as getCanonicalNotificationSettings,
  updateMyNotificationSettings as updateCanonicalNotificationSettings,
} from '../notificationSettings'
import type { NotificationSettings } from '@/types'

const settings: NotificationSettings = {
  inApp: { reply: false, mention: true, like: false, follow: true, system: true },
  email: { enabled: false, frequency: 'instant' },
}
const originalAdapter = api.defaults.adapter

function respond(data: unknown): void {
  api.defaults.adapter = async (config) => ({ data, status: 200, statusText: 'OK', headers: {}, config })
}

describe('notification preference response contract', () => {
  beforeEach(() => { localStorage.setItem('accessToken', 'preference-session') })
  afterEach(() => {
    api.defaults.adapter = originalAdapter
    localStorage.clear()
    vi.restoreAllMocks()
  })

  it.each(['read', 'save'] as const)('accepts the complete backend %s DTO without coercing false values', async (operation) => {
    respond({ ...settings, ignored: 'extra field' })
    const result = operation === 'read'
      ? await getMyNotificationSettings()
      : await updateMyNotificationSettings(settings)
    expect(result).toEqual(settings)
    expect(result).not.toBe(settings)
  })

  const invalidResponses: Array<[string, unknown]> = [
    ['empty response', null],
    ['HTML fallback', '<!doctype html><html>gateway fallback</html>'],
    ['array', []],
    ['missing settings', {}],
    ['missing email', { inApp: settings.inApp }],
    ['missing preference', { ...settings, inApp: { mention: true, like: false, follow: true, system: true } }],
    ['string boolean', { ...settings, inApp: { ...settings.inApp, reply: 'false' } }],
    ['disabled system notifications', { ...settings, inApp: { ...settings.inApp, system: false } }],
    ['enabled unsupported email', { ...settings, email: { enabled: true, frequency: 'instant' } }],
    ['invalid email frequency', { ...settings, email: { enabled: false, frequency: null } }],
  ]

  for (const operation of ['read', 'save'] as const) {
    it.each(invalidResponses)(`${operation} rejects %s without manufacturing defaults`, async (_label, data) => {
      respond(data)
      const request = operation === 'read' ? getMyNotificationSettings() : updateMyNotificationSettings(settings)
      await expect(request).rejects.toMatchObject({ name: 'NotificationSettingsResponseError' })
    })
  }

  it('preserves transport errors unchanged', async () => {
    const failure = new Error('unavailable')
    api.defaults.adapter = async () => { throw failure }
    await expect(getMyNotificationSettings()).rejects.toBe(failure)
    await expect(updateMyNotificationSettings(settings)).rejects.toBe(failure)
  })

  it('keeps the legacy settings exports identical to the canonical module', async () => {
    expect(getMyNotificationSettings).toBe(getCanonicalNotificationSettings)
    expect(updateMyNotificationSettings).toBe(updateCanonicalNotificationSettings)
    const legacyError = (await import('../settings')).NotificationSettingsResponseError
    expect(legacyError).toBe(CanonicalNotificationSettingsResponseError)
  })
})
