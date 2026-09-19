package in.simplifymoney.ledgersync.store;

/**
 * Moves everything already in the SQL store into the document store.
 *
 * NOT IMPLEMENTED - this is yours.
 *
 * Two things to know before you start:
 *  - the SQL store is not clean. It has been running without a uniqueness
 *    guarantee for a long time
 *  - this will be run more than once, including after a partial failure
 */
public final class Backfill {

    private final LedgerStore source;
    private final DocumentStore target;

    public Backfill(LedgerStore source, DocumentStore target) {
        this.source = source;
        this.target = target;
    }

    /**
     * Moves everything in SQL across, idempotently.
     *
     * <p>The SQL store ran without a uniqueness guarantee (V2__seed.sql has exact
     * duplicate rows and same-txn-different-message rows). We dedup by
     * TxnKeys.key in memory first, then upsert each logical txn: DocumentStore.save
     * merges on _id, so a second run — including after a partial failure where
     * only the first N docs landed — writes 0 new docs and only re-merges ids.
     */
    public Result run() {
        var rows = source.all();
        long read = rows.size();
        // Dedup SQL rows that predate idempotent save (legacy duplicates).
        java.util.LinkedHashMap<String, in.simplifymoney.ledgersync.model.NormalizedTxn> distinct =
                new java.util.LinkedHashMap<>();
        for (var t : rows) {
            String k = in.simplifymoney.ledgersync.ingest.TxnKeys.key(t);
            var prev = distinct.get(k);
            if (prev == null) {
                distinct.put(k, t);
            } else {
                java.util.TreeSet<String> ids = new java.util.TreeSet<>(prev.sourceMessageIds());
                ids.addAll(t.sourceMessageIds());
                // Keep the prevailing category/merchant; keys already match.
                distinct.put(k, new in.simplifymoney.ledgersync.model.NormalizedTxn(
                        prev.accountLast4(), prev.occurredAt(), prev.direction(), prev.amount(),
                        prev.category(), prev.merchant(), java.util.List.copyOf(ids)));
            }
        }
        long written = 0;
        long skipped = 0;
        for (var t : distinct.values()) {
            // Idempotency probe for InMemoryDocumentStore; Mongo save() is itself
            // an upsert that no-ops on identical _id+ids, so counting via a
            // pre-check keeps Result honest on both implementations.
            boolean alreadyThere = false;
            if (!t.sourceMessageIds().isEmpty()) {
                var probe = target.byMessageId(t.sourceMessageIds().get(0));
                if (probe.isPresent()
                        && in.simplifymoney.ledgersync.ingest.TxnKeys.key(probe.get())
                                .equals(in.simplifymoney.ledgersync.ingest.TxnKeys.key(t))
                        && new java.util.HashSet<>(probe.get().sourceMessageIds())
                                .containsAll(t.sourceMessageIds())) {
                    alreadyThere = true;
                }
            }
            target.save(t);
            if (alreadyThere) skipped++;
            else written++;
        }
        // Legacy duplicate SQL rows that collapsed in memory count as skipped too.
        skipped += read - distinct.size();
        return new Result(read, written, skipped);
    }

    public record Result(long read, long written, long skipped) {}
}
