package com.bred.rms.ui;

import java.time.LocalDate;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

final class ChatDateFormatting {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("EEE, M/d/yyyy", Locale.US);

    private ChatDateFormatting() { }

    static String separator(LocalDate date) {
        return DATE.format(date);
    }

    static String separator(Instant timestamp) {
        return separator(timestamp.atZone(ZoneId.systemDefault()).toLocalDate());
    }
}
