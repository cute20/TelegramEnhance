import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as library from '../src/library.ts'
import * as cardContent from '../src/postCardContent.ts'

test('card projection shows identical title, text and tag only once without mutating indexed content', () => {
  const post = { title: '#晴小兔', text: '#晴小兔', tags: ['晴小兔'] }, original = structuredClone(post)
  assert.deepEqual(cardContent.postCardContent(post.title, post.text, post.tags), { title: '#晴小兔', excerpt: '', tags: [], hasSourceTags: true })
  assert.deepEqual(post, original)
})
test('card projection preserves distinct body and tags, and matches only complete Unicode hashtags', () => {
  assert.deepEqual(cardContent.postCardContent('#晴小兔', '#晴小兔\r\n这是正文 #旅行', ['晴小兔', '旅行', '晴小兔子']),
    { title: '#晴小兔', excerpt: '这是正文 #旅行', tags: ['#晴小兔子'], hasSourceTags: true })
  assert.equal(cardContent.postCardContent('不同标题', '#晴小兔 正文', ['晴小兔']).excerpt, '#晴小兔 正文')
  assert.equal(cardContent.postCardContent('标题', '标题延伸的正文', []).excerpt, '标题延伸的正文')
  assert.deepEqual(cardContent.postCardContent('#Ｔａｇ', '#Tag', ['tag', 'ＴＡＧ', '#other', 'other']).tags, ['#other'])
  assert.equal(cardContent.postCardContent('TEST', 'test', []).excerpt, 'test', 'Distinct case in prose is retained')
  assert.deepEqual(cardContent.postCardContent('', '', []), { title: '未命名主帖', excerpt: '', tags: [], hasSourceTags: false })
})

const { descriptor, errors } = parse(readFileSync(new URL('../src/components/LibraryWorkspace.vue', import.meta.url), 'utf8'), { filename: 'LibraryWorkspace.vue' })
assert.deepEqual(errors, [])
const script = compileScript(descriptor, { id: 'card-display-test' })
const template = compileTemplate({ source: descriptor.template.content, filename: 'LibraryWorkspace.vue', id: 'card-display-test',
  compilerOptions: { bindingMetadata: script.bindings, expressionPlugins: ['typescript'] } })
assert.deepEqual(template.errors, [])
const transpile = source => ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
function mount(t) {
  const hooks = [], calls = [], modules = { vue: { ...vue, onBeforeUnmount: hook => hooks.push(hook), withDirectives: node => node }, '../library': library,
    '../postCardContent': cardContent, '../api': { ApiError: class extends Error {}, api: (...args) => { calls.push(args); throw new Error('Display must not request data') } } }
  const requireModule = name => name.endsWith('.vue') ? { __esModule: true, default: { __name: name } } : modules[name]
  const scriptModule = { exports: {} }, renderModule = { exports: {} }
  new Function('require', 'module', 'exports', transpile(script.content))(requireModule, scriptModule, scriptModule.exports)
  new Function('require', 'module', 'exports', transpile(template.code))(requireModule, renderModule, renderModule.exports)
  const props = vue.reactive({ authorized: false, available: false, accountId: '1' }), scope = vue.effectScope()
  const state = scope.run(() => scriptModule.exports.default.setup(props, { expose() {}, emit() {} }))
  t.after(() => { hooks.forEach(hook => hook()); scope.stop(); assert.equal(calls.length, 0) })
  return { state, render: () => renderModule.exports.render({}, [], props, vue.proxyRefs(state), {}, {}) }
}
function nodes(node) {
  if (Array.isArray(node)) return node.flatMap(nodes)
  return node && typeof node === 'object' ? [node, ...nodes(node.children)] : []
}
const text = node => typeof node === 'string' ? node : Array.isArray(node) ? node.map(text).join('') : node && typeof node === 'object' ? text(node.children) : ''
test('catalog card and list render one hashtag, preserve unique body and use the original post key', async t => {
  const f = mount(t), post = { key: 'original-key', title: '#晴小兔', text: '#晴小兔', channelTitle: '合成频道', mainMedia: [], coverage: 'PARTIAL' }
  const item = { post, tags: ['晴小兔'], indexedCommentMedia: 0, indexedCommentMessages: 0, updatedAt: 1 }
  f.state.catalog.value = { items: [item], indexedChannels: 1, indexedPosts: 1, total: 1, limit: 20, offset: 0, hasMore: false }
  for (const view of ['cards', 'list']) {
    f.state.showCatalogView(view); await vue.nextTick()
    const card = nodes(f.render()).find(node => node.props?.class === 'library-post-card')
    assert.ok(card); assert.equal(text(card).match(/#晴小兔/gu)?.length, 1)
    assert.equal(nodes(card).find(node => node.type?.__name?.endsWith('LibraryPostPreview.vue')).props['post-key'], 'original-key')
    assert.equal(nodes(card).filter(node => node.props?.class === 'library-card-excerpt').length, 0)
  }
  f.state.catalog.value.items[0].post.text = '#晴小兔\n保留这段正文 #旅行'
  f.state.catalog.value.items[0].tags = ['晴小兔', '旅行', '额外标签']
  await vue.nextTick()
  const card = nodes(f.render()).find(node => node.props?.class === 'library-post-card'), displayed = text(card)
  assert.equal(displayed.match(/#晴小兔/gu)?.length, 1); assert.match(displayed, /保留这段正文 #旅行/); assert.match(displayed, /#额外标签/)
  assert.equal(f.state.catalog.value.items[0].post.text, '#晴小兔\n保留这段正文 #旅行')
  assert.deepEqual(f.state.catalog.value.items[0].tags, ['晴小兔', '旅行', '额外标签'])
})
