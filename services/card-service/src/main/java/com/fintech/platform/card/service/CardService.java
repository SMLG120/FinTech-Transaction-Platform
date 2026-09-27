package com.fintech.platform.card.service;

import com.fintech.platform.card.domain.Card;
import com.fintech.platform.card.domain.CardBrand;
import com.fintech.platform.card.domain.CardStatus;
import com.fintech.platform.card.domain.Pan;
import com.fintech.platform.card.error.CardErrorCodes;
import com.fintech.platform.card.persistence.CardRepository;
import com.fintech.platform.card.tokenisation.CardTokenizer;
import com.fintech.platform.common.identity.InternalIdentity;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Issuing and lifecycle operations on cards.
 *
 * <p>The only place in the platform that holds a card number, and it holds it for the length of one
 * method call. See {@link Pan} for why that is enforced by the type system and {@link IssuedCard} for
 * the one response that carries it out.
 *
 * <p>Transactional throughout, with the read paths marked read-only. Expiry is deliberately a write:
 * reading a lapsed card retires it, so {@link #getCard} and friends are not read-only transactions even
 * though they mostly read. A read-only version would have had to report the card as ACTIVE and let the
 * sweep catch up, which is how a card ends up shown to its holder as usable the day after it stopped
 * being usable.
 */
@Service
public class CardService {

    /**
     * Statuses that still occupy a slot in a customer's card allowance.
     *
     * <p>A frozen or lost card still exists and still has to be replaced, so it counts. A cancelled or
     * expired one does not, so cancelling frees the slot and a customer is never permanently locked out
     * of issuing by having churned through cards.
     */
    private static final List<CardStatus> OPEN_STATUSES =
            List.of(CardStatus.ACTIVE, CardStatus.FROZEN, CardStatus.LOST);

    /** Statuses the expiry sweep is allowed to touch. The mirror image of {@link #OPEN_STATUSES}. */
    private static final List<CardStatus> SWEEPABLE_STATUSES = List.of(CardStatus.ACTIVE, CardStatus.FROZEN);

    /**
     * Cards retired per sweep.
     *
     * <p>A bound on one transaction rather than a limit on the feature: a table with a million lapsed
     * cards is retired over several runs, and each run commits. The alternative — one transaction over
     * every lapsed card — is the shape of code that passes every test and then holds a write lock long
     * enough to matter.
     */
    private static final int SWEEP_BATCH = 200;

    private final Period validityPeriod;
    private final int maxCardsPerCustomer;
    private final CardRepository repository;
    private final CardTokenizer tokenizer;
    private final CardAuthorization authorization;
    private final CardIssuanceEligibility eligibility;
    private final Clock clock;

    public CardService(
            CardRepository repository,
            CardTokenizer tokenizer,
            CardAuthorization authorization,
            CardIssuanceEligibility eligibility,
            Clock clock,
            @Value("${platform.card.validity-months:36}") int validityMonths,
            @Value("${platform.card.max-cards-per-customer:5}") int maxCardsPerCustomer) {
        if (validityMonths <= 0 || validityMonths > Card.MAX_VALIDITY_MONTHS) {
            throw new IllegalStateException("platform.card.validity-months must be between 1 and "
                    + Card.MAX_VALIDITY_MONTHS + ", got " + validityMonths);
        }
        if (maxCardsPerCustomer < 1) {
            throw new IllegalStateException("platform.card.max-cards-per-customer must be at least 1");
        }
        this.repository = repository;
        this.tokenizer = tokenizer;
        this.authorization = authorization;
        this.eligibility = eligibility;
        this.clock = clock;
        this.validityPeriod = Period.ofMonths(validityMonths);
        this.maxCardsPerCustomer = maxCardsPerCustomer;
    }

    /**
     * Issues a card to the caller.
     *
     * <p>The order of the three checks is the substance of this method.
     *
     * <ol>
     *   <li>The card allowance is counted first, because it is local and free. A customer who already
     *       holds the maximum is told that without troubling customer-service.
     *   <li>Eligibility is checked next, because that is the check that can fail for reasons which are
     *       not about the caller at all.
     *   <li>The number is minted last, immediately before it is used. Minting first would leave a
     *       card number sitting in memory across a network call to customer-service, which is a longer
     *       exposure of a secret than the design needs to take.
     * </ol>
     *
     * <p>{@code customerId} is not trusted to identify the caller. It goes to the eligibility check
     * alongside the caller's own signed identity, and customer-service refuses if the two do not
     * match, so naming somebody else's customer id yields 403 from the profile service rather than a
     * card issued in their name. This is the reason the check exists in a service that has no copy of
     * the identity state: the one place that does can answer "is this your profile and is it approved"
     * in a single authoritative reply.
     *
     * <p>No staff override. See {@link CardAuthorization}: only a cardholder issues their own card.
     *
     * @return the card and its number, which is returned to the caller once and never stored
     */
    @Transactional
    public IssuedCard issue(InternalIdentity caller, UUID customerId, CardBrand brand) {
        Instant now = clock.instant();

        long held = repository.countByCustomerIdAndStatusIn(customerId, OPEN_STATUSES);
        if (held >= maxCardsPerCustomer) {
            throw CardErrorCodes.CARD_LIMIT_REACHED.exception(
                    "customer already holds " + held + " of a permitted " + maxCardsPerCustomer + " cards",
                    Map.of("held", held, "limit", maxCardsPerCustomer));
        }

        if (!eligibility.isApproved(customerId, caller)) {
            throw CardErrorCodes.HOLDER_NOT_ELIGIBLE.exception();
        }

        Pan pan = Pan.mint();
        String token = tokenizer.tokenise(pan).value();

        // Deterministic tokenisation means this collision is reachable only if the same number was
        // minted twice, which SecureRandom makes vanishingly unlikely. The check turns a 500 from a
        // unique-constraint violation three frames up into a definite, retryable domain outcome, and
        // the retry is safe because nothing is persisted until the save below commits.
        if (repository.countByToken(token) > 0) {
            throw CardErrorCodes.CARD_INVALID_TRANSITION.exception("token collision; retry the request");
        }

        Card saved = repository.save(Card.issue(
                UUID.randomUUID(),
                token,
                tokenizer.ownerDigest(caller.subject()),
                customerId,
                pan,
                brand,
                validityPeriod,
                now));
        return IssuedCard.of(saved, today(), pan);
    }

    /** The caller's own cards, newest first. Staff get their own empty list rather than everyone's. */
    @Transactional(readOnly = true)
    public List<CardView> listOwnCards(InternalIdentity caller) {
        return repository.findByOwnerSubjectDigestOrderByCreatedAtDesc(tokenizer.ownerDigest(caller.subject())).stream()
                .map(card -> CardView.of(card, today()))
                .toList();
    }

    /**
     * One card, by id.
     *
     * <p>The first lookup is scoped to the caller's own digest, so the common case — a cardholder
     * reading their own card — costs one indexed query and never has to authorise anything, because
     * matching the digest <em>is</em> the authorisation. Only when that misses does the service look
     * the card up again unscoped, and only to let a staff reader see it.
     */
    @Transactional
    public CardView getCard(InternalIdentity caller, UUID cardId) {
        return CardView.of(requireVisible(caller, cardId), today());
    }

    @Transactional
    public CardView freeze(InternalIdentity caller, UUID cardId) {
        Card card = requireOperable(caller, cardId, Operation.STOP);
        card.freeze(clock.instant());
        return CardView.of(card, today());
    }

    /**
     * Returns a frozen card to service.
     *
     * <p>Cardholder only, and impossible for a card reported lost. See {@link CardAuthorization} and
     * {@link Card#unfreeze}.
     */
    @Transactional
    public CardView unfreeze(InternalIdentity caller, UUID cardId) {
        Card card = requireOperable(caller, cardId, Operation.REACTIVATE);
        card.unfreeze(clock.instant());
        return CardView.of(card, today());
    }

    /**
     * Reports a card lost or stolen.
     *
     * <p>Available to support, because it is what a customer asks for when they ring to report a
     * stolen card. A service that cannot do it makes them ask twice, and the second ask happens on a
     * channel nobody logs.
     */
    @Transactional
    public CardView reportLost(InternalIdentity caller, UUID cardId) {
        Card card = requireOperable(caller, cardId, Operation.STOP);
        card.reportLost(clock.instant());
        return CardView.of(card, today());
    }

    @Transactional
    public CardView cancel(InternalIdentity caller, UUID cardId) {
        Card card = requireOperable(caller, cardId, Operation.CLOSE);
        card.cancel(clock.instant());
        return CardView.of(card, today());
    }

    /**
     * Retires every live card whose expiry date has passed, in bounded batches.
     *
     * <p>Needed because {@link #getCard} only catches the cards somebody looks at. An unlooked-at card
     * would otherwise sit at ACTIVE indefinitely, and a status that is only corrected by a read is not a
     * status a batch job, a report or the authorisation path can rely on.
     *
     * <p>Public and transactional so that a scheduler and the test exercise the same code rather than a
     * copy of it that can drift.
     *
     * @return how many cards this run retired
     */
    @Transactional
    public int expireLapsedCards() {
        LocalDate today = today();
        int retired = 0;
        boolean more = true;
        // Each batch commits as the query shrinks: retiring a card removes it from SWEEPABLE_STATUSES,
        // so the walk strictly progresses and terminates instead of re-reading the same page.
        while (more) {
            List<Card> batch = repository.findLapsed(SWEEPABLE_STATUSES, today, PageRequest.of(0, SWEEP_BATCH));
            more = batch.size() == SWEEP_BATCH;
            for (Card card : batch) {
                card.expire(clock.instant());
                retired++;
            }
        }
        return retired;
    }

    /** Which authorisation rule a lifecycle operation is held to. */
    private enum Operation {
        /** Only ever moves a card away from use, so support may do it. */
        STOP,
        /** Returns a card to circulation, so only the cardholder may. */
        REACTIVATE,
        /** Irreversible, so admin only. */
        CLOSE
    }

    private Card requireOperable(InternalIdentity caller, UUID cardId, Operation operation) {
        Card card = requireVisible(caller, cardId);
        String digest = tokenizer.ownerDigest(caller.subject());
        String owner = card.getOwnerSubjectDigest();

        switch (operation) {
            case STOP -> authorization.requireSelfOrRestrictor(caller, owner, digest);
            case REACTIVATE -> authorization.requireSelf(caller, owner, digest);
            case CLOSE -> authorization.requireSelfOrAdmin(caller, owner, digest);
        }

        if (!card.isOperable()) {
            throw CardErrorCodes.CARD_INVALID_TRANSITION.exception(
                    "card is " + card.getStatus() + " and no further operations are possible",
                    Map.of("status", card.getStatus().name()));
        }
        return card;
    }

    /**
     * Loads a card the caller may see, applying expiry on the way past.
     *
     * <p>Three outcomes, and the order matters. A card the caller owns is found by the digest-scoped
     * query. A card they do not own but a staff role covers is found by the unscoped query and
     * authorised. A card that does not exist is 404 for everyone, staff included, so this method cannot
     * be used to enumerate which card ids are real.
     *
     * <p>The row is loaded before the staff check rather than after, which is a deliberate trade: it
     * costs a second query for a staff reader and buys the certainty that a non-reader is refused
     * against the real owner digest rather than a guess. The alternative — authorising on the absence of
     * a match — cannot distinguish "not yours" from "not there", and the first is a 403 while the second
     * has to be a 404.
     */
    private Card requireVisible(InternalIdentity caller, UUID cardId) {
        String digest = tokenizer.ownerDigest(caller.subject());
        Card card = repository.findByIdAndOwnerSubjectDigest(cardId, digest).orElse(null);

        if (card == null) {
            Card unscoped = repository.findById(cardId).orElse(null);
            if (unscoped == null) {
                throw CardErrorCodes.CARD_NOT_FOUND.exception();
            }
            authorization.requireReadAccess(caller, unscoped.getOwnerSubjectDigest(), digest);
            card = unscoped;
        }

        if (card.hasLapsed(today())) {
            card.expire(clock.instant());
        }
        return card;
    }

    private LocalDate today() {
        return LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC);
    }
}
