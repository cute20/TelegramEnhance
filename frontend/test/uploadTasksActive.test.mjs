import * as taskHistory from '../src/taskHistory.ts'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'
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

test('switching upload panels preserves appended history while refreshing the head',async t=>{
  const head=Array.from({length:20},(_,i)=>task({id:'00000000-0000-0000-0000-'+String(i+1).padStart(12,'0')})),value=task({id:'00000000-0000-0000-0000-000000000021'}),f=mount(t,{steps:[{path:LIST,value:list(head)}]});await flush()
  f.expect('/transfers/upload/tasks?limit=20&offset=20',list([value]));await f.state.loadMore();assert.equal(f.state.offset.value,21);assert.equal(f.state.tasks.value.length,21)
  f.props.active=false;f.props.refreshVersion++;await flush();assert.equal(f.calls.length,2)
  f.expect(LIST,list(head));f.props.active=true;await flush()
  assert.equal(f.state.offset.value,21);assert.equal(f.state.tasks.value.length,21);assert.equal(f.calls.length,3)
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
