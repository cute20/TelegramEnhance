import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as library from '../src/library.ts'
import * as download from '../src/downloadPreview.ts'
import * as downloadTasks from '../src/downloadTasks.ts'

const parsed = parse(readFileSync(new URL('../src/components/NewTransfer.vue', import.meta.url), 'utf8'), { filename: 'NewTransfer.vue' })
assert.deepEqual(parsed.errors, [])
const compiled = ts.transpileModule(compileScript(parsed.descriptor, { id: 'new-transfer-test' }).content, {
  fileName: 'NewTransfer.ts', compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText
const evaluate = new Function('require', 'module', 'exports', compiled)
const LINK = 'https://t.me/synthetic/1', ID = '00000000-0000-0000-0000-000000000001'
const CURSOR = '9007199254740999', OLDER = '9007199254740998'
const flush = async () => { await vue.nextTick(); await new Promise(setImmediate) }
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes }); return { promise, resolve } }
class ApiError extends Error { constructor(message, status) { super(message); this.status = status } }
function media(type, source = 'COMMENT', id = CURSOR) {
  return { key: source + ':' + id, message: { chatId: source === 'MAIN' ? '-1001' : '-2001', messageId: id, link: null }, source, type, fileName: 'synthetic', size: 123, caption: '' }
}
function preview(overrides = {}) {
  const post = { key: '1:-1001:100', accountId: '1', channelId: '-1001', channelTitle: 'Synthetic', mainMessageId: '100', title: 'Synthetic', text: '#tag', albumId: '0', mainMessages: [], mainMedia: [media('PHOTO', 'MAIN', '100')], discussionChatId: '-2001', discussionRootMessageId: '500', discussionRoots: [], canReadComments: true, mainCoverage: 'COMPLETE', coverage: 'PARTIAL', warnings: [] }
  return { id: ID, post, comments: { postKey: post.key, media: [media('VIDEO'), media('PHOTO', 'COMMENT', '9007199254741000')], messages: [{ chatId: '-2001', messageId: CURSOR, link: null }], messageCount: 1, nextCursor: CURSOR, exhausted: false, coverage: 'PARTIAL', warnings: [] }, pageNumber: 1, observedMessages: 1, exhausted: false, canContinue: true, warnings: [], ...overrides }
}
function lastPage(previous) { return { ...previous, comments: { ...previous.comments, media: [], messages: [], messageCount: 0, exhausted: true, coverage: 'COMPLETE' }, pageNumber: 2, exhausted: true, canContinue: false } }
function mount(t, initialProps = {}) {
  const props = vue.reactive({ authorized: true, available: true, accountId: '1', ...initialProps })
  const expected = [], calls = [], failures = [], hooks = [], events = []
  const scope = vue.effectScope()
  const api = async (path, options) => {
    calls.push({ path, ...options })
    const next = expected.shift()
    try { assert.ok(next, 'Unexpected API request'); assert.equal(path, next.path); assert.deepEqual(options, { method: 'POST', timeout: 120000, body: next.body }) }
    catch (cause) { failures.push(cause); throw cause }
    if (next.result instanceof Error) throw next.result
    return next.result
  }
  const modules = { vue: { ...vue, onMounted: () => {}, onBeforeUnmount: hook => hooks.push(hook) }, '../api': { api, ApiError }, '../library': library, '../downloadPreview': download, '../downloadTasks': downloadTasks, './AppIcon.vue': {} }
  const module = { exports: {} }
  evaluate(name => { if (name.endsWith('.vue')) return {}; assert.ok(Object.hasOwn(modules, name), name); return modules[name] }, module, module.exports)
  const state = scope.run(() => module.exports.default.setup(props, { expose() {}, emit: (...event) => events.push(event) }))
  let disposed = false
  const destroy = () => { if (disposed) return; disposed = true; hooks.forEach(hook => hook()); scope.stop() }
  t.after(() => { destroy(); assert.deepEqual(failures, []); assert.equal(expected.length, 0) })
  return { state, props, calls, events, destroy,
    expect: (result, next = false, value = state.preview.value) => expected.push({ path: next ? '/transfers/download/preview/next' : '/transfers/download/preview', body: next ? { id: value.id, cursor: value.comments.nextCursor, pageNumber: value.pageNumber } : { link: state.link.value.trim() }, result }),
  }
}
async function show(t, result = preview()) {
  const f = mount(t); f.state.link.value = LINK; await flush(); f.expect(result); await f.state.readPreview(); assert.equal(f.state.preview.value?.id, ID); return f
}

test('source preview is explicit and never invokes library import or file transfer APIs', async t => {
  const f = mount(t); assert.equal(f.calls.length, 0)
  f.state.link.value = LINK; await flush(); assert.equal(f.calls.length, 0)
  f.expect(preview()); await f.state.readPreview(); assert.equal(f.calls.length, 1)
  assert.equal(f.state.preview.value.observedMessages, 1); assert.equal(f.state.busy.value, false)
})
test('opening a channel result switches to the original single-post readonly preview without creating a task',async t=>{const f=mount(t);f.state.downloadSourceKind.value='channel';f.state.link.value=LINK;await flush();f.expect(preview());await f.state.openChannelPost(LINK);assert.equal(f.state.downloadSourceKind.value,'post');assert.equal(f.state.preview.value.id,ID);assert.equal(f.calls.length,1);assert.equal(f.calls[0].path,'/transfers/download/preview')})

test('returning to the channel list is immediate during loading and ignores the late post response', async t => {
  const f = mount(t, { previewOnly: true, browseSource: { sourceMode: 'CHANNEL', source: '-1001', tags: '', keywords: '' } })
  f.state.link.value = LINK; await flush()
  const pending = deferred(); f.expect(pending.promise)
  const reading = f.state.openChannelPost(LINK); await flush()
  assert.equal(f.state.busy.value, true)
  f.state.backToChannel()
  assert.equal(f.state.downloadSourceKind.value, 'channel'); assert.equal(f.state.busy.value, false)
  pending.resolve(preview()); await reading; await flush()
  assert.equal(f.state.preview.value, null); assert.equal(f.state.error.value, '')
  assert.equal(f.calls.length, 1)
})

test('an abandoned post failure cannot replace the channel list with an error', async t => {
  const f = mount(t, { previewOnly: true, browseSource: { sourceMode: 'CHANNEL', source: '-1001', tags: '', keywords: '' } })
  f.state.link.value = LINK; await flush()
  const pending = deferred(); f.expect(pending.promise)
  const reading = f.state.openChannelPost(LINK); await flush(); f.state.backToChannel(); await flush()
  pending.resolve(Promise.reject(new ApiError('Abandoned request', 403))); await reading
  assert.equal(f.state.downloadSourceKind.value, 'channel'); assert.equal(f.state.error.value, '')
  assert.equal(f.state.needsPreview.value, false); assert.deepEqual(f.events, [])
})

test('a late abandoned response cannot clear a newer post loading state or replace its media', async t => {
  const f = mount(t, { previewOnly: true, browseSource: { sourceMode: 'CHANNEL', source: '-1001', tags: '', keywords: '' } })
  f.state.link.value = LINK; await flush()
  const old = deferred(); f.expect(old.promise)
  const readingOld = f.state.openChannelPost(LINK); await flush(); f.state.backToChannel(); await flush()
  f.state.link.value = 'https://t.me/synthetic/2'; await flush()
  const newer = deferred(); f.expect(newer.promise)
  const readingNew = f.state.openChannelPost(f.state.link.value); await flush()
  old.resolve(preview()); await readingOld
  assert.equal(f.state.busy.value, true); assert.equal(f.state.preview.value, null)
  const result = preview({ id: '00000000-0000-0000-0000-000000000002' })
  newer.resolve(result); await readingNew
  assert.equal(f.state.preview.value.id, result.id); assert.equal(f.state.busy.value, false)
  assert.equal(f.state.downloadSourceKind.value, 'post'); assert.equal(f.calls.length, 2)
})
test('video is the default; media types toggle directly and including main media is independent', async t => {
  const f = await show(t)
  assert.deepEqual(f.state.selectedTypes.value, ['VIDEO']); assert.equal(f.state.includeMain.value, false); assert.equal(f.state.items.value.length, 1)
  f.state.includeMain.value = true; assert.equal(f.state.items.value.length, 2); assert.equal(f.state.items.value[0].type, 'PHOTO')
  f.state.toggleType('PHOTO'); assert.equal(f.state.items.value.length, 3)
  f.state.toggleType('VIDEO'); assert.equal(f.state.commentMatches.value, 1)
  f.state.toggleType('PHOTO'); assert.deepEqual(f.state.selectedTypes.value, []); assert.equal(f.state.items.value.length, 1)
  f.state.includeMain.value = false; assert.equal(f.state.items.value.length, 0); assert.match(f.state.emptyText.value, /未选择媒体类型/)
  assert.equal(f.calls.length, 1, 'Filtering does not read Telegram or download files')
})
test('all types toggle on and off explicitly; clearing pauses comment reads and keeps main media independent', async t => {
  const f = await show(t)
  f.state.toggleAllTypes(); assert.equal(f.state.allTypesSelected.value, true); assert.equal(f.state.selectedTypes.value.length, 5); assert.equal(f.state.commentMatches.value, 2)
  f.state.includeMain.value = true; f.state.toggleAllTypes()
  assert.equal(f.state.allTypesSelected.value, false); assert.deepEqual(f.state.selectedTypes.value, []); assert.equal(f.state.commentMatches.value, 0); assert.equal(f.state.mainMatches.value, 1)
  f.state.loadMore(); await flush(); assert.equal(f.calls.length, 1, 'No next comment page while all comment types are cleared')
  f.state.toggleType('PHOTO'); assert.equal(f.state.commentMatches.value, 1); assert.equal(f.state.allTypesSelected.value, false)
  f.state.toggleAllTypes(); assert.equal(f.state.allTypesSelected.value, true)
  f.state.toggleType('VIDEO'); assert.equal(f.state.allTypesSelected.value, false); assert.equal(f.state.selectedTypes.value.length, 4)
})
test('scroll loading preserves native cursor precision and retains previously loaded media', async t => {
  const f = await show(t); const first = f.state.preview.value
  assert.equal(f.calls.length, 1); assert.equal(first.exhausted, false)
  f.expect(lastPage(first), true); f.state.loadMore(); await flush()
  assert.equal(f.calls[1].body.cursor, CURSOR); assert.equal(f.state.preview.value.pageNumber, 2)
  assert.equal(f.state.preview.value.exhausted, true); assert.equal(f.state.preview.value.observedMessages, 1)
  assert.equal(f.state.collectedMedia.value.length, 2, 'An empty EOF page must not erase already displayed media')
  assert.match(f.state.emptyText.value, /已读到边界/); assert.doesNotMatch(f.state.emptyText.value, /下一页/)
  await f.state.readPreview(true); assert.equal(f.calls.length, 2)
})
test('a short zero-media page can continue and is never mistaken for EOF', async t => {
  const result = preview(); result.comments.media = []
  const f = await show(t, result); assert.equal(f.state.items.value.length, 0)
  assert.equal(f.state.preview.value.canContinue, true); assert.equal(f.state.preview.value.exhausted, false)
  assert.match(f.state.emptyText.value, /零媒体不表示评论读完/)
})
test('a missing comment thread preserves the main post without inventing an empty complete history', async t => {
  const result = preview({ comments: null, pageNumber: 0, observedMessages: 0, exhausted: false, canContinue: false }); result.post.canReadComments = false
  const f = await show(t, result); assert.equal(f.state.preview.value.comments, null); assert.equal(f.state.preview.value.exhausted, false)
  assert.match(f.state.emptyText.value, /范围尚未核全/)
  f.state.includeMain.value = true; assert.equal(f.state.items.value.length, 1)
})
test('failed next page keeps the previous preview, locks continuation, and does not retry automatically', async t => {
  const f = await show(t); const previous = f.state.preview.value
  f.expect(new Error('Synthetic timeout'), true); await f.state.readPreview(true)
  assert.equal(f.state.preview.value, previous); assert.equal(f.state.needsPreview.value, true)
  assert.match(f.state.error.value, /重新预览/); await f.state.readPreview(true); assert.equal(f.calls.length, 2)
  f.expect(preview()); await f.state.readPreview(); assert.equal(f.state.needsPreview.value, false)
})
test('a failed fresh preview clears the former source and does not substitute a library result', async t => {
  const f = await show(t); f.expect(new Error('Synthetic inaccessible')); await f.state.readPreview()
  assert.equal(f.state.preview.value, null); assert.match(f.state.error.value, /Synthetic inaccessible/); assert.equal(f.calls.length, 2)
})
test('editing the source invalidates the prior preview without saving it', async t => {
  const f = await show(t); f.state.link.value = 'https://t.me/synthetic/2'; await flush()
  assert.equal(f.state.preview.value, null); assert.equal(f.calls.length, 1)
})
test('a late response is discarded after an account switch and the old source link is cleared', async t => {
  const f = mount(t); f.state.link.value = LINK; await flush(); const pending = deferred(); f.expect(pending.promise)
  const running = f.state.readPreview(); f.props.accountId = '2'; await flush(); pending.resolve(preview()); await running
  assert.equal(f.state.preview.value, null); assert.equal(f.state.link.value, ''); assert.equal(f.state.busy.value, false)
})
test('service interruption invalidates the preview and late in-flight results', async t => {
  const f = await show(t); const pending = deferred(); f.expect(pending.promise, true); const running = f.state.readPreview(true)
  f.props.available = false; await flush(); pending.resolve(lastPage(preview())); await running
  assert.equal(f.state.preview.value, null); assert.equal(f.state.busy.value, false)
})
test('closing the dialog during a request ignores its late result', async t => {
  const f = mount(t); f.state.link.value = LINK; await flush(); const pending = deferred(); f.expect(pending.promise)
  const running = f.state.readPreview(); f.destroy(); pending.resolve(preview()); await running
  assert.equal(f.state.preview.value, null); assert.equal(f.calls.length, 1)
})
test('repeated clicks cannot start parallel preview reads', async t => {
  const f = mount(t); f.state.link.value = LINK; await flush(); const pending = deferred(); f.expect(pending.promise)
  const running = f.state.readPreview(); await f.state.readPreview(); assert.equal(f.calls.length, 1)
  pending.resolve(preview()); await running
})
test('authorization errors reach the parent without accepting the source result', async t => {
  const f = mount(t); f.state.link.value = LINK; await flush(); const cause = new ApiError('Synthetic access', 401); f.expect(cause)
  await f.state.readPreview(); assert.deepEqual(f.events, [['accessError', cause]]); assert.equal(f.state.preview.value, null)
})
test('unavailable, unauthorized, and empty-link cases never issue source reads', async t => {
  const f = mount(t); await f.state.readPreview(); assert.equal(f.calls.length, 0)
  f.state.link.value = LINK; await flush(); f.props.authorized = false; await flush(); await f.state.readPreview(); assert.equal(f.calls.length, 0)
})
test('forged account, numeric native IDs, inconsistent counts, and unrelated next receipts are rejected', () => {
  const first = preview(); assert.equal(download.validDownloadPreview(first, '1'), true)
  for (const mutate of [value => { value.post.accountId = '2' }, value => { value.post.channelId = -1001 }, value => { value.observedMessages = 2 }, value => { value.comments.postKey = 'different' }, value => { value.canContinue = false }]) {
    const bad = structuredClone(first); mutate(bad); assert.equal(download.validDownloadPreview(bad, '1'), false)
  }
  const next = lastPage(first); next.id = '00000000-0000-0000-0000-000000000002'
  assert.equal(download.validDownloadPreview(next, '1', first), false)
})
test('non-EOF cursor progression is exact and an invalid next receipt leaves the old view paused', async t => {
  const f = await show(t); const first = f.state.preview.value
  const next = { ...preview(), pageNumber: 2, observedMessages: 2 }
  assert.equal(download.validDownloadPreview(next, '1', first), false, 'Repeated cursor cannot continue')
  next.comments.nextCursor = OLDER; assert.equal(download.validDownloadPreview(next, '1', first), true)
  next.comments.nextCursor = CURSOR; f.expect(next, true); await f.state.readPreview(true)
  assert.equal(f.state.preview.value, first); assert.equal(f.state.needsPreview.value, true)
})
test('scroll batches append all media types, keep origin-page grants, and preserve the selected layout', async t => {
  const f = await show(t); const first = f.state.preview.value
  const photo = media('PHOTO', 'COMMENT', OLDER)
  const second = { ...preview(), pageNumber: 2, observedMessages: 2,
    comments: { ...first.comments, media: [photo], messages: [{ chatId: '-2001', messageId: OLDER, link: null }], nextCursor: OLDER } }
  f.state.mediaView.value = 'cards'; f.expect(second, true); f.state.loadMore(); await flush()
  assert.equal(f.state.collectedMedia.value.length, 3); assert.equal(f.state.commentMatches.value, 1)
  f.state.selectedTypes.value = ['PHOTO']; assert.equal(f.state.commentMatches.value, 2)
  assert.equal(f.state.originPages.value[first.comments.media[0].key], 1); assert.equal(f.state.originPages.value[photo.key], 2)
  assert.equal(f.state.mediaView.value, 'cards'); assert.equal(f.calls.length, 2)
})
test('a fresh source resets the accumulated feed instead of mixing it with a previous source', async t => {
  const f = await show(t); assert.equal(f.state.collectedMedia.value.length, 2)
  f.state.link.value = 'https://t.me/synthetic/2'; await flush()
  assert.equal(f.state.collectedMedia.value.length, 0); assert.deepEqual(f.state.originPages.value, {})
  assert.equal(f.state.preview.value, null)
})
