package com.fintech.platform.card.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * The stand-in for a card number that the platform stores instead of a PAN.
 *
 * <p>A token is deterministic, which is the whole reason tokenisation works: the network returns the
 * same token for the same underlying number, so an authorisation can be matched to a stored card
 * without anybody holding the number. Determinism is a property of the derivation, not of this type;
 * what this type guarantees is the shape and, more usefully, that nothing can construct a token that
 * was not produced by the tokeniser.
 *
 * <p>Not reversible by design, and deliberately not carrying a {@code detokenise} operation. An
 * issuer that can map a token back to a number can leak every number it ever issued, so the absence is
 * the control rather than an oversight. {@code Card} stores a token, not a number, and the only place
 * the two meet is the moment of issue.
 */
public record CardToken(String value) {

    private static final Pattern HEX_64 = Pattern.compile("^[0-9a-f]{64}$");

    public CardToken {
        Objects.requireNonNull(value, "token must not be null");
        if (!HEX_64.matcher(value).matches()) {
            throw new IllegalArgumentException("token must be 64 lowercase hex characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
