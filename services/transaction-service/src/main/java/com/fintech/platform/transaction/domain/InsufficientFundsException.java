package com.fintech.platform.transaction.domain;

import com.fintech.platform.common.error.ApiException;
import com.fintech.platform.common.error.ErrorCode;
import com.fintech.platform.transaction.error.TransactionErrorCodes;
import java.util.Map;

/**
 * Thrown when a posting would take a customer account below zero.
 *
 * <p>A domain exception rather than a bare {@code ApiException} because it carries the account and the
 * shortfall as data. {@link com.fintech.platform.transaction.service.TransactionService} catches it to
 * record a decline with a reason and to publish a decline event, and neither should have to parse a
 * message string to learn which account ran out.
 *
 * <p><b>An {@code ApiException} subtype, which is the part worth explaining.</b> The platform's handler
 * maps {@code ApiException} and treats everything else as an unexpected failure reported as
 * {@code INTERNAL_ERROR}, and no service declares an exception handler of its own. Extending it is
 * therefore how a domain outcome reaches the caller as a governed 422 without inventing a second
 * mapping path — the handler already exists and is already the place where responses are shaped.
 *
 * <p>The alternative, thrown as a plain {@code RuntimeException} with a local handler, would work too.
 * It would be worse: two places that decide what a refusal looks like on the wire, and a second one
 * that only exists in the module that happened to need it.
 *
 * <p>The shortfall goes in typed fields rather than in {@code details}. Details are serialised into the
 * response body and the log line; a balance is customer financial data, and the caller can read their
 * own balance from the account endpoint without this exception echoing it back.
 */
public class InsufficientFundsException extends ApiException {

    private static final long serialVersionUID = 1L;

    private final transient LedgerAccountType accountType;
    private final transient Money requested;
    private final transient Money available;

    public InsufficientFundsException(LedgerAccountType accountType, Money requested, Money available) {
        this(accountType, requested, available, TransactionErrorCodes.INSUFFICIENT_FUNDS);
    }

    public InsufficientFundsException(
            LedgerAccountType accountType, Money requested, Money available, ErrorCode errorCode) {
        super(errorCode, describe(accountType, requested, available), Map.of());
        this.accountType = accountType;
        this.requested = requested;
        this.available = available;
    }

    private static String describe(LedgerAccountType accountType, Money requested, Money available) {
        StringBuilder message = new StringBuilder("insufficient funds in ").append(accountType);
        if (requested != null) {
            message.append(": requested ").append(requested);
        }
        if (available != null) {
            message.append(", available ").append(available);
        }
        return message.toString();
    }

    /** The account that could not be debited, or null when the throw site did not say. */
    public LedgerAccountType accountType() {
        return accountType;
    }

    /** The amount the posting needed, or null when the throw site did not say. */
    public Money requested() {
        return requested;
    }

    /** The balance that was actually there, or null when the throw site did not say. */
    public Money available() {
        return available;
    }
}
