package io.github.telegramenhance.transfer;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/** Main-post publication dates, with both user-selected Beijing calendar dates inclusive. */
final class DownloadDateRange {
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final LocalDate start, end;

    private DownloadDateRange(LocalDate start, LocalDate end) { this.start = start; this.end = end; }

    static DownloadDateRange parse(String startDate, String endDate) {
        LocalDate start = date(startDate), end = date(endDate);
        if (start != null && end != null && start.isAfter(end))
            throw new IllegalArgumentException("开始日期不能晚于结束日期。");
        return new DownloadDateRange(start, end);
    }

    private static LocalDate date(String value) {
        if (value == null || value.isBlank()) return null;
        if (!value.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw new IllegalArgumentException("日期格式应为YYYY-MM-DD，请填写有效日期。");
        try { return LocalDate.parse(value); }
        catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("日期格式应为YYYY-MM-DD，请填写有效日期。");
        }
    }

    String startDate() { return start == null ? null : start.toString(); }
    String endDate() { return end == null ? null : end.toString(); }
    boolean active() { return start != null || end != null; }

    /** null means an active filter cannot be verified because publication time is unknown. */
    Boolean matches(Long publishedAt) {
        if (!active()) return true;
        if (publishedAt == null || publishedAt <= 0) return null;
        LocalDate day = Instant.ofEpochMilli(publishedAt).atZone(ZONE).toLocalDate();
        return (start == null || !day.isBefore(start)) && (end == null || !day.isAfter(end));
    }
}
