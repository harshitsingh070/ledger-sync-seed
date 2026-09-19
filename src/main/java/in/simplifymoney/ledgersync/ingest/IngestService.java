package in.simplifymoney.ledgersync.ingest;

import in.simplifymoney.ledgersync.json.Json;
import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.ParsedTxn;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.LedgerStore;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Reads a corpus of raw messages and puts transactions in the ledger.
 *
 * <p>Dedup: message_id identifies the upload, not the message. The same SMS
 * re-read uploads again with a new id (identical body), and the same purchase
 * arrives as SMS+email with different bodies. Both collapse on
 * TxnKeys.key(account, occurredAt in IST, direction, amount, merchant).
 *
 * <p>Categories: TRANSFER is a paired self-move (same amount, same merchant,
 * opposite directions, different accounts, within minutes) — never hardcoded to
 * a name so unseen corpora work. MICRO is a UPI debit of Rs.100 or less.
 * Everything else follows the direction. TRANSFER wins over MICRO (no overlap
 * in practice, but deterministic).
 *
 * <p>Idempotent: re-running over the same or an overlapping corpus merges on the
 * same key via LedgerStore.saveWithEvidence; the ledger is identical.
 */
public final class IngestService {

    /** Max gap between the two legs of a self-transfer. Observed: 1-2 min. */
    static final long TRANSFER_WINDOW_MINUTES = 10;

    private final Parsers parsers;
    private final LedgerStore store;

    public IngestService(Parsers parsers, LedgerStore store) {
        this.parsers = parsers;
        this.store = store;
    }

    public Stats ingestFile(Path corpus) throws IOException {
        List<RawMessage> messages = readCorpus(corpus);
        int skipped = 0;
        // Group parsed messages by logical transaction.
        java.util.LinkedHashMap<String, Group> groups = new java.util.LinkedHashMap<>();
        for (RawMessage m : messages) {
            Optional<ParsedTxn> p = parsers.parse(m);
            if (p.isEmpty()) {
                skipped++;
                continue;
            }
            ParsedTxn pt = p.get();
            String k = TxnKeys.key(pt);
            Group g = groups.get(k);
            if (g == null) {
                g = new Group(pt);
                groups.put(k, g);
            }
            g.add(pt);
        }

        java.util.List<Group> distinct = new java.util.ArrayList<>(groups.values());
        java.util.Set<String> transferKeys = findTransferKeys(distinct);

        int written = 0;
        for (Group g : distinct) {
            Category c = categorize(g.representative, transferKeys.contains(g.key));
            // Normalise amount to exactly 2dp positive; direction carries the sign.
            java.math.BigDecimal amount =
                    g.representative.amount().setScale(2);
            if (amount.signum() <= 0) {
                amount = amount.abs().setScale(2);
            }
            java.util.List<String> ids = g.sourceIds.stream().sorted().toList();
            NormalizedTxn txn = new NormalizedTxn(
                    g.representative.accountLast4(),
                    TxnKeys.normalizeTime(g.representative.occurredAt()),
                    g.representative.direction(),
                    amount, c, g.representative.merchant(), ids);
            store.saveWithEvidence(txn, g.statedBalance);
            written++;
        }
        return new Stats(messages.size(), written, skipped);
    }

    private static Category categorize(ParsedTxn p, boolean isTransfer) {
        if (isTransfer) return Category.TRANSFER;
        if (TxnKeys.isMicro(p.direction(), p.amount(), p.merchant())) return Category.MICRO;
        return p.direction() == Direction.DEBIT ? Category.SPEND : Category.INCOME;
    }

    /**
     * Self-moves appear as mirrored legs: same amount, same merchant string,
     * opposite directions, different accounts, minutes apart. RAHUL SHARMA (debit
     * only) stays SPEND; MYNTRA vs MYNTRA REFUND differ in merchant and stay
     * SPEND/INCOME; NEFT INWARD SELF (credits only) stays INCOME.
     */
    static java.util.Set<String> findTransferKeys(java.util.List<Group> groups) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (int i = 0; i < groups.size(); i++) {
            for (int j = i + 1; j < groups.size(); j++) {
                Group a = groups.get(i);
                Group b = groups.get(j);
                ParsedTxn pa = a.representative;
                ParsedTxn pb = b.representative;
                if (pa.direction() == pb.direction()) continue;
                if (!pa.accountLast4().equals(pb.accountLast4())
                        && pa.amount().compareTo(pb.amount()) == 0
                        && TxnKeys.normalizeMerchant(pa.merchant())
                                .equals(TxnKeys.normalizeMerchant(pb.merchant()))) {
                    long mins = Math.abs(java.time.Duration.between(
                            TxnKeys.normalizeTime(pa.occurredAt()),
                            TxnKeys.normalizeTime(pb.occurredAt())).toMinutes());
                    if (mins <= TRANSFER_WINDOW_MINUTES) {
                        out.add(a.key);
                        out.add(b.key);
                    }
                }
            }
        }
        return out;
    }

    static final class Group {
        final String key;
        ParsedTxn representative;
        final java.util.TreeSet<String> sourceIds = new java.util.TreeSet<>();
        java.math.BigDecimal statedBalance;

        Group(ParsedTxn first) {
            this.key = TxnKeys.key(first);
            this.representative = first;
        }

        void add(ParsedTxn p) {
            sourceIds.add(p.sourceMessageId());
            // Prefer an SMS balance when merging SMS+email (emails quote none).
            if (statedBalance == null) statedBalance = p.statedBalance();
            // Keep the earliest merchant spelling deterministically; key already
            // guarantees the normalised forms match.
        }
    }

    public static List<RawMessage> readCorpus(Path corpus) throws IOException {
        List<RawMessage> out = new ArrayList<>();
        try (Stream<String> lines = Files.lines(corpus)) {
            for (String line : (Iterable<String>) lines.filter(s -> !s.isBlank())::iterator) {
                Map<String, Object> o = Json.parseObject(line);
                out.add(new RawMessage(
                        (String) o.get("message_id"),
                        (String) o.get("channel"),
                        (String) o.get("sender"),
                        OffsetDateTime.parse((String) o.get("received_at")),
                        (String) o.get("device_id"),
                        (String) o.get("body")));
            }
        }
        return out;
    }

    public record Stats(int messagesRead, int transactionsWritten, int messagesSkipped) {}
}
