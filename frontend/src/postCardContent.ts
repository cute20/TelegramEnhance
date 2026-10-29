export interface PostCardContent {
  title: string
  excerpt: string
  tags: string[]
  hasSourceTags: boolean
}

const comparableText = (value: string) => value.normalize('NFKC').replace(/\s+/gu, ' ').trim()
const comparableTag = (value: string) => value.normalize('NFKC').toLowerCase().replace(/^#/u, '').trim()

/** A display projection only: the indexed post and its search terms stay intact. */
export function postCardContent(title: string, text: string, tags: readonly string[]): PostCardContent {
  const heading = title.trim() || '未命名主帖'
  const lines = text.trim().split(/\r\n?|\n/u)
  const excerpt = comparableText(lines[0] ?? '') === comparableText(heading)
    ? lines.slice(1).join('\n').trim() : text.trim()
  const shownTags = new Set([...`${heading}\n${excerpt}`.normalize('NFKC').toLowerCase()
    .matchAll(/#[\p{L}\p{N}_][\p{L}\p{M}\p{N}_]*/gu)].map(match => match[0].slice(1)))
  const remainingTags: string[] = []
  for (const tag of tags) {
    const key = comparableTag(tag)
    if (!key || shownTags.has(key)) continue
    shownTags.add(key)
    remainingTags.push(tag.startsWith('#') ? tag : `#${tag}`)
  }
  return { title: heading, excerpt, tags: remainingTags, hasSourceTags: tags.length > 0 }
}
