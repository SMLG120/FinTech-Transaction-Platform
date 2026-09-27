package com.fintech.platform.fraud.service;

import com.fintech.platform.fraud.config.FraudProperties;
import com.fintech.platform.fraud.domain.FraudDecision;
import com.fintech.platform.fraud.domain.RiskBand;
import com.fintech.platform.fraud.persistence.FraudAlertEntity;
import com.fintech.platform.fraud.persistence.FraudAlertRepository;
import com.fintech.platform.fraud.persistence.RiskDecisionEntity;
import com.fintech.platform.fraud.persistence.RiskDecisionRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads decisions and aggregates for the fraud team's dashboard and for the analyst screens.
 *
 * <p><b>Every figure here is over a stated window, and the window is part of the answer.</b> "12 declines"
 * is not a finding; "12 declines in the last 24 hours, out of 400 payments" is. Each aggregate therefore
 * takes the window as a parameter and the API returns it alongside the numbers, so a screenshot in a
 * review cannot be read as a statement about a different period.
 *
 * <p><b>Nothing is computed by fetching rows and counting them in Java.</b> The band, decision, merchant
 * and customer distributions are aggregate queries, because a dashboard that loads a hundred thousand
 * decisions to count them is a dashboard that falls over on the morning it is most needed — and the
 * "risky customers" panel is precisely the query that would do it.
 *
 * <p><b>The top-N panels are top-N.</b> Each is bounded by the same page size, so a customer with a
 * hundred thousand payments cannot make the response unbounded. A "top 10" that returns everything when
 * the data is skewed is a panel nobody can render.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    /** How many rows the top-N panels return. */
    private static final int TOP_N = 10;

    private final RiskDecisionRepository decisions;

    private final FraudAlertRepository alerts;

    private final FraudProperties properties;

    private final Clock clock;

    public AnalyticsService(
            RiskDecisionRepository decisions, FraudAlertRepository alerts, FraudProperties properties, Clock clock) {
        this.decisions = decisions;
        this.alerts = alerts;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * The dashboard, over a window.
     *
     * @param window how far back to look; the default is a day, which is a working shift
     */
    public DashboardSummary summary(Duration window) {
        Instant now = clock.instant();
        Instant since = now.minus(window);

        Map<RiskBand, Long> byBand = new EnumMap<>(RiskBand.class);
        for (RiskBand band : RiskBand.values()) {
            byBand.put(band, 0L);
        }
        decisions.countByBandSince(since).forEach(row -> byBand.put((RiskBand) row[0], ((Number) row[1]).longValue()));

        Map<FraudDecision, Long> byDecision = new EnumMap<>(FraudDecision.class);
        for (FraudDecision decision : FraudDecision.values()) {
            byDecision.put(decision, 0L);
        }
        decisions
                .countByDecisionSince(since)
                .forEach(row -> byDecision.put((FraudDecision) row[0], ((Number) row[1]).longValue()));

        long total = decisions.countSince(since);
        long declined = byDecision.getOrDefault(FraudDecision.DECLINE, 0L);
        long alerted = alerts.findBreaching(
                        FraudAlertRepository.OPEN_STATES,
                        now.minusSeconds(properties.getAlerts().getBreachHours() * 3600L),
                        org.springframework.data.domain.PageRequest.of(0, TOP_N))
                .getTotalElements();

        return new DashboardSummary(
                window,
                total,
                byBand,
                byDecision,
                declinedRate(total, declined),
                alerts.countByState(FraudAlertEntity.AlertState.OPEN),
                alerts.countByState(FraudAlertEntity.AlertState.CLAIMED),
                alerts.countByState(FraudAlertEntity.AlertState.RESOLVED),
                alerts.countByState(FraudAlertEntity.AlertState.DISMISSED),
                alerted,
                topMerchants(since),
                riskiestCustomers(since),
                recentDeclines(since));
    }

    /**
     * The decline rate, as a fraction of decisions in the window.
     *
     * <p>Zero rather than null when nothing was scored. A dashboard panel that renders "NaN%" because a
     * quiet night produced no payments is a panel people stop trusting.
     */
    private double declinedRate(long total, long declined) {
        return total == 0 ? 0.0 : (double) declined / total;
    }

    /** Merchants with the most decisions in the window, and the average score they attract. */
    public List<MerchantRisk> topMerchants(Instant since) {
        List<MerchantRisk> rows = new ArrayList<>();
        for (Object[] row :
                decisions.mostActiveMerchants(since, org.springframework.data.domain.PageRequest.of(0, TOP_N))) {
            long count = ((Number) row[1]).longValue();
            double average = row[2] == null ? 0.0 : ((Number) row[2]).doubleValue();
            rows.add(new MerchantRisk((String) row[0], count, average));
        }
        return rows;
    }

    /**
     * Customers with the highest average score.
     *
     * <p>Averages, not maxima: one CRITICAL payment makes a customer the "riskiest customer" on the board,
     * which is a claim the data does not support and which an analyst would have to disprove before
     * believing anything else on the panel.
     */
    public List<CustomerRisk> riskiestCustomers(Instant since) {
        List<CustomerRisk> rows = new ArrayList<>();
        for (Object[] row :
                decisions.riskiestCustomers(since, org.springframework.data.domain.PageRequest.of(0, TOP_N))) {
            long count = ((Number) row[1]).longValue();
            double average = row[2] == null ? 0.0 : ((Number) row[2]).doubleValue();
            rows.add(new CustomerRisk((String) row[0], count, average));
        }
        return rows;
    }

    /** The most recent declines, which is what an analyst looks at first. */
    public List<RiskDecisionEntity> recentDeclines(Instant since) {
        return decisions.recentWithDecision(
                FraudDecision.DECLINE, since, org.springframework.data.domain.PageRequest.of(0, TOP_N));
    }

    /** A merchant and how it looks. The reference, not a name, because a name is not an identity. */
    public record MerchantRisk(String merchantReference, long decisions, double averageScore) {}

    /**
     * A customer, as a digest. The dashboard shows the digest, not a name, and the correlation back to a
     * person happens through the identity provider — a support agent with the right role, not anyone with
     * dashboard access.
     */
    public record CustomerRisk(String ownerSubjectDigest, long decisions, double averageScore) {}

    /**
     * The dashboard's payload.
     *
     * @param window the period every figure covers, echoed so the numbers cannot be read out of context
     * @param declinedRate fraction of decisions that were declines, 0.0 when nothing was scored
     * @param breachingAlerts open or claimed alerts past the configured SLA
     */
    public record DashboardSummary(
            Duration window,
            long totalDecisions,
            Map<RiskBand, Long> byBand,
            Map<FraudDecision, Long> byDecision,
            double declinedRate,
            long openAlerts,
            long claimedAlerts,
            long resolvedAlerts,
            long dismissedAlerts,
            long breachingAlerts,
            List<MerchantRisk> topMerchants,
            List<CustomerRisk> riskiestCustomers,
            List<RiskDecisionEntity> recentDeclines) {}
}
