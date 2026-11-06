package io.github.telegramenhance.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.downloadrules.*;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.rules.*;
import io.github.telegramenhance.security.LocalAccess;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.*;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.transfer.DownloadModels.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class TransferCenterDeletionControllerTest {
    @TempDir Path directory;
    private static final String HOST="127.0.0.1:43127",ACTION="/api/transfers/center/action";

    @Test void authenticatedPausedDeletionIsIdempotentAndKeepsTheOriginalOperationWhileBlockingResume()throws Exception{
        try(var f=new Fixture()){
            var task=f.task("1");f.downloads.pause("1",task.id());
            var pending=f.mvc.perform(f.authorized(post(ACTION)).content(body(task.id()))).andExpect(request().asyncStarted()).andReturn();
            f.mvc.perform(asyncDispatch(pending)).andExpect(status().isOk()).andExpect(jsonPath("$.item").isEmpty()).andExpect(jsonPath("$.check.category").value("download")).andExpect(jsonPath("$.check.id").value(task.id())).andExpect(jsonPath("$.check.deleted").value(true));
            var repeated=f.mvc.perform(f.authorized(post(ACTION)).content(body(task.id()))).andExpect(request().asyncStarted()).andReturn();
            f.mvc.perform(asyncDispatch(repeated)).andExpect(status().isOk()).andExpect(jsonPath("$.check.id").value(task.id())).andExpect(jsonPath("$.check.deleted").value(true));
            assertEquals(1,f.tombstones());assertTrue(f.downloads.list("1",20,0).isEmpty());assertEquals(task.id(),f.downloads.byOperation("1",task.operationId()).id());
            f.mvc.perform(f.authorized(get("/api/transfers/download/tasks/"+task.id()))).andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("下载任务已删除"));
            f.mvc.perform(f.authorized(post("/api/transfers/download/tasks/"+task.id()+"/resume"))).andExpect(status().isBadRequest());
            assertNull(f.downloads.next("1"));verifyNoInteractions(f.reader,f.engine);
        }
    }

    @Test void activeForeignAndManagedTaskRequestsCannotCreateDeletionTombstones()throws Exception{
        try(var f=new Fixture()){
            var active=f.task("1");assertEquals(active.id(),f.downloads.next("1").id());
            var foreign=f.task("2");f.downloads.pause("2",foreign.id());
            var post=f.post("1");var file=f.file(post);var snapshot=new DownloadBatchModels.Snapshot(UUID.randomUUID().toString(),1,"synthetic",List.of(),List.of(),List.of("DOCUMENT"),true,f.output.toString(),1,1,37);
            var batch=f.downloads.createBatch("1",UUID.randomUUID().toString(),"batch",snapshot,f.output,List.of(new DownloadBatchModels.Job(post,List.of(file))));
            var child=batch.taskIds().getFirst();f.downloads.pause("1",child);
            for(String id:List.of(active.id(),foreign.id(),child))f.mvc.perform(f.authorized(post(ACTION)).content(body(id))).andExpect(request().asyncNotStarted()).andExpect(status().isBadRequest());
            assertEquals(0,f.tombstones());assertEquals("RUNNING",f.downloads.task("1",active.id()).state());assertEquals("PAUSED",f.downloads.task("2",foreign.id()).state());assertEquals(1,f.downloads.batchStatus("1",batch.id()).totalTasks());
            verifyNoInteractions(f.reader,f.engine);
        }
    }

    @Test void missingLaunchAuthorizationAndCrossOriginRequestsNeverReachDeletion()throws Exception{
        try(var f=new Fixture()){
            var task=f.task("1");f.downloads.pause("1",task.id());
            f.mvc.perform(post(ACTION).header("Host",HOST).contentType("application/json").content(body(task.id()))).andExpect(status().isUnauthorized());
            f.mvc.perform(f.authorized(post(ACTION)).header("Origin","https://foreign.example").content(body(task.id()))).andExpect(status().isForbidden());
            assertEquals(0,f.tombstones());assertEquals("PAUSED",f.downloads.task("1",task.id()).state());verifyNoInteractions(f.reader,f.engine);
        }
    }

    private static String body(String id){return "{\"category\":\"download\",\"id\":\""+id+"\",\"action\":\"delete\"}";}
    private final class Fixture implements AutoCloseable {
        final AppPaths paths=new AppPaths(directory.resolve(UUID.randomUUID().toString()));final Database database=new Database(paths);final DownloadRepository downloads=new DownloadRepository(database);
        final TelegramService telegram=mock(TelegramService.class);final DownloadFileReader reader=mock(DownloadFileReader.class);final DownloadEngine engine=mock(DownloadEngine.class);
        final Path output=directory.resolve("output");final MockMvc mvc;final String token;
        Fixture()throws Exception{
            when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);
            var tasks=new DownloadTaskService(mock(DownloadSourceService.class),mock(ResourceService.class),reader,downloads,new DownloadPaths(paths),telegram,engine);
            var center=new TransferCenterService(database,telegram,downloads,mock(UploadRepository.class),tasks,mock(DownloadBatchService.class),mock(UploadTaskService.class),mock(DownloadRuleTaskService.class),mock(RuleService.class),mock(DownloadRuleService.class),mock(RulePreparationService.class),mock(DownloadChannelService.class),mock(UploadRetryService.class));
            var access=new LocalAccess(paths);var port=LocalAccess.class.getDeclaredField("port");port.setAccessible(true);port.setInt(access,43127);token=access.launchUri().getFragment().substring("token=".length());
            mvc=MockMvcBuilders.standaloneSetup(new TransferCenterController(center),new DownloadTaskController(tasks)).setControllerAdvice(new ApiErrors()).addFilters(access).build();
        }
        MockHttpServletRequestBuilder authorized(MockHttpServletRequestBuilder request){return request.header("Host",HOST).header("X-App-Token",token).contentType("application/json");}
        Post post(String account){var ref=new MessageRef("9","100",null);var media=new Media(account+":9:100:0",ref,"BOT","DOCUMENT","file.bin",37,"");return new Post("bot:9:9:100",account,"9","synthetic","100","synthetic","",null,List.of(ref),List.of(media),null,null,List.of(),false,"PARTIAL",List.of());}
        FrozenFile file(Post post){return new FrozenFile(post.mainMedia().getFirst(),"uid-"+UUID.randomUUID(),37,"file.bin");}
        Task task(String account){var post=post(account);return downloads.create(account,UUID.randomUUID().toString(),"fingerprint",post,output,List.of(file(post)));}
        int tombstones(){return database.read(c->{try(var q=c.createStatement();var r=q.executeQuery("SELECT COUNT(*) FROM download_task_deletions")){assertTrue(r.next());return r.getInt(1);}});}
        @Override public void close(){database.close();}
    }
}
