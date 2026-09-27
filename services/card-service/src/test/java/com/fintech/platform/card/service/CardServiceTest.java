package com.fintech.platform.card.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.card.domain.Card;
import com.fintech.platform.card.domain.CardBrand;
import com.fintech.platform.card.domain.CardStatus;
import com.fintech.platform.card.domain.Pan;
import com.fintech.platform.card.error.CardErrorCodes;
import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.common.identity.InternalIdentity;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The issuing and lifecycle rules that sit above the aggregate.
 *
 * <p>A fake repository and a stub eligibility check, deliberately. This is the layer whose job is
 * ordering and delegation, so the things worth testing are which check runs before which, what a caller
 * is told when one of them refuses, and that the number never escapes. Wiring a real database in would
 * make the assertions slower and would not make them sharper.
 */
class CardServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-15T12:00:00Z");
    private static final UUID UUID_NO_SUCH_CARD = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    private Repository repository;
    private StubEligibility eligibility;
    private CardAuthorization authorization;
    private CardService service;

    @BeforeEach
    void setUp() {
        repository = new Repository();
        eligibility = new StubEligibility();
        authorization = new CardAuthorization();
        service = new CardService(
                repository.wired(),
                TestTokens.tokenizer(),
                authorization,
                eligibility,
                Clock.fixed(NOW, ZoneOffset.UTC),
                36,
                5);
    }

    @Nested
    @DisplayName("issuing")
    class Issuing {

        @Test
        @DisplayName("returns the number exactly once, and never persists it")
        void returnsTheNumberOnce() {
            IssuedCard issued = service.issue(caller("subject-1"), UUID.randomUUID(), CardBrand.DEBIT);

            // Grouped in fours, because a cardholder reads a number in groups and an unbroken
            // sixteen digits is the shape people get wrong when reading it off a screen.
            assertThat(issued.cardNumber()).matches("\\d{4} \\d{4} \\d{4} \\d{4}");
            // The whole point of the design, asserted where it is actually observable: the aggregate
            // that was handed the number has no field for it, and neither does the saved row.
            assertThat(repository.saved()).hasSize(1);
            assertThat(repository.saved().getFirst().toString()).doesNotContain(issued.cardNumber());
            assertThat(repository.saved().getFirst().getLast4())
                    .isEqualTo(issued.cardNumber().substring(issued.cardNumber().length() - 4));
        }

        @Test
        @DisplayName("stores a token rather than the number, and derives the owner's digest")
        void storesTokenAndDigest() {
            UUID customer = UUID.randomUUID();
            IssuedCard issued = service.issue(caller("subject-1"), customer, CardBrand.CREDIT);

            Card saved = repository.saved.getFirst();
            assertThat(saved.getToken()).hasSize(64).isNotEqualTo(issued.cardNumber());
            assertThat(saved.getOwnerSubjectDigest())
                    .isEqualTo(TestTokens.tokenizer().ownerDigest("subject-1"));
            assertThat(saved.getCustomerId()).isEqualTo(customer);
            assertThat(saved.getBrand()).isEqualTo(CardBrand.CREDIT);
            assertThat(saved.getStatus()).isEqualTo(CardStatus.ACTIVE);
        }

        @Test
        @DisplayName("refuses a customer at their card limit without troubling customer-service")
        void refusesAtTheLimitWithoutCallingOut() {
            // The limit is local and free, and a customer who is merely told they have enough cards
            // should not have their profile service involved in being told so.
            UUID customer = UUID.randomUUID();
            for (int i = 0; i < 5; i++) {
                repository.current(customer, "someone-else-" + i);
            }

            assertThatThrownBy(() -> service.issue(caller("subject-1"), customer, CardBrand.DEBIT))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CardErrorCodes.CARD_LIMIT_REACHED.code());
            assertThat(eligibility.calls).isZero();
        }

        @Test
        @DisplayName("counts a frozen or lost card against the limit but not a cancelled one")
        void countsTheRightStatuses() {
            UUID customer = UUID.randomUUID();
            repository.current(customer, "a");
            Card frozen = repository.current(customer, "b");
            frozen.freeze(NOW);
            Card lost = repository.current(customer, "c");
            lost.reportLost(NOW);
            Card cancelled = repository.current(customer, "d");
            cancelled.cancel(NOW);

            // Four cards, but only three of them occupy a slot, so a fifth is still issuable. Cancelling
            // has to free the slot or a customer who churns through cards is locked out for good.
            assertThat(service.issue(caller("subject-1"), customer, CardBrand.DEBIT)
                            .cardNumber())
                    .matches("\\d{4} \\d{4} \\d{4} \\d{4}");
        }

        @Test
        @DisplayName("refuses to issue to a customer who is not KYC approved")
        void refusesIneligible() {
            eligibility.approved = false;

            assertThatThrownBy(() -> service.issue(caller("subject-1"), UUID.randomUUID(), CardBrand.DEBIT))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CardErrorCodes.HOLDER_NOT_ELIGIBLE.code());
            assertThat(repository.saved()).isEmpty();
        }

        @Test
        @DisplayName("refuses a token collision rather than letting the unique constraint surface")
        void refusesTokenCollision() {
            repository.tokenAlreadyTaken = true;

            // SecureRandom makes this vanishingly unlikely, but "retry the request" is the right answer
            // and a 500 from a constraint violation three frames up is not.
            assertThatThrownBy(() -> service.issue(caller("subject-1"), UUID.randomUUID(), CardBrand.DEBIT))
                    .isInstanceOf(ApiException.class)
                    .hasMessageContaining("token collision");
        }
    }

    @Nested
    @DisplayName("reading")
    class Reading {

        @Test
        @DisplayName("shows a cardholder only their own cards")
        void listsOnlyOwnCards() {
            repository.current(UUID.randomUUID(), "subject-1");
            repository.current(UUID.randomUUID(), "subject-2");

            List<CardView> found = service.listOwnCards(caller("subject-1"));

            assertThat(found).hasSize(1);
        }

        @Test
        @DisplayName("retires a lapsed card on the way past, rather than reporting it as active")
        void expiresOnRead() {
            Card lapsed = repository.lapsed(UUID.randomUUID(), "subject-1");

            CardView view = service.getCard(caller("subject-1"), lapsed.getId());

            // The cardholder sees EXPIRED, not ACTIVE-but-unusable: a status that is only corrected by a
            // read is a status nothing else can rely on.
            assertThat(view.status()).isEqualTo(CardStatus.EXPIRED);
            assertThat(view.usable()).isFalse();
        }

        @Test
        @DisplayName("reports a card that does not exist as not found, for everyone")
        void reportsMissingAsNotFound() {
            // 404 rather than 403, so the endpoint cannot be used to discover which card ids are real.
            assertThatThrownBy(() -> service.getCard(caller("subject-1"), UUID_NO_SUCH_CARD))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CardErrorCodes.CARD_NOT_FOUND.code());
        }

        @Test
        @DisplayName("refuses another cardholder's card without revealing that it exists")
        void refusesAnotherCardholdersCard() {
            Card theirs = repository.current(UUID.randomUUID(), "subject-2");

            assertThatThrownBy(() -> service.getCard(caller("subject-1"), theirs.getId()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CardErrorCodes.NOT_THE_CARDHOLDER.code());
        }
    }

    @Nested
    @DisplayName("lifecycle")
    class Lifecycle {

        @Test
        @DisplayName("lets a cardholder freeze and unfreeze their own card")
        void freezeAndUnfreeze() {
            Card card = repository.current(UUID.randomUUID(), "subject-1");

            assertThat(service.freeze(caller("subject-1"), card.getId()).status())
                    .isEqualTo(CardStatus.FROZEN);
            assertThat(service.unfreeze(caller("subject-1"), card.getId()).status())
                    .isEqualTo(CardStatus.ACTIVE);
        }

        @Test
        @DisplayName("lets support freeze a card they do not own")
        void supportCanStop() {
            Card card = repository.current(UUID.randomUUID(), "subject-1");

            assertThat(service.freeze(caller("agent", "SUPPORT_AGENT"), card.getId())
                            .status())
                    .isEqualTo(CardStatus.FROZEN);
        }

        @Test
        @DisplayName("refuses to let support reactivate a card")
        void supportCannotReactivate() {
            // Support may stop a card but not bring one back: unfreezing puts a card into use, and
            // support stopping a card is a precaution while a cardholder's own unfreeze is a decision
            // about their own money.
            Card card = repository.current(UUID.randomUUID(), "subject-1");
            service.freeze(caller("agent", "SUPPORT_AGENT"), card.getId());

            assertThatThrownBy(() -> service.unfreeze(caller("agent", "SUPPORT_AGENT"), card.getId()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CardErrorCodes.NOT_THE_CARDHOLDER.code());
        }

        @Test
        @DisplayName("lets a cardholder cancel, and an admin too")
        void cancelByHolderOrAdmin() {
            Card byHolder = repository.current(UUID.randomUUID(), "subject-1");
            assertThat(service.cancel(caller("subject-1"), byHolder.getId()).status())
                    .isEqualTo(CardStatus.CANCELLED);

            Card byAdmin = repository.current(UUID.randomUUID(), "subject-3");
            assertThat(service.cancel(caller("admin", "PLATFORM_ADMIN"), byAdmin.getId())
                            .status())
                    .isEqualTo(CardStatus.CANCELLED);
        }

        @Test
        @DisplayName("refuses to let a customer with no role cancel somebody else's card")
        void refusesCrossCancellation() {
            Card theirs = repository.current(UUID.randomUUID(), "subject-2");

            assertThatThrownBy(() -> service.cancel(caller("subject-1"), theirs.getId()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CardErrorCodes.NOT_THE_CARDHOLDER.code());
        }

        @Test
        @DisplayName("refuses to unfreeze a card reported lost, naming the reason")
        void refusesUnfreezingLost() {
            Card card = repository.current(UUID.randomUUID(), "subject-1");
            service.freeze(caller("subject-1"), card.getId());
            service.reportLost(caller("subject-1"), card.getId());

            assertThatThrownBy(() -> service.unfreeze(caller("subject-1"), card.getId()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CardErrorCodes.CARD_REPORTED_LOST.code());
        }

        @Test
        @DisplayName("refuses any further operation on a cancelled card")
        void refusesAfterCancellation() {
            Card card = repository.current(UUID.randomUUID(), "subject-1");
            service.cancel(caller("subject-1"), card.getId());

            assertThatThrownBy(() -> service.freeze(caller("subject-1"), card.getId()))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getErrorCode().code())
                    .isEqualTo(CardErrorCodes.CARD_INVALID_TRANSITION.code());
        }
    }

    @Nested
    @DisplayName("the expiry sweep")
    class Sweep {

        @Test
        @DisplayName("retires every lapsed card and reports how many")
        void retiresLapsedCards() {
            for (int i = 0; i < 3; i++) {
                repository.lapsed(UUID.randomUUID(), "subject-" + i);
            }

            assertThat(service.expireLapsedCards()).isEqualTo(3);
        }

        @Test
        @DisplayName("leaves a card that is still within its validity alone")
        void leavesCurrentCardsAlone() {
            repository.current(UUID.randomUUID(), "subject-1");

            assertThat(service.expireLapsedCards()).isZero();
        }
    }

    @Test
    @DisplayName("rejects a nonsensical configuration at startup rather than at first use")
    void rejectsBadConfiguration() {
        // A validity of zero months would otherwise produce a card that is born expired, and the failure
        // would show up as a cardholder's problem rather than a deployment's.
        assertThatThrownBy(() -> new CardService(
                        repository.wired(),
                        TestTokens.tokenizer(),
                        authorization,
                        eligibility,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        0,
                        5))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CardService(
                        repository.wired(),
                        TestTokens.tokenizer(),
                        authorization,
                        eligibility,
                        Clock.fixed(NOW, ZoneOffset.UTC),
                        36,
                        0))
                .isInstanceOf(IllegalStateException.class);
    }

    private static InternalIdentity caller(String subject, String... roles) {
        return new InternalIdentity(subject, "user", List.of(roles), "corr-1", NOW);
    }

    /** Mirrors the real adapter's contract: an answer, and the number of times it was asked. */
    private static final class StubEligibility implements CardIssuanceEligibility {

        private boolean approved = true;
        private int calls;

        @Override
        public boolean isApproved(java.util.UUID customerId, InternalIdentity caller) {
            calls++;
            return approved;
        }
    }

    /**
     * A real tokenizer over a fixed key.
     *
     * <p>Real rather than stubbed because tokenisation is not a collaborator here, it is a property of
     * the service: the owner digest the service writes has to be the same one its queries will look up,
     * and a stub that returned whatever the test wanted would let a digest mismatch pass unnoticed.
     */
    static final class TestTokens {

        private static final com.fintech.platform.card.tokenisation.CardTokenizer TOKENIZER = build();

        private TestTokens() {}

        static com.fintech.platform.card.tokenisation.CardTokenizer tokenizer() {
            return TOKENIZER;
        }

        private static com.fintech.platform.card.tokenisation.CardTokenizer build() {
            var properties = new com.fintech.platform.card.tokenisation.CardTokenisationProperties();
            properties.setKey(java.util.Base64.getEncoder()
                    .encodeToString("0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8)));
            return new com.fintech.platform.card.tokenisation.CardTokenizer(properties);
        }
    }

    /**
     * The repository, as a Mockito mock over a real list of rows.
     *
     * <p>A mock rather than a hand-written fake because {@code CardRepository} extends
     * {@code JpaRepository}, whose sixty-odd inherited methods are none of this test's business. The
     * handful that are get real answers from the list, so the count queries, the owner-scoped find and
     * the lapsed page behave the way the service's rules actually depend on them behaving, instead of
     * being told what to return.
     */
    private static final class Repository {

        private final com.fintech.platform.card.persistence.CardRepository mock =
                org.mockito.Mockito.mock(com.fintech.platform.card.persistence.CardRepository.class);

        private final List<Card> rows = new java.util.ArrayList<>();
        private final List<Card> saved = new java.util.ArrayList<>();

        /** Forces the token-collision branch, which SecureRandom otherwise makes unreachable. */
        private boolean tokenAlreadyTaken;

        void given(Card card) {
            rows.add(card);
        }

        List<Card> saved() {
            return saved;
        }

        Card fresh(UUID customer, String ownerSubject, java.time.Period validity, Instant issuedAt) {
            Pan pan = Pan.mint();
            return Card.issue(
                    UUID.randomUUID(),
                    TestTokens.tokenizer().tokenise(pan).value(),
                    TestTokens.tokenizer().ownerDigest(ownerSubject),
                    customer,
                    pan,
                    CardBrand.DEBIT,
                    validity,
                    issuedAt);
        }

        Card current(UUID customer, String ownerSubject) {
            Card card = fresh(customer, ownerSubject, Period.ofMonths(36), NOW);
            given(card);
            return card;
        }

        /**
         * A card whose validity ran out before the service's clock reads. Issued long enough ago with a
         * one-month validity, which is the only honest way to reach a lapsed card without forging a row.
         */
        Card lapsed(UUID customer, String ownerSubject) {
            Card card = fresh(customer, ownerSubject, Period.ofMonths(1), NOW.minus(Duration.ofDays(550)));
            given(card);
            return card;
        }

        private com.fintech.platform.card.persistence.CardRepository wired() {
            // A fresh matcher per stubbing, never a shared variable. Argument matchers are recorded on
            // a thread-local stack that the stubbing consumes, so handing the same matcher instance to
            // two stubbings leaves the second one looking at the first one's leftovers and Mockito
            // rejects the whole thing with "Invalid use of argument matchers".
            org.mockito.Mockito.lenient()
                    .when(mock.countByCustomerIdAndStatusIn(
                            org.mockito.ArgumentMatchers.any(UUID.class),
                            org.mockito.ArgumentMatchers.<Collection<CardStatus>>any()))
                    .thenAnswer(invocation -> rows.stream()
                            .filter(c -> c.getCustomerId().equals(invocation.<UUID>getArgument(0)))
                            .filter(c -> invocation
                                    .<Collection<CardStatus>>getArgument(1)
                                    .contains(c.getStatus()))
                            .count());
            org.mockito.Mockito.lenient()
                    .when(mock.countByToken(org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(invocation -> tokenAlreadyTaken ? 1L : 0L);
            org.mockito.Mockito.lenient()
                    .when(mock.findById(org.mockito.ArgumentMatchers.any(UUID.class)))
                    .thenAnswer(invocation -> rows.stream()
                            .filter(c -> c.getId().equals(invocation.<UUID>getArgument(0)))
                            .findFirst());
            org.mockito.Mockito.lenient()
                    .when(mock.findByIdAndOwnerSubjectDigest(
                            org.mockito.ArgumentMatchers.any(UUID.class), org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(invocation -> rows.stream()
                            .filter(c -> c.getId().equals(invocation.<UUID>getArgument(0)))
                            .filter(c -> c.getOwnerSubjectDigest().equals(invocation.<String>getArgument(1)))
                            .findFirst());
            org.mockito.Mockito.lenient()
                    .when(mock.findByOwnerSubjectDigestOrderByCreatedAtDesc(org.mockito.ArgumentMatchers.anyString()))
                    .thenAnswer(invocation -> rows.stream()
                            .filter(c -> c.getOwnerSubjectDigest().equals(invocation.<String>getArgument(0)))
                            .sorted(java.util.Comparator.comparing(Card::getCreatedAt)
                                    .reversed())
                            .toList());
            org.mockito.Mockito.lenient()
                    .when(mock.findLapsed(
                            org.mockito.ArgumentMatchers.<Collection<CardStatus>>any(),
                            org.mockito.ArgumentMatchers.any(LocalDate.class),
                            org.mockito.ArgumentMatchers.any(org.springframework.data.domain.Pageable.class)))
                    .thenAnswer(invocation -> {
                        var statuses = invocation.<Collection<CardStatus>>getArgument(0);
                        var day = invocation.<LocalDate>getArgument(1);
                        var page = invocation.<org.springframework.data.domain.Pageable>getArgument(2);
                        return rows.stream()
                                .filter(c -> statuses.contains(c.getStatus()))
                                .filter(c -> day.isAfter(c.getExpiresOn()))
                                .limit(page.getPageSize())
                                .toList();
                    });
            org.mockito.Mockito.lenient()
                    .when(mock.save(org.mockito.ArgumentMatchers.any(Card.class)))
                    .thenAnswer(invocation -> {
                        Card card = invocation.getArgument(0);
                        rows.add(card);
                        saved.add(card);
                        return card;
                    });
            return mock;
        }
    }
}
