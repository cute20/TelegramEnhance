export const taskCategories = [
  { id: 'download', label: '单帖下载' }, { id: 'batches', label: '频道批量下载' }, { id: 'upload', label: '上传' },
] as const
export type TaskCategory = typeof taskCategories[number]['id']
export function validTaskCategory(value: unknown): value is TaskCategory {
  return taskCategories.some(category => category.id === value)
}
