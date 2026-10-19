export type NotificationSignal = 'session' | 'changed' | 'resync'
type Listener = (signal: NotificationSignal) => void
const listeners = new Set<Listener>()

export function subscribeNotificationChanges(listener: Listener): () => void {
  listeners.add(listener)
  return () => { listeners.delete(listener) }
}

export function signalNotificationChanges(signal: NotificationSignal): void {
  for (const listener of listeners) listener(signal)
}
