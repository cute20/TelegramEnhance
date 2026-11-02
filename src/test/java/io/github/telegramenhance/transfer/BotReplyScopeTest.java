package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BotReplyScopeTest {
    private static BotPagination.InputButton callback(String text) { return new BotPagination.InputButton(text, text, true); }
    private static BotPagination.InputButton url(String text) { return new BotPagination.InputButton(text, text, false); }
    private static BotReplyScope.Message message(String id, String text, String... buttons) {
        return new BotReplyScope.Message(id, text, List.of(buttons).stream().map(BotReplyScopeTest::callback).toList(), false);
    }
    private static BotReplyScope.Message media(String id) { return new BotReplyScope.Message(id, "", List.of(), true); }
    private static BotReplyScope.Message first(String id) { return message(id, "当前模式：视频浏览 | 页码：1/16", "·1·", "2", "下10页 »"); }
    private static BotReplyScope.Message terminal(String id) { return message(id, "✅ 文件全部获取成功！\n共收到6组文件", "AD: 免费体验"); }
    private static void noScope(BotReplyScope.InitialResult result, String status, String controlId) {
        assertEquals(status, result.status()); assertEquals(controlId, result.controlMessageId());
        assertNull(result.lowerExclusiveId()); assertNull(result.upperInclusiveId()); assertFalse(result.requestWindow());
    }

    @Test void aNewFirstReplyExcludesThePreviousResourceAndUsesTheLatestRequestBoundary() {
        var result = BotReplyScope.initial(List.of(media("40"), terminal("50"), media("101"), media("102"), first("103")), "100");
        assertEquals("CURRENT_PAGE", result.status()); assertEquals("103", result.controlMessageId());
        assertEquals("100", result.lowerExclusiveId()); assertEquals("103", result.upperInclusiveId());
    }

    @Test void aPriorReliableFooterCanBoundTheFirstPageWithoutAnOutgoingRequest() {
        for (var previous : List.of(terminal("50"), message("50", "页码2/6", "下一页"))) {
            var result = BotReplyScope.initial(List.of(media("40"), previous, media("101"), first("103")), null);
            assertEquals("CURRENT_PAGE", result.status()); assertEquals("50", result.lowerExclusiveId()); assertEquals("103", result.upperInclusiveId());
        }
    }

    @Test void aMoreRecentFooterWinsOverAnOlderRequestAsTheLowerBoundary() {
        var result = BotReplyScope.initial(List.of(terminal("50"), media("101"), first("103")), "30");
        assertEquals("CURRENT_PAGE", result.status()); assertEquals("50", result.lowerExclusiveId()); assertEquals("103", result.upperInclusiveId());
    }

    @Test void theLatestSecondPageIsSelectedInsteadOfFallingBackToAnOldFirstPage() {
        var result = BotReplyScope.initial(List.of(first("50"), media("101"), message("103", "已获取第2组文件（共6组）", "➡查看下一组 (3/6)")), "30");
        assertEquals("CURRENT_PAGE", result.status()); assertEquals("103", result.controlMessageId());
        assertEquals("50", result.lowerExclusiveId()); assertEquals("103", result.upperInclusiveId()); assertFalse(result.requestWindow());
    }

    @Test void anUnknownCurrentPageUsesItsOwnBoundedReplyInsteadOfAnOlderFirstPage() {
        var result = BotReplyScope.initial(List.of(first("50"), media("101"), message("103", "本组文件已发送", "下一组")), "30");
        assertEquals("CURRENT_PAGE", result.status()); assertEquals("103", result.controlMessageId());
        assertEquals("50", result.lowerExclusiveId()); assertEquals("103", result.upperInclusiveId()); assertFalse(result.requestWindow());
    }

    @Test void aNewRequestWithoutItsReplyDoesNotReuseThePreviousResource() {
        for (var old : List.of(first("103"), message("103", "页码2/6", "下一页"))) {
            for (String outgoing : List.of("103", "150")) {
                var result = BotReplyScope.initial(List.of(media("101"), old), outgoing);
                noScope(result, "UNVERIFIED", "103"); assertTrue(result.reason().contains("本次请求"));
            }
        }
    }

    @Test void evenAnExplicitFirstPageNeedsARequestOrPreviousReliableFooter() {
        var result = BotReplyScope.initial(List.of(media("40"), media("101"), first("103")), null);
        noScope(result, "UNVERIFIED", "103"); assertTrue(result.reason().contains("起点"));
    }

    @Test void aFirstGroupedDeliveryUsesTheNextGroupCounterAsFirstPageEvidence() {
        var result = BotReplyScope.initial(List.of(media("101"), message("103", "✅已获取第1组文件（共6组）\n点击下方按钮获取下一组", "➡️查看下一组 (2/6)")), "100");
        assertEquals("CURRENT_PAGE", result.status()); assertEquals("100", result.lowerExclusiveId()); assertEquals("103", result.upperInclusiveId());
    }

    @Test void aPartialFolderBatchUsesItsRangeAndMatchingSentCounterWithoutInventingPageNumbers() {
        var result = BotReplyScope.initial(List.of(media("101"), message("103", "✅当前批次发送完成\n已发送：26/38条消息\n点击下方按钮查看下一组文件↓", "➡点击查看下一组", "📍1-26/38 跳转", "文件夹详情", "收藏", "little_le_mni")), "100");
        assertEquals("CURRENT_PAGE", result.status()); assertEquals("100", result.lowerExclusiveId()); assertEquals("103", result.upperInclusiveId());
    }

    @Test void aFinalFolderRangeStartingAfterOneIsReadableAsTheCurrentPage() {
        var result = BotReplyScope.initial(List.of(first("50"), media("101"), message("103", "✅文件夹内容发送完成\n共发送38条消息", "📍27-38/38 跳转", "文件夹详情", "收藏", "文件夹列表")), "100");
        assertEquals("CURRENT_PAGE", result.status()); assertEquals("103", result.controlMessageId());
        assertEquals("100", result.lowerExclusiveId()); assertEquals("103", result.upperInclusiveId()); assertFalse(result.requestWindow());
    }

    @Test void theFinalFolderBatchExcludesMediaBeforeItsPreviousFooterEvenWithinTheSameRequest() {
        var earlier = message("150", "✅当前批次发送完成\n已发送26/38条消息", "下一组", "📍1-26/38 跳转");
        var last = message("170", "✅文件夹内容发送完成\n共发送38条消息", "📍27-38/38 跳转", "文件夹详情", "收藏", "文件夹列表");
        for (String outgoing : new String[] { null, "100" }) {
            var result = BotReplyScope.initial(List.of(media("101"), earlier, media("151"), media("162"), last), outgoing);
            assertEquals("CURRENT_PAGE", result.status()); assertEquals("170", result.controlMessageId());
            assertEquals("150", result.lowerExclusiveId()); assertEquals("170", result.upperInclusiveId()); assertFalse(result.requestWindow());
        }
    }

    @Test void aSingleBatchFolderCanBeBothTheFirstAndLastPage() {
        var result = BotReplyScope.initial(List.of(media("101"), message("103", "✅文件夹内容发送完成\n共发送38条消息", "📍1-38/38 跳转", "文件夹详情")), "100");
        assertEquals("CURRENT_PAGE", result.status()); assertEquals("100", result.lowerExclusiveId()); assertEquals("103", result.upperInclusiveId());
    }

    @Test void fullWidthTraditionalFolderEvidenceIsNormalized() {
        var result = BotReplyScope.initial(List.of(message("103", "✅當前批次發送完成\n已發送：２６／３８條消息", "下一組", "📍１－２６／３８ 跳轉")), "100");
        assertEquals("CURRENT_PAGE", result.status()); assertEquals("103", result.upperInclusiveId());
    }

    @Test void inconsistentRangesAndCountersCannotExposeAnyHistory() {
        for (var footer : List.of(
                message("103", "当前批次发送完成\n已发送25/38条消息", "下一组", "1-26/38 跳转"),
                message("103", "当前批次发送完成\n已发送26/38条消息", "下一组", "1-26/38 跳转", "27-38/38 跳转"),
                message("103", "当前批次发送完成\n已发送26/38条消息", "下一组", "0-26/38 跳转"),
                message("103", "当前批次发送完成\n已发送26/38条消息", "下一组", "27-26/38 跳转"),
                message("103", "当前批次发送完成\n已发送26/38条消息", "下一组", "1-26/25 跳转"),
                message("103", "当前批次发送完成\n已发送26/38条消息\n已发送25/38条消息", "下一组", "1-26/38 跳转"),
                message("103", "文件夹内容发送完成\n共发送37条消息", "1-38/38 跳转"),
                message("103", "文件夹内容发送完成\n共发送38条消息", "1-38/38 跳转", "下一组"))) {
            noScope(BotReplyScope.initial(List.of(first("50"), footer), "100"), "UNVERIFIED", "103");
        }
    }

    @Test void quotedNegatedAndConditionalBatchNoticesDoNotEstablishTheFirstRange() {
        for (String notice : List.of("当前批次未发送完成", "如果当前批次发送完成，请关闭", "当前批次发送完成后点击按钮", "资源标题：当前批次发送完成", "“当前批次发送完成”", "> 当前批次发送完成", "❌当前批次发送完成", "🚫当前批次发送完成")) {
            noScope(BotReplyScope.initial(List.of(message("103", notice + "\n已发送26/38条消息", "下一组", "1-26/38 跳转")), "100"), "UNVERIFIED", "103");
        }
        noScope(BotReplyScope.initial(List.of(message("103", "当前批次发送完成\n“已发送26/38条消息”", "下一组", "1-26/38 跳转")), "100"), "UNVERIFIED", "103");
    }

    @Test void messageCountersAndRangeJumpAloneAreInsufficient() {
        noScope(BotReplyScope.initial(List.of(message("103", "已发送26/38条消息", "下一组", "1-26/38 跳转")), "100"), "UNVERIFIED", "103");
        noScope(BotReplyScope.initial(List.of(message("103", "当前批次发送完成\n已发送38/38条消息", "1-38/38 跳转")), "100"), "UNVERIFIED", "103");
    }

    @Test void laterAdvertisingAndUtilityMenusDoNotReplaceTheResourceFooterOrExtendItsScope() {
        var ad = new BotReplyScope.Message("105", "广告：更多资源", List.of(url("下一页"), callback("AD: Next page"), callback("文件夹列表"), callback("收藏")), false);
        var result = BotReplyScope.initial(List.of(media("101"), first("103"), ad, media("106")), "100");
        assertEquals("CURRENT_PAGE", result.status()); assertEquals("103", result.controlMessageId()); assertEquals("103", result.upperInclusiveId());
    }

    @Test void anAdvertisingNoticeCannotBecomeAPageFromItsBodyCounter() {
        var ad = new BotReplyScope.Message("105", "AD: Page 1/16", List.of(url("下一页")), false);
        var result = BotReplyScope.initial(List.of(first("103"), ad), "100");
        assertEquals("CURRENT_PAGE", result.status()); assertEquals("103", result.controlMessageId()); assertEquals("103", result.upperInclusiveId());
    }

    @Test void aMediaBearingHeaderCanIncludeAlbumSiblingsOnlyWhenTheCurrentRequestBoundsThem() {
        var header = new BotReplyScope.Message("101", "页码1/6", List.of(callback("下一页")), true);
        var result = BotReplyScope.initial(List.of(terminal("50"), header, media("102"), media("103")), "100");
        assertEquals("CURRENT_PAGE", result.status()); assertEquals("100", result.lowerExclusiveId()); assertEquals("103", result.upperInclusiveId());
        assertTrue(result.reason().contains("同页相册")); assertFalse(result.requestWindow());
    }

    @Test void laterOrUnknownMediaHeadersExposeOnlyAnExplicitlyWarnedBoundedRequestWindow() {
        for (String body : List.of("页码2/6", "当前回复")) {
            var header = new BotReplyScope.Message("101", body, List.of(callback("下一页")), true);
            var result = BotReplyScope.initial(List.of(terminal("50"), header, media("102"), media("103")), "100");
            assertEquals("CURRENT_PAGE", result.status()); assertEquals("100", result.lowerExclusiveId()); assertEquals("103", result.upperInclusiveId());
            assertTrue(result.requestWindow()); assertEquals("当前请求已返回的媒体，可能含本资源已返回的其他页/组，以勾选列表为准", result.reason());
        }
    }

    @Test void aLaterHeaderWithoutFollowingMessagesDoesNotClaimARequestWindowExtension() {
        var header = new BotReplyScope.Message("101", "页码2/6", List.of(callback("下一页")), true);
        var result = BotReplyScope.initial(List.of(header), "100");
        assertEquals("CURRENT_PAGE", result.status()); assertEquals("101", result.upperInclusiveId()); assertFalse(result.requestWindow());
    }

    @Test void aHeaderWithOnlyAnOldFooterBoundaryCannotClaimFollowingMedia() {
        for (String body : List.of("页码1/6", "页码2/6", "当前回复")) {
            var header = new BotReplyScope.Message("101", body, List.of(callback("下一页")), true);
            for (String outgoing : new String[] { null, "30" }) {
                var result = BotReplyScope.initial(List.of(terminal("50"), header, media("102"), media("103")), outgoing);
                assertEquals("CURRENT_PAGE", result.status()); assertEquals("50", result.lowerExclusiveId()); assertEquals("101", result.upperInclusiveId());
                assertFalse(result.requestWindow());
            }
        }
    }

    @Test void pageAndRangePositionsMustNotDisagree() {
        var footer = message("103", "当前批次发送完成\n已发送26/38条消息\n页码2/6", "下一页", "1-26/38 跳转");
        noScope(BotReplyScope.initial(List.of(footer), "100"), "UNVERIFIED", "103");
    }

    @Test void anExplicitEndWithoutPageNumbersStillBoundsTheCurrentObservedMedia() {
        var result = BotReplyScope.initial(List.of(media("101"), terminal("103")), "100");
        assertEquals("CURRENT_PAGE", result.status()); assertEquals("100", result.lowerExclusiveId()); assertEquals("103", result.upperInclusiveId());
        assertFalse(result.requestWindow());
    }

    @Test void conflictingPageMetadataIsUnverifiedRatherThanFallingBackToHistoricalPages() {
        for (var footer : List.of(message("103", "页码1/16 当前页2", "下一页"), message("103", "页码1/16 页码2/6"),
                message("103", "页码9/3"), message("103", "页码1/1", "下一页"), message("103", "文件全部获取成功\n页码1/6")))
            noScope(BotReplyScope.initial(List.of(first("50"), footer), "100"), "UNVERIFIED", "103");
    }

    @Test void unknownNavigationDoesNotPreventReadingConsistentCurrentPageMetadata() {
        for (var footer : List.of(message("103", "页码1/16"), message("103", "页码9/16"),
                message("103", "当前回复", "下一页", "Next page"), message("103", "页码2/6", "下一页", "Next page"))) {
            var result = BotReplyScope.initial(List.of(media("101"), footer), "100");
            assertEquals("CURRENT_PAGE", result.status()); assertEquals("100", result.lowerExclusiveId()); assertEquals("103", result.upperInclusiveId());
            assertFalse(result.requestWindow());
        }
    }

    @Test void aConsistentPreviousNumberedFooterDoesNotNeedANextButtonToEstablishABoundary() {
        var result = BotReplyScope.initial(List.of(message("50", "页码1/6"), media("101"), first("103")), null);
        assertEquals("CURRENT_PAGE", result.status()); assertEquals("50", result.lowerExclusiveId()); assertEquals("103", result.upperInclusiveId());
    }

    @Test void aContradictoryPreviousFooterDoesNotAuthorizeTheWholeHistory() {
        noScope(BotReplyScope.initial(List.of(message("50", "页码1/6 当前页2"), media("101"), first("103")), null), "UNVERIFIED", "103");
    }

    @Test void numericIdentityOrderingPreservesAllSignedSixtyFourBitDigits() {
        var result = BotReplyScope.initial(List.of(first("9007199254740999"), media("9007199254740994"), terminal("9007199254740992")), "9007199254740993");
        assertEquals("CURRENT_PAGE", result.status()); assertEquals("9007199254740993", result.lowerExclusiveId()); assertEquals("9007199254740999", result.upperInclusiveId());
        var unsorted = BotReplyScope.initial(List.of(message("10", "页码2/3", "下一页"), first("9")), "8");
        assertEquals("CURRENT_PAGE", unsorted.status()); assertEquals("10", unsorted.controlMessageId()); assertEquals("9", unsorted.lowerExclusiveId());
        assertEquals("10", unsorted.upperInclusiveId()); assertFalse(unsorted.requestWindow());
        assertEquals("CURRENT_PAGE", BotReplyScope.initial(List.of(first(Long.toString(Long.MAX_VALUE))), Long.toString(Long.MAX_VALUE - 1)).status());
    }

    @Test void emptyAndUnrecognizedRepliesNeverExposeTheWholeHistory() {
        noScope(BotReplyScope.initial(List.of(), null), "UNVERIFIED", null);
        noScope(BotReplyScope.initial(null, "100"), "UNVERIFIED", null);
        noScope(BotReplyScope.initial(List.of(media("101"), message("103", "资源正在准备", "文件夹详情", "收藏")), "100"), "UNVERIFIED", null);
    }

    @Test void messageInputsCopyTheirButtonList() {
        var buttons = new ArrayList<>(List.of(callback("下一页")));
        var footer = new BotReplyScope.Message("103", "页码1/6", buttons, false);
        buttons.clear(); assertEquals(1, footer.buttons().size()); assertThrows(UnsupportedOperationException.class, () -> footer.buttons().clear());
        assertEquals("CURRENT_PAGE", BotReplyScope.initial(List.of(footer), "100").status());
    }

    @Test void invalidAndUnboundedInputsAreRejectedInsteadOfWideningTheScope() {
        for (String id : List.of("0", "-1", "01", "9x", "9223372036854775808", "900719925474099312345", "")) {
            assertThrows(IllegalArgumentException.class, () -> BotReplyScope.initial(List.of(first(id)), "100"), id);
            assertThrows(IllegalArgumentException.class, () -> BotReplyScope.initial(List.of(first("103")), id), id);
        }
        assertThrows(IllegalArgumentException.class, () -> BotReplyScope.initial(List.of(first("103"), first("103")), "100"));
        assertThrows(IllegalArgumentException.class, () -> BotReplyScope.initial(List.of(message("103", "x".repeat(8193))), "100"));
        assertThrows(IllegalArgumentException.class, () -> BotReplyScope.initial(IntStream.rangeClosed(1, 201).mapToObj(i -> first(Integer.toString(i))).toList(), null));
    }
}
