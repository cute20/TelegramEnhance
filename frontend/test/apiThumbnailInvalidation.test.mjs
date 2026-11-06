import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import ts from 'typescript'
import { createThumbnailMemory, ThumbnailCancelledError } from '../src/thumbnailMemory.ts'

const code=ts.transpileModule(readFileSync(new URL('../src/api.ts',import.meta.url),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
const preview={status:'AVAILABLE',quality:'THUMBNAIL',dataUrl:'data:image/jpeg;base64,'+Buffer.from([255,216,255,217]).toString('base64'),width:64,height:32}
const ok=()=>({ok:true,status:200,text:async()=>'{"ok":true}'})
const deferred=()=>{let resolve,reject;const promise=new Promise((yes,no)=>{resolve=yes;reject=no});return {promise,resolve,reject}}
const flush=async()=>{await new Promise(setImmediate)}

function client(){
  const memory=createThumbnailMemory(),calls=[],module={exports:{}},storage=new Map(),window={location:{hash:'#token=synthetic',pathname:'/',search:''},history:{replaceState(){}},setTimeout,clearTimeout}
  let invalidations=0,respond=async()=>ok()
  const require=name=>{assert.equal(name,'./thumbnailMemory');return {invalidateThumbnails(){invalidations++;memory.invalidateThumbnails()}}}
  const fetch=async(url,options)=>{calls.push({url,options});return respond(url,options)}
  new Function('require','module','exports','window','sessionStorage','fetch',code)(require,module,module.exports,window,{setItem:(key,value)=>storage.set(key,value),getItem:key=>storage.get(key)??null,removeItem:key=>storage.delete(key)},fetch)
  return {...module.exports,memory,calls,storage,invalidations:()=>invalidations,respond:fn=>{respond=fn}}
}
async function seed(f,key='cached'){await f.memory.acquireThumbnail(key,async()=>preview).promise;assert.deepEqual(f.memory.readThumbnail(key),preview)}

test('indexed media writes invalidate before dispatch and after settlement, including encoded post keys',async()=>{
  const f=client()
  const paths=['/library/probe','/library/posts/1%3A-1001%3A100/refresh','/library/posts/1%3A-1001%3A100/comments?source=library','/library/import/posts','/library/import/all/start','/library/import/all/step']
  for(const [index,path] of paths.entries()){
    await seed(f)
    f.respond(async()=>{assert.equal(f.invalidations(),index*2+1);assert.equal(f.memory.readThumbnail('cached'),undefined);return ok()})
    await f.api(path,{method:'POST'});assert.equal(f.invalidations(),(index+1)*2)
  }
  assert.deepEqual(f.calls.map(call=>call.url),paths.map(path=>'/api'+path))
})

test('read-only previews and unrelated transfer, rule and settings writes keep reusable images',async()=>{
  const f=client();await seed(f)
  const paths=[['/library/posts/post/preview','GET'],['/library/posts/post/media-preview?mediaKey=observed','GET'],['/library/catalog','GET'],['/library/import/all/state?channelId=-1001','GET'],['/settings/thumbnail-cache','GET'],['/library/import/preview','POST'],['/library/channels/-1001/history','POST'],['/transfers/download/tasks','POST'],['/rules','POST'],['/settings/download','PUT']]
  for(const [path,method] of paths)await f.api(path,{method})
  assert.equal(f.invalidations(),0);assert.deepEqual(f.memory.readThumbnail('cached'),preview)
})

test('cache save and clear reject old and during-write thumbnails so late replies cannot refill memory',async()=>{
  for(const [path,method] of [['/settings/thumbnail-cache','PUT'],['/settings/thumbnail-cache/clear','POST']]){
    const f=client();await seed(f)
    const old=deferred(),oldRead=f.memory.acquireThumbnail('old-in-flight',()=>old.promise),request=deferred()
    const oldRejected=assert.rejects(oldRead.promise,ThumbnailCancelledError)
    f.respond(()=>request.promise);const writing=f.api(path,{method});assert.equal(f.invalidations(),1);await oldRejected
    await seed(f,'loaded-during-write')
    const late=deferred(),lateRead=f.memory.acquireThumbnail('late-in-flight',()=>late.promise),lateRejected=assert.rejects(lateRead.promise,ThumbnailCancelledError)
    request.resolve(ok());await writing;await lateRejected;assert.equal(f.invalidations(),2)
    assert.equal(f.memory.readThumbnail('loaded-during-write'),undefined)
    old.resolve(preview);late.resolve(preview);await flush()
    assert.equal(f.memory.readThumbnail('old-in-flight'),undefined);assert.equal(f.memory.readThumbnail('late-in-flight'),undefined)
  }
})

test('an unknown cache mutation never restores browser images when the local request fails',async()=>{
  const f=client();await seed(f);const request=deferred();f.respond(()=>request.promise)
  const writing=f.api('/settings/thumbnail-cache/clear',{method:'POST'}),failure=assert.rejects(writing,/无法连接本机服务/)
  assert.equal(f.invalidations(),1);await seed(f,'during-unknown-write');request.reject(new Error('transport lost'));await failure
  assert.equal(f.invalidations(),2);assert.equal(f.memory.readThumbnail('cached'),undefined);assert.equal(f.memory.readThumbnail('during-unknown-write'),undefined)
})

test('clearing the launch token invalidates memory once and blocks further authenticated requests',async()=>{
  const f=client();await seed(f);const late=deferred(),read=f.memory.acquireThumbnail('pending',()=>late.promise),rejected=assert.rejects(read.promise,ThumbnailCancelledError)
  f.clearAppToken();await rejected;assert.equal(f.invalidations(),1);assert.equal(f.memory.readThumbnail('cached'),undefined);assert.equal(f.storage.size,0)
  f.clearAppToken();assert.equal(f.invalidations(),1)
  await assert.rejects(f.api('/telegram'),error=>error instanceof f.ApiError&&error.status===401);assert.equal(f.calls.length,0)
  late.resolve(preview);await flush();assert.equal(f.memory.readThumbnail('pending'),undefined)
})
