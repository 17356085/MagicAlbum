import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

/** Keep fixed mobile UI out of text-entry and the browser's visible viewport. */
export function useMobileViewport() {
  const inputActive = ref(false)
  const height = ref('100dvh')
  const bottom = ref('0px')
  let disposed = false

  function updateFocus(): void {
    if (disposed) return
    const element = document.activeElement
    inputActive.value = element instanceof HTMLElement && (
      element.isContentEditable || element instanceof HTMLTextAreaElement ||
      (element instanceof HTMLInputElement && !['button', 'submit', 'checkbox', 'radio', 'file', 'range', 'color'].includes(element.type))
    )
  }

  function onFocusChange(): void {
    // focusout fires before the browser updates activeElement.
    queueMicrotask(updateFocus)
  }

  function updateViewport(): void {
    const viewport = window.visualViewport
    height.value = viewport ? `${viewport.height}px` : '100dvh'
    bottom.value = viewport ? `${Math.max(0, window.innerHeight - viewport.height - viewport.offsetTop)}px` : '0px'
  }

  onMounted(() => {
    updateFocus()
    updateViewport()
    document.addEventListener('focusin', onFocusChange)
    document.addEventListener('focusout', onFocusChange)
    window.addEventListener('resize', updateViewport)
    window.visualViewport?.addEventListener('resize', updateViewport)
    window.visualViewport?.addEventListener('scroll', updateViewport)
  })
  onBeforeUnmount(() => {
    disposed = true
    document.removeEventListener('focusin', onFocusChange)
    document.removeEventListener('focusout', onFocusChange)
    window.removeEventListener('resize', updateViewport)
    window.visualViewport?.removeEventListener('resize', updateViewport)
    window.visualViewport?.removeEventListener('scroll', updateViewport)
  })

  return {
    inputActive,
    viewportStyle: computed(() => ({ '--mobile-viewport-height': height.value, '--mobile-viewport-bottom': bottom.value })),
  }
}
