package com.fintech.platform.customer.web;

import com.fintech.platform.customer.domain.CustomerIdentity;
import com.fintech.platform.customer.kyc.KycStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The wire shapes for customer-service.
 *
 * <p>Separate from both the entities and the service read model, deliberately. A JPA entity cannot be
 * returned directly: it is mutable, lazily loaded, and carries ciphertext fields that must never
 * serialise. The service's own read model is not a contract either, because it is a Java type with
 * erasure in its accessor signatures and no notion of which fields a caller may see.
 *
 * <p>Every request shape here excludes anything the client has no business sending. There is no
 * {@code subject} field, no {@code customerId}, no {@code kycStatus} and no {@code version}: a client
 * that could set its own identity or its own approval status would make every authorisation check in
 * the service decorative. Anything the caller does not get to choose is absent from the request.
 */
public final class CustomerDtos {

    private CustomerDtos() {}

    /**
     * Registration.
     *
     * <p>No subject and no id. The subject comes from the verified token and the id is assigned, so
     * there is nothing for a caller to tamper with here.
     */
    public record RegisterRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotNull @Past LocalDate dateOfBirth,

            @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "[A-Za-z]{2}", message = "must be an ISO country code")
            String nationality,

            @NotBlank @Email @Size(max = 320) String email,
            @Size(max = 32) String phone,
            @NotNull @Valid AddressRequest address) {

        /**
         * Converts to the domain value object, normalising as it goes.
         *
         * <p>{@link CustomerIdentity} normalises internally, so nothing is pre-processed here. The
         * document is the explicit placeholder: a document is submitted to the KYC endpoint, not at
         * registration, and carrying one here would suggest the two are the same act.
         *
         * <p>Nationality is an ISO country code, validated as one so an unrecognised value is a 400 from
         * Jackson's binding, instead of a string that silently becomes a rejected submission later.
         */
        public CustomerIdentity toIdentity() {
            return new CustomerIdentity(
                    new CustomerIdentity.ClaimedName(fullName),
                    new CustomerIdentity.DateOfBirth(
                            dateOfBirth, CustomerIdentity.Nationality.fromCountryCode(nationality)),
                    new CustomerIdentity.Email(email),
                    address.toDomain(),
                    CustomerIdentity.IdentityDocument.unsubmitted());
        }
    }

    /** Internal Auth Service provisioning request. No role or caller identity is client-selectable. */
    public record ProvisionRequest(
            @NotBlank @Size(max = 512) String keycloakSubject,
            @NotBlank @Size(max = 200) String fullName,
            @NotNull @Past LocalDate dateOfBirth,
            @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "[A-Za-z]{2}") String nationality,
            @NotBlank @Email @Size(max = 320) String email,
            @Size(max = 32) String phone,
            @NotNull @Valid AddressRequest address) {

        public CustomerIdentity toIdentity() {
            return new CustomerIdentity(
                    new CustomerIdentity.ClaimedName(fullName),
                    new CustomerIdentity.DateOfBirth(
                            dateOfBirth, CustomerIdentity.Nationality.fromCountryCode(nationality)),
                    new CustomerIdentity.Email(email),
                    address.toDomain(),
                    CustomerIdentity.IdentityDocument.unsubmitted());
        }
    }

    /** Full profile replacement. Same shape as registration; see {@code CustomerService#updateProfile}. */
    public record UpdateProfileRequest(
            @NotBlank @Size(max = 200) String fullName,
            @NotNull @Past LocalDate dateOfBirth,

            @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "[A-Za-z]{2}", message = "must be an ISO country code")
            String nationality,

            @NotBlank @Email @Size(max = 320) String email,
            @Size(max = 32) String phone,
            @NotNull @Valid AddressRequest address) {

        public CustomerIdentity toIdentity() {
            return new CustomerIdentity(
                    new CustomerIdentity.ClaimedName(fullName),
                    new CustomerIdentity.DateOfBirth(
                            dateOfBirth, CustomerIdentity.Nationality.fromCountryCode(nationality)),
                    new CustomerIdentity.Email(email),
                    address.toDomain(),
                    CustomerIdentity.IdentityDocument.unsubmitted());
        }
    }

    /**
     * @param line1 required
     * @param line2 optional
     * @param country ISO 3166-1 alpha-2, upper case
     */
    public record AddressRequest(
            @NotBlank @Size(max = 200) String line1,
            @Size(max = 200) String line2,
            @NotBlank @Size(max = 100) String city,
            @NotBlank @Size(max = 20) String postalCode,

            @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "[A-Za-z]{2}", message = "must be an ISO country code")
            String country) {

        public CustomerIdentity.PostalAddress toDomain() {
            return line2 == null || line2.isBlank()
                    ? new CustomerIdentity.PostalAddress(line1, city, postalCode, country)
                    : new CustomerIdentity.PostalAddress(line1, line2, city, postalCode, country);
        }
    }

    /**
     * An identity document submission.
     *
     * <p>Carries the document reference and printed name only. The applicant does not restate their
     * name, date of birth or address: those come from the stored profile, because a check that
     * compares a claim against a restatement of itself verifies nothing.
     */
    public record KycSubmissionRequest(
            @NotBlank @Size(max = 64) String documentReference,
            @NotBlank @Size(max = 200) String printedName,
            // Future, not past. A document's expiry date is the day it stops being valid, so
            // requiring it to be in the past rejected every unexpired passport and accepted every
            // expired one. IdentityDocument.isExpiredOn is where the real check belongs, and it
            // already had it right.
            @NotNull @Future LocalDate expiryDate,

            @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "[A-Za-z]{2}", message = "must be an ISO country code")
            String issuingCountry,

            @NotBlank @Size(min = 2, max = 2) @Pattern(regexp = "[A-Za-z]{2}", message = "must be an ISO country code")
            String nationality) {

        public CustomerIdentity.IdentityDocument toDocument() {
            return new CustomerIdentity.IdentityDocument(
                    documentReference, new CustomerIdentity.ClaimedName(printedName), expiryDate, issuingCountry);
        }

        /**
         * @return the claimed nationality, mapped from the country code the client sent
         */
        public CustomerIdentity.Nationality toNationality() {
            return CustomerIdentity.Nationality.fromCountryCode(nationality);
        }
    }

    /**
     * A profile, as returned over HTTP.
     *
     * <p>Exactly one of {@code dateOfBirth} and {@code birthYear} is populated, and {@link #masked}
     * says which. A masked date of birth is returned as a year alone rather than as a date with its
     * month and day zeroed, because a {@code LocalDate} holding a fabricated day is a value that
     * looks like data and is not: it would render as the first of January in a UI and be stored by a
     * careless client as if the customer were born on it.
     *
     * <p>The choice of view is not the caller's. There is no query parameter that turns masking off,
     * because a flag a client can set is a flag the next endpoint will also expose.
     *
     * @param masked true when the personal fields are masked
     * @param birthYear the year of birth alone, populated only when masked
     */
    public record CustomerResponse(
            UUID id,
            String fullName,
            LocalDate dateOfBirth,
            String birthYear,
            String email,
            String phone,
            AddressResponse address,
            KycStatus kycStatus,
            Instant createdAt,
            Instant updatedAt,
            boolean erased,
            boolean masked) {

        /** @param line2 may be null */
        public record AddressResponse(String line1, String line2, String city, String postalCode, String country) {}
    }

    /** The outcome of a KYC submission: the resulting status, and why if it was a refusal. */
    public record KycResponse(UUID checkId, KycStatus status, List<String> failureReasons, String message) {

        /**
         * @param message a short caller-safe summary, present so a client can show something without
         *     parsing the reason list
         */
    }

    /** One identity check in a customer's history, as returned to an authorised caller. */
    public record KycCheckResponse(
            UUID id,
            KycStatus outcome,
            String providerReference,
            List<String> failureReasons,
            List<CheckResultResponse> checks,
            Instant submittedAt,
            Instant decidedAt) {

        public record CheckResultResponse(String checkName, boolean passed, String reason) {}
    }
}
