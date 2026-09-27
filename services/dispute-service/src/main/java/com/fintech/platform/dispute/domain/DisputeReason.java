package com.fintech.platform.dispute.domain;

/**
 * Why the customer says the payment should not have happened.
 *
 * <p>A closed vocabulary, because chargeback reasons are the set the support workflow triages on.
 * An open text field here would push the triage onto whoever reads the description, and the
 * description already exists for the customer's own words.
 */
public enum DisputeReason {
    FRAUD,
    NOT_RECEIVED,
    DUPLICATE,
    DEFECTIVE,
    OTHER
}
