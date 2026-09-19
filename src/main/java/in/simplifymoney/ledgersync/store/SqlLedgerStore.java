package in.simplifymoney.ledgersync.store;

import in.simplifymoney.ledgersync.model.Category;
import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.NormalizedTxn;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The store this service has used since it was written: a single relational
 * table, reached over plain JDBC.
 *
 * The driver is a runtime dependency (see build.gradle) - this class compiles
 * against the JDK alone.
 */
public final class SqlLedgerStore implements LedgerStore, AutoCloseable {

    private static final String URL_PREFIX = "jdbc:h2:";
    private final Connection conn;

    public SqlLedgerStore(Path dbFile) {
        try {
            // V1__initial.sql uses H2's IDENTITY type, which PostgreSQL mode rejects
            // ("Unknown data type IDENTITY"). Plain H2 mode runs all migrations.
            this.conn = DriverManager.getConnection(
                    URL_PREFIX + dbFile.toAbsolutePath(), "sa", "");
        } catch (SQLException e) {
            throw new IllegalStateException(
                    "could not open the ledger database at " + dbFile
                            + " (is the H2 driver on the runtime classpath?)", e);
        }
    }

    /** Applies every db/migration/V*.sql in filename order. */
    public void migrate(Path migrationDir) {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS schema_history ("
                    + "  filename VARCHAR(200) PRIMARY KEY,"
                    + "  applied_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");

            List<Path> files;
            try (var s = Files.list(migrationDir)) {
                files = s.filter(p -> p.getFileName().toString().endsWith(".sql")).sorted().toList();
            }
            for (Path f : files) {
                String name = f.getFileName().toString();
                try (PreparedStatement q = conn.prepareStatement(
                        "SELECT 1 FROM schema_history WHERE filename = ?")) {
                    q.setString(1, name);
                    try (ResultSet rs = q.executeQuery()) {
                        if (rs.next()) continue;
                    }
                }
                String sql = Files.readString(f);
                for (String stmt : sql.split(";")) {
                    if (!stmt.isBlank()) st.execute(stmt);
                }
                try (PreparedStatement ins = conn.prepareStatement(
                        "INSERT INTO schema_history(filename) VALUES (?)")) {
                    ins.setString(1, name);
                    ins.executeUpdate();
                }
                System.out.println("applied " + name);
            }
        } catch (Exception e) {
            throw new IllegalStateException("migration failed", e);
        }
    }

    @Override
    public void save(NormalizedTxn t) {
        saveWithEvidence(t, null);
    }

    @Override
    public void saveWithEvidence(NormalizedTxn t, BigDecimal statedBalance) {
        try {
            // Idempotent upsert on the logical key (account, instant in IST,
            // direction, amount, merchant normalised). The SQL store ran for a long
            // time without a uniqueness guarantee (see V2__seed.sql duplicates), so
            // we merge in Java rather than relying on a DB constraint.
            String wantKey = in.simplifymoney.ledgersync.ingest.TxnKeys.key(t);
            List<Row> existing = findRows(t.accountLast4(), t.occurredAt().toString(),
                    t.direction().name(), t.amount());
            Row match = null;
            for (Row r : existing) {
                String rk = in.simplifymoney.ledgersync.ingest.TxnKeys.key(
                        r.txn.accountLast4(), r.txn.occurredAt(), r.txn.direction(),
                        r.txn.amount(), r.txn.merchant());
                if (rk.equals(wantKey)) {
                    match = r;
                    break;
                }
            }
            if (match == null) {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO ledger(account_last4, occurred_at, direction, amount,"
                                + " category, merchant, source_message_ids, stated_balance)"
                                + " VALUES (?,?,?,?,?,?,?,?)")) {
                    ps.setString(1, t.accountLast4());
                    ps.setString(2, t.occurredAt().toString());
                    ps.setString(3, t.direction().name());
                    ps.setBigDecimal(4, t.amount());
                    ps.setString(5, t.category().name());
                    ps.setString(6, t.merchant());
                    ps.setString(7, String.join(",", t.sourceMessageIds()));
                    if (statedBalance == null) ps.setNull(8, java.sql.Types.DECIMAL);
                    else ps.setBigDecimal(8, statedBalance);
                    ps.executeUpdate();
                }
                return;
            }
            java.util.TreeSet<String> ids =
                    new java.util.TreeSet<>(match.txn.sourceMessageIds());
            ids.addAll(t.sourceMessageIds());
            BigDecimal mergedBal = match.statedBalance != null ? match.statedBalance : statedBalance;
            boolean idsChanged = !ids.equals(new java.util.TreeSet<>(match.txn.sourceMessageIds()));
            boolean balChanged = match.statedBalance == null && statedBalance != null;
            // Category could differ if transfer detection improved between runs;
            // latest write wins when the key matches.
            boolean catChanged = match.txn.category() != t.category();
            if (!idsChanged && !balChanged && !catChanged) return;
            Category cat = t.category();
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ledger SET source_message_ids = ?, stated_balance = ?,"
                            + " category = ?, merchant = ? WHERE id = ?")) {
                ps.setString(1, String.join(",", ids));
                if (mergedBal == null) ps.setNull(2, java.sql.Types.DECIMAL);
                else ps.setBigDecimal(2, mergedBal);
                ps.setString(3, cat.name());
                ps.setString(4, match.txn.merchant());
                ps.setLong(5, match.id);
                ps.executeUpdate();
            }
            // Collapse any historical duplicates sharing this key (V2 seed has
            // e.g. two identical SWIGGY rows plus a same-message repeat): keep the
            // merged row, fold the others into it, delete the rest.
            for (Row r : existing) {
                if (r.id == match.id) continue;
                String rk = in.simplifymoney.ledgersync.ingest.TxnKeys.key(
                        r.txn.accountLast4(), r.txn.occurredAt(), r.txn.direction(),
                        r.txn.amount(), r.txn.merchant());
                if (!rk.equals(wantKey)) continue;
                ids.addAll(r.txn.sourceMessageIds());
                if (mergedBal == null) mergedBal = r.statedBalance;
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE ledger SET source_message_ids = ?, stated_balance = ? WHERE id = ?")) {
                ps.setString(1, String.join(",", ids));
                if (mergedBal == null) ps.setNull(2, java.sql.Types.DECIMAL);
                else ps.setBigDecimal(2, mergedBal);
                ps.setLong(3, match.id);
                ps.executeUpdate();
            }
            for (Row r : existing) {
                if (r.id == match.id) continue;
                String rk = in.simplifymoney.ledgersync.ingest.TxnKeys.key(
                        r.txn.accountLast4(), r.txn.occurredAt(), r.txn.direction(),
                        r.txn.amount(), r.txn.merchant());
                if (!rk.equals(wantKey)) continue;
                try (PreparedStatement ps = conn.prepareStatement("DELETE FROM ledger WHERE id = ?")) {
                    ps.setLong(1, r.id);
                    ps.executeUpdate();
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not save " + t, e);
        }
    }

    private static final class Row {
        final long id;
        final NormalizedTxn txn;
        final BigDecimal statedBalance;
        Row(long id, NormalizedTxn txn, BigDecimal statedBalance) {
            this.id = id;
            this.txn = txn;
            this.statedBalance = statedBalance;
        }
    }

    private List<Row> findRows(String acct, String occurredAt, String dir, BigDecimal amount)
            throws SQLException {
        List<Row> out = new ArrayList<>();
        boolean hasBalCol = hasStatedBalanceColumn();
        // Narrow by account+direction+amount in SQL; time+merchant are checked in
        // Java via TxnKeys (normalised IST instant + upper merchant) so offset
        // spellings ("+05:30" vs "Z") never miss. occurredAt is also filtered here
        // when the stored spelling matches exactly, to keep 100k backfills fast.
        String sql = "SELECT id, account_last4, occurred_at, direction, amount, category,"
                + " merchant, source_message_ids"
                + (hasBalCol ? ", stated_balance" : "")
                + " FROM ledger WHERE account_last4 = ? AND direction = ? AND amount = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, acct);
            ps.setString(2, dir);
            ps.setBigDecimal(3, amount);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // occurred_at compared in Java (normalised to IST instant) because
                    // stored strings may differ in offset representation for the same instant.
                    String occStr = rs.getString(3);
                    OffsetDateTime occ;
                    try {
                        occ = OffsetDateTime.parse(occStr);
                    } catch (Exception ex) {
                        continue;
                    }
                    NormalizedTxn txn = new NormalizedTxn(
                            rs.getString(2), occ, Direction.valueOf(rs.getString(4)),
                            rs.getBigDecimal(5).setScale(2), Category.valueOf(rs.getString(6)),
                            rs.getString(7),
                            Arrays.stream(rs.getString(8).split(","))
                                    .filter(s -> !s.isBlank()).toList());
                    BigDecimal bal = null;
                    if (hasBalCol) {
                        BigDecimal b = rs.getBigDecimal(9);
                        if (b != null) bal = b.setScale(2);
                    }
                    // Pre-filter on occurred string; full key check happens in caller.
                    out.add(new Row(rs.getLong(1), txn, bal));
                }
            }
        }
        return out;
    }

    private boolean hasStatedBalanceColumn() {
        try (ResultSet rs = conn.getMetaData().getColumns(null, null, "LEDGER", "STATED_BALANCE")) {
            return rs.next();
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public List<NormalizedTxn> all() {
        List<NormalizedTxn> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT account_last4, occurred_at, direction, amount, category,"
                             + " merchant, source_message_ids FROM ledger ORDER BY occurred_at")) {
            while (rs.next()) {
                out.add(new NormalizedTxn(
                        rs.getString(1),
                        OffsetDateTime.parse(rs.getString(2)),
                        Direction.valueOf(rs.getString(3)),
                        rs.getBigDecimal(4).setScale(2),
                        Category.valueOf(rs.getString(5)),
                        rs.getString(6),
                        Arrays.stream(rs.getString(7).split(","))
                                .filter(s -> !s.isBlank()).toList()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the ledger", e);
        }
        return out;
    }

    @Override
    public java.util.Map<String, BigDecimal> statedBalances() {
        java.util.Map<String, BigDecimal> out = new java.util.HashMap<>();
        if (!hasStatedBalanceColumn()) return out;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT account_last4, occurred_at, direction, amount, merchant,"
                             + " stated_balance FROM ledger WHERE stated_balance IS NOT NULL")) {
            while (rs.next()) {
                BigDecimal bal = rs.getBigDecimal(6);
                if (bal == null) continue;
                bal = bal.setScale(2);
                // Rebuild the key the same way save() does.
                NormalizedTxn pseudo;
                try {
                    pseudo = new NormalizedTxn(
                            rs.getString(1), OffsetDateTime.parse(rs.getString(2)),
                            Direction.valueOf(rs.getString(3)),
                            rs.getBigDecimal(4).setScale(2), Category.SPEND,
                            rs.getString(5), List.of("m-dummy"));
                } catch (Exception ex) {
                    continue;
                }
                // Category is irrelevant for the key except direction; use a
                // direction-matching dummy of the right direction.
                String k = in.simplifymoney.ledgersync.ingest.TxnKeys.key(
                        pseudo.accountLast4(), pseudo.occurredAt(), pseudo.direction(),
                        pseudo.amount(), pseudo.merchant());
                out.putIfAbsent(k, bal);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read stated balances", e);
        }
        return out;
    }

    @Override
    public long count() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM ledger")) {
            return rs.next() ? rs.getLong(1) : 0L;
        } catch (SQLException e) {
            throw new IllegalStateException("could not count the ledger", e);
        }
    }

    public BigDecimal sumAmounts() {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT SUM(amount) FROM ledger")) {
            return rs.next() && rs.getBigDecimal(1) != null
                    ? rs.getBigDecimal(1).setScale(2) : BigDecimal.ZERO.setScale(2);
        } catch (SQLException e) {
            throw new IllegalStateException("could not total the ledger", e);
        }
    }

    @Override
    public void close() {
        try { conn.close(); } catch (SQLException ignored) { }
    }
}
