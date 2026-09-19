package in.simplifymoney.ledgersync.store;

import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import org.bson.Document;

/**
 * Benchmarks the three DocumentStore queries at 100,000 transactions and prints
 * examined-vs-returned from Mongo explain() (totalDocsExamined / nReturned).
 * Run with mongo from `docker compose up` running:
 *   ./gradlew run --args="bench"  (wired in App) or java -cp ... DocStoreBench
 */
public final class DocStoreBench {

    private DocStoreBench() {}

    public static void main(String[] args) throws Exception {
        String conn = args.length > 0 ? args[0] : "mongodb://localhost:27017";
        String dbName = args.length > 1 ? args[1] : "ledger_bench";
        // Bulk-load 100k docs directly (insertMany, 5k batches) instead of one
        // save() per txn (find+insert+totals update = 300k round trips, too slow
        // for a benchmark). Examined/returned depend only on data shape+indexes,
        // not on the insert path; totals are built once at the end.
        ZoneOffset ist = ZoneOffset.ofHoursMinutes(5, 30);
        String[] accts = {"4821", "9075", "3310"};
        String[] merch = {"AMAZON PAY", "UPI/CHAIWALA", "IMPS/P2A/PARAG KAPOOR", "SWIGGY"};
        int n = 100_000;
        java.util.Map<String, java.util.Map<Category, BigDecimal>> totalsAcc = new java.util.HashMap<>();
        try (var client = MongoClients.create(conn)) {
            MongoDatabase db = client.getDatabase(dbName);
            db.getCollection("txns").drop();
            db.getCollection("account_totals").drop();
            MongoCollection<Document> txns = db.getCollection("txns");
            // Indexes first so the bulk load also pays index cost (honest).
            txns.createIndex(com.mongodb.client.model.Indexes.compoundIndex(
                    com.mongodb.client.model.Indexes.ascending("accountLast4"),
                    com.mongodb.client.model.Indexes.ascending("yearMonth"),
                    com.mongodb.client.model.Indexes.descending("occurredAt")));
            txns.createIndex(com.mongodb.client.model.Indexes.ascending("sourceMessageIds"));
            List<Document> batch = new java.util.ArrayList<>(5000);
            for (int i = 0; i < n; i++) {
                String acct = accts[i % accts.length];
                int month = 7 + (i % 3);
                int day = 1 + (i % 28);
                int hour = i % 24;
                OffsetDateTime at = OffsetDateTime.of(2026, month, day, hour, 10, 0, 0, ist);
                Direction dir = (i % 5 == 0) ? Direction.CREDIT : Direction.DEBIT;
                BigDecimal amt = new BigDecimal(String.format("%d.%02d", 10 + (i % 5000), i % 100))
                        .setScale(2);
                Category cat = (i % 11 == 0) ? Category.MICRO
                        : (i % 29 == 0) ? Category.TRANSFER
                                : (dir == Direction.DEBIT ? Category.SPEND : Category.INCOME);
                String m = merch[i % merch.length];
                YearMonth ym = YearMonth.of(2026, month);
                String id = acct + "|" + at.toLocalDateTime().withSecond(0).withNano(0)
                        + at.getOffset() + "|" + dir.name() + "|" + amt.toPlainString()
                        + "|" + m.toUpperCase() + "|bench" + i;
                batch.add(new Document("_id", id)
                        .append("accountLast4", acct)
                        .append("occurredAt", at.toString())
                        .append("yearMonth", ym.toString())
                        .append("direction", dir.name())
                        .append("amount", amt.toPlainString())
                        .append("category", cat.name())
                        .append("merchant", m)
                        .append("sourceMessageIds", List.of(String.format("m-bench-%06d", i))));
                totalsAcc.computeIfAbsent(acct, k -> {
                    var mm = new java.util.EnumMap<Category, BigDecimal>(Category.class);
                    for (var c : Category.values()) mm.put(c, BigDecimal.ZERO.setScale(2));
                    return mm;
                });
                var tm = totalsAcc.get(acct);
                tm.put(cat, tm.get(cat).add(amt));
                if (batch.size() == 5000) {
                    txns.insertMany(batch);
                    batch.clear();
                    System.out.println("inserted up to " + i);
                }
            }
            if (!batch.isEmpty()) txns.insertMany(batch);
            MongoCollection<Document> totals = db.getCollection("account_totals");
            List<Document> tdocs = new java.util.ArrayList<>();
            for (var e : totalsAcc.entrySet()) {
                Document d = new Document("_id", e.getKey());
                for (var c : Category.values()) {
                    d.append(c.name(), e.getValue().get(c).toPlainString());
                }
                tdocs.add(d);
            }
            if (!tdocs.isEmpty()) totals.insertMany(tdocs);
            System.out.println("inserted " + txns.countDocuments());
        }
        MongoDocumentStore store = new MongoDocumentStore(conn, dbName);

        try (var client = MongoClients.create(conn)) {
            MongoDatabase db = client.getDatabase(dbName);
            MongoCollection<Document> txns = db.getCollection("txns");
            // Q1: one account one month newest first. Pick 4821/2026-07.
            Document q1Explain = txns.find(
                    new Document("accountLast4", "4821").append("yearMonth", "2026-07"))
                    .sort(new Document("occurredAt", -1))
                    .explain();
            // Q2 is a single-doc lookup on account_totals.
            MongoCollection<Document> totals = db.getCollection("account_totals");
            Document q2Explain = totals.find(new Document("_id", "4821")).explain();
            // Q3: by message id (multikey index).
            Document q3Explain = txns.find(new Document("sourceMessageIds", "m-bench-000123")).explain();
            System.out.println("Q1 explain: " + q1Explain.toJson());
            System.out.println("Q2 explain: " + q2Explain.toJson());
            System.out.println("Q3 explain: " + q3Explain.toJson());
            System.out.println("Q1 examined/returned: " + stats(q1Explain));
            System.out.println("Q2 examined/returned: " + stats(q2Explain));
            System.out.println("Q3 examined/returned: " + stats(q3Explain));
        }
        // Also exercise the typed API once (sanity).
        System.out.println("Q1 typed count: " + store.forAccountMonth("4821", YearMonth.of(2026, 7)).size());
        System.out.println("Q2 typed: " + store.categoryTotals("4821"));
        System.out.println("Q3 typed: " + store.byMessageId("m-bench-000123").isPresent());
        store.close();
    }

    private static String stats(Document explain) {
        try {
            Document stage = explain.get("queryPlanner", Document.class)
                    .get("winningPlan", Document.class);
            // executionStats holds the numbers we need.
            Document exec = explain.get("executionStats", Document.class);
            if (exec != null) {
                return "totalDocsExamined=" + exec.get("totalDocsExamined")
                        + " nReturned=" + exec.get("nReturned");
            }
        } catch (Exception e) {
            // fall through
        }
        return explain.toJson();
    }
}
