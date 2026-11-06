package io.github.telegramenhance.transfer;

import java.math.BigInteger;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Selects a bounded current reply; history exhaustion is deliberately not evidence of its scope. */
public final class BotReplyScope {
    private static final int MAX_MESSAGES = 200;
    private static final BigInteger MAX_ID = BigInteger.valueOf(Long.MAX_VALUE);
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern RANGE = Pattern.compile("(?<!\\d)(\\d+)\\s*[-–—~～至]\\s*(\\d+)\\s*/\\s*(\\d+)(?!\\d)");
    private static final Pattern PAGE_PAIR = Pattern.compile("(?:页码|頁碼|当前页|當前頁|page|group|batch|第)\\s*[:：]?\\s*(\\d+)\\s*(?:页|頁|组|組|批)?\\s*(?:/|of)\\s*(\\d+)(?!\\d)");
    private static final Pattern CURRENT = Pattern.compile("(?:第\\s*(\\d+)\\s*(?:页|頁|组|組|批)|(?:当前页|當前頁|当前组|當前組)\\s*[:：]?\\s*(\\d+))(?!\\d)");
    private static final Pattern TOTAL = Pattern.compile("(?:共(?:收到|获取到|獲取到|有)?\\s*(\\d+)\\s*(?:页|頁|组|組|批))");
    private static final Pattern BUTTON_PAIR = Pattern.compile("(?<!\\d)(\\d+)\\s*/\\s*(\\d+)(?!\\d)");
    private static final Pattern MARKED_NUMBER = Pattern.compile("^[·•●\\[({（【「〈]\\s*(\\d+)\\s*[·•●\\])}）】」〉]$");
    private static final Pattern BATCH_DONE = Pattern.compile("(?:当前批次|當前批次|本批次)\\s*(?:已)?\\s*(?:发送|發送|推送)\\s*(?:完成|完毕|完畢|结束|結束)");
    private static final Pattern SENT = Pattern.compile("(?:已发送|已發送)\\s*[:：]?\\s*(\\d+)\\s*/\\s*(\\d+)\\s*[条條]\\s*(?:消息|信息|訊息)");
    private static final Pattern SENT_TOTAL = Pattern.compile("共\\s*(?:发送|發送|推送)\\s*[:：]?\\s*(\\d+)\\s*[条條]\\s*(?:消息|信息|訊息)");
    private static final Pattern NOTICE_PREFIX = Pattern.compile("[\\p{So}\\p{P}\\p{Z}\\p{Cf}\\s]*(?:(?:提示|通知|状态|狀態|result|status|notice)\\s*[:：]\\s*)?");
    private static final Pattern NOTICE_SUFFIX = Pattern.compile("[\\p{So}\\p{P}\\p{Z}\\p{Cf}\\s]*");
    private static final Pattern QUOTED_OR_NEGATIVE = Pattern.compile("[>\"'“‘「『`❌🚫]");
    private static final Pattern AD_NOTICE = Pattern.compile("^(?:[\\p{So}\\p{P}\\p{Z}\\p{Cf}\\s]*)(?:ad|ads|advertisement|广告|廣告|推广|推廣)\\s*[:：]");
    private static final Set<String> PAGING_KINDS = Set.of("NEXT", "WINDOW", "PAGE", "PREVIOUS");

    private BotReplyScope() { }

    public record Message(String id, String text, List<BotPagination.InputButton> buttons, boolean hasMedia) {
        public Message {
            text = text == null ? "" : text;
            if (buttons == null) buttons = List.of();
            for (var button : buttons) if (button == null) throw new IllegalArgumentException("机器人消息按钮无效");
            buttons = List.copyOf(buttons);
        }
    }

    public record InitialResult(String status, String controlMessageId, String lowerExclusiveId,
                                String upperInclusiveId, boolean requestWindow, String reason) { }

    /**
     * The caller may expose media only in (lowerExclusiveId, upperInclusiveId] after CURRENT_PAGE.
     * latestOutgoingId is the latest actual outgoing request in this bot chat, never a guessed cursor.
     * requestWindow must be displayed as an observed request range, not as proof of one exact page.
     */
    public static InitialResult initial(List<Message> messages, String latestOutgoingId) {
        if (messages == null) messages = List.of();
        if (messages.size() > MAX_MESSAGES) throw new IllegalArgumentException("机器人回复超出单次识别范围");
        BigInteger outgoing = latestOutgoingId == null ? null : id(latestOutgoingId);
        var identities = new HashSet<BigInteger>();
        var parsed = new ArrayList<Footer>();
        BigInteger newest = null;
        for (var message : messages) {
            if (message == null) throw new IllegalArgumentException("机器人消息无效");
            BigInteger identity = id(message.id());
            if (!identities.add(identity)) throw new IllegalArgumentException("机器人消息身份重复");
            if (newest == null || newest.compareTo(identity) < 0) newest = identity;
            var footer = inspect(message, identity);
            if (footer.candidate()) parsed.add(footer);
        }
        if (parsed.isEmpty()) return unverified(null, "尚未找到当前资源的可靠回复边界，请等待机器人回复后重新读取");
        parsed.sort(Comparator.comparing(Footer::id));
        Footer control = parsed.getLast();
        if (outgoing != null && outgoing.compareTo(control.id()) >= 0)
            return unverified(control, "本次请求尚未出现可靠回复，请等待机器人回复后重新读取");
        if (!control.reliableBoundary())
            return unverified(control, "当前回复的分页信息相互冲突或范围证据不足，请核对机器人当前回复后重新读取");

        BigInteger lower = outgoing;
        for (int index = parsed.size() - 2; index >= 0; index--) {
            Footer earlier = parsed.get(index);
            if (earlier.reliableBoundary()) {
                if (lower == null || lower.compareTo(earlier.id()) < 0) lower = earlier.id();
                break;
            }
        }
        if (lower == null)
            return unverified(control, "无法确定当前回复的起点，请在机器人中重新展开当前资源后读取；不会读取整个历史记录");
        BigInteger upper = control.id();
        // A media-bearing header can precede its media or be edited in place. The actual outgoing request
        // bounds the resource, but only an explicit first-page position proves these are same-page siblings.
        // Later/unknown pages therefore require an explicit observed-request-range warning from the caller.
        boolean boundedHeader = control.message().hasMedia() && outgoing != null && outgoing.equals(lower);
        boolean extended = boundedHeader && newest.compareTo(control.id()) > 0;
        if (extended) upper = newest;
        boolean requestWindow = extended && control.position() != Position.FIRST;
        return new InitialResult("CURRENT_PAGE", control.message().id(), lower.toString(), upper.toString(), requestWindow,
                requestWindow ? "当前请求已返回的媒体，可能含本资源已返回的其他页/组，以勾选列表为准"
                        : extended ? "已根据本次请求限定第一页及同页相册成员" : "已根据请求或前一回复边界限定当前已展开的媒体");
    }

    private static Footer inspect(Message message, BigInteger identity) {
        var page = BotPagination.analyze(message.text(), message.buttons());
        String body = normalize(message.text());
        boolean paging = page.buttons().stream().anyMatch(button -> PAGING_KINDS.contains(button.kind()));
        boolean conflict = paginationConflict(body, message.buttons(), page);
        var range = inspectRange(body, message.buttons(), page, conflict);
        boolean advertising = AD_NOTICE.matcher(body).find();
        boolean metadata = PAGE_PAIR.matcher(body).find() || CURRENT.matcher(body).find() || TOTAL.matcher(body).find();
        boolean candidate = !advertising && (paging || metadata || "END".equals(page.status()) || range.relevant());
        // A missing/ambiguous next button is not a conflict: reading selected media does not execute it.
        boolean reliable = !conflict && !range.conflict() && (!range.relevant() || range.proven());
        Position position = Position.UNKNOWN;
        if (range.relevant()) {
            if (range.proven() && !range.conflict()) {
                position = BigInteger.ONE.equals(range.start()) ? Position.FIRST : Position.LATER;
                if (page.exactCurrent() != null && BigInteger.ONE.equals(page.exactCurrent()) != (position == Position.FIRST)) {
                    position = Position.UNKNOWN; reliable = false;
                }
            }
        } else if (page.exactCurrent() != null) {
            if (page.exactCurrent().compareTo(BigInteger.ONE)>0) position = Position.LATER;
            else if (reliable) position = Position.FIRST;
        }
        boolean boundary = candidate && reliable;
        return new Footer(message, identity, candidate, boundary, position);
    }

    private static RangeEvidence inspectRange(String body, List<BotPagination.InputButton> buttons, BotPagination.Result page, boolean pageConflict) {
        var ranges = new LinkedHashSet<RangeValue>();
        boolean invalidRange = false;
        boolean found = false;
        for (var button : buttons) {
            var matcher = RANGE.matcher(normalize(button.text()));
            while (matcher.find()) {
                found = true;
                BigInteger start = count(matcher.group(1)), end = count(matcher.group(2)), total = count(matcher.group(3));
                if (start == null || end == null || total == null || start.compareTo(end)>0 || end.compareTo(total)>0) invalidRange = true;
                else ranges.add(new RangeValue(start, end, total));
            }
        }
        boolean batch = hasNotice(body, BATCH_DONE);
        var sent = pairs(body, SENT);
        var totals = totals(body);
        boolean relevant = found;
        if (!relevant) return new RangeEvidence(false, false, false, BigInteger.ZERO);
        boolean conflict = invalidRange || ranges.size() != 1 || sent.invalid() || totals.invalid()
                || sent.values().size() > 1 || totals.values().size() > 1 || pageConflict;
        if (conflict) return new RangeEvidence(true, false, true, BigInteger.ZERO);
        RangeValue range = ranges.iterator().next();
        boolean partial = batch && sent.values().size() == 1
                && sent.values().contains(new CountPair(range.end(), range.total())) && range.end().compareTo(range.total())<0;
        boolean completed = "END".equals(page.status()) && range.end().equals(range.total()) && totals.values().size() == 1
                && totals.values().contains(range.total());
        if (!sent.values().isEmpty() && !sent.values().contains(new CountPair(range.end(), range.total()))) conflict = true;
        if (!totals.values().isEmpty() && !totals.values().contains(range.total())) conflict = true;
        return new RangeEvidence(true, !conflict && (partial || completed), conflict, range.start());
    }

    /** Distinguish unknown navigation from contradictory metadata without granting button execution. */
    private static boolean paginationConflict(String body, List<BotPagination.InputButton> buttons, BotPagination.Result page) {
        var counters = new Counters();
        var pair = PAGE_PAIR.matcher(body);
        while (pair.find()) { counters.current(pair.group(1)); counters.total(pair.group(2)); }
        var current = CURRENT.matcher(body);
        while (current.find()) counters.current(current.group(1) == null ? current.group(2) : current.group(1));
        var total = TOTAL.matcher(body);
        while (total.find()) counters.total(total.group(1));
        for (int index = 0; index < buttons.size(); index++) {
            String label = normalize(buttons.get(index).text());
            var marked = MARKED_NUMBER.matcher(label);
            if (marked.matches()) counters.current(marked.group(1));
            if ("NEXT".equals(page.buttons().get(index).kind())) {
                var target = BUTTON_PAIR.matcher(label);
                while (target.find()) {
                    BigInteger at = count(target.group(1)), size = count(target.group(2));
                    if (at == null || size == null || at.compareTo(size)>0) counters.conflict = true;
                    else { counters.total(target.group(2)); if (at.compareTo(BigInteger.ONE)>0) counters.current(at.subtract(BigInteger.ONE).toString()); }
                }
            }
        }
        BigInteger at = counters.current.size() == 1 ? counters.current.iterator().next() : null;
        BigInteger size = counters.total.size() == 1 ? counters.total.iterator().next() : null;
        boolean forward = page.buttons().stream().anyMatch(button -> "NEXT".equals(button.kind()) || "WINDOW".equals(button.kind())
                || "PAGE".equals(button.kind()) && at != null && button.exactPage().compareTo(at)>0);
        // Remove recognized counters only to ask the existing parser about affirmative terminal notices.
        String notices = TOTAL.matcher(CURRENT.matcher(PAGE_PAIR.matcher(body).replaceAll("")).replaceAll("")).replaceAll("");
        boolean explicitEnd = "END".equals(BotPagination.analyze(notices, List.of()).status());
        return counters.conflict || at != null && size != null && (at.compareTo(size)>0 || at.equals(size) && forward)
                || size != null && page.buttons().stream().anyMatch(button -> "PAGE".equals(button.kind()) && button.exactPage().compareTo(size)>0)
                || explicitEnd && (forward || at != null && size != null && at.compareTo(size)<0);
    }

    private static PairEvidence pairs(String body, Pattern pattern) {
        var values = new LinkedHashSet<CountPair>();
        boolean invalid = false;
        for (String line : body.split("\\R")) {
            var matcher = pattern.matcher(line);
            while (matcher.find()) if (affirmative(line, matcher.start(), matcher.end())) {
                BigInteger sent = count(matcher.group(1)), total = count(matcher.group(2));
                if (sent == null || total == null || sent.compareTo(total)>0) invalid = true;
                else values.add(new CountPair(sent, total));
            }
        }
        return new PairEvidence(values, invalid);
    }

    private static TotalEvidence totals(String body) {
        var values = new LinkedHashSet<BigInteger>();
        boolean invalid = false;
        for (String line : body.split("\\R")) {
            var matcher = SENT_TOTAL.matcher(line);
            while (matcher.find()) if (affirmative(line, matcher.start(), matcher.end())) {
                BigInteger total = count(matcher.group(1));
                if (total == null) invalid = true;
                else values.add(total);
            }
        }
        return new TotalEvidence(values, invalid);
    }

    private static boolean hasNotice(String body, Pattern pattern) {
        for (String line : body.split("\\R")) {
            var matcher = pattern.matcher(line);
            while (matcher.find()) if (affirmative(line, matcher.start(), matcher.end())) return true;
        }
        return false;
    }

    private static boolean affirmative(String line, int start, int end) {
        String prefix = line.substring(0, start);
        return NOTICE_PREFIX.matcher(prefix).matches() && !QUOTED_OR_NEGATIVE.matcher(prefix).find()
                && NOTICE_SUFFIX.matcher(line.substring(end)).matches();
    }

    private static InitialResult unverified(Footer control, String reason) {
        return new InitialResult("UNVERIFIED", control == null ? null : control.message().id(), null, null, false, reason);
    }

    private static BigInteger id(String value) {
        if (value == null || !ID.matcher(value).matches()) throw new IllegalArgumentException("机器人消息身份无效");
        var identity = new BigInteger(value);
        if (identity.compareTo(MAX_ID) > 0) throw new IllegalArgumentException("机器人消息身份无效");
        return identity;
    }

    private static BigInteger count(String value) {
        try { var count = new BigInteger(value); return count.signum()>0 ? count : null; }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace("\uFE0E", "").replace("\uFE0F", "").replace("\u200B", "").replace("\u200D", "").strip();
    }

    private enum Position { FIRST, LATER, UNKNOWN }
    private record Footer(Message message, BigInteger id, boolean candidate, boolean reliableBoundary, Position position) { }
    private record RangeValue(BigInteger start, BigInteger end, BigInteger total) { }
    private record CountPair(BigInteger sent, BigInteger total) { }
    private record PairEvidence(Set<CountPair> values, boolean invalid) { }
    private record TotalEvidence(Set<BigInteger> values, boolean invalid) { }
    private record RangeEvidence(boolean relevant, boolean proven, boolean conflict, BigInteger start) { }
    private static final class Counters {
        final Set<BigInteger> current = new LinkedHashSet<>(), total = new LinkedHashSet<>();
        boolean conflict;
        void current(String value) { add(current, value); }
        void total(String value) { add(total, value); }
        void add(Set<BigInteger> values, String text) {
            BigInteger value = count(text);
            if (value == null) conflict = true;
            else { values.add(value); if (values.size() > 1) conflict = true; }
        }
    }
}
