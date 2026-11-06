import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import ts from 'typescript'

const code=ts.transpileModule(readFileSync(new URL('../src/api.ts',import.meta.url),'utf8'),{compilerOptions:{module:ts.ModuleKind.CommonJS,target:ts.ScriptTarget.ES2022}}).outputText
function client(body,status=400){
  const module={exports:{}},window={location:{hash:'#token=synthetic',pathname:'/',search:''},history:{replaceState(){}},setTimeout,clearTimeout}
  new Function('require','module','exports','window','sessionStorage','fetch',code)(name=>{assert.equal(name,'./thumbnailMemory');return {invalidateThumbnails(){}}},module,module.exports,window,{setItem(){},getItem(){return null}},async()=>({ok:false,status,json:async()=>body}))
  return module.exports
}
test('API failures preserve the explicit pre-commit rejection code without inferring it from HTTP status',async()=>{
  for(const [body,expected] of [[{message:'未创建',code:'CREATION_REJECTED'},'CREATION_REJECTED'],[{message:'保存后会话变化'},undefined],[{message:'invalid code',code:123},undefined]]){
    const api=client(body)
    await assert.rejects(api.api('/transfers/upload/tasks',{method:'POST'}),error=>error instanceof api.ApiError&&error.status===400&&error.code===expected&&error.message===body.message)
  }
})
