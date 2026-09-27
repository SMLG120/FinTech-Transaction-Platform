package com.fintech.platform.settlement.web;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The settlement API's request bodies, in one place.
 *
 * <p>Validation lives here rather than in the controller so the rules sit next to each other and are
 * testable without a web context. Every field is bounded, because an unbounded amount or a free-text
 * explanation of any length ends up in a statement or a {@code detail} column that somebody has to read at
 * 3am.
 *
 * <p><b>No request body carries a timestamp or an actor.</b> Both come from the server: the instant from
 * its own clock, and the actor from the gateway-verified identity. A body field for either would be a
 * client that can acknowledge a break as somebody else or backdate it, and an audit trail that accepts
 * either is not an audit trail.
 */
public final class SettlementRequests {

    private SettlementRequests() {}

    /**
     * Closes a cycle, so its lines and total become final.
     *
     * <p>The reference is in the body rather than only in the path so a close is one complete, replayable
     * document — which matters more here than elsewhere, because closing is irreversible and a request log
     * is the only record of who asked for it.
     */
    public record CloseCycleRequest(
            @NotBlank @Size(max = 64) String reference) {}

    /**
     * Declares the independently sourced actual for a period.
     *
     * <p>The field is named {@code actualAmount} rather than {@code amount} on purpose. It is not the
     * cycle's amount, it is somebody else's number, and a body that says only "amount" invites the reader
     * — and eventually the code — to treat it as the expected total, which is the exact confusion ADR-0009
     * exists to prevent.
     *
     * <p>Non-negative, which is not a restriction on the arithmetic: a shortfall is a smaller positive
     * figure than expected, or a larger one, and never a negative clearing balance. Allowing a negative
     * here would let a typo produce a difference that looks like a real reconciliation finding.
     */
    public record DeclareActualRequest(
            @NotBlank @Size(max = 64) String reference,

            @NotBlank
            @Pattern(regexp = "\\d+(\\.\\d+)?", message = "must be plain digits with at most one decimal point")
            @DecimalMin(value = "0.00", message = "a declared actual cannot be negative")
            @Digits(integer = 12, fraction = 2)
            String actualAmount,

            @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "must be a three-letter ISO 4217 code")
            String currency) {}

    /**
     * Resolves a break with an explanation of what the money is doing.
     *
     * <p>A minimum length as well as a maximum. "Fixed" is not an explanation of a reconciliation
     * difference, and a resolution field that accepts it produces an audit trail that records that
     * something was done without recording what.
     */
    public record ResolveBreakRequest(
            @NotBlank @Size(min = 12, max = 500) String resolution) {}
}
