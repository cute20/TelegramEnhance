<script setup lang="ts">
import {computed,ref,watch} from 'vue'
import AppIcon from './AppIcon.vue'
const props=defineProps<{title:string;description:string;status:string;tone?:'neutral'|'success'|'warning'|'active';timestamp?:number;timeLabel?:string;defaultExpanded?:boolean;alert?:string|null;active?:boolean}>()
const expanded=ref(props.active!==false&&!!props.defaultExpanded)
const timeText=computed(()=>props.timestamp&&Number.isFinite(props.timestamp)?new Intl.DateTimeFormat('zh-CN',{timeZone:'Asia/Shanghai',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit'}).format(props.timestamp):'')
function toggled(event:Event){const details=event.currentTarget as HTMLDetailsElement;if(props.active===false)details.open=false;expanded.value=details.open}
watch(()=>props.active,active=>{if(active===false)expanded.value=false},{flush:'sync'})
</script>
<template>
  <article class="compact-record">
    <details class="record-disclosure" :open="expanded" @toggle="toggled">
      <summary class="record-summary">
        <span class="record-main"><strong class="record-title" :title="title">{{title}}</strong><span class="record-meta"><span :title="description">{{description}}</span><time v-if="timeText">{{timeLabel??'更新'}} {{timeText}}</time></span></span>
        <span class="record-end"><span class="record-state" :class="tone??'neutral'">{{status}}</span><span class="record-hint">{{expanded?'收起':'详情'}}<AppIcon name="chevron" /></span></span>
        <span v-if="alert" class="record-alert" :title="alert">{{alert}}</span>
      </summary>
      <div v-if="expanded" class="record-body"><slot /></div>
    </details>
    <footer v-if="$slots.actions" class="record-actions"><slot name="actions" /></footer>
  </article>
</template>
<style scoped>
.compact-record{min-width:0;background:#fff;border:1px solid #dfe8f0;border-radius:8px;margin:0 0 8px;overflow:hidden}.record-summary{display:grid;grid-template-columns:minmax(0,1fr) auto;gap:6px 16px;padding:12px 15px;cursor:pointer;list-style:none}.record-summary::-webkit-details-marker{display:none}.record-summary::marker{content:''}.record-summary:hover{background:#fafcfe}.record-summary:focus-visible{outline:2px solid #2986cc;outline-offset:-3px;border-radius:7px}.record-main{min-width:0}.record-title{display:block;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;color:#35566e;font-size:13px;font-weight:600;line-height:1.7}.record-meta{display:flex;gap:12px;align-items:center;min-width:0;margin-top:3px;font-size:10px;line-height:1.7;color:#7690a4}.record-meta>span{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.record-meta time{flex-shrink:0;color:#8ba0b1}.record-end{display:flex;align-items:center;gap:13px;align-self:start;padding-top:2px;white-space:nowrap}.record-state{font-size:10px;color:#7990a2;background:#f1f5f8;border-radius:4px;padding:3px 7px}.record-state.success{color:#4e8b70;background:#edf7f1}.record-state.warning{color:#a4773c;background:#fff7e9}.record-state.active{color:#347bb1;background:#edf5fc}.record-hint{display:flex;align-items:center;gap:4px;color:#8199ab;font-size:10px}.record-hint .icon{width:12px;height:12px}.record-disclosure[open] .record-hint .icon{transform:rotate(90deg)}.record-alert{grid-column:1/-1;min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap;font-size:10px;line-height:1.7;color:#a4773c}.record-body{padding:16px;border-top:1px solid #e8eff5;min-width:0}.record-actions{display:flex;gap:7px;flex-wrap:wrap;padding:0 15px 10px}.record-actions :deep(.btn){font-size:10px;min-height:28px;padding:5px 9px}@media(max-width:600px){.record-summary{gap:7px 9px;padding:11px 12px}.record-end{flex-direction:column;align-items:flex-end;gap:5px}.record-meta{display:block}.record-meta>span{display:block}.record-meta time{display:block}.record-body{padding:13px}.record-title{font-size:12px}.record-actions{padding:0 12px 10px}}
</style>
