import * as uploadTasks from '../src/uploadTasks.ts'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import {parse,compileScript,compileTemplate} from 'vue/compiler-sfc'
import ts from 'typescript'
import * as rules from '../src/rules.ts'
import * as upload from '../src/uploadPreview.ts'
import * as library from '../src/library.ts'
import * as preparation from '../src/rulePreparation.ts'
import {byteSplitGroups,byteSplitCounts} from './fixtures/uploadByteGroups.mjs'
const ID='00000000-0000-0000-0000-000000000001'
const input=()=>({name:'sample',link:'https://t.me/synthetic/1',sourceDirectory:'D:\\source',archiveDirectory:'D:\\archive',recursive:true,captionMode:'FILENAME',albumSize:10,window:{mode:'ALL_DAY',start:null,end:null,timezone:'Asia/Shanghai'},executionMode:'OBSERVE_ONLY'})
const result=()=>({mode:'OBSERVE_ONLY',state:'PAUSED',scanning:false,nextAllowedAt:1,rule:{id:ID,accountId:'1',version:1,revision:1,enabled:false,nextScanAt:0,createdAt:1,updatedAt:1,input:input(),observation:null}})
const flush=async()=>{await vue.nextTick();await new Promise(setImmediate)}
function mount(t,initialProps={}){
  const {descriptor}=parse(readFileSync(new URL('../src/components/RuleWorkspace.vue',import.meta.url),'utf8'),{filename:'RuleWorkspace.vue'})
  const script=compileScript(descriptor,{id:'rules-test'}),template=compileTemplate({source:descriptor.template.content,filename:'RuleWorkspace.vue',id:'rules-test',compilerOptions:{bindingMetadata:script.bindings,expressionPlugins:['typescript']}})
  assert.deepEqual(template.errors,[])
  const transpile=source=>ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText,code=transpile(script.content),renderCode=transpile(template.code)
  const props=vue.reactive({authorized:true,available:true,accountId:'1',...initialProps}),calls=[],steps=props.createOnly?[]:[{path:'/rules',method:'GET',value:{mode:'OBSERVE_ONLY',items:[]}}],errors=[],hooks=[],mounted=[],events=[]
  let now=100000,serial=0,activeRequests=0,maxRequests=0;const timers=new Map()
  class ClockDate extends Date{static now(){return now}}
  const setTimer=(callback,delay)=>{const id=++serial;timers.set(id,{callback,due:now+delay});return id},clearTimer=id=>timers.delete(id)
  const api=async(path,options={})=>{calls.push({path,options});const step=steps.shift();try{assert.ok(step,path);assert.equal(path,step.path);assert.equal(options.method??'GET',step.method??'POST')}catch(e){errors.push(e);throw e}activeRequests++;maxRequests=Math.max(maxRequests,activeRequests);try{const value=await(typeof step.value==='function'?step.value(options):step.value);if(value instanceof Error)throw value;return value}finally{activeRequests--}}
  const modules={vue:{...vue,onMounted:f=>mounted.push(f),onBeforeUnmount:f=>hooks.push(f),withCtx:fn=>fn,withDirectives:node=>node},'../api':{api,ApiError},'../uploadTasks':uploadTasks,'../rules':rules,'../rulePreparation':preparation,'../uploadPreview':upload,'../library':library},module={exports:{}},rendered={exports:{}}
  const require=name=>name.endsWith('.vue')?{__esModule:true,default:{__name:name.split('/').at(-1).replace('.vue','')}}:modules[name]
  new Function('require','module','exports','setTimeout','clearTimeout','Date',code)(require,module,module.exports,setTimer,clearTimer,ClockDate)
  new Function('require','module','exports',renderCode)(require,rendered,rendered.exports)
  const scope=vue.effectScope(),state=scope.run(()=>module.exports.default.setup(props,{expose(){},emit:(...event)=>events.push(event)}))
  mounted.forEach(f=>f())
  let disposed=false;const destroy=()=>{if(!disposed){disposed=true;hooks.forEach(f=>f());scope.stop()}}
  t.after(()=>{destroy();assert.deepEqual(errors,[]);assert.equal(steps.length,0);assert.equal(timers.size,0)})
  const expect=(path,value,method='POST')=>steps.push({path,value,method})
  const receipt=(request,state='RUNNING',value=null)=>({operationId:request.operationId,ruleId:request.ruleId,accountId:request.accountId,version:request.version,revision:request.revision,kind:request.kind,state,startedAt:request.startedAt??now,finishedAt:state==='RUNNING'?null:now,error:state==='FAILED'?String(value):null,result:state==='COMPLETED'?value:null})
  const advance=async milliseconds=>{const target=now+milliseconds;let guard=0;while(true){const next=[...timers].sort((a,b)=>a[1].due-b[1].due).find(([,timer])=>timer.due<=target);if(!next)break;assert.ok(++guard<450);now=next[1].due;timers.delete(next[0]);next[1].callback();await flush()}now=target;await flush()}
  return {state,props,calls,events,timers,destroy,advance,receipt,get maxRequests(){return maxRequests},expect,render:()=>rendered.exports.render({},[],props,vue.proxyRefs(state),{},{}),expectPreparation:(rule,kind,value)=>expect('/rules/'+rule.rule.id+'/preparations',async options=>{const result=await value;if(result instanceof Error)throw result;return receipt({operationId:options.body.operationId,ruleId:rule.rule.id,accountId:rule.rule.accountId,version:rule.rule.version,revision:rule.rule.revision,kind},'COMPLETED',result)})}
}
class ApiError extends Error{constructor(message,status){super(message);this.status=status}}
test('upload rules update only on an explicit refresh and retain the open draft',async t=>{
  const f=mount(t);await flush();f.state.edit(null);f.state.form.name='stable upload draft'
  assert.equal(f.calls.length,1);assert.equal(f.state.formOpen.value,true)
  assert.equal(f.state.form.name,'stable upload draft');assert.equal(f.state.busy.value,'')
  f.state.closeForm();f.expect('/rules',{mode:'RULES',items:[]},'GET');await f.state.refresh()
  assert.equal(f.calls.length,2)
})

test('only observation results for this account pass validation',()=>{const r=result();assert.equal(rules.validRule(r,'1'),true);assert.equal(rules.validRule(r,'2'),false);r.mode='AUTO_UPLOAD';assert.equal(rules.validRule(r,'1'),false)})
test('saving creates a paused rule and never creates or starts a transfer',async t=>{const f=mount(t);await flush();f.state.edit(null);Object.assign(f.state.form,input());f.expect('/rules',result());await f.state.save();assert.equal(f.state.items.value[0].rule.enabled,false);assert.equal(f.calls.length,2);assert.equal(f.calls[1].options.body.input.window.mode,'ALL_DAY');assert.equal(f.calls.some(c=>c.path.includes('transfers')),false)})
test('editing reactive rule copies fields and saves a new version with original revision',async t=>{const f=mount(t);await flush();f.state.items.value=[result()];f.state.edit(f.state.items.value[0]);f.state.form.window={mode:'WINDOW',start:'22:00',end:'08:00',timezone:'Asia/Shanghai'};assert.equal(f.state.items.value[0].rule.input.window.mode,'ALL_DAY');const next=result();next.rule.version=2;next.rule.revision=2;next.rule.input.window=f.state.form.window;f.expect('/rules/'+ID,next,'PUT');await f.state.save();assert.equal(f.calls[1].options.body.revision,1);assert.equal(f.state.items.value[0].rule.version,2)})
test('editing upload group size preserves the saved rule until an explicit paused revision is saved',async t=>{
  const f=mount(t);await flush();const saved=result();saved.rule.input.albumSize=3;f.state.edit(saved)
  assert.equal(f.state.form.albumSize,3)
  const size=nodes(f.render()).find(node=>node.type==='select'&&nodes(node).filter(child=>child.type==='option').length===10)
  assert.ok(size);size.props['onUpdate:modelValue'](1);await flush()
  assert.equal(f.state.form.albumSize,1);assert.equal(saved.rule.input.albumSize,3)
  const next=result();next.rule.version=2;next.rule.revision=2;next.rule.input.albumSize=1
  f.expect('/rules/'+ID,next,'PUT');await f.state.save()
  assert.equal(f.calls.at(-1).options.body.input.albumSize,1);assert.equal(f.state.items.value[0].rule.input.albumSize,1)
  assert.equal(f.state.items.value[0].rule.enabled,false);assert.equal(f.calls.some(call=>call.path.endsWith('/enabled')),false)
})
test('old upload rules default to ten while invalid explicit group sizes are rejected',async t=>{
  const f=mount(t);await flush();const legacy=result();delete legacy.rule.input.albumSize
  assert.equal(rules.validRule(legacy,'1'),true);f.state.edit(legacy);assert.equal(f.state.form.albumSize,10)
  for(const invalid of [0,11,2.5,'3']){const value=result();value.rule.input.albumSize=invalid;assert.equal(rules.validRule(value,'1'),false)}
})
test('observation activation never invokes upload preview or send',async t=>{const f=mount(t);await flush();const enabled=result();enabled.rule.enabled=true;enabled.rule.revision=2;enabled.state='OBSERVING';f.expect('/rules/'+ID+'/enabled',enabled);await f.state.act(result(),'enabled');assert.deepEqual(f.calls[1].options.body,{revision:1,enabled:true});assert.equal(f.state.items.value[0].rule.enabled,true);assert.equal(f.calls.length,2)})
test('manual observation is available while paused and uses expected revision',async t=>{const f=mount(t);await flush();f.expectPreparation(result(),'observe',result());await f.state.act(result(),'observe');assert.equal(f.calls[1].options.body.revision,1);assert.equal(f.calls[1].options.body.kind,'observe');assert.ok(f.calls[1].options.body.operationId);assert.equal(f.state.items.value[0].state,'PAUSED')})
test('unknown save response is not retried and is resolved by GET operation lookup',async t=>{const f=mount(t);await flush();f.state.edit(null);Object.assign(f.state.form,input());f.expect('/rules',new Error('timeout'));await f.state.save();assert.equal(f.state.uncertain.value,true);await f.state.save();assert.equal(f.calls.length,2);f.expect('/rules/by-operation/'+f.calls[1].options.body.operationId,result(),'GET');await f.state.lookup();assert.equal(f.state.uncertain.value,false);assert.equal(f.state.items.value[0].rule.id,ID);assert.equal(f.calls.filter(c=>c.options.method==='POST').length,1)})

test('upload form cannot close during save and closes only after its successful receipt',async t=>{const f=mount(t);await flush();f.state.edit(null);Object.assign(f.state.form,input());let release;f.expect('/rules',new Promise(r=>release=r));const pending=f.state.save();f.state.closeForm();assert.equal(f.state.formOpen.value,true);assert.equal(f.state.busy.value,'save');release(result());await pending;assert.equal(f.state.formOpen.value,false);assert.equal(f.state.busy.value,'')})

test('closing an upload form invalidates late automatic naming without saving',async t=>{const f=mount(t);await flush();f.state.edit(null);f.state.form.link=input().link;let release;f.expect('/rules/name',new Promise(r=>release=r));const pending=f.state.resolveName();f.state.closeForm();assert.equal(f.state.formOpen.value,false);assert.equal(f.state.nameBusy.value,false);f.state.edit(null);f.state.form.link=input().link;release({accountId:'1',link:input().link,name:'已取消表单的旧名字'});await pending;assert.equal(f.state.form.name,'');assert.equal(f.state.formOpen.value,true);assert.equal(f.calls.filter(c=>c.path==='/rules').length,1)})

test('unknown upload save can be closed and reopened while retaining the exact operation lookup',async t=>{const f=mount(t);await flush();f.state.edit(null);Object.assign(f.state.form,input());f.expect('/rules',new Error('timeout'));await f.state.save();const operation=f.calls[1].options.body.operationId;f.state.closeForm();assert.equal(f.state.formOpen.value,false);assert.equal(f.state.uncertain.value,true);f.state.edit(null);assert.equal(f.state.formOpen.value,false);f.state.reopenForm();assert.equal(f.state.formOpen.value,true);assert.equal(f.state.form.name,'sample');await f.state.save();assert.equal(f.calls.length,2);f.state.closeForm();f.expect('/rules/by-operation/'+operation,result(),'GET');await f.state.lookup();assert.equal(f.state.uncertain.value,false);assert.equal(f.state.formOpen.value,false);assert.equal(f.calls.filter(c=>c.options.method==='POST').length,1)})

test('an old edit revision does not clear uncertain save or permit another submission',async t=>{const f=mount(t);await flush();f.state.edit(result());f.state.form.name='edited';f.expect('/rules/'+ID,new Error('timeout'),'PUT');await f.state.save();f.state.closeForm();f.expect('/rules/'+ID,result(),'GET');await f.state.lookup();assert.equal(f.state.uncertain.value,true);assert.match(f.state.error.value,/尚未确认新版本/);await f.state.save();assert.equal(f.calls.length,3);const next=result();next.rule.version=2;next.rule.revision=2;next.rule.input.name='edited';f.expect('/rules/'+ID,next,'GET');await f.state.lookup();assert.equal(f.state.uncertain.value,false);assert.equal(f.state.items.value[0].rule.version,2)})

test('account changes discard a closed uncertain upload form and its pending operation',async t=>{const f=mount(t);await flush();f.state.edit(null);Object.assign(f.state.form,input());f.expect('/rules',new Error('timeout'));await f.state.save();f.state.closeForm();f.props.authorized=false;await flush();f.state.reopenForm();await f.state.lookup();assert.equal(f.state.formOpen.value,false);assert.equal(f.state.uncertain.value,false);assert.equal(f.state.form.name,'');assert.equal(f.calls.length,2)})

test('service polling and brief interruption preserve an upload draft and its automatic-name choice',async t=>{
  const f=mount(t);await flush();f.state.edit(null);Object.assign(f.state.form,input());f.state.nameAutomatic.value=false
  const draft=JSON.parse(JSON.stringify(f.state.form))
  f.expect('/rules',{mode:'OBSERVE_ONLY',items:[]},'GET');await f.state.refresh()
  assert.equal(f.state.formOpen.value,true)
  f.props.available=false;await flush();assert.equal(f.state.formOpen.value,true);assert.deepEqual(f.state.form,draft)
  await f.state.save();assert.equal(f.calls.length,2)
  f.expect('/rules',{mode:'OBSERVE_ONLY',items:[]},'GET');f.props.available=true;await flush()
  assert.equal(f.state.formOpen.value,true);assert.deepEqual(f.state.form,draft);assert.equal(f.state.nameAutomatic.value,false)
  assert.equal(f.calls.every(call=>(call.options.method??'GET')==='GET'),true)
})

test('in-flight upload save becomes uncertain on service interruption and keeps the same operation lookup',async t=>{
  const f=mount(t);await flush();f.state.edit(null);Object.assign(f.state.form,input())
  let release;f.expect('/rules',new Promise(resolve=>release=resolve));const saving=f.state.save()
  const operation=f.calls[1].options.body.operationId
  f.props.available=false;await flush();assert.equal(f.state.uncertain.value,true);assert.equal(f.state.formOpen.value,true)
  f.expect('/rules',{mode:'OBSERVE_ONLY',items:[]},'GET');f.props.available=true;await flush()
  release(result());await saving;await flush()
  assert.equal(f.state.formOpen.value,true);assert.equal(f.state.form.name,'sample');assert.equal(f.state.uncertain.value,true)
  await f.state.save();assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
  f.expect('/rules/by-operation/'+operation,result(),'GET');await f.state.lookup()
  assert.equal(f.state.uncertain.value,false);assert.equal(f.state.formOpen.value,false)
})

test('unknown upload edit and its frozen revision survive a service reconnect',async t=>{
  const f=mount(t);await flush();f.state.edit(result());f.state.form.name='edited'
  f.expect('/rules/'+ID,new Error('timeout'),'PUT');await f.state.save()
  f.props.available=false;await flush()
  f.expect('/rules',{mode:'OBSERVE_ONLY',items:[]},'GET');f.props.available=true;await flush()
  assert.equal(f.state.formOpen.value,true);assert.equal(f.state.form.name,'edited');assert.equal(f.state.editing.value.rule.revision,1)
  assert.equal(f.state.uncertain.value,true);await f.state.save();assert.equal(f.calls.filter(call=>call.options.method==='PUT').length,1)
})
test('late observation cannot update an account that changed while reading',async t=>{const f=mount(t);await flush();let release;f.expectPreparation(result(),'observe',new Promise(r=>release=r));const pending=f.state.act(result(),'observe');f.props.authorized=false;await flush();release(result());await pending;assert.equal(f.state.items.value.length,0);assert.equal(f.state.busy.value,'')})
test('rule grouping preview is read only and no action can send a transfer',async t=>{const f=mount(t);await flush();const preview={id:ID,accountId:'1',state:'PREVIEW_ONLY',permission:'MEDIA_ALLOWED_AT_CHECK',sourceDirectory:'D:\\source',archiveDirectory:'D:\\archive',channelTitle:'sample',postTitle:'sample',postText:'',discussionTitle:'discussion',postLink:input().link,recursive:true,captionMode:'FILENAME',albumSize:10,fileCount:0,totalBytes:0,otherFiles:0,deferredFiles:0,linkedEntries:0,duplicateFiles:0,slowModeSeconds:0,targetCheckedAt:1,createdAt:1,expiresAt:600001,groups:[],warnings:[]};f.expectPreparation(result(),'preview',{ruleId:ID,version:1,revision:1,preview});await f.state.act(result(),'preview');assert.equal(f.state.preview.value.preview.state,'PREVIEW_ONLY');assert.equal(f.calls.some(c=>c.path.includes('/transfers/')),false)})

test('archive is a sibling named after source with suffix, including trailing slash and normalized parent',()=>{
  assert.equal(rules.ruleArchiveDirectory('D:\\素材\\'), 'D:\\素材-已上传');assert.equal(rules.ruleArchiveDirectory('D:/a/../素材/'),'D:\\素材-已上传');assert.equal(rules.ruleArchiveDirectory('D:\\'), '');assert.equal(rules.ruleArchiveDirectory('\\\\server\\share\\x'),'')
})
test('new rule auto-fills name from the entered post without saving or indexing a rule',async t=>{const f=mount(t);await flush();f.state.edit(null);f.state.form.link=input().link;f.expect('/rules/name',{accountId:'1',link:input().link,name:'主帖名称'});await f.state.resolveName();assert.equal(f.state.form.name,'主帖名称');assert.equal(f.state.items.value.length,0);assert.equal(f.calls.filter(c=>c.path==='/rules').length,1)})
test('manual name edits survive a late automatic name response',async t=>{const f=mount(t);await flush();f.state.edit(null);f.state.form.link=input().link;let release;f.expect('/rules/name',new Promise(r=>release=r));const pending=f.state.resolveName();f.state.form.name='我的名字';f.state.nameAutomatic.value=false;release({accountId:'1',link:input().link,name:'主帖名称'});await pending;assert.equal(f.state.form.name,'我的名字')})
test('changing target link discards the earlier name response',async t=>{const f=mount(t);await flush();f.state.edit(null);f.state.form.link=input().link;let release;f.expect('/rules/name',new Promise(r=>release=r));const pending=f.state.resolveName();f.state.form.link='https://t.me/synthetic/2';release({accountId:'1',link:input().link,name:'旧主帖'});await pending;assert.equal(f.state.form.name,'');assert.equal(f.state.nameBusy.value,false)})
test('archive follows source changes and old saved rule stays frozen until save',async t=>{const f=mount(t);await flush();const old=result();f.state.items.value=[old];f.state.edit(f.state.items.value[0]);f.state.form.sourceDirectory='D:\\新素材\\';assert.equal(f.state.form.archiveDirectory,'D:\\新素材-已上传');assert.equal(f.state.items.value[0].rule.input.archiveDirectory,old.rule.input.archiveDirectory)})
test('failed name lookup preserves manual fallback and is not repeated by blur',async t=>{const f=mount(t);await flush();f.state.edit(null);f.state.form.link=input().link;f.expect('/rules/name',new Error('timeout'));await f.state.resolveName();await f.state.resolveName();assert.equal(f.calls.length,2);assert.match(f.state.nameError.value,/手动填写/);f.state.form.name='手动名称';f.state.nameAutomatic.value=false;await f.state.resolveName();assert.equal(f.state.form.name,'手动名称')})

const automatic=()=>{const r=result();r.mode='AUTO_UPLOAD';r.rule.input.executionMode='AUTO_UPLOAD';return r}
const activationPreview=()=>({ruleId:ID,version:1,revision:1,preview:{id:ID,accountId:'1',state:'PREVIEW_ONLY',permission:'MEDIA_ALLOWED_AT_CHECK',sourceDirectory:'D:\\source',archiveDirectory:'D:\\archive',channelTitle:'sample',postTitle:'sample',postText:'',discussionTitle:'discussion',postLink:input().link,recursive:true,captionMode:'FILENAME',albumSize:10,fileCount:0,totalBytes:0,otherFiles:0,deferredFiles:0,linkedEntries:0,duplicateFiles:0,slowModeSeconds:0,targetCheckedAt:Date.now(),createdAt:Date.now(),expiresAt:Date.now()+600000,groups:[],warnings:[]}})
test('new rules default to automatic upload but saving still requires separate preview and enable',async t=>{const f=mount(t);await flush();f.state.edit(null);assert.equal(f.state.form.executionMode,'AUTO_UPLOAD');Object.assign(f.state.form,automatic().rule.input);f.expect('/rules',automatic());await f.state.save();assert.equal(f.calls[1].options.body.input.executionMode,'AUTO_UPLOAD');assert.equal(f.state.items.value[0].rule.enabled,false);assert.match(f.state.notice.value,/暂停/);assert.equal(f.calls.length,2)})
test('editing a legacy rule without a mode field keeps observation rather than inheriting the new default',async t=>{const f=mount(t);await flush();const old=result();delete old.rule.input.executionMode;const before=JSON.stringify(old);f.state.edit(old);assert.equal(f.state.form.executionMode,'OBSERVE_ONLY');assert.equal(JSON.stringify(old),before);assert.equal(f.calls.length,1)})
test('a saved mode mismatch remains locked until GET confirms the requested mode, without resubmitting',async t=>{const f=mount(t);await flush();f.state.edit(null);Object.assign(f.state.form,automatic().rule.input);f.expect('/rules',result());await f.state.save();assert.equal(f.state.uncertain.value,true);const operation=f.calls[1].options.body.operationId;f.expect('/rules/by-operation/'+operation,result(),'GET');await f.state.lookup();assert.equal(f.state.uncertain.value,true);f.expect('/rules/by-operation/'+operation,automatic(),'GET');await f.state.lookup();assert.equal(f.state.uncertain.value,false);assert.equal(f.calls.filter(c=>c.options.method==='POST').length,1)})
test('enable automatic first prepares a readonly bound preview and requires explicit confirmation',async t=>{const f=mount(t);await flush();f.expectPreparation(automatic(),'preview',activationPreview());await f.state.act(automatic(),'enabled');assert.ok(f.state.activation.value);assert.equal(f.calls.length,2);assert.equal(f.calls.some(c=>c.path.endsWith('/enabled')),false);const enabled=automatic();enabled.rule.enabled=true;enabled.rule.revision=2;enabled.state='OBSERVING';f.expect('/rules/'+ID+'/enabled',enabled);await f.state.confirmActivation();assert.deepEqual(f.calls[2].options.body,{revision:1,enabled:true,previewId:ID});assert.equal(f.state.activation.value,null);assert.equal(f.state.items.value[0].rule.enabled,true);assert.equal(f.calls.some(c=>c.path.includes('/transfers/')),false)})
test('expired automatic activation cannot enable and asks for another preview',async t=>{const f=mount(t);await flush();const p=activationPreview();f.expectPreparation(automatic(),'preview',p);await f.state.prepareActivation(automatic());f.state.activation.value.preview.preview.expiresAt=1;await f.state.confirmActivation();assert.equal(f.calls.length,2);assert.equal(f.state.activation.value,null);assert.match(f.state.error.value,/过期/)})
test('unknown automatic activation response is closed and never automatically posted twice',async t=>{const f=mount(t);await flush();f.expectPreparation(automatic(),'preview',activationPreview());await f.state.prepareActivation(automatic());f.expect('/rules/'+ID+'/enabled',new Error('timeout'));await f.state.confirmActivation();await f.state.confirmActivation();assert.equal(f.calls.length,3);assert.equal(f.state.activation.value,null);assert.match(f.state.error.value,/刷新规则核对/)})
test('changing account discards a late activation preview and cannot enable it',async t=>{const f=mount(t);await flush();let resolve;f.expectPreparation(automatic(),'preview',new Promise(r=>resolve=r));const pending=f.state.prepareActivation(automatic());f.props.authorized=false;await flush();resolve(activationPreview());await pending;assert.equal(f.state.activation.value,null);await f.state.confirmActivation();assert.equal(f.calls.length,2)})
test('an automatic rule can be paused while scanning without an enable preview',async t=>{const f=mount(t);await flush();const current=automatic();current.rule.enabled=true;current.scanning=true;f.expect('/rules/'+ID+'/enabled',automatic());await f.state.act(current,'enabled');assert.deepEqual(f.calls[1].options.body,{revision:1,enabled:false});assert.equal(f.calls.length,2)})
test('automatic result must agree with saved mode and editing old observation clears previous automatic mode',async t=>{const a=automatic();assert.equal(rules.validRule(a,'1'),true);a.rule.input.executionMode='OBSERVE_ONLY';assert.equal(rules.validRule(a,'1'),false);const f=mount(t);await flush();f.state.edit(automatic());assert.equal(f.state.form.executionMode,'AUTO_UPLOAD');f.state.edit(result());assert.equal(f.state.form.executionMode,'OBSERVE_ONLY')})

test('embedded upload-rule creation opens the existing form without reading management lists',async t=>{
  const f=mount(t,{createOnly:true,embedded:true,category:'download',refreshVersion:0});await flush()
  assert.equal(f.state.formOpen.value,true);assert.equal(f.state.editing.value,null)
  assert.equal(f.state.selectedCategory.value,'upload');assert.equal(f.state.form.executionMode,'AUTO_UPLOAD')
  await f.state.refresh();f.props.refreshVersion++;f.props.category='upload';await flush()
  assert.equal(f.calls.length,0);assert.deepEqual(f.events,[['locked',false]])
  f.state.closeForm();assert.equal(f.state.formOpen.value,false);assert.deepEqual(f.events.at(-1),['close'])
})
test('embedded upload-rule save emits completion only after its paused receipt and unlock',async t=>{
  const f=mount(t,{createOnly:true,embedded:true});await flush();Object.assign(f.state.form,automatic().rule.input)
  let release;f.expect('/rules',new Promise(resolve=>release=resolve));const saving=f.state.save()
  assert.equal(f.state.busy.value,'save');assert.deepEqual(f.events.at(-1),['locked',true])
  f.state.closeForm();assert.equal(f.state.formOpen.value,true);assert.equal(f.events.some(event=>event[0]==='close'),false)
  release(automatic());await saving
  assert.equal(f.state.busy.value,'');assert.equal(f.state.formOpen.value,false)
  assert.equal(f.state.items.value[0].rule.enabled,false)
  assert.deepEqual(f.events.slice(-2),[['locked',false],['saved','upload']])
  assert.equal(f.calls.length,1);assert.equal(f.calls[0].options.method,'POST')
  assert.equal(f.calls[0].options.body.input.executionMode,'AUTO_UPLOAD')
  assert.equal(f.calls.some(call=>call.path.includes('/enabled')||call.path.includes('/transfers/')),false)
})
test('unknown embedded upload-rule save stays open and locked until the original operation GET confirms it',async t=>{
  const f=mount(t,{createOnly:true,embedded:true});await flush();Object.assign(f.state.form,automatic().rule.input)
  f.expect('/rules',new Error('timeout'));await f.state.save()
  const operation=f.calls[0].options.body.operationId,draft=JSON.parse(JSON.stringify(f.state.form))
  assert.equal(f.state.uncertain.value,true);assert.deepEqual(f.events.at(-1),['locked',true])
  f.state.closeForm();f.state.edit(null);await f.state.save()
  assert.equal(f.state.formOpen.value,true);assert.deepEqual(f.state.form,draft)
  assert.equal(f.calls.length,1);assert.equal(f.events.some(event=>event[0]==='close'||event[0]==='saved'),false)
  f.props.available=false;await flush();f.props.available=true;await flush()
  assert.equal(f.calls.length,1);assert.equal(f.state.uncertain.value,true);assert.deepEqual(f.state.form,draft)
  f.expect('/rules/by-operation/'+operation,automatic(),'GET');await f.state.lookup()
  assert.equal(f.state.uncertain.value,false);assert.equal(f.state.items.value[0].rule.enabled,false)
  assert.deepEqual(f.events.slice(-2),[['locked',false],['saved','upload']])
  assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
})
test('an embedded upload-rule mode mismatch cannot emit saved or close the unresolved form',async t=>{
  const f=mount(t,{createOnly:true,embedded:true});await flush();Object.assign(f.state.form,automatic().rule.input)
  f.expect('/rules',result());await f.state.save()
  const operation=f.calls[0].options.body.operationId
  assert.equal(f.state.uncertain.value,true);f.state.closeForm();assert.equal(f.state.formOpen.value,true)
  f.expect('/rules/by-operation/'+operation,result(),'GET');await f.state.lookup()
  assert.equal(f.state.uncertain.value,true);assert.equal(f.events.some(event=>event[0]==='saved'),false)
  f.expect('/rules/by-operation/'+operation,automatic(),'GET');await f.state.lookup()
  assert.deepEqual(f.events.at(-1),['saved','upload']);assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
})
test('management category and refresh presets select saved uploads through readonly reads',async t=>{
  const f=mount(t,{category:'upload',refreshVersion:0});await flush()
  assert.equal(f.state.selectedCategory.value,'upload');assert.equal(f.events.length,0)
  f.props.category='download';await flush();assert.equal(f.state.selectedCategory.value,'download')
  f.expect('/rules',{mode:'RULES',items:[automatic()]},'GET');f.props.refreshVersion++;await flush()
  assert.equal(f.state.items.value.length,1);assert.equal(f.calls.length,2)
  assert.equal(f.calls.every(call=>(call.options.method??'GET')==='GET'),true)
  f.props.category='upload';await flush();f.state.selectCategory('download');await flush();assert.equal(f.state.selectedCategory.value,'download')
  f.expect('/rules',{mode:'RULES',items:[automatic()]},'GET');f.props.refreshVersion++;await flush();assert.equal(f.state.selectedCategory.value,'upload')
})

const lookupPath=request=>'/rules/'+request.ruleId+'/preparations/by-operation/'+request.operationId
const deferred=()=>{let resolve;const promise=new Promise(done=>{resolve=done});return{promise,resolve}}
function nodes(node){if(!node||typeof node!=='object')return[];if(Array.isArray(node))return node.flatMap(nodes);const children=Array.isArray(node.children)?node.children:node.children&&typeof node.children==='object'?Object.values(node.children).filter(value=>typeof value==='function').flatMap(slot=>slot()):[];return[node,...children.flatMap(nodes)]}
const text=node=>typeof node==='string'?node:Array.isArray(node)?node.map(text).join(''):node&&typeof node==='object'?text(node.children):''
const beginRunning=(f,rule=result(),kind='observe')=>f.expect('/rules/'+ID+'/preparations',()=>f.receipt(f.state.preparationRequest.value))

test('another enabled rule can be paused during or after waiting for a preparation without replacing its operation',async t=>{
  for(const stopWaiting of [false,true]){
    const f=mount(t);await flush()
    const a=automatic(),b=automatic();b.rule.id='00000000-0000-0000-0000-000000000002';b.rule.input.name='Other upload';b.rule.enabled=true;b.state='OBSERVING'
    f.state.items.value=[a,b];beginRunning(f,a,'preview');await f.state.prepareActivation(a)
    const request={...f.state.preparationRequest.value}
    if(stopWaiting)f.state.pausePreparation()
    const record=nodes(f.render()).find(node=>node.type?.__name==='CompactRecord'&&node.key===b.rule.id)
    const pause=nodes(record).find(node=>node.type==='button'&&text(node)==='暂停自动上传')
    assert.ok(pause);assert.equal(pause.props.disabled,false)
    const response=deferred(),paused=structuredClone(b);paused.rule.enabled=false;paused.rule.revision=2;paused.state='PAUSED'
    f.expect('/rules/'+b.rule.id+'/enabled',response.promise)
    const pending=pause.props.onClick();await f.state.act(b,'enabled')
    assert.equal(f.calls.length,3,'repeated pause cannot overlap the pending pause')
    assert.deepEqual(f.calls[2].options.body,{revision:1,enabled:false})
    assert.equal(f.state.preparationRequest.value.operationId,request.operationId)
    assert.equal(f.state.preparationWaiting.value,!stopWaiting)
    response.resolve(paused);await pending
    assert.equal(f.state.items.value.find(item=>item.rule.id===b.rule.id).rule.enabled,false)
    f.expect(lookupPath(request),f.receipt(request,'COMPLETED',activationPreview()),'GET')
    if(stopWaiting){f.state.resumePreparation();await flush()}else await f.advance(2000)
    assert.ok(f.state.activation.value,'the original preparation can still complete after pausing the unrelated rule')
    assert.equal(f.state.preparationRequest.value,null)
    assert.equal(f.calls.filter(call=>call.path.endsWith('/preparations')&&call.options.method==='POST').length,1)
    assert.equal(f.calls.some(call=>call.path==='/rules/'+ID+'/enabled'||call.path.includes('/transfers/')),false)
  }
})

test('a preparation still blocks changing its own rule or enabling another paused rule',async t=>{
  const f=mount(t);await flush();const a=automatic(),b=automatic();a.rule.enabled=true;b.rule.id='00000000-0000-0000-0000-000000000002'
  f.state.items.value=[a,b];beginRunning(f);await f.state.act(a,'observe');f.state.pausePreparation()
  await f.state.act(a,'enabled');await f.state.act(b,'enabled');await f.state.act(b,'observe');f.state.edit(b)
  assert.equal(f.calls.length,2);assert.equal(f.state.formOpen.value,false);assert.ok(f.state.preparationRequest.value)
})

test('completion of the original preparation does not discard a pending independent pause or implicitly enable it',async t=>{
  const f=mount(t);await flush();const a=automatic(),b=automatic();b.rule.id='00000000-0000-0000-0000-000000000002';b.rule.enabled=true;b.state='OBSERVING'
  f.state.items.value=[a,b];beginRunning(f,a,'preview');await f.state.prepareActivation(a);const request={...f.state.preparationRequest.value}
  const response=deferred(),paused=structuredClone(b);paused.rule.enabled=false;paused.rule.revision=2;paused.state='PAUSED'
  f.expect('/rules/'+b.rule.id+'/enabled',response.promise);const pausing=f.state.act(b,'enabled')
  f.expect(lookupPath(request),f.receipt(request,'COMPLETED',activationPreview()),'GET');await f.advance(2000)
  assert.ok(f.state.activation.value);assert.equal(f.state.preparationRequest.value,null)
  await f.state.confirmActivation();assert.equal(f.calls.length,4,'activation waits for the explicit pause response')
  response.resolve(paused);await pausing
  assert.equal(f.state.items.value.find(item=>item.rule.id===b.rule.id).rule.enabled,false)
  assert.equal(f.state.pausingRule.value,'');assert.equal(f.calls.filter(call=>call.path.endsWith('/enabled')).length,1)
})

test('a late list read cannot undo an independent pause and failed pause evidence survives preparation polling',async t=>{
  const f=mount(t);await flush();const a=automatic(),b=automatic();b.rule.id='00000000-0000-0000-0000-000000000002';b.rule.enabled=true;b.state='OBSERVING'
  f.state.items.value=[a,b];beginRunning(f);await f.state.act(a,'observe');const request={...f.state.preparationRequest.value};f.state.pausePreparation()
  const stale=deferred();f.expect('/rules',stale.promise,'GET');const listing=f.state.refresh()
  const paused=structuredClone(b);paused.rule.enabled=false;paused.rule.revision=2;paused.state='PAUSED'
  f.expect('/rules/'+b.rule.id+'/enabled',paused);await f.state.act(b,'enabled')
  stale.resolve({mode:'RULES',items:[a,b]});await listing
  assert.equal(f.state.items.value.find(item=>item.rule.id===b.rule.id).rule.enabled,false)
  const other=structuredClone(b);other.rule.id='00000000-0000-0000-0000-000000000003';f.state.items.value.push(other)
  f.expect('/rules/'+other.rule.id+'/enabled',new Error('lost pause response'));await f.state.act(other,'enabled')
  f.expect(lookupPath(request),f.receipt(request),'GET');f.state.resumePreparation();await flush()
  assert.match(f.state.ruleAlert(other),/lost pause response.*不会自动重试/)
  assert.equal(f.state.preparationRequest.value.operationId,request.operationId);assert.equal(f.calls.filter(call=>call.path=== '/rules/'+other.rule.id+'/enabled').length,1)
})

test('real Vue preparation state accepts the initial plain request and polls serial GETs without refreshing the list',async t=>{
  const f=mount(t);await flush();f.state.items.value=[result()];const original=f.state.items.value
  beginRunning(f);await f.state.act(f.state.items.value[0],'observe')
  const request={...f.state.preparationRequest.value};assert.ok(request.operationId);assert.equal(f.state.preparation.value.state,'RUNNING');assert.equal(f.state.preparationWaiting.value,true)
  assert.deepEqual(f.calls[1].options.body,{operationId:request.operationId,revision:1,kind:'observe'});assert.equal(f.state.items.value,original)
  assert.match(text(f.render()),/正在校验目录与文件.*已用 0:00/s);assert.equal(text(f.render()).includes('%'),false)
  await f.advance(1999);assert.equal(f.calls.length,2)
  const response=deferred();f.expect(lookupPath(request),response.promise,'GET');await f.advance(1)
  await f.advance(5000);assert.equal(f.calls.length,3,'a pending GET never overlaps another poll')
  response.resolve(f.receipt(request));await flush();assert.equal(f.state.items.value,original);assert.equal(f.state.preparationTime.value,'0:07')
  f.expect(lookupPath(request),f.receipt(request,'COMPLETED',result()),'GET');await f.advance(2000)
  assert.equal(f.state.preparationRequest.value,null);assert.equal(f.state.busy.value,'');assert.equal(f.timers.size,0);assert.equal(f.maxRequests,1)
  assert.match(f.state.notice.value,/检测已完成/);assert.equal(f.calls.filter(call=>call.path==='/rules').length,1)
  assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
})

test('automatic activation cannot be confirmed until its background preview completes and then still needs the original explicit enable POST',async t=>{
  const f=mount(t);await flush();beginRunning(f,automatic(),'preview');await f.state.prepareActivation(automatic())
  const request={...f.state.preparationRequest.value};assert.equal(request.kind,'preview');assert.equal(f.state.activation.value,null)
  await f.state.confirmActivation();assert.equal(f.calls.length,2)
  f.expect(lookupPath(request),f.receipt(request,'COMPLETED',activationPreview()),'GET');await f.advance(2000)
  assert.ok(f.state.activation.value);assert.equal(f.state.preparationRequest.value,null);assert.equal(f.calls.some(call=>call.path.endsWith('/enabled')),false)
  const enabled=automatic();enabled.rule.enabled=true;enabled.rule.revision=2;enabled.state='OBSERVING'
  f.expect('/rules/'+ID+'/enabled',enabled);await f.state.confirmActivation()
  assert.deepEqual(f.calls[3].options.body,{revision:1,enabled:true,previewId:ID});assert.equal(f.calls.some(call=>call.path.includes('/transfers/')),false)
})

test('stopping frontend waiting retains the original preparation, discards its pending read and resumes only GET observation',async t=>{
  const f=mount(t);await flush();beginRunning(f);await f.state.act(result(),'observe');const request={...f.state.preparationRequest.value}
  const response=deferred();f.expect(lookupPath(request),response.promise,'GET');await f.advance(2000)
  f.state.pausePreparation();assert.match(f.state.notice.value,/后台不会因此取消/);assert.equal(f.timers.size,0);assert.equal(f.state.busy.value,'')
  response.resolve(f.receipt(request,'COMPLETED',result()));await flush()
  assert.equal(f.state.preparationRequest.value.operationId,request.operationId);assert.equal(f.state.preparation.value.state,'RUNNING')
  await f.advance(30000);assert.equal(f.calls.length,3)
  f.expect(lookupPath(request),f.receipt(request),'GET');f.state.resumePreparation();await flush();assert.equal(f.state.preparationWaiting.value,true)
  f.expect(lookupPath(request),f.receipt(request,'COMPLETED',result()),'GET');await f.advance(2000)
  assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1);assert.equal(f.state.preparationRequest.value,null)
})

test('an unknown preparation POST stays frozen and can only be recovered through its original operation GET',async t=>{
  const f=mount(t);await flush();f.expect('/rules/'+ID+'/preparations',new Error('lost preparation response'));await f.state.act(result(),'observe')
  const request={...f.state.preparationRequest.value};assert.match(f.state.error.value,/核对原操作/);assert.equal(f.timers.size,0)
  await f.state.act(result(),'observe');f.state.edit(result());assert.equal(f.calls.length,2);assert.equal(f.state.formOpen.value,false)
  f.expect(lookupPath(request),null,'GET');f.state.resumePreparation();await flush();assert.equal(f.state.preparationRequest.value.operationId,request.operationId)
  assert.match(f.state.error.value,/尚未查到/);assert.equal(f.timers.size,0)
  f.expect(lookupPath(request),f.receipt(request,'COMPLETED',result()),'GET');f.state.resumePreparation();await flush()
  assert.equal(f.state.preparationRequest.value,null);assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
})

test('failed preparation, malformed completion and a rejected start never enable or create uploads',async t=>{
  for(const mode of ['failed','malformed','rejected']){
    const f=mount(t);await flush()
    if(mode==='rejected')f.expect('/rules/'+ID+'/preparations',new ApiError('规则已变化',400))
    else f.expect('/rules/'+ID+'/preparations',()=>f.receipt(f.state.preparationRequest.value,mode==='failed'?'FAILED':'COMPLETED',mode==='failed'?'文件已变化':{partial:true}))
    await f.state.prepareActivation(automatic());assert.equal(f.state.activation.value,null);assert.ok(f.state.error.value);assert.equal(f.timers.size,0)
    if(mode==='malformed')assert.ok(f.state.preparationRequest.value)
    else assert.equal(f.state.preparationRequest.value,null)
    assert.equal(f.calls.some(call=>call.path.endsWith('/enabled')||call.path.includes('/transfers/')),false)
  }
})

test('a connection interruption retains the pending preparation operation, while a real account change or disposal drops late results',async t=>{
  const f=mount(t);await flush();beginRunning(f);await f.state.act(result(),'observe');const request={...f.state.preparationRequest.value}
  f.props.available=false;await flush();assert.equal(f.timers.size,0);assert.equal(f.state.preparationRequest.value.operationId,request.operationId)
  f.expect('/rules',{mode:'RULES',items:[result()]},'GET');f.props.available=true;await flush()
  assert.equal(f.state.preparationRequest.value.operationId,request.operationId);assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
  const response=deferred();f.expect(lookupPath(request),response.promise,'GET');f.state.resumePreparation();await flush();f.props.authorized=false;await flush()
  response.resolve(f.receipt(request,'COMPLETED',result()));await flush();assert.equal(f.state.preparationRequest.value,null);assert.equal(f.state.items.value.length,0)
  const second=mount(t);await flush();beginRunning(second);await second.state.prepareActivation(automatic());const other={...second.state.preparationRequest.value}
  const late=deferred();second.expect(lookupPath(other),late.promise,'GET');await second.advance(2000);second.destroy();late.resolve(second.receipt(other,'COMPLETED',activationPreview()));await flush()
  assert.equal(second.state.activation.value,null);assert.equal(second.timers.size,0)
})

test('a changed rule version invalidates the old preparation and ignores its late completed preview',async t=>{
  const f=mount(t);await flush();beginRunning(f);await f.state.prepareActivation(automatic());const request={...f.state.preparationRequest.value}
  const response=deferred();f.expect(lookupPath(request),response.promise,'GET');await f.advance(2000);f.state.pausePreparation()
  const changed=automatic();changed.rule.version=2;changed.rule.revision=2
  f.expect('/rules',{mode:'RULES',items:[changed]},'GET');await f.state.refresh();assert.equal(f.state.preparationRequest.value,null)
  response.resolve(f.receipt(request,'COMPLETED',activationPreview()));await flush()
  assert.equal(f.state.activation.value,null);assert.equal(f.state.items.value[0].rule.version,2);assert.match(f.state.error.value,/规则已变化/)
  assert.equal(f.timers.size,0);assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
})

test('an invalidated preparation GET clears its context while a temporary GET failure keeps the original lookup operation',async t=>{
  for(const status of [400,503]){
    const f=mount(t);await flush();beginRunning(f);await f.state.act(result(),'observe');const request={...f.state.preparationRequest.value}
    f.expect(lookupPath(request),new ApiError(status===400?'规则版本已失效':'服务暂不可用',status),'GET');await f.advance(2000)
    assert.equal(f.timers.size,0);assert.equal(f.state.busy.value,'');assert.match(f.state.error.value,/不会自动重新准备/)
    if(status===400)assert.equal(f.state.preparationRequest.value,null)
    else{
      assert.equal(f.state.preparationRequest.value.operationId,request.operationId);await f.state.act(result(),'observe');assert.equal(f.calls.length,3)
      f.expect(lookupPath(request),f.receipt(request,'COMPLETED',result()),'GET');f.state.resumePreparation();await flush()
      assert.equal(f.state.preparationRequest.value,null);assert.equal(f.calls[3].path,lookupPath(request))
    }
    assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
  }
})

test('completed byte-bounded preparation displays all 66 videos in ten confirmation groups without enabling or sending',async t=>{
  const f=mount(t);await flush();beginRunning(f,automatic(),'preview');await f.state.prepareActivation(automatic())
  const request={...f.state.preparationRequest.value},completed=activationPreview()
  completed.preview.groups=byteSplitGroups();completed.preview.fileCount=66;completed.preview.totalBytes=completed.preview.groups.flatMap(group=>group.files).reduce((sum,file)=>sum+file.size,0)
  assert.equal(upload.validUploadPreview(completed.preview,'1'),true)
  f.expect(lookupPath(request),f.receipt(request,'COMPLETED',completed),'GET');await f.advance(2000)
  assert.ok(f.state.activation.value);assert.deepEqual(f.state.activation.value.preview.preview.groups.map(group=>group.files.length),byteSplitCounts)
  assert.match(text(f.render()),/当前预览 66 个媒体、10 组/);assert.match(text(f.render()),/确认启用自动上传/)
  assert.equal(f.state.preparationRequest.value,null);assert.equal(f.timers.size,0)
  assert.equal(f.calls.some(call=>call.path.endsWith('/enabled')||call.path.includes('/transfers/')),false)
})

test('leaving the rule page pauses preparation polling and returns to the original read-only operation',async t=>{
  const f=mount(t);await flush();beginRunning(f);await f.state.act(result(),'observe')
  const request={...f.state.preparationRequest.value};assert.equal(f.timers.size,1)
  f.props.active=false;await flush();assert.equal(f.timers.size,0)
  await f.advance(6000);assert.equal(f.calls.length,2);assert.equal(f.state.preparationRequest.value.operationId,request.operationId)
  f.expect(lookupPath(request),f.receipt(request,'COMPLETED',result()),'GET');f.props.active=true;await flush()
  assert.equal(f.state.preparationRequest.value,null);assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
})

test('a preparation receipt arriving while hidden is retained without starting hidden polling',async t=>{
  const f=mount(t);await flush();let release
  f.expect('/rules/'+ID+'/preparations',new Promise(resolve=>release=resolve));const starting=f.state.act(result(),'observe')
  const request={...f.state.preparationRequest.value};f.props.active=false;await flush()
  release(f.receipt(request));await starting;assert.equal(f.state.preparationRequest.value.operationId,request.operationId);assert.equal(f.timers.size,0)
  f.expect(lookupPath(request),f.receipt(request,'COMPLETED',result()),'GET');f.props.active=true;await flush()
  assert.equal(f.state.preparationRequest.value,null);assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
})

test('page visibility preserves an uncertain rule save and its exact frozen operation',async t=>{
  const f=mount(t);await flush();f.state.edit(null);Object.assign(f.state.form,input())
  f.expect('/rules',new Error('timeout'));await f.state.save();const operation=f.calls[1].options.body.operationId
  f.props.active=false;await flush();assert.equal(f.state.uncertain.value,true);assert.equal(f.state.formOpen.value,true)
  f.props.active=true;await flush();assert.equal(f.calls.length,2);assert.equal(f.state.form.name,'sample')
  f.expect('/rules/by-operation/'+operation,result(),'GET');await f.state.lookup()
  assert.equal(f.state.uncertain.value,false);assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
})

test('manually stopped preparation is not silently resumed after leaving and returning',async t=>{
  const f=mount(t);await flush();beginRunning(f);await f.state.act(result(),'observe');f.state.pausePreparation()
  const request={...f.state.preparationRequest.value};f.props.active=false;await flush()
  f.expect('/rules',{mode:'RULES',items:[result()]},'GET');f.props.active=true;await flush();await f.advance(4000)
  assert.equal(f.state.preparationWaiting.value,false);assert.equal(f.state.preparationRequest.value.operationId,request.operationId);assert.equal(f.timers.size,0)
  f.expect(lookupPath(request),f.receipt(request,'COMPLETED',result()),'GET');f.state.resumePreparation();await flush()
  assert.equal(f.state.preparationRequest.value,null);assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
})
