import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as categories from '../src/taskCategories.ts'

const { descriptor, errors } = parse(readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8'), { filename: 'App.vue' })
assert.deepEqual(errors, [])
const script = compileScript(descriptor, { id: 'page-navigation' })
const template = compileTemplate({ source: descriptor.template.content, filename: 'App.vue', id: 'page-navigation',
  compilerOptions: { bindingMetadata: script.bindings, expressionPlugins: ['typescript'] } })
assert.deepEqual(template.errors, [])
const transpile = source => ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const code = transpile(script.content), renderCode = transpile(template.code)
const copy = value => JSON.parse(JSON.stringify(value))
const flush = async () => { await vue.nextTick(); await new Promise(setImmediate) }
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes }); return { promise, resolve } }
const settings = overrides => ({ apiId: 1001, apiHashStored: true, proxy: { type: 'SOCKS5', host: '127.0.0.1', port: 10080, username: 'saved-user', passwordStored: true }, ...overrides })
const diagnostics = suffix => ({ javaVersion: '21', os: 'Windows', architecture: 'amd64', dataDirectory: 'D:\\synthetic-' + suffix,
  database: { schemaVersion: 16, journalMode: 'wal', synchronous: 'FULL', integrity: 'ok' }, dpapi: 'AVAILABLE', stage: 'M5' })
class ApiError extends Error { constructor(message, status) { super(message); this.status = status } }
function mount(t) {
  const calls = [], expected = [], failures = [], hooks = [], timers = new Map(), stubs = new Map()
  let serial = 0
  const api = async (path, options = {}) => {
    calls.push({ path, options: copy(options) }); const response = expected.shift()
    try { assert.ok(response, 'Unexpected request: ' + path); assert.equal(path, response.path); assert.equal(options.method ?? 'GET', 'GET') }
    catch (cause) { failures.push(cause); throw cause }
    if (response.value instanceof Error) throw response.value
    return await response.value
  }
  const runtimeVue = { ...vue, onMounted() {}, onBeforeUnmount: hook => hooks.push(hook), withCtx: fn => fn,
    withDirectives: (node, directives) => { node.testDirectives = directives; return node } }
  const modules = { vue: runtimeVue, './taskCategories': categories, './api': { api, ApiError, hasAppToken: true, clearAppToken() {} } }
  const requireModule = name => {
    if (name.endsWith('.vue')) {
      const title = name.split('/').at(-1).replace('.vue', '')
      if (!stubs.has(title)) stubs.set(title, { __name: title })
      return { __esModule: true, default: stubs.get(title) }
    }
    assert.ok(Object.hasOwn(modules, name), name); return modules[name]
  }
  const fakeWindow = { setTimeout(fn) { timers.set(++serial, fn); return serial }, clearTimeout(id) { timers.delete(id) }, clearInterval() {} }
  const module = { exports: {} }, rendered = { exports: {} }
  new Function('require', 'module', 'exports', 'window', code)(requireModule, module, module.exports, fakeWindow)
  new Function('require', 'module', 'exports', renderCode)(requireModule, rendered, rendered.exports)
  const scope = vue.effectScope(), state = scope.run(() => module.exports.default.setup({}, { expose() {} }))
  state.loading.value = false; state.serviceReachable.value = true
  state.applySettings(settings()); state.diagnostics.value = diagnostics('initial')
  state.telegram.value = { authState: 'READY', connectionState: 'READY', userId: '1', nativeLoaded: true, nativeVersion: 'synthetic' }
  t.after(() => { hooks.forEach(hook => hook()); scope.stop(); assert.equal(expected.length, 0); assert.deepEqual(failures, []) })
  return { state, calls, timers,
    expect: (path, value) => expected.push({ path, value }),
    expectSettings: (config, runtime) => { expected.push({ path: '/settings', value: config }, { path: '/diagnostics', value: runtime }) },
    render: () => rendered.exports.render({}, [], {}, vue.proxyRefs(state), {}, {}) }
}
function nodes(node) {
  if (!node || typeof node !== 'object') return []
  if (Array.isArray(node)) return node.flatMap(nodes)
  const children = Array.isArray(node.children) ? node.children : node.children && typeof node.children === 'object'
    ? Object.values(node.children).filter(value => typeof value === 'function').flatMap(slot => slot()) : []
  return [node, ...nodes(children)]
}
const component = (tree, name) => nodes(tree).find(node => node.type?.__name === name)

test('leaving and returning to settings reads only settings and diagnostics once without toggling global loading or busy', async t => {
  const f = mount(t), fresh = settings({ apiId: 1002 }), runtime = diagnostics('fresh')
  f.state.authValue.value = 'typed-verification-value'
  f.state.navigate('library'); await flush(); assert.deepEqual(f.calls, [])
  const config = deferred(), local = deferred(); f.expectSettings(config.promise, local.promise)
  f.state.navigate('settings')
  assert.equal(f.state.page.value, 'settings'); assert.equal(f.state.loading.value, false); assert.equal(f.state.busy.value, '')
  assert.equal(f.state.serviceReachable.value, true); assert.equal(f.state.authValue.value, 'typed-verification-value')
  config.resolve(fresh); local.resolve(runtime); await flush()
  assert.deepEqual(f.calls.map(call => call.path), ['/settings', '/diagnostics'])
  assert.deepEqual(f.state.savedSettings.value, fresh); assert.equal(f.state.form.apiId, '1002'); assert.deepEqual(f.state.diagnostics.value, runtime)
  assert.equal(f.state.authValue.value, 'typed-verification-value'); assert.equal(f.state.loading.value, false); assert.equal(f.state.busy.value, '')
  assert.equal(f.timers.size, 0, 'navigation does not add a periodic refresh')
})

test('clicking the selected page or an unsupported page does not read or change refresh versions', async t => {
  const f = mount(t), nonce = f.state.taskRefresh.value, rules = f.state.ruleRefresh.value
  f.state.navigate('settings'); f.state.navigate('invalid'); await flush()
  assert.equal(f.state.page.value, 'settings'); assert.deepEqual(f.calls, [])
  f.state.navigate('tasks'); f.state.navigate('tasks'); await flush()
  assert.equal(f.state.page.value, 'tasks'); assert.deepEqual(f.calls, [])
  assert.equal(f.state.taskRefresh.value, nonce); assert.equal(f.state.ruleRefresh.value, rules)
})

test('returning to settings preserves an existing dirty draft, secrets, authorization input and its saved baseline', async t => {
  const f = mount(t), baseline = copy(f.state.savedSettings.value), runtime = diagnostics('dirty')
  Object.assign(f.state.form, { apiId: '9876', apiHash: 'typed-private-hash', host: 'new.proxy', port: '12345', username: 'typed-user', password: 'typed-private-password' })
  f.state.authValue.value = 'typed-code'; const draft = copy(f.state.form)
  assert.equal(f.state.dirty.value, true); f.state.navigate('rules'); f.expectSettings(settings({ apiId: 2002 }), runtime)
  f.state.navigate('settings'); await flush()
  assert.deepEqual(f.state.form, draft); assert.deepEqual(f.state.savedSettings.value, baseline)
  assert.deepEqual(f.state.diagnostics.value, runtime); assert.equal(f.state.authValue.value, 'typed-code')
  assert.equal(f.state.loading.value, false); assert.equal(f.state.busy.value, '')
})

test('edits made after a navigation read starts cannot be replaced by the older server snapshot', async t => {
  const f = mount(t), baseline = copy(f.state.savedSettings.value), response = deferred()
  f.state.navigate('library'); f.expectSettings(response.promise, diagnostics('read')); f.state.navigate('settings')
  Object.assign(f.state.form, { host: 'edit-during-read', apiHash: 'new-secret', password: 'new-password' }); f.state.authValue.value = 'new-auth-value'
  const draft = copy(f.state.form); response.resolve(settings({ apiId: 3003 })); await flush()
  assert.deepEqual(f.state.form, draft); assert.deepEqual(f.state.savedSettings.value, baseline); assert.equal(f.state.authValue.value, 'new-auth-value')
})

test('responses arriving after leaving settings are discarded without applying settings or diagnostics', async t => {
  const f = mount(t), baseline = copy(f.state.savedSettings.value), runtime = copy(f.state.diagnostics.value), response = deferred(), local = deferred()
  f.state.navigate('tasks'); f.expectSettings(response.promise, local.promise); f.state.navigate('settings'); f.state.navigate('library')
  response.resolve(settings({ apiId: 4004 })); local.resolve(diagnostics('late')); await flush()
  assert.deepEqual(f.state.savedSettings.value, baseline); assert.deepEqual(f.state.diagnostics.value, runtime)
  assert.equal(f.state.page.value, 'library'); assert.equal(f.calls.length, 2); assert.equal(f.timers.size, 0)
})

test('a newer applySettings save response prevents an older navigation read from changing either form or baseline', async t => {
  const f = mount(t), response = deferred(), local = deferred(), saved = settings({ apiId: 5005 })
  f.state.navigate('library'); f.expectSettings(response.promise, local.promise); f.state.navigate('settings')
  f.state.applySettings(saved); Object.assign(f.state.form, { apiHash: 'post-save-edit', password: 'post-save-password' }); const draft = copy(f.state.form)
  response.resolve(settings({ apiId: 1111 })); local.resolve(diagnostics('older-read')); await flush()
  assert.deepEqual(f.state.savedSettings.value, saved); assert.deepEqual(f.state.form, draft)
  assert.equal(f.calls.length, 2); assert.equal(f.timers.size, 0)
})

test('failed entry reads keep saved settings, diagnostics and draft and never automatically retry', async t => {
  const f = mount(t), baseline = copy(f.state.savedSettings.value), runtime = copy(f.state.diagnostics.value), draft = copy(f.state.form)
  f.state.navigate('tasks'); f.expectSettings(new Error('settings unavailable'), new Error('diagnostics unavailable')); f.state.navigate('settings'); await flush()
  assert.deepEqual(f.state.savedSettings.value, baseline); assert.deepEqual(f.state.diagnostics.value, runtime); assert.deepEqual(f.state.form, draft)
  assert.match(f.state.error.value, /settings unavailable/); assert.equal(f.calls.length, 2)
  await flush(); assert.equal(f.calls.length, 2); assert.equal(f.timers.size, 0); assert.equal(f.state.loading.value, false); assert.equal(f.state.busy.value, '')
})

test('task sidebar return keeps the same unkeyed mounted component and category while only active changes', async t => {
  const f = mount(t), initial = component(f.render(), 'TransferTasks'), nonce = f.state.taskRefresh.value
  assert.ok(initial); assert.equal(initial.key, null); assert.equal(initial.props.active, false)
  f.state.taskCategory.value = 'upload'; f.state.navigate('tasks'); await flush()
  const shown = component(f.render(), 'TransferTasks'); assert.equal(shown.type, initial.type); assert.equal(shown.key, initial.key)
  assert.equal(shown.props.active, true); assert.equal(shown.props.category, 'upload')
  assert.ok(shown.testDirectives?.some(([, value]) => value === true), 'the task center is shown with v-show')
  f.state.navigate('rules'); await flush(); const hidden = component(f.render(), 'TransferTasks')
  assert.equal(hidden.type, initial.type); assert.equal(hidden.key, initial.key); assert.equal(hidden.props.active, false)
  f.state.navigate('tasks'); await flush(); const returned = component(f.render(), 'TransferTasks')
  assert.equal(returned.type, initial.type); assert.equal(returned.key, initial.key); assert.equal(returned.props.active, true)
  assert.equal(returned.props.category, 'upload'); assert.equal(f.state.taskRefresh.value, nonce); assert.deepEqual(f.calls, [])
})
