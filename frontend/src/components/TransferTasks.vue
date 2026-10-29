<script setup lang="ts">
import {ref,watch} from 'vue'
import type {ApiError} from '../api'
import {taskCategories,validTaskCategory,type TaskCategory} from '../taskCategories'
import UploadTasks from './UploadTasks.vue'
import DownloadTasks from './DownloadTasks.vue'
import DownloadBatches from './DownloadBatches.vue'
const props=withDefaults(defineProps<{authorized:boolean;available:boolean;accountId:string|null;active?:boolean;category?:TaskCategory;refreshVersion?:number}>(),{active:true})
const emit=defineEmits<{accessError:[cause:ApiError];'update:category':[value:TaskCategory]}>()
const selected=ref<TaskCategory>(props.category??'download')
function select(category:TaskCategory){if(!validTaskCategory(category))return;selected.value=category;emit('update:category',category)}
watch(()=>props.category,category=>{if(validTaskCategory(category))selected.value=category})
</script>
<template>
  <div class="transfer-tasks">
    <div class="task-category-switch" role="group" aria-label="任务类型"><button v-for="category in taskCategories" :id="'task-category-'+category.id" :key="category.id" type="button" :class="{selected:selected===category.id}" :aria-pressed="selected===category.id" :aria-controls="'task-panel-'+category.id" @click="select(category.id)">{{category.label}}</button></div>
    <section id="task-panel-download" v-show="selected==='download'" aria-labelledby="task-category-download"><DownloadTasks :active="active!==false&&selected==='download'" :refresh-version="refreshVersion" :authorized="authorized" :available="available" :account-id="accountId" @access-error="emit('accessError',$event)" /></section>
    <section id="task-panel-batches" v-show="selected==='batches'" aria-labelledby="task-category-batches"><DownloadBatches :active="active!==false&&selected==='batches'" :refresh-version="refreshVersion" :authorized="authorized" :available="available" :account-id="accountId" @access-error="emit('accessError',$event)" /></section>
    <section id="task-panel-upload" v-show="selected==='upload'" aria-labelledby="task-category-upload"><UploadTasks :active="active!==false&&selected==='upload'" :refresh-version="refreshVersion" :authorized="authorized" :available="available" :account-id="accountId" @access-error="emit('accessError',$event)" /></section>
  </div>
</template>
<style scoped>
.transfer-tasks{min-width:0}.task-category-switch{display:flex;flex-wrap:wrap;gap:8px;border-bottom:1px solid #dfe8ef;padding-bottom:15px;margin-bottom:23px}.task-category-switch button{border:1px solid #d8e3ec;border-radius:6px;background:#fff;color:#7893a8;padding:8px 14px;font-size:12px;line-height:1.5}.task-category-switch button.selected{background:#263f54;border-color:#263f54;color:#fff}.task-category-switch button:hover:not(.selected){background:#f0f6fa}@media(max-width:480px){.task-category-switch{gap:6px;margin-bottom:18px}.task-category-switch button{font-size:10px;padding:7px 10px}}
</style>
