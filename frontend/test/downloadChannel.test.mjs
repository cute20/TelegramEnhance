import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import * as vue from 'vue'
import {parse,compileScript} from 'vue/compiler-sfc'
import ts from 'typescript'
import * as channels from '../src/downloadChannel.ts'
const ID='00000000-0000-0000-0000-000000000001'
const listing=()=>({items:[{id:'-1001',title:'source'}],scope:'READABLE_CHANNELS',partial:true,warnings:['partial']})
const initial=()=>({id:ID,accountId:'1',channel:{id:'-1001',title:'source'},tags:[],keywords:[],revision:1,scanned:0,matched:0,unverified:0,rows:[],exhausted:false,canContinue:true,busy:false,limitReached:false,error:null,warnings:[],createdAt:1,expiresAt:600001})
const row=n=>({match:'MATCH',postLink:`https://t.me/synthetic/${n}`,comments:'NOT_READ',post:{key:`1:-1001:${n}`,accountId:'1',channelId:'-1001',mainMessageId:String(n),title:'title '+n,text:'主帖文字',mainMedia:[],warnings:[]}})
const progress=(n,eof=true)=>({...initial(),revision:n+1,scanned:n,matched:n,rows:Array.from({length:n},(_,i)=>row(100-i)),exhausted:eof,canContinue:!eof})
const flush=async()=>{await vue.nextTick();await new Promise(setImmediate)}
function mount(t,initialProps={}){
  const props=vue.reactive({authorized:true,available:true,accountId:'1',active:true,...initialProps}),calls=[],failures=[],hooks=[],mountedHooks=[],events=[],steps=props.active&&!props.readOnly?[{path:'/transfers/download/channels',method:'GET',value:listing()}]:[]
  const api=async(path,options={})=>{calls.push({path,options});const next=steps.shift();try{assert.ok(next,path);assert.equal(path,next.path);assert.equal(options.method??'GET',next.method??'POST');if(next.body)assert.deepEqual(options.body,next.body)}catch(e){failures.push(e);throw e}if(next.value instanceof Error)throw next.value;return await next.value}
  const parsed=parse(readFileSync(new URL('../src/components/DownloadChannelPreview.vue',import.meta.url),'utf8'),{filename:'DownloadChannelPreview.vue'})
  const code=ts.transpileModule(compileScript(parsed.descriptor,{id:'channel-test'}).content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
  const modules={vue:{...vue,onMounted:f=>mountedHooks.push(f),onBeforeUnmount:f=>hooks.push(f)},'../api':{api,ApiError:class extends Error{}},'../downloadChannel':channels},module={exports:{}}
  new Function('require','module','exports',code)(name=>name.endsWith('.vue')?{}:modules[name],module,module.exports)
  const scope=vue.effectScope(),state=scope.run(()=>module.exports.default.setup(props,{expose(){},emit:(...v)=>events.push(v)}));let disposed=false
  const destroy=()=>{if(disposed)return;disposed=true;hooks.forEach(f=>f());scope.stop()}
  t.after(()=>{destroy();assert.deepEqual(failures,[]);assert.equal(steps.length,0)})
  return {state,props,calls,events,destroy,mounted:()=>mountedHooks.forEach(f=>f()),expect:(path,value,method='POST',body)=>steps.push({path,value,method,body})}
}
test('readonly rule channel browsing preserves filters, reads at most ten steps and cannot prepare a batch',async t=>{
  const f=mount(t,{readOnly:true,initialSource:{source:'-1001',tags:'旅行',keywords:''}});await flush()
  assert.equal(f.calls.length,0);assert.equal(f.state.tags.value,'旅行')
  f.expect('/transfers/download/channels/preview',{...initial(),tags:['旅行']},'POST',{source:'-1001',tags:'旅行',keywords:''})
  for(let n=1;n<=10;n++)f.expect('/transfers/download/channels/preview/'+ID+'/next',{...progress(n,false),tags:['旅行'],rows:progress(n,false).rows.map(r=>({...r,post:{...r.post,mainCoverage:'COMPLETE'}}))},'POST',{revision:n})
  f.mounted();await flush();await flush()
  assert.equal(f.calls.length,11);assert.equal(f.state.scan.value.scanned,10);assert.equal(f.state.running.value,false)
  f.state.selectPost(f.state.scan.value.rows[0]);f.state.selectBatch();assert.deepEqual(f.state.selectedPosts.value,[])
  assert.ok(f.calls.every(call=>call.path.includes('/preview')));assert.match(f.state.notice.value,/继续浏览/)
})
function prepare(f,result=progress(1)){f.state.source.value='-1001';f.expect('/transfers/download/channels/preview',initial(),'POST',{source:'-1001',tags:'',keywords:''});f.expect('/transfers/download/channels/preview/'+ID+'/next',result,'POST',{revision:1})}
test('hidden channel mode does not make reads and only explicit mode activation loads readable sources',async t=>{const f=mount(t,{active:false});await flush();assert.equal(f.calls.length,0);f.expect('/transfers/download/channels',listing(),'GET');f.props.active=true;await flush();assert.equal(f.calls.length,1)})
test('channel scan uses only preview APIs and never imports a post or starts a transfer',async t=>{const f=mount(t);await flush();prepare(f);await f.state.begin();assert.equal(f.state.scan.value.exhausted,true);assert.equal(f.state.scan.value.scanned,1);assert.equal(f.state.scan.value.rows[0].comments,'NOT_READ');assert.equal(f.calls.length,3);assert.equal(f.calls.some(c=>c.path.includes('/tasks')||c.path.includes('/library')),false)})
test('stop accepts the current readonly step but never starts another until explicitly continued',async t=>{const f=mount(t);await flush();let release;prepare(f,new Promise(r=>release=r));const pending=f.state.begin();await flush();f.state.stop();release(progress(1,false));await pending;assert.equal(f.state.scan.value.scanned,1);assert.equal(f.state.running.value,false);assert.equal(f.calls.length,3);f.expect('/transfers/download/channels/preview/'+ID+'/next',progress(2),'POST',{revision:2});await f.state.continueScan();assert.equal(f.state.scan.value.rows.length,2)})
test('unknown step response is not replayed and GET resolves the current revision before continue',async t=>{const f=mount(t);await flush();prepare(f,new Error('timeout'));await f.state.begin();assert.equal(f.state.needsCheck.value,true);await f.state.continueScan();assert.equal(f.calls.length,3);f.expect('/transfers/download/channels/preview/'+ID,progress(1,false),'GET');await f.state.check();assert.equal(f.state.needsCheck.value,false);f.expect('/transfers/download/channels/preview/'+ID+'/next',progress(2),'POST',{revision:2});await f.state.continueScan();assert.equal(f.state.scan.value.scanned,2);assert.equal(f.calls.filter(c=>c.options.method==='POST').length,3)})
test('empty non-EOF results continue rather than reporting an empty complete channel',async t=>{const f=mount(t);await flush();const empty={...initial(),revision:2};prepare(f,empty);f.expect('/transfers/download/channels/preview/'+ID+'/next',{...progress(1),revision:3},'POST',{revision:2});await f.state.begin();assert.equal(f.state.scan.value.exhausted,true);assert.equal(f.calls.length,4)})
test('filter changes invalidate a late step and preserve no rows from the old search',async t=>{const f=mount(t);await flush();let release;prepare(f,new Promise(r=>release=r));const pending=f.state.begin();await flush();f.state.tags.value='旅行';release(progress(1));await pending;assert.equal(f.state.scan.value,null);assert.equal(f.state.running.value,false);assert.equal(f.calls.length,3)})
test('authorization changes and unmount discard old results and issue no further scan',async t=>{const f=mount(t);await flush();let release;prepare(f,new Promise(r=>release=r));const pending=f.state.begin();await flush();f.props.authorized=false;await flush();release(progress(1));await pending;assert.equal(f.state.scan.value,null);assert.equal(f.calls.length,3)})
test('closing the channel view during a step discards its response and never requests another',async t=>{const f=mount(t);await flush();let release;prepare(f,new Promise(r=>release=r));const pending=f.state.begin();await flush();f.destroy();release(progress(1,false));await pending;assert.equal(f.state.scan.value.scanned,0);assert.equal(f.calls.length,3)})
test('opening a selected source stops channel scanning and emits only its native post link',async t=>{const f=mount(t);await flush();prepare(f);await f.state.begin();f.state.openPost(f.state.scan.value.rows[0]);assert.deepEqual(f.events,[['openPost','https://t.me/synthetic/100']]);assert.equal(f.calls.length,3);f.state.openPost({...row(100),postLink:'javascript:bad'});assert.equal(f.events.length,1)})
test('hiding mid-step retains the preview but requires GET before reusing its stale client position',async t=>{const f=mount(t);await flush();let release;prepare(f,new Promise(r=>release=r));const pending=f.state.begin();await flush();f.props.active=false;await flush();release(progress(1,false));await pending;assert.equal(f.state.needsCheck.value,true);f.expect('/transfers/download/channels/preview/'+ID,progress(1,false),'GET');f.props.active=true;await flush();assert.equal(f.state.scan.value.revision,2);assert.equal(f.state.running.value,false);assert.equal(f.calls.length,4)})
test('same-account session metadata cannot disguise malformed rows, duplicate sources or incorrect progress',()=>{assert.equal(channels.validChannelScan(initial(),'1'),true);assert.equal(channels.validChannelScan(progress(1),'1',initial(),true),true);assert.equal(channels.validChannelScan(progress(1),'2'),false);const invalid=progress(1);invalid.rows[0].post.channelId='-999';assert.equal(channels.validChannelScan(invalid,'1'),false);const backwards=progress(1);backwards.revision=1;assert.equal(channels.validChannelScan(backwards,'1',initial(),true),false);const wrong=progress(1);wrong.matched=0;assert.equal(channels.validChannelScan(wrong,'1'),false);const duplicate=listing();duplicate.items.push(duplicate.items[0]);assert.equal(channels.validSourceChannels(duplicate),false);const nil=listing();nil.items=[null];assert.equal(channels.validSourceChannels(nil),false)})
test('reaching a preview limit never legitimizes an exhausted range',()=>{const limit={...progress(1,false),limitReached:true,canContinue:false};assert.equal(channels.validChannelScan(limit,'1'),true);limit.exhausted=true;assert.equal(channels.validChannelScan(limit,'1'),false);const unsafe=row(100);unsafe.postLink='https://t.me.evil.example/synthetic/1';const value={...progress(1),rows:[unsafe]};assert.equal(channels.validChannelScan(value,'1'),false)})

test('channel name search supports partial text, width/case normalization, multiple terms and ID fragments',()=>{
  const items=[{id:'-1001',title:'我的 Photo 资源'},{id:'-2002',title:'另一个频道'},{id:'-3003',title:'ＰＨＯＴＯ 收藏'}]
  assert.deepEqual(channels.filterSourceChannels(items,'photo').map(c=>c.id),['-1001','-3003'])
  assert.deepEqual(channels.filterSourceChannels(items,'我的 ＰＨＯＴＯ').map(c=>c.id),['-1001'])
  assert.deepEqual(channels.filterSourceChannels(items,'2002').map(c=>c.id),['-2002'])
  assert.deepEqual(channels.filterSourceChannels(items,'不存在'),[])
  assert.deepEqual(channels.filterSourceChannels(items,'  '),items)
})

test('changing channels clears previous post filters and results without automatically scanning',async t=>{
  const f=mount(t);await flush();prepare(f);await f.state.begin();f.state.tags.value='旅行';f.state.keywords.value='旧频道';f.state.source.value='-2002';await flush()
  assert.equal(f.state.tags.value,'');assert.equal(f.state.keywords.value,'');assert.equal(f.state.hasFilters.value,false);assert.equal(f.state.scan.value,null);assert.match(f.state.notice.value,/已切换频道并清空/);assert.equal(f.calls.length,3)
})

test('clearing post filters invalidates old results and the next scan explicitly sends no filters',async t=>{
  const f=mount(t);await flush();f.state.source.value='-1001';f.state.tags.value='旅行';f.state.keywords.value='周末'
  f.state.scan.value={...progress(1),tags:['旅行'],keywords:['周末']};assert.match(f.state.filterSummary.value,/旅行/);f.state.clearFilters()
  assert.equal(f.state.scan.value,null);assert.equal(f.state.hasFilters.value,false);assert.match(f.state.filterSummary.value,/展示全部已读取主帖/);assert.equal(f.calls.length,1)
  prepare(f);await f.state.begin();assert.equal(f.state.scan.value.scanned,1)
})

test('scan summary distinguishes excluded posts and unverified albums from actual matches',async t=>{
  const f=mount(t);await flush();f.state.source.value='-1001';f.state.tags.value='旅行';f.state.keywords.value='周末'
  f.state.scan.value={...initial(),tags:['旅行'],keywords:['周末'],scanned:200,matched:0,unverified:1,rows:[{...row(99),match:'UNVERIFIED'}]}
  assert.equal(f.state.excludedCount.value,199);assert.match(f.state.filterSummary.value,/标签：旅行；关键词：周末/);assert.equal(f.state.hasFilters.value,true);assert.equal(f.calls.length,1)
})

test('batch selection only uses verified matches, caps at20 and respects a pending batch lock',async t=>{
  const f=mount(t);await flush();f.state.source.value='-1001';const value=progress(25);value.rows.forEach(row=>row.post.mainCoverage='COMPLETE');value.rows[0].match='UNVERIFIED';value.rows[1].post.mainCoverage='PARTIAL';f.state.scan.value=value
  f.state.selectPost(value.rows[0]);f.state.selectPost(value.rows[1]);assert.deepEqual(f.state.selectedPosts.value,[]);f.state.selectBatch();assert.equal(f.state.selectedPosts.value.length,20);assert.equal(f.state.selectedPosts.value.includes(value.rows[0].post.key),false)
  f.state.lockBatch(true);f.state.selectPost(value.rows[2]);await f.state.begin();assert.equal(f.state.selectedPosts.value.length,20);assert.equal(f.calls.length,1);f.state.lockBatch(false);f.state.selectPost(value.rows[2]);assert.equal(f.state.selectedPosts.value.length,19)
})
