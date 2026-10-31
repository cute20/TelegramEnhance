import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import * as vue from 'vue'
import {parse,compileScript,compileTemplate} from 'vue/compiler-sfc'
import ts from 'typescript'
import * as library from '../src/library.ts'
import * as previewModule from '../src/downloadPreview.ts'
import * as taskModule from '../src/downloadTasks.ts'
import * as channelModule from '../src/downloadChannel.ts'
import * as batchModule from '../src/downloadBatch.ts'

const ID='00000000-0000-0000-0000-000000000001',TASK='00000000-0000-0000-0000-000000000002'
const selection=(overrides={})=>({entry:'POST',source:'https://t.me/synthetic/10',tags:'旅行',keywords:'山',types:['DOCUMENT'],includeMain:false,targetDirectory:'D:\\chosen',suggestedName:'选定频道',...overrides})
const message=(id,chatId='-2001')=>({chatId,messageId:String(id),link:null})
const media=(id,type='DOCUMENT',source='COMMENT')=>({key:`1:-2001:${id}:0`,message:message(id),type,source,fileName:`file-${id}.bin`,size:37,caption:''})
const source=()=>({id:ID,post:{key:'post',accountId:'1',channelId:'-1001',mainMessageId:'10',mainMedia:[{...media(10,'PHOTO','MAIN'),message:message(10,'-1001')}],canReadComments:true,discussionChatId:'-2001',discussionRootMessageId:'500',warnings:[]},comments:{postKey:'post',messages:[message(601),message(600)],media:[media(601),media(600,'VIDEO')],messageCount:2,nextCursor:null,exhausted:true,warnings:[]},pageNumber:1,observedMessages:2,exhausted:true,canContinue:false,warnings:[]})
const task=()=>({id:TASK,state:'QUEUED',title:'Synthetic',channelTitle:'Synthetic',scope:'OBSERVED_SELECTION',duplicatePolicy:'KEEP_DUPLICATES_V2',duplicates:0,duplicateDirectory:'D:\\chosen\\重复媒体',pauseRequested:false,targetDirectory:'D:\\chosen',total:1,completed:0,skipped:0,totalBytes:37,items:[{id:'item',state:'QUEUED',type:'DOCUMENT',size:37,fileName:'file-601.bin',downloadedBytes:0,writtenBytes:0,outputPath:'D:\\chosen\\file-601.bin',error:null}],error:null,createdAt:1,updatedAt:1})
const scan=(overrides={})=>({id:ID,accountId:'1',channel:{id:'-1001',title:'source'},tags:['旅行'],keywords:['山'],revision:1,scanned:0,matched:0,unverified:0,rows:[],exhausted:true,canContinue:false,busy:false,limitReached:false,error:null,warnings:[],createdAt:1,expiresAt:600001,...overrides})
const batch=()=>({id:ID,accountId:'1',revision:1,channelTitle:'channel',tags:['旅行'],keywords:['山'],types:['DOCUMENT'],includeMain:true,targetDirectory:'D:\\chosen',posts:[{key:'post',title:'title',state:'PENDING',commentMessages:0,mainMedia:0,commentMedia:0,bytes:0}],total:0,totalBytes:0,exhausted:false,canContinue:true,busy:false,error:null,warnings:[],createdAt:1,expiresAt:600001})
const batchDone=()=>({...batch(),revision:2,posts:[{...batch().posts[0],state:'READY',commentMessages:1,mainMedia:1,commentMedia:1,bytes:74}],total:2,totalBytes:74,exhausted:true,canContinue:false})
const receipt=()=>({id:'00000000-0000-0000-0000-000000000003',snapshot:{previewId:ID,revision:2,channelTitle:'channel',tags:['旅行'],keywords:['山'],types:['DOCUMENT'],includeMain:true,targetDirectory:'D:\\chosen',postCount:1,total:2,totalBytes:74},taskIds:[TASK],createdAt:10})
const flush=async()=>{await vue.nextTick();await new Promise(setImmediate)}
const deferred=()=>{let resolve;const promise=new Promise(yes=>{resolve=yes});return {promise,resolve}}

function mount(t,name,initialProps={}){
  const props=vue.reactive({authorized:true,available:true,accountId:'1',active:true,embedded:true,...initialProps}),calls=[],events=[],expected=[],failures=[],hooks=[],mounted=[],stubs=new Map()
  const api=async(path,options={})=>{
    calls.push({path,options});const next=expected.shift()
    try{assert.ok(next,`Unexpected ${options.method??'GET'} ${path}`);assert.equal(path,next.path);assert.equal(options.method??'GET',next.method);if(next.body)assert.deepEqual(options.body,next.body)}catch(cause){failures.push(cause);throw cause}
    if(next.value instanceof Error)throw next.value
    return await next.value
  }
  const {descriptor}=parse(readFileSync(new URL(`../src/components/${name}.vue`,import.meta.url),'utf8'),{filename:`${name}.vue`})
  const script=compileScript(descriptor,{id:`selection-${name}`}),template=compileTemplate({source:descriptor.template.content,filename:`${name}.vue`,id:`selection-${name}`,compilerOptions:{bindingMetadata:script.bindings,expressionPlugins:['typescript']}})
  assert.deepEqual(template.errors,[])
  const modules={vue:{...vue,onMounted:f=>mounted.push(f),onBeforeUnmount:f=>hooks.push(f),withCtx:f=>f,withDirectives:node=>node,resolveDynamicComponent:value=>value},'../api':{api,ApiError:class extends Error{}},'../library':library,'../downloadPreview':previewModule,'../downloadTasks':taskModule,'../downloadChannel':channelModule,'../downloadBatch':batchModule}
  const requireModule=name=>{if(name.endsWith('.vue')){if(!stubs.has(name))stubs.set(name,{__name:name.split('/').at(-1).replace('.vue','')});return {__esModule:true,default:stubs.get(name)}}assert.ok(Object.hasOwn(modules,name),name);return modules[name]}
  const transpile=code=>ts.transpileModule(code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
  const scriptModule={exports:{}},renderModule={exports:{}}
  new Function('require','module','exports',transpile(script.content))(requireModule,scriptModule,scriptModule.exports)
  new Function('require','module','exports',transpile(template.code))(requireModule,renderModule,renderModule.exports)
  const scope=vue.effectScope(),state=scope.run(()=>scriptModule.exports.default.setup(props,{expose(){},emit:(...event)=>events.push(event)}))
  let destroyed=false
  const destroy=()=>{if(destroyed)return;destroyed=true;hooks.forEach(hook=>hook());scope.stop()}
  t.after(()=>{destroy();assert.deepEqual(failures,[]);assert.equal(expected.length,0)})
  const nodes=node=>!node||typeof node!=='object'?[]:Array.isArray(node)?node.flatMap(nodes):[node,...(Array.isArray(node.children)?node.children:node.children&&typeof node.children==='object'?Object.values(node.children).filter(value=>typeof value==='function').flatMap(slot=>slot()):[]).flatMap(nodes)]
  const rendered=()=>nodes(renderModule.exports.render({},[],props,vue.proxyRefs(state),{},{}))
  return {state,props,calls,events,destroy,mounted:()=>mounted.forEach(f=>f()),nodes:rendered,expect:(path,value,method='POST',body)=>expected.push({path,value,method,body})}
}

test('shared post fields drive the observed selection and actual task directory without duplicate form inputs',async t=>{
  const f=mount(t,'NewTransfer',{selection:selection()});f.mounted();await flush();assert.equal(f.calls.length,0)
  assert.equal(f.state.downloadSourceKind.value,'post');assert.equal(f.state.link.value,'https://t.me/synthetic/10')
  f.expect('/transfers/download/preview',source(),'POST',{link:'https://t.me/synthetic/10'});await f.state.readPreview()
  assert.deepEqual(f.state.items.value.map(item=>item.key),[media(601).key])
  assert.equal(f.nodes().some(node=>node.props?.id==='transfer-link'||node.props?.id==='download-directory'),false)
  assert.equal(f.nodes().some(node=>node.type==='fieldset'&&node.props?.class==='transfer-filters'),false)
  f.props.selection.types=[];f.props.selection.includeMain=true;await flush()
  assert.deepEqual(f.state.items.value.map(item=>item.source),['MAIN'],'main-only selection remains main-only')
  f.props.selection.types=['DOCUMENT'];f.props.selection.includeMain=false;f.props.selection.targetDirectory='D:\\chosen';await flush()
  f.expect('/transfers/download/tasks',task());await f.state.createDownload()
  const request=f.calls.at(-1).options.body
  assert.equal(request.sourceId,ID);assert.deepEqual(request.mediaKeys,[media(601).key]);assert.equal(request.includeMain,false);assert.equal(request.targetDirectory,'D:\\chosen')
  assert.equal(f.state.createdTask.value.id,TASK);assert.equal(f.events.filter(event=>event[0]==='fieldsLocked').at(-1)[1],true)
})

test('shared source changes discard old post responses and keep manual-channel navigation separate from the saved draft',async t=>{
  const f=mount(t,'NewTransfer',{selection:selection()});const pending=deferred()
  f.expect('/transfers/download/preview',pending.promise);const reading=f.state.readPreview();await flush()
  f.props.selection=selection({entry:'MANUAL',source:'@synthetic',tags:'新标签',keywords:'新词'});await flush()
  pending.resolve(source());await reading
  assert.equal(f.state.preview.value,null);assert.equal(f.state.downloadSourceKind.value,'channel');assert.equal(f.state.busy.value,false)
  f.expect('/transfers/download/preview',source(),'POST',{link:'https://t.me/synthetic/10'});await f.state.openChannelPost('https://t.me/synthetic/10')
  assert.equal(f.state.downloadSourceKind.value,'post');assert.equal(f.props.selection.source,'@synthetic')
  assert.ok(f.nodes().some(node=>node.type==='button'&&node.props?.class==='btn browse-back'))
  f.state.backToChannel();await flush();assert.equal(f.state.downloadSourceKind.value,'channel');assert.equal(f.state.preview.value,null)
})

test('immutable shared type and directory updates preserve the post preview and channel selection while filter changes invalidate the scan',async t=>{
  const post=mount(t,'NewTransfer',{selection:selection()})
  post.expect('/transfers/download/preview',source());await post.state.readPreview();const loaded=post.state.preview.value
  post.props.selection=selection({types:['VIDEO'],targetDirectory:'D:\\updated',suggestedName:'新名称'});await flush()
  assert.equal(post.state.preview.value,loaded);assert.deepEqual(post.state.items.value.map(item=>item.type),['VIDEO']);assert.equal(post.state.targetDirectory.value,'D:\\updated');assert.equal(post.calls.length,1)
  const channel=mount(t,'DownloadChannelPreview',{selection:selection({entry:'CHANNEL',source:'-1001'})});await flush()
  channel.state.scan.value=scan();channel.state.selectedPosts.value=['selected-post'];const loadedScan=channel.state.scan.value
  channel.props.selection=selection({entry:'CHANNEL',source:'-1001',types:['PHOTO'],includeMain:true,targetDirectory:'D:\\updated',suggestedName:'新名称'});await flush()
  assert.equal(channel.state.scan.value,loadedScan);assert.deepEqual(channel.state.selectedPosts.value,['selected-post']);assert.equal(channel.calls.length,0)
  channel.props.selection=selection({entry:'CHANNEL',source:'-1001',tags:'新标签'});await flush()
  assert.equal(channel.state.scan.value,null);assert.deepEqual(channel.state.selectedPosts.value,[]);assert.equal(channel.calls.length,0)
})

test('uncertain single-post creation keeps its original operation and shared fields frozen through draft edits and connection loss',async t=>{
  const f=mount(t,'NewTransfer',{selection:selection()})
  f.expect('/transfers/download/preview',source());await f.state.readPreview()
  f.expect('/transfers/download/tasks',new Error('timeout'));await f.state.createDownload()
  const command=structuredClone(f.calls.at(-1).options.body)
  assert.equal(f.state.createUncertain.value,true);assert.deepEqual(f.events.filter(event=>event[0]==='locked').at(-1),['locked',true])
  f.props.selection=selection({entry:'MANUAL',source:'@other',types:['VIDEO'],targetDirectory:'D:\\other'});await flush()
  f.props.available=false;await flush();f.props.available=true;await flush()
  assert.equal(f.state.link.value,'https://t.me/synthetic/10');assert.equal(f.state.targetDirectory.value,'D:\\chosen');assert.deepEqual(f.state.selectedTypes.value,['DOCUMENT'])
  await f.state.createDownload();assert.equal(f.calls.length,2)
  f.expect('/transfers/download/tasks/by-operation/'+command.operationId,{task:task()},'GET');await f.state.lookupDownload()
  assert.equal(f.state.createdTask.value.id,TASK);assert.deepEqual(f.events.filter(event=>event[0]==='locked').at(-1),['locked',false]);assert.deepEqual(f.events.filter(event=>event[0]==='fieldsLocked').at(-1),['fieldsLocked',true])
  assert.equal(f.calls.filter(call=>call.path==='/transfers/download/tasks').length,1)
})

test('single-post connection interruption never releases the synchronous mutation lock before entering review',async t=>{
  const f=mount(t,'NewTransfer',{selection:selection()});f.expect('/transfers/download/preview',source());await f.state.readPreview()
  const pending=deferred();f.expect('/transfers/download/tasks',pending.promise);const creating=f.state.createDownload(),command=structuredClone(f.calls.at(-1).options.body)
  assert.equal(f.state.creating.value,true);const from=f.events.length
  f.props.available=false;await flush()
  assert.equal(f.state.createUncertain.value,true);assert.equal(f.state.creating.value,false)
  assert.ok(f.events.slice(from).every(event=>event[0]!=='locked'||event[1]===true),'the outer composer stays locked throughout interrupted creation')
  f.props.available=true;await flush();pending.resolve(task());await creating
  assert.equal(f.state.createdTask.value,null);assert.equal(f.state.createUncertain.value,true)
  f.expect('/transfers/download/tasks/by-operation/'+command.operationId,{task:task()},'GET');await f.state.lookupDownload()
  assert.equal(f.state.createdTask.value.id,TASK);assert.deepEqual(f.events.filter(event=>event[0]==='locked').at(-1),['locked',false])
})

test('shared channel source uses one channel discovery owner and sends unchanged tags and keywords to the scan API',async t=>{
  const f=mount(t,'DownloadChannelPreview',{selection:selection({entry:'CHANNEL',source:'-1001'})});await flush();assert.equal(f.calls.length,0)
  assert.equal(f.nodes().some(node=>node.type?.__name==='SourceChannelSelect'),false)
  f.expect('/transfers/download/channels/preview',scan(),'POST',{source:'-1001',tags:'旅行',keywords:'山'});await f.state.begin()
  assert.equal(f.state.scan.value.exhausted,true)
  f.props.selection=selection({entry:'MANUAL',source:'@other',tags:'新标签',keywords:'新词'});await flush()
  assert.equal(f.state.scan.value,null);assert.equal(f.state.tags.value,'新标签');assert.equal(f.state.keywords.value,'新词')
  f.expect('/transfers/download/channels/preview',scan({tags:['新标签'],keywords:['新词']}),'POST',{source:'@other',tags:'新标签',keywords:'新词'});await f.state.begin()
  assert.equal(f.calls.length,2);assert.ok(f.calls.every(call=>call.path==='/transfers/download/channels/preview'))
})

test('channel batch child receives the same shared draft and its frozen-field signal independently of mutation lock',async t=>{
  const f=mount(t,'DownloadChannelPreview',{selection:selection({entry:'CHANNEL',source:'-1001'})});await flush()
  const post={key:'post',accountId:'1',channelId:'-1001',mainMessageId:'10',title:'title',text:'旅行 山',mainCoverage:'COMPLETE',mainMedia:[],warnings:[]}
  f.state.scan.value=scan({scanned:1,matched:1,rows:[{match:'MATCH',postLink:'https://t.me/synthetic/10',comments:'NOT_READ',post}]});f.state.selectBatch()
  const child=f.nodes().find(node=>node.type?.__name==='DownloadBatchPreparation');assert.ok(child);assert.equal(child.props.selection,f.props.selection)
  child.props.onFieldsLocked(true);assert.equal(f.state.fieldsLocked.value,true);assert.equal(f.state.batchTransitionLocked.value,false)
  const frozen=JSON.parse(JSON.stringify(f.state.scan.value));f.props.selection=selection({entry:'MANUAL',source:'@other'});await flush()
  assert.deepEqual(f.state.scan.value,frozen);assert.equal(f.state.source.value,'-1001');assert.deepEqual(f.state.selectedPosts.value,['post'])
  child.props.onTransitionLocked(true);assert.deepEqual(f.events.filter(event=>event[0]==='transitionLocked').at(-1),['transitionLocked',true])
  f.props.available=false;await flush();assert.deepEqual(f.state.scan.value,frozen);assert.deepEqual(f.state.selectedPosts.value,['post'])
})

test('shared batch fields become the frozen preparation request and remain unchanged after an unknown create',async t=>{
  const f=mount(t,'DownloadBatchPreparation',{scanId:ID,scanRevision:3,postKeys:['post'],selection:selection({entry:'CHANNEL',source:'-1001',includeMain:true})})
  assert.equal(f.nodes().some(node=>node.type==='fieldset'),false)
  f.expect('/transfers/download/batches/preview',batch(),'POST',{scanId:ID,revision:3,postKeys:['post'],types:['DOCUMENT'],includeMain:true,targetDirectory:'D:\\chosen'})
  f.expect('/transfers/download/batches/preview/'+ID+'/next',batchDone(),'POST',{revision:1});await f.state.begin()
  assert.equal(f.state.preview.value.exhausted,true);assert.equal(f.state.fieldsLocked.value,true);assert.equal(f.state.transitionLocked.value,false)
  f.expect('/transfers/download/batches',new Error('timeout'));await f.state.create();const command=structuredClone(f.calls.at(-1).options.body)
  f.props.selection=selection({types:['PHOTO'],includeMain:false,targetDirectory:'D:\\other'});await flush()
  f.props.available=false;await flush();f.props.available=true;await flush()
  assert.deepEqual(f.state.types.value,['DOCUMENT']);assert.equal(f.state.includeMain.value,true);assert.equal(f.state.directory.value,'D:\\chosen');assert.equal(f.state.uncertain.value,true)
  await f.state.create();assert.equal(f.calls.length,3)
  f.expect('/transfers/download/batches/by-operation/'+command.operationId,{batch:receipt()},'GET');await f.state.lookup()
  assert.equal(f.state.created.value.id,receipt().id);assert.equal(f.state.transitionLocked.value,false);assert.equal(f.state.fieldsLocked.value,true)
  assert.equal(f.calls.filter(call=>call.path==='/transfers/download/batches').length,1)
})

test('empty shared comment types stay empty and block unsupported channel batch preparation without a request',async t=>{
  const f=mount(t,'DownloadBatchPreparation',{scanId:ID,scanRevision:3,postKeys:['post'],selection:selection({entry:'CHANNEL',types:[],includeMain:true})})
  await f.state.begin();assert.deepEqual(f.state.types.value,[]);assert.equal(f.calls.length,0);assert.equal(f.state.preview.value,null)
  assert.ok(f.nodes().some(node=>node.type==='p'&&node.children==='频道批量下载需至少选择一种评论媒体类型。'))
  f.props.selection.types=['PHOTO'];await flush();assert.deepEqual(f.state.types.value,['PHOTO'])
})

test('once-upload passes the shared upload draft and forwards field locks separately from transition locks',async t=>{
  const uploadSelection={link:'https://t.me/synthetic/10',sourceDirectory:'D:\\source',recursive:true,captionMode:'FILENAME'}
  const f=mount(t,'NewTransfer',{initialDirection:'upload',uploadSelection})
  const child=f.nodes().find(node=>node.type?.__name==='UploadPreparation');assert.ok(child);assert.equal(child.props.selection,f.props.uploadSelection)
  child.props.onFieldsLocked(true);assert.deepEqual(f.events.filter(event=>event[0]==='fieldsLocked').at(-1),['fieldsLocked',true]);assert.deepEqual(f.events.filter(event=>event[0]==='locked').at(-1),['locked',false])
  child.props.onTransitionLocked(true);assert.deepEqual(f.events.filter(event=>event[0]==='locked').at(-1),['locked',true])
})
