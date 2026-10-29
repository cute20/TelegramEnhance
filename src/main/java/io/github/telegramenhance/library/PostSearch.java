package io.github.telegramenhance.library;

import java.text.Normalizer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** The shared main-post-only matching contract for browsing and future rule selection. */
public final class PostSearch {
    private static final Pattern SEPARATORS = Pattern.compile("[\\s,，]+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern TAG = Pattern.compile("#[\\p{L}\\p{N}_][\\p{L}\\p{M}\\p{N}_]*");
    private static final Pattern TAG_TERM = Pattern.compile("[\\p{L}\\p{N}_][\\p{L}\\p{M}\\p{N}_]*");
    private static final int MAX_INPUT = 2048;
    private static final int MAX_TERMS = 20;
    private PostSearch() {}

    public record Query(List<String> channelIds, List<String> tags, List<String> keywords) {
        public Query {
            channelIds = List.copyOf(channelIds); tags = List.copyOf(tags); keywords = List.copyOf(keywords);
        }
    }

    public static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    public static List<String> extractTags(String mainText) {
        Set<String> tags = new LinkedHashSet<>();
        var matcher = TAG.matcher(normalize(mainText));
        while (matcher.find()) tags.add(matcher.group().substring(1));
        return List.copyOf(tags);
    }

    public static Query parse(List<String> channels, String tags, String keywords) {
        Set<String> channelIds = new LinkedHashSet<>();
        if (channels != null) {
            if (channels.size() > 100) throw invalid("一次最多筛选100个频道");
            for (String id : channels) {
                try {
                    long parsed = Long.parseLong(id == null ? "" : id.strip());
                    if (parsed == 0) throw new NumberFormatException();
                    channelIds.add(Long.toString(parsed));
                } catch (NumberFormatException failure) { throw invalid("频道筛选标识无效，请重新选择频道"); }
            }
        }
        return new Query(List.copyOf(channelIds), terms(tags, true), terms(keywords, false));
    }

    private static List<String> terms(String value, boolean tags) {
        if (value == null || value.isBlank()) return List.of();
        if (value.length() > MAX_INPUT) throw invalid("搜索内容过长，请缩短到2048个字符以内");
        Set<String> terms = new LinkedHashSet<>();
        for (String piece : SEPARATORS.split(normalize(value))) {
            String term = tags ? piece.replaceFirst("^#+", "") : piece;
            if (term.isBlank()) continue;
            if (tags && !TAG_TERM.matcher(term).matches()) throw invalid("标签只支持文字、数字和下划线；多个标签请用空格或逗号分隔");
            terms.add(term);
            if (terms.size() > MAX_TERMS) throw invalid("标签和关键词分别最多20项");
        }
        return List.copyOf(terms);
    }

    public static boolean matches(String mainText, Query query) {
        String text = normalize(mainText);
        List<String> tags = extractTags(mainText);
        return tags.containsAll(query.tags()) && query.keywords().stream().allMatch(text::contains);
    }

    private static IllegalArgumentException invalid(String message) { return new IllegalArgumentException(message); }
}
