package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Dates;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * Dedup key: one real transaction, no matter how many uploads evidence it.
 *
 * <p>message_id identifies the upload, not the message (RawMessage javadoc): the same
 * SMS re-read from the inbox is uploaded again with a new id, and the same
 * transaction arrives twice more as SMS+email with different bodies. All of those
 * must collapse to one ledger row.
 *
 * <p>Key = account | occurredAt (same instant in IST) | direction | amount (2dp) |
 * merchant normalised (upper, trimmed). Merchant is included so two different
 * purchases in the same minute never merge; SMS and email for the same purchase
 * carry the same merchant string, so they still merge (verified: 0 merchant
 * mismatches within merged groups on corpus-a).
 */
public final class TxnKeys {

    private TxnKeys() {}

    public static String normalizeMerchant(String merchant) {
        return merchant == null ? "" : merchant.trim().toUpperCase();
    }

    public static OffsetDateTime normalizeTime(OffsetDateTime at) {
        if (at == null) return null;
        return at.withOffsetSameInstant(Dates.IST);
    }

    public static String amountString(BigDecimal amount) {
        return amount.setScale(2).toPlainString();
    }

    public static String key(String account, OffsetDateTime at, Direction dir,
            BigDecimal amount, String merchant) {
        OffsetDateTime n = normalizeTime(at);
        // Seconds are always 00 in this data, but truncate defensively so
        // "09:02:00" (email) and "09:02" (sms) key identically.
        String when = n == null ? "null"
                : n.toLocalDateTime().withSecond(0).withNano(0).toString()
                        + n.getOffset().toString();
        return account + "|" + when + "|" + dir.name() + "|"
                + amountString(amount) + "|" + normalizeMerchant(merchant);
    }

    public static String key(ParsedTxn p) {
        return key(p.accountLast4(), p.occurredAt(), p.direction(), p.amount(), p.merchant());
    }

    public static String key(NormalizedTxn t) {
        return key(t.accountLast4(), t.occurredAt(), t.direction(), t.amount(), t.merchant());
    }

    /** UPI debit test for MICRO: merchant "UPI/..." or "UPI ..." (covers "UPI MANDATE VERIFY"). */
    public static boolean isUpi(String merchant) {
        String u = normalizeMerchant(merchant);
        return u.startsWith("UPI/") || u.startsWith("UPI ");
    }

    public static boolean isMicro(Direction dir, BigDecimal amount, String merchant) {
        return dir == Direction.DEBIT
                && amount.compareTo(new BigDecimal("100.00")) <= 0
                && isUpi(merchant);
    }
}
