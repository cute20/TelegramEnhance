package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import it.tdlight.jni.TdApi;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class DownloadSourceReaderTest {
    @Test void independentlySelectedExternalChannelPostRetainsItsFullTextAndLibraryRemainsManaged(){
        var f=new Fixture();f.main.content=new TdApi.MessageText(new TdApi.FormattedText("x".repeat(150)+" #旅行 周末",new TdApi.TextEntity[0]),null,null);
        var post=f.reader.downloadChannelPost("-1001","100",25000);assertTrue(post.text().endsWith("#旅行 周末"));assertThrows(IllegalArgumentException.class,()->f.reader.channelPost("-1001","100"));
    }
    @Test void scopedPostGuardPreventsNativeCallsInAChangedContext(){var f=new Fixture();assertThrows(IllegalArgumentException.class,()->f.reader.downloadChannelPost("-1001","100",25000,()->{throw new IllegalArgumentException("old session");}));assertTrue(f.calls.isEmpty());}
    @Test void scopedCommentGuardPreventsNativeCallsInAChangedContext(){var f=new Fixture();var post=DownloadFixturePost();assertThrows(IllegalArgumentException.class,()->f.reader.downloadComments(post,null,25000,()->{throw new IllegalArgumentException("old session");}));assertTrue(f.calls.isEmpty());}
    private static io.github.telegramenhance.library.ResourceModels.Post DownloadFixturePost(){return new io.github.telegramenhance.library.ResourceModels.Post("account:-1001:100","account","-1001","source","100","title","body","0",List.of(),List.of(),"-2001","500",List.of(),true,"PARTIAL",List.of());}
    @Test void anAccessibleExternalPostCanBePreviewedWhileTheLibraryStillRejectsNonAdministrators() {
        var f = new Fixture();
        var post = f.reader.probeDownloadSource("https://t.me/synthetic/1");
        assertEquals("account:-1001:100", post.key()); assertEquals("COMPLETE", post.mainCoverage());
        assertTrue(f.calls.stream().noneMatch(query -> query instanceof TdApi.GetSupergroup));
        assertThrows(IllegalArgumentException.class, () -> f.reader.probe("https://t.me/synthetic/1"));
        assertTrue(f.calls.stream().anyMatch(query -> query instanceof TdApi.GetSupergroup));
    }
    @Test void externalPreviewDoesNotAcceptGroupMessagesCommentsOrMismatchedResolvedIdentities() {
        for (int kind = 0; kind < 3; kind++) {
            var f = new Fixture();
            if (kind == 0) f.main.isChannelPost = false;
            if (kind == 1) f.chat.type = new TdApi.ChatTypeSupergroup(1001, false);
            if (kind == 2) f.resolvedChat = -9999;
            assertThrows(IllegalArgumentException.class, () -> f.reader.probeDownloadSource("https://t.me/synthetic/1"));
            assertTrue(f.calls.stream().noneMatch(query -> query instanceof TdApi.GetMessageThread));
        }
    }
    @Test void commentLinksAndNonTelegramUrlsNeverReachTheNativeClient() {
        for (String link : List.of("https://example.com/1", "https://t.me/synthetic/1?comment=2", "https://t.me/synthetic/1?thread=2")) {
            var f = new Fixture(); assertThrows(IllegalArgumentException.class, () -> f.reader.probeDownloadSource(link)); assertTrue(f.calls.isEmpty());
        }
    }
    @Test void anAccountChangeWhileReadingAnExternalPostIsNotReturnedAsAValidPreview() {
        var f = new Fixture(); f.changeAccount = true;
        assertThrows(IllegalArgumentException.class, () -> f.reader.probeDownloadSource("https://t.me/synthetic/1"));
    }
    private static final class Fixture implements ResourceReader.Gateway {
        final List<TdApi.Function<?>> calls = new ArrayList<>();
        final TdApi.Message main = new TdApi.Message(); final TdApi.Chat chat = new TdApi.Chat();
        String account = "account"; boolean changeAccount; long resolvedChat = -1001;
        final ResourceReader reader = new ResourceReader(this, () -> account);
        Fixture() {
            main.chatId = -1001; main.id = 100; main.isChannelPost = true;
            main.content = new TdApi.MessageText(new TdApi.FormattedText("synthetic", new TdApi.TextEntity[0]), null, null);
            chat.id = -1001; chat.title = "Synthetic external source"; chat.type = new TdApi.ChatTypeSupergroup(1001, true);
        }
        @Override @SuppressWarnings("unchecked") public <R extends TdApi.Object> CompletableFuture<R> read(TdApi.Function<R> query) {
            calls.add(query); TdApi.Object result;
            if (query instanceof TdApi.GetMessageLinkInfo) { var info = new TdApi.MessageLinkInfo(); info.chatId = resolvedChat; info.message = main; result = info; }
            else if (query instanceof TdApi.GetMessage) result=main;
            else if (query instanceof TdApi.GetChat) { result = chat; if (changeAccount) account = "different"; }
            else if (query instanceof TdApi.GetSupergroup) { var group = new TdApi.Supergroup(); group.status = new TdApi.ChatMemberStatusMember(); result = group; }
            else if (query instanceof TdApi.GetMessageThread) result = null;
            else if (query instanceof TdApi.GetMessageLink) result = new TdApi.MessageLink("", true);
            else throw new AssertionError("Preview permits only explicit metadata reads");
            return CompletableFuture.completedFuture((R) result);
        }
    }
}
