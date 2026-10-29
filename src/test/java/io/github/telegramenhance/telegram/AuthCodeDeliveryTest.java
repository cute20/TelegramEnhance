package io.github.telegramenhance.telegram;

import static org.junit.jupiter.api.Assertions.*;
import it.tdlight.jni.TdApi;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AuthCodeDeliveryTest {
    private final MutableClock clock = new MutableClock();
    private final AuthCodeDelivery delivery = new AuthCodeDelivery(clock);

    @Test void clientMessageTellsUserWhereToLookWithoutExposingPhone() {
        delivery.update(info(null, 0));
        assertTrue(delivery.hint().contains("官方“Telegram”服务消息"));
        assertTrue(delivery.hint().contains("不是短信"));
        assertFalse(delivery.view().toString().contains("synthetic-phone"));
    }
    @Test void missingNextTypeNeverPermitsForcedSmsEvenAfterTimeout() {
        delivery.update(info(null, 0));
        clock.advance(600_000);
        assertFalse(delivery.view().canResend());
        assertNull(delivery.view().resendAvailableAt());
        assertThrows(IllegalArgumentException.class, delivery::claimResend);
    }
    @Test void pollingDoesNotResetServerDeadline() {
        delivery.update(info(new TdApi.AuthenticationCodeTypeSms(6), 30));
        long deadline = delivery.view().resendAvailableAt();
        clock.advance(29_999);
        assertEquals(deadline, delivery.view().resendAvailableAt());
        assertFalse(delivery.view().canResend());
        assertThrows(IllegalArgumentException.class, delivery::claimResend);
        clock.advance(1);
        assertTrue(delivery.view().canResend());
    }
    @Test void oneClaimRequiresFreshServerStateBeforeAnotherRequest() {
        delivery.update(info(new TdApi.AuthenticationCodeTypeSms(6), 0));
        delivery.claimResend();
        assertFalse(delivery.view().canResend());
        assertThrows(IllegalArgumentException.class, delivery::claimResend);
        clock.advance(60_000);
        assertFalse(delivery.view().canResend());
        delivery.update(info(null, 0));
        assertFalse(delivery.view().canResend());
    }
    @Test void leavingCodeStepRemovesChallengeAndResendPermission() {
        delivery.update(info(new TdApi.AuthenticationCodeTypeSms(6), 0));
        delivery.clear();
        assertNull(delivery.view());
        assertNull(delivery.hint());
        assertThrows(IllegalArgumentException.class, delivery::claimResend);
    }
    @Test void newServerStateUsesItsRemainingTimeout() {
        delivery.update(info(new TdApi.AuthenticationCodeTypeSms(6), 30));
        clock.advance(10_000);
        delivery.update(info(new TdApi.AuthenticationCodeTypeSms(6), 20));
        assertEquals(30_000L, delivery.view().resendAvailableAt());
        clock.advance(20_000);
        assertTrue(delivery.view().canResend());
    }
    @Test void nativeQueueDelayDoesNotExtendTheCountdown() {
        long observedAt = clock.millis();
        clock.advance(15_000);
        delivery.update(info(new TdApi.AuthenticationCodeTypeSms(6), 30), observedAt);
        assertEquals(30_000L, delivery.view().resendAvailableAt());
        clock.advance(15_000);
        assertTrue(delivery.view().canResend());
    }
    private TdApi.AuthenticationCodeInfo info(TdApi.AuthenticationCodeType nextType, int timeout) {
        return new TdApi.AuthenticationCodeInfo("synthetic-phone", new TdApi.AuthenticationCodeTypeTelegramMessage(6), nextType, timeout);
    }
    private static class MutableClock extends Clock {
        long time;
        void advance(long milliseconds) { time += milliseconds; }
        @Override public long millis() { return time; }
        @Override public Instant instant() { return Instant.ofEpochMilli(time); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
    }
}
