package com.fintech.platform.fraud.persistence;

import java.io.Serial;
import java.io.Serializable;
import java.util.Objects;

/**
 * The composite key of {@link FraudObservationEntity}: what was observed, of whom, by whom.
 *
 * <p>A plain class rather than a record because JPA's {@code @IdClass} instantiates it with a no-argument
 * constructor and calls {@code equals} and {@code hashCode} on values it builds itself. A record has no
 * no-argument constructor, so a record here fails at context startup with an error that does not mention
 * records.
 *
 * <p>The field names must match the entity's field names exactly. JPA builds this key by reflection over
 * those names, so a mismatch is a startup failure rather than a lookup that quietly returns nothing —
 * which is the better of the two failure modes.
 */
public class FraudObservationId implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    private String scope;

    private String kind;

    private String subjectDigest;

    private String observationKey;

    /** For JPA. */
    public FraudObservationId() {}

    public FraudObservationId(String scope, String kind, String subjectDigest, String observationKey) {
        this.scope = scope;
        this.kind = kind;
        this.subjectDigest = subjectDigest;
        this.observationKey = observationKey;
    }

    public String getScope() {
        return scope;
    }

    public String getKind() {
        return kind;
    }

    public String getSubjectDigest() {
        return subjectDigest;
    }

    public String getObservationKey() {
        return observationKey;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof FraudObservationId that)) {
            return false;
        }
        return Objects.equals(scope, that.scope)
                && Objects.equals(kind, that.kind)
                && Objects.equals(subjectDigest, that.subjectDigest)
                && Objects.equals(observationKey, that.observationKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(scope, kind, subjectDigest, observationKey);
    }

    @Override
    public String toString() {
        return scope + "/" + kind + "/" + observationKey;
    }
}
