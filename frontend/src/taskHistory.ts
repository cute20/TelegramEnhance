/** Keep already browsed history while newer status pages arrive or older pages append. */
export function mergeTaskHistory<T extends { id: string; createdAt: number }>(current: readonly T[], incoming: readonly T[]): T[] {
  const records = new Map(current.map(item => [item.id, item]))
  for (const item of incoming) records.set(item.id, item)
  return [...records.values()].sort((a, b) => b.createdAt - a.createdAt || (a.id < b.id ? -1 : a.id > b.id ? 1 : 0))
}
