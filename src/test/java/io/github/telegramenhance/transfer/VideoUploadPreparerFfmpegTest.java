package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import java.io.ByteArrayInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Optional real local tools; no native Telegram library, account, or network is used. */
class VideoUploadPreparerFfmpegTest {
    @TempDir Path directory;
    @Test void realAnamorphicFramesAndRotatedFramesKeepTheirDisplayAspectAndOriginalBytes()throws Exception{
        assumeTrue(Boolean.getBoolean("workbench.test.ffmpeg"),"Enable with -Dworkbench.test.ffmpeg=true and FFmpeg/FFprobe on PATH");
        Path original=directory.resolve("anamorphic.mp4");
        ffmpeg("-f","lavfi","-i","testsrc2=size=720x576:rate=25","-t","1","-vf","setsar=64/45",
                "-c:v","libx264","-pix_fmt","yuv420p","-movflags","+faststart",original.toString());
        for(int rotation:List.of(0,90,270)){
            Path input=original;
            if(rotation!=0){input=directory.resolve("rotation-"+rotation+".mp4");ffmpeg("-display_rotation",Integer.toString(rotation),"-i",original.toString(),"-c","copy","-movflags","+faststart",input.toString());}
            byte[] before=Files.readAllBytes(input);var prepared=new VideoUploadPreparer().prepare(input,()->{});
            assertEquals(rotation==0?1024:576,prepared.width());assertEquals(rotation==0?576:1024,prepared.height());
            var image=ImageIO.read(new ByteArrayInputStream(prepared.jpeg()));
            assertEquals(rotation==0?320:180,image.getWidth());assertEquals(rotation==0?180:320,image.getHeight());
            assertEquals(image.getWidth(),prepared.thumbnailWidth());assertEquals(image.getHeight(),prepared.thumbnailHeight());
            assertArrayEquals(before,Files.readAllBytes(input));
        }
    }
    private static void ffmpeg(String...arguments)throws Exception{
        var command=new ArrayList<>(List.of("ffmpeg","-hide_banner","-loglevel","error","-nostdin","-y"));command.addAll(List.of(arguments));
        var process=new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try{assertTrue(process.waitFor(30,TimeUnit.SECONDS),"Local FFmpeg fixture generation timed out");assertEquals(0,process.exitValue(),"Local FFmpeg fixture generation failed");}
        finally{if(process.isAlive())process.destroyForcibly();}
    }
}
