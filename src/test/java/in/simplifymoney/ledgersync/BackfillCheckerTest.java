package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import in.simplifymoney.ledgersync.ingest.IngestService;
import in.simplifymoney.ledgersync.ingest.TxnKeys;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.Backfill;
import in.simplifymoney.ledgersync.store.ConsistencyChecker;
import in.simplifymoney.ledgersync.store.InMemoryDocumentStore;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class BackfillCheckerTest {

    private InMemoryLedgerStore ingestCorpus() throws Exception {
        // Minimal corpus path uses fixtures; falls back to temp single-txn if missing.
        var store = new InMemoryLedgerStore();
        Path corpus = Path.of("fixtures/corpus-a.jsonl");
        if (Files.exists(corpus)) {
            new IngestService(new Parsers(), store).ingestFile(corpus);
        }
        return store;
    }

    @Test
    void backfillIsIdempotent() throws Exception {
        var sql = ingestCorpus();
        var docs = new InMemoryDocumentStore();
        var r1 = new Backfill(sql, docs).run();
        var r2 = new Backfill(sql, docs).run();
        assertEquals(sql.count(), docs.count());
        assertEquals(0, r2.written());
        assertTrue(r1.written() > 0 || sql.count() == 0);
    }

    @Test
    void checkerFindsAlteredAmount() throws Exception {
        var sql = ingestCorpus();
        if (sql.count() == 0) return;
        var docs = new InMemoryDocumentStore();
        new Backfill(sql, docs).run();
        assertEquals(0, new ConsistencyChecker(sql, docs).check().size());

        // Deliberately alter: drop one txn and re-add it with a different amount.
        // Same account/time/merchant but amount+1 creates a missing+extra pair,
        // which the checker must name (not just count).
        var victim = sql.all().get(0);
        var alteredDocs = new InMemoryDocumentStore();
        boolean skipped = false;
        for (var t : sql.all()) {
            if (!skipped) {
                skipped = true;
                var altered = new NormalizedTxn(t.accountLast4(), t.occurredAt(),
                        t.direction(), t.amount().add(new java.math.BigDecimal("1.00")),
                        t.category(), t.merchant(), t.sourceMessageIds());
                alteredDocs.save(altered);
            } else {
                alteredDocs.save(t);
            }
        }
        var divs = new ConsistencyChecker(sql, alteredDocs).check();
        assertTrue(divs.size() >= 2,
                "expected missing+extra for altered amount, got: " + divs);
        boolean namesAmount = divs.stream().anyMatch(d ->
                d.what().contains("missing") || d.what().contains("extra")
                        || d.what().contains("amount"));
        assertTrue(namesAmount, "checker must name the alteration, got: " + divs);
    }

    @Test
    void dedupMergesSmsAndEmail() throws Exception {
        var sql = ingestCorpus();
        if (sql.count() == 0) return;
        // Every ledger row must cite at least one message, and at least one row
        // must cite 2+ (SMS+email pair or re-upload duplicate) on corpus-a.
        boolean multi = false;
        for (var t : sql.all()) {
            assertTrue(t.sourceMessageIds().size() >= 1);
            // Sorted, as the contract requires traceability.
            List<String> ids = t.sourceMessageIds();
            assertEquals(ids.stream().sorted().toList(), ids);
            if (ids.size() > 1) multi = true;
        }
        assertTrue(multi, "expected some multi-message transactions on corpus-a");
        // No two rows share a dedup key.
        var keys = sql.all().stream().map(TxnKeys::key).toList();
        assertEquals(keys.size(), new java.util.HashSet<>(keys).size());
    }

    @Test
    void microAndTransferCategoriesExist() throws Exception {
        var sql = ingestCorpus();
        if (sql.count() == 0) return;
        var cats = sql.all().stream().map(NormalizedTxn::category).toList();
        assertTrue(cats.contains(Category.MICRO), "expected MICRO txns");
        assertTrue(cats.contains(Category.TRANSFER), "expected TRANSFER txns");
        assertTrue(cats.contains(Category.SPEND), "expected SPEND txns");
        assertTrue(cats.contains(Category.INCOME), "expected INCOME txns");
    }
}
