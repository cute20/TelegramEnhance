package io.github.telegramenhance.transfer;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.transfer.UploadPreviewModels.FileVersion;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import javax.imageio.ImageIO;
import com.sun.nio.file.ExtendedOpenOption;
import org.springframework.stereotype.Component;

/** Own immutable sending copies, and journalled, no-overwrite archival after formal receipts. */
@Component
public final class UploadFiles {
    private final AppPaths app;private final DownloadPaths paths;private final UploadRepository repository;
    public UploadFiles(AppPaths app,DownloadPaths paths,UploadRepository repository){this.app=app;this.paths=paths;this.repository=repository;}
    public Staged stage(Task task,Item item,Runnable check)throws IOException{
        Path source=source(task,item);verifyOriginal(source,item.file(),check);
        Path dir=directory(app.root().resolve("outgoing"),Path.of(task.id(),item.id()));
        Path output=dir.resolve(source.getFileName().toString());if(output.toString().length()>240)throw new IOException("Staging path too long");
        Path part=dir.resolve("content.twb-part"),owner=dir.resolve("content.twb-owner");
        if(Files.exists(output,LinkOption.NOFOLLOW_LINKS)){
            if(item.staged()!=null){verifyStaged(item.staged(),check);return item.staged();}
            if(!Files.isRegularFile(owner,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(owner)||Files.size(owner)>256||!Files.readString(owner).equals(item.id()+"\n"+DownloadFileStore.fileKey(output))||!matches(output,item.file().size(),item.file().sha256(),check))throw new IOException("Unknown staged file");
            int[] dimensions=dimensions(output,item);var recovered=new Staged(output.toString(),item.file().size(),item.file().sha256(),DownloadFileStore.fileKey(output),dimensions[0],dimensions[1]);repository.staged(task,item,recovered);Files.delete(owner);return recovered;
        }
        checkQuota(item.file().size(),check);copyOwned(source,part,owner,item.id(),item.file().size(),item.file().sha256(),check);verifyOriginal(source,item.file(),check);
        int[] dimensions=dimensions(part,item);
        check.run();Files.move(part,output);var staged=new Staged(output.toString(),item.file().size(),item.file().sha256(),DownloadFileStore.fileKey(output),dimensions[0],dimensions[1]);
        repository.staged(task,item,staged);Files.delete(owner);return staged;
    }
    private static int[] dimensions(Path path,Item item)throws IOException{
        boolean animation=item.file().mediaType().equals("ANIMATION");
        if(!animation&&!item.file().mediaType().equals("PHOTO"))return new int[]{0,0};
        try(var input=ImageIO.createImageInputStream(path.toFile())){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Unsupported image encoding");var reader=readers.next();try{reader.setInput(input,true,true);if(animation&&!reader.getFormatName().equalsIgnoreCase("gif"))throw new IOException("Animation source is not a GIF");int width=reader.getWidth(0),height=reader.getHeight(0);if(width<1||height<1||(long)width*height>16000000||!animation&&item.file().size()>10L*1024*1024)throw new IOException("Image exceeds supported limits");return new int[]{width,height};}finally{reader.dispose();}}
    }
    private void checkQuota(long additional,Runnable check)throws IOException{
        Path root=app.root().resolve("outgoing");long used=0;int entries=0;try(var paths=Files.walk(root)){var iterator=paths.iterator();while(iterator.hasNext()){check.run();Path path=iterator.next();if(++entries>10000)throw new IOException("Outgoing cache entry limit");if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))used=Math.addExact(used,Files.size(path));if(used+additional>16L*1024*1024*1024)throw new IOException("Outgoing cache limit");}}
    }
    public void verifyStaged(Staged staged,Runnable check)throws IOException{Path file=Path.of(staged.path());if(!staged.identity().equals(DownloadFileStore.fileKey(file))||!matches(file,staged.size(),staged.sha256(),check))throw new IOException("Staged file changed");}
    public static final class Lease implements AutoCloseable {private final List<FileChannel> channels=new ArrayList<>();public void close(){for(var channel:channels)try{channel.close();}catch(IOException ignored){}}}
    /** Windows denies concurrent writes/replacement while TDLib is consuming our frozen copies. */
    public Lease lease(List<Staged> staged,Runnable check)throws IOException{
        var lease=new Lease();try{for(var file:staged){check.run();lease.channels.add(FileChannel.open(Path.of(file.path()),StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS,ExtendedOpenOption.NOSHARE_WRITE,ExtendedOpenOption.NOSHARE_DELETE));verifyStaged(file,check);}return lease;}catch(IOException|RuntimeException failure){lease.close();throw failure;}
    }
    public void archive(Task task,Item original,Runnable check)throws IOException{
        if(original.formalId()==null)throw new IOException("Formal receipt required");
        Path source=source(task,original),held=source.resolveSibling(".twb-"+original.id()+".twb-hold");
        Path archive=paths.root(task.prepared().scan().archiveDirectory());if(!DownloadPaths.key(archive).equals(DownloadPaths.key(Path.of(task.prepared().scan().archiveDirectory()))))throw new IOException("Archive root changed");
        Path relative=Path.of(original.archiveRelative()==null?original.file().relativePath():original.archiveRelative());
        Path parent=directory(archive,relative.getParent()==null?Path.of(""):relative.getParent());Path target=parent.resolve(relative.getFileName());String identity=original.archiveIdentity();
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)&&identity!=null&&identity.equals(DownloadFileStore.fileKey(target))){
            if(!matches(target,original.file().size(),original.file().sha256(),check))throw new IOException("Published archive changed");
            finish(task,original,held,check);return;
        }
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){
            String name=relative.getFileName().toString();int dot=name.lastIndexOf('.');String base=dot>0?name.substring(0,dot):name,ext=dot>0?name.substring(dot):"";boolean available=false;
            for(int i=2;i<10000;i++){Path candidate=parent.resolve(base+" ("+i+")"+ext);if(!Files.exists(candidate,LinkOption.NOFOLLOW_LINKS)){target=candidate;relative=archive.relativize(candidate);available=true;break;}}
            if(!available)throw new IOException("Archive names occupied");identity=null;
        }
        repository.archivePlan(task,original,relative.toString(),"PLANNED",identity);check.run();
        if(!Files.exists(held,LinkOption.NOFOLLOW_LINKS)){
            verifyOriginal(source,original.file(),check);Files.move(source,held);
            try{verifyOriginal(held,original.file(),check);}catch(IOException changed){if(!Files.exists(source,LinkOption.NOFOLLOW_LINKS))Files.move(held,source);throw changed;}
        }else verifyOriginal(held,original.file(),check);
        repository.archivePlan(task,original,relative.toString(),"HELD",null);
        if(Files.getFileStore(held).equals(Files.getFileStore(parent))){
            repository.archivePlan(task,original,relative.toString(),"PUBLISHING",original.file().identity());check.run();Files.move(held,target);
        }else{
            Path part=parent.resolve(".twb-"+original.id()+".twb-part"),owner=parent.resolve(".twb-"+original.id()+".twb-owner");
            copyOwned(held,part,owner,original.id(),original.file().size(),original.file().sha256(),check);verifyOriginal(held,original.file(),check);
            repository.archivePlan(task,original,relative.toString(),"PUBLISHING",DownloadFileStore.fileKey(part));check.run();Files.move(part,target);
            if(!matches(target,original.file().size(),original.file().sha256(),check))throw new IOException("Archive verification failed");Files.delete(owner);
        }
        if(!matches(target,original.file().size(),original.file().sha256(),check))throw new IOException("Archive verification failed");
        repository.archivePlan(task,original,relative.toString(),"PUBLISHED",DownloadFileStore.fileKey(target));finish(task,original,held,check);
    }
    private void finish(Task task,Item item,Path held,Runnable check)throws IOException{
        check.run();if(Files.exists(held,LinkOption.NOFOLLOW_LINKS)){verifyOriginal(held,item.file(),check);Files.delete(held);}
        if(item.archiveRelative()!=null&&item.archiveIdentity()!=null){Path target=Path.of(task.prepared().scan().archiveDirectory()).resolve(item.archiveRelative());Path owner=target.getParent().resolve(".twb-"+item.id()+".twb-owner");if(Files.isRegularFile(owner,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(owner)&&Files.size(owner)<=256&&Files.readString(owner).equals(item.id()+"\n"+item.archiveIdentity())&&!Files.exists(target.getParent().resolve(".twb-"+item.id()+".twb-part"),LinkOption.NOFOLLOW_LINKS))Files.delete(owner);}
        repository.archived(task,item);cleanupStage(item);
    }
    private static void cleanupStage(Item item){if(item.staged()==null)return;try{var p=Path.of(item.staged().path());if(item.staged().identity().equals(DownloadFileStore.fileKey(p))&&matches(p,item.staged().size(),item.staged().sha256(),()->{})){Files.delete(p);try{Files.delete(p.getParent());Files.delete(p.getParent().getParent());}catch(IOException keepDirectory){/* Nonempty directories are preserved. */}}}catch(IOException ignored){/* Preserve anything whose ownership cannot be verified. */}}
    private Path source(Task task,Item item)throws IOException{
        Path root=paths.root(task.prepared().scan().sourceDirectory());if(!DownloadPaths.key(root).equals(DownloadPaths.key(Path.of(task.prepared().scan().sourceDirectory()))))throw new IOException("Source root changed");
        Path relative=Path.of(item.file().relativePath());if(relative.isAbsolute()||relative.normalize().startsWith(".."))throw new IOException("Invalid relative source");Path file=root.resolve(relative).normalize();
        if(!file.startsWith(root))throw new IOException("Source escaped root");Path parent=file.getParent();if(!Files.isDirectory(parent)||!parent.toRealPath().equals(parent))throw new IOException("Source parent changed");return file;
    }
    private static Path directory(Path root,Path relative)throws IOException{
        if(relative.isAbsolute()||relative.normalize().startsWith(".."))throw new IOException("Invalid directory");
        Path existing=root;while(!Files.exists(existing,LinkOption.NOFOLLOW_LINKS))existing=existing.getParent();
        if(!existing.toRealPath().equals(existing.toAbsolutePath().normalize()))throw new IOException("Directory alias changed");
        Path current=existing;for(Path part:existing.relativize(root.resolve(relative).normalize())){if(part.toString().isEmpty())continue;current=current.resolve(part);if(!Files.exists(current,LinkOption.NOFOLLOW_LINKS))Files.createDirectory(current);if(!Files.isDirectory(current)||Files.isSymbolicLink(current)||!current.toRealPath().equals(current))throw new IOException("Directory escaped binding");}return current;
    }
    private static void verifyOriginal(Path path,FileVersion file,Runnable check)throws IOException{
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))throw new IOException("Source missing or linked");var attrs=Files.readAttributes(path,BasicFileAttributes.class,LinkOption.NOFOLLOW_LINKS);
        if(attrs.size()!=file.size()||attrs.lastModifiedTime().toMillis()!=file.modifiedMillis()||!file.identity().equals(DownloadFileStore.fileKey(path))||!matches(path,file.size(),file.sha256(),check))throw new IOException("Source version changed");
    }
    private static void copyOwned(Path source,Path part,Path owner,String token,long size,String hash,Runnable check)throws IOException{
        if(!Files.exists(part,LinkOption.NOFOLLOW_LINKS)){
            if(Files.exists(owner,LinkOption.NOFOLLOW_LINKS))throw new IOException("Uncertain temporary ownership");Files.createFile(part);Files.writeString(owner,token+"\n"+DownloadFileStore.fileKey(part),StandardOpenOption.CREATE_NEW);
        }
        if(Files.isSymbolicLink(owner)||!Files.isRegularFile(owner,LinkOption.NOFOLLOW_LINKS)||Files.size(owner)>256||!Files.readString(owner).equals(token+"\n"+DownloadFileStore.fileKey(part)))throw new IOException("Unknown temporary file");
        if(Files.getFileStore(part).getUsableSpace()<size+16L*1024*1024)throw new IOException("Insufficient space");
        try(var input=FileChannel.open(source,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);var output=FileChannel.open(part,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS,ExtendedOpenOption.NOSHARE_WRITE,ExtendedOpenOption.NOSHARE_DELETE)){
            if(!Files.readString(owner).equals(token+"\n"+DownloadFileStore.fileKey(part)))throw new IOException("Temporary file replaced before opening");output.truncate(0);output.position(0);
            ByteBuffer block=ByteBuffer.allocate(1024*1024);long remaining=size;while(remaining>0){check.run();block.clear().limit((int)Math.min(block.capacity(),remaining));int n=input.read(block);if(n<=0)throw new IOException("Source length changed");block.flip();while(block.hasRemaining())output.write(block);remaining-=n;}if(input.read(ByteBuffer.allocate(1))!=-1)throw new IOException("Source grew");output.force(true);
        }
        if(!matches(part,size,hash,check))throw new IOException("Copy verification failed");
    }
    static boolean matches(Path path,long size,String hash,Runnable check)throws IOException{return DownloadFileStore.matches(path,size,hash,check);}
}
