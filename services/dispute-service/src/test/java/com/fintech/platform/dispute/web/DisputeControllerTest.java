package com.fintech.platform.dispute.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.common.web.WebAutoConfiguration;
import com.fintech.platform.dispute.domain.DisputeReason;
import com.fintech.platform.dispute.error.DisputeErrors;
import com.fintech.platform.dispute.persistence.DisputeEntity;
import com.fintech.platform.dispute.persistence.DisputeEvidenceEntity;
import com.fintech.platform.dispute.persistence.DisputeEvidenceRepository;
import com.fintech.platform.dispute.persistence.DisputeRepository;
import com.fintech.platform.dispute.service.DisputeService;
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
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

/**
 * What the parties to a case actually see, and the refusals they actually get.
 *
 * <p>Authorization is not asserted here — that is {@code DisputeSecurityTest} and {@code
 * DisputeAuthorizationTest}, and duplicating it would make this class a second place to forget to
 * update. What is here is everything a role check cannot tell you: the 201 with a Location on open,
 * the file with its mine-markers, and the shape of each refusal.
 */
@WebMvcTest(DisputeController.class)
@AutoConfigureMockMvc
@Import({WebAutoConfiguration.class, CurrentCaller.class, DisputeAuthorization.class})
class DisputeControllerTest {

    private static final String SIGNING_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";

    private static final String OPENER_SUBJECT = "0f4b6c2e-6c1a-4a2b-9f3d-8c7b6a5d4e3f";

    private static final UUID TRANSACTION_ID = UUID.fromString("22222222-2222-4222-8222-222222222222");

    private static final InternalIdentityCodec CODEC =
            InternalIdentityCodec.fromHexKey(SIGNING_KEY, Duration.ofSeconds(60), Clock.systemUTC());

    @DynamicPropertySource
    static void internalIdentityKey(DynamicPropertyRegistry registry) {
        registry.add("platform.security.internal-identity.signing-key", () -> SIGNING_KEY);
    }

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private DisputeService cases;

    @MockitoBean
    private DisputeRepository disputes;

    @MockitoBean
    private DisputeEvidenceRepository file;

    private static DisputeEntity openCase() {
        return DisputeEntity.open(
                TRANSACTION_ID,
                DisputeReason.FRAUD,
                "I did not pay this",
                OPENER_SUBJECT,
                Instant.parse("2026-09-27T12:00:00Z"));
    }

    private static RequestPostProcessor signedAs(String subject, String... roles) {
        return request -> {
            Map<String, String> headers = CODEC.headersFor(
                    new InternalIdentity(subject, "user", List.of(roles), "correlation-1", Instant.now()));
            headers.forEach(request::addHeader);
            return request;
        };
    }

    @Test
    @DisplayName("opens a case with a 201, a Location, and an open status")
    void opensACase() throws Exception {
        DisputeEntity dispute = openCase();
        when(cases.open(eq(TRANSACTION_ID), eq(DisputeReason.FRAUD), any(), any()))
                .thenReturn(dispute);
        when(cases.fileOf(dispute.getId())).thenReturn(List.of());

        mvc.perform(post("/api/v1/disputes")
                        .with(signedAs(OPENER_SUBJECT, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":\"" + TRANSACTION_ID + "\",\"reason\":\"FRAUD\","
                                + "\"description\":\"I did not pay this\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/disputes/" + dispute.getId()))
                .andExpect(jsonPath("$.dispute.status").value("OPEN"))
                .andExpect(jsonPath("$.dispute.reason").value("FRAUD"));
    }

    @Test
    @DisplayName("refuses a second case on the same payment as a conflict, not a second case")
    void duplicateOpenIs409() throws Exception {
        when(cases.open(eq(TRANSACTION_ID), eq(DisputeReason.FRAUD), any(), any()))
                .thenThrow(DisputeErrors.ALREADY_OPEN.exception("already open"));

        mvc.perform(post("/api/v1/disputes")
                        .with(signedAs(OPENER_SUBJECT, "CUSTOMER"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transactionId\":\"" + TRANSACTION_ID + "\",\"reason\":\"FRAUD\","
                                + "\"description\":\"I did not pay this\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DISPUTE_ALREADY_OPEN"));
    }

    @Test
    @DisplayName("reads a case with its file, marking the reader's own words")
    void readsACaseWithItsFile() throws Exception {
        DisputeEntity dispute = openCase();
        DisputeEvidenceEntity mine = DisputeEvidenceEntity.submit(
                dispute.getId(), OPENER_SUBJECT, "it arrived broken", Instant.parse("2026-09-27T12:01:00Z"));
        DisputeEvidenceEntity theirs = DisputeEvidenceEntity.submit(
                dispute.getId(), "agent-subject", "checking with the merchant", Instant.parse("2026-09-27T12:02:00Z"));
        when(disputes.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(cases.fileOf(dispute.getId())).thenReturn(List.of(mine, theirs));

        mvc.perform(get("/api/v1/disputes/" + dispute.getId()).with(signedAs(OPENER_SUBJECT, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispute.id").value(dispute.getId().toString()))
                .andExpect(jsonPath("$.evidence.length()").value(2))
                .andExpect(jsonPath("$.evidence[0].submittedByMe").value(true))
                .andExpect(jsonPath("$.evidence[1].submittedByMe").value(false));
    }

    @Test
    @DisplayName("decides a case as a refund, returning the outcome")
    void resolvesARefund() throws Exception {
        DisputeEntity dispute = openCase();
        dispute.resolve(true, "agent-subject", "tracking shows no delivery", Instant.parse("2026-09-27T13:00:00Z"));
        when(disputes.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        // The controller re-reads the row for rendering after deciding; the mock returns the
        // decided case the service would have returned.
        when(cases.resolve(eq(dispute.getId()), eq(true), any(), any())).thenReturn(dispute);
        when(cases.fileOf(dispute.getId())).thenReturn(List.of());

        mvc.perform(post("/api/v1/disputes/" + dispute.getId() + "/resolve")
                        .with(signedAs("agent-subject", "SUPPORT_AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"REFUND\",\"resolution\":\"tracking shows no delivery\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dispute.status").value("RESOLVED_REFUNDED"));
    }

    @Test
    @DisplayName("refuses to decide a decided case, because the ledger would refund twice")
    void secondResolveIs409() throws Exception {
        DisputeEntity dispute = openCase();
        when(disputes.findById(dispute.getId())).thenReturn(Optional.of(dispute));
        when(cases.resolve(eq(dispute.getId()), eq(true), any(), any()))
                .thenThrow(DisputeErrors.NOT_OPEN.exception("already resolved"));

        mvc.perform(post("/api/v1/disputes/" + dispute.getId() + "/resolve")
                        .with(signedAs("agent-subject", "SUPPORT_AGENT"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"outcome\":\"REFUND\",\"resolution\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("DISPUTE_NOT_OPEN"));
    }

    @Test
    @DisplayName("lists the opener's own cases and nothing else")
    void listsOwnCases() throws Exception {
        DisputeEntity dispute = openCase();
        when(disputes.findByOpenedBySubjectOrderByCreatedAtDesc(eq(OPENER_SUBJECT), any()))
                .thenReturn(new PageImpl<>(List.of(dispute), PageRequest.of(0, 20), 1));
        when(file.countByDisputeId(dispute.getId())).thenReturn(0L);

        mvc.perform(get("/api/v1/disputes").with(signedAs(OPENER_SUBJECT, "CUSTOMER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(dispute.getId().toString()));
    }
}
