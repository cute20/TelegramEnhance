import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import * as vue from 'vue'
import {parse,compileScript,compileTemplate} from 'vue/compiler-sfc'
import ts from 'typescript'
import * as batch from '../src/downloadBatch.ts'
import * as channelModule from '../src/downloadChannel.ts'
const ID='00000000-0000-0000-0000-000000000001',TASK='00000000-0000-0000-0000-000000000002'
const initial=()=>({id:ID,accountId:'1',revision:1,channelTitle:'channel',tags:[],keywords:[],types:['VIDEO'],includeMain:false,targetDirectory:'C:\\test',posts:[{key:'post',title:'title',state:'PENDING',commentMessages:0,mainMedia:0,commentMedia:0,bytes:0}],total:0,totalBytes:0,exhausted:false,canContinue:true,busy:false,error:null,warnings:[],createdAt:1,expiresAt:600001})
const done=()=>({...initial(),revision:2,posts:[{...initial().posts[0],state:'READY',commentMessages:1,commentMedia:1,bytes:37}],total:1,totalBytes:37,exhausted:true,canContinue:false})
const receipt=()=>({id:'00000000-0000-0000-0000-000000000003',snapshot:{previewId:ID,revision:2,channelTitle:'channel',tags:[],keywords:[],types:['VIDEO'],includeMain:false,targetDirectory:'C:\\test',postCount:1,total:1,totalBytes:37},taskIds:[TASK],createdAt:10})
const flush=async()=>{await vue.nextTick();await new Promise(setImmediate)}
function mount(t){
  const props=vue.reactive({scanId:ID,scanRevision:3,postKeys:['post'],accountId:'1',active:true,authorized:true,available:true}),calls=[],expected=[],failures=[],events=[],hooks=[]
  const api=async(path,options={})=>{calls.push({path,options});const next=expected.shift();try{assert.ok(next,path);assert.equal(path,next.path);assert.equal(options.method??'GET',next.method??'POST');if(next.body)assert.deepEqual(options.body,next.body)}catch(error){failures.push(error);throw error}if(next.value instanceof Error)throw next.value;return await next.value}
  const parsed=parse(readFileSync(new URL('../src/components/DownloadBatchPreparation.vue',import.meta.url),'utf8'),{filename:'DownloadBatchPreparation.vue'}),module={exports:{}}
  const code=ts.transpileModule(compileScript(parsed.descriptor,{id:'batch-test'}).content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
  const modules={vue:{...vue,onBeforeUnmount:f=>hooks.push(f)},'../api':{api,ApiError:class extends Error{}},'../downloadPreview':{mediaTypes:['VIDEO','PHOTO','AUDIO','DOCUMENT','OTHER'].map(type=>({type,label:type}))},'../downloadBatch':batch}
  new Function('require','module','exports',code)(name=>modules[name],module,module.exports)
  const scope=vue.effectScope(),state=scope.run(()=>module.exports.default.setup(props,{expose(){},emit:(...event)=>events.push(event)}));let destroyed=false
  const destroy=()=>{if(!destroyed){destroyed=true;hooks.forEach(f=>f());scope.stop()}}
  t.after(()=>{destroy();assert.deepEqual(failures,[]);assert.equal(expected.length,0)})
  return {state,props,calls,events,destroy,expect:(path,value,method='POST',body)=>expected.push({path,value,method,body})}
}
function prepare(f,value=done()){
  f.state.directory.value='C:\\test';f.expect('/transfers/download/batches/preview',initial(),'POST',{scanId:ID,revision:3,postKeys:['post'],types:['VIDEO'],includeMain:false,targetDirectory:'C:\\test'})
  f.expect('/transfers/download/batches/preview/'+ID+'/next',value,'POST',{revision:1})
}
test('preparing a complete batch never creates tasks before explicit confirmation',async t=>{const f=mount(t);prepare(f);await f.state.begin();assert.equal(f.state.preview.value.exhausted,true);assert.equal(f.calls.length,2);assert.equal(f.state.created.value,null);assert.equal(f.state.locked.value,false);assert.equal(f.calls.some(c=>c.path==='/transfers/download/batches'),false)})
test('stop preserves the current metadata step and explicit continue completes the remaining range',async t=>{const f=mount(t);let release;prepare(f,new Promise(r=>release=r));const request=f.state.begin();await flush();f.state.stop();release({...done(),exhausted:false,canContinue:true,posts:[{...done().posts[0],state:'READING_COMMENTS'}]});await request;assert.equal(f.calls.length,2);assert.equal(f.state.preview.value.exhausted,false);f.expect('/transfers/download/batches/preview/'+ID+'/next',{...done(),revision:3},'POST',{revision:2});await f.state.proceed();assert.equal(f.state.preview.value.exhausted,true)})
test('unknown metadata response requires GET before retrying the next step',async t=>{const f=mount(t);prepare(f,new Error('timeout'));await f.state.begin();await f.state.proceed();assert.equal(f.calls.length,2);assert.equal(f.state.needsCheck.value,true);f.expect('/transfers/download/batches/preview/'+ID,done(),'GET');await f.state.check();assert.equal(f.state.needsCheck.value,false);assert.equal(f.state.preview.value.exhausted,true)})
test('unknown create response locks the snapshot and resolves through the same operation',async t=>{const f=mount(t);prepare(f);await f.state.begin();f.expect('/transfers/download/batches',new Error('timeout'));await f.state.create();const command=f.calls[2].options.body;assert.equal(f.state.uncertain.value,true);assert.equal(f.state.locked.value,true);await f.state.create();assert.equal(f.calls.length,3);f.expect('/transfers/download/batches/by-operation/'+command.operationId,{batch:receipt()},'GET');await f.state.lookup();assert.equal(f.state.created.value.taskIds[0],TASK);assert.equal(f.calls.filter(c=>c.path==='/transfers/download/batches').length,1)})
test('an explicit retry reuses its original operation and cannot change the prepared files',async t=>{const f=mount(t);prepare(f);await f.state.begin();f.expect('/transfers/download/batches',new Error('timeout'));await f.state.create();const command=f.calls[2].options.body;f.expect('/transfers/download/batches',receipt(),'POST',command);await f.state.create(true);assert.equal(f.state.created.value.snapshot.total,1);assert.equal(f.state.uncertain.value,false)})
test('changing selection or account drops a late readonly preparation result',async t=>{const f=mount(t);let release;prepare(f,new Promise(r=>release=r));const request=f.state.begin();await flush();f.props.postKeys=['other'];await flush();release(done());await request;assert.equal(f.state.preview.value,null);assert.equal(f.state.created.value,null);assert.equal(f.calls.length,2)})
test('hiding mid-step only resumes after an explicit current-position check',async t=>{const f=mount(t);let release;prepare(f,new Promise(r=>release=r));const request=f.state.begin();await flush();f.props.active=false;await flush();release(done());await request;assert.equal(f.state.needsCheck.value,true);f.expect('/transfers/download/batches/preview/'+ID,done(),'GET');f.props.active=true;await flush();assert.equal(f.state.preview.value.exhausted,true);assert.equal(f.calls.length,3)})
test('malformed totals, foreign accounts, changed paths and incomplete receipts are rejected',()=>{assert.equal(batch.validBatch(initial(),'1'),true);assert.equal(batch.validBatch(done(),'1',initial(),true),true);assert.equal(batch.validBatch(done(),'2'),false);assert.equal(batch.validBatch({...done(),total:2},'1'),false);assert.equal(batch.validBatch({...done(),targetDirectory:'other'},'1',initial(),true),false);assert.equal(batch.validBatchReceipt(receipt(),done(),{operationId:TASK,previewId:ID,revision:2}),true);assert.equal(batch.validBatchReceipt({...receipt(),taskIds:[]},done(),{operationId:TASK,previewId:ID,revision:2}),false)})
test('batch creation keeps its frozen operation through connectivity loss and only GET unlocks the composer',async t=>{
  const f=mount(t);prepare(f);await f.state.begin();let release
  f.expect('/transfers/download/batches',new Promise(resolve=>{release=resolve}));const creating=f.state.create(),command=structuredClone(f.calls.at(-1).options.body)
  assert.equal(f.state.transitionLocked.value,true)
  f.props.available=false;await flush()
  assert.equal(f.state.uncertain.value,true);assert.equal(f.state.preview.value.exhausted,true);assert.equal(f.state.preview.value.revision,2);assert.equal(f.state.directory.value,'C:\\test')
  assert.deepEqual(f.events.filter(event=>event[0]==='transitionLocked'),[['transitionLocked',true]],'a disconnect cannot briefly release the mutation lock')
  await f.state.lookup();assert.equal(f.calls.length,3,'offline lookup stays local')
  f.props.available=true;await flush();release(receipt());await creating
  assert.equal(f.state.created.value,null);assert.equal(f.state.uncertain.value,true);assert.equal(f.state.transitionLocked.value,true)
  await f.state.create();assert.equal(f.calls.length,3,'the interrupted create is not replayed on reconnect')
  f.expect('/transfers/download/batches/by-operation/'+command.operationId,{batch:receipt()},'GET');await f.state.lookup()
  assert.equal(f.state.created.value.id,receipt().id);assert.equal(f.state.locked.value,true,'created leaf inputs remain frozen');assert.equal(f.state.transitionLocked.value,false,'confirmed creation must not trap the outer composer')
  assert.deepEqual(f.events.filter(event=>event[0]==='transitionLocked'),[['transitionLocked',true],['transitionLocked',false]])
  assert.equal(f.calls.filter(call=>call.path==='/transfers/download/batches').length,1)
})
test('readonly batch preparation can be cancelled without acquiring a persistent creation lock',async t=>{
  const f=mount(t);let release;prepare(f,new Promise(resolve=>{release=resolve}));const preparing=f.state.begin();await flush()
  assert.equal(f.state.locked.value,true);assert.equal(f.state.transitionLocked.value,false)
  assert.deepEqual(f.events.filter(event=>event[0]==='transitionLocked'),[])
  release(done());await preparing
  assert.equal(f.state.preview.value.exhausted,true);assert.equal(f.state.transitionLocked.value,false);assert.equal(f.calls.length,2)
})

const selectedChannelScan=()=>({id:ID,accountId:'1',channel:{id:'-1001',title:'channel'},tags:[],keywords:[],revision:2,scanned:1,matched:1,unverified:0,
  rows:[{match:'MATCH',postLink:'https://t.me/synthetic/1',comments:'NOT_READ',post:{key:'post',accountId:'1',channelId:'-1001',mainMessageId:'1',title:'post',text:'text',mainCoverage:'COMPLETE',mainMedia:[],warnings:[]}}],
  exhausted:true,canContinue:false,busy:false,limitReached:false,error:null,warnings:[],createdAt:1,expiresAt:600001})
function mountChannelWithBatch(t){
  const props=vue.reactive({authorized:true,available:true,accountId:'1',active:true,initialSource:{source:'-1001',tags:'',keywords:''}}),calls=[],failures=[],events=[],hooks=[]
  const api=async(path,options={})=>{calls.push({path,options});try{assert.equal(path,'/transfers/download/channels');assert.equal(options.method??'GET','GET')}catch(cause){failures.push(cause);throw cause}return {items:[{id:'-1001',title:'channel'}],scope:'READABLE_CHANNELS',partial:false,warnings:[]}}
  const {descriptor}=parse(readFileSync(new URL('../src/components/DownloadChannelPreview.vue',import.meta.url),'utf8'),{filename:'DownloadChannelPreview.vue'})
  const script=compileScript(descriptor,{id:'channel-batch-connection-test'}),template=compileTemplate({source:descriptor.template.content,filename:'DownloadChannelPreview.vue',id:'channel-batch-connection-test',compilerOptions:{bindingMetadata:script.bindings,expressionPlugins:['typescript']}})
  assert.deepEqual(template.errors,[])
  const transpile=code=>ts.transpileModule(code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
  const runtimeVue={...vue,onMounted(){},onBeforeUnmount:hook=>hooks.push(hook),withCtx:fn=>fn,withDirectives:node=>node},stubs=new Map()
  const modules={vue:runtimeVue,'../api':{api,ApiError:class extends Error{}},'../downloadChannel':channelModule}
  const requireModule=name=>{if(name.endsWith('.vue')){if(!stubs.has(name))stubs.set(name,{__name:name.split('/').at(-1).replace('.vue','')});return {__esModule:true,default:stubs.get(name)}}assert.ok(Object.hasOwn(modules,name),name);return modules[name]}
  const scriptModule={exports:{}},renderModule={exports:{}}
  new Function('require','module','exports',transpile(script.content))(requireModule,scriptModule,scriptModule.exports)
  new Function('require','module','exports',transpile(template.code))(requireModule,renderModule,renderModule.exports)
  const scope=vue.effectScope(),state=scope.run(()=>scriptModule.exports.default.setup(props,{expose(){},emit:(...event)=>events.push(event)}))
  t.after(()=>{hooks.forEach(hook=>hook());scope.stop();assert.deepEqual(failures,[])})
  const nodes=node=>!node||typeof node!=='object'?[]:Array.isArray(node)?node.flatMap(nodes):[node,...(Array.isArray(node.children)?node.children:node.children&&typeof node.children==='object'?Object.values(node.children).filter(v=>typeof v==='function').flatMap(slot=>slot()):[]).flatMap(nodes)]
  return {state,props,calls,events,child:()=>nodes(renderModule.exports.render({},[],props,vue.proxyRefs(state),{},{})).find(node=>node.type?.__name==='DownloadBatchPreparation')}
}
test('same-account channel disconnect preserves the selected batch child and forwards only its transition lock',async t=>{
  const f=mountChannelWithBatch(t);await flush();f.state.scan.value=selectedChannelScan();f.state.selectedPosts.value=['post']
  const before=f.child();assert.ok(before);before.props.onLocked(true);before.props.onTransitionLocked(true)
  const snapshot=JSON.parse(JSON.stringify(f.state.scan.value));f.props.available=false;await flush()
  assert.deepEqual(f.state.scan.value,snapshot);assert.deepEqual(f.state.selectedPosts.value,['post']);assert.equal(f.state.batchTransitionLocked.value,true)
  assert.ok(f.child(),'the child owning the frozen operation must remain mounted');assert.equal(f.child().type,before.type);assert.equal(f.child().props['scan-id'],ID);assert.deepEqual(f.child().props['post-keys'],['post'])
  f.props.available=true;await flush()
  assert.ok(f.child());assert.deepEqual(f.state.scan.value,snapshot);assert.equal(f.calls.length,1,'recovery does not replace the scan or submit a batch')
  assert.deepEqual(f.events.filter(event=>event[0]==='transitionLocked'),[['transitionLocked',true]])
  f.child().props.onTransitionLocked(false)
  assert.equal(f.state.batchLocked.value,true,'the legacy leaf lock is independent');assert.equal(f.state.batchTransitionLocked.value,false)
})
test('changing channel account clears the old selected batch and releases its transition lock',async t=>{
  const f=mountChannelWithBatch(t);await flush();f.state.scan.value=selectedChannelScan();f.state.selectedPosts.value=['post'];f.child().props.onTransitionLocked(true)
  f.props.accountId='2';await flush()
  assert.equal(f.state.scan.value,null);assert.deepEqual(f.state.selectedPosts.value,[]);assert.equal(f.child(),undefined);assert.equal(f.state.batchTransitionLocked.value,false)
  assert.deepEqual(f.events.filter(event=>event[0]==='transitionLocked'),[['transitionLocked',true],['transitionLocked',false]])
})
