import * as uploadTasks from '../src/uploadTasks.ts'
import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import {parse,compileScript} from 'vue/compiler-sfc'
import ts from 'typescript'
import * as rules from '../src/rules.ts'
import * as upload from '../src/uploadPreview.ts'
import * as library from '../src/library.ts'
const ID='00000000-0000-0000-0000-000000000001'
const input=()=>({name:'sample',link:'https://t.me/synthetic/1',sourceDirectory:'D:\\source',archiveDirectory:'D:\\archive',recursive:true,captionMode:'FILENAME',window:{mode:'ALL_DAY',start:null,end:null,timezone:'Asia/Shanghai'},executionMode:'OBSERVE_ONLY'})
const result=()=>({mode:'OBSERVE_ONLY',state:'PAUSED',scanning:false,nextAllowedAt:1,rule:{id:ID,accountId:'1',version:1,revision:1,enabled:false,nextScanAt:0,createdAt:1,updatedAt:1,input:input(),observation:null}})
const flush=async()=>{await vue.nextTick();await new Promise(setImmediate)}
function mount(t,initialProps={}){
  const {descriptor}=parse(readFileSync(new URL('../src/components/RuleWorkspace.vue',import.meta.url),'utf8'),{filename:'RuleWorkspace.vue'})
  const code=ts.transpileModule(compileScript(descriptor,{id:'rules-test'}).content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
  const props=vue.reactive({authorized:true,available:true,accountId:'1',...initialProps}),calls=[],steps=props.createOnly?[]:[{path:'/rules',method:'GET',value:{mode:'OBSERVE_ONLY',items:[]}}],errors=[],hooks=[],mounted=[],events=[]
  const api=async(path,options={})=>{calls.push({path,options});const step=steps.shift();try{assert.ok(step,path);assert.equal(path,step.path);assert.equal(options.method??'GET',step.method??'POST')}catch(e){errors.push(e);throw e}if(step.value instanceof Error)throw step.value;return await step.value}
  const modules={vue:{...vue,onMounted:f=>mounted.push(f),onBeforeUnmount:f=>hooks.push(f)},'../api':{api,ApiError:class extends Error{}},'../uploadTasks':uploadTasks,'../rules':rules,'../uploadPreview':upload,'../library':library},module={exports:{}}
  new Function('require','module','exports',code)(name=>modules[name],module,module.exports)
  const scope=vue.effectScope(),state=scope.run(()=>module.exports.default.setup(props,{expose(){},emit:(...event)=>events.push(event)}))
  mounted.forEach(f=>f())
  t.after(()=>{hooks.forEach(f=>f());scope.stop();assert.deepEqual(errors,[]);assert.equal(steps.length,0)})
  return {state,props,calls,events,expect:(path,value,method='POST')=>steps.push({path,value,method})}
}
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
test('observation activation never invokes upload preview or send',async t=>{const f=mount(t);await flush();const enabled=result();enabled.rule.enabled=true;enabled.rule.revision=2;enabled.state='OBSERVING';f.expect('/rules/'+ID+'/enabled',enabled);await f.state.act(result(),'enabled');assert.deepEqual(f.calls[1].options.body,{revision:1,enabled:true});assert.equal(f.state.items.value[0].rule.enabled,true);assert.equal(f.calls.length,2)})
test('manual observation is available while paused and uses expected revision',async t=>{const f=mount(t);await flush();f.expect('/rules/'+ID+'/observe',result());await f.state.act(result(),'observe');assert.deepEqual(f.calls[1].options.body,{revision:1});assert.equal(f.state.items.value[0].state,'PAUSED')})
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
test('late observation cannot update an account that changed while reading',async t=>{const f=mount(t);await flush();let release;f.expect('/rules/'+ID+'/observe',new Promise(r=>release=r));const pending=f.state.act(result(),'observe');f.props.authorized=false;await flush();release(result());await pending;assert.equal(f.state.items.value.length,0);assert.equal(f.state.busy.value,'')})
test('rule grouping preview is read only and no action can send a transfer',async t=>{const f=mount(t);await flush();const preview={id:ID,accountId:'1',state:'PREVIEW_ONLY',permission:'MEDIA_ALLOWED_AT_CHECK',sourceDirectory:'D:\\source',archiveDirectory:'D:\\archive',channelTitle:'sample',postTitle:'sample',postText:'',discussionTitle:'discussion',postLink:input().link,recursive:true,captionMode:'FILENAME',fileCount:0,totalBytes:0,otherFiles:0,deferredFiles:0,linkedEntries:0,duplicateFiles:0,slowModeSeconds:0,targetCheckedAt:1,createdAt:1,expiresAt:600001,groups:[],warnings:[]};f.expect('/rules/'+ID+'/preview',{ruleId:ID,version:1,revision:1,preview});await f.state.act(result(),'preview');assert.equal(f.state.preview.value.preview.state,'PREVIEW_ONLY');assert.equal(f.calls.some(c=>c.path.includes('/transfers/')),false)})

test('archive is a sibling named after source with suffix, including trailing slash and normalized parent',()=>{
  assert.equal(rules.ruleArchiveDirectory('D:\\素材\\'), 'D:\\素材-已上传');assert.equal(rules.ruleArchiveDirectory('D:/a/../素材/'),'D:\\素材-已上传');assert.equal(rules.ruleArchiveDirectory('D:\\'), '');assert.equal(rules.ruleArchiveDirectory('\\\\server\\share\\x'),'')
})
test('new rule auto-fills name from the entered post without saving or indexing a rule',async t=>{const f=mount(t);await flush();f.state.edit(null);f.state.form.link=input().link;f.expect('/rules/name',{accountId:'1',link:input().link,name:'主帖名称'});await f.state.resolveName();assert.equal(f.state.form.name,'主帖名称');assert.equal(f.state.items.value.length,0);assert.equal(f.calls.filter(c=>c.path==='/rules').length,1)})
test('manual name edits survive a late automatic name response',async t=>{const f=mount(t);await flush();f.state.edit(null);f.state.form.link=input().link;let release;f.expect('/rules/name',new Promise(r=>release=r));const pending=f.state.resolveName();f.state.form.name='我的名字';f.state.nameAutomatic.value=false;release({accountId:'1',link:input().link,name:'主帖名称'});await pending;assert.equal(f.state.form.name,'我的名字')})
test('changing target link discards the earlier name response',async t=>{const f=mount(t);await flush();f.state.edit(null);f.state.form.link=input().link;let release;f.expect('/rules/name',new Promise(r=>release=r));const pending=f.state.resolveName();f.state.form.link='https://t.me/synthetic/2';release({accountId:'1',link:input().link,name:'旧主帖'});await pending;assert.equal(f.state.form.name,'');assert.equal(f.state.nameBusy.value,false)})
test('archive follows source changes and old saved rule stays frozen until save',async t=>{const f=mount(t);await flush();const old=result();f.state.items.value=[old];f.state.edit(f.state.items.value[0]);f.state.form.sourceDirectory='D:\\新素材\\';assert.equal(f.state.form.archiveDirectory,'D:\\新素材-已上传');assert.equal(f.state.items.value[0].rule.input.archiveDirectory,old.rule.input.archiveDirectory)})
test('failed name lookup preserves manual fallback and is not repeated by blur',async t=>{const f=mount(t);await flush();f.state.edit(null);f.state.form.link=input().link;f.expect('/rules/name',new Error('timeout'));await f.state.resolveName();await f.state.resolveName();assert.equal(f.calls.length,2);assert.match(f.state.nameError.value,/手动填写/);f.state.form.name='手动名称';f.state.nameAutomatic.value=false;await f.state.resolveName();assert.equal(f.state.form.name,'手动名称')})

const automatic=()=>{const r=result();r.mode='AUTO_UPLOAD';r.rule.input.executionMode='AUTO_UPLOAD';return r}
const activationPreview=()=>({ruleId:ID,version:1,revision:1,preview:{id:ID,accountId:'1',state:'PREVIEW_ONLY',permission:'MEDIA_ALLOWED_AT_CHECK',sourceDirectory:'D:\\source',archiveDirectory:'D:\\archive',channelTitle:'sample',postTitle:'sample',postText:'',discussionTitle:'discussion',postLink:input().link,recursive:true,captionMode:'FILENAME',fileCount:0,totalBytes:0,otherFiles:0,deferredFiles:0,linkedEntries:0,duplicateFiles:0,slowModeSeconds:0,targetCheckedAt:Date.now(),createdAt:Date.now(),expiresAt:Date.now()+600000,groups:[],warnings:[]}})
test('new rules default to automatic upload but saving still requires separate preview and enable',async t=>{const f=mount(t);await flush();f.state.edit(null);assert.equal(f.state.form.executionMode,'AUTO_UPLOAD');Object.assign(f.state.form,automatic().rule.input);f.expect('/rules',automatic());await f.state.save();assert.equal(f.calls[1].options.body.input.executionMode,'AUTO_UPLOAD');assert.equal(f.state.items.value[0].rule.enabled,false);assert.match(f.state.notice.value,/暂停/);assert.equal(f.calls.length,2)})
test('editing a legacy rule without a mode field keeps observation rather than inheriting the new default',async t=>{const f=mount(t);await flush();const old=result();delete old.rule.input.executionMode;const before=JSON.stringify(old);f.state.edit(old);assert.equal(f.state.form.executionMode,'OBSERVE_ONLY');assert.equal(JSON.stringify(old),before);assert.equal(f.calls.length,1)})
test('a saved mode mismatch remains locked until GET confirms the requested mode, without resubmitting',async t=>{const f=mount(t);await flush();f.state.edit(null);Object.assign(f.state.form,automatic().rule.input);f.expect('/rules',result());await f.state.save();assert.equal(f.state.uncertain.value,true);const operation=f.calls[1].options.body.operationId;f.expect('/rules/by-operation/'+operation,result(),'GET');await f.state.lookup();assert.equal(f.state.uncertain.value,true);f.expect('/rules/by-operation/'+operation,automatic(),'GET');await f.state.lookup();assert.equal(f.state.uncertain.value,false);assert.equal(f.calls.filter(c=>c.options.method==='POST').length,1)})
test('enable automatic first prepares a readonly bound preview and requires explicit confirmation',async t=>{const f=mount(t);await flush();f.expect('/rules/'+ID+'/preview',activationPreview());await f.state.act(automatic(),'enabled');assert.ok(f.state.activation.value);assert.equal(f.calls.length,2);assert.equal(f.calls.some(c=>c.path.endsWith('/enabled')),false);const enabled=automatic();enabled.rule.enabled=true;enabled.rule.revision=2;enabled.state='OBSERVING';f.expect('/rules/'+ID+'/enabled',enabled);await f.state.confirmActivation();assert.deepEqual(f.calls[2].options.body,{revision:1,enabled:true,previewId:ID});assert.equal(f.state.activation.value,null);assert.equal(f.state.items.value[0].rule.enabled,true);assert.equal(f.calls.some(c=>c.path.includes('/transfers/')),false)})
test('expired automatic activation cannot enable and asks for another preview',async t=>{const f=mount(t);await flush();const p=activationPreview();f.expect('/rules/'+ID+'/preview',p);await f.state.prepareActivation(automatic());f.state.activation.value.preview.preview.expiresAt=1;await f.state.confirmActivation();assert.equal(f.calls.length,2);assert.equal(f.state.activation.value,null);assert.match(f.state.error.value,/过期/)})
test('unknown automatic activation response is closed and never automatically posted twice',async t=>{const f=mount(t);await flush();f.expect('/rules/'+ID+'/preview',activationPreview());await f.state.prepareActivation(automatic());f.expect('/rules/'+ID+'/enabled',new Error('timeout'));await f.state.confirmActivation();await f.state.confirmActivation();assert.equal(f.calls.length,3);assert.equal(f.state.activation.value,null);assert.match(f.state.error.value,/刷新规则核对/)})
test('changing account discards a late activation preview and cannot enable it',async t=>{const f=mount(t);await flush();let resolve;f.expect('/rules/'+ID+'/preview',new Promise(r=>resolve=r));const pending=f.state.prepareActivation(automatic());f.props.authorized=false;await flush();resolve(activationPreview());await pending;assert.equal(f.state.activation.value,null);await f.state.confirmActivation();assert.equal(f.calls.length,2)})
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
