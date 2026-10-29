package io.github.telegramenhance.downloadrules;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.downloadrules.DownloadRuleModels.*;
import io.github.telegramenhance.library.ChannelHistoryModels.HistoryPage;
import io.github.telegramenhance.library.ChannelImportModels.ManagedChannel;
import io.github.telegramenhance.library.ChannelImportModels.Candidate;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.rules.RuleModels.Window;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.*;
import io.github.telegramenhance.transfer.DownloadPaths;
import io.github.telegramenhance.transfer.DownloadRepository;
import io.github.telegramenhance.transfer.DownloadModels.FrozenFile;
import java.nio.file.Path;
import java.util.Map;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Isolated safety regressions: no real Telegram connection or production metadata is used. */
class DownloadRuleSafetyTest {
    @TempDir Path directory;
    private static Rule rule() {
        var input=new Input("sample","-1001","","",List.of("DOCUMENT"),false,"D:\\out",new Window("ALL_DAY",null,null,"Asia/Shanghai"));
        return new Rule(UUID.randomUUID().toString(),"1",1,1,true,0,1,1,input,"-1001","channel");
    }
    @Test void normalShutdownDoesNotLatchAnEnabledRuleIntoAPermanentReadError() {
        var rule=rule();var repository=mock(DownloadRuleRepository.class);var work=mock(ResourceService.class);var telegram=mock(TelegramService.class);
        when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);when(repository.get("1",rule.id())).thenReturn(rule);
        var service=new DownloadRuleService(repository,work,mock(ChannelReader.class),mock(ResourceReader.class),mock(DownloadFileReader.class),mock(DownloadPaths.class),mock(DownloadRulePolicy.class),telegram);
        when(work.readWork(any())).thenAnswer(call->{service.stop();try{return CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(0)).get());}catch(RuntimeException failure){return CompletableFuture.failedFuture(failure);}});
        assertThrows(ExecutionException.class,()->service.check(rule.id(),new DownloadRuleService.Position(1)).get());
        verify(repository,never()).schedule(any(),anyLong(),any(),anyLong(),any());
    }
    @Test void monitorStopDoesNotTurnAQueuedReadIntoAPermanentError() {
        var rule=rule();var repository=mock(DownloadRuleRepository.class);var work=mock(ResourceService.class);var telegram=mock(TelegramService.class);
        when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);when(repository.get("1",rule.id())).thenReturn(rule);when(repository.nextDue(eq("1"),anyLong())).thenReturn(rule);
        when(work.readWork(any())).thenAnswer(call->{try{return CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(0)).get());}catch(RuntimeException failure){return CompletableFuture.failedFuture(failure);}});
        var service=new DownloadRuleService(repository,work,mock(ChannelReader.class),mock(ResourceReader.class),mock(DownloadFileReader.class),mock(DownloadPaths.class),mock(DownloadRulePolicy.class),telegram);
        assertThrows(ExecutionException.class,()->service.monitorOnce(()->{throw new IllegalArgumentException("stopping");}).get());
        verify(repository,never()).schedule(any(),anyLong(),any(),anyLong(),any());
    }
    @Test void warningAboutSkippedHistoryCannotBeSilentlyPromotedToACompleteBoundary() {
        var rule=rule();var scan=new Scan(rule.id(),1,0,"HISTORY",null,null,0,false,0,0,null,1);
        var page=new HistoryPage(new ManagedChannel("-1001","channel"),List.of(),"CHANNEL_PAGE",10,null,true,false,List.of("存在尚未正式发送或临时消息，已跳过；本轮范围需结合提示核对。"));
        assertThrows(IllegalArgumentException.class,()->DownloadRuleService.validateHistory(rule,scan,page));
    }
    @Test void anEnormousCommentThreadDoesNotStarveNewPostDiscoveryOrAnotherOldPostReview() throws Exception {
        try(var f=new FairFixture()) {
            var rule=f.seed();for(int unit=0;unit<14;unit++)f.step(rule);
            assertNotNull(f.repository.getPost(rule,f.newest.key()));
            verify(f.posts,atLeastOnce()).downloadChannelPost(eq("-1001"),eq(f.old.mainMessageId()),anyLong(),any());
            verify(f.posts,atLeastOnce()).downloadComments(argThat(p->p.key().equals(f.longThread.key())),nullable(String.class),anyLong(),any());
            assertEquals("COMMENTS",f.repository.getPost(rule,f.longThread.key()).state());
            assertTrue(f.repository.getPost(rule,f.longThread.key()).pages()>0);assertTrue(f.downloads.list("1",20,0).isEmpty());
        }
    }
    @Test void discoveryAndOldPostMetadataCanAdvanceWhileTheSingleFileWorkerOwnsAnActiveJob() throws Exception {
        try(var f=new FairFixture()) {
            var rule=f.seed();var state=f.repository.getPost(rule,f.longThread.key());
            var media=new Media("1:-2001:1000:0",new MessageRef("-2001","1000",""),"COMMENT","DOCUMENT","existing.bin",3,"");
            f.repository.commitPage(rule,state,"1000",false,List.of(new FrozenFile(media,"stable-existing",3,"existing.bin")),f.root,f.now,()->{});
            var active=f.downloads.next("1");assertNotNull(active);
            for(int unit=0;unit<12;unit++)f.step(rule);
            assertNotNull(f.repository.getPost(rule,f.newest.key()));
            verify(f.posts,atLeastOnce()).downloadChannelPost(eq("-1001"),eq(f.old.mainMessageId()),anyLong(),any());
            assertEquals(1,f.downloads.list("1",20,0).size());assertEquals("RUNNING",f.downloads.task("1",active.id()).state());
            verifyNoInteractions(f.files);
        }
    }
    private final class FairFixture implements AutoCloseable {
        final AppPaths paths=new AppPaths(directory.resolve(UUID.randomUUID().toString()));final Database db=new Database(paths);final DownloadRepository downloads=new DownloadRepository(db);
        final DownloadRuleRepository repository=new DownloadRuleRepository(db,downloads);final ResourceService work=mock(ResourceService.class);final ChannelReader channels=mock(ChannelReader.class);
        final ResourceReader posts=mock(ResourceReader.class);final DownloadFileReader files=mock(DownloadFileReader.class);final TelegramService telegram=mock(TelegramService.class);
        final ManagedChannel channel=new ManagedChannel("-1001","channel");final Post longThread=post("100"),old=post("90"),newest=post("110");final Path root=directory.resolve("out").toAbsolutePath();
        final DownloadRuleService service;long now=1_000_000;
        FairFixture(){
            when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);
            when(work.readWork(any())).thenAnswer(call->{try{return CompletableFuture.completedFuture(((Supplier<?>)call.getArgument(0)).get());}catch(RuntimeException failure){return CompletableFuture.failedFuture(failure);}});
            var values=Map.of("100",longThread,"90",old,"110",newest);
            when(posts.downloadChannelPost(anyString(),anyString(),anyLong(),any())).thenAnswer(call->{((Runnable)call.getArgument(3)).run();return values.get(call.getArgument(1));});
            when(channels.downloadHistoryPage(anyString(),nullable(String.class))).thenReturn(history(newest,longThread));
            when(posts.downloadComments(any(),nullable(String.class),anyLong(),any())).thenAnswer(call->{((Runnable)call.getArgument(3)).run();var p=(Post)call.getArgument(0);
                if(!p.key().equals(longThread.key()))return new Page(p.key(),List.of(),List.of(),0,null,true,"COMPLETE",List.of());
                String previous=call.getArgument(1);String next=previous==null?"1000":Long.toString(Long.parseLong(previous)-1);
                return new Page(p.key(),List.of(),List.of(new MessageRef("-2001",next,"")),1,next,false,"PARTIAL",List.of());});
            service=new DownloadRuleService(repository,work,channels,posts,files,new DownloadPaths(paths),mock(DownloadRulePolicy.class),telegram,()->now);
        }
        Rule seed(){
            var input=new Input("sample","-1001","","",List.of("DOCUMENT"),false,root.toString(),new Window("ALL_DAY",null,null,"Asia/Shanghai"));
            var paused=repository.create("1",UUID.randomUUID().toString(),"fingerprint",input,channel,now,()->{});var rule=repository.toggle("1",paused.id(),1,true,now,()->{});
            var scan=repository.saveHistoryPage(rule,repository.scan(rule),history(longThread,old),now,()->{});
            repository.observePost(rule,scan,longThread,true,now,()->{});
            repository.commitPage(rule,repository.getPost(rule,longThread.key()),null,false,List.of(),root,now,()->{});
            repository.observePost(rule,repository.scan(rule),old,true,now,()->{});
            repository.commitPage(rule,repository.getPost(rule,old.key()),null,false,List.of(),root,now,()->{});
            repository.commitPage(rule,repository.getPost(rule,old.key()),null,true,List.of(),root,now,()->{});
            now+=360_000;return rule;
        }
        void step(Rule rule)throws Exception {now+=2_000;service.check(rule.id(),new DownloadRuleService.Position(rule.revision())).get();}
        HistoryPage history(Post... values){return new HistoryPage(channel,java.util.Arrays.stream(values).map(p->new Candidate(p.mainMessageId(),"0",p.title())).toList(),"CHANNEL_PAGE",10,null,true,false,List.of());}
        Post post(String id){return new Post("1:-1001:"+id,"1","-1001","channel",id,"post "+id,"body","0",List.of(new MessageRef("-1001",id,"")),List.of(),"-2001","50",List.of(new MessageRef("-2001","50","")),true,"PARTIAL",List.of(),new AlbumEvidence(true,"synthetic","1","2"),"COMPLETE");}
        public void close(){db.close();}
    }
}
