import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as library from '../src/library.ts'
import { runCommentRead } from '../src/commentReadRunner.ts'

const descriptor = parse(readFileSync(new URL('../src/components/LibraryPostDetail.vue', import.meta.url), 'utf8'), { filename: 'LibraryPostDetail.vue' }).descriptor
const script = compileScript(descriptor, { id: 'library-detail-reader' })
const template = compileTemplate({ source: descriptor.template.content, filename: 'LibraryPostDetail.vue', id: 'library-detail-reader', compilerOptions: { bindingMetadata: script.bindings, expressionPlugins: ['typescript'] } })
assert.deepEqual(template.errors, [])
const transpile = value => ts.transpileModule(value, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const code = transpile(script.content), renderCode = transpile(template.code)
const KEY = '1:-1001:100'
const base = key => '/library/posts/' + encodeURIComponent(key)
const saved = (key = KEY) => base(key) + '?limit=1&offset=0'
const mediaPath = (key = KEY) => base(key) + '/media?includeMain=false&limit=50&offset=0'
const media = (id = 1, source = 'COMMENT') => ({ key: 'media-' + id, source, type: 'PHOTO', fileName: 'photo-' + id + '.jpg', size: 37, caption: '评论说明 ' + id, message: { chatId: '-2001', messageId: String(id), link: 'https://t.me/synthetic/' + id } })
const detail = (overrides = {}, post = {}) => ({ post: { key: KEY, accountId: '1', channelId: '-1001', channelTitle: '合成频道', mainMessageId: '100', title: '主帖标题', text: '主帖简要内容', mainMessages: [{ chatId: '-1001', messageId: '100', link: 'https://t.me/synthetic/100' }], mainMedia: [media(100, 'MAIN')], discussionRoots: [], canReadComments: true, coverage: 'PARTIAL', mainCoverage: 'COMPLETE', warnings: [], ...post }, nextCursor: '900', exhausted: false, coverage: 'PARTIAL', mainCoverage: 'COMPLETE', commentCoverage: 'PARTIAL', warnings: [], commentWarnings: [], indexedCommentMessages: 12, indexedCommentMedia: 1, commentMedia: [], ...overrides })
const mediaPage = (items = [media()], extra = {}) => ({ items, offset: 0, limit: 50, total: items.length, hasMore: false, mainMediaCount: 0, commentMediaCount: items.length, scope: 'INDEXED_ONLY', ...extra })
const page = (cursor, extra = {}) => ({ postKey: KEY, media: [media(2)], messages: [], messageCount: 2, nextCursor: cursor, exhausted: false, coverage: 'PARTIAL', commentCoverage: 'PARTIAL', warnings: [], ...extra })
const step = (path, value, method = 'GET', body) => ({ path, value, method, body })
const comments = (value, cursor) => step(base(KEY) + '/comments', value, 'POST', { cursor })
const local = (value = detail(), items = [media()]) => [step(saved(), value), step(mediaPath(), mediaPage(items))]
const flush = async () => { await vue.nextTick(); await new Promise(setImmediate) }
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
class ApiError extends Error { constructor(message, status) { super(message); this.status = status } }
function setup(t, initial = detail(), overrides = {}) {
  const steps = overrides.active === false ? [] : local(initial), calls = [], failures = [], events = [], hooks = [], timers = [], stubs = new Map()
  const api = async (path, options = {}) => {
    const call = { path, method: options.method ?? 'GET', body: options.body, timeout: options.timeout }; calls.push(call)
    const expected = steps.shift()
    try { assert.ok(expected, 'Unexpected ' + call.method + ' ' + path); assert.equal(path, expected.path); assert.equal(call.method, expected.method); assert.deepEqual(call.body, expected.body) }
    catch (cause) { failures.push(cause); throw cause }
    if (expected.value instanceof Error) throw expected.value
    return expected.value
  }
  const modules = { vue: { ...vue, onBeforeUnmount: callback => hooks.push(callback), withCtx: callback => callback, withDirectives: node => node }, '../api': { api, ApiError }, '../library': library, '../commentReadRunner': { runCommentRead } }
  const requireModule = name => {
    if (name.endsWith('.vue')) { if (!stubs.has(name)) stubs.set(name, { __name: name.split('/').at(-1).slice(0, -4) }); return { __esModule: true, default: stubs.get(name) } }
    assert.ok(Object.hasOwn(modules, name), name); return modules[name]
  }
  const module = { exports: {} }, rendered = { exports: {} }
  new Function('require', 'module', 'exports', 'window', code)(requireModule, module, module.exports, { setTimeout: callback => { timers.push(callback); return timers.length } })
  new Function('require', 'module', 'exports', renderCode)(requireModule, rendered, rendered.exports)
  const props = vue.reactive({ postKey: KEY, accountId: '1', authorized: true, available: true, active: true, ...overrides })
  const scope = vue.effectScope(), state = scope.run(() => module.exports.default.setup(props, { expose() {}, emit: (...event) => events.push(event) }))
  let disposed = false
  const destroy = () => { if (disposed) return; disposed = true; hooks.forEach(callback => callback()); scope.stop() }
  t.after(() => { destroy(); assert.deepEqual(failures, []); assert.equal(steps.length, 0, 'All expected requests must be consumed') })
  return { props, state, calls, events, timers, destroy, expect: (...next) => steps.push(...next), postCalls: () => calls.filter(call => call.method === 'POST'), render: () => rendered.exports.render({}, [], props, vue.proxyRefs(state), {}, {}) }
}
function nodes(node) { return Array.isArray(node) ? node.flatMap(nodes) : node && typeof node === 'object' ? [node, ...nodes(node.children)] : [] }
const text = node => typeof node === 'string' ? node : Array.isArray(node) ? node.map(text).join('') : node && typeof node === 'object' ? text(node.children) : ''
const classHas = (node, name) => String(node.props?.class ?? '').split(' ').includes(name)

test('details open read-only with large comment cards first, folded main attachments and secondary reading details', async t => {
  const initial = detail({ warnings: ['合成范围提示'] }), f = setup(t, initial); await flush()
  assert.equal(f.state.mediaView.value, 'cards'); assert.equal(f.postCalls().length, 0); assert.equal(f.calls.length, 2)
  const tree = f.render(), all = nodes(tree), collections = all.filter(node => node.type?.__name === 'MediaCollection')
  assert.equal(collections[0].props.layout, 'cards'); assert.equal(collections[0].props.items[0].source, 'COMMENT')
  assert.equal(collections[1].props.items[0].source, 'MAIN')
  const main = all.find(node => classHas(node, 'main-source-group')), reading = all.find(node => classHas(node, 'reading-details'))
  assert.equal(main.type, 'details'); assert.equal(main.props.open, undefined); assert.equal(reading.type, 'details'); assert.equal(reading.props.open, undefined)
  assert.ok(all.indexOf(all.find(node => classHas(node, 'detail-comments'))) < all.indexOf(main))
  assert.match(text(reading), /合成范围提示/); assert.doesNotMatch(text(all.find(node => classHas(node, 'detail-overview'))), /合成范围提示/)
  assert.ok(all.some(node => node.type === 'a' && node.props.href === 'https://t.me/synthetic/100'))
  assert.doesNotMatch(text(tree), /下载|添加.*更新主帖|账号 ID|稳定标识/)
  assert.deepEqual(f.events, [])
})

test('the compact header keeps the title once, folds main text and leaves comment actions and filters directly reachable', async t => {
  const f = setup(t, detail({ commentCoverage: 'COMPLETE', exhausted: true, nextCursor: null })); await flush()
  const all = nodes(f.render()), header = all.find(node => node.type === 'header'), overview = all.find(node => classHas(node, 'detail-overview'))
  const heading = all.find(node => classHas(node, 'comments-heading')), reading = all.find(node => classHas(node, 'reading-details'))
  assert.equal(all.filter(node => node.type === 'h1').length, 1); assert.match(text(header), /主帖标题.*合成频道/)
  assert.equal(overview.type, 'details'); assert.equal(overview.props.open, undefined); assert.match(text(nodes(overview).find(node => node.type === 'summary')), /主帖信息/)
  assert.match(text(overview), /主帖简要内容.*更新主帖/); assert.doesNotMatch(text(header), /主帖简要内容/)
  assert.ok(nodes(heading).some(node => node.type === 'button' && text(node) === '从头更新评论' && node.props.disabled === false))
  assert.ok(nodes(heading).some(node => node.type?.__name === 'MediaViewSwitch'))
  assert.ok(all.some(node => node.type === 'fieldset' && node.props['aria-label'] === '评论媒体类型'))
  assert.doesNotMatch(text(heading), /评论与回复|本轮评论已读完|条评论消息/); assert.match(text(reading), /本轮评论已读完.*12 条评论消息/)
  assert.equal(f.calls.length, 2); assert.equal(f.postCalls().length, 0)
})

test('updating the main post stays in this reader and notifies the catalog after the saved receipt', async t => {
  const f = setup(t); await flush(); const pending = deferred(), before = f.state.loadedMedia.value
  f.expect(step(base(KEY) + '/refresh', pending.promise, 'POST', {}), step(mediaPath(), mediaPage()))
  const running = f.state.refreshPost(); assert.equal(f.state.loadedMedia.value, before); assert.equal(f.events.length, 0)
  pending.resolve(detail({}, { title: '主帖新标题' })); await running
  assert.equal(f.state.detail.value.post.title, '主帖新标题'); assert.deepEqual(f.events, [['update']]); assert.equal(f.state.remoteBusy.value, '')
  assert.equal(f.postCalls()[0].timeout, 60000); assert.ok(f.calls.every(call => call.path.startsWith(base(KEY))))
})

test('from-head comments read sequentially to real EOF and refresh saved media after each page', async t => {
  const f = setup(t); await flush()
  f.expect(step(saved(), detail()), comments(page('90071992547409930000'), null), ...local(detail({ nextCursor: '90071992547409930000', indexedCommentMedia: 2 }), [media(), media(2)]))
  const reading = f.state.readComments(true); await flush()
  assert.equal(f.postCalls().length, 1); assert.equal(f.timers.length, 1); assert.equal(f.state.loadedMedia.value.length, 2)
  f.expect(comments(page(null, { exhausted: true, coverage: 'COMPLETE', commentCoverage: 'COMPLETE', media: [media(3)] }), '90071992547409930000'), ...local(detail({ nextCursor: null, exhausted: true, coverage: 'COMPLETE', commentCoverage: 'COMPLETE', indexedCommentMedia: 3 }), [media(), media(2), media(3)]))
  f.timers.shift()(); await reading
  assert.deepEqual(f.state.readProgress.value, { pages: 2, messages: 4, media: 2 }); assert.equal(f.state.loadedMedia.value.length, 3)
  assert.equal(f.state.detail.value.exhausted, true); assert.match(f.state.notice.value, /已读到边界/)
  assert.deepEqual(f.events, [['update'], ['update']]); assert.equal(f.postCalls().length, 2)
})

test('continue always uses the latest persisted cursor as an exact string', async t => {
  const f = setup(t); await flush(); const cursor = '90071992547409931234'
  f.expect(step(saved(), detail({ nextCursor: cursor })), comments(page(null, { exhausted: true }), cursor), ...local(detail({ nextCursor: null, exhausted: true })))
  await f.state.readComments(false)
  assert.equal(f.postCalls()[0].body.cursor, cursor); assert.equal(f.postCalls().length, 1)
})

test('Stop preserves an in-flight saved page and prevents any next request', async t => {
  const f = setup(t); await flush(); const pending = deferred()
  f.expect(step(saved(), detail()), comments(pending.promise, '900'), ...local(detail({ nextCursor: '800' }), [media(), media(2)]))
  const reading = f.state.readComments(); await flush(); f.state.stopReading()
  pending.resolve(page('800')); await reading
  assert.equal(f.state.loadedMedia.value.length, 2); assert.equal(f.state.detail.value.nextCursor, '800')
  assert.deepEqual(f.state.readProgress.value, { pages: 1, messages: 2, media: 1 }); assert.match(f.state.notice.value, /已停止后续读取/)
  assert.equal(f.postCalls().length, 1); assert.equal(f.timers.length, 0); assert.equal(f.state.needsCheck.value, false)
})

test('Stop during the preflight GET sends no comment page', async t => {
  const f = setup(t); await flush(); const pending = deferred()
  f.expect(step(saved(), pending.promise)); const reading = f.state.readComments(); await flush(); f.state.stopReading()
  pending.resolve(detail()); await reading
  assert.equal(f.postCalls().length, 0); assert.equal(f.state.readProgress.value.pages, 0); assert.equal(f.state.loadedMedia.value.length, 1)
})

test('unknown POST outcomes preserve old media and require a GET-only check before explicit continuation', async t => {
  const f = setup(t); await flush(); const old = f.state.loadedMedia.value
  f.expect(step(saved(), detail()), comments(new Error('合成请求超时'), '900'))
  await f.state.readComments()
  assert.equal(f.state.needsCheck.value, true); assert.equal(f.state.loadedMedia.value, old); assert.match(f.state.readError.value, /不会自动重新提交/)
  await f.state.readComments(); await f.state.refreshPost(); assert.equal(f.postCalls().length, 1)
  f.expect(...local(detail({ nextCursor: '700' }), [media(), media(2)])); await f.state.refreshLocal()
  assert.equal(f.state.needsCheck.value, false); assert.equal(f.state.detail.value.nextCursor, '700'); assert.equal(f.postCalls().length, 1)
  await flush(); assert.equal(f.postCalls().length, 1)
  f.expect(step(saved(), detail({ nextCursor: '600' })), comments(page(null, { exhausted: true }), '600'), ...local(detail({ exhausted: true, nextCursor: null })))
  await f.state.readComments(); assert.equal(f.postCalls().length, 2)
})

test('an unconfirmed main refresh likewise permits only saved-data checking and retains both media groups', async t => {
  const f = setup(t); await flush(); const before = f.state.detail.value
  f.expect(step(base(KEY) + '/refresh', new ApiError('合成服务错误', 503), 'POST', {})); await f.state.refreshPost()
  assert.equal(f.state.needsCheck.value, true); assert.equal(f.state.detail.value, before); assert.equal(f.state.mainPageItems.value.length, 1)
  await f.state.refreshPost(); await f.state.readComments(true); assert.equal(f.postCalls().length, 1)
  f.expect(...local()); await f.state.refreshLocal(); assert.equal(f.postCalls().length, 1)
})

test('a saved PARTIAL page remains visible when its local list refresh fails, and no later page is submitted', async t => {
  const f = setup(t, detail({ coverage: 'COMPLETE', commentCoverage: 'COMPLETE', exhausted: true, nextCursor: null })); await flush()
  f.expect(step(saved(), f.state.detail.value), comments(page('800', { warnings: ['合成未完整读取提示'] }), null), step(saved(), new Error('合成本机列表错误')), step(mediaPath(), new Error('合成媒体错误')))
  await f.state.readComments(true)
  assert.equal(f.state.needsCheck.value, true); assert.equal(f.state.commentCoverage.value, 'PARTIAL'); assert.equal(f.state.detail.value.coverage, 'PARTIAL')
  assert.equal(f.state.detail.value.indexedCommentMedia, undefined); assert.equal(f.state.loadedMedia.value.length, 1)
  assert.ok(f.state.warnings.value.includes('合成未完整读取提示')); assert.equal(f.postCalls().length, 1); assert.equal(f.timers.length, 0)
})

for (const property of ['active', 'available']) test(`${property} loss stops paging, keeps saved content and resumes with GETs only`, async t => {
  const f = setup(t); await flush(); const pending = deferred(), old = f.state.detail.value
  f.expect(step(saved(), detail()), comments(pending.promise, '900')); const reading = f.state.readComments(); await flush()
  f.props[property] = false; await flush(); assert.equal(f.state.needsCheck.value, true)
  pending.resolve(page('800')); await reading
  assert.equal(f.state.detail.value.nextCursor, old.nextCursor); assert.equal(f.state.loadedMedia.value.length, 1); assert.equal(f.events.length, 0)
  f.expect(...local(detail({ nextCursor: '800' }), [media(), media(2)])); f.props[property] = true; await flush()
  assert.equal(f.postCalls().length, 1); assert.equal(f.state.needsCheck.value, true); assert.equal(f.state.loadedMedia.value.length, 2)
})

test('switching account and post discards a late page without exposing the previous account', async t => {
  const f = setup(t); await flush(); const pending = deferred(), otherKey = '2:-1001:200', other = detail({}, { key: otherKey, accountId: '2', title: '另一账号主帖' })
  f.expect(step(saved(), detail()), comments(pending.promise, '900')); const reading = f.state.readComments(); await flush()
  f.expect(step(saved(otherKey), other), step(mediaPath(otherKey), mediaPage([]))); f.props.accountId = '2'; f.props.postKey = otherKey; await flush()
  pending.resolve(page('800')); await reading
  assert.equal(f.state.detail.value.post.accountId, '2'); assert.equal(f.state.detail.value.post.key, otherKey); assert.equal(f.state.loadedMedia.value.length, 0); assert.equal(f.events.length, 0)
})

test('unmount discards an in-flight comment receipt and never performs a follow-up GET or POST', async t => {
  const f = setup(t); await flush(); const pending = deferred()
  f.expect(step(saved(), detail()), comments(pending.promise, '900')); const reading = f.state.readComments(); await flush(); f.destroy()
  pending.resolve(page('800')); await reading; assert.equal(f.postCalls().length, 1); assert.equal(f.events.length, 0)
})

test('malformed page receipts stop at the original saved media and require explicit checking', async t => {
  const f = setup(t); await flush()
  f.expect(step(saved(), detail()), comments(page('800', { postKey: 'other' }), '900')); await f.state.readComments()
  assert.equal(f.state.needsCheck.value, true); assert.equal(f.state.loadedMedia.value.length, 1); assert.equal(f.state.lastReadPage.value, null); assert.equal(f.events.length, 0)
})

test('inactive details perform no reads until shown and never automatically update Telegram', async t => {
  const f = setup(t, detail(), { active: false }); await flush(); assert.equal(f.calls.length, 0)
  f.expect(...local()); f.props.active = true; await flush(); assert.equal(f.calls.length, 2); assert.equal(f.postCalls().length, 0)
})
