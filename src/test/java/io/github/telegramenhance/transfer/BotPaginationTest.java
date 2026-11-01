package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import io.github.telegramenhance.transfer.BotPagination.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BotPaginationTest {
    @Test void partialFolderBatchCompletionRecommendsNextWithoutInventingPageCounters() {
        var result=analyze("✅当前批次发送完成\n已发送：26/38条消息\n点击下方按钮查看下一组文件↓", "➡点击查看下一组", "📍1-26/38 跳转", "📁文件夹详情", "☆收藏", "🏷little_le_mni");
        assertEquals("MORE",result.status());assertEquals("➡点击查看下一组",result.nextButtonId());assertNull(result.current());assertNull(result.total());
        assertEquals("UNSUPPORTED",result.buttons().get(1).kind());assertEquals("UNSUPPORTED",result.buttons().get(2).kind());assertEquals("UNSUPPORTED",result.buttons().get(3).kind());
    }
    @Test void entireFolderCompletionWithJumpAndUtilityButtonsIsTerminal() {
        var result=analyze("✅文件夹内容发送完成\n共发送38条消息", "📍27-38/38 跳转", "📁文件夹详情", "☆收藏", "🗂文件夹列表");
        assertEquals("END",result.status());assertNull(result.nextButtonId());assertNull(result.current());assertNull(result.total());
        assertTrue(result.buttons().stream().allMatch(button->"UNSUPPORTED".equals(button.kind())));
    }
    @Test void batchAndMessageCountsAloneNeverProveTheEndOfTheFolder() {
        for(String body:List.of("✅当前批次发送完成\n已发送38/38条消息", "本批次发送完毕", "共发送38条消息")) {
            assertEquals("UNKNOWN",analyze(body,"📍27-38/38 跳转").status(),body);
            assertEquals("MORE",analyze(body,"➡点击查看下一组").status(),body);
        }
    }
    @Test void explicitFolderCompletionStillRejectsConflictingForwardButtonsAndCounters() {
        for(String button:List.of("下一组","→","下10页"))assertEquals("UNKNOWN",analyze("文件夹内容发送完成",button).status(),button);
        assertEquals("UNKNOWN",analyze("文件夹内容发送完成\n页码1/3").status());
    }
    @Test void folderCompletionMustBeAnAffirmativeStandaloneNotice() {
        for(String text:List.of("文件夹内容未发送完成","文件夹内容尚未发送完成","如果文件夹内容发送完成，请关闭","文件夹内容发送完成后点击按钮","资源标题：文件夹内容发送完成","文件夹当前批次发送完成","“文件夹内容发送完成”","> 文件夹内容发送完成","❌文件夹内容发送完成","🚫文件夹内容发送完成"))
            assertEquals("UNKNOWN",analyze(text).status(),text);
        for(String text:List.of("✅ 文件夾內容發送完成！\n共發送38條消息","提示：文件夹内容发送完毕。","文件夹全部发送完成","文件夹内全部文件发送完成"))
            assertEquals("END",analyze(text).status(),text);
    }
    private static InputButton callback(String text) { return new InputButton(text, text, true); }
    private static InputButton url(String text) { return new InputButton(text, text, false); }
    private static Result analyze(String text, String... labels) { return BotPagination.analyze(text, List.of(labels).stream().map(BotPaginationTest::callback).toList()); }
    private static List<InputButton> numbered(int start, int end, int current) {
        return new ArrayList<>(IntStream.rangeClosed(start, end).mapToObj(page -> callback(page == current ? "·" + page + "·" : Integer.toString(page))).toList());
    }

    @Test void numberedFirstPageRecommendsTheNextMediaPageBeforeTheNextKeyboardWindow() {
        var buttons = numbered(1, 10, 1); buttons.add(callback("下10页 »")); buttons.add(callback("❌关闭")); buttons.add(callback("⭐收藏")); buttons.add(callback("❤️20")); buttons.add(url("进入频道搜索资源"));
        var result = BotPagination.analyze("资源内容: 🎞158 ❤️20\n当前模式: 视频浏览 | 页码:1/16", buttons);
        assertEquals("MORE", result.status()); assertEquals(1, result.current()); assertEquals(16, result.total()); assertEquals("2", result.nextButtonId());
        assertEquals("WINDOW", result.buttons().get(10).kind()); assertEquals("UNSUPPORTED", result.buttons().get(11).kind()); assertEquals("UNSUPPORTED", result.buttons().get(13).kind());
        assertNull(result.buttons().get(13).page());
    }

    @Test void lastPageInTheFirstNumberWindowCanAdvanceTheKeyboardWithoutPretendingItIsPageEleven() {
        var buttons = numbered(1, 10, 10); buttons.add(callback("下10页 »"));
        var result = BotPagination.analyze("页码:10/16", buttons);
        assertEquals("MORE", result.status()); assertEquals("下10页 »", result.nextButtonId()); assertNull(result.buttons().getLast().page());
    }

    @Test void afterMovingTheNumberWindowTheNextConsecutivePageIsRecommended() {
        var buttons = numbered(11, 16, -1); buttons.add(callback("«上10页"));
        var result = BotPagination.analyze("页码:10/16", buttons);
        assertEquals("MORE", result.status()); assertEquals("11", result.nextButtonId());
    }

    @Test void finalNumberedPageWithOnlyBackAndAdvertisingIsAnExplicitEnd() {
        var buttons = numbered(11, 16, 16); buttons.add(callback("«上10页")); buttons.add(callback("❌关闭")); buttons.add(callback("⭐收藏")); buttons.add(callback("❤️20")); buttons.add(callback("📤分享")); buttons.add(url("AD: next page"));
        var result = BotPagination.analyze("当前模式: 视频浏览 | 页码:16/16", buttons);
        assertEquals("END", result.status()); assertEquals(16, result.current()); assertEquals(16, result.total()); assertNull(result.nextButtonId());
        assertEquals("PREVIOUS", result.buttons().get(6).kind());
    }

    @Test void groupedDeliveryCounterAndNextGroupLabelAgree() {
        var result = analyze("✅已获取第1组文件（共6组）\n👇点击下方按钮获取下一组", "➡️查看下一组 (2/6)", "👑Telegram会员订阅👑");
        assertEquals("MORE", result.status()); assertEquals(1, result.current()); assertEquals(6, result.total()); assertEquals("➡️查看下一组 (2/6)", result.nextButtonId());
        assertEquals("NEXT", result.buttons().getFirst().kind()); assertEquals(2, result.buttons().getFirst().page()); assertEquals("UNSUPPORTED", result.buttons().getLast().kind());
    }

    @Test void finalSuccessMessageWithAdvertisingOnlyDoesNotNeedAFakeNextButton() {
        var result = BotPagination.analyze("✅ 文件全部获取成功！\n📁共收到6组文件", List.of(url("万人在用的看片神器"), callback("AD: 免费体验")));
        assertEquals("END", result.status()); assertEquals(6, result.total()); assertNull(result.nextButtonId());
    }

    @Test void aNextButtonTargetCanSupplyTheGroupCounterWhenTheBotTextHasNone() {
        var result = analyze("本组文件已发送", "➡查看下一组（2/6）");
        assertEquals("MORE", result.status()); assertEquals(1, result.current()); assertEquals(6, result.total());
    }

    @Test void theFirstGroupButtonDoesNotInventPageZero() {
        var result = analyze("点击获取", "下一组 (1/6)");
        assertEquals("MORE", result.status()); assertNull(result.current()); assertEquals(6, result.total());
    }

    @Test void chineseTraditionalEnglishAndArrowLabelsAreSupportedWithoutBodyCounters() {
        for (String label : List.of("下一页", "下一頁", "下一组", "下一批", "继续获取", "繼續獲取", "继续加载", "NEXT", "Next page", "Next group", "Next batch", "→", "➡️", "▶️", "»", ">>", "⏭️")) {
            var result = analyze("内容已发送", label);
            assertEquals("MORE", result.status(), label); assertEquals(label, result.nextButtonId(), label);
        }
    }

    @Test void anEnglishPageCounterUsesNextAndIgnoresPrevious() {
        var result = analyze("Page 2 of 5", "Previous page", "NEXT PAGE");
        assertEquals("MORE", result.status()); assertEquals(2, result.current()); assertEquals(5, result.total()); assertEquals("NEXT PAGE", result.nextButtonId());
        assertEquals("PREVIOUS", result.buttons().getFirst().kind());
    }

    @Test void fullWidthNumbersAndWhitespaceNormalizeWithoutChangingOpaqueButtonIds() {
        var result = BotPagination.analyze("頁碼：１／１６", List.of(new InputButton("opaque-id", "２", true), callback("下１０頁 »")));
        assertEquals("MORE", result.status()); assertEquals(1, result.current()); assertEquals(16, result.total()); assertEquals("opaque-id", result.nextButtonId());
    }

    @Test void currentMarkersCanProvidePositionButCannotInventTheTotal() {
        var result = analyze("视频资源", "·3·", "4", "下10页");
        assertEquals("MORE", result.status()); assertEquals(3, result.current()); assertNull(result.total()); assertEquals("4", result.nextButtonId());
        var finalWindow = analyze("视频资源", "11", "·12·", "上一页");
        assertEquals("UNKNOWN", finalWindow.status()); assertNull(finalWindow.nextButtonId());
    }

    @Test void missingButtonsAndAdvertisingOnlyAreUnknownWithoutExplicitEndEvidence() {
        for (List<InputButton> buttons : List.of(List.<InputButton>of(), List.of(url("下一页")), List.of(callback("AD: 下一页")), List.of(callback("上一页")))) {
            var result = BotPagination.analyze("已发送本页文件", buttons);
            assertEquals("UNKNOWN", result.status()); assertNull(result.nextButtonId());
        }
    }

    @Test void bodyNextInstructionsDoNotActAsClickableButtonsOrProveEof() {
        var result = analyze("第1组文件（共6组），点击下一组继续获取");
        assertEquals("UNKNOWN", result.status()); assertEquals(1, result.current()); assertEquals(6, result.total()); assertNull(result.nextButtonId());
    }

    @Test void dangerousAndNonPagingCallbackActionsAreUnavailableEvenForManualSuggestions() {
        for (String label : List.of("关闭", "取消", "删除", "收藏", "分享", "会员", "支付", "购买", "充值", "AD: 下一页", "AD", "Ads", "Favorites", "Bookmark", "❤️20", "👍20", "点赞", "关注", "充值看下一组", "Subscribe", "Buy next page", "Share", "Close", "❌", "下载链接", "Download link", "/start")) {
            var result = analyze("资源内容:158 ❤️20", label);
            assertEquals("UNSUPPORTED", result.buttons().getFirst().kind(), label); assertNull(result.buttons().getFirst().page(), label);
            assertEquals("UNKNOWN", result.status(), label); assertNull(result.nextButtonId(), label);
        }
        assertEquals("OTHER", analyze("", "自定义翻页").buttons().getFirst().kind());
    }

    @Test void conflictingCurrentCountersOrTargetsNeverRecommendACallback() {
        for (String body : List.of("页码1/16，当前页:2", "页码1/16 页码2/16", "页码1/16，·当前·", "第2组文件(共6组)")) {
            var result = analyze(body, "下一组 (2/6)");
            // The third fixture conflicts on total: 16 in the body vs 6 in the button.
            assertEquals("UNKNOWN", result.status(), body); assertNull(result.nextButtonId(), body);
        }
        assertEquals("UNKNOWN", analyze("页码1/16", "·2·", "3").status());
    }

    @Test void aLastPageCounterWithAnyForwardCallbackIsContradictory() {
        for (String label : List.of("下一页", "→", "下10页", "17")) {
            var result = analyze("页码:16/16", label);
            assertEquals("UNKNOWN", result.status(), label); assertNull(result.nextButtonId(), label);
        }
    }

    @Test void successTextAndAForwardCallbackOrIncompleteCounterIsContradictory() {
        assertEquals("UNKNOWN", analyze("文件全部获取成功！", "下一页").status());
        assertEquals("UNKNOWN", analyze("文件全部获取成功！页码1/6").status());
    }

    @Test void onlyConsecutiveNumberedPagesAreAutomaticallySelected() {
        assertEquals("UNKNOWN", analyze("页码1/16", "3", "4", "下10页").status());
        assertEquals("UNKNOWN", analyze("资源列表", "1", "2", "下10页").status());
        var result = BotPagination.analyze("页码1/16", List.of(new InputButton("a", "2", true), new InputButton("b", "2", true)));
        assertEquals("UNKNOWN", result.status()); assertNull(result.nextButtonId());
    }

    @Test void severalNextButtonsAreAmbiguousAndAUrlButtonCannotOverrideAnActualCallback() {
        assertEquals("UNKNOWN", analyze("", "下一页", "Next page").status());
        var result = BotPagination.analyze("", List.of(url("下一页"), callback("Next page")));
        assertEquals("MORE", result.status()); assertEquals("Next page", result.nextButtonId());
    }

    @Test void unknownWordsContainingNextDoNotBecomePagination() {
        assertEquals("OTHER", analyze("", "nextdoor").buttons().getFirst().kind());
        assertEquals("UNKNOWN", analyze("", "nextdoor").status());
    }

    @Test void standardExplicitEndVariantsAndMissingHistoryRemainDistinct() {
        for (String body : List.of("已到最后一页", "沒有更多文件", "No more results", "All files received successfully", "End of list", "Page 5/5", "第6组文件（共6组）")) {
            assertEquals("END", analyze(body, "上一页").status(), body);
        }
        assertEquals("UNKNOWN", BotPagination.analyze(null, null).status());
    }

    @Test void mentionsAndNegatedSuccessDoNotProveTheEndOfAResource() {
        for (String body : List.of("点击按钮前往最后一页", "不是最后一页", "文件未全部获取成功", "尚未全部获取成功", "Not all files received successfully", "This is not last page", "如果没有更多文件请手动结束", "Only when all files received successfully", "资源标题：文件全部获取成功", "> 文件全部获取成功", "“文件全部获取成功”")) {
            assertEquals("UNKNOWN", analyze(body).status(), body);
        }
        assertEquals("END", analyze("提示：文件全部获取成功！").status());
    }

    @Test void invalidCountsDoNotOverflowOrFalselyCompleteAndOutputListsAreImmutable() {
        for (String body : List.of("页码0/16", "页码17/16", "页码1000001/1000001")) {
            var result = analyze(body, "下一页"); assertEquals("UNKNOWN", result.status(), body); assertNull(result.nextButtonId(), body);
        }
        assertThrows(UnsupportedOperationException.class, () -> analyze("", "下一页").buttons().clear());
    }

    @Test void inputLimitsRejectDuplicateIdentitiesAndOversizedPayloads() {
        assertThrows(IllegalArgumentException.class, () -> BotPagination.analyze("x".repeat(8193), List.of()));
        assertThrows(IllegalArgumentException.class, () -> BotPagination.analyze("", IntStream.range(0, 101).mapToObj(i -> callback(Integer.toString(i))).toList()));
        assertThrows(IllegalArgumentException.class, () -> BotPagination.analyze("", List.of(callback("下一页"), callback("下一页"))));
        assertThrows(IllegalArgumentException.class, () -> BotPagination.analyze("", List.of(new InputButton("", "Next", true))));
        assertThrows(IllegalArgumentException.class, () -> BotPagination.analyze("", List.of(new InputButton("a", "x".repeat(513), true))));
    }
}
