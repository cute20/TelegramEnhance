import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { compileScript, parse } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as library from '../src/library.ts'

const parsed = parse(readFileSync(new URL('../src/components/ThumbnailCacheSettings.vue', import.meta.url), 'utf8'), { filename: 'ThumbnailCacheSettings.vue' })
assert.deepEqual(parsed.errors, [])
const code = ts.transpileModule(compileScript(parsed.descriptor, { id: 'cache-settings' }).content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
const flush = async () => { await vue.nextTick(); await new Promise(setImmediate) }
const view = (overrides = {}) => ({ directory: 'D:\\project\\thumbnail-cache', defaultDirectory: 'D:\\project\\thumbnail-cache', files: 2, bytes: 1234, maximumBytes: 268435456, warning: null, ...overrides })
class ApiError extends Error { constructor(message, status) { super(message); this.status = status } }
function mount(t, first = view()) {
  const calls = [], responses = [first], hooks = [], events = [], props = vue.reactive({ available: true })
  const api = async (path, options = {}) => { calls.push({ path, ...options }); assert.ok(responses.length, 'Unexpected request'); const result = responses.shift(); if (result instanceof Error) throw result; return result }
  const modules = { vue: { ...vue, onBeforeUnmount: hook => hooks.push(hook) }, '../api': { api, ApiError }, '../library': library }
  const module = { exports: {} }
  new Function('require', 'module', 'exports', code)(name => { if (name.endsWith('.vue')) return {}; assert.ok(Object.hasOwn(modules, name), name); return modules[name] }, module, module.exports)
  const scope = vue.effectScope(), state = scope.run(() => module.exports.default.setup(props, { expose() {}, emit: (...event) => events.push(event) }))
  let disposed = false
  const destroy = () => { if (!disposed) { disposed = true; hooks.forEach(hook => hook()); scope.stop() } }
  t.after(() => { destroy(); assert.equal(responses.length, 0) })
  return { state, calls, responses, props, events, destroy }
}
test('cache settings read independently and changing directory never touches credentials or Telegram', async t => {
  const f = mount(t); await flush(); assert.equal(f.state.directory.value, view().directory)
  f.state.directory.value = 'D:\\images'; f.responses.push(view({ directory: 'D:\\images', files: 0, bytes: 0 })); await f.state.perform('save')
  assert.deepEqual(f.calls, [{ path: '/settings/thumbnail-cache' }, { path: '/settings/thumbnail-cache', method: 'PUT', body: { directory: 'D:\\images' } }])
  assert.equal(f.state.saved.value.directory, 'D:\\images'); assert.match(f.state.notice.value, /已更新/)
})
test('clear is explicit, serial and updates counts only after its own receipt', async t => {
  const f = mount(t); await flush(); assert.equal(f.calls.length, 1)
  let resolve; f.responses.push(new Promise(yes => { resolve = yes }))
  const clearing = f.state.perform('clear'); assert.equal(f.state.saved.value.files, 2)
  await f.state.perform('clear'); assert.equal(f.calls.length, 2)
  resolve({ cache: view({ files: 0, bytes: 0 }), removed: 2 }); await clearing
  assert.equal(f.state.saved.value.files, 0); assert.equal(f.state.saved.value.bytes, 0)
  assert.deepEqual(f.calls[1], { path: '/settings/thumbnail-cache/clear', method: 'POST' }); assert.match(f.state.notice.value, /已清理/)
})
test('unknown saves are not automatically resent and require a GET before another mutation', async t => {
  const f = mount(t); await flush(); f.state.directory.value = 'D:\\images'; f.responses.push(new Error('Timeout'))
  await f.state.perform('save'); assert.equal(f.state.uncertain.value, true); assert.match(f.state.error.value, /核对结果/)
  await f.state.perform('save'); await f.state.perform('clear'); assert.equal(f.calls.length, 2)
  f.responses.push(view({ directory: 'D:\\images' })); await f.state.perform('load')
  assert.equal(f.state.uncertain.value, false); assert.equal(f.state.directory.value, 'D:\\images'); assert.deepEqual(f.calls[2], { path: '/settings/thumbnail-cache' })
})
test('invalid paths preserve the input and do not pretend the cache was cleared', async t => {
  const f = mount(t); await flush(); f.state.directory.value = 'relative'; f.responses.push(new ApiError('请选择绝对目录', 400))
  await f.state.perform('save'); assert.equal(f.state.directory.value, 'relative'); assert.equal(f.state.saved.value.files, 2)
  assert.equal(f.state.uncertain.value, false); assert.equal(f.state.notice.value, ''); assert.match(f.state.error.value, /绝对目录/)
})
test('leaving settings during clear ignores its late response', async t => {
  const f = mount(t); await flush(); let resolve; f.responses.push(new Promise(yes => { resolve = yes }))
  const clearing = f.state.perform('clear'); f.destroy(); resolve({ cache: view({ files: 0, bytes: 0 }), removed: 2 }); await clearing
  assert.equal(f.state.saved.value.files, 2); assert.equal(f.state.notice.value, '')
})
