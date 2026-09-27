package com.fintech.platform.card.web;

import com.fintech.platform.card.domain.CardBrand;
import com.fintech.platform.card.domain.CardStatus;
import com.fintech.platform.card.service.CardView;
import com.fintech.platform.card.service.IssuedCard;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * The card-service wire contract.
 *
 * <p>Read {@link IssueCardRequest} first, because it is the only request in the platform that carries
 * a secret in either direction and it is shaped entirely around that.
 */
public final class CardDtos {

    private CardDtos() {}

    /**
     * A request for a new card.
     *
     * <p>There is deliberately no field for a card number, and no way to supply one. The cardholder does
     * not choose their card number and does not send it: the platform mints it, tokenises it, and shows
     * it once. A request type that accepted a PAN would be the first step towards a flow where a client
     * can hand the platform a real number, which is precisely the capability ADR-0006 exists to
     * foreclose.
     *
     * @param customerId the customer to issue to. Checked against the caller's own identity by
     *     customer-service during the eligibility call, so a caller cannot issue to somebody else by
     *     naming them here
     * @param brand which product to issue; there is no default, because guessing on a missing field
     *     would issue a card the caller did not ask for
     */
    public record IssueCardRequest(
            @NotNull UUID customerId, @NotNull CardBrand brand) {}

    /**
     * A card, as returned on every read and on every lifecycle operation.
     *
     * <p>No token, no card number, no owner digest. See {@link CardView} for why those are absent rather
     * than merely omitted from this record.
     *
     * @param usable whether the card would be accepted right now, which is not the same question as its
     *     status: a card past its expiry date can still read {@code FROZEN} until something looks at
     *     it, and a client that only branched on {@code status} would tell a cardholder their frozen
     *     card is fine when it is not
     */
    public record CardResponse(
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

        public static CardResponse from(CardView view) {
            return new CardResponse(
                    view.id(),
                    view.customerId(),
                    view.last4(),
                    view.brand(),
                    view.expiresOn(),
                    view.status(),
                    view.usable(),
                    view.frozenAt(),
                    view.lostAt(),
                    view.cancelledAt(),
                    view.createdAt());
        }
    }

    /**
     * A newly issued card, and the only time its number is ever sent.
     *
     * <p>The single most important field in this contract is {@code cardNumber}, and the most important
     * fact about it is that it is not retrievable afterwards. There is no endpoint that returns it, no
     * admin view that shows it, and no table that stores it. A client that loses it has a card with a
     * number nobody in the platform can read, which is the intended outcome: the number's only remaining
     * copy is the plastic in the cardholder's hand.
     *
     * <p>It is a top-level field rather than folded into {@link CardResponse} so that the type makes the
     * asymmetry unmissable — a card response is built from a stored card, and a stored card has no number.
     */
    public record IssuedCardResponse(CardResponse card, String cardNumber) {

        public static IssuedCardResponse from(IssuedCard issued) {
            return new IssuedCardResponse(CardResponse.from(issued.card()), issued.cardNumber());
        }
    }
}
