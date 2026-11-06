package io.github.telegramenhance.transfer;

import java.text.Normalizer;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** Conservative button advice only. This class never invokes a callback or treats silence as EOF. */
public final class BotPagination {
    private static final Pattern PAIR = Pattern.compile("(?:页码|頁碼|当前页|當前頁|page|group|batch|第)\\s*[:：]?\\s*(\\d+)\\s*(?:页|頁|组|組|批)?\\s*(?:/|of)\\s*(\\d+)(?!\\d)");
    private static final Pattern CURRENT = Pattern.compile("(?:第\\s*(\\d+)\\s*(?:页|頁|组|組|批)|(?:当前页|當前頁|当前组|當前組)\\s*[:：]?\\s*(\\d+))(?!\\d)");
    private static final Pattern TOTAL = Pattern.compile("(?:共(?:收到|获取到|獲取到|有)?\\s*(\\d+)\\s*(?:页|頁|组|組|批))");
    private static final Pattern BUTTON_PAIR = Pattern.compile("(?<!\\d)(\\d+)\\s*/\\s*(\\d+)(?!\\d)");
    private static final Pattern NUMBER = Pattern.compile("^(?:第\\s*)?(\\d+)\\s*(?:页|頁)?$");
    private static final Pattern MARKED_NUMBER = Pattern.compile("^[·•●\\[({（【「〈]\\s*(\\d+)\\s*[·•●\\])}）】」〉]$");
    private static final Pattern NON_PAGING = Pattern.compile("(?:文件[夹夾]?|資料夾)(?:详[情細]|詳情|列表)|\\bfolders?\\s+(?:details?|list)\\b|\\bfile\\s+details?\\b|\\d+\\s*[-–—~～至]\\s*\\d+\\s*/\\s*\\d+(?!\\d)");
    private static final Pattern FOLDER_END = Pattern.compile("(?:文件[夹夾]|資料夾|目录|目錄)\\s*(?:内容|內容|内文件|內文件|内全部文件|內全部文件|全部文件)?\\s*(?:已|已经|已經)?\\s*(?:全部)?\\s*(?:发送|發送|推送)\\s*(?:完毕|完畢|完成|结束|結束)");
    private static final Pattern BLOCKED = Pattern.compile("关闭|關閉|取消|退出|删除|刪除|收藏|分享|会员|會員|充值|付费|付費|支付|购买|購買|打赏|打賞|解锁|解鎖|广告|廣告|推广|推廣|点赞|點讚|关注|關注|投票|评分|評分|下载链接|下載連結|[❤♥♡👍❌✖✕]|(?<![a-z])(?:close|cancel|delete|remove|save|share|forward|bookmark|favourites?|favorites?|collect|subscribe|subscription|member|premium|pay|payment|buy|purchase|recharge|donate|ad|ads|advert|advertisement|advertising|promo|promotion|like|love|follow|vote|rating|start)(?![a-z])|download\\s+link");
    private static final Pattern PREVIOUS = Pattern.compile("上一[页頁组組批]|上[页頁组組]|前一[页頁组組批]|上\\s*\\d+\\s*[页頁]|返回|(?<![a-z])(?:previous|prev|back)(?![a-z])");
    private static final Pattern WINDOW = Pattern.compile("下\\s*(?:[2-9]|[1-9]\\d+)\\s*[页頁]|(?<![a-z])next\\s+(?:[2-9]|[1-9]\\d+)\\s+pages?(?![a-z])");
    private static final Pattern NEXT = Pattern.compile("下一[页頁组組批套辑輯集]|下[页頁组組批]|继续获取|繼續獲取|继续加载|繼續載入|(?<![a-z])next(?:\\s+(?:page|group|batch))?(?![a-z])");
    private static final Set<String> NEXT_ARROWS = Set.of("→", "➡", "➜", "➔", "▶", "▶▶", "»", "›", ">", ">>", "⏭", "⏩", "👉");
    private static final Set<String> PREVIOUS_ARROWS = Set.of("←", "⬅", "◀", "◀◀", "«", "‹", "<", "<<", "⏮", "⏪", "👈");
    private static final Pattern END = Pattern.compile("(?<![\\p{L}\\p{N}])(?:文件|资源|資源|媒体|媒體|视频|視頻)?全部(?:文件|资源|資源|媒体|媒體|视频|視頻)?(?:已)?(?:获取|獲取|下载|下載|发送|發送|推送|加载|載入|读取|讀取)(?:成功|完毕|完畢|完成)|(?:已经是|已經是|已是|已到|当前是|當前是|这是|這是)(?:最后一页|最後一頁|末页|末頁|末尾)|(?m)^\\s*[\\p{So}\\p{P}]*\\s*(?:最后一页|最後一頁|末页|末頁|末尾|last page)\\s*[\\p{So}\\p{P}]*$|(?<![\\p{L}\\p{N}])(?:没有|沒有|无|無)更多(?:文件|资源|資源|媒体|媒體|内容|內容|视频|視頻)?|(?<![\\p{L}\\p{N}])(?:没有|沒有)下一[页頁组組]|\\ball (?:files|media|items|resources) (?:received|retrieved|loaded|sent|downloaded)(?: successfully)?\\b|\\bend of (?:results|list)\\b|\\bno more (?:results|pages|files|media|items)\\b");

    private BotPagination() { }

    public record InputButton(String id, String text, boolean callback) { }
    public record ButtonClassification(String id, String kind, Integer page,
            @com.fasterxml.jackson.annotation.JsonIgnore BigInteger exactPage) {
        public ButtonClassification(String id,String kind,Integer page){this(id,kind,page,page==null?null:BigInteger.valueOf(page));}
    }
    public record Result(String status, Integer current, Integer total, String nextButtonId, List<ButtonClassification> buttons,
            @com.fasterxml.jackson.annotation.JsonIgnore BigInteger exactCurrent,
            @com.fasterxml.jackson.annotation.JsonIgnore BigInteger exactTotal) {
        public Result { buttons = List.copyOf(buttons); }
        public Result(String status,Integer current,Integer total,String nextButtonId,List<ButtonClassification> buttons){this(status,current,total,nextButtonId,buttons,current==null?null:BigInteger.valueOf(current),total==null?null:BigInteger.valueOf(total));}
    }

    public static Result analyze(String text, List<InputButton> buttons) {
        if (text == null) text = "";
        if (buttons == null) buttons = List.of();
        if (text.length() > 8192 || buttons.size() > 100) throw new IllegalArgumentException("机器人分页内容超出单次识别范围");
        var ids = new HashSet<String>();
        for (var button : buttons) if (button == null || button.id() == null || button.id().isBlank() || button.id().length() > 512
                || button.text() == null || button.text().length() > 512 || !ids.add(button.id())) throw new IllegalArgumentException("机器人分页按钮无效");

        String body = normalize(text);
        var counters = new Counters();
        var pair = PAIR.matcher(body);
        while (pair.find()) counters.pair(pair.group(1), pair.group(2));
        var current = CURRENT.matcher(body);
        while (current.find()) counters.current(current.group(1) == null ? current.group(2) : current.group(1));
        var total = TOTAL.matcher(body);
        while (total.find()) counters.total(total.group(1));

        var classified = new ArrayList<ButtonClassification>();
        for (var button : buttons) {
            String label = normalize(button.text());
            var marked = MARKED_NUMBER.matcher(label);
            if (marked.matches()) counters.current(marked.group(1));
            String kind;
            BigInteger page = page(label);
            if (!button.callback() || BLOCKED.matcher(label).find() || NON_PAGING.matcher(label).find()) kind = "UNSUPPORTED";
            else if (page != null) kind = "PAGE";
            else if (PREVIOUS.matcher(label).find() || PREVIOUS_ARROWS.contains(label)) kind = "PREVIOUS";
            else if (WINDOW.matcher(label).find()) kind = "WINDOW";
            else if (NEXT.matcher(label).find() || NEXT_ARROWS.contains(label)) kind = "NEXT";
            else kind = "OTHER";

            if ("NEXT".equals(kind)) {
                var target = BUTTON_PAIR.matcher(label);
                while (target.find()) {
                    BigInteger targetPage = positive(target.group(1)), targetTotal = positive(target.group(2));
                    if (targetPage == null || targetTotal == null || targetPage.compareTo(targetTotal)>0) counters.conflict = true;
                    else {
                        if (page != null && !page.equals(targetPage)) counters.conflict = true;
                        page = targetPage;
                        counters.total(target.group(2));
                        if (targetPage.compareTo(BigInteger.ONE)>0) counters.current(targetPage.subtract(BigInteger.ONE).toString());
                    }
                }
            }
            classified.add(new ButtonClassification(button.id(), kind, display(page),page));
        }

        BigInteger at = counters.current(), count = counters.total();
        boolean explicitEnd = explicitEnd(body);
        boolean hasForward = classified.stream().anyMatch(button -> "NEXT".equals(button.kind()) || "WINDOW".equals(button.kind())
                || "PAGE".equals(button.kind()) && at != null && button.exactPage().compareTo(at)>0);
        if (at != null && count != null && at.compareTo(count)>0) counters.conflict = true;
        if (count != null && classified.stream().anyMatch(button -> "PAGE".equals(button.kind()) && button.exactPage().compareTo(count)>0)) counters.conflict = true;
        if (explicitEnd && (hasForward || at != null && count != null && at.compareTo(count)<0)) counters.conflict = true;
        if (at != null && at.equals(count) && hasForward) counters.conflict = true;
        if (counters.conflict) return result("UNKNOWN", at, count, null, classified);
        if (explicitEnd || at != null && at.equals(count)) return result("END", at, count, null, classified);

        if (at != null) {
            var consecutive = classified.stream().filter(button -> "PAGE".equals(button.kind()) && button.exactPage().equals(at.add(BigInteger.ONE))).toList();
            if (consecutive.size() == 1) return result("MORE", at, count, consecutive.getFirst().id(), classified);
            if (consecutive.size() > 1) return result("UNKNOWN", at, count, null, classified);
        }
        var next = classified.stream().filter(button -> "NEXT".equals(button.kind())).toList();
        if (next.size() == 1) return result("MORE", at, count, next.getFirst().id(), classified);
        if (next.size() > 1) return result("UNKNOWN", at, count, null, classified);

        // A numbered keyboard window is not a media page. Advance it only after reaching its final known page.
        var windows = classified.stream().filter(button -> "WINDOW".equals(button.kind())).toList();
        boolean inaccessibleGap = at != null && classified.stream().anyMatch(button -> "PAGE".equals(button.kind()) && button.exactPage().compareTo(at.add(BigInteger.ONE))>0);
        if (windows.size() == 1 && at != null && !inaccessibleGap) return result("MORE", at, count, windows.getFirst().id(), classified);
        return result("UNKNOWN", at, count, null, classified);
    }

    private static Result result(String state, BigInteger current, BigInteger total, String next, List<ButtonClassification> buttons) {
        return new Result(state, display(current), display(total), next, buttons,current,total);
    }
    // The existing optional display counters keep their wire shape. Navigation never
    // depends on whether a bot's exact count fits that legacy display representation.
    private static Integer display(BigInteger value){return value!=null&&value.bitLength()<32?value.intValue():null;}
    private static boolean explicitEnd(String body) {
        return explicitEnd(body, END, false) || explicitEnd(body, FOLDER_END, true);
    }
    private static boolean explicitEnd(String body, Pattern pattern, boolean standalone) {
        var found = pattern.matcher(body);
        while (found.find()) {
            String linePrefix = body.substring(body.lastIndexOf('\n', found.start() - 1) + 1, found.start());
            if (!linePrefix.matches("[\\p{So}\\p{P}\\p{Z}\\p{Cf}\\s]*(?:(?:提示|通知|状态|狀態|result|status|notice)\\s*[:：]\\s*)?")) continue;
            if (linePrefix.matches("(?s).*[>\"'“‘「『`❌🚫].*")) continue;
            if (standalone) {
                int end = body.indexOf('\n', found.end());
                String suffix = body.substring(found.end(), end < 0 ? body.length() : end);
                if (!suffix.matches("[\\p{So}\\p{P}\\p{Z}\\p{Cf}\\s]*")) continue;
            }
            String prefix = body.substring(Math.max(0, found.start() - 24), found.start());
            if (!prefix.matches("(?s).*(?:not|never|isn't|isn’t|未|不是|不在|并非|並非|尚未)\\s*$")) return true;
        }
        return false;
    }
    private static String normalize(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT)
                .replace("\uFE0E", "").replace("\uFE0F", "").replace("\u200B", "").replace("\u200D", "").strip();
    }
    private static BigInteger page(String label) {
        var plain = NUMBER.matcher(label);
        if (plain.matches()) return positive(plain.group(1));
        var marked = MARKED_NUMBER.matcher(label);
        return marked.matches() ? positive(marked.group(1)) : null;
    }
    private static BigInteger positive(String text) {
        try { var value = new BigInteger(text); return value.signum()>0 ? value : null; }
        catch (NumberFormatException ignored) { return null; }
    }
    private static final class Counters {
        final Set<BigInteger> current = new LinkedHashSet<>(), total = new LinkedHashSet<>();
        boolean conflict;
        void current(String text) { var value = positive(text); if (value == null) conflict = true; else { current.add(value); if (current.size() > 1) conflict = true; } }
        void total(String text) { var value = positive(text); if (value == null) conflict = true; else { total.add(value); if (total.size() > 1) conflict = true; } }
        void pair(String at, String count) { current(at); total(count); }
        BigInteger current() { return current.size() == 1 ? current.iterator().next() : null; }
        BigInteger total() { return total.size() == 1 ? total.iterator().next() : null; }
    }
}
