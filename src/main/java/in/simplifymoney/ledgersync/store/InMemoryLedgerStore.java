package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Used by SelfCheck and by tests. Idempotent: merges on dedup key. */
public final class InMemoryLedgerStore implements LedgerStore {

    private final java.util.LinkedHashMap<String, NormalizedTxn> byKey = new java.util.LinkedHashMap<>();
    private final java.util.HashMap<String, java.math.BigDecimal> balances = new java.util.HashMap<>();

    private static String keyOf(NormalizedTxn t) {
        return in.simplifymoney.ledgersync.ingest.TxnKeys.key(t);
    }

    @Override public synchronized void save(NormalizedTxn txn) {
        saveWithEvidence(txn, null);
    }

    @Override public synchronized void saveWithEvidence(
            NormalizedTxn txn, java.math.BigDecimal statedBalance) {
        String k = keyOf(txn);
        NormalizedTxn existing = byKey.get(k);
        if (existing == null) {
            byKey.put(k, txn);
        } else {
            java.util.TreeSet<String> ids = new java.util.TreeSet<>(existing.sourceMessageIds());
            ids.addAll(txn.sourceMessageIds());
            if (!ids.equals(new java.util.TreeSet<>(existing.sourceMessageIds()))) {
                byKey.put(k, new NormalizedTxn(existing.accountLast4(), existing.occurredAt(),
                        existing.direction(), existing.amount(), existing.category(),
                        existing.merchant(), java.util.List.copyOf(ids)));
            }
        }
        if (statedBalance != null) {
            balances.putIfAbsent(k, statedBalance);
        }
    }

    @Override public synchronized java.util.List<NormalizedTxn> all() {
        return java.util.List.copyOf(byKey.values());
    }

    @Override public synchronized java.util.Map<String, java.math.BigDecimal> statedBalances() {
        return java.util.Map.copyOf(balances);
    }

    @Override public synchronized long count() { return byKey.size(); }
}
