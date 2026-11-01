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

/** Selects a bounded first reply only; history exhaustion is deliberately not evidence of its scope. */
public final class BotReplyScope {
    private static final int MAX_MESSAGES = 200;
    private static final BigInteger MAX_ID = BigInteger.valueOf(Long.MAX_VALUE);
    private static final Pattern ID = Pattern.compile("[1-9][0-9]{0,18}");
    private static final Pattern RANGE = Pattern.compile("(?<!\\d)(\\d{1,7})\\s*[-–—~～至]\\s*(\\d{1,7})\\s*/\\s*(\\d{1,7})(?!\\d)");
    private static final Pattern BATCH_DONE = Pattern.compile("(?:当前批次|當前批次|本批次)\\s*(?:已)?\\s*(?:发送|發送|推送)\\s*(?:完成|完毕|完畢|结束|結束)");
    private static final Pattern SENT = Pattern.compile("(?:已发送|已發送)\\s*[:：]?\\s*(\\d{1,7})\\s*/\\s*(\\d{1,7})\\s*[条條]\\s*(?:消息|信息|訊息)");
    private static final Pattern SENT_TOTAL = Pattern.compile("共\\s*(?:发送|發送|推送)\\s*[:：]?\\s*(\\d{1,7})\\s*[条條]\\s*(?:消息|信息|訊息)");
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
                                String upperInclusiveId, String reason) { }

    /**
     * The caller may expose media only in (lowerExclusiveId, upperInclusiveId] after FIRST_PAGE.
     * latestOutgoingId is the latest actual outgoing request in this bot chat, never a guessed cursor.
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
        if (parsed.isEmpty()) return unverified(null, "尚未找到本次资源第一页的可靠分页信息，请等待回复或在机器人中打开第一页");
        parsed.sort(Comparator.comparing(Footer::id));
        Footer control = parsed.getLast();
        if (outgoing != null && outgoing.compareTo(control.id()) >= 0)
            return unverified(control, "本次请求尚未出现可靠的第一页回复，请等待机器人回复后重新预览");
        if (control.position() == Position.LATER)
            return new InitialResult("NOT_FIRST_PAGE", control.message().id(), null, null, "当前资源不是第一页，请在机器人中打开第一页后重新预览");
        if (control.position() != Position.FIRST)
            return unverified(control, "当前分页信息不足或相互冲突，请在机器人中打开本次资源第一页后重新预览");

        BigInteger lower = outgoing;
        for (int index = parsed.size() - 2; index >= 0; index--) {
            Footer earlier = parsed.get(index);
            if (earlier.reliableBoundary()) {
                if (lower == null || lower.compareTo(earlier.id()) < 0) lower = earlier.id();
                break;
            }
        }
        if (lower == null)
            return unverified(control, "无法确定本次资源的起点，请重新请求资源第一页后预览；不会读取整个历史记录");
        BigInteger upper = control.id();
        // A media-bearing header can precede its album siblings. Extend only for an actual same-request
        // boundary; a previous footer alone cannot prove that media after a header belongs to this page.
        boolean boundedHeader = control.message().hasMedia() && outgoing != null && outgoing.equals(lower);
        if (boundedHeader) upper = newest;
        return new InitialResult("FIRST_PAGE", control.message().id(), lower.toString(), upper.toString(),
                boundedHeader ? "已根据本次请求限定第一页及同页相册成员" : "已根据请求或前一分页边界限定本次资源第一页");
    }

    private static Footer inspect(Message message, BigInteger identity) {
        var page = BotPagination.analyze(message.text(), message.buttons());
        String body = normalize(message.text());
        boolean paging = page.buttons().stream().anyMatch(button -> PAGING_KINDS.contains(button.kind()));
        var range = inspectRange(body, message.buttons(), page);
        boolean advertising = AD_NOTICE.matcher(body).find();
        boolean candidate = !advertising && (paging || page.current() != null || "END".equals(page.status()) || range.relevant());
        boolean reliable = !"UNKNOWN".equals(page.status()) && !range.conflict();
        Position position = Position.UNKNOWN;
        if (range.relevant()) {
            if (range.proven() && !range.conflict()) {
                position = range.start() == 1 ? Position.FIRST : Position.LATER;
                if (page.current() != null && (page.current() == 1) != (position == Position.FIRST)) position = Position.UNKNOWN;
            }
        } else if (page.current() != null) {
            if (page.current() > 1) position = Position.LATER;
            else if (reliable) position = Position.FIRST;
        }
        boolean boundary = candidate && reliable && (!range.relevant() || range.proven() && position != Position.UNKNOWN);
        return new Footer(message, identity, candidate, boundary, position);
    }

    private static RangeEvidence inspectRange(String body, List<BotPagination.InputButton> buttons, BotPagination.Result page) {
        var ranges = new LinkedHashSet<RangeValue>();
        boolean invalidRange = false;
        boolean found = false;
        for (var button : buttons) {
            var matcher = RANGE.matcher(normalize(button.text()));
            while (matcher.find()) {
                found = true;
                Integer start = count(matcher.group(1)), end = count(matcher.group(2)), total = count(matcher.group(3));
                if (start == null || end == null || total == null || start > end || end > total) invalidRange = true;
                else ranges.add(new RangeValue(start, end, total));
            }
        }
        boolean batch = hasNotice(body, BATCH_DONE);
        var sent = pairs(body, SENT);
        var totals = totals(body);
        boolean relevant = found && (batch || !sent.values().isEmpty() || sent.invalid() || "END".equals(page.status())
                || body.contains("批次") || body.contains("已发送") || body.contains("已發送"));
        if (!relevant) return new RangeEvidence(false, false, false, 0);
        boolean conflict = invalidRange || ranges.size() != 1 || sent.invalid() || totals.invalid()
                || sent.values().size() > 1 || totals.values().size() > 1 || "UNKNOWN".equals(page.status());
        if (conflict) return new RangeEvidence(true, false, true, 0);
        RangeValue range = ranges.iterator().next();
        boolean partial = batch && "MORE".equals(page.status()) && sent.values().size() == 1
                && sent.values().contains(new CountPair(range.end(), range.total())) && range.end() < range.total();
        boolean completed = "END".equals(page.status()) && range.end() == range.total() && totals.values().size() == 1
                && totals.values().contains(range.total());
        if (!sent.values().isEmpty() && !sent.values().contains(new CountPair(range.end(), range.total()))) conflict = true;
        if (!totals.values().isEmpty() && !totals.values().contains(range.total())) conflict = true;
        return new RangeEvidence(true, !conflict && (partial || completed), conflict, range.start());
    }

    private static PairEvidence pairs(String body, Pattern pattern) {
        var values = new LinkedHashSet<CountPair>();
        boolean invalid = false;
        for (String line : body.split("\\R")) {
            var matcher = pattern.matcher(line);
            while (matcher.find()) if (affirmative(line, matcher.start(), matcher.end())) {
                Integer sent = count(matcher.group(1)), total = count(matcher.group(2));
                if (sent == null || total == null || sent > total) invalid = true;
                else values.add(new CountPair(sent, total));
            }
        }
        return new PairEvidence(values, invalid);
    }

    private static TotalEvidence totals(String body) {
        var values = new LinkedHashSet<Integer>();
        boolean invalid = false;
        for (String line : body.split("\\R")) {
            var matcher = SENT_TOTAL.matcher(line);
            while (matcher.find()) if (affirmative(line, matcher.start(), matcher.end())) {
                Integer total = count(matcher.group(1));
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
        return new InitialResult("UNVERIFIED", control == null ? null : control.message().id(), null, null, reason);
    }

    private static BigInteger id(String value) {
        if (value == null || !ID.matcher(value).matches()) throw new IllegalArgumentException("机器人消息身份无效");
        var identity = new BigInteger(value);
        if (identity.compareTo(MAX_ID) > 0) throw new IllegalArgumentException("机器人消息身份无效");
        return identity;
    }

    private static Integer count(String value) {
        try { int count = Integer.parseInt(value); return count > 0 && count <= 1_000_000 ? count : null; }
        catch (NumberFormatException ignored) { return null; }
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace("\uFE0E", "").replace("\uFE0F", "").replace("\u200B", "").replace("\u200D", "").strip();
    }

    private enum Position { FIRST, LATER, UNKNOWN }
    private record Footer(Message message, BigInteger id, boolean candidate, boolean reliableBoundary, Position position) { }
    private record RangeValue(int start, int end, int total) { }
    private record CountPair(int sent, int total) { }
    private record PairEvidence(Set<CountPair> values, boolean invalid) { }
    private record TotalEvidence(Set<Integer> values, boolean invalid) { }
    private record RangeEvidence(boolean relevant, boolean proven, boolean conflict, int start) { }
}
