import assert from 'node:assert/strict'
import test from 'node:test'
import { createThumbnailMemory, ThumbnailCancelledError, validThumbnailPreview, previewLimits,
  acquireThumbnail, readThumbnail, invalidateThumbnails, subscribeThumbnailInvalidation, forgetThumbnail } from '../src/thumbnailMemory.ts'

const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
const flush = () => new Promise(setImmediate)
function jpeg(bytes = 5) {
  const data = Buffer.alloc(bytes)
  data[0] = 255; data[1] = 216; data[2] = 255; data[bytes - 2] = 255; data[bytes - 1] = 217
  return 'data:image/jpeg;base64,' + data.toString('base64')
}
const preview = (overrides = {}) => ({ status: 'AVAILABLE', quality: 'THUMBNAIL', dataUrl: jpeg(), width: 480, height: 320, ...overrides })
const unavailable = () => ({ status: 'UNAVAILABLE', quality: null, dataUrl: null, width: 0, height: 0 })
const load = (cache, key, value = preview()) => cache.acquireThumbnail(key, async () => value).promise
const cancelled = handle => assert.rejects(handle.promise, ThumbnailCancelledError)

test('JPEG and geometry validation preserves the existing THUMBNAIL and MINI bounds', () => {
  assert.deepEqual(previewLimits('THUMBNAIL'), { bytes: 128 * 1024, edge: 480 })
  assert.deepEqual(previewLimits('MINI'), { bytes: 32 * 1024, edge: 256 })
  assert.equal(previewLimits('FULL'), null)
  assert.equal(validThumbnailPreview(preview({ dataUrl: jpeg(128 * 1024), height: 480 })), true)
  assert.equal(validThumbnailPreview(preview({ quality: 'MINI', dataUrl: jpeg(32 * 1024), width: 256, height: 256 })), true)
  assert.equal(validThumbnailPreview(unavailable()), true)
  for (const value of [null, [], {}, preview({ status: 'ERROR' }), preview({ quality: null }),
    preview({ width: 0 }), preview({ width: 481 }), preview({ width: 1.5 }), preview({ height: Infinity }),
    preview({ quality: 'MINI', width: 257, height: 1 }), preview({ dataUrl: jpeg(128 * 1024 + 1) }),
    preview({ quality: 'MINI', width: 1, height: 1, dataUrl: jpeg(32 * 1024 + 1) }),
    { ...unavailable(), dataUrl: jpeg() }, { ...unavailable(), width: 1 }, { ...unavailable(), quality: 'MINI' }]) {
    assert.equal(validThumbnailPreview(value), false)
  }
})

test('non-JPEG, malformed base64, missing JPEG markers and injected data URLs are rejected', () => {
  for (const dataUrl of [null, '', 'data:image/png;base64,/9j//9k=', 'https://example.invalid/image.jpg',
    'data:image/jpeg;base64,', 'data:image/jpeg;base64,!!!!', 'data:image/jpeg;base64,/9j//9k',
    'data:image/jpeg;base64,/9j//9k===', 'data:image/jpeg;base64, /9j//9k=',
    'data:image/jpeg;base64,' + Buffer.from('not a jpeg').toString('base64'),
    'data:image/jpeg;base64,' + Buffer.from([255, 216, 255, 0]).toString('base64'),
    jpeg() + '\n', 'data:image/svg+xml;base64,' + Buffer.from('<svg/>').toString('base64')]) {
    assert.equal(validThumbnailPreview(preview({ dataUrl })), false)
  }
})

test('cached thumbnail hits avoid IO, are immutable and expire exactly ten minutes after creation', async () => {
  let time = 100, requests = 0
  const cache = createThumbnailMemory({ now: () => time }), original = preview()
  const first = await load(cache, 'account:post', original)
  original.width = 1
  assert.equal(first.width, 480); assert.equal(Object.isFrozen(first), true)
  time += 5 * 60 * 1000
  const hit = await cache.acquireThumbnail('account:post', async () => { requests++; return preview() }).promise
  assert.equal(hit, first); assert.equal(requests, 0)
  time = 100 + 10 * 60 * 1000 - 1; assert.equal(cache.readThumbnail('account:post'), first)
  time++; assert.equal(cache.readThumbnail('account:post'), undefined)
  await cache.acquireThumbnail('account:post', async () => { requests++; return preview() }).promise
  assert.equal(requests, 1, 'reads do not extend the absolute TTL')
})

for (const value of [preview({ quality: 'MINI', width: 128, height: 96 }), unavailable()]) {
  test(`${value.quality ?? value.status} expires after thirty seconds and permits a thumbnail upgrade`, async () => {
    let time = 0
    const cache = createThumbnailMemory({ now: () => time })
    await load(cache, 'account:post', value)
    time = 29999; assert.equal(cache.readThumbnail('account:post').quality, value.quality)
    time = 30000; assert.equal(cache.readThumbnail('account:post'), undefined)
    await load(cache, 'account:post', preview())
    assert.equal(cache.readThumbnail('account:post').quality, 'THUMBNAIL')
  })
}

test('errors including 401, thrown fetchers and malformed receipts are never cached or converted to unavailable', async () => {
  const cache = createThumbnailMemory(), unauthorized = Object.assign(new Error('unauthorized'), { status: 401 })
  for (const error of [unauthorized, new Error('timeout')]) {
    await assert.rejects(cache.acquireThumbnail('a', async () => { throw error }).promise, actual => actual === error)
    assert.equal(cache.readThumbnail('a'), undefined)
  }
  await assert.rejects(cache.acquireThumbnail('a', () => { throw new Error('synchronous failure') }).promise, /synchronous failure/)
  for (const value of [{}, { ...unavailable(), width: -1 }, preview({ dataUrl: 'broken' })]) {
    await assert.rejects(load(cache, 'a', value), /回执无法确认/)
    assert.equal(cache.readThumbnail('a'), undefined)
  }
  assert.equal((await load(cache, 'a')).quality, 'THUMBNAIL')
})

test('one shared key runs one fetch and cancellation of its first consumer cannot cancel the second', async () => {
  const cache = createThumbnailMemory(), pending = deferred(); let requests = 0, alternate = 0
  const first = cache.acquireThumbnail('a', () => { requests++; return pending.promise })
  const second = cache.acquireThumbnail('a', async () => { alternate++; return unavailable() })
  await flush(); first.cancel(); await cancelled(first)
  pending.resolve(preview()); assert.equal((await second.promise).quality, 'THUMBNAIL')
  assert.equal(requests, 1); assert.equal(alternate, 0); assert.ok(cache.readThumbnail('a'))
  first.cancel(); second.cancel(); assert.ok(cache.readThumbnail('a'), 'settled consumer cancellation cannot evict shared data')
})

test('the document queue starts only two fetches and failures release a slot without overlapping work', async () => {
  const cache = createThumbnailMemory(), pending = Array.from({ length: 5 }, deferred), starts = []
  let active = 0, peak = 0
  const handles = pending.map((item, i) => cache.acquireThumbnail(String(i), () => {
    starts.push(i); active++; peak = Math.max(peak, active)
    return item.promise.finally(() => active--)
  }))
  await flush(); assert.deepEqual(starts, [0, 1])
  pending[0].reject(new Error('timeout')); await assert.rejects(handles[0].promise, /timeout/); await flush()
  assert.deepEqual(starts, [0, 1, 2])
  pending[1].resolve(preview()); await handles[1].promise; await flush(); assert.deepEqual(starts, [0, 1, 2, 3])
  pending[2].resolve(unavailable()); await handles[2].promise; await flush(); assert.deepEqual(starts, [0, 1, 2, 3, 4])
  pending[3].resolve(preview()); pending[4].resolve(preview()); await Promise.all(handles.slice(3).map(handle => handle.promise))
  assert.equal(peak, 2); assert.equal(active, 0)
})

test('a queued singleflight survives the first consumer leaving and starts its captured fetcher once', async () => {
  const cache = createThumbnailMemory({ concurrency: 1 }), pending = deferred(); let requests = 0
  const blocker = cache.acquireThumbnail('blocker', () => pending.promise)
  const first = cache.acquireThumbnail('queued', async () => { requests++; return preview() })
  const second = cache.acquireThumbnail('queued', async () => { throw new Error('must share original fetcher') })
  first.cancel(); await cancelled(first); await flush(); assert.equal(requests, 0)
  pending.resolve(unavailable()); await blocker.promise; await second.promise; assert.equal(requests, 1)
})

test('the last queued consumer cancels promptly, settles every promise and never reaches the fetcher', async () => {
  const cache = createThumbnailMemory({ concurrency: 1 }), pending = deferred(); let requests = 0
  const blocker = cache.acquireThumbnail('blocker', () => pending.promise)
  const first = cache.acquireThumbnail('queued', async () => { requests++; return preview() })
  const second = cache.acquireThumbnail('queued', async () => { requests++; return preview() })
  first.cancel(); second.cancel(); await Promise.all([cancelled(first), cancelled(second)])
  pending.resolve(unavailable()); await blocker.promise; await flush()
  assert.equal(requests, 0); assert.equal(cache.readThumbnail('queued'), undefined)
})

test('an orphaned in-flight result is not cached, while a new consumer can still adopt the same in-flight request', async () => {
  const cache = createThumbnailMemory(), firstPending = deferred(), nextPending = deferred(); let requests = 0
  const abandoned = cache.acquireThumbnail('a', () => { requests++; return firstPending.promise })
  await flush(); abandoned.cancel(); await cancelled(abandoned); firstPending.resolve(preview()); await flush()
  assert.equal(cache.readThumbnail('a'), undefined)
  const first = cache.acquireThumbnail('a', () => { requests++; return nextPending.promise })
  await flush(); first.cancel(); await cancelled(first)
  const adopted = cache.acquireThumbnail('a', async () => { requests++; return unavailable() })
  nextPending.resolve(preview()); await adopted.promise
  assert.equal(requests, 2); assert.equal(cache.readThumbnail('a').quality, 'THUMBNAIL')
})

test('cancelling or invalidating before the fetch microtask prevents a not-yet-submitted request', async () => {
  const cache = createThumbnailMemory(); let calls = 0
  const first = cache.acquireThumbnail('first', async () => { calls++; return preview() })
  first.cancel(); await cancelled(first); await flush(); assert.equal(calls, 0)
  const next = cache.acquireThumbnail('next', async () => { calls++; return preview() })
  cache.invalidateThumbnails(); await cancelled(next); await flush(); assert.equal(calls, 0)
})

test('global invalidation clears cached and queued data and late old-epoch reads cannot overwrite a fresh result', async () => {
  const cache = createThumbnailMemory(); await load(cache, 'cached')
  const a = deferred(), b = deferred(), starts = []
  const oldA = cache.acquireThumbnail('a', () => { starts.push('old-a'); return a.promise })
  const oldB = cache.acquireThumbnail('b', () => { starts.push('old-b'); return b.promise })
  const queued = cache.acquireThumbnail('queued', async () => { starts.push('old-queued'); return preview() })
  await flush(); cache.invalidateThumbnails()
  await Promise.all([cancelled(oldA), cancelled(oldB), cancelled(queued)])
  assert.equal(cache.readThumbnail('cached'), undefined)
  const fresh = cache.acquireThumbnail('a', async () => { starts.push('fresh-a'); return preview({ width: 42 }) })
  await flush(); assert.deepEqual(starts, ['old-a', 'old-b'], 'old native reads still occupy the two slots')
  b.resolve(unavailable()); await fresh.promise; assert.equal(cache.readThumbnail('a').width, 42)
  a.resolve(preview({ width: 12 })); await flush()
  assert.equal(cache.readThumbnail('a').width, 42); assert.equal(cache.readThumbnail('b'), undefined)
  assert.deepEqual(starts, ['old-a', 'old-b', 'fresh-a'])
})

test('invalidation subscribers are synchronous, removable and isolated from another listener throwing', async () => {
  const cache = createThumbnailMemory(), observed = []
  await load(cache, 'a')
  const unsubscribe = cache.subscribeThumbnailInvalidation(() => observed.push(cache.readThumbnail('a')))
  cache.subscribeThumbnailInvalidation(() => { throw new Error('view error') })
  cache.subscribeThumbnailInvalidation(() => observed.push('other view'))
  cache.invalidateThumbnails(); assert.deepEqual(observed, [undefined, 'other view'])
  unsubscribe(); unsubscribe(); cache.invalidateThumbnails(); assert.deepEqual(observed, [undefined, 'other view', 'other view'])
})

test('forget removes only the broken image and applies a thirty-second retry cooldown without retry loops', async () => {
  let time = 0, calls = 0
  const cache = createThumbnailMemory({ now: () => time })
  await load(cache, 'broken'); await load(cache, 'healthy')
  cache.forgetThumbnail('broken'); assert.equal(cache.readThumbnail('broken').status, 'UNAVAILABLE'); assert.ok(cache.readThumbnail('healthy'))
  for (let i = 0; i < 5; i++) assert.equal((await cache.acquireThumbnail('broken', async () => { calls++; return preview() }).promise).status, 'UNAVAILABLE')
  time = 29999; assert.equal(cache.readThumbnail('broken').status, 'UNAVAILABLE'); assert.equal(calls, 0)
  time = 30000; await cache.acquireThumbnail('broken', async () => { calls++; return preview() }).promise
  assert.equal(calls, 1); assert.equal(cache.readThumbnail('broken').quality, 'THUMBNAIL')
})

test('forget also blocks an older in-flight result from replacing its decode-failure cooldown', async () => {
  const cache = createThumbnailMemory(), pending = deferred()
  const handle = cache.acquireThumbnail('broken', () => pending.promise)
  await flush(); cache.forgetThumbnail('broken'); await cancelled(handle)
  pending.resolve(preview()); await flush(); assert.equal(cache.readThumbnail('broken').status, 'UNAVAILABLE')
})

test('an old MINI decode failure cannot evict a replacement thumbnail cached by another component', async () => {
  let time = 0
  const cache = createThumbnailMemory({ now: () => time }), oldImage = preview({ quality: 'MINI', width: 128, height: 96 })
  await load(cache, 'shared', oldImage)
  time = 30000
  const replacement = await load(cache, 'shared', preview({ dataUrl: jpeg(8), width: 240, height: 180 }))
  cache.forgetThumbnail('shared', oldImage)
  assert.equal(cache.readThumbnail('shared'), replacement)
  assert.equal((await cache.acquireThumbnail('shared', async () => { throw new Error('must remain cached') }).promise).quality, 'THUMBNAIL')
})

test('an old decode failure cannot cancel another component replacement already in flight or add a negative cache entry', async () => {
  let time = 0, requests = 0
  const cache = createThumbnailMemory({ now: () => time }), oldImage = preview({ quality: 'MINI', width: 128, height: 96 })
  await load(cache, 'shared', oldImage)
  time = 30000
  const pending = deferred(), replacement = cache.acquireThumbnail('shared', () => { requests++; return pending.promise })
  await flush(); cache.forgetThumbnail('shared', oldImage)
  assert.equal(cache.readThumbnail('shared'), undefined)
  const joined = cache.acquireThumbnail('shared', async () => { throw new Error('replacement must remain singleflight') })
  pending.resolve(preview({ dataUrl: jpeg(8) }))
  const [first, second] = await Promise.all([replacement.promise, joined.promise])
  assert.equal(first, second); assert.equal(requests, 1); assert.equal(cache.readThumbnail('shared'), first)
})

test('an old decode failure cannot remove a queued replacement or consume its request slot', async () => {
  let time = 0, requests = 0
  const cache = createThumbnailMemory({ now: () => time, concurrency: 1 }), oldImage = preview({ quality: 'MINI', width: 128, height: 96 })
  await load(cache, 'shared', oldImage); await flush(); time = 30000
  const pending = deferred(), blocker = cache.acquireThumbnail('blocker', () => pending.promise)
  const replacement = cache.acquireThumbnail('shared', async () => { requests++; return preview() })
  cache.forgetThumbnail('shared', oldImage); assert.equal(cache.readThumbnail('shared'), undefined)
  pending.resolve(unavailable()); await blocker.promise; await replacement.promise
  assert.equal(requests, 1); assert.equal(cache.readThumbnail('shared').quality, 'THUMBNAIL')
})

test('conditional forget requires every cached image identity field and cools down only the actual failed image', async () => {
  const cache = createThumbnailMemory(), original = await load(cache, 'same')
  for (const changed of [{ status: 'UNAVAILABLE' }, { quality: 'MINI' }, { dataUrl: jpeg(8) }, { width: 12 }, { height: 12 }]) {
    cache.forgetThumbnail('same', { ...original, ...changed }); assert.equal(cache.readThumbnail('same'), original)
  }
  cache.forgetThumbnail('missing', original); assert.equal(cache.readThumbnail('missing'), undefined)
  cache.forgetThumbnail('same', { ...original }); assert.equal(cache.readThumbnail('same').status, 'UNAVAILABLE')
})

test('LRU evicts the least recently read entry rather than the oldest inserted one', async () => {
  const cache = createThumbnailMemory({ maxEntries: 3 })
  for (const key of ['a', 'b', 'c']) await load(cache, key)
  cache.readThumbnail('a'); await load(cache, 'd')
  assert.equal(cache.readThumbnail('b'), undefined)
  for (const key of ['a', 'c', 'd']) assert.ok(cache.readThumbnail(key))
})

test('the default entry limit is 512 even for unavailable entries with no image bytes', async () => {
  const cache = createThumbnailMemory()
  for (let i = 0; i < 513; i++) await load(cache, 'account:post:' + i, unavailable())
  assert.equal(cache.readThumbnail('account:post:0'), undefined)
  for (let i = 1; i < 513; i++) assert.ok(cache.readThumbnail('account:post:' + i))
})

test('the byte LRU budgets data URLs and metadata keys at two bytes per character including the exact boundary', async () => {
  const value = preview(), weight = (value.dataUrl.length + 1) * 2
  const cache = createThumbnailMemory({ maxBytes: 2 * weight })
  await load(cache, 'a', value); await load(cache, 'b', value)
  assert.ok(cache.readThumbnail('a')); assert.ok(cache.readThumbnail('b'))
  cache.readThumbnail('a'); await load(cache, 'c', value)
  assert.equal(cache.readThumbnail('b'), undefined); assert.ok(cache.readThumbnail('a')); assert.ok(cache.readThumbnail('c'))
  const metadata = createThumbnailMemory({ maxBytes: 200 })
  await load(metadata, 'k'.repeat(101), unavailable()); assert.equal(metadata.readThumbnail('k'.repeat(101)), undefined, 'metadata alone cannot exceed the byte budget')
})

test('the default 32 MiB byte budget applies before the 512-entry limit to maximum-sized thumbnails', async () => {
  const cache = createThumbnailMemory(), value = preview({ dataUrl: jpeg(128 * 1024) })
  const capacity = Math.floor(32 * 1024 * 1024 / ((value.dataUrl.length + 8) * 2))
  for (let i = 0; i <= capacity; i++) await load(cache, 'key-' + String(i).padStart(4, '0'), value)
  assert.ok(capacity < 512); assert.equal(cache.readThumbnail('key-0000'), undefined)
  assert.ok(cache.readThumbnail('key-' + String(capacity).padStart(4, '0')))
})

test('oversized image entries and metadata keys return normally without displacing useful cached entries', async () => {
  const small = preview(), cache = createThumbnailMemory({ maxBytes: 200, maxKeyChars: 12 })
  await load(cache, 'small', small)
  assert.equal((await load(cache, 'large', preview({ dataUrl: jpeg(256) }))).status, 'AVAILABLE')
  assert.equal(cache.readThumbnail('large'), undefined); assert.ok(cache.readThumbnail('small'))
  assert.equal((await load(cache, 'metadata-over-limit', unavailable())).status, 'UNAVAILABLE')
  assert.equal(cache.readThumbnail('metadata-over-limit'), undefined); assert.ok(cache.readThumbnail('small'))
  const defaults = createThumbnailMemory(), longKey = 'k'.repeat(64 * 1024 + 1)
  await load(defaults, longKey, unavailable()); assert.equal(defaults.readThumbnail(longKey), undefined)
})

test('expired entries are reclaimed before useful LRU entries and each factory has independent document state', async () => {
  let time = 0
  const first = createThumbnailMemory({ maxEntries: 2, now: () => time }), second = createThumbnailMemory()
  await load(first, 'short', unavailable()); await load(first, 'long')
  first.readThumbnail('short'); time = 30000; await load(first, 'new')
  assert.equal(first.readThumbnail('short'), undefined); assert.ok(first.readThumbnail('long')); assert.ok(first.readThumbnail('new'))
  assert.equal(second.readThumbnail('long'), undefined)
  await load(second, 'long', preview({ width: 24 })); first.invalidateThumbnails()
  assert.equal(second.readThumbnail('long').width, 24)
})

test('production singleton exports share one cache and invalidation subscription', async () => {
  invalidateThumbnails(); let invalidated = 0
  const unsubscribe = subscribeThumbnailInvalidation(() => invalidated++)
  await acquireThumbnail('account:singleton', async () => preview()).promise
  assert.ok(readThumbnail('account:singleton')); forgetThumbnail('account:singleton')
  assert.equal(readThumbnail('account:singleton').status, 'UNAVAILABLE')
  invalidateThumbnails(); assert.equal(readThumbnail('account:singleton'), undefined); assert.equal(invalidated, 1)
  unsubscribe(); invalidateThumbnails(); assert.equal(invalidated, 1)
})

test('invalid configuration and empty acquisition identities fail locally without starting a fetch', () => {
  for (const options of [{ maxBytes: 0 }, { maxEntries: -1 }, { concurrency: 1.5 }, { thumbnailTtlMs: Infinity }, { transientTtlMs: NaN }, { cooldownMs: 0 }, { maxKeyChars: 0 }]) {
    assert.throws(() => createThumbnailMemory(options), TypeError)
  }
  const cache = createThumbnailMemory(); let calls = 0
  assert.throws(() => cache.acquireThumbnail('', async () => { calls++; return preview() }), TypeError)
  assert.throws(() => cache.acquireThumbnail('a', null), TypeError); assert.equal(calls, 0)
})
