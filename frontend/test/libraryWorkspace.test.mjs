import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as library from '../src/library.ts'
import * as postCardContent from '../src/postCardContent.ts'

const parsed = parse(readFileSync(new URL('../src/components/LibraryWorkspace.vue', import.meta.url), 'utf8'), { filename: 'LibraryWorkspace.vue' })
assert.deepEqual(parsed.errors, [])
const script = compileScript(parsed.descriptor, { id: 'library-workspace-ui' })
const template = compileTemplate({ source: parsed.descriptor.template.content, filename: 'LibraryWorkspace.vue', id: 'library-workspace-ui',
  compilerOptions: { bindingMetadata: script.bindings, expressionPlugins: ['typescript'] } })
assert.deepEqual(template.errors, [])
const transpile = value => ts.transpileModule(value, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const code = transpile(script.content), renderCode = transpile(template.code)
const flush = async () => { await vue.nextTick(); await new Promise(setImmediate) }
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes }); return { promise, resolve } }
class ApiError extends Error { constructor(message, status) { super(message); this.status = status } }
class Element {
  isConnected = true
  focused = 0
  focus(options) { this.focused++; this.focusOptions = options }
}
const channel = { id: '-1009007199254740993', title: '合成频道', indexedPosts: 42 }
const item = (key = 'one', overrides = {}) => ({ post: { key, accountId: '1', channelId: channel.id, channelTitle: channel.title, title: '真实标题 ' + key,
  text: '真实标题 ' + key + '\n这是一段正文', mainMedia: [], coverage: 'PARTIAL', mainMessages: [], warnings: [], ...overrides.post },
  tags: ['旅行'], indexedCommentMessages: 3, indexedCommentMedia: 5, updatedAt: 1780000000000, publishedAt: 1700000000000,
  mediaTypeCounts: { VIDEO: 3, PHOTO: 2, AUDIO: 0, DOCUMENT: 0, OTHER: 0 }, ...overrides })
const page = (overrides = {}) => ({ items: [item()], limit: 20, offset: 0, total: 42, indexedPosts: 42, indexedChannels: 1,
  hasMore: true, scope: 'INDEXED_ONLY', facets: { tags: [{ tag: '旅行', count: 42 }, { tag: '北京', count: 13 }] }, ...overrides })
const path = (extra = {}) => {
  const { channelIds = [], ...parameters } = extra
  const query = new URLSearchParams({ tags: '', keywords: '', limit: '20', offset: '0', sort: 'published-desc', ...parameters })
  channelIds.forEach(id => query.append('channelIds', id)); return '/library/catalog?' + query
}
function mount(t, initial = [{ path: '/library/channels', value: [channel] }, { path: path(), value: page() }], initialProps = {}) {
  const steps = [...initial], calls = [], failures = [], events = [], hooks = [], stubs = new Map()
  const api = async (url, options = {}) => {
    calls.push({ path: url, options }); const expected = steps.shift()
    try { assert.ok(expected, 'Unexpected request ' + url); assert.equal(url, expected.path); assert.equal(options.method ?? 'GET', expected.method ?? 'GET'); if (expected.body) assert.deepEqual(options.body, expected.body) }
    catch (cause) { failures.push(cause); throw cause }
    if (expected.value instanceof Error) throw expected.value
    return await expected.value
  }
  const modules = { vue: { ...vue, onBeforeUnmount: fn => hooks.push(fn), withDirectives: node => node }, '../api': { api, ApiError }, '../library': library, '../postCardContent': postCardContent }
  const requireModule = name => {
    if (name.endsWith('.vue')) { if (!stubs.has(name)) stubs.set(name, { __name: name.split('/').at(-1).slice(0, -4) }); return { __esModule: true, default: stubs.get(name) } }
    assert.ok(Object.hasOwn(modules, name), name); return modules[name]
  }
  const module = { exports: {} }, rendered = { exports: {} }
  new Function('require', 'module', 'exports', 'HTMLElement', 'document', code)(requireModule, module, module.exports, Element, { activeElement: null })
  new Function('require', 'module', 'exports', renderCode)(requireModule, rendered, rendered.exports)
  const props = vue.reactive({ authorized: true, available: true, accountId: '1', ...initialProps })
  const scope = vue.effectScope(), state = scope.run(() => module.exports.default.setup(props, { expose() {}, emit: (...args) => events.push(args) }))
  let disposed = false
  const destroy = () => { if (disposed) return; disposed = true; hooks.forEach(fn => fn()); scope.stop() }
  t.after(() => { destroy(); assert.deepEqual(failures, []); assert.equal(steps.length, 0) })
  return { props, state, calls, events, destroy, expect: (...next) => steps.push(...next), render: () => rendered.exports.render({}, [], props, vue.proxyRefs(state), {}, {}) }
}
function nodes(node) {
  if (Array.isArray(node)) return node.flatMap(nodes)
  return node && typeof node === 'object' ? [node, ...nodes(node.children)] : []
}
const text = node => typeof node === 'string' ? node : Array.isArray(node) ? node.map(text).join('') : node && typeof node === 'object' ? text(node.children) : ''
const byClass = (tree, name) => nodes(tree).filter(node => String(node.props?.class ?? '').split(' ').includes(name))

test('catalog defaults to server publication order and uses full-channel facets and type counts', async t => {
  const f = mount(t); await flush()
  assert.equal(f.state.sort.value, 'published-desc')
  assert.deepEqual(f.state.tagFacets.value, [{ tag: '旅行', count: 42 }, { tag: '北京', count: 13 }])
  const tree = f.render(), card = byClass(tree, 'library-post-card')[0]
  assert.match(text(card), /视频 3/); assert.match(text(card), /图片 2/); assert.match(text(card), /5 个媒体/)
  assert.match(text(byClass(tree, 'library-tag-facets')[0]), /北京13/)
  assert.equal(nodes(card).find(node => node.type?.__name === 'LibraryPostPreview').props.lazy, '')
  assert.equal(f.calls.length, 2)
})
test('tag chips combine with AND without silently applying an edited keyword draft', async t => {
  const f = mount(t); await flush()
  f.state.appliedKeywords.value = '周末'; f.state.keywordsInput.value = '周末 新输入'
  f.expect({ path: path({ tags: '旅行', keywords: '周末' }), value: page() })
  f.state.toggleTag('旅行'); await flush()
  assert.equal(f.state.appliedTags.value, '旅行'); assert.equal(f.state.keywordsInput.value, '周末 新输入')
  assert.equal(f.state.searchPending.value, true)
  f.expect({ path: path({ tags: '旅行 北京', keywords: '周末' }), value: page() })
  f.state.toggleTag('北京'); await flush()
  assert.deepEqual(f.state.selectedTagTerms.value, ['旅行', '北京'])
  f.expect({ path: path({ tags: '北京', keywords: '周末' }), value: page() })
  f.state.toggleTag('#旅行'); await flush(); assert.equal(f.state.appliedTags.value, '北京')
})
test('channel multiselect refreshes facets and keeps applied search and server sort', async t => {
  const f = mount(t); await flush(); f.state.appliedTags.value = f.state.tagsInput.value = '旅行'
  f.state.appliedKeywords.value = f.state.keywordsInput.value = '周末'; f.state.sort.value = 'title-asc'
  f.expect({ path: path({ tags: '旅行', keywords: '周末', sort: 'title-asc', channelIds: [channel.id] }), value: page({ facets: { tags: [{ tag: '北京', count: 7 }] } }) })
  f.state.toggleChannel(channel.id); await flush()
  assert.deepEqual(f.state.selectedChannels.value, [channel.id]); assert.equal(f.state.selectedChannelLabel.value, channel.title)
  assert.ok(f.state.tagFacets.value.some(facet => facet.tag === '旅行'), 'Selected tags remain removable even when absent from the new facet list')
  assert.equal(f.state.tagFacets.value.find(facet => facet.tag === '北京').count, 7)
})
test('sort changes reset pagination and preserve server ordering rather than sorting a page locally', async t => {
  const f = mount(t); await flush(); f.state.offset.value = 40
  const ordered = [item('z'), item('a')]
  f.expect({ path: path({ sort: 'title-asc' }), value: page({ items: ordered }) })
  f.state.changeSort({ target: { value: 'title-asc' } }); await flush()
  assert.equal(f.state.offset.value, 0); assert.deepEqual(f.state.catalog.value.items.map(value => value.post.key), ['z', 'a'])
  f.expect({ path: path({ sort: 'title-asc', offset: '20' }), value: page({ offset: 20 }) })
  await f.state.loadCatalog(20); assert.equal(f.state.offset.value, 20)
  f.state.changeSort({ target: { value: 'unsupported' } }); assert.equal(f.calls.length, 4)
})
test('opening and closing the wide reader preserves the grid, filters, view and paging without refetching', async t => {
  const f = mount(t); await flush(); const original = f.state.catalog.value, target = new Element()
  f.state.view.value = 'list'; f.state.tagsInput.value = '未提交'; f.state.offset.value = 40
  f.state.openPost('one', { currentTarget: target }); await flush()
  let tree = f.render()
  assert.equal(nodes(tree).some(node => node.type === 'dialog'), false)
  assert.equal(byClass(tree, 'library-reader-page').length, 1)
  assert.equal(byClass(tree, 'library-post-card').length, 1)
  assert.equal(nodes(tree).find(node => node.type?.__name === 'LibraryPostDetail').props['post-key'], 'one')
  assert.ok(byClass(tree, 'library-post-card')[0].props.class.includes('is-selected'))
  f.state.showCatalogView('cards'); await flush(); assert.equal(f.state.selectedPostKey.value, 'one')
  f.state.closePost(); await flush()
  assert.equal(byClass(f.render(), 'library-reader-page').length, 0); assert.equal(target.focused, 1); assert.deepEqual(target.focusOptions, { preventScroll: true })
  assert.equal(f.state.catalog.value, original); assert.equal(f.state.tagsInput.value, '未提交'); assert.equal(f.state.offset.value, 40)
  assert.equal(f.calls.length, 2); assert.equal(byClass(f.render(), 'library-post-card').length, 1)
})
test('reader returns through its navigation without modal or duplicate probe navigation', async t => {
  const f = mount(t); await flush()
  f.state.openPost('one'); await flush()
  assert.equal(nodes(f.render()).some(node => node.type === 'dialog' || node.type?.__name === 'LibraryProbe'), false)
  const detail = nodes(f.render()).find(node => node.type?.__name === 'LibraryPostDetail')
  assert.equal(detail.props.active, true)
  detail.props.onClose(); await flush(); assert.equal(f.state.selectedPostKey.value, '')
  assert.equal(f.state.mode.value, 'catalog'); assert.equal(f.calls.length, 2)
})
test('refresh loading and failure keep the last grid and opened detail instead of replacing them', async t => {
  const f = mount(t); await flush(); const pending = deferred(), original = f.state.catalog.value
  f.state.openPost('one')
  f.expect({ path: '/library/channels', value: [channel] }, { path: path(), value: pending.promise })
  const refreshing = f.state.refreshCatalog(); await flush()
  assert.equal(byClass(f.render(), 'library-post-card').length, 1); assert.equal(byClass(f.render(), 'library-loading').length, 0)
  pending.resolve(Promise.reject(new Error('合成读取失败'))); await refreshing; await flush()
  assert.equal(f.state.catalog.value, original); assert.equal(f.state.selectedPostKey.value, 'one')
  assert.match(text(byClass(f.render(), 'library-error')[0]), /保留上次成功/)
  assert.equal(byClass(f.render(), 'library-post-card').length, 1)
})
test('publication dates remain unknown for old indexes even when update time is present', async t => {
  const f = mount(t); await flush()
  const unknown = item('old', { publishedAt: null })
  f.state.catalog.value.items = [unknown]
  const card = byClass(f.render(), 'library-post-card')[0]
  assert.match(text(card), /发布时间未知/); assert.equal(nodes(card).find(node => node.type === 'time').props.datetime, undefined)
  assert.equal(f.state.publishedTime(unknown), null)
  assert.match(f.state.dateLabel(1700000000000), /2023/)
})
test('an account change clears the drawer and filters and rejects a late previous-account catalog', async t => {
  const pending = deferred(), f = mount(t, [{ path: '/library/channels', value: [channel] }, { path: path(), value: pending.promise }])
  f.state.selectedPostKey.value = 'old'; f.state.tagsInput.value = f.state.appliedTags.value = '旧账号'
  f.expect({ path: '/library/channels', value: [] }, { path: path(), value: page({ items: [], total: 0, facets: { tags: [] } }) })
  f.props.accountId = '2'; await flush()
  pending.resolve(page({ items: [item('private-old-account')] })); await flush()
  assert.equal(f.state.selectedPostKey.value, ''); assert.equal(f.state.appliedTags.value, '')
  assert.equal(f.state.catalog.value.items.length, 0); assert.deepEqual(f.state.tagFacets.value, [])
})
test('authorization errors propagate once and disconnected empty state does not offer account data', async t => {
  const f = mount(t, [], { authorized: false }); await flush()
  assert.equal(f.calls.length, 0); assert.match(text(f.render()), /连接后浏览自己的资源/)
  assert.equal(byClass(f.render(), 'library-state').length, 1)
  f.expect({ path: '/library/channels', value: [channel] }, { path: path(), value: new ApiError('令牌过期', 401) })
  f.props.authorized = true; await flush()
  assert.equal(f.events.length, 1); assert.equal(f.events[0][0], 'accessError')
  assert.match(text(byClass(f.render(), 'library-error')[0]), /令牌过期/)
})
test('selected tags outside the first facet group remain visible and Unicode variants toggle once', async t => {
  const facets = Array.from({ length: 20 }, (_, index) => ({ tag: '标签' + index, count: 30 - index }))
  const f = mount(t, [{ path: '/library/channels', value: [channel] }, { path: path(), value: page({ facets: { tags: facets } }) }]); await flush()
  f.state.appliedTags.value = '标签19 Ｔａｇ'
  assert.equal(f.state.tagFacets.value.length, 14); assert.ok(f.state.tagFacets.value.some(facet => facet.tag === '标签19'))
  assert.equal(f.state.tagSelected('#tag'), true)
  f.expect({ path: path({ tags: '标签19' }), value: page() }); f.state.toggleTag('tag'); await flush()
  assert.equal(f.state.appliedTags.value, '标签19')
  assert.deepEqual(library.libraryTagTerms('#旅行,旅行 Ｔａｇ tag，'), ['旅行', 'tag'])
})
test('updates stay in the reader and refresh the directory once when returning', async t => {
  const f = mount(t); await flush(); f.state.openPost('one')
  const detail = nodes(f.render()).find(node => node.type?.__name === 'LibraryPostDetail')
  detail.props.onUpdate(); await flush()
  assert.equal(f.state.mode.value, 'catalog'); assert.equal(f.state.selectedPostKey.value, 'one')
  assert.equal(f.calls.length, 2)
  detail.props.onUpdate(); detail.props.onUpdate()
  f.expect({ path: '/library/channels', value: [channel] }, { path: path(), value: page({ items: [item('one', { indexedCommentMedia: 20 })] }) })
  f.state.closePost(); await flush()
  assert.equal(f.state.catalog.value.items[0].indexedCommentMedia, 20); assert.equal(f.calls.length, 4)
})

test('adding a post from the directory opens the same reader without a separate maintenance page', async t => {
  const f = mount(t); await flush(); f.state.showAddPost(); f.state.postLink.value = 'https://t.me/synthetic/123'
  assert.equal(byClass(f.render(), 'library-add-post').length, 1)
  f.expect({ path: '/library/probe', method: 'POST', body: { link: 'https://t.me/synthetic/123' }, value: { post: item('new').post } },
    { path: '/library/channels', value: [channel] }, { path: path(), value: page({ items: [item('new')] }) })
  await f.state.addPost(); await flush()
  assert.equal(f.state.addingPost.value, false); assert.equal(f.state.mode.value, 'catalog'); assert.equal(f.state.selectedPostKey.value, 'new')
  assert.equal(nodes(f.render()).some(node => node.type?.__name === 'LibraryProbe'), false)
  assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 1)
})

test('a late add-post response cannot open data from the previous account', async t => {
  const f = mount(t), pending = deferred(); await flush(); f.state.showAddPost(); f.state.postLink.value = 'https://t.me/synthetic/123'
  f.expect({ path: '/library/probe', method: 'POST', value: pending.promise })
  const adding = f.state.addPost(); await flush()
  f.expect({ path: '/library/channels', value: [] }, { path: path(), value: page({ items: [], total: 0 }) })
  f.props.accountId = '2'; await flush(); pending.resolve({ post: item('old').post }); await adding; await flush()
  assert.equal(f.state.selectedPostKey.value, ''); assert.equal(f.state.postLink.value, ''); assert.equal(f.state.catalog.value.items.length, 0)
})
