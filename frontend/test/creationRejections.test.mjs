import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'
import ts from 'typescript'
import * as library from '../src/library.ts'
import * as downloadPreview from '../src/downloadPreview.ts'
import * as downloadTasks from '../src/downloadTasks.ts'
import * as downloadBatch from '../src/downloadBatch.ts'
import * as uploadPreview from '../src/uploadPreview.ts'
import * as uploadTasks from '../src/uploadTasks.ts'
import * as rules from '../src/rules.ts'
import * as creation from '../src/transferCreation.ts'

const ID='00000000-0000-4000-8000-000000000001'
const flush=async()=>{await vue.nextTick();await new Promise(setImmediate)}
class ApiError extends Error { constructor(message,status,code){super(message);this.status=status;this.code=code} }
const compiled=new Map()
function mount(t,name,overrides={}){
  if(!compiled.has(name)){
    const {descriptor}=parse(readFileSync(new URL('../src/components/'+name+'.vue',import.meta.url),'utf8'),{filename:name+'.vue'})
    compiled.set(name,ts.transpileModule(compileScript(descriptor,{id:'creation-rejection-'+name}).content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText)
  }
  const props=vue.reactive({authorized:true,available:true,accountId:'1',embedded:true,active:true,...overrides}),calls=[],replies=[],hooks=[],events=[]
  let forward=()=>{}
  const api=async(path,options={})=>{calls.push({path,options:structuredClone(options)});assert.ok(replies.length,'unexpected '+path);const value=await replies.shift();if(value instanceof Error)throw value;return value}
  const modules={vue:{...vue,onMounted(){},onBeforeUnmount:fn=>hooks.push(fn)},'../api':{api,ApiError},'../library':library,'../downloadPreview':downloadPreview,'../downloadTasks':downloadTasks,'../downloadBatch':downloadBatch,'../uploadPreview':uploadPreview,'../uploadTasks':uploadTasks,'../rules':rules,'../transferCreation':creation}
  const module={exports:{}}
  new Function('require','module','exports',compiled.get(name))(name=>name.endsWith('.vue')?{}:modules[name],module,module.exports)
  const scope=vue.effectScope(),state=scope.run(()=>module.exports.default.setup(props,{expose(){},emit:(...event)=>{events.push(event);forward(...event)}}))
  t.after(()=>{hooks.forEach(hook=>hook());scope.stop();assert.equal(replies.length,0)})
  return {state,props,calls,replies,events,forward:fn=>{forward=fn}}
}
const cases=[
  {name:'NewTransfer',lock:'locked',props:{selection:{...creation.emptyDownloadSelection(),entry:'POST',source:'https://t.me/synthetic/1',types:[],includeMain:true,targetDirectory:'relative-folder'}},
    async prepare(f){f.state.preview.value={id:ID,post:{mainMedia:[{key:'main',source:'MAIN',type:'VIDEO'}]},comments:null};await flush()},
    create:(f,retry=false)=>f.state.createDownload(retry),lookup:f=>f.state.lookupDownload(),unknown:f=>f.state.createUncertain.value,error:f=>f.state.error.value,
    missing:{task:null},bodyKey:'sourceId',canPrepare:f=>!f.state.downloadLocked.value},
  {name:'UploadPreparation',lock:'transitionLocked',props:{selection:{...creation.emptyUploadSelection(),link:'https://t.me/synthetic/1',sourceDirectory:'D:\\source'}},
    async prepare(f){await flush();f.state.preview.value={id:ID,fileCount:1};await flush()},
    create:(f,retry=false)=>f.state.createTask(retry),lookup:f=>f.state.lookupTask(),unknown:f=>f.state.uncertain.value,error:f=>f.state.error.value,
    missing:{task:null},bodyKey:'previewId',canPrepare:f=>!f.state.fieldsLocked.value},
  {name:'DownloadBatchPreparation',lock:'transitionLocked',props:{scanId:ID,scanRevision:1,postKeys:['post']},
    async prepare(f){f.state.preview.value={id:ID,revision:1,total:1,exhausted:true};await flush()},
    create:(f,retry=false)=>f.state.create(retry),lookup:f=>f.state.lookup(),unknown:f=>f.state.uncertain.value,error:f=>f.state.error.value,
    missing:{batch:null},bodyKey:'previewId',canPrepare:f=>!f.state.fieldsLocked.value},
]
for(const c of cases){
  test(c.name+' releases the composer after a first confirmed pre-commit rejection and permits a new preparation',async t=>{
    const composer=mount(t,'TransferComposer'),f=mount(t,c.name,c.props);await c.prepare(f)
    f.forward((event,value)=>{if(event===c.lock)composer.state.locked.value=value})
    f.replies.push(new ApiError('保存参数或预览已失效',400,'CREATION_REJECTED'));await c.create(f);await flush()
    const first=f.calls[0].options.body
    assert.equal(c.unknown(f),false);assert.equal(c.canPrepare(f),true);assert.equal(f.state.preview.value,null)
    assert.match(c.error(f),/本次未创建任务/);assert.equal(composer.state.locked.value,false)
    composer.state.close();assert.deepEqual(composer.events.at(-1),['close'])
    await c.prepare(f);f.replies.push(new ApiError('仍需重新准备',400,'CREATION_REJECTED'));await c.create(f)
    assert.notEqual(f.calls[1].options.body.operationId,first.operationId)
    assert.equal(f.calls.filter(call=>!call.options.method||call.options.method==='GET').length,0,'a known rejected first attempt needs no negative lookup')
  })
  test(c.name+' retains the original operation when a later retry is rejected after an unknown attempt',async t=>{
    const composer=mount(t,'TransferComposer'),f=mount(t,c.name,c.props);await c.prepare(f)
    f.forward((event,value)=>{if(event===c.lock)composer.state.locked.value=value})
    f.replies.push(new Error('response lost'));await c.create(f)
    const original=structuredClone(f.calls[0].options.body)
    f.replies.push(new ApiError('本次重试未提交',400,'CREATION_REJECTED'));await c.create(f,true)
    assert.deepEqual(f.calls[1].options.body,original);assert.equal(c.unknown(f),true);assert.equal(c.canPrepare(f),false)
    f.replies.push(c.missing);await c.lookup(f);assert.equal(c.unknown(f),true,'missing lookup does not prove the first request has stopped')
    assert.equal(composer.state.locked.value,true);composer.state.close();assert.equal(composer.events.some(event=>event[0]==='close'),false)
    await c.create(f);assert.equal(f.calls.length,3,'no automatic retry or new operation is allowed')
  })
  test(c.name+' treats ordinary 400 and server failures as unknown without a pre-commit guarantee',async t=>{
    for(const error of [new ApiError('会话在保存后发生变化',400),new ApiError('unavailable',503),new ApiError('invalid server proof',503,'CREATION_REJECTED')]){
      const f=mount(t,c.name,c.props);await c.prepare(f);f.replies.push(error);await c.create(f)
      assert.equal(c.unknown(f),true);assert.equal(c.canPrepare(f),false);assert.ok(f.state.preview.value)
    }
  })
}
