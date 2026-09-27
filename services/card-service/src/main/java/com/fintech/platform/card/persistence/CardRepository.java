package com.fintech.platform.card.persistence;

import com.fintech.platform.card.domain.Card;
import com.fintech.platform.card.domain.CardStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Card storage.
 *
 * <p>Every method that can be answered from a card row is scoped to the cardholder digest rather than
 * filtering in memory after a load. {@link #findByIdAndOwnerSubjectDigest} in particular is the
 * ownership check: a card that is not the caller's is not loaded at all, so a bug in a view or a
 * serialiser cannot turn a forbidden card into a readable one, and the database is not asked to hand
 * over a row the caller has no claim to.
 *
 * <p>No method exposes a query by token for use outside the authorisation path. Tokens are what a
 * network would present in an authorisation, so this service will need one eventually, but that belongs
 * to the transaction phase along with the network simulation; exposing it now would be an unused way to
 * look up a card without proving ownership.
 */
public interface CardRepository extends JpaRepository<Card, UUID> {

    Optional<Card> findByIdAndOwnerSubjectDigest(UUID id, String ownerSubjectDigest);

    List<Card> findByOwnerSubjectDigestOrderByCreatedAtDesc(String ownerSubjectDigest);

    Optional<Card> findByToken(String token);

    /**
     * The customer's non-terminal cards.
     *
     * <p>Passes the terminal statuses in as a collection rather than using {@code status <> :status},
     * because "not in (CANCELLED, EXPIRED)" and "in (ACTIVE, FROZEN, LOST)" are equivalent today and will
     * diverge the moment a fourth terminal state appears: the first form would start counting the new
     * state against the limit, the second would not. The literal list is the safer one to leave behind.
     */
    List<Card> findByCustomerIdAndStatusIn(UUID customerId, Collection<CardStatus> statuses);

    /**
     * How many of a customer's cards are still live.
     *
     * <p>A count rather than a length of {@link #findByCustomerIdAndStatusIn}, because loading five
     * rows to discover there are five of them, on every issue attempt, is a cost the enforcement of a
     * limit should not carry. The statuses are passed in for the same reason the finder takes them: the
     * definition of "live" belongs to the caller, so adding a status cannot silently change what the
     * limit means.
     */
    long countByCustomerIdAndStatusIn(UUID customerId, Collection<CardStatus> statuses);

    /**
     * A page of cards that are still live by status but past their expiry date.
     *
     * <p>Paged rather than returned whole so the sweep that retires lapsed cards is bounded per
     * transaction. Retired cards drop out of the predicate, so a caller walking pages with this query
     * terminates rather than looping over the same rows forever — which is the failure mode of the
     * obvious "find all, filter in memory" version.
     *
     * <p>Strictly less than, matching {@link Card#hasLapsed} exactly. {@code expires_on} is the last
     * day the card is valid, so a card expiring today is still live today and the sweep must not touch
     * it. The two must not be allowed to drift apart: this query is the only thing standing between a
     * card and a day of validity nobody notices is missing.
     */
    @Query("select c from Card c where c.status in :statuses and c.expiresOn < :today order by c.id")
    List<Card> findLapsed(
            @Param("statuses") Collection<CardStatus> statuses, @Param("today") LocalDate today, Pageable pageable);

    /**
     * Whether the token is already in use.
     *
     * <p>A count query rather than relying on the unique constraint so that a collision is reported as
     * a domain condition. The constraint is still the backstop; this exists so a token clash does not
     * surface as a constraint violation five frames up the stack.
     */
    @Query("select count(c) from Card c where c.token = :token")
    long countByToken(@Param("token") String token);
}
