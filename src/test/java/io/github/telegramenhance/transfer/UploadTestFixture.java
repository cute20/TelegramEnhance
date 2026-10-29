package io.github.telegramenhance.transfer;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.library.ResourceModels.*;
import io.github.telegramenhance.storage.Database;
import io.github.telegramenhance.transfer.UploadPreviewModels.*;
import io.github.telegramenhance.transfer.UploadPreviewService.Prepared;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;

public final class UploadTestFixture implements AutoCloseable {
    public final AppPaths app;public final Database database;public final UploadRepository repository;public final UploadFiles files;public final Path source,archive;public final Prepared prepared;public final Task task;
    public UploadTestFixture(Path root,int count)throws Exception{this(root,count,root.resolve("archive"));}
    public UploadTestFixture(Path root,int count,Path archive)throws Exception{this(root,count,archive,"DOCUMENT");}
    public UploadTestFixture(Path root,int count,String mediaType)throws Exception{this(root,count,root.resolve("archive"),mediaType);}
    private UploadTestFixture(Path root,int count,Path archive,String mediaType)throws Exception{
        this.archive=archive;source=Files.createDirectories(root.resolve("source"));app=new AppPaths(root.resolve("app"));database=new Database(app);repository=new UploadRepository(database);files=new UploadFiles(app,new DownloadPaths(app),repository);
        String extension=switch(mediaType){case "AUDIO"->"mp3";case "ANIMATION"->"gif";case "PHOTO"->"jpg";case "VIDEO"->"mp4";default->"bin";};
        for(int i=1;i<=count;i++){var p=source.resolve("file"+i+"."+extension);if(mediaType.equals("ANIMATION")||mediaType.equals("PHOTO")){var image=new java.awt.image.BufferedImage(2,1,java.awt.image.BufferedImage.TYPE_INT_RGB);image.setRGB(0,0,i);javax.imageio.ImageIO.write(image,mediaType.equals("PHOTO")?"JPEG":"GIF",p.toFile());}else Files.writeString(p,"synthetic-file-"+i);Files.setLastModifiedTime(p,FileTime.fromMillis(System.currentTimeMillis()-10000));}
        var scan=new UploadDirectoryScanner(new DownloadPaths(app)).scan(source.toString(),archive.toString(),true,()->{});
        var post=new Post("1:-1001:100","1","-1001","Synthetic channel","100","Synthetic post","text","0",List.of(new MessageRef("-1001","100","https://t.me/synthetic/1")),List.of(),"-2001","500",List.of(new MessageRef("-2001","500","")),true,"PARTIAL",List.of(),null,"COMPLETE");
        int maximum=mediaType.equals("ANIMATION")?1:10;
        var groups=new ArrayList<UploadPreviewModels.Group>();for(int i=0;i<scan.files().size();i+=maximum){var members=scan.files().subList(i,Math.min(i+maximum,scan.files().size())).stream().map(f->new Member(f.relativePath(),Path.of(f.relativePath()).getFileName().toString(),f.mediaType(),f.size(),Path.of(f.relativePath()).getFileName().toString())).toList();groups.add(new UploadPreviewModels.Group(groups.size()+1,mediaType,members.size()==1?"SINGLE":"ALBUM",members));}
        prepared=new Prepared(new Input("https://t.me/synthetic/1",source.toString(),archive.toString(),true,"FILENAME"),new Target(post,"Synthetic discussion","MEDIA_ALLOWED_AT_CHECK",0,1),scan,groups);task=repository.create("1",UUID.randomUUID().toString(),"fp",prepared);
    }
    public Task fresh(){return repository.task("1",task.id());}
    public void prepareAndIntent()throws Exception{var t=repository.start("1",task.id());t=repository.next("1");var g=t.groups().getFirst();repository.preparing(t,g);for(var i:g.items())files.stage(t,i,()->{});t=fresh();repository.intent(t,t.groups().getFirst(),"test-run");}
    public void confirm()throws Exception{prepareAndIntent();var t=fresh();var g=t.groups().getFirst();var replies=new ArrayList<Initial>();for(var i:g.items())replies.add(new Initial(Integer.toString(100+i.ordinal()),Math.toIntExact(g.id()),true,null));repository.initial(t,g,replies);for(var i:g.items())repository.event(g.id(),proof(i,g,"100"+i.ordinal()));}
    public Evidence proof(Item item,UploadTaskModels.Group group,String formal){return new Evidence(Integer.toString(100+item.ordinal()),formal,"-2001","500",null,group.mode().equals("ALBUM")?"77":"0",item.file().mediaType(),item.caption(),"remote-"+item.id(),true,true,1);}
    public void close(){database.close();}
}
