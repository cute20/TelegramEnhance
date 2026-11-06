<script setup lang="ts">
import {onBeforeUnmount, ref, watch} from 'vue'
import {api, ApiError, type TransferPreferences} from '../api'
import AppIcon from './AppIcon.vue'
interface DesktopPreferences {trayEnabled:boolean;traySupported:boolean;trayActive:boolean;warning:string|null}
const props=withDefaults(defineProps<{available:boolean;active?:boolean}>(),{active:true})
const emit=defineEmits<{accessError:[cause:ApiError];updated:[value:TransferPreferences]}>()
const saved=ref<TransferPreferences|null>(null),desktop=ref<DesktopPreferences|null>(null)
const directory=ref(''),concurrency=ref(1),trayEnabled=ref(false),busy=ref(''),error=ref(''),notice=ref(''),uncertain=ref(false)
let generation=0,disposed=false
function ready(){return props.available&&props.active&&!disposed}
function valid(value:TransferPreferences){return value&&typeof value.defaultDownloadDirectory==='string'&&Number.isInteger(value.downloadConcurrency)&&value.downloadConcurrency>=1&&value.downloadConcurrency<=4}
async function load(){
  if(!ready()||busy.value)return
  const draftDirectory=directory.value,draftConcurrency=concurrency.value,draftTray=trayEnabled.value
  const keepTransferDraft=!!saved.value&&(draftDirectory!==saved.value.defaultDownloadDirectory||draftConcurrency!==saved.value.downloadConcurrency)
  const keepDesktopDraft=!!desktop.value&&draftTray!==desktop.value.trayEnabled
  const g=++generation;busy.value='load';error.value='';notice.value=''
  try{
    const results=await Promise.allSettled([api<TransferPreferences>('/settings/transfers'),api<DesktopPreferences>('/settings/desktop')])
    if(!ready()||g!==generation)return
    let failed=false
    const [transferResult,desktopResult]=results
    if(transferResult.status==='fulfilled'&&valid(transferResult.value)){saved.value=transferResult.value;if(!keepTransferDraft&&directory.value===draftDirectory&&concurrency.value===draftConcurrency){directory.value=saved.value.defaultDownloadDirectory;concurrency.value=saved.value.downloadConcurrency}emit('updated',saved.value)}
    else {failed=true;const cause=transferResult.status==='rejected'?transferResult.reason:new Error('传输偏好回执无法确认');report(cause)}
    if(desktopResult.status==='fulfilled'&&desktopResult.value&&typeof desktopResult.value.trayEnabled==='boolean'&&typeof desktopResult.value.traySupported==='boolean') {desktop.value=desktopResult.value;if(!keepDesktopDraft&&trayEnabled.value===draftTray)trayEnabled.value=desktopResult.value.trayEnabled}
    else {failed=true;report(desktopResult.status==='rejected'?desktopResult.reason:new Error('桌面设置回执无法确认'))}
    if(!failed)uncertain.value=false
  }finally{if(g===generation)busy.value=''}
}
function report(cause:unknown){error.value=cause instanceof Error?cause.message:'设置读取未完成';if(cause instanceof ApiError&&[401,403].includes(cause.status))emit('accessError',cause)}
async function save(kind:'transfers'|'desktop'){
  if(!ready()||busy.value||uncertain.value||kind==='transfers'&&!saved.value||kind==='desktop'&&!desktop.value)return
  const g=++generation;busy.value=kind;error.value='';notice.value=''
  try{
    if(kind==='transfers'){
      const result=await api<TransferPreferences>('/settings/transfers',{method:'PUT',body:{defaultDownloadDirectory:directory.value.trim(),downloadConcurrency:concurrency.value}})
      if(!ready()||g!==generation)return
      if(!valid(result))throw new Error('传输偏好保存回执无法确认')
      saved.value=result;directory.value=result.defaultDownloadDirectory;concurrency.value=result.downloadConcurrency;emit('updated',result)
      notice.value='传输偏好已保存。新表单使用默认目录；下一下载任务使用新的并发数。'
    }else{
      const result=await api<DesktopPreferences>('/settings/desktop',{method:'PUT',body:{trayEnabled:trayEnabled.value}})
      if(!ready()||g!==generation)return
      if(!result||typeof result.trayEnabled!=='boolean'||typeof result.trayActive!=='boolean')throw new Error('托盘设置保存回执无法确认')
      desktop.value=result;trayEnabled.value=result.trayEnabled;notice.value=result.trayActive?'托盘图标已启用，可从托盘打开资源台或正常退出。':'托盘偏好已保存。'
    }
  }catch(cause){if(g!==generation||!ready())return;report(cause);if(!(cause instanceof ApiError&&cause.status===400))uncertain.value=true}
  finally{if(g===generation)busy.value=''}
}
watch([()=>props.available,()=>props.active],()=>{generation++;if(busy.value&&busy.value!=='load')uncertain.value=true;busy.value='';if(ready())void load()},{immediate:true})
onBeforeUnmount(()=>{disposed=true;generation++})
</script>
<template>
  <section id="transfer-preferences" class="settings-section local-preferences" aria-labelledby="transfer-preferences-heading">
    <div class="section-heading"><span class="section-icon"><AppIcon name="tasks"/></span><div><h2 id="transfer-preferences-heading">传输与桌面</h2><p>让新任务沿用你的常用目录与资源偏好。</p></div></div>
    <p v-if="error" class="notice warning" role="alert">{{error}}</p><p v-if="notice" class="preferences-notice" role="status">{{notice}}</p>
    <form @submit.prevent="save('transfers')">
      <div class="field"><label for="default-download-directory">默认下载目录</label><input id="default-download-directory" v-model="directory" class="input mono" :disabled="!available||!!busy||uncertain" placeholder="留空则每次创建时填写" autocomplete="off"/><small>仅用于新建表单，已有任务和规则的目录保持不变。</small></div>
      <div class="field"><label for="download-concurrency">同时下载文件数</label><select id="download-concurrency" v-model.number="concurrency" class="input" :disabled="!available||!!busy||uncertain"><option :value="1">1 个 · 节省资源</option><option :value="2">2 个</option><option :value="3">3 个</option><option :value="4">4 个</option></select><small>下一下载任务生效。上传仍按组发送，完成回执后归档。</small></div>
      <button class="btn" type="submit" :disabled="!available||!!busy||uncertain||!saved||directory.trim()===saved.defaultDownloadDirectory&&concurrency===saved.downloadConcurrency">{{busy==='transfers'?'正在保存…':'保存传输偏好'}}</button>
    </form>
    <div class="desktop-preferences">
      <label class="tray-toggle"><input v-model="trayEnabled" type="checkbox" :disabled="!available||!!busy||uncertain||!desktop?.traySupported&&!trayEnabled"/><span><strong>显示系统托盘图标</strong><small>关闭网页后后台仍运行，可从 Windows 托盘返回或退出。</small></span></label>
      <p v-if="desktop?.warning" class="section-footnote" role="status">{{desktop.warning}}</p>
      <button class="btn" type="button" :disabled="!available||!!busy||uncertain||!desktop||trayEnabled===desktop.trayEnabled" @click="save('desktop')">{{busy==='desktop'?'正在应用…':'应用托盘设置'}}</button>
    </div>
    <button type="button" class="preferences-refresh" :disabled="!available||!!busy" @click="load">{{uncertain?'读取当前设置并核对结果':'刷新设置'}}</button>
  </section>
</template>
<style scoped>
.preferences-notice{font-size:12px;color:var(--success);margin:0 0 16px}.desktop-preferences{border-top:1px solid var(--line);margin-top:22px;padding-top:20px}.tray-toggle{display:flex;align-items:flex-start;gap:10px;cursor:pointer;margin-bottom:14px}.tray-toggle input{accent-color:var(--blue);width:16px;height:16px;margin:4px 0 0;flex-shrink:0}.tray-toggle strong{display:block;font-size:13px;color:var(--ink);font-weight:550}.tray-toggle small{display:block;font-size:12px;color:var(--muted);line-height:1.7;margin-top:5px}.preferences-refresh{border:0;background:none;color:var(--blue);font:inherit;font-size:12px;padding:0;margin-top:18px;cursor:pointer}.local-preferences .notice{margin-bottom:16px}
</style>
