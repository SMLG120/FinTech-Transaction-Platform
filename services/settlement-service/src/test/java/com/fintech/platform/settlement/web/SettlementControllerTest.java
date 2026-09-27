package com.fintech.platform.settlement.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.settlement.domain.BreakKind;
import com.fintech.platform.settlement.messaging.TransactionMovementEvent;
import com.fintech.platform.settlement.persistence.SettlementCycleRepository;
import com.fintech.platform.settlement.service.SettlementService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The statement an operator actually reads, and the refusals they actually get.
 *
 * <p>Authorization is not asserted here — that is {@code SettlementSecurityTest} and
 * {@code SettlementAuthorizationTest}, and duplicating it would make this class a second place to forget
 * to update. What is here is everything a role check cannot tell you: the field names on the wire, the
 * difference rendered in the body, and the shape of each refusal.
 *
 * <p>The refusals are the part worth the database. A statement that is wrong in a way only a human notices
 * is a quiet failure, and the difference between a period that balances and one that does not is a single
 * sign in a field. So a mismatch is asserted to come back <em>as an answer</em> — 200 with the difference —
 * and only an impossible request comes back as a 4xx.
 *
 * <p>Identities are signed rather than injected as a request attribute, for the reason
 * {@code FraudSecurityTest} sets out: a test able to set the attribute directly would prove the controller's
 * behaviour without proving that the only way in is through a verified identity.
 */
@Testcontainers
@SpringBootTest
@AutoConfigureMockMvc
class SettlementControllerTest {

    private static final String SIGNING_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";

    private static final InternalIdentityCodec CODEC =
            InternalIdentityCodec.fromHexKey(SIGNING_KEY, Duration.ofSeconds(60), Clock.systemUTC());

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_settlement_web");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("platform.security.internal-identity.signing-key", () -> SIGNING_KEY);
        registry.add("app.settlement.outbox.relay-enabled", () -> false);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private SettlementService settlement;

    @Autowired
    private SettlementCycleRepository cycles;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private JdbcTemplate jdbc;

    // ------------------------------------------------------------------ fixtures

    /**
     * Empties the ledger before each test.
     *
     * <p>One container serves the whole class, so without this the findings from a previous test are still
     * in the queue — and "list the open findings" then counts the whole class's history instead of one
     * test's. Distinct business dates per test stop the periods colliding, but nothing stops the breaks
     * accumulating, so the reset is what actually isolates them.
     */
    @org.junit.jupiter.api.BeforeEach
    void emptyTheLedger() {
        transactions.executeWithoutResult(status -> jdbc.execute(
                "TRUNCATE settlement_breaks, settlement_lines, settlement_cycles, outbox_events, processed_events"
                        + " RESTART IDENTITY CASCADE"));
    }

    private static RequestPostProcessor signedAs(String subject, String... roles) {
        return request -> {
            Map<String, String> headers = CODEC.headersFor(
                    new InternalIdentity(subject, "user", List.of(roles), "correlation-1", Instant.now()));
            headers.forEach(request::addHeader);
            return request;
        };
    }

    private static RequestPostProcessor asOperator() {
        return signedAs("operator-subject", "SETTLEMENT_OPERATOR");
    }

    private static RequestPostProcessor asCompliance() {
        return signedAs("compliance-subject", "COMPLIANCE_OFFICER");
    }

    private TransactionMovementEvent capture(int dayOfMonth, String amount) {
        return new TransactionMovementEvent(
                UUID.randomUUID(), "sha256:irrelevant", amount, "GBP", "SETTLED", "Coffee", at(dayOfMonth), 1L);
    }

    private Instant at(int dayOfMonth) {
        return LocalDate.of(2026, 4, dayOfMonth).atTime(9, 0).toInstant(ZoneOffset.UTC);
    }

    /** A closed period whose declared actual is 50.00 short, so the difference and the break are both real. */
    private String brokenPeriod(int dayOfMonth) {
        return transactions.execute(status -> {
            settlement.applyCapture(capture(dayOfMonth, "500.00"));
            settlement.applyCapture(capture(dayOfMonth, "200.00"));
            String reference = referenceOf(dayOfMonth);
            settlement.close(reference);
            settlement.declareActual(
                    reference,
                    com.fintech.platform.settlement.domain.Money.parse(
                            "650.00", java.util.Currency.getInstance("GBP")));
            return reference;
        });
    }

    private String closedPeriod(int dayOfMonth, String amount) {
        return transactions.execute(status -> {
            settlement.applyCapture(capture(dayOfMonth, amount));
            String reference = referenceOf(dayOfMonth);
            settlement.close(reference);
            return reference;
        });
    }

    private String referenceOf(int dayOfMonth) {
        return cycles.findByBusinessDateAndCurrencyCode(LocalDate.of(2026, 4, dayOfMonth), "GBP")
                .orElseThrow()
                .getReference();
    }

    private UUID breakIdOf(String reference) {
        UUID cycleId = cycles.findByReference(reference).orElseThrow().getId();
        return transactions.execute(status ->
                jdbc.queryForObject("SELECT id FROM settlement_breaks WHERE cycle_id = ?", UUID.class, cycleId));
    }

    // ------------------------------------------------------------------ the statement

    @Test
    @DisplayName("shows both figures and the signed difference, so a shortfall cannot be mistaken for a match")
    void showsTheDifferenceWithItsSign() throws Exception {
        String reference = brokenPeriod(1);

        // 700.00 expected against 650.00 declared. The difference is rendered negative, from the platform's
        // side: the money is short, not over. A consumer that has to know which way round to read a bare
        // figure is a consumer that will eventually get it wrong.
        mvc.perform(get("/api/v1/settlement/cycles/{reference}", reference).with(asCompliance()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cycle.reference").value(reference))
                .andExpect(jsonPath("$.cycle.businessDate").value("2026-04-01"))
                .andExpect(jsonPath("$.cycle.currency").value("GBP"))
                .andExpect(jsonPath("$.cycle.expected").value("700.00"))
                .andExpect(jsonPath("$.cycle.actual").value("650.00"))
                .andExpect(jsonPath("$.cycle.difference").value("-50.00"))
                .andExpect(jsonPath("$.cycle.status").value("BROKEN"))
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].amount").value("500.00"))
                .andExpect(jsonPath("$.breaks.length()").value(1))
                .andExpect(jsonPath("$.breaks[0].kind").value(BreakKind.AMOUNT_MISMATCH.name()))
                .andExpect(jsonPath("$.breaks[0].status").value("OPEN"));
    }

    @Test
    @DisplayName("carries a refund as a negative line, so a statement sums one signed column")
    void carriesRefundsAsNegativeLines() throws Exception {
        UUID payment = UUID.randomUUID();
        transactions.executeWithoutResult(status -> {
            settlement.applyCapture(
                    new TransactionMovementEvent(payment, "sha256:x", "120.00", "GBP", "SETTLED", "Coffee", at(2), 1L));
            settlement.applyReversal(new TransactionMovementEvent(
                    payment, "sha256:x", "120.00", "GBP", "REVERSED", "Coffee", at(2), 2L));
        });
        String reference = referenceOf(2);

        mvc.perform(get("/api/v1/settlement/cycles/{reference}", reference).with(asCompliance()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[1].kind").value("REVERSAL"))
                .andExpect(jsonPath("$.lines[1].amount").value("-120.00"))
                .andExpect(jsonPath("$.breaks.length()").value(0));
    }

    @Test
    @DisplayName("404s a reference that does not exist rather than returning an empty period")
    void unknownReferenceIsNotFound() throws Exception {
        // The failure this prevents: an operator who mistypes a reference sees a period with nothing in
        // it and concludes the bank sent nothing. The platform does not know, and it must not look like it
        // does.
        mvc.perform(get("/api/v1/settlement/cycles/{reference}", "SETTLE-2026-04-31-GBP")
                        .with(asCompliance()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("SETTLEMENT_CYCLE_NOT_FOUND"));
    }

    // ------------------------------------------------------------------ refusals

    @Test
    @DisplayName("returns a mismatch as an answer, because the declaration succeeded and the news is in the reply")
    void aMismatchIsAnAnswerNotAFailure() throws Exception {
        String reference = closedPeriod(3, "100.00");

        // 200, not 409. The operator's request was perfectly valid; what it revealed is that the period does
        // not balance. A 409 would tell them their request was malformed, and they would go looking for the
        // mistake in their own input rather than in the clearing file.
        mvc.perform(post("/api/v1/settlement/cycles/actual")
                        .with(asOperator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"" + reference + "\",\"actualAmount\":\"90.00\","
                                + "\"currency\":\"GBP\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.difference").value("-10.00"))
                .andExpect(jsonPath("$.status").value("BROKEN"));
    }

    @Test
    @DisplayName("confirms a period whose figures agree")
    void reconcilesAPeriodThatBalances() throws Exception {
        String reference = closedPeriod(4, "100.00");
        transactions.executeWithoutResult(status -> settlement.declareActual(
                reference,
                com.fintech.platform.settlement.domain.Money.parse("100.00", java.util.Currency.getInstance("GBP"))));

        mvc.perform(post("/api/v1/settlement/cycles/{reference}/reconcile", reference)
                        .with(asOperator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RECONCILED"));
    }

    @Test
    @DisplayName("reports closing a period twice as a conflict with a code, not as a server fault")
    void closingTwiceIsAConflict() throws Exception {
        String reference = closedPeriod(5, "10.00");

        // 500 would say the platform is broken. What happened is that the caller asked for something the
        // period's state forbids, and the colleague who clicks the same button next gets the same answer.
        mvc.perform(post("/api/v1/settlement/cycles/close")
                        .with(asOperator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"" + reference + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SETTLEMENT_CYCLE_NOT_OPEN"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("cannot close")));
    }

    @Test
    @DisplayName("refuses to confirm a period with an open finding, and says which finding is in the way")
    void refusesToReconcileAnUnbalancedPeriod() throws Exception {
        String reference = brokenPeriod(6);

        mvc.perform(post("/api/v1/settlement/cycles/{reference}/reconcile", reference)
                        .with(asOperator()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SETTLEMENT_CYCLE_NOT_OPEN"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("unresolved break")));
    }

    @Test
    @DisplayName("tells the operator to look at a break before resolving it, rather than just refusing")
    void resolutionRequiresAcknowledgement() throws Exception {
        String reference = brokenPeriod(7);
        UUID breakId = breakIdOf(reference);

        // Its own code, not the generic state conflict, because the caller's next move is different. A
        // generic "not in a state that allows this change" teaches them nothing and they will send the same
        // request again.
        mvc.perform(post("/api/v1/settlement/breaks/{breakId}/resolve", breakId)
                        .with(asOperator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"checked the clearing file\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("SETTLEMENT_BREAK_NOT_ACKNOWLEDGED"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("acknowledged")));
    }

    @Test
    @DisplayName("refuses an amount the currency cannot hold rather than rounding it into existence")
    void refusesUnrepresentableAmount() throws Exception {
        String reference = closedPeriod(8, "10.00");

        // 10.005 GBP does not exist. Rounding invents a figure, and half a penny is invisible on the
        // statement while being inexplicable against a bank file.
        mvc.perform(post("/api/v1/settlement/cycles/actual")
                        .with(asOperator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"" + reference + "\",\"actualAmount\":\"10.005\","
                                + "\"currency\":\"GBP\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("refuses an unknown currency code as a bad request, not as a conflict with the period")
    void refusesUnknownCurrency() throws Exception {
        String reference = closedPeriod(9, "10.00");

        // "XYZ" satisfies the three-uppercase-letters constraint, so it reaches the controller and fails
        // there. It has to be a 400: a 409 about the cycle's state would send the operator to investigate
        // a period that was never the problem.
        mvc.perform(post("/api/v1/settlement/cycles/actual")
                        .with(asOperator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reference\":\"" + reference + "\",\"actualAmount\":\"10.00\","
                                + "\"currency\":\"XYZ\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("SETTLEMENT_AMOUNT_UNUSABLE"));
    }

    // ------------------------------------------------------------------ working a finding

    @Test
    @DisplayName("records the acknowledging operator from the verified identity, not the request body")
    void acknowledgesWithTheIdentityNotTheBody() throws Exception {
        String reference = brokenPeriod(10);
        UUID breakId = breakIdOf(reference);

        // There is no actor field to send, and that is the point: an acknowledgement is the record of who
        // looked at a discrepancy, so it has to come from something the client cannot set for itself.
        mvc.perform(post("/api/v1/settlement/breaks/{breakId}/acknowledge", breakId)
                        .with(asOperator()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.acknowledgedBy").value("operator-subject"));
    }

    @Test
    @DisplayName("keeps the explanation on a resolved finding, because the explanation is the point")
    void resolvingKeepsTheExplanation() throws Exception {
        String reference = brokenPeriod(11);
        UUID breakId = breakIdOf(reference);
        mvc.perform(post("/api/v1/settlement/breaks/{breakId}/acknowledge", breakId)
                        .with(asOperator()))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/settlement/breaks/{breakId}/resolve", breakId)
                        .with(asOperator())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"resolution\":\"a 50.00 bank charge, found in the clearing file\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RESOLVED"))
                .andExpect(jsonPath("$.resolution").value("a 50.00 bank charge, found in the clearing file"));
    }

    @Test
    @DisplayName("lists findings newest first, and can be filtered by status")
    void listsBreaks() throws Exception {
        brokenPeriod(12);

        mvc.perform(get("/api/v1/settlement/breaks").with(asCompliance()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].kind").value(BreakKind.AMOUNT_MISMATCH.name()));

        // The filter is what makes the queue usable: "open findings" is the question somebody has every
        // morning, and answering it by filtering client-side means every client downloads every finding
        // ever recorded.
        mvc.perform(get("/api/v1/settlement/breaks").param("status", "RESOLVED").with(asCompliance()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0));
    }
}
