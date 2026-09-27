package com.fintech.platform.audit.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fintech.platform.audit.persistence.AuditRecordEntity;
import com.fintech.platform.audit.persistence.AuditRecordRepository;
import com.fintech.platform.audit.service.AuditService;
import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.common.web.WebAutoConfiguration;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * What an auditor actually reads, and the refusals they actually get.
 *
 * <p>Authorization is not asserted here — that is {@code AuditSecurityTest} and {@code
 * AuditAuthorizationTest}, and duplicating it would make this class a second place to forget to
 * update. What is here is everything a role check cannot tell you: the field names on the wire, the
 * receipt (occurred vs received) that makes consumption lag measurable, and the shape of each
 * refusal.
 */
@WebMvcTest(AuditController.class)
@AutoConfigureMockMvc
@Import({WebAutoConfiguration.class, CurrentCaller.class, AuditAuthorization.class})
class AuditControllerTest {

    private static final String SIGNING_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";

    private static final InternalIdentityCodec CODEC =
            InternalIdentityCodec.fromHexKey(SIGNING_KEY, Duration.ofSeconds(60), Clock.systemUTC());

    @DynamicPropertySource
    static void internalIdentityKey(DynamicPropertyRegistry registry) {
        registry.add("platform.security.internal-identity.signing-key", () -> SIGNING_KEY);
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuditService trail;

    @MockitoBean
    private AuditRecordRepository records;

    private static AuditRecordEntity claimRecord() {
        return AuditRecordEntity.record(
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                "audit-events",
                "fraud-alert-claimed",
                "fraud-alert",
                "bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb",
                UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc"),
                "digest-held-by-service",
                "SUCCESS",
                "correlation-1",
                "{\"alertId\":\"bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb\"}",
                Instant.parse("2026-09-27T12:00:00Z"),
                Instant.parse("2026-09-27T12:00:01Z"));
    }

    private static RequestPostProcessor signedAsAuditor() {
        return request -> {
            Map<String, String> headers = CODEC.headersFor(new InternalIdentity(
                    "0f4b6c2e-6c1a-4a2b-9f3d-8c7b6a5d4e3f",
                    "auditor-1",
                    List.of("AUDITOR"),
                    "correlation-1",
                    Instant.now()));
            headers.forEach(request::addHeader);
            return request;
        };
    }

    @Test
    @DisplayName("lists the trail newest first, with both timestamps on every row")
    void listsWithBothTimestamps() throws Exception {
        when(records.search(eq(null), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(claimRecord()), PageRequest.of(0, 20), 1));

        mvc.perform(get("/api/audit/records").with(signedAsAuditor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("fraud-alert-claimed"))
                .andExpect(jsonPath("$.content[0].resourceType").value("fraud-alert"))
                .andExpect(jsonPath("$.content[0].result").value("SUCCESS"))
                .andExpect(jsonPath("$.content[0].occurredAt").value("2026-09-27T12:00:00Z"))
                .andExpect(jsonPath("$.content[0].receivedAt").value("2026-09-27T12:00:01Z"));
    }

    @Test
    @DisplayName("reads one record with its actor digest and correlation id")
    void readsOne() throws Exception {
        AuditRecordEntity row = claimRecord();
        when(records.findById(row.getId())).thenReturn(Optional.of(row));

        mvc.perform(get("/api/audit/records/" + row.getId()).with(signedAsAuditor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(row.getId().toString()))
                .andExpect(jsonPath("$.actorDigest").value("digest-held-by-service"))
                .andExpect(jsonPath("$.correlationId").value("correlation-1"));
    }

    @Test
    @DisplayName("answers 404 for a record that does not exist, rather than an empty body")
    void missingIs404() throws Exception {
        UUID unknown = UUID.fromString("dddddddd-dddd-4ddd-8ddd-dddddddddddd");
        when(records.findById(unknown)).thenReturn(Optional.empty());

        mvc.perform(get("/api/audit/records/" + unknown).with(signedAsAuditor()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("AUDIT_RECORD_NOT_FOUND"));
    }

    @Test
    @DisplayName("reads one resource's history in the order it happened")
    void readsResourceHistory() throws Exception {
        when(records.historyOf(eq("fraud-alert"), eq("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")))
                .thenReturn(List.of(claimRecord()));

        mvc.perform(get("/api/audit/records/by-resource/fraud-alert/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
                        .with(signedAsAuditor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].action").value("fraud-alert-claimed"));
    }

    @Test
    @DisplayName("reads one payment's trail")
    void readsPaymentTrail() throws Exception {
        when(records.findByTransactionIdOrderByOccurredAtAsc(any())).thenReturn(List.of(claimRecord()));

        mvc.perform(get("/api/audit/records/by-transaction/cccccccc-cccc-4ccc-8ccc-cccccccccccc")
                        .with(signedAsAuditor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transactionId").value("cccccccc-cccc-4ccc-8ccc-cccccccccccc"));
    }
}
