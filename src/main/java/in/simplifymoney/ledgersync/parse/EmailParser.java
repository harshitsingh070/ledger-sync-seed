package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.RawMessage;
import java.util.Optional;

/**
 * Bank transaction alert emails.
 *
 * Not written yet. The corpus contains them and they are currently all dropped.
 */
public final class EmailParser implements MessageParser {

    // e.g.:
    // "Date: Wed, 01 Jul 2026 09:02:00 +0530\nSubject: ...\n\nYour account ending 4821
    //  has been credited with INR 45,000.\nMerchant / Remarks: SALARY CREDIT\nTransaction reference: ..."
    // Amount may be whole rupees ("INR 45,000", "Rs.2,750") — see INC-2026-09-11.
    private static final java.util.regex.Pattern TXN = java.util.regex.Pattern.compile(
            "Your account ending (?<acct>\\d{4}) has been (?<dir>credited|debited) with "
                    + "(?:Rs\\.?|INR)\\s*(?<amt>[0-9,]+(?:\\.[0-9]{2})?)\\.?"
                    + "\\s*\\nMerchant / Remarks: (?<merchant>.+?)\\nTransaction reference:",
            java.util.regex.Pattern.DOTALL);
    private static final java.util.regex.Pattern DATE_LINE = java.util.regex.Pattern.compile(
            "^Date:\\s*(.+)$", java.util.regex.Pattern.MULTILINE);

    @Override
    public boolean supports(RawMessage m) {
        return "email".equals(m.channel());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        java.util.regex.Matcher t = TXN.matcher(m.body());
        if (!t.find()) return Optional.empty();
        java.util.regex.Matcher d = DATE_LINE.matcher(m.body());
        if (!d.find()) return Optional.empty();
        java.time.OffsetDateTime at = Dates.emailToIst(d.group(1));
        java.math.BigDecimal amount = Amounts.first(m.body());
        if (at == null || amount == null) return Optional.empty();
        in.simplifymoney.ledgersync.model.Direction dir =
                "credited".equals(t.group("dir"))
                        ? in.simplifymoney.ledgersync.model.Direction.CREDIT
                        : in.simplifymoney.ledgersync.model.Direction.DEBIT;
        // Emails quote no balance.
        return Optional.of(new ParsedTxn(t.group("acct"), at, dir, amount,
                t.group("merchant").trim(), null, m.messageId()));
    }
}
