package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.ingest.TxnKeys;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * JDK-only DocumentStore with the same document design as MongoDocumentStore
 * (one doc per dedup key, pre-aggregated per-account totals). Used by verify.sh,
 * SelfCheck and unit tests where no Mongo is running. Tracks examined-vs-returned
 * the same way the README reports for Mongo (index hits only).
 */
public final class InMemoryDocumentStore implements DocumentStore {

    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    /** _id (dedup key) -> txn, insertion order. */
    private final LinkedHashMap<String, NormalizedTxn> byId = new LinkedHashMap<>();
    /** messageId -> _id. */
    private final java.util.HashMap<String, String> byMessage = new java.util.HashMap<>();
    /** account -> per-category sums. */
    private final java.util.HashMap<String, Map<Category, BigDecimal>> totals = new java.util.HashMap<>();

    /** Last query stats, for tests/benchmarks: int[]{examined, returned}. */
    private int[] lastStats = new int[]{0, 0};

    public int[] lastStats() {
        return lastStats.clone();
    }

    @Override
    public synchronized void save(NormalizedTxn txn) {
        String id = TxnKeys.key(txn);
        NormalizedTxn existing = byId.get(id);
        if (existing == null) {
            byId.put(id, txn);
            for (String mid : txn.sourceMessageIds()) byMessage.put(mid, id);
            totals.computeIfAbsent(txn.accountLast4(), k -> {
                Map<Category, BigDecimal> m = new LinkedHashMap<>();
                for (Category c : Category.values()) m.put(c, ZERO);
                return m;
            });
            Map<Category, BigDecimal> t = totals.get(txn.accountLast4());
            t.put(txn.category(), t.get(txn.category()).add(txn.amount()));
        } else {
            TreeSet<String> merged = new TreeSet<>(existing.sourceMessageIds());
            merged.addAll(txn.sourceMessageIds());
            if (!merged.equals(new TreeSet<>(existing.sourceMessageIds()))
                    || existing.category() != txn.category()) {
                // Category change adjusts totals by delta (same as Mongo store).
                if (existing.category() != txn.category()) {
                    Map<Category, BigDecimal> t = totals.get(existing.accountLast4());
                    t.put(existing.category(),
                            t.get(existing.category()).subtract(existing.amount()));
                    t.put(txn.category(), t.get(txn.category()).add(txn.amount()));
                }
                byId.put(id, new NormalizedTxn(existing.accountLast4(), existing.occurredAt(),
                        existing.direction(), existing.amount(), txn.category(),
                        existing.merchant(), List.copyOf(merged)));
            }
            for (String mid : txn.sourceMessageIds()) byMessage.put(mid, id);
        }
    }

    @Override
    public synchronized List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        // Index {account, yearMonth, occurredAt}: examine only the bucket.
        List<NormalizedTxn> bucket = new ArrayList<>();
        for (NormalizedTxn t : byId.values()) {
            YearMonth ym = YearMonth.from(t.occurredAt()
                    .withOffsetSameInstant(in.simplifymoney.ledgersync.parse.Dates.IST));
            if (t.accountLast4().equals(accountLast4) && ym.equals(month)) bucket.add(t);
        }
        bucket.sort(Comparator.comparing(NormalizedTxn::occurredAt).reversed());
        lastStats = new int[]{bucket.size(), bucket.size()};
        return bucket;
    }

    @Override
    public synchronized Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        // Pre-aggregated totals doc: examine 1, return 1 (4 buckets inside).
        Map<Category, BigDecimal> t = totals.get(accountLast4);
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) {
            out.put(c, t == null ? ZERO : t.getOrDefault(c, ZERO));
        }
        lastStats = new int[]{t == null ? 0 : 1, 1};
        return out;
    }

    @Override
    public synchronized Optional<NormalizedTxn> byMessageId(String messageId) {
        String id = byMessage.get(messageId);
        if (id == null) {
            lastStats = new int[]{0, 0};
            return Optional.empty();
        }
        // Index on sourceMessageIds: examine 1, return 1.
        lastStats = new int[]{1, 1};
        return Optional.ofNullable(byId.get(id));
    }

    public synchronized List<NormalizedTxn> all() {
        return List.copyOf(byId.values());
    }

    public synchronized long count() {
        return byId.size();
    }
}
