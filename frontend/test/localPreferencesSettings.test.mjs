import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import {parse,compileScript} from 'vue/compiler-sfc'
import ts from 'typescript'
const {descriptor,errors}=parse(readFileSync(new URL('../src/components/LocalPreferencesSettings.vue',import.meta.url),'utf8'),{filename:'LocalPreferencesSettings.vue'})
assert.deepEqual(errors,[])
const code=ts.transpileModule(compileScript(descriptor,{id:'local-preferences'}).content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
const flush=async()=>{await vue.nextTick();await new Promise(setImmediate)}
const preferences=()=>({defaultDownloadDirectory:'D:\\Downloads',downloadConcurrency:2})
const desktop=()=>({trayEnabled:false,traySupported:true,trayActive:false,warning:null})
class ApiError extends Error{constructor(message,status){super(message);this.status=status}}
function mount(t,firstDesktop=desktop()){
  const props=vue.reactive({available:true,active:true}),calls=[],responses=[preferences(),firstDesktop],hooks=[],events=[]
  const api=async(path,options={})=>{calls.push({path,...options});assert.ok(responses.length,'Unexpected request '+path);const result=responses.shift();if(result instanceof Error)throw result;return result}
  const modules={vue:{...vue,onBeforeUnmount:hook=>hooks.push(hook)},'../api':{api,ApiError}},module={exports:{}}
  new Function('require','module','exports',code)(name=>{if(name.endsWith('.vue'))return{};assert.ok(Object.hasOwn(modules,name),name);return modules[name]},module,module.exports)
  const scope=vue.effectScope(),state=scope.run(()=>module.exports.default.setup(props,{expose(){},emit:(...event)=>events.push(event)}))
  t.after(()=>{hooks.forEach(hook=>hook());scope.stop();assert.equal(responses.length,0)})
  return{props,calls,responses,state,events}
}
test('transfer defaults save independently and are emitted for new creation forms',async t=>{
  const f=mount(t);await flush();assert.equal(f.state.directory.value,'D:\\Downloads')
  f.state.directory.value='D:\\New';f.state.concurrency.value=4
  f.responses.push({defaultDownloadDirectory:'D:\\New',downloadConcurrency:4});await f.state.save('transfers')
  assert.deepEqual(f.calls.at(-1),{path:'/settings/transfers',method:'PUT',body:{defaultDownloadDirectory:'D:\\New',downloadConcurrency:4}})
  assert.deepEqual(f.events.at(-1),['updated',{defaultDownloadDirectory:'D:\\New',downloadConcurrency:4}])
  assert.equal(f.calls.some(call=>call.path==='/settings'||call.path.startsWith('/telegram')),false)
})
test('unknown saves require a read before another mutation and never resend automatically',async t=>{
  const f=mount(t);await flush();f.responses.push(new Error('Timeout'));await f.state.save('transfers')
  assert.equal(f.state.uncertain.value,true);await f.state.save('transfers');await f.state.save('desktop');assert.equal(f.calls.length,3)
  f.responses.push(preferences(),desktop());await f.state.load();assert.equal(f.state.uncertain.value,false)
  assert.deepEqual(f.calls.slice(-2).map(call=>call.path),['/settings/transfers','/settings/desktop'])
})
test('desktop preference does not pretend that a missing tray icon is active',async t=>{
  const f=mount(t);await flush();f.state.trayEnabled.value=true
  f.responses.push({trayEnabled:true,traySupported:true,trayActive:false,warning:'No interactive desktop'})
  await f.state.save('desktop');assert.equal(f.state.desktop.value.trayActive,false);assert.match(f.state.desktop.value.warning,/desktop/)
  assert.deepEqual(f.calls.at(-1),{path:'/settings/desktop',method:'PUT',body:{trayEnabled:true}})
})
test('hidden settings retain unknown mutation state and discard a late receipt',async t=>{
  const f=mount(t);await flush();let resolve;f.responses.push(new Promise(yes=>{resolve=yes}))
  const saving=f.state.save('transfers');f.props.active=false;await flush()
  resolve({defaultDownloadDirectory:'D:\\Late',downloadConcurrency:4});await saving
  assert.equal(f.state.saved.value.defaultDownloadDirectory,'D:\\Downloads');assert.equal(f.state.uncertain.value,true)
})
test('returning to settings refreshes saved defaults without overwriting unsaved directory, concurrency or tray drafts',async t=>{
  const f=mount(t);await flush();f.state.directory.value='D:\\Draft';f.state.concurrency.value=4;f.state.trayEnabled.value=true
  f.props.active=false;await flush();f.responses.push(preferences(),desktop());f.props.active=true;await flush()
  assert.equal(f.state.directory.value,'D:\\Draft');assert.equal(f.state.concurrency.value,4);assert.equal(f.state.trayEnabled.value,true)
  assert.equal(f.state.saved.value.defaultDownloadDirectory,'D:\\Downloads')
})
test('a desktop status failure does not silently disable saving independently loaded transfer defaults',async t=>{
  const f=mount(t,new Error('Desktop unavailable'));await flush();assert.equal(f.state.desktop.value,null)
  f.state.concurrency.value=3;f.responses.push({...preferences(),downloadConcurrency:3});await f.state.save('transfers')
  assert.equal(f.state.saved.value.downloadConcurrency,3);assert.equal(f.calls.at(-1).path,'/settings/transfers')
})
