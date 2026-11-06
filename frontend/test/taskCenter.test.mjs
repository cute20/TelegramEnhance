import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import {parse,compileScript,compileTemplate} from 'vue/compiler-sfc'
import ts from 'typescript'
import * as center from '../src/taskCenter.ts'
import * as library from '../src/library.ts'
const ID='00000000-0000-0000-0000-000000000001',SECOND='00000000-0000-0000-0000-000000000002'
const row=(overrides={})=>({id:ID,kind:'download',category:'download',title:'Synthetic',subtitle:'channel',state:'RUNNING',statusGroup:'active',createdAt:1,updatedAt:2,totalFiles:1,resolvedFiles:0,totalBytes:100,transferredBytes:38,writtenBytes:10,speedBytesPerSecond:null,etaSeconds:null,phase:'DOWNLOADING',detailId:ID,actions:['pause'],error:null,targetDirectory:'D:\\out',...overrides})
const page=(items=[row()],overrides={})=>({items,total:items.length,counts:{active:1,attention:2,waiting:3,completed:4},warnings:['父任务只计一次'],...overrides})
const flush=async()=>{await vue.nextTick();await new Promise(setImmediate)}
class ApiError extends Error{constructor(message,status){super(message);this.status=status}}
const {descriptor}=parse(readFileSync(new URL('../src/components/TransferTasks.vue',import.meta.url),'utf8'),{filename:'TransferTasks.vue'})
const script=compileScript(descriptor,{id:'center-test'}),template=compileTemplate({source:descriptor.template.content,filename:'TransferTasks.vue',id:'center-test',compilerOptions:{bindingMetadata:script.bindings,expressionPlugins:['typescript']}})
assert.deepEqual(template.errors,[])
const transpile=code=>ts.transpileModule(code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
function mount(t,initial=[],initialProps={}){
  const steps=[...initial],calls=[],failures=[],hooks=[],timers=[],events=[]
  const props=vue.reactive({authorized:true,available:true,accountId:'1',active:true,...initialProps})
  const api=async(path,options={})=>{calls.push({path,options});const step=steps.shift();try{assert.ok(step,path);assert.equal(path,step.path);assert.equal(options.method??'GET',step.method??'GET')}catch(cause){failures.push(cause);throw cause}if(step.value instanceof Error)throw step.value;return typeof step.value==='function'?step.value(options):step.value}
  const modules={vue:{...vue,onBeforeUnmount:hook=>hooks.push(hook),withCtx:fn=>fn,withDirectives:node=>node},'../api':{api,ApiError},'../taskCenter':center,'../library':library}
  const require=name=>name.endsWith('.vue')?{__esModule:true,default:{__name:name.split('/').at(-1).replace('.vue','')}}:(assert.ok(Object.hasOwn(modules,name),name),modules[name])
  const source={exports:{}},rendered={exports:{}}
  new Function('require','module','exports','setTimeout','clearTimeout',transpile(script.content))(require,source,source.exports,(callback,delay)=>{const timer={callback,delay,active:true};timers.push(timer);return timer},timer=>{if(timer)timer.active=false})
  new Function('require','module','exports',transpile(template.code))(require,rendered,rendered.exports)
  const scope=vue.effectScope(),state=scope.run(()=>source.exports.default.setup(props,{expose(){},emit:(...args)=>events.push(args)}))
  let destroyed=false;const destroy=()=>{if(!destroyed){destroyed=true;hooks.forEach(hook=>hook());scope.stop()}}
  t.after(()=>{destroy();assert.deepEqual(failures,[]);assert.equal(steps.length,0);assert.equal(timers.some(timer=>timer.active),false)})
  return {state,props,calls,events,timers,destroy,expect:(path,value,method)=>steps.push({path,value,method}),render:()=>rendered.exports.render({},[],props,vue.proxyRefs(state),{},{})}
}
function nodes(node){if(!node||typeof node!=='object')return [];if(Array.isArray(node))return node.flatMap(nodes);return [node,...nodes(Array.isArray(node.children)?node.children:[])]}
const text=node=>typeof node==='string'?node:Array.isArray(node)?node.map(text).join(''):node&&typeof node==='object'?text(node.children):''
const scan=(category='channel-scan',state='RUNNING',actions=['pause'])=>row({kind:'scan',category,state,actions,statusGroup:state==='COMPLETED'?'completed':['EXPIRED','NEEDS_REVIEW'].includes(state)?'attention':state==='PAUSED'||state==='WAITING_STEP'?'waiting':'active',detailId:SECOND,totalFiles:0,resolvedFiles:7,totalBytes:0,transferredBytes:0,writtenBytes:0,phase:category==='import'?'IMPORTING':category==='preparation'?'PREPARING':'SCANNING'})
const scanDetail=(value=scan(),overrides={})=>({id:value.id,sourceId:'original-run-or-scan',category:value.category,title:value.title,subtitle:value.subtitle,state:value.state,createdAt:1,updatedAt:2,total:value.totalFiles,processed:value.resolvedFiles,bytes:0,phase:value.phase,detailId:value.detailId,directory:value.targetDirectory,error:null,revision:9,actions:value.actions,current:true,...overrides})
const listing='/transfers/center?kind=all&status=all&limit=20&offset=0'
test('task center validators reject impossible progress and keep unknown scan totals honest',()=>{
  assert.equal(center.validCenterPage(page()),true);assert.equal(center.validCenterPage(page([row({transferredBytes:101})])),false);assert.equal(center.validCenterPage(page([row(),row()])),false)
  assert.equal(center.validCenterPage(page([row({state:'COMPLETED',resolvedFiles:0})])),false)
  assert.equal(center.centerProgress(row({kind:'scan',totalFiles:0,resolvedFiles:17})),null)
  assert.equal(center.centerProgress(row({state:'COMPLETED',resolvedFiles:1,transferredBytes:0,writtenBytes:0})),100,'history skips remain completed without inventing native bytes')
  assert.equal(center.centerProgress(row({kind:'upload',state:'NEEDS_REVIEW',transferredBytes:100})),100,'100% bytes do not change the task state')
})
test('server filters and pages drive one table while global counts remain intact',async t=>{
  const f=mount(t,[{path:listing,value:page([row()],{total:41})}]);await flush();assert.equal(f.state.selected.value,'all');assert.deepEqual(f.state.page.value.counts,{active:1,attention:2,waiting:3,completed:4})
  f.expect('/transfers/center?kind=all&status=all&limit=20&offset=20',page([row({id:SECOND,detailId:SECOND})],{total:41}));f.state.paginate(1);await flush();assert.equal(f.state.offset.value,20)
  f.expect('/transfers/center?kind=all&status=attention&limit=20&offset=0',page([row({state:'FAILED',statusGroup:'attention'})]));f.state.filter('attention');await flush();assert.equal(f.state.offset.value,0);assert.equal(f.state.page.value.total,1)
  f.expect('/transfers/center?kind=scan&status=attention&limit=20&offset=0',page([]));f.state.select('scan');await flush();assert.deepEqual(f.events,[['update:category','scan']]);assert.equal(f.state.page.value.counts.completed,4)
})
test('only an explicitly opened task gets its existing receipt-aware detail component',async t=>{
  const upload=row({kind:'upload',category:'upload',state:'NEEDS_REVIEW',statusGroup:'attention',phase:'WAITING_RECEIPT',actions:['recover-pending','retry-check']})
  const f=mount(t,[{path:listing,value:page([upload])}]);await flush();assert.equal(nodes(f.render()).some(node=>node.type?.__name==='UploadTasks'),false)
  f.state.openDetail(upload);const detail=nodes(f.render()).find(node=>node.type?.__name==='UploadTasks');assert.equal(detail.props['focus-id'],ID);assert.equal(nodes(f.render()).some(node=>node.type?.__name==='DownloadTasks'),false);assert.equal(f.calls.length,1)
  f.state.openDetail(upload);const hidden=nodes(f.render()).find(node=>node.type?.__name==='UploadTasks');assert.equal(hidden.props.active,false);assert.equal(hidden.key,detail.key,'closing retains unknown retry operation state in the same component')
  f.expect('/transfers/center?kind=scan&status=all&limit=20&offset=0',page([]));f.state.select('scan');await flush();assert.ok(nodes(f.render()).find(node=>node.type?.__name==='UploadTasks'),'filtering retains the already opened account-scoped detail')
})
test('pause-all unknown outcomes retain the original operation for read-only lookup without automatic replay',async t=>{
  const f=mount(t,[{path:listing,value:page()}]);await flush();f.expect('/transfers/center/pause-all',new Error('lost response'),'POST');await f.state.pauseAll();assert.equal(f.state.pauseUnknown.value,true);const operation=f.state.pauseOperation.value;assert.match(operation,/^[0-9a-f-]{36}$/)
  const receipt={operationId:operation,state:'COMPLETED',accepted:[{category:'download',id:ID,action:'pause',message:null}],failed:[{category:'upload-rule',id:SECOND,action:'disable',message:'revision changed'}],pending:[],warnings:['单次有界目录准备不支持中断，已记录此能力限制'],createdAt:1,updatedAt:2}
  f.expect('/transfers/center/pause-all/'+operation,receipt);f.expect(listing,page());await f.state.lookupPause();await flush();assert.equal(f.state.pauseReceipt.value.failed.length,1);assert.equal(f.state.pauseUnknown.value,false);assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
})
test('late account responses cannot populate another account task center',async t=>{
  let resolve;const old=new Promise(yes=>resolve=yes),f=mount(t,[{path:listing,value:old}]);f.expect(listing,page([row({id:SECOND,detailId:SECOND})]));f.props.accountId='2';await vue.nextTick();resolve(page([row()]));await flush();assert.equal(f.state.page.value.items[0].id,SECOND)
})
test('an individual action with an unknown outcome keeps its row and never repeats the mutation automatically',async t=>{
  const f=mount(t,[{path:listing,value:page()}]);await flush();f.expect('/transfers/center/action',{item:{id:SECOND,state:'PAUSED'}},'POST');await f.state.action(row(),'pause');assert.match(f.state.error.value,/回执无法确认/);assert.equal(f.state.page.value.items[0].state,'RUNNING');assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
  f.expect(listing,page([row({state:'PAUSED',statusGroup:'waiting',actions:['resume']})]));await f.state.load();assert.equal(f.state.page.value.items[0].state,'PAUSED');assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
})

test('scan controls accept only supported current checkpoints while historical and bounded rule preparations stay read-only',()=>{
  for(const category of ['channel-scan','preparation','import']){
    assert.equal(center.validCenterRow(scan(category)),true)
    assert.equal(center.validCenterRow(scan(category,'PAUSED',['resume'])),true)
    for(const state of ['EXPIRED','NEEDS_REVIEW','COMPLETED']){
      assert.equal(center.validCenterRow(scan(category,state,[])),true)
      assert.equal(center.validCenterRow(scan(category,state,['resume'])),false)
    }
    assert.equal(center.centerActionLabel(scan(category,'PAUSED',['resume']),'resume'),'恢复检查点')
  }
  assert.equal(center.validCenterRow(scan('rule-preparation','RUNNING',[])),true)
  assert.equal(center.validCenterRow(scan('rule-preparation','RUNNING',['pause'])),false)
})

for(const category of ['channel-scan','preparation','import'])test(category+' pauses and resumes its history record without dispatching another scan step',async t=>{
  const initial=scan(category),paused=scan(category,'PAUSED',['resume']),resumed=scan(category,'WAITING_STEP',['pause'])
  const f=mount(t,[{path:listing,value:page([initial])}]);await flush()
  f.expect('/transfers/center/action',{item:{id:ID,state:'PAUSING'}},'POST');f.expect(listing,page([paused]))
  await f.state.action(initial,'pause');await flush();assert.match(f.state.notice.value,/当前有界读取结束后/)
  assert.equal(f.state.page.value.items[0].actions[0],'resume')
  f.expect('/transfers/center/action',{item:{id:ID,state:'WAITING_STEP'}},'POST');f.expect(listing,page([resumed]))
  await f.state.action(paused,'resume');await flush();assert.match(f.state.notice.value,/尚未自动读取/);assert.match(f.state.notice.value,/原检查点和修订号/)
  assert.deepEqual(f.calls.filter(call=>call.options.method==='POST').map(call=>call.options.body),[{category,id:ID,action:'pause'},{category,id:ID,action:'resume'}])
  assert.equal(f.calls.some(call=>/\/(next|step)/.test(call.path)),false)
  assert.equal(f.state.page.value.items[0].detailId,SECOND)
})

test('scan detail distinguishes checkpoint control, read-only history and non-interruptible rule preparation',async t=>{
  const f=mount(t,[{path:listing,value:page([scan()])}]);await flush();f.expect('/transfers/center/scans/'+ID,scanDetail());f.state.openDetail(scan());await flush()
  let detail=nodes(f.render()).find(node=>node.props?.class==='scan-detail')
  assert.match(text(detail),/恢复只解除暂停门禁/);assert.match(text(detail),/原检查点和修订号/);assert.doesNotMatch(text(detail),/没有单独暂停扫描/)
  assert.match(text(detail),new RegExp('原检查点：'+SECOND));assert.match(text(detail),new RegExp('历史记录：'+ID))
  const historical={...scan('channel-scan','EXPIRED',[]),id:'00000000-0000-0000-0000-000000000003'};f.expect('/transfers/center/scans/'+historical.id,scanDetail(historical,{current:false}));f.state.showDetail(historical);await flush()
  detail=nodes(f.render()).filter(node=>node.props?.class==='scan-detail').at(-1);assert.match(text(detail),/只读历史记录/);assert.match(text(detail),/不能恢复此检查点/)
  const preparation=scan('rule-preparation','RUNNING',[]);f.expect('/transfers/center/scans/'+ID,scanDetail(preparation));f.state.showDetail(preparation);await flush()
  detail=nodes(f.render()).filter(node=>node.props?.class==='scan-detail').at(-1);assert.match(text(detail),/单次有界目录准备不支持中断/);assert.match(text(detail),/能力限制/)
  assert.equal(f.calls.length,4)
})

test('a completed bounded scan keeps unknown scope totals without inventing a full-channel percentage',()=>{
  assert.equal(center.centerProgress(scan('channel-scan','COMPLETED',[])),null)
  assert.match(center.centerScanHint(scan('channel-scan','COMPLETED',[])),/不推断整个频道或目录的总量/)
})

test('ended counts include cancellation without labelling cancelled rows as successful completion',async t=>{
  const completed=row({state:'COMPLETED',statusGroup:'completed',resolvedFiles:1,actions:[]}),cancelled=row({id:SECOND,detailId:SECOND,state:'CANCELLED',statusGroup:'completed',actions:[]})
  const f=mount(t,[{path:listing,value:page([completed,cancelled])}]);await flush();let tree=f.render()
  const ended=nodes(tree).find(node=>node.type==='button'&&text(node).includes('已结束'))
  assert.ok(ended);assert.match(ended.props.title,/已完成和已取消/)
  const states=nodes(tree).filter(node=>String(node.props?.class??'').includes('center-state'))
  assert.deepEqual(states.map(text),['已完成','已取消']);assert.ok(states[1].props.class.includes('cancelled'));assert.equal(states[1].props.class.includes('completed'),false)
  f.expect('/transfers/center?kind=all&status=completed&limit=20&offset=0',page([completed,cancelled]));f.state.filter('completed');await flush()
  tree=f.render();assert.equal(text(nodes(tree).find(node=>node.type==='option'&&node.props?.value==='completed')),'已结束')
})

test('scan history details GET only on explicit open or refresh and show source identity, revision and read-only ownership',async t=>{
  const value=scan(),f=mount(t,[{path:listing,value:page([value])}]);await flush();assert.equal(f.calls.length,1)
  f.expect(listing,page([value]));await f.state.load(true);assert.equal(f.calls.length,2)
  f.expect('/transfers/center/scans/'+ID,scanDetail(value));f.state.openDetail(value);await flush()
  let snapshot=nodes(f.render()).find(node=>node.props?.class==='scan-snapshot')
  assert.match(text(snapshot),/当前会话记录/);assert.match(text(snapshot),/original-run-or-scan/);assert.match(text(snapshot),/修订号9/)
  assert.equal(f.state.detail.value.detailId,SECOND)
  f.expect(listing,page([value]));await f.state.load(true);assert.equal(f.calls.filter(call=>call.path.includes('/scans/')).length,1)
  f.expect('/transfers/center/scans/'+ID,scanDetail(value,{current:false,actions:[],state:'EXPIRED',revision:10}));await f.state.loadScanDetail(value);await flush()
  snapshot=nodes(f.render()).find(node=>node.props?.class==='scan-snapshot');assert.match(text(snapshot),/历史记录 · 只读/);assert.match(text(snapshot),/修订号10/)
  assert.match(text(nodes(f.render()).find(node=>node.props?.class==='scan-detail')),/这是只读历史记录/)
  assert.equal(f.calls.every(call=>(call.options.method??'GET')==='GET'),true)
  assert.equal(center.validScanDetail(scanDetail(value,{id:SECOND}),value),false)
  assert.equal(center.validScanDetail(scanDetail(value,{current:false,actions:['resume']}),value),false)
})

test('unregistered dynamic scans do not GET history, legacy import lookup is friendly and closed detail rejects late receipts',async t=>{
  const value=scan(),f=mount(t,[{path:listing,value:page([value])}]);await flush()
  f.state.showDetail(scan('download-rule-scan','WAITING_STEP',[]));await flush();assert.equal(f.calls.length,1)
  const legacy=scan('import','COMPLETED',[]);f.expect('/transfers/center/scans/'+ID,new ApiError('missing history',400));f.state.showDetail(legacy);await flush()
  assert.match(text(f.render()),/可能属于旧版导入记录/)
  let release;f.expect('/transfers/center/scans/'+ID,new Promise(resolve=>release=resolve));f.state.showDetail(value);f.state.closeDetail()
  release(scanDetail(value));await flush();assert.equal(f.state.scanDetails.value['channel-scan:'+ID],undefined)
  assert.equal(f.calls.every(call=>(call.options.method??'GET')==='GET'),true)
})

const pausedDownload=(overrides={})=>row({state:'PAUSED',statusGroup:'waiting',actions:['resume','delete'],...overrides})
const deleteReceipt=(overrides={})=>({item:null,check:{id:ID,category:'download',deleted:true},warning:null,...overrides})
function attachDeleteDialog(f){let shown=0;f.state.deleteDialog.value={showModal(){shown++},close(){}};return()=>shown}

test('only paused standalone downloads accept advertised deletion and its receipt must bind category and task identity',()=>{
  const value=pausedDownload();assert.equal(center.validCenterRow(value),true);assert.equal(center.centerActionLabel(value,'delete'),'删除');assert.equal(center.canDeleteCenterTask(value),true)
  for(const mutate of [{kind:'upload',category:'upload'},{category:'batch'},{category:'rule-download'},{state:'FAILED'},{state:'RUNNING'},{state:'COMPLETED',resolvedFiles:1}])assert.equal(center.validCenterRow(pausedDownload(mutate)),false)
  assert.equal(center.validCenterDelete(deleteReceipt(),value),true)
  for(const receipt of [null,{item:null},deleteReceipt({check:{id:SECOND,category:'download',deleted:true}}),deleteReceipt({check:{id:ID,category:'upload',deleted:true}}),deleteReceipt({check:{id:ID,category:'download',deleted:false}}),deleteReceipt({item:{id:ID}})])assert.equal(center.validCenterDelete(receipt,value),false)
})

test('the delete button opens a reviewable native confirmation and cancelling performs no mutation',async t=>{
  const value=pausedDownload(),f=mount(t,[{path:listing,value:page([value])}]);await flush();const shown=attachDeleteDialog(f)
  const button=nodes(f.render()).find(node=>node.type==='button'&&text(node)==='删除');assert.ok(button);await button.props.onClick()
  assert.equal(shown(),1);assert.equal(f.calls.length,1)
  const dialog=nodes(f.render()).find(node=>node.type==='dialog');assert.equal(dialog.props['aria-labelledby'],'center-delete-title')
  assert.match(text(dialog),/Synthetic.*删除这条任务记录及未完成的临时文件，已下载完成的文件会保留；删除后不能继续该任务/s)
  nodes(dialog).find(node=>node.type==='button'&&text(node)==='取消').props.onClick();await f.state.confirmDelete()
  assert.equal(f.state.deleteConfirmation.value,null);assert.equal(f.calls.length,1);assert.equal(f.state.page.value.items.length,1)
})

test('confirming deletion sends one identity-bound action then refreshes counts and closes the deleted task detail',async t=>{
  const value=pausedDownload(),f=mount(t,[{path:listing,value:page([value])}]);await flush();attachDeleteDialog(f);f.state.showDetail(value);assert.equal(f.state.cachedDetails.value.length,1)
  await f.state.action(value,'delete');const dialog=nodes(f.render()).find(node=>node.type==='dialog')
  f.expect('/transfers/center/action',deleteReceipt(),'POST');f.expect(listing,page([],{counts:{active:0,attention:0,waiting:0,completed:4}}))
  const confirm=nodes(dialog).find(node=>node.type==='button'&&text(node)==='确认删除');await confirm.props.onClick();await f.state.confirmDelete();await flush()
  assert.deepEqual(f.calls.find(call=>call.options.method==='POST').options.body,{category:'download',id:ID,action:'delete'})
  assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1);assert.equal(f.state.page.value.counts.waiting,0);assert.equal(f.state.page.value.total,0)
  assert.equal(f.state.detail.value,null);assert.deepEqual(f.state.cachedDetails.value,[]);assert.match(f.state.notice.value,/任务记录已删除.*文件已保留/)
})

test('deleting the last row of the last page reloads the previous valid page and global counts',async t=>{
  const value=pausedDownload(),other=row({id:SECOND,detailId:SECOND}),last='/transfers/center?kind=all&status=all&limit=20&offset=20'
  const f=mount(t,[{path:listing,value:page([other],{total:21})}]);await flush();f.expect(last,page([value],{total:21}));f.state.paginate(1);await flush();attachDeleteDialog(f)
  await f.state.action(value,'delete');f.expect('/transfers/center/action',deleteReceipt(),'POST');f.expect(last,page([],{total:20,counts:{active:20,attention:0,waiting:0,completed:0}}));f.expect(listing,page([other],{total:20,counts:{active:20,attention:0,waiting:0,completed:0}}))
  await f.state.confirmDelete();await flush();assert.equal(f.state.offset.value,0);assert.equal(f.state.pageNumber.value,1);assert.equal(f.state.page.value.total,20);assert.equal(f.state.page.value.counts.waiting,0)
  assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
})

test('a lost or invalid delete acknowledgement retains the row and only a readonly refresh can unlock a new explicit deletion',async t=>{
  for(const result of [new Error('lost delete acknowledgement'),new ApiError('task cleanup unavailable',400),deleteReceipt({check:{id:SECOND,category:'download',deleted:true}})]){
    const value=pausedDownload(),f=mount(t,[{path:listing,value:page([value])}]);await flush();attachDeleteDialog(f);await f.state.action(value,'delete')
    f.expect('/transfers/center/action',result,'POST');await f.state.confirmDelete();assert.equal(f.state.page.value.items[0].id,ID);assert.match(f.state.error.value,/请刷新任务列表核对删除结果.*不会自动重试删除/)
    await f.state.action(value,'delete');await f.state.confirmDelete();assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1);assert.equal(f.state.deleteConfirmation.value,null)
    const remove=nodes(f.render()).find(node=>node.type==='button'&&text(node)==='删除');assert.equal(remove.props.disabled,true)
    f.expect(listing,page([value]));await f.state.load();assert.equal(f.calls.filter(call=>call.options.method==='POST').length,1)
    await f.state.action(value,'delete');assert.ok(f.state.deleteConfirmation.value);f.state.cancelDelete()
  }
})

test('a task that changes state during confirmation cannot be deleted from the old snapshot',async t=>{
  const value=pausedDownload(),f=mount(t,[{path:listing,value:page([value])}]);await flush();attachDeleteDialog(f);await f.state.action(value,'delete')
  f.expect(listing,page([row()]));await f.state.load();assert.equal(f.state.deleteConfirmation.value,null);await f.state.confirmDelete()
  assert.equal(f.calls.every(call=>(call.options.method??'GET')==='GET'),true);assert.match(f.state.notice.value,/任务状态已变化/)
})

test('account and panel lifecycle changes during confirmation prevent the old dialog from opening or deleting',async t=>{
  for(const change of ['account','offline','inactive','unauthorized','dispose']){
    const value=pausedDownload(),f=mount(t,[{path:listing,value:page([value])}]);await flush();const shown=attachDeleteDialog(f),pending=f.state.action(value,'delete')
    if(change==='account'){f.expect(listing,page([value]));f.props.accountId='2'}
    else if(change==='offline')f.props.available=false
    else if(change==='inactive')f.props.active=false
    else if(change==='unauthorized')f.props.authorized=false
    else f.destroy()
    await pending;await flush();await f.state.confirmDelete();assert.equal(f.state.deleteConfirmation.value,null,change);assert.equal(shown(),0,change)
    assert.equal(f.calls.every(call=>(call.options.method??'GET')==='GET'),true,change)
  }
})

test('a delete confirmation already shown is discarded on account change and cannot send an old-account mutation',async t=>{
  const value=pausedDownload(),f=mount(t,[{path:listing,value:page([value])}]);await flush();const shown=attachDeleteDialog(f);await f.state.action(value,'delete');assert.equal(shown(),1)
  f.expect(listing,page([value]));f.props.accountId='2';await flush();await f.state.confirmDelete();assert.equal(f.calls.filter(call=>call.options.method==='POST').length,0)
})

test('a queued automatic retry is labelled as waiting in the task center and retains pause without a manual resume action',async t=>{
  const retry=row({state:'QUEUED',statusGroup:'waiting',phase:'RETRY_WAIT',actions:['pause'],error:'连接中断，预计稍后自动重试'})
  assert.equal(center.validCenterRow(retry),true);assert.equal(center.centerPhaseLabels.RETRY_WAIT,'等待自动重试')
  const f=mount(t,[{path:listing,value:page([retry])}]);await flush();const tree=f.render()
  assert.match(text(tree),/等待自动重试/);assert.match(text(tree),/连接中断，预计稍后自动重试/)
  assert.ok(nodes(tree).some(node=>node.type==='button'&&text(node)==='暂停'));assert.equal(nodes(tree).some(node=>node.type==='button'&&text(node)==='继续 / 重试'),false)
  assert.equal(f.calls.length,1);assert.equal(f.calls.every(call=>!call.options.method),true)
})

const retryAt=Date.UTC(2026,10,5,1,2,3)
const retryRow=(overrides={})=>row({state:'QUEUED',statusGroup:'waiting',phase:'RETRY_WAIT',actions:['pause'],error:'原文件连续两分钟没有下载进展',retry:{attempt:1,nextAttemptAt:retryAt},...overrides})

test('task center retry metadata is optional but requires a standalone queued download and a valid schedule',()=>{
  assert.equal(center.validCenterRow(retryRow()),true)
  assert.equal(center.validCenterRow(retryRow({retry:null})),true)
  assert.equal(center.validCenterRow(row()),true)
  assert.equal(center.centerRetrySummary(row()),'')
  for(const retry of [{attempt:0,nextAttemptAt:retryAt},{attempt:1.5,nextAttemptAt:retryAt},{attempt:1,nextAttemptAt:'soon'},{attempt:1,nextAttemptAt:0},{attempt:1,nextAttemptAt:Number.MAX_SAFE_INTEGER},{},[]])assert.equal(center.validCenterRow(retryRow({retry})),false)
  for(const patch of [{category:'batch'},{category:'rule-download'},{kind:'upload',category:'upload'},{state:'RUNNING'},{state:'PAUSED'},{statusGroup:'active'},{phase:'DOWNLOADING'}])assert.equal(center.validCenterRow(retryRow(patch)),false)
})

test('the main row shows the exact Beijing retry time and cause without opening details or creating work',async t=>{
  t.mock.method(Date,'now',()=>retryAt-30000)
  const f=mount(t,[{path:listing,value:page([retryRow()])}]);await flush()
  const tree=f.render(),schedule=nodes(tree).find(node=>node.props?.class==='retry-schedule')
  assert.equal(text(schedule),'第 1 次自动重试 · 预计北京时间 09:02:03')
  assert.match(text(tree),/无需手动继续；暂停会取消自动重试/)
  assert.match(text(tree),/原文件连续两分钟没有下载进展/)
  assert.equal(nodes(tree).some(node=>node.type?.__name==='DownloadTasks'),false,'the schedule is visible before details are opened')
  assert.equal(f.calls.length,1);assert.equal(f.calls[0].options.method,undefined)
})

test('readonly progress updates the attempt, explains an overdue schedule and clears it once the backend starts',async t=>{
  let now=retryAt-30000;t.mock.method(Date,'now',()=>now)
  const f=mount(t,[{path:listing,value:page([retryRow()])}]);await flush()
  f.expect(listing,page([retryRow({retry:{attempt:2,nextAttemptAt:retryAt+60000}})]));await f.state.load(true)
  assert.match(text(f.render()),/第 2 次自动重试 · 预计北京时间 09:03:03/)
  now=retryAt+60000
  f.expect(listing,page([retryRow({retry:{attempt:2,nextAttemptAt:now}})]));await f.state.load(true)
  assert.match(text(f.render()),/第 2 次自动重试 · 已到重试时间，等待后台调度/)
  assert.doesNotMatch(text(f.render()),/预计北京时间/)
  f.expect(listing,page([row({retry:null})]));await f.state.load(true)
  assert.equal(nodes(f.render()).some(node=>node.props?.class==='retry-schedule'),false)
  assert.doesNotMatch(text(f.render()),/无需手动继续|等待后台调度/)
  assert.equal(f.calls.length,4);assert.ok(f.calls.every(call=>!call.options.method),'elapsed retry times never trigger a frontend POST')
})

test('manual pause removes the scheduled retry hint and never resumes or retries the task automatically',async t=>{
  t.mock.method(Date,'now',()=>retryAt-30000)
  const value=retryRow(),paused=row({state:'PAUSED',statusGroup:'waiting',phase:'QUEUED',actions:['resume','delete'],retry:null,error:'已手动暂停'})
  const f=mount(t,[{path:listing,value:page([value])}]);await flush()
  f.expect('/transfers/center/action',{item:{id:ID,state:'PAUSED'}},'POST');f.expect(listing,page([paused]))
  await f.state.action(value,'pause');await flush()
  assert.equal(f.state.page.value.items[0].state,'PAUSED');assert.equal(f.state.page.value.items[0].retry,null)
  assert.equal(nodes(f.render()).some(node=>node.props?.class==='retry-schedule'),false)
  assert.doesNotMatch(text(f.render()),/等待自动重试|预计北京时间|无需手动继续/)
  f.expect(listing,page([paused]));await f.state.load(true)
  const mutations=f.calls.filter(call=>call.options.method==='POST')
  assert.equal(mutations.length,1);assert.deepEqual(mutations[0].options.body,{category:'download',id:ID,action:'pause'})
})

test('a malformed retry schedule cannot replace the last verified row or invent a retry time for a legacy DTO',async t=>{
  t.mock.method(Date,'now',()=>retryAt-30000)
  const f=mount(t,[{path:listing,value:page([retryRow()])}]);await flush()
  f.expect(listing,page([retryRow({retry:{attempt:2,nextAttemptAt:0}})]));await f.state.load(true)
  assert.match(f.state.error.value,/回执无法确认/);assert.match(text(f.render()),/第 1 次自动重试 · 预计北京时间 09:02:03/)
  const legacy=retryRow();delete legacy.retry;f.expect(listing,page([legacy]));await f.state.load(true)
  assert.match(text(f.render()),/已安排自动重试，可打开详情核对时间/);assert.doesNotMatch(text(f.render()),/预计北京时间/)
  assert.ok(f.calls.every(call=>!call.options.method))
})
