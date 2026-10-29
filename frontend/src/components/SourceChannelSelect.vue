<script setup lang="ts">
import {computed,nextTick,ref,useId,watch} from 'vue'
import {filterSourceChannels,type SourceChannel} from '../downloadChannel'
import AppIcon from './AppIcon.vue'

const props=defineProps<{modelValue:string;channels:SourceChannel[];disabled:boolean;loading?:boolean}>()
const emit=defineEmits<{'update:modelValue':[value:string]}>()
const id=useId(),open=ref(false),query=ref(''),activeIndex=ref(-1),listbox=ref<HTMLElement|null>(null)
const selected=computed(()=>props.channels.find(channel=>channel.id===props.modelValue))
const matches=computed(()=>filterSourceChannels(props.channels,query.value))
const displayValue=computed(()=>open.value?query.value:selected.value?.title??props.modelValue)
const activeId=computed(()=>open.value&&activeIndex.value>=0&&activeIndex.value<matches.value.length?id+'-option-'+activeIndex.value:undefined)
function close(){open.value=false;query.value='';activeIndex.value=-1}
function revealActive(){void nextTick(()=>listbox.value?.querySelector('[data-active="true"]')?.scrollIntoView({block:'nearest'}))}
function show(){
  if(props.disabled||open.value)return
  query.value='';open.value=true
  const index=matches.value.findIndex(channel=>channel.id===props.modelValue)
  activeIndex.value=index>=0?index:matches.value.length?0:-1
  revealActive()
}
function search(value:string){if(props.disabled)return;query.value=value;open.value=true;activeIndex.value=matches.value.length?0:-1;revealActive()}
function choose(channel:SourceChannel){
  if(props.disabled||!props.channels.some(item=>item.id===channel.id))return
  if(channel.id!==props.modelValue)emit('update:modelValue',channel.id)
  close()
}
function keydown(event:KeyboardEvent){
  if(props.disabled||event.isComposing)return
  if(event.key==='ArrowDown'||event.key==='ArrowUp'){
    event.preventDefault()
    if(!open.value){show();if(event.key==='ArrowUp'&&!selected.value)activeIndex.value=matches.value.length-1}
    else if(matches.value.length)activeIndex.value=(activeIndex.value+(event.key==='ArrowDown'?1:-1)+matches.value.length)%matches.value.length
    revealActive()
  }else if(event.key==='Enter'&&open.value){event.preventDefault();const channel=matches.value[activeIndex.value];if(channel)choose(channel)}
  else if(event.key==='Escape'&&open.value){event.preventDefault();event.stopPropagation();close()}
  else if(event.key==='Tab')close()
}
function focusout(event:FocusEvent){if(!(event.currentTarget as HTMLElement).contains(event.relatedTarget as Node|null))close()}
watch(()=>props.modelValue,close)
watch(()=>props.disabled,disabled=>{if(disabled)close()})
watch(()=>props.channels,()=>{if(open.value){activeIndex.value=matches.value.length?0:-1;revealActive()}})
</script>

<template>
  <div class="source-channel-select" @focusout="focusout">
    <label :for="id">当前可读频道</label>
    <div class="source-channel-input">
      <input :id="id" class="input" role="combobox" :value="displayValue" :disabled="disabled" :aria-expanded="open" :aria-controls="id+'-options'" :aria-activedescendant="activeId" aria-autocomplete="list" autocomplete="off" maxlength="256" placeholder="选择频道，或输入名称 / ID 搜索" @focus="show" @click="show" @input="search(($event.target as HTMLInputElement).value)" @keydown="keydown" />
      <AppIcon name="chevron" />
    </div>
    <div v-if="open" class="source-channel-popup">
      <div class="source-channel-count" role="status">{{loading?'正在读取频道…':`${query.trim()?'匹配':'本次列表'} ${matches.length} 个频道`}}</div>
      <div :id="id+'-options'" ref="listbox" class="source-channel-options" role="listbox" aria-label="可读频道">
        <button v-for="(channel,index) in matches" :id="id+'-option-'+index" :key="channel.id" type="button" role="option" tabindex="-1" :aria-selected="channel.id===modelValue" :data-active="index===activeIndex" @mousedown.prevent @click="choose(channel)">
          <span>{{channel.title}}</span><AppIcon v-if="channel.id===modelValue" name="check" />
        </button>
      </div>
      <p v-if="!matches.length&&!loading" class="source-channel-empty">没有匹配频道，请修改搜索文字。</p>
    </div>
  </div>
</template>

<style scoped>
.source-channel-select{position:relative;flex:1;min-width:0}.source-channel-select label{display:block;font-size:11px;color:#69869e}.source-channel-input{position:relative;margin-top:7px}.source-channel-input .input{margin:0;width:100%;padding-right:34px}.source-channel-input>.icon{position:absolute;right:12px;top:50%;width:14px;height:14px;transform:translateY(-50%) rotate(90deg);pointer-events:none;color:#8298aa}.source-channel-popup{position:absolute;top:calc(100% + 5px);left:0;right:0;z-index:20;background:white;border:1px solid #cbddea;border-radius:7px;box-shadow:0 8px 24px #17364f20;overflow:hidden}.source-channel-count{padding:9px 12px;color:#8199ad;font-size:10px;border-bottom:1px solid #e8eff4}.source-channel-options{max-height:240px;overflow-y:auto;overscroll-behavior:contain}.source-channel-options>button{display:flex;align-items:center;justify-content:space-between;gap:8px;width:100%;padding:11px 12px;border:0;background:white;text-align:left;color:#456981;font:inherit;font-size:12px;cursor:pointer}.source-channel-options>button>span{min-width:0;overflow-wrap:anywhere}.source-channel-options>button>.icon{width:15px;height:15px;flex-shrink:0}.source-channel-options>button:hover,.source-channel-options>button[data-active=true]{background:#edf5fc}.source-channel-options>button[aria-selected=true]{color:#176bb0;font-weight:600}.source-channel-empty{padding:12px;margin:0;color:#8199ad;font-size:11px}
</style>
