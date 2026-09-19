package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.util.List;

/**
 * Where transactions live.
 *
 * <p>Idempotency: saving the same logical transaction twice (same dedup key,
 * see ingest.TxnKeys) must not create a second row. Implementations merge
 * source_message_ids (union, sorted). Re-running ingest over the same corpus,
 * or over an overlapping corpus, leaves the ledger identical.
 *
 * <p>Evidence: banks quote a running balance in most SMS ("Avl Bal", "Available
 * Balance", "BalAvl"). That balance is not part of NormalizedTxn (frozen), but
 * reconciliation needs it to find balance gaps. Stores keep, per dedup key, the
 * stated balance when any evidencing message quoted one (emails quote none).
 */
public interface LedgerStore {

    void save(NormalizedTxn txn);

    /**
     * Save with the stated balance quoted by the evidencing message(s), may be
     * null (emails quote none). Default merges the txn and keeps a non-null
     * balance when one is supplied.
     */
    default void saveWithEvidence(NormalizedTxn txn, java.math.BigDecimal statedBalance) {
        save(txn);
    }

    List<NormalizedTxn> all();

    /**
     * Stated balance per dedup key, for txns whose messages quoted one.
     * Keys are ingest.TxnKeys.key(txn). Absent key or null value = no balance quoted.
     */
    default java.util.Map<String, java.math.BigDecimal> statedBalances() {
        return java.util.Map.of();
    }

    long count();
}
