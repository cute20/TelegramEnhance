import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as library from '../src/library.ts'
import * as postCardContent from '../src/postCardContent.ts'

function compileComponent(name) {
  const parsed = parse(readFileSync(new URL(`../src/components/${name}.vue`, import.meta.url), 'utf8'), { filename: `${name}.vue` })
  assert.deepEqual(parsed.errors, [])
  const compiled = ts.transpileModule(compileScript(parsed.descriptor, { id: `test-${name}` }).content, {
    fileName: `${name}.ts`, compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
  }).outputText
  return new Function('require', 'module', 'exports', 'window', compiled)
}
const evaluateImport = compileComponent('LibraryChannelImport')
const evaluateWorkspace = compileComponent('LibraryWorkspace')
const CHANNEL = { id: '-1009007199254740993', title: 'Synthetic channel' }
const OTHER_CHANNEL = { id: '-1009007199254740994', title: 'Another synthetic channel' }
const MESSAGE_IDS = ['9007199254740993000', '9007199254740992999', '9007199254740992998']
const CHANNELS_PATH = '/library/import/channels'
const PREVIEW_PATH = '/library/import/history'
const START_PATH = '/library/import/all/start'
const STEP_PATH = '/library/import/all/step'
const POSTS_PATH = '/library/import/posts'
class ApiError extends Error {
  constructor(message, status) { super(message); this.name = 'ApiError'; this.status = status }
}
const flush = async () => { await vue.nextTick(); await new Promise(setImmediate) }
const deferred = () => {
  let resolve
  const promise = new Promise(yes => { resolve = yes })
  return { promise, resolve }
}
const channels = (overrides = {}) => ({ items: [CHANNEL, OTHER_CHANNEL], scope: 'MANAGED_CHANNELS', partial: false, warnings: [], ...overrides })
const preview = (count = 3, overrides = {}) => ({
  channel: CHANNEL, items: MESSAGE_IDS.slice(0, count).map((messageId, index) => ({ messageId, albumId: index ? null : '77', title: `Synthetic post ${index + 1}` })),
  scope: 'CHANNEL_PAGE', limit: 10, nextCursor: 'opaque:first+/==', exhausted: false, hasMore: true, warnings: [], ...overrides,
})
const imported = (created = true, overrides = {}, index = 0) => ({ post: {
  key: `synthetic-post-${index}`, accountId: '1', channelId: CHANNEL.id, mainMessageId: MESSAGE_IDS[index], channelTitle: CHANNEL.title,
  title: 'Synthetic post', text: '', albumId: index ? null : '77', mainMessages: [{ chatId: CHANNEL.id, messageId: MESSAGE_IDS[index], link: null }], mainMedia: [], discussionChatId: null,
  discussionRootMessageId: null, discussionRoots: [], canReadComments: false, coverage: 'UNAVAILABLE', warnings: [], ...overrides,
}, created })
const request = (path, result, { method = 'GET', body, timeout } = {}) => ({ path, result, method, body, timeout })
const channelRequest = result => request(CHANNELS_PATH, result, { timeout: 60000 })
const previewRequest = (result, cursor = null) => request(PREVIEW_PATH, result, { method: 'POST', body: { channelId: CHANNEL.id, cursor }, timeout: 60000 })
const stateRequest = (result, channelId = CHANNEL.id) => request('/library/import/all/state?' + new URLSearchParams({ channelId }), result, { timeout: 35000 })
const run = (overrides = {}) => ({ channelId: CHANNEL.id, runId: 'synthetic-run', revision: 0, status: 'READY', processed: 0, created: 0, updated: 0, incomplete: 0, exhausted: false, warning: null, ...overrides })
const startRequest = (result, body = { channelId: CHANNEL.id, restart: false }) => request(START_PATH, result, { method: 'POST', body, timeout: 90000 })
const stepRequest = (result, previous = run()) => request(STEP_PATH, result, { method: 'POST', body: { channelId: CHANNEL.id, runId: previous.runId, revision: previous.revision }, timeout: 90000 })
const postRequest = (result, index = 0) => request(POSTS_PATH, result, { method: 'POST', body: { channelId: CHANNEL.id, messageId: MESSAGE_IDS[index] }, timeout: 90000 })

function mount(t, evaluate, initialSteps, initialProps = {}) {
  const steps = [...initialSteps], calls = [], failures = [], events = [], hooks = [], timers = []
  const props = vue.reactive({ authorized: true, available: true, accountId: '1', ...initialProps })
  const scope = vue.effectScope()
  let destroyed = false
  const api = async (path, options = {}) => {
    const call = { path, method: options.method ?? 'GET', body: options.body, timeout: options.timeout }
    calls.push(call)
    const expected = steps.shift()
    try {
      assert.ok(expected, `Unexpected request: ${call.method} ${path}`)
      assert.deepEqual(call, { path: expected.path, method: expected.method, body: expected.body, timeout: expected.timeout })
    } catch (error) { failures.push(error); throw error }
    if (expected.result instanceof Error) throw expected.result
    return expected.result
  }
  const modules = { vue: { ...vue, onBeforeUnmount: callback => hooks.push(callback) }, '../api': { api, ApiError }, '../library': library, '../postCardContent': postCardContent }
  const module = { exports: {} }
  evaluate(name => {
    if (name.endsWith('.vue')) return {}
    assert.ok(Object.hasOwn(modules, name), `Unexpected component dependency: ${name}`)
    return modules[name]
  }, module, module.exports, { setTimeout: callback => { timers.push(callback); return timers.length } })
  const state = scope.run(() => module.exports.default.setup(props, { expose() {}, emit: (...event) => events.push(event) }))
  const destroy = () => {
    if (destroyed) return
    destroyed = true; hooks.forEach(callback => callback()); scope.stop()
  }
  t.after(() => { destroy(); assert.deepEqual(failures, []); assert.equal(steps.length, 0, 'Expected API steps must all execute') })
  return { state, props, calls, events, timers, destroy, expect: (...next) => steps.push(...next), postCalls: () => calls.filter(call => call.path === POSTS_PATH) }
}
async function setupImport(t, result = channels(), props = {}) {
  const accessible = (props.authorized ?? true) && (props.available ?? true) && (Object.hasOwn(props, 'accountId') ? !!props.accountId : true)
  const f = mount(t, evaluateImport, accessible ? [channelRequest(result)] : [], props)
  await flush()
  return f
}
async function chooseChannel(f, id = CHANNEL.id, saved = null) {
  if (f.state.selectedChannelId.value === id) return
  f.expect(stateRequest(saved, id))
  await f.state.selectChannel(id)
}
async function showPreview(f, result = preview()) {
  await chooseChannel(f)
  f.expect(previewRequest(result))
  await f.state.previewRecent()
  assert.equal(f.state.previewReady.value, true)
}
async function nextTurn(f) {
  assert.ok(f.timers.length, 'The serial importer must yield to controls between posts')
  f.timers.shift()()
  await flush()
}

test('channel selection and explicit preview stay read-only, with partial scope and warnings retained', async t => {
  const f = await setupImport(t, channels({ partial: true, warnings: ['Synthetic partial channel list'] }))
  assert.equal(f.calls.length, 1)
  await chooseChannel(f)
  assert.equal(f.state.selectedChannelId.value, CHANNEL.id)
  assert.equal(f.calls.length, 2, 'Selecting a channel only reads persistent progress, never imports posts')
  await showPreview(f, preview(2, { warnings: ['Synthetic recent batch warning'] }))
  assert.equal(f.calls.length, 3)
  assert.equal(f.postCalls().length, 0)
  assert.equal(f.state.channels.value.partial, true)
  assert.deepEqual(f.state.warnings.value, ['Synthetic partial channel list', 'Synthetic recent batch warning'])
  await chooseChannel(f, OTHER_CHANNEL.id)
  assert.equal(f.state.preview.value, null)
  assert.equal(f.state.previewReady.value, false)
})

test('empty partial channel and empty recent-post results do not pretend the whole channel is empty', async t => {
  const f = await setupImport(t, channels({ items: [], partial: true, warnings: ['Synthetic partial list'] }))
  assert.deepEqual(f.state.channels.value.items, [])
  assert.equal(f.state.channels.value.partial, true)
  await f.state.previewRecent()
  assert.equal(f.calls.length, 1)
  f.expect(channelRequest(channels()))
  await f.state.loadChannels()
  await showPreview(f, preview(0))
  await f.state.importBatch()
  assert.equal(f.postCalls().length, 0)
  assert.equal(f.state.progress.value, null)
})

for (const invalid of ['scope', 'over-limit', 'wrong-channel', 'numeric-message-id']) {
  test(`a ${invalid} preview cannot be imported`, async t => {
    const f = await setupImport(t)
    await chooseChannel(f)
    const result = preview()
    if (invalid === 'scope') result.scope = 'WHOLE_CHANNEL'
    if (invalid === 'over-limit') result.items = Array.from({ length: 11 }, (_, index) => ({ messageId: String(index + 1), albumId: null, title: '' }))
    if (invalid === 'wrong-channel') result.channel = OTHER_CHANNEL
    if (invalid === 'numeric-message-id') result.items[0].messageId = 123
    f.expect(previewRequest(result))
    await f.state.previewRecent()
    await f.state.importBatch()
    assert.equal(f.state.preview.value, null)
    assert.equal(f.state.previewReady.value, false)
    assert.match(f.state.error.value, /页面无法确认/)
    assert.equal(f.postCalls().length, 0)
  })
}

test('imports are serial, preserve exact ID strings and count independently saved new and updated posts', async t => {
  const f = await setupImport(t)
  await showPreview(f)
  f.expect(postRequest(imported(true)), postRequest(imported(false, {}, 1), 1), postRequest(imported(true, {}, 2), 2))
  const running = f.state.importBatch()
  await flush()
  assert.equal(f.postCalls().length, 1)
  assert.equal(f.state.progress.value.completed, 1)
  assert.equal(f.state.busy.value, 'import')
  f.state.selectChannel(OTHER_CHANNEL.id)
  assert.equal(f.state.selectedChannelId.value, CHANNEL.id)
  await nextTurn(f)
  assert.equal(f.postCalls().length, 2)
  assert.equal(f.state.progress.value.completed, 2)
  await nextTurn(f)
  await running
  assert.deepEqual(f.state.progress.value, { completed: 3, total: 3, created: 2, updated: 1, status: 'complete' })
  assert.deepEqual(f.postCalls().map(call => call.body.messageId), MESSAGE_IDS)
  assert.ok(f.postCalls().every(call => call.body.channelId === CHANNEL.id && call.timeout === 90000))
  assert.match(f.state.notice.value, /选中主帖.*评论仍按需读取/)
  assert.equal(f.state.previewReady.value, false)
  assert.ok(!f.calls.some(call => /comments|\/preview\?/.test(call.path)))
})

test('Stop during a post accepts its saved receipt and submits no subsequent post', async t => {
  const f = await setupImport(t)
  await showPreview(f)
  const pending = deferred()
  f.expect(postRequest(pending.promise))
  const running = f.state.importBatch()
  await flush()
  f.state.stopImport()
  pending.resolve(imported(true))
  await running
  assert.deepEqual(f.state.progress.value, { completed: 1, total: 3, created: 1, updated: 0, status: 'stopped' })
  assert.match(f.state.notice.value, /已停止后续导入.*保存结果已接收/)
  assert.equal(f.postCalls().length, 1)
  assert.equal(f.timers.length, 0)
  await f.state.importBatch()
  assert.equal(f.postCalls().length, 1, 'A stopped batch needs an explicit new preview')
})

test('Stop between posts prevents the queued next iteration from making a request', async t => {
  const f = await setupImport(t)
  await showPreview(f)
  f.expect(postRequest(imported(false)))
  const running = f.state.importBatch()
  await flush()
  f.state.stopImport()
  await nextTurn(f)
  await running
  assert.equal(f.postCalls().length, 1)
  assert.equal(f.state.progress.value.status, 'stopped')
  assert.equal(f.state.progress.value.updated, 1)
})

for (const failure of ['HTTP 401', 'HTTP 500', 'timeout']) {
  test(`${failure} pauses the current batch with no retry and no unconfirmed success count`, async t => {
    const f = await setupImport(t)
    await showPreview(f)
    const error = failure === 'timeout' ? new Error('Synthetic timeout') : new ApiError(`Synthetic ${failure}`, Number(failure.slice(5)))
    f.expect(postRequest(error))
    await f.state.importBatch()
    await flush()
    assert.deepEqual(f.state.progress.value, { completed: 0, total: 3, created: 0, updated: 0, status: 'failed' })
    assert.equal(f.postCalls().length, 1)
    assert.equal(f.state.previewReady.value, false)
    assert.match(f.state.error.value, /导入已暂停.*重新读取本页.*去重/)
    if (failure === 'timeout') assert.match(f.state.error.value, /可能仍在后台保存/)
    assert.equal(f.events.length, failure === 'HTTP 401' ? 1 : 0)
    if (f.events.length) assert.deepEqual(f.events[0], ['accessError', error])
    await f.state.importBatch()
    assert.equal(f.postCalls().length, 1)
    assert.equal(f.timers.length, 0)
  })
}

test('an error after one saved post preserves that count; only a fresh explicit preview allows deduplicated import', async t => {
  const f = await setupImport(t)
  await showPreview(f)
  f.expect(postRequest(imported(true)), postRequest(new ApiError('Synthetic failure', 500), 1))
  const running = f.state.importBatch()
  await flush(); await nextTurn(f); await running
  assert.deepEqual(f.state.progress.value, { completed: 1, total: 3, created: 1, updated: 0, status: 'failed' })
  await showPreview(f, preview(1))
  assert.equal(f.state.progress.value, null)
  f.expect(postRequest(imported(false)))
  await f.state.importBatch()
  assert.deepEqual(f.state.progress.value, { completed: 1, total: 1, created: 0, updated: 1, status: 'complete' })
})

for (const mismatch of ['account', 'channel', 'created', 'missing-key', 'blank-key', 'album', 'missing-member', 'foreign-member', 'other-member', 'incomplete-post']) {
  test(`a mismatched ${mismatch} receipt cannot be counted or advance the batch`, async t => {
    const f = await setupImport(t)
    await showPreview(f)
    const result = imported()
    if (mismatch === 'account') result.post.accountId = '2'
    if (mismatch === 'channel') result.post.channelId = OTHER_CHANNEL.id
    if (mismatch === 'created') delete result.created
    if (mismatch === 'missing-key') delete result.post.key
    if (mismatch === 'blank-key') result.post.key = '  '
    if (mismatch === 'album') result.post.albumId = '88'
    if (mismatch === 'missing-member') result.post.mainMessages = []
    if (mismatch === 'foreign-member') result.post.mainMessages[0].chatId = OTHER_CHANNEL.id
    if (mismatch === 'other-member') result.post.mainMessages[0].messageId = MESSAGE_IDS[1]
    if (mismatch === 'incomplete-post') result.post = { accountId: '1', channelId: CHANNEL.id }
    f.expect(postRequest(result))
    await f.state.importBatch()
    assert.equal(f.state.progress.value.completed, 0)
    assert.equal(f.state.progress.value.status, 'failed')
    assert.equal(f.postCalls().length, 1)
    assert.equal(f.timers.length, 0)
  })
}

for (const mismatch of ['another-post', 'unexpected-album']) {
  test(`a single-message candidate rejects a same-channel ${mismatch} receipt`, async t => {
    const f = await setupImport(t)
    await showPreview(f, preview(1, { items: [{ messageId: MESSAGE_IDS[0], albumId: null, title: 'Synthetic single post' }] }))
    const result = imported(true, { albumId: '0', mainMessageId: MESSAGE_IDS[0] })
    if (mismatch === 'another-post') result.post.mainMessageId = MESSAGE_IDS[1]
    else result.post.albumId = '77'
    f.expect(postRequest(result))
    await f.state.importBatch()
    assert.deepEqual(f.state.progress.value, { completed: 0, total: 1, created: 0, updated: 0, status: 'failed' })
    assert.equal(f.postCalls().length, 1)
    assert.equal(f.timers.length, 0)
  })
}

for (const albumId of [null, '0']) {
  test(`a correctly associated single-message receipt accepts albumId ${JSON.stringify(albumId)}`, async t => {
    const f = await setupImport(t)
    await showPreview(f, preview(1, { items: [{ messageId: MESSAGE_IDS[0], albumId: '0', title: 'Synthetic single post' }] }))
    f.expect(postRequest(imported(true, { albumId })))
    await f.state.importBatch()
    assert.deepEqual(f.state.progress.value, { completed: 1, total: 1, created: 1, updated: 0, status: 'complete' })
  })
}

test('an album receipt may use a different canonical message when it includes the requested member', async t => {
  const f = await setupImport(t)
  await showPreview(f, preview(1))
  f.expect(postRequest(imported(false, {
    mainMessageId: MESSAGE_IDS[1],
    mainMessages: [{ chatId: CHANNEL.id, messageId: MESSAGE_IDS[1], link: null }, { chatId: CHANNEL.id, messageId: MESSAGE_IDS[0], link: null }],
  })))
  await f.state.importBatch()
  assert.deepEqual(f.state.progress.value, { completed: 1, total: 1, created: 0, updated: 1, status: 'complete' })
  assert.equal(f.state.error.value, '')
})

for (const context of ['account', 'unavailable', 'unmount']) {
  for (const phase of ['channels', 'preview', 'post']) {
    test(`${context} change rejects a late ${phase} response without another import`, async t => {
      const pending = deferred()
      const f = await setupImport(t, phase === 'channels' ? pending.promise : channels())
      let running
      if (phase === 'preview') {
        await chooseChannel(f); f.expect(previewRequest(pending.promise)); running = f.state.previewRecent()
      } else if (phase === 'post') {
        await showPreview(f); f.expect(postRequest(pending.promise)); running = f.state.importBatch()
      }
      await flush()
      if (context === 'account') {
        f.expect(channelRequest(channels({ items: [OTHER_CHANNEL] })))
        f.props.accountId = '2'
      } else if (context === 'unavailable') f.props.available = false
      else f.destroy()
      await flush()
      pending.resolve(phase === 'channels' ? channels() : phase === 'preview' ? preview() : imported())
      await running; await flush()
      if (context !== 'unmount') {
        assert.equal(f.state.preview.value, null)
        assert.equal(f.state.progress.value, null)
        assert.equal(f.state.selectedChannelId.value, '')
        assert.equal(f.state.notice.value, '')
        assert.equal(f.state.error.value, '')
        assert.deepEqual(f.state.channels.value?.items ?? [], context === 'account' ? [OTHER_CHANNEL] : [])
      } else {
        assert.notEqual(f.state.progress.value?.status, 'complete')
        assert.equal(f.state.progress.value?.completed ?? 0, 0)
      }
      assert.equal(f.postCalls().length, phase === 'post' ? 1 : 0)
      assert.equal(f.timers.length, 0)
    })
  }
}

for (const [missing, value, restored] of [['authorized', false, true], ['available', false, true], ['accountId', null, '1']]) {
  test(`no API is requested while ${missing} is missing`, async t => {
    const f = await setupImport(t, channels(), { [missing]: value })
    await f.state.loadChannels(); await f.state.previewRecent(); await f.state.importBatch()
    assert.equal(f.calls.length, 0)
    f.expect(channelRequest(channels()))
    f.props[missing] = restored
    await flush()
    assert.equal(f.calls.length, 1)
  })
}

test('returning from import refreshes the catalog while preserving existing filters, page and view', async t => {
  const catalogPath = '/library/catalog?tags=&keywords=&limit=20&offset=0'
  const catalog = { items: [], limit: 20, offset: 0, total: 0, indexedPosts: 0, indexedChannels: 0, hasMore: false, scope: 'INDEXED_ONLY' }
  const f = mount(t, evaluateWorkspace, [request('/library/channels', []), request(catalogPath, catalog)])
  await flush()
  f.state.selectedChannels.value = [CHANNEL.id]
  f.state.appliedTags.value = f.state.tagsInput.value = '旅行'
  f.state.appliedKeywords.value = f.state.keywordsInput.value = '周末'
  f.state.offset.value = 40; f.state.view.value = 'list'; f.state.selectedPostKey.value = 'synthetic-selected'
  f.state.openImport()
  await flush()
  assert.equal(f.state.mode.value, 'import')
  assert.equal(f.state.selectedPostKey.value, '')
  assert.equal(f.calls.length, 2)
  const query = new URLSearchParams({ tags: '旅行', keywords: '周末', limit: '20', offset: '40' })
  query.append('channelIds', CHANNEL.id)
  f.expect(request('/library/channels', [{ ...CHANNEL, indexedPosts: 1 }]), request(`/library/catalog?${query}`, { ...catalog, offset: 40, indexedPosts: 1, indexedChannels: 1 }))
  f.state.mode.value = 'catalog'
  await flush()
  assert.equal(f.calls.length, 4)
  assert.deepEqual(f.state.selectedChannels.value, [CHANNEL.id])
  assert.equal(f.state.appliedTags.value, '旅行'); assert.equal(f.state.appliedKeywords.value, '周末')
  assert.equal(f.state.offset.value, 40); assert.equal(f.state.view.value, 'list')
  assert.equal(f.state.catalog.value.indexedPosts, 1)
})

test('history next and previous reload opaque cursor pages, including empty non-EOF service pages', async t => {
  const f = await setupImport(t)
  await showPreview(f)
  const secondCursor = 'opaque:first+/=='
  const thirdCursor = 'opaque:older/?==9007199254740993'
  const empty = preview(0, { nextCursor: thirdCursor })
  f.expect(previewRequest(empty, secondCursor))
  await f.state.nextPage()
  assert.equal(f.state.pageIndex.value, 1)
  assert.equal(f.state.preview.value.exhausted, false)
  assert.equal(f.state.preview.value.hasMore, true)
  assert.deepEqual(f.state.selectedMessages.value, [])
  f.expect(previewRequest(preview(1, { nextCursor: null, exhausted: true, hasMore: false }), thirdCursor))
  await f.state.nextPage()
  assert.equal(f.state.pageIndex.value, 2)
  assert.deepEqual(f.state.selectedMessages.value, [MESSAGE_IDS[0]])
  assert.deepEqual(f.state.pageCursors.value, [null, secondCursor, thirdCursor])
  f.expect(previewRequest(empty, secondCursor), previewRequest(preview(), null))
  await f.state.previousPage(); await f.state.previousPage()
  assert.equal(f.state.pageIndex.value, 0)
  assert.deepEqual(f.state.selectedMessages.value, MESSAGE_IDS)
  assert.ok(f.state.pageCursors.value.every(cursor => cursor === null || typeof cursor === 'string'))
  await chooseChannel(f, OTHER_CHANNEL.id)
  assert.deepEqual(f.state.pageCursors.value, [null])
  assert.equal(f.state.preview.value, null)
  assert.deepEqual(f.state.selectedMessages.value, [])
})

test('only selected posts are imported and row receipts refer to their real selected message', async t => {
  const f = await setupImport(t)
  await showPreview(f)
  f.state.selectPage(false)
  assert.deepEqual(f.state.selectedItems.value, [])
  f.state.togglePost(MESSAGE_IDS[1])
  f.expect(postRequest(imported(true, {}, 1), 1))
  await f.state.importBatch()
  assert.deepEqual(f.state.progress.value, { completed: 1, total: 1, created: 1, updated: 0, status: 'complete' })
  assert.deepEqual(f.state.importedMessages.value, [MESSAGE_IDS[1]])
  assert.equal(f.postCalls()[0].body.messageId, MESSAGE_IDS[1])
})

test('full import serially loads batches then saves posts, and exhausted READY does not mean COMPLETE', async t => {
  const f = await setupImport(t)
  await chooseChannel(f)
  const loaded = run({ revision: 1, exhausted: true })
  const saved = run({ revision: 2, processed: 1, created: 1, exhausted: true })
  const complete = { ...saved, revision: 3, status: 'COMPLETE' }
  f.expect(startRequest(run()), stepRequest(loaded), stepRequest(saved, loaded), stepRequest(complete, saved))
  const running = f.state.startAll()
  await flush()
  assert.equal(f.state.fullRun.value.status, 'READY')
  assert.equal(f.state.fullRun.value.exhausted, true)
  assert.equal(f.calls.filter(call => call.path === STEP_PATH).length, 1)
  await f.state.importBatch()
  assert.equal(f.postCalls().length, 0)
  await nextTurn(f)
  assert.equal(f.state.fullRun.value.processed, 1)
  await nextTurn(f)
  await running
  assert.equal(f.state.fullRun.value.status, 'COMPLETE')
  assert.deepEqual(f.calls.filter(call => call.path === STEP_PATH).map(call => call.body.revision), [0, 1, 2])
  assert.match(f.state.notice.value, /历史已读到边界.*评论仍按需读取/)
})

test('full import has only one step in flight, Stop applies that step and sends no subsequent step', async t => {
  const f = await setupImport(t)
  await showPreview(f)
  const pending = deferred()
  f.expect(startRequest(run()), stepRequest(pending.promise))
  const running = f.state.startAll()
  await flush()
  assert.equal(f.calls.filter(call => call.path === STEP_PATH).length, 1)
  await f.state.importBatch(); await f.state.startAll(); await f.state.nextPage()
  f.state.stopImport()
  pending.resolve(run({ revision: 1, processed: 1, created: 1 }))
  await running
  assert.equal(f.calls.filter(call => call.path === STEP_PATH).length, 1)
  assert.equal(f.postCalls().length, 0)
  assert.equal(f.state.fullRun.value.processed, 1)
  assert.equal(f.state.fullRun.value.status, 'READY')
  assert.match(f.state.notice.value, /已停止后续导入/)
  assert.equal(f.timers.length, 0)
})

test('Stop during start records its persistent run but does not submit a first step', async t => {
  const f = await setupImport(t)
  await chooseChannel(f)
  const pending = deferred()
  f.expect(startRequest(pending.promise))
  const running = f.state.startAll()
  await flush(); f.state.stopImport(); pending.resolve(run())
  await running
  assert.equal(f.state.fullRun.value.revision, 0)
  assert.equal(f.calls.filter(call => call.path === STEP_PATH).length, 0)
  assert.match(f.state.notice.value, /已停止后续导入/)
})

test('Stop after a step yield prevents another step', async t => {
  const f = await setupImport(t)
  await chooseChannel(f)
  f.expect(startRequest(run()), stepRequest(run({ revision: 1 })))
  const running = f.state.startAll()
  await flush(); f.state.stopImport(); await nextTurn(f); await running
  assert.equal(f.calls.filter(call => call.path === STEP_PATH).length, 1)
})

test('selecting a channel recovers its persistent READY state and explicitly resumes its exact revision', async t => {
  const f = await setupImport(t)
  const saved = run({ revision: 5, processed: 4, created: 2, updated: 2, incomplete: 1 })
  await chooseChannel(f, CHANNEL.id, saved)
  assert.equal(f.state.fullRun.value.processed, 4)
  assert.ok(f.state.warnings.value.some(warning => /主帖待核实 1 条/.test(warning)))
  assert.equal(f.calls.filter(call => call.path === START_PATH).length, 0)
  f.expect(startRequest(saved), stepRequest({ ...saved, revision: 6, status: 'COMPLETE', exhausted: true }, saved))
  await f.state.startAll()
  assert.equal(f.state.fullRun.value.revision, 6)
  assert.equal(f.calls.find(call => call.path === STEP_PATH).body.revision, 5)
})

test('a deduplicated post becoming complete may reduce incomplete without adding processed posts', async t => {
  const f = await setupImport(t)
  const saved = run({ revision: 5, processed: 4, created: 2, updated: 2, incomplete: 1 })
  await chooseChannel(f, CHANNEL.id, saved)
  f.expect(startRequest(saved), stepRequest({ ...saved, revision: 6, incomplete: 0, status: 'COMPLETE', exhausted: true }, saved))
  await f.state.startAll()
  assert.equal(f.state.fullRun.value.processed, 4)
  assert.equal(f.state.fullRun.value.incomplete, 0)
  assert.equal(f.state.fullRun.value.status, 'COMPLETE')
  assert.equal(f.state.error.value, '')
  assert.ok(!f.state.warnings.value.some(warning => /主帖待核实/.test(warning)))
})

test('BLOCKED state never auto-steps; explicit restart uses current CAS and starts a fresh zero-revision run', async t => {
  const f = await setupImport(t)
  const blocked = run({ status: 'BLOCKED', revision: 8, processed: 3, created: 2, updated: 1, warning: 'Synthetic boundary requires review' })
  await chooseChannel(f, CHANNEL.id, blocked)
  await f.state.startAll()
  assert.equal(f.calls.filter(call => call.path === START_PATH).length, 0)
  assert.ok(f.state.warnings.value.includes(blocked.warning))
  const fresh = run({ runId: 'synthetic-new-run' })
  f.expect(startRequest(fresh, { channelId: CHANNEL.id, restart: true, runId: blocked.runId, revision: 8 }),
    stepRequest({ ...fresh, revision: 1, status: 'COMPLETE', exhausted: true }, fresh))
  await f.state.startAll(true)
  assert.equal(f.state.fullRun.value.runId, fresh.runId)
  assert.equal(f.state.fullRun.value.processed, 0)
})

for (const failure of ['HTTP', 'timeout']) {
  test(`uncertain ${failure} step only GETs persistent state and never retries a step`, async t => {
    const f = await setupImport(t)
    await chooseChannel(f)
    const persisted = run({ revision: 1, processed: 1, created: 1 })
    const error = failure === 'HTTP' ? new ApiError('Synthetic server error', 500) : new Error('Synthetic timeout')
    f.expect(startRequest(run()), stepRequest(error), stateRequest(persisted))
    await f.state.startAll(); await flush()
    assert.equal(f.calls.filter(call => call.path === STEP_PATH).length, 1)
    assert.deepEqual(f.state.fullRun.value, persisted)
    assert.equal(f.state.runKnown.value, true)
    assert.match(f.state.error.value, /已暂停.*手动继续.*不会自动重试/)
    assert.equal(f.timers.length, 0)
  })
}

test('failed persistent-state verification locks stepping until a user successfully refreshes progress', async t => {
  const f = await setupImport(t)
  await chooseChannel(f)
  f.expect(startRequest(run()), stepRequest(new Error('Synthetic timeout')), stateRequest(new Error('Synthetic GET error')))
  await f.state.startAll()
  assert.equal(f.state.runKnown.value, false)
  assert.equal(f.state.fullNeedsCheck.value, true)
  await f.state.startAll()
  assert.equal(f.calls.filter(call => call.path === STEP_PATH).length, 1)
  f.expect(stateRequest(run({ revision: 1, processed: 1, created: 1 })))
  await f.state.refreshRun()
  assert.equal(f.state.runKnown.value, true)
  assert.equal(f.state.fullNeedsCheck.value, false)
  assert.equal(f.state.fullRun.value.processed, 1)
  assert.equal(f.calls.filter(call => call.path === STEP_PATH).length, 1)
})

for (const mismatch of ['channel', 'run', 'revision', 'complete-without-boundary', 'regressed-count', 'incomplete-over-processed']) {
  test(`a full-step ${mismatch} receipt cannot replace persistent confirmed progress or trigger another step`, async t => {
    const f = await setupImport(t)
    const previous = run({ revision: 4, processed: 2, created: 1, updated: 1 })
    await chooseChannel(f, CHANNEL.id, previous)
    const invalid = { ...previous, revision: 5 }
    if (mismatch === 'channel') invalid.channelId = OTHER_CHANNEL.id
    if (mismatch === 'run') invalid.runId = 'other-synthetic-run'
    if (mismatch === 'revision') invalid.revision = 4
    if (mismatch === 'complete-without-boundary') invalid.status = 'COMPLETE'
    if (mismatch === 'regressed-count') invalid.processed = 1
    if (mismatch === 'incomplete-over-processed') invalid.incomplete = 3
    f.expect(startRequest(previous), stepRequest(invalid, previous), stateRequest(previous))
    await f.state.startAll()
    assert.deepEqual(f.state.fullRun.value, previous)
    assert.equal(f.calls.filter(call => call.path === STEP_PATH).length, 1)
    assert.match(f.state.error.value, /已暂停/)
    assert.equal(f.timers.length, 0)
  })
}

for (const mismatch of ['run', 'older-revision']) {
  test(`a resumed start with ${mismatch} mismatch cannot start a step`, async t => {
    const f = await setupImport(t)
    const previous = run({ revision: 4 })
    await chooseChannel(f, CHANNEL.id, previous)
    const invalid = { ...previous, ...(mismatch === 'run' ? { runId: 'other-synthetic-run' } : { revision: 3 }) }
    f.expect(startRequest(invalid), stateRequest(previous))
    await f.state.startAll()
    assert.deepEqual(f.state.fullRun.value, previous)
    assert.equal(f.calls.filter(call => call.path === STEP_PATH).length, 0)
  })
}

for (const context of ['account', 'unavailable', 'unmount']) {
  for (const phase of ['start', 'step']) {
    test(`${context} change discards a late full ${phase} result and never starts another step`, async t => {
      const f = await setupImport(t)
      await chooseChannel(f)
      const pending = deferred()
      f.expect(startRequest(phase === 'start' ? pending.promise : run()))
      if (phase === 'step') f.expect(stepRequest(pending.promise))
      const running = f.state.startAll()
      await flush()
      if (context === 'account') { f.expect(channelRequest(channels())); f.props.accountId = '2' }
      else if (context === 'unavailable') f.props.available = false
      else f.destroy()
      await flush()
      pending.resolve(phase === 'start' ? run() : run({ revision: 1, processed: 1, created: 1 }))
      await running
      if (context !== 'unmount') assert.equal(f.state.fullRun.value, null)
      else assert.equal(f.state.fullRun.value?.processed ?? 0, 0)
      assert.equal(f.calls.filter(call => call.path === STEP_PATH).length, phase === 'step' ? 1 : 0)
      assert.equal(f.timers.length, 0)
    })
  }
}
