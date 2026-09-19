package in.simplifymoney.ledgersync.store;

import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import org.bson.Document;

/** Explain-only: prints examined vs returned for the three queries on an existing DB. */
public final class BenchExplain {
    private BenchExplain() {}

    public static void main(String[] args) {
        String conn = args.length > 0 ? args[0] : "mongodb://localhost:27017";
        String dbName = args.length > 1 ? args[1] : "ledger_bench";
        try (var client = MongoClients.create(conn)) {
            MongoDatabase db = client.getDatabase(dbName);
            MongoCollection<Document> txns = db.getCollection("txns");
            System.out.println("count=" + txns.countDocuments());
            Document q1 = txns.find(new Document("accountLast4", "4821").append("yearMonth", "2026-07"))
                    .sort(new Document("occurredAt", -1)).explain();
            Document q2 = db.getCollection("account_totals").find(new Document("_id", "4821")).explain();
            Document q3 = txns.find(new Document("sourceMessageIds", "m-bench-000123")).explain();
            System.out.println("Q1 " + shortStats(q1));
            System.out.println("Q2 " + shortStats(q2));
            System.out.println("Q3 " + shortStats(q3));
        }
    }

    private static String shortStats(Document explain) {
        try {
            Document exec = explain.get("executionStats", Document.class);
            if (exec != null) {
                return "totalDocsExamined=" + exec.get("totalDocsExamined")
                        + " nReturned=" + exec.get("nReturned");
            }
        } catch (Exception ignored) {
        }
        return explain.toJson();
    }
}
