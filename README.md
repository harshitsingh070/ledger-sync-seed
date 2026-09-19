# ledger-sync — submission

## Run it in under 5 minutes

Prereqs: JDK 17+, Docker with Compose. No AWS account needed.

```bash
# 1. core pipeline, no network, no DB (JDK only)
./verify.sh
# -> INGEST messages 522, transactions 256, skipped 43; 9075 balances to the paisa

# 2. document store (MongoDB 7, from compose)
docker compose up -d
# wait ~10s for healthy, then:

# 3. full pipeline on SQL (H2, file data/ledger.mv.db) + reports
./gradlew run --args="migrate"
./gradlew run --args="ingest fixtures/corpus-a.jsonl"
./gradlew run --args="report submission"
# -> submission/ledger.json (256 txns), summary.json, reconciliation.json (1 gap)

# 4. document store ops
./gradlew run --args="backfill"    # SQL -> Mongo, idempotent; re-run writes 0
./gradlew run --args="doccheck"    # 0 divergences when stores agree
./gradlew test                     # 33 tests green (contract + amounts + incident + backfill/checker + unseen-corpus)
```

`submission/` in this repo is gitignored; the three files for corpus-a were generated
from a clean store (see "What the data made you decide / honest mismatch") and are
attached to the submission email. `data/` holds the SQL file with June legacy rows
(V2__seed.sql) used only for Backfill tests — corpus-a scoring uses the clean 256.

One command for the store: `docker compose up -d` (mongo:7 on :27017).
`MONGO_URI`/`MONGO_DB` env vars override the defaults (`mongodb://localhost:27017`, `ledger`).

---

## What was built

- **Amounts fix (INC-2026-09-11):** `Rs.5`, `INR 18,000`, `Sent INR99`, `INR 45,000`
  now parse as 5.00/18000.00/99.00/45000.00 instead of returning the stated balance.
- **EmailParser (new):** `alerts@hdfcbank.net` + `alerts@icicibank.com`, amount with or
  without paise, `Date:` header via RFC1123 normalised to IST (handles the one
  `+0000` mail that caused a double-count — see decision 3).
- **IciciSmsParser V2:** `ICICI Bank Acct XX9075 Dr/Cr INR <amt> on 23-Jul-2026 18:41; … ref no … BalAvl …`.
- **Dedup:** `TxnKeys.key(account | instant in IST | direction | amount 2dp | merchant UPPER)`
  collapses exact re-uploads (identical body, new id) and SMS+email pairs
  (different bodies, same instant/amount/merchant). 479 parsed messages → 256 txns.
  Verified 0 merchant mismatches inside merged groups on corpus-a.
- **Categories:** TRANSFER by mirrored legs (same amount+merchant, opposite dirs,
  different accounts, ≤10 min) — no hardcoded names. MICRO = UPI debit ≤100
  (`UPI/` or `UPI `, covers `UPI MANDATE VERIFY` 0.50). Else SPEND/INCOME.
- **Summary:** spend=SPEND only, income=INCOME only, micro rolled to count+total,
  transferred_out/in from TRANSFER by direction. Card 3310 included as SPEND-only.
- **Reconciliation:** consecutive stated-balance check per savings account
  (`stated[i] == stated[i-1] + effects since`). Card skipped (Avl Limit moves on
  off-SMS payments). Finds the single 29-Jul Rs.7500.00 drop (see below).
- **Stores:** `save()` is an upsert merging `source_message_ids` on the dedup key
  (both SQL and in-memory/document). `stated_balance` column added via
  `V3__stated_balance.sql`; emails store NULL.
- **Document store:** MongoDB (see decision 7). One doc per txn (`_id` = dedup key)
  + `account_totals` pre-aggregation for Q2. `Backfill` dedups legacy rows then
  upserts (re-run writes 0). `ConsistencyChecker` compares field-by-field, names
  missing/extra/altered (not row counts).

---

## Decision log (9)

1. **Fix Amounts by making paise optional, not by anchoring to "debited".**
   Considered parsing the amount positionally per format (e.g. "after Sent INR").
   Rejected: every format puts the txn figure first and the balance second, so
   fixing `first()` to handle integers fixes all formats at once, including future
   ones. Risk was matching the trailing sentence dot in `INR 45,000.` — the regex
   `([0-9,]+(?:\.[0-9]{2})?)` correctly captures `45,000` without the dot.

2. **Dedup key includes merchant, not just account+time+amount.**
   Was unsure: including merchant risks splitting SMS+email if spellings differ;
   excluding risks merging two different buys in the same minute. Data decided:
   all 191 multi-message groups on corpus-a share the exact upper merchant
   (0 mismatches), so including it is safe and prevents false merges. Unseen
   corpora with same-minute different-merchant buys stay separate.

3. **Normalise every instant to IST before keying (email `+0000` bug).**
   `m-00131` mail is `18 Jul 18:50 +0000` = `19 Jul 00:20 IST`, same as its SMS
   `m-00130`. String-keying without conversion double-counts Rs.412.67 UBER.
   Found via balance chain (running − stated = −412.67 from 19-Jul onward until
   the 7500 gap). Fix: `Dates.emailToIst()` with `withOffsetSameInstant(IST)`.

4. **TRANSFER by pairing, not by name list.**
   Hardcoding `PARAG KAPOOR` gives perfect corpus-a numbers but fails unseen data.
   Data: `RAHUL SHARMA` 12000 debit (no credit leg) must stay SPEND;
   `MYNTRA` 4200 debit vs `MYNTRA REFUND` 4200 credit differ in merchant and are
   65 min apart, must stay SPEND/INCOME; `NEFT INWARD SELF` 18000 credits have no
   debit legs, must stay INCOME. Pairing (same amount+merchant, opposite dirs,
   different accounts, ≤10 min) gets all five PARAG pairs and none of the above.
   Rejected amount-only pairing (would confuse MYNTRA purchase/refund).

5. **MICRO = `UPI/` or `UPI ` prefix, ≤100.00 inclusive.**
   Was unsure about `UPI MANDATE VERIFY` (space, not slash) 0.50 — counted as UPI
   because the vuelto is still a UPI debit; excluding it would drop micro_count
   for 9075 from 45 to 44 and break micro_total by 0.50. Inclusive 100.00 keeps
   three `100.00` PARKING/WATER CAN/VEG rows as MICRO, matching
   52/2357.51 and 45/2086.34 exactly.

6. **Report the Rs.7500.00 gap honestly instead of faking a txn to match totals.**
   Balance chain: `36054.05 (11:53) −75.00 = 35979.05` expected at 17:06, bank says
   `28479.05` → 7500.00 left with no message, and the 7500 offset persists to the
   closing balance (48626.34 derived vs 41126.34 stated). Totals file expects
   spend 87068.38 / 146 txns for 4821; honest ledger is 79568.38 / 145 txns.
   Faking a 7500 txn with invented source ids would match the file but violate
   traceability ("We check for this specifically"). Chose ledger 256 +
   reconciliation 1 gap = 257 total, with the mismatch explained. See below.

7. **MongoDB over DynamoDB.**
   DynamoDB is preferred in the brief, but needs AWS creds/region/signing even
   against `dynamodb-local`, plus a heavier SDK for a JDK-only `verify.sh`.
   Mongo runs from `docker compose up` with no creds, one driver
   (`mongodb-driver-sync:5.2.0`), and `explain()` gives `totalDocsExamined` /
   `nReturned` directly for the six numbers. Rejected DynamoDB-local via raw HTTP
   SigV4 in pure JDK (feasible but ~200 lines of signing for no grading benefit).

8. **Pre-aggregated `account_totals` for Q2 instead of on-the-fly aggregation.**
   Q2 could scan all of an account's docs (≈33k at 100k) and sum. Rejected: Q2 is
   "running totals", read far more than written; one-doc lookup (examined 1) is
   the point of "design documents so the engine serves these directly". Totals
   update only on insert (merging ids for the same key changes no money), so
   re-runs stay correct. Trade-off: category changes on re-ingest need a delta
   adjust (implemented).

9. **Keep `App` compilable with plain `javac` (no driver) via reflection for Mongo.**
   `verify.sh` must work with "JDK and nothing else". If `App.java` imported
   `MongoDocumentStore`, `javac` without the driver fails. So `backfill`/`doccheck`/
   `bench` load Mongo classes via `Class.forName`. Rejected adding the driver to
   `verify.sh` (would need network). `InMemoryDocumentStore` (same design, JDK-only)
   covers verify/tests without Docker.

---

## What the data made you decide

- **Integer rupees are common (38 messages, 24 distinct txns + 2 integer emails).**
  Rule: any txn amount without paise was affected (grabbed balance or dropped).
  Blast list includes `m-00022 WATER CAN Rs.5`, `m-00009 SELF 18000`, `m-00180 Sent INR99`,
  `m-00225 Sent 5000 PARAG`, etc. Fixed in Amounts; emails would have been silently
  dropped without it.
- **One mail is `+0000`, rest `+0530`.** Without IST normalisation, UBER 412.67
  double-counts (counts 257 with wrong spend vs honest 256). Found via balance diff.
- **Transfers are exactly the five PARAG pairs (8000/12000/5000/2500/3500, 1–2 min apart).**
  `RAHUL SHARMA` 12000 alone → SPEND. `MYNTRA`/`MYNTRA REFUND` → SPEND/INCOME.
  `SELF` 18000s → INCOME. Pairing rule captures this without names.
- **Micros are exactly UPI ≤100 (52/2357.51, 45/2086.34).** Non-UPI ≤100
  (20 for 4821 totalling 1601.62, e.g. `BIGBASKET 99.99`, `IRCTC 99.99`) stay SPEND.
- **Card 3310: 20 txns, SPEND-only, excluded from summary expectations and from
  reconciliation.** Limits rise on spends (196250→199858) → off-SMS payments exist,
  so limit-chain checks would false-positive. Ledger still lists them (257→256+20).
- **Non-transactions (43 skipped):** OTPs (`268880 … Rs.5160.00`), balance pings
  (`Avl Bal in a/c … as on …`), `E-mandate! … will be deducted` (future; the actual
  649 debit arrives as email `m-00154` and is counted once), loan ads
  (`Rs.5,00,000`), delivery (`BP-DELHVY`), Swiggy pings (`AX-SWGGYX`), phishing
  (`VK-ICICIB … icicibank-secure.co … Rs.5126.00` — sender never matches a parser).
- **Honest mismatch:** ledger 256 (145+91+20) vs totals 257 (146+91+20); 4821 spend
  79568.38 vs 87068.38; balance 48626.34 vs 41126.34. All deltas = single 7500.00
  debit between `29 Jul 11:53 IRCTC (36054.05)` and `29 Jul 17:06 UPI/STATIONERY
  (28479.05)` with no message. Reported in `reconciliation.json`, not invented in
  `ledger.json`. 9075 matches to the paisa (91 txns, spend 39058.11, etc.).
- **V2__seed.sql June rows (15) are production history, not corpus-a.** `report`
  on a migrated DB yields 271 rows (15 June + 256 July–Aug). Submission files in
  `submission/` were generated from a clean store (InMemory) for corpus-a scoring;
  SQL history is exercised by Backfill/Checker (dedupes 271→266 docs).

---

## Document model + six numbers (100,000 txns, MongoDB 7 via compose)

Doc (`txns`, `_id` = dedup key): `accountLast4, occurredAt (IST ISO), yearMonth
(YYYY-MM), direction, amount (string 2dp), category, merchant (display),
sourceMessageIds (sorted)`. Indexes: `{accountLast4, yearMonth, occurredAt:-1}`
(Q1), `{sourceMessageIds}` multikey (Q3). Second collection `account_totals`
(`_id`=account, per-category string sums + MICRO_COUNT) for Q2.

Benchmark: bulk-loaded 100k synthetic (3 accts × 1 month each in this load, so
Q1 bucket = whole account). `explain()` on `ledger_bench` (mongo:7, `docker compose up`):

| Query | Examined (`totalDocsExamined`) | Returned (`nReturned`) |
|---|---|---|
| Q1 `forAccountMonth("4821", 2026-07)` newest-first | 33334 | 33334 |
| Q2 `categoryTotals("4821")` (one totals doc) | 1 | 1 |
| Q3 `byMessageId("m-bench-000123")` | 1 | 1 |

Reproduce: `docker compose up -d`, then
`./gradlew run --args="bench mongodb://localhost:27017 ledger_bench"` (bulk load,
~3 min) or `./gradlew run --args="benchexplain mongodb://localhost:27017 ledger_bench"`
(explain-only on existing load, ~2s). Q1 examines only its bucket (index, no
collection scan); Q2/Q3 are single-doc lookups. With accounts spread over more
months, Q1 examined/returned shrink proportionally (e.g. ~11k for 3 months).

---

## AI disclosure

Tools: Muse Spark (Meta, via OpenCode) for drafting parsers, ingest, stores, and
tests; Copilot-style completion for boilerplate (getters, regex scaffolds). All
logic was re-derived from the corpus by hand (counts above) and verified by running.

One concrete case where the AI output was worse and I overrode it:

- AI draft for transfers (rejected):
  ```java
  // AI suggested: hardcoded self-name list
  private static final Set<String> SELF = Set.of("PARAG KAPOOR", "SELF");
  boolean isTransfer(String m) { return SELF.stream().anyMatch(m.toUpperCase()::contains); }
  ```
  This marks both `NEFT INWARD SELF` 18000 credits as TRANSFER (income drops
  36000, transferred_in jumps), and misses unseen self-names entirely. It also
  marks nothing for `RAHUL SHARMA` correctly by luck, but for the wrong reason.

- What I shipped (pairing, see IngestService.findTransferKeys):
  ```java
  // same amount + same merchant + opposite dirs + different accounts + ≤10 min
  if (!a.account().equals(b.account()) && a.amount().compareTo(b.amount()) == 0
      && norm(a.merchant()).equals(norm(b.merchant()))
      && opposite(a.dir(), b.dir()) && minutesBetween(a.when(), b.when()) <= 10)
      markBothTransfer();
  ```
  Difference: pairing keeps `SELF` as INCOME (no debit leg), `RAHUL` as SPEND
  (no credit leg), `MYNTRA`/`MYNTRA REFUND` split (merchant differs, 65 min apart),
  and works on unseen names/amounts. Corpus-a transfers then match
  25000/6000/6000/25000 exactly instead of being off by 36000.

---

## What's unfinished / known limits

- Task 0 (app download/referrals/feedback) and Task 1 (Track teardown with screenshots)
  and the 5-min walkthrough recording + CV are manual phone/user artefacts — not in
  this repo. Code Tasks 2–4 are complete.
- `verify.sh` covers core (parse/ingest/summary/reconciliation) without Docker;
  Mongo paths need `./gradlew` (network once for driver/JUnit) + `docker compose up`.
- `occurred_at` renders with seconds (`2026-07-29T17:06:00+05:30`); inputs without
  seconds parse identically (instant equality, IST-normalised).
- Transfer window is 10 min (observed 1–2). A pathological unseen corpus with two
  independent same-amount same-merchant opposite-direction cross-account moves
  within 10 min would false-positive as TRANSFER; widening/narrowing the window
  trades this against missing slow legs. Pairing also assumes identical merchant
  spelling after UPPER-trim (true on corpus-a, 0 mismatches).
- `Backfill.Result` counts `read` = SQL rows (incl. legacy duplicates),
  `written` = new docs, `skipped` = already-present + collapsed duplicates.
- No auth/TLS on local mongo (compose, localhost only); production would need both.

---

## Incident note (five lines for the channel)

1. What broke: `Amounts` required paise (`\.[0-9]{2}`), so whole-rupee sends
   (`Rs.5`, `INR 18,000`, `Sent INR99`) skipped the txn figure and stored the balance.
2. How found: `incident/app.log` shows `m-00004 WATER CAN amount_extracted=92213.10`
   from `Rs.5 … Avl Bal: Rs.92,213.10`; reproduced with `Amounts.first()` + ingest test.
3. Who affected: 38 messages / 24 distinct txns with integer amounts (rule: txn figure
   has no `.xx`), plus 2 integer emails that were dropped — all UPI/small and PARAG
   amounts, not just the water can.
4. Fix: paise optional in `Amounts` (`([0-9,]+(?:\.[0-9]{2})?)`, normalised to 2dp) +
   `EmailParser`/`Icici V2` handle integers; `Incident20260911Test` (6 tests) fails
   before, passes after; old suite stayed green because it only used paise cases.
5. No recurrence: integer-amount cases now in the suite; dedup key uses the fixed
   amount so balance-grabs cannot re-enter as separate rows.

---

## Files

- `submission/ledger.json`, `summary.json`, `reconciliation.json` (clean, 256 txns)
  — gitignored, attached to email. Reproduce via `GenSubmission` (InMemory) or
  SQL `report` on a DB without June legacy rows.
- `incident/INC-2026-09-11.md` (open brief), `incident/app.log` (trace).
- `db/migration/V3__stated_balance.sql` (adds `stated_balance` for reconciliation).
