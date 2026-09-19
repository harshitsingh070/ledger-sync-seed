package in.simplifymoney.ledgersync.parse;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Locale;

/**
 * Bank SMS carry a local date and time and no timezone. The customer, the bank
 * and the branch are all in India, so these are IST.
 */
public final class Dates {

    private Dates() {}

    public static final ZoneOffset IST = ZoneOffset.ofHoursMinutes(5, 30);

    private static final List<DateTimeFormatter> SMS_FORMATS = List.of(
            DateTimeFormatter.ofPattern("dd-MM-yy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd MMM yy HH:mm", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("dd-MMM-yyyy HH:mm", Locale.ENGLISH));

    /** Parse a local date-time written by a bank, as IST. */
    public static OffsetDateTime ist(String dateAndTime) {
        for (DateTimeFormatter f : SMS_FORMATS) {
            try {
                return LocalDateTime.parse(dateAndTime.trim(), f).atOffset(IST);
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }

    /**
     * Parse an email Date header (RFC 1123, e.g. "Wed, 01 Jul 2026 09:02:00 +0530")
     * and return the same instant in IST. Most headers are already +0530; one in
     * the corpus is +0000 (18 Jul 18:50 UTC == 19 Jul 00:20 IST) and must be
     * converted, otherwise the SMS and the email for the same transaction land
     * on different keys and the transaction is double-counted.
     */
    public static OffsetDateTime emailToIst(String dateHeader) {
        if (dateHeader == null) return null;
        String s = dateHeader.trim();
        // Strip a leading "Date:" if the caller passed the whole line.
        if (s.regionMatches(true, 0, "Date:", 0, 5)) s = s.substring(5).trim();
        try {
            OffsetDateTime odt = OffsetDateTime.parse(s, DateTimeFormatter.RFC_1123_DATE_TIME);
            return odt.withOffsetSameInstant(IST);
        } catch (DateTimeParseException ignored) {
        }
        // Fallback: "01 Jul 2026 09:02:00" without weekday/timezone -> assume IST.
        List<DateTimeFormatter> fallbacks = List.of(
                DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm:ss", Locale.ENGLISH),
                DateTimeFormatter.ofPattern("dd MMM yyyy HH:mm", Locale.ENGLISH));
        for (DateTimeFormatter f : fallbacks) {
            try {
                return LocalDateTime.parse(s, f).atOffset(IST);
            } catch (DateTimeParseException ignored2) {
            }
        }
        return null;
    }
}
