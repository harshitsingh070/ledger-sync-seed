package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.SqlLedgerStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Command line entry point. Core commands (migrate/ingest/report) are JDK-only
 * and work via ./verify.sh with no network. Document-store commands need the
 * Mongo driver + `docker compose up` mongo, so they load MongoDocumentStore
 * reflectively to keep this file compilable with plain javac.
 *
 *   migrate                  apply db/migration/*.sql
 *   ingest  <corpus.jsonl>   read a corpus into the ledger
 *   report  <out-dir>        write ledger.json, summary.json, reconciliation.json
 *   backfill                 SQL -> document store (idempotent, re-runnable)
 *   doccheck                 compare SQL vs document store, print divergences
 */
public final class App {

    private static final String MONGO_CLS =
            "in.simplifymoney.ledgersync.store.MongoDocumentStore";

    private static final Path DB = Path.of("data", "ledger");
    private static final Path MIGRATIONS = Path.of("db", "migration");

    /** Reflective so App stays compilable with plain javac (no Mongo driver). */
    private static in.simplifymoney.ledgersync.store.DocumentStore mongoStore() throws Exception {
        String conn = System.getenv().getOrDefault("MONGO_URI", "mongodb://localhost:27017");
        String db = System.getenv().getOrDefault("MONGO_DB", "ledger");
        var cls = Class.forName(MONGO_CLS);
        var ctor = cls.getConstructor(String.class, String.class);
        return (in.simplifymoney.ledgersync.store.DocumentStore) ctor.newInstance(conn, db);
    }

    private static long docCount(in.simplifymoney.ledgersync.store.DocumentStore docs)
            throws Exception {
        try {
            var m = docs.getClass().getMethod("count");
            return (long) m.invoke(docs);
        } catch (NoSuchMethodException e) {
            return -1;
        }
    }

    private static void closeQuietly(Object o) {
        try {
            var m = o.getClass().getMethod("close");
            m.invoke(o);
        } catch (Exception ignored) {
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.err.println(
                    "usage: migrate | ingest <corpus.jsonl> | report <out-dir> | backfill | doccheck");
            System.exit(2);
        }
        Files.createDirectories(DB.getParent());

        switch (args[0]) {
            case "migrate" -> {
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "ingest" -> {
                if (args.length < 2) throw new IllegalArgumentException("ingest needs a corpus");
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    store.migrate(MIGRATIONS);
                    var stats = new IngestService(new Parsers(), store)
                            .ingestFile(Path.of(args[1]));
                    System.out.println(stats);
                    System.out.println("ledger rows: " + store.count());
                }
            }
            case "report" -> {
                if (args.length < 2) throw new IllegalArgumentException("report needs a directory");
                Path out = Path.of(args[1]);
                Files.createDirectories(out);
                try (SqlLedgerStore store = new SqlLedgerStore(DB)) {
                    var ledger = store.all();
                    // Ledger rows are sorted by occurred_at for stable output; source ids
                    // are already sorted at ingest.
                    ledger = ledger.stream()
                            .sorted(java.util.Comparator.comparing(
                                    in.simplifymoney.ledgersync.model.NormalizedTxn::occurredAt))
                            .toList();
                    Files.writeString(out.resolve("ledger.json"),
                            Json.writePretty(Reports.ledgerDocument(ledger)));
                    Files.writeString(out.resolve("summary.json"),
                            Json.writePretty(Reports.summary(ledger)));
                    Files.writeString(out.resolve("reconciliation.json"),
                            Json.writePretty(Reports.reconciliationWithEvidence(
                                    ledger, store.statedBalances())));
                    System.out.println("wrote 3 files to " + out);
                }
            }
            case "backfill" -> {
                // SQL -> Mongo, idempotent. Reads legacy duplicates, merges on key.
                try (SqlLedgerStore sql = new SqlLedgerStore(DB)) {
                    sql.migrate(MIGRATIONS);
                    var docs = mongoStore();
                    try {
                        var r = new in.simplifymoney.ledgersync.store.Backfill(sql, docs).run();
                        System.out.println("backfill read=" + r.read()
                                + " written=" + r.written() + " skipped=" + r.skipped());
                        System.out.println("documents: " + docCount(docs));
                    } finally {
                        closeQuietly(docs);
                    }
                }
            }
            case "doccheck" -> {
                try (SqlLedgerStore sql = new SqlLedgerStore(DB)) {
                    var docs = mongoStore();
                    try {
                        var divs =
                                new in.simplifymoney.ledgersync.store.ConsistencyChecker(sql, docs)
                                        .check();
                        System.out.println("divergences: " + divs.size());
                        for (var d : divs) {
                            System.out.println("- " + d.what());
                            System.out.println("    sql: " + d.inSql());
                            System.out.println("    doc: " + d.inDocuments());
                        }
                    } finally {
                        closeQuietly(docs);
                    }
                }
            }
            case "bench" -> {
                // 100k benchmark for README six numbers. Reflective to stay javac-clean.
                var cls = Class.forName(
                        "in.simplifymoney.ledgersync.store.DocStoreBench");
                var m = cls.getMethod("main", String[].class);
                String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
                m.invoke(null, (Object) rest);
            }
            case "benchexplain" -> {
                var cls = Class.forName(
                        "in.simplifymoney.ledgersync.store.BenchExplain");
                var m = cls.getMethod("main", String[].class);
                String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
                m.invoke(null, (Object) rest);
            }
            default -> {
                System.err.println("unknown command: " + args[0]);
                System.exit(2);
            }
        }
    }
}
