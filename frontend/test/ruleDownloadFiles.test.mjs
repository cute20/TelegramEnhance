import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import {parse,compileScript,compileTemplate} from 'vue/compiler-sfc'
import ts from 'typescript'
import * as ruleModule from '../src/ruleDownloadTasks.ts'
import * as taskModule from '../src/downloadTasks.ts'
import * as library from '../src/library.ts'

const ID='00000000-0000-0000-0000-000000000001',OTHER='00000000-0000-0000-0000-000000000002'
const TASK_A='00000000-0000-0000-0000-000000000010',TASK_B='00000000-0000-0000-0000-000000000011'
const BASE='/transfers/download/rule-tasks',CURSOR='opaque / 中文?after=50'
const flush=async()=>{await vue.nextTick();await new Promise(setImmediate)}
const deferred=()=>{let resolve;const promise=new Promise(yes=>resolve=yes);return{promise,resolve}}
const group=overrides=>({id:ID,name:'合成规则',channelTitle:'合成频道',targetDirectory:'D:\\out',revision:1,enabled:true,
  window:{mode:'ALL_DAY',start:null,end:null,timezone:'Asia/Shanghai'},state:'WATCHING',taskCount:3,total:89,completed:89,skipped:0,duplicates:1,
  mainTotal:2,commentTotal:87,totalBytes:3293,downloadedBytes:3293,writtenBytes:3293,updatedAt:1,failedTasks:0,...overrides})
function row(number,source='COMMENT'){
  const item={id:source+'-'+number,fileName:number<2?'同名.txt':'文件'+number+'.txt',type:'DOCUMENT',state:'COMPLETED',size:37,
    downloadedBytes:37,writtenBytes:37,outputPath:'D:\\out\\文件'+number+'.txt',error:null,history:null,manifestPath:null,sourceLink:'https://t.me/synthetic/1'}
  if(source==='COMMENT'&&number===0)item.duplicate={itemId:'previous',reason:'CONTENT_SHA',sourceLink:null,path:'D:\\old\\same.txt'}
  return {taskId:number<37?TASK_A:TASK_B,ruleVersion:number<37?1:2,taskState:'COMPLETED',pauseRequested:false,source,item}
}
const totals=source=>({total:source==='COMMENT'?87:2,completed:source==='COMMENT'?87:2,skipped:0,duplicates:source==='COMMENT'?1:0,
  totalBytes:(source==='COMMENT'?87:2)*37,downloadedBytes:(source==='COMMENT'?87:2)*37,writtenBytes:(source==='COMMENT'?87:2)*37})
const page=(source='COMMENT',start=0,count=source==='COMMENT'?50:2,more=source==='COMMENT'&&start===0)=>({ruleId:ID,source,totals:totals(source),
  items:Array.from({length:count},(_,index)=>row(start+index,source)),nextCursor:more?CURSOR:null,hasMore:more})
const path=(source,cursor)=>`${BASE}/${ID}/files?source=${source}&limit=50${cursor===undefined?'':'&cursor='+encodeURIComponent(cursor)}`
const disclosure=open=>({target:{open,classList:{contains:name=>name==='record-disclosure'}}})
const {descriptor}=parse(readFileSync(new URL('../src/components/RuleDownloadTasks.vue',import.meta.url),'utf8'),{filename:'RuleDownloadTasks.vue'})
const script=compileScript(descriptor,{id:'rule-files-test'})
const transpile=code=>ts.transpileModule(code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
const code=transpile(script.content)
const template=compileTemplate({source:descriptor.template.content,filename:'RuleDownloadTasks.vue',id:'rule-files-test',compilerOptions:{bindingMetadata:script.bindings,expressionPlugins:['typescript']}})
assert.deepEqual(template.errors,[])
class ApiError extends Error{constructor(message,status){super(message);this.status=status}}
function mount(t,initial=group()){
  const props=vue.reactive({authorized:true,available:true,accountId:'1',active:true}),steps=[{path:BASE,value:{items:[initial]}}],calls=[],failures=[],hooks=[],timers=[],events=[]
  const api=async(path,options={})=>{calls.push({path,options});const expected=steps.shift()
    try{assert.ok(expected,path);assert.equal(path,expected.path);assert.equal(options.method??'GET',expected.method??'GET')}catch(cause){failures.push(cause);throw cause}
    if(expected.value instanceof Error)throw expected.value;return await expected.value
  }
  const modules={vue:{...vue,onBeforeUnmount:hook=>hooks.push(hook),withCtx:fn=>fn,withDirectives:node=>node},'../api':{api,ApiError},'../library':library,'../downloadTasks':taskModule,'../ruleDownloadTasks':ruleModule}
  const componentStubs=new Map()
  const requireModule=name=>{if(name.endsWith('.vue')){if(!componentStubs.has(name))componentStubs.set(name,{__name:name.split('/').at(-1).replace('.vue','')});return{__esModule:true,default:componentStubs.get(name)}}assert.ok(Object.hasOwn(modules,name),name);return modules[name]}
  const module={exports:{}},renderModule={exports:{}}
  new Function('require','module','exports','setTimeout','clearTimeout',code)(requireModule,module,module.exports,
    (callback,delay)=>{const timer={callback,delay,cancelled:false};timers.push(timer);return timer},timer=>{if(timer)timer.cancelled=true})
  new Function('require','module','exports',transpile(template.code))(requireModule,renderModule,renderModule.exports)
  const scope=vue.effectScope(),state=scope.run(()=>module.exports.default.setup(props,{expose(){},emit:(...args)=>events.push(args)}))
  let dead=false;const destroy=()=>{if(!dead){dead=true;hooks.forEach(hook=>hook());scope.stop()}}
  t.after(()=>{destroy();assert.deepEqual(failures,[]);assert.equal(steps.length,0)})
  return{state,props,calls,timers,events,destroy,expect:(path,value,method='GET')=>steps.push({path,value,method}),render:()=>renderModule.exports.render({},[],props,vue.proxyRefs(state),{},{}),
    async open(){await flush();this.expect(path('COMMENT'),page());this.expect(path('MAIN'),page('MAIN'));state.openFiles(initial,disclosure(true));await flush()}}
}
function nodes(node){if(!node||typeof node!=='object')return[];if(Array.isArray(node))return node.flatMap(nodes);const children=Array.isArray(node.children)?node.children:node.children&&typeof node.children==='object'?Object.values(node.children).filter(child=>typeof child==='function').flatMap(slot=>slot()):[];return[node,...nodes(children)]}
const text=node=>typeof node==='string'?node:Array.isArray(node)?node.map(text).join(''):node&&typeof node==='object'?text(node.children):''

test('rule record activity follows its panel and every existing or newly returned rule starts closed',async t=>{
  const f=mount(t);await flush();let records=nodes(f.render()).filter(node=>node.type?.__name==='CompactRecord')
  assert.equal(records.length,1);assert.equal(records[0].props.active,true);assert.equal(records[0].props['default-expanded'],false)
  f.props.active=false;await flush();records=nodes(f.render()).filter(node=>node.type?.__name==='CompactRecord');assert.equal(records[0].props.active,false)
  f.expect(BASE,{items:[group(),group({id:OTHER,state:'RUNNING'})]});f.props.active=true;await flush()
  records=nodes(f.render()).filter(node=>node.type?.__name==='CompactRecord');assert.equal(records.length,2);assert.ok(records.every(record=>record.props.active===true&&record.props['default-expanded']===false));assert.equal(f.calls.length,2)
})

test('87 comments span internal 37/50 tasks as one 50+37 file feed, with MAIN2 separate and no routine batch/version cards',async t=>{
  const f=mount(t);await flush();assert.equal(f.calls.length,1)
  await f.open()
  const comment=f.state.fileState(ID,'COMMENT'),main=f.state.fileState(ID,'MAIN')
  assert.equal(comment.items.length,50);assert.equal(comment.totals.completed,87);assert.equal(main.items.length,2)
  let sources=nodes(f.render()).filter(node=>node.type==='details'&&node.props.class==='rule-source-files')
  assert.equal(sources.length,2);assert.equal(sources[0].props.open,false);assert.equal(sources[1].props.open,true)
  assert.match(text(sources[0].children[0]),/主帖附件.*2\/2/);assert.match(text(sources[1].children[0]),/评论媒体.*87\/87/)
  f.expect(path('COMMENT',CURSOR),page('COMMENT',50,37,false));await f.state.loadFiles(group(),'COMMENT',true)
  assert.equal(comment.items.length,87);assert.equal(new Set(comment.items.map(row=>row.item.id)).size,87)
  assert.equal(comment.items.filter(row=>row.item.fileName==='同名.txt').length,2)
  assert.equal(comment.items[0].item.manifestPath,null);assert.equal(comment.hasMore,false)
  const rendered=nodes(f.render());assert.equal(rendered.filter(node=>node.props?.class==='download-segment').length,0)
  assert.equal(f.calls.some(call=>call.path.includes('/members')),false)
  assert.ok(f.calls.every(call=>(call.options.method??'GET')==='GET'))
})
test('source validator rejects wrong rule/source, repeated item identity and invented completion, while bytes100 stays pending',()=>{
  assert.equal(ruleModule.validRuleFilePage(page(),ID,'COMMENT'),true)
  for(const change of [value=>value.ruleId=OTHER,value=>value.source='MAIN',value=>value.items[0].source='MAIN',value=>value.items[1].item.id=value.items[0].item.id,
    value=>value.totals.completed=88,value=>value.totals.completed=49,value=>value.totals.duplicates=0,
    value=>{value.totals.totalBytes=1000;value.totals.downloadedBytes=0;value.totals.writtenBytes=0},
    value=>value.nextCursor='',value=>value.items[0].item.history={id:'fake'},value=>value.items[0].item.duplicate.reason='SAME_NAME']){
    const invalid=page();change(invalid);assert.equal(ruleModule.validRuleFilePage(invalid,ID,'COMMENT'),false)
  }
  const pending=page('COMMENT',0,1,false);pending.totals.completed=0;pending.totals.duplicates=0;pending.items[0].taskState='RUNNING';pending.items[0].item.state='FINALIZING'
  assert.equal(ruleModule.validRuleFilePage(pending,ID,'COMMENT'),true)
  const skipped=page();skipped.items[0].item={...skipped.items[0].item,state:'SKIPPED_HISTORY',outputPath:null,downloadedBytes:0,writtenBytes:0,duplicate:null,
    history:{id:'history',taskId:TASK_A,path:'D:\\old\\saved.txt',sha256:'a'.repeat(64),completedAt:1}}
  assert.equal(ruleModule.validRuleFilePage(skipped,ID,'COMMENT'),false)
  skipped.totals.skipped=1;skipped.totals.completed=86;assert.equal(ruleModule.validRuleFilePage(skipped,ID,'COMMENT'),true)
})
test('duplicate scroll callbacks cannot read the same source concurrently and opaque cursors are only encoded',async t=>{
  const f=mount(t);await f.open();const response=deferred()
  f.expect(path('COMMENT',CURSOR),response.promise)
  const reading=f.state.loadFiles(group(),'COMMENT',true);await f.state.loadFiles(group(),'COMMENT',true)
  assert.equal(f.calls.length,4);response.resolve(page('COMMENT',50,37,false));await reading
  assert.equal(f.state.fileState(ID,'COMMENT').items.length,87)
})
test('append failure retains all rows and cursor and does not automatically retry; explicit retry can continue',async t=>{
  const f=mount(t);await f.open();const state=f.state.fileState(ID,'COMMENT')
  f.expect(path('COMMENT',CURSOR),new Error('synthetic offline'));await f.state.loadFiles(group(),'COMMENT',true);await flush()
  assert.equal(state.items.length,50);assert.equal(state.cursor,CURSOR);assert.match(state.error,/offline/);assert.equal(f.calls.length,4)
  assert.equal(state.failedAppend,true);assert.equal(state.failedCursor,CURSOR)
  await f.state.loadFiles(group(),'COMMENT',true);assert.equal(f.calls.length,4,'A queued scroll callback cannot automatically retry a failed page')
  const loader=nodes(f.render()).find(node=>node.type?.__name==='InfiniteLoad');assert.equal(loader.props.blocked,true)
  f.expect(path('COMMENT',CURSOR),page('COMMENT',50,37,false));await f.state.retryFiles(group(),'COMMENT')
  assert.equal(state.items.length,87);assert.equal(state.error,'');assert.equal(state.failedAppend,null);assert.equal(state.failedCursor,null)
})
test('non-progressing cursor or foreign source receipt cannot replace a successful page',async t=>{
  const f=mount(t);await f.open();const state=f.state.fileState(ID,'COMMENT')
  f.expect(path('COMMENT',CURSOR),page());await f.state.loadFiles(group(),'COMMENT',true)
  assert.equal(state.items.length,50);assert.equal(state.cursor,CURSOR);assert.match(state.error,/分页位置/)
  f.expect(path('COMMENT',CURSOR),{...page('COMMENT',50,37,false),ruleId:OTHER});await f.state.retryFiles(group(),'COMMENT')
  assert.equal(state.items.length,50);assert.equal(state.cursor,CURSOR)
})
test('manual refresh reads sources in sequence, preserves all loaded rows and disclosure state, and makes no whole-history loop',async t=>{
  const f=mount(t);await f.open();f.expect(path('COMMENT',CURSOR),page('COMMENT',50,37,false));await f.state.loadFiles(group(),'COMMENT',true)
  f.state.setSourceOpen(ID,'MAIN',{target:{open:true}})
  const state=f.state.fileState(ID,'COMMENT'),response=deferred(),before=f.calls.length
  f.expect(BASE,{items:[group()]});f.expect(path('COMMENT'),response.promise);const reading=f.state.refreshFiles(group());await flush()
  assert.equal(f.calls.length,before+2);assert.equal(state.items.length,87)
  f.expect(path('COMMENT',CURSOR),page('COMMENT',50,37,false));f.expect(path('MAIN'),page('MAIN'));response.resolve(page());await reading
  assert.equal(f.calls.length,before+4);assert.equal(state.items.length,87);assert.equal(f.state.sourceIsOpen(ID,'MAIN'),true)
})
for(const exhausted of [false,true]){
  test(`failed homepage refresh with ${exhausted?'EOF87':'loaded50 and next cursor'} retries that same homepage GET and keeps loaded rows`,async t=>{
    const f=mount(t);await f.open()
    if(exhausted){f.expect(path('COMMENT',CURSOR),page('COMMENT',50,37,false));await f.state.loadFiles(group(),'COMMENT',true)}
    const state=f.state.fileState(ID,'COMMENT'),count=state.items.length,cursor=state.cursor
    f.expect(BASE,{items:[group()]});f.expect(path('COMMENT'),new Error('homepage offline'));await f.state.refreshFiles(group())
    assert.equal(state.items.length,count);assert.equal(state.cursor,cursor);assert.equal(state.failedAppend,false);assert.equal(state.failedCursor,null)
    const before=f.calls.length,response=deferred();f.expect(path('COMMENT'),response.promise)
    const retry=nodes(f.render()).find(node=>node.type==='button'&&text(node)==='重试读取')
    assert.ok(retry);retry.props.onClick();assert.equal(state.items.length,count);assert.equal(f.calls.length,before+1)
    assert.equal(f.calls.at(-1).path,path('COMMENT'));response.resolve(page());await flush()
    assert.equal(state.items.length,count);assert.equal(state.error,'');assert.equal(state.failedAppend,null)
    assert.ok(f.calls.every(call=>(call.options.method??'GET')==='GET'))
  })
}
test('failed source initial read is not retried by closing and reopening the disclosure',async t=>{
  const f=mount(t);await flush();f.expect(path('COMMENT'),new Error('offline'));f.expect(path('MAIN'),page('MAIN'))
  f.state.openFiles(group(),disclosure(true));await flush();f.state.openFiles(group(),disclosure(false));f.state.openFiles(group(),disclosure(true));await flush()
  assert.equal(f.calls.length,3);assert.equal(f.state.fileState(ID,'COMMENT').loaded,false)
  assert.equal(f.state.fileState(ID,'MAIN').loaded,true)
})
test('old account source replies are discarded and cannot start the second source in the new account',async t=>{
  const f=mount(t);await flush();const response=deferred();f.expect(path('COMMENT'),response.promise)
  f.state.openFiles(group(),disclosure(true));f.expect(BASE,{items:[group({id:OTHER})]});f.props.accountId='2';await flush()
  response.resolve(page());await flush();assert.deepEqual(f.state.files.value,{})
  assert.equal(f.state.groups.value[0].id,OTHER);assert.equal(f.calls.length,3)
})
test('hiding or unmounting discards in-flight files and issues no hidden continuation',async t=>{
  const f=mount(t);await flush();const response=deferred();f.expect(path('COMMENT'),response.promise)
  f.state.openFiles(group(),disclosure(true));f.props.active=false;await flush();response.resolve(page());await flush()
  assert.equal(f.state.fileState(ID,'COMMENT').loaded,false);assert.equal(f.calls.length,2)
  f.expect(BASE,{items:[group()]});f.props.active=true;await flush();assert.equal(f.calls.length,3)
  f.expect(path('COMMENT'),page());f.expect(path('MAIN'),page('MAIN'));f.state.openFiles(group(),disclosure(true));await flush()
  assert.equal(f.state.fileState(ID,'COMMENT').loaded,true)
  f.destroy();await f.state.loadFiles(group(),'COMMENT',true);assert.equal(f.calls.length,5)
})
test('completed histories never load internal task records, and incomplete task controls remain opt-in',async t=>{
  const complete=mount(t);await complete.open();await complete.state.loadMembers(group());assert.equal(complete.calls.length,3)
  const pending=group({completed:88,failedTasks:1,state:'FAILED'}),f=mount(t,pending);await f.open()
  assert.equal(f.calls.some(call=>call.path.includes('/members')),false)
  const task={id:TASK_B,title:'internal',channelTitle:'channel',state:'FAILED',pauseRequested:false,targetDirectory:'D:\\out',scope:'OBSERVED_SELECTION',
    total:1,completed:0,skipped:0,totalBytes:37,error:'synthetic failure',createdAt:1,updatedAt:1,duplicatePolicy:'KEEP_DUPLICATES_V2',duplicateDirectory:'D:\\out\\重复媒体',duplicates:0,
    items:[{...row(60).item,state:'QUEUED',writtenBytes:0,downloadedBytes:0}],automation:{ruleId:ID,ruleVersion:2,ruleName:'name',manual:false,held:false,enabled:true,currentVersion:2,window:pending.window}}
  f.expect(`${BASE}/${ID}/members?limit=10&offset=0`,{items:[task]});f.state.openMembers(pending,{target:{open:true}});await flush()
  assert.equal(f.state.unfinishedMembers(pending).length,1);assert.equal(f.calls.length,4)
})
test('source file reads and refreshes never clear an unknown rule operation or automatically repeat POST',async t=>{
  const f=mount(t);await f.open();f.expect('/rules/download/'+ID+'/enabled',new Error('timeout'),'POST');await f.state.toggle(group())
  assert.deepEqual(f.state.uncertain.value,[ID]);f.expect(BASE,{items:[group()]});f.expect(path('COMMENT'),page());f.expect(path('MAIN'),page('MAIN'));await f.state.refreshFiles(group())
  assert.deepEqual(f.state.uncertain.value,[],'Explicit full refresh also reconciles the rule summary with GET')
  assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
})
test('same-account disconnection keeps 87 loaded files, source disclosures and member cursor; restoration only reads local group progress',async t=>{
  const f=mount(t);await f.open();f.expect(path('COMMENT',CURSOR),page('COMMENT',50,37,false));await f.state.loadFiles(group(),'COMMENT',true)
  f.state.setSourceOpen(ID,'MAIN',{target:{open:true}});f.state.offsets.value[ID]=10;f.state.more.value[ID]=true
  const comments=f.state.fileState(ID,'COMMENT'),before=f.calls.length
  f.props.available=false;await flush()
  assert.equal(f.state.groups.value.length,1);assert.equal(f.state.fileState(ID,'COMMENT'),comments);assert.equal(comments.items.length,87)
  assert.equal(f.state.sourceIsOpen(ID,'MAIN'),true);assert.equal(f.state.offsets.value[ID],10)
  f.expect(BASE+'/'+ID,group({updatedAt:2}));f.props.available=true;await flush()
  assert.equal(f.calls.length,before+1);assert.equal(f.calls.at(-1).path,BASE+'/'+ID)
  assert.equal(comments.items.length,87);assert.equal(comments.cursor,null);assert.equal(f.state.sourceIsOpen(ID,'MAIN'),true)
})
test('source response from before a brief disconnection cannot change the retained page or automatically reload files',async t=>{
  const f=mount(t);await f.open();const response=deferred(),comments=f.state.fileState(ID,'COMMENT')
  f.expect(path('COMMENT',CURSOR),response.promise);const reading=f.state.loadFiles(group(),'COMMENT',true)
  f.props.available=false;await flush();response.resolve(page('COMMENT',50,37,false));await reading
  assert.equal(comments.items.length,50);assert.equal(comments.cursor,CURSOR)
  f.expect(BASE+'/'+ID,group());f.props.available=true;await flush()
  assert.equal(comments.items.length,50);assert.equal(comments.cursor,CURSOR);assert.equal(f.calls.length,5)
})
test('an operation interrupted by availability stays unconfirmed after restored progress and file cache never resets',async t=>{
  const f=mount(t);await f.open();const response=deferred(),comments=f.state.fileState(ID,'COMMENT')
  f.expect('/rules/download/'+ID+'/enabled',response.promise,'POST');const changing=f.state.toggle(group())
  f.props.available=false;await flush();assert.deepEqual(f.state.uncertain.value,[ID])
  response.resolve({rule:{id:ID,enabled:false,revision:2}});await changing
  f.expect(BASE+'/'+ID,group({enabled:false,state:'STOPPED',revision:2}));f.props.available=true;await flush()
  assert.deepEqual(f.state.uncertain.value,[ID]);assert.equal(f.state.groups.value[0].enabled,false)
  assert.equal(f.state.fileState(ID,'COMMENT'),comments);assert.equal(comments.items.length,50)
  await f.state.toggle(group());assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
})

test('navigation collapses a rule and reopening refreshes all previously read source pages once without following a returned new cursor',async t=>{
  const f=mount(t);await f.open();f.expect(path('COMMENT',CURSOR),page('COMMENT',50,37,false));await f.state.loadFiles(group(),'COMMENT',true)
  f.state.setSourceOpen(ID,'MAIN',{target:{open:true}});const state=f.state.fileState(ID,'COMMENT'),oldIds=state.items.map(row=>row.item.id),response=deferred()
  f.props.active=false;assert.equal(f.state.opened.value[ID],undefined,'Parent disclosure state closes synchronously before native toggle delivery');assert.equal(f.state.staleSources.value[ID],true)
  f.state.openFiles(group(),disclosure(true));assert.equal(f.state.opened.value[ID],false,'A late open toggle cannot restore the hidden disclosure');await flush()
  f.expect(BASE,{items:[group({updatedAt:9})]});f.props.active=true;f.props.refreshVersion=1;await flush()
  assert.equal(f.calls.length,5);assert.equal(f.state.opened.value[ID],false);assert.equal(state.items.length,87)
  f.expect(BASE,{items:[group({updatedAt:9})]});f.expect(path('COMMENT'),response.promise)
  const tail=page('COMMENT',50,37,false);tail.items[36].item.outputPath='D:\\out\\refreshed-tail.txt'
  f.expect(path('COMMENT',CURSOR),tail);f.expect(path('MAIN'),page('MAIN'))
  f.state.openFiles(group(),disclosure(true));await flush();assert.equal(state.items.length,87);assert.equal(f.state.refreshingRange.value,true)
  const head=page();head.nextCursor='new opaque cursor not followed';response.resolve(head);await flush()
  assert.equal(f.calls.length,9);assert.equal(state.items.at(-1).item.outputPath,'D:\\out\\refreshed-tail.txt')
  assert.deepEqual(state.items.map(row=>row.item.id),oldIds);assert.equal(state.cursor,null);assert.equal(state.hasMore,false)
  assert.equal(f.state.sourceIsOpen(ID,'MAIN'),true);assert.equal(f.state.opened.value[ID],true);assert.equal(f.state.groups.value[0].updatedAt,9)
  assert.equal(f.state.staleSources.value[ID],undefined)
  assert.ok(f.calls.every(call=>(call.options.method??'GET')==='GET'))
})
test('new source heads appear first and reopen safe user scrolling from the head cursor without reading an unseen tail on return',async t=>{
  const f=mount(t);await f.open();f.expect(path('COMMENT',CURSOR),page('COMMENT',50,37,false));await f.state.loadFiles(group(),'COMMENT',true)
  const state=f.state.fileState(ID,'COMMENT');f.props.active=false;await flush()
  const head=page();head.items=Array.from({length:50},(_,index)=>row(100+index));head.totals={total:187,completed:187,skipped:0,duplicates:1,totalBytes:187*37,downloadedBytes:187*37,writtenBytes:187*37};head.nextCursor='new-head-next'
  const tail=page('COMMENT',50,37,false);tail.totals=head.totals
  f.expect(BASE,{items:[group()]});f.props.active=true;await flush()
  f.expect(BASE,{items:[group()]});f.expect(path('COMMENT'),head);f.expect(path('COMMENT',CURSOR),tail);f.expect(path('MAIN'),page('MAIN'))
  f.state.openFiles(group(),disclosure(true));await flush()
  assert.equal(state.items.length,137);assert.equal(state.items[0].item.id,'COMMENT-100');assert.equal(state.cursor,'new-head-next');assert.equal(state.hasMore,true)
  assert.equal(f.calls.length,9);assert.equal(f.calls.some(call=>call.path===path('COMMENT','new-head-next')),false)
  assert.deepEqual(state.pages,[null,CURSOR],'The unread continuation is not added to the successful request scope')
})
test('a failed previously loaded tail on return retains every file, retries the exact GET on click, and does not auto read the next source',async t=>{
  const f=mount(t);await f.open();f.expect(path('COMMENT',CURSOR),page('COMMENT',50,37,false));await f.state.loadFiles(group(),'COMMENT',true)
  const state=f.state.fileState(ID,'COMMENT'),before=JSON.stringify(state.items);f.props.active=false;await flush()
  f.expect(BASE,{items:[group()]});f.props.active=true;await flush()
  f.expect(BASE,{items:[group()]});f.expect(path('COMMENT'),page());f.expect(path('COMMENT',CURSOR),new Error('loaded tail offline'));f.state.openFiles(group(),disclosure(true));await flush()
  assert.equal(JSON.stringify(state.items),before);assert.equal(state.cursor,null);assert.equal(state.hasMore,false)
  assert.match(f.state.error.value,/loaded tail offline/);assert.equal(state.failedCursor,CURSOR);assert.equal(state.failedAppend,true)
  const count=f.calls.length;f.state.openFiles(group(),disclosure(false));f.state.openFiles(group(),disclosure(true));await flush();assert.equal(f.calls.length,count,'Reopening after a failed freshness read does not repeat GETs')
  f.expect(path('COMMENT',CURSOR),page('COMMENT',50,37,false));await f.state.retryFiles(group(),'COMMENT');assert.equal(state.error,'');assert.equal(state.items.length,87)
  assert.ok(f.calls.every(call=>(call.options.method??'GET')==='GET'))
})
test('ordinary task fallback cannot elevate an old source row to completed without a strict source-file receipt',async t=>{
  const pending=group({completed:88,state:'RUNNING'}),f=mount(t,pending);await flush()
  const initial=page();initial.items[49].item={...initial.items[49].item,state:'QUEUED',downloadedBytes:0,writtenBytes:0};initial.items[49].taskState='RUNNING';initial.totals.completed=86
  f.expect(path('COMMENT'),initial);f.expect(path('MAIN'),page('MAIN'));f.state.openFiles(pending,disclosure(true));await flush()
  const state=f.state.fileState(ID,'COMMENT'),before=JSON.stringify(state.items);f.props.active=false;await flush()
  const head=page();head.items=[row(90),...head.items.slice(0,49)];head.totals.total=88;head.totals.completed=87;head.totals.totalBytes=88*37;head.totals.downloadedBytes=88*37;head.totals.writtenBytes=88*37
  const taskRows=Array.from({length:50},(_,index)=>row(index+37)),task={id:TASK_B,title:'internal',channelTitle:'channel',state:'COMPLETED',pauseRequested:false,targetDirectory:'D:\\out',scope:'OBSERVED_SELECTION',
    total:50,completed:50,skipped:0,totalBytes:1850,error:null,createdAt:1,updatedAt:2,duplicatePolicy:'KEEP_DUPLICATES_V2',duplicateDirectory:'D:\\out\\重复媒体',duplicates:0,items:taskRows.map(row=>row.item),
    automation:{ruleId:ID,ruleVersion:2,ruleName:'name',manual:false,held:false,enabled:true,currentVersion:2,window:pending.window}}
  assert.equal(taskModule.validTask(task),true)
  f.expect(BASE,{items:[pending]});f.props.active=true;await flush()
  f.expect(BASE,{items:[pending]});f.expect(path('COMMENT'),head);f.expect('/transfers/download/tasks/'+TASK_B,task);f.state.openFiles(pending,disclosure(true));await flush()
  assert.equal(JSON.stringify(state.items),before);assert.equal(state.items[49].item.state,'QUEUED');assert.match(f.state.error.value,/保存回执.*来源明细/)
  const count=f.calls.length;await flush();assert.equal(f.calls.length,count);assert.ok(f.calls.every(call=>(call.options.method??'GET')==='GET'))
})
test('return waits for an old source GET, discards its receipt and starts one current read scope without overlap',async t=>{
  const f=mount(t);await flush();const response=deferred();f.expect(path('COMMENT'),response.promise);f.state.openFiles(group(),disclosure(true))
  f.props.active=false;await flush();f.props.active=true;f.props.refreshVersion=1;await flush();assert.equal(f.calls.length,2)
  f.expect(BASE,{items:[group()]});response.resolve(page());await flush();assert.equal(f.calls.length,3);assert.equal(f.state.fileState(ID,'COMMENT').loaded,false)
  f.expect(path('COMMENT'),page());f.expect(path('MAIN'),page('MAIN'));f.state.openFiles(group(),disclosure(true));await flush()
  assert.equal(f.calls.length,5);assert.equal(f.state.fileState(ID,'COMMENT').items.length,50);assert.equal(f.state.fileState(ID,'MAIN').items.length,2)
})

test('reopening stale files during the return summary GET queues one bounded source refresh and retains cached rows',async t=>{
  const f=mount(t);await f.open();const comments=f.state.fileState(ID,'COMMENT'),before=JSON.stringify(comments.items)
  f.props.active=false;await flush();const response=deferred();f.expect(BASE,response.promise);f.props.active=true;await flush()
  assert.equal(f.calls.length,4);assert.equal(f.state.opened.value[ID],undefined)
  f.state.openFiles(group(),disclosure(true));f.state.openFiles(group(),disclosure(true));await flush();assert.equal(f.calls.length,4);assert.equal(JSON.stringify(comments.items),before)
  const head=page();head.items[0].item.outputPath='D:\\out\\fresh-source.txt'
  f.expect(BASE,{items:[group()]});f.expect(path('COMMENT'),head);f.expect(path('MAIN'),page('MAIN'));response.resolve({items:[group()]});await flush()
  assert.equal(f.calls.length,7);assert.equal(comments.items[0].item.outputPath,'D:\\out\\fresh-source.txt');assert.equal(comments.items.length,50)
  assert.equal(comments.cursor,CURSOR);assert.equal(f.state.staleSources.value[ID],undefined);assert.ok(f.calls.every(call=>(call.options.method??'GET')==='GET'))
})
