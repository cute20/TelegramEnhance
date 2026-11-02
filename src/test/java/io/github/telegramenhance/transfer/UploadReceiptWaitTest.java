package io.github.telegramenhance.transfer;

import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class UploadReceiptWaitTest {
    @Test void smallUploadsRetainTwentyMinutesAndNegativeSizesAreRejected() {
        assertEquals(1_200, UploadEngine.receiptWaitSeconds(0)); assertEquals(1_200, UploadEngine.receiptWaitSeconds(1));
        assertEquals(1_200, UploadEngine.receiptWaitSeconds(1_080L * 1024 * 1024));
        assertThrows(IllegalArgumentException.class, () -> UploadEngine.receiptWaitSeconds(-1));
    }
    @Test void estimatesRoundUpToMebibytesAndIncludeTwoMinutesForFormalReceipts() {
        assertEquals(1_201, UploadEngine.receiptWaitSeconds(1_080L * 1024 * 1024 + 1));
        assertEquals(8_312, UploadEngine.receiptWaitSeconds(8L * 1024 * 1024 * 1024));
        assertTrue(UploadEngine.receiptWaitSeconds(8_549_788_582L) > TimeUnit.MINUTES.toSeconds(42));
    }
    @Test void veryLargeOrOverflowSizedInputsStillHaveASixHourUpperBound() {
        long cap = TimeUnit.HOURS.toSeconds(6);
        assertEquals(cap, UploadEngine.receiptWaitSeconds(21_480L * 1024 * 1024));
        assertEquals(cap, UploadEngine.receiptWaitSeconds(Long.MAX_VALUE));
    }
}
