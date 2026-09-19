package in.simplifymoney.ledgersync.parse;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ICICI Bank SMS. Handles both shapes in the corpus:
 * V1 "Dear Customer, Acct XX.... is debited with ... on dd/MM/yyyy HH:mm. Info: ...",
 * V2 "ICICI Bank Acct XX.... Dr/Cr INR ... on dd-MMM-yyyy HH:mm; ... ref no ... BalAvl ...".
 * Anything else from this sender (e.g. pre-approved loan promos) is not a
 * transaction and returns empty.
 */
public final class IciciSmsParser implements MessageParser {

    public static final String SENDER = "VM-ICICIB-T";

    private static final Pattern V1 = Pattern.compile(
            "Acct XX(?<acct>\\d{4}) is (?<dir>debited|credited) with .*? "
                    + "on (?<when>\\d{2}/\\d{2}/\\d{4} \\d{2}:\\d{2})\\. "
                    + "Info: (?<merchant>[^.]+)\\.");

    // Second shape, e.g.:
    // "ICICI Bank Acct XX9075 Dr INR 5 on 23-Jul-2026 18:41; UPI/BARBER ref no 154245459403. BalAvl Rs 52,841.30"
    // "ICICI Bank Acct XX9075 Cr INR 5000.00 on 01-Aug-2026 14:22; IMPS/P2A/PARAG KAPOOR ref no 908129880743. BalAvl Rs 52,928.02"
    // Amount may be whole rupees ("INR 5", "INR 4,200") — see Amounts / INC-2026-09-11.
    private static final Pattern V2 = Pattern.compile(
            "ICICI Bank Acct XX(?<acct>\\d{4}) (?<dir>Dr|Cr) INR\\s*(?<amt>[0-9,]+(?:\\.[0-9]{2})?) "
                    + "on (?<when>\\d{2}-[A-Za-z]{3}-\\d{4} \\d{2}:\\d{2});\\s*(?<merchant>[^;]+?) ref no");

    @Override
    public boolean supports(RawMessage m) {
        return "sms".equals(m.channel()) && SENDER.equals(m.sender());
    }

    @Override
    public Optional<ParsedTxn> parse(RawMessage m) {
        Matcher v1 = V1.matcher(m.body());
        if (v1.find()) {
            BigDecimal amount = Amounts.first(m.body());
            OffsetDateTime at = Dates.ist(v1.group("when"));
            if (amount == null || at == null) return Optional.empty();

            Direction d = "debited".equals(v1.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            return Optional.of(new ParsedTxn(v1.group("acct"), at, d, amount,
                    v1.group("merchant").trim(), Amounts.statedBalance(m.body()),
                    m.messageId()));
        }

        Matcher v2 = V2.matcher(m.body());
        if (v2.find()) {
            BigDecimal amount = Amounts.first(m.body());
            OffsetDateTime at = Dates.ist(v2.group("when"));
            if (amount == null || at == null) return Optional.empty();
            Direction d = "Dr".equals(v2.group("dir")) ? Direction.DEBIT : Direction.CREDIT;
            return Optional.of(new ParsedTxn(v2.group("acct"), at, d, amount,
                    v2.group("merchant").trim(), Amounts.statedBalance(m.body()),
                    m.messageId()));
        }
        // Promotional ("pre-approved Personal Loan"), phishing-adjacent etc.:
        // not a transaction.
        return Optional.empty();
    }
}
