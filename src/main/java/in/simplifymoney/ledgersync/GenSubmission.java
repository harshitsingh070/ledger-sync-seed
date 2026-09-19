package in.simplifymoney.ledgersync;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.report.Reports;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Generates the three corpus-a submission files from a clean in-memory store
 * (no June legacy rows from V2__seed.sql, which exist only for Backfill tests).
 *
 * <p>Reproduce: {@code java -cp build/selfcheck
 * in.simplifymoney.ledgersync.GenSubmission submission}
 * or via Gradle (same classpath as App). Output matches totals except the
 * honest Rs.7500.00 gap documented in README (ledger 256 + 1 discrepancy = 257).
 */
public final class GenSubmission {

    private GenSubmission() {}

    public static void main(String[] args) throws Exception {
        Path corpus = Path.of(args.length > 1 ? args[1] : "fixtures/corpus-a.jsonl");
        Path out = Path.of(args.length > 0 ? args[0] : "submission");
        var store = new InMemoryLedgerStore();
        var stats = new IngestService(new Parsers(), store).ingestFile(corpus);
        System.out.println(stats);
        var ledger = store.all().stream()
                .sorted(java.util.Comparator.comparing(
                        in.simplifymoney.ledgersync.model.NormalizedTxn::occurredAt))
                .toList();
        Files.createDirectories(out);
        Files.writeString(out.resolve("ledger.json"), Json.writePretty(Reports.ledgerDocument(ledger)));
        Files.writeString(out.resolve("summary.json"), Json.writePretty(Reports.summary(ledger)));
        Files.writeString(out.resolve("reconciliation.json"),
                Json.writePretty(Reports.reconciliationWithEvidence(ledger, store.statedBalances())));
        System.out.println("wrote 3 files to " + out + " ledger=" + ledger.size());
    }
}
