import assert from 'node:assert/strict'
import test from 'node:test'
import { validUploadPreview } from '../src/uploadPreview.ts'
import { GROUP_BYTES, byteSplitCounts, byteSplitGroups } from './fixtures/uploadByteGroups.mjs'

const clone=value=>JSON.parse(JSON.stringify(value))
function preview(groups=byteSplitGroups()){
  const files=groups.flatMap(group=>group.files)
  return{id:'00000000-0000-4000-8000-000000000001',accountId:'1',state:'PREVIEW_ONLY',sourceDirectory:'D:/synthetic-source',archiveDirectory:'D:/synthetic-archive',recursive:true,captionMode:'FILENAME',albumSize:10,channelTitle:'Synthetic',postTitle:'Synthetic',postText:'',postLink:'https://t.me/synthetic/1',discussionTitle:'Synthetic',permission:'MEDIA_ALLOWED_AT_CHECK',slowModeSeconds:0,fileCount:files.length,totalBytes:files.reduce((sum,file)=>sum+file.size,0),groups,otherFiles:0,deferredFiles:0,linkedEntries:0,duplicateFiles:0,warnings:[],targetCheckedAt:1,createdAt:1,expiresAt:600001}
}
const file=(name,size,type='VIDEO')=>({relativePath:name,fileName:name,mediaType:type,caption:name,size})
const group=(number,files,type='VIDEO')=>({number,mediaType:type,mode:files.length===1?'SINGLE':'ALBUM',files})

test('66 videos split into ten byte-bounded groups are accepted despite every group containing fewer than ten members',()=>{
  const value=preview();assert.equal(value.fileCount,66);assert.deepEqual(value.groups.map(group=>group.files.length),byteSplitCounts)
  assert.equal(validUploadPreview(value,'1'),true)
  for(const current of value.groups)assert.ok(current.files.reduce((sum,file)=>sum+file.size,0)<=GROUP_BYTES)
})

test('a same-type short group can only end when the next member would exceed eight GiB, including the exact boundary',()=>{
  const GiB=1024**3
  assert.equal(validUploadPreview(preview([group(1,[file('a',7*GiB)]),group(2,[file('b',GiB+1)])]),'1'),true)
  for(const sizes of [[1,1],[7*GiB,GiB]]){
    const value=preview([group(1,[file('a',sizes[0])]),group(2,[file('b',sizes[1])])])
    assert.equal(validUploadPreview(value,'1'),false)
  }
  const ten=Array.from({length:10},(_,index)=>file('f'+index,1))
  assert.equal(validUploadPreview(preview([group(1,ten),group(2,[file('tail',1)])]),'1'),true)
})

test('eight GiB is allowed per file and group but either bound cannot be exceeded',()=>{
  for(const type of ['PHOTO','VIDEO','AUDIO','DOCUMENT','ANIMATION']){
    assert.equal(validUploadPreview(preview([group(1,[file('a',GROUP_BYTES,type)],type)]),'1'),true)
    assert.equal(validUploadPreview(preview([group(1,[file('a',GROUP_BYTES+1,type)],type)]),'1'),false)
  }
  assert.equal(validUploadPreview(preview([group(1,[file('a',GROUP_BYTES/2),file('b',GROUP_BYTES/2)])]),'1'),true)
  assert.equal(validUploadPreview(preview([group(1,[file('a',GROUP_BYTES/2),file('b',GROUP_BYTES/2+1)])]),'1'),false)
})

test('byte-sized grouping retains mode, type order, count, path, caption and total-byte validation',()=>{
  for(const change of [v=>v.groups[0].mode='SINGLE',v=>v.groups[0].number=2,v=>v.fileCount--,v=>v.totalBytes--,v=>v.groups[0].files[0].caption='wrong',v=>v.groups[1].files[0]=v.groups[0].files[0],v=>v.groups[1].files[0].mediaType='PHOTO',v=>v.groups[1].files[0].size=NaN]){
    const value=clone(preview());change(value);assert.equal(validUploadPreview(value,'1'),false)
  }
  assert.equal(validUploadPreview(preview([group(1,[file('video',1)]),group(2,[file('photo',1,'PHOTO')],'PHOTO')]),'1'),false)
})

test('preview groups obey the selected maximum and accept individual sends when it is one',()=>{
  const custom=preview([group(1,[file('a',1),file('b',1),file('c',1)]),group(2,[file('d',1)])]);custom.albumSize=3
  assert.equal(validUploadPreview(custom,'1'),true)
  custom.albumSize=2;assert.equal(validUploadPreview(custom,'1'),false)
  custom.albumSize=4;assert.equal(validUploadPreview(custom,'1'),false,'a short group cannot end before the chosen maximum without a byte limit')
  const individual=preview([group(1,[file('a',1)]),group(2,[file('b',1)])]);individual.albumSize=1
  assert.equal(validUploadPreview(individual,'1'),true)
  for(const invalid of [undefined,null,0,11,1.5,'3']){individual.albumSize=invalid;assert.equal(validUploadPreview(individual,'1'),false)}
})
