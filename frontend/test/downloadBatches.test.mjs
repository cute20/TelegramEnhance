import * as taskHistory from '../src/taskHistory.ts'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as library from '../src/library.ts'
import * as taskModule from '../src/downloadTasks.ts'
import * as batchModule from '../src/downloadBatchStatus.ts'

const id = n => '00000000-0000-0000-0000-' + String(n).padStart(12, '0')
const BATCH = id(1), TASK = id(100), PREVIEW = id(900)
const ROOT = 'D:\\batch'
const LIST = '/transfers/download/batches?limit=10&offset=0'
const STATUS = '/transfers/download/batches/' + BATCH
const flush = async () => { await vue.nextTick(); await new Promise(setImmediate) }
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes }); return { promise, resolve } }
const summary = (overrides = {}) => ({
  id: TASK, title: 'Synthetic post', state: 'PAUSED', pauseRequested: false, total: 1, completed: 0, skipped: 0,
  duplicates: 0, totalBytes: 37, downloadedBytes: 0, writtenBytes: 0, updatedAt: 20, error: null, ...overrides,
})
const batch = (tasks = [summary()], overrides = {}) => {
  const sum = key => tasks.reduce((total, task) => total + task[key], 0)
  const n = state => tasks.filter(task => task.state === state).length
  const state = tasks.some(task => task.state === 'RUNNING' && task.pauseRequested) ? 'PAUSING'
    : ['RUNNING', 'QUEUED', 'FAILED', 'PAUSED'].find(state => n(state)) ?? 'COMPLETED'
  return {
    id: BATCH, snapshot: { previewId: PREVIEW, revision: 2, channelTitle: 'Synthetic scan title', tags: ['tag'], keywords: [], types: ['DOCUMENT'],
      includeMain: false, targetDirectory: ROOT, postCount: 1, total: sum('total'), totalBytes: sum('totalBytes') },
    state, totalTasks: tasks.length, completedTasks: n('COMPLETED'), runningTasks: n('RUNNING'), queuedTasks: n('QUEUED'), pausedTasks: n('PAUSED'), failedTasks: n('FAILED'),
    completed: sum('completed'), skipped: sum('skipped'), duplicates: sum('duplicates'), downloadedBytes: sum('downloadedBytes'), writtenBytes: sum('writtenBytes'),
    createdAt: 10, updatedAt: 20, canPause: state !== 'PAUSING' && n('RUNNING') + n('QUEUED') > 0,
    canResume: state !== 'PAUSING' && n('PAUSED') + n('FAILED') > 0, tasks, ...overrides,
  }
}
const done = overrides => summary({ state: 'COMPLETED', completed: 1, downloadedBytes: 37, writtenBytes: 37, ...overrides })
const fullTask = (member = summary(), overrides = {}) => ({
  id: member.id, title: member.title, channelTitle: 'Renamed channel after scan', state: member.state, pauseRequested: member.pauseRequested,
  scope: 'OBSERVED_SELECTION', targetDirectory: ROOT + '\\频道\\主帖', total: 1, completed: member.completed, skipped: 0, duplicates: 0,
  totalBytes: 37, error: null, createdAt: 10, updatedAt: 20, duplicatePolicy: 'KEEP_DUPLICATES_V2', duplicateDirectory: ROOT + '\\重复媒体',
  items: [{ id: 'file-1', fileName: 'synthetic.txt', type: 'DOCUMENT', size: 37, state: member.state === 'COMPLETED' ? 'COMPLETED' : 'QUEUED',
    downloadedBytes: member.downloadedBytes, writtenBytes: member.writtenBytes, outputPath: ROOT + '\\频道\\主帖\\synthetic.txt', error: null }], ...overrides,
})

test('actual child states define the batch and mixed running/failed members keep their failure count and controls', () => {
  const mixed = batch([summary({ state: 'RUNNING' }), summary({ id: id(101), state: 'FAILED', error: 'Synthetic failure' })])
  assert.equal(batchModule.validBatchStatus(mixed), true)
  assert.equal(mixed.state, 'RUNNING'); assert.equal(mixed.failedTasks, 1)
  assert.equal(mixed.canPause, true); assert.equal(mixed.canResume, true)
  const pausing = batch([{ ...mixed.tasks[0], pauseRequested: true }, mixed.tasks[1]])
  assert.equal(batchModule.validBatchStatus(pausing, mixed), true)
  assert.equal(pausing.state, 'PAUSING'); assert.equal(pausing.canPause, false); assert.equal(pausing.canResume, false)
  assert.equal(batchModule.aggregateBatchState([summary({ state: 'QUEUED' }), summary({ id: id(101), state: 'FAILED' })]), 'QUEUED')
  assert.equal(batchModule.aggregateBatchState([summary({ state: 'FAILED' }), summary({ id: id(101) })]), 'FAILED')
})

test('100 percent client or written bytes do not complete a batch without actual completed file/task state', () => {
  const allBytes = batch([summary({ state: 'RUNNING', downloadedBytes: 37, writtenBytes: 37 })])
  assert.equal(batchModule.validBatchStatus(allBytes), true)
  assert.equal(allBytes.state, 'RUNNING'); assert.equal(allBytes.completed, 0)
  assert.equal(batchModule.validBatchStatus({ ...allBytes, state: 'COMPLETED', canPause: false }), false)
  assert.equal(batchModule.validBatchStatus(batch([done()])), true)
  assert.equal(batchModule.validBatchStatus(batch([done({ writtenBytes: 0 })])), false)
})

test('saved duplicate and historical skip counts stay distinct from new written files', () => {
  const saved = batch([done({ duplicates: 1 }), summary({ id: id(101), state: 'COMPLETED', skipped: 1 })])
  assert.equal(batchModule.validBatchStatus(saved), true)
  assert.equal(saved.completed, 1); assert.equal(saved.duplicates, 1); assert.equal(saved.skipped, 1)
  assert.equal(saved.snapshot.total, 2); assert.equal(saved.writtenBytes, 37)
})

for (const [label, mutate] of [
  ['aggregate saved count', value => value.completed++], ['aggregate bytes', value => value.writtenBytes++],
  ['child task count', value => value.totalTasks++], ['state count', value => value.pausedTasks = 0],
  ['snapshot total', value => value.snapshot.total++], ['snapshot bytes', value => value.snapshot.totalBytes++],
  ['duplicate members', value => { value.tasks.push({ ...value.tasks[0] }); value.totalTasks++ }],
  ['invalid capabilities', value => value.canPause = true], ['oversized child progress', value => value.tasks[0].downloadedBytes = 38],
  ['negative counts', value => value.failedTasks = -1], ['unsafe bytes', value => value.snapshot.totalBytes = Number.MAX_SAFE_INTEGER + 1],
]) {
  test(`batch invariant rejects ${label}`, () => { const value = batch(); mutate(value); assert.equal(batchModule.validBatchStatus(value), false) })
}

test('frozen membership, root and completed facts cannot change, while native cache bytes may decrease', () => {
  const previous = batch([summary({ state: 'RUNNING', downloadedBytes: 37 })])
  const restarted = batch([summary({ state: 'PAUSED', downloadedBytes: 0 })])
  assert.equal(batchModule.validBatchStatus(restarted, previous), true)
  assert.equal(batchModule.validBatchStatus(batch([summary({ id: id(101) })]), previous), false)
  const changedRoot = batch(); changedRoot.snapshot.targetDirectory = 'D:\\other'
  assert.equal(batchModule.validBatchStatus(changedRoot, previous), false)
  assert.equal(batchModule.validBatchStatus(batch([summary()]), batch([done()])), false)
  assert.equal(batchModule.validBatchStatus(batch([summary({ title: 'Other post' })]), previous), false)
})

test('child file detail belongs to a known frozen member and its descendant output directory, not necessarily the root or old channel title', () => {
  const saved = batch()
  assert.equal(batchModule.validBatchTaskDetail(fullTask(), saved, TASK), true)
  assert.equal(batchModule.validBatchTaskDetail(fullTask(undefined, { targetDirectory: 'd:/BATCH/频道/主帖', duplicateDirectory: 'd:/BATCH/重复媒体/' }), saved, TASK), true)
  for (const change of [{ id: id(101) }, { targetDirectory: ROOT }, { targetDirectory: ROOT + '-other\\post' },
    { targetDirectory: ROOT + '\\..\\foreign' }, { targetDirectory: '\\\\server\\batch\\post' }, { duplicateDirectory: ROOT + '\\other' }, { title: 'Another post' }]) {
    assert.equal(batchModule.validBatchTaskDetail(fullTask(undefined, change), saved, TASK), false)
  }
  assert.equal(batchModule.validBatchTaskDetail(fullTask(), saved, id(999)), false)
})

const source = readFileSync(new URL('../src/components/DownloadBatches.vue', import.meta.url), 'utf8')
const parsed = parse(source, { filename: 'DownloadBatches.vue' })
assert.deepEqual(parsed.errors, [])
const compiled = ts.transpileModule(compileScript(parsed.descriptor, { id: 'download-batches-test' }).content, {
  fileName: 'DownloadBatches.ts', compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText
class ApiError extends Error { constructor(message, status) { super(message); this.status = status } }
function mount(t, initial = batch(), propsOverride = {}) {
  const accessible = propsOverride.active !== false && (propsOverride.authorized ?? true) && (propsOverride.available ?? true) && (Object.hasOwn(propsOverride, 'accountId') ? !!propsOverride.accountId : true)
  const steps = accessible ? [{ path: LIST, value: initial instanceof Promise || initial instanceof Error ? initial : { items: [initial], workerWarning: null } }] : []
  const calls = [], failures = [], hooks = [], timers = [], events = []
  const props = vue.reactive({ authorized: true, available: true, accountId: '1', ...propsOverride })
  const api = async (path, options = {}) => {
    const call = { path, method: options.method ?? 'GET' }; calls.push(call)
    const expected = steps.shift()
    try { assert.ok(expected, path); assert.equal(path, expected.path); assert.equal(call.method, expected.method ?? 'GET') }
    catch (error) { failures.push(error); throw error }
    if (expected.value instanceof Error) throw expected.value
    return expected.value
  }
  const modules = { vue: { ...vue, onBeforeUnmount: callback => hooks.push(callback) }, '../api': { api, ApiError },
    '../taskHistory': taskHistory, '../library': library, '../downloadTasks': taskModule, '../downloadBatchStatus': batchModule }
  const module = { exports: {} }
  new Function('require', 'module', 'exports', 'setTimeout', 'clearTimeout', compiled)(name => {
    if (name.endsWith('.vue')) return {}
    assert.ok(Object.hasOwn(modules, name), name); return modules[name]
  }, module, module.exports, (callback, delay) => { const timer = { callback, delay, cancelled: false }; timers.push(timer); return timer }, timer => { if (timer) timer.cancelled = true })
  const scope = vue.effectScope()
  const state = scope.run(() => module.exports.default.setup(props, { expose() {}, emit: (...event) => events.push(event) }))
  let disposed = false
  const destroy = () => { if (!disposed) { disposed = true; hooks.forEach(callback => callback()); scope.stop() } }
  t.after(() => { destroy(); assert.deepEqual(failures, []); assert.equal(steps.length, 0) })
  return { state, props, calls, timers, events, destroy, expect: (path, value, method = 'GET') => steps.push({ path, value, method }) }
}

test('compact history starts with only a bounded GET, never resumes completed or paused tasks on view', async t => {
  const completed = batch([done()]), f = mount(t, completed)
  await flush()
  assert.equal(f.calls.length, 1)
  assert.equal(f.state.batches.value[0].state, 'COMPLETED')
  assert.match(f.state.description(completed), /已保存 1\/1/)
  assert.equal(f.timers.at(-1).delay, 10000)
  assert.match(parsed.descriptor.template.content, /:default-expanded="false"/)
  await f.state.action(completed, 'pause'); await f.state.action(completed, 'resume')
  assert.equal(f.calls.length, 1, 'Repeated completed controls are no-ops')
})

test('running with full client bytes keeps saved zero and schedules one two-second read', async t => {
  const active = batch([summary({ state: 'RUNNING', downloadedBytes: 37 })]), f = mount(t, active)
  await flush()
  assert.equal(f.state.batches.value[0].state, 'RUNNING')
  assert.match(f.state.description(active), /已保存 0\/1/)
  assert.equal(f.timers.filter(timer => !timer.cancelled).length, 1)
  assert.equal(f.timers.at(-1).delay, 2000)
})

test('mixed-running failures remain visible and pause stays PAUSING until actual workers stop', async t => {
  const active = batch([summary({ state: 'RUNNING' }), summary({ id: id(101), state: 'FAILED' })])
  const f = mount(t, active); await flush()
  assert.match(f.state.batchAlert(active), /1 个子任务需要处理/)
  const pausing = batch([{ ...active.tasks[0], pauseRequested: true, downloadedBytes: 37 }, active.tasks[1]])
  f.expect(STATUS + '/pause', pausing, 'POST')
  await f.state.action(active, 'pause')
  assert.equal(f.state.batches.value[0].state, 'PAUSING')
  assert.equal(f.state.batches.value[0].failedTasks, 1)
  await f.state.action(pausing, 'resume')
  assert.equal(f.calls.length, 2)
  const stopped = batch([summary({ state: 'PAUSED', downloadedBytes: 37 }), active.tasks[1]])
  f.expect(LIST, { items: [stopped], workerWarning: null })
  await f.state.load()
  assert.equal(f.state.batches.value[0].state, 'FAILED')
  assert.equal(f.state.batches.value[0].canResume, true)
})

test('mixed resume changes eligible children while completed members retain their saved evidence', async t => {
  const saved = batch([done(), summary({ id: id(101), state: 'FAILED' })]), f = mount(t, saved)
  await flush()
  const resumed = batch([done(), summary({ id: id(101), state: 'QUEUED' })])
  f.expect(STATUS + '/resume', resumed, 'POST')
  await f.state.action(saved, 'resume')
  assert.equal(f.state.batches.value[0].completed, 1)
  assert.equal(f.state.batches.value[0].completedTasks, 1)
  assert.equal(f.state.batches.value[0].state, 'QUEUED')
})

for (const failure of [new Error('Synthetic timeout'), new ApiError('Synthetic HTTP error', 500)]) {
  test(`${failure.message} triggers only GET verification and never repeats POST`, async t => {
    const saved = batch(), f = mount(t, saved); await flush()
    const queued = batch([summary({ state: 'QUEUED' })])
    f.expect(STATUS + '/resume', failure, 'POST'); f.expect(STATUS, queued)
    await f.state.action(saved, 'resume'); await flush()
    assert.equal(f.calls.filter(call => call.method === 'POST').length, 1)
    assert.equal(f.state.batches.value[0].state, 'QUEUED')
    assert.deepEqual(f.state.uncertainIds.value, [])
    assert.match(f.state.error.value, /已读取当前状态.*不会自动重试/)
  })
}

test('failed verification locks controls until explicit status GET succeeds, followed by an explicit retry', async t => {
  const saved = batch(), f = mount(t, saved); await flush()
  f.expect(STATUS + '/resume', new Error('Synthetic timeout'), 'POST'); f.expect(STATUS, new Error('Synthetic GET failure'))
  await f.state.action(saved, 'resume')
  assert.deepEqual(f.state.uncertainIds.value, [BATCH])
  await f.state.action(saved, 'resume'); await f.state.action(saved, 'cancel')
  assert.equal(f.calls.filter(call => call.method === 'POST').length, 1)
  f.expect(STATUS, saved)
  await f.state.checkBatch(saved)
  assert.deepEqual(f.state.uncertainIds.value, [])
  f.expect(STATUS + '/resume', batch([summary({ state: 'QUEUED' })]), 'POST')
  await f.state.action(saved, 'resume')
  assert.equal(f.calls.filter(call => call.method === 'POST').length, 2)
})

test('foreign membership in an action receipt is rejected and only a verified GET replaces current state', async t => {
  const saved = batch(), f = mount(t, saved); await flush()
  f.expect(STATUS + '/resume', batch([summary({ id: id(101), state: 'QUEUED' })]), 'POST'); f.expect(STATUS, saved)
  await f.state.action(saved, 'resume')
  assert.deepEqual(f.state.batches.value[0].tasks.map(task => task.id), [TASK])
  assert.equal(f.calls.filter(call => call.method === 'POST').length, 1)
  assert.match(f.state.error.value, /未确认/)
})

test('polling cannot overlap an action and stale account replies cannot overwrite or POST in the new account', async t => {
  const saved = batch(), f = mount(t, saved); await flush()
  const pending = deferred()
  f.expect(STATUS + '/resume', pending.promise, 'POST')
  const running = f.state.action(saved, 'resume')
  await f.state.load(); await f.state.action(saved, 'resume')
  assert.equal(f.calls.length, 2)
  f.props.accountId = '2'; await flush()
  const other = batch([summary({ id: id(102) })], { id: id(2) })
  f.expect(LIST, { items: [other], workerWarning: null })
  pending.resolve(batch([summary({ state: 'QUEUED' })]))
  await running; await flush()
  assert.deepEqual(f.state.batches.value.map(batch => batch.id), [id(2)])
  assert.equal(f.state.error.value, '')
  assert.equal(f.calls.filter(call => call.method === 'POST').length, 1)
})

test('availability loss invalidates pending reads and restoration issues exactly one current GET', async t => {
  const pending = deferred(), f = mount(t, pending.promise)
  f.props.available = false; await flush(); pending.resolve({ items: [batch()], workerWarning: null }); await flush()
  assert.deepEqual(f.state.batches.value, [])
  f.expect(LIST, { items: [batch()], workerWarning: null })
  f.props.available = true; await flush()
  assert.equal(f.calls.length, 2)
  assert.equal(f.state.batches.value.length, 1)
})

test('a scheduled poll cannot overlap itself while its GET is in flight', async t => {
  const active = batch([summary({ state: 'RUNNING' })]), f = mount(t, active)
  await flush()
  const pending = deferred(), poll = f.timers.at(-1)
  f.expect(LIST, pending.promise)
  poll.callback(); poll.callback()
  await flush()
  assert.equal(f.calls.length, 2)
  pending.resolve({ items: [active], workerWarning: null }); await flush()
  assert.equal(f.timers.filter(timer => !timer.cancelled).length, 1)
})

for (const changed of ['account', 'available']) {
  test(`${changed} context change discards a late child detail and never applies it to the current batch`, async t => {
    const saved = batch(), f = mount(t, saved); await flush()
    const pending = deferred()
    f.expect('/transfers/download/tasks/' + TASK, pending.promise)
    const reading = f.state.showTask(saved, TASK)
    if (changed === 'account') { f.expect(LIST, { items: [], workerWarning: null }); f.props.accountId = '2' }
    else f.props.available = false
    await flush(); pending.resolve(fullTask()); await reading; await flush()
    assert.equal(f.state.taskDetail.value, null)
    assert.equal(f.state.detailTaskId.value, '')
    assert.deepEqual(f.state.batches.value, [])
  })
}

for (const [field, missing, restored] of [['authorized', false, true], ['available', false, true], ['accountId', null, '1']]) {
  test(`batch GET and actions wait for ${field} context`, async t => {
    const f = mount(t, batch(), { [field]: missing })
    await f.state.load(); await f.state.action(batch(), 'resume')
    assert.equal(f.calls.length, 0)
    f.expect(LIST, { items: [batch()], workerWarning: null })
    f.props[field] = restored; await flush()
    assert.equal(f.calls.length, 1)
  })
}

test('unmount stops polling and discards late child file details', async t => {
  const saved = batch(), f = mount(t, saved); await flush()
  const pending = deferred(); f.expect('/transfers/download/tasks/' + TASK, pending.promise)
  const reading = f.state.showTask(saved, TASK)
  f.destroy(); pending.resolve(fullTask()); await reading
  assert.equal(f.state.taskDetail.value, null)
  assert.equal(f.timers.filter(timer => !timer.cancelled).length, 0)
})

test('child detail is fetched only on demand and a new child summary invalidates stale file detail without another GET', async t => {
  const saved = batch(), f = mount(t, saved); await flush()
  assert.equal(f.calls.length, 1)
  f.expect('/transfers/download/tasks/' + TASK, fullTask())
  await f.state.showTask(saved, TASK)
  assert.equal(f.state.taskDetail.value.targetDirectory, ROOT + '\\频道\\主帖')
  const changed = batch([summary({ state: 'RUNNING', downloadedBytes: 37, updatedAt: 21 })], { updatedAt: 21 })
  f.expect(LIST, { items: [changed], workerWarning: 'Synthetic worker warning' })
  await f.state.load()
  assert.equal(f.state.taskDetail.value, null)
  assert.match(f.state.detailError.value, /状态已更新/)
  assert.equal(f.state.workerWarning.value, 'Synthetic worker warning')
  assert.equal(f.calls.length, 3)
})

test('foreign child file response and a child outside the batch never appear as current file details', async t => {
  const saved = batch(), f = mount(t, saved); await flush()
  await f.state.showTask(saved, id(999))
  assert.equal(f.calls.length, 1)
  f.expect('/transfers/download/tasks/' + TASK, fullTask(undefined, { id: id(999) }))
  await f.state.showTask(saved, TASK)
  assert.equal(f.state.taskDetail.value, null)
  assert.match(f.state.detailError.value, /不一致/)
})

test('ten-batch history appends without clearing rows and never shares a child between memberships', async t => {
  const f = mount(t, batch()); await flush()
  await f.state.loadMore()
  assert.equal(f.calls.length, 1)
  const ten = Array.from({ length: 10 }, (_, i) => batch([summary({ id: id(100 + i) })], { id: id(1 + i) }))
  f.expect(LIST, { items: ten, workerWarning: null }); await f.state.load()
  f.expect('/transfers/download/batches?limit=10&offset=10', { items: [], workerWarning: null })
  f.state.loadMore(); await flush()
  assert.equal(f.state.offset.value, 10); assert.equal(f.state.batches.value.length, 10); assert.equal(f.state.hasMore.value, false)
  f.expect(LIST, { items: [batch(), batch([summary()], { id: id(20) })], workerWarning: null })
  await f.state.load()
  assert.equal(f.state.batches.value.length, 10)
  assert.match(f.state.error.value, /归属无法确认/)
})

test('inactive batch panel starts no GET/actions and preserves selection while polling stops until activated', async t => {
  const saved = batch(), f = mount(t, saved, { active: false }); await flush()
  await f.state.load(); await f.state.action(saved, 'resume'); await f.state.checkBatch(saved); await f.state.showTask(saved, TASK); f.state.loadMore()
  assert.equal(f.calls.length, 0); assert.equal(f.timers.length, 0)
  f.expect(LIST, { items: [saved], workerWarning: null }); f.props.active = true; await flush()
  assert.equal(f.calls.length, 1); f.props.active = false; await flush(); assert.ok(f.timers.every(timer => timer.cancelled))
  assert.equal(f.state.batches.value[0].id, BATCH)
  f.expect(LIST, { items: [saved], workerWarning: null }); f.props.active = true; await flush(); assert.equal(f.calls.length, 2)
  f.destroy(); f.timers.at(-1).callback(); await flush(); assert.equal(f.calls.length, 2, 'An already queued timer cannot open a GET after disposal')
})
test('batch mutation completed after hiding keeps its receipt and does not begin another GET or POST', async t => {
  const saved = batch(), response = deferred(), f = mount(t, saved); await flush()
  f.expect(STATUS + '/resume', response.promise, 'POST'); const action = f.state.action(saved, 'resume')
  f.props.active = false; await flush(); response.resolve(batch([summary({ state: 'QUEUED' })])); await action
  assert.equal(f.state.batches.value[0].state, 'QUEUED'); assert.equal(f.calls.length, 2); assert.ok(f.timers.every(timer => timer.cancelled))
  f.expect(LIST, { items: [batch([summary({ state: 'QUEUED' })])], workerWarning: null }); f.props.active = true; await flush()
  assert.equal(f.calls.length, 3); assert.equal(f.calls.filter(call => call.method === 'POST').length, 1)
})
test('hidden unknown batch POST retains its lock and a moved listing cannot remove the only status-check control', async t => {
  const saved = batch(), response = deferred(), f = mount(t, saved); await flush()
  f.expect(STATUS + '/resume', response.promise, 'POST'); const action = f.state.action(saved, 'resume')
  f.props.active = false; await flush(); response.resolve(Promise.reject(new Error('timeout'))); await action
  assert.deepEqual(f.state.uncertainIds.value, [BATCH]); assert.equal(f.calls.length, 2)
  f.expect(LIST, { items: [], workerWarning: null }); f.props.active = true; await flush()
  assert.equal(f.state.batches.value[0].id, BATCH); assert.match(f.state.error.value, /尚未确认/)
  await f.state.action(saved, 'resume'); assert.equal(f.calls.length, 3)
  f.expect(STATUS, saved); await f.state.checkBatch(saved); assert.deepEqual(f.state.uncertainIds.value, [])
  assert.equal(f.calls.filter(call => call.method === 'POST').length, 1)
})
test('reactivation while a batch read is pending merges into one current refresh and never overlaps reads', async t => {
  const response = deferred(), f = mount(t, response.promise); f.props.active = false; await flush(); f.props.active = true; await flush()
  f.expect(LIST, { items: [batch()], workerWarning: null }); response.resolve({ items: [], workerWarning: null }); await flush()
  assert.equal(f.calls.length, 2); assert.equal(f.state.batches.value.length, 1); assert.equal(f.timers.filter(timer => !timer.cancelled).length, 1)
})
test('authorization errors emit accessError and never perform a verification POST or leak a stale account result', async t => {
  const saved = batch(), f = mount(t, saved); await flush()
  const error = new ApiError('Synthetic token expired', 401)
  f.expect(STATUS + '/resume', error, 'POST')
  await f.state.action(saved, 'resume')
  assert.deepEqual(f.events, [['accessError', error]])
  assert.equal(f.calls.length, 2)
  assert.deepEqual(f.state.uncertainIds.value, [BATCH])
})
