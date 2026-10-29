package io.github.telegramenhance.transfer;

import io.github.telegramenhance.transfer.DownloadModels.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import com.sun.jna.Platform;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef;
import com.sun.jna.platform.win32.WinNT;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

/** Publishes only complete files, in the selected target filesystem, without replacing existing files. */
@Component
public final class DownloadFileStore {
    private static final int CHUNK = 1024 * 1024;
    private final DownloadPaths paths; private final DownloadRepository repository;
    public DownloadFileStore(DownloadPaths paths, DownloadRepository repository) { this.paths = paths; this.repository = repository; }
    public interface Content { byte[] read(long offset, int count); }
    public static final class Stopped extends RuntimeException { }
    public Path directory(Task task) throws IOException {
        return directory(task,Path.of(task.relativeDirectory()));
    }
    private Path directory(Task task,Path relative) throws IOException {
        Path root = paths.root(task.targetRoot());
        if (!DownloadPaths.key(root).equals(DownloadPaths.key(Path.of(task.targetRoot())))) throw new IOException("Bound root changed");
        Files.createDirectories(root); root = root.toRealPath();
        if (relative.isAbsolute() || relative.normalize().startsWith("..")) throw new IOException("Invalid relative directory");
        Path current = root;
        for (Path part : relative) {
            Path child = current.resolve(part);
            if (!Files.exists(child, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(child);
            if (!Files.isDirectory(child) || Files.isSymbolicLink(child) || !child.toRealPath().startsWith(root)) throw new IOException("Target directory changed");
            current = child.toRealPath();
            if(!paths.root(current.toString()).equals(current))throw new IOException("Protected output directory");
        }
        return current;
    }
    public boolean recoverPublished(Task task, Item item, Runnable check) throws IOException {
        Path dir = directory(task), target = item.plan()==null?target(dir,item.relativeFile()):output(task,item.plan().relativePath());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && "FINALIZING".equals(item.state()) && item.sha256() != null && matches(target, item.file().size(), item.sha256(), check)) {
            writeManifest(task,item,item.plan(),check);
            repository.complete(task.accountId(), task.id(), item.id(), item.relativeFile(), target, item.sha256()); cleanupAfterCompleted(dir, item); return true;
        }
        return false;
    }
    public void write(Task task, Item original, Content content, Runnable check) throws IOException {
        Path dir = directory(task); Item item = original;
        Path part = part(dir, item), owner = owner(dir, item);
        if (!Files.exists(owner, LinkOption.NOFOLLOW_LINKS)) Files.writeString(owner, item.token(), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        if (!Files.exists(part, LinkOption.NOFOLLOW_LINKS)) {
            if (item.writtenBytes() != 0) throw new IOException("Partial file missing");
            verifyToken(owner, item);
            Files.createFile(part);
            Files.writeString(owner, item.token() + "\n" + fileKey(part), StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        }
        verifyOwner(owner, item);
        if (Files.isSymbolicLink(part) || !Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Invalid partial file");
        long written = item.writtenBytes();
        if (written < 0 || written > item.file().size() || Files.size(part) < written) throw new IOException("Partial length changed");
        MessageDigest digest = digestPrefix(part, written, check);
        String prefix = digestCopy(digest);
        if (written > 0 && !prefix.equals(item.sha256())) throw new IOException("Partial file changed");
        long remaining = item.file().size() - written;
        if (Files.getFileStore(dir).getUsableSpace() < remaining + 16L * 1024 * 1024) throw new IOException("Insufficient output space");
        repository.progress(task.accountId(), task.id(), item.id(), "WRITING", item.file().size(), written, prefix, item.relativeFile());
        try (var output = FileChannel.open(part, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            verifyOwner(owner, item);
            output.truncate(written); output.position(written);
            while (written < item.file().size()) {
                check.run(); if (!directory(task).equals(dir)) throw new IOException("Target changed"); verifyOwner(owner, item);
                int count = (int) Math.min(CHUNK, item.file().size() - written);
                byte[] data = content.read(written, count);
                if (data == null || data.length != count) throw new IOException("Unexpected source length");
                ByteBuffer buffer = ByteBuffer.wrap(data); while (buffer.hasRemaining()) output.write(buffer);
                digest.update(data); output.force(false); written += count;
                repository.progress(task.accountId(), task.id(), item.id(), "WRITING", item.file().size(), written, digestCopy(digest), item.relativeFile());
            }
            output.force(true);
        }
        String hash = digestCopy(digest); check.run();
        if (Files.size(part) != item.file().size() || !matches(part, item.file().size(), hash, check)) throw new IOException("Output verification failed");
        var plan=repository.outputPlan(task,item,hash);
        String base = plan==null?item.relativeFile():Path.of(plan.relativePath()).getFileName().toString();
        for (int collision = 0; collision < 10000; collision++) {
            String name = collision == 0 ? base : suffix(base, collision + 1);
            String relative=plan==null?null:Path.of(plan.relativePath()).resolveSibling(name).toString();
            Path destination = plan==null?target(dir,name):output(task,relative);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) continue;
            if(plan==null)repository.progress(task.accountId(), task.id(), item.id(), "FINALIZING", item.file().size(), item.file().size(), hash, name);
            else repository.finalizing(task,item,plan,relative);
            check.run(); if (!directory(task).equals(dir)) throw new IOException("Target changed"); verifyOwner(owner, item);
            if(plan!=null&&!output(task,relative).equals(destination))throw new IOException("Output directory changed");
            try {
                // Default MOVE never replaces an existing file. ATOMIC_MOVE may replace, so it is deliberately absent.
                Files.move(part, destination);
            } catch (FileAlreadyExistsException occupied) { continue; }
            writeManifest(task,item,plan==null?null:new OutputPlan(relative,plan.sha256(),plan.duplicate(),plan.manifestRelative(),plan.plannedAt()),check);
            repository.complete(task.accountId(), task.id(), item.id(), name, destination, hash);
            cleanupAfterCompleted(dir, item); return;
        }
        throw new IOException("No available output name");
    }
    private Path output(Task task,String value)throws IOException{
        Path relative=Path.of(value);
        if(relative.isAbsolute()||!relative.normalize().equals(relative)||relative.startsWith("..")||relative.getParent()==null)throw new IOException("Invalid planned output path");
        return target(directory(task,relative.getParent()),relative.getFileName().toString());
    }
    private record SourceRecord(int version,String taskId,String itemId,String sourceLink,String sourceChatId,String sourceMessageId,String postLink,String uniqueId,long size,String sha256,String savedFile,String reason,String duplicateOf,String previousSavedPath,long plannedAt) { }
    private void writeManifest(Task task,Item item,OutputPlan plan,Runnable check)throws IOException{
        if(plan==null||plan.duplicate()==null)return;
        check.run();Path target=output(task,plan.manifestRelative());var ref=item.file().media().message();var duplicate=plan.duplicate();
        String postLink=task.post().mainMessages().stream().map(r->r.link()).filter(s->s!=null&&!s.isBlank()).findFirst().orElse(null);
        byte[] bytes=JsonMapper.builder().build().writeValueAsString(new SourceRecord(1,task.id(),item.id(),ref.link(),ref.chatId(),ref.messageId(),postLink,item.file().uniqueId(),item.file().size(),plan.sha256(),Path.of(task.targetRoot()).resolve(plan.relativePath()).toString(),duplicate.reason(),duplicate.itemId(),duplicate.path(),plan.plannedAt())).getBytes(StandardCharsets.UTF_8);
        if(bytes.length>32768)throw new IOException("Source record too large");
        if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)){verifyManifest(target,bytes);return;}
        Path temporary=target.resolveSibling(".来源-"+UUID.randomUUID()+".tmp");String identity=null;
        try{
            try(var channel=FileChannel.open(temporary,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE,LinkOption.NOFOLLOW_LINKS)){
                identity=fileKey(temporary);var buffer=ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer);channel.force(true);
            }
            check.run();if(!output(task,plan.manifestRelative()).equals(target)||!identity.equals(fileKey(temporary)))throw new IOException("Source record directory changed");
            try{Files.move(temporary,target);}catch(FileAlreadyExistsException race){verifyManifest(target,bytes);}
            verifyManifest(target,bytes);
        }finally{
            if(identity!=null&&Files.exists(temporary,LinkOption.NOFOLLOW_LINKS)&&!Files.isSymbolicLink(temporary)&&identity.equals(fileKey(temporary)))Files.delete(temporary);
        }
    }
    private static void verifyManifest(Path target,byte[] expected)throws IOException{
        if(Files.isSymbolicLink(target)||!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS)||Files.size(target)!=expected.length||!java.util.Arrays.equals(Files.readAllBytes(target),expected))throw new IOException("Existing source record differs; preserved");
    }
    private static String suffix(String name, int index) { int dot = name.lastIndexOf('.'); return dot > 0 ? name.substring(0, dot) + " (" + index + ")" + name.substring(dot) : name + " (" + index + ")"; }
    private static Path target(Path dir, String name) throws IOException {
        Path relative = Path.of(name); if (relative.isAbsolute() || relative.getNameCount() != 1 || name.equals(".") || name.equals("..")) throw new IOException("Invalid output name");
        Path result = dir.resolve(relative).normalize(); if (!result.getParent().equals(dir)) throw new IOException("Output escapes target"); return result;
    }
    private static Path part(Path dir, Item item) { return dir.resolve("." + item.id() + ".part"); }
    private static Path owner(Path dir, Item item) { return dir.resolve("." + item.id() + ".owner"); }
    private static void verifyOwner(Path owner, Item item) throws IOException {
        verifyToken(owner, item); Path part = part(owner.getParent(), item);
        if (Files.exists(part, LinkOption.NOFOLLOW_LINKS) && !Files.readString(owner).equals(item.token() + "\n" + fileKey(part))) throw new IOException("Temporary file identity changed");
    }
    private static void verifyToken(Path owner, Item item) throws IOException {
        if (Files.isSymbolicLink(owner) || !Files.isRegularFile(owner, LinkOption.NOFOLLOW_LINKS) || Files.size(owner) > 256
                || !Files.readString(owner).split("\n", -1)[0].equals(item.token())) throw new IOException("Temporary file ownership unknown");
    }
    static String fileKey(Path part) throws IOException {
        Object key = Files.readAttributes(part, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).fileKey();
        if (key != null) return key.toString();
        if (!Platform.isWindows()) throw new IOException("Filesystem cannot identify partial file");
        // Windows' JDK provider may return null fileKey. FILE_ID_INFO identifies volume + file.
        // https://learn.microsoft.com/windows/win32/api/winbase/nf-winbase-getfileinformationbyhandleex
        var handle = Kernel32.INSTANCE.CreateFile(part.toString(), WinNT.FILE_READ_ATTRIBUTES,
                WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE | WinNT.FILE_SHARE_DELETE, null,
                WinNT.OPEN_EXISTING, WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null);
        if (WinBase.INVALID_HANDLE_VALUE.equals(handle)) throw new IOException("Cannot identify partial file");
        try {
            var info = new WinBase.FILE_ID_INFO();
            if (!Kernel32.INSTANCE.GetFileInformationByHandleEx(handle, WinBase.FileIdInfo, info.getPointer(), new WinDef.DWORD(info.size()))) throw new IOException("Cannot read partial file identity");
            info.read(); byte[] identifier = new byte[info.FileId.Identifier.length];
            for (int i = 0; i < identifier.length; i++) identifier[i] = info.FileId.Identifier[i].byteValue();
            return Long.toUnsignedString(info.VolumeSerialNumber, 16) + ":" + HexFormat.of().formatHex(identifier);
        } finally { Kernel32.INSTANCE.CloseHandle(handle); }
    }
    private static void cleanupAfterCompleted(Path dir, Item item) {
        try { cleanupOwned(dir, item); } catch (IOException ignored) { /* Preserve uncertain remnants; the verified published file is already committed. */ }
    }
    private static void cleanupOwned(Path dir, Item item) throws IOException {
        Path owner = owner(dir, item); if (!Files.exists(owner, LinkOption.NOFOLLOW_LINKS)) return;
        verifyOwner(owner, item); Path part = part(dir, item);
        if (Files.exists(part, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isSymbolicLink(part) || !Files.isRegularFile(part, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Temporary file changed");
            if (Files.size(part) != item.writtenBytes() || item.writtenBytes() > 0 && (item.sha256() == null || !matches(part, item.writtenBytes(), item.sha256(), () -> {}))) return;
            Files.delete(part);
        }
        Files.delete(owner);
    }
    static boolean matches(Path file, long size, String hash, Runnable check) throws IOException {
        return !Files.isSymbolicLink(file) && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) == size
                && digestCopy(digestPrefix(file, size, check)).equals(hash);
    }
    private static MessageDigest digestPrefix(Path file, long length, Runnable check) throws IOException {
        var digest = DownloadPaths.digest(); ByteBuffer buffer = ByteBuffer.allocate(CHUNK);
        try (var input = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long remaining = length;
            while (remaining > 0) {
                check.run(); buffer.clear().limit((int) Math.min(buffer.capacity(), remaining)); int count = input.read(buffer);
                if (count <= 0) throw new IOException("Partial read failed"); digest.update(buffer.array(), 0, count); remaining -= count;
            }
        }
        return digest;
    }
    private static String digestCopy(MessageDigest digest) {
        try { return HexFormat.of().formatHex(((MessageDigest) digest.clone()).digest()); }
        catch (CloneNotSupportedException failure) { throw new IllegalStateException("本机摘要状态不可恢复"); }
    }
}
