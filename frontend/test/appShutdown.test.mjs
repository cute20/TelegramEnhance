import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as categories from '../src/taskCategories.ts'

const { descriptor, errors } = parse(readFileSync(new URL('../src/App.vue', import.meta.url), 'utf8'), { filename: 'App.vue' })
assert.deepEqual(errors, [])
const code = ts.transpileModule(compileScript(descriptor, { id: 'app-shutdown' }).content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
class ApiError extends Error { constructor(message, status) { super(message); this.status = status } }
function mount(t) {
  const calls = [], responses = [], timers = new Map(), hooks = []
  let clearedTokens = 0, serial = 0, dialogs = 0, thumbnailInvalidations = 0
  const api = async (path, options) => { calls.push({ path, options }); assert.ok(responses.length, 'Unexpected request'); const value = responses.shift(); if (value instanceof Error) throw value; return value }
  const modules = { vue: { ...vue, onMounted() {}, onBeforeUnmount: hook => hooks.push(hook) }, './taskCategories': categories,
    './api': { api, ApiError, hasAppToken: true, clearAppToken() { clearedTokens++ } }, './thumbnailMemory': { invalidateThumbnails() { thumbnailInvalidations++ } } }
  const module = { exports: {} }, fakeWindow = { setTimeout(fn) { timers.set(++serial, fn); return serial }, clearTimeout(id) { timers.delete(id) }, clearInterval() {} }
  new Function('require', 'module', 'exports', 'window', code)(name => { if (name.endsWith('.vue')) return {}; assert.ok(Object.hasOwn(modules, name), name); return modules[name] }, module, module.exports, fakeWindow)
  const scope = vue.effectScope(), state = scope.run(() => module.exports.default.setup({}, { expose() {} }))
  state.loading.value = false; state.serviceReachable.value = true
  state.dialog.value = { showModal() { dialogs++ }, close() {} }
  t.after(() => { hooks.forEach(hook => hook()); scope.stop(); assert.equal(responses.length, 0) })
  return { state, calls, responses, timers, clearedTokens: () => clearedTokens, dialogs: () => dialogs, thumbnailInvalidations: () => thumbnailInvalidations }
}
test('one click requests only graceful project exit, without saving settings or logging out', async t => {
  const f = mount(t); f.state.authValue.value = 'sensitive'; f.state.form.apiHash = 'sensitive'; f.state.form.password = 'sensitive'
  f.state.schedulePoll(); assert.equal(f.timers.size, 1)
  f.responses.push({ message: 'closing' }); await f.state.exitProject()
  assert.deepEqual(f.calls, [{ path: '/app/exit', options: { method: 'POST' } }]); assert.equal(f.dialogs(), 0)
  assert.equal(f.state.exited.value, true); assert.equal(f.state.serviceReachable.value, false); assert.equal(f.clearedTokens(), 1)
  assert.equal(f.state.authValue.value, ''); assert.equal(f.state.form.apiHash, ''); assert.equal(f.state.form.password, '')
  f.state.schedulePoll(); assert.equal(f.timers.size, 0); assert.equal(f.state.connectionLabel.value, '已请求关闭项目')
})
test('repeated clicks while closing or after acknowledgement cannot send another exit request', async t => {
  const f = mount(t); let resolve; f.responses.push(new Promise(yes => { resolve = yes }))
  const pending = f.state.exitProject(); assert.equal(f.state.busy.value, 'exit'); await f.state.exitProject(); assert.equal(f.calls.length, 1)
  resolve({}); await pending; await f.state.exitProject(); assert.equal(f.calls.length, 1)
})
test('an unconfirmed exit retains the token, shows failure and only resumes status polling', async t => {
  const f = mount(t); f.responses.push(new Error('Connection interrupted')); await f.state.exitProject()
  assert.equal(f.state.exited.value, false); assert.equal(f.clearedTokens(), 0); assert.match(f.state.error.value, /Connection interrupted/)
  assert.equal(f.calls.length, 1); assert.equal(f.timers.size, 1)
})
test('unavailable service or another action blocks shutdown; logout still requires its own dialog', async t => {
  const f = mount(t); f.state.busy.value = 'save'; await f.state.exitProject(); f.state.busy.value = ''; f.state.serviceReachable.value = false
  await f.state.exitProject(); assert.equal(f.calls.length, 0)
  f.state.serviceReachable.value = true; await f.state.openConfirmation('logout'); assert.equal(f.dialogs(), 1); assert.equal(f.calls.length, 0)
  f.state.closeConfirmation(); assert.equal(f.calls.length, 0)
})

test('thumbnail memory clears on identity and authorization boundaries without clearing on status polling or navigation', t => {
  const f=mount(t),status={authState:'READY',connectionState:'READY',userId:'1',nativeLoaded:true,nativeVersion:'synthetic',displayName:'first'}
  f.state.applyTelegram(status);assert.equal(f.thumbnailInvalidations(),1)
  f.state.applyTelegram({...status,displayName:'updated',connectionState:'UPDATING'});f.state.applyTelegram({...status})
  f.state.serviceReachable.value=false;f.state.serviceReachable.value=true
  f.state.navigate('tasks');f.state.navigate('rules');assert.equal(f.thumbnailInvalidations(),1)
  f.state.applyTelegram({...status,authState:'CLOSED'});assert.equal(f.thumbnailInvalidations(),2)
  f.state.applyTelegram({...status,authState:'CLOSED'});assert.equal(f.thumbnailInvalidations(),2)
  f.state.applyTelegram(status);assert.equal(f.thumbnailInvalidations(),2)
  f.state.applyTelegram({...status,userId:'2'});assert.equal(f.thumbnailInvalidations(),3)
  f.state.applyTelegram({...status,userId:null,authState:'DISCONNECTED'});assert.equal(f.thumbnailInvalidations(),4,'simultaneous identity and auth changes are one boundary')
  f.state.accessInvalid.value=true;f.state.accessInvalid.value=true;assert.equal(f.thumbnailInvalidations(),5)
  f.state.exited.value=true;f.state.exited.value=true;assert.equal(f.thumbnailInvalidations(),6)
})
