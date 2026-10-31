package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.downloadrules.DownloadRuleModels.*;
import io.github.telegramenhance.downloadrules.DownloadRuleRepository;
import io.github.telegramenhance.library.ChannelImportModels.ManagedChannel;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.rules.RuleModels.Window;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.transfer.DownloadModels.*;
import io.github.telegramenhance.transfer.DownloadRuleTaskService.*;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

class DownloadRuleFilesServiceTest {
    @TempDir Path directory;
    private static final JsonMapper JSON=JsonMapper.builder().build();
    private static final String HASH="a".repeat(64);
    private static final ManagedChannel CHANNEL=new ManagedChannel("-1001","same channel");

    @Test void twoCommentBatchesAppearAsOne87FileSourceWhileMainAttachmentsStaySeparate(){try(var f=new Fixture()){
        var rule=f.rule("1");f.task(rule,Collections.nCopies(37,"COMMENT"),100,true);f.task(rule,Collections.nCopies(50,"REPLY"),200,true);f.task(rule,List.of("MAIN","MAIN"),300,true);
        var comments=f.service.files(rule.id(),"COMMENT",50,null);
        assertEquals(rule.id(),comments.ruleId());assertEquals("COMMENT",comments.source());assertEquals(87,comments.totals().total());assertEquals(87,comments.totals().completed());assertEquals(87*37,comments.totals().totalBytes());
        assertEquals(50,comments.items().size());assertTrue(comments.hasMore());assertNotNull(comments.nextCursor());
        var remaining=f.service.files(rule.id(),"COMMENT",50,comments.nextCursor());assertEquals(37,remaining.items().size());assertFalse(remaining.hasMore());assertNull(remaining.nextCursor());
        var ids=new HashSet<String>();for(var page:List.of(comments,remaining))for(var file:page.items()){assertTrue(ids.add(file.item().id()));assertEquals("COMMENT",file.source());}
        assertEquals(87,ids.size());
        var main=f.service.files(rule.id(),"MAIN",50,null);assertEquals(2,main.totals().total());assertEquals(2,main.totals().completed());assertEquals(2,main.items().size());assertFalse(main.hasMore());
        assertTrue(main.items().stream().allMatch(file->file.source().equals("MAIN")&&!ids.contains(file.item().id())));
        assertThrows(UnsupportedOperationException.class,()->main.items().clear());
    }}

    @Test void mixedSourcesAndVersionsStayWithTheirRuleAndNeverMergeSameNameOrAnotherAccount(){try(var f=new Fixture()){
        var first=f.rule("1");var a=f.task(first,List.of("MAIN","COMMENT","REPLY"),100,true);
        var nextVersion=f.rules.update("1",first.id(),first.revision(),f.input(),CHANNEL,3,()->{});var b=f.task(nextVersion,List.of("MAIN","COMMENT"),200,true);
        var sameName=f.rule("1");f.task(sameName,List.of("COMMENT"),300,true);
        var anotherAccount=f.rule("2");f.task(anotherAccount,List.of("COMMENT"),400,true);
        var comments=f.service.files(first.id(),"COMMENT",50,null);assertEquals(3,comments.totals().total());
        assertEquals(Set.of(a.id(),b.id()),comments.items().stream().map(SourceFile::taskId).collect(java.util.stream.Collectors.toSet()));
        assertEquals(Set.of(1L,2L),comments.items().stream().map(SourceFile::ruleVersion).collect(java.util.stream.Collectors.toSet()));
        assertEquals(2,f.service.files(first.id(),"MAIN",50,null).totals().total());
        assertEquals(1,f.service.files(sameName.id(),"COMMENT",50,null).totals().total());
        assertThrows(IllegalArgumentException.class,()->f.service.files(anotherAccount.id(),"COMMENT",50,null));
    }}

    @Test void keysetPagingAcrossMoreThanTenTasksDoesNotShiftWhenANewerTaskArrives(){try(var f=new Fixture()){
        var rule=f.rule("1");var old=new ArrayList<Task>();for(int n=0;n<13;n++)old.add(f.task(rule,List.of("COMMENT","REPLY"),100+n/3,true));
        var expected=old.stream().sorted(Comparator.comparingLong(Task::createdAt).thenComparing(Task::id).reversed())
            .flatMap(task->task.items().reversed().stream().map(Item::id)).toList();
        var first=f.service.files(rule.id(),"COMMENT",7,null);assertEquals(expected.subList(0,7),first.items().stream().map(file->file.item().id()).toList());
        var newer=f.task(rule,List.of("COMMENT"),10_000,true);
        var all=new ArrayList<String>();first.items().forEach(file->all.add(file.item().id()));
        String cursor=first.nextCursor();int pages=1;
        while(cursor!=null){var page=f.service.files(rule.id(),"COMMENT",7,cursor);assertEquals(27,page.totals().total());page.items().forEach(file->all.add(file.item().id()));cursor=page.nextCursor();assertTrue(++pages<10);}
        assertEquals(expected,all);assertEquals(26,new HashSet<>(all).size());assertFalse(all.contains(newer.items().getFirst().id()));
        var refreshed=f.service.files(rule.id(),"COMMENT",7,null);assertEquals(newer.id(),refreshed.items().getFirst().taskId());assertEquals(27,refreshed.totals().total());
    }}

    @Test void cursorsAreBoundToAccountRuleAndSourceAndRejectMalformedPositions(){try(var f=new Fixture()){
        var rule=f.rule("1");f.task(rule,List.of("COMMENT","COMMENT"),100,true);var other=f.rule("1");
        String cursor=f.service.files(rule.id(),"COMMENT",1,null).nextCursor();assertNotNull(cursor);
        assertThrows(IllegalArgumentException.class,()->f.service.files(rule.id(),"MAIN",1,cursor));
        assertThrows(IllegalArgumentException.class,()->f.service.files(other.id(),"COMMENT",1,cursor));
        when(f.telegram.accountId()).thenReturn("2");assertThrows(IllegalArgumentException.class,()->f.service.files(rule.id(),"COMMENT",1,cursor));when(f.telegram.accountId()).thenReturn("1");
        for(String bad:List.of("","!not-base64!","x".repeat(2049),"e30"))assertThrows(IllegalArgumentException.class,()->f.service.files(rule.id(),"COMMENT",1,bad));
        for(var entry:Map.<String,Object>of("version",2,"createdAt",-1L,"ordinal",100,"taskId","../other","itemId","not-an-id").entrySet()){
            String bad=changedCursor(cursor,entry.getKey(),entry.getValue());assertThrows(IllegalArgumentException.class,()->f.service.files(rule.id(),"COMMENT",1,bad));
        }
        for(String source:new String[]{null,"","REPLY","OTHER","comment"})assertThrows(IllegalArgumentException.class,()->f.service.files(rule.id(),source,1,null));
        for(int limit:List.of(-1,0,51,Integer.MAX_VALUE))assertThrows(IllegalArgumentException.class,()->f.service.files(rule.id(),"COMMENT",limit,null));
        assertThrows(IllegalArgumentException.class,()->f.service.files("../other","COMMENT",1,null));
    }}

    @Test void missingOrUnknownPersistedSourcesAreRejectedInsteadOfBeingCalledComments(){try(var f=new Fixture()){
        var rule=f.rule("1");var task=f.task(rule,List.of("MAIN","COMMENT"),100,false);String id=task.items().getLast().id();
        for(String change:List.of("json_set(spec_json,'$.media.source','OTHER')","json_set(spec_json,'$.media.source',null)","json_remove(spec_json,'$.media.source')")){
            f.db.write(c->{try(var q=c.prepareStatement("UPDATE download_items SET spec_json="+change+" WHERE item_id=?")){q.setString(1,id);q.executeUpdate();}return null;});
            assertThrows(IllegalStateException.class,()->f.service.files(rule.id(),"COMMENT",50,null));assertThrows(IllegalStateException.class,()->f.service.files(rule.id(),"MAIN",50,null));
        }
    }}

    @Test void completedBytesWithoutAMatchingCompletionLogDoNotCountAsSuccessfulFiles(){try(var f=new Fixture()){
        var validRule=f.rule("1");f.task(validRule,List.of("COMMENT"),100,true);
        var absentRule=f.rule("1");var absent=f.task(absentRule,List.of("COMMENT"),200,false);
        var wrongRule=f.rule("1");var wrong=f.task(wrongRule,List.of("COMMENT"),300,true);
        f.db.write(c->{try(var q=c.prepareStatement("UPDATE download_items SET state='COMPLETED',downloaded_bytes=size,written_bytes=size,sha256=? WHERE task_id=?")){q.setString(1,HASH);q.setString(2,absent.id());q.executeUpdate();}
            try(var q=c.prepareStatement("UPDATE download_completion_log SET sha256=? WHERE task_id=?")){q.setString(1,"b".repeat(64));q.setString(2,wrong.id());q.executeUpdate();}return null;});
        assertEquals(0,f.service.get(absentRule.id()).completed());assertEquals(0,f.service.get(wrongRule.id()).completed());
        var before=f.rows();
        assertThrows(IllegalStateException.class,()->f.service.files(absentRule.id(),"COMMENT",50,null));
        assertThrows(IllegalStateException.class,()->f.service.files(wrongRule.id(),"COMMENT",50,null));
        assertEquals(before,f.rows());
        var page=f.service.files(validRule.id(),"COMMENT",50,null);assertEquals(1,page.totals().total());assertEquals(37,page.totals().writtenBytes());assertEquals(1,page.totals().completed());assertEquals(0,page.totals().duplicates());
    }}

    @Test void noJsonDuplicateChainsKeepEvidenceAndAllSourceFileReadsLeaveStoredRowsUntouched(){try(var f=new Fixture()){
        var rule=f.rule("1");var original=f.task(rule,List.of("COMMENT"),100,true);
        var duplicate=f.duplicate(rule,"REPLY",200,original.items().getFirst().id());
        var chained=f.duplicate(rule,"COMMENT",300,duplicate.items().getFirst().id());
        var before=f.rows();var page=f.service.files(rule.id(),"COMMENT",50,null);
        assertEquals(3,page.totals().completed());assertEquals(2,page.totals().duplicates());assertEquals(0,page.totals().skipped());
        var chain=page.items().stream().filter(file->file.taskId().equals(chained.id())).findFirst().orElseThrow().item();
        assertNull(chain.manifestPath());assertEquals(duplicate.items().getFirst().id(),chain.duplicate().itemId());assertNotNull(chain.duplicate().sourceLink());
        assertEquals(f.downloads.view(duplicate).items().getFirst().outputPath(),chain.duplicate().path());assertEquals(37,chain.size());
        f.service.files(rule.id(),"COMMENT",1,null);f.service.files(rule.id(),"MAIN",50,null);
        assertEquals(before,f.rows());
    }}

    @Test void onlyTheRequestedFrozenFilePageIsDecodedAndCompleteTaskPostsAreNotLoaded(){try(var f=new Fixture()){
        var rule=f.rule("1");var old=f.task(rule,List.of("COMMENT"),100,false);var head=f.task(rule,List.of("COMMENT","COMMENT"),200,false);
        f.db.write(c->{try(var q=c.prepareStatement("UPDATE download_tasks SET post_json='not-a-post-json' WHERE task_id IN (?,?)")){q.setString(1,old.id());q.setString(2,head.id());q.executeUpdate();}
            try(var q=c.prepareStatement("UPDATE download_items SET spec_json='{\"media\":{\"source\":\"COMMENT\"},\"uniqueId\":\"broken\"}' WHERE task_id=?")){q.setString(1,old.id());q.executeUpdate();}return null;});
        clearInvocations(f.downloads);
        var page=f.service.files(rule.id(),"COMMENT",1,null);assertEquals(3,page.totals().total());assertEquals(head.id(),page.items().getFirst().taskId());assertEquals(1,page.items().size());assertTrue(page.hasMore());
        verifyNoInteractions(f.downloads);
        var second=f.service.files(rule.id(),"COMMENT",1,page.nextCursor());assertEquals(head.id(),second.items().getFirst().taskId());
        assertThrows(RuntimeException.class,()->f.service.files(rule.id(),"COMMENT",1,second.nextCursor()));
    }}

    @Test void authorizationAndSessionChangesDiscardSourceFileResults(){try(var f=new Fixture()){
        var rule=f.rule("1");f.task(rule,List.of("COMMENT"),100,true);
        when(f.telegram.sessionGeneration()).thenReturn(1L,2L);assertThrows(IllegalArgumentException.class,()->f.service.files(rule.id(),"COMMENT",50,null));
        when(f.telegram.sessionGeneration()).thenReturn(3L);when(f.telegram.accountId()).thenReturn("1","2");assertThrows(IllegalArgumentException.class,()->f.service.files(rule.id(),"COMMENT",50,null));
        when(f.telegram.accountId()).thenThrow(new IllegalArgumentException("not authorized"));assertThrows(IllegalArgumentException.class,()->f.service.files(rule.id(),"COMMENT",50,null));
    }}

    @SuppressWarnings("unchecked") private static String changedCursor(String cursor,String key,Object value){
        var data=new LinkedHashMap<String,Object>((Map<String,Object>)JSON.readValue(Base64.getUrlDecoder().decode(cursor),Map.class));data.put(key,value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(data));
    }
    final class Fixture implements AutoCloseable {
        final Database db=new Database(new AppPaths(directory.resolve(UUID.randomUUID().toString())));
        final DownloadRepository downloads=spy(new DownloadRepository(db));
        final DownloadRuleRepository rules=new DownloadRuleRepository(db,downloads);
        final TelegramService telegram=mock(TelegramService.class);
        final DownloadRuleTaskService service=new DownloadRuleTaskService(db,rules,downloads,telegram);
        final Path output=directory.resolve("out").toAbsolutePath();
        long sequence=9007199254740993L;
        Fixture(){when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);}
        Input input(){return new Input("same name",CHANNEL.id(),"","",List.of("DOCUMENT"),true,output.toString(),new Window("ALL_DAY",null,null,"Asia/Shanghai"));}
        Rule rule(String account){return rules.create(account,UUID.randomUUID().toString(),"fp",input(),CHANNEL,1,()->{});}
        Task task(Rule rule,List<String> sources,long createdAt,boolean complete){return task(rule,sources,createdAt,complete,DownloadModels.HISTORY_SKIP);}
        Task task(Rule rule,List<String> sources,long createdAt,boolean complete,String policy){
            String account=rule.accountId(),mid=Long.toString(sequence++);var ref=new MessageRef(CHANNEL.id(),mid,"https://t.me/synthetic/"+mid);
            var post=new Post(account+":"+CHANNEL.id()+":"+mid,account,CHANNEL.id(),CHANNEL.title(),mid,"same post","text","0",List.of(ref),List.of(),"-2001","500",List.of(),true,"PARTIAL",List.of(),new AlbumEvidence(true,"test",null,null),"COMPLETE");
            var files=new ArrayList<FrozenFile>();
            for(String source:sources){String message=Long.toString(sequence++),chat="MAIN".equals(source)?CHANNEL.id():"-2001",name="file-"+message+".txt";
                var media=new Media(account+":"+chat+":"+message+":0",new MessageRef(chat,message,"https://t.me/synthetic/"+message),source,"DOCUMENT",name,37,"");
                files.add(new FrozenFile(media,"uid-"+UUID.randomUUID(),37,name));}
            var task=downloads.create(account,UUID.randomUUID().toString(),"fp",post,output,files,policy);
            if(complete){for(var item:task.items())downloads.complete(account,task.id(),item.id(),item.relativeFile(),output.resolve(item.relativeFile()),HASH);downloads.finishState(account,task.id(),"COMPLETED",null);}
            else downloads.pause(account,task.id());
            db.write(c->{try(var q=c.prepareStatement("UPDATE download_tasks SET created_at=?,updated_at=? WHERE task_id=?")){q.setLong(1,createdAt);q.setLong(2,createdAt);q.setString(3,task.id());q.executeUpdate();}
                try(var q=c.prepareStatement("INSERT INTO download_rule_tasks VALUES(?,?,?,?,?,1,0,?,?)")){q.setString(1,task.id());q.setString(2,rule.id());q.setString(3,account);q.setLong(4,rule.version());q.setString(5,JSON.writeValueAsString(rule.input()));q.setLong(6,createdAt);q.setLong(7,createdAt);q.executeUpdate();}return null;});
            return downloads.task(account,task.id());
        }
        Task duplicate(Rule rule,String source,long createdAt,String previous){
            var task=task(rule,List.of(source),createdAt,false,DownloadModels.KEEP_DUPLICATES);var item=task.items().getFirst();
            downloads.progress(rule.accountId(),task.id(),item.id(),"WRITING",37,37,HASH,item.relativeFile());
            task=downloads.task(rule.accountId(),task.id());item=task.items().getFirst();downloads.outputPlan(task,item,HASH);
            String itemId=item.id();db.write(c->{try(var q=c.prepareStatement("UPDATE download_output_plans SET duplicate_of=?,manifest_relative=NULL WHERE item_id=?")){q.setString(1,previous);q.setString(2,itemId);q.executeUpdate();}return null;});
            task=downloads.task(rule.accountId(),task.id());item=task.items().getFirst();
            downloads.complete(rule.accountId(),task.id(),item.id(),item.relativeFile(),output.resolve(item.plan().relativePath()),HASH);downloads.finishState(rule.accountId(),task.id(),"COMPLETED",null);
            return downloads.task(rule.accountId(),task.id());
        }
        Map<String,List<List<Object>>> rows(){return db.read(c->{var snapshot=new LinkedHashMap<String,List<List<Object>>>();
            for(String table:List.of("download_tasks","download_items","download_rules","download_rule_versions","download_rule_tasks","download_output_plans","download_completion_log","download_history")){
                var values=new ArrayList<List<Object>>();try(var q=c.createStatement();var rows=q.executeQuery("SELECT * FROM "+table+" ORDER BY rowid")){while(rows.next()){var row=new ArrayList<Object>();for(int n=1;n<=rows.getMetaData().getColumnCount();n++)row.add(rows.getObject(n));values.add(row);}}snapshot.put(table,values);
            }return snapshot;});}
        @Override public void close(){db.close();}
    }
}
