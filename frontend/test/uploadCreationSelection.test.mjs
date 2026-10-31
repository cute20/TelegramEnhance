import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import {compileScript,parse} from 'vue/compiler-sfc'
import ts from 'typescript'
import * as rules from '../src/rules.ts'
import * as upload from '../src/uploadPreview.ts'
import * as uploadTasks from '../src/uploadTasks.ts'
import * as library from '../src/library.ts'

const ID='00000000-0000-0000-0000-000000000001',LINK='https://t.me/synthetic/1'
const flush=async()=>{await vue.nextTick();await new Promise(setImmediate)}
const selection=()=>vue.reactive({link:LINK,sourceDirectory:'D:\\shared-upload',recursive:false,captionMode:'EMPTY'})
function preview(input){return {id:ID,accountId:'1',state:'PREVIEW_ONLY',...input,channelTitle:'Synthetic',postTitle:'Synthetic',postText:'Synthetic',postLink:input.link,discussionTitle:'Synthetic',permission:'MEDIA_ALLOWED_AT_CHECK',slowModeSeconds:0,fileCount:1,totalBytes:10,groups:[{number:1,mediaType:'DOCUMENT',mode:'SINGLE',files:[{relativePath:'one.txt',fileName:'one.txt',mediaType:'DOCUMENT',size:10,caption:input.captionMode==='FILENAME'?'one.txt':''}]}],otherFiles:0,deferredFiles:0,linkedEntries:0,duplicateFiles:0,warnings:[],targetCheckedAt:1,createdAt:1,expiresAt:600001}}
function savedTask(){return {id:ID,state:'READY',channelTitle:'Synthetic',postTitle:'Synthetic',postLink:LINK,discussionTitle:'Synthetic',sourceDirectory:'D:\\shared-upload',archiveDirectory:'D:\\shared-upload-已上传',stopRequested:false,total:1,confirmed:0,archived:0,error:null,createdAt:1,updatedAt:1,groups:[{id:'1',number:1,mediaType:'DOCUMENT',state:'READY',items:[{id:'one',fileName:'one.txt',mediaType:'DOCUMENT',size:10,state:'READY',formalMessageId:null,archivePath:null,error:null}]}]}}
function savedRule(input){return {mode:'AUTO_UPLOAD',state:'PAUSED',scanning:false,nextAllowedAt:1,rule:{id:ID,accountId:'1',version:1,revision:1,enabled:false,nextScanAt:0,createdAt:1,updatedAt:1,input,observation:null}}}
function mount(t,component,initialProps={},initialSteps=[]){
  const {descriptor}=parse(readFileSync(new URL('../src/components/'+component+'.vue',import.meta.url),'utf8'),{filename:component+'.vue'})
  const code=ts.transpileModule(compileScript(descriptor,{id:'upload-selection-test'}).content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
  const props=vue.reactive({authorized:true,available:true,accountId:'1',...initialProps}),calls=[],events=[],errors=[],hooks=[],mounted=[],steps=[...initialSteps]
  const api=async(path,options={})=>{calls.push({path,options});const step=steps.shift();try{assert.ok(step,path);assert.equal(path,step.path);assert.equal(options.method??'GET',step.method??'POST')}catch(error){errors.push(error);throw error}if(step.value instanceof Error)throw step.value;return await step.value}
  const modules={vue:{...vue,onMounted:fn=>mounted.push(fn),onBeforeUnmount:fn=>hooks.push(fn)},'../api':{api,ApiError:class extends Error{}},'../rules':rules,'../uploadPreview':upload,'../uploadTasks':uploadTasks,'../library':library},module={exports:{}}
  new Function('require','module','exports',code)(name=>name.endsWith('.vue')?{}:modules[name],module,module.exports)
  const scope=vue.effectScope(),state=scope.run(()=>module.exports.default.setup(props,{expose(){},emit:(...event)=>events.push(event)}))
  mounted.forEach(fn=>fn())
  let destroyed=false
  const destroy=()=>{if(destroyed)return;destroyed=true;hooks.forEach(fn=>fn());scope.stop()}
  t.after(()=>{destroy();assert.deepEqual(errors,[]);assert.equal(steps.length,0)})
  return {props,state,calls,events,destroy,expect:(path,value,method='POST')=>steps.push({path,value,method})}
}

test('shared upload fields map to the original preview API with the same sibling archive default',async t=>{
  const shared=selection(),f=mount(t,'UploadPreparation',{selection:shared});await flush()
  const input={...shared,archiveDirectory:'D:\\shared-upload-已上传'}
  assert.equal(f.state.link.value,LINK);assert.equal(f.state.sourceDirectory.value,shared.sourceDirectory)
  assert.equal(f.state.archiveDirectory.value,input.archiveDirectory);assert.equal(f.calls.length,0)
  f.expect('/transfers/upload/preview',preview(input));await f.state.prepare()
  assert.deepEqual(f.calls[0].options.body,input);assert.equal(f.state.preview.value.fileCount,1)
  assert.equal(f.state.fieldsLocked.value,false);assert.equal(f.state.transitionLocked.value,false)
  assert.deepEqual({...shared},{link:LINK,sourceDirectory:'D:\\shared-upload',recursive:false,captionMode:'EMPTY'})
})

test('editing a shared source invalidates readonly preview and derives a new sibling archive',async t=>{
  const shared=selection(),f=mount(t,'UploadPreparation',{selection:shared});await flush()
  f.expect('/transfers/upload/preview',preview({...shared,archiveDirectory:rules.ruleArchiveDirectory(shared.sourceDirectory)}));await f.state.prepare()
  shared.sourceDirectory='D:/other/../next-upload/';shared.recursive=true;shared.captionMode='FILENAME';await flush()
  assert.equal(f.state.preview.value,null);assert.equal(f.state.archiveDirectory.value,'D:\\next-upload-已上传')
  const input={...shared,archiveDirectory:'D:\\next-upload-已上传'};f.expect('/transfers/upload/preview',preview(input));await f.state.prepare()
  assert.deepEqual(f.calls[1].options.body,input)
  shared.sourceDirectory='D:\\';await flush();assert.equal(f.state.archiveDirectory.value,'');await f.state.prepare();assert.equal(f.calls.length,2)
})

test('shared upload fields lock through preparation, an unknown create and its original operation lookup',async t=>{
  const shared=selection(),f=mount(t,'UploadPreparation',{selection:shared});await flush()
  const original={...shared,archiveDirectory:'D:\\shared-upload-已上传'}
  let release;f.expect('/transfers/upload/preview',new Promise(resolve=>{release=resolve}));const pending=f.state.prepare()
  assert.equal(f.state.fieldsLocked.value,true);assert.equal(f.state.transitionLocked.value,false)
  release(preview(original));await pending;assert.equal(f.state.fieldsLocked.value,false)
  f.expect('/transfers/upload/tasks',new Error('timeout'));await f.state.createTask()
  const operation=f.calls.at(-1).options.body.operationId
  assert.equal(f.state.fieldsLocked.value,true);assert.equal(f.state.transitionLocked.value,true)
  shared.link='https://t.me/synthetic/2';shared.sourceDirectory='D:\\unrelated';await flush()
  assert.equal(f.state.link.value,LINK);assert.equal(f.state.sourceDirectory.value,original.sourceDirectory)
  assert.equal(f.state.uncertain.value,true);await f.state.createTask();assert.equal(f.calls.length,2)
  f.expect('/transfers/upload/tasks/by-operation/'+operation,{task:savedTask()},'GET');await f.state.lookupTask()
  assert.equal(f.state.created.value.state,'READY');assert.equal(f.state.transitionLocked.value,false);assert.equal(f.state.fieldsLocked.value,true)
  assert.equal(f.calls.filter(call=>call.options.method==='POST'&&call.path.endsWith('/tasks')).length,1)
  assert.equal(f.calls.some(call=>call.path.endsWith('/start')),false)
  f.destroy();assert.deepEqual(f.events.at(-1),['fieldsLocked',false])
})

test('a single shared upload selection survives the once-to-rule controller switch without edit resetting it',async t=>{
  const shared=selection(),once=mount(t,'UploadPreparation',{selection:shared});await flush();once.destroy()
  const f=mount(t,'RuleWorkspace',{selection:shared,createOnly:true,embedded:true},[{path:'/rules/name',value:{accountId:'1',link:LINK,name:'Synthetic rule'}}]);await flush()
  assert.equal(f.state.formOpen.value,true);assert.equal(f.state.form.link,shared.link);assert.equal(f.state.form.sourceDirectory,shared.sourceDirectory)
  assert.equal(f.state.form.archiveDirectory,'D:\\shared-upload-已上传');assert.equal(f.state.form.recursive,false);assert.equal(f.state.form.captionMode,'EMPTY')
  assert.equal(f.state.form.name,'Synthetic rule');assert.equal(f.state.form.executionMode,'AUTO_UPLOAD')
  const frozen=structuredClone({...f.state.form,window:{...f.state.form.window}})
  f.expect('/rules',savedRule(frozen));await f.state.save()
  assert.deepEqual(f.calls.at(-1).options.body.input,frozen);assert.deepEqual(f.events.at(-1),['saved','upload'])
  assert.equal(f.calls.some(call=>call.path.includes('/transfers/')||call.path.endsWith('/enabled')),false)
})

test('shared rule selection changes preserve a manual name and invalidate late automatic naming',async t=>{
  const shared=selection();let release
  const f=mount(t,'RuleWorkspace',{selection:shared,createOnly:true,embedded:true},[{path:'/rules/name',value:new Promise(resolve=>{release=resolve})}]);await flush()
  f.state.form.name='Manual label';f.state.nameAutomatic.value=false
  shared.link='https://t.me/synthetic/2';shared.sourceDirectory='D:\\next';shared.captionMode='FILENAME';await flush()
  release({accountId:'1',link:LINK,name:'Late old title'});await flush()
  assert.equal(f.state.form.name,'Manual label');assert.equal(f.state.form.link,shared.link);assert.equal(f.state.form.archiveDirectory,'D:\\next-已上传')
  assert.equal(f.state.form.captionMode,'FILENAME');assert.equal(f.calls.length,1)
})

test('unknown shared upload-rule creation freezes its original request until a readonly lookup confirms it',async t=>{
  const shared=selection(),f=mount(t,'RuleWorkspace',{selection:shared,createOnly:true,embedded:true},[{path:'/rules/name',value:{accountId:'1',link:LINK,name:'Synthetic rule'}}]);await flush()
  const original=structuredClone({...f.state.form,window:{...f.state.form.window}})
  f.expect('/rules',new Error('timeout'));await f.state.save();const operation=f.calls.at(-1).options.body.operationId
  shared.link='https://t.me/synthetic/2';shared.sourceDirectory='D:\\later';await flush();f.state.closeForm();await f.state.save()
  assert.equal(f.state.form.link,original.link);assert.equal(f.state.form.sourceDirectory,original.sourceDirectory);assert.equal(f.state.formOpen.value,true)
  assert.equal(f.state.uncertain.value,true);assert.equal(f.calls.length,2)
  f.expect('/rules/by-operation/'+operation,savedRule(original),'GET');await f.state.lookup()
  assert.deepEqual(f.events.at(-1),['saved','upload']);assert.equal(f.state.items.value[0].rule.enabled,false)
  assert.equal(f.calls.filter(call=>call.path==='/rules'&&call.options.method==='POST').length,1)
})

test('an in-flight shared rule save hands its lock directly to unknown across a same-account interruption',async t=>{
  const shared=selection(),f=mount(t,'RuleWorkspace',{selection:shared,createOnly:true,embedded:true},[{path:'/rules/name',value:{accountId:'1',link:LINK,name:'Synthetic rule'}}]);await flush()
  const original=structuredClone({...f.state.form,window:{...f.state.form.window}})
  let release;f.expect('/rules',new Promise(resolve=>{release=resolve}));const pending=f.state.save()
  const operation=f.calls.at(-1).options.body.operationId,lockedAt=f.events.length
  assert.equal(f.state.busy.value,'save');assert.deepEqual(f.events.at(-1),['locked',true])
  f.props.available=false;await flush()
  assert.equal(f.state.busy.value,'');assert.equal(f.state.uncertain.value,true)
  assert.equal(f.events.slice(lockedAt).some(event=>event[0]==='locked'&&event[1]===false),false)
  f.props.available=true;await flush();release(savedRule(original));await pending
  assert.equal(f.state.uncertain.value,true);assert.equal(f.state.formOpen.value,true)
  assert.equal(f.events.slice(lockedAt).some(event=>event[0]==='locked'&&event[1]===false),false)
  f.expect('/rules/by-operation/'+operation,savedRule(original),'GET');await f.state.lookup()
  assert.deepEqual(f.events.slice(-2),[['locked',false],['saved','upload']])
  assert.equal(f.calls.filter(call=>call.path==='/rules'&&call.options.method==='POST').length,1)
})

test('saving after asynchronous naming snapshots the latest shared source and caption options',async t=>{
  const shared=selection();shared.link=''
  const f=mount(t,'RuleWorkspace',{selection:shared,createOnly:true,embedded:true});await flush()
  shared.link=LINK;await flush()
  let release;f.expect('/rules/name',new Promise(resolve=>{release=resolve}));const saving=f.state.save()
  assert.equal(f.state.nameBusy.value,true);assert.equal(f.state.busy.value,'')
  shared.sourceDirectory='D:\\updated-during-name';shared.recursive=true;shared.captionMode='FILENAME';await flush()
  const expected={...f.state.form,name:'Resolved name',window:{...f.state.form.window}}
  f.expect('/rules',savedRule(expected));release({accountId:'1',link:LINK,name:'Resolved name'});await saving
  assert.deepEqual(f.calls.at(-1).options.body.input,expected)
  assert.equal(expected.archiveDirectory,'D:\\updated-during-name-已上传')
  assert.deepEqual(f.events.at(-1),['saved','upload'])
})
