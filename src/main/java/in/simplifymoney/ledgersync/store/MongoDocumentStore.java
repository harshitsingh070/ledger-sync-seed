package in.simplifymoney.ledgersync.store;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.Indexes;
import com.mongodb.client.model.ReplaceOptions;
import in.simplifymoney.ledgersync.ingest.TxnKeys;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import org.bson.Document;

/**
 * MongoDB document store. One collection holds one document per real transaction;
 * a second tiny collection holds pre-aggregated per-account category totals so Q2
 * is a single-document lookup.
 *
 * <p>Document (collection {@code txns}):
 * <pre>
 * {
 *   _id: "&lt;dedup key&gt;",          // TxnKeys.key — idempotent upsert key
 *   accountLast4: "4821",
 *   occurredAt: "2026-07-04T07:19:00+05:30", // ISO, IST
 *   yearMonth: "2026-07",              // for Q1 month filter
 *   direction: "DEBIT",
 *   amount: "5.00",                    // string, exactly 2dp — money stays exact
 *   category: "MICRO",
 *   merchant: "UPI/WATER CAN",         // display spelling, first seen
 *   sourceMessageIds: ["m-..."]        // sorted, every evidencing upload
 * }
 * </pre>
 *
 * <p>Indexes:
 * <ul>
 *   <li>{@code {accountLast4:1, yearMonth:1, occurredAt:-1}} — Q1 hits only the
 *       requested account-month, newest first.
 *   <li>{@code {sourceMessageIds:1}} multikey — Q3 hits one doc by message id.
 *   <li>{@code account_totals._id = accountLast4} — Q2 is one lookup.
 * </ul>
 */
public final class MongoDocumentStore implements DocumentStore, AutoCloseable {

    private static final String TXNS = "txns";
    private static final String TOTALS = "account_totals";
    private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

    private final MongoClient client;
    private final MongoDatabase db;
    private final MongoCollection<Document> txns;
    private final MongoCollection<Document> totals;

    public MongoDocumentStore(String connectionString, String dbName) {
        this.client = MongoClients.create(connectionString);
        this.db = client.getDatabase(dbName);
        this.txns = db.getCollection(TXNS);
        this.totals = db.getCollection(TOTALS);
        ensureIndexes();
    }

    /** Defaults for `docker compose up`: mongo:7 on localhost. */
    public MongoDocumentStore() {
        this("mongodb://localhost:27017", "ledger");
    }

    private void ensureIndexes() {
        txns.createIndex(Indexes.compoundIndex(
                Indexes.ascending("accountLast4"),
                Indexes.ascending("yearMonth"),
                Indexes.descending("occurredAt")),
                new IndexOptions().name("q1_account_month"));
        txns.createIndex(Indexes.ascending("sourceMessageIds"),
                new IndexOptions().name("q3_message"));
    }

    @Override
    public void save(NormalizedTxn txn) {
        String id = TxnKeys.key(txn);
        YearMonth ym = YearMonth.from(txn.occurredAt().withOffsetSameInstant(
                in.simplifymoney.ledgersync.parse.Dates.IST));
        Document existing = txns.find(Filters.eq("_id", id)).first();
        if (existing == null) {
            Document doc = toDoc(id, txn, ym);
            txns.insertOne(doc);
            updateTotalsOnInsert(txn);
        } else {
            @SuppressWarnings("unchecked")
            List<String> cur = (List<String>) existing.get("sourceMessageIds", List.class);
            TreeSet<String> merged = new TreeSet<>(cur == null ? List.of() : cur);
            merged.addAll(txn.sourceMessageIds());
            boolean idsChanged = merged.size() != (cur == null ? 0 : cur.size());
            String curCat = existing.getString("category");
            String curMerch = existing.getString("merchant");
            boolean catChanged = curCat == null || !curCat.equals(txn.category().name());
            if (!idsChanged && !catChanged) return;
            // Totals only depend on (account, category, amount). Merging new source
            // ids for the same logical txn does not change money, so totals are
            // untouched here. A category change (re-ingest with better transfer
            // detection) adjusts totals by delta.
            if (catChanged) {
                adjustTotalsOnCategoryChange(txn.accountLast4(),
                        Category.valueOf(curCat), txn.category(), txn.amount(), txn.direction());
            }
            Document updated = new Document(existing);
            updated.put("sourceMessageIds", new ArrayList<>(merged));
            updated.put("category", txn.category().name());
            if (curMerch == null) updated.put("merchant", txn.merchant());
            txns.replaceOne(Filters.eq("_id", id), updated);
        }
    }

    private static Document toDoc(String id, NormalizedTxn t, YearMonth ym) {
        return new Document("_id", id)
                .append("accountLast4", t.accountLast4())
                .append("occurredAt", t.occurredAt().toString())
                .append("yearMonth", ym.toString())
                .append("direction", t.direction().name())
                .append("amount", t.amount().toPlainString())
                .append("category", t.category().name())
                .append("merchant", t.merchant())
                .append("sourceMessageIds", new ArrayList<>(t.sourceMessageIds()));
    }

    // ---- Q1 ----
    @Override
    public List<NormalizedTxn> forAccountMonth(String accountLast4, YearMonth month) {
        List<NormalizedTxn> out = new ArrayList<>();
        for (Document d : txns.find(Filters.and(
                        Filters.eq("accountLast4", accountLast4),
                        Filters.eq("yearMonth", month.toString())))
                .sort(new Document("occurredAt", -1))) {
            out.add(fromDoc(d));
        }
        return out;
    }

    // ---- Q2 ----
    @Override
    public Map<Category, BigDecimal> categoryTotals(String accountLast4) {
        Document t = totals.find(Filters.eq("_id", accountLast4)).first();
        Map<Category, BigDecimal> out = new LinkedHashMap<>();
        for (Category c : Category.values()) out.put(c, ZERO);
        if (t == null) return out;
        for (Category c : Category.values()) {
            Object v = t.get(c.name());
            if (v != null) out.put(c, new BigDecimal(v.toString()).setScale(2));
        }
        return out;
    }

    // ---- Q3 ----
    @Override
    public Optional<NormalizedTxn> byMessageId(String messageId) {
        Document d = txns.find(Filters.eq("sourceMessageIds", messageId)).first();
        return d == null ? Optional.empty() : Optional.of(fromDoc(d));
    }

    static NormalizedTxn fromDoc(Document d) {
        @SuppressWarnings("unchecked")
        List<String> ids = (List<String>) d.get("sourceMessageIds", List.class);
        return new NormalizedTxn(
                d.getString("accountLast4"),
                java.time.OffsetDateTime.parse(d.getString("occurredAt")),
                Direction.valueOf(d.getString("direction")),
                new BigDecimal(d.getString("amount")).setScale(2),
                Category.valueOf(d.getString("category")),
                d.getString("merchant"),
                ids == null ? List.of() : List.copyOf(new TreeSet<>(ids)));
    }

    // ---- totals maintenance (Q2 pre-aggregation) ----

    private void updateTotalsOnInsert(NormalizedTxn t) {
        // Increment the category bucket by amount. Micro/transfer/spend/income are
        // all just per-category sums here; Reports.summary splits them for display.
        Document cur = totals.find(Filters.eq("_id", t.accountLast4())).first();
        if (cur == null) {
            Document doc = new Document("_id", t.accountLast4());
            for (Category c : Category.values()) doc.append(c.name(), "0.00");
            doc.put(t.category().name(), t.amount().toPlainString());
            if (t.category() == Category.MICRO) doc.append("MICRO_COUNT", 1);
            else doc.append("MICRO_COUNT", 0);
            totals.insertOne(doc);
            return;
        }
        BigDecimal prev = new BigDecimal(cur.getString(t.category().name()));
        BigDecimal next = prev.add(t.amount()).setScale(2);
        Document update = new Document("$set",
                new Document(t.category().name(), next.toPlainString()));
        if (t.category() == Category.MICRO) {
            Object c = cur.get("MICRO_COUNT");
            int n = c instanceof Number num ? num.intValue() : Integer.parseInt(c.toString());
            update.append("$set", ((Document) update.get("$set")).append("MICRO_COUNT", n + 1));
            // $set with two fields: rebuild correctly below.
            totals.updateOne(Filters.eq("_id", t.accountLast4()),
                    new Document("$set", new Document(t.category().name(), next.toPlainString())
                            .append("MICRO_COUNT", n + 1)));
        } else {
            totals.updateOne(Filters.eq("_id", t.accountLast4()), update);
        }
    }

    private void adjustTotalsOnCategoryChange(String acct, Category from, Category to,
            BigDecimal amount, Direction dir) {
        if (from == to) return;
        Document cur = totals.find(Filters.eq("_id", acct)).first();
        if (cur == null) return;
        BigDecimal f = new BigDecimal(cur.getString(from.name())).subtract(amount).setScale(2);
        BigDecimal tt = new BigDecimal(cur.getString(to.name())).add(amount).setScale(2);
        Document set = new Document(from.name(), f.toPlainString())
                .append(to.name(), tt.toPlainString());
        if (from == Category.MICRO || to == Category.MICRO) {
            Object c = cur.get("MICRO_COUNT");
            int n = c instanceof Number num ? num.intValue() : Integer.parseInt(c.toString());
            if (from == Category.MICRO) n--;
            if (to == Category.MICRO) n++;
            set.append("MICRO_COUNT", Math.max(0, n));
        }
        totals.updateOne(Filters.eq("_id", acct), new Document("$set", set));
    }

    /** All docs, for Backfill verification and ConsistencyChecker scans. */
    public List<NormalizedTxn> all() {
        List<NormalizedTxn> out = new ArrayList<>();
        for (Document d : txns.find()) out.add(fromDoc(d));
        return out;
    }

    public long count() {
        return txns.countDocuments();
    }

    public void dropForTests() {
        txns.deleteMany(new Document());
        totals.deleteMany(new Document());
    }

    @Override
    public void close() {
        try {
            client.close();
        } catch (Exception ignored) {
        }
    }
}
