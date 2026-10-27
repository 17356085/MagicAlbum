import assert from 'node:assert/strict'
import { test } from 'node:test'
import { checkDeferredAuthModal, checkInitialLoad, staticChunks } from '../../frontend/scripts/check-initial-load.mjs'

function manifest() {
  return {
    'index.html': { file: 'assets/app.js', imports: ['_vue'], dynamicImports: ['_math'] },
    'src/pages/Discover.vue': { file: 'assets/discover.js', imports: ['_markdown'] },
    'src/pages/SectionsList.vue': { file: 'assets/sections.js', imports: ['_markdown'] },
    'src/pages/ThreadCreate.vue': { file: 'assets/create.js', isDynamicEntry: true, imports: ['_math', '_highlight'] },
    'src/pages/ThreadDetail.vue': { file: 'assets/detail.js', isDynamicEntry: true, imports: ['_math', '_highlight'] },
    'src/components/auth/AuthModal.vue': { file: 'assets/auth-modal.js', isDynamicEntry: true, imports: ['_vue'] },
    _vue: { file: 'assets/vue.js' },
    _markdown: { file: 'assets/markdown.js', imports: ['_vue'] },
    _math: { file: 'assets/math.js', name: 'math' },
    _highlight: { file: 'assets/highlight.js', name: 'highlight' },
  }
}

test('allows optional rendering dependencies behind lazy pages', () => {
  const routes = checkInitialLoad(manifest())
  assert.deepEqual(routes['src/pages/Discover.vue'], ['assets/discover.js', 'assets/markdown.js', 'assets/vue.js'])
})

test('requires the auth modal source to be a dynamic entry outside the initial static files', () => {
  checkDeferredAuthModal(manifest())

  const eager = manifest()
  eager['src/components/auth/AuthModal.vue'].isDynamicEntry = false
  assert.throws(() => checkDeferredAuthModal(eager), /Auth modal must remain dynamically loaded/)

  const reachable = manifest()
  reachable._vue.imports = ['src/components/auth/AuthModal.vue']
  assert.throws(() => checkDeferredAuthModal(reachable), /Auth modal is reachable from the initial static graph/)
})

test('detects static reachability by output file even when the chunk name changes', () => {
  const graph = manifest()
  graph['src/components/auth/AuthModal.vue'].file = 'assets/renamed-auth-feature.js'
  graph._vue.imports = ['src/components/auth/AuthModal.vue']
  assert.throws(() => checkDeferredAuthModal(graph), /renamed-auth-feature\.js/)
})

test('catches a heavy dependency introduced through a shared transitive chunk', () => {
  const graph = manifest()
  graph._vue.imports = ['_highlight']
  assert.throws(() => checkInitialLoad(graph), /Optional rendering dependencies loaded by index.html/)
})

test('catches an eagerly imported detail page even if the chunk names change', () => {
  const graph = manifest()
  graph['src/pages/ThreadDetail.vue'].isDynamicEntry = false
  assert.throws(() => checkInitialLoad(graph), /Page must remain lazy-loaded/)
})

test('rejects broken graphs and terminates on shared-import cycles', () => {
  const graph = manifest()
  graph._vue.imports = ['_markdown']
  assert.equal(staticChunks(graph, 'index.html').length, 3)
  graph._vue.imports = ['_missing']
  assert.throws(() => staticChunks(graph, 'index.html'), /Missing build manifest entry: _missing/)
})
