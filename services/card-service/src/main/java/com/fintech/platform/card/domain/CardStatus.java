package com.fintech.platform.card.domain;

/**
 * Where a card is in its life.
 *
 * <p>{@link #EXPIRED} is a status rather than a property of the expiry date because the two answer
 * different questions. {@link Card#expiresOn()} says when the number stops working according to the
 * calendar; {@code EXPIRED} says the platform has recorded that it stopped. Keeping them apart is what
 * lets a batch job find the cards that lapsed, and it is the same reasoning that keeps a lapsed
 * identity check in its own state instead of inferring it from a date.
 */
public enum CardStatus {

    /** Usable, subject to an authorisation decision at the point of sale. */
    ACTIVE,

    /**
     * Temporarily unusable at the cardholder's request, and reversible.
     *
     * <p>Separate from {@link #LOST} because a frozen card is a precaution the holder can undo and a
     * lost card is not.
     */
    FROZEN,

    /** Reported lost or stolen. Terminal for the card in question. */
    LOST,

    /** Closed by the holder or by the platform. Terminal. */
    CANCELLED,

    /** Past its expiry date, or retired by the platform. Terminal. */
    EXPIRED
}
