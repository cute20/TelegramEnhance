package io.github.telegramenhance.transfer;

import io.github.telegramenhance.config.AppPaths;
import io.github.telegramenhance.transfer.UploadPreviewModels.FileVersion;
import io.github.telegramenhance.transfer.UploadTaskModels.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.io.ByteArrayInputStream;
import java.util.*;
import javax.imageio.ImageIO;
import com.sun.nio.file.ExtendedOpenOption;
import org.springframework.stereotype.Component;

/** Own immutable sending copies, and journalled, no-overwrite archival after formal receipts. */
@Component
public final class UploadFiles {
    @FunctionalInterface interface CopyWriter { int write(FileChannel output,ByteBuffer bytes)throws IOException; }
    private final AppPaths app;private final DownloadPaths paths;private final UploadRepository repository;private final VideoUploadPreparer videos;private final CopyWriter copyWriter;
    public UploadFiles(AppPaths app,DownloadPaths paths,UploadRepository repository){this(app,paths,repository,new VideoUploadPreparer());}
    @org.springframework.beans.factory.annotation.Autowired
    public UploadFiles(AppPaths app,DownloadPaths paths,UploadRepository repository,VideoUploadPreparer videos){this(app,paths,repository,videos,FileChannel::write);}
    UploadFiles(AppPaths app,DownloadPaths paths,UploadRepository repository,VideoUploadPreparer videos,CopyWriter copyWriter){this.app=app;this.paths=paths;this.repository=repository;this.videos=Objects.requireNonNull(videos);this.copyWriter=Objects.requireNonNull(copyWriter);}
    public Staged stage(Task task,Item item,Runnable check)throws IOException{
        Path source=source(task,item);verifyOriginal(source,item.file(),check);
        Path dir=directory(app.root().resolve("outgoing"),Path.of(task.id(),item.id()));
        Path output=dir.resolve(source.getFileName().toString());if(output.toString().length()>240)throw new IOException("Staging path too long");
        Path part=dir.resolve("content.twb-part"),owner=dir.resolve("content.twb-owner");
        if(Files.exists(output,LinkOption.NOFOLLOW_LINKS)){
            if(item.staged()!=null){
                if(!output.equals(Path.of(item.staged().path())))throw new IOException("Staged path changed");
                verifyStaged(item.staged(),check);
                if(!item.file().mediaType().equals("VIDEO")||item.staged().video()!=null){cleanupStageOwners(item,item.staged());return item.staged();}
                return registerStage(task,item,withVideo(item.staged(),item,check),check);
            }
            if(!owned(owner,item.id(),output)||!matches(output,item.file().size(),item.file().sha256(),check))throw new IOException("Unknown staged file");
            int[] dimensions=dimensions(output,item);var recovered=new Staged(output.toString(),item.file().size(),item.file().sha256(),DownloadFileStore.fileKey(output),dimensions[0],dimensions[1]);
            return registerStage(task,item,withVideo(recovered,item,check),check);
        }
        checkQuota(item.file().size(),check);copyOwned(source,part,owner,item.id(),item.file().size(),item.file().sha256(),check);verifyOriginal(source,item.file(),check);
        int[] dimensions=dimensions(part,item);
        check.run();Files.move(part,output);var staged=new Staged(output.toString(),item.file().size(),item.file().sha256(),DownloadFileStore.fileKey(output),dimensions[0],dimensions[1]);
        return registerStage(task,item,withVideo(staged,item,check),check);
    }
    private Staged registerStage(Task task,Item item,Staged staged,Runnable check)throws IOException{
        verifyStaged(staged,check);check.run();repository.staged(task,item,staged);cleanupStageOwners(item,staged);return staged;
    }
    /** A legacy never-sent copy is upgraded before intent; legacy pending recovery can still lease null metadata. */
    private Staged withVideo(Staged staged,Item item,Runnable check)throws IOException{
        if(!item.file().mediaType().equals("VIDEO"))return staged;
        try(var ignored=lease(List.of(staged),check)){
            VideoUploadPreparer.PreparedVideo prepared;
            try{prepared=videos.prepare(Path.of(staged.path()),check);}catch(IOException failure){throw new IllegalArgumentException("无法读取视频信息或生成封面，请检查 FFmpeg、FFprobe 与视频格式；尚未发送",failure);}
            check.run();
            byte[] jpeg=prepared.jpeg();
            if(prepared.duration()<1||prepared.width()<1||prepared.height()<1||prepared.width()>32768||prepared.height()>32768||jpeg==null||jpeg.length<1||jpeg.length>128*1024||prepared.thumbnailWidth()<1||prepared.thumbnailWidth()>320||prepared.thumbnailHeight()<1||prepared.thumbnailHeight()>320)throw new IOException("视频元数据或封面无效，未发送");
            try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(jpeg))){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("视频封面无效，未发送");var reader=readers.next();try{reader.setInput(input,true,true);if(!reader.getFormatName().equalsIgnoreCase("JPEG")||reader.getWidth(0)!=prepared.thumbnailWidth()||reader.getHeight(0)!=prepared.thumbnailHeight())throw new IOException("视频封面无效，未发送");}finally{reader.dispose();}}
            var thumbnail=writeThumbnail(Path.of(staged.path()).getParent(),item.id(),jpeg,prepared.thumbnailWidth(),prepared.thumbnailHeight(),check);
            var result=new Staged(staged.path(),staged.size(),staged.sha256(),staged.identity(),prepared.width(),prepared.height(),new VideoDetails(prepared.duration(),prepared.supportsStreaming(),thumbnail));
            verifyStaged(result,check);return result;
        }
    }
    private VideoThumbnail writeThumbnail(Path directory,String token,byte[] bytes,int width,int height,Runnable check)throws IOException{
        Path output=directory.resolve("thumbnail.twb.jpg"),part=directory.resolve("thumbnail.twb-part"),owner=directory.resolve("thumbnail.twb-owner");
        String hash=HexFormat.of().formatHex(DownloadPaths.digest().digest(bytes));
        if(Files.exists(output,LinkOption.NOFOLLOW_LINKS)){
            if(!owned(owner,token,output)||!matches(output,bytes.length,hash,check))throw new IOException("Unknown video thumbnail");
            return new VideoThumbnail(output.toString(),bytes.length,hash,DownloadFileStore.fileKey(output),width,height);
        }
        checkQuota(bytes.length,check);
        if(!Files.exists(part,LinkOption.NOFOLLOW_LINKS)){
            if(Files.exists(owner,LinkOption.NOFOLLOW_LINKS))throw new IOException("Uncertain thumbnail ownership");
            Files.createFile(part);Files.writeString(owner,checkpoint(token,part,0,emptyHash()),StandardOpenOption.CREATE_NEW);
        }
        if(!owned(owner,token,part))throw new IOException("Unknown thumbnail temporary file");
        try(var channel=FileChannel.open(part,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS,ExtendedOpenOption.NOSHARE_WRITE,ExtendedOpenOption.NOSHARE_DELETE)){
            if(!owned(owner,token,part))throw new IOException("Thumbnail replaced before opening");
            check.run();channel.truncate(0);channel.position(0);ByteBuffer data=ByteBuffer.wrap(bytes);
            try{while(data.hasRemaining()){check.run();channel.write(data);}channel.force(true);}
            finally{saveCheckpoint(channel,owner,token,part,data.position(),DownloadPaths.digest().digest(Arrays.copyOf(bytes,data.position())));}
        }
        if(!matches(part,bytes.length,hash,check))throw new IOException("Thumbnail verification failed");
        check.run();Files.move(part,output);
        if(!owned(owner,token,output)||!matches(output,bytes.length,hash,check))throw new IOException("Thumbnail publication changed");
        return new VideoThumbnail(output.toString(),bytes.length,hash,DownloadFileStore.fileKey(output),width,height);
    }
    private record Owner(String text,String identity,Long size,String hash) { }
    private static Owner owner(Path path,String token)throws IOException{
        if(Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>256)return null;
        String text=Files.readString(path);String[] fields=text.split("\n",-1);
        if((fields.length!=2&&fields.length!=4)||!fields[0].equals(token)||fields[1].isBlank())return null;
        if(fields.length==2)return new Owner(text,fields[1],null,null);
        try{long size=Long.parseLong(fields[2]);return size>=0&&fields[3].matches("[0-9a-f]{64}")?new Owner(text,fields[1],size,fields[3]):null;}
        catch(NumberFormatException invalid){return null;}
    }
    private static boolean owned(Path owner,String token,Path content)throws IOException{var proof=owner(owner,token);return proof!=null&&proof.identity().equals(DownloadFileStore.fileKey(content));}
    private static String emptyHash(){return HexFormat.of().formatHex(DownloadPaths.digest().digest());}
    private static String checkpoint(String token,Path file,long size,String hash)throws IOException{return token+"\n"+DownloadFileStore.fileKey(file)+"\n"+size+"\n"+hash;}
    /** Record even an interrupted copy's exact owned prefix while its write lease is still held. */
    private static void saveCheckpoint(FileChannel lease,Path owner,String token,Path file,long written,byte[] hash){
        boolean interrupted=Thread.interrupted();
        try{
            if(!lease.isOpen()||!owned(owner,token,file))return;
            long actual=Files.size(file);
            if(actual!=written){
                // A filesystem can write a partial block and then throw (for example disk full).
                // The caller still holds the exclusive write/delete lease: hash precisely that
                // owned prefix instead of leaving a stale zero/previous-block checkpoint.
                var digest=DownloadPaths.digest();var buffer=ByteBuffer.allocate(1024*1024);
                try(var input=FileChannel.open(file,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
                    for(int read;(read=input.read(buffer))!=-1;){if(read>0)digest.update(buffer.array(),0,read);buffer.clear();}
                }
                if(!lease.isOpen()||!owned(owner,token,file)||Files.size(file)!=actual)return;
                written=actual;hash=digest.digest();
            }
            byte[] bytes=checkpoint(token,file,written,HexFormat.of().formatHex(hash)).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try(var channel=FileChannel.open(owner,StandardOpenOption.WRITE,StandardOpenOption.TRUNCATE_EXISTING,LinkOption.NOFOLLOW_LINKS)){
                var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
        }catch(IOException ignored){/* An unjournalled or changed temporary file is preserved during collection. */}
        finally{if(interrupted)Thread.currentThread().interrupt();}
    }
    private static void cleanupStageOwners(Item item,Staged staged){
        Path main=Path.of(staged.path());try{Path owner=main.getParent().resolve("content.twb-owner");if(owned(owner,item.id(),main))Files.delete(owner);}catch(IOException ignored){/* An unverified marker is preserved. */}
        if(staged.video()!=null&&staged.video().thumbnail()!=null)try{Path thumbnail=thumbnailPath(staged),owner=thumbnail.getParent().resolve("thumbnail.twb-owner");if(owned(owner,item.id(),thumbnail))Files.delete(owner);}catch(IOException ignored){/* An unverified marker is preserved. */}
    }
    private static int[] dimensions(Path path,Item item)throws IOException{
        boolean animation=item.file().mediaType().equals("ANIMATION");
        if(!animation&&!item.file().mediaType().equals("PHOTO"))return new int[]{0,0};
        try(var input=ImageIO.createImageInputStream(path.toFile())){var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())throw new IOException("Unsupported image encoding");var reader=readers.next();try{reader.setInput(input,true,true);if(animation&&!reader.getFormatName().equalsIgnoreCase("gif"))throw new IOException("Animation source is not a GIF");int width=reader.getWidth(0),height=reader.getHeight(0);if(width<1||height<1||(long)width*height>16000000||!animation&&item.file().size()>10L*1024*1024)throw new IOException("Image exceeds supported limits");return new int[]{width,height};}finally{reader.dispose();}}
    }
    private void checkQuota(long additional,Runnable check)throws IOException{
        Path root=app.root().resolve("outgoing");long used=0;int entries=0;try(var paths=Files.walk(root)){var iterator=paths.iterator();while(iterator.hasNext()){check.run();Path path=iterator.next();if(++entries>10000)throw new IOException("Outgoing cache entry limit");if(Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))used=Math.addExact(used,Files.size(path));if(used+additional>16L*1024*1024*1024)throw new IOException("Outgoing cache limit");}}
    }
    public void verifyStaged(Staged staged,Runnable check)throws IOException{
        Path file=Path.of(staged.path());if(!staged.identity().equals(DownloadFileStore.fileKey(file))||!matches(file,staged.size(),staged.sha256(),check))throw new IOException("Staged file changed");
        if(staged.video()!=null){Path thumbnail=thumbnailPath(staged);var saved=staged.video().thumbnail();if(!saved.identity().equals(DownloadFileStore.fileKey(thumbnail))||!matches(thumbnail,saved.size(),saved.sha256(),check))throw new IOException("Video thumbnail changed");}
    }
    private static Path thumbnailPath(Staged staged)throws IOException{
        var video=staged.video();var thumbnail=video==null?null:video.thumbnail();
        if(video==null||video.duration()<1||staged.width()<1||staged.height()<1||staged.width()>32768||staged.height()>32768||thumbnail==null||thumbnail.path()==null||thumbnail.size()<1||thumbnail.size()>128*1024||thumbnail.width()<1||thumbnail.width()>320||thumbnail.height()<1||thumbnail.height()>320||thumbnail.sha256()==null||!thumbnail.sha256().matches("[0-9a-f]{64}")||thumbnail.identity()==null||thumbnail.identity().isBlank()||thumbnail.identity().equals(staged.identity()))throw new IOException("Video metadata changed");
        Path main=Path.of(staged.path()),path=Path.of(thumbnail.path());
        if(!path.isAbsolute()||!path.equals(path.normalize())||!Objects.equals(path.getParent(),main.getParent())||!path.getFileName().toString().equals("thumbnail.twb.jpg"))throw new IOException("Thumbnail escaped staging directory");
        return path;
    }
    public static final class Lease implements AutoCloseable {private final List<FileChannel> channels=new ArrayList<>();public void close(){for(var channel:channels)try{channel.close();}catch(IOException ignored){}}}
    /** Windows denies concurrent writes/replacement while TDLib is consuming our frozen copies. */
    public Lease lease(List<Staged> staged,Runnable check)throws IOException{
        var lease=new Lease();try{for(var file:staged){check.run();lease.channels.add(hold(Path.of(file.path())));if(file.video()!=null)lease.channels.add(hold(thumbnailPath(file)));verifyStaged(file,check);}return lease;}catch(IOException|RuntimeException failure){lease.close();throw failure;}
    }
    private static FileChannel hold(Path path)throws IOException{return FileChannel.open(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS,ExtendedOpenOption.NOSHARE_WRITE,ExtendedOpenOption.NOSHARE_DELETE);}
    /** Collect terminal, never-sent tasks on the same worker that stages/sends files. Never walk source trees. */
    public void cleanupCancelled(Task task,Runnable check){
        var saved=repository.cancelledForCleanup(task.accountId(),task.id());if(saved==null)return;
        for(var group:saved.groups())for(var item:group.items()){
            check.run();
            try{
                Path dir=cancelledDirectory(saved,item);if(dir==null)continue;
                Path main=dir.resolve(Path.of(item.file().relativePath()).getFileName());var staged=item.staged();
                if(staged!=null&&main.equals(Path.of(staged.path())))
                    deleteVerified(main,staged.identity(),staged.size(),staged.sha256(),check);
                if(staged!=null&&staged.video()!=null){
                    Path thumbnail=thumbnailPath(staged);var proof=staged.video().thumbnail();
                    if(thumbnail.getParent().equals(dir))deleteVerified(thumbnail,proof.identity(),proof.size(),proof.sha256(),check);
                }
                cleanupMarker(dir.resolve("content.twb-owner"),item.id(),List.of(main,dir.resolve("content.twb-part")),item.file(),check);
                if(item.file().mediaType().equals("VIDEO"))cleanupMarker(dir.resolve("thumbnail.twb-owner"),item.id(),
                        List.of(dir.resolve("thumbnail.twb.jpg"),dir.resolve("thumbnail.twb-part")),null,check);
                try{Files.delete(dir);Files.delete(dir.getParent());}catch(DirectoryNotEmptyException keepUnknownFiles){/* Only empty directories are removed. */}
            }catch(IOException|java.nio.file.InvalidPathException unverified){/* Never delete changed, aliased, unknown or locked content. */}
        }
    }
    private Path cancelledDirectory(Task task,Item item)throws IOException{
        if(!task.id().matches("[0-9a-f-]{36}")||!item.id().matches("[0-9a-f-]{36}"))throw new IOException("Invalid cache identity");
        Path dir=app.root().resolve("outgoing").resolve(task.id()).resolve(item.id());
        for(Path path:List.of(app.root(),app.root().resolve("outgoing"),dir.getParent(),dir)){
            if(!Files.exists(path,LinkOption.NOFOLLOW_LINKS))return null;
            if(!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(path)||!path.toRealPath().equals(path))throw new IOException("Cache directory changed");
        }
        return dir;
    }
    private static boolean deleteVerified(Path file,String identity,long size,String hash,Runnable check)throws IOException{
        if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS))return true;
        if(identity==null||hash==null||size<0||!hash.matches("[0-9a-f]{64}")||Files.isSymbolicLink(file)
                ||!identity.equals(DownloadFileStore.fileKey(file))||!matches(file,size,hash,check))return false;
        check.run();if(!identity.equals(DownloadFileStore.fileKey(file)))return false;
        Files.delete(file);return true;
    }
    private static void cleanupMarker(Path marker,String token,List<Path> candidates,FileVersion fallback,Runnable check)throws IOException{
        var proof=owner(marker,token);if(proof==null)return;
        for(var file:candidates){
            if(!Files.exists(file,LinkOption.NOFOLLOW_LINKS)||Files.isSymbolicLink(file)||!proof.identity().equals(DownloadFileStore.fileKey(file)))continue;
            // Old two-line main markers can still be checked against the frozen source version.
            // Old unregistered thumbnails have no content digest, so retain them rather than guess.
            long size=proof.size()!=null?proof.size():fallback==null?-1:fallback.size();
            String hash=proof.hash()!=null?proof.hash():fallback==null?null:fallback.sha256();
            if(!deleteVerified(file,proof.identity(),size,hash,check))return;
        }
        // If the file was already removed before shutdown, only remove this same owned marker.
        var current=owner(marker,token);if(current!=null&&current.text().equals(proof.text())
            &&candidates.stream().noneMatch(p->Files.exists(p,LinkOption.NOFOLLOW_LINKS)))Files.delete(marker);
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
        if(item.archiveRelative()!=null&&item.archiveIdentity()!=null){Path target=Path.of(task.prepared().scan().archiveDirectory()).resolve(item.archiveRelative());Path owner=target.getParent().resolve(".twb-"+item.id()+".twb-owner");var proof=owner(owner,item.id());if(proof!=null&&proof.identity().equals(item.archiveIdentity())&&!Files.exists(target.getParent().resolve(".twb-"+item.id()+".twb-part"),LinkOption.NOFOLLOW_LINKS))Files.delete(owner);}
        repository.archived(task,item);cleanupStage(item);
    }
    private static void cleanupStage(Item item){
        var staged=item.staged();if(staged==null)return;
        cleanupStageOwners(item,staged);
        if(staged.video()!=null)try{Path thumbnail=thumbnailPath(staged);var saved=staged.video().thumbnail();if(saved.identity().equals(DownloadFileStore.fileKey(thumbnail))&&matches(thumbnail,saved.size(),saved.sha256(),()->{}))Files.delete(thumbnail);}catch(IOException ignored){/* Preserve an altered or unowned thumbnail. */}
        try{var p=Path.of(staged.path());if(staged.identity().equals(DownloadFileStore.fileKey(p))&&matches(p,staged.size(),staged.sha256(),()->{}))Files.delete(p);try{Files.delete(p.getParent());Files.delete(p.getParent().getParent());}catch(IOException keepDirectory){/* Nonempty directories are preserved. */}}catch(IOException ignored){/* Preserve anything whose ownership cannot be verified. */}
    }
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
    private void copyOwned(Path source,Path part,Path owner,String token,long size,String hash,Runnable check)throws IOException{
        if(!Files.exists(part,LinkOption.NOFOLLOW_LINKS)){
            if(Files.exists(owner,LinkOption.NOFOLLOW_LINKS))throw new IOException("Uncertain temporary ownership");Files.createFile(part);Files.writeString(owner,checkpoint(token,part,0,emptyHash()),StandardOpenOption.CREATE_NEW);
        }
        if(!owned(owner,token,part))throw new IOException("Unknown temporary file");
        if(Files.getFileStore(part).getUsableSpace()<size+16L*1024*1024)throw new IOException("Insufficient space");
        try(var input=FileChannel.open(source,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);var output=FileChannel.open(part,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS,ExtendedOpenOption.NOSHARE_WRITE,ExtendedOpenOption.NOSHARE_DELETE)){
            if(!owned(owner,token,part))throw new IOException("Temporary file replaced before opening");output.truncate(0);output.position(0);
            ByteBuffer block=ByteBuffer.allocate(1024*1024);long remaining=size,written=0;var digest=DownloadPaths.digest();
            try{while(remaining>0){check.run();block.clear().limit((int)Math.min(block.capacity(),remaining));int n=input.read(block);if(n<=0)throw new IOException("Source length changed");block.flip();while(block.hasRemaining()){int before=block.position(),count=copyWriter.write(output,block);if(count>0){digest.update(block.array(),before,count);written+=count;}}remaining-=n;}if(input.read(ByteBuffer.allocate(1))!=-1)throw new IOException("Source grew");output.force(true);}
            finally{saveCheckpoint(output,owner,token,part,written,digest.digest());}
        }
        if(!matches(part,size,hash,check))throw new IOException("Copy verification failed");
    }
    static boolean matches(Path path,long size,String hash,Runnable check)throws IOException{return DownloadFileStore.matches(path,size,hash,check);}
}
