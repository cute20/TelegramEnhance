package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class TransferRateSamplerTest {
    @Test void nativeBytesHaveAStableRateWhileWritesAreNotAdded(){var clock=new AtomicLong(1000);var sampler=new TransferRateSampler(clock::get);
        assertNull(sampler.observe("1",1,"download:1",0,100L,true).bytesPerSecond());clock.set(1250);assertNull(sampler.observe("1",1,"download:1",10,100L,true).bytesPerSecond());
        clock.set(2000);var sample=sampler.observe("1",1,"download:1",50,100L,true);assertEquals(50,sample.bytesPerSecond());assertEquals(1,sample.etaSeconds());
        clock.set(3000);assertNull(sampler.observe("1",1,"download:1",50,100L,true).bytesPerSecond(),"stalled bytes do not keep the old speed");
    }
    @Test void pausesWritesSessionChangesAndLongUnobservedGapsDiscardTheRate(){var clock=new AtomicLong(1000);var sampler=new TransferRateSampler(clock::get);
        sampler.observe("1",1,"batch:1",0,100L,true);clock.set(2000);assertNotNull(sampler.observe("1",1,"batch:1",50,100L,true).bytesPerSecond());assertNull(sampler.observe("1",1,"batch:1",50,100L,false).bytesPerSecond());
        clock.set(3000);assertNull(sampler.observe("1",1,"batch:1",70,100L,true).bytesPerSecond());clock.set(4000);assertNull(sampler.observe("1",2,"batch:1",80,100L,true).bytesPerSecond());
        clock.set(16000);assertNull(sampler.observe("1",2,"batch:1",90,100L,true).bytesPerSecond());assertNull(sampler.observe("2",2,"batch:1",90,100L,true).bytesPerSecond());
    }
    @Test void unknownRemainingHistoryBytesPermitRateButNotAnInventedEta(){var clock=new AtomicLong(1000);var sampler=new TransferRateSampler(clock::get);sampler.observe("1",1,"rule:1",0,null,true);clock.set(2000);var sample=sampler.observe("1",1,"rule:1",50,null,true);assertEquals(50,sample.bytesPerSecond());assertNull(sample.etaSeconds());}
}
