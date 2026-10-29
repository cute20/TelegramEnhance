<script setup lang="ts">
import {computed,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import {api,ApiError} from '../api'
import {validChannelScan,validSourceChannels,validPostLink,type DownloadChannels,type ChannelScan,type ChannelRow} from '../downloadChannel'
import AppIcon from './AppIcon.vue'
import MediaViewSwitch from './MediaViewSwitch.vue'
import InfiniteLoad from './InfiniteLoad.vue'
import SourceChannelSelect from './SourceChannelSelect.vue'
import DownloadBatchPreparation from './DownloadBatchPreparation.vue'
const props=defineProps<{authorized:boolean;available:boolean;accountId:string|null;active:boolean;readOnly?:boolean;initialSource?:{source:string;tags:string;keywords:string}}>()
const emit=defineEmits<{openPost:[link:string];accessError:[cause:ApiError];tasks:[];locked:[value:boolean]}>()
const source=ref(props.initialSource?.source??''),tags=ref(props.initialSource?.tags??''),keywords=ref(props.initialSource?.keywords??''),channels=ref<DownloadChannels|null>(null),scan=ref<ChannelScan|null>(null)
const busy=ref(''),running=ref(false),needsCheck=ref(false),error=ref(''),notice=ref(''),layout=ref<'list'|'cards'>('cards'),visible=ref(20)
const ready=()=>props.active&&props.authorized&&props.available&&!!props.accountId
const rows=computed(()=>scan.value?.rows.slice(0,visible.value)??[])
const selectedPosts=ref<string[]>([]),batchLocked=ref(false)
const eligiblePosts=computed(()=>scan.value?.rows.filter(row=>row.match==='MATCH'&&row.post.mainCoverage==='COMPLETE')??[])
const controlsLocked=computed(()=>!!busy.value||running.value||batchLocked.value)
function selectPost(row:ChannelRow){if(props.readOnly||controlsLocked.value||row.match!=='MATCH'||row.post.mainCoverage!=='COMPLETE')return;selectedPosts.value=selectedPosts.value.includes(row.post.key)?selectedPosts.value.filter(key=>key!==row.post.key):selectedPosts.value.length<20?[...selectedPosts.value,row.post.key]:selectedPosts.value}
function selectBatch(){if(!props.readOnly&&!controlsLocked.value)selectedPosts.value=eligiblePosts.value.slice(0,20).map(row=>row.post.key)}
function lockBatch(value:boolean){if(batchLocked.value===value)return;batchLocked.value=value;emit('locked',value)}
const hasFilters=computed(()=>!!(tags.value.trim()||keywords.value.trim()))
const filterSummary=computed(()=>{
  const selectedTags=scan.value?.tags.join('、')??tags.value.trim(),selectedKeywords=scan.value?.keywords.join('、')??keywords.value.trim()
  return selectedTags||selectedKeywords?['标签：'+(selectedTags||'不限'),'关键词：'+(selectedKeywords||'不限')].join('；')+'（全部满足）':'不限标签和关键词，展示全部已读取主帖'
})
const excludedCount=computed(()=>scan.value?scan.value.scanned-scan.value.matched-scan.value.unverified:0)
let generation=0,disposed=false
const alive=(g:number,account:string|null)=>!disposed&&g===generation&&props.accountId===account&&ready()
function fail(cause:unknown){error.value=cause instanceof Error?cause.message:'频道读取未完成。';if(cause instanceof ApiError&&[401,403].includes(cause.status))emit('accessError',cause)}
async function loadChannels(){if(props.readOnly||!ready()||controlsLocked.value)return;const g=generation,account=props.accountId;busy.value='channels';error.value=''
  try{const value=await api<DownloadChannels>('/transfers/download/channels',{timeout:50000});if(!alive(g,account))return;if(!validSourceChannels(value))throw new Error('下载频道列表无法确认。');channels.value=value}
  catch(cause){if(alive(g,account))fail(cause)}finally{if(alive(g,account))busy.value=''}
}
async function begin(){if(!ready()||controlsLocked.value||!source.value.trim())return;const g=++generation,account=props.accountId;scan.value=null;selectedPosts.value=[];needsCheck.value=false;error.value='';notice.value='';visible.value=20;busy.value='start'
  try{const value=await api<ChannelScan>('/transfers/download/channels/preview',{method:'POST',timeout:50000,body:{source:source.value.trim(),tags:tags.value,keywords:keywords.value}});if(!alive(g,account))return;if(!validChannelScan(value,account))throw new Error('频道预览回执无法确认。');scan.value=value;busy.value='';await continueScan()}
  catch(cause){if(alive(g,account)){fail(cause);notice.value='未自动重试；可核对来源后重新开始预览。'}}finally{if(alive(g,account))busy.value=''}
}
async function step(g:number,account:string|null){const before=scan.value;if(!before||!before.canContinue||busy.value)return false;busy.value='step'
  try{const value=await api<ChannelScan>('/transfers/download/channels/preview/'+before.id+'/next',{method:'POST',timeout:85000,body:{revision:before.revision}});if(!alive(g,account))return false;if(!validChannelScan(value,account,before,true))throw new Error('扫描位置或结果无法确认。');scan.value=value;if(value.error){error.value=value.error;return false}return value.canContinue}
  catch(cause){if(alive(g,account)){fail(cause);needsCheck.value=true;notice.value='当前请求结果未确认，请先核对扫描位置；不会自动重试。'}return false}
  finally{if(alive(g,account))busy.value=''}
}
async function continueScan(){if(!ready()||controlsLocked.value||needsCheck.value||!scan.value?.canContinue)return;running.value=true;error.value='';notice.value='';const g=generation,account=props.accountId,start=Date.now();let steps=0
  try{while(running.value&&alive(g,account)&&scan.value?.canContinue){if(steps>=(props.readOnly?10:200)||Date.now()-start>=20*60*1000){notice.value=props.readOnly?'本批帖子已加载，点击“继续浏览”查看更早内容。':'本次连续扫描达到200步或20分钟，已暂停，可继续已保存的预览位置。';break}steps++;if(!await step(g,account))break}}
  finally{if(alive(g,account))running.value=false}
}
function stop(){running.value=false;notice.value=busy.value==='step'?'已停止后续扫描，等待当前主帖结果。':'已暂停；已读结果保留，可继续扫描。'}
async function check(){if(!ready()||busy.value||!scan.value)return;running.value=false;const g=generation,account=props.accountId,before=scan.value;busy.value='check'
  try{const value=await api<ChannelScan>('/transfers/download/channels/preview/'+before.id);if(!alive(g,account))return;if(!validChannelScan(value,account,before))throw new Error('当前扫描状态无法确认。');scan.value=value;needsCheck.value=value.busy;error.value=value.error??'';notice.value=value.busy?'后台仍在读取当前主帖，请稍后再核对。':'扫描位置已核对，需要时点击继续扫描。'}
  catch(cause){if(alive(g,account)){fail(cause);needsCheck.value=true}}finally{if(alive(g,account))busy.value=''}
}
function openPost(row:ChannelRow){if(!ready()||batchLocked.value||!validPostLink(row.postLink))return;stop();emit('openPost',row.postLink)}
function reset(){generation++;running.value=false;busy.value='';needsCheck.value=false;scan.value=null;selectedPosts.value=[];lockBatch(false);error.value='';notice.value='';visible.value=20}
function clearFilters(){if(!ready()||controlsLocked.value)return;tags.value='';keywords.value='';reset();notice.value='已清空主帖筛选；点击“开始扫描预览”重新读取，展示全部已读取主帖。'}
watch([tags,keywords],reset,{flush:'sync'})
watch(source,()=>{const cleared=hasFilters.value;tags.value='';keywords.value='';reset();if(cleared)notice.value='已切换频道并清空上一频道的主帖筛选，默认展示全部已读取主帖。'},{flush:'sync'})
watch([()=>props.authorized,()=>props.available,()=>props.accountId],(_value,previous)=>{reset();channels.value=null;if(previous&&previous[2]!==props.accountId){source.value='';tags.value='';keywords.value=''}if(ready())void loadChannels()})
watch(()=>props.active,active=>{if(!active){generation++;running.value=false;if(busy.value==='step')needsCheck.value=true;busy.value=''}else if(ready()){if(needsCheck.value&&scan.value)void check();else if(!channels.value)void loadChannels()}},{immediate:true})
onBeforeUnmount(()=>{disposed=true;generation++;running.value=false})
onMounted(()=>{if(props.readOnly&&props.initialSource&&ready())void begin()})
</script>
<template>
  <section class="channel-source-preview" aria-label="频道下载扫描预览">
    <template v-if="!readOnly"><p class="channel-intro">先扫描频道主帖目录，再选择主帖查看评论媒体。支持可访问的外部频道，预览不加入频道、不保存资源库、不下载原媒体。</p>
    <div class="channel-discovery"><SourceChannelSelect v-model="source" :channels="channels?.items??[]" :disabled="controlsLocked||!ready()" /><button class="btn" :disabled="controlsLocked||!ready()" @click="loadChannels">{{busy==='channels'?'正在读取频道…':'刷新频道列表'}}</button></div>
    <p v-if="channels" class="channel-muted">本次列表 {{channels.items.length}} 个频道；点击上方输入框可搜索并选择。</p>
    <p v-if="channels?.partial" class="channel-muted">本次列表可能未列全；可直接填写公开频道链接、用户名，或私有频道的一条主帖链接。</p>
    <p v-for="warning in channels?.warnings??[]" :key="warning" class="channel-muted">{{warning}}</p>
    <form class="channel-source-form" @submit.prevent="begin"><label>频道来源<input v-model="source" class="input" placeholder="https://t.me/频道用户名 或 @用户名" autocomplete="off" :disabled="controlsLocked||!ready()" required /></label><div class="channel-filters"><label>主帖标签 · 全部满足<input v-model="tags" class="input" placeholder="留空则不限制标签" :disabled="controlsLocked" maxlength="2048" /></label><label>主帖关键词 · 全部包含<input v-model="keywords" class="input" placeholder="留空则不限制关键词" :disabled="controlsLocked" maxlength="2048" /></label></div><small>多项用空格或逗号分隔，标签和关键词同时填写时也必须全部满足；只匹配主帖文字。</small><div class="channel-filter-summary"><span>当前主帖筛选：{{filterSummary}}</span><button v-if="hasFilters" class="btn" type="button" :disabled="controlsLocked||!ready()" @click="clearFilters">清空主帖筛选</button></div><div class="channel-actions"><button class="btn primary" :disabled="controlsLocked||!ready()||!source.trim()">{{busy==='start'?'正在解析频道…':'开始扫描预览'}}</button><button v-if="running" class="btn" type="button" @click="stop">停止后续扫描</button><button v-else-if="scan?.canContinue&&!needsCheck" class="btn" type="button" :disabled="controlsLocked||!ready()" @click="continueScan">继续扫描</button><button v-if="scan" class="btn" type="button" :disabled="controlsLocked||!ready()" @click="check">核对扫描位置</button></div></form>
    </template>
    <div v-else class="channel-actions"><button class="btn" :disabled="controlsLocked||!ready()" @click="begin">刷新帖子</button><button v-if="running" class="btn" @click="stop">停止读取</button><button v-else-if="scan?.canContinue&&!needsCheck" class="btn" :disabled="controlsLocked||!ready()" @click="continueScan">继续浏览</button><button v-if="needsCheck" class="btn" :disabled="controlsLocked||!ready()" @click="check">核对读取位置</button></div>
    <p v-if="error" class="notice danger" role="alert">{{error}}</p><p v-if="notice" class="notice" role="status">{{notice}}</p>
    <template v-if="scan"><div class="channel-scan-status" role="status"><strong>{{scan.channel.title}}</strong><span>已检查 {{scan.scanned}} 条主帖 · 符合筛选 {{scan.matched}} · 不匹配 {{excludedCount}} · 待核实 {{scan.unverified}}</span><span class="channel-active-filter">主帖筛选：{{filterSummary}}</span><small v-if="scan.unverified">待核实项仅供检查相册文字，不计入符合筛选的主帖数量。</small><b>{{running?'正在分批扫描…':scan.exhausted?'本轮主帖目录已到边界':scan.limitReached?'预览达到上限，频道尚未读完':needsCheck?'请先核对当前位置':'扫描已暂停'}}</b><small>以上仅统计已读取范围；相册按一条主帖，评论未逐帖扫描，不估算全频道媒体总量。</small></div><p v-for="warning in scan.warnings" :key="warning" class="channel-muted">{{warning}}</p><div class="channel-results-bar"><span>预览结果 {{scan.rows.length}} 条<span v-if="scan.unverified">（符合筛选 {{scan.matched}} · 待核实 {{scan.unverified}}）</span></span><MediaViewSwitch v-model="layout" /></div>
      <div v-if="!readOnly" class="channel-batch-tools"><span>已选 {{selectedPosts.length}} / 20 条主帖</span><button class="btn" :disabled="controlsLocked||!eligiblePosts.length" @click="selectBatch">选择前20条已核全主帖</button><button v-if="selectedPosts.length" class="btn" :disabled="controlsLocked" @click="selectedPosts=[]">清空主帖选择</button></div>
      <DownloadBatchPreparation v-if="!readOnly&&selectedPosts.length" :scan-id="scan.id" :scan-revision="scan.revision" :post-keys="selectedPosts" :account-id="accountId" :active="active" :authorized="authorized" :available="available" @locked="lockBatch" @tasks="emit('tasks')" @access-error="emit('accessError',$event)" />
      <div class="channel-scan-rows" :class="layout"><article v-for="row in rows" :key="row.post.key" class="channel-scan-row"><div class="channel-row-icon"><AppIcon name="library" /><span>{{row.post.mainMedia.length}} 个主帖附件</span></div><div class="channel-row-body"><label v-if="!readOnly&&row.match==='MATCH'&&row.post.mainCoverage==='COMPLETE'" class="channel-batch-select"><input type="checkbox" :checked="selectedPosts.includes(row.post.key)" :disabled="controlsLocked||!selectedPosts.includes(row.post.key)&&selectedPosts.length>=20" @change="selectPost(row)" /> 加入批量下载</label><small v-else-if="!readOnly">范围待核实，请使用单帖预览</small><span v-if="row.match==='UNVERIFIED'" class="status-pill">相册文字未核全，筛选待核实</span><h3>{{row.post.title}}</h3><p>{{row.post.text||'主帖没有文字'}}</p><small>{{row.post.mainCoverage==='COMPLETE'?'主帖范围已核全':'主帖范围待核实'}} · {{row.comments==='NOT_READ'?'评论尚未读取':'评论不可读或未开放'}}</small><details><summary>查看主帖全文</summary><p>{{row.post.text||'主帖没有文字'}}</p></details></div><button class="btn" :disabled="batchLocked||!ready()||!row.postLink" @click="openPost(row)">预览主帖与评论媒体</button></article></div>
      <p v-if="!scan.rows.length&&!running" class="channel-muted">已读取范围尚无匹配结果。{{scan.exhausted?'本轮主帖目录已到边界。':'可继续扫描；零匹配不表示频道已读完。'}}</p>
      <InfiniteLoad :has-more="visible<scan.rows.length" :busy="false" :blocked="!ready()" :version="visible+':'+scan.revision" :context="scan.id+':'+layout" @load="visible+=20" />
      <p v-if="!readOnly" class="channel-muted">扫描和准备清单在关闭或重启后需重新读取；已创建的下载任务独立保留，可在任务中心继续。</p>
    </template>
  </section>
</template>
<style scoped>
.channel-batch-tools{display:flex;flex-wrap:wrap;align-items:center;gap:8px;margin:12px 0;font-size:10px;color:#6b89a1}.channel-batch-tools .btn{font-size:10px}.channel-batch-select{display:flex;align-items:center;gap:5px;font-size:10px;color:#6b89a1;margin-bottom:10px}.channel-batch-select input{accent-color:#2986cc}
.channel-filter-summary{display:flex;flex-wrap:wrap;align-items:center;justify-content:space-between;gap:8px;margin-top:12px;padding:10px 12px;background:#f2f7fc;border-radius:5px;color:#456d8a;font-size:11px;overflow-wrap:anywhere}.channel-filter-summary>.btn{font-size:10px;flex-shrink:0}.channel-active-filter{color:#355b76;overflow-wrap:anywhere}
.channel-intro,.channel-muted{font-size:11px;color:#7d97ad;line-height:1.9}.channel-muted{font-size:10px}.channel-discovery{display:flex;gap:12px;align-items:end;margin:18px 0}.channel-discovery label{flex:1}.channel-source-form label,.channel-discovery label{display:block;font-size:11px;color:#69869e}.channel-source-form .input,.channel-discovery .input{margin-top:7px;width:100%}.channel-filters{display:grid;grid-template-columns:1fr 1fr;gap:12px;margin:14px 0 7px}.channel-source-form>small{font-size:10px;color:#8ba1b3}.channel-actions{display:flex;flex-wrap:wrap;gap:8px;margin:15px 0 20px}.channel-scan-status{display:flex;flex-direction:column;gap:8px;background:#f2f7fc;border:1px solid #deebf5;padding:15px;border-radius:7px;font-size:12px;color:#567d9c}.channel-scan-status small{font-size:10px;line-height:1.8}.channel-results-bar{display:flex;justify-content:space-between;align-items:center;margin:20px 0 12px;font-size:11px;color:#7e99af}.channel-scan-rows{display:grid;gap:12px}.channel-scan-rows.cards{grid-template-columns:repeat(auto-fill,minmax(230px,1fr))}.channel-scan-row{border:1px solid #e0e9f0;border-radius:7px;padding:15px;display:flex;gap:14px;align-items:flex-start}.cards .channel-scan-row{flex-direction:column}.channel-row-icon{background:#edf5fb;color:#7c9cb5;padding:12px;border-radius:5px;display:flex;align-items:center;gap:7px;font-size:10px;flex-shrink:0}.channel-row-icon .icon{width:20px;height:20px}.channel-row-body{flex:1;min-width:0;width:100%}.channel-row-body h3{margin:0 0 8px;font-size:13px;color:#355b76;overflow-wrap:anywhere}.channel-row-body>p{font-size:11px;color:#7591a7;line-height:1.8;white-space:pre-line;display:-webkit-box;-webkit-line-clamp:3;-webkit-box-orient:vertical;overflow:hidden;overflow-wrap:anywhere}.channel-row-body small,.channel-row-body details{font-size:10px;line-height:1.8;color:#89a0b2}.channel-row-body details p{white-space:pre-wrap;overflow-wrap:anywhere}.channel-scan-row>.btn{font-size:10px;flex-shrink:0}.channel-source-preview .notice{font-size:11px}@media(max-width:650px){.channel-filters{grid-template-columns:1fr}.channel-discovery{align-items:stretch;flex-direction:column}.channel-scan-row{flex-direction:column}}
</style>
