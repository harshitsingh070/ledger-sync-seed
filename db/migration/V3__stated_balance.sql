-- Stated balance quoted by the bank ("Avl Bal", "Available Balance", "BalAvl",
-- "Avl Limit"). Not part of the frozen NormalizedTxn, but reconciliation needs it
-- to find balance gaps. Nullable: emails quote no balance.
ALTER TABLE ledger ADD COLUMN IF NOT EXISTS stated_balance DECIMAL(14, 2);
CREATE INDEX IF NOT EXISTS idx_ledger_key ON ledger (account_last4, occurred_at, direction, amount);
