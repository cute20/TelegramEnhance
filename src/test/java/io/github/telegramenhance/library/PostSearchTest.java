package io.github.telegramenhance.library;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class PostSearchTest {
    @Test void fullWidthCaseAndCanonicalUnicodeFormsShareOneTagIdentity() {
        String text = "＃Ｂｅｉｊｉｎｇ #北京 #Café #cafe\u0301";
        assertEquals(List.of("beijing", "北京", "café"), PostSearch.extractTags(text));
        assertTrue(PostSearch.matches(text, PostSearch.parse(null, "#BEIJING，#cafe\u0301", "北京")));
    }
    @Test void lowercasingAndCombiningMarksDoNotTruncateTags() {
        String text = "#İSTANBUL #हिन्दी";
        assertEquals(List.of("i\u0307stanbul", "हिन्दी"), PostSearch.extractTags(text));
        assertTrue(PostSearch.matches(text, PostSearch.parse(null, "İstanbul हिन्दी", "")));
    }
    @Test void everyTagIsRequiredAndPrefixTagsDoNotMatch() {
        var query = PostSearch.parse(null, "#北京 #旅行 #北京", "");
        assertEquals(List.of("北京", "旅行"), query.tags());
        assertFalse(PostSearch.matches("#北京旅行 #旅行", query));
        assertFalse(PostSearch.matches("#北京", query));
        assertTrue(PostSearch.matches("#旅行。#北京", query));
    }
    @Test void everyKeywordIsRequiredAcrossSpacesAndCommaSeparators() {
        var query = PostSearch.parse(null, "", "CAFÉ， 北京\u3000café");
        assertEquals(List.of("café", "北京"), query.keywords());
        assertTrue(PostSearch.matches("Ｃａｆé\n北京的行程", query));
        assertFalse(PostSearch.matches("café in Shanghai", query));
    }
    @Test void punctuationEndsTagsButDoesNotBecomePartOfThem() {
        assertEquals(List.of("北京", "旅行", "travel_time", "旅行2", "2"),
                PostSearch.extractTags("#北京。#旅行 / #travel_time #旅行2 #2"));
        assertThrows(IllegalArgumentException.class, () -> PostSearch.parse(null, "北京-旅行", ""));
    }
    @Test void emptyInputsAndStandaloneMarkersDoNotInventTerms() {
        var query = PostSearch.parse(null, "# ,，\u3000", " , ");
        assertTrue(query.tags().isEmpty()); assertTrue(query.keywords().isEmpty());
        assertTrue(PostSearch.matches(null, query));
        assertEquals("", PostSearch.normalize(null));
    }
    @Test void channelIdsRemainExactStringsBeyondJavascriptIntegerPrecision() {
        var query = PostSearch.parse(List.of("-1009007199254740999", "-1009007199254740999"), "", "");
        assertEquals(List.of("-1009007199254740999"), query.channelIds());
        for (String bad : List.of("0", "not-an-id", "1 OR 1=1", "9223372036854775808")) {
            assertThrows(IllegalArgumentException.class, () -> PostSearch.parse(List.of(bad), "", ""));
        }
    }
    @Test void oversizedInputsAndTooManyConditionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> PostSearch.parse(null, "a".repeat(2049), ""));
        String twenty = IntStream.range(0, 20).mapToObj(i -> "term" + i).collect(java.util.stream.Collectors.joining(" "));
        assertEquals(20, PostSearch.parse(null, "", twenty).keywords().size());
        assertThrows(IllegalArgumentException.class, () -> PostSearch.parse(null, "", twenty + " extra"));
        assertThrows(IllegalArgumentException.class, () -> PostSearch.parse(java.util.Collections.nCopies(101, "1"), "", ""));
    }
    @Test void keywordWildcardsRemainLiteralCharacters() {
        var query = PostSearch.parse(null, "", "100% _");
        assertTrue(PostSearch.matches("literal 100% and _", query));
        assertFalse(PostSearch.matches("1000 and anything", query));
    }
    @Test void keywordHashRemainsLiteralAndCannotTurnIntoAnEmptyFilter() {
        var query = PostSearch.parse(null, "", "#旅行");
        assertEquals(List.of("#旅行"), query.keywords());
        assertTrue(PostSearch.matches("主帖 #旅行", query));
        assertFalse(PostSearch.matches("旅行攻略", query));
        var marker = PostSearch.parse(null, "", "#");
        assertEquals(List.of("#"), marker.keywords());
        assertFalse(PostSearch.matches("没有标签的文字", marker));
    }
    @Test void queryCollectionsCannotBeMutatedAfterValidation() {
        var tags = new ArrayList<>(List.of("北京"));
        var query = new PostSearch.Query(List.of(), tags, List.of()); tags.clear();
        assertEquals(List.of("北京"), query.tags());
        assertThrows(UnsupportedOperationException.class, () -> query.tags().clear());
    }
}
