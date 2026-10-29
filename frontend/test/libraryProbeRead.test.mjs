import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as library from '../src/library.ts'
import { runCommentRead } from '../src/commentReadRunner.ts'

// Execute the real script setup without a DOM. Only its API, lifecycle hook and
// browser timer are injected; Vue reactivity and the continuous reader are real.
const filename = new URL('../src/components/LibraryProbe.vue', import.meta.url)
const parsed = parse(readFileSync(filename, 'utf8'), { filename: 'LibraryProbe.vue' })
assert.deepEqual(parsed.errors, [])
const script = compileScript(parsed.descriptor, { id: 'library-probe-read-test' })
const compiled = ts.transpileModule(script.content, {
  fileName: 'LibraryProbe.ts',
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText
const evaluate = new Function('require', 'module', 'exports', 'window', compiled)
const KEY = '1:-1001:100'
const NEXT_KEY = '1:-1001:200'
const DETAIL_PATH = key => `/library/posts/${encodeURIComponent(key)}?limit=50&offset=0`
const COMMENTS_PATH = `/library/posts/${encodeURIComponent(KEY)}/comments`
const INDEX_PATH = '/library/posts?limit=20&offset=0'

class ApiError extends Error {
  constructor(message, status) { super(message); this.name = 'ApiError'; this.status = status }
}
const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const flush = async () => { await vue.nextTick(); await new Promise(setImmediate) }
const detail = ({ key = KEY, accountId = '1', coverage = 'PARTIAL', nextCursor = '900', exhausted = false } = {}) => ({
  post: {
    key, accountId, channelId: '-1001', channelTitle: 'Synthetic channel', mainMessageId: key === KEY ? '100' : '200',
    title: 'Synthetic post', text: '', albumId: '0', mainMessages: [], mainMedia: [],
    discussionChatId: '-2001', discussionRootMessageId: '500', discussionRoots: [], canReadComments: true,
    mainCoverage: 'COMPLETE', coverage, warnings: [],
  },
  commentMedia: [], nextCursor, exhausted, coverage, warnings: [], mainCoverage: 'COMPLETE', commentCoverage: coverage,
  indexedCommentMessages: 12, indexedCommentMedia: 9, commentWarnings: [],
})
const page = (nextCursor, overrides = {}) => ({
  postKey: KEY, nextCursor, exhausted: false, coverage: 'PARTIAL', commentCoverage: 'PARTIAL',
  media: [], messages: [], messageCount: 2, warnings: [], commentWarnings: [], observations: [], scanWarnings: [], ...overrides,
})
const request = (path, result, { method = 'GET', body } = {}) => ({ path, result, method, body })
const comments = (result, cursor) => request(COMMENTS_PATH, result, { method: 'POST', body: { cursor } })

async function setup(t, initial = detail()) {
  const steps = [request(INDEX_PATH, [initial.post]), request(DETAIL_PATH(initial.post.key), initial)]
  const calls = [], failures = [], events = [], unmount = [], timers = []
  const scope = vue.effectScope()
  const props = vue.reactive({ authorized: true, available: true, accountId: '1', initialPostKey: initial.post.key })
  const api = async (path, options = {}) => {
    const call = { path, method: options.method ?? 'GET', body: options.body, timeout: options.timeout }
    calls.push(call)
    const expected = steps.shift()
    try {
      assert.ok(expected, `Unexpected request: ${call.method} ${path}`)
      assert.equal(call.path, expected.path)
      assert.equal(call.method, expected.method)
      assert.deepEqual(call.body, expected.body)
    } catch (error) { failures.push(error); throw error }
    if (expected.result instanceof Error) throw expected.result
    return expected.result
  }
  const modules = {
    vue: { ...vue, onBeforeUnmount: callback => unmount.push(callback) },
    '../api': { api, ApiError }, '../library': library, '../commentReadRunner': { runCommentRead },
    './AppIcon.vue': {}, './LibraryMediaTable.vue': {},
  }
  const module = { exports: {} }
  evaluate(name => {
    assert.ok(Object.hasOwn(modules, name), `Unexpected component dependency: ${name}`)
    return modules[name]
  }, module, module.exports, { setTimeout: callback => { timers.push(callback); return timers.length } })
  const state = scope.run(() => module.exports.default.setup(props, { expose() {}, emit: (...event) => events.push(event) }))
  t.after(() => {
    for (const callback of unmount) callback()
    scope.stop()
    assert.deepEqual(failures, [], 'The component must follow the expected request sequence')
    assert.equal(steps.length, 0, 'All expected requests must be consumed')
  })
  await flush()
  assert.equal(state.busy.value, '')
  assert.equal(state.selectedKey.value, initial.post.key)
  assert.equal(calls.length, 2)
  return {
    state, props, calls, events, timers,
    expect: (...next) => steps.push(...next),
    postCalls: () => calls.filter(call => call.method === 'POST' && call.path.endsWith('/comments')),
  }
}

test('a PARTIAL receipt replaces COMPLETE visible state even when the local refresh fails, then stops', async t => {
  const complete = detail({ coverage: 'COMPLETE', exhausted: true, nextCursor: null })
  const f = await setup(t, complete)
  const receipt = page('800', { warnings: ['Synthetic pending media warning'] })
  f.expect(request(DETAIL_PATH(KEY), complete), comments(receipt, null),
    request(DETAIL_PATH(KEY), new Error('Synthetic local refresh failure')))

  await f.state.readComments(true)

  assert.equal(f.state.coverage.value, 'PARTIAL')
  assert.equal(f.state.commentCoverage.value, 'PARTIAL')
  assert.equal(f.state.detail.value.post.coverage, 'PARTIAL')
  assert.equal(f.state.lastReadPage.value.nextCursor, '800')
  assert.equal(f.state.remoteState.value.nextCursor, '800')
  assert.deepEqual(f.state.readProgress.value, { pages: 1, messages: 2, media: 0 })
  assert.equal(f.state.indexedMessages.value, null)
  assert.equal(f.state.indexedMedia.value, null)
  assert.ok(f.state.warnings.value.includes('Synthetic pending media warning'))
  assert.match(f.state.error.value, /本页已保存.*刷新失败.*已暂停/)
  assert.equal(f.state.busy.value, '')
  assert.equal(f.postCalls().length, 1)
  assert.equal(f.timers.length, 0)
})

test('probing a new post clears progress and the last page from the previous real read', async t => {
  const f = await setup(t)
  const finished = detail({ coverage: 'COMPLETE', exhausted: true, nextCursor: null })
  f.expect(request(DETAIL_PATH(KEY), detail()), comments(page(null, { exhausted: true, coverage: 'COMPLETE', commentCoverage: 'COMPLETE' }), '900'),
    request(DETAIL_PATH(KEY), finished))
  await f.state.readComments()
  assert.equal(f.state.readProgress.value.pages, 1)
  assert.ok(f.state.lastReadPage.value)
  const next = detail({ key: NEXT_KEY, nextCursor: '700' })
  f.state.link.value = 'https://t.me/synthetic_channel/200'
  f.expect(request('/library/probe', next, { method: 'POST', body: { link: f.state.link.value } }),
    request(INDEX_PATH, [next.post]))

  await f.state.probe()

  assert.equal(f.state.selectedKey.value, NEXT_KEY)
  assert.equal(f.state.readProgress.value, null)
  assert.equal(f.state.lastReadPage.value, null)
  assert.equal(f.state.remoteState.value.nextCursor, '700')
  assert.equal(f.state.error.value, '')
})

test('continue uses the fresh GET checkpoint as an exact string instead of stale remoteState', async t => {
  const f = await setup(t, detail({ nextCursor: '99999999999999999999' }))
  const freshCursor = '90071992547409930001'
  const fresh = detail({ nextCursor: freshCursor })
  f.expect(request(DETAIL_PATH(KEY), fresh), comments(page(null, { exhausted: true }), freshCursor),
    request(DETAIL_PATH(KEY), detail({ exhausted: true, nextCursor: null })))

  await f.state.readComments()

  assert.equal(f.postCalls().length, 1)
  assert.equal(f.postCalls()[0].body.cursor, freshCursor)
  assert.equal(f.postCalls()[0].timeout, 60000)
  assert.match(f.state.notice.value, /已读到边界.*仍有未核全项/)
  assert.equal(f.state.commentCoverage.value, 'PARTIAL')
})

test('a fresh checkpoint already at EOF prevents another POST despite stale visible continuation', async t => {
  const f = await setup(t)
  f.expect(request(DETAIL_PATH(KEY), detail({ exhausted: true, nextCursor: null })))

  await f.state.readComments()

  assert.equal(f.postCalls().length, 0)
  assert.match(f.state.notice.value, /已到读取边界/)
  assert.equal(f.state.remoteState.value.exhausted, true)
})

test('Stop during an in-flight POST still applies that receipt and local snapshot without another page', async t => {
  const f = await setup(t)
  const pending = deferred()
  f.expect(request(DETAIL_PATH(KEY), detail()), comments(pending.promise, '900'),
    request(DETAIL_PATH(KEY), detail({ nextCursor: '800' })))
  const running = f.state.readComments()
  await flush()
  assert.equal(f.postCalls().length, 1)
  assert.equal(f.state.readingComments.value, true)

  f.state.stopReading()
  assert.equal(f.state.stopRequested.value, true)
  pending.resolve(page('800', { messageCount: 4, media: [{ key: 'synthetic-media' }] }))
  await running

  assert.deepEqual(f.state.readProgress.value, { pages: 1, messages: 4, media: 1 })
  assert.equal(f.state.lastReadPage.value.messageCount, 4)
  assert.equal(f.state.remoteState.value.nextCursor, '800')
  assert.equal(f.state.detail.value.nextCursor, '800')
  assert.match(f.state.notice.value, /已停止后续读取.*当前页结果已保存/)
  assert.equal(f.postCalls().length, 1)
  assert.equal(f.timers.length, 0)
  assert.equal(f.state.busy.value, '')
})

test('Stop while the initial checkpoint is in flight submits zero pages and never claims a page was saved', async t => {
  const f = await setup(t)
  const checkpoint = deferred()
  f.expect(request(DETAIL_PATH(KEY), checkpoint.promise))
  const running = f.state.readComments()
  await flush()
  f.state.stopReading()
  checkpoint.resolve(detail())
  await running

  assert.equal(f.postCalls().length, 0)
  assert.deepEqual(f.state.readProgress.value, { pages: 0, messages: 0, media: 0 })
  assert.equal(f.state.lastReadPage.value, null)
  assert.match(f.state.notice.value, /已停止.*未发起/)
  assert.doesNotMatch(f.state.notice.value, /已保存|结果已保存/)
  assert.equal(f.state.busy.value, '')
})

for (const changed of ['accountId', 'initialPostKey']) {
  for (const phase of ['page receipt', 'local refresh']) {
    test(`${changed} change discards a late ${phase} without overwriting the new context`, async t => {
      const f = await setup(t)
      const pending = deferred()
      f.expect(request(DETAIL_PATH(KEY), detail()), comments(phase === 'page receipt' ? pending.promise : page('800'), '900'))
      if (phase === 'local refresh') f.expect(request(DETAIL_PATH(KEY), pending.promise))
      const running = f.state.readComments()
      await flush()
      assert.equal(f.postCalls().length, 1)

      if (changed === 'accountId') {
        // The old selected post is inaccessible to the new account; its own GET may
        // fail normally, but the old account's receipt must not revive that post.
        f.expect(request(INDEX_PATH, []), request(DETAIL_PATH(KEY), new ApiError('Synthetic post is unavailable to this account', 404)))
        f.props.accountId = '2'
      } else {
        f.expect(request(DETAIL_PATH(NEXT_KEY), detail({ key: NEXT_KEY, coverage: 'COMPLETE', exhausted: true, nextCursor: null })))
        f.props.initialPostKey = NEXT_KEY
      }
      await flush()
      const newError = f.state.error.value
      pending.resolve(phase === 'page receipt' ? page('800', { warnings: ['Synthetic stale warning'] }) : detail({ nextCursor: '800' }))
      await running

      assert.equal(f.state.selectedKey.value, changed === 'accountId' ? '' : NEXT_KEY)
      assert.equal(f.state.readProgress.value, null)
      assert.equal(f.state.lastReadPage.value, null)
      assert.equal(f.state.error.value, newError)
      assert.equal(f.state.busy.value, '')
      assert.ok(!f.state.warnings.value.includes('Synthetic stale warning'))
      if (changed === 'initialPostKey') assert.equal(f.state.coverage.value, 'COMPLETE')
      assert.equal(f.postCalls().length, 1)
      assert.equal(f.timers.length, 0)
    })
  }
}

for (const failure of ['HTTP 401', 'HTTP 500', 'timeout']) {
  test(`${failure} from the page POST pauses without retrying or claiming saved results`, async t => {
    const f = await setup(t)
    const error = failure === 'timeout' ? new Error('Synthetic response timeout') : new ApiError(`Synthetic ${failure}`, Number(failure.slice(5)))
    f.expect(request(DETAIL_PATH(KEY), detail()), comments(error, '900'))

    await f.state.readComments()
    await flush()

    assert.equal(f.postCalls().length, 1)
    assert.deepEqual(f.state.readProgress.value, { pages: 0, messages: 0, media: 0 })
    assert.equal(f.state.lastReadPage.value, null)
    assert.equal(f.state.remoteState.value.nextCursor, '900')
    assert.equal(f.state.busy.value, '')
    assert.equal(f.state.notice.value, '')
    if (failure === 'timeout') assert.match(f.state.error.value, /当前页可能仍在后台保存.*核对已保存位置/)
    else assert.equal(f.state.error.value, error.message)
    assert.equal(f.events.length, failure === 'HTTP 401' ? 1 : 0)
    if (f.events.length) assert.deepEqual(f.events[0], ['accessError', error])
    assert.equal(f.timers.length, 0)
  })
}
