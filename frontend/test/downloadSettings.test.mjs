import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { compileScript, compileTemplate, parse } from 'vue/compiler-sfc'
import ts from 'typescript'

const path='/settings/downloads',KEEP='KEEP_DUPLICATES_V2',SKIP='HISTORY_SKIP_V1'
const {descriptor,errors}=parse(readFileSync(new URL('../src/components/DownloadSettings.vue',import.meta.url),'utf8'),{filename:'DownloadSettings.vue'})
assert.deepEqual(errors,[])
const script=compileScript(descriptor,{id:'download-settings'}),template=compileTemplate({source:descriptor.template.content,filename:'DownloadSettings.vue',id:'download-settings',compilerOptions:{bindingMetadata:script.bindings,expressionPlugins:['typescript']}})
assert.deepEqual(template.errors,[])
const transpile=source=>ts.transpileModule(source,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
const code=transpile(script.content),renderCode=transpile(template.code)
const view=(duplicatePolicy=KEEP)=>({duplicatePolicy}),flush=async()=>{await vue.nextTick();await new Promise(setImmediate)}
const deferred=()=>{let resolve;const promise=new Promise(done=>{resolve=done});return{promise,resolve}}
class ApiError extends Error{constructor(message,status){super(message);this.status=status}}
function mount(t,first=view(),overrides={}){
  const calls=[],responses=[],events=[],hooks=[],failures=[],props=vue.reactive({available:true,active:true,...overrides})
  if(props.available&&props.active)responses.push(first)
  const api=async(target,options={})=>{
    calls.push({path:target,...options});assert.equal(target,path)
    if(!responses.length){failures.push('unexpected request');throw new Error('unexpected request')}
    const result=await responses.shift();if(result instanceof Error)throw result;return result
  }
  const runtimeVue={...vue,onBeforeUnmount:hook=>hooks.push(hook),withDirectives:node=>node}
  const modules={vue:runtimeVue,'../api':{api,ApiError}},require=name=>name.endsWith('.vue')?{}:modules[name]
  const module={exports:{}},rendered={exports:{}}
  new Function('require','module','exports',code)(require,module,module.exports)
  new Function('require','module','exports',renderCode)(require,rendered,rendered.exports)
  const scope=vue.effectScope(),state=scope.run(()=>module.exports.default.setup(props,{expose(){},emit:(...event)=>events.push(event)}))
  let disposed=false;const destroy=()=>{if(!disposed){disposed=true;hooks.forEach(hook=>hook());scope.stop()}}
  t.after(()=>{destroy();assert.equal(responses.length,0);assert.deepEqual(failures,[])})
  return{state,props,calls,responses,events,destroy,render:()=>rendered.exports.render({},[],props,vue.proxyRefs(state),{},{})}
}
function nodes(node){if(!node||typeof node!=='object')return[];if(Array.isArray(node))return node.flatMap(nodes);return[node,...(Array.isArray(node.children)?node.children:[]).flatMap(nodes)]}
const text=node=>typeof node==='string'?node:Array.isArray(node)?node.map(text).join(''):node&&typeof node==='object'?text(node.children):''

test('duplicate settings read independently of credentials and Telegram and save only after an explicit button click',async t=>{
  const f=mount(t);await flush();assert.equal(f.state.policy.value,KEEP)
  assert.deepEqual(f.calls,[{path}]);assert.match(text(f.render()),/继续下载，放入“重复媒体”文件夹.*不下载重复媒体.*已有任务保持原设置/s)
  const radios=nodes(f.render()).filter(node=>node.type==='input'&&node.props.type==='radio')
  assert.deepEqual(radios.map(node=>node.props.value),[KEEP,SKIP]);radios[1].props['onUpdate:modelValue'](SKIP)
  await flush();assert.equal(f.calls.length,1);assert.equal(f.state.dirty.value,true);assert.match(text(f.render()),/已确认的重复媒体直接跳过；首次遇到的文件需校验后判断/)
  const save=nodes(f.render()).find(node=>node.type==='button'&&text(node)==='保存下载设置');assert.equal(save.props.disabled,false)
  f.responses.push(view(SKIP));await save.props.onClick();assert.deepEqual(f.calls[1],{path,method:'PUT',body:view(SKIP)})
  assert.equal(f.state.dirty.value,false);assert.match(f.state.notice.value,/已保存/);assert.equal(f.events.length,0)
})

test('a refresh keeps a dirty choice and edits made after the GET started without disabling its input',async t=>{
  const f=mount(t);await flush();f.state.policy.value=SKIP
  const response=deferred();f.responses.push(response.promise);const pending=f.state.perform('load')
  assert.equal(nodes(f.render()).find(node=>node.type==='fieldset').props.disabled,false)
  response.resolve(view());await pending;assert.equal(f.state.policy.value,SKIP);assert.equal(f.state.saved.value.duplicatePolicy,KEEP)
  f.state.policy.value=KEEP;const changed=deferred();f.responses.push(changed.promise);const second=f.state.perform('load');f.state.policy.value=SKIP
  changed.resolve(view());await second;assert.equal(f.state.policy.value,SKIP);assert.equal(f.state.dirty.value,true)
})

test('an uncertain PUT freezes its choice and only a GET can reconcile it without another PUT',async t=>{
  const f=mount(t);await flush();f.state.policy.value=SKIP;f.responses.push(new Error('lost save response'));await f.state.perform('save')
  assert.equal(f.state.uncertain.value,true);assert.equal(nodes(f.render()).find(node=>node.type==='fieldset').props.disabled,true)
  assert.match(text(f.render()),/lost save response.*读取当前设置核对/s);await f.state.perform('save');assert.equal(f.calls.length,2)
  f.responses.push(view(SKIP));await f.state.perform('load');assert.deepEqual(f.calls[2],{path})
  assert.equal(f.state.uncertain.value,false);assert.equal(f.state.policy.value,SKIP);assert.equal(f.state.dirty.value,false)
})

test('server errors or an invalid PUT receipt remain uncertain, while a definite 400 rejection keeps the draft editable',async t=>{
  for(const failure of [new ApiError('invalid option',400),new ApiError('server unavailable',500),view(KEEP),{duplicatePolicy:'invalid'}]){
    const f=mount(t);await flush();f.state.policy.value=SKIP;f.responses.push(failure);await f.state.perform('save')
    assert.equal(f.state.policy.value,SKIP);assert.equal(f.state.saved.value.duplicatePolicy,KEEP);assert.ok(f.state.error.value)
    assert.equal(f.state.uncertain.value,!(failure instanceof ApiError&&failure.status===400));assert.equal(f.calls.length,2)
  }
})

test('a pending save is serial and a brief outage cannot release its unknown result lock',async t=>{
  const f=mount(t);await flush();f.state.policy.value=SKIP
  const response=deferred();f.responses.push(response.promise);const pending=f.state.perform('save')
  await f.state.perform('save');await f.state.perform('load');assert.equal(f.calls.length,2)
  f.props.available=false;await flush();response.resolve(view(SKIP));await pending
  assert.equal(f.state.uncertain.value,true);assert.equal(f.state.saved.value.duplicatePolicy,KEEP);assert.equal(f.state.policy.value,SKIP)
  f.responses.push(view(SKIP));f.props.available=true;await flush();assert.equal(f.calls.length,3);assert.deepEqual(f.calls[2],{path})
  assert.equal(f.state.uncertain.value,false)
})

test('hidden settings do not read, and returning preserves a dirty choice while rechecking through GET',async t=>{
  const f=mount(t,view(),{active:false});await flush();assert.equal(f.calls.length,0)
  f.responses.push(view());f.props.active=true;await flush();f.state.policy.value=SKIP
  f.props.active=false;await flush();assert.equal(f.calls.length,1)
  f.responses.push(view());f.props.active=true;await flush();assert.equal(f.calls.length,2);assert.equal(f.state.policy.value,SKIP)
  const response=deferred();f.responses.push(response.promise);const pending=f.state.perform('load')
  f.props.active=false;await flush();response.resolve(view(SKIP));await pending
  assert.equal(f.state.saved.value.duplicatePolicy,KEEP);assert.equal(f.state.policy.value,SKIP)
})

test('leaving the page during an uncertain PUT preserves its lookup lock until the next entry GET',async t=>{
  const f=mount(t);await flush();f.state.policy.value=SKIP
  const response=deferred();f.responses.push(response.promise);const pending=f.state.perform('save');f.props.active=false;await flush()
  response.resolve(new Error('lost while hidden'));await pending;assert.equal(f.state.uncertain.value,true)
  f.responses.push(view());f.props.active=true;await flush()
  assert.equal(f.state.uncertain.value,false);assert.equal(f.state.policy.value,KEEP);assert.equal(f.calls.length,3);assert.deepEqual(f.calls[2],{path})
})

test('invalid reads and authorization failures remain visible, and disposal ignores late results',async t=>{
  const f=mount(t,{duplicatePolicy:'invalid'});await flush();assert.equal(f.state.saved.value,null);assert.match(text(f.render()),/回执无法确认/)
  f.responses.push(new ApiError('invalid token',401));await f.state.perform('load');assert.equal(f.events[0][0],'accessError')
  const response=deferred();f.responses.push(response.promise);const pending=f.state.perform('load');f.destroy();response.resolve(view(SKIP));await pending
  assert.equal(f.state.saved.value,null);assert.equal(f.state.policy.value,KEEP)
})
