package com.fintech.platform.transaction.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;

/**
 * Who the money is going to.
 *
 * <p>An embedded value rather than a table, because a counterparty is not something this service
 * needs to know anything else about, and giving it an id would imply it is. Two payments to the same
 * payee are two payments to the same payee for no reason a query needs to exploit.
 *
 * <p>Free-text and unverified on purpose. There is no payee directory in this platform and inventing a
 * validated one would mean either trusting a caller-supplied name — which is what a phishing attempt
 * looks like to the person being phished — or building a registry that belongs to a different phase.
 * The name is recorded as the caller stated it, and the payment shows it back to them.
 */
@Embeddable
public class Counterparty {

    /** Ceiling on the name, so a caller cannot put a document into a column meant for a label. */
    public static final int MAX_NAME_LENGTH = 140;

    /** Ceiling on the reference, same reason. */
    public static final int MAX_REFERENCE_LENGTH = 64;

    /**
     * A reference supplied by the caller, for matching this payment to their own records.
     *
     * <p>Nullable because it is optional, and short because the only thing it has to achieve is
     * round-tripping: it goes out on the request and back on the response, and a caller that needs to
     * attach a document has a field for it.
     */
    @Column(name = "payee_reference", length = MAX_REFERENCE_LENGTH)
    private String reference;

    @Column(name = "payee_name", nullable = false, length = MAX_NAME_LENGTH)
    private String name;

    protected Counterparty() {}

    public Counterparty(String name, String reference) {
        this.name = requireText(name, "payee name", MAX_NAME_LENGTH);
        this.reference = optionalText(reference, "payee reference", MAX_REFERENCE_LENGTH);
    }

    private static String requireText(String value, String field, int max) {
        Objects.requireNonNull(value, field + " must not be null");
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(
                    field + " must be at most " + max + " characters, got " + trimmed.length());
        }
        return trimmed;
    }

    private static String optionalText(String value, String field, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > max) {
            throw new IllegalArgumentException(
                    field + " must be at most " + max + " characters, got " + trimmed.length());
        }
        return trimmed;
    }

    public String name() {
        return name;
    }

    public String reference() {
        return reference;
    }

    @Override
    public String toString() {
        return reference == null ? name : name + " (" + reference + ")";
    }
}
