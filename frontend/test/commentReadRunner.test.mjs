import assert from 'node:assert/strict'
import test from 'node:test'
import { runCommentRead } from '../src/commentReadRunner.ts'

const KEY = 'synthetic-post'
const immediate = async () => {}
const deferred = () => {
  let resolve, reject
  const promise = new Promise((yes, no) => { resolve = yes; reject = no })
  return { promise, resolve, reject }
}
const page = (nextCursor, options = {}) => ({
  postKey: KEY, media: [], messages: [], messageCount: 0,
  nextCursor, exhausted: false, coverage: 'PARTIAL', warnings: [], ...options,
})
const options = overrides => ({
  postKey: KEY, cursor: null, readPage: async () => page(null, { exhausted: true }),
  isCurrent: () => true, shouldStop: () => false, onPage: () => {},
  betweenPages: immediate, ...overrides,
})

test('serial pages retain precise BigInt cursors and PARTIAL EOF is still exhausted', async () => {
  const cursors = [], received = [], progress = []
  const pages = [
    page('9223372036854775806', { messageCount: 30, media: [{ key: 'a' }, { key: 'b' }] }),
    page('9223372036854775805', { messageCount: 2, media: [{ key: 'c' }], coverage: 'COMPLETE' }),
    page(null, { exhausted: true, messageCount: 1, warnings: ['尚有媒体未能核验'] }),
  ]
  const result = await runCommentRead(options({
    readPage: async cursor => { cursors.push(cursor); return pages[cursors.length - 1] },
    onPage: (value, valueProgress) => { received.push(value); progress.push(valueProgress) },
  }))
  assert.deepEqual(cursors, [null, '9223372036854775806', '9223372036854775805'])
  assert.deepEqual(received, pages)
  assert.deepEqual(progress, [
    { pages: 1, messages: 30, media: 2 }, { pages: 2, messages: 32, media: 3 }, { pages: 3, messages: 33, media: 3 },
  ])
  assert.deepEqual(result, { reason: 'exhausted', pages: 3, messages: 33, media: 3 })
})

test('short pages, no media, no messages, coverage and warnings never imply EOF', async () => {
  const pages = [
    page('100', { messageCount: 1 }),
    page('99', { coverage: 'UNAVAILABLE', warnings: ['保留范围告警'] }),
    page('98', { coverage: 'COMPLETE' }),
    page(null, { exhausted: true }),
  ]
  let reads = 0
  const result = await runCommentRead(options({ readPage: async () => pages[reads++] }))
  assert.equal(reads, 4)
  assert.deepEqual(result, { reason: 'exhausted', pages: 4, messages: 1, media: 0 })
})

for (const cursor of [null, '', '0', '-1', '+2', '1.2', '1e3', ' 3', '3 ', '01', 'Infinity']) {
  test(`invalid non-EOF nextCursor ${JSON.stringify(cursor)} applies the receipt once and pauses`, async () => {
    let reads = 0, applied = 0, yielded = 0
    const result = await runCommentRead(options({
      readPage: async () => { reads++; return page(cursor, { warnings: ['游标告警'] }) },
      onPage: value => { applied++; assert.deepEqual(value.warnings, ['游标告警']) },
      betweenPages: async () => { yielded++ },
    }))
    assert.deepEqual(result, { reason: 'no-progress', pages: 1, messages: 0, media: 0 })
    assert.equal(reads, 1); assert.equal(applied, 1); assert.equal(yielded, 0)
  })
}

for (const cursor of ['100', '101', '9007199254740993']) {
  test(`unchanged or newer cursor ${cursor} cannot continue an older-history scan`, async () => {
    let reads = 0, applied = 0
    const result = await runCommentRead(options({
      cursor: '100', readPage: async () => { reads++; return page(cursor) }, onPage: () => { applied++ },
    }))
    assert.equal(result.reason, 'no-progress'); assert.equal(reads, 1); assert.equal(applied, 1)
  })
}

test('a repeated continuation after the first page stops without issuing a third request', async () => {
  const cursors = []
  const result = await runCommentRead(options({ readPage: async cursor => { cursors.push(cursor); return page('100') } }))
  assert.equal(result.reason, 'no-progress'); assert.equal(result.pages, 2)
  assert.deepEqual(cursors, [null, '100'])
})

test('invalid saved cursor pauses before submitting any request', async () => {
  let reads = 0
  const result = await runCommentRead(options({ cursor: 'invalid', readPage: async () => { reads++; return page(null) } }))
  assert.deepEqual(result, { reason: 'no-progress', pages: 0, messages: 0, media: 0 }); assert.equal(reads, 0)
})

test('a stopped run does not start a request', async () => {
  let reads = 0
  const result = await runCommentRead(options({ shouldStop: () => true, readPage: async () => { reads++; return page(null) } }))
  assert.deepEqual(result, { reason: 'stopped', pages: 0, messages: 0, media: 0 }); assert.equal(reads, 0)
})

test('stop during a request still applies that saved page before pausing', async () => {
  const response = deferred(), started = deferred()
  let stop = false, reads = 0, applied = 0
  const running = runCommentRead(options({
    readPage: async () => { reads++; started.resolve(); return response.promise },
    shouldStop: () => stop, onPage: () => { applied++ },
  }))
  await started.promise; stop = true; response.resolve(page('100', { messageCount: 2 }))
  assert.deepEqual(await running, { reason: 'stopped', pages: 1, messages: 2, media: 0 })
  assert.equal(reads, 1); assert.equal(applied, 1)
})

test('EOF wins over stop, invalid cursor and a deadline crossed in flight', async () => {
  let stop = false, time = 0, applied = 0
  const result = await runCommentRead(options({
    now: () => time, maxDurationMs: 10, shouldStop: () => stop,
    readPage: async () => { stop = true; time = 11; return page(null, { exhausted: true }) },
    onPage: () => { applied++ },
  }))
  assert.equal(result.reason, 'exhausted'); assert.equal(applied, 1)
})

test('a stale run does not start a request', async () => {
  let reads = 0
  const result = await runCommentRead(options({ isCurrent: () => false, readPage: async () => { reads++; return page(null) } }))
  assert.deepEqual(result, { reason: 'stale', pages: 0, messages: 0, media: 0 }); assert.equal(reads, 0)
})

test('a late response after changing post is discarded without onPage or counting it', async () => {
  const response = deferred(), started = deferred()
  let current = true, applied = 0
  const running = runCommentRead(options({
    readPage: async () => { started.resolve(); return response.promise },
    isCurrent: () => current, onPage: () => { applied++ },
  }))
  await started.promise; current = false
  response.resolve(page(null, { postKey: 'other-private-id', exhausted: true, messageCount: 10 }))
  assert.deepEqual(await running, { reason: 'stale', pages: 0, messages: 0, media: 0 }); assert.equal(applied, 0)
})

test('an async page handler changing context cannot start another request', async () => {
  const entered = deferred(), release = deferred()
  let current = true, reads = 0
  const running = runCommentRead(options({
    readPage: async () => { reads++; return page('100') }, isCurrent: () => current,
    onPage: async () => { entered.resolve(); await release.promise },
  }))
  await entered.promise; current = false; release.resolve()
  assert.equal((await running).reason, 'stale'); assert.equal(reads, 1)
})

test('wrong-post response throws a generic error without either identity', async () => {
  let applied = 0, reads = 0
  const expected = 'private-expected-id', received = 'private-received-id'
  await assert.rejects(runCommentRead(options({
    postKey: expected, readPage: async () => { reads++; return page('100', { postKey: received }) },
    onPage: () => { applied++ },
  })), error => error instanceof Error && !error.message.includes(expected) && !error.message.includes(received))
  assert.equal(reads, 1); assert.equal(applied, 0)
})

test('read failures propagate unchanged with no automatic retry even if stop was requested', async () => {
  let reads = 0, applied = 0, stop = false
  const failure = new Error('synthetic uncertain response')
  await assert.rejects(runCommentRead(options({
    readPage: async () => { reads++; stop = true; throw failure }, shouldStop: () => stop,
    onPage: () => { applied++ },
  })), error => error === failure)
  assert.equal(reads, 1); assert.equal(applied, 0)
})

test('page-handler failures propagate unchanged and do not re-read the saved page', async () => {
  let reads = 0
  const failure = new Error('synthetic local refresh failure')
  await assert.rejects(runCommentRead(options({
    readPage: async () => { reads++; return page('100') }, onPage: async () => { throw failure },
  })), error => error === failure)
  assert.equal(reads, 1)
})

test('between-page failures propagate with no automatic retry', async () => {
  let reads = 0
  const failure = new Error('synthetic yield failure')
  await assert.rejects(runCommentRead(options({
    readPage: async () => { reads++; return page('100') }, betweenPages: async () => { throw failure },
  })), error => error === failure)
  assert.equal(reads, 1)
})

test('page limit pauses after applying the last receipt and before yielding or another request', async () => {
  let reads = 0, applied = 0, yielded = 0
  const result = await runCommentRead(options({
    maxPages: 1, readPage: async () => { reads++; return page('100') }, onPage: () => { applied++ },
    betweenPages: async () => { yielded++ },
  }))
  assert.deepEqual(result, { reason: 'limit', pages: 1, messages: 0, media: 0 })
  assert.equal(reads, 1); assert.equal(applied, 1); assert.equal(yielded, 0)
})

test('zero page or time budget pauses before submitting a request', async () => {
  for (const budget of [{ maxPages: 0 }, { maxDurationMs: 0 }]) {
    let reads = 0
    const result = await runCommentRead(options({ ...budget, readPage: async () => { reads++; return page(null) } }))
    assert.deepEqual(result, { reason: 'limit', pages: 0, messages: 0, media: 0 }); assert.equal(reads, 0)
  }
})

test('a deadline crossed by a response still applies it before pausing', async () => {
  let time = 0, reads = 0, applied = 0
  const result = await runCommentRead(options({
    now: () => time, maxDurationMs: 10,
    readPage: async () => { reads++; time = 10; return page('100', { messageCount: 3 }) }, onPage: () => { applied++ },
  }))
  assert.deepEqual(result, { reason: 'limit', pages: 1, messages: 3, media: 0 }); assert.equal(reads, 1); assert.equal(applied, 1)
})

for (const reason of ['stopped', 'stale', 'limit']) {
  test(`checks ${reason} after awaiting betweenPages before submitting the next request`, async () => {
    const entered = deferred(), release = deferred()
    let stop = false, current = true, time = 0, reads = 0
    const running = runCommentRead(options({
      now: () => time, maxDurationMs: 10, isCurrent: () => current, shouldStop: () => stop,
      readPage: async () => { reads++; return page('100') },
      betweenPages: async () => { entered.resolve(); await release.promise },
    }))
    await entered.promise
    if (reason === 'stopped') stop = true
    if (reason === 'stale') current = false
    if (reason === 'limit') time = 10
    release.resolve()
    assert.equal((await running).reason, reason); assert.equal(reads, 1)
  })
}

test('only one page is in flight and onPage finishes before the next page starts', async () => {
  const firstResponse = deferred(), started = deferred(), handlerEntered = deferred(), handlerRelease = deferred()
  let reads = 0, active = 0, peak = 0, firstApplied = false
  const running = runCommentRead(options({
    readPage: async () => {
      reads++; active++; peak = Math.max(peak, active)
      if (reads === 1) { started.resolve(); await firstResponse.promise }
      else assert.equal(firstApplied, true)
      active--
      return reads === 1 ? page('100') : page(null, { exhausted: true })
    },
    onPage: async (_value, progress) => {
      if (progress.pages === 1) { handlerEntered.resolve(); await handlerRelease.promise; firstApplied = true }
    },
  }))
  await started.promise; assert.equal(reads, 1); assert.equal(active, 1)
  firstResponse.resolve(); await handlerEntered.promise
  assert.equal(reads, 1); handlerRelease.resolve()
  assert.equal((await running).reason, 'exhausted'); assert.equal(reads, 2); assert.equal(peak, 1)
})

test('default bounds pause at 200 pages or twenty minutes without declaring EOF', async () => {
  let reads = 0
  const pageLimited = await runCommentRead(options({
    now: () => 0, readPage: async () => { reads++; return page(String(1000 - reads)) },
  }))
  assert.equal(pageLimited.reason, 'limit'); assert.equal(pageLimited.pages, 200); assert.equal(reads, 200)
  let time = 0
  const timeLimited = await runCommentRead(options({
    now: () => time, readPage: async () => { time = 20 * 60 * 1000; return page('100') },
  }))
  assert.equal(timeLimited.reason, 'limit'); assert.equal(timeLimited.pages, 1)
})

test('the default between-page yield lets a stop click run even for immediate responses', async () => {
  let stop = false, reads = 0
  const input = options({
    shouldStop: () => stop, readPage: async () => { reads++; return page('100') },
    onPage: () => { setTimeout(() => { stop = true }, 0) },
  })
  delete input.betweenPages
  const result = await runCommentRead(input)
  assert.equal(result.reason, 'stopped'); assert.equal(reads, 1)
})
