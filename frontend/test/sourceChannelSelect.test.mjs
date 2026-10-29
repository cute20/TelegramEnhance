import assert from 'node:assert/strict'
import test from 'node:test'
import {readFileSync} from 'node:fs'
import * as vue from 'vue'
import {parse,compileScript} from 'vue/compiler-sfc'
import ts from 'typescript'
import * as channels from '../src/downloadChannel.ts'

const items=()=>[{id:'-1001',title:'个人-精品'},{id:'-1002',title:'Photo 收藏'},{id:'-1003',title:'另一个频道'}]
function mount(t,initial={}){
  const props=vue.reactive({modelValue:'-1001',channels:items(),disabled:false,...initial}),events=[]
  const parsed=parse(readFileSync(new URL('../src/components/SourceChannelSelect.vue',import.meta.url),'utf8'),{filename:'SourceChannelSelect.vue'})
  const code=ts.transpileModule(compileScript(parsed.descriptor,{id:'source-select-test'}).content,{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
  const module={exports:{}},modules={vue:{...vue,useId:()=> 'source-select-test'},'../downloadChannel':channels}
  new Function('require','module','exports',code)(name=>name.endsWith('.vue')?{}:modules[name],module,module.exports)
  const scope=vue.effectScope(),state=scope.run(()=>module.exports.default.setup(props,{expose(){},emit:(name,value)=>{events.push([name,value]);props.modelValue=value}}))
  t.after(()=>scope.stop())
  return {state,props,events}
}
function key(value,extra={}){return {key:value,prevented:false,stopped:false,isComposing:false,preventDefault(){this.prevented=true},stopPropagation(){this.stopped=true},...extra}}

test('one field shows the confirmed channel and opens all loaded choices on focus',t=>{
  const f=mount(t);assert.equal(f.state.displayValue.value,'个人-精品');f.state.show()
  assert.equal(f.state.open.value,true);assert.equal(f.state.displayValue.value,'');assert.equal(f.state.matches.value.length,3);assert.equal(f.state.activeIndex.value,0);assert.ok(f.state.activeId.value)
  f.state.close();assert.equal(f.state.displayValue.value,'个人-精品');assert.equal(f.state.activeId.value,undefined)
})
test('typing filters locally without changing the confirmed channel or emitting model updates',t=>{
  const f=mount(t);f.state.search('ＰＨＯＴＯ');assert.deepEqual(f.state.matches.value.map(c=>c.id),['-1002']);assert.equal(f.props.modelValue,'-1001');assert.deepEqual(f.events,[])
  f.state.close();assert.equal(f.state.displayValue.value,'个人-精品')
})
test('choosing a channel commits its string ID and restores its title in the same field',t=>{
  const f=mount(t);f.state.search('photo');f.state.choose(f.state.matches.value[0])
  assert.deepEqual(f.events,[['update:modelValue','-1002']]);assert.equal(f.state.displayValue.value,'Photo 收藏');assert.equal(f.state.open.value,false)
  f.state.choose(f.props.channels[1]);assert.equal(f.events.length,1)
})
test('arrow navigation wraps and Enter selects the highlighted channel',t=>{
  const f=mount(t);const down=key('ArrowDown');f.state.keydown(down);assert.equal(down.prevented,true);assert.equal(f.state.activeIndex.value,0)
  f.state.keydown(key('ArrowUp'));assert.equal(f.state.activeIndex.value,2);f.state.keydown(key('ArrowDown'));assert.equal(f.state.activeIndex.value,0)
  f.state.keydown(key('ArrowDown'));const enter=key('Enter');f.state.keydown(enter);assert.equal(enter.prevented,true);assert.equal(f.props.modelValue,'-1002');assert.equal(f.state.open.value,false)
})
test('Escape cancels searching without closing the dialog or changing the channel',t=>{
  const f=mount(t);f.state.search('photo');const escape=key('Escape');f.state.keydown(escape)
  assert.equal(escape.prevented,true);assert.equal(escape.stopped,true);assert.equal(f.state.displayValue.value,'个人-精品');assert.deepEqual(f.events,[])
})
test('no matches cannot commit typed text, and Tab returns to the confirmed channel',t=>{
  const f=mount(t);f.state.search('missing');assert.equal(f.state.activeIndex.value,-1);assert.equal(f.state.activeId.value,undefined);f.state.keydown(key('Enter'));assert.deepEqual(f.events,[])
  const tab=key('Tab');f.state.keydown(tab);assert.equal(tab.prevented,false);assert.equal(f.state.displayValue.value,'个人-精品')
})
test('disabled picker rejects edits, navigation and selection and closes an existing popup',async t=>{
  const f=mount(t);f.state.show();f.props.disabled=true;await vue.nextTick();assert.equal(f.state.open.value,false)
  f.state.show();f.state.search('photo');f.state.choose(f.props.channels[1]);f.state.keydown(key('ArrowDown'));assert.equal(f.state.open.value,false);assert.equal(f.state.query.value,'');assert.deepEqual(f.events,[])
})
test('external source changes and list refresh cannot silently select a search result',async t=>{
  const f=mount(t);f.state.search('photo');f.props.modelValue='https://t.me/synthetic';await vue.nextTick();assert.equal(f.state.open.value,false);assert.equal(f.state.displayValue.value,'https://t.me/synthetic')
  f.state.search('photo');f.props.channels=[f.props.channels[0]];await vue.nextTick();assert.equal(f.state.matches.value.length,0);assert.equal(f.state.activeIndex.value,-1);assert.deepEqual(f.events,[])
  f.state.choose({id:'-9999',title:'old item'});assert.deepEqual(f.events,[])
})
test('IME Enter and focus moving inside the picker do not commit or discard the composition',t=>{
  const f=mount(t);f.state.search('个人');const composing=key('Enter',{isComposing:true});f.state.keydown(composing);assert.deepEqual(f.events,[]);assert.equal(composing.prevented,false)
  f.state.focusout({currentTarget:{contains:()=>true},relatedTarget:{}});assert.equal(f.state.open.value,true)
  f.state.focusout({currentTarget:{contains:()=>false},relatedTarget:null});assert.equal(f.state.open.value,false);assert.equal(f.state.displayValue.value,'个人-精品')
})
