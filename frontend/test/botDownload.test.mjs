import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript, compileTemplate } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as bots from '../src/botDownload.ts'
import * as tasks from '../src/downloadTasks.ts'
import * as library from '../src/library.ts'
import * as creation from '../src/transferCreation.ts'

const BASE='/transfers/download/bots',ID='11111111-1111-4111-8111-111111111111',CHAT='9007199254740993'
const copy=value=>JSON.parse(JSON.stringify(value))
const flush=async()=>{await vue.nextTick();await new Promise(setImmediate)}
const deferred=()=>{let resolve;const promise=new Promise(done=>{resolve=done});return{promise,resolve}}
const media=()=>({key:'file-a',message:{chatId:CHAT,messageId:'1001',link:null},source:'BOT',type:'DOCUMENT',fileName:'sample.txt',size:16,caption:'sample'})
const button=()=>({id:'button-next',text:'下一组 (2/6)',kind:'NEXT',messageId:'1002',row:0,column:0,supported:true})
const wait=(overrides={})=>({waitSeconds:5,timeoutSeconds:120,elapsedMs:0,quietMs:0,remainingMs:120000,progressObserved:false,timedOut:false,...overrides})
function preview(overrides={}){const m=media();return{id:ID,revision:1,bot:{chatId:CHAT,botUserId:'123456',title:'Synthetic bot'},messages:[{id:'1001',text:'',media:[copy(m)],buttons:[]},{id:'1002',text:'已获取第1组文件（共6组）',media:[],buttons:[button()]}],media:[m],controlMessageId:'1002',recommendedButtonId:'button-next',paginationStatus:'MORE',currentPage:1,totalPages:6,state:'READY',warning:null,nextHistoryCursor:null,historyEnd:true,...overrides}}
function task(){return{id:'22222222-2222-4222-8222-222222222222',title:'Synthetic bot',channelTitle:'Synthetic bot',state:'QUEUED',pauseRequested:false,targetDirectory:'D:\\chosen',scope:'OBSERVED_SELECTION',total:1,completed:0,skipped:0,totalBytes:16,error:null,createdAt:1,updatedAt:1,duplicatePolicy:'KEEP_DUPLICATES_V2',duplicates:0,duplicateDirectory:'D:\\chosen\\重复媒体',items:[{id:'item',fileName:'sample.txt',type:'DOCUMENT',state:'QUEUED',size:16,downloadedBytes:0,writtenBytes:0,outputPath:'D:\\chosen\\sample.txt',error:null,history:null,sourceLink:null,duplicate:null,manifestPath:null}]}}
class ApiError extends Error{constructor(message,status){super(message);this.status=status}}
const compiled=new Map()
function mount(t,name='BotDownload',overrides={},runtime={}){
  if(!compiled.has(name)){
    const {descriptor,errors}=parse(readFileSync(new URL(`../src/components/${name}.vue`,import.meta.url),'utf8'),{filename:name+'.vue'});assert.deepEqual(errors,[])
    const script=compileScript(descriptor,{id:'bot-'+name}),template=compileTemplate({source:descriptor.template.content,filename:name+'.vue',id:'bot-'+name,compilerOptions:{bindingMetadata:script.bindings,expressionPlugins:['typescript']}});assert.deepEqual(template.errors,[])
    const transpile=code=>ts.transpileModule(code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
    compiled.set(name,{code:transpile(script.content),render:transpile(template.code)})
  }
  const props=vue.reactive({authorized:true,available:true,accountId:'1',...overrides}),calls=[],steps=[],failures=[],events=[],hooks=[],stubs=new Map(),timers=new Map()
  let now=100_000,timerSerial=0,activeRequests=0,maxRequests=0
  class ClockDate extends Date{static now(){return now}}
  const setTimer=(callback,delay)=>{const token=++timerSerial;timers.set(token,{callback,due:now+delay});return token},clearTimer=token=>timers.delete(token)
  const api=async(path,options={})=>{calls.push({path,options});const expected=steps.shift();try{assert.ok(expected,'unexpected '+path);assert.equal(path,expected.path);assert.equal(options.method??'GET',expected.method??'GET')}catch(error){failures.push(error);throw error}activeRequests++;maxRequests=Math.max(maxRequests,activeRequests);try{const value=await expected.value;if(value instanceof Error)throw value;return value}finally{activeRequests--}}
  const botModule=Object.hasOwn(runtime,'operationCrypto')?{...bots,botOperationId:()=>bots.botOperationId(runtime.operationCrypto)}:bots
  const modules={vue:{...vue,onBeforeUnmount:hook=>hooks.push(hook),withCtx:fn=>fn,withDirectives:node=>node},'../api':{api,ApiError},'../botDownload':botModule,'../downloadTasks':tasks,'../library':library,'../transferCreation':creation}
  const require=name=>{if(name.endsWith('.vue')){const key=name.split('/').at(-1).replace('.vue','');if(!stubs.has(key))stubs.set(key,{__name:key});return{__esModule:true,default:stubs.get(key)}}assert.ok(Object.hasOwn(modules,name),name);return modules[name]}
  const code=compiled.get(name),module={exports:{}},render={exports:{}}
  new Function('require','module','exports','setTimeout','clearTimeout','Date',code.code)(require,module,module.exports,setTimer,clearTimer,ClockDate);new Function('require','module','exports',code.render)(require,render,render.exports)
  const scope=vue.effectScope(),state=scope.run(()=>module.exports.default.setup(props,{expose(){},emit:(...event)=>events.push(event)}))
  let destroyed=false;const destroy=()=>{if(!destroyed){destroyed=true;hooks.forEach(hook=>hook());scope.stop()}}
  t.after(()=>{destroy();assert.deepEqual(failures,[]);assert.equal(steps.length,0);assert.equal(timers.size,0)})
  const advance=async milliseconds=>{const target=now+milliseconds;let guard=0;while(true){const next=[...timers].sort((a,b)=>a[1].due-b[1].due).find(([,timer])=>timer.due<=target);if(!next)break;assert.ok(++guard<=200,'timer loop is not bounded');now=next[1].due;timers.delete(next[0]);next[1].callback();await flush()}now=target;await flush()}
  return{state,props,calls,events,timers,advance,get maxRequests(){return maxRequests},destroy,expect:(path,value,method='GET')=>steps.push({path,value,method}),render:()=>render.exports.render({},[],props,vue.proxyRefs(state),{},{})}
}
function nodes(node){if(!node||typeof node!=='object')return[];if(Array.isArray(node))return node.flatMap(nodes);const children=Array.isArray(node.children)?node.children:node.children&&typeof node.children==='object'?Object.values(node.children).filter(value=>typeof value==='function').flatMap(slot=>slot()):[];return[node,...nodes(children)]}
const component=(tree,name)=>nodes(tree).find(node=>node.type?.__name===name)
const text=node=>typeof node==='string'?node:Array.isArray(node)?node.map(text).join(''):node&&typeof node==='object'?text(node.children):''
async function opened(t,view=preview(),runtime={}){const f=mount(t,'BotDownload',{},runtime);f.state.source.value='@synthetic_bot';f.expect(BASE+'/preview',view,'POST');await f.state.open();return f}

test('bot preview accepts string Int64 identities and no callback payload reaches the public DTO',()=>{
  assert.equal(bots.validBotPreview(preview()),true)
  for(const mutate of [v=>v.bot.chatId=Number(CHAT),v=>v.media[0].message.chatId='9',v=>v.messages[1].buttons[0].data='private callback',v=>v.messages[1].buttons[0].url='https://ad.example',v=>v.recommendedButtonId='missing',v=>v.controlMessageId='missing',v=>v.messages[1].buttons[0].supported=false]){const value=preview();mutate(value);assert.equal(bots.validBotPreview(value),false)}
})
test('bot preview validates bounded messages, button positions, media membership and final history identity',()=>{
  for(const mutate of [v=>v.messages=Array(51).fill(v.messages[0]),v=>v.media=Array(501).fill(v.media[0]),v=>v.messages[1].buttons.push({...button(),id:'duplicate-position'}),v=>v.messages[0].media[0].size=17,v=>v.nextHistoryCursor='1001',v=>v.historyEnd=false,v=>v.currentPage=7,v=>v.bot.botUserId='9223372036854775808']){const value=preview();mutate(value);assert.equal(bots.validBotPreview(value),false)}
  const value=preview({paginationStatus:'END',currentPage:6,recommendedButtonId:null,historyEnd:true,nextHistoryCursor:null});assert.equal(bots.validBotPreview(value),true)
})
test('bot source reads only on the explicit open action and does not select or download any media automatically',async t=>{
  const f=mount(t);await flush();assert.equal(f.calls.length,0)
  f.state.source.value='@synthetic_bot';f.expect(BASE+'/preview',preview(),'POST');await f.state.open()
  assert.deepEqual(f.calls[0].options.body,{source:'@synthetic_bot'});assert.deepEqual(f.state.selected.value,[]);assert.equal(f.state.preview.value.media.length,1)
  assert.match(text(f.render()),/列表.*卡片/s);assert.equal(f.calls.length,1)
})
test('explicitly rereading the same bot clears the old resource before the request and never restores it on failure',async t=>{
  const f=await opened(t);f.state.select('file-a');f.state.manualButton.value=button();f.state.createdTask.value=task();f.state.pressUnknown.value=true;f.state.waitUnknown.value=true;f.state.localTimedOut.value=true
  const response=deferred();f.expect(BASE+'/preview',response.promise,'POST');const pending=f.state.open()
  assert.equal(f.state.source.value,'@synthetic_bot');assert.equal(f.state.preview.value,null);assert.deepEqual(f.state.selected.value,[])
  assert.equal(f.state.manualButton.value,null);assert.equal(f.state.createdTask.value,null);assert.equal(f.state.pressUnknown.value,false);assert.equal(f.state.waitUnknown.value,false);assert.equal(f.state.localTimedOut.value,false)
  response.resolve(new ApiError('请先在Telegram重新打开当前资源',400));await pending
  assert.equal(f.state.preview.value,null);assert.deepEqual(f.state.selected.value,[]);assert.match(f.state.error.value,/重新打开当前资源/)
  assert.doesNotMatch(text(f.render()),/sample\.txt|已获取第1组文件/)
})
test('an unknown task creation still blocks resource rereads without discarding the frozen operation or its selected media',async t=>{
  const f=await opened(t);f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen';f.expect(BASE+'/tasks',new Error('lost creation response'),'POST');await f.state.create()
  const request=copy(f.state.createRequest.value),prior=copy(f.state.preview.value)
  await f.state.open();assert.equal(f.calls.length,2);assert.deepEqual(f.state.createRequest.value,request);assert.deepEqual(f.state.preview.value,prior);assert.deepEqual(f.state.selected.value,['file-a'])
})
test('one next-page click submits exactly once and manual refresh uses only GET',async t=>{
  const f=await opened(t);f.expect(BASE+'/preview/'+ID+'/press',preview({revision:2,state:'WAITING'}),'POST')
  await f.state.press(button());await f.state.press(button());await flush()
  assert.equal(f.calls.length,2);assert.equal(f.state.canPress.value,false)
  assert.ok(bots.botUuid(f.calls[1].options.body.operationId));assert.equal(f.calls[1].options.body.revision,1)
  f.expect(BASE+'/preview/'+ID,preview({revision:3,currentPage:2}));await f.state.read('refresh');assert.equal(f.state.canPress.value,true)
  assert.equal(f.calls.filter(call=>call.options.method==='POST').length,2)
})
test('a lost pagination acknowledgement blocks further clicks until GET verifies it',async t=>{
  const f=await opened(t);f.expect(BASE+'/preview/'+ID+'/press',new Error('lost acknowledgement'),'POST');await f.state.press(button())
  assert.equal(f.state.pressUnknown.value,true);await f.state.press(button());await f.state.read('older');await f.state.read('control','1002');assert.equal(f.calls.length,2)
  f.expect(BASE+'/preview/'+ID,preview());await f.state.read('refresh');assert.equal(f.state.pressUnknown.value,true,'unchanged snapshot cannot prove the old callback completed')
  f.expect(BASE+'/preview/'+ID,preview({revision:3,currentPage:2}));await f.state.read('refresh');assert.equal(f.state.pressUnknown.value,false)
})
test('unsupported callbacks cannot be invoked and manual choices require a second explicit action',async t=>{
  const f=await opened(t);const unsupported={...button(),supported:false,kind:'UNSUPPORTED'}
  f.state.chooseButton(unsupported);await f.state.press(unsupported);assert.equal(f.state.manualButton.value,null);assert.equal(f.calls.length,1)
  f.state.chooseButton(button());assert.equal(f.state.manualButton.value.id,'button-next');assert.equal(f.calls.length,1)
  f.expect(BASE+'/preview/'+ID+'/press',preview({revision:2,currentPage:2}),'POST');await f.state.press(f.state.manualButton.value);assert.equal(f.calls.length,2)
})
test('media selection caps at 100, keeps choices on refresh, and leaves new media unselected',async t=>{
  const value=preview();value.media=Array.from({length:101},(_,i)=>({...media(),key:'m'+i,message:{chatId:CHAT,messageId:String(2000+i),link:null}}));value.messages[0].media=[]
  const f=await opened(t,value);f.state.selectAll();assert.equal(f.state.selected.value.length,100);f.state.select('m100');assert.equal(f.state.selected.value.length,100)
  f.state.select('m0');f.state.select('m100');assert.equal(f.state.selected.value.includes('m100'),true)
  f.expect(BASE+'/preview/'+ID,{...value,revision:2});await f.state.read('refresh');assert.equal(f.state.selected.value.length,100)
})
test('download creation freezes the selected revision/directory and a lost result only queries its original operation',async t=>{
  const f=await opened(t);f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen';f.expect(BASE+'/tasks',new Error('lost task response'),'POST');await f.state.create()
  const request=copy(f.state.createRequest.value);assert.ok(request);assert.equal(request.revision,1);assert.deepEqual(request.mediaKeys,['file-a']);assert.ok(f.events.some(event=>event[0]==='locked'&&event[1]===true))
  await f.state.create();f.state.select('file-a');assert.equal(f.calls.length,2);assert.deepEqual(f.state.selected.value,['file-a'])
  f.expect(BASE+'/tasks/by-operation/'+request.operationId,{task:null});await f.state.lookup();assert.deepEqual(f.state.createRequest.value,request)
  f.expect(BASE+'/tasks/by-operation/'+request.operationId,{task:task()});await f.state.lookup();assert.equal(f.state.createdTask.value.id,task().id);assert.equal(f.state.createRequest.value,null)
  assert.equal(f.calls.filter(call=>call.path===BASE+'/tasks').length,1);assert.deepEqual(f.events.at(-1),['locked',false])
  f.state.continueBrowsing();assert.equal(f.state.createdTask.value,null);assert.deepEqual(f.state.selected.value,[]);assert.equal(f.state.targetDirectory.value,'D:\\chosen');assert.equal(f.calls.length,4)
})
test('a definite rejected task request is editable, while malformed success retains the frozen lookup identity',async t=>{
  const f=await opened(t);f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen';f.expect(BASE+'/tasks',new ApiError('invalid directory',400),'POST');await f.state.create();assert.equal(f.state.createRequest.value,null)
  f.expect(BASE+'/tasks',{...task(),targetDirectory:'D:\\other'},'POST');await f.state.create();assert.ok(f.state.createRequest.value);assert.equal(f.state.createdTask.value,null)
})
test('account changes and unmount discard late reads and clear old robot identities',async t=>{
  const f=mount(t),response=deferred();f.state.source.value='@synthetic_bot';f.expect(BASE+'/preview',response.promise,'POST');const pending=f.state.open()
  f.props.accountId='2';await flush();response.resolve(preview());await pending;assert.equal(f.state.preview.value,null);assert.deepEqual(f.state.selected.value,[])
  const second=deferred();f.expect(BASE+'/preview',second.promise,'POST');const next=f.state.open();f.destroy();second.resolve(preview());await next;assert.equal(f.state.preview.value,null)
})
test('brief local unavailability preserves source, media choice and unresolved task operation',async t=>{
  const f=await opened(t);f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen';f.expect(BASE+'/tasks',new Error('offline'),'POST');await f.state.create();const before=copy(f.state.createRequest.value)
  f.props.available=false;await flush();assert.equal(f.state.source.value,'@synthetic_bot');assert.deepEqual(f.state.selected.value,['file-a']);assert.deepEqual(f.state.createRequest.value,before)
  await f.state.lookup();assert.equal(f.calls.length,2);f.props.available=true;await flush();assert.equal(f.calls.length,2,'recovery does not automatically submit or poll')
})
test('the resource browser exposes no old-history or controller-switch route and refreshes only the current resource',async t=>{
  const f=await opened(t);const tree=f.render()
  assert.match(text(tree),/读取当前页／组.*可从任意页开始.*只追加本次媒体/s);assert.match(text(tree),/本次资源消息/)
  assert.doesNotMatch(text(tree),/第一页/)
  assert.doesNotMatch(text(tree),/读取更早消息|分页消息|可选择分页消息/)
  assert.equal(nodes(tree).filter(node=>node.type==='select').length,0)
  await f.state.read('older');await f.state.read('control','1002');assert.equal(f.calls.length,1,'removed routes are also rejected at runtime')
  f.expect(BASE+'/preview/'+ID,preview({revision:2,paginationStatus:'END',recommendedButtonId:null,currentPage:6}));await f.state.read('refresh')
  assert.equal(f.calls.length,2);assert.equal(f.calls[1].options.method,undefined);assert.equal(f.calls.some(call=>call.path.endsWith('/press')),false)
  assert.match(text(f.render()),/机器人已显示最后一页／组/)
})
test('robot downloads use the existing composer and cannot silently become an automatic rule',async t=>{
  const f=mount(t,'TransferComposer',{initialMode:'RULE'});assert.ok(component(f.render(),'DownloadRules'))
  f.state.selectDownloadSource('BOT');await flush();let tree=f.render();assert.equal(f.state.mode.value,'ONCE');assert.ok(component(tree,'BotDownload'));assert.equal(component(tree,'DownloadCreationFields'),undefined);assert.equal(component(tree,'DownloadRules'),undefined);assert.equal(component(tree,'NewTransfer'),undefined)
  f.state.selectMode('RULE');assert.equal(f.state.mode.value,'ONCE')
  const bot=component(tree,'BotDownload');bot.props.onLocked(true);f.state.selectDownloadSource('POST');f.state.selectDirection('upload');f.state.close();assert.equal(f.state.downloadSource.value,'BOT');assert.equal(f.state.direction.value,'download');assert.deepEqual(f.events,[])
  bot.props.onLocked(false);bot.props.onTasks('download');assert.deepEqual(f.events.at(-1),['tasks','download'])
  f.state.selectDownloadSource('POST');await flush();tree=f.render();assert.ok(component(tree,'DownloadCreationFields'));assert.ok(component(tree,'NewTransfer'));assert.equal(component(tree,'BotDownload'),undefined)
})

test('wait settings and metadata have explicit bounds and cannot advertise an invalid timeout',()=>{
  assert.equal(bots.validBotWaitSettings(5,120),true);assert.equal(bots.validBotWaitSettings(60,120),true)
  for(const settings of [[1,120],[61,120],[5,14],[5,301],[60,119],[2.5,30],['5',120]])assert.equal(bots.validBotWaitSettings(...settings),false)
  assert.equal(bots.validBotPreview(preview({wait:wait()})),true)
  for(const value of [wait({remainingMs:120001}),wait({timedOut:true}),wait({elapsedMs:10,quietMs:11}),wait({progressObserved:'yes'}),wait({waitSeconds:60,timeoutSeconds:100})])assert.equal(bots.validBotPreview(preview({wait:value})),false)
  assert.equal(bots.botObservationDelay(5,1000,100000,true),4000);assert.equal(bots.botObservationDelay(5,6000,100000,true),0)
  assert.equal(bots.botObservationDelay(5,6000,500,false),500)
})

test('reply observation honors the first buffer and serial local GETs without clearing media or disabling selection',async t=>{
  const f=await opened(t);f.state.select('file-a');f.expect(BASE+'/preview/'+ID+'/press',preview({revision:2,state:'WAITING',wait:wait()}),'POST');await f.state.press(button())
  assert.equal(f.state.observing.value,true);assert.equal(f.state.canPress.value,false)
  assert.equal(f.calls[1].options.body.waitSeconds,5);assert.equal(f.calls[1].options.body.timeoutSeconds,120)
  await f.advance(4999);assert.equal(f.calls.length,2)
  const response=deferred();f.expect(BASE+'/preview/'+ID,response.promise);await f.advance(1)
  assert.equal(f.state.previewReadPending.value,true);assert.equal(f.state.busy.value,'');assert.equal(f.state.inputLocked.value,false)
  assert.equal(f.state.preview.value.media.length,1);assert.deepEqual(f.state.selected.value,['file-a'])
  await f.state.read('refresh');assert.equal(f.calls.length,3,'manual GET cannot overlap the scheduled read')
  response.resolve(preview({revision:3,state:'WAITING',wait:wait({elapsedMs:5000,quietMs:3000,remainingMs:115000,progressObserved:true})}));await flush()
  await f.advance(1999);assert.equal(f.calls.length,3)
  f.expect(BASE+'/preview/'+ID,preview({revision:4,state:'READY',wait:null}));await f.advance(1)
  assert.equal(f.state.observing.value,false);assert.equal(f.timers.size,0);assert.equal(f.maxRequests,1);assert.equal(f.state.canPress.value,true)
  assert.deepEqual(f.state.selected.value,['file-a']);await f.advance(300000);assert.equal(f.calls.length,4)
})

test('stopping observation cancels later GETs and discards its in-flight READY response',async t=>{
  const f=await opened(t);f.expect(BASE+'/preview/'+ID+'/press',preview({revision:2,state:'WAITING',wait:wait()}),'POST');await f.state.press(button())
  const response=deferred();f.expect(BASE+'/preview/'+ID,response.promise);await f.advance(5000);f.state.stopWaiting()
  response.resolve(preview({revision:3,state:'READY',wait:null}));await flush()
  assert.equal(f.state.preview.value.revision,2);assert.equal(f.state.preview.value.state,'WAITING');assert.equal(f.state.observing.value,false)
  await f.advance(300000);assert.equal(f.calls.length,3);assert.equal(f.timers.size,0);assert.equal(f.state.canPress.value,false)
})

test('the total timeout stops automatic GETs and explicit continuation restarts waiting without repeating a callback',async t=>{
  const f=await opened(t);f.state.waitSeconds.value=2;f.state.timeoutSeconds.value=15
  f.expect(BASE+'/preview/'+ID+'/press',preview({revision:2,state:'WAITING',wait:wait({waitSeconds:2,timeoutSeconds:15,remainingMs:15000})}),'POST');await f.state.press(button())
  for(let seconds=2;seconds<=14;seconds+=2)f.expect(BASE+'/preview/'+ID,preview({revision:2,state:'WAITING',wait:wait({waitSeconds:2,timeoutSeconds:15,elapsedMs:seconds*1000,quietMs:seconds*1000,remainingMs:(15-seconds)*1000})}))
  await f.advance(15000);assert.equal(f.calls.length,9);assert.equal(f.state.waitingExpired.value,true);assert.equal(f.state.observing.value,false);assert.equal(f.timers.size,0)
  f.expect(BASE+'/preview/'+ID,preview({revision:3,state:'WAITING',wait:wait({waitSeconds:2,timeoutSeconds:15,elapsedMs:15000,quietMs:15000,remainingMs:0,timedOut:true})}));await f.state.read('refresh')
  assert.equal(f.state.canPress.value,false);assert.equal(f.state.canContinueWait.value,true)
  f.expect(BASE+'/preview/'+ID+'/wait',preview({revision:4,state:'WAITING',wait:wait({waitSeconds:2,timeoutSeconds:15,remainingMs:15000})}),'POST');await f.state.continueWait()
  assert.equal(f.state.observing.value,true);assert.equal(f.state.waitingExpired.value,false)
  assert.deepEqual(f.calls.at(-1).options.body,{revision:3,waitSeconds:2,timeoutSeconds:15})
  assert.equal(f.calls.filter(call=>call.path.endsWith('/press')).length,1);f.state.stopWaiting()
})

test('a lost wait acknowledgement is only resolved by GET, including a newer READY response with no wait metadata',async t=>{
  const f=await opened(t);f.expect(BASE+'/preview/'+ID+'/press',preview({revision:2,state:'WAITING',wait:wait()}),'POST');await f.state.press(button());f.state.stopWaiting()
  f.expect(BASE+'/preview/'+ID+'/wait',new Error('lost wait response'),'POST');await f.state.continueWait()
  assert.equal(f.state.waitUnknown.value,true);assert.equal(f.state.canContinueWait.value,false);assert.equal(f.timers.size,0)
  await f.state.continueWait();await f.advance(300000);assert.equal(f.calls.length,3)
  f.expect(BASE+'/preview/'+ID,preview({revision:2,state:'READY',wait:null}));await f.state.read('refresh');assert.equal(f.state.waitUnknown.value,true,'the same revision cannot unlock the unconfirmed continuation')
  f.expect(BASE+'/preview/'+ID,preview({revision:4,state:'READY',wait:null}));await f.state.read('refresh')
  assert.equal(f.state.waitUnknown.value,false);assert.equal(f.state.canPress.value,true);assert.equal(f.calls.filter(call=>call.path.endsWith('/wait')).length,1)
})

test('a definitive 4xx wait rejection stays editable and does not clear the older uncertain pagination intent',async t=>{
  const f=await opened(t);f.expect(BASE+'/preview/'+ID+'/press',new Error('lost callback response'),'POST');await f.state.press(button());f.state.stopWaiting()
  assert.equal(f.state.pressUnknown.value,true)
  f.expect(BASE+'/preview/'+ID+'/wait',new ApiError('revision changed',400),'POST');await f.state.continueWait()
  assert.equal(f.state.waitUnknown.value,false);assert.equal(f.state.pressUnknown.value,true);assert.equal(f.state.canPress.value,false);assert.equal(f.state.canContinueWait.value,true)
  assert.equal(f.timers.size,0);assert.equal(f.calls.filter(call=>call.path.endsWith('/press')).length,1)
})

test('observation errors and brief unavailability stop timers while retaining the preview, selection and draft',async t=>{
  const f=await opened(t);f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen'
  f.expect(BASE+'/preview/'+ID+'/press',preview({revision:2,state:'WAITING',wait:wait()}),'POST');await f.state.press(button())
  f.expect(BASE+'/preview/'+ID,new Error('metadata read failed'));await f.advance(5000)
  assert.equal(f.state.observing.value,false);assert.equal(f.timers.size,0);assert.match(f.state.error.value,/metadata read failed/)
  f.props.available=false;await flush();f.props.available=true;await flush();await f.advance(300000)
  assert.deepEqual(f.state.selected.value,['file-a']);assert.equal(f.state.targetDirectory.value,'D:\\chosen');assert.equal(f.calls.length,3)
})

test('offline, source changes and disposal invalidate pending observation reads without an automatic restart',async t=>{
  const f=await opened(t);f.expect(BASE+'/preview/'+ID+'/press',preview({revision:2,state:'WAITING',wait:wait()}),'POST');await f.state.press(button())
  const response=deferred();f.expect(BASE+'/preview/'+ID,response.promise);await f.advance(5000);f.props.available=false
  assert.equal(f.state.observing.value,false);response.resolve(preview({revision:3,state:'READY',wait:null}));await flush()
  assert.equal(f.state.preview.value.revision,2);f.props.available=true;await flush();assert.equal(f.timers.size,0)
  f.expect(BASE+'/preview/'+ID+'/wait',preview({revision:4,state:'WAITING',wait:wait()}),'POST');await f.state.continueWait()
  f.state.source.value='@different_bot';await flush();assert.equal(f.state.observing.value,false);assert.equal(f.state.preview.value,null)
  await f.advance(300000);assert.equal(f.calls.length,4)
})

test('manual refresh between scheduled reads remains serial and preserves the original total deadline',async t=>{
  const f=await opened(t);f.state.waitSeconds.value=2;f.state.timeoutSeconds.value=15
  f.expect(BASE+'/preview/'+ID+'/press',preview({revision:2,state:'WAITING',wait:wait({waitSeconds:2,timeoutSeconds:15,remainingMs:15000})}),'POST');await f.state.press(button())
  await f.advance(1000);f.expect(BASE+'/preview/'+ID,preview({revision:3,state:'WAITING',wait:wait({waitSeconds:2,timeoutSeconds:15,elapsedMs:1000,quietMs:1000,remainingMs:14000})}));await f.state.read('refresh')
  assert.equal(f.state.observing.value,true);await f.advance(1999);assert.equal(f.calls.length,3)
  f.expect(BASE+'/preview/'+ID,preview({revision:4,state:'READY',wait:null}));await f.advance(1)
  assert.equal(f.maxRequests,1);assert.equal(f.state.observing.value,false);assert.equal(f.calls.filter(call=>call.path.endsWith('/press')).length,1)
})

test('an automatic read returned after its observation deadline cannot unlock another pagination action',async t=>{
  const f=await opened(t);f.state.waitSeconds.value=2;f.state.timeoutSeconds.value=15
  f.expect(BASE+'/preview/'+ID+'/press',preview({revision:2,state:'WAITING',wait:wait({waitSeconds:2,timeoutSeconds:15,remainingMs:15000})}),'POST');await f.state.press(button())
  const response=deferred();f.expect(BASE+'/preview/'+ID,response.promise);await f.advance(2000);await f.advance(14000)
  assert.equal(f.state.observing.value,false,'the deadline stops the window even while a metadata GET is still pending')
  assert.equal(f.state.waitingExpired.value,true)
  response.resolve(preview({revision:3,state:'READY',wait:null}));await flush()
  assert.equal(f.state.preview.value.state,'WAITING');assert.equal(f.state.waitingExpired.value,true);assert.equal(f.state.observing.value,false);assert.equal(f.state.canPress.value,false)
})

test('manual refresh during observation retains the original deadline and discards its late READY response',async t=>{
  const f=await opened(t);f.state.select('file-a');f.state.waitSeconds.value=2;f.state.timeoutSeconds.value=15
  f.expect(BASE+'/preview/'+ID+'/press',preview({revision:2,state:'WAITING',wait:wait({waitSeconds:2,timeoutSeconds:15,remainingMs:15000})}),'POST');await f.state.press(button())
  await f.advance(1000);const response=deferred();f.expect(BASE+'/preview/'+ID,response.promise);const pending=f.state.read('refresh')
  assert.equal(f.state.observing.value,true);assert.equal(f.state.previewReadPending.value,true)
  await f.advance(14000);assert.equal(f.state.observing.value,false);assert.equal(f.state.waitingExpired.value,true)
  response.resolve(preview({revision:3,state:'READY',wait:null}));await pending
  assert.equal(f.state.preview.value.revision,2);assert.equal(f.state.preview.value.state,'WAITING');assert.equal(f.state.canPress.value,false)
  assert.deepEqual(f.state.selected.value,['file-a']);assert.equal(f.timers.size,0);assert.equal(f.maxRequests,1)
})

test('a failed manual refresh within an observation window immediately stops its remaining deadline timer',async t=>{
  const f=await opened(t);f.expect(BASE+'/preview/'+ID+'/press',preview({revision:2,state:'WAITING',wait:wait()}),'POST');await f.state.press(button())
  await f.advance(1000);f.expect(BASE+'/preview/'+ID,new Error('manual read failed'));await f.state.read('refresh')
  assert.equal(f.state.observing.value,false);assert.equal(f.timers.size,0);assert.equal(f.state.preview.value.revision,2)
  assert.match(f.state.error.value,/manual read failed/);await f.advance(300000);assert.equal(f.calls.length,3)
})

test('a manual GET rejection invalidates old-resource media and controls while preserving source and output drafts',async t=>{
  const f=await opened(t);f.state.select('file-a');f.state.manualButton.value=button();f.state.targetDirectory.value='D:\\chosen'
  f.expect(BASE+'/preview/'+ID,new ApiError('机器人已收到另一次请求，请重新打开当前资源再读取',400));await f.state.read('refresh')
  assert.equal(f.state.preview.value,null);assert.deepEqual(f.state.selected.value,[]);assert.equal(f.state.manualButton.value,null)
  assert.equal(f.state.source.value,'@synthetic_bot');assert.equal(f.state.targetDirectory.value,'D:\\chosen');assert.equal(f.state.canPress.value,false)
  assert.match(f.state.error.value,/另一次请求/);assert.doesNotMatch(text(f.render()),/sample\.txt/)
  assert.equal(f.state.busy.value,'');assert.equal(f.timers.size,0);await f.advance(300000);assert.equal(f.calls.length,2)
})

test('an automatic GET 400 clears the invalid preview and both timers, while a 5xx retains current media',async t=>{
  for(const status of [400,503]){
    const f=await opened(t);f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen'
    f.expect(BASE+'/preview/'+ID+'/press',preview({revision:2,state:'WAITING',wait:wait()}),'POST');await f.state.press(button())
    f.expect(BASE+'/preview/'+ID,new ApiError(status===400?'预览已过期，请重新读取':'temporarily unavailable',status));await f.advance(5000)
    assert.equal(f.state.observing.value,false);assert.equal(f.timers.size,0);assert.equal(f.state.source.value,'@synthetic_bot');assert.equal(f.state.targetDirectory.value,'D:\\chosen')
    if(status===400){assert.equal(f.state.preview.value,null);assert.deepEqual(f.state.selected.value,[])}
    else{assert.equal(f.state.preview.value.revision,2);assert.deepEqual(f.state.selected.value,['file-a'])}
    await f.advance(300000);assert.equal(f.calls.length,3)
  }
})

test('observed media can be downloaded regardless of pagination waiting, review, timeout or unknown callback state',async t=>{
  for(const state of ['WAITING','NEEDS_REVIEW','TIMED_OUT','PRESS_UNKNOWN','WAIT_UNKNOWN']){
    const view=preview({state:state==='NEEDS_REVIEW'?'NEEDS_REVIEW':'WAITING',wait:wait({timedOut:state==='TIMED_OUT',remainingMs:state==='TIMED_OUT'?0:120000})})
    const f=await opened(t,view);f.state.pressUnknown.value=state==='PRESS_UNKNOWN';f.state.waitUnknown.value=state==='WAIT_UNKNOWN'
    assert.equal(f.state.canCreate.value,false);assert.match(f.state.creationHint.value,/选择/)
    f.state.select('file-a');assert.equal(f.state.canCreate.value,false);assert.match(f.state.creationHint.value,/保存目录/)
    f.state.targetDirectory.value='D:\\chosen';f.state.waitSeconds.value=1
    assert.equal(f.state.canCreate.value,true,state);assert.equal(f.state.canPress.value,false,state)
    f.expect(BASE+'/tasks',task(),'POST');await f.state.create()
    assert.equal(f.calls.length,2,'creating from an accepted snapshot needs no extra metadata request')
    assert.equal(f.calls[1].options.body.revision,1);assert.deepEqual(f.calls[1].options.body.mediaKeys,['file-a'])
    f.state.continueBrowsing();assert.equal(f.state.canPress.value,false,'downloading never unlocks the unresolved pagination operation')
    assert.equal(f.state.pressUnknown.value,state==='PRESS_UNKNOWN');assert.equal(f.state.waitUnknown.value,state==='WAIT_UNKNOWN')
  }
})

test('creating a download waits for an in-flight observation, freezes choices and uses its accepted appended revision',async t=>{
  const f=await opened(t);f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen'
  f.expect(BASE+'/preview/'+ID+'/press',preview({revision:2,state:'WAITING',wait:wait()}),'POST');await f.state.press(button())
  const response=deferred();f.expect(BASE+'/preview/'+ID,response.promise);await f.advance(5000)
  assert.equal(f.state.previewReadPending.value,true);assert.equal(f.state.canCreate.value,true)
  const pending=f.state.create();assert.equal(f.state.preparingCreate.value,true);assert.equal(f.state.inputLocked.value,true)
  assert.equal(f.state.canCreate.value,false);assert.match(f.state.creationHint.value,/正在核对已选媒体/)
  assert.match(text(f.render()),/正在核对已选媒体/);assert.equal(f.timers.size,0);assert.deepEqual(f.events.at(-1),['locked',true])
  f.state.select('file-a');f.state.selectAll();await f.state.press(button());await f.state.open()
  assert.deepEqual(f.state.selected.value,['file-a']);assert.equal(f.calls.length,3)
  const added={...media(),key:'file-b',message:{chatId:CHAT,messageId:'1003',link:null}},value=preview({revision:3,state:'WAITING',wait:wait({elapsedMs:5000,remainingMs:115000})})
  value.media.push(added);f.expect(BASE+'/tasks',task(),'POST');response.resolve(value);await pending
  assert.equal(f.state.preview.value.media.length,2);assert.deepEqual(f.state.selected.value,['file-a'])
  assert.equal(f.calls[3].options.body.revision,3);assert.deepEqual(f.calls[3].options.body.mediaKeys,['file-a'])
  assert.equal(f.calls[3].options.body.targetDirectory,'D:\\chosen');assert.equal(f.maxRequests,1);assert.equal(f.timers.size,0)
  assert.equal(f.state.preparingCreate.value,false);assert.equal(f.state.observing.value,false)
  f.state.continueBrowsing();assert.equal(f.state.canPress.value,false);await f.advance(300000);assert.equal(f.calls.length,4)
})

test('a pending manual refresh and download remain serial and submit only its final accepted revision',async t=>{
  const f=await opened(t);f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen'
  const response=deferred();f.expect(BASE+'/preview/'+ID,response.promise);const refresh=f.state.read()
  assert.equal(f.state.busy.value,'refresh');assert.equal(f.state.canCreate.value,true)
  const pending=f.state.create();f.expect(BASE+'/tasks',task(),'POST');response.resolve(preview({revision:2,state:'NEEDS_REVIEW'}))
  await Promise.all([refresh,pending]);assert.equal(f.calls[2].options.body.revision,2);assert.equal(f.maxRequests,1)
  assert.equal(f.state.createdTask.value.id,task().id);assert.equal(f.state.busy.value,'');assert.equal(f.state.preparingCreate.value,false)
})

test('a selected media version changed or removed by the pending GET requires another explicit download choice',async t=>{
  for(const field of ['fileName','size','type','chatId','messageId','missing']){
    const f=await opened(t);f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen'
    const response=deferred();f.expect(BASE+'/preview/'+ID,response.promise);const refresh=f.state.read(),pending=f.state.create()
    const value=preview({revision:2})
    if(field==='missing'){value.media=[];value.messages[0].media=[]}
    else if(field==='chatId'){
      value.bot.chatId='9007199254740994';for(const m of [...value.media,...value.messages[0].media])m.message.chatId=value.bot.chatId
    }else if(field==='messageId'){
      value.messages[0].id='1003';for(const m of [...value.media,...value.messages[0].media])m.message.messageId='1003'
    }else for(const m of [...value.media,...value.messages[0].media])m[field]=field==='fileName'?'changed.txt':field==='size'?17:'VIDEO'
    assert.equal(bots.validBotPreview(value),true,field);response.resolve(value);await Promise.all([refresh,pending])
    assert.equal(f.calls.length,2,field);assert.equal(f.state.createRequest.value,null);assert.equal(f.state.createdTask.value,null)
    assert.match(f.state.error.value,/所选媒体已变化或不再可见/);assert.equal(f.state.preparingCreate.value,false)
    if(field==='missing')assert.deepEqual(f.state.selected.value,[])
  }
})

test('a failed in-flight metadata read prevents creating from the old revision until an explicit refresh succeeds',async t=>{
  for(const status of [400,503]){
    const f=await opened(t);f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen'
    const response=deferred();f.expect(BASE+'/preview/'+ID,response.promise);const refresh=f.state.read(),pending=f.state.create()
    response.resolve(new ApiError(status===400?'本次资源已失效':'temporary failure',status));await Promise.all([refresh,pending])
    assert.equal(f.calls.length,2);assert.equal(f.state.createRequest.value,null);assert.equal(f.state.canCreate.value,false)
    assert.equal(f.state.preparingCreate.value,false);assert.equal(f.state.source.value,'@synthetic_bot');assert.equal(f.state.targetDirectory.value,'D:\\chosen')
    await f.state.create();assert.equal(f.calls.length,2)
    if(status===400){assert.equal(f.state.preview.value,null);assert.deepEqual(f.state.selected.value,[])}
    else{
      assert.deepEqual(f.state.selected.value,['file-a']);assert.equal(f.state.creationRecheckRequired.value,true)
      assert.match(f.state.creationHint.value,/刷新消息/)
      f.expect(BASE+'/preview/'+ID,preview({revision:2}));await f.state.read();assert.equal(f.state.canCreate.value,true)
      f.expect(BASE+'/tasks',task(),'POST');await f.state.create();assert.equal(f.calls[3].options.body.revision,2)
    }
  }
})

test('account changes, disconnection and disposal abort download preparation behind a pending metadata read',async t=>{
  for(const change of ['account','offline','dispose']){
    const f=await opened(t);f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen'
    const response=deferred();f.expect(BASE+'/preview/'+ID,response.promise);const refresh=f.state.read(),pending=f.state.create()
    if(change==='account'){f.props.accountId='2';await flush()}
    else if(change==='offline')f.props.available=false
    else f.destroy()
    response.resolve(preview({revision:2}));await Promise.all([refresh,pending])
    assert.equal(f.calls.length,2,change);assert.equal(f.state.createRequest.value,null);assert.equal(f.state.createdTask.value,null)
    assert.equal(f.timers.size,0)
    if(change==='account')assert.equal(f.state.preview.value,null)
    if(change==='offline'){
      assert.equal(f.state.preparingCreate.value,false);assert.deepEqual(f.state.selected.value,['file-a'])
      f.props.available=true;await flush();assert.equal(f.state.canCreate.value,false);assert.equal(f.state.creationRecheckRequired.value,true)
    }
  }
})

test('preparation never borrows a late observation revision discarded after its original deadline',async t=>{
  const f=await opened(t);f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen';f.state.waitSeconds.value=2;f.state.timeoutSeconds.value=15
  f.expect(BASE+'/preview/'+ID+'/press',preview({revision:2,state:'WAITING',wait:wait({waitSeconds:2,timeoutSeconds:15,remainingMs:15000})}),'POST');await f.state.press(button())
  const response=deferred();f.expect(BASE+'/preview/'+ID,response.promise);await f.advance(2000);const pending=f.state.create()
  assert.equal(f.state.preparingCreate.value,true);await f.advance(14000);response.resolve(preview({revision:3,state:'READY',wait:null}));await pending
  assert.equal(f.calls.length,3);assert.equal(f.state.preview.value.revision,2);assert.equal(f.state.createdTask.value,null)
  assert.equal(f.state.waitingExpired.value,true);assert.equal(f.state.creationRecheckRequired.value,true);assert.equal(f.state.preparingCreate.value,false)
  assert.equal(f.state.canPress.value,false);assert.equal(f.state.canCreate.value,false);assert.equal(f.timers.size,0)
})

const downloadFooter=f=>nodes(f.render()).find(node=>String(node.props?.class??'').split(/\s+/).includes('bot-download-footer'))

test('download preparation and a rejected in-flight GET remain visible in the action footer even after the preview expires',async t=>{
  const f=await opened(t);f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen'
  const response=deferred();f.expect(BASE+'/preview/'+ID,response.promise);const refresh=f.state.read(),pending=f.state.create()
  assert.match(text(downloadFooter(f)),/正在核对已选媒体/)
  response.resolve(new ApiError('本次资源已失效，请重新读取当前页',400));await Promise.all([refresh,pending])
  assert.equal(f.state.preview.value,null);assert.match(text(downloadFooter(f)),/本次资源已失效，请重新读取当前页/)
  assert.equal(f.calls.filter(call=>call.path===BASE+'/tasks').length,0)
})

test('a definite download rejection is shown beside the download button and remains editable',async t=>{
  const f=await opened(t);f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen'
  const response=deferred();f.expect(BASE+'/tasks',response.promise,'POST');const pending=f.state.create()
  assert.match(text(downloadFooter(f)),/正在创建下载任务/)
  response.resolve(new ApiError('目标目录不可写',400));await pending
  assert.match(text(downloadFooter(f)),/目标目录不可写.*下载选中媒体/s)
  assert.equal(f.state.createRequest.value,null);assert.equal(f.state.canCreate.value,true);assert.equal(f.calls.length,2)
})

test('an unknown download creation shows its original failure and lookup-only action in the footer',async t=>{
  const f=await opened(t);f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen'
  f.expect(BASE+'/tasks',new ApiError('服务暂不可用',500),'POST');await f.state.create()
  const request=copy(f.state.createRequest.value)
  assert.match(text(downloadFooter(f)),/服务暂不可用.*核对创建结果.*不会重复提交/s)
  assert.doesNotMatch(text(downloadFooter(f)),/下载选中媒体/);await f.state.create();assert.equal(f.calls.length,2)
  f.expect(BASE+'/tasks/by-operation/'+request.operationId,{task:null});await f.state.lookup()
  assert.match(text(downloadFooter(f)),/尚未查到这次创建.*核对创建结果/s);assert.deepEqual(f.state.createRequest.value,request)
})

test('a created download displays its file count and queue state beside task navigation without changing pages',async t=>{
  const f=await opened(t);f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen'
  f.expect(BASE+'/tasks',task(),'POST');await f.state.create()
  assert.match(text(downloadFooter(f)),/下载任务已创建，共 1 个文件 · 等待下载.*查看下载任务.*继续浏览机器人/s)
  assert.equal(f.events.some(event=>event[0]==='tasks'),false);assert.equal(f.state.preview.value.id,ID)
  const action=nodes(downloadFooter(f)).find(node=>node.type==='button'&&text(node)==='查看下载任务');assert.ok(action)
  action.props.onClick();assert.deepEqual(f.events.at(-1),['tasks','download']);assert.equal(f.calls.length,2)
  f.state.continueBrowsing();assert.doesNotMatch(text(downloadFooter(f)),/下载任务已创建/)
})

test('bot operation IDs prefer a valid native UUID and securely construct UUIDv4 when native UUID support is absent or invalid',()=>{
  const original='12345678-1234-4abc-9abc-123456789abc'
  assert.equal(bots.botOperationId({randomUUID:()=>original,getRandomValues(){assert.fail('valid native UUID needs no fallback')}}),original)
  for(const native of [undefined,()=> 'invalid',()=> '12345678-1234-1abc-9abc-123456789abc',()=>{throw new Error('native unavailable')}]){
    let calls=0;const provider={getRandomValues(bytes){calls++;for(let index=0;index<bytes.length;index++)bytes[index]=index;return bytes}}
    if(native)provider.randomUUID=native
    const value=bots.botOperationId(provider);assert.equal(value,'00010203-0405-4607-8809-0a0b0c0d0e0f');assert.equal(calls,1)
    assert.match(value,/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  }
  for(const provider of [{},{randomUUID(){throw new Error('native failed')}},{getRandomValues(){throw new Error('secure RNG failed')}}])assert.throws(()=>bots.botOperationId(provider),/无法生成安全操作号/)
})

test('the rendered download click creates exactly one task with native UUID or secure random fallback and never resubmits an unknown operation',async t=>{
  for(const native of [true,false]){
    let randomCalls=0;const operationCrypto=native?{randomUUID:()=>{randomCalls++;return '12345678-1234-4abc-9abc-123456789abc'}}:{getRandomValues(bytes){randomCalls++;bytes.fill(7);return bytes}}
    const f=await opened(t,preview(),{operationCrypto});f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen'
    const click=nodes(downloadFooter(f)).find(node=>node.type==='button'&&text(node)==='下载选中媒体')
    assert.equal(click.props.disabled,false);assert.equal(click.props.type,'button');assert.equal(typeof click.props.onClick,'function')
    const response=deferred();f.expect(BASE+'/tasks',response.promise,'POST');const pending=click.props.onClick({type:'click'})
    await click.props.onClick({type:'click'});assert.equal(f.calls.length,2);assert.equal(randomCalls,1)
    const request=copy(f.state.createRequest.value);assert.match(request.operationId,/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    response.resolve(new ApiError('response lost after creation',500));await pending
    assert.match(text(downloadFooter(f)),/核对创建结果/);await click.props.onClick({type:'click'})
    assert.equal(f.calls.length,2);assert.equal(randomCalls,1);assert.deepEqual(f.state.createRequest.value,request)
    f.expect(BASE+'/tasks/by-operation/'+request.operationId,{task:task()});await f.state.lookup()
    assert.equal(f.state.createdTask.value.id,task().id);assert.equal(f.calls.filter(call=>call.path===BASE+'/tasks').length,1)
  }
})

test('a rendered download click reports missing or failed secure randomness beside the button without posting or leaving a lock',async t=>{
  for(const operationCrypto of [{},{randomUUID(){throw new Error('native unavailable')},getRandomValues(){throw new Error('secure RNG unavailable')}}]){
    const f=await opened(t,preview(),{operationCrypto});f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen'
    const click=nodes(downloadFooter(f)).find(node=>node.type==='button'&&text(node)==='下载选中媒体')
    await assert.doesNotReject(()=>click.props.onClick({type:'click'}))
    assert.match(text(downloadFooter(f)),/无法生成安全操作号/);assert.match(f.state.error.value,/无法生成安全操作号/)
    assert.equal(f.calls.length,1);assert.equal(f.state.createRequest.value,null);assert.equal(f.state.createdTask.value,null)
    assert.equal(f.state.preparingCreate.value,false);assert.equal(f.state.busy.value,'');assert.equal(f.state.inputLocked.value,false)
    assert.deepEqual(f.events.at(-1),['locked',false]);assert.equal(f.state.canCreate.value,true)
  }
})

test('operation ID generation failure never dispatches or marks a pagination callback as unknown',async t=>{
  const f=await opened(t,preview(),{operationCrypto:{}})
  const click=nodes(f.render()).find(node=>node.type==='button'&&text(node).startsWith('下一页／组：'))
  await assert.doesNotReject(()=>click.props.onClick({type:'click'}))
  assert.match(f.state.error.value,/无法生成安全操作号/);assert.equal(f.calls.length,1)
  assert.equal(f.state.pressUnknown.value,false);assert.equal(f.state.observing.value,false);assert.equal(f.timers.size,0)
  assert.equal(f.state.busy.value,'');assert.equal(f.state.inputLocked.value,false);assert.equal(f.state.preview.value.revision,1)
})

test('a permission rejection preserves its footer reason and lets the user remove the bad media before a new explicit operation',async t=>{
  const view=preview(),remaining={...media(),key:'file-b',fileName:'remaining.txt',message:{chatId:CHAT,messageId:'1003',link:null}}
  view.media.push(remaining)
  const f=await opened(t,view);f.state.selectAll();f.state.targetDirectory.value='D:\\chosen'
  const rejectedReason='源消息不可保存或身份已变化'
  f.expect(BASE+'/tasks',new ApiError(rejectedReason,400),'POST')
  let click=nodes(downloadFooter(f)).find(node=>node.type==='button'&&text(node)==='下载选中媒体')
  await click.props.onClick({type:'click'});const rejected=copy(f.calls[1].options.body)
  assert.deepEqual(rejected.mediaKeys,['file-a','file-b']);assert.match(text(downloadFooter(f)),/源消息不可保存或身份已变化/)
  assert.equal(f.state.createRequest.value,null);assert.equal(f.state.preparingCreate.value,false);assert.equal(f.state.busy.value,'')
  assert.equal(f.state.inputLocked.value,false);assert.equal(f.state.canCreate.value,true);assert.doesNotMatch(text(downloadFooter(f)),/核对创建结果/)
  await f.advance(300000);assert.equal(f.calls.length,2,'a definite rejection never retries itself')
  f.state.select('file-a');assert.deepEqual(f.state.selected.value,['file-b'])
  const accepted=task();accepted.items[0].fileName=remaining.fileName
  f.expect(BASE+'/tasks',accepted,'POST');click=nodes(downloadFooter(f)).find(node=>node.type==='button'&&text(node)==='下载选中媒体')
  assert.equal(click.props.disabled,false);await click.props.onClick({type:'click'})
  const request=f.calls[2].options.body;assert.deepEqual(request.mediaKeys,['file-b']);assert.notEqual(request.operationId,rejected.operationId)
  assert.equal(request.sourceId,rejected.sourceId);assert.equal(request.revision,rejected.revision);assert.equal(request.targetDirectory,rejected.targetDirectory)
  assert.equal(f.state.createdTask.value.id,accepted.id);assert.equal(f.calls.filter(call=>call.path===BASE+'/tasks').length,2)
  await f.advance(300000);assert.equal(f.calls.length,3);assert.equal(f.events.some(event=>event[0]==='tasks'),false)
})

test('the current-page reader accepts an initial middle or final page and downloads its media without implicit pagination or history reads',async t=>{
  for(const currentPage of [2,6]){
    const last=currentPage===6,view=preview({currentPage,paginationStatus:last?'END':'MORE',recommendedButtonId:last?null:'button-next'})
    view.messages[1].text=`已获取第${currentPage}组文件（共6组）`
    const f=mount(t);f.state.source.value='@synthetic_bot'
    const open=nodes(f.render()).find(node=>node.type==='button'&&text(node)==='读取当前页／组')
    f.expect(BASE+'/preview',view,'POST');await open.props.onClick({type:'click'})
    assert.equal(f.state.preview.value.currentPage,currentPage);assert.match(text(f.render()),new RegExp(`第 ${currentPage} / 6 页／组`))
    assert.equal(f.calls.length,1);assert.deepEqual(f.state.selected.value,[]);assert.equal(f.state.observing.value,false)
    if(last)assert.match(text(f.render()),/机器人已显示最后一页／组/)
    f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen';assert.equal(f.state.canCreate.value,true)
    const download=nodes(downloadFooter(f)).find(node=>node.type==='button'&&text(node)==='下载选中媒体')
    f.expect(BASE+'/tasks',task(),'POST');await download.props.onClick({type:'click'})
    assert.deepEqual(f.calls[1].options.body.mediaKeys,['file-a']);assert.equal(f.state.createdTask.value.id,task().id)
    await f.advance(300000);assert.deepEqual(f.calls.map(call=>call.path),[BASE+'/preview',BASE+'/tasks'])
    assert.equal(f.timers.size,0,'reading a current page never starts a pagination observation window by itself')
  }
})

test('explicitly rereading after a manual Telegram page change starts from that current batch without carrying the previous selection',async t=>{
  const f=await opened(t,preview({currentPage:2}));f.state.select('file-a');f.state.targetDirectory.value='D:\\chosen'
  const value=preview({currentPage:6,paginationStatus:'END',recommendedButtonId:null})
  value.media[0].key='file-b';value.media[0].fileName='current-batch.txt';value.media[0].message.messageId='1003'
  value.messages[0].id='1003';value.messages[0].media=[copy(value.media[0])];value.messages[1].text='文件夹内容发送完成'
  f.expect(BASE+'/preview',value,'POST')
  const open=nodes(f.render()).find(node=>node.type==='button'&&text(node)==='读取当前页／组');await open.props.onClick({type:'click'})
  assert.deepEqual(f.state.selected.value,[]);assert.deepEqual(f.state.preview.value.media.map(media=>media.key),['file-b'])
  assert.equal(f.state.preview.value.currentPage,6);assert.equal(f.state.targetDirectory.value,'D:\\chosen')
  f.state.select('file-b');f.expect(BASE+'/tasks',task(),'POST');await f.state.create()
  assert.deepEqual(f.calls[2].options.body.mediaKeys,['file-b'])
  assert.deepEqual(f.calls.map(call=>call.path),[BASE+'/preview',BASE+'/preview',BASE+'/tasks']);assert.equal(f.timers.size,0)
})
