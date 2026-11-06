import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as library from '../src/library.ts'
import { runCommentRead } from '../src/commentReadRunner.ts'
import * as thumbnails from '../src/thumbnailMemory.ts'

test.beforeEach(() => thumbnails.invalidateThumbnails())

const flush = async () => { await vue.nextTick(); await new Promise(setImmediate) }
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes }); return { promise, resolve } }
const IMAGE = { status: 'AVAILABLE', dataUrl: 'data:image/jpeg;base64,/9j/2Q==', width: 1, height: 1, quality: 'MINI' }
const SOURCE = '00000000-0000-0000-0000-000000000001'
class ApiError extends Error { constructor(message, status) { super(message); this.status = status } }
function mount(t, name, propsValue, initial = []) {
  const parsed = parse(readFileSync(new URL(`../src/components/${name}.vue`, import.meta.url), 'utf8'), { filename: name + '.vue' })
  assert.deepEqual(parsed.errors, [])
  const compiled = ts.transpileModule(compileScript(parsed.descriptor, { id: name + '-test' }).content, {
    compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText
  const steps = [...initial], calls = [], failures = [], events = [], mounted = [], unmounted = [], observers = []
  class IO {
    constructor(callback, options) { this.callback = callback; this.options = options; observers.push(this) }
    observe(target) { this.target = target }
    disconnect() { this.disconnected = true }
    emit(value = true) { this.callback([{ target: this.target, isIntersecting: value }]) }
  }
  const api = async (path, options = {}) => {
    calls.push({ path, options }); const expected = steps.shift()
    try { assert.ok(expected, `Unexpected API call ${path}`); if (expected.path instanceof RegExp) assert.match(path, expected.path); else assert.equal(path, expected.path) }
    catch (cause) { failures.push(cause); throw cause }
    if (expected.value instanceof Error) throw expected.value
    return expected.value
  }
  const modules = { vue: { ...vue, onMounted: callback => mounted.push(callback), onBeforeUnmount: callback => unmounted.push(callback) }, '../api': { api, ApiError }, '../library': library, '../commentReadRunner': { runCommentRead }, '../thumbnailMemory': thumbnails }
  const module = { exports: {} }
  new Function('require', 'module', 'exports', 'IntersectionObserver', compiled)(name => {
    if (name.endsWith('.vue')) return {}
    assert.ok(Object.hasOwn(modules, name), name); return modules[name]
  }, module, module.exports, IO)
  const scope = vue.effectScope(), props = vue.reactive(propsValue)
  const state = scope.run(() => module.exports.default.setup(props, { expose() {}, emit: (...event) => events.push(event) }))
  let disposed = false
  const destroy = () => { if (disposed) return; disposed = true; unmounted.forEach(callback => callback()); scope.stop() }
  t.after(() => { destroy(); assert.deepEqual(failures, []); assert.equal(steps.length, 0) })
  return { state, props, calls, events, observers, destroy, expect: (...requests) => steps.push(...requests),
    observe: root => { state[name === 'InfiniteLoad' ? 'sentinel' : 'previewRoot'].value = root ?? { closest: () => null }; mounted.forEach(callback => callback()) },
  }
}
const media = index => ({ key: 'm' + index, message: { chatId: '-2001', messageId: String(1000 + index), link: null }, type: 'PHOTO', source: 'COMMENT', fileName: 'synthetic.jpg', size: 100, caption: '' })
const page = (offset = 0, total = 59) => ({ items: Array.from({ length: Math.min(50, total - offset) }, (_, i) => media(offset + i)), offset, limit: 50, total, hasMore: offset + 50 < total, mainMediaCount: 0, commentMediaCount: total, scope: 'INDEXED_ONLY' })
const DETAIL = { post: { key: 'post', accountId: '1', title: 'Synthetic', mainMessages: [], mainMedia: [], warnings: [] }, warnings: [], commentWarnings: [], coverage: 'COMPLETE', mainCoverage: 'COMPLETE', commentCoverage: 'COMPLETE', exhausted: true }
async function detail(t) {
  const f = mount(t, 'LibraryPostDetail', { postKey: 'post', accountId: '1', authorized: true, available: true }, [
    { path: '/library/posts/post?limit=1&offset=0', value: DETAIL }, { path: /\/media\?includeMain=false&limit=50&offset=0$/, value: page() },
  ])
  await flush(); assert.equal(f.state.loadedMedia.value.length, 50); return f
}
test('library scroll appends the next local batch, retains old rows, and finishes from the server result', async t => {
  const f = await detail(t), version = f.state.mediaPageVersion.value
  f.expect({ path: /offset=50$/, value: page(50) }); f.state.loadMore(); await flush()
  assert.equal(f.state.loadedMedia.value.length, 59); assert.equal(f.state.loadedMedia.value[0].key, 'm0')
  assert.equal(f.state.mediaPage.value.hasMore, false); assert.equal(f.state.mediaPageVersion.value, version)
  f.state.loadMore(); await flush(); assert.equal(f.calls.length, 3)
})
test('switching list and cards keeps the same loaded rows and makes no media request', async t => {
  const f = await detail(t); const items = f.state.loadedMedia.value
  f.state.mediaView.value = 'cards'; await flush(); assert.equal(f.state.loadedMedia.value, items); assert.equal(f.calls.length, 2)
  f.state.mediaView.value = 'list'; await flush(); assert.equal(f.calls.length, 2)
})
test('a failed scroll request keeps existing media and cannot retry itself', async t => {
  const f = await detail(t); f.expect({ path: /offset=50$/, value: new Error('Synthetic failure') })
  f.state.loadMore(); await flush(); assert.equal(f.state.loadedMedia.value.length, 50); assert.match(f.state.mediaError.value, /Synthetic failure/)
  f.state.loadMore(); await flush(); assert.equal(f.calls.length, 3)
})
test('changing media filters clears the old feed and invalidates a late append response', async t => {
  const f = await detail(t); const pending = deferred()
  f.expect({ path: /offset=50$/, value: pending.promise }); f.state.loadMore()
  f.expect({ path: /offset=0&types=VIDEO$/, value: { ...page(0, 0), items: [], total: 0, commentMediaCount: 0 } })
  f.state.types.value = ['VIDEO']; await flush(); pending.resolve(page(50)); await flush()
  assert.equal(f.state.loadedMedia.value.length, 0); assert.equal(f.state.mediaPage.value.total, 0)
})
test('an index count change or an overlapping batch pauses instead of claiming all media are shown', async t => {
  const f = await detail(t)
  f.expect({ path: /offset=50$/, value: page(50, 60) }); f.state.loadMore(); await flush()
  assert.match(f.state.mediaError.value, /索引已变化/); assert.equal(f.state.loadedMedia.value.length, 50)
})
test('losing connection while appending discards late data and preserves the saved reading content', async t => {
  const f = await detail(t), pending = deferred()
  f.expect({ path: /offset=50$/, value: pending.promise }); f.state.loadMore(); f.props.available = false; await flush()
  pending.resolve(page(50)); await flush(); assert.equal(f.state.loadedMedia.value.length, 50); assert.equal(f.state.mediaPage.value.offset, 0)
})
test('duplicate scroll events do not run concurrent local media requests', async t => {
  const f = await detail(t), pending = deferred()
  f.expect({ path: /offset=50$/, value: pending.promise }); f.state.loadMore(); f.state.loadMore(); assert.equal(f.calls.length, 3)
  pending.resolve(page(50)); await flush(); assert.equal(f.state.loadedMedia.value.length, 59)
})
test('the infinite sentinel requests once per version only when visible and not busy', async t => {
  const f = mount(t, 'InfiniteLoad', { hasMore: true, busy: false, blocked: false, version: 0, context: 'source' })
  const modal = {}, root = { closest: () => modal }; f.observe(root)
  assert.equal(f.events.length, 0); assert.equal(f.observers[0].options.root, modal)
  f.observers[0].emit(); f.observers[0].emit(); assert.deepEqual(f.events, [['load']])
  f.props.busy = true; f.props.version = 1; await flush(); assert.equal(f.events.length, 1)
  f.props.busy = false; await flush(); assert.equal(f.events.length, 1, 'A new batch must wait for fresh intersection geometry')
  f.observers.at(-1).emit(); assert.equal(f.events.length, 2)
})
test('EOF and blocked state stop the infinite sentinel until explicitly resumed', async t => {
  const f = mount(t, 'InfiniteLoad', { hasMore: true, busy: false, blocked: true, version: 0, context: 'source' }); f.observe()
  f.observers[0].emit(); assert.equal(f.events.length, 0); f.props.blocked = false; await flush(); assert.equal(f.events.length, 1)
  f.props.hasMore = false; f.props.version = 1; await flush(); f.observers[0].emit(); assert.equal(f.events.length, 1)
})
test('stale observer callbacks and callbacks after unmount cannot load another source', async t => {
  const f = mount(t, 'InfiniteLoad', { hasMore: true, busy: false, version: 0, context: 'one' }); f.observe()
  const old = f.observers[0]; f.props.context = 'two'; await flush(); old.emit(); assert.equal(f.events.length, 0)
  f.observers.at(-1).emit(); assert.equal(f.events.length, 1); f.destroy(); f.observers.at(-1).emit(); assert.equal(f.events.length, 1)
})
const thumbnailProps = (extra = {}) => ({ postKey: 'post', mediaKey: 'observed', accountId: '1', version: 1, authorized: true, available: true, icon: 'photo', lazy: true, compact: true, downloadPreviewId: SOURCE, downloadPage: 1, ...extra })
test('download thumbnails load only visible items through their source-and-origin-page endpoint', async t => {
  const f = mount(t, 'LibraryPostPreview', thumbnailProps(), [{ path: new RegExp('/transfers/download/preview/' + SOURCE + '/thumbnail\\?'), value: IMAGE }])
  f.observe(); assert.equal(f.calls.length, 0); f.observers[0].emit(); await flush()
  assert.equal(f.state.imageSource.value, IMAGE.dataUrl)
  const query = new URL(f.calls[0].path, 'http://local').searchParams
  assert.equal(query.get('mediaKey'), 'observed'); assert.equal(query.get('pageNumber'), '1')
  f.observers[0].emit(false); await flush(); assert.equal(f.state.imageSource.value, null, 'Offscreen DOM rows do not retain unbounded image strings')
  f.observers[0].emit(); await flush(); assert.equal(f.state.imageSource.value, IMAGE.dataUrl); assert.equal(f.calls.length, 1, 'Returning to the same visible image does not refetch it')
})
test('existing library thumbnail routing remains separate from download source routing', async t => {
  const f = mount(t, 'LibraryPostPreview', thumbnailProps({ downloadPreviewId: undefined, downloadPage: undefined }), [{ path: /\/library\/posts\/post\/media-preview\?/, value: IMAGE }])
  f.observe(); f.observers[0].emit(); await flush(); assert.equal(f.state.imageSource.value, IMAGE.dataUrl)
})
test('a source or origin-page change rejects a late image from the previous identity', async t => {
  const pending = deferred(); const f = mount(t, 'LibraryPostPreview', thumbnailProps(), [{ path: /pageNumber=1$/, value: pending.promise }])
  f.observe(); f.observers[0].emit(); await flush()
  f.expect({ path: /pageNumber=2$/, value: { ...IMAGE, status: 'UNAVAILABLE', dataUrl: null } }); f.props.downloadPage = 2; await flush()
  pending.resolve(IMAGE); await flush(); assert.equal(f.state.imageSource.value, null)
})
test('a failed thumbnail keeps the row usable and never retries without a new visibility or version', async t => {
  const f = mount(t, 'LibraryPostPreview', thumbnailProps(), [{ path: /\/thumbnail\?/, value: new Error('Synthetic thumbnail error') }])
  f.observe(); f.observers[0].emit(); await flush(); assert.equal(f.state.imageSource.value, null); assert.equal(f.state.loading.value, false)
  f.observers[0].emit(); await flush(); assert.equal(f.calls.length, 1)
})
test('a source main attachment accepts origin page zero but an unspecified source scope cannot request', async t => {
  const f = mount(t, 'LibraryPostPreview', thumbnailProps({ downloadPage: 0 }), [{ path: /pageNumber=0$/, value: IMAGE }])
  f.observe(); f.observers[0].emit(); await flush(); assert.equal(f.state.imageSource.value, IMAGE.dataUrl)
  f.props.downloadPage = undefined; await flush(); assert.equal(f.state.imageSource.value, null); assert.equal(f.calls.length, 1)
})

test('a remounted thumbnail reuses the session cache with no second request', async t => {
  const first = mount(t, 'LibraryPostPreview', thumbnailProps(), [{ path: /\/thumbnail\?/, value: IMAGE }])
  first.observe(); first.observers[0].emit(); await flush(); first.destroy()
  const next = mount(t, 'LibraryPostPreview', thumbnailProps())
  next.observe(); next.observers[0].emit(); await flush()
  assert.equal(next.state.imageSource.value, IMAGE.dataUrl); assert.equal(next.calls.length, 0)
})

test('duplicate visible thumbnails share a request even when its first component unmounts', async t => {
  const pending = deferred(), first = mount(t, 'LibraryPostPreview', thumbnailProps(), [{ path: /\/thumbnail\?/, value: pending.promise }])
  first.observe(); first.observers[0].emit(); await flush()
  const second = mount(t, 'LibraryPostPreview', thumbnailProps())
  second.observe(); second.observers[0].emit(); await flush(); first.destroy()
  pending.resolve(IMAGE); await flush()
  assert.equal(second.state.imageSource.value, IMAGE.dataUrl); assert.equal(second.calls.length, 0)
})

test('a changed media snapshot cannot reuse the same local version after remount', async t => {
  const first = mount(t, 'LibraryPostPreview', thumbnailProps({ cacheRevision: 'old-media' }), [{ path: /\/thumbnail\?/, value: IMAGE }])
  first.observe(); first.observers[0].emit(); await flush(); first.destroy()
  const next = mount(t, 'LibraryPostPreview', thumbnailProps({ cacheRevision: 'changed-media' }), [{ path: /\/thumbnail\?/, value: IMAGE }])
  next.observe(); next.observers[0].emit(); await flush()
  assert.equal(next.calls.length, 1); assert.equal(next.state.imageSource.value, IMAGE.dataUrl)
})

test('clearing the cache drops an active image and reloads the currently visible identity', async t => {
  const f = mount(t, 'LibraryPostPreview', thumbnailProps(), [{ path: /\/thumbnail\?/, value: IMAGE }])
  f.observe(); f.observers[0].emit(); await flush()
  const pending = deferred(); f.expect({ path: /\/thumbnail\?/, value: pending.promise })
  thumbnails.invalidateThumbnails(); await flush()
  assert.equal(f.state.imageSource.value, null); assert.equal(f.calls.length, 2)
  pending.resolve(IMAGE); await flush(); assert.equal(f.state.imageSource.value, IMAGE.dataUrl)
})

test('a decode failure evicts its cache entry and does not loop when revisiting immediately', async t => {
  const f = mount(t, 'LibraryPostPreview', thumbnailProps(), [{ path: /\/thumbnail\?/, value: IMAGE }])
  f.observe(); f.observers[0].emit(); await flush()
  f.state.imageFailed({ currentTarget: { getAttribute: () => IMAGE.dataUrl, dataset: { previewGeneration: String(f.state.imageIdentity.value) } } })
  assert.equal(f.state.imageSource.value, null)
  f.observers[0].emit(false); await flush(); f.observers[0].emit(); await flush()
  assert.equal(f.calls.length, 1); assert.equal(f.state.imageSource.value, null)
})

test('thumbnail cache isolation includes the account and the cover versus media endpoint', async t => {
  const first = mount(t, 'LibraryPostPreview', thumbnailProps(), [{ path: /\/thumbnail\?/, value: IMAGE }])
  first.observe(); first.observers[0].emit(); await flush(); first.destroy()
  const other = mount(t, 'LibraryPostPreview', thumbnailProps({ accountId: '2' }), [{ path: /\/thumbnail\?/, value: IMAGE }])
  other.observe(); other.observers[0].emit(); await flush(); assert.equal(other.calls.length, 1)
  const cover = mount(t, 'LibraryPostPreview', thumbnailProps({ downloadPreviewId: undefined, downloadPage: undefined, mediaKey: undefined }), [{ path: /\/library\/posts\/post\/preview\?/, value: IMAGE }])
  cover.observe(); cover.observers[0].emit(); await flush(); assert.equal(cover.calls.length, 1)
})
