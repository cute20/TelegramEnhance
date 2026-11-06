import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as library from '../src/library.ts'
import * as download from '../src/downloadPreview.ts'
import * as downloadTasks from '../src/downloadTasks.ts'
import * as upload from '../src/uploadPreview.ts'
import * as uploadTasks from '../src/uploadTasks.ts'
import * as rules from '../src/rules.ts'
import * as channels from '../src/downloadChannel.ts'
import * as batches from '../src/downloadBatch.ts'

const flush = async () => { await vue.nextTick(); await new Promise(setImmediate) }
const ID = '00000000-0000-0000-0000-000000000001', LINK = 'https://t.me/synthetic/1', TARGET = '#synthetic-footer'
const selection = () => ({ entry: 'POST', source: LINK, tags: '', keywords: '', types: ['VIDEO'], includeMain: false, targetDirectory: 'D:\\out', suggestedName: '' })
const uploadSelection = () => ({ link: LINK, sourceDirectory: 'D:\\source', recursive: true, captionMode: 'FILENAME', albumSize: 10 })
const transpile = code => ts.transpileModule(code, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
function mount(t, name, initialProps = {}) {
  const parsed = parse(readFileSync(new URL('../src/components/' + name + '.vue', import.meta.url), 'utf8'), { filename: name + '.vue' })
  assert.deepEqual(parsed.errors, [])
  const script = compileScript(parsed.descriptor, { id: 'preview-footer-' + name })
  const template = compileTemplate({ source: parsed.descriptor.template.content, filename: name + '.vue', id: 'preview-footer-' + name,
    compilerOptions: { bindingMetadata: script.bindings, expressionPlugins: ['typescript'] } })
  assert.deepEqual(template.errors, [])
  const props = vue.reactive({ authorized: true, available: true, accountId: '1', active: true, actionTarget: TARGET, ...initialProps })
  const hooks = [], calls = [], expected = [], failures = []
  const modules = { vue: { ...vue, onMounted() {}, onBeforeUnmount: fn => hooks.push(fn), withDirectives: node => node },
    '../library': library, '../downloadPreview': download, '../downloadTasks': downloadTasks, '../uploadPreview': upload,
    '../uploadTasks': uploadTasks, '../rules': rules, '../downloadChannel': channels, '../downloadBatch': batches,
    '../api': { ApiError: class extends Error {}, api: async (path, options = {}) => {
      calls.push({ path, options }); const response = expected.shift()
      try { assert.ok(response, 'Unexpected request ' + path); assert.equal(path, response.path) } catch (cause) { failures.push(cause); throw cause }
      if (response.value instanceof Error) throw response.value
      return await response.value
    } } }
  const require = name => name.endsWith('.vue') ? { __esModule: true, default: { __name: name } } : modules[name]
  const module = { exports: {} }, rendered = { exports: {} }
  new Function('require', 'module', 'exports', transpile(script.content))(require, module, module.exports)
  new Function('require', 'module', 'exports', transpile(template.code))(require, rendered, rendered.exports)
  const scope = vue.effectScope(), state = scope.run(() => module.exports.default.setup(props, { expose() {}, emit() {} }))
  t.after(() => { hooks.forEach(fn => fn()); scope.stop(); assert.deepEqual(failures, []); assert.equal(expected.length, 0) })
  return { props, state, calls, expect: (path, value) => expected.push({ path, value }),
    render: () => rendered.exports.render({}, [], props, vue.proxyRefs(state), {}, {}) }
}
function located(value, location = 'body') {
  if (!value || typeof value !== 'object') return []
  if (Array.isArray(value)) return value.flatMap(node => located(node, location))
  const childLocation = value.type === vue.Teleport && !value.props.disabled ? 'footer' : location
  return [{ node: value, location }, ...located(value.children, childLocation)]
}
const text = value => typeof value === 'string' ? value : Array.isArray(value) ? value.map(text).join('') : value && typeof value === 'object' ? text(value.children) : ''
const hasClass = (node, name) => String(node.props?.class ?? '').split(/\s+/u).includes(name)
const primary = tree => located(tree).filter(({node,location}) => location === 'footer' && node.type === 'button' && hasClass(node, 'primary'))
const button = (tree, label) => located(tree).find(({node}) => node.type === 'button' && text(node).includes(label))
const form = tree => located(tree).find(({node}) => node.type === 'form')?.node
const sourcePreview = () => ({ id: ID, post: { key: 'post', channelTitle: '合成频道', title: '合成主帖', text: '', mainCoverage: 'COMPLETE', mainMedia: [], warnings: [] },
  comments: null, observedMessages: 0, exhausted: true, canContinue: false, pageNumber: 1, warnings: [] })
const uploadPreview = () => ({ id: ID, channelTitle: '合成频道', postTitle: '合成主帖', postText: '', discussionTitle: '讨论区', sourceDirectory: 'D:\\source',
  archiveDirectory: 'D:\\source-已上传', albumSize: 10, postLink: LINK, fileCount: 1, totalBytes: 1, groups: [], warnings: [], otherFiles: 0, deferredFiles: 0, duplicateFiles: 0 })

test('first upload preview is the single footer primary and submits its original native form only explicitly', async t => {
  const f = mount(t, 'UploadPreparation', { selection: uploadSelection() }); await flush()
  let tree = f.render(), first = button(tree, '生成上传预览')
  assert.equal(first.location, 'footer'); assert.equal(primary(tree).length, 1)
  assert.equal(first.node.props.type, 'submit'); assert.equal(first.node.props.form, form(tree).props.id)
  assert.equal(first.node.props.disabled, false); assert.equal(f.calls.length, 0)
  f.expect('/transfers/upload/preview', new Error('合成预览失败'))
  await form(tree).props.onSubmit({ preventDefault() {} }); await flush()
  assert.equal(f.calls.length, 1); assert.equal(f.calls[0].options.method, 'POST')
  assert.equal(f.calls.some(call => call.path === '/transfers/upload/tasks'), false)
  f.state.preview.value = uploadPreview(); await flush(); tree = f.render()
  assert.equal(button(tree, '重新生成上传预览').location, 'body')
  assert.equal(hasClass(button(tree, '重新生成上传预览').node, 'primary'), false)
  assert.equal(primary(tree).length, 1); assert.match(text(primary(tree)[0].node), /保存上传任务/)
})
test('teleported upload preview preserves all disabled gates after leaving its disabled fieldset', async t => {
  const f = mount(t, 'UploadPreparation', { selection: uploadSelection() }); await flush()
  for (const [state,value] of [['busy',true],['creating',true],['uncertain',true],['created',{}]]) {
    f.state[state].value = value; await flush(); assert.equal(primary(f.render())[0].node.props.disabled, true); f.state[state].value = state === 'created' ? null : false
  }
  f.props.available = false; await flush(); assert.equal(button(f.render(), '预览').node.props.disabled, true)
})
test('standalone upload form offers album size and preview confirmation displays its selected maximum',async t=>{
  const f=mount(t,'UploadPreparation',{actionTarget:undefined});await flush()
  const size=located(f.render()).find(({node})=>node.type==='select'&&node.props.id==='upload-album-size')?.node
  assert.ok(size);assert.equal(f.state.albumSize.value,10)
  assert.deepEqual(located(size).filter(({node})=>node.type==='option').map(({node})=>node.props.value),[1,2,3,4,5,6,7,8,9,10])
  size.props['onUpdate:modelValue'](3);await flush();assert.equal(f.state.albumSize.value,3)
  f.state.preview.value={...uploadPreview(),albumSize:3};await flush();assert.match(text(f.render()),/每组最多 3 个媒体/)
  f.state.preview.value={...uploadPreview(),albumSize:1};await flush();assert.match(text(f.render()),/逐条发送（每组 1 个）/)
  assert.equal(f.calls.length,0)
})
test('single-post shared preview is explicitly clicked in the footer then hands off to the existing create action', async t => {
  const f = mount(t, 'NewTransfer', { embedded: true, selection: selection() }); await flush()
  let tree = f.render(); assert.equal(primary(tree).length, 1); assert.match(text(primary(tree)[0].node), /预览下载来源/); assert.equal(f.calls.length, 0)
  f.expect('/transfers/download/preview', new Error('合成读取失败'))
  await primary(tree)[0].node.props.onClick(); await flush(); assert.equal(f.calls.length, 1)
  f.state.preview.value = sourcePreview(); await flush(); tree = f.render()
  assert.equal(button(tree, '重新预览来源').location, 'body'); assert.equal(hasClass(button(tree, '重新预览来源').node, 'primary'), false)
  assert.equal(primary(tree).length, 1); assert.match(text(primary(tree)[0].node), /创建并开始下载/)
})
test('external single-post preview button retains the source form association when teleported', async t => {
  const f = mount(t, 'NewTransfer', { embedded: true, authorized: false }); f.state.link.value = LINK; await flush()
  const tree = f.render(), first = button(tree, '预览下载来源')
  assert.equal(first.location, 'footer'); assert.equal(first.node.props.form, form(tree).props.id)
  assert.equal(first.node.props.type, 'submit'); assert.equal(first.node.props.disabled, true); assert.equal(f.calls.length, 0)
})
test('first channel scan moves only its existing explicit action into the footer and does not scan on mount', async t => {
  const f = mount(t, 'DownloadChannelPreview', { selection: { ...selection(), entry: 'CHANNEL', source: '-1001' } }); await flush()
  let tree = f.render(); assert.equal(primary(tree).length, 1); assert.match(text(primary(tree)[0].node), /开始扫描预览/); assert.equal(f.calls.length, 0)
  f.expect('/transfers/download/channels/preview', new Error('合成扫描失败'))
  await primary(tree)[0].node.props.onClick(); await flush(); assert.equal(f.calls.length, 1); assert.equal(f.calls[0].options.method, 'POST')
  f.state.scan.value = { id: ID, channel: { title: '合成频道' }, rows: [], tags: [], keywords: [], scanned: 0, matched: 0, unverified: 0, exhausted: true, canContinue: false, warnings: [] }
  await flush(); tree = f.render()
  assert.equal(button(tree, '重新扫描预览').location, 'body'); assert.equal(primary(tree).length, 0)
})
test('a teleported channel source submit keeps the original form owner and independent read-only browsing stays inline', async t => {
  const f = mount(t, 'DownloadChannelPreview', { authorized: false }); f.state.source.value = '-1001'; await flush()
  const tree = f.render(), first = button(tree, '开始扫描预览')
  assert.equal(first.location, 'footer'); assert.equal(first.node.props.form, form(tree).props.id); assert.equal(first.node.props.type, 'submit')
  const readOnly = mount(t, 'DownloadChannelPreview', { readOnly: true, initialSource: { source: '-1001', tags: '', keywords: '' } }); await flush()
  assert.equal(button(readOnly.render(), '刷新帖子').location, 'body'); assert.equal(readOnly.calls.length, 0)
})
test('batch preparation owns the footer before its snapshot is ready and then yields to create confirmation', async t => {
  const f = mount(t, 'DownloadBatchPreparation', { scanId: ID, scanRevision: 1, postKeys: ['post'], selection: selection() }); await flush()
  let tree = f.render(); assert.equal(primary(tree).length, 1); assert.match(text(primary(tree)[0].node), /准备批量下载清单/); assert.equal(f.calls.length, 0)
  f.state.preview.value = { id: ID, posts: [], warnings: [], exhausted: false, canContinue: true, total: 0, totalBytes: 0 }; await flush(); tree = f.render()
  assert.equal(primary(tree).length, 1); assert.match(text(primary(tree)[0].node), /继续准备/)
  f.state.preview.value = { ...f.state.preview.value, exhausted: true, canContinue: false, total: 1 }; await flush(); tree = f.render()
  assert.equal(primary(tree).length, 1); assert.match(text(primary(tree)[0].node), /创建并开始批量下载/)
})
test('standalone upload, post and channel preview actions retain inline fallback without a target', async t => {
  for (const [name,props,label] of [
    ['UploadPreparation',{selection:uploadSelection()},'生成上传预览'],
    ['NewTransfer',{embedded:true,selection:selection()},'预览下载来源'],
    ['DownloadChannelPreview',{selection:{...selection(),entry:'CHANNEL',source:'-1001'}},'开始扫描预览'],
  ]) {
    const f=mount(t,name,{...props,actionTarget:undefined});await flush()
    assert.equal(button(f.render(),label).location,'body');assert.equal(primary(f.render()).length,0);assert.equal(f.calls.length,0)
  }
})
