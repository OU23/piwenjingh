package com.example.toolbox.common.util;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间统一格式 yyyy-MM-dd HH:mm:ss（契约 §1.6）。
 */
public final class DateTimeUtils {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public static String format(LocalDateTime time) {
        return time == null ? null : time.format(FMT);
    }

    private DateTimeUtils() {
    }
}
