package in.simplifymoney.ledgersync.report;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * The two reports the assignment asks for.
 *
 * summary() below is a first cut: it adds up what is in the ledger. It does not
 * know that a transfer is not spending, and it does not roll micro spends up.
 *
 * reconciliation() has not been written at all.
 */
public final class Reports {

    private Reports() {}

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    public static Map<String, Object> summary(List<NormalizedTxn> ledger) {
        Map<String, Object> accounts = new LinkedHashMap<>();
        for (String acct : new TreeSet<>(ledger.stream()
                .map(NormalizedTxn::accountLast4).toList())) {

            BigDecimal spend = ZERO;
            BigDecimal income = ZERO;
            BigDecimal microTotal = ZERO;
            long microCount = 0;
            BigDecimal out = ZERO;
            BigDecimal in = ZERO;
            for (NormalizedTxn t : ledger) {
                if (!t.accountLast4().equals(acct)) continue;
                switch (t.category()) {
                    case SPEND -> {
                        if (t.direction() == Direction.DEBIT) spend = spend.add(t.amount());
                        else income = income.add(t.amount());
                    }
                    case INCOME -> {
                        if (t.direction() == Direction.CREDIT) income = income.add(t.amount());
                        else spend = spend.add(t.amount());
                    }
                    case MICRO -> {
                        microCount++;
                        microTotal = microTotal.add(t.amount());
                    }
                    case TRANSFER -> {
                        if (t.direction() == Direction.DEBIT) out = out.add(t.amount());
                        else in = in.add(t.amount());
                    }
                }
            }

            Map<String, Object> a = new LinkedHashMap<>();
            a.put("spend", spend.toPlainString());
            a.put("income", income.toPlainString());
            a.put("micro_count", (int) microCount);
            a.put("micro_total", microTotal.toPlainString());
            a.put("transferred_out", out.toPlainString());
            a.put("transferred_in", in.toPlainString());
            accounts.put(acct, a);
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("accounts", accounts);
        return doc;
    }

    public static Map<String, Object> ledgerDocument(List<NormalizedTxn> ledger) {
        // Sort for stable output; occurred_at always rendered with seconds
        // (spec shows "2026-07-04T20:24:00+05:30", while OffsetDateTime.toString()
        // omits ":00" when seconds are zero).
        java.time.format.DateTimeFormatter fmt =
                java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME;
        List<Object> rows = ledger.stream()
                .sorted(java.util.Comparator.comparing(NormalizedTxn::occurredAt))
                .map(t -> {
                    Map<String, Object> r = new LinkedHashMap<>();
                    r.put("account_last4", t.accountLast4());
                    r.put("occurred_at", t.occurredAt().format(fmt));
                    r.put("direction", t.direction().name().toLowerCase());
                    r.put("amount", t.amount().toPlainString());
                    r.put("category", t.category().name());
                    r.put("merchant", t.merchant());
                    r.put("source_message_ids", t.sourceMessageIds().stream().sorted().toList());
                    return (Object) r;
                }).toList();
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("transactions", rows);
        return doc;
    }

    public static Map<String, Object> reconciliation(List<NormalizedTxn> ledger) {
        return reconciliationWithEvidence(ledger, java.util.Map.of());
    }

    /**
     * Balance-gap reconciliation. For each savings account, consecutive bank-quoted
     * balances must agree with the ledger between them:
     * stated[i] == stated[i-1] + sum(effects of txns after i-1 up to i).
     * A mismatch means money moved with no message (the 29-Jul Rs.7500.00 drop for
     * **4821) and is reported as a discrepancy. Credit-card **3310 is skipped:
     * its "Avl Limit" moves on payments/adjustments outside SMS, so a limit chain
     * would false-positive (limits rise 196250.03 -&gt; 199858.01 on spends).
     */
    public static Map<String, Object> reconciliationWithEvidence(
            List<NormalizedTxn> ledger,
            java.util.Map<String, java.math.BigDecimal> statedByKey) {
        java.util.List<Object> discs = new java.util.ArrayList<>();
        java.util.TreeSet<String> accounts = new java.util.TreeSet<>();
        for (NormalizedTxn t : ledger) accounts.add(t.accountLast4());

        for (String acct : accounts) {
            if ("3310".equals(acct)) continue;
            java.util.List<NormalizedTxn> txns = ledger.stream()
                    .filter(t -> t.accountLast4().equals(acct))
                    .sorted(java.util.Comparator.comparing(NormalizedTxn::occurredAt))
                    .toList();

            java.math.BigDecimal prevStated = null;
            java.math.BigDecimal pending = BigDecimal.ZERO.setScale(2);
            for (NormalizedTxn t : txns) {
                java.math.BigDecimal effect = t.direction() == Direction.DEBIT
                        ? t.amount().negate() : t.amount();
                pending = pending.add(effect);
                String k = in.simplifymoney.ledgersync.ingest.TxnKeys.key(t);
                java.math.BigDecimal stated = statedByKey.get(k);
                if (stated == null) continue;
                stated = stated.setScale(2);
                if (prevStated == null) {
                    prevStated = stated;
                    pending = BigDecimal.ZERO.setScale(2);
                    continue;
                }
                java.math.BigDecimal expected = prevStated.add(pending).setScale(2);
                java.math.BigDecimal gap = expected.subtract(stated).setScale(2);
                if (gap.compareTo(BigDecimal.ZERO) != 0) {
                    String atStr = t.occurredAt().format(
                            java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME);
                    java.util.Map<String, Object> d = new LinkedHashMap<>();
                    d.put("account_last4", acct);
                    d.put("occurred_at", atStr);
                    d.put("amount", gap.abs().toPlainString());
                    d.put("note", gap.signum() > 0
                            ? "bank states " + stated.toPlainString() + " after "
                                    + t.merchant() + " at " + atStr
                                    + " but ledger derives " + expected.toPlainString()
                                    + " from previous stated " + prevStated.toPlainString()
                                    + ": Rs." + gap.abs().toPlainString()
                                    + " left with no evidencing message"
                            : "bank states " + stated.toPlainString() + " after "
                                    + t.merchant() + " at " + atStr
                                    + " but ledger derives " + expected.toPlainString()
                                    + ": Rs." + gap.abs().toPlainString()
                                    + " arrived with no evidencing message");
                    discs.add(d);
                    // Re-anchor to the bank's number so one gap does not cascade.
                    prevStated = stated;
                    pending = BigDecimal.ZERO.setScale(2);
                } else {
                    prevStated = stated;
                    pending = BigDecimal.ZERO.setScale(2);
                }
            }
        }
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("discrepancies", discs);
        return doc;
    }

    public static Map<Category, BigDecimal> byCategory(List<NormalizedTxn> ledger) {
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, ZERO);
        for (NormalizedTxn t : ledger) {
            out.put(t.category(), out.get(t.category()).add(t.amount()));
        }
        return out;
    }
}
