import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as vue from 'vue'
import { parse, compileScript } from 'vue/compiler-sfc'
import ts from 'typescript'

const { descriptor, errors } = parse(readFileSync(new URL('../src/components/CompactRecord.vue', import.meta.url), 'utf8'), { filename: 'CompactRecord.vue' })
assert.deepEqual(errors, [])
const code = ts.transpileModule(compileScript(descriptor, { id: 'compact-record' }).content, {
  compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 },
}).outputText
const module = { exports: {} }
new Function('require', 'module', 'exports', code)(name => {
  if (name === 'vue') return vue
  if (name.endsWith('.vue')) return {}
  throw new Error('Unexpected dependency: ' + name)
}, module, module.exports)
function mount(t, initial = {}) {
  const props = vue.reactive({ title: '任务', description: '已保存 1/2', status: '正在处理', ...initial })
  const scope = vue.effectScope(), state = scope.run(() => module.exports.default.setup(props, { expose() {} }))
  t.after(() => scope.stop())
  return { props, state }
}
const toggle = (state, open) => state.toggled({ currentTarget: { open } })

test('leaving the task page collapses an open record and returning keeps it collapsed', async t => {
  const { props, state } = mount(t, { active: true })
  toggle(state, true); assert.equal(state.expanded.value, true)
  props.active = false; await vue.nextTick(); assert.equal(state.expanded.value, false)
  props.description = '已保存 2/2'; props.status = '已完成'; props.active = true
  await vue.nextTick(); assert.equal(state.expanded.value, false)
  assert.equal(props.description, '已保存 2/2', 'collapsing does not clear refreshed task data')
  toggle(state, true); assert.equal(state.expanded.value, true)
})

test('a delayed native toggle cannot reopen an inactive record', async t => {
  const { props, state } = mount(t, { active: true, defaultExpanded: true })
  props.active = false; assert.equal(state.expanded.value, false)
  const details = { open: true }; state.toggled({ currentTarget: details }); await vue.nextTick()
  assert.equal(details.open, false, 'the delayed native event is also corrected in the disclosure element')
  assert.equal(state.expanded.value, false)
  props.active = true; await vue.nextTick(); assert.equal(state.expanded.value, false)
})

test('new hidden records remain collapsed even when their initial default requests expansion', t => {
  const { state } = mount(t, { active: false, defaultExpanded: true })
  assert.equal(state.expanded.value, false)
})

test('progress updates never override the user expansion choice while the page is active', async t => {
  const { props, state } = mount(t, { active: true, defaultExpanded: true })
  props.defaultExpanded = false; props.status = '已完成'; await vue.nextTick()
  assert.equal(state.expanded.value, true)
  toggle(state, false); props.status = '正在处理'; props.defaultExpanded = true
  await vue.nextTick(); assert.equal(state.expanded.value, false)
})

test('records outside the task center retain the existing optional-active behavior', t => {
  const { state } = mount(t, { defaultExpanded: true })
  assert.equal(state.expanded.value, true)
  toggle(state, false); assert.equal(state.expanded.value, false)
})
