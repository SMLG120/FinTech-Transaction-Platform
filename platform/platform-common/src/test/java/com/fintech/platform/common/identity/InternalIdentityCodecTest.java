package com.fintech.platform.common.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Tests for the signing scheme ADR-0004 depends on.
 *
 * <p>The forgery cases here are the point of the exercise: a scheme that only proves it can verify its
 * own output is untested against the only adversary that matters.
 */
class InternalIdentityCodecTest {

    private static final String HEX_KEY = "b2ab932a72634cbd70fa5a5182b10d07ac8223ea87e101c1c0fb98d65b3b9801";

    private final Instant now = Instant.parse("2026-03-01T10:00:00Z");
    private final Clock clock = Clock.fixed(now, ZoneOffset.UTC);
    private final InternalIdentityCodec codec =
            InternalIdentityCodec.fromHexKey(HEX_KEY, Duration.ofSeconds(60), clock);

    private InternalIdentity identity() {
        return new InternalIdentity(
                "8f14e45f-ea0c-4f2b-9a1d-1234567890ab",
                "customer@fintech.test",
                List.of("CUSTOMER", "SUPPORT_AGENT"),
                "01HQ8Z3K7M2N9P4R6T8V0W2X4Y",
                now);
    }

    @Nested
    @DisplayName("round trip")
    class RoundTrip {

        @Test
        void restores_every_field_of_a_signed_identity() {
            InternalIdentity verified = codec.verify(codec.headersFor(identity()));

            assertThat(verified).isEqualTo(identity());
            assertThat(verified.hasRole("CUSTOMER")).isTrue();
        }

        @Test
        void survives_the_header_string_conversion_a_real_proxy_performs() {
            // Real hops are not byte-perfect; base64url avoids +, / and = specifically so that the
            // signature cannot be mangled or rejected as a form value by an intermediary.
            Map<String, String> headers = codec.headersFor(identity());
            String signature = headers.get(InternalIdentityCodec.HEADER_SIGNATURE);

            assertThat(signature).doesNotContain("+").doesNotContain("/").doesNotContain("=");
        }

        @Test
        void produces_the_same_signature_regardless_of_role_order() {
            InternalIdentity ascending = new InternalIdentity("sub", "u", List.of("AUDITOR", "CUSTOMER"), "cid", now);
            InternalIdentity descending = new InternalIdentity("sub", "u", List.of("CUSTOMER", "AUDITOR"), "cid", now);

            assertThat(ascending.canonicalForm()).isEqualTo(descending.canonicalForm());
            assertThat(codec.headersFor(ascending)).isEqualTo(codec.headersFor(descending));
        }
    }

    @Nested
    @DisplayName("forgery resistance")
    class ForgeryResistance {

        @Test
        void rejects_a_tampered_role() {
            Map<String, String> headers = new HashMap<>(codec.headersFor(identity()));
            headers.put(InternalIdentityCodec.HEADER_ROLES, "CUSTOMER,PLATFORM_ADMIN");

            assertThatThrownBy(() -> codec.verify(headers))
                    .isInstanceOf(InternalIdentityVerificationException.class)
                    .hasMessageContaining("signature");
        }

        @Test
        void rejects_a_swapped_subject_kept_with_the_original_signature() {
            // The realistic escalation: replay a captured header set for a different principal.
            Map<String, String> headers = new HashMap<>(codec.headersFor(identity()));
            headers.put(InternalIdentityCodec.HEADER_SUBJECT, "8f14e45f-ea0c-4f2b-9a1d-ffffffffffff");

            assertThatThrownBy(() -> codec.verify(headers)).isInstanceOf(InternalIdentityVerificationException.class);
        }

        @Test
        void rejects_a_signature_from_a_different_key() {
            InternalIdentityCodec attacker = InternalIdentityCodec.fromHexKey(
                    "1111111111111111111111111111111111111111111111111111111111111111", Duration.ofSeconds(60), clock);

            assertThatThrownBy(() -> codec.verify(attacker.headersFor(identity())))
                    .isInstanceOf(InternalIdentityVerificationException.class);
        }

        @Test
        void rejects_a_signature_one_character_short() {
            Map<String, String> headers = new HashMap<>(codec.headersFor(identity()));
            String signature = headers.get(InternalIdentityCodec.HEADER_SIGNATURE);
            headers.put(
                    InternalIdentityCodec.HEADER_SIGNATURE,
                    signature.substring(0, signature.length() - 1) + (signature.endsWith("A") ? "B" : "A"));

            assertThatThrownBy(() -> codec.verify(headers)).isInstanceOf(InternalIdentityVerificationException.class);
        }

        @Test
        void rejects_a_role_injected_through_a_field_separator() {
            // The canonical form is delimiter-joined, so a value carrying the delimiter would move a
            // field across the boundary. Construction refuses it, and the signature then never covers
            // an ambiguous string.
            assertThatThrownBy(() -> new InternalIdentity(
                            "sub", "user\nX-Internal-Identity-Roles: ADMIN", List.of(), "cid", now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("control characters");
        }

        @Test
        void rejects_a_non_identifier_role() {
            // Lower case and dashes are excluded on purpose: role names become Spring authorities and
            // end up in hasRole comparisons, so an unexpected shape is a bug worth stopping.
            assertThatThrownBy(() -> new InternalIdentity("sub", "u", List.of("admin"), "cid", now))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not a valid identifier");
        }

        @Test
        void rejects_a_missing_header() {
            Map<String, String> headers = new HashMap<>(codec.headersFor(identity()));
            headers.remove(InternalIdentityCodec.HEADER_SIGNATURE);

            assertThatThrownBy(() -> codec.verify(headers))
                    .isInstanceOf(InternalIdentityVerificationException.class)
                    .hasMessageContaining("missing header");
        }

        @Test
        void rejects_a_header_set_with_no_identity_at_all() {
            // What a service sees when a caller reaches it directly, bypassing the gateway.
            assertThatThrownBy(() -> codec.verify(Map.of()))
                    .isInstanceOf(InternalIdentityVerificationException.class)
                    .hasMessageContaining("missing header");
        }
    }

    @Nested
    @DisplayName("replay window")
    class ReplayWindow {

        @Test
        void accepts_an_identity_issued_moments_ago() {
            InternalIdentity fresh = new InternalIdentity("sub", "u", List.of("CUSTOMER"), "cid", now.minusSeconds(5));
            assertThat(codec.verify(codec.headersFor(fresh))).isEqualTo(fresh);
        }

        @Test
        void rejects_an_identity_older_than_the_window() {
            InternalIdentity stale = new InternalIdentity("sub", "u", List.of("CUSTOMER"), "cid", now.minusSeconds(61));

            assertThatThrownBy(() -> codec.verify(codec.headersFor(stale)))
                    .isInstanceOf(InternalIdentityVerificationException.class)
                    .hasMessageContaining("older than");
        }

        @Test
        void tolerates_a_gateway_running_slightly_ahead() {
            InternalIdentity skewed = new InternalIdentity("sub", "u", List.of("CUSTOMER"), "cid", now.plusSeconds(5));
            assertThat(codec.verify(codec.headersFor(skewed))).isEqualTo(skewed);
        }

        @Test
        void rejects_an_identity_dated_far_in_the_future() {
            InternalIdentity future =
                    new InternalIdentity("sub", "u", List.of("CUSTOMER"), "cid", now.plusSeconds(600));

            assertThatThrownBy(() -> codec.verify(codec.headersFor(future)))
                    .isInstanceOf(InternalIdentityVerificationException.class)
                    .hasMessageContaining("future");
        }
    }

    @Nested
    @DisplayName("key handling")
    class KeyHandling {

        @Test
        void refuses_a_key_shorter_than_the_digest() {
            assertThatThrownBy(() -> InternalIdentityCodec.fromHexKey("abcd", Duration.ofSeconds(60), clock))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("at least 32 bytes");
        }

        @Test
        void refuses_a_key_that_is_not_hex() {
            assertThatThrownBy(() -> InternalIdentityCodec.fromHexKey(
                            "zzzz" + HEX_KEY.substring(4), Duration.ofSeconds(60), clock))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-hex");
        }

        @Test
        void refuses_an_odd_length_key() {
            assertThatThrownBy(() -> InternalIdentityCodec.fromHexKey("abc", Duration.ofSeconds(60), clock))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("even length");
        }

        @Test
        void decodes_the_same_bytes_the_gateway_signed_with() {
            // Guards the hex round trip that bootstrap.sh and the compose file both depend on.
            Map<String, String> headers = codec.headersFor(identity());
            assertThat(codec.verify(headers)).isEqualTo(identity());
        }
    }

    @Test
    @DisplayName("canonical form is stable and self-describing")
    void canonical_form_reads_as_the_signed_fields() {
        assertThat(identity().canonicalForm())
                .isEqualTo("8f14e45f-ea0c-4f2b-9a1d-1234567890ab\ncustomer@fintech.test\nCUSTOMER,SUPPORT_AGENT\n"
                        + "01HQ8Z3K7M2N9P4R6T8V0W2X4Y\n" + now.getEpochSecond());
    }

    @Test
    void an_identity_without_roles_is_legal_because_it_carries_no_authority() {
        InternalIdentity anonymous = new InternalIdentity("sub", "u", List.of(), "cid", now);
        assertThat(codec.verify(codec.headersFor(anonymous)).roles()).isEmpty();
    }

    @Test
    void tolerates_a_realistic_role_set_without_mangling_it() {
        List<String> many = IntStream.range(0, 64).mapToObj(i -> "ROLE_" + i).toList();
        InternalIdentity loaded = new InternalIdentity("sub", "u", many, "cid", now);

        // Canonical form sorts, so the round trip preserves the set rather than the caller's ordering.
        assertThat(codec.verify(codec.headersFor(loaded)).roles())
                .hasSize(64)
                .containsExactlyElementsOf(many.stream().sorted().toList());
    }

    @Test
    void refuses_more_roles_than_the_header_can_carry_safely() {
        List<String> tooMany = new ArrayList<>();
        IntStream.range(0, 65).forEach(i -> tooMany.add("ROLE_" + i));

        assertThatThrownBy(() -> new InternalIdentity("sub", "u", tooMany, "cid", now))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("more than 64 roles");
    }
}
