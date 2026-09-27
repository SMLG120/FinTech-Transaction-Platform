package com.fintech.platform.card.tokenisation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fintech.platform.card.domain.CardToken;
import com.fintech.platform.card.domain.Pan;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

@DisplayName("CardTokenizer")
class CardTokenizerTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);

    private static CardTokenizer tokenizerWith(byte[] key) {
        CardTokenisationProperties properties = new CardTokenisationProperties();
        properties.setKey(Base64.getEncoder().encodeToString(key));
        return new CardTokenizer(properties);
    }

    private static CardTokenizer tokenizer() {
        return tokenizerWith(KEY);
    }

    @Nested
    @DisplayName("key handling")
    class Keys {

        @Test
        @DisplayName("refuses to start without a key, because a default would make every token reproducible")
        void refusesMissingKey() {
            // The most important test in this file. A defaulted key would let anyone holding the cards
            // table and this repository recompute every token, and — since the token is deterministic —
            // confirm guesses about a card number. Failing at startup is the only safe answer.
            CardTokenisationProperties properties = new CardTokenisationProperties();
            assertThatThrownBy(properties::decodeKey).isInstanceOf(NullPointerException.class);
        }

        @Test
        @DisplayName("refuses a key that is not base64")
        void refusesMalformedKey() {
            CardTokenisationProperties properties = new CardTokenisationProperties();
            properties.setKey("not base64 at all!!");
            assertThatThrownBy(properties::decodeKey)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("base64");
        }

        @Test
        @DisplayName("refuses a key of the wrong length rather than padding or truncating it")
        void refusesWrongLength() {
            // Silently padding a 16-byte key to 32 would halve the security while looking like it
            // worked, and truncating an over-long key would do the same without any signal at all.
            CardTokenisationProperties properties = new CardTokenisationProperties();
            properties.setKey(Base64.getEncoder().encodeToString(new byte[16]));
            assertThatThrownBy(properties::decodeKey)
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("32");
        }

        @Test
        @DisplayName("tolerates surrounding whitespace, which a pasted secret usually has")
        void toleratesWhitespace() {
            CardTokenisationProperties properties = new CardTokenisationProperties();
            properties.setKey("  " + Base64.getEncoder().encodeToString(KEY) + "\n");
            assertThat(properties.decodeKey()).containsExactly(KEY);
        }
    }

    @Nested
    @DisplayName("tokenisation")
    class Tokenisation {

        @Test
        @DisplayName("is deterministic, which is what makes a token usable for matching a payment")
        void isDeterministic() {
            Pan pan = Pan.mint();
            assertThat(tokenizer().tokenise(pan)).isEqualTo(tokenizer().tokenise(pan));
        }

        @Test
        @DisplayName("gives different cards different tokens")
        void separatesCards() {
            Set<CardToken> tokens = new HashSet<>();
            for (int i = 0; i < 500; i++) {
                tokens.add(tokenizer().tokenise(Pan.mint()));
            }
            assertThat(tokens).hasSize(500);
        }

        @Test
        @DisplayName("produces 64 hex characters, matching the column width")
        void matchesColumnWidth() {
            // 32 bytes of HMAC-SHA256 as hex. Asserted rather than assumed, because a change of
            // algorithm would silently need a schema migration and fail at that point instead of here.
            assertThat(tokenizer().tokenise(Pan.mint()).value()).matches("^[0-9a-f]{64}$");
        }

        @Test
        @DisplayName("changes completely with the key, so rotating it is a real change")
        void dependsOnTheKey() {
            Pan pan = Pan.mint();
            byte[] otherKey = "fedcba9876543210fedcba9876543210".getBytes(StandardCharsets.UTF_8);
            assertThat(tokenizerWith(otherKey).tokenise(pan))
                    .isNotEqualTo(tokenizer().tokenise(pan));
        }

        @Test
        @DisplayName("cannot be reversed from the token, and offers no operation that could")
        void offersNoReversal() {
            // Enforced by construction: CardTokenizer exposes tokenise and ownerDigest and nothing else,
            // and Pan has no field that could be reconstituted. The assertion documents the intent for
            // the next person who considers adding a detokenise.
            Pan pan = Pan.mint();
            CardToken token = tokenizer().tokenise(pan);
            assertThat(token.value()).doesNotContain(pan.digits());
        }
    }

    @Nested
    @DisplayName("domain separation")
    class DomainSeparation {

        @Test
        @DisplayName("keeps card tokens and owner digests in disjoint value spaces under one key")
        void separatesPurposes() {
            // Both derivations use the same key. Without a purpose prefix, a subject digest and a card
            // token would be values in one space, and a row read from the wrong column would still be a
            // syntactically valid value of the other type. Here they cannot collide by construction.
            CardTokenizer tokenizer = tokenizer();
            String owner = tokenizer.ownerDigest("9f2c-abc");
            CardToken token = tokenizer.tokenise(Pan.mint());
            assertThat(owner).isNotEqualTo(token.value());
            assertThat(owner).hasSize(64).matches("^[0-9a-f]{64}$");
        }

        @Test
        @DisplayName("cannot be confused by feeding a token-shaped string to the other function")
        void resistsCrossUse() {
            CardTokenizer tokenizer = tokenizer();
            String token = tokenizer.tokenise(Pan.mint()).value();
            // Even if a column were mis-mapped, the derived values do not coincide.
            assertThat(tokenizer.ownerDigest(token)).isNotEqualTo(token);
        }
    }

    @Nested
    @DisplayName("owner digests")
    class OwnerDigests {

        @Test
        @DisplayName("are stable for one subject, so ownership is a single indexed equality")
        void areStable() {
            assertThat(tokenizer().ownerDigest("subject-1"))
                    .isEqualTo(tokenizer().ownerDigest("subject-1"));
        }

        @Test
        @DisplayName("differ between subjects")
        void differPerSubject() {
            assertThat(tokenizer().ownerDigest("subject-1"))
                    .isNotEqualTo(tokenizer().ownerDigest("subject-2"));
        }

        @Test
        @DisplayName("do not reveal the subject")
        void doNotRevealTheSubject() {
            String subject = "8a3f1c22-9d4e-4b7a-8c1f-2e6d5a9b0c31";
            String digest = tokenizer().ownerDigest(subject);
            assertThat(digest).doesNotContain(subject);
            assertThat(digest).isNotEqualTo(subject);
        }

        @Test
        @DisplayName("cannot be joined to another service's digest of the same subject, because the key differs")
        void areNotJoinableAcrossServices() {
            // card-service and customer-service both hold a keyed digest of the same subject, so the
            // only thing standing between an attacker holding both databases and a cross-database join
            // is that the two digests are computed under different keys. That is the whole defence
            // ADR-0002's per-service roles assume once a value is derived rather than stored.
            //
            // Tested as a property of key separation rather than by reimplementing customer-service's
            // HKDF here: a copy of another service's key schedule in this test would rot silently the
            // day that service changed its derivation, and would then be asserting against a fiction.
            String subject = "shared-subject";
            String underCardKey = tokenizer().ownerDigest(subject);
            String underPiiKey = tokenizerWith("pii-master-key-32-bytes-long!!!!".getBytes(StandardCharsets.UTF_8))
                    .ownerDigest(subject);
            assertThat(underCardKey).isNotEqualTo(underPiiKey);
        }
    }
}
