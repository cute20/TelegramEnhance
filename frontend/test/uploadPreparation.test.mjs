import * as taskHistory from '../src/taskHistory.ts'
import * as uploadRetry from '../src/uploadRetry.ts'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as library from '../src/library.ts'
import * as upload from '../src/uploadPreview.ts'
import * as uploadTasks from '../src/uploadTasks.ts'
import * as rules from '../src/rules.ts'
const ID = '00000000-0000-0000-0000-000000000001'
const LINK = 'https://t.me/synthetic/1'
const flush = async () => { await vue.nextTick(); await new Promise(setImmediate) }
function result(count = 23) {
  const groups = []; for (let i = 0; i < count; i += 10) { const files = Array.from({ length: Math.min(10,count-i) }, (_,j) => ({ mediaType: 'VIDEO', relativePath: `clip${i+j+1}.mp4`, fileName: `clip${i+j+1}.mp4`, size: 10, caption: `clip${i+j+1}.mp4` })); groups.push({ number: groups.length + 1, mediaType: 'VIDEO', mode: files.length === 1 ? 'SINGLE' : 'ALBUM', files }) }
  return { id: ID, accountId: '1', state: 'PREVIEW_ONLY', sourceDirectory: 'D:\source', archiveDirectory: 'D:\archive', recursive: true, captionMode: 'FILENAME', channelTitle: 'Synthetic', postTitle: 'Synthetic', postText: 'Synthetic', postLink: LINK, discussionTitle: 'Synthetic', permission: 'MEDIA_ALLOWED_AT_CHECK', slowModeSeconds: 0, fileCount: count, totalBytes: count * 10, groups, otherFiles: 0, deferredFiles: 0, linkedEntries: 0, duplicateFiles: 0, warnings: [], targetCheckedAt: 1, createdAt: 1, expiresAt: 600001 }
}
function mount(t) {
  const parsed = parse(readFileSync(new URL('../src/components/UploadPreparation.vue', import.meta.url), 'utf8'), { filename: 'UploadPreparation.vue' })
  const compiled = ts.transpileModule(compileScript(parsed.descriptor,{ id: 'upload-test' }).content,{ compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText
  const props = vue.reactive({ accountId: '1', authorized: true, available: true }), steps = [], calls = [], failures = [], hooks = [], events = []
  const api = async (path, options={}) => { calls.push({ path, options }); const step = steps.shift(); try { assert.ok(step, path); assert.equal(path, step.path); assert.equal(options.method??'GET', step.method??'POST') } catch(e) { failures.push(e); throw e } if (step.value instanceof Error) throw step.value; return step.value }
  const modules = { vue: { ...vue, onBeforeUnmount: f => hooks.push(f) }, '../api': { api, ApiError: class extends Error {} }, '../taskHistory': taskHistory, '../library': library, '../uploadPreview': upload, '../uploadRetry':uploadRetry,'../uploadTasks':uploadTasks,'../rules':rules }
  const module = { exports: {} }; new Function('require','module','exports',compiled)(name => name.endsWith('.vue') ? {} : modules[name],module,module.exports)
  const scope = vue.effectScope(); const state = scope.run(() => module.exports.default.setup(props,{ expose() {}, emit: (...args) => events.push(args) }))
  let disposed = false; const destroy = () => { if (disposed) return; disposed = true; hooks.forEach(f => f()); scope.stop() }
  t.after(() => { destroy(); assert.deepEqual(failures,[]); assert.equal(steps.length,0) })
  return { state, props, calls, events, destroy, expectPath:(path,value,method='POST')=>steps.push({path,value,method}), expect: (value, verify = false) => steps.push({ path: verify ? '/transfers/upload/preview/'+ID+'/verify' : '/transfers/upload/preview', value }) }
}
async function fields(t) { const f=mount(t); f.state.link.value=LINK; f.state.sourceDirectory.value='D:\source'; f.state.archiveDirectory.value='D:\archive'; await flush(); return f }
test('upload preparation requires explicit valid inputs and never submits a send task', async t => {
  const f=mount(t); await f.state.prepare(); assert.equal(f.calls.length,0); f.state.link.value=LINK; f.state.sourceDirectory.value='D:\source'; f.state.archiveDirectory.value='D:\archive'; await flush();
  f.expect(result()); await f.state.prepare(); assert.equal(f.calls.length,1); assert.deepEqual(f.state.preview.value.groups.map(g=>g.files.length),[10,10,3]); assert.equal(f.state.preview.value.state,'PREVIEW_ONLY'); assert.equal(f.state.verified.value,false)
})
test('rechecking uses the same preview and cannot silently replace its frozen groups', async t => {
  const f=await fields(t); f.expect(result()); await f.state.prepare(); const old=f.state.preview.value; f.expect(result(21),true); await f.state.prepare(true)
  assert.deepEqual(f.state.preview.value.groups,old.groups); assert.match(f.state.error.value,/分组发生变化/); assert.equal(f.state.verified.value,false)
})
test('successful recheck preserves grouping and remains a non-sending preview', async t => {
  const f=await fields(t); f.expect(result()); await f.state.prepare(); f.expect(result(),true); await f.state.prepare(true); assert.equal(f.state.verified.value,true); assert.equal(f.state.preview.value.state,'PREVIEW_ONLY'); assert.deepEqual(f.calls[1].options.body,{})
})
test('source directory or caption changes invalidate both old preview and late responses', async t => {
  const f=await fields(t); let resolve; f.expect(new Promise(yes=>{resolve=yes})); const running=f.state.prepare(); f.state.captionMode.value='EMPTY'; await flush(); resolve(result()); await running; assert.equal(f.state.preview.value,null); assert.equal(f.state.busy.value,false)
})
test('account changes and closing the view discard late preparation results', async t => {
  const f=await fields(t); let resolve; f.expect(new Promise(yes=>{resolve=yes})); const running=f.state.prepare(); f.props.accountId='2'; await flush(); resolve(result()); await running; assert.equal(f.state.preview.value,null); assert.equal(f.state.sourceDirectory.value,''); assert.equal(f.state.link.value,'')
  f.state.link.value=LINK; f.state.sourceDirectory.value='D:\source'; f.state.archiveDirectory.value='D:\archive'; await flush(); let late; f.expect(new Promise(yes=>{late=yes})); const closed=f.state.prepare(); f.destroy(); late(result()); await closed; assert.equal(f.state.preview.value,null)
})
test('permission or file errors preserve the earlier preview and do not retry automatically', async t => {
  const f=await fields(t); f.expect(result()); await f.state.prepare(); f.expect(new Error('source changed'),true); await f.state.prepare(true); assert.equal(f.calls.length,2); assert.equal(f.state.preview.value.fileCount,23); assert.equal(f.state.verified.value,false); assert.match(f.state.error.value,/不会自动重试/)
})
test('invalid grouping, wrong accounts and fake completed statuses are rejected',()=> {
  assert.equal(upload.validUploadPreview(result(),'1'),true); assert.equal(upload.validUploadPreview(result(),'2'),false)
  const changed=result(); changed.groups[0].files.pop(); assert.equal(upload.validUploadPreview(changed,'1'),false)
  const complete=result(); complete.state='COMPLETED'; assert.equal(upload.validUploadPreview(complete,'1'),false)
  const duplicate=result(); duplicate.groups[1].files[0]=duplicate.groups[0].files[0]; assert.equal(upload.validUploadPreview(duplicate,'1'),false)
})
test('empty directories and a single tail member are represented honestly',()=> {
  assert.equal(upload.validUploadPreview(result(0),'1'),true); const tail=result(21); assert.equal(upload.validUploadPreview(tail,'1'),true); assert.equal(tail.groups.at(-1).mode,'SINGLE'); tail.groups.at(-1).mode='ALBUM'; assert.equal(upload.validUploadPreview(tail,'1'),false)
})
test('photo video audio and document groups remain separate even when Telegram permits mixed photo video albums',()=> {
  const mixed=result(6); mixed.groups=[{number:1,mediaType:'PHOTO',mode:'ALBUM',files:mixed.groups[0].files.slice(0,3).map(f=>({...f,mediaType:'PHOTO'}))},{number:2,mediaType:'VIDEO',mode:'ALBUM',files:mixed.groups[0].files.slice(3)}]
  assert.equal(upload.validUploadPreview(mixed,'1'),true); mixed.groups[0].files[0].mediaType='VIDEO'; assert.equal(upload.validUploadPreview(mixed,'1'),false)
})
const savedTask=()=>({id:ID,state:'READY',channelTitle:'Synthetic',postTitle:'Synthetic',postLink:LINK,discussionTitle:'Synthetic',sourceDirectory:'D:\\source',archiveDirectory:'D:\\archive',stopRequested:false,total:1,confirmed:0,archived:0,error:null,createdAt:1,updatedAt:1,groups:[{id:'1',number:1,mediaType:'VIDEO',state:'READY',items:[{id:'item',fileName:'clip1.mp4',mediaType:'VIDEO',size:10,state:'READY',formalMessageId:null,archivePath:null,error:null}]}]})
const managedTask=()=>({...savedTask(),state:'PAUSED',automation:{ruleId:ID,name:'自动规则',version:1,manualTakeover:false,windowMode:'WINDOW',start:'22:00',end:'08:00',timezone:'Asia/Shanghai',status:'WAITING_WINDOW',reason:'等待时段',nextAllowedAt:1}})
test('managed task cannot start outside its frozen rule window until explicitly taken over',()=>{const task=managedTask();assert.equal(uploadTasks.validUploadTask(task),true);assert.equal(uploadTasks.canStartManaged(task),false);task.automation.status='RULE_PAUSED';assert.equal(uploadTasks.canStartManaged(task),false);task.automation.status='AUTOMATIC';assert.equal(uploadTasks.canStartManaged(task),true);task.automation.manualTakeover=true;task.automation.status='MANUAL';assert.equal(uploadTasks.canStartManaged(task),true);assert.equal(uploadTasks.canStartManaged(savedTask()),true)})
test('manual takeover needs its own confirmation and only detaches the frozen task without starting it',async t=>{const task=managedTask();const ui=taskUi(t,task);await flush();await ui.state.prepareTakeover(task);assert.ok(ui.state.takeover.value);assert.equal(ui.calls.length,1);const manual=structuredClone(task);manual.automation.manualTakeover=true;manual.automation.status='MANUAL';ui.steps.push({path:`/transfers/upload/tasks/${ID}/takeover`,method:'POST',value:manual});await ui.state.confirmTakeover();assert.equal(ui.state.tasks.value[0].state,'PAUSED');assert.deepEqual(ui.state.tasks.value[0].groups,task.groups);assert.equal(ui.calls.length,2);assert.equal(ui.calls.some(c=>c.path.endsWith('/start')),false)})
test('account changes cancel pending manual takeover confirmation',async t=>{const ui=taskUi(t,managedTask());await flush();await ui.state.prepareTakeover(managedTask());ui.props.authorized=false;await flush();assert.equal(ui.state.takeover.value,null);await ui.state.confirmTakeover();assert.equal(ui.calls.length,1)})
test('saving an upload freezes the preview identity but never invokes the start endpoint',async t=>{
  const f=await fields(t);f.expect(result(1));await f.state.prepare();f.expectPath('/transfers/upload/tasks',savedTask());await f.state.createTask();assert.equal(f.state.created.value.state,'READY');assert.equal(f.calls.length,2);assert.equal(f.calls[1].options.body.previewId,ID);assert.equal(f.calls.some(c=>c.path.endsWith('/start')),false)
})
test('uncertain upload creation only retries explicitly with the same operation id',async t=>{
  const f=await fields(t);f.expect(result(1));await f.state.prepare();f.expectPath('/transfers/upload/tasks',new Error('timeout'));await f.state.createTask();assert.equal(f.state.uncertain.value,true);const first=f.calls[1].options.body;f.expectPath('/transfers/upload/tasks',savedTask());await f.state.createTask(true);assert.deepEqual(first,f.calls[2].options.body);assert.equal(f.state.created.value.state,'READY')
})
test('uncertain upload creation can be looked up without sending another POST',async t=>{
  const f=await fields(t);f.expect(result(1));await f.state.prepare();f.expectPath('/transfers/upload/tasks',new Error('timeout'));await f.state.createTask();const operation=f.calls[1].options.body.operationId;f.expectPath('/transfers/upload/tasks/by-operation/'+operation,{task:savedTask()},'GET');await f.state.lookupTask();assert.equal(f.state.created.value.id,ID);assert.equal(f.calls.filter(c=>c.path==='/transfers/upload/tasks').length,1)
})
test('upload creation keeps its original operation locked through a same-account disconnect until explicit GET confirms it',async t=>{
  const f=await fields(t);f.expect(result(1));await f.state.prepare()
  let release;f.expectPath('/transfers/upload/tasks',new Promise(resolve=>{release=resolve}));const saving=f.state.createTask()
  const operation=f.calls.at(-1).options.body.operationId,source=f.state.sourceDirectory.value
  assert.equal(f.state.transitionLocked.value,true)
  f.props.available=false;await flush()
  assert.equal(f.state.uncertain.value,true);assert.equal(f.state.creating.value,false);assert.equal(f.state.preview.value.id,ID);assert.equal(f.state.sourceDirectory.value,source)
  assert.deepEqual(f.events.filter(event=>event[0]==='transitionLocked'),[['transitionLocked',true]],'connectivity must not transiently unlock the outer composer')
  await f.state.lookupTask();assert.equal(f.calls.length,2,'offline lookup cannot submit a request')
  f.props.available=true;await flush();release(savedTask());await saving
  assert.equal(f.state.created.value,null,'late success belongs to the invalidated connection generation');assert.equal(f.state.uncertain.value,true);assert.equal(f.state.transitionLocked.value,true)
  await f.state.createTask();assert.equal(f.calls.length,2,'recovery cannot silently replace or resubmit the original operation')
  f.expectPath('/transfers/upload/tasks/by-operation/'+operation,{task:savedTask()},'GET');await f.state.lookupTask()
  assert.equal(f.state.created.value.id,ID);assert.equal(f.state.uncertain.value,false);assert.equal(f.state.transitionLocked.value,false)
  assert.deepEqual(f.events.filter(event=>event[0]==='transitionLocked'),[['transitionLocked',true],['transitionLocked',false]])
  assert.equal(f.calls.filter(call=>call.path==='/transfers/upload/tasks').length,1)
})
test('a confirmed upload task freezes its leaf inputs without permanently locking the composer',async t=>{
  const f=await fields(t);f.expect(result(1));await f.state.prepare();assert.equal(f.state.transitionLocked.value,false)
  f.expectPath('/transfers/upload/tasks',savedTask());await f.state.createTask()
  assert.equal(f.state.created.value.state,'READY');assert.equal(f.state.transitionLocked.value,false)
  f.props.available=false;await flush();f.props.available=true;await flush()
  assert.equal(f.state.created.value.id,ID);assert.equal(f.state.transitionLocked.value,false);assert.equal(f.state.uncertain.value,false)
  assert.equal(f.calls.some(call=>call.path.endsWith('/start')),false)
})
test('changing upload account clears the old unresolved operation and rejects its late creation reply',async t=>{
  const f=await fields(t);f.expect(result(1));await f.state.prepare();let release
  f.expectPath('/transfers/upload/tasks',new Promise(resolve=>{release=resolve}));const saving=f.state.createTask()
  f.props.accountId='2';await flush();release(savedTask());await saving
  assert.equal(f.state.created.value,null);assert.equal(f.state.uncertain.value,false);assert.equal(f.state.transitionLocked.value,false)
  assert.equal(f.state.link.value,'');assert.equal(f.state.sourceDirectory.value,'');assert.equal(f.state.archiveDirectory.value,'')
  await f.state.lookupTask();assert.equal(f.calls.length,2,'the old operation is not queried under the next account')
})
test('upload completion cannot be fabricated from a temporary message or only a formal receipt',()=>{
  const task=savedTask();assert.equal(uploadTasks.validUploadTask(task),true);task.state='COMPLETED';assert.equal(uploadTasks.validUploadTask(task),false);task.confirmed=1;task.groups[0].items[0].formalMessageId='9007199254740999';task.groups[0].items[0].state='SENT';assert.equal(uploadTasks.validUploadTask(task),false);task.archived=1;task.groups[0].items[0].state='ARCHIVED';task.groups[0].items[0].archivePath='D:\\archive\\clip1.mp4';assert.equal(uploadTasks.validUploadTask(task),true)
})
const deliveredGif=()=>{const task=savedTask();task.state='NEEDS_REVIEW';const group=task.groups[0];group.state='UNCERTAIN';group.mediaType='ANIMATION';group.canAcceptDocument=true;group.items[0].mediaType='ANIMATION';group.items[0].fileName='sample.gif';group.items[0].state='PENDING';return task}
function taskUi(t,initial){
  const parsed=parse(readFileSync(new URL('../src/components/UploadTasks.vue',import.meta.url),'utf8'),{filename:'UploadTasks.vue'})
  const compiled=ts.transpileModule(compileScript(parsed.descriptor,{id:'upload-task-test'}).content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
  const props=vue.reactive({accountId:'1',authorized:true,available:true}),calls=[],hooks=[],failures=[],steps=[{path:'/transfers/upload/tasks?limit=20&offset=0',method:'GET',value:{items:[initial],workerWarning:null}}]
  const api=async(path,options={})=>{calls.push({path,options});const step=steps.shift();try{assert.ok(step,path);assert.equal(path,step.path);assert.equal(options.method??'GET',step.method)}catch(e){failures.push(e);throw e}if(step.value instanceof Error)throw step.value;return step.value}
  const modules={vue:{...vue,onBeforeUnmount:f=>hooks.push(f)},'../api':{api,ApiError:class extends Error{}},'../taskHistory':taskHistory,'../library':library,'../uploadPreview':upload,'../uploadRetry':uploadRetry,'../uploadTasks':uploadTasks,'../rules':rules}
  const module={exports:{}};new Function('require','module','exports',compiled)(name=>name.endsWith('.vue')?{}:modules[name],module,module.exports)
  const scope=vue.effectScope();const state=scope.run(()=>module.exports.default.setup(props,{expose(){},emit(){}}))
  t.after(()=>{hooks.forEach(f=>f());scope.stop();assert.deepEqual(failures,[]);assert.equal(steps.length,0)})
  return {state,calls,steps,props}
}
test('a delivered GIF can only be accepted for its own eligible group by an explicit action',async t=>{
  const task=deliveredGif();assert.equal(uploadTasks.validUploadTask(task),true);const ui=taskUi(t,task);await flush();assert.equal(ui.calls.length,1)
  await ui.state.action(task,'accept-document','another-group');assert.equal(ui.calls.length,1)
  const queued=structuredClone(task);queued.state='QUEUED';queued.stopRequested=true;queued.confirmed=1;queued.groups[0].state='CONFIRMED';queued.groups[0].canAcceptDocument=false;queued.groups[0].items[0].state='SENT';queued.groups[0].items[0].formalMessageId='9007199254740999'
  ui.steps.push({path:`/transfers/upload/tasks/${ID}/groups/1/accept-document`,method:'POST',value:queued});await ui.state.action(task,'accept-document','1')
  assert.equal(ui.calls.length,2);assert.equal(ui.calls.some(c=>c.path.endsWith('/start')),false);assert.equal(ui.state.tasks.value[0].stopRequested,true)
})
test('an uncertain acceptance response is not automatically retried or replaced by a send',async t=>{
  const task=deliveredGif();const ui=taskUi(t,task);await flush();ui.steps.push({path:`/transfers/upload/tasks/${ID}/groups/1/accept-document`,method:'POST',value:new Error('timeout')});await ui.state.action(task,'accept-document','1');await flush()
  assert.equal(ui.calls.length,2);assert.match(ui.state.error.value,/不会自动重试/);assert.equal(ui.state.tasks.value[0].confirmed,0)
})
test('acceptance capability cannot legitimize an audio group, a ready task or a missing member',()=>{
  const wrong=deliveredGif();wrong.groups[0].mediaType='AUDIO';wrong.groups[0].items[0].mediaType='AUDIO';assert.equal(uploadTasks.validUploadTask(wrong),false)
  const ready=deliveredGif();ready.state='READY';assert.equal(uploadTasks.validUploadTask(ready),false)
  const missing=deliveredGif();missing.groups[0].items[0]=null;assert.equal(uploadTasks.validUploadTask(missing),false)
})
test('uncertain pending recovery is explicit and a timeout never triggers a retry or send',async t=>{
  const task=deliveredGif();task.groups[0].canAcceptDocument=false;const ui=taskUi(t,task);await flush();assert.equal(ui.calls.length,1)
  ui.steps.push({path:`/transfers/upload/tasks/${ID}/recover-pending`,method:'POST',value:new Error('timeout')});await ui.state.action(task,'reconcile');await flush()
  assert.equal(ui.calls.length,2);assert.equal(ui.calls[1].options.timeout,150000);assert.match(ui.state.error.value,/不会自动重试/);assert.equal(ui.state.tasks.value[0].state,'NEEDS_REVIEW')
})
test('confirmed files waiting for archive use only the existing archive retry route',async t=>{
  const task=savedTask();task.state='SENT_PENDING_ARCHIVE';task.confirmed=1;task.groups[0].state='CONFIRMED';task.groups[0].items[0].state='SENT';task.groups[0].items[0].formalMessageId='1000'
  const ui=taskUi(t,task);await flush();const queued=structuredClone(task);queued.state='QUEUED';queued.stopRequested=true
  ui.steps.push({path:`/transfers/upload/tasks/${ID}/reconcile`,method:'POST',value:queued});await ui.state.action(task,'reconcile')
  assert.equal(ui.calls.length,2);assert.equal(ui.state.tasks.value[0].stopRequested,true)
})

const failedTask=()=>{const task=savedTask();task.state='NEEDS_REVIEW';task.groups[0].state='UNCERTAIN';task.groups[0].items[0].state='FAILED';return task}
const failedCheck=()=>({id:'00000000-0000-0000-0000-000000000002',taskId:ID,groupId:'1',expiresAt:Date.now()+600000,confirmed:0,retryable:1,pending:0,unresolved:0,items:[{itemId:'item',fileName:'clip1.mp4',state:'RETRYABLE',reason:'native failed'}]})
test('checking a failed group never resends until the explicit failed-member action',async t=>{
  const task=failedTask(),ui=taskUi(t,task);await flush();assert.equal(uploadTasks.validUploadTask(task),true)
  ui.steps.push({path:`/transfers/upload/tasks/${ID}/retry-check`,method:'POST',value:failedCheck()});await ui.state.checkFailures(task);assert.equal(ui.state.retryCheck.value.retryable,1);assert.equal(ui.calls.length,2)
  const queued=structuredClone(task);queued.state='QUEUED';queued.stopRequested=true;queued.groups[0].hasRetries=true
  ui.steps.push({path:`/transfers/upload/tasks/${ID}/retry-failed`,method:'POST',value:queued});await ui.state.retryFailed(task);assert.equal(ui.calls.length,3);assert.equal(ui.calls[2].options.body.checkId,'00000000-0000-0000-0000-000000000002');assert.equal(ui.calls.some(c=>c.path.endsWith('/start')),false)
})
test('unknown failed-member submission is never repeated automatically and can be resolved by GET',async t=>{
  const task=failedTask(),ui=taskUi(t,task);await flush();ui.steps.push({path:`/transfers/upload/tasks/${ID}/retry-check`,method:'POST',value:failedCheck()});await ui.state.checkFailures(task)
  ui.steps.push({path:`/transfers/upload/tasks/${ID}/retry-failed`,method:'POST',value:new Error('timeout')});await ui.state.retryFailed(task);await ui.state.retryFailed(task);assert.equal(ui.state.retryUncertain.value,true);assert.equal(ui.calls.length,3)
  const operation=ui.calls[2].options.body.operationId;ui.steps.push({path:`/transfers/upload/tasks/${ID}/retry-operations/${operation}`,method:'GET',value:{task}});await ui.state.lookupRetry(task);assert.equal(ui.state.retryUncertain.value,false);assert.equal(ui.calls.filter(c=>c.path.endsWith('/retry-failed')).length,1)
})
test('explicit retry of the same uncertain request preserves operation and check identities',async t=>{
  const task=failedTask(),ui=taskUi(t,task);await flush();ui.steps.push({path:`/transfers/upload/tasks/${ID}/retry-check`,method:'POST',value:failedCheck()});await ui.state.checkFailures(task);ui.steps.push({path:`/transfers/upload/tasks/${ID}/retry-failed`,method:'POST',value:new Error('timeout')});await ui.state.retryFailed(task)
  ui.steps.push({path:`/transfers/upload/tasks/${ID}/retry-failed`,method:'POST',value:task});await ui.state.retryFailed(task,true);assert.deepEqual(ui.calls[2].options.body,ui.calls[3].options.body)
})
test('pending or unknown members cannot become a failed-member submission',async t=>{
  const task=failedTask(),ui=taskUi(t,task);await flush();const check=failedCheck();check.retryable=0;check.pending=1;check.items[0].state='PENDING';ui.steps.push({path:`/transfers/upload/tasks/${ID}/retry-check`,method:'POST',value:check});await ui.state.checkFailures(task);await ui.state.retryFailed(task);assert.equal(ui.calls.length,2)
})
test('a late failure check is discarded when authorization changes',async t=>{
  const task=failedTask(),ui=taskUi(t,task);await flush();let release;ui.steps.push({path:`/transfers/upload/tasks/${ID}/retry-check`,method:'POST',value:new Promise(r=>release=r)});const pending=ui.state.checkFailures(task);ui.props.authorized=false;await flush();release(failedCheck());await pending;assert.equal(ui.state.retryCheck.value,null);assert.equal(ui.state.tasks.value.length,0)
})
test('retry check must match real group membership and exact status counts',()=>{
  const task=failedTask(),check=failedCheck();assert.equal(uploadRetry.validRetryCheck(check,task),true);check.items[0].itemId='foreign';assert.equal(uploadRetry.validRetryCheck(check,task),false);check.items[0].itemId='item';check.confirmed=1;assert.equal(uploadRetry.validRetryCheck(check,task),false)
})
