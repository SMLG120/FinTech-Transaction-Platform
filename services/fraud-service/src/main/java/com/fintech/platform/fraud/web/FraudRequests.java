package com.fintech.platform.fraud.web;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The request bodies this API accepts.
 *
 * <p>Two of them, and both are staff actions. There is no public fraud API: nothing a customer can call
 * submits evidence to the engine or moves a decision. That is a design property worth stating, because the
 * obvious abuse of a fraud endpoint is a caller influencing its own score, and the way to be sure that is
 * impossible is for there to be no path from a customer's token to a score.
 */
public final class FraudRequests {

    private FraudRequests() {}

    /**
     * An analyst's override of a score.
     *
     * @param score 0 to 100, and above {@code app.fraud.alerts.max-manual-score} is refused — a cap the
     *     service enforces, not a validation here, because the cap is configuration and this annotation
     *     would have to hardcode it
     * @param reason required and length-limited. It is the audit trail, and an adjustment with an empty
     *     reason leaves a row that says the engine was overruled and no record of why
     */
    public record ManualAdjustmentRequest(
            @Min(0) @Max(100) int score,
            @NotBlank @Size(max = 500) String reason) {}

    /**
     * Closing an alert.
     *
     * @param resolution a short label for what was found, so a false-positive rate can be computed by
     *     category rather than only in total
     */
    public record CloseAlertRequest(
            @NotBlank @Size(max = 64) String resolution,
            @Size(max = 2000) String note) {}

    /**
     * Asking for a payment to be scored again.
     *
     * @param reason shown on the alert timeline, so the next analyst to open it knows a re-score was asked
     *     for and why
     */
    public record RescoreRequest(@NotBlank @Size(max = 500) String reason) {}
}
