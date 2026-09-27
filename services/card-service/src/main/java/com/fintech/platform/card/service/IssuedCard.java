package com.fintech.platform.card.service;

import com.fintech.platform.card.domain.Card;

/**
 * A newly issued card, and the only moment its number exists outside this process.
 *
 * <p>This type is the reason the platform can issue a usable card without storing a number. The PAN is
 * minted in memory, tokenised, reduced to four digits for the {@link Card}, and carried here solely so
 * the HTTP layer can put it in a single response. Nothing persists this object, nothing logs it, and
 * there is no endpoint that can return it later: after the response that carried it, the number is
 * gone and the card is reachable only by token and last4.
 *
 * <p>That is a real constraint on the cardholder, and it is the correct one. A number nobody can
 * retrieve is a number nobody can leak, and the holder has a card in their hand to read it from. The
 * platform trades convenience for the property that its database is not a card vault, and this record
 * is where the trade is made explicit.
 *
 * <p>{@link #cardNumber()} is a {@code String} rather than a {@code Pan} on purpose. The value leaves
 * the domain, and keeping the type would invite someone to hand this object to a logger that would
 * then call {@code Pan.toString} and print a masked number that is not what the client needs. At this
 * boundary it is unavoidably a plain secret, and it is named so that everyone who touches it knows.
 */
public record IssuedCard(CardView card, String cardNumber) {

    public static IssuedCard of(Card card, java.time.LocalDate today, com.fintech.platform.card.domain.Pan pan) {
        return new IssuedCard(CardView.of(card, today), pan.formatted());
    }
}
