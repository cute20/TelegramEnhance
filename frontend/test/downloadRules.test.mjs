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

const ID = '00000000-0000-0000-0000-000000000001', TASK = '00000000-0000-0000-0000-000000000002'
const BASE = '/rules/download', STATUS = BASE + '/' + ID
const copy = value => JSON.parse(JSON.stringify(value))
const flush = async () => { await vue.nextTick(); await new Promise(setImmediate) }
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes }); return { promise, resolve } }
const input = () => ({ name: '合成频道', source: '-1001', tags: '旅行 北京', keywords: '山 海', types: ['VIDEO'], includeMain: false,
  targetDirectory: 'D:\\synthetic-downloads', window: { mode: 'ALL_DAY', start: null, end: null, timezone: 'Asia/Shanghai' } })
const job = overrides => ({ taskId: TASK, ruleVersion: 1, state: 'PAUSED', total: 2, completed: 0, error: null, manual: false, held: false, ...overrides })
function view(overrides = {}) {
  const rule = { id: ID, accountId: '1', version: 1, revision: 1, enabled: false, nextScanAt: 30, createdAt: 1, updatedAt: 2,
    input: input(), channelId: '-1001', channelTitle: '合成频道' }
  const summary = { historyDone: false, scanned: 0, matched: 0, unresolved: 0, commentPages: 0, media: 0, completed: 0,
    nextScanAt: 30, scanError: null, job: null }
  return { state: 'PAUSED', busy: false, nextAllowedAt: 0, ...overrides,
    rule: { ...rule, ...overrides.rule, input: { ...rule.input, ...overrides.rule?.input } }, summary: { ...summary, ...overrides.summary } }
}
const enabled = overrides => view({ rule: { enabled: true, revision: 2 }, state: 'WAITING', ...overrides })
const listing = items => ({ items, intervalSeconds: 30 })

test('download rule validation keeps account/channel identity, bounded exact types and frozen versions', () => {
  const previous = view()
  assert.equal(rules.validDownloadRule(previous, '1'), true)
  assert.equal(rules.validDownloadRule(previous, '2'), false)
  assert.equal(rules.validDownloadRule(view({ rule: { channelId: '-1002' } }), '1'), false)
  assert.equal(rules.validDownloadRule(view({ rule: { channelId: -1001 } }), '1'), false)
  assert.equal(rules.validDownloadRule(view({ rule: { input: { types: ['VIDEO', 'VIDEO'] } } }), '1'), false)
  assert.equal(rules.validDownloadRule(view({ rule: { input: { types: ['UNKNOWN'] } } }), '1'), false)
  assert.equal(rules.validDownloadRule(view({ rule: { input: { includeMain: 'true' } } }), '1'), false)
  assert.equal(rules.validDownloadRule(view({ rule: { input: { tags: 'new' } } }), '1', previous), false)
  assert.equal(rules.validDownloadRule(view({ rule: { version: 2, revision: 2, input: { tags: 'new' } } }), '1', previous), true)
  assert.equal(rules.validDownloadRule(view({ rule: { revision: 1 } }), '1', enabled()), false)
})
test('unresolved is not a failure or completion: optional errors and actual saved counts stay separate', () => {
  const value = enabled({ summary: { historyDone: true, scanned: 10, matched: 8, unresolved: 8, media: 50, completed: 1, errors: 0 } })
  assert.equal(rules.validDownloadRule(value, '1'), true)
  assert.equal(rules.validDownloadRule(view({ summary: { errors: 1, unresolved: 0 } }), '1'), false)
  assert.equal(rules.validDownloadRule(view({ summary: { completed: Number.MAX_SAFE_INTEGER + 1 } }), '1'), false)
  assert.equal(rules.validDownloadRule(view({ summary: { job: job({ state: 'COMPLETED', completed: 1 }) } }), '1'), false)
  assert.equal(rules.validDownloadRule(view({ summary: { job: job({ completed: 3 }) } }), '1'), false)
  assert.equal(rules.validDownloadRule(view({ state: 'COMPLETED' }), '1'), false, 'A persistent rule has no whole-channel complete state')
})
test('window accepts cross-midnight and requires actual Shanghai times rather than browser-zone guesses', () => {
  const value = input(); value.window = { mode: 'WINDOW', start: '22:00', end: '08:00', timezone: 'Asia/Shanghai' }
  assert.equal(rules.validDownloadRuleInput(value), true)
  value.window.end = '22:00'; assert.equal(rules.validDownloadRuleInput(value), false)
  value.window.end = '25:00'; assert.equal(rules.validDownloadRuleInput(value), false)
  value.window.end = '08:00'; value.window.timezone = 'UTC'; assert.equal(rules.validDownloadRuleInput(value), false)
})
const postInput = () => ({ ...input(), sourceMode: 'POST', source: 'https://t.me/c/1/9', tags: '', keywords: '',
  postKey: '1:-1001:album:99', mainMessageId: '9437184' })
test('post rules validate canonical post identity separately from old channel rules', () => {
  assert.equal(rules.downloadRuleSourceMode(input()), 'CHANNEL')
  assert.equal(rules.validDownloadRule(view({ rule: { input: postInput() } }), '1'), true)
  for (const patch of [{ postKey: '2:-1001:album:99' }, { mainMessageId: 9437184 }, { postKey: null },
    { source: 'https://t.me/c/2/9' }, { source: 'https://example.com/c/1/9' }, { tags: '旅行' }, { sourceMode: 'ANY' }]) {
    assert.equal(rules.validDownloadRule(view({ rule: { input: { ...postInput(), ...patch } } }), '1'), false, JSON.stringify(patch))
  }
  for (const source of ['https://t.me/c/1/9?single', 'https://telegram.me/synthetic/9?single=1', 'https://t.me/synthetic/9']) assert.equal(rules.validDownloadPostSource(source), true)
  for (const source of ['https://t.me/synthetic', 'https://t.me/c/1/9?comment=8', 'https://t.me/c/1/9?single&single', 'https://t.me/c/1/9/', 'https://t.me@evil.test/name/9', 'https://t.me/c/1/9#hash']) assert.equal(rules.validDownloadPostSource(source), false)
  assert.equal(rules.sameDownloadRuleSelection(input(), postInput()), false)
  assert.equal(rules.sameDownloadRuleInput(input(), { ...input(), sourceMode: 'CHANNEL' }), true)
})
test('no comment type means none; only main inclusion permits an empty selection to save', () => {
  assert.equal(rules.validDownloadRuleInput({ ...input(), types: [] }), false)
  assert.equal(rules.validDownloadRuleInput({ ...input(), types: [], includeMain: true }), true)
  assert.equal(rules.validDownloadRuleInput({ ...postInput(), types: [], includeMain: true }), true)
})
test('explicit metadata check respects paused/window/busy gates and permits attention without forcing a task resume', () => {
  assert.equal(rules.canCheckDownloadRule(view()), false)
  assert.equal(rules.canCheckDownloadRule(enabled()), true)
  assert.equal(rules.canCheckDownloadRule(enabled({ state: 'WAITING_WINDOW' })), false)
  assert.equal(rules.canCheckDownloadRule(enabled({ state: 'SCANNING', busy: true })), false)
  assert.equal(rules.canCheckDownloadRule(enabled({ state: 'ATTENTION', summary: { job: job({ state: 'FAILED', held: true }) } })), true)
  assert.equal(rules.canTakeoverDownloadRule(view({ summary: { job: job() } })), true)
  assert.equal(rules.canTakeoverDownloadRule(view({ summary: { job: job({ state: 'RUNNING' }) } })), false)
  assert.equal(rules.canTakeoverDownloadRule(view({ summary: { job: job({ manual: true }) } })), false)
})

const parsed = parse(readFileSync(new URL('../src/components/DownloadRules.vue', import.meta.url), 'utf8'), { filename: 'DownloadRules.vue' })
assert.deepEqual(parsed.errors, [])
const compiled = ts.transpileModule(compileScript(parsed.descriptor, { id: 'download-rules-test' }).content, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText
class ApiError extends Error { constructor(message, status) { super(message); this.status = status } }
function mount(t, initial = view(), overrides = {}, cachedChannels = true) {
  const props = vue.reactive({ authorized: true, available: true, accountId: '1', ...overrides })
  const accessible = props.authorized && props.available && props.accountId
  const steps = accessible ? [{ path: BASE, value: initial instanceof Promise || initial instanceof Error ? initial : listing(Array.isArray(initial) ? initial : [initial]) }] : []
  const calls = [], failures = [], hooks = [], timers = [], events = []
  let active = 0, maxActive = 0
  const api = async (path, options = {}) => {
    calls.push({ path, options }); active++; maxActive = Math.max(maxActive, active)
    const expected = steps.shift()
    try {
      try { assert.ok(expected, path); assert.equal(path, expected.path); assert.equal(options.method ?? 'GET', expected.method ?? 'GET'); if (expected.body) assert.deepEqual(options.body, expected.body) }
      catch (cause) { failures.push(cause); throw cause }
      if (expected.value instanceof Error) throw expected.value
      return await expected.value
    } finally { active-- }
  }
  const modules = { vue: { ...vue, onBeforeUnmount: hook => hooks.push(hook) }, '../api': { api, ApiError }, '../downloadRules': rules,
    '../downloadChannel': channels, '../downloadTasks': tasks, '../rules': ruleModule }
  const module = { exports: {} }
  new Function('require', 'module', 'exports', 'setTimeout', 'clearTimeout', compiled)(name => {
    if (name.endsWith('.vue')) return {}
    assert.ok(Object.hasOwn(modules, name), name); return modules[name]
  }, module, module.exports, (callback, delay) => { const timer = { callback, delay, cancelled: false }; timers.push(timer); return timer }, timer => { if (timer) timer.cancelled = true })
  const scope = vue.effectScope()
  const state = scope.run(() => module.exports.default.setup(props, { expose() {}, emit: (...args) => events.push(args) }))
  // Most behavior tests reuse a successfully loaded channel list. Discovery
  // tests opt out to exercise the actual first-open automatic request.
  if (cachedChannels) state.channels.value = { items: [{ id: '-1001', title: '合成频道' }], scope: 'READABLE_CHANNELS', partial: false, warnings: [] }
  let dead = false
  const destroy = () => { if (!dead) { dead = true; hooks.forEach(hook => hook()); scope.stop() } }
  t.after(() => { destroy(); assert.deepEqual(failures, []); assert.equal(steps.length, 0) })
  return { state, props, calls, timers, events, destroy, maxActive: () => maxActive,
    expect: (path, value, method = 'GET', body) => steps.push({ path, value, method, body }) }
}
const fill = state => { state.edit(null); Object.assign(state.form, input()) }

test('source mode changes clear stale sources and filters while preserving chosen media and manual name', async t => {
  const f = mount(t); await flush(); fill(f.state)
  f.state.form.includeMain = true; f.state.form.types = ['PHOTO']; f.state.changeSourceMode('POST')
  assert.equal(f.state.sourceMode.value, 'POST'); assert.equal(f.state.form.source, '')
  assert.equal(f.state.form.tags, ''); assert.equal(f.state.form.keywords, '')
  assert.equal(f.state.form.name, '合成频道'); assert.deepEqual(f.state.form.types, ['PHOTO']); assert.equal(f.state.form.includeMain, true)
  f.state.form.source = postInput().source; f.state.form.postKey = postInput().postKey
  f.state.changeSourceMode('CHANNEL'); assert.equal(f.state.form.source, ''); assert.equal(f.state.form.postKey, null)
  f.state.edit(view()); assert.equal(f.state.sourceMode.value, 'CHANNEL'); assert.equal(f.state.form.postKey, null)
})
test('empty selections never submit and post source saves paused without invoking channel discovery', async t => {
  const f = mount(t, []); await flush(); fill(f.state); f.state.form.types = []
  await f.state.save(); assert.equal(f.calls.length, 1); assert.match(f.state.error.value, /选择评论媒体/)
  Object.assign(f.state.form, postInput(), { types: [], includeMain: true })
  f.expect(BASE, view({ rule: { input: { ...postInput(), types: [], includeMain: true } } }), 'POST')
  await f.state.save(); assert.equal(f.state.formOpen.value, false)
  assert.deepEqual(f.calls.map(item => item.path), [BASE, BASE]); assert.equal(f.state.items.value[0].rule.enabled, false)
})
test('unknown edit remains recoverable after closing dialog and cannot clear on an old GET version', async t => {
  const f = mount(t); await flush(); f.state.edit(f.state.items.value[0]); f.state.form.types = ['PHOTO']
  const response = deferred(); f.expect(STATUS, response.promise, 'PUT'); const saving = f.state.save()
  f.state.closeForm(); assert.equal(f.state.formOpen.value, true, 'busy save cannot close')
  response.resolve(Promise.reject(new Error('timeout'))); await saving
  f.state.closeForm(); assert.equal(f.state.formOpen.value, false); assert.ok(f.state.uncertain.value)
  f.state.reopenForm(); assert.equal(f.state.formOpen.value, true); assert.deepEqual(f.state.form.types, ['PHOTO'])
  f.expect(STATUS, view()); await f.state.lookup(); assert.ok(f.state.uncertain.value); assert.equal(f.state.formOpen.value, true)
  f.expect(STATUS, view({ rule: { version: 2, revision: 2, input: { types: ['PHOTO'] } } })); await f.state.lookup()
  assert.equal(f.state.uncertain.value, null); assert.equal(f.state.formOpen.value, false)
})

test('mount reads rules once without any timer; further list reads require an explicit refresh', async t => {
  const f = mount(t); await flush()
  assert.equal(f.calls.length, 1); assert.equal(f.timers.length, 0)
  f.expect(BASE, { ...listing([view()]), intervalSeconds: 2 }); await f.state.refresh()
  assert.deepEqual(f.calls.map(call => call.path), [BASE, BASE]); assert.ok(f.calls.every(call => !call.options.method))
  assert.equal(f.state.intervalSeconds.value, 2)
  assert.match(parsed.descriptor.template.content, /:default-expanded="false"/)
})
test('creating saves paused with one operation ID, forwards AND text intact and does not enable or download', async t => {
  const f = mount(t, []); await flush(); fill(f.state)
  f.state.form.tags = '＃旅行，北京'; f.state.form.keywords = '山, 海'; f.state.form.includeMain = true
  f.state.form.targetDirectory = 'D:/synthetic-downloads/'
  const saved = view({ rule: { input: { tags: '旅行 北京', keywords: '山 海', includeMain: true } } })
  f.expect(BASE, saved, 'POST'); await f.state.save()
  const body = f.calls.at(-1).options.body
  assert.match(body.operationId, /^[0-9a-f-]{36}$/); assert.equal(body.input.tags, '＃旅行，北京'); assert.equal(body.input.keywords, '山, 海')
  assert.equal(body.input.includeMain, true); assert.equal(f.state.items.value[0].rule.enabled, false)
  assert.equal(f.state.formOpen.value, false); assert.match(f.state.notice.value, /暂停/); assert.equal(f.calls.length, 2)
})
test('selected channels use string identity; all and last media selection toggle off independently of main media', async t => {
  const f = mount(t); await flush(); f.state.edit(null)
  const available = { scope: 'READABLE_CHANNELS', items: [{ id: '-1001', title: '合成频道' }], partial: true, warnings: ['列表有界'] }
  f.expect('/transfers/download/channels', available); await f.state.loadChannels(); f.state.chooseSource('-1001')
  assert.equal(f.state.form.source, '-1001'); assert.equal(f.state.form.name, '合成频道')
  f.state.form.includeMain = true; f.state.toggleType('VIDEO')
  assert.equal(f.state.form.types.length, 0); assert.equal(f.state.form.includeMain, true)
  f.state.toggleAllTypes(); assert.equal(f.state.form.types.length, 5)
  f.state.toggleType('PHOTO'); assert.equal(f.state.form.types.includes('PHOTO'), false); assert.equal(f.state.form.types.length, 4)
  f.state.toggleAllTypes(); assert.equal(f.state.form.types.length, 5)
  f.state.toggleAllTypes(); assert.equal(f.state.form.types.length, 0)
  assert.equal(f.calls.length, 2)
})
test('manual accessible source can resolve canonically without passing diagnostics IDs through Number', async t => {
  const f = mount(t, []); await flush(); fill(f.state); f.state.form.source = 'https://t.me/synthetic'
  f.expect(BASE, view(), 'POST'); await f.state.save()
  assert.equal(f.calls.at(-1).options.body.input.source, 'https://t.me/synthetic')
  assert.equal(f.state.items.value[0].rule.input.source, '-1001')
})
test('editing copies frozen inputs, sends revision, forms a new paused version and leaves old job untouched', async t => {
  const old = view({ summary: { job: job() } }), f = mount(t, old); await flush()
  f.state.edit(f.state.items.value[0]); f.state.form.types = ['PHOTO']; f.state.form.targetDirectory = 'D:\\other'
  assert.deepEqual(f.state.items.value[0].rule.input.types, ['VIDEO'])
  const saved = view({ rule: { version: 2, revision: 2, input: { types: ['PHOTO'], targetDirectory: 'D:\\other' } }, summary: { job: job() } })
  f.expect(STATUS, saved, 'PUT'); await f.state.save()
  assert.equal(f.calls.at(-1).options.body.revision, 1); assert.equal(f.state.items.value[0].rule.version, 2)
  assert.equal(f.state.items.value[0].summary.job.ruleVersion, 1); assert.equal(f.state.items.value[0].rule.enabled, false)
})
test('all-day save clears stale time fields while cross-midnight remains an explicit Shanghai window', async t => {
  const f = mount(t, []); await flush(); fill(f.state); f.state.form.window.start = '22:00'; f.state.form.window.end = '08:00'
  f.expect(BASE, view(), 'POST'); await f.state.save()
  assert.deepEqual(f.calls.at(-1).options.body.input.window, input().window)
})
test('enable requires a snapshot confirmation and sends exactly one revision-bound enable only after confirmation', async t => {
  const f = mount(t); await flush(); let opened = 0
  f.state.confirmationDialog.value = { showModal() { opened++ }, close() {} }
  await f.state.prepareConfirmation(f.state.items.value[0], 'enable')
  assert.equal(opened, 1); assert.equal(f.calls.length, 1); assert.equal(f.state.confirmation.value.view.rule.enabled, false)
  f.expect(STATUS + '/enabled', enabled(), 'POST', { revision: 1, enabled: true }); await f.state.confirm(); await f.state.confirm()
  assert.equal(f.calls.length, 2); assert.equal(f.state.items.value[0].rule.enabled, true)
  assert.match(parsed.descriptor.template.content, /会扫描全部可读历史.*实际下载/)
})
test('pause preserves current download state and reports media-boundary pause rather than immediate cancellation', async t => {
  const old = enabled({ summary: { job: job({ state: 'RUNNING' }) }, state: 'DOWNLOADING' }), f = mount(t, old); await flush()
  const paused = view({ rule: { revision: 3 }, summary: { job: job({ state: 'RUNNING' }) } })
  f.expect(STATUS + '/enabled', paused, 'POST', { revision: 2, enabled: false }); await f.state.pause(f.state.items.value[0])
  assert.equal(f.state.items.value[0].summary.job.state, 'RUNNING'); assert.match(f.state.notice.value, /当前文件继续完成/)
})
test('manual check cannot bypass paused or waiting window and attention performs only one step without task retry', async t => {
  const f = mount(t); await flush(); await f.state.check(f.state.items.value[0]); assert.equal(f.calls.length, 1)
  f.state.items.value = [enabled({ state: 'WAITING_WINDOW' })]; await f.state.check(f.state.items.value[0]); assert.equal(f.calls.length, 1)
  const attention = enabled({ state: 'ATTENTION', summary: { unresolved: 1, errors: 1, scanError: '合成读取失败', job: job({ state: 'FAILED', error: '合成任务失败' }) } })
  f.state.items.value = [attention]; f.expect(STATUS + '/check', attention, 'POST', { revision: 2 }); await f.state.check(f.state.items.value[0])
  assert.equal(f.calls.length, 2); assert.match(f.state.notice.value, /不会连续/)
  assert.equal(f.calls.some(call => call.path.includes('/resume')), false)
})
test('held task is displayed as manual pause, can be taken over only after confirmation, and is never auto-resumed', async t => {
  const old = enabled({ summary: { job: job({ held: true }) }, state: 'ATTENTION' }), f = mount(t, old); await flush()
  await f.state.prepareConfirmation(f.state.items.value[0], 'takeover'); assert.equal(f.calls.length, 1)
  const taken = enabled({ summary: { job: job({ held: false, manual: true }) } })
  f.expect(STATUS + '/takeover', taken, 'POST', { revision: 2, taskId: TASK }); await f.state.confirm()
  assert.equal(f.state.items.value[0].summary.job.manual, true); assert.match(f.state.notice.value, /未立即开始下载/)
  assert.match(parsed.descriptor.template.content, /任务已手动暂停，请到任务中心处理/)
  assert.equal(f.calls.some(call => call.path.includes('/transfers/')), false)
})
test('running or already-manual task cannot prepare a takeover', async t => {
  const f = mount(t, view({ summary: { job: job({ state: 'RUNNING' }) } })); await flush()
  await f.state.prepareConfirmation(f.state.items.value[0], 'takeover'); assert.equal(f.state.confirmation.value, null)
  f.state.items.value = [view({ summary: { job: job({ manual: true }) } })]
  await f.state.prepareConfirmation(f.state.items.value[0], 'takeover'); assert.equal(f.state.confirmation.value, null); assert.equal(f.calls.length, 1)
})
test('a replacement task cannot inherit another task takeover confirmation even when rule revision is unchanged', async t => {
  const f = mount(t, view({ summary: { job: job() } })); await flush()
  await f.state.prepareConfirmation(f.state.items.value[0], 'takeover')
  f.state.items.value = [view({ summary: { job: job({ taskId: ID }) } })]
  await f.state.confirm(); assert.equal(f.calls.length, 1); assert.match(f.state.error.value, /成员已变化/)
})
test('polling closes a takeover confirmation when that frozen task is no longer eligible', async t => {
  const f = mount(t, view({ summary: { job: job() } })); await flush()
  await f.state.prepareConfirmation(f.state.items.value[0], 'takeover')
  f.expect(BASE, listing([view({ summary: { job: job({ state: 'RUNNING' }) } })])); await f.state.refresh()
  assert.equal(f.state.confirmation.value, null); await f.state.confirm(); assert.equal(f.calls.length, 2)
})
test('an enable response cannot claim success for the opposite requested state', async t => {
  const f = mount(t); await flush(); await f.state.prepareConfirmation(f.state.items.value[0], 'enable')
  f.expect(STATUS + '/enabled', view({ rule: { revision: 2 } }), 'POST'); await f.state.confirm()
  assert.equal(f.state.items.value[0].rule.revision, 1); assert.ok(f.state.uncertain.value)
})
test('unknown create remains locked on null lookup and reuses original operation without another POST or operation ID', async t => {
  const f = mount(t, []); await flush(); fill(f.state); f.expect(BASE, new Error('timeout'), 'POST'); await f.state.save()
  const operation = f.calls.at(-1).options.body.operationId
  assert.equal(f.state.uncertain.value.kind, 'create'); await f.state.save(); f.state.edit(null); assert.equal(f.calls.length, 2)
  f.expect(BASE + '/by-operation/' + operation, null); await f.state.lookup(); assert.ok(f.state.uncertain.value)
  f.expect(BASE + '/by-operation/' + operation, view()); await f.state.lookup()
  assert.equal(f.state.uncertain.value, null); assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 1)
})
test('unknown edit is resolved with GET of that same rule, never repeats PUT or generates a create operation', async t => {
  const f = mount(t); await flush(); f.state.edit(f.state.items.value[0]); f.state.form.types = ['PHOTO']
  f.expect(STATUS, new Error('timeout'), 'PUT'); await f.state.save(); assert.ok(f.state.uncertain.value)
  const saved = view({ rule: { version: 2, revision: 2, input: { types: ['PHOTO'] } } })
  f.expect(STATUS, saved); await f.state.lookup(); assert.equal(f.state.items.value[0].rule.version, 2)
  assert.equal(f.calls.filter(call => call.options.method === 'PUT').length, 1); assert.equal(f.calls.some(call => call.path.includes('by-operation')), false)
})
test('enable timeout closes confirmation, locks actions and requires GET before another explicit user action', async t => {
  const f = mount(t); await flush(); await f.state.prepareConfirmation(f.state.items.value[0], 'enable')
  f.expect(STATUS + '/enabled', new Error('timeout'), 'POST'); await f.state.confirm(); await f.state.confirm()
  assert.equal(f.state.confirmation.value, null); assert.ok(f.state.uncertain.value)
  await f.state.pause(f.state.items.value[0]); await f.state.check(f.state.items.value[0]); assert.equal(f.calls.length, 2)
  f.expect(STATUS, enabled()); await f.state.lookup(); assert.equal(f.state.items.value[0].rule.enabled, true)
  assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 1)
})
test('foreign account/rule and changed selection receipts are refused and remain unconfirmed', async t => {
  const f = mount(t, []); await flush(); fill(f.state)
  f.expect(BASE, view({ rule: { input: { types: ['PHOTO'] } } }), 'POST'); await f.state.save()
  assert.equal(f.state.items.value.length, 0); assert.ok(f.state.uncertain.value)
  const operation = f.state.uncertain.value.operationId
  f.expect(BASE + '/by-operation/' + operation, view({ rule: { accountId: '2' } })); await f.state.lookup()
  assert.ok(f.state.uncertain.value); assert.equal(f.state.items.value.length, 0)
})
test('a wrong rule ID or unexpected version in a mutation cannot replace the selected rule', async t => {
  const f = mount(t, enabled()); await flush()
  f.expect(STATUS + '/check', enabled({ rule: { id: TASK } }), 'POST'); await f.state.check(f.state.items.value[0])
  assert.equal(f.state.items.value[0].rule.id, ID); assert.ok(f.state.uncertain.value)
  f.expect(STATUS, enabled()); await f.state.lookup()
  f.expect(STATUS + '/check', enabled({ rule: { version: 2, revision: 3 } }), 'POST'); await f.state.check(f.state.items.value[0])
  assert.equal(f.state.items.value[0].rule.version, 1); assert.ok(f.state.uncertain.value)
})
test('HTTP rejection and metadata failures are not automatically retried', async t => {
  const f = mount(t, enabled()); await flush()
  f.expect(STATUS + '/check', new ApiError('读取被拒绝', 400), 'POST'); await f.state.check(f.state.items.value[0])
  assert.match(f.state.error.value, /读取被拒绝/); assert.equal(f.calls.length, 2); assert.equal(f.state.uncertain.value, null)
  f.expect(STATUS + '/check', new Error('timeout'), 'POST'); await f.state.check(f.state.items.value[0]); assert.ok(f.state.uncertain.value)
  await f.state.check(f.state.items.value[0]); assert.equal(f.calls.length, 3)
})
test('browse freezes the selected rule source without creating work and clears on account context change', async t => {
  const f = mount(t); await flush(); const selected = f.state.items.value[0]
  f.state.browse(selected); assert.equal(f.state.browsing.value.rule.id, ID)
  selected.rule.input.tags = 'changed after opening'; assert.equal(f.state.browsing.value.rule.input.tags, input().tags)
  f.state.browse(view({ rule: { revision: 99 } })); assert.equal(f.state.browsing.value.rule.revision, 1)
  assert.equal(f.calls.length, 1); f.props.available = false; await flush(); assert.equal(f.state.browsing.value, null)
})
test('manual list refresh cannot overlap itself, channels, or a mutation', async t => {
  const response = deferred(), f = mount(t); await flush(); f.expect(BASE, response.promise)
  const refreshing = f.state.refresh(); await f.state.refresh(); await f.state.check(f.state.items.value[0]); await f.state.loadChannels()
  assert.equal(f.calls.length, 2); response.resolve(listing([view()])); await refreshing; assert.equal(f.maxActive(), 1)
  assert.equal(f.timers.length, 0)
})
test('account change discards a late mutation, clears form/confirmation and queues exactly one fresh GET', async t => {
  const f = mount(t, enabled()); await flush(); const response = deferred(); f.expect(STATUS + '/check', response.promise, 'POST')
  const checking = f.state.check(f.state.items.value[0]); f.props.accountId = '2'; await flush()
  assert.equal(f.state.items.value.length, 0); f.expect(BASE, listing([view({ rule: { accountId: '2' } })]))
  response.resolve(enabled({ summary: { scanned: 10 } })); await checking; await flush()
  assert.equal(f.state.items.value[0].rule.accountId, '2'); assert.equal(f.state.items.value[0].summary.scanned, 0)
  assert.equal(f.calls.length, 3); assert.equal(f.maxActive(), 1)
})
test('availability off while reading, then on, yields one current GET without carrying a stale queued refresh', async t => {
  const response = deferred(), f = mount(t, response.promise); f.props.available = false; await flush()
  response.resolve(listing([view()])); await flush(); assert.equal(f.state.items.value.length, 0)
  f.expect(BASE, listing([view()])); f.props.available = true; await flush(); assert.equal(f.calls.length, 2)
  assert.equal(f.state.items.value.length, 1); assert.equal(f.maxActive(), 1)
})

test('brief service interruption preserves the open download draft and manual source selection', async t => {
  const f = mount(t); await flush(); fill(f.state)
  f.state.manualSource.value = true
  Object.assign(f.state.form, { source: '@draft', name: '未保存的下载规则', types: ['PHOTO', 'DOCUMENT'], includeMain: true })
  const draft = copy(f.state.form)
  f.props.available = false; await flush()
  assert.equal(f.state.formOpen.value, true); assert.deepEqual(f.state.form, draft)
  await f.state.save(); assert.equal(f.calls.length, 1)
  f.expect(BASE, listing([view()])); f.props.available = true; await flush()
  assert.equal(f.state.formOpen.value, true); assert.deepEqual(f.state.form, draft)
  assert.equal(f.state.manualSource.value, true); assert.equal(f.calls.every(call => !call.options.method), true)
})

test('download edit keeps its frozen revision and existing uncertainty through service recovery', async t => {
  const f = mount(t); await flush(); f.state.edit(f.state.items.value[0]); f.state.form.name = 'my draft'
  f.expect(STATUS, new Error('timeout'), 'PUT'); await f.state.save()
  const command = copy(f.state.uncertain.value)
  f.props.available = false; await flush()
  f.expect(BASE, listing([view({ rule: { version: 2, revision: 2, input: { name: 'new remote revision' } } })]))
  f.props.available = true; await flush()
  assert.equal(f.state.formOpen.value, true); assert.equal(f.state.form.name, 'my draft')
  assert.equal(f.state.editing.value.rule.revision, 1); assert.deepEqual(f.state.uncertain.value, command)
  await f.state.save(); assert.equal(f.calls.filter(call => call.options.method === 'PUT').length, 1)
})

test('interrupted in-flight download save retains its operation and only an explicit GET can confirm it', async t => {
  const f = mount(t, []); await flush(); fill(f.state)
  const response = deferred(); f.expect(BASE, response.promise, 'POST')
  const saving = f.state.save(), operation = f.calls[1].options.body.operationId
  f.props.available = false; await flush()
  assert.equal(f.state.uncertain.value.operationId, operation); assert.equal(f.state.formOpen.value, true)
  f.expect(BASE, listing([view()])); f.props.available = true; await flush()
  response.resolve(view()); await saving; await flush()
  assert.equal(f.state.formOpen.value, true); assert.equal(f.state.uncertain.value.operationId, operation)
  await f.state.save(); assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 1)
  f.expect(BASE + '/by-operation/' + operation, view()); await f.state.lookup()
  assert.equal(f.state.formOpen.value, false); assert.equal(f.state.uncertain.value, null)
})

test('download drafts are cleared for a real account switch or loss of authorization', async t => {
  const f = mount(t); await flush(); fill(f.state); f.state.manualSource.value = true
  f.expect(BASE, listing([view({ rule: { accountId: '2' } })])); f.props.accountId = '2'; await flush()
  assert.equal(f.state.formOpen.value, false); assert.equal(f.state.form.name, ''); assert.equal(f.state.manualSource.value, false)
  const channelsResponse = deferred(); f.expect('/transfers/download/channels', channelsResponse.promise)
  f.state.edit(null); f.state.form.name = 'second account draft'; f.props.authorized = false; await flush()
  channelsResponse.resolve({ items: [], scope: 'READABLE_CHANNELS', partial: false, warnings: [] }); await flush()
  assert.equal(f.state.formOpen.value, false); assert.equal(f.state.form.name, '')
})

const discoveredChannels = () => ({ items: [{ id: '-1001', title: '合成频道' }], scope: 'READABLE_CHANNELS', partial: false, warnings: [] })

test('opening a new download rule automatically reads channels once and reuses them on reopen', async t => {
  const f = mount(t, [], {}, false); await flush()
  const response = deferred(); f.expect('/transfers/download/channels', response.promise)
  f.state.edit(null)
  assert.equal(f.state.channelsLoading.value, true); assert.equal(f.state.working.value, false)
  assert.equal(f.state.formOpen.value, true); assert.equal(f.state.sourceEntry.value, 'CHANNEL')
  f.state.form.name = 'typing while channels load'
  response.resolve(discoveredChannels()); await flush()
  assert.equal(f.state.channels.value.items.length, 1); assert.equal(f.state.form.source, '')
  assert.equal(f.state.form.name, 'typing while channels load')
  f.state.closeForm(); f.state.edit(null); await flush()
  assert.equal(f.calls.filter(call => call.path.endsWith('/channels')).length, 1)
  assert.equal(f.maxActive(), 1)
})

test('channel discovery queues behind the initial list read without blocking the new form', async t => {
  const initial = deferred(), f = mount(t, initial.promise, {}, false)
  f.state.edit(null); assert.equal(f.state.formOpen.value, true)
  f.expect('/transfers/download/channels', discoveredChannels())
  initial.resolve(listing([])); await flush()
  assert.equal(f.state.channels.value.items.length, 1); assert.equal(f.maxActive(), 1)
  assert.equal(f.timers.filter(timer => !timer.cancelled).length, 0, 'No list polling while editing')
})

test('failed automatic channel discovery keeps the draft and only retries on explicit refresh', async t => {
  const f = mount(t, [], {}, false); await flush()
  f.expect('/transfers/download/channels', new Error('offline channel service'))
  f.state.edit(null); f.state.form.name = 'keep draft'; await flush()
  assert.match(f.state.channelError.value, /offline channel service/)
  f.state.ensureChannels(); await flush(); assert.equal(f.calls.length, 2)
  f.expect('/transfers/download/channels', discoveredChannels()); await f.state.loadChannels()
  assert.equal(f.state.form.name, 'keep draft'); assert.equal(f.state.channelError.value, '')
})

test('closing during channel discovery discards the old response and permits a fresh next open', async t => {
  const f = mount(t, [], {}, false); await flush(); const response = deferred()
  f.expect('/transfers/download/channels', response.promise); f.state.edit(null)
  f.state.closeForm(); assert.equal(f.state.formOpen.value, false)
  response.resolve(discoveredChannels()); await flush(); assert.equal(f.state.channels.value, null)
  f.expect('/transfers/download/channels', discoveredChannels()); f.state.edit(null); await flush()
  assert.equal(f.state.formOpen.value, true); assert.equal(f.state.channels.value.items.length, 1)
})

test('top-level manual channel mode keeps channel semantics and cannot inherit a selected or post source', async t => {
  const f = mount(t); await flush(); fill(f.state)
  f.state.selectSourceEntry('MANUAL')
  assert.equal(f.state.sourceEntry.value, 'MANUAL'); assert.equal(f.state.sourceMode.value, 'CHANNEL')
  assert.equal(f.state.form.source, ''); assert.equal(f.state.form.tags, input().tags)
  f.state.form.source = '@manual'; f.state.selectSourceEntry('POST')
  assert.equal(f.state.sourceEntry.value, 'POST'); assert.equal(f.state.form.source, '')
  assert.equal(f.state.form.tags, ''); assert.equal(f.state.form.keywords, '')
  Object.assign(f.state.form, postInput()); f.state.selectSourceEntry('MANUAL')
  assert.equal(f.state.form.postKey, null); assert.equal(f.state.form.mainMessageId, null)
  assert.equal(f.state.form.source, ''); assert.deepEqual(f.state.form.types, input().types)
  f.state.form.source = '@manual'; f.state.selectSourceEntry('CHANNEL')
  assert.equal(f.state.sourceEntry.value, 'CHANNEL'); assert.equal(f.state.form.source, '')
})

test('switching to manual entry during automatic discovery never replaces typed source or changes modes', async t => {
  const f = mount(t, [], {}, false); await flush(); const response = deferred()
  f.expect('/transfers/download/channels', response.promise); f.state.edit(null)
  f.state.selectSourceEntry('MANUAL'); f.state.form.source = '@manual'; f.state.form.name = 'manual draft'
  response.resolve(discoveredChannels()); await flush()
  assert.equal(f.state.sourceEntry.value, 'MANUAL'); assert.equal(f.state.form.source, '@manual')
  assert.equal(f.state.form.name, 'manual draft'); assert.equal(f.state.channels.value.items.length, 1)
})

test('opening a form during a manual list read preserves the draft and never schedules another read', async t => {
  const f = mount(t); await flush(); const response = deferred()
  f.expect(BASE, response.promise); const refreshing = f.state.refresh()
  assert.equal(f.state.pending.value, true); assert.equal(f.state.working.value, false)
  f.state.edit(null); f.state.form.name = 'stable draft'
  response.resolve(listing([view()])); await refreshing; await flush()
  assert.equal(f.calls.length, 2); assert.equal(f.state.form.name, 'stable draft')
  assert.equal(f.state.formOpen.value, true); assert.equal(f.timers.filter(timer => !timer.cancelled).length, 0)
  f.state.closeForm(); assert.equal(f.timers.length, 0)
})
test('unmount discards late channel discovery and stops every polling timer', async t => {
  const f = mount(t); await flush(); const response = deferred(); f.expect('/transfers/download/channels', response.promise)
  const reading = f.state.loadChannels(); f.destroy(); response.resolve({ items: [], scope: 'READABLE_CHANNELS', partial: true, warnings: [] }); await reading
  assert.equal(f.state.channels.value, null); assert.ok(f.timers.every(timer => timer.cancelled)); assert.equal(f.calls.length, 2)
})
test('unauthorized or unavailable context performs no request or confirmation side effect', async t => {
  const f = mount(t, view(), { authorized: false }); await flush(); f.state.edit(null); await f.state.prepareConfirmation(view(), 'enable')
  assert.equal(f.calls.length, 0); assert.equal(f.state.formOpen.value, false); assert.equal(f.state.confirmation.value, null)
  f.expect(BASE, listing([view()])); f.props.authorized = true; await flush(); assert.equal(f.calls.length, 1)
})
test('401 reports access error and does not retry the failed mutation', async t => {
  const f = mount(t, enabled()); await flush(); f.expect(STATUS + '/check', new ApiError('失效', 401), 'POST')
  await f.state.check(f.state.items.value[0]); assert.equal(f.events[0][0], 'accessError'); assert.equal(f.calls.length, 2)
})
test('list failures retain explicitly marked last successful results and reject foreign/duplicate rules', async t => {
  const f = mount(t); await flush(); f.expect(BASE, listing([view({ rule: { accountId: '2' } })])); await f.state.refresh()
  assert.equal(f.state.items.value[0].rule.accountId, '1'); assert.match(f.state.listError.value, /无法确认/)
  f.expect(BASE, listing([view(), view()])); await f.state.refresh(); assert.equal(f.state.items.value.length, 1)
  assert.match(parsed.descriptor.template.content, /当前保留上次成功读取的规则/)
})
test('progress distinguishes directory boundary, unread/unresolved work, explicit errors and saved files', async t => {
  const value = enabled({ summary: { historyDone: true, scanned: 31, matched: 2, unresolved: 2, errors: 1, commentPages: 4, media: 50, completed: 1 } })
  const f = mount(t, value); await flush(); const text = f.state.progress(value)
  assert.match(text, /待读取\/核对 2（其中异常 1）/); assert.match(text, /已保存 1/); assert.doesNotMatch(text, /100%|全部完成/)
  assert.match(f.state.rangeSummary(value), /本轮主帖目录已到边界；评论和待核实范围仍分别处理/)
  assert.match(f.state.rangeSummary(view({ rule: { input: postInput() } })), /范围固定为这条主帖/)
})
