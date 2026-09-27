package com.fintech.platform.transaction.web;

import com.fintech.platform.common.correlation.CorrelationId;
import com.fintech.platform.common.error.ApiErrorResponse;
import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.transaction.domain.FraudContext;
import com.fintech.platform.transaction.domain.PaymentChannel;
import com.fintech.platform.transaction.error.TransactionErrorCodes;
import com.fintech.platform.transaction.identity.SubjectDigester;
import com.fintech.platform.transaction.service.PaymentFacade;
import com.fintech.platform.transaction.service.TransactionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payments API.
 *
 * <p><b>A thin adapter, on purpose.</b> It resolves the caller, hands the request to
 * {@link PaymentFacade}, and maps the result to HTTP. There is no idempotency logic here and no
 * transaction here, because both of those need to be in the same place as the money movement and a
 * controller has neither. See that class for why.
 *
 * <p><b>Amounts cross the wire as strings in both directions.</b> See {@link CreateTransactionRequest}
 * for the receiving side and {@link TransactionResponse} for the sending one; the reason is the same
 * both ways, and it is that a JSON number is a double by the time anybody has parsed it.
 *
 * <p><b>No card number appears in any request or response.</b> The only way to name a card is
 * {@code cardToken}, and a response never echoes it — see {@link TransactionResponse} for why echoing
 * it is worse than not having it.
 */
@RestController
@RequestMapping("/api/v1")
public class TransactionController {

    private final TransactionService transactions;
    private final PaymentFacade payments;
    private final CurrentCaller caller;
    private final SubjectDigester digester;

    public TransactionController(
            TransactionService transactions, PaymentFacade payments, CurrentCaller caller, SubjectDigester digester) {
        this.transactions = transactions;
        this.payments = payments;
        this.caller = caller;
        this.digester = digester;
    }

    /**
     * Creates and authorises a payment.
     *
     * <p>Requires an {@code Idempotency-Key}. Answers 422 when the payment is declined rather than 201
     * with a declined body, so a client that inspects only the status code cannot mistake a refusal for
     * a success.
     */
    @PostMapping("/transactions")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN', 'SUPPORT', 'OPERATIONS')")
    public ResponseEntity<?> create(
            @Valid @RequestBody CreateTransactionRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {

        String fingerprint = com.fintech.platform.transaction.service.IdempotencyService.fingerprint(
                body.canonicalFingerprint("POST", "/api/v1/transactions"));

        return render(
                payments.createPayment(ownerDigest(), idempotencyKey, fingerprint, body, fraudContext(body, request)),
                transactionId -> "/api/v1/transactions/" + transactionId,
                request);
    }

    /** One payment, if the caller owns it. */
    @GetMapping("/transactions/{id}")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN', 'SUPPORT', 'OPERATIONS')")
    public TransactionResponse get(@PathVariable UUID id) {
        return TransactionResponse.from(transactions.get(id, ownerDigest()));
    }

    /** The caller's payments, newest first. */
    @GetMapping("/transactions")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN', 'SUPPORT', 'OPERATIONS')")
    public Map<String, Object> list(@RequestParam(defaultValue = "20") int limit) {
        List<TransactionResponse> items = transactions.list(ownerDigest(), limit).stream()
                .map(TransactionResponse::from)
                .toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("count", items.size());
        return body;
    }

    /**
     * Captures an authorised payment.
     *
     * <p>Deliberately not idempotency-keyed. Capture cannot happen twice: the state machine refuses to
     * settle a payment that is not authorised, and a payment that is already settled is refused again.
     * Requiring a key here would make callers invent one to protect against an operation the ledger
     * already prevents, and the 409 it produces says the more useful thing — that it is already settled.
     */
    @PostMapping("/transactions/{id}/settle")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN', 'OPERATIONS')")
    public TransactionResponse settle(@PathVariable UUID id) {
        return TransactionResponse.from(transactions.settle(id, ownerDigest()));
    }

    /**
     * Reverses a payment: releases an uncollected hold, or refunds a capture.
     *
     * <p>Not keyed, for the same reason as settle. The state machine makes a second reversal a 409.
     */
    @PostMapping("/transactions/{id}/reverse")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN', 'OPERATIONS')")
    public TransactionResponse reverse(@PathVariable UUID id) {
        return TransactionResponse.from(transactions.reverse(id, ownerDigest()));
    }

    /** The caller's spendable and held balances in a currency. */
    @GetMapping("/accounts/balance")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN', 'SUPPORT', 'OPERATIONS')")
    public Map<String, Object> balance(@RequestParam(required = false, defaultValue = "GBP") String currency) {
        Currency code = Currency.getInstance(currency.toUpperCase(Locale.ROOT));
        var balances = transactions.balances(ownerDigest(), code);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currency", code.getCurrencyCode());
        body.put(
                "available",
                balances.get(com.fintech.platform.transaction.domain.LedgerAccountType.CUSTOMER_AVAILABLE)
                        .toDecimalString());
        body.put(
                "held",
                balances.get(com.fintech.platform.transaction.domain.LedgerAccountType.CUSTOMER_RESERVED)
                        .toDecimalString());
        return body;
    }

    /**
     * Adds funds to the caller's account.
     *
     * <p>Idempotency-keyed, because it moves money and a double top-up is as wrong as a double payment.
     */
    @PostMapping("/accounts/fund")
    @PreAuthorize("hasAnyRole('CUSTOMER', 'ADMIN', 'OPERATIONS')")
    public ResponseEntity<?> fund(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @ModelAttribute FundAccountRequest request,
            HttpServletRequest servletRequest) {
        return render(payments.fundAccount(ownerDigest(), idempotencyKey, request), id -> null, servletRequest);
    }

    // ---------------------------------------------------------------------------- helpers

    /**
     * The fraud context for this attempt, from the three places it can honestly come from.
     *
     * <p>Built here rather than in the service because two of the three values exist only in the request:
     * the remote address is a property of the connection and the device fingerprint is a property of the
     * body. The service would have to be handed the {@code HttpServletRequest} to see them, which is a
     * transport type in a layer that has no business knowing one.
     *
     * <p><b>Every identifier is reduced to a digest before it is returned.</b> {@code cardReference} and
     * {@code deviceReference} are HMACs under this service's own key, and the network is masked to a /24
     * and then hashed as well. What leaves this method is therefore four values that let the fraud engine
     * correlate and identify nothing, and that is asserted in {@code TransactionControllerTest} rather
     * than left to this comment.
     *
     * <p>Nothing here can fail the payment. A request with no device fingerprint and no forwarded address
     * produces a context with two nulls, and the fraud engine records the missing facts instead of
     * guessing them.
     */
    private FraudContext fraudContext(CreateTransactionRequest body, HttpServletRequest request) {
        String network = SourceNetwork.mask(request.getRemoteAddr());
        String fingerprint =
                body.deviceFingerprint() == null || body.deviceFingerprint().isBlank()
                        ? null
                        : body.deviceFingerprint().trim();
        return new FraudContext(
                digester.cardReference(body.cardToken().trim()),
                PaymentChannel.parse(body.channel()),
                fingerprint == null ? null : digester.deviceReference(fingerprint),
                network == null ? null : digester.networkReference(network));
    }

    /**
     * The caller's subject digest, as this service stores it.
     *
     * <p>Derived here rather than read from a header, because the digest is this service's own keyed
     * value and a header carrying one would let a caller present another service's digest. Deriving it
     * from the verified subject means the only input is something the gateway signed.
     */
    private String ownerDigest() {
        return digester.digestOf(caller.require().subject());
    }

    /** Maps a facade result onto HTTP, uniformly for every money-moving endpoint. */
    private ResponseEntity<?> render(
            PaymentFacade.Result result,
            java.util.function.Function<UUID, String> locationFor,
            HttpServletRequest request) {
        if (result instanceof PaymentFacade.Result.Conflict) {
            // Built from the same error codes and the same envelope as every other refusal in the
            // platform, rather than a hand-rolled map. A second error shape in one service means every
            // client needs two parsers, and it costs the fields a client needs most: a stable machine
            // code under "error" instead of "code", the correlation id to quote in a support ticket, and
            // the path the failure happened on. Both codes already existed in TransactionErrorCodes
            // precisely so this would be the only way to produce them.
            throw TransactionErrorCodes.IDEMPOTENCY_KEY_REUSED.exception();
        }
        if (result instanceof PaymentFacade.Result.InProgress) {
            // The same envelope, built rather than thrown, because this one carries a Retry-After that
            // the shared handler has no reason to know about.
            //
            // It says when to come back rather than leaving the client to guess, which is the difference
            // between a retry loop and a client that gives up and asks a human. Guessing is not neutral
            // either: a client that retries immediately is the retry storm the idempotency claim exists
            // to absorb, and one that gives up abandons a payment that may well have succeeded.
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .header(HttpHeaders.RETRY_AFTER, "1")
                    .body(ApiErrorResponse.of(
                            HttpStatus.CONFLICT.value(),
                            TransactionErrorCodes.IDEMPOTENT_REQUEST_IN_PROGRESS.code(),
                            TransactionErrorCodes.IDEMPOTENT_REQUEST_IN_PROGRESS.defaultMessage(),
                            request.getRequestURI(),
                            CorrelationId.current()));
        }
        var answer = (PaymentFacade.Result.Answer) result;
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(answer.status());
        if (answer.replayed()) {
            // Marked so a client can tell a replay from a fresh result. The status and body are
            // identical either way, which is the point — the difference is only ever visible to someone
            // debugging, never to a retry that just wants to know what happened.
            builder.header("Idempotency-Replayed", "true");
        }
        if (answer.body() instanceof TransactionResponse response && locationFor != null) {
            builder.header(HttpHeaders.LOCATION, locationFor.apply(UUID.fromString(response.id())));
        }
        if (answer.replayed()) {
            // The stored bytes, written out as they are.
            //
            // A replay's body is the JSON text saved on the first attempt, not an object. Handing that
            // String to the message converter as a plain body gets it JSON-encoded a second time, so the
            // retry receives "\"{\\\"id\\\":...}\"" — a quoted string where the original attempt returned an
            // object. Declaring the content type makes the string converter, which is registered ahead of
            // Jackson and writes String bodies through untouched, take it; the client then sees exactly
            // the bytes the first attempt saw.
            //
            // This is the whole reason the response is stored rather than recomputed, and encoding it
            // twice is the one way of losing that: a client parsing the retry's response as an object
            // would fail on a payment that had in fact succeeded.
            return builder.contentType(MediaType.APPLICATION_JSON).body(answer.storedJson());
        }
        return builder.body(answer.body());
    }
}
