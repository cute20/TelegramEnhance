import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import {parse,compileScript,compileTemplate} from 'vue/compiler-sfc'
import ts from 'typescript'

const flush=async()=>{await vue.nextTick();await new Promise(setImmediate)}
function mount(t){
  const {descriptor}=parse(readFileSync(new URL('../src/components/RuleFormDialog.vue',import.meta.url),'utf8'),{filename:'RuleFormDialog.vue'})
  assert.deepEqual(compileTemplate({source:descriptor.template.content,filename:'RuleFormDialog.vue',id:'dialog-test'}).errors,[])
  const code=ts.transpileModule(compileScript(descriptor,{id:'dialog-test'}).content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
  const props=vue.reactive({open:false,title:'新建上传规则',busy:false}),events=[],hooks=[],module={exports:{}}
  const counts={shown:0,closed:0,inputFocus:0,returned:0,closeFocus:0}
  const trigger={isConnected:true,focus(){counts.returned++}},input={focus(){counts.inputFocus++}}
  const oldDocument=Object.getOwnPropertyDescriptor(globalThis,'document')
  Object.defineProperty(globalThis,'document',{value:{activeElement:trigger},configurable:true,writable:true})
  new Function('require','module','exports',code)(name=>name==='vue'?{...vue,useId:()=> 'dialog-test-title',onBeforeUnmount:f=>hooks.push(f)}:undefined,module,module.exports)
  const scope=vue.effectScope(),state=scope.run(()=>module.exports.default.setup(props,{expose(){},emit:(event)=>events.push(event)}))
  const element=vue.markRaw({open:false,showModal(){this.open=true;counts.shown++},close(){this.open=false;counts.closed++},querySelector(){return input},getBoundingClientRect(){return {left:20,right:700,top:20,bottom:700}}})
  state.dialog.value=element;state.closeButton.value=vue.markRaw({focus(){counts.closeFocus++}})
  t.after(()=>{hooks.forEach(f=>f());scope.stop();if(oldDocument)Object.defineProperty(globalThis,'document',oldDocument);else delete globalThis.document})
  return {state,props,events,counts,element,input}
}

test('rule dialog opens in the native top layer, focuses its form, and restores the triggering control',async t=>{
  const f=mount(t);await flush();assert.equal(f.counts.shown,0);f.props.open=true;await flush();assert.equal(f.element.open,true);assert.equal(f.counts.shown,1);assert.equal(f.counts.inputFocus,1);f.props.open=false;await flush();assert.equal(f.element.open,false);assert.equal(f.counts.closed,1);assert.equal(f.counts.returned,1)
})

test('Escape and explicit close are controlled by the parent and blocked while a save is in flight',async t=>{
  const f=mount(t);f.props.open=true;await flush();let prevented=0;f.props.busy=true;f.state.cancel({preventDefault(){prevented++}});f.state.requestClose();assert.equal(prevented,1);assert.deepEqual(f.events,[]);assert.equal(f.element.open,true);f.props.busy=false;f.state.cancel({preventDefault(){prevented++}});assert.equal(prevented,2);assert.deepEqual(f.events,['close']);assert.equal(f.element.open,true)
})

test('only an actual backdrop click requests closing and busy blocks it',async t=>{
  const f=mount(t);f.props.open=true;await flush();f.state.backdrop({target:f.element,clientX:100,clientY:100});f.state.backdrop({target:{},clientX:0,clientY:0});assert.deepEqual(f.events,[]);f.props.busy=true;f.state.backdrop({target:f.element,clientX:0,clientY:0});assert.deepEqual(f.events,[]);f.props.busy=false;f.state.backdrop({target:f.element,clientX:0,clientY:0});assert.deepEqual(f.events,['close'])
})

test('rapidly cancelled opening cannot focus or display a stale form',async t=>{
  const f=mount(t);f.props.open=true;f.props.open=false;await flush();assert.equal(f.counts.shown,0);assert.equal(f.counts.inputFocus,0);assert.equal(f.element.open,false)
})

test('dialog falls back to its close button when the form has no editable controls',async t=>{
  const f=mount(t);f.element.querySelector=()=>null;f.props.open=true;await flush();assert.equal(f.counts.closeFocus,1);assert.equal(f.counts.inputFocus,0)
})
