package io.github.telegramenhance.transfer;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Reads an already frozen local video; only its small JPEG cover is encoded. */
@Component
public class VideoUploadPreparer {
    static final int PROBE_LIMIT = 64 * 1024, JPEG_LIMIT = 128 * 1024, THUMBNAIL_SIDE = 320;
    static final long BUDGET_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final String FORMATS = "mov,matroska,webm,avi,mpegts,mpeg,mpegvideo,asf,flv,ogg,rm,h264,hevc";
    private static final Set<String> MP4_BRANDS = Set.of("isom", "iso2", "iso3", "iso4", "iso5", "iso6", "iso7", "iso8", "iso9", "mp41", "mp42", "avc1", "M4V ");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private final Runner runner;
    private final LongSupplier nanos;
    private final ToolLocator tools;

    @FunctionalInterface interface Runner {
        byte[] run(List<String> command, int maximumBytes, long deadlineNanos, Runnable check) throws IOException;
    }
    @FunctionalInterface interface ToolLocator { Path find(String name) throws IOException; }

    public VideoUploadPreparer() {
        this(System::nanoTime, VideoUploadPreparer::findTool);
    }
    private VideoUploadPreparer(LongSupplier nanos, ToolLocator tools) {
        this(new ProcessRunner(nanos), nanos, tools);
    }
    VideoUploadPreparer(Runner runner, LongSupplier nanos, ToolLocator tools) {
        this.runner = java.util.Objects.requireNonNull(runner);
        this.nanos = java.util.Objects.requireNonNull(nanos);
        this.tools = java.util.Objects.requireNonNull(tools);
    }

    public record PreparedVideo(int duration, int width, int height, boolean supportsStreaming,
                                byte[] jpeg, int thumbnailWidth, int thumbnailHeight) {
        public PreparedVideo {
            if (duration < 1 || width < 1 || height < 1 || jpeg == null || jpeg.length == 0 || jpeg.length > JPEG_LIMIT
                    || thumbnailWidth < 1 || thumbnailWidth > THUMBNAIL_SIDE || thumbnailHeight < 1 || thumbnailHeight > THUMBNAIL_SIDE)
                throw new IllegalArgumentException("视频元数据或封面无效");
            jpeg = jpeg.clone();
        }
        @Override public byte[] jpeg() { return jpeg.clone(); }
        @Override public String toString() {
            return "PreparedVideo[duration=" + duration + ", width=" + width + ", height=" + height
                    + ", supportsStreaming=" + supportsStreaming + ", thumbnailBytes=" + jpeg.length + "]";
        }
    }
    private record Metadata(double seconds, int duration, int width, int height, int streamIndex, boolean streamingCodec) { }
    private record Thumbnail(byte[] bytes, int width, int height, double brightness) { }

    public PreparedVideo prepare(Path video, Runnable check) throws IOException {
        java.util.Objects.requireNonNull(check);
        long deadline = nanos.getAsLong() + BUDGET_NANOS;
        Runnable bounded = () -> {
            check.run();
            if (Thread.currentThread().isInterrupted()) throw new PreparationStopped();
        };
        tick(deadline, bounded);
        Path input = java.util.Objects.requireNonNull(video).toAbsolutePath().normalize();
        if (Files.isSymbolicLink(input) || !Files.isRegularFile(input, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("待上传视频副本不存在或不是普通文件");
        var original = Files.readAttributes(input, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (original.size() < 1) throw new IOException("待上传视频为空，无法读取元数据");
        Path ffprobe = tools.find("ffprobe"), ffmpeg = tools.find("ffmpeg");
        tick(deadline, bounded);
        var probe = new ArrayList<>(List.of(ffprobe.toString(), "-v", "error", "-protocol_whitelist", "file,pipe",
                "-format_whitelist", FORMATS, "-threads", "2", "-show_entries",
                "stream=index,codec_type,codec_name,profile,pix_fmt,width,height,duration:stream_disposition=attached_pic:stream_tags=rotate:stream_side_data=rotation:format=duration,format_name",
                "-of", "json", input.toString()));
        Metadata metadata = metadata(run(probe, PROBE_LIMIT, deadline, bounded));
        tick(deadline, bounded);
        boolean streaming = metadata.streamingCodec() && fastStartMp4(input, deadline, bounded);
        Thumbnail best = null;
        Set<Long> attempted = new HashSet<>();
        for (double candidate : new double[] { Math.min(5, metadata.seconds() * .1), Math.min(30, metadata.seconds() * .5), Math.min(60, metadata.seconds() * .8) }) {
            double seek = Math.max(0, Math.min(candidate, metadata.seconds() - .001));
            if (!attempted.add(Math.round(seek * 1_000_000))) continue;
            tick(deadline, bounded);
            var command = List.of(ffmpeg.toString(), "-hide_banner", "-loglevel", "error", "-nostdin",
                    "-threads", "2", "-filter_threads", "1", "-protocol_whitelist", "file,pipe", "-format_whitelist", FORMATS,
                    "-ss", String.format(Locale.ROOT, "%.6f", seek), "-i", input.toString(), "-map", "0:" + metadata.streamIndex(),
                    "-an", "-sn", "-dn", "-frames:v", "1", "-vf", "scale=320:320:force_original_aspect_ratio=decrease,setsar=1",
                    "-c:v", "mjpeg", "-threads", "2", "-q:v", "5", "-f", "image2pipe", "pipe:1");
            Thumbnail next;
            try { next = thumbnail(run(command, JPEG_LIMIT, deadline, bounded)); }
            catch (IOException failedFrame) { tick(deadline, bounded); continue; }
            if (best == null || next.brightness() > best.brightness()) best = next;
            if (next.brightness() >= 14) break;
        }
        if (best == null) throw new IOException("未能生成有效视频封面，请检查视频编码或本机 FFmpeg");
        tick(deadline, bounded);
        var current = Files.readAttributes(input, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!current.isRegularFile() || original.size() != current.size() || !original.lastModifiedTime().equals(current.lastModifiedTime())
                || !java.util.Objects.equals(original.fileKey(), current.fileKey()))
            throw new IOException("视频副本在读取期间发生变化，请重新准备上传");
        return new PreparedVideo(metadata.duration(), metadata.width(), metadata.height(), streaming, best.bytes(), best.width(), best.height());
    }

    private byte[] run(List<String> command, int maximumBytes, long deadline, Runnable check) throws IOException {
        tick(deadline, check);
        byte[] bytes = runner.run(List.copyOf(command), maximumBytes, deadline, check);
        tick(deadline, check);
        if (bytes == null || bytes.length == 0 || bytes.length > maximumBytes) throw new IOException("视频工具未返回有效且有界的结果");
        return bytes;
    }
    private void tick(long deadline, Runnable check) throws IOException {
        check.run();
        if (nanos.getAsLong() - deadline >= 0) throw new IOException("视频元数据和封面准备超过60秒，请检查文件或本机工具后重试");
    }
    private static final class PreparationStopped extends RuntimeException {
        @Override public String getMessage() { return "视频准备已停止"; }
    }

    private static Metadata metadata(byte[] bytes) throws IOException {
        try {
            JsonNode root = JSON.readTree(bytes), streams = root.path("streams");
            if (!streams.isArray() || streams.size() > 64) throw new IOException("视频流信息无效");
            JsonNode video = null;
            int videos = 0;
            boolean compatibleAudio = true;
            for (var stream : streams) {
                if ("video".equals(stream.path("codec_type").asText()) && stream.path("disposition").path("attached_pic").asInt(0) != 1) {
                    videos++;
                    if (video == null) video = stream;
                } else if (!"audio".equals(stream.path("codec_type").asText()) || !"aac".equals(stream.path("codec_name").asText())) compatibleAudio = false;
            }
            if (video == null) throw new IOException("未找到可上传的视频流");
            int width = video.path("width").asInt(0), height = video.path("height").asInt(0), index = video.path("index").asInt(-1);
            if (width < 1 || height < 1 || width > 32768 || height > 32768 || index < 0 || index > 1023)
                throw new IOException("无法读取有效的视频尺寸或流编号");
            double seconds = positiveDouble(video.path("duration"));
            if (!Double.isFinite(seconds)) seconds = positiveDouble(root.path("format").path("duration"));
            if (!Double.isFinite(seconds) || seconds > Integer.MAX_VALUE) throw new IOException("无法读取有效的视频时长");
            int rotation = rotation(video);
            if (rotation == 90 || rotation == 270) { int swap = width; width = height; height = swap; }
            String profile = video.path("profile").asText("").toLowerCase(Locale.ROOT);
            boolean profileSupported = Set.of("baseline", "constrained baseline", "main", "high").contains(profile);
            boolean codec = videos == 1 && compatibleAudio && "h264".equals(video.path("codec_name").asText())
                    && "yuv420p".equals(video.path("pix_fmt").asText()) && profileSupported
                    && Arrays.asList(root.path("format").path("format_name").asText("").split(",")).contains("mp4");
            return new Metadata(seconds, (int) Math.ceil(seconds), width, height, index, codec);
        } catch (IOException failure) { throw failure; }
        catch (RuntimeException invalid) { throw new IOException("视频工具返回的元数据无法解析"); }
    }
    private static double positiveDouble(JsonNode value) {
        try { double parsed = Double.parseDouble(value.asText("")); return Double.isFinite(parsed) && parsed > 0 ? parsed : Double.NaN; }
        catch (NumberFormatException invalid) { return Double.NaN; }
    }
    private static int rotation(JsonNode stream) throws IOException {
        Double angle = null;
        var side = stream.path("side_data_list");
        if (side.isArray()) for (var item : side) if (item.has("rotation")) {
            double next = angle(item.path("rotation"));
            if (angle != null && normalizedRotation(angle) != normalizedRotation(next)) throw new IOException("视频旋转元数据互相矛盾");
            angle = next;
        }
        if (angle == null && stream.path("tags").has("rotate")) angle = angle(stream.path("tags").path("rotate"));
        return angle == null ? 0 : normalizedRotation(angle);
    }
    private static double angle(JsonNode value) throws IOException {
        try { double angle = Double.parseDouble(value.asText()); if (!Double.isFinite(angle)) throw new NumberFormatException(); return angle; }
        catch (NumberFormatException invalid) { throw new IOException("无法读取视频旋转方向"); }
    }
    private static int normalizedRotation(double angle) throws IOException {
        long quarter = Math.round(angle / 90);
        if (Math.abs(angle - quarter * 90) > .01) throw new IOException("暂不支持此视频旋转角度，请先调整视频方向");
        return (int) Math.floorMod(quarter, 4) * 90;
    }
    private static Thumbnail thumbnail(byte[] jpeg) throws IOException {
        if (jpeg.length < 4 || jpeg[0] != (byte) 0xff || jpeg[1] != (byte) 0xd8
                || jpeg[jpeg.length - 2] != (byte) 0xff || jpeg[jpeg.length - 1] != (byte) 0xd9)
            throw new IOException("视频封面不是完整 JPEG");
        try (var input = ImageIO.createImageInputStream(new ByteArrayInputStream(jpeg))) {
            if (input == null) throw new IOException("视频封面无法读取");
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) throw new IOException("视频封面无法解码");
            var reader = readers.next();
            try {
                reader.setInput(input, true, true);
                if (!reader.getFormatName().equalsIgnoreCase("JPEG")) throw new IOException("视频封面编码不是 JPEG");
                int width = reader.getWidth(0), height = reader.getHeight(0);
                if (width < 1 || height < 1 || width > THUMBNAIL_SIDE || height > THUMBNAIL_SIDE) throw new IOException("视频封面尺寸超限");
                BufferedImage image = reader.read(0);
                double light = 0;
                int samples = 0;
                for (int y = 0; y < height; y += Math.max(1, height / 32)) for (int x = 0; x < width; x += Math.max(1, width / 32)) {
                    int pixel = image.getRGB(x, y);
                    light += .2126 * ((pixel >>> 16) & 255) + .7152 * ((pixel >>> 8) & 255) + .0722 * (pixel & 255); samples++;
                }
                return new Thumbnail(jpeg, width, height, light / samples);
            } finally { reader.dispose(); }
        } catch (RuntimeException invalid) { throw new IOException("视频封面无法完整解码"); }
    }

    /** Only positive evidence permits streaming; unsupported containers and malformed boxes stay false. */
    private boolean fastStartMp4(Path path, long deadline, Runnable check) throws IOException {
        try (var file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            long length = file.size(), offset = 0;
            boolean brand = false, moov = false;
            for (int boxes = 0; boxes < 512 && offset < length; boxes++) {
                tick(deadline, check);
                if (length - offset < 8) return false;
                var header = ByteBuffer.allocate(16);
                file.position(offset);
                header.limit(8);
                if (!read(file, header)) return false;
                header.flip(); long size = Integer.toUnsignedLong(header.getInt());
                byte[] typeBytes = new byte[4]; header.get(typeBytes);
                String type = new String(typeBytes, java.nio.charset.StandardCharsets.US_ASCII);
                int headerSize = 8;
                if (size == 1) {
                    header.clear().limit(8);
                    if (!read(file, header)) return false;
                    header.flip(); size = header.getLong(); headerSize = 16;
                } else if (size == 0) size = length - offset;
                if (size < headerSize || size > length - offset) return false;
                if (type.equals("ftyp")) {
                    if (brand || size < headerSize + 8 || size > 4096) return false;
                    var major = ByteBuffer.allocate(4);
                    if (!read(file, major)) return false;
                    brand = MP4_BRANDS.contains(new String(major.array(), java.nio.charset.StandardCharsets.US_ASCII));
                    if (!brand) return false;
                }
                if (type.equals("moov")) moov = true;
                if (type.equals("mdat")) return brand && moov;
                offset += size;
            }
            return false;
        }
    }
    private static boolean read(FileChannel file, ByteBuffer bytes) throws IOException {
        while (bytes.hasRemaining()) if (file.read(bytes) < 0) return false;
        return true;
    }
    private static Path findTool(String name) throws IOException {
        String path = System.getenv("PATH");
        String executable = name + (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? ".exe" : "");
        if (path != null) for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
            if (entry.isBlank()) continue;
            try {
                Path candidate = Path.of(entry.replaceAll("^\"|\"$", "")).resolve(executable);
                if (Files.isRegularFile(candidate) && Files.isExecutable(candidate)) return candidate.toRealPath();
            } catch (IOException | RuntimeException unavailable) { /* Try the next explicit PATH entry. */ }
        }
        throw new IOException("视频上传需要 FFmpeg 和 FFprobe，请安装并加入 PATH 后重新准备");
    }

    static final class ProcessRunner implements Runner {
        private final LongSupplier nanos;
        ProcessRunner(LongSupplier nanos) { this.nanos = nanos; }
        @Override public byte[] run(List<String> command, int maximumBytes, long deadline, Runnable check) throws IOException {
            Process process;
            try { process = new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start(); }
            catch (IOException cannotStart) { throw new IOException("无法启动本机视频工具，请检查 FFmpeg 和 FFprobe 配置"); }
            var result = new ByteArrayOutputStream(Math.min(maximumBytes, 8192));
            var readFailure = new AtomicReference<IOException>();
            Thread reader = Thread.ofPlatform().daemon(true).name("video-preparation-output").unstarted(() -> {
                try (var output = process.getInputStream()) {
                    byte[] block = new byte[8192];
                    for (int count; (count = output.read(block)) != -1;) {
                        if (count > maximumBytes - result.size()) throw new IOException("视频工具输出超过安全上限");
                        result.write(block, 0, count);
                    }
                } catch (IOException failure) { readFailure.set(new IOException("视频工具输出未能完整读取或超过安全上限")); process.destroyForcibly(); }
            });
            try {
                process.getOutputStream().close(); reader.start();
                while (process.isAlive()) {
                    boundary(deadline, check);
                    if (readFailure.get() != null) throw readFailure.get();
                    try { process.waitFor(200, TimeUnit.MILLISECONDS); }
                    catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException("视频准备已停止"); }
                }
                while (reader.isAlive()) {
                    boundary(deadline, check);
                    try { reader.join(200); }
                    catch (InterruptedException stopped) { Thread.currentThread().interrupt(); throw new IOException("视频准备已停止"); }
                }
                boundary(deadline, check);
                if (readFailure.get() != null) throw readFailure.get();
                if (process.exitValue() != 0) throw new IOException("本机视频工具未能读取此视频，请检查文件编码或工具版本");
                return result.toByteArray();
            } finally {
                if (process.isAlive()) {
                    process.descendants().forEach(child -> { try { child.destroyForcibly(); } catch (RuntimeException ignored) { } });
                    process.destroyForcibly();
                }
                try { process.getInputStream().close(); } catch (IOException ignored) { }
                try { process.getOutputStream().close(); } catch (IOException ignored) { }
                try { process.getErrorStream().close(); } catch (IOException ignored) { }
                boolean interrupted = Thread.interrupted();
                try { process.waitFor(2, TimeUnit.SECONDS); reader.join(2000); }
                catch (InterruptedException stopped) { interrupted = true; }
                if (interrupted) Thread.currentThread().interrupt();
            }
        }
        private void boundary(long deadline, Runnable check) throws IOException {
            check.run();
            if (Thread.currentThread().isInterrupted()) throw new IOException("视频准备已停止");
            if (nanos.getAsLong() - deadline >= 0) throw new IOException("视频元数据和封面准备超过60秒，请稍后重新准备");
        }
    }
}
