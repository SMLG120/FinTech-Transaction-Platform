package com.fintech.platform.notification.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fintech.platform.common.identity.CurrentCaller;
import com.fintech.platform.common.identity.InternalIdentity;
import com.fintech.platform.common.identity.InternalIdentityCodec;
import com.fintech.platform.common.web.WebAutoConfiguration;
import com.fintech.platform.notification.domain.NotificationKind;
import com.fintech.platform.notification.error.NotificationErrors;
import com.fintech.platform.notification.persistence.NotificationEntity;
import com.fintech.platform.notification.persistence.NotificationRepository;
import com.fintech.platform.notification.service.NotificationService;
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
 * What a support agent actually reads, and the refusals they actually get.
 *
 * <p>Authorization is not asserted here — that is {@code NotificationSecurityTest} and {@code
 * NotificationAuthorizationTest}, and duplicating it would make this class a second place to forget
 * to update. What is here is everything a role check cannot tell you: the field names on the wire,
 * the recipient digest that must never appear, and the shape of each refusal.
 *
 * <p>The digest absence is the part worth the mock. The row holds an owner digest and the view must
 * not render it; a response that leaked it would hand out a join key into another service's
 * pseudonyms, and only a test that names the field can catch it coming back.
 */
@WebMvcTest(NotificationController.class)
@AutoConfigureMockMvc
@Import({WebAutoConfiguration.class, CurrentCaller.class, NotificationAuthorization.class})
class NotificationControllerTest {

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
    private NotificationService notifications;

    @MockitoBean
    private NotificationRepository repository;

    private static NotificationEntity paymentNotice() {
        return NotificationEntity.pending(
                UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa"),
                "transaction-settled",
                NotificationKind.PAYMENT_SETTLED,
                "digest-held-by-service",
                UUID.fromString("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"),
                null,
                5000L,
                "GBP",
                "Acme Books",
                "Payment completed",
                "Your payment of GBP 50.00 to Acme Books has completed.",
                Instant.parse("2026-09-27T12:00:00Z"));
    }

    private static RequestPostProcessor signedAsSupport() {
        return request -> {
            Map<String, String> headers = CODEC.headersFor(new InternalIdentity(
                    "0f4b6c2e-6c1a-4a2b-9f3d-8c7b6a5d4e3f",
                    "support-1",
                    List.of("SUPPORT_AGENT"),
                    "correlation-1",
                    Instant.now()));
            headers.forEach(request::addHeader);
            return request;
        };
    }

    @Test
    @DisplayName("lists the delivery log newest first, without the recipient digest")
    void listsWithoutTheDigest() throws Exception {
        when(repository.findAllByOrderByCreatedAtDesc(any()))
                .thenReturn(new PageImpl<>(List.of(paymentNotice()), PageRequest.of(0, 20), 1));

        mvc.perform(get("/api/v1/notifications").with(signedAsSupport()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].kind").value("PAYMENT_SETTLED"))
                .andExpect(jsonPath("$.content[0].channel").value("PUSH"))
                .andExpect(jsonPath("$.content[0].status").value("PENDING"))
                .andExpect(jsonPath("$.content[0].amount").value("50.00"))
                .andExpect(jsonPath("$.content[0].currency").value("GBP"))
                .andExpect(jsonPath("$.content[0].subject").value("Payment completed"))
                // The absence that matters: the recipient digest must not travel to support.
                .andExpect(jsonPath("$.content[0].recipientDigest").doesNotExist());
    }

    @Test
    @DisplayName("reads one notification with its delivery state")
    void readsOne() throws Exception {
        NotificationEntity notice = paymentNotice();
        when(repository.findById(notice.getId())).thenReturn(Optional.of(notice));

        mvc.perform(get("/api/v1/notifications/" + notice.getId()).with(signedAsSupport()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(notice.getId().toString()))
                .andExpect(jsonPath("$.attempts").value(0))
                .andExpect(jsonPath("$.recipientDigest").doesNotExist());
    }

    @Test
    @DisplayName("answers 404 for a notification that does not exist, rather than an empty body")
    void missingIs404() throws Exception {
        UUID unknown = UUID.fromString("cccccccc-cccc-4ccc-8ccc-cccccccccccc");
        when(repository.findById(unknown)).thenReturn(Optional.empty());

        mvc.perform(get("/api/v1/notifications/" + unknown).with(signedAsSupport()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("NOTIFICATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("retries a failed notification and returns what the attempt did")
    void retriesOne() throws Exception {
        NotificationEntity notice = paymentNotice();
        when(repository.findById(notice.getId())).thenReturn(Optional.of(notice));
        when(notifications.retryOne(notice.getId())).thenReturn(notice);

        mvc.perform(post("/api/v1/notifications/" + notice.getId() + "/retry").with(signedAsSupport()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(notice.getId().toString()));
    }

    @Test
    @DisplayName("refuses to retry a notification that already went out, as a conflict rather than silence")
    void retryingASentMessageIs409() throws Exception {
        NotificationEntity notice = paymentNotice();
        when(repository.findById(notice.getId())).thenReturn(Optional.of(notice));
        when(notifications.retryOne(notice.getId()))
                .thenThrow(NotificationErrors.ALREADY_SENT.exception("already sent"));

        mvc.perform(post("/api/v1/notifications/" + notice.getId() + "/retry").with(signedAsSupport()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("NOTIFICATION_ALREADY_SENT"));
    }

    @Test
    @DisplayName("lists one payment's messages in the order they were recorded")
    void listsByTransaction() throws Exception {
        when(repository.findByTransactionIdOrderByCreatedAtAsc(any())).thenReturn(List.of(paymentNotice()));

        mvc.perform(get("/api/v1/notifications/by-transaction/bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb")
                        .with(signedAsSupport()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].transactionId").value("bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb"));
    }
}
