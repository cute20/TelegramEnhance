package io.github.telegramenhance.telegram;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.telegramenhance.telegram.ChannelReaderTest.*;
import it.tdlight.jni.TdApi;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
class DownloadChannelReaderTest{
    @Test void membersCanDiscoverAndReadDownloadChannelsWithoutWeakeningLibraryRestrictions(){
        var s=new Scenario();s.add(-1001,new TdApi.ChatMemberStatusMember());s.main=new long[]{-1001};assertEquals("READABLE_CHANNELS",s.reader().downloadChannels().scope());assertEquals(1,s.reader().downloadChannels().items().size());assertTrue(s.reader().channels().items().isEmpty());s.pages.add(messages(message(100,0,"source")));assertEquals(1,s.reader().downloadHistoryPage("-1001",null).items().size());assertThrows(IllegalArgumentException.class,()->s.reader().historyPage("-1001",null));
    }
    @Test void privateLeftAndPublicBannedChannelsAreRejectedButPublicLeftCanBeReadWithoutJoining(){
        for(boolean banned:List.of(false,true)){var s=new Scenario();s.add(-1001,banned?new TdApi.ChatMemberStatusBanned():new TdApi.ChatMemberStatusLeft());if(banned){s.groups.get(1001L).usernames=new TdApi.Usernames();s.groups.get(1001L).usernames.activeUsernames=new String[]{"synthetic"};}assertThrows(IllegalArgumentException.class,()->s.reader().downloadChannel("-1001"));assertTrue(s.historyCursors().isEmpty());}
        var s=new Scenario();s.add(-1001,new TdApi.ChatMemberStatusLeft());s.groups.get(1001L).usernames=new TdApi.Usernames();s.groups.get(1001L).usernames.activeUsernames=new String[]{"synthetic"};assertEquals("-1001",s.reader().downloadChannel("-1001").id());
    }
    @Test void explicitPublicNamesAndPostLinksResolveOnlyThroughMetadataCalls(){
        for(String source:List.of("@synthetic","https://t.me/synthetic","https://t.me/synthetic/1","https://t.me/c/1001/1")){
            var s=new Scenario();s.add(-1001,new TdApi.ChatMemberStatusMember());var reader=new ChannelReader(new ChannelReader.Gateway(){@SuppressWarnings("unchecked")public <R extends TdApi.Object>CompletableFuture<R>read(long session,TdApi.Function<R>query){if(query instanceof TdApi.SearchPublicChat q){assertEquals("synthetic",q.username);s.queries.add(query);return CompletableFuture.completedFuture((R)s.chats.get(-1001L));}if(query instanceof TdApi.GetMessageLinkInfo){s.queries.add(query);var info=new TdApi.MessageLinkInfo();info.chatId=-1001;info.message=message(100,0,"source");return CompletableFuture.completedFuture((R)info);}return s.read(session,query);}},()->s.account,()->s.generation,List::of,35000);
            assertEquals("-1001",reader.downloadChannel(source).id());assertTrue(s.queries.stream().allMatch(q->q instanceof TdApi.SearchPublicChat||q instanceof TdApi.GetMessageLinkInfo||q instanceof TdApi.GetChat||q instanceof TdApi.GetSupergroup));
        }
    }
    @Test void invitesForeignSitesAndMalformedSourcesNeverStartNativeReads(){for(String source:List.of("https://t.me/+invite","https://t.me/joinchat/abcdef","@joinchat","https://evil.example/synthetic","http://t.me/synthetic","https://user@t.me/synthetic","https://t.me/synthetic#fragment","https://t.me/c/1001")){var s=new Scenario();assertThrows(IllegalArgumentException.class,()->s.reader().downloadChannel(source),source);assertTrue(s.queries.isEmpty(),source);}}
    @Test void downloadHistoryKeepsTheSameAlbumTailAndStrictOpaqueCursorRules(){
        var s=new Scenario();s.add(-1001,new TdApi.ChatMemberStatusMember());for(long n=100;n>=95;n--)s.pages.add(messages(message(n,8,"member")));var first=s.reader().downloadHistoryPage("-1001",null);assertEquals(1,first.items().size());assertTrue(first.hasMore());s.pages.add(messages(message(94,8,"tail"),message(93,0,"next")));var next=s.reader().downloadHistoryPage("-1001",first.nextCursor());assertEquals(List.of("93"),next.items().stream().map(c->c.messageId()).toList());assertTrue(next.exhausted());assertThrows(IllegalArgumentException.class,()->s.reader().downloadHistoryPage("-1002",first.nextCursor()));
    }
    @Test void sameAccountSessionChangeAndPermissionLossRejectFurtherHistory(){
        var s=new Scenario();s.add(-1001,new TdApi.ChatMemberStatusMember());s.after=q->s.generation++;assertThrows(IllegalArgumentException.class,()->s.reader().downloadChannel("-1001"));var revoked=new Scenario();revoked.add(-1001,new TdApi.ChatMemberStatusBanned());assertThrows(IllegalArgumentException.class,()->revoked.reader().downloadHistoryPage("-1001",null));assertTrue(revoked.historyCursors().isEmpty());
    }
}
