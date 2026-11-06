package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VideoUploadPreparerTest {
    @TempDir Path directory;
    private static final String VIDEO = "{\"index\":0,\"codec_type\":\"video\",\"codec_name\":\"h264\",\"profile\":\"High\",\"pix_fmt\":\"yuv420p\",\"width\":1920,\"height\":1080,\"duration\":\"10.25\"}";

    @Test void readsRealMetadataAndProvesFastStartInsteadOfUsingFilenameOrInventingZeros() throws Exception {
        var fixture = new Fixture(probe(VIDEO), jpeg(160, 90, Color.RED));
        var prepared = fixture.prepare(mp4(true, "isom"));
        assertEquals(11, prepared.duration());
        assertEquals(1920, prepared.width());
        assertEquals(1080, prepared.height());
        assertTrue(prepared.supportsStreaming());
        assertEquals(160, prepared.thumbnailWidth());
        assertEquals(90, prepared.thumbnailHeight());
        assertEquals(2, fixture.commands.size());
        var probe = fixture.commands.getFirst();
        assertEquals("file,pipe", argument(probe, "-protocol_whitelist"));
        assertFalse(argument(probe, "-format_whitelist").contains("hls"));
        assertFalse(argument(probe, "-format_whitelist").contains("concat"));
        var cover = fixture.commands.get(1);
        assertTrue(cover.contains("-nostdin"));
        assertEquals("file,pipe", argument(cover, "-protocol_whitelist"));
        assertEquals("0:0", argument(cover, "-map"));
        assertEquals("1", argument(cover, "-frames:v"));
        assertEquals("pipe:1", cover.getLast());
        assertTrue(cover.contains("-an") && cover.contains("-sn") && cover.contains("-dn"));
        assertEquals(List.of(VideoUploadPreparer.PROBE_LIMIT, VideoUploadPreparer.JPEG_LIMIT), fixture.limits);
        assertArrayEquals(mp4(true, "isom"), Files.readAllBytes(fixture.video));
    }

    @Test void videoPathIsOneUnmodifiedProcessArgumentEvenWithSpacesAndShellPunctuation() throws Exception {
        var fixture = new Fixture(probe(VIDEO), jpeg(160, 90, Color.WHITE));
        fixture.video = directory.resolve("测试视频 ' & ; $ (x).mp4");
        fixture.prepare(mp4(true, "isom"));
        assertEquals(fixture.video.toAbsolutePath().toString(), fixture.commands.getFirst().getLast());
        assertEquals(fixture.video.toAbsolutePath().toString(), argument(fixture.commands.get(1), "-i"));
        assertTrue(fixture.commands.stream().allMatch(command -> !List.of("cmd", "cmd.exe", "powershell", "sh", "bash").contains(command.getFirst())));
    }

    @Test void displayMatrixAndLegacyRotationTagsAdjustDisplayedDimensions() throws Exception {
        for (String rotation : List.of(
                ",\"side_data_list\":[{\"rotation\":-90}]",
                ",\"side_data_list\":[{\"rotation\":270}]",
                ",\"tags\":{\"rotate\":\"90\"}")) {
            var fixture = new Fixture(probe(VIDEO.substring(0, VIDEO.length() - 1) + rotation + "}"), jpeg(90, 160, Color.BLUE));
            var prepared = fixture.prepare(mp4(true, "isom"));
            assertEquals(1080, prepared.width());
            assertEquals(1920, prepared.height());
        }
    }

    @Test void anamorphicDisplayGeometryIsNormalizedBeforeApplyingRightAngleRotation() throws Exception {
        String anamorphic=VIDEO.replace("1920","720").replace("1080","576");
        anamorphic=anamorphic.substring(0,anamorphic.length()-1)+",\"sample_aspect_ratio\":\"64:45\"}";
        for(int rotation:List.of(0,90,270)){
            String stream=anamorphic.substring(0,anamorphic.length()-1)+",\"side_data_list\":[{\"rotation\":"+rotation+"}]}";
            var fixture=new Fixture(probe(stream),jpeg(160,90,Color.BLUE));var prepared=fixture.prepare(mp4(true,"isom"));
            assertEquals(rotation==0?1024:576,prepared.width());assertEquals(rotation==0?576:1024,prepared.height());
            assertEquals(rotation==0?"scale=320:180,setsar=1":"scale=180:320,setsar=1",argument(fixture.commands.get(1),"-vf"));
            assertArrayEquals(mp4(true,"isom"),Files.readAllBytes(fixture.video));
        }
    }

    @Test void unknownPixelAspectRatiosUseSquarePixelsButInvalidExplicitRatiosAreRejected()throws Exception{
        for(String sar:List.of("N/A","0:1","1:1")){
            String stream=VIDEO.substring(0,VIDEO.length()-1)+",\"sample_aspect_ratio\":\""+sar+"\"}";
            assertEquals(1920,new Fixture(probe(stream),jpeg(160,90,Color.BLUE)).prepare(mp4(true,"isom")).width());
        }
        for(String sar:List.of("1:0","-1:1","1:2:3","NaN","100:1")){
            String stream=VIDEO.substring(0,VIDEO.length()-1)+",\"sample_aspect_ratio\":\""+sar+"\"}";
            var fixture=new Fixture(probe(stream),jpeg(160,90,Color.BLUE));assertThrows(IOException.class,()->fixture.prepare(mp4(true,"isom")));
            assertEquals(1,fixture.commands.size());
        }
    }

    @Test void nonRightAngleOrConflictingRotationFailsWithoutInventingDisplayDimensions() throws Exception {
        for (String rotation : List.of(
                ",\"side_data_list\":[{\"rotation\":45}]",
                ",\"side_data_list\":[{\"rotation\":90},{\"rotation\":180}]")) {
            var fixture = new Fixture(probe(VIDEO.substring(0, VIDEO.length() - 1) + rotation + "}"), jpeg(160, 90, Color.BLUE));
            assertThrows(IOException.class, () -> fixture.prepare(mp4(true, "isom")));
            assertEquals(1, fixture.commands.size());
        }
    }

    @Test void missingStreamDurationCanUseTheContainerDurationButUnknownOrInvalidMetadataFails() throws Exception {
        var fallback = new Fixture(probe(VIDEO.replace("10.25", "N/A")), jpeg(160, 90, Color.WHITE));
        assertEquals(13, fallback.prepare(mp4(false, "isom")).duration());
        for (String metadata : List.of(
                "{\"streams\":[],\"format\":{\"duration\":\"10\"}}",
                probe(VIDEO.replace("1920", "0")),
                probe(VIDEO.replace("1080", "-2")),
                probe(VIDEO.replace("1920", "32769")),
                probe(VIDEO.replace("10.25", "N/A")).replace("12.5", "N/A"),
                probe(VIDEO.replace("10.25", "0")).replace("12.5", "0"),
                probe(VIDEO.replace("10.25", "Infinity")).replace("12.5", "NaN"),
                "{\"streams\":[")) {
            var invalid = new Fixture(metadata, jpeg(160, 90, Color.WHITE));
            assertThrows(IOException.class, () -> invalid.prepare(mp4(true, "isom")));
            assertEquals(1, invalid.commands.size());
        }
    }

    @Test void attachedCoverImagesDoNotReplaceTheRealVideoStream() throws Exception {
        String poster = "{\"index\":0,\"codec_type\":\"video\",\"codec_name\":\"mjpeg\",\"width\":100,\"height\":100,\"disposition\":{\"attached_pic\":1}}";
        var fixture = new Fixture(probe(poster + "," + VIDEO.replace("\"index\":0", "\"index\":1")), jpeg(160, 90, Color.WHITE));
        var prepared = fixture.prepare(mp4(true, "isom"));
        assertEquals(1920, prepared.width());
        assertEquals("0:1", argument(fixture.commands.get(1), "-map"));
        assertFalse(prepared.supportsStreaming());
        var onlyPoster = new Fixture(probe(poster), jpeg(160, 90, Color.WHITE));
        assertThrows(IOException.class, () -> onlyPoster.prepare(mp4(true, "isom")));
    }

    @Test void streamingRequiresProvenContainerOrderAndKnownCompatibleCodecs() throws Exception {
        for (byte[] bytes : List.of(mp4(false, "isom"), mp4(true, "qt  "), new byte[] { 1, 2, 3 },
                ByteBuffer.allocate(8).putInt(7).put("ftyp".getBytes(StandardCharsets.US_ASCII)).array())) {
            var fixture = new Fixture(probe(VIDEO), jpeg(160, 90, Color.WHITE));
            assertFalse(fixture.prepare(bytes).supportsStreaming());
        }
        for (String metadata : List.of(
                probe(VIDEO.replace("h264", "hevc")),
                probe(VIDEO.replace("yuv420p", "yuv420p10le")),
                probe(VIDEO.replace("High", "High 10")),
                probe(VIDEO + ",{\"index\":1,\"codec_type\":\"audio\",\"codec_name\":\"ac3\"}"),
                probe(VIDEO).replace("mov,mp4,m4a,3gp,3g2,mj2", "matroska,webm"))) {
            assertFalse(new Fixture(metadata, jpeg(160, 90, Color.WHITE)).prepare(mp4(true, "isom")).supportsStreaming());
        }
        assertTrue(new Fixture(probe(VIDEO + ",{\"index\":1,\"codec_type\":\"audio\",\"codec_name\":\"aac\"}"),
                jpeg(160, 90, Color.WHITE)).prepare(mp4(true, "isom")).supportsStreaming());
    }

    @Test void blackOpeningTriesAtMostThreeDifferentRepresentativePositionsAndPrefersAVisibleFrame() throws Exception {
        byte[] black = jpeg(160, 90, Color.BLACK), red = jpeg(160, 90, Color.RED);
        var fixture = new Fixture(probe(VIDEO), black, black, red);
        var prepared = fixture.prepare(mp4(true, "isom"));
        assertArrayEquals(red, prepared.jpeg());
        assertEquals(4, fixture.commands.size());
        assertEquals(List.of("1.025000", "5.125000", "8.200000"), fixture.commands.subList(1, 4).stream().map(command -> argument(command, "-ss")).toList());
        var allBlack = new Fixture(probe(VIDEO), black, black, black);
        assertArrayEquals(black, allBlack.prepare(mp4(true, "isom")).jpeg());
        assertEquals(4, allBlack.commands.size());
    }

    @Test void malformedTruncatedOversizedOrNonJpegCoversCannotBeAttached() throws Exception {
        byte[] valid = jpeg(160, 90, Color.BLUE);
        var png = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(10, 10, BufferedImage.TYPE_INT_RGB), "PNG", png);
        for (byte[] bad : List.of(Arrays.copyOf(valid, valid.length - 2), png.toByteArray(),
                jpeg(321, 90, Color.WHITE), new byte[VideoUploadPreparer.JPEG_LIMIT + 1])) {
            var fixture = new Fixture(probe(VIDEO), bad, bad, bad);
            assertThrows(IOException.class, () -> fixture.prepare(mp4(true, "isom")));
            assertTrue(fixture.commands.size() <= 4);
        }
    }

    @Test void missingToolsFailClearlyWithoutStartingAnyProcessOrLeakingThePrivateFilename() throws Exception {
        Path video = directory.resolve("private-video-name.mp4");
        Files.write(video, mp4(true, "isom"));
        var calls = new AtomicInteger();
        var helper = new VideoUploadPreparer((command, limit, deadline, check) -> {
            calls.incrementAndGet(); return new byte[0];
        }, System::nanoTime, tool -> { throw new IOException("视频上传需要 FFmpeg 和 FFprobe"); });
        var failure = assertThrows(IOException.class, () -> helper.prepare(video, () -> { }));
        assertEquals(0, calls.get());
        assertTrue(failure.getMessage().contains("FFmpeg"));
        assertFalse(failure.getMessage().contains(video.toString()));
    }

    @Test void fullPreparationUsesOneMonotonicBudgetAndDoesNotRetryAfterTheDeadline() throws Exception {
        Path video = directory.resolve("deadline.mp4");
        Files.write(video, mp4(true, "isom"));
        var nanos = new AtomicLong();
        var calls = new AtomicInteger();
        var helper = new VideoUploadPreparer((command, limit, deadline, check) -> {
            assertEquals(VideoUploadPreparer.BUDGET_NANOS, deadline);
            calls.incrementAndGet(); nanos.set(VideoUploadPreparer.BUDGET_NANOS);
            return probe(VIDEO).getBytes(StandardCharsets.UTF_8);
        }, nanos::get, name -> Path.of(name));
        assertThrows(IOException.class, () -> helper.prepare(video, () -> { }));
        assertEquals(1, calls.get());
    }

    @Test void cancellationAndSourceVersionChangesDiscardPreparedMetadataAndCovers() throws Exception {
        var cancelled = new Fixture(probe(VIDEO), jpeg(160, 90, Color.WHITE));
        Files.write(cancelled.video, mp4(true, "isom"));
        var stops = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> cancelled.helper.prepare(cancelled.video, () -> {
            if (stops.incrementAndGet() == 2) throw new IllegalStateException("caller stopped");
        }));
        assertTrue(cancelled.commands.isEmpty());

        Path changed = directory.resolve("changed.mp4");
        Files.write(changed, mp4(true, "isom"));
        var calls = new AtomicInteger();
        byte[] cover = jpeg(160, 90, Color.WHITE);
        var helper = new VideoUploadPreparer((command, limit, deadline, check) -> {
            if (calls.getAndIncrement() == 0) return probe(VIDEO).getBytes(StandardCharsets.UTF_8);
            Files.write(changed, new byte[] { 1 }); return cover;
        }, System::nanoTime, name -> Path.of(name));
        assertThrows(IOException.class, () -> helper.prepare(changed, () -> { }));
    }

    @Test void preparedCoverBytesAreDefensivelyCopiedAndNotDumpedByToString() throws Exception {
        byte[] source = jpeg(16, 9, Color.RED), unchanged = source.clone();
        var prepared = new VideoUploadPreparer.PreparedVideo(1, 160, 90, false, source, 16, 9);
        source[0] = 0;
        assertArrayEquals(unchanged, prepared.jpeg());
        byte[] retrieved = prepared.jpeg(); retrieved[1] = 0;
        assertArrayEquals(unchanged, prepared.jpeg());
        assertTrue(prepared.toString().contains("thumbnailBytes="));
        assertFalse(prepared.toString().contains(Arrays.toString(unchanged)));
    }

    @Test void processRunnerBoundsStdoutAndDiscardsStderrWithoutDeadlocking() throws Exception {
        var runner = new VideoUploadPreparer.ProcessRunner(System::nanoTime);
        byte[] output = runner.run(child("output", "1024"), 1024, System.nanoTime() + TimeUnit.SECONDS.toNanos(15), () -> { });
        assertEquals(1024, output.length);
        assertThrows(IOException.class, () -> runner.run(child("output", "4096"), 1024,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(15), () -> { }));
    }

    @Test void processRunnerTimesOutAndHonorsCallerCancellation() throws Exception {
        var runner = new VideoUploadPreparer.ProcessRunner(System::nanoTime);
        long before = System.nanoTime();
        assertThrows(IOException.class, () -> runner.run(child("sleep", "10000"), 1024,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300), () -> { }));
        assertTrue(System.nanoTime() - before < TimeUnit.SECONDS.toNanos(8));
        var checks = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> runner.run(child("sleep", "10000"), 1024,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(15), () -> {
                    if (checks.incrementAndGet() >= 2) throw new IllegalStateException("cancel");
                }));
    }

    private static List<String> child(String operation, String value) {
        String executable = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "-cp",
                System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
                OutputChild.class.getName(), operation, value);
    }
    /** Local synthetic process used only to verify bounded pipes and termination. */
    public static final class OutputChild {
        public static void main(String[] args) throws Exception {
            if (args[0].equals("sleep")) { Thread.sleep(Long.parseLong(args[1])); return; }
            int count = Integer.parseInt(args[1]);
            for (int i = 0; i < count; i++) { System.err.write('e'); System.out.write('x'); }
            System.err.flush(); System.out.flush();
        }
    }
    private static String probe(String streams) {
        return "{\"streams\":[" + streams + "],\"format\":{\"duration\":\"12.5\",\"format_name\":\"mov,mp4,m4a,3gp,3g2,mj2\"}}";
    }
    private static String argument(List<String> command, String flag) { return command.get(command.indexOf(flag) + 1); }
    private static byte[] jpeg(int width, int height, Color color) throws IOException {
        var image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(color); graphics.fillRect(0, 0, width, height); graphics.dispose();
        var output = new ByteArrayOutputStream(); assertTrue(ImageIO.write(image, "JPEG", output)); return output.toByteArray();
    }
    private static byte[] box(String type, byte[] payload) {
        return ByteBuffer.allocate(8 + payload.length).putInt(8 + payload.length)
                .put(type.getBytes(StandardCharsets.US_ASCII)).put(payload).array();
    }
    private static byte[] mp4(boolean fastStart, String brand) throws IOException {
        var output = new ByteArrayOutputStream();
        output.write(box("ftyp", ByteBuffer.allocate(8).put(brand.getBytes(StandardCharsets.US_ASCII)).putInt(0).array()));
        byte[] moov = box("moov", new byte[0]), mdat = box("mdat", new byte[] { 1, 2, 3 });
        output.write(fastStart ? moov : mdat); output.write(fastStart ? mdat : moov); return output.toByteArray();
    }
    private final class Fixture {
        Path video = directory.resolve(UUID.randomUUID() + ".mp4");
        final List<List<String>> commands = new ArrayList<>();
        final List<Integer> limits = new ArrayList<>();
        final VideoUploadPreparer helper;
        Fixture(String metadata, byte[]... covers) {
            var index = new AtomicInteger();
            helper = new VideoUploadPreparer((command, limit, deadline, check) -> {
                commands.add(command); limits.add(limit); check.run();
                int n = index.getAndIncrement();
                return n == 0 ? metadata.getBytes(StandardCharsets.UTF_8) : covers[Math.min(covers.length - 1, n - 1)];
            }, System::nanoTime, Path::of);
        }
        VideoUploadPreparer.PreparedVideo prepare(byte[] bytes) throws IOException {
            Files.write(video, bytes); return helper.prepare(video, () -> { });
        }
    }
}
