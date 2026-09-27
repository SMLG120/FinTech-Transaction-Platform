package com.fintech.platform.card.error;

import com.fintech.platform.common.error.ErrorCode;
import org.springframework.http.HttpStatus;

/**
 * Error codes specific to card-service.
 *
 * <p>Same {@link ErrorCode} contract and the same wire format as every other service, so a client
 * handles a platform error without knowing which service raised it.
 *
 * <p>No code in this list ever mentions a card number, a token or a cardholder. That is a deliberate
 * constraint on the whole file rather than a property of any one entry: an error message is the most
 * likely thing in a service to end up in a log, a support ticket or a monitoring label, and the
 * platform has no safe way to review all of those. The codes therefore describe what the caller did
 * wrong, and the caller learns nothing about stored card data beyond the four digits already on
 * every receipt.
 */
public final class CardErrorCodes {

    private CardErrorCodes() {}

    public static final ErrorCode CARD_NOT_FOUND =
            ErrorCode.of("CARD_NOT_FOUND", HttpStatus.NOT_FOUND, "No card exists with that reference");

    /**
     * The cardholder named on the request does not exist.
     *
     * <p>Reported by card-service rather than passed through from customer-service, which raises the
     * same condition under its own code. Two services declaring the same condition with the same code
     * would look like a shared enum and would not be one; the alternative, card-service importing
     * customer-service's codes, would put another service's error vocabulary in this service's public
     * contract and make the two impossible to change independently. A caller sees the same 404 with a
     * code that means the same thing, which is what a client actually needs.
     */
    public static final ErrorCode HOLDER_NOT_FOUND =
            ErrorCode.of("HOLDER_NOT_FOUND", HttpStatus.NOT_FOUND, "No customer profile exists with that reference");

    /**
     * The caller is not the cardholder and holds no role that permits acting on the card.
     *
     * <p>403 rather than 404 for a non-owner who is authenticated, on the same reasoning as
     * customer-service: a caller who is neither the owner nor staff is entitled to know the card is
     * not theirs, and hiding that behind 404 would make a support agent's mistake look like a
     * non-existent card.
     */
    public static final ErrorCode NOT_THE_CARDHOLDER =
            ErrorCode.of("NOT_THE_CARDHOLDER", HttpStatus.FORBIDDEN, "This card belongs to another customer");

    public static final ErrorCode CARD_INVALID_TRANSITION = ErrorCode.of(
            "CARD_INVALID_TRANSITION",
            HttpStatus.CONFLICT,
            "The requested card operation is not allowed from the current state");

    public static final ErrorCode CARD_ALREADY_CANCELLED =
            ErrorCode.of("CARD_ALREADY_CANCELLED", HttpStatus.CONFLICT, "This card has already been cancelled");

    /**
     * A lost card cannot be returned to service.
     *
     * <p>A distinct code from {@link #CARD_INVALID_TRANSITION} because it is the one illegal
     * transition a cardholder will plausibly attempt by accident, usually by asking for an unfreeze
     * they were refused moments earlier. "That card was reported lost" is an answer they can act on;
     * the generic conflict is not.
     */
    public static final ErrorCode CARD_REPORTED_LOST = ErrorCode.of(
            "CARD_REPORTED_LOST", HttpStatus.CONFLICT, "This card was reported lost and cannot be reactivated");

    public static final ErrorCode CARD_EXPIRED =
            ErrorCode.of("CARD_EXPIRED", HttpStatus.CONFLICT, "This card is past its expiry date");

    /** The cardholder already holds as many usable cards as the platform will issue. */
    public static final ErrorCode CARD_LIMIT_REACHED = ErrorCode.of(
            "CARD_LIMIT_REACHED",
            HttpStatus.CONFLICT,
            "The maximum number of cards for this customer is already issued");

    /**
     * The named customer is not currently approved, so no card can be issued to them.
     *
     * <p>Surfaced rather than swallowed because a client that asked to issue a card and received a
     * successful response would go on to show a customer a card that should not exist.
     */
    public static final ErrorCode HOLDER_NOT_ELIGIBLE = ErrorCode.of(
            "HOLDER_NOT_ELIGIBLE",
            HttpStatus.CONFLICT,
            "Cards can only be issued to a customer whose identity check is approved");

    /**
     * The eligibility check could not be completed.
     *
     * <p>503 and not a fallback that issues anyway. Issuance is the one place in this service where
     * failing open would create a card for somebody the platform has not verified, and a card for an
     * unverified holder is precisely the compliance failure the check exists to prevent.
     */
    public static final ErrorCode ELIGIBILITY_UNAVAILABLE = ErrorCode.of(
            "ELIGIBILITY_UNAVAILABLE",
            HttpStatus.SERVICE_UNAVAILABLE,
            "Cardholder eligibility could not be confirmed; the card was not issued");
}
