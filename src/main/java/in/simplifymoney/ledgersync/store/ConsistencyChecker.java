package in.simplifymoney.ledgersync.store;

import java.util.List;

/**
 * Proves the two stores agree, and says precisely where they do not.
 *
 * NOT IMPLEMENTED - this is yours.
 *
 * We will run your checker against a document store we have deliberately
 * altered. It has to find what we changed and name it. A checker that only
 * compares row counts will not.
 */
public final class ConsistencyChecker {

    private final LedgerStore sql;
    private final DocumentStore documents;

    public ConsistencyChecker(LedgerStore sql, DocumentStore documents) {
        this.sql = sql;
        this.documents = documents;
    }

    /**
     * Field-level comparison, not row counts. Both sides are reduced to logical
     * transactions keyed by TxnKeys.key (so legacy SQL duplicates collapse), then
     * compared key by key: missing/extra docs plus per-field mismatches (amount,
     * direction, category, merchant normalised, occurredAt instant, source-id set).
     * A deliberately altered doc (changed amount, deleted txn, etc.) is named
     * precisely, e.g. {@code txn 4821|...|UPI/WATER CAN: amount differs}.
     */
    public List<Divergence> check() {
        java.util.Map<String, in.simplifymoney.ledgersync.model.NormalizedTxn> sqlMap =
                dedup(sql.all());
        java.util.Map<String, in.simplifymoney.ledgersync.model.NormalizedTxn> docMap =
                fetchDocuments();

        java.util.TreeSet<String> keys = new java.util.TreeSet<>();
        keys.addAll(sqlMap.keySet());
        keys.addAll(docMap.keySet());

        List<Divergence> out = new java.util.ArrayList<>();
        for (String k : keys) {
            var s = sqlMap.get(k);
            var d = docMap.get(k);
            // Keys embed merchant/amount/time, so a changed amount/merchant/time
            // usually shows up as missing+extra rather than same-key mismatch.
            // Report both sides explicitly so the alteration is identifiable.
            if (s == null) {
                out.add(new Divergence("extra txn in documents not in SQL: " + k,
                        "(absent)", describe(d)));
                continue;
            }
            if (d == null) {
                // Try to find the altered counterpart: same account+time but
                // different amount/merchant (the common deliberate change).
                String alt = findAlteredCounterpart(s, docMap);
                out.add(new Divergence("missing txn in documents (altered or deleted): " + k,
                        describe(s), alt == null ? "(absent)" : "altered as " + alt));
                continue;
            }
            compareFields(k, s, d, out);
        }
        return out;
    }

    private static java.util.Map<String, in.simplifymoney.ledgersync.model.NormalizedTxn> dedup(
            java.util.List<in.simplifymoney.ledgersync.model.NormalizedTxn> rows) {
        java.util.LinkedHashMap<String, in.simplifymoney.ledgersync.model.NormalizedTxn> m =
                new java.util.LinkedHashMap<>();
        for (var t : rows) {
            String k = in.simplifymoney.ledgersync.ingest.TxnKeys.key(t);
            var prev = m.get(k);
            if (prev == null) m.put(k, t);
            else {
                java.util.TreeSet<String> ids = new java.util.TreeSet<>(prev.sourceMessageIds());
                ids.addAll(t.sourceMessageIds());
                m.put(k, new in.simplifymoney.ledgersync.model.NormalizedTxn(prev.accountLast4(),
                        prev.occurredAt(), prev.direction(), prev.amount(), prev.category(),
                        prev.merchant(), java.util.List.copyOf(ids)));
            }
        }
        return m;
    }

    private java.util.Map<String, in.simplifymoney.ledgersync.model.NormalizedTxn> fetchDocuments() {
        // Prefer a full scan when available. Both bundled implementations expose
        // all(), but MongoDocumentStore lives behind the driver (excluded from
        // verify.sh's JDK-only javac), so reach it reflectively to keep this file
        // compilable with a plain JDK.
        try {
            var m = documents.getClass().getMethod("all");
            @SuppressWarnings("unchecked")
            var all = (java.util.List<in.simplifymoney.ledgersync.model.NormalizedTxn>) m.invoke(documents);
            return dedup(all);
        } catch (NoSuchMethodException e) {
            // Fallback via the three queries: probe every SQL message id.
        } catch (Exception e) {
            throw new IllegalStateException("could not scan document store", e);
        }
        java.util.Map<String, in.simplifymoney.ledgersync.model.NormalizedTxn> m =
                new java.util.LinkedHashMap<>();
        for (var t : sql.all()) {
            for (String mid : t.sourceMessageIds()) {
                var hit = documents.byMessageId(mid);
                hit.ifPresent(h -> m.putIfAbsent(
                        in.simplifymoney.ledgersync.ingest.TxnKeys.key(h), h));
            }
        }
        // Also page each account-month touched by SQL so extra docs that share no
        // message id (inserted directly) are still seen.
        java.util.TreeSet<String> accts = new java.util.TreeSet<>();
        java.util.TreeSet<java.time.YearMonth> months = new java.util.TreeSet<>();
        for (var t : sql.all()) {
            accts.add(t.accountLast4());
            months.add(java.time.YearMonth.from(t.occurredAt().withOffsetSameInstant(
                    in.simplifymoney.ledgersync.parse.Dates.IST)));
        }
        for (String a : accts) {
            for (java.time.YearMonth ym : months) {
                for (var h : documents.forAccountMonth(a, ym)) {
                    m.putIfAbsent(in.simplifymoney.ledgersync.ingest.TxnKeys.key(h), h);
                }
            }
        }
        return m;
    }

    private static void compareFields(String k,
            in.simplifymoney.ledgersync.model.NormalizedTxn s,
            in.simplifymoney.ledgersync.model.NormalizedTxn d,
            List<Divergence> out) {
        if (s.amount().compareTo(d.amount()) != 0) {
            out.add(new Divergence("txn " + k + ": amount differs",
                    s.amount().toPlainString(), d.amount().toPlainString()));
        }
        if (s.direction() != d.direction()) {
            out.add(new Divergence("txn " + k + ": direction differs",
                    s.direction().name(), d.direction().name()));
        }
        if (s.category() != d.category()) {
            out.add(new Divergence("txn " + k + ": category differs",
                    s.category().name(), d.category().name()));
        }
        if (!in.simplifymoney.ledgersync.ingest.TxnKeys.normalizeMerchant(s.merchant())
                .equals(in.simplifymoney.ledgersync.ingest.TxnKeys.normalizeMerchant(d.merchant()))) {
            out.add(new Divergence("txn " + k + ": merchant differs", s.merchant(), d.merchant()));
        }
        if (!s.occurredAt().isEqual(d.occurredAt())) {
            out.add(new Divergence("txn " + k + ": occurredAt differs",
                    s.occurredAt().toString(), d.occurredAt().toString()));
        }
        var si = new java.util.TreeSet<>(s.sourceMessageIds());
        var di = new java.util.TreeSet<>(d.sourceMessageIds());
        if (!si.equals(di)) {
            out.add(new Divergence("txn " + k + ": source_message_ids differ",
                    si.toString(), di.toString()));
        }
    }

    private static String findAlteredCounterpart(
            in.simplifymoney.ledgersync.model.NormalizedTxn s,
            java.util.Map<String, in.simplifymoney.ledgersync.model.NormalizedTxn> docMap) {
        for (var e : docMap.entrySet()) {
            var d = e.getValue();
            if (d.accountLast4().equals(s.accountLast4())
                    && d.occurredAt().isEqual(s.occurredAt())
                    && d.direction() == s.direction()) {
                return e.getKey() + " -> " + describe(d);
            }
        }
        return null;
    }

    private static String describe(in.simplifymoney.ledgersync.model.NormalizedTxn t) {
        if (t == null) return "(absent)";
        return t.accountLast4() + "|" + t.occurredAt() + "|" + t.direction()
                + "|" + t.amount().toPlainString() + "|" + t.category()
                + "|" + t.merchant() + "|" + t.sourceMessageIds();
    }

    /** One place the two stores disagree. */
    public record Divergence(String what, String inSql, String inDocuments) {}
}
