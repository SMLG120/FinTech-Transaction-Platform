package com.fintech.platform.card.service;

import com.fintech.platform.card.domain.Card;
import com.fintech.platform.card.domain.CardBrand;
import com.fintech.platform.card.domain.CardStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * What the HTTP layer is allowed to see about a card.
 *
 * <p>There is no field here for a card number, a token, an owner digest or a cardholder name, so a
 * controller cannot leak one by accident even if a future edit adds a field: the wrong information
 * would have to be added here first, next to this comment, where it would be noticed.
 *
 * <p>{@code last4} is the only fragment of the number that exists after issue, which is why it is safe
 * to expose broadly to the reader set. The token is not exposed at all, not even to the cardholder:
 * a token is a bearer value for the authorisation path, and it has no business in a response a client
 * can log.
 *
 * @param customerId present so a support agent can correlate a card with the profile they are looking
 *     at, and so a cardholder's client can confirm which profile it belongs to
 */
public record CardView(
        UUID id,
        UUID customerId,
        String last4,
        CardBrand brand,
        LocalDate expiresOn,
        CardStatus status,
        boolean usable,
        Instant frozenAt,
        Instant lostAt,
        Instant cancelledAt,
        Instant createdAt) {

    public static CardView of(Card card, LocalDate today) {
        return new CardView(
                card.getId(),
                card.getCustomerId(),
                card.getLast4(),
                card.getBrand(),
                card.getExpiresOn(),
                card.getStatus(),
                card.isUsable(today),
                card.getFrozenAt(),
                card.getLostAt(),
                card.getCancelledAt(),
                card.getCreatedAt());
    }
}
