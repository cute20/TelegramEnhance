import * as taskHistory from '../src/taskHistory.ts'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as categories from '../src/taskCategories.ts'
import * as library from '../src/library.ts'
import * as preview from '../src/downloadPreview.ts'
import * as downloadTasks from '../src/downloadTasks.ts'
import * as uploadTasks from '../src/uploadTasks.ts'
import * as uploadPreview from '../src/uploadPreview.ts'
import * as rules from '../src/rules.ts'
import * as downloadBatchStatus from '../src/downloadBatchStatus.ts'

const compiled = new Map()
const flush = async () => { await vue.nextTick(); await new Promise(setImmediate) }
function mount(t, name, initialProps = {}) {
  if (!compiled.has(name)) {
    const url = name === 'App' ? '../src/App.vue' : `../src/components/${name}.vue`
    const { descriptor, errors } = parse(readFileSync(new URL(url, import.meta.url), 'utf8'), { filename: name + '.vue' })
    assert.deepEqual(errors, [])
    const script = compileScript(descriptor, { id: 'routing-' + name })
    const template = compileTemplate({ source: descriptor.template.content, filename: name + '.vue', id: 'routing-' + name,
      compilerOptions: { bindingMetadata: script.bindings, expressionPlugins: ['typescript'] } })
    assert.deepEqual(template.errors, [])
    const transpile = code => ts.transpileModule(code, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
    compiled.set(name, { script: transpile(script.content), render: transpile(template.code) })
  }
  const props = vue.reactive({ authorized: false, available: false, accountId: '1', ...initialProps }), hooks = [], events = [], calls = [], timers = []
  const api = { hasAppToken: false, clearAppToken() {}, ApiError: class extends Error {}, api: async (...args) => { calls.push(args); throw new Error('Navigation must not invoke API') } }
  const runtimeVue = { ...vue, onMounted() {}, onBeforeUnmount: hook => hooks.push(hook), withCtx: fn => fn,
    withDirectives: (node, directives) => { node.testDirectives = directives; return node } }
  const stubs = new Map()
  const modules = { vue: runtimeVue, '../api': api, './api': api, '../taskCategories': categories, './taskCategories': categories,
    '../taskHistory': taskHistory, '../library': library, '../downloadPreview': preview, '../downloadTasks': downloadTasks, '../uploadTasks': uploadTasks,
    '../uploadPreview': uploadPreview, '../rules': rules, '../downloadBatchStatus': downloadBatchStatus }
  const requireModule = name => {
    if (name.endsWith('.vue')) { const title = name.split('/').at(-1).replace('.vue', ''); if (!stubs.has(title)) stubs.set(title, { __name: title }); return { __esModule: true, default: stubs.get(title) } }
    assert.ok(Object.hasOwn(modules, name), name); return modules[name]
  }
  const fakeTimeout = (callback, delay) => { const timer = { callback, delay }; timers.push(timer); return timer }
  const fakeWindow = { clearTimeout() {}, clearInterval() {} }
  const scriptModule = { exports: {} }, renderModule = { exports: {} }, code = compiled.get(name)
  new Function('require', 'module', 'exports', 'setTimeout', 'clearTimeout', 'window', code.script)(requireModule, scriptModule, scriptModule.exports, fakeTimeout, () => {}, fakeWindow)
  new Function('require', 'module', 'exports', code.render)(requireModule, renderModule, renderModule.exports)
  const scope = vue.effectScope(), state = scope.run(() => scriptModule.exports.default.setup(props, { expose() {}, emit: (...event) => events.push(event) }))
  t.after(() => { hooks.forEach(hook => hook()); scope.stop(); assert.equal(calls.length, 0) })
  return { state, props, events, render: () => renderModule.exports.render({}, [], props, vue.proxyRefs(state), {}, {}) }
}
function nodes(node) {
  if (!node || typeof node !== 'object') return []
  if (Array.isArray(node)) return node.flatMap(nodes)
  const children = Array.isArray(node.children) ? node.children : node.children && typeof node.children === 'object'
    ? Object.values(node.children).filter(value => typeof value === 'function').flatMap(slot => slot()) : []
  return [node, ...nodes(children)]
}
const component = (tree, name) => nodes(tree).find(node => node.type?.__name === name)
const text = node => typeof node === 'string' ? node : Array.isArray(node) ? node.map(text).join('') : node && typeof node === 'object' ? text(node.children) : ''

test('rule browsing renders no upload preparation or download creation and fixes the channel seed', t => {
  const seed={source:'-1001',tags:'旅行',keywords:'山'},f=mount(t,'NewTransfer',{previewOnly:true,browseSource:seed})
  let tree=f.render();assert.match(text(tree),/浏览帖子/);assert.equal(component(tree,'DownloadChannelPreview').props['read-only'],true);assert.deepEqual(component(tree,'DownloadChannelPreview').props['initial-source'],seed)
  assert.equal(component(tree,'UploadPreparation'),undefined);assert.doesNotMatch(text(tree),/创建并开始下载|上传准备/)
  f.state.direction.value='upload';tree=f.render();assert.equal(component(tree,'UploadPreparation'),undefined)
})

test('task center defaults to downloads and keeps all three panels mounted with only the selected category active', async t => {
  const f = mount(t, 'TransferTasks', { authorized: true, available: true })
  assert.equal(f.state.selected.value, 'download')
  let tree = f.render()
  for (const [name, active] of [['DownloadTasks', true], ['DownloadBatches', false], ['UploadTasks', false]]) assert.equal(component(tree, name).props.active, active)
  const buttons = nodes(tree).filter(node => node.type === 'button')
  assert.deepEqual(buttons.map(text), ['单帖下载', '频道批量下载', '上传'])
  buttons[2].props.onClick(); await flush(); tree = f.render()
  assert.equal(f.state.selected.value, 'upload'); assert.equal(component(tree, 'UploadTasks').props.active, true)
  assert.equal(component(tree, 'DownloadTasks').props.active, false); assert.equal(component(tree, 'DownloadBatches').props.active, false)
  assert.deepEqual(f.events, [['update:category', 'upload']]); assert.ok(component(tree, 'DownloadTasks'), 'Hiding does not unmount the other panel')
})
test('external navigation selects its category and hiding the whole task center deactivates every panel without resetting selection', async t => {
  const f = mount(t, 'TransferTasks', { category: 'upload', active: true, refreshVersion: 0 })
  f.props.category = 'batches'; f.props.refreshVersion = 1; await flush()
  assert.equal(f.state.selected.value, 'batches'); assert.equal(component(f.render(), 'DownloadBatches').props['refresh-version'], 1)
  f.props.active = false; await flush()
  for (const name of ['DownloadTasks', 'DownloadBatches', 'UploadTasks']) assert.equal(component(f.render(), name).props.active, false)
  assert.equal(f.state.selected.value, 'batches'); f.state.select('invalid'); assert.equal(f.state.selected.value, 'batches')
})
test('download view headings stay concise and keep separate task categories', t => {
  const single = mount(t, 'DownloadTasks'), batches = mount(t, 'DownloadBatches')
  assert.equal(text(nodes(single.render()).find(node => node.type === 'h2')), '单帖下载')
  assert.doesNotMatch(text(single.render()), /按主帖查看下载明细/)
  assert.equal(text(nodes(batches.render()).find(node => node.type === 'h2')), '频道批量下载')
  assert.doesNotMatch(text(batches.render()), /按频道批次汇总进度/)
  assert.deepEqual(categories.taskCategories.map(item => item.id), ['download', 'batches', 'upload'])
})
test('App renders product navigation without implementation stage badges while keeping runtime versions', async t => {
  const f = mount(t, 'App')
  f.state.diagnostics.value = { stage: 'M5', javaVersion: '21', os: 'Windows', architecture: 'amd64', database: {}, dpapi: 'AVAILABLE', dataDirectory: 'D:\\data' }
  f.state.telegram.value = { authState: 'DISCONNECTED', connectionState: 'DISCONNECTED', nativeLoaded: true, nativeVersion: 'v1.8.1' }
  assert.match(text(f.render()), /v1\.8\.1/)
  for (const page of ['settings', 'library', 'tasks', 'rules']) {
    f.state.page.value = page; await flush()
    const displayed = text(f.render())
    assert.doesNotMatch(displayed, /\bM[1-6]\b|阶段开放|当前阶段验证/)
    for (const label of ['我的资源库', '任务中心', '自动规则', '设置']) assert.ok(displayed.includes(label))
  }
})
test('App navigation selects upload, single download or batch category and refreshes by prop rather than component remount', async t => {
  const f = mount(t, 'App')
  assert.equal(f.state.taskCategory.value, 'download')
  let panel = component(f.render(), 'TransferTasks'); assert.ok(panel); assert.equal(panel.props.active, false); assert.equal(panel.key, null)
  for (const category of ['upload', 'download', 'batches']) {
    f.state.newTransferOpen.value = true; f.state.openTasks(category); await flush()
    assert.equal(f.state.page.value, 'tasks'); assert.equal(f.state.newTransferOpen.value, false)
    panel = component(f.render(), 'TransferTasks'); assert.equal(panel.props.category, category); assert.equal(panel.props.active, true); assert.equal(panel.key, null)
  }
  assert.equal(f.state.taskRefresh.value, 3)
  f.state.page.value = 'rules'; await flush(); panel = component(f.render(), 'TransferTasks'); assert.ok(panel); assert.equal(panel.props.active, false)
  const rulePage = component(f.render(), 'RuleWorkspace'); rulePage.props.onTasks('download'); await flush()
  assert.equal(f.state.taskCategory.value, 'download'); assert.equal(f.state.page.value, 'tasks')
})
test('new transfer upload and channel-batch task links emit distinct target categories without starting a task', async t => {
  const f = mount(t, 'NewTransfer')
  component(f.render(), 'DownloadChannelPreview').props.onTasks()
  assert.deepEqual(f.events, [['tasks', 'batches']])
  f.state.direction.value = 'upload'; await flush(); component(f.render(), 'UploadPreparation').props.onTasks()
  assert.deepEqual(f.events.at(-1), ['tasks', 'upload'])
})
test('new transfer created single-download link returns to download category', async t => {
  const f = mount(t, 'NewTransfer')
  f.state.preview.value = { id: 'source', post: { key: 'post', title: '合成主帖', channelTitle: '合成频道', mainCoverage: 'COMPLETE', mainMedia: [], warnings: [] },
    comments: null, observedMessages: 0, exhausted: true, canContinue: false, pageNumber: 1, warnings: [] }
  f.state.createdTask.value = { total: 1, skipped: 0 }; await flush()
  const link = nodes(f.render()).find(node => node.type === 'button' && text(node) === '查看任务')
  assert.ok(link); link.props.onClick(); assert.deepEqual(f.events.at(-1), ['tasks', 'download'])
})
test('rule workspace forwards continuous-download tasks to download and upload job links to upload', async t => {
  const f = mount(t, 'RuleWorkspace')
  component(f.render(), 'DownloadRules').props.onTasks(); assert.deepEqual(f.events, [['tasks', 'download']])
  f.state.items.value = [{ mode: 'AUTO_UPLOAD', state: 'PAUSED', scanning: false, nextAllowedAt: 0,
    rule: { id: 'rule', version: 1, revision: 1, enabled: false, observation: null, input: { name: '合成规则', sourceDirectory: 'D:\\source', archiveDirectory: 'D:\\archive', window: { mode: 'ALL_DAY' } } },
    job: { taskId: 'task', state: 'FAILED', ruleVersion: 1, total: 1, confirmed: 0, archived: 0, manualTakeover: false, error: '合成错误' } }]
  const link = nodes(f.render()).find(node => node.type === 'button' && text(node) === '到任务中心处理')
  assert.ok(link); link.props.onClick(); assert.deepEqual(f.events.at(-1), ['tasks', 'upload'])
})
