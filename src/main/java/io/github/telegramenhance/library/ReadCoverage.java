package io.github.telegramenhance.library;

import java.util.List;

/** Statuses are per scope; localized messages never decide whether another scope was completed. */
public final class ReadCoverage {
    private ReadCoverage() {}
    private static final List<String> LEGACY_MAIN_OR_INFO = List.of(
            "本次可见历史已读至结尾；新增评论需从首轮重新读取，特殊媒体仍以主帖警告为准。",
            "主帖相册通过有限邻域读取识别，请与 Telegram 核对成员；本片不宣称已证明相册完整。");
    public static List<String> commentWarnings(List<String> warnings) {
        // Exact compatibility for old mixed-provenance rows only. Unknown warnings remain unresolved.
        return warnings.stream().filter(w -> !LEGACY_MAIN_OR_INFO.contains(w)).distinct().toList();
    }
    public static String comments(boolean readable, boolean exhausted, List<String> warnings) {
        return !readable ? "UNAVAILABLE" : exhausted && warnings.isEmpty() ? "COMPLETE" : "PARTIAL";
    }
    public static String overall(String main, String comments) {
        if ("UNAVAILABLE".equals(main) || "UNAVAILABLE".equals(comments)) return "UNAVAILABLE";
        return "COMPLETE".equals(main) && "COMPLETE".equals(comments) ? "COMPLETE" : "PARTIAL";
    }
}
