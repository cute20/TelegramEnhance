import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as rules from '../src/downloadRules.ts'
import * as channels from '../src/downloadChannel.ts'
import * as tasks from '../src/downloadTasks.ts'
import * as ruleModule from '../src/rules.ts'

const copy = value => JSON.parse(JSON.stringify(value))
const flush = async () => { await vue.nextTick(); await new Promise(setImmediate) }
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes }); return { promise, resolve } }
const BASE = '/rules/download'
const selection = overrides => ({ entry: 'CHANNEL', source: '-1001', tags: '旅行', keywords: '山', types: ['PHOTO'],
  includeMain: true, targetDirectory: 'D:\\synthetic-downloads', suggestedName: '合成频道', ...overrides })
const saved = input => ({
  rule: { id: '00000000-0000-0000-0000-000000000001', accountId: '1', version: 1, revision: 1,
    enabled: false, nextScanAt: 30, createdAt: 1, updatedAt: 2, channelId: '-1001', channelTitle: '合成频道',
    input: { ...copy(input), source: input.sourceMode === 'POST' ? 'https://t.me/c/1/9' : '-1001',
      ...(input.sourceMode === 'POST' ? { postKey: '1:-1001:message:9437184', mainMessageId: '9437184' } : {}) } },
  summary: { historyDone: false, scanned: 0, matched: 0, unresolved: 0, commentPages: 0, media: 0, completed: 0,
    nextScanAt: 30, scanError: null, job: null }, state: 'PAUSED', busy: false, nextAllowedAt: 0,
})
const parsed = parse(readFileSync(new URL('../src/components/DownloadRules.vue', import.meta.url), 'utf8'), { filename: 'DownloadRules.vue' })
assert.deepEqual(parsed.errors, [])
const compiled = ts.transpileModule(compileScript(parsed.descriptor, { id: 'download-creation-rule-test' }).content, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText
class ApiError extends Error { constructor(message, status) { super(message); this.status = status } }
function mount(t, initial = selection()) {
  const props = vue.reactive({ authorized: true, available: true, accountId: '1', createOnly: true, embedded: true, selection: initial })
  const calls = [], steps = [], events = [], mounts = [], unmounts = [], failures = []
  const api = async (path, options = {}) => {
    calls.push({ path, options: copy(options) })
    const expected = steps.shift()
    try { assert.ok(expected, path); assert.equal(path, expected.path); assert.equal(options.method ?? 'GET', expected.method ?? 'GET') }
    catch (cause) { failures.push(cause); throw cause }
    if (expected.value instanceof Error) throw expected.value
    return await expected.value
  }
  const modules = { vue: { ...vue, onMounted: hook => mounts.push(hook), onBeforeUnmount: hook => unmounts.push(hook) },
    '../api': { api, ApiError }, '../downloadRules': rules, '../downloadChannel': channels,
    '../downloadTasks': tasks, '../rules': ruleModule }
  const module = { exports: {} }
  new Function('require', 'module', 'exports', compiled)(name => {
    if (name.endsWith('.vue')) return {}
    assert.ok(Object.hasOwn(modules, name), name); return modules[name]
  }, module, module.exports)
  const scope = vue.effectScope()
  const state = scope.run(() => module.exports.default.setup(props, { expose() {}, emit: (...args) => events.push(args) }))
  scope.run(() => mounts.forEach(hook => hook()))
  t.after(() => { unmounts.forEach(hook => hook()); scope.stop(); assert.deepEqual(failures, []); assert.equal(steps.length, 0) })
  return { state, props, calls, events, expect: (path, value, method = 'GET') => steps.push({ path, value, method }) }
}

test('shared selection initializes the rule after create-only mount without duplicate discovery or list reads', async t => {
  const f = mount(t); await flush()
  assert.equal(f.state.formOpen.value, true)
  assert.equal(f.state.form.source, '-1001'); assert.equal(f.state.form.sourceMode, 'CHANNEL')
  assert.equal(f.state.form.name, '合成频道'); assert.equal(f.state.form.tags, '旅行'); assert.equal(f.state.form.keywords, '山')
  assert.deepEqual(f.state.form.types, ['PHOTO']); assert.equal(f.state.form.includeMain, true)
  assert.equal(f.state.form.targetDirectory, 'D:\\synthetic-downloads')
  await f.state.loadChannels(); assert.deepEqual(f.calls, [])
})

test('shared post and manual sources map to existing backend scopes and preserve the parent channel filters', async t => {
  const f = mount(t); await flush()
  Object.assign(f.props.selection, { entry: 'POST', source: 'https://t.me/c/1/9', types: [], includeMain: true, targetDirectory: 'D:\\next' })
  await flush()
  assert.equal(f.state.form.sourceMode, 'POST'); assert.equal(f.state.form.source, 'https://t.me/c/1/9')
  assert.equal(f.state.form.tags, ''); assert.equal(f.state.form.keywords, '')
  assert.equal(f.props.selection.tags, '旅行'); assert.equal(f.props.selection.keywords, '山')
  assert.deepEqual(f.state.form.types, []); assert.equal(f.state.form.targetDirectory, 'D:\\next')
  Object.assign(f.props.selection, { entry: 'MANUAL', source: '@synthetic' }); await flush()
  assert.equal(f.state.form.sourceMode, 'CHANNEL'); assert.equal(f.state.sourceEntry.value, 'MANUAL')
  assert.equal(f.state.form.source, '@synthetic'); assert.equal(f.state.form.tags, '旅行')
  assert.deepEqual(f.calls, [])
})

test('suggested rule names follow a new source until the user edits the name', async t => {
  const f = mount(t); await flush()
  f.props.selection.suggestedName = '新的合成频道'; await flush()
  assert.equal(f.state.form.name, '新的合成频道')
  f.state.form.name = '手动规则名称'; f.state.markNameEdited()
  f.props.selection.suggestedName = '另一个合成频道'; await flush()
  assert.equal(f.state.form.name, '手动规则名称')
  assert.deepEqual(f.calls, [])
})

test('shared rule save submits only the frozen selection and saves paused without creating a transfer or enabling it', async t => {
  const f = mount(t, selection({ entry: 'POST', source: 'https://t.me/c/1/9', types: [], includeMain: true })); await flush()
  f.state.form.window = { mode: 'WINDOW', start: '22:00', end: '08:00', timezone: 'Asia/Shanghai' }
  const input = copy(f.state.form); f.expect(BASE, saved(input), 'POST')
  await f.state.save()
  assert.equal(f.calls.length, 1); assert.equal(f.calls[0].path, BASE)
  assert.deepEqual(f.calls[0].options.body.input, input)
  assert.equal(f.state.items.value[0].rule.enabled, false)
  assert.equal(f.state.formOpen.value, false); assert.ok(f.events.some(event => event[0] === 'saved' && event[1] === 'download'))
  assert.equal(f.events.filter(event => event[0] === 'locked').at(-1)[1], false)
})

test('no selected comment or main media cannot be silently broadened to all types or submitted', async t => {
  const f = mount(t, selection({ types: [], includeMain: false })); await flush()
  await f.state.save()
  assert.deepEqual(f.state.form.types, []); assert.deepEqual(f.calls, [])
  assert.match(f.state.error.value, /选择评论媒体/)
})

test('an unknown save keeps its original operation and snapshot while shared fields change and only explicit GET can resolve it', async t => {
  const f = mount(t); await flush()
  const input = copy(f.state.form), response = deferred(); f.expect(BASE, response.promise, 'POST')
  const pending = f.state.save(); const operationId = f.calls[0].options.body.operationId
  f.props.available = false; await flush()
  assert.ok(f.state.uncertain.value); assert.equal(f.state.uncertain.value.operationId, operationId)
  const locks = f.events.filter(event => event[0] === 'locked').map(event => event[1])
  assert.equal(locks[0], false); assert.ok(locks.length > 1 && locks.slice(1).every(Boolean), 'unknown must replace the pending lock without a transient unlock')
  Object.assign(f.props.selection, { source: '-1002', suggestedName: '下一频道', targetDirectory: 'D:\\changed' }); await flush()
  assert.equal(f.state.form.source, input.source); assert.equal(f.state.form.targetDirectory, input.targetDirectory)
  response.resolve(saved(input)); await pending
  assert.equal(f.state.items.value.length, 0, 'late original success must not resolve a disconnected operation')
  f.props.available = true; await flush(); await f.state.save()
  assert.equal(f.calls.length, 1, 'recovery must not repost or create a new operation')
  f.expect(`${BASE}/by-operation/${operationId}`, saved(input)); await f.state.lookup()
  assert.equal(f.calls.length, 2); assert.equal(f.calls[1].options.method, undefined)
  assert.equal(f.state.uncertain.value, null); assert.equal(f.state.formOpen.value, false)
  assert.equal(f.state.items.value[0].rule.input.source, input.source)
  assert.equal(f.events.filter(event => event[0] === 'locked').at(-1)[1], false)
})
