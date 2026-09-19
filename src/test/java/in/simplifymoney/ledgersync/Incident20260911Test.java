package in.simplifymoney.ledgersync;

import static org.junit.jupiter.api.Assertions.assertEquals;

import in.simplifymoney.ledgersync.model.Direction;
import in.simplifymoney.ledgersync.model.RawMessage;
import in.simplifymoney.ledgersync.parse.Amounts;
import in.simplifymoney.ledgersync.parse.HdfcSmsParser;
import in.simplifymoney.ledgersync.parse.Parsers;
import in.simplifymoney.ledgersync.store.InMemoryLedgerStore;
import in.simplifymoney.ledgersync.ingest.IngestService;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * INC-2026-09-11: "Your app says I spent Rs.92,213.10 on a water can. I paid Rs.5."
 *
 * <p>Cause: Amounts.AMOUNT required "\.[0-9]{2}". Whole-rupee amounts
 * ("Rs.5", "INR 18,000", "Sent INR99", "INR 45,000") never matched, so
 * Amounts.first() skipped the transaction figure and returned the stated
 * balance instead.
 *
 * <p>Why the suite stayed green: every AmountsTest case uses paise
 * ("2499.50", "333.33", "45000.00"). No test ever passed an integer amount,
 * so the missing branch was never exercised.
 */
class Incident20260911Test {

    @Test
    @DisplayName("INC-2026-09-11: Rs.5 water can is Rs.5, not the Rs.92,213.10 balance")
    void waterCanIsFiveRupees() {
        String body = "Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 "
                + "to UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161";
        assertEquals(new BigDecimal("5.00"), Amounts.first(body));
    }

    @Test
    @DisplayName("integer thousands with comma: INR 18,000 is not the balance")
    void integerThousands() {
        String body = "Dear Customer, Acct XX9075 is credited with INR 18,000 "
                + "on 01/07/2026 21:14. Info: NEFT INWARD SELF. Avl Bal Rs.49,882.25 -ICICI Bank";
        assertEquals(new BigDecimal("18000.00"), Amounts.first(body));
    }

    @Test
    @DisplayName("no-space V2 integer: Sent INR99 is not the available balance")
    void v2IntegerNoSpace() {
        String body = "Sent INR99\nTo: UPI/VEGETABLE VENDOR\nOn: 26 Jul 26 09:47\n"
                + "A/c: XX4821\nAvailable Balance: INR 39203.03\n-HDFC Bank";
        assertEquals(new BigDecimal("99.00"), Amounts.first(body));
    }

    @Test
    @DisplayName("email whole rupees: INR 45,000 parses instead of being dropped")
    void emailInteger() {
        String body = "Date: Wed, 01 Jul 2026 09:02:00 +0530\nSubject: Transaction alert\n\n"
                + "Your account ending 4821 has been credited with INR 45,000.\n"
                + "Merchant / Remarks: SALARY CREDIT\nTransaction reference: 1\n";
        assertEquals(new BigDecimal("45000.00"), Amounts.first(body));
    }

    @Test
    @DisplayName("end to end: the water-can SMS ingests as a Rs.5 MICRO, not Rs.92213.10 SPEND")
    void waterCanEndToEnd() throws Exception {
        // Minimal corpus with the exact incident message.
        String line = "{\"message_id\":\"m-00004-9c11ae\",\"channel\":\"sms\","
                + "\"sender\":\"AD-HDFCBK-S\",\"received_at\":\"2026-07-04T07:19:00+05:30\","
                + "\"device_id\":\"dev-3f1a90c47b21\","
                + "\"body\":\"Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 "
                + "to UPI/WATER CAN. Avl Bal: Rs.92,213.10. Not you? Call 18002586161\"}";
        Path tmp = Files.createTempFile("incident", ".jsonl");
        Files.writeString(tmp, line + "\n");
        try {
            InMemoryLedgerStore store = new InMemoryLedgerStore();
            new IngestService(new Parsers(), store).ingestFile(tmp);
            assertEquals(1, store.count());
            var t = store.all().get(0);
            assertEquals(new BigDecimal("5.00"), t.amount());
            assertEquals(Direction.DEBIT, t.direction());
            // Rs.5 UPI debit <= 100 -> MICRO, never SPEND.
            assertEquals(in.simplifymoney.ledgersync.model.Category.MICRO, t.category());
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    @Test
    @DisplayName("parser still reads the incident message (documents the HdfcSmsParser line)")
    void parserReadsIncidentMessage() {
        RawMessage m = new RawMessage("m-00004-9c11ae", "sms", HdfcSmsParser.SENDER,
                OffsetDateTime.parse("2026-07-04T07:19:00+05:30"), "dev-3f1a90c47b21",
                "Rs.5 debited from a/c **4821 on 04-07-26 at 07:19 to UPI/WATER CAN. "
                        + "Avl Bal: Rs.92,213.10. Not you? Call 18002586161");
        var parsed = new HdfcSmsParser().parse(m);
        assertEquals(true, parsed.isPresent());
        assertEquals(new BigDecimal("5.00"), parsed.get().amount());
    }
}
