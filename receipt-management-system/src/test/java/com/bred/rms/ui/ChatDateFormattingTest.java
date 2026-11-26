package com.bred.rms.ui;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;

public final class ChatDateFormattingTest {
    @Test
    public void formatsDateSeparatorsWithWeekdayAndUsMonthDayYear() {
        assertEquals("Thu, 11/26/2026", ChatDateFormatting.separator(LocalDate.of(2026, 11, 26)));
        Instant timestamp = Instant.parse("2026-11-26T12:00:00Z");
        assertEquals("Thu, 11/26/2026", ChatDateFormatting.separator(timestamp));
    }
}
