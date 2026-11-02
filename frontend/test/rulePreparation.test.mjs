import assert from 'node:assert/strict'
import test from 'node:test'
import { preparationElapsed, rulePreparationId, validRulePreparation } from '../src/rulePreparation.ts'

const identity={operationId:'00000000-0000-4000-8000-000000000001',ruleId:'00000000-0000-4000-8000-000000000002',accountId:'9007199254740993',version:2,revision:3,kind:'preview'}
const running=()=>({...identity,state:'RUNNING',startedAt:1000,finishedAt:null,error:null,result:null})
test('rule preparation receipts bind the operation, account, revision and phase before accepting terminal results',()=>{
  assert.equal(validRulePreparation(running(),identity),true)
  assert.equal(validRulePreparation({...running(),state:'COMPLETED',finishedAt:2000,result:{preview:'final'}},identity),true)
  assert.equal(validRulePreparation({...running(),state:'FAILED',finishedAt:2000,error:'文件已变化'},identity),true)
  for(const change of [{operationId:'wrong'},{ruleId:'wrong'},{accountId:'2'},{version:3},{revision:4},{kind:'observe'},{state:'READY'},{startedAt:-1},{finishedAt:2000},{error:'failure'},{result:{partial:true}}])assert.equal(validRulePreparation({...running(),...change},identity),false)
  for(const change of [{state:'COMPLETED',finishedAt:2000},{state:'FAILED',finishedAt:2000,error:''},{state:'FAILED',finishedAt:500,error:'failure'},{state:'COMPLETED',finishedAt:2000,error:'failure',result:{}}])assert.equal(validRulePreparation({...running(),...change},identity),false)
})
test('elapsed preparation time uses observed wall time without a fabricated percentage',()=>{
  assert.equal(preparationElapsed(1000,1000),'0:00');assert.equal(preparationElapsed(1000,66000),'1:05');assert.equal(preparationElapsed(1000,0),'0:00')
})
test('preparation operation IDs support secure RNG fallback and fail explicitly without secure randomness',()=>{
  const id=rulePreparationId({getRandomValues(bytes){bytes.fill(255);return bytes}})
  assert.equal(id,'ffffffff-ffff-4fff-bfff-ffffffffffff')
  assert.throws(()=>rulePreparationId({}),/安全操作号/)
})
