package com.bred.rms.importer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.format.DateTimeParseException;
import java.time.format.ResolverStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Validates review drafts independently of AI output and never changes extracted values. */
public final class ReceiptCandidateValidator {
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            formatter("M/d/uuuu"),
            formatter("d/M/uuuu"),
            formatter("d-MMM-uuuu"),
            formatter("d MMM uuuu"),
            formatter("MM-dd-uuuu"));
    private static final Pattern SLASH_DATE = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$");

    public List<String> validate(ReceiptCandidate candidate) {
        List<String> warnings = new ArrayList<>(candidate.reviewFlags());
        for (ReceiptField field : ReceiptField.values()) {
            String value = candidate.value(field);
            if (value.isBlank()) {
                warnings.add("Missing " + field.label().toLowerCase(Locale.ROOT) + ".");
            }
            String confidence = candidate.confidence(field).trim().toLowerCase(Locale.ROOT);
            if (confidence.equals("low") || confidence.equals("uncertain") || confidence.equals("medium")) {
                warnings.add("Verify " + field.label().toLowerCase(Locale.ROOT)
                        + " (AI confidence: " + confidence + ").");
            } else {
                Optional<BigDecimal> numericConfidence = number(confidence);
                if (numericConfidence.isPresent() && numericConfidence.get().compareTo(new BigDecimal("0.7")) < 0) {
                    warnings.add("Verify " + field.label().toLowerCase(Locale.ROOT)
                            + " (AI confidence: " + confidence + ").");
                }
            }
        }

        validateDate(candidate.value(ReceiptField.DATE), warnings);
        BigDecimal lineSum = BigDecimal.ZERO;
        boolean allLineTotalsKnown = !candidate.lineItems().isEmpty();
        for (int i = 0; i < candidate.lineItems().size(); i++) {
            ImportedLineItem item = candidate.lineItems().get(i);
            String prefix = "Line item " + (i + 1) + ": ";
            if (item.description().isBlank()) warnings.add(prefix + "description is missing.");
            BigDecimal quantity = readNumber(item.quantity(), prefix + "quantity", warnings);
            BigDecimal unitPrice = readNumber(item.unitPrice(), prefix + "unit price", warnings);
            BigDecimal lineTotal = readNumber(item.lineTotal(), prefix + "line total", warnings);
            warnNegative(quantity, prefix + "quantity", warnings);
            warnNegative(unitPrice, prefix + "unit price", warnings);
            warnNegative(lineTotal, prefix + "line total", warnings);
            if (lineTotal == null) {
                allLineTotalsKnown = false;
            } else {
                lineSum = lineSum.add(lineTotal);
            }
            if (quantity != null && unitPrice != null && lineTotal != null) {
                BigDecimal expected = quantity.multiply(unitPrice).setScale(2, RoundingMode.HALF_UP);
                if (expected.compareTo(lineTotal.setScale(2, RoundingMode.HALF_UP)) != 0) {
                    warnings.add(prefix + "quantity × unit price does not match the extracted line total.");
                }
            }
        }

        BigDecimal subtotal = amount(candidate.value(ReceiptField.SUBTOTAL), "subtotal", warnings);
        BigDecimal tax = amount(candidate.value(ReceiptField.TAX), "tax", warnings);
        BigDecimal discount = amount(candidate.value(ReceiptField.DISCOUNT), "discount", warnings);
        BigDecimal grandTotal = amount(candidate.value(ReceiptField.GRAND_TOTAL), "grand total", warnings);
        warnNegative(subtotal, "subtotal", warnings);
        warnNegative(tax, "tax", warnings);
        warnNegative(discount, "discount", warnings);
        warnNegative(grandTotal, "grand total", warnings);

        if (allLineTotalsKnown && subtotal != null
                && lineSum.setScale(2, RoundingMode.HALF_UP).compareTo(subtotal.setScale(2, RoundingMode.HALF_UP)) != 0) {
            warnings.add("The extracted line totals do not add up to the subtotal.");
        }
        if (subtotal != null && tax != null && discount != null && grandTotal != null) {
            BigDecimal expected = subtotal.subtract(discount).add(tax).setScale(2, RoundingMode.HALF_UP);
            if (expected.compareTo(grandTotal.setScale(2, RoundingMode.HALF_UP)) != 0) {
                warnings.add("Subtotal − discount + tax does not match the grand total.");
            }
        }
        return List.copyOf(warnings);
    }

    private static void validateDate(String value, List<String> warnings) {
        if (value.isBlank()) return;
        Matcher slashDate = SLASH_DATE.matcher(value.trim());
        if (slashDate.matches()) {
            int first = Integer.parseInt(slashDate.group(1));
            int second = Integer.parseInt(slashDate.group(2));
            if (first <= 12 && second <= 12 && first != second) {
                warnings.add("Receipt date uses an ambiguous numeric format; verify the month and day.");
                return;
            }
        }
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                LocalDate.parse(value.trim(), format);
                return;
            } catch (DateTimeParseException ignored) {
                // Try the next explicitly supported date format.
            }
        }
        warnings.add("Receipt date could not be validated. Check the original document.");
    }

    private static BigDecimal amount(String value, String label, List<String> warnings) {
        if (value.isBlank()) return null;
        Optional<BigDecimal> number = number(value);
        if (number.isEmpty()) warnings.add("The extracted " + label + " is not a valid number.");
        return number.orElse(null);
    }

    private static BigDecimal readNumber(String value, String label, List<String> warnings) {
        if (value.isBlank()) {
            warnings.add("Missing " + label + ".");
            return null;
        }
        Optional<BigDecimal> number = number(value);
        if (number.isEmpty()) warnings.add("The extracted " + label + " is not a valid number.");
        return number.orElse(null);
    }

    private static Optional<BigDecimal> number(String value) {
        return ReceiptAmountParser.parse(value);
    }

    private static void warnNegative(BigDecimal value, String label, List<String> warnings) {
        if (value != null && value.signum() < 0) warnings.add("The extracted " + label + " is negative.");
    }

    private static DateTimeFormatter formatter(String pattern) {
        return new DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
                .toFormatter(Locale.US).withResolverStyle(ResolverStyle.STRICT);
    }
}
