package io.github.telegramenhance.rules;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.Post;
import io.github.telegramenhance.library.ResourceService;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.telegram.TelegramService;
import io.github.telegramenhance.telegram.UploadTargetReader;
import io.github.telegramenhance.transfer.*;
import io.github.telegramenhance.rules.RuleModels.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuleTest {
    @TempDir Path root;
    static final String LINK="https://t.me/synthetic/1";
    static Window all(){return new Window("ALL_DAY",null,null,"Asia/Shanghai");}
    static Instant time(String local){return LocalDateTime.parse("2026-10-27T"+local).atZone(RuleModels.ZONE).toInstant();}
    @Test void windowsIncludeStartExcludeEndAndCrossMidnight(){
        var day=new Window("WINDOW","09:00","17:00","Asia/Shanghai");assertFalse(day.allows(time("08:59")));assertTrue(day.allows(time("09:00")));assertFalse(day.allows(time("17:00")));assertEquals(time("09:00").toEpochMilli(),day.nextAllowed(time("08:59")));
        var night=new Window("WINDOW","22:00","08:00","Asia/Shanghai");assertTrue(night.allows(time("23:00")));assertTrue(night.allows(time("07:59")));assertFalse(night.allows(time("08:00")));assertFalse(night.allows(time("21:59")));assertEquals(time("22:00").toEpochMilli(),night.nextAllowed(time("12:00")));assertTrue(all().allows(time("12:00")));
    }
    @Test void malformedWindowsAreRejected(){for(String[] v:new String[][]{{null,null,null},{"WINDOW","08:00","08:00"},{"WINDOW","24:00","01:00"},{"WINDOW","8:00","09:00"}})assertThrows(IllegalArgumentException.class,()->new Window(v[0],v[1],v[2],"Asia/Shanghai"));assertThrows(IllegalArgumentException.class,()->new Window("ALL_DAY",null,null,"UTC"));}
    @Test void createIsPausedIdempotentAndAccountScoped()throws Exception{try(var f=new Fixture()){
        String op=UUID.randomUUID().toString();var a=f.service.create(new RuleService.Create(op,f.input(all()))).rule();assertFalse(a.enabled());assertEquals(a.id(),f.service.create(new RuleService.Create(op,f.input(all()))).rule().id());assertNull(f.repo.due("1",Long.MAX_VALUE));
        assertThrows(IllegalArgumentException.class,()->f.repo.get("2",a.id()));assertEquals(0,f.repo.list("2").size());assertThrows(IllegalArgumentException.class,()->f.repo.create("1",op,f.input(new Window("WINDOW","22:00","08:00","Asia/Shanghai")),1));assertFalse(Files.exists(f.archive));
    }}
    @Test void versionsRemainImmutableAndStaleObservationsAndWritesAreDiscarded()throws Exception{try(var f=new Fixture()){
        var a=f.create(all());var enabled=f.repo.enabled("1",a.id(),a.revision(),true,2);assertFalse(f.repo.observe(a,f.scan(),null,false,3));assertThrows(IllegalArgumentException.class,()->f.repo.enabled("1",a.id(),a.revision(),true,4));
        var b=f.repo.update("1",a.id(),enabled.revision(),f.input(new Window("WINDOW","22:00","08:00","Asia/Shanghai")),5);assertEquals(2,b.version());assertTrue(b.enabled());assertNull(b.observation());assertFalse(f.repo.observe(enabled,f.scan(),null,true,6));assertEquals(all(),f.repo.versions("1",a.id()).getLast().input().window());assertEquals(2,f.repo.versions("1",a.id()).size());
    }}
    @Test void scansTrackAddedChangedUnobservedAndFailureKeepsSuccessTimestamp()throws Exception{try(var f=new Fixture()){
        f.file("one.txt","one");f.file("two.txt","two");var a=f.create(all());var first=f.service.observe(a.id(),new RuleService.Position(a.revision())).get();assertFalse(first.scanning());assertEquals(2,first.rule().observation().added());
        f.file("one.txt","changed");Files.delete(f.source.resolve("two.txt"));f.file("three.txt","three");var next=f.service.observe(a.id(),new RuleService.Position(a.revision())).get().rule().observation();assertEquals(1,next.added());assertEquals(1,next.changed());assertEquals(1,next.unobserved());
        assertTrue(f.repo.observe(a,null,"failed",false,999));var failed=f.repo.get("1",a.id()).observation();assertEquals("ERROR",failed.outcome());assertEquals(next.scanAt(),failed.scanAt());assertEquals(999,failed.checkedAt());assertEquals(next.scan(),failed.scan());assertFalse(Files.exists(f.archive));verifyNoInteractions(f.targets);
    }}
    @Test void scheduledDetectionWaitsForWindowButManualScanWorksOutsideIt()throws Exception{try(var f=new Fixture()){
        f.file("one.txt","one");var a=f.create(new Window("WINDOW","22:00","08:00","Asia/Shanghai"));a=f.repo.enabled("1",a.id(),a.revision(),true,1);assertEquals("WAITING_WINDOW",f.service.get(a.id()).state());f.service.monitorOnce(()->{}).get();assertNull(f.repo.get("1",a.id()).observation());assertEquals(time("22:00").toEpochMilli(),f.repo.get("1",a.id()).nextScanAt());
        assertEquals(1,f.service.observe(a.id(),new RuleService.Position(a.revision())).get().rule().observation().scan().files().size());verifyNoInteractions(f.targets);
    }}
    @Test void scheduledObservationIsReadOnlyAndNextDueIsThrottled()throws Exception{try(var f=new Fixture()){
        f.file("clip.mp4","video");var a=f.create(all());a=f.repo.enabled("1",a.id(),a.revision(),true,1);f.service.monitorOnce(()->{}).get();assertEquals(1,f.repo.get("1",a.id()).observation().scan().files().size());assertNull(f.repo.due("1",f.clock.millis()));verifyNoInteractions(f.targets);assertFalse(Files.exists(f.archive));
    }}
    @Test void previewUsesExistingTargetPermissionsAndSeparatesMediaWithoutCreatingATask()throws Exception{try(var f=new Fixture()){
        f.file("picture.jpg","picture");f.file("clip.mp4","video");f.file("doc.txt","document");var a=f.create(all());var p=f.service.preview(a.id(),new RuleService.Position(a.revision())).get().preview();assertEquals("PREVIEW_ONLY",p.state());assertEquals(List.of("PHOTO","VIDEO","DOCUMENT"),p.groups().stream().map(UploadPreviewModels.Group::mediaType).toList());verify(f.targets).read(LINK,"1",1,Set.of("PHOTO","VIDEO","DOCUMENT"));assertFalse(Files.exists(f.archive));assertEquals(0,f.db.read(c->{try(var s=c.createStatement();var r=s.executeQuery("SELECT COUNT(*) FROM upload_tasks")){r.next();return r.getInt(1);}}).intValue());
    }}
    @Test void pauseOrSessionChangeRejectsInFlightResult()throws Exception{try(var f=new Fixture()){
        var a=f.create(all());var pending=new CompletableFuture<UploadPreviewModels.Scan>();var previews=mock(UploadPreviewService.class);when(previews.observeDirectories(any(),any())).thenReturn(pending);var service=new RuleService(f.repo,f.scanner,previews,f.telegram,f.clock);var work=service.observe(a.id(),new RuleService.Position(a.revision()));f.repo.enabled("1",a.id(),a.revision(),false,2);pending.complete(f.scan());assertThrows(ExecutionException.class,work::get);assertNull(f.repo.get("1",a.id()).observation());
        var b=f.repo.get("1",a.id());var pending2=new CompletableFuture<UploadPreviewModels.Scan>();when(previews.observeDirectories(any(),any())).thenReturn(pending2);var work2=service.observe(b.id(),new RuleService.Position(b.revision()));when(f.telegram.sessionGeneration()).thenReturn(2L);pending2.complete(f.scan());assertThrows(ExecutionException.class,work2::get);assertNull(f.repo.get("1",a.id()).observation());
    }}
    @Test void invalidTargetAndDriveRootFailBeforeSaving()throws Exception{try(var f=new Fixture()){
        var input=f.input(all());assertThrows(IllegalArgumentException.class,()->f.service.create(new RuleService.Create(UUID.randomUUID().toString(),new Input("x","https://example.com/1",input.sourceDirectory(),input.archiveDirectory(),true,"FILENAME",all()))));assertThrows(IllegalArgumentException.class,()->f.service.create(new RuleService.Create(UUID.randomUUID().toString(),new Input("x",LINK,f.source.getRoot().toString(),input.archiveDirectory(),true,"FILENAME",all()))));assertTrue(f.repo.list("1").isEmpty());
    }}
    @Test void ruleArchiveAlwaysUsesSiblingSuffixWithoutCreatingIt()throws Exception{try(var f=new Fixture()){
        var a=f.service.create(new RuleService.Create(UUID.randomUUID().toString(),new Input("x",LINK,f.source.toString(),f.source.toString(),true,"FILENAME",all()))).rule();
        assertEquals(f.source.resolveSibling("source-已上传").toString(),a.input().archiveDirectory());assertFalse(Files.exists(Path.of(a.input().archiveDirectory())));
    }}
    @Test void oldArchiveConfigOnlyChangesWhenNewVersionIsSaved()throws Exception{try(var f=new Fixture()){
        var old=f.repo.create("1",UUID.randomUUID().toString(),new Input("old",LINK,f.source.toString(),root.resolve("legacy-archive").toString(),true,"FILENAME",all()),1);
        assertEquals(old.input(),f.service.get(old.id()).rule().input());var next=f.service.update(old.id(),new RuleService.Update(old.revision(),old.input())).rule();
        assertEquals(f.archive.toString(),next.input().archiveDirectory());assertEquals(old.input(),f.repo.versions("1",old.id()).getLast().input());assertFalse(Files.exists(f.archive));
    }}
    final class Fixture implements AutoCloseable{
        final Path source,archive;final Database db;final RuleRepository repo;final TelegramService telegram=mock(TelegramService.class);final ResourceService reads=mock(ResourceService.class);final UploadTargetReader targets=mock(UploadTargetReader.class);final UploadDirectoryScanner scanner;final UploadPreviewService previews;final RuleService service;final Clock clock=Clock.fixed(time("12:00"),ZoneOffset.UTC);
        Fixture()throws Exception{source=Files.createDirectories(root.resolve("source"));archive=root.resolve("source-已上传");var paths=new AppPaths(root.resolve("data"));db=new Database(paths);repo=new RuleRepository(db);scanner=new UploadDirectoryScanner(new DownloadPaths(paths));when(telegram.accountId()).thenReturn("1");when(telegram.sessionGeneration()).thenReturn(1L);when(reads.readWork(any())).thenAnswer(i->CompletableFuture.completedFuture(((Supplier<?>)i.getArgument(0)).get()));
            var post=new Post("p","1","-1001","channel","100","title","text","0",List.of(),List.of(),"-2001","500",List.of(),true,"PARTIAL",List.of(),null,"COMPLETE");when(targets.read(eq(LINK),eq("1"),eq(1L),anySet())).thenReturn(new UploadPreviewModels.Target(post,"discussion","MEDIA_ALLOWED_AT_CHECK",0,1));previews=new UploadPreviewService(telegram,reads,targets,scanner);service=new RuleService(repo,scanner,previews,telegram,clock);
        }
        Input input(Window window){return new Input("test",LINK,source.toString(),archive.toString(),true,"FILENAME",window);}
        Rule create(Window window){return service.create(new RuleService.Create(UUID.randomUUID().toString(),input(window))).rule();}
        void file(String name,String data)throws Exception{var path=source.resolve(name);Files.writeString(path,data);Files.setLastModifiedTime(path,FileTime.fromMillis(System.currentTimeMillis()-10000));}
        UploadPreviewModels.Scan scan(){return scanner.scan(source.toString(),archive.toString(),true,()->{});}
        public void close(){previews.close();db.close();}
    }
}
