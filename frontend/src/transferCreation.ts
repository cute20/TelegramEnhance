import type { Media } from './library'

export interface DownloadCreationSelection {
  entry: 'CHANNEL' | 'POST' | 'MANUAL'
  source: string
  tags: string
  keywords: string
  types: Media['type'][]
  includeMain: boolean
  targetDirectory: string
  suggestedName: string
}
export interface UploadCreationSelection {
  link: string
  sourceDirectory: string
  recursive: boolean
  captionMode: 'FILENAME' | 'EMPTY'
}
export const emptyDownloadSelection = (): DownloadCreationSelection => ({
  entry: 'CHANNEL', source: '', tags: '', keywords: '', types: ['VIDEO'],
  includeMain: false, targetDirectory: '', suggestedName: '',
})
export const emptyUploadSelection = (): UploadCreationSelection => ({
  link: '', sourceDirectory: '', recursive: true, captionMode: 'FILENAME',
})
