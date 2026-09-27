package com.fintech.platform.transaction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fintech.platform.transaction.domain.FraudContext;
import com.fintech.platform.transaction.domain.LedgerAccountType;
import com.fintech.platform.transaction.domain.Money;
import com.fintech.platform.transaction.domain.Transaction;
import com.fintech.platform.transaction.domain.TransactionStatus;
import com.fintech.platform.transaction.web.CreateTransactionRequest;
import com.fintech.platform.transaction.web.FundAccountRequest;
import com.fintech.platform.transaction.web.TransactionResponse;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs a money-moving request exactly once, whatever the client does.
 *
 * <p><b>This is where the transaction lives, and the reason it is not in the controller.</b> The three
 * things that must happen together are: decide what the key means, move the money, and store the
 * response to replay. The controller has the key and the serialised body but no transaction; the
 * services have the transaction but neither. Attempting to assemble them in the controller means calling
 * {@code decide} and {@code complete} outside the transaction that performs the work, which either
 * fails outright under {@code MANDATORY} propagation or — worse, if the propagation were relaxed — lets
 * the key be claimed and the payment committed independently, which is precisely the double payment the
 * mechanism exists to prevent.
 *
 * <p>So the whole operation is one {@code @Transactional} method here. The claim, the posting, the state
 * change and the stored response commit together or not at all, and a key is never pinned by a payment
 * that did not happen.
 *
 * <p><b>Responses are serialised here and stored as bytes.</b> Replaying the exact bytes the first
 * attempt produced is the only way a retry is indistinguishable from the original. Recomputing the
 * response would show current state — a payment that has since been settled and reversed would replay as
 * reversed — and the caller would be told their retry did something the retry did not do.
 */
@Service
public class PaymentFacade {

    private static final Logger log = LoggerFactory.getLogger(PaymentFacade.class);

    private final TransactionService transactions;
    private final LedgerService ledger;
    private final IdempotencyService idempotency;
    private final ObjectMapper objectMapper;

    public PaymentFacade(
            TransactionService transactions,
            LedgerService ledger,
            IdempotencyService idempotency,
            ObjectMapper objectMapper) {
        this.transactions = transactions;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.objectMapper = objectMapper;
    }

    /** What the HTTP layer should do with the request. */
    public sealed interface Result {

        /**
         * Ran, or was a replay of a request that already ran.
         *
         * <p><b>Two shapes rather than one flag.</b> A fresh result has an object to serialise; a replay
         * has the JSON text stored on the first attempt, which has to reach the wire byte for byte. Carrying
         * both in one {@code Object} with a boolean to say which is which is what let a replayed String be
         * handed to the message converter as a body and JSON-encoded a second time, so a client retrying a
         * successful payment received a quoted string where the original had received an object. Two named
         * factories make that mismatch unrepresentable: a replay cannot be built without its stored text, and
         * a fresh result cannot be built with any.
         *
         * @param status the HTTP status, identical on both paths — a retry must not be told something
         *     different from what the first attempt was told
         * @param body the response to serialise, for a fresh result; null on a replay
         * @param storedJson the first attempt's response, verbatim, for a replay; null on a fresh result
         * @param replayed whether this answers a repeat rather than a first attempt
         */
        record Answer(int status, Object body, String storedJson, boolean replayed) implements Result {

            /**
             * A first attempt: an object for the response layer to serialise.
             *
             * <p>Public because the adapter builds these, and public on purpose — a caller outside this
             * package has no other way to make one, so the two shapes stay tied to their names.
             */
            public static Answer fresh(int status, Object body) {
                return new Answer(status, body, null, false);
            }

            /** A repeat: the first attempt's bytes, to be written through untouched. */
            public static Answer replayed(int status, String storedJson) {
                return new Answer(status, null, storedJson, true);
            }
        }

        /** The key was used with a different request. 409. */
        record Conflict() implements Result {}

        /** The key's first attempt is still running. 409 with Retry-After. */
        record InProgress() implements Result {}
    }

    /**
     * Creates and authorises a payment, once.
     *
     * @param fingerprint a digest of the request, so a key reused with different content is detected
     */
    @Transactional
    public Result createPayment(
            String ownerSubjectDigest,
            String idempotencyKey,
            String fingerprint,
            CreateTransactionRequest request,
            FraudContext context) {

        var decision = idempotency.decide(ownerSubjectDigest, idempotencyKey, fingerprint);
        if (decision instanceof IdempotencyService.Decision.Replay replay) {
            log.debug("replaying a stored response; this request has already been processed");
            return Result.Answer.replayed(replay.status(), replay.body());
        }
        if (decision instanceof IdempotencyService.Decision.Conflict) {
            return new Result.Conflict();
        }
        if (decision instanceof IdempotencyService.Decision.InProgress) {
            return new Result.InProgress();
        }
        var proceed = (IdempotencyService.Decision.Proceed) decision;

        Money amount = request.toMoney();
        Transaction transaction = transactions.authorize(
                ownerSubjectDigest, request.cardToken(), amount, request.toCounterparty(), context);
        TransactionResponse response = TransactionResponse.from(transaction);

        // A decline is stored and replayed as 422, exactly as it was first answered. A retry must not
        // see a different status than the original attempt, or a client retrying a declined card would
        // be told it succeeded the second time.
        int status = transaction.status() == TransactionStatus.DECLINED ? 422 : 201;
        idempotency.complete(proceed.record(), status, serialise(response));
        return Result.Answer.fresh(status, response);
    }

    /**
     * Adds funds, once.
     *
     * <p>Keyed on the amount and currency rather than on the whole body, because funding has no other
     * fields: two requests that differ only in an ignored field are the same request, and treating them
     * as different would refuse a legitimate retry.
     */
    @Transactional
    public Result fundAccount(String ownerSubjectDigest, String idempotencyKey, FundAccountRequest request) {

        String fingerprint = IdempotencyService.fingerprint(request.canonicalFingerprint("/api/v1/accounts/fund"));
        var decision = idempotency.decide(ownerSubjectDigest, idempotencyKey, fingerprint);
        if (decision instanceof IdempotencyService.Decision.Replay replay) {
            return Result.Answer.replayed(replay.status(), replay.body());
        }
        if (decision instanceof IdempotencyService.Decision.Conflict) {
            return new Result.Conflict();
        }
        if (decision instanceof IdempotencyService.Decision.InProgress) {
            return new Result.InProgress();
        }
        var proceed = (IdempotencyService.Decision.Proceed) decision;

        Currency currency = request.toCurrency();
        transactions.fund(ownerSubjectDigest, Money.parse(request.amount(), currency));

        Map<LedgerAccountType, Money> balances = ledger.balancesFor(ownerSubjectDigest, currency);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("currency", currency.getCurrencyCode());
        body.put("available", balances.get(LedgerAccountType.CUSTOMER_AVAILABLE).toDecimalString());
        body.put("held", balances.get(LedgerAccountType.CUSTOMER_RESERVED).toDecimalString());

        idempotency.complete(proceed.record(), 200, serialise(body));
        return Result.Answer.fresh(200, body);
    }

    /**
     * Serialises a response for storage.
     *
     * <p>Fails the request rather than storing nothing. A key completed with no body would replay an
     * empty response, and the client would have been told its payment succeeded and learned nothing
     * about it.
     */
    private String serialise(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("response could not be serialised for the idempotency record", e);
        }
    }
}
