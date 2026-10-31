<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, useId, watch } from 'vue'

const props=withDefaults(defineProps<{open:boolean;title:string;busy?:boolean;embedded?:boolean;stableSize?:boolean}>(),{busy:false,embedded:false,stableSize:false})
const emit=defineEmits<{close:[]}>()
const dialog=ref<HTMLDialogElement|null>(null),closeButton=ref<HTMLButtonElement|null>(null)
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
onBeforeUnmount(()=>{disposed=true;revision++;if(dialog.value?.open)dialog.value.close();restoreFocus()})
</script>

<template>
  <div v-if="embedded" class="rule-form-embedded"><slot v-if="open" /></div>
  <dialog v-else ref="dialog" class="rule-form-dialog" :class="{'stable-size':stableSize}" :aria-labelledby="titleId" @cancel="cancel" @click="backdrop" @close="closed">
    <header class="rule-form-dialog-header">
      <h2 :id="titleId">{{title}}</h2>
      <button ref="closeButton" class="rule-form-dialog-close" type="button" :disabled="busy" :aria-label="'关闭'+title" @click="requestClose">×</button>
    </header>
    <div class="rule-form-dialog-body"><slot /></div>
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
</style>
