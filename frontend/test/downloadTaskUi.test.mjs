import * as taskHistory from '../src/taskHistory.ts'
import * as uploadRetry from '../src/uploadRetry.ts'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as library from '../src/library.ts'
import * as preview from '../src/downloadPreview.ts'
import * as taskModule from '../src/downloadTasks.ts'
import * as uploadTaskModule from '../src/uploadTasks.ts'
import * as uploadPreviewModule from '../src/uploadPreview.ts'
const ID = '00000000-0000-0000-0000-000000000001', KEY = '1:-2001:601:0'
const flush = async () => { await vue.nextTick(); await new Promise(setImmediate) }
const deferred = () => { let resolve; const promise = new Promise(yes => { resolve = yes }); return { promise, resolve } }
const media = { key: KEY, message: { chatId: '-2001', messageId: '601', link: null }, source: 'COMMENT', type: 'VIDEO', fileName: 'test.mp4', size: 37, caption: '' }
const source = () => ({ id: ID, post: { key: 'post', accountId: '1', channelId: '-1001', mainMessageId: '100', mainMedia: [], canReadComments: true, discussionChatId: '-2001', discussionRootMessageId: '500', warnings: [] }, comments: { postKey: 'post', messages: [media.message], media: [media], messageCount: 1, nextCursor: '601', exhausted: false, warnings: [] }, pageNumber: 1, observedMessages: 1, exhausted: false, canContinue: true, warnings: [] })
const task = (overrides = {}) => ({ id: ID, state: 'QUEUED', title: 'Synthetic', channelTitle: 'Synthetic', scope: 'OBSERVED_SELECTION', duplicatePolicy:'HISTORY_SKIP_V1',duplicates:0,duplicateDirectory:'D:\\test\\重复媒体',pauseRequested: false, targetDirectory: 'D:\\test', total: 1, completed: 0, skipped: 0, totalBytes: 37, items: [{ id: 'item', state: 'QUEUED', type: 'VIDEO', size: 37, fileName: 'test.mp4', downloadedBytes: 0, writtenBytes: 0, outputPath: 'D:\\test\\out.mp4', error: null }], error: null, createdAt: 1, updatedAt: 1, ...overrides })
const downloadAutomation = overrides => ({ ruleId: ID, ruleVersion: 1, ruleName: '合成下载规则', manual: false, held: false, enabled: true, currentVersion: 1,
  window: { mode: 'WINDOW', start: '22:00', end: '08:00', timezone: 'Asia/Shanghai' }, ...overrides })
const readyUpload=()=>({id:ID,state:'READY',channelTitle:'Synthetic',postTitle:'Synthetic',postLink:'https://t.me/synthetic/1',discussionTitle:'Synthetic',sourceDirectory:'D:\\source',archiveDirectory:'D:\\archive',stopRequested:false,total:1,confirmed:0,archived:0,error:null,createdAt:1,updatedAt:1,groups:[{id:'1',number:1,mediaType:'VIDEO',state:'READY',items:[{id:'item',fileName:'clip.mp4',mediaType:'VIDEO',size:37,state:'READY',formalMessageId:null,archivePath:null,error:null}]}]})
class ApiError extends Error { constructor(message, status) { super(message); this.status = status } }
function mount(t, name, initial = [], initialProps = {}) {
  const parsed = parse(readFileSync(new URL(`../src/components/${name}.vue`, import.meta.url), 'utf8'), { filename: name + '.vue' })
  const compiled = ts.transpileModule(compileScript(parsed.descriptor, { id: name }).content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const steps = [...initial], calls = [], failures = [], hooks = [], mountedHooks = [], events = [], timers = []
  const props = vue.reactive({ accountId: '1', authorized: true, available: true, ...initialProps })
  const api = async (path, options = {}) => {
    calls.push({ path, options }); const expected = steps.shift()
    try { assert.ok(expected, path); assert.equal(path, expected.path); if (expected.method) assert.equal(options.method ?? 'GET', expected.method) } catch (cause) { failures.push(cause); throw cause }
    if (expected.value instanceof Error) throw expected.value
    return expected.value
  }
  const modules = { vue: { ...vue, onMounted: callback => mountedHooks.push(callback), onBeforeUnmount: callback => hooks.push(callback) }, '../api': { api, ApiError }, '../taskHistory': taskHistory, '../library': library, '../downloadPreview': preview, '../downloadTasks': taskModule, '../uploadRetry':uploadRetry,'../uploadTasks':uploadTaskModule, '../uploadPreview':uploadPreviewModule }
  const module = { exports: {} }
  new Function('require', 'module', 'exports', 'setTimeout', 'clearTimeout', compiled)(name => { if (name.endsWith('.vue')) return {}; assert.ok(Object.hasOwn(modules, name), name); return modules[name] }, module, module.exports,
    (callback, delay) => { const token = { callback, delay, cancelled: false }; timers.push(token); return token }, token => { if (token) token.cancelled = true })
  const scope = vue.effectScope(), state = scope.run(() => module.exports.default.setup(props, { expose() {}, emit: (...event) => events.push(event) }))
  let disposed = false; const destroy = () => { if (disposed) return; disposed = true; hooks.forEach(callback => callback()); scope.stop() }
  t.after(() => { destroy(); assert.deepEqual(failures, []); assert.equal(steps.length, 0) })
  return { state, props, calls, events, timers, destroy, mounted: () => mountedHooks.forEach(callback => callback()), expect: (path, value, method) => steps.push({ path, value, method }) }
}
test('rule post browser automatically reads the source but cannot select media, create or resume a download', async t => {
  const f = mount(t, 'NewTransfer', [], { previewOnly: true, browseSource: { sourceMode: 'POST', source: 'https://t.me/synthetic/1', tags: '', keywords: '' } })
  let shown = 0; f.state.dialog.value = { showModal() { shown++ }, close() {} }
  f.expect('/transfers/download/preview', source(), 'POST'); f.mounted(); await flush()
  assert.equal(shown, 1); assert.equal(f.state.link.value, 'https://t.me/synthetic/1'); assert.equal(f.state.downloadSourceKind.value, 'post')
  assert.deepEqual(f.state.selectedTypes.value, ['VIDEO','PHOTO','AUDIO','DOCUMENT','OTHER']); assert.equal(f.state.includeMain.value, true)
  f.state.targetDirectory.value = 'D:\\test'; f.state.toggleDownload(KEY); await f.state.createDownload(); await f.state.createDownload(true); await f.state.lookupDownload()
  assert.equal(f.calls.length, 1); assert.deepEqual(f.state.excludedKeys.value, []); assert.equal(f.state.createdTask.value, null)
})
test('rule channel browser starts in the frozen channel context without accidentally reading it as a post', async t => {
  const f = mount(t, 'NewTransfer', [], { previewOnly: true, browseSource: { source: '-1001', tags: '旅行', keywords: '山' } })
  f.state.dialog.value = { showModal() {}, close() {} }; f.mounted(); await flush()
  assert.equal(f.state.downloadSourceKind.value, 'channel'); assert.equal(f.calls.length, 0)
})
async function ready(t) {
  const f = mount(t, 'NewTransfer'); f.state.link.value = 'https://t.me/synthetic/1'; await flush()
  f.expect('/transfers/download/preview', source(), 'POST'); await f.state.readPreview(); f.state.targetDirectory.value = 'D:\\test'; return f
}
test('download task optional automation keeps existing fixtures compatible and rejects malformed rule ownership/version/window metadata', () => {
  assert.equal(taskModule.validTask(task()), true); assert.equal(taskModule.validTask(task({ automation: null })), true)
  assert.equal(taskModule.validTask(task({ automation: downloadAutomation() })), true)
  for (const change of [{ ruleId: 'foreign' }, { ruleVersion: 0 }, { ruleVersion: 2, currentVersion: 1 }, { held: 'yes' },
    { window: { mode: 'WINDOW', start: '22:00', end: '08:00', timezone: 'UTC' } }]) {
    assert.equal(taskModule.validTask(task({ automation: downloadAutomation(change) })), false)
  }
})
test('automation description reports frozen Beijing window and distinguishes manual hold, paused rule, old version and takeover', () => {
  assert.match(taskModule.downloadAutomationLabel(downloadAutomation({ held: true })), /手动暂停.*22:00—08:00.*北京时间/)
  assert.match(taskModule.downloadAutomationLabel(downloadAutomation({ enabled: false })), /规则已暂停/)
  assert.match(taskModule.downloadAutomationLabel(downloadAutomation({ currentVersion: 2 })), /旧版本/)
  assert.match(taskModule.downloadAutomationLabel(downloadAutomation({ manual: true, held: true, enabled: false })), /已转手动/)
})
test('viewing held rule download only reads status and an explicit resume still relies on backend admission', async t => {
  const saved = task({ state: 'PAUSED', automation: downloadAutomation({ held: true, enabled: false }) })
  const f = mount(t, 'DownloadTasks', [{ path: '/transfers/download/tasks?limit=20&offset=0', value: { items: [saved], workerWarning: null } }]); await flush()
  assert.equal(f.calls.length, 1); assert.equal(f.state.tasks.value[0].automation.held, true)
  f.expect('/transfers/download/tasks/' + ID + '/resume', new ApiError('规则已暂停，不能继续', 400), 'POST')
  await f.state.action(f.state.tasks.value[0], 'resume')
  assert.equal(f.state.tasks.value[0].state, 'PAUSED'); assert.equal(f.calls.length, 2); assert.match(f.state.error.value, /规则已暂停.*不会自动重试/)
})
test('viewing a saved upload never starts it without the explicit start action',async t=>{
  const saved=readyUpload(),path='/transfers/upload/tasks?limit=20&offset=0';const f=mount(t,'UploadTasks',[{path,value:{items:[saved],workerWarning:null}}]);await flush();assert.equal(f.calls.length,1);assert.equal(f.state.tasks.value[0].state,'READY');f.expect('/transfers/upload/tasks/'+ID+'/start',{...saved,state:'QUEUED'},'POST');await f.state.action(saved,'start');assert.equal(f.calls.length,2);assert.equal(f.state.tasks.value[0].state,'QUEUED');assert.equal(f.state.tasks.value[0].archived,0)
})
test('reconciling an uncertain upload uses only the non-resending reconciliation route',async t=>{
  const saved=readyUpload();saved.state='NEEDS_REVIEW';saved.groups[0].state='UNCERTAIN';const f=mount(t,'UploadTasks',[{path:'/transfers/upload/tasks?limit=20&offset=0',value:{items:[saved],workerWarning:null}}]);await flush();f.expect('/transfers/upload/tasks/'+ID+'/recover-pending',new Error('timeout'),'POST');await f.state.action(saved,'reconcile');assert.match(f.state.error.value,/不会自动重试/);assert.equal(f.calls.some(c=>c.path.endsWith('/start')),false);assert.equal(f.state.tasks.value[0].confirmed,0)
})
test('selecting and previewing media never creates a task until the explicit download action', async t => {
  const f = await ready(t); assert.deepEqual(f.state.selectedDownloadKeys.value, [KEY]); assert.equal(f.calls.length, 1)
  f.state.toggleDownload(KEY); assert.deepEqual(f.state.selectedDownloadKeys.value, []); await f.state.createDownload(); assert.equal(f.calls.length, 1)
})
test('explicit creation freezes the observed selection and reports queued rather than completed', async t => {
  const f = await ready(t); f.expect('/transfers/download/tasks', task(), 'POST'); await f.state.createDownload()
  const input = f.calls[1].options.body; assert.equal(input.sourceId, ID); assert.deepEqual(input.mediaKeys, [KEY]); assert.equal(input.targetDirectory, 'D:\\test')
  assert.match(input.operationId, /^[0-9a-f-]{36}$/); assert.equal(f.state.createdTask.value.state, 'QUEUED'); assert.equal(f.state.downloadLocked.value, true)
  await f.state.createDownload(); assert.equal(f.calls.length, 2)
})
test('uncertain creation is never retried automatically and an explicit retry keeps the same operation and snapshot', async t => {
  const f = await ready(t); f.expect('/transfers/download/tasks', new Error('Synthetic timeout'), 'POST'); await f.state.createDownload()
  assert.equal(f.state.createUncertain.value, true); assert.equal(f.state.downloadLocked.value, true); assert.equal(f.calls.length, 2)
  f.state.targetDirectory.value = 'D:\\changed'; f.expect('/transfers/download/tasks', task(), 'POST'); await f.state.createDownload(true)
  assert.deepEqual(f.calls[1].options.body, f.calls[2].options.body); assert.equal(f.state.createUncertain.value, false)
})
test('looking up an uncertain operation recovers its task without posting another create request', async t => {
  const f = await ready(t); f.expect('/transfers/download/tasks', new Error('Synthetic timeout'), 'POST'); await f.state.createDownload()
  const operation = f.calls[1].options.body.operationId; f.expect('/transfers/download/tasks/by-operation/' + operation, { task: task() }, 'GET'); await f.state.lookupDownload()
  assert.equal(f.state.createdTask.value.id, ID); assert.equal(f.calls.filter(call => call.path === '/transfers/download/tasks').length, 1)
})
test('account switching invalidates a late task creation response', async t => {
  const f = await ready(t), pending = deferred(); f.expect('/transfers/download/tasks', pending.promise, 'POST')
  const creating = f.state.createDownload(); f.props.accountId = '2'; await flush(); pending.resolve(task()); await creating
  assert.equal(f.state.createdTask.value, null); assert.equal(f.state.preview.value, null); assert.deepEqual(f.state.selectedDownloadKeys.value, [])
})
test('100 percent native download bytes cannot masquerade as a completed saved file', () => {
  const result = task({ state: 'RUNNING' }); result.items[0] = { ...result.items[0], state: 'DOWNLOADING', downloadedBytes: 37 }
  assert.equal(taskModule.validTask(result), true)
  result.state = 'COMPLETED'; result.completed = 1; assert.equal(taskModule.validTask(result), false)
  result.items[0] = { ...result.items[0], state: 'COMPLETED', writtenBytes: 37 }; assert.equal(taskModule.validTask(result), true)
  result.scope = 'ALL_CHANNEL'; assert.equal(taskModule.validTask(result), false)
})
const historyTask=()=>task({state:'COMPLETED',completed:0,skipped:1,items:[{...task().items[0],state:'SKIPPED_HISTORY',outputPath:null,history:{id:'old-item',taskId:'old-task',path:'D:\\moved-source\\test.mp4',sha256:'a'.repeat(64),completedAt:1}}]})
test('database history skips finish processing without pretending a file was saved in this task',async t=>{
  const saved=historyTask();assert.equal(taskModule.validTask(saved),true)
  const f=mount(t,'DownloadTasks',[{path:'/transfers/download/tasks?limit=20&offset=0',value:{items:[saved],workerWarning:null}}]);await flush()
  assert.equal(f.state.tasks.value[0].completed,0);assert.equal(f.state.tasks.value[0].skipped,1);assert.equal(f.state.tasks.value[0].items[0].outputPath,null)
  assert.equal(f.calls.length,1);assert.equal(f.timers.length,0)
})
test('history skips require evidence, zero current byte counts and no invented output path',()=>{
  for(const mutate of [t=>t.items[0].history=null,t=>t.items[0].history.sha256='prefix',t=>t.items[0].outputPath='D:\\new-copy.mp4',t=>t.items[0].writtenBytes=37,t=>t.completed=1,t=>t.skipped=0]){
    const candidate=historyTask();mutate(candidate);assert.equal(taskModule.validTask(candidate),false)
  }
})
test('mixed downloaded and historical items have separate counts and finish only when both are resolved',()=>{
  const mixed=historyTask();mixed.total=2;mixed.totalBytes=74;mixed.completed=1
  mixed.items.push({...task().items[0],id:'new-file',state:'COMPLETED',downloadedBytes:37,writtenBytes:37})
  assert.equal(taskModule.validTask(mixed),true);mixed.items[1].state='DOWNLOADING';mixed.completed=0;assert.equal(taskModule.validTask(mixed),false)
})
test('a saved duplicate is a real completed file and does not become a history skip',async t=>{
  const saved=task({state:'COMPLETED',completed:1,duplicates:1,duplicatePolicy:'KEEP_DUPLICATES_V2'})
  saved.items[0]={...saved.items[0],state:'COMPLETED',downloadedBytes:37,writtenBytes:37,outputPath:'D:\\test\\重复媒体\\test.mp4',sourceLink:'https://t.me/synthetic/2',duplicate:{itemId:'old',reason:'CONTENT_SHA',sourceLink:'https://t.me/synthetic/1',path:'D:\\old.mp4'},manifestPath:'D:\\test\\重复媒体\\来源.json'}
  assert.equal(taskModule.validTask(saved),true)
  const f=mount(t,'DownloadTasks',[{path:'/transfers/download/tasks?limit=20&offset=0',value:{items:[saved],workerWarning:null}}]);await flush();assert.equal(f.state.tasks.value[0].duplicates,1);assert.equal(f.state.tasks.value[0].skipped,0)
  for(const mutate of [t=>t.items[0].manifestPath=null,t=>t.items[0].writtenBytes=0,t=>t.duplicates=0,t=>t.items[0].duplicate.reason='SAME_NAME']){const wrong=structuredClone(saved);mutate(wrong);assert.equal(taskModule.validTask(wrong),false)}
})
test('the new policy cannot silently turn a selected duplicate into an old-style skip',()=>{
  const candidate=historyTask();candidate.duplicatePolicy='KEEP_DUPLICATES_V2';assert.equal(taskModule.validTask(candidate),false)
})
test('task center polls active tasks, shows the confirmed state, and never replays a failed pause automatically', async t => {
  const active = task({ state: 'RUNNING' }); const path = '/transfers/download/tasks?limit=20&offset=0'
  const f = mount(t, 'DownloadTasks', [{ path, value: { items: [active], workerWarning: null } }]); await flush()
  assert.equal(f.state.tasks.value.length, 1); assert.equal(f.timers.at(-1).delay, 2000)
  f.expect('/transfers/download/tasks/' + ID + '/pause', new Error('Synthetic timeout'), 'POST'); await f.state.action(active, 'pause')
  assert.match(f.state.error.value, /不会自动重试/); assert.equal(f.calls.length, 2)
})
test('single-task progress updates neither reload history nor disable controls or move the cursor', async t => {
  const active=task({state:'RUNNING'}),path='/transfers/download/tasks?limit=20&offset=0'
  const f=mount(t,'DownloadTasks',[{path,value:{items:[active],workerWarning:null}}]);await flush()
  const response=deferred();f.expect('/transfers/download/tasks/'+ID,response.promise)
  const reading=f.state.readProgress();assert.equal(f.state.loading.value,false);assert.equal(f.state.busy.value,'')
  await f.state.readProgress();assert.equal(f.calls.length,2)
  const updated=task({state:'RUNNING',updatedAt:2,items:[{...active.items[0],downloadedBytes:7}]})
  response.resolve(updated);await reading
  assert.equal(f.state.tasks.value[0].items[0].downloadedBytes,7);assert.equal(f.state.offset.value,1)
  assert.equal(f.state.hasMore.value,false);assert.equal(f.calls.filter(call=>call.path===path).length,1)
})

test('a late progress response cannot overwrite a user pause and progress failures retain the visible record', async t => {
  const active=task({state:'RUNNING'}),path='/transfers/download/tasks?limit=20&offset=0'
  const f=mount(t,'DownloadTasks',[{path,value:{items:[active],workerWarning:null}}]);await flush()
  f.expect('/transfers/download/tasks/'+ID,new Error('temporary progress error'));await f.state.readProgress()
  assert.equal(f.state.error.value,'');assert.equal(f.state.tasks.value[0].state,'RUNNING')
  const response=deferred();f.expect('/transfers/download/tasks/'+ID,response.promise);const reading=f.state.readProgress()
  f.expect('/transfers/download/tasks/'+ID+'/pause',task({state:'PAUSED'}),'POST');await f.state.action(active,'pause')
  response.resolve(active);await reading;assert.equal(f.state.tasks.value[0].state,'PAUSED')
  assert.equal(f.timers.filter(timer=>!timer.cancelled).length,0)
})

test('task polling cannot restore another account data after a late response or after closing', async t => {
  const pending = deferred(); const path = '/transfers/download/tasks?limit=20&offset=0'
  const f = mount(t, 'DownloadTasks', [{ path, value: pending.promise }]); f.props.available = false; await flush(); pending.resolve({ items: [task()], workerWarning: null }); await flush()
  assert.deepEqual(f.state.tasks.value, []); f.destroy(); assert.equal(f.timers.filter(timer => !timer.cancelled).length, 0)
})
test('inactive single-download panel starts no read or action and activation refreshes without resetting rows or pagination', async t => {
  const path = '/transfers/download/tasks?limit=20&offset=0', saved = task({ state: 'PAUSED' })
  const f = mount(t, 'DownloadTasks', [], { active: false }); await flush()
  await f.state.load(); await f.state.action(saved, 'resume'); f.state.loadMore(); assert.equal(f.calls.length, 0)
  f.expect(path, { items: [saved], workerWarning: null }); f.props.active = true; await flush()
  assert.equal(f.calls.length, 1); f.props.active = false; await flush()
  assert.equal(f.state.tasks.value[0].id, ID); assert.ok(f.timers.every(timer => timer.cancelled))
  await f.state.action(saved, 'resume'); assert.equal(f.calls.length, 1)
  f.expect(path, { items: [saved], workerWarning: null }); f.props.active = true; await flush(); assert.equal(f.calls.length, 2)
  f.destroy(); await f.state.readProgress(); assert.equal(f.calls.length, 2, 'Disposed panels do not read progress')
})
const historyId = n => '00000000-0000-0000-0000-' + String(n).padStart(12, '0')
const historyPage = (start, count = 20) => Array.from({ length: count }, (_, i) => task({ id: historyId(start + i) }))
test('scroll history appends once while progress reads keep rows, order and history position unchanged', async t => {
  const head = historyPage(1), f = mount(t, 'DownloadTasks', [{ path: '/transfers/download/tasks?limit=20&offset=0', value: { items: head, workerWarning: null } }]); await flush()
  f.expect('/transfers/download/tasks?limit=20&offset=20', { items: historyPage(20, 2), workerWarning: null })
  await f.state.loadMore(); assert.equal(f.state.tasks.value.length, 21); assert.equal(f.state.offset.value, 22); assert.equal(f.state.hasMore.value, false)
  assert.equal(f.state.tasks.value[0].id, historyId(1)); assert.equal(f.state.tasks.value.at(-1).id, historyId(21))
  const order = f.state.tasks.value.map(item => item.id)
  f.expect('/transfers/download/tasks/' + historyId(1), head[0]); f.timers.at(-1).callback(); await flush()
  f.expect('/transfers/download/tasks/' + historyId(2), head[1]); f.timers.at(-1).callback(); await flush()
  assert.equal(f.state.tasks.value.length, 21); assert.ok(f.calls.every(call => !call.options.method))
  assert.deepEqual(f.state.tasks.value.map(item => item.id), order); assert.equal(f.state.offset.value, 22)
})
test('failed history append keeps rows and cursor, remains explicitly retryable after a successful status refresh', async t => {
  const head = historyPage(1), path = '/transfers/download/tasks?limit=20&offset=0'
  const f = mount(t, 'DownloadTasks', [{ path, value: { items: head, workerWarning: null } }]); await flush()
  f.expect('/transfers/download/tasks?limit=20&offset=20', new Error('history offline')); await f.state.loadMore()
  assert.equal(f.state.offset.value, 20); assert.equal(f.state.tasks.value.length, 20); assert.match(f.state.historyError.value, /offline/)
  f.expect(path, { items: head, workerWarning: null }); await f.state.load(); assert.match(f.state.historyError.value, /offline/)
  f.expect('/transfers/download/tasks?limit=20&offset=20', { items: historyPage(21, 2), workerWarning: null }); await f.state.loadMore()
  assert.equal(f.state.historyError.value, ''); assert.equal(f.state.tasks.value.length, 22)
})
test('new head records reopen an exhausted history range without gaps or dropping existing records', async t => {
  const path = '/transfers/download/tasks?limit=20&offset=0', f = mount(t, 'DownloadTasks', [{ path, value: { items: historyPage(1), workerWarning: null } }]); await flush()
  f.expect('/transfers/download/tasks?limit=20&offset=20', { items: [], workerWarning: null }); await f.state.loadMore(); assert.equal(f.state.hasMore.value, false)
  const newHead = historyPage(101).map(item => ({ ...item, createdAt: 2, updatedAt: 2 }))
  f.expect(path, { items: newHead, workerWarning: null }); await f.state.load()
  assert.equal(f.state.tasks.value.length, 40); assert.equal(f.state.offset.value, 20); assert.equal(f.state.hasMore.value, true)
  f.expect('/transfers/download/tasks?limit=20&offset=20', { items: historyPage(121, 2), workerWarning: null }); await f.state.loadMore()
  assert.equal(f.state.tasks.value.length, 42); assert.equal(new Set(f.state.tasks.value.map(item => item.id)).size, 42)
})
test('an old account history append cannot restore rows or its cursor after account change', async t => {
  const path = '/transfers/download/tasks?limit=20&offset=0', pending = deferred(), f = mount(t, 'DownloadTasks', [{ path, value: { items: historyPage(1), workerWarning: null } }]); await flush()
  f.expect('/transfers/download/tasks?limit=20&offset=20', pending.promise); const append = f.state.loadMore()
  f.props.accountId = '2'; await flush(); f.expect(path, { items: [], workerWarning: null }); pending.resolve({ items: historyPage(21, 2), workerWarning: null }); await append; await flush()
  assert.equal(f.state.tasks.value.length, 0); assert.equal(f.state.offset.value, 0); assert.equal(f.state.hasMore.value, false)
})
test('single-download POST received while hidden is still applied but never polls or resumes another item', async t => {
  const saved = task({ state: 'PAUSED' }), response = deferred(), path = '/transfers/download/tasks?limit=20&offset=0'
  const f = mount(t, 'DownloadTasks', [{ path, value: { items: [saved], workerWarning: null } }]); await flush()
  f.expect('/transfers/download/tasks/' + ID + '/resume', response.promise, 'POST'); const action = f.state.action(saved, 'resume')
  f.props.active = false; await flush(); response.resolve(task()); await action
  assert.equal(f.state.tasks.value[0].state, 'QUEUED'); assert.equal(f.calls.length, 2); assert.ok(f.timers.every(timer => timer.cancelled))
  f.expect(path, { items: [task()], workerWarning: null }); f.props.active = true; await flush(); assert.equal(f.calls.length, 3)
  assert.equal(f.calls.filter(call => call.options.method === 'POST').length, 1)
})
test('single-download reactivation during a pending read queues one GET and account changes discard old POST state', async t => {
  const response = deferred(), path = '/transfers/download/tasks?limit=20&offset=0'
  const f = mount(t, 'DownloadTasks', [{ path, value: response.promise }]); f.props.active = false; await flush(); f.props.active = true; await flush()
  f.expect(path, { items: [task({ state: 'PAUSED' })], workerWarning: null }); response.resolve({ items: [], workerWarning: null }); await flush()
  assert.equal(f.calls.length, 2); assert.equal(f.state.tasks.value[0].state, 'PAUSED')
  const operation = deferred(); f.expect('/transfers/download/tasks/' + ID + '/resume', operation.promise, 'POST')
  const pending = f.state.action(f.state.tasks.value[0], 'resume'); f.props.accountId = '2'; await flush()
  f.expect(path, { items: [], workerWarning: null }); operation.resolve(task()); await pending; await flush()
  assert.deepEqual(f.state.tasks.value, []); assert.equal(f.calls.length, 4)
})
