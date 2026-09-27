package com.fintech.platform.card.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.card.domain.Card;
import com.fintech.platform.card.domain.CardBrand;
import com.fintech.platform.card.domain.CardStatus;
import com.fintech.platform.card.domain.Pan;
import com.fintech.platform.card.tokenisation.CardTokenizer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The card schema and aggregate against a real Postgres.
 *
 * <p>Worth a container because the interesting assertions are about the database rather than the Java.
 * The CHECK constraints, the partial index and the column list only exist in Postgres; Hibernate's
 * {@code ddl-auto: validate} is what catches an entity and its migration disagreeing, and neither can
 * be checked with a mocked repository.
 *
 * <p>The tokenisation key is a dynamic property rather than a checked-in test
 * {@code application.yml}, so this file cannot become a second source of configuration that shadows
 * the real one.
 */
@Testcontainers
@SpringBootTest
@Transactional
class CardPersistenceTest {

    private static final String KEY =
            Base64.getEncoder().encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8));

    /**
     * Hex, not base64: the identity signing key has its own format and its own validator, and
     * {@code platform-common-web} deliberately registers the verification filter only when the key is
     * present. Supplying it here means the context is wired exactly as it is in a deployment, so this
     * test cannot pass in a configuration that would not start for real.
     */
    private static final String IDENTITY_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");

    /**
     * A card is lapsed by having been issued far enough in the past, not by being issued with a
     * negative validity. The aggregate refuses a validity outside 1-120 months, so the way to build a
     * card that expired before "now" is to issue it in the past with a legal, short validity. Anything
     * else would mean a card the application could never issue, and the sweep would never see one.
     */
    private static final Instant LAPSED_ISSUED_AT = Instant.parse("2025-01-10T10:00:00Z");

    private static final Period LAPSED_VALIDITY = Period.ofMonths(1);

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine").withDatabaseName("fintech_cards_persistence");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("platform.card.tokenisation.key", () -> KEY);
        // card-service holds a signed identity and re-signs it for the eligibility call, so the codec
        // is a real dependency of the context rather than something only the filter needs.
        registry.add("platform.security.internal-identity.signing-key", () -> IDENTITY_KEY);
        registry.add("spring.kafka.bootstrap-servers", () -> "localhost:1");
    }

    @Autowired
    private CardRepository repository;

    @Autowired
    private CardTokenizer tokenizer;

    @Autowired
    private DataSource dataSource;

    private Card issue(String subject, CardBrand brand) {
        return issue(subject, brand, NOW);
    }

    private Card issue(String subject, CardBrand brand, Instant issuedAt) {
        Pan pan = Pan.mint();
        return repository.save(Card.issue(
                UUID.randomUUID(),
                tokenizer.tokenise(pan).value(),
                tokenizer.ownerDigest(subject),
                UUID.randomUUID(),
                pan,
                brand,
                Period.ofMonths(36),
                issuedAt));
    }

    @Nested
    @DisplayName("the schema")
    class Schema {

        @Test
        @DisplayName("has no column that could hold a card number, a CVV or any track data")
        void hasNoCardDataColumns() throws Exception {
            // The single most important assertion in the card service, and the one a unit test could
            // never make. Everything else in this design is a convention someone can follow; this is
            // the property that there is nowhere to put a number even if somebody wants to. A future
            // migration that adds an encrypted pan column fails here, which is exactly the moment to
            // reconsider.
            List<String> columns = columnNames("cards");
            assertThat(columns)
                    .as("cards table columns")
                    .containsExactlyInAnyOrder(
                            "id",
                            "token",
                            "owner_subject_digest",
                            "customer_id",
                            "last4",
                            "brand",
                            "expires_on",
                            "status",
                            "frozen_at",
                            "lost_at",
                            "cancelled_at",
                            "created_at",
                            "updated_at",
                            "version");
        }

        @Test
        @DisplayName("contains no plaintext of an issued card anywhere in the row")
        void storesNoPlaintext() throws Exception {
            Pan pan = Pan.mint();
            String token = tokenizer.tokenise(pan).value();
            repository.save(Card.issue(
                    UUID.randomUUID(),
                    token,
                    tokenizer.ownerDigest("subject-1"),
                    UUID.randomUUID(),
                    pan,
                    CardBrand.DEBIT,
                    Period.ofMonths(36),
                    NOW));
            repository.flush();

            // Not just "the pan column is absent" but "the digits are not in the row at all", which
            // covers the case where someone later adds a column for them under a name this test did not
            // think to check.
            String row = singleRowAsText();
            assertThat(row).doesNotContain(pan.digits());
            assertThat(row).contains(token);
            assertThat(row).contains(pan.last4());
        }

        @Test
        @DisplayName("rejects a last4 that is not four digits")
        void rejectsMalformedLast4() {
            // The database's own check, not only the domain's. A direct SQL write is exactly the path a
            // migration script or a support engineer takes, and it must not be able to put a row in a
            // shape the application would never produce.
            //
            // Four characters, not five, and non-numeric rather than merely too long. `last4` is
            // VARCHAR(4), so a five-character value is rejected by the column's own width and the CHECK
            // never runs; asserting on the constraint name against that input passes for the wrong
            // reason the moment someone widens the column, or fails for the right one for the wrong
            // reason today. This input is the one the constraint is actually there to catch.
            assertThatThrownBy(() -> rawInsert("last4", "12x4")).hasMessageContaining("cards_last4_is_four_digits");
        }

        @Test
        @DisplayName("rejects a status outside the state machine")
        void rejectsUnknownStatus() {
            assertThatThrownBy(() -> rawInsert("status", "MAGIC")).hasMessageContaining("cards_status_is_known");
        }

        @Test
        @DisplayName("rejects a brand outside the enum")
        void rejectsUnknownBrand() {
            assertThatThrownBy(() -> rawInsert("brand", "MAESTRO")).hasMessageContaining("cards_brand_is_known");
        }

        @Test
        @DisplayName("rejects a card that claims to be lost while its status still says active")
        void rejectsContradictoryLifecycle() {
            // The contradiction an audit query would eventually find and nobody could explain. It is
            // cheap to check here and impossible to check in the aggregate, because a direct write
            // never goes through it.
            assertThatThrownBy(() -> rawInsert("lost_at", "2026-06-15T12:00:00Z"))
                    .hasMessageContaining("cards_lost_at_requires_at_least_lost");
        }

        @Test
        @DisplayName("allows a card that was frozen and then reported lost, which is not a contradiction")
        void allowsFrozenThenLost() {
            // The counter-case to the one above, and the reason the constraint is one-directional. A
            // card frozen in March and reported lost in April carries frozen_at with a status of LOST;
            // a symmetric check would reject that perfectly ordinary row.
            Card card = issue("subject-ok", CardBrand.DEBIT);
            card.freeze(NOW);
            card.reportLost(NOW.plusSeconds(60));
            repository.saveAndFlush(card);
            assertThat(repository.findById(card.getId()).orElseThrow().getStatus())
                    .isEqualTo(CardStatus.LOST);
        }

        @Test
        @DisplayName("refuses two cards sharing one token")
        void refusesDuplicateToken() {
            Card first = issue("subject-a", CardBrand.DEBIT);
            repository.flush();
            // Deterministic tokenisation means the same number always yields the same token, so this
            // is what stopping a second card being issued against a number already in use looks like.
            String clash = first.getToken();
            Pan pan = Pan.mint();
            assertThatThrownBy(() -> repository.saveAndFlush(Card.issue(
                            UUID.randomUUID(),
                            clash,
                            tokenizer.ownerDigest("subject-b"),
                            UUID.randomUUID(),
                            pan,
                            CardBrand.CREDIT,
                            Period.ofMonths(36),
                            NOW)))
                    .hasRootCauseInstanceOf(java.sql.SQLException.class);
        }
    }

    @Nested
    @DisplayName("queries")
    class Queries {

        @Test
        @DisplayName("find a card by id and owner digest, and refuse the wrong digest")
        void findsOnlyTheOwner() {
            Card card = issue("owner-subject", CardBrand.DEBIT);
            repository.flush();

            assertThat(repository.findByIdAndOwnerSubjectDigest(card.getId(), tokenizer.ownerDigest("owner-subject")))
                    .isPresent();
            assertThat(repository.findByIdAndOwnerSubjectDigest(card.getId(), tokenizer.ownerDigest("someone-else")))
                    .isEmpty();
        }

        @Test
        @DisplayName("list a cardholder's cards newest first")
        void listsOwnCardsNewestFirst() {
            String subject = "list-subject";
            // Two cards issued at the same instant have a createdAt tie, and the repository orders only
            // by createdAt, so the assertion would be testing Postgres's discretion. Give them distinct
            // issue times.
            Card older = issue(subject, CardBrand.DEBIT, NOW.minusSeconds(60));
            older.freeze(NOW);
            repository.saveAndFlush(older);
            Card newer = issue(subject, CardBrand.CREDIT);

            List<Card> found = repository.findByOwnerSubjectDigestOrderByCreatedAtDesc(tokenizer.ownerDigest(subject));
            assertThat(found).extracting(Card::getId).containsExactly(newer.getId(), older.getId());
        }

        @Test
        @DisplayName("count only the statuses a caller asks for, so the card limit is the caller's definition")
        void countsByStatus() {
            UUID customer = UUID.randomUUID();
            Card active = cardFor(customer, CardStatus.ACTIVE);
            Card frozen = cardFor(customer, CardStatus.FROZEN);
            Card cancelled = cardFor(customer, CardStatus.CANCELLED);
            repository.saveAll(List.of(active, frozen, cancelled));
            repository.flush();

            // Cancelling a card has to free a slot, or a customer who churns through cards is
            // permanently locked out of issuing.
            assertThat(repository.countByCustomerIdAndStatusIn(
                            customer, List.of(CardStatus.ACTIVE, CardStatus.FROZEN, CardStatus.LOST)))
                    .isEqualTo(2);
        }

        @Test
        @DisplayName("return only cards that are live by status and past their date")
        void findsLapsedCards() {
            UUID customer = UUID.randomUUID();
            Card lapsed = cardFor(customer, CardStatus.ACTIVE, LAPSED_ISSUED_AT, LAPSED_VALIDITY);
            Card current = cardFor(customer, CardStatus.ACTIVE, NOW, Period.ofMonths(36));
            Card alreadyRetired = cardFor(customer, CardStatus.EXPIRED, LAPSED_ISSUED_AT, LAPSED_VALIDITY);
            Card cancelled = cardFor(customer, CardStatus.CANCELLED, LAPSED_ISSUED_AT, LAPSED_VALIDITY);
            repository.saveAll(List.of(lapsed, current, alreadyRetired, cancelled));
            repository.flush();

            // A frozen card past its date must be caught by the sweep, because nothing may unfreeze it
            // and the read path would only fix it if somebody happened to look at that card.
            Card frozenAndLapsed = cardFor(customer, CardStatus.FROZEN, LAPSED_ISSUED_AT, LAPSED_VALIDITY);
            repository.saveAndFlush(frozenAndLapsed);

            List<Card> found = repository.findLapsed(
                    List.of(CardStatus.ACTIVE, CardStatus.FROZEN),
                    LocalDate.ofInstant(NOW, java.time.ZoneOffset.UTC),
                    org.springframework.data.domain.PageRequest.of(0, 50));

            assertThat(found)
                    .extracting(Card::getId)
                    .containsExactlyInAnyOrder(lapsed.getId(), frozenAndLapsed.getId());
            assertThat(found).doesNotContain(current, alreadyRetired, cancelled);
        }

        @Test
        @DisplayName("page the sweep, so retiring a large table is not one unbounded transaction")
        void pagesTheSweep() {
            UUID customer = UUID.randomUUID();
            List<Card> many = new java.util.ArrayList<>();
            for (int i = 0; i < 5; i++) {
                many.add(cardFor(customer, CardStatus.ACTIVE, LAPSED_ISSUED_AT, LAPSED_VALIDITY));
            }
            repository.saveAll(many);
            repository.flush();

            List<Card> firstPage = repository.findLapsed(
                    List.of(CardStatus.ACTIVE),
                    LocalDate.ofInstant(NOW, java.time.ZoneOffset.UTC),
                    org.springframework.data.domain.PageRequest.of(0, 2));
            assertThat(firstPage).hasSize(2);
        }
    }

    private Card cardFor(UUID customer, CardStatus status) {
        return cardFor(customer, status, NOW, Period.ofMonths(36));
    }

    private Card cardFor(UUID customer, CardStatus status, Instant issuedAt, Period validity) {
        Pan pan = Pan.mint();
        Card card = Card.issue(
                UUID.randomUUID(),
                tokenizer.tokenise(pan).value(),
                tokenizer.ownerDigest("s-" + UUID.randomUUID()),
                customer,
                pan,
                CardBrand.DEBIT,
                validity,
                issuedAt);
        if (status == CardStatus.FROZEN) {
            card.freeze(issuedAt);
        } else if (status == CardStatus.CANCELLED) {
            card.cancel(issuedAt);
        } else if (status == CardStatus.EXPIRED) {
            card.expire(issuedAt);
        }
        return card;
    }

    /**
     * Column list and rows for the schema assertions.
     *
     * <p>Every helper here goes through {@link DataSourceUtils} rather than {@code dataSource} so the
     * connection it borrows is the one running the test's transaction. A connection taken straight from
     * the pool is outside it: rows written by the repository are invisible to it, and rows it writes are
     * auto-committed, so a test that inserts a deliberately invalid row would leave it behind for every
     * test that ran afterwards.
     */
    private List<String> columnNames(String table) throws Exception {
        List<String> names = new java.util.ArrayList<>();
        var connection = DataSourceUtils.getConnection(dataSource);
        try (var statement = connection.prepareStatement(
                "select column_name from information_schema.columns where table_name = ?")) {
            statement.setString(1, table);
            try (var rs = statement.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString(1));
                }
            }
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
        return names;
    }

    private String singleRowAsText() throws Exception {
        var connection = DataSourceUtils.getConnection(dataSource);
        try (var statement = connection.createStatement();
                var rs = statement.executeQuery("select cards::text from cards")) {
            return rs.next() ? rs.getString(1) : "";
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }

    /**
     * Inserts a card row directly, bypassing the aggregate, to prove the database's own checks hold.
     *
     * <p>The point of going around the domain is that the domain cannot produce these rows at all, so
     * the only way to find out whether the CHECK constraints work is to write something the domain
     * would refuse. A migration script or a support engineer with psql is exactly that path.
     *
     * <p>The row is assembled as an ordered column-to-expression map and the column under test
     * <em>replaces</em> its entry. Two earlier versions of this helper appended the override to a list
     * that already contained it, and both failed for the same reason and in the same unhelpful way:
     * Postgres rejected the statement with "column specified more than once" before evaluating any
     * CHECK, so every test reported a duplicate-column error while appearing to be about constraints.
     */
    private void rawInsert(String column, String value) {
        java.util.LinkedHashMap<String, String> cells = new java.util.LinkedHashMap<>();
        cells.put("id", "?");
        cells.put("token", "?");
        cells.put("owner_subject_digest", "?");
        cells.put("customer_id", "?");
        cells.put("last4", "'4242'");
        cells.put("brand", "'DEBIT'");
        cells.put("expires_on", "date '2029-06-30'");
        cells.put("status", "'ACTIVE'");
        cells.put("created_at", "timestamptz '2026-06-15 12:00:00+00'");
        cells.put("updated_at", "timestamptz '2026-06-15 12:00:00+00'");
        cells.put("version", "0");
        cells.put(column, "?" + (column.endsWith("_at") ? "::timestamptz" : ""));

        String sql = "insert into cards (" + String.join(", ", cells.keySet()) + ") values ("
                + String.join(", ", cells.values()) + ")";

        var connection = DataSourceUtils.getConnection(dataSource);
        try (var statement = connection.prepareStatement(sql)) {
            int index = 1;
            for (var cell : cells.entrySet()) {
                // Only the cells written as "?" take a parameter; the rest are inline literals in the
                // SQL. Binding every cell regardless counts the literals as parameters and the driver
                // rejects the statement with an index out of range.
                if (!cell.getValue().startsWith("?")) {
                    continue;
                }
                switch (cell.getKey()) {
                    case "id", "customer_id" -> statement.setObject(index++, UUID.randomUUID());
                    case "token" ->
                        statement.setString(
                                index++, tokenizer.tokenise(Pan.mint()).value());
                    case "owner_subject_digest" ->
                        statement.setString(index++, tokenizer.ownerDigest("raw-" + UUID.randomUUID()));
                    default -> statement.setString(index++, value);
                }
            }
            statement.executeUpdate();
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        } finally {
            DataSourceUtils.releaseConnection(connection, dataSource);
        }
    }
}
