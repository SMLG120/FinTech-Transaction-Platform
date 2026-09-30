package com.fintech.platform.customer.web;

import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.customer.pii.PiiMasker;
import com.fintech.platform.customer.service.CustomerProfileView;
import com.fintech.platform.customer.service.CustomerService;
import com.fintech.platform.customer.service.KycService;
import com.fintech.platform.customer.service.KycWorkflow;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP surface for customer-service.
 *
 * <p>Three jobs and nothing else: bind a request, ask {@link CurrentCaller} who is calling, hand both
 * to a service that authorises. There is no ownership check here, because an ownership check in a
 * controller is one endpoint away from being left out, and the one that leaves it out is the breach.
 * The services take the caller's identity as their first argument for that reason: an endpoint that
 * forgot to pass it would not compile.
 *
 * <p>No request shape carries a subject or a customer id. The subject always comes from the verified
 * token, so there is no field a caller could set to act as somebody else.
 */
@RestController
@RequestMapping("/api/v1/customers")
public class CustomerController {

    private static final String AUTH_SERVICE_SUBJECT = "auth-service";
    private static final String AUTH_SERVICE_USERNAME = "auth-service";

    private final CustomerService customerService;
    private final KycService kycService;
    private final CurrentCaller caller;

    public CustomerController(CustomerService customerService, KycService kycService, CurrentCaller caller) {
        this.customerService = customerService;
        this.kycService = kycService;
        this.caller = caller;
    }

    /** Creates the calling customer's profile. The subject is taken from the token, never the body. */
    @PostMapping
    public ResponseEntity<CustomerDtos.CustomerResponse> register(
            @Valid @RequestBody CustomerDtos.RegisterRequest request) {
        CustomerProfileView view = customerService.register(caller.require(), request.toIdentity(), request.phone());
        return ResponseEntity.created(URI.create("/api/v1/customers/" + view.id()))
                .body(toResponse(view));
    }

    /** Internal-only profile provisioning for Auth Service after Keycloak user creation. */
    @PostMapping("/internal/provision")
    public ResponseEntity<CustomerDtos.CustomerResponse> provision(
            @Valid @RequestBody CustomerDtos.ProvisionRequest request) {
        InternalIdentity internal = caller.require();
        if (!AUTH_SERVICE_SUBJECT.equals(internal.subject())
                || !AUTH_SERVICE_USERNAME.equals(internal.username())) {
            throw com.fintech.platform.customer.error.CustomerErrorCodes.NOT_THE_OWNER.exception();
        }
        CustomerProfileView view = customerService.provision(request.keycloakSubject(), request.toIdentity(), request.phone());
        return ResponseEntity.status(201).body(toResponse(view));
    }

    /**
     * The calling customer's own profile.
     *
     * <p>{@code /me} rather than a path variable, so the id cannot be swapped for somebody else's and
     * the only question left is authorisation, which the service answers.
     */
    @GetMapping("/me")
    public CustomerDtos.CustomerResponse getOwnProfile() {
        return toResponse(customerService.getOwnProfile(caller.require()));
    }

    /**
     * Erases the calling customer's profile.
     *
     * <p>Resolved by the caller's own subject inside the service rather than by an id in the path,
     * because after an erasure there is no subject left to compare against and a retry still has to be
     * able to answer "yes, it is gone" rather than "that is not yours".
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> eraseOwnProfile() {
        customerService.eraseOwnProfile(caller.require());
        return ResponseEntity.noContent().build();
    }

    /** Replaces the caller's own profile. A full replacement rather than a patch; see the service. */
    @PutMapping("/me")
    public CustomerDtos.CustomerResponse updateOwnProfile(
            @Valid @RequestBody CustomerDtos.UpdateProfileRequest request) {
        InternalIdentity identity = caller.require();
        UUID id = customerService.getOwnProfile(identity).id();
        return toResponse(customerService.updateProfile(identity, id, request.toIdentity(), request.phone()));
    }

    /**
     * A named profile, for support, compliance and audit callers.
     *
     * <p>Masked for anyone but the owner. The service decides that, because it is the only layer that
     * knows the owning subject; the controller just renders what it is told.
     */
    @GetMapping("/{customerId}")
    public CustomerDtos.CustomerResponse getProfile(@PathVariable UUID customerId) {
        return toResponse(customerService.getProfile(caller.require(), customerId));
    }

    /**
     * Submits an identity document for assessment.
     *
     * <p>The name, date of birth and address come from the stored profile, not the request. A check
     * that let the applicant restate those would be comparing a claim against itself.
     */
    @PostMapping("/{customerId}/kyc")
    public CustomerDtos.KycResponse submitIdentityCheck(
            @PathVariable UUID customerId, @Valid @RequestBody CustomerDtos.KycSubmissionRequest request) {
        return toResponse(
                kycService.submit(caller.require(), customerId, request.toDocument(), request.toNationality()));
    }

    /** The caller's own identity-check history. */
    @GetMapping("/me/kyc")
    public List<CustomerDtos.KycCheckResponse> ownKycHistory() {
        InternalIdentity identity = caller.require();
        UUID id = customerService.getOwnProfile(identity).id();
        return customerService.kycHistory(identity, id).stream()
                .map(CustomerController::toResponse)
                .toList();
    }

    /**
     * Renders a profile, masking the personal fields when the service says they are masked.
     *
     * <p>A masked date of birth becomes a bare year. It is not zeroed to the first of January, because
     * a {@code LocalDate} is a claim about a specific day and inventing one to mean "some day in that
     * year" is the kind of small lie that ends up in a client's database.
     */
    private static CustomerDtos.CustomerResponse toResponse(CustomerProfileView view) {
        return new CustomerDtos.CustomerResponse(
                view.id(),
                mask(view.masked(), view.fullName(), PiiMasker::name),
                view.masked() ? null : view.dateOfBirth(),
                view.masked() && view.dateOfBirth() != null ? PiiMasker.birthYear(view.dateOfBirth()) : null,
                mask(view.masked(), view.email(), PiiMasker::email),
                mask(view.masked(), view.phone(), PiiMasker::phone),
                toAddress(view),
                view.kycStatus(),
                view.createdAt(),
                view.updatedAt(),
                view.erased(),
                view.masked());
    }

    private static CustomerDtos.CustomerResponse.AddressResponse toAddress(CustomerProfileView view) {
        if (view.address() == null) {
            return null;
        }
        // The country is left intact when masked: an address with no country is not recognisable enough
        // to help a support agent, and the country alone does not identify anybody.
        return new CustomerDtos.CustomerResponse.AddressResponse(
                mask(view.masked(), view.address().line1(), PiiMasker::name),
                view.address().line2() == null
                        ? null
                        : mask(view.masked(), view.address().line2(), PiiMasker::name),
                mask(view.masked(), view.address().city(), PiiMasker::name),
                mask(view.masked(), view.address().postalCode(), PiiMasker::name),
                view.address().country());
    }

    private static CustomerDtos.KycResponse toResponse(KycWorkflow.KycDecision decision) {
        String message = switch (decision.status()) {
            case APPROVED -> "identity check passed";
            case REJECTED -> "identity check failed";
            case UNDER_REVIEW -> "identity check is being reviewed by a compliance officer";
            default -> "identity check submitted";
        };
        return new CustomerDtos.KycResponse(decision.checkId(), decision.status(), decision.failureReasons(), message);
    }

    private static CustomerDtos.KycCheckResponse toResponse(CustomerProfileView.KycCheckView view) {
        return new CustomerDtos.KycCheckResponse(
                view.id(),
                view.outcome(),
                view.providerReference(),
                view.failureReasons(),
                view.checks().stream()
                        .map(check -> new CustomerDtos.KycCheckResponse.CheckResultResponse(
                                check.checkName(), check.passed(), check.reason()))
                        .toList(),
                view.submittedAt(),
                view.decidedAt());
    }

    private static String mask(boolean masked, String value, java.util.function.UnaryOperator<String> masker) {
        return value == null ? null : masked ? masker.apply(value) : value;
    }
}
