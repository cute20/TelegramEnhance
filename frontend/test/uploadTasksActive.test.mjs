import * as taskHistory from '../src/taskHistory.ts'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as uploadTasks from '../src/uploadTasks.ts'
import * as uploadPreview from '../src/uploadPreview.ts'
import * as uploadRetry from '../src/uploadRetry.ts'
import * as library from '../src/library.ts'

const ID='00000000-0000-0000-0000-000000000001',CHECK='00000000-0000-0000-0000-000000000002'
const LIST='/transfers/upload/tasks?limit=20&offset=0'
const flush=async()=>{await vue.nextTick();await new Promise(setImmediate)}
const deferred=()=>{let resolve;const promise=new Promise(done=>{resolve=done});return {promise,resolve}}
const task=(overrides={})=>({id:ID,state:'READY',channelTitle:'Synthetic',postTitle:'Synthetic',postLink:'https://t.me/synthetic/1',discussionTitle:'Synthetic',sourceDirectory:'D:\\source',archiveDirectory:'D:\\archive',stopRequested:false,total:1,confirmed:0,archived:0,error:null,createdAt:1,updatedAt:1,groups:[{id:'group',number:1,mediaType:'DOCUMENT',state:'READY',items:[{id:'item',fileName:'sample.txt',mediaType:'DOCUMENT',size:3,state:'READY',formalMessageId:null,archivePath:null,error:null}]}],...overrides})
const reviewTask=()=>{const value=task({state:'NEEDS_REVIEW'});value.groups[0].state='UNCERTAIN';value.groups[0].items[0].state='FAILED';return value}
const archiveTask=(count=9)=>{const value=task({state:'NEEDS_REVIEW',total:count,confirmed:count});value.groups[0].state='CONFIRMED';value.groups[0].items=Array.from({length:count},(_,index)=>({...value.groups[0].items[0],id:'item-'+index,fileName:`sample-${index+1}.txt`,state:'SENT',formalMessageId:String(1000+index)}));return value}
const check=()=>({id:CHECK,taskId:ID,groupId:'group',expiresAt:Date.now()+60_000,confirmed:0,retryable:1,pending:0,unresolved:0,items:[{itemId:'item',fileName:'sample.txt',state:'RETRYABLE',reason:'Synthetic confirmed failure'}]})
const list=items=>({items,workerWarning:null})
class ApiError extends Error{constructor(message,status){super(message);this.status=status}}

function mount(t,{props:overrides={},steps:initial=[]}={}){
  const source=readFileSync(new URL('../src/components/UploadTasks.vue',import.meta.url),'utf8')
  const parsed=parse(source,{filename:'UploadTasks.vue'})
  const compiled=ts.transpileModule(compileScript(parsed.descriptor,{id:'UploadTasks'}).content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
  const steps=[...initial],calls=[],failures=[],timers=[],hooks=[],events=[]
  const props=vue.reactive({accountId:'1',authorized:true,available:true,active:true,refreshVersion:0,...overrides})
  let activeRequests=0,maxRequests=0
  const api=async(path,options={})=>{
    calls.push({path,options});const expected=steps.shift()
    try{assert.ok(expected,path);assert.equal(path,expected.path);assert.equal(options.method??'GET',expected.method??'GET')}catch(cause){failures.push(cause);throw cause}
    activeRequests++;maxRequests=Math.max(maxRequests,activeRequests)
    try{const value=await expected.value;if(value instanceof Error)throw value;return value}finally{activeRequests--}
  }
  const modules={vue:{...vue,onBeforeUnmount:callback=>hooks.push(callback)},'../api':{api,ApiError},'../uploadTasks':uploadTasks,'../uploadPreview':uploadPreview,'../uploadRetry':uploadRetry,'../taskHistory':taskHistory,'../library':library}
  const module={exports:{}}
  new Function('require','module','exports','setTimeout','clearTimeout',compiled)(name=>{if(name.endsWith('.vue'))return {};assert.ok(Object.hasOwn(modules,name),name);return modules[name]},module,module.exports,(callback,delay)=>{const token={callback,delay,cancelled:false};timers.push(token);return token},token=>{if(token)token.cancelled=true})
  const scope=vue.effectScope(),state=scope.run(()=>module.exports.default.setup(props,{expose(){},emit:(...value)=>events.push(value)}))
  let disposed=false
  const destroy=()=>{if(disposed)return;disposed=true;hooks.forEach(callback=>callback());scope.stop()}
  t.after(()=>{destroy();assert.deepEqual(failures,[]);assert.equal(steps.length,0)})
  return {state,props,calls,timers,events,destroy,get maxRequests(){return maxRequests},expect:(path,value,method='GET')=>steps.push({path,value,method})}
}

function renderRecords(f){
  const source=readFileSync(new URL('../src/components/UploadTasks.vue',import.meta.url),'utf8')
  const parsed=parse(source,{filename:'UploadTasks.vue'})
  const template=compileTemplate({source:parsed.descriptor.template.content,filename:'UploadTasks.vue',id:'UploadTasks',compilerOptions:{mode:'function',expressionPlugins:['typescript']}})
  assert.deepEqual(template.errors,[])
  const code=ts.transpileModule(template.code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
  const render=new Function('Vue',code)({...vue,resolveComponent:name=>name})
  const root=render(vue.proxyRefs({...f.state,...f.props}),[]),records=[]
  function visit(node){if(!node||typeof node!=='object')return;if(node.type==='CompactRecord')records.push(node);if(Array.isArray(node.children))node.children.forEach(visit)}
  visit(root);return records
}

function nodes(value){
  if(Array.isArray(value))return value.flatMap(nodes)
  if(!value||typeof value!=='object')return []
  return [value,...(Array.isArray(value.children)?nodes(value.children):[])]
}
function text(value){return Array.isArray(value)?value.map(text).join(''):value&&typeof value==='object'?text(value.children):typeof value==='string'?value:''}
const recordBody=(f,index=0)=>{const record=renderRecords(f)[index];return nodes(typeof record.children?.default==='function'?record.children.default():record.children)}

test('paused tasks only offer receipt recovery when some member still has sending evidence',()=>{
  const value=task({state:'PAUSED'})
  assert.equal(uploadTasks.canReconcileUpload(value),false)
  value.groups[0].items[0].state='STAGED';assert.equal(uploadTasks.canReconcileUpload(value),false)
  value.groups[0].state='SENDING';assert.equal(uploadTasks.canReconcileUpload(value),true)
  const past=archiveTask(1);Object.assign(past,{state:'PAUSED',total:2,archived:1})
  Object.assign(past.groups[0].items[0],{state:'ARCHIVED',archivePath:'D:\\archive\\sample.txt'})
  past.groups.push({...task().groups[0],id:'next',items:[{...task().groups[0].items[0],id:'next-item'}]})
  assert.equal(uploadTasks.validUploadTask(past),true)
  assert.equal(uploadTasks.canReconcileUpload(past),false,'an archived previous group must not obstruct an untouched next group')
  for(const state of ['PENDING','FAILED']){const pending=task({state:'PAUSED'});pending.groups[0].items[0].state=state;assert.equal(uploadTasks.canReconcileUpload(pending),true)}
  assert.equal(uploadTasks.canReconcileUpload({...archiveTask(1),state:'PAUSED'}),true)
})

test('untouched paused task renders continue and cancel without a misleading receipt-recovery button',async t=>{
  const value=task({state:'PAUSED'}),f=mount(t,{steps:[{path:LIST,value:list([value])}]});await flush()
  const body=recordBody(f)
  assert.ok(body.some(node=>node.type==='button'&&text(node)==='继续未发送组'))
  assert.ok(body.some(node=>node.type==='button'&&text(node)==='取消未发送任务'))
  assert.ok(!body.some(node=>node.type==='button'&&/核对|归档/.test(text(node))))
  assert.equal(f.timers.filter(timer=>!timer.cancelled).length,0)
})

test('fully confirmed review explains pending archive accurately and keeps only a closed recovery fallback',async t=>{
  const value=archiveTask(),f=mount(t,{steps:[{path:LIST,value:list([value])}]});await flush()
  assert.equal(uploadTasks.validUploadTask(value),true);assert.equal(uploadTasks.awaitingArchiveVerification(value),true)
  const body=recordBody(f),status=body.find(node=>node.props?.role==='status'),fallback=body.find(node=>node.type==='details'&&node.props?.class==='upload-archive-fallback')
  assert.equal(text(status),'正式回执已齐，等待后台核对并归档（不会重发）。')
  assert.ok(fallback);assert.notEqual(fallback.props?.open,true)
  const fallbackButtons=nodes(fallback).filter(node=>node.type==='button')
  assert.deepEqual(fallbackButtons.map(text),['核对发送结果','核对已有回执，不重发'])
  assert.ok(body.filter(node=>node.props?.class==='upload-task-actions').at(-1).children.every(node=>node.type!== 'button'))
  assert.ok(!body.some(node=>node.type==='button'&&/开始|继续未发送|重传/.test(text(node))))
  assert.equal(f.calls.length,1);assert.ok(f.calls.every(call=>!call.options.method))
  for(const changed of [{confirmed:8},{archived:9},{state:'SENT_PENDING_ARCHIVE'}])assert.equal(uploadTasks.awaitingArchiveVerification({...value,...changed}),false)
  const unresolved=archiveTask();unresolved.groups[0].state='UNCERTAIN';assert.equal(uploadTasks.awaitingArchiveVerification(unresolved),false)
})

test('fully confirmed review follows only local GET progress through archive completion without mutating or collapsing its card',async t=>{
  const value=archiveTask(),f=mount(t,{steps:[{path:LIST,value:list([value])}]});await flush()
  const initial=renderRecords(f)[0],known=f.state.tasks.value[0]
  f.state.toggleGroup(ID,'group',{currentTarget:{open:true}})
  let timer=f.timers.findLast(timer=>!timer.cancelled);assert.equal(timer.delay,2000)
  f.expect('/transfers/upload/tasks/'+ID,{...value,state:'RUNNING',updatedAt:2});timer.callback();await flush()
  assert.equal(f.state.tasks.value[0].state,'RUNNING');assert.equal(f.state.loading.value,false);assert.equal(f.state.busy.value,'')
  const completed=archiveTask();Object.assign(completed,{state:'COMPLETED',archived:9,updatedAt:3});completed.groups[0].items.forEach(item=>Object.assign(item,{state:'ARCHIVED',archivePath:'D:\\archive\\'+item.fileName}))
  timer=f.timers.findLast(timer=>!timer.cancelled);f.expect('/transfers/upload/tasks/'+ID,completed);timer.callback();await flush()
  assert.equal(f.state.tasks.value[0].state,'COMPLETED');assert.notEqual(f.state.tasks.value[0],known)
  assert.equal(renderRecords(f)[0].key,initial.key);assert.equal(f.state.groupOpen(f.state.tasks.value[0],'group'),true)
  assert.equal(f.state.offset.value,1);assert.equal(f.calls.filter(call=>call.path===LIST).length,1)
  assert.ok(f.calls.every(call=>!call.options.method),'archive observation must never submit start, reconcile, or retry')
  assert.ok(f.timers.every(timer=>timer.cancelled))
})

test('partial unknown review and archive failures do not enter the full-formal automatic observation category',async t=>{
  const partial=reviewTask(),failed={...archiveTask(1),id:'archive-failure',state:'SENT_PENDING_ARCHIVE',error:'Synthetic move failure'}
  const f=mount(t,{steps:[{path:LIST,value:list([partial,failed])}]});await flush()
  assert.equal(f.timers.filter(timer=>!timer.cancelled).length,0);await f.state.readProgress();assert.equal(f.calls.length,1)
  const body=recordBody(f),button=body.find(node=>node.type==='button'&&text(node)==='核对发送结果')
  assert.ok(button);assert.ok(!text(body).includes('核对并继续'))
  f.expect('/transfers/upload/tasks/'+ID+'/retry-check',check(),'POST');await button.props.onClick();await flush()
  assert.equal(f.calls.at(-1).path,'/transfers/upload/tasks/'+ID+'/retry-check')
  assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
  const failedBody=recordBody(f,1),retry=failedBody.find(node=>node.type==='button'&&text(node)==='仅重试归档')
  assert.ok(retry);f.expect('/transfers/upload/tasks/archive-failure/reconcile',failed,'POST');await retry.props.onClick();await flush()
  assert.equal(f.calls.at(-1).path,'/transfers/upload/tasks/archive-failure/reconcile')
  assert.ok(!f.calls.some(call=>call.path.endsWith('/start')||call.path.endsWith('/retry-failed')))
})

test('archive follow-up GET is discarded after leaving the page and stops scheduling until a deliberate return',async t=>{
  const value=archiveTask(),f=mount(t,{steps:[{path:LIST,value:list([value])}]});await flush()
  const pending=deferred(),timer=f.timers.findLast(timer=>!timer.cancelled);f.expect('/transfers/upload/tasks/'+ID,pending.promise);timer.callback();await flush()
  f.props.active=false;await flush();pending.resolve({...value,state:'RUNNING',updatedAt:2});await flush()
  assert.equal(f.state.tasks.value[0].state,'NEEDS_REVIEW');assert.ok(f.timers.every(timer=>timer.cancelled));assert.equal(f.calls.length,2)
})

test('upload records pass panel activity and always start closed, including newly returned running tasks',async t=>{
  const value=task({state:'RUNNING'}),f=mount(t,{steps:[{path:LIST,value:list([value])}]});await flush()
  let records=renderRecords(f)
  assert.equal(records.length,1);assert.equal(records[0].props.active,true);assert.equal(records[0].props['default-expanded'],false)
  assert.match(records[0].props.description,/已归档 0/)
  f.props.active=false;await flush();records=renderRecords(f)
  assert.equal(records[0].props.active,false);assert.equal(records[0].props['default-expanded'],false)
  const fresh=task({id:'new-running-task',state:'RUNNING',createdAt:2})
  f.expect(LIST,list([fresh,value]));f.props.active=true;await flush();records=renderRecords(f)
  assert.equal(records.length,2)
  assert.ok(records.every(record=>record.props.active===true&&record.props['default-expanded']===false))
  assert.equal(records[0].key,fresh.id)
})

test('inactive upload panel does not load, poll, page or issue mutations, and first activation only reads',async t=>{
  const value=task(),f=mount(t,{props:{active:false}});await flush()
  await f.state.load();f.state.loadMore();await f.state.action(value,'start');await f.state.checkFailures(reviewTask());await f.state.retryFailed(reviewTask());await f.state.lookupRetry(reviewTask())
  assert.equal(f.calls.length,0);assert.equal(f.state.offset.value,0)
  f.expect(LIST,list([value]));f.props.active=true;await flush();assert.equal(f.calls.length,1)
  assert.equal(f.timers.length,0,'READY history does not schedule automatic reads');f.props.active=false;await flush()
  await f.state.readProgress();assert.equal(f.calls.length,1);assert.equal(f.state.tasks.value[0].id,ID)
})

test('upload progress reads only the known active task and leaves history loading and controls untouched',async t=>{
  const active=task({state:'RUNNING'}),f=mount(t,{steps:[{path:LIST,value:list([active])}]});await flush()
  const response=deferred();f.expect('/transfers/upload/tasks/'+ID,response.promise)
  const reading=f.state.readProgress();assert.equal(f.state.loading.value,false);assert.equal(f.state.busy.value,'')
  await f.state.readProgress();assert.equal(f.calls.length,2)
  response.resolve(task({state:'RUNNING',updatedAt:2}));await reading
  assert.equal(f.state.tasks.value[0].updatedAt,2);assert.equal(f.state.offset.value,1)
  assert.equal(f.state.hasMore.value,false);assert.equal(f.calls.filter(call=>call.path===LIST).length,1)
})

test('user pause supersedes an older upload progress read and a transient progress failure does not flash an error',async t=>{
  const active=task({state:'RUNNING'}),f=mount(t,{steps:[{path:LIST,value:list([active])}]});await flush()
  f.expect('/transfers/upload/tasks/'+ID,new Error('temporary progress error'));await f.state.readProgress()
  assert.equal(f.state.error.value,'');assert.equal(f.state.tasks.value[0].state,'RUNNING')
  const response=deferred();f.expect('/transfers/upload/tasks/'+ID,response.promise);const reading=f.state.readProgress()
  f.expect('/transfers/upload/tasks/'+ID+'/pause',task({state:'PAUSED'}),'POST');await f.state.action(active,'pause')
  response.resolve(active);await reading;assert.equal(f.state.tasks.value[0].state,'PAUSED')
  assert.equal(f.timers.filter(timer=>!timer.cancelled).length,0)
})

test('reactivation and refresh nonce changes coalesce behind an in-flight GET without overlap',async t=>{
  const pending=deferred(),value=task(),f=mount(t,{steps:[{path:LIST,value:pending.promise}]})
  f.props.active=false;await flush();f.props.active=true;f.props.refreshVersion++;await flush();f.props.refreshVersion++;await flush()
  assert.equal(f.calls.length,1)
  f.expect(LIST,list([value]));pending.resolve(list([value]));await flush();await flush()
  assert.equal(f.calls.length,2);assert.equal(f.maxRequests,1);assert.equal(f.state.tasks.value[0].id,ID)
})

test('hidden panel retains successful in-flight POST receipt and activation never resends it',async t=>{
  const value=task(),queued=task({state:'QUEUED'}),pending=deferred(),f=mount(t,{steps:[{path:LIST,value:list([value])}]});await flush()
  f.expect('/transfers/upload/tasks/'+ID+'/start',pending.promise,'POST');const action=f.state.action(value,'start')
  f.props.active=false;await flush();pending.resolve(queued);await action;await flush()
  assert.equal(f.state.tasks.value[0].state,'QUEUED');assert.equal(f.state.busy.value,'');assert.equal(f.calls.length,2)
  f.expect(LIST,list([queued]));f.props.active=true;await flush();assert.equal(f.calls.length,3)
  assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
})

test('activation during POST queues one GET until the original response is applied',async t=>{
  const value=task(),queued=task({state:'QUEUED'}),pending=deferred(),f=mount(t,{steps:[{path:LIST,value:list([value])}]});await flush()
  f.expect('/transfers/upload/tasks/'+ID+'/start',pending.promise,'POST');const action=f.state.action(value,'start')
  f.props.active=false;await flush();f.props.active=true;f.props.refreshVersion++;await flush();assert.equal(f.calls.length,2)
  f.expect(LIST,list([queued]));pending.resolve(queued);await action;await flush()
  assert.equal(f.calls.length,3);assert.equal(f.maxRequests,1);assert.equal(f.state.tasks.value[0].state,'QUEUED')
})

test('switching upload panels refreshes the full appended range while preserving rows and paging',async t=>{
  const head=Array.from({length:20},(_,i)=>task({id:'00000000-0000-0000-0000-'+String(i+1).padStart(12,'0')})),value=task({id:'00000000-0000-0000-0000-000000000021'}),f=mount(t,{steps:[{path:LIST,value:list(head)}]});await flush()
  f.expect('/transfers/upload/tasks?limit=20&offset=20',list([value]));await f.state.loadMore();assert.equal(f.state.offset.value,21);assert.equal(f.state.tasks.value.length,21)
  f.props.active=false;f.props.refreshVersion++;await flush();assert.equal(f.calls.length,2)
  const order=f.state.tasks.value.map(item=>item.id)
  f.expect(LIST,list(head));f.expect('/transfers/upload/tasks?limit=20&offset=20',list([{...value,updatedAt:2}]))
  f.props.active=true;await flush()
  assert.equal(f.state.offset.value,21);assert.equal(f.state.tasks.value.length,21);assert.equal(f.calls.length,4)
  assert.equal(f.state.tasks.value.at(-1).updatedAt,2);assert.equal(f.state.hasMore.value,false)
  assert.deepEqual(f.state.tasks.value.map(item=>item.id),order);assert.equal(f.maxRequests,1)
})

test('return refresh keeps upload file disclosures open or user-closed when task completion changes',async t=>{
  const value=task({state:'RUNNING'}),completed=task({state:'COMPLETED',confirmed:1,archived:1,updatedAt:2})
  completed.groups[0].state='ARCHIVED'
  Object.assign(completed.groups[0].items[0],{state:'ARCHIVED',formalMessageId:'99',archivePath:'D:\\archive\\sample.txt'})
  const f=mount(t,{steps:[{path:LIST,value:list([value])}]});await flush()
  assert.equal(f.state.groupOpen(f.state.tasks.value[0],'group'),true)
  f.props.active=false;await flush();f.expect(LIST,list([completed]));f.props.active=true;await flush()
  assert.equal(f.state.tasks.value[0].state,'COMPLETED')
  assert.equal(f.state.groupOpen(f.state.tasks.value[0],'group'),true,'completion must not collapse already-open file details')
  f.state.toggleGroup(ID,'group',{currentTarget:{open:false}})
  f.expect(LIST,list([{...completed,updatedAt:3}]));f.props.refreshVersion++;await flush()
  assert.equal(f.state.groupOpen(f.state.tasks.value[0],'group'),false,'refresh must keep a user-closed file disclosure closed')
})
const historyId=n=>'00000000-0000-0000-0000-'+String(n).padStart(12,'0')
const history=(start,count=20)=>Array.from({length:count},(_,index)=>task({id:historyId(start+index)}))
test('return after EOF reads the loaded range, supplements its displaced tail, and puts a new head first using history ordering',async t=>{
  const first=history(1),second=history(21),f=mount(t,{steps:[{path:LIST,value:list(first)}]});await flush()
  f.expect('/transfers/upload/tasks?limit=20&offset=20',list(second));await f.state.loadMore()
  f.expect('/transfers/upload/tasks?limit=20&offset=40',list([]));await f.state.loadMore()
  assert.equal(f.state.offset.value,40);assert.equal(f.state.hasMore.value,false)
  const order=f.state.tasks.value.map(item=>item.id),updated=[...first,...second].map(item=>({...item,updatedAt:2})),newest=task({id:historyId(101),createdAt:2})
  f.props.active=false;await flush()
  f.expect(LIST,list([newest,...updated.slice(0,19)]));f.expect('/transfers/upload/tasks?limit=20&offset=20',list(updated.slice(19,39)))
  f.expect('/transfers/upload/tasks/'+historyId(40),updated[39]);f.props.active=true;f.props.refreshVersion++;await flush()
  assert.equal(f.state.offset.value,40);assert.equal(f.state.hasMore.value,true);assert.equal(f.state.tasks.value.length,41)
  assert.equal(f.state.tasks.value[0].id,newest.id)
  assert.deepEqual(f.state.tasks.value.slice(1).map(item=>item.id),order);assert.ok(f.state.tasks.value.slice(1).every(item=>item.updatedAt===2))
  assert.equal(f.maxRequests,1);assert.ok(f.calls.every(call=>(call.options.method??'GET')==='GET'))
})
test('failed later refresh page leaves every visible upload row and cursor intact and does not retry until the user refreshes',async t=>{
  const first=history(1),second=history(21),f=mount(t,{steps:[{path:LIST,value:list(first)}]});await flush()
  f.expect('/transfers/upload/tasks?limit=20&offset=20',list(second));await f.state.loadMore()
  const order=f.state.tasks.value.map(item=>item.id),updated=first.map(item=>({...item,updatedAt:2}))
  f.expect(LIST,list(updated));f.expect('/transfers/upload/tasks?limit=20&offset=20',new Error('tail offline'))
  f.props.refreshVersion++;await flush();await flush()
  assert.equal(f.calls.length,4);assert.equal(f.state.offset.value,40);assert.equal(f.state.hasMore.value,true)
  assert.deepEqual(f.state.tasks.value.map(item=>item.id),order);assert.ok(f.state.tasks.value.every(item=>item.updatedAt===1));assert.match(f.state.error.value,/tail offline/)
  f.expect(LIST,list(updated));f.expect('/transfers/upload/tasks?limit=20&offset=20',list(second.map(item=>({...item,updatedAt:2}))))
  await f.state.load();assert.ok(f.state.tasks.value.every(item=>item.updatedAt===2));assert.equal(f.calls.length,6)
})
test('hidden refresh result cannot overwrite retained records or continue reading remaining history pages',async t=>{
  const first=history(1),second=history(21),f=mount(t,{steps:[{path:LIST,value:list(first)}]});await flush()
  f.expect('/transfers/upload/tasks?limit=20&offset=20',list(second));await f.state.loadMore()
  const response=deferred();f.expect(LIST,response.promise);f.props.refreshVersion++;await flush()
  f.props.active=false;await flush();response.resolve(list(first.map(item=>({...item,updatedAt:9}))));await flush()
  assert.equal(f.calls.length,3);assert.ok(f.state.tasks.value.every(item=>item.updatedAt===1));assert.equal(f.state.offset.value,40)
  f.expect(LIST,list(first.map(item=>({...item,updatedAt:2}))));f.expect('/transfers/upload/tasks?limit=20&offset=20',list(second.map(item=>({...item,updatedAt:2}))))
  f.props.active=true;await flush();assert.equal(f.calls.length,5);assert.ok(f.state.tasks.value.every(item=>item.updatedAt===2));assert.equal(f.maxRequests,1)
})
test('return refresh waits for an older local-progress GET and never lets that stale progress overwrite the refreshed record',async t=>{
  const value=task({state:'RUNNING'}),f=mount(t,{steps:[{path:LIST,value:list([value])}]});await flush()
  const response=deferred();f.expect('/transfers/upload/tasks/'+ID,response.promise);const progress=f.state.readProgress()
  f.props.active=false;await flush();f.props.active=true;f.props.refreshVersion++;await flush();assert.equal(f.calls.length,2)
  f.expect(LIST,list([task({state:'PAUSED',updatedAt:2})]));response.resolve(value);await progress;await flush()
  assert.equal(f.calls.length,3);assert.equal(f.state.tasks.value[0].state,'PAUSED');assert.equal(f.state.tasks.value[0].updatedAt,2);assert.equal(f.maxRequests,1)
})

test('late GET after account change is discarded and only the next active account is fetched',async t=>{
  const pending=deferred(),first=task(),second=task({id:'other-account-task'}),f=mount(t,{steps:[{path:LIST,value:pending.promise}]})
  f.props.active=false;f.props.accountId='2';await flush();pending.resolve(list([first]));await flush();assert.equal(f.state.tasks.value.length,0)
  f.expect(LIST,list([second]));f.props.active=true;await flush();assert.equal(f.state.tasks.value[0].id,second.id)
})

test('late POST after account change cannot restore the old task or clear current account state',async t=>{
  const value=task(),pending=deferred(),f=mount(t,{steps:[{path:LIST,value:list([value])}]});await flush()
  f.expect('/transfers/upload/tasks/'+ID+'/start',pending.promise,'POST');const action=f.state.action(value,'start')
  f.props.active=false;f.props.accountId='2';await flush();pending.resolve(task({state:'QUEUED'}));await action;await flush()
  assert.deepEqual(f.state.tasks.value,[]);assert.equal(f.state.error.value,'');assert.equal(f.calls.length,2)
})

test('takeover dialog cannot open after its panel hides during nextTick and cannot submit while hidden',async t=>{
  const value=task({automation:{ruleId:ID,name:'Synthetic rule',version:1,manualTakeover:false,windowMode:'ALL_DAY',start:null,end:null,timezone:'Asia/Shanghai',status:'RULE_PAUSED',reason:'paused',nextAllowedAt:1}})
  const f=mount(t,{steps:[{path:LIST,value:list([value])}]});await flush();let opened=0,closed=0
  f.state.takeoverDialog.value={showModal(){opened++},close(){closed++}}
  const preparation=f.state.prepareTakeover(value);f.props.active=false;await preparation;await flush()
  assert.equal(opened,0);assert.equal(f.state.takeover.value,null);await f.state.confirmTakeover();assert.equal(f.calls.length,1)
  f.expect(LIST,list([value]));f.props.active=true;await flush();await f.state.prepareTakeover(value);assert.equal(opened,1)
  f.props.active=false;await flush();assert.equal(f.state.takeover.value,null);assert.ok(closed>0);await f.state.confirmTakeover();assert.equal(f.calls.length,2)
})

async function retryTimeout(t){
  const value=reviewTask(),f=mount(t,{steps:[{path:LIST,value:list([value])}]});await flush()
  f.expect('/transfers/upload/tasks/'+ID+'/retry-check',check(),'POST');await f.state.checkFailures(value)
  const pending=deferred();f.expect('/transfers/upload/tasks/'+ID+'/retry-failed',pending.promise,'POST');const attempt=f.state.retryFailed(value)
  f.props.active=false;await flush();pending.resolve(new Error('Synthetic timeout'));await attempt;await flush()
  assert.equal(f.state.retryUncertain.value,true)
  return {f,value,operation:f.calls.at(-1).options.body}
}

test('unknown retry stays attached to its task across hidden refresh and only explicit operation GET unlocks it',async t=>{
  const {f,value,operation}=await retryTimeout(t);f.props.active=true;f.props.refreshVersion++;await flush();await f.state.load();await flush()
  assert.equal(f.calls.length,3);assert.equal(f.state.tasks.value[0].id,ID);assert.equal(f.state.retryUncertain.value,true)
  await f.state.action(value,'reconcile');f.state.loadMore();assert.equal(f.calls.length,3);assert.equal(f.state.offset.value,1)
  f.expect('/transfers/upload/tasks/'+ID+'/retry-operations/'+operation.operationId,{task:value});f.expect(LIST,list([value]));await f.state.lookupRetry(value);await flush()
  assert.equal(f.state.retryUncertain.value,false);assert.equal(f.calls.filter(call=>call.options.method==='POST').length,2)
  assert.equal(f.calls.at(-1).path,LIST)
})

test('explicit retry after panel switching reuses the original operation and check, with no automatic POST',async t=>{
  const {f,value,operation}=await retryTimeout(t);f.props.active=true;await flush();assert.equal(f.calls.length,3)
  f.expect('/transfers/upload/tasks/'+ID+'/retry-failed',value,'POST');f.expect(LIST,list([value]));await f.state.retryFailed(value,true);await flush()
  const retries=f.calls.filter(call=>call.path.endsWith('/retry-failed'));assert.equal(retries.length,2);assert.deepEqual(retries[1].options.body,operation)
  assert.equal(f.state.retryUncertain.value,false)
})

test('disposing an upload panel discards in-flight GET and schedules no later work',async t=>{
  const pending=deferred(),f=mount(t,{steps:[{path:LIST,value:pending.promise}]});f.destroy();pending.resolve(list([task()]));await flush()
  assert.equal(f.state.tasks.value.length,0);assert.ok(f.timers.every(timer=>timer.cancelled));assert.equal(f.calls.length,1)
})
