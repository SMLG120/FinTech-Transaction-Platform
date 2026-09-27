package com.fintech.platform.customer.domain;

import com.fintech.platform.customer.pii.PiiNormalizer;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Objects;

/**
 * A claimed identity, in plaintext, for exactly as long as the decision needs it.
 *
 * <p>This is the one place plaintext PII is allowed to exist in customer-service, and it exists only
 * because a provider has to be given something readable. It is deliberately not a JPA entity and
 * carries no identifier: it cannot be persisted by accident, and its lifetime is a method call.
 *
 * <p>{@link ClaimedName} and {@link Email} normalise on construction so that two spellings of the same
 * person reach the provider as one value. {@link DateOfBirth} and {@link PostalAddress} keep their
 * structure because the checks over them are structural.
 */
public record CustomerIdentity(
        ClaimedName name, DateOfBirth dateOfBirth, Email email, PostalAddress address, IdentityDocument document) {

    public CustomerIdentity {
        Objects.requireNonNull(name, "name must not be null");
        Objects.requireNonNull(dateOfBirth, "dateOfBirth must not be null");
        Objects.requireNonNull(email, "email must not be null");
        Objects.requireNonNull(address, "address must not be null");
        Objects.requireNonNull(document, "document must not be null");
    }

    /** A person's name as claimed, compared case- and accent-insensitively. */
    public record ClaimedName(String value) {

        public ClaimedName {
            value = PiiNormalizer.name(value);
            if (value.isEmpty()) {
                throw new IllegalArgumentException("name must not be blank");
            }
        }

        /** @return true when two spellings reduce to the same name */
        public boolean matches(ClaimedName other) {
            return value.equals(other.value);
        }
    }

    /**
     * @param value the instant of birth
     * @param supportedOn documents of this type are accepted for this nationality
     */
    public record DateOfBirth(LocalDate value, Nationality nationality) {

        public DateOfBirth {
            Objects.requireNonNull(value, "date of birth must not be null");
            Objects.requireNonNull(nationality, "nationality must not be null");
            if (value.isAfter(LocalDate.now())) {
                // Catches the most common data-entry error there is, and it has to be caught before a
                // provider is told a customer was born in the future.
                throw new IllegalArgumentException("date of birth cannot be in the future");
            }
        }

        public int ageYearsOn(LocalDate on) {
            return java.time.Period.between(value, on).getYears();
        }
    }

    /** An email address, compared on its canonical form. */
    public record Email(String value) {

        public Email {
            value = PiiNormalizer.email(value);
            if (value.isEmpty()) {
                throw new IllegalArgumentException("email must not be blank");
            }
        }
    }

    /**
     * @param line1 street and number
     * @param line2 optional
     * @param city
     * @param postalCode
     * @param country ISO 3166-1 alpha-2, upper case
     */
    public record PostalAddress(String line1, String line2, String city, String postalCode, String country) {

        public PostalAddress {
            line1 = requireText(line1, "address line 1");
            city = requireText(city, "city");
            postalCode = requireText(postalCode, "postal code");
            country = normaliseCountry(country);
        }

        public PostalAddress(String line1, String city, String postalCode, String country) {
            this(line1, null, city, postalCode, country);
        }

        private static String normaliseCountry(String country) {
            String normalised = requireText(country, "country").toUpperCase(Locale.ROOT);
            if (!normalised.matches("[A-Z]{2}")) {
                throw new IllegalArgumentException("country must be an ISO 3166-1 alpha-2 code, got: " + country);
            }
            return normalised;
        }
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    /**
     * The identity document submitted for checking, as distinct from what the applicant claims.
     *
     * <p>Both halves are needed and they are not the same thing. A check that compared the claim to
     * itself would pass by construction; the whole point of {@code NAME_MATCHES_DOCUMENT} is that an
     * applicant can write one name and present another, so the printed name is carried separately and
     * compared independently.
     *
     * @param reference the document's own number, as printed
     * @param printedName the name as it appears on the document
     * @param expiryDate the last day the document is valid
     * @param issuingCountry ISO 3166-1 alpha-2
     */
    public record IdentityDocument(
            String reference, ClaimedName printedName, LocalDate expiryDate, String issuingCountry) {

        /**
         * The reference the {@link #unsubmitted()} placeholder carries. A value no real document can
         * have, so a placeholder is recognisable however it reaches a provider.
         */
        private static final String UNSUBMITTED_REFERENCE = "UNSUBMITTED";

        public IdentityDocument {
            reference = requireText(reference, "document reference");
            Objects.requireNonNull(printedName, "printedName must not be null");
            Objects.requireNonNull(expiryDate, "expiryDate must not be null");
            issuingCountry = requireText(issuingCountry, "issuingCountry").toUpperCase(Locale.ROOT);
            if (!issuingCountry.matches("[A-Z]{2}")) {
                throw new IllegalArgumentException(
                        "issuingCountry must be an ISO 3166-1 alpha-2 code, got: " + issuingCountry);
            }
        }

        /**
         * A placeholder for the profile-only path, where a document is genuinely not part of the claim.
         *
         * <p>{@code CustomerIdentity} carries a document because a provider needs one to assess, but
         * registration and a profile update are not assessments. Rather than make the document
         * nullable and push a null check into every provider, those paths name the absence explicitly.
         *
         * <p>It must never reach a provider. A real one asked to assess this would check the
         * placeholder, and {@link SyntheticKycProvider} rejects it outright rather than returning a
         * plausible-looking decision against a document that does not exist.
         */
        public static IdentityDocument unsubmitted() {
            return new IdentityDocument(
                    UNSUBMITTED_REFERENCE, new ClaimedName("UNSUBMITTED"), LocalDate.of(1970, 1, 1), "ZZ");
        }

        /** Whether this is the {@link #unsubmitted()} placeholder rather than a real document. */
        public boolean isUnsubmitted() {
            return UNSUBMITTED_REFERENCE.equals(reference);
        }

        public boolean isExpiredOn(LocalDate on) {
            return expiryDate.isBefore(on);
        }
    }

    /**
     * The handful of nationalities the synthetic provider knows how to assess.
     *
     * <p>Internal vocabulary on purpose. The wire format is a plain ISO 3166-1 alpha-2 country code,
     * and this is where a code becomes something the provider understands. Exposing these names
     * instead would bake the synthetic provider's supported set into the public API, and every real
     * provider would then need a new enum plus a migration of every stored profile to match.
     */
    public enum Nationality {
        BRITISH,
        GERMAN,
        FRENCH,
        /** Deliberately present so the rejection path is reachable in a demo without editing code. */
        UNSUPPORTED_NATION;

        /**
         * @param countryCode ISO 3166-1 alpha-2, any case
         * @return the matching nationality, or {@link #UNSUPPORTED_NATION} for anything unrecognised
         */
        public static Nationality fromCountryCode(String countryCode) {
            if (countryCode == null) {
                return UNSUPPORTED_NATION;
            }
            return switch (countryCode.trim().toUpperCase(java.util.Locale.ROOT)) {
                case "GB" -> BRITISH;
                case "DE" -> GERMAN;
                case "FR" -> FRENCH;
                // Not an error. An unknown code is a real situation for every provider that is not
                // worldwide, and mapping it here means the unsupported-nationality path is exercised
                // by ordinary traffic rather than needing a code change to test.
                default -> UNSUPPORTED_NATION;
            };
        }
    }
}
