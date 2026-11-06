import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as bots from '../src/botDownload.ts'
import * as tasks from '../src/downloadTasks.ts'
import * as library from '../src/library.ts'

const source = readFileSync(new URL('../src/components/BotDownload.vue', import.meta.url), 'utf8')
const { descriptor, errors } = parse(source, { filename: 'BotDownload.vue' })
assert.deepEqual(errors, [])
const script = compileScript(descriptor, { id: 'bot-ui' })
const template = compileTemplate({ source: descriptor.template.content, filename: 'BotDownload.vue', id: 'bot-ui',
  compilerOptions: { bindingMetadata: script.bindings, expressionPlugins: ['typescript'] } })
assert.deepEqual(template.errors, [])
const transpile = code => ts.transpileModule(code, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const scriptCode = transpile(script.content), renderCode = transpile(template.code)
const flush = async () => { await vue.nextTick(); await new Promise(setImmediate) }
function mount(t, overrides = {}) {
  const props = vue.reactive({ authorized: true, available: true, accountId: '1', ...overrides }), events = [], hooks = [], calls = [], expected = [], failures = [], timers = new Map()
  let serial = 0
  const modules = { vue: { ...vue, onBeforeUnmount: hook => hooks.push(hook), withCtx: fn => fn, withDirectives: node => node },
    '../api': { api: async (path, options = {}) => {
      calls.push({ path, options }); const response = expected.shift()
      try { assert.ok(response, 'Unexpected request ' + path); assert.equal(path, response.path) } catch (cause) { failures.push(cause); throw cause }
      return await response.value
    }, ApiError: class extends Error {} },
    '../botDownload': bots, '../downloadTasks': tasks, '../library': library }
  const require = name => name.endsWith('.vue') ? { __esModule: true, default: { __name: name } } : modules[name]
  const module = { exports: {} }, rendered = { exports: {} }
  new Function('require', 'module', 'exports', 'setTimeout', 'clearTimeout', scriptCode)(require, module, module.exports,
    (callback, delay) => { timers.set(++serial, { callback, delay }); return serial }, id => timers.delete(id))
  new Function('require', 'module', 'exports', renderCode)(require, rendered, rendered.exports)
  const scope = vue.effectScope(), state = scope.run(() => module.exports.default.setup(props, { expose() {}, emit: (...event) => events.push(event) }))
  t.after(() => { hooks.forEach(hook => hook()); scope.stop(); assert.deepEqual(failures, []); assert.equal(expected.length, 0); assert.equal(timers.size, 0) })
  return { props, state, events, calls, timers, expect: (path, value) => expected.push({ path, value }),
    runNextTimer: () => { const [id, timer] = [...timers].sort((a, b) => a[1].delay - b[1].delay)[0]; timers.delete(id); timer.callback() },
    render: () => rendered.exports.render({}, [], props, vue.proxyRefs(state), {}, {}) }
}
function nodes(value) {
  if (!value || typeof value !== 'object') return []
  if (Array.isArray(value)) return value.flatMap(nodes)
  return [value, ...nodes(value.children)]
}
const text = value => typeof value === 'string' ? value : Array.isArray(value) ? value.map(text).join('') : value && typeof value === 'object' ? text(value.children) : ''
const hasClass = (value, name) => String(value.props?.class ?? '').split(/\s+/).includes(name)
function preview(state = 'READY') {
  const media = { key: 'file-a', message: { chatId: '123', messageId: '1001', link: null }, source: 'BOT', type: 'DOCUMENT', fileName: 'sample.txt', size: 16, caption: '' }
  const button = { id: 'next', text: '下一组', kind: 'NEXT', messageId: '1002', row: 0, column: 0, supported: true }
  return { id: '11111111-1111-4111-8111-111111111111', revision: 1, bot: { title: '合成机器人', chatId: '123', botUserId: '456' },
    messages: [{ id: '1001', text: '', media: [media], buttons: [] }, { id: '1002', text: '第1组', media: [], buttons: [button] }], media: [media], controlMessageId: '1002', recommendedButtonId: 'next',
    paginationStatus: 'MORE', currentPage: 1, totalPages: 6, state, warning: null, nextHistoryCursor: null, historyEnd: true }
}

test('default directory fills only an empty editable field at initialization or a changed preference', async t => {
  const f = mount(t, { defaultDownloadDirectory: 'D:\\default' })
  assert.equal(f.state.targetDirectory.value, 'D:\\default')
  f.state.targetDirectory.value = 'D:\\chosen'; f.props.defaultDownloadDirectory = 'D:\\updated'; await flush()
  assert.equal(f.state.targetDirectory.value, 'D:\\chosen')
  f.state.targetDirectory.value = ''; f.props.defaultDownloadDirectory = 'D:\\empty-default'; await flush()
  assert.equal(f.state.targetDirectory.value, 'D:\\empty-default')
  f.state.targetDirectory.value = ''; f.state.busy.value = 'create'; f.props.defaultDownloadDirectory = 'D:\\locked'; await flush()
  assert.equal(f.state.targetDirectory.value, '')
  f.state.busy.value = ''; await flush(); assert.equal(f.state.targetDirectory.value, '', 'Unlocking must not rewrite the field')
})

test('the original action footer teleports once and keeps lookup-only behavior for a frozen creation', t => {
  const f = mount(t, { actionTarget: '#bot-actions', defaultDownloadDirectory: 'D:\\chosen' }); f.state.autoPagination.value = false
  f.state.preview.value = preview(); f.state.selected.value = ['file-a']
  let tree = f.render(), teleport = nodes(tree).find(node => node.type === vue.Teleport)
  assert.equal(teleport.props.to, '#bot-actions'); assert.equal(teleport.props.disabled, false); assert.equal(teleport.props.defer, '')
  assert.equal(nodes(tree).filter(node => hasClass(node, 'bot-download-footer')).length, 1)
  assert.equal(nodes(tree).filter(node => node.type === 'button' && text(node) === '下载选中媒体').length, 1)
  assert.equal(f.events.at(-1)[0], 'stage'); assert.equal(f.events.at(-1)[1], 'confirm')
  f.state.createRequest.value = { operationId: 'frozen', sourceId: 'source', revision: 1, mediaKeys: ['file-a'], targetDirectory: 'D:\\chosen' }
  tree = f.render(); assert.match(text(tree), /核对创建结果/)
  assert.equal(nodes(tree).filter(node => node.type === 'button' && text(node) === '下载选中媒体').length, 0)
  f.props.actionTarget = undefined; teleport = nodes(f.render()).find(node => node.type === vue.Teleport)
  assert.equal(teleport.props.disabled, true); assert.equal(teleport.props.to, 'body')
})

test('advanced waits stay collapsed while waiting, next-page, selection and stage remain clear projections', t => {
  const f = mount(t)
  assert.deepEqual(f.events[0], ['stage', 'parameters'])
  f.state.preview.value = preview('WAITING'); f.state.observing.value = true
  let tree = f.render(); const advanced = nodes(tree).find(node => hasClass(node, 'bot-advanced'))
  assert.equal(advanced.type, 'details'); assert.equal(advanced.props.open, undefined); assert.match(text(advanced), /高级等待设置/)
  assert.equal(nodes(advanced).filter(node => node.type === 'input' && node.props.type === 'number').length, 2)
  assert.match(text(nodes(tree).find(node => hasClass(node, 'bot-wait-status'))), /等待回复稳定/)
  assert.equal(nodes(tree).find(node => hasClass(node, 'bot-next')).props.disabled, true)
  assert.match(text(nodes(tree).find(node => hasClass(node, 'bot-selection-count'))), /已选 0 个/)
  f.state.selected.value = ['file-a']; assert.equal(f.events.at(-1)[1], 'confirm')
  f.state.createdTask.value = { total: 1, state: 'QUEUED' }; assert.equal(f.events.at(-1)[1], 'complete')
  assert.doesNotMatch(descriptor.styles[0].content, /font-size:(?:10|11)px/)
})

test('automatic pagination defaults on and its rendered stop action remains usable during an in-flight callback', t => {
  const f = mount(t)
  assert.equal(f.state.autoPagination.value, true)
  assert.match(text(nodes(f.render()).find(node => hasClass(node, 'bot-auto-option'))), /自动翻页采集至末页/)
  f.state.preview.value = preview(); f.state.autoPaging.value = true; f.state.busy.value = 'press'
  const tree = f.render(), stop = nodes(tree).find(node => node.type === 'button' && text(node) === '停止自动采集')
  assert.ok(stop); assert.notEqual(stop.props.disabled, true); assert.equal(stop.props.type, 'button')
  assert.equal(nodes(tree).find(node => hasClass(node, 'bot-next')).props.disabled, true)
  stop.props.onClick(); assert.equal(f.state.autoPaging.value, false)
  assert.match(f.state.notice.value, /已停止自动采集/)
})

test('an acknowledged pagination review shows active automatic verification rather than a stopped warning', t => {
  const f = mount(t), originalWarning = '机器人回调结果尚未确认，请刷新核对'
  f.state.preview.value = { ...preview('NEEDS_REVIEW'), warning: originalWarning }
  f.state.autoPaging.value = true; f.state.observing.value = true; f.state.pressUnknown.value = true
  const tree = f.render(), pending = nodes(tree).find(node => node.type === 'p' && text(node).includes('正在自动核对回复'))
  assert.ok(pending); assert.equal(hasClass(pending, 'notice'), true); assert.equal(hasClass(pending, 'warning'), false)
  assert.match(text(pending), /确认翻页成功并稳定后会继续.*不会重复点击当前按钮/)
  assert.match(text(nodes(tree).find(node => hasClass(node, 'bot-auto-status'))), /正在自动采集.*停止自动采集/)
  assert.doesNotMatch(text(tree), /已停止自动翻页|自动观察已停止/)
  assert.equal(text(tree).includes(originalWarning), false)
  assert.equal(nodes(tree).find(node => hasClass(node, 'bot-next')).props.disabled, true)
  f.state.stopAutoPagination()
  const stoppedWarning = nodes(f.render()).find(node => node.type === 'p' && text(node) === originalWarning)
  assert.ok(stoppedWarning); assert.equal(hasClass(stoppedWarning, 'warning'), true)
})

test('background preparation exposes a usable stop-wait action while preserving only the original lookup action', t => {
  const f = mount(t), operationId = '22222222-2222-4222-8222-222222222222'
  f.state.preview.value = preview(); f.state.selected.value = ['file-a']; f.state.targetDirectory.value = 'D:\\chosen'
  const request = { operationId, sourceId: f.state.preview.value.id, revision: 1, mediaKeys: ['file-a'], targetDirectory: 'D:\\chosen' }
  f.state.createRequest.value = request
  f.state.preparation.value = { operationId, state: 'PREPARING', task: null, error: null }; f.state.pollingCreation.value = true
  f.state.busy.value = 'lookup'
  const footer = nodes(f.render()).find(node => hasClass(node, 'bot-download-footer'))
  const stop = nodes(footer).find(node => node.type === 'button' && text(node) === '停止等待')
  const lookup = nodes(footer).find(node => node.type === 'button' && text(node) === '核对创建结果')
  assert.ok(stop); assert.notEqual(stop.props.disabled, true); assert.equal(lookup.props.disabled, true)
  assert.equal(nodes(footer).some(node => node.type === 'button' && text(node) === '下载选中媒体'), false)
  stop.props.onClick(); f.state.busy.value = ''
  assert.equal(f.state.pollingCreation.value, false); assert.deepEqual(f.state.createRequest.value, request)
  const stopped = nodes(f.render()).find(node => hasClass(node, 'bot-download-footer'))
  assert.match(text(stopped), /正在后台核对 1 个媒体.*页面等待已停止.*核对创建结果/s)
  assert.equal(nodes(stopped).some(node => node.type === 'button' && text(node) === '停止等待'), false)
  assert.equal(f.state.canCreate.value, false); assert.equal(f.calls.length, 0)
})

test('created task navigation does not expose download-completion monitoring in the collector',t=>{
  const f=mount(t);f.state.preview.value=preview();f.state.createdTask.value={total:1,completed:0,skipped:0,state:'QUEUED'}
  const tree=f.render(),action=nodes(tree).find(node=>node.type==='button'&&text(node)==='查看下载任务')
  assert.ok(action);assert.doesNotMatch(text(tree),/继续等待本页下载后翻页/);action.props.onClick();assert.deepEqual(f.events.at(-1),['tasks','download'])
})

test('leaving the preview stops later GETs while the current response and uncertain identity remain valid', async t => {
  const f = mount(t, { active: true }), initial = preview()
  f.state.preview.value = initial; f.state.selected.value = ['file-a']
  const waiting = { ...preview('WAITING'), revision: 2, wait: { waitSeconds: 5, timeoutSeconds: 120, elapsedMs: 0, quietMs: 0, remainingMs: 120000, progressObserved: false, timedOut: false } }
  f.expect('/transfers/download/bots/preview/' + initial.id + '/press', waiting)
  await f.state.press(initial.messages[1].buttons[0]); assert.equal(f.state.observing.value, true)
  let resolve
  f.expect('/transfers/download/bots/preview/' + initial.id, new Promise(done => { resolve = done }))
  f.runNextTimer(); await flush(); assert.equal(f.state.previewReadPending.value, true)
  f.state.pressUnknown.value = true; f.props.active = false
  assert.equal(f.state.observing.value, false); assert.equal(f.timers.size, 0)
  assert.equal(f.state.preview.value.revision, 2); assert.equal(f.state.pressUnknown.value, true); assert.deepEqual(f.state.selected.value, ['file-a'])
  resolve({ ...waiting, revision: 3 }); await flush()
  assert.equal(f.state.preview.value.revision, 3, 'The in-flight reply still uses the original account, session and request identity')
  assert.equal(f.state.pressUnknown.value, true); assert.equal(f.timers.size, 0)
  f.state.createRequest.value = { operationId: 'frozen', sourceId: initial.id, revision: 3, mediaKeys: ['file-a'], targetDirectory: 'D:\\chosen' }
  f.props.active = true; await flush()
  assert.equal(f.state.createRequest.value.operationId, 'frozen'); assert.equal(f.calls.length, 2); assert.equal(f.timers.size, 0)
})
