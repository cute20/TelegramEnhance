package io.github.telegramenhance.telegram;

import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.NavigableMap;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Bounded, read-only album enumeration. A short/empty page never proves a history endpoint. */
final class AlbumReader {
    private static final int PAGE_SIZE = 30;
    private final ResourceReader.Gateway gateway;
    private final int pageLimit;
    private final long budgetMillis;

    record Result(List<TdApi.Message> members, boolean complete, String method,
                  String newerBoundaryId, String olderBoundaryId, List<String> warnings) {
        Result { members = List.copyOf(members); warnings = List.copyOf(warnings); }
    }

    AlbumReader(ResourceReader.Gateway gateway) { this(gateway, 8, 30_000); }

    /** Package-local bounds make failure/timeout tests deterministic without any native session. */
    AlbumReader(ResourceReader.Gateway gateway, int pageLimit, long budgetMillis) {
        if (gateway == null || pageLimit < 1 || pageLimit > 8 || budgetMillis < 1) {
            throw new IllegalArgumentException("相册读取参数无效");
        }
        this.gateway = gateway;
        this.pageLimit = pageLimit;
        this.budgetMillis = budgetMillis;
    }

    Result read(TdApi.Message anchor, TdApi.Chat chat, TdApi.MessageThreadInfo optionalThread) {
        if (anchor == null || anchor.id <= 0 || anchor.chatId == 0 || chat == null || chat.id != anchor.chatId) {
            throw new IllegalArgumentException("主帖相册身份无效");
        }
        if (anchor.mediaAlbumId == 0) {
            return new Result(List.of(anchor), true, "SINGLE_MESSAGE", null, null, List.of());
        }
        Scan scan = new Scan(anchor);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        boolean newerTurn = true;
        int pages = 0;
        while (pages < pageLimit && !(scan.newer.done && scan.older.done)) {
            Side side = newerTurn ? scan.newer : scan.older;
            newerTurn = !newerTurn;
            if (side.done || side.stalled) {
                side = side == scan.newer ? scan.older : scan.newer;
                if (side.done || side.stalled) break;
            }
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) { scan.warnings.add("相册读取达到本轮时间上限，已保留取得的成员，范围尚不完整。"); break; }
            // TDLight 1.8.68 OrderedMessages::get_history skips a loaded anchor at offset 0.
            // Negative offsets count the anchor: -2 is one strictly newer message plus anchor.
            // See tdlight commit ccf8956e63d29074009867859ecad251c75f36e1, OrderedMessage.cpp.
            int offset = side.newer ? (side.immediate ? -2 : -(PAGE_SIZE - 1)) : 0;
            int limit = side.newer && side.immediate ? 2 : PAGE_SIZE;
            try {
                pages++;
                var future = gateway.read(new TdApi.GetChatHistory(anchor.chatId, side.edge, offset, limit, false));
                TdApi.Messages result = future.get(Math.min(remaining, TimeUnit.SECONDS.toNanos(16)), TimeUnit.NANOSECONDS);
                accept(scan, side, result);
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                scan.warnings.add("相册读取已中断，已保留取得的成员，范围尚不完整。");
                break;
            } catch (TimeoutException failure) {
                scan.warnings.add("相册读取超时，已保留取得的成员，范围尚不完整。");
                break;
            } catch (ExecutionException | RuntimeException failure) {
                // The upstream exception may contain request or message contents; never attach it.
                scan.warnings.add("相册历史读取未完成，请检查连接和频道访问权限后重试。");
                break;
            }
        }
        checkOrigins(scan, optionalThread);
        if (scan.newer.done && scan.members.lastKey() >= scan.newer.boundary
                || scan.older.done && scan.members.firstKey() <= scan.older.boundary) {
            scan.warnings.add("已取得的相册成员超出邻接边界，历史结果存在矛盾，范围尚待核对。");
        }
        boolean complete = scan.newer.done && scan.older.done && scan.warnings.isEmpty();
        if (!complete) {
            if (pages >= pageLimit && !(scan.newer.done && scan.older.done)) {
                scan.warnings.add("相册读取达到本轮页数上限，范围尚不完整。");
            }
            if (!scan.newer.done) scan.warnings.add("尚未取得相册新侧的连续非相册邻接消息；空页或短页不能证明已到频道最新端点。");
            if (!scan.older.done) scan.warnings.add("尚未取得相册旧侧的连续非相册邻接消息；空页或短页不能证明已到频道最旧端点。");
        }
        return new Result(new ArrayList<>(scan.members.values()), complete,
            complete ? "ADJACENT_BOUNDARIES" : "PARTIAL_SCAN",
            boundary(scan.newer), boundary(scan.older), new ArrayList<>(scan.warnings));
    }

    private void accept(Scan scan, Side side, TdApi.Messages result) {
        if (result == null || result.messages == null) {
            scan.warnings.add("相册历史返回缺少消息清单，范围尚不完整。");
            side.stalled = true;
            return;
        }
        NavigableMap<Long, TdApi.Message> page = new TreeMap<>();
        boolean invalid = false;
        long previousId = Long.MAX_VALUE;
        for (TdApi.Message message : result.messages) {
            if (message == null || message.chatId != scan.anchor.chatId || message.id <= 0) { invalid = true; continue; }
            if (message.id > previousId || !side.newer && message.id > side.edge) invalid = true;
            previousId = message.id;
            TdApi.Message duplicate = page.putIfAbsent(message.id, message);
            if (duplicate != null && duplicate.mediaAlbumId != message.mediaAlbumId) invalid = true;
        }
        if (invalid) {
            scan.warnings.add("相册历史存在缺失、跨聊天、乱序或矛盾的消息身份，范围尚不完整。");
            side.stalled = true;
            return;
        }
        for (TdApi.Message message : page.values()) {
            if (message.mediaAlbumId == scan.anchor.mediaAlbumId) scan.members.put(message.id, message);
        }
        TdApi.Message cursor = page.get(side.edge);
        if (cursor != null && cursor.mediaAlbumId != scan.anchor.mediaAlbumId) {
            scan.warnings.add("读取期间相册消息身份发生变化，请重新读取主帖。");
            side.stalled = true;
            return;
        }
        if (side.newer && cursor == null) {
            // A truncated negative-offset batch may omit the anchor and its nearest neighbors.
            // Do not jump to a distant member or certify a boundary across that unknown interval.
            if (side.immediate) {
                side.stalled = true;
                scan.warnings.add("相册新侧邻接响应未包含当前锚点，不能排除删除或窗口漂移，未标记为完整。");
            } else side.immediate = true;
            return;
        }
        List<TdApi.Message> adjacent = new ArrayList<>((side.newer
            ? page.tailMap(side.edge, false) : page.headMap(side.edge, false).descendingMap()).values());
        if (side.newer && side.immediate && adjacent.size() > 1) {
            scan.warnings.add("相册新侧邻接请求返回了超出范围的消息，范围尚不完整。");
            side.stalled = true;
            return;
        }
        if (adjacent.isEmpty()) {
            if (++side.noProgress >= 2) {
                side.stalled = true;
                scan.warnings.add((side.newer ? "相册新侧" : "相册旧侧") + "分页连续两次未取得新的邻接消息，未标记为完整。");
            }
            return;
        }
        side.noProgress = 0;
        for (int index = 0; index < adjacent.size(); index++) {
            TdApi.Message message = adjacent.get(index);
            if (message.mediaAlbumId != scan.anchor.mediaAlbumId) {
                side.done = true;
                side.boundary = message.id;
                if (adjacent.subList(index + 1, adjacent.size()).stream().anyMatch(m -> m.mediaAlbumId == scan.anchor.mediaAlbumId)) {
                    scan.warnings.add("同一相册身份出现在非连续消息范围，尚不能证明成员完整。");
                }
                break;
            }
            scan.members.put(message.id, message);
            side.edge = message.id;
        }
    }

    private void checkOrigins(Scan scan, TdApi.MessageThreadInfo thread) {
        checkOrigins(scan.anchor.chatId, scan.members.keySet(), scan.warnings, thread);
    }

    /** Cross-check a later canonical-member thread without repeating the bounded history scan. */
    static Result validateThreadOrigins(Result album, long channelId, TdApi.MessageThreadInfo thread) {
        var warnings = new LinkedHashSet<>(album.warnings());
        Set<Long> memberIds = new HashSet<>();
        for (var member : album.members()) memberIds.add(member.id);
        checkOrigins(channelId, memberIds, warnings, thread);
        boolean complete = album.complete() && warnings.isEmpty();
        return new Result(album.members(), complete, complete ? album.method() : "PARTIAL_SCAN",
                album.newerBoundaryId(), album.olderBoundaryId(), new ArrayList<>(warnings));
    }

    private static void checkOrigins(long channelId, Set<Long> memberIds, Set<String> warnings,
                                     TdApi.MessageThreadInfo thread) {
        if (thread == null || thread.messages == null) return;
        Set<Long> origins = new HashSet<>();
        for (TdApi.Message root : thread.messages) {
            if (root == null || root.forwardInfo == null || !(root.forwardInfo.origin instanceof TdApi.MessageOriginChannel origin)) continue;
            if (origin.chatId != channelId || origin.messageId <= 0) {
                warnings.add("讨论根消息的原频道身份与主帖不一致，相册范围尚待核对。");
                continue;
            }
            origins.add(origin.messageId);
        }
        if (!memberIds.containsAll(origins)) {
            warnings.add("讨论根消息指向尚未取得的原频道相册成员，相册范围尚待核对。");
        }
        // Equal counts or origin sets only cross-check membership; they never establish boundaries.
    }

    private static String boundary(Side side) { return side.done ? Long.toString(side.boundary) : null; }

    private static final class Scan {
        final TdApi.Message anchor;
        final NavigableMap<Long, TdApi.Message> members = new TreeMap<>();
        final LinkedHashSet<String> warnings = new LinkedHashSet<>();
        final Side newer;
        final Side older;
        Scan(TdApi.Message anchor) {
            this.anchor = anchor;
            members.put(anchor.id, anchor);
            newer = new Side(true, anchor.id);
            older = new Side(false, anchor.id);
        }
    }

    private static final class Side {
        final boolean newer;
        long edge;
        long boundary;
        int noProgress;
        boolean immediate;
        boolean done;
        boolean stalled;
        Side(boolean newer, long edge) { this.newer = newer; this.edge = edge; }
    }
}
