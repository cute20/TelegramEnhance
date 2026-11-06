<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'

const props=withDefaults(defineProps<{open:boolean;title:string;busy?:boolean;embedded?:boolean;stableSize?:boolean;scrollKey?:string}>(),{busy:false,embedded:false,stableSize:false,scrollKey:'default'})
const emit=defineEmits<{close:[]}>()
const dialog=ref<HTMLDialogElement|null>(null),closeButton=ref<HTMLButtonElement|null>(null)
const body=ref<HTMLElement|null>(null),scrollPositions=new Map<string,number>()
const titleId=useId()
let returnFocus:HTMLElement|null=null,disposed=false,revision=0

function restoreFocus(){const previous=returnFocus;returnFocus=null;if(previous?.isConnected!==false)previous?.focus({preventScroll:true})}
async function syncDialog(){
  if(props.embedded)return
  const ticket=++revision;await nextTick()
  const element=dialog.value;if(disposed||ticket!==revision||!element)return
  if(!props.open){if(element.open)element.close();restoreFocus();return}
  if(!element.open){const active=document.activeElement;returnFocus=active&&'focus' in active?active as HTMLElement:null;element.showModal()}
  await nextTick();if(disposed||ticket!==revision||!props.open||!element.open)return
  const first=element.querySelector<HTMLElement>('[autofocus]:not(:disabled), input:not(:disabled):not([readonly]):not([type="hidden"]), select:not(:disabled), textarea:not(:disabled):not([readonly])')
  ;(first??closeButton.value)?.focus({preventScroll:true})
}
function requestClose(){if(!props.busy)emit('close')}
function cancel(event:Event){event.preventDefault();requestClose()}
function backdrop(event:MouseEvent){
  const element=dialog.value;if(!element||event.target!==element)return
  const bounds=element.getBoundingClientRect()
  if(event.clientX<bounds.left||event.clientX>bounds.right||event.clientY<bounds.top||event.clientY>bounds.bottom)requestClose()
}
function closed(){
  if(disposed)return
  if(props.open){if(props.busy)void syncDialog();else requestClose()}
  else restoreFocus()
}
watch([()=>props.open,()=>dialog.value],()=>void syncDialog(),{flush:'post',immediate:true})
watch(()=>props.scrollKey,async (key,previous)=>{
  if(body.value&&previous!==undefined)scrollPositions.set(previous,body.value.scrollTop)
  await nextTick()
  if(!disposed&&props.scrollKey===key&&body.value)body.value.scrollTop=scrollPositions.get(key)??0
})
onBeforeUnmount(()=>{disposed=true;revision++;if(dialog.value?.open)dialog.value.close();restoreFocus()})
</script>

<template>
  <div v-if="embedded" class="rule-form-embedded"><slot v-if="open" /></div>
  <dialog v-else ref="dialog" class="rule-form-dialog" :class="{'stable-size':stableSize}" :aria-labelledby="titleId" @cancel="cancel" @click="backdrop" @close="closed">
    <header class="rule-form-dialog-header">
      <h2 :id="titleId">{{title}}</h2>
      <button ref="closeButton" class="rule-form-dialog-close" type="button" :disabled="busy" :aria-label="'关闭'+title" @click="requestClose">×</button>
    </header>
    <div ref="body" class="rule-form-dialog-body"><slot /></div>
    <footer v-if="$slots.footer" class="rule-form-dialog-footer"><slot name="footer" /></footer>
  </dialog>
</template>

<style scoped>
.rule-form-dialog{box-sizing:border-box;width:min(800px,calc(100vw - 32px));max-width:calc(100vw - 32px);max-height:calc(100dvh - 32px);margin:auto;padding:0;border:1px solid #dce7ef;border-radius:12px;overflow:hidden;background:#fff;color:#55788f;box-shadow:0 24px 80px #102d4d33}
.rule-form-dialog[open]{display:flex;flex-direction:column}.rule-form-dialog::backdrop{background:#12283d88;backdrop-filter:blur(3px)}
.rule-form-dialog.stable-size{height:min(780px,calc(100dvh - 32px))}.stable-size>.rule-form-dialog-body{flex:1}
.rule-form-dialog-header{display:flex;align-items:center;justify-content:space-between;gap:16px;flex:none;padding:18px 24px;border-bottom:1px solid #e5edf3}.rule-form-dialog-header h2{font-size:18px;font-weight:650;line-height:1.5;color:#1d394e;margin:0;overflow-wrap:anywhere}
.rule-form-dialog-close{display:grid;place-items:center;flex:none;width:32px;height:32px;padding:0;border:0;border-radius:6px;background:transparent;color:#7894a8;font:24px/1 sans-serif;cursor:pointer}.rule-form-dialog-close:hover{background:#f1f6fa}.rule-form-dialog-close:focus-visible{outline:2px solid #287fca;outline-offset:2px}.rule-form-dialog-close:disabled{opacity:.45;cursor:wait}
.rule-form-dialog-body{min-width:0;min-height:0;padding:6px 24px 24px;overflow:auto;overscroll-behavior:contain;scrollbar-gutter:stable}
@media(max-width:600px){.rule-form-dialog{width:calc(100vw - 16px);max-width:calc(100vw - 16px);max-height:calc(100dvh - 16px);border-radius:9px}.rule-form-dialog-header{padding:14px 16px}.rule-form-dialog-header h2{font-size:16px}.rule-form-dialog-body{padding:2px 16px 18px}}
.rule-form-dialog{width:min(920px,calc(100vw - 32px));border-color:var(--line,#dce7ef);color:var(--ink,#29485f);background:var(--surface,#fff)}.rule-form-dialog.stable-size{height:min(860px,calc(100dvh - 32px))}.rule-form-dialog-header{padding:19px 26px}.rule-form-dialog-header h2{font-size:19px;color:var(--ink,#243f55)}.rule-form-dialog-body{padding:20px 26px 24px}.rule-form-dialog-footer{flex:none;padding:14px 26px 18px;border-top:1px solid var(--line,#e1e9f0);background:var(--surface,#fff);max-height:38dvh;overflow:auto;scrollbar-gutter:stable;box-shadow:0 -4px 14px #21456804}
.rule-form-dialog :deep(.btn){font-size:13px;min-height:38px}.rule-form-dialog :deep(.input){font-size:14px;min-height:40px}.rule-form-dialog :deep(label),.rule-form-dialog :deep(legend){font-size:13px}.rule-form-dialog :deep(small),.rule-form-dialog :deep(.notice),.rule-form-dialog :deep(.transfer-limit),.rule-form-dialog :deep(.transfer-intro),.rule-form-dialog :deep(.transfer-count),.rule-form-dialog :deep(.transfer-count>small),.rule-form-dialog :deep(.upload-notes p),.rule-form-dialog :deep(.batch-download p),.rule-form-dialog :deep(.channel-muted),.rule-form-dialog :deep(.channel-source-form small){font-size:12px;line-height:1.8}.rule-form-dialog :deep(.creation-primary-actions){display:flex;align-items:center;justify-content:flex-end;flex-wrap:wrap;gap:10px;margin:0}.rule-form-dialog :deep(.creation-primary-actions .notice){flex-basis:100%;margin:0;min-width:0}.rule-form-dialog :deep(.download-create){border:0;border-radius:0;background:transparent;padding:0;margin:0}.rule-form-dialog :deep(.download-create>p){font-size:12px;line-height:1.7;margin:7px 0 10px}.rule-form-dialog :deep(.download-select-tools){font-size:12px;line-height:1.7}.rule-form-dialog :deep(.download-select-tools .btn){font-size:12px;min-height:32px}.rule-form-dialog :deep(.download-create>.btn){font-size:13px;min-height:38px}.rule-form-dialog :deep(.upload-group li strong),.rule-form-dialog :deep(.upload-group header),.rule-form-dialog :deep(.collection-description strong){font-size:13px}.rule-form-dialog :deep(.upload-group li small),.rule-form-dialog :deep(.collection-description small),.rule-form-dialog :deep(.collection-meta),.rule-form-dialog :deep(.collection-link){font-size:12px}
@media(max-width:600px){.rule-form-dialog{width:calc(100vw - 16px)}.rule-form-dialog-header{padding:15px 18px}.rule-form-dialog-body{padding:16px 18px 20px}.rule-form-dialog-footer{padding:12px 18px 16px;max-height:42dvh}.rule-form-dialog-header h2{font-size:18px}}
.rule-form-dialog .rule-form-dialog-body :deep(.btn),.rule-form-dialog .rule-form-dialog-footer :deep(.btn){font-size:13px;min-height:38px}.rule-form-dialog .rule-form-dialog-body :deep(.input){font-size:14px;min-height:40px}.rule-form-dialog .rule-form-dialog-body :deep(label),.rule-form-dialog .rule-form-dialog-body :deep(legend){font-size:13px}
</style>
