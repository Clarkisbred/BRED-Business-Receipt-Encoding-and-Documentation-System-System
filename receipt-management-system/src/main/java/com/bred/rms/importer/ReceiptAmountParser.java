package com.bred.rms.importer;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.regex.Pattern;

/** Parses unambiguous receipt amounts without guessing decimal or grouping separators. */
public final class ReceiptAmountParser {
    private static final Pattern SUPPORTED_NUMBER = Pattern.compile(
            "^[+-]?(?:(?:\\d{1,3}(?:,\\d{3})+|\\d+)(?:\\.\\d+)?|\\.\\d+)$");

    private ReceiptAmountParser() { }

    public static Optional<BigDecimal> parse(String raw) {
        if (raw == null || raw.isBlank()) return Optional.empty();
        String value = raw.trim()
                .replaceFirst("(?i)^(?:PHP|USD|EUR)\\s*", "")
                .replaceFirst("(?i)\\s*(?:PHP|USD|EUR)$", "")
                .replaceAll("[\\p{Sc}\\s\\u00A0]", "");
        boolean parenthesizedNegative = value.startsWith("(") && value.endsWith(")");
        if (value.startsWith("(") != value.endsWith(")")) return Optional.empty();
        if (parenthesizedNegative) {
            value = value.substring(1, value.length() - 1);
            if (value.startsWith("+") || value.startsWith("-")) return Optional.empty();
        }
        if (!SUPPORTED_NUMBER.matcher(value).matches()) return Optional.empty();
        try {
            BigDecimal amount = new BigDecimal(value.replace(",", ""));
            return Optional.of(parenthesizedNegative ? amount.negate() : amount);
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
