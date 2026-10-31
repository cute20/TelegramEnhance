import assert from 'node:assert/strict'
import {readFileSync} from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import {parse,compileScript,compileTemplate} from 'vue/compiler-sfc'
import ts from 'typescript'
import * as channels from '../src/downloadChannel.ts'
import * as preview from '../src/downloadPreview.ts'
import * as rules from '../src/rules.ts'
import * as creation from '../src/transferCreation.ts'
const flush=async()=>{await vue.nextTick();await new Promise(setImmediate)}
function mount(t,name,input={}) {
  const {descriptor}=parse(readFileSync(new URL(`../src/components/${name}.vue`,import.meta.url),'utf8'))
  const script=compileScript(descriptor,{id:name}),template=compileTemplate({source:descriptor.template.content,filename:name+'.vue',id:name,compilerOptions:{bindingMetadata:script.bindings,expressionPlugins:['typescript']}})
  assert.deepEqual(template.errors,[])
  const transpile=code=>ts.transpileModule(code,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
  const calls=[],events=[],hooks=[],replies=[]
  const props=vue.reactive({authorized:true,available:true,accountId:'1',disabled:false,modelValue:name==='DownloadCreationFields'?creation.emptyDownloadSelection():creation.emptyUploadSelection(),...input})
  const modules={vue:{...vue,onBeforeUnmount:fn=>hooks.push(fn)},'../api':{ApiError:class extends Error{},api:async(path,options)=>{calls.push({path,options});return replies.shift()??{items:[],partial:false,warnings:[]}}},'../downloadChannel':channels,'../downloadPreview':preview,'../rules':rules}
  const require=name=>name.endsWith('.vue')?{__esModule:true,default:{__name:name}}:modules[name]
  const code={exports:{}},render={exports:{}}
  new Function('require','module','exports',transpile(script.content))(require,code,code.exports)
  new Function('require','module','exports',transpile(template.code))(require,render,render.exports)
  const scope=vue.effectScope(),state=scope.run(()=>code.exports.default.setup(props,{expose(){},emit:(...event)=>{events.push(event);if(event[0]==='update:modelValue')props.modelValue=event[1]}}))
  t.after(()=>{hooks.forEach(fn=>fn());scope.stop()})
  return {state,props,calls,events,replies,render:()=>render.exports.render({},[],props,vue.proxyRefs(state),{},{})}
}
function nodes(node){if(!node||typeof node!=='object')return [];if(Array.isArray(node))return node.flatMap(nodes);return [node,...nodes(node.children)]}
const text=node=>typeof node==='string'?node:Array.isArray(node)?node.map(text).join(''):node&&typeof node==='object'?text(node.children):''

test('shared download fields load channels once and selecting a channel never scans or creates a task',async t=>{
  const f=mount(t,'DownloadCreationFields');await flush()
  assert.deepEqual(f.calls.map(c=>c.path),['/transfers/download/channels'])
  f.state.channels.value={items:[{id:'-1001',title:'频道一'}],partial:false,warnings:[]}
  f.state.source('-1001');await flush()
  assert.equal(f.props.modelValue.source,'-1001');assert.equal(f.props.modelValue.suggestedName,'频道一')
  f.state.entry('POST');await flush();assert.equal(f.props.modelValue.source,'')
  f.state.entry('CHANNEL');await flush();assert.equal(f.calls.length,1)
})

test('shared all button really selects and clears every type, preserving the independent main switch',async t=>{
  const f=mount(t,'DownloadCreationFields',{modelValue:{...creation.emptyDownloadSelection(),entry:'POST',includeMain:true}})
  function all(){return nodes(f.render()).find(n=>n.type==='button'&&text(n)==='全部')}
  all().props.onClick();await flush();assert.equal(f.props.modelValue.types.length,5)
  all().props.onClick();await flush();assert.deepEqual(f.props.modelValue.types,[]);assert.equal(f.props.modelValue.includeMain,true)
  assert.equal(f.calls.length,0)
})

test('common draft is immutable while frozen and channel responses cannot cross account identity',async t=>{
  const f=mount(t,'DownloadCreationFields',{modelValue:{...creation.emptyDownloadSelection(),entry:'POST'}})
  f.props.disabled=true;await flush();f.state.source('-1002');f.state.entry('CHANNEL');assert.equal(f.props.modelValue.entry,'POST')
  f.props.disabled=false;await flush();let resolve;f.replies.push(new Promise(yes=>resolve=yes));f.state.entry('CHANNEL');await flush()
  f.props.accountId='2';await flush();resolve({items:[{id:'-1001',title:'old'}],partial:false,warnings:[]});await flush()
  assert.notEqual(f.state.channels.value?.items[0]?.title,'old')
  assert.ok(f.calls.every(call=>!call.options?.method||call.options.method==='GET'))
})

test('upload common fields derive one sibling archive path for both execution modes',async t=>{
  const f=mount(t,'UploadCreationFields')
  f.state.update({sourceDirectory:'D:\\media\\待上传',captionMode:'EMPTY',recursive:false});await flush()
  const labels=nodes(f.render()).filter(n=>n.type==='label')
  const archive=labels.find(n=>text(n).includes('已上传归档目录'))
  const input=nodes(archive).find(n=>n.type==='input')
  assert.equal(input.props.value,'D:\\media\\待上传-已上传');assert.equal(input.props.readonly,'')
  assert.equal(f.props.modelValue.captionMode,'EMPTY');assert.equal(f.props.modelValue.recursive,false)
  f.props.disabled=true;f.state.update({link:'https://t.me/new/1'});assert.equal(f.props.modelValue.link,'')
  assert.equal(f.calls.length,0)
})
