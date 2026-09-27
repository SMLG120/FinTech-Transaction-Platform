package com.fintech.platform.transaction.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for {@link SourceNetwork}.
 *
 * <p>Two properties are being defended here, and the second is the reason this class exists.
 *
 * <p><b>Grouping is correct and stable.</b> Two addresses on the same fixed line must produce the same
 * network, or the "rapid network change" rule fires every time a household's router hands out a
 * different address, and a rule that cries wolf gets switched off. A /24 does that for IPv4; the IPv6
 * equivalent is a /32, which needs the compressed form resolved first — see the note on
 * {@code expandIpv6}.
 *
 * <p><b>Nothing here performs name resolution.</b> The address arrives from a header a client controls, so
 * any implementation that hands it to {@code InetAddress.getByName} has handed an attacker a DNS query
 * to a resolver of their choosing. The tests below pass values that would resolve if a resolver were
 * ever consulted, and assert that the answer is null rather than an address: a hostname-shaped input has
 * to come back unmasked, not masked and not resolved.
 */
class SourceNetworkTest {

    @Nested
    @DisplayName("IPv4")
    class Ipv4 {

        @ParameterizedTest
        @CsvSource({
            // The whole point: a /24, so the last octet of the host is discarded.
            "192.0.2.147, 192.0.2.0/24",
            "192.0.2.1, 192.0.2.0/24",
            "192.0.2.255, 192.0.2.0/24",
            "10.4.5.6, 10.4.5.0/24"
        })
        @DisplayName("masks to the /24, so two hosts on one line share a network")
        void masksToSlash24(String address, String expected) {
            assertThat(SourceNetwork.mask(address)).isEqualTo(expected);
        }

        @Test
        @DisplayName("keeps neighbours apart, so a real move between lines is visible")
        void distinguishesAdjacentNetworks() {
            assertThat(SourceNetwork.mask("192.0.2.1")).isNotEqualTo(SourceNetwork.mask("192.0.3.1"));
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    // A hostname. Resolving it would be the SSRF primitive; unmasked is the only safe answer.
                    "evil.example.com",
                    "localhost",
                    // Shape-shifted hostnames with a numeric-looking label.
                    "1.2.3.4.example.com",
                    // Too few and too many octets.
                    "192.0.2",
                    "192.0.2.1.5",
                    // Out of range, and negative, and non-numeric.
                    "192.0.2.256",
                    "192.0.2.-1",
                    "192.0.2.a",
                    // A leading zero is octal to some parsers and zero to others; two implementations
                    // disagreeing about "the same" address would fork one network into two identities.
                    "192.0.2.01",
                    "010.0.0.1",
                    // Empty octets.
                    "192..2.1",
                    "192.0.2.",
                    // Whitespace inside, which a lenient trim-then-split would accept.
                    "192.0. 2.1"
                })
        @DisplayName("refuses anything that is not a plain decimal literal")
        void refusesNonLiterals(String value) {
            assertThat(SourceNetwork.mask(value)).isNull();
        }

        @Test
        @DisplayName("refuses null and blank rather than inventing a network")
        void refusesAbsentAddress() {
            assertThat(SourceNetwork.mask(null)).isNull();
            assertThat(SourceNetwork.mask("")).isNull();
            assertThat(SourceNetwork.mask("   ")).isNull();
        }

        @Test
        @DisplayName("trims surrounding whitespace, which a proxy may add")
        void trimsSurroundingWhitespace() {
            assertThat(SourceNetwork.mask("  192.0.2.1  ")).isEqualTo("192.0.2.0/24");
        }
    }

    @Nested
    @DisplayName("IPv6")
    class Ipv6 {

        @ParameterizedTest
        @CsvSource({
            // Compressed, and the reason the expansion exists. Reading the first two colon-separated
            // fields would give the same answer here, which is exactly what makes the other cases below
            // worth testing.
            "2001:db8::1, 2001:db8::/32",
            "2001:db8::, 2001:db8::/32",
            "2001:0db8:0:0:0:0:0:1, 2001:db8::/32",
            // Compression after the first two groups: still the same /32.
            "2001:db8:1:2:3:4:5:6, 2001:db8::/32",
            // Compression in the middle, where the second hextet is genuinely zero and reading fields
            // positionally would have produced 2001:0... — a different, wrong network.
            "2001::db8:1:1, 2001:0::/32",
            "fe80:0:0:1:2:3:4:5, fe80:0::/32",
            // Uppercase and full-width groups normalise to lowercase, so one network cannot be two.
            "2001:DB8::1, 2001:db8::/32",
            "ABCD:EF01::, abcd:ef01::/32"
        })
        @DisplayName("masks to the /32, resolving compression to find the first two groups")
        void masksToSlash32(String address, String expected) {
            assertThat(SourceNetwork.mask(address)).isEqualTo(expected);
        }

        @Test
        @DisplayName("gives one network for every spelling of the same /32")
        void allSpellingsOfOneNetworkAgree() {
            String canonical = SourceNetwork.mask("2001:db8:1234:5678:9abc:def0:1:2");
            assertThat(SourceNetwork.mask("2001:db8:1234:5678:9abc:def0:1:2")).isEqualTo(canonical);
            assertThat(SourceNetwork.mask("2001:0db8:1234:5678:9abc:def0:0001:0002"))
                    .isEqualTo(canonical);
            assertThat(SourceNetwork.mask("2001:db8:1234:5678:9abc:def0:1:2")).isEqualTo(canonical);
        }

        @Test
        @DisplayName("strips the brackets a Host header puts around a literal")
        void acceptsBracketedLiteral() {
            assertThat(SourceNetwork.mask("[2001:db8::1]")).isEqualTo("2001:db8::/32");
        }

        @ParameterizedTest
        @ValueSource(
                strings = {
                    // ::/8 in every spelling. A /32 of this is not a location, it is every address with
                    // no network: grouping on it would make the rapid-network-change rule fire for a
                    // customer on loopback or link-local.
                    "::",
                    "::1",
                    "0:0:0:0:0:0:0:1",
                    "0::1",
                    "fe80::1%eth0",
                    // A second ::, or a :: standing for nothing.
                    "2001::db8::1",
                    "1:2:3:4:5:6:7:8::",
                    // Seven explicit groups with no :: — one short, and not a valid address.
                    "2001:db8:0:0:0:0:1",
                    // Nine groups.
                    "2001:db8:1:2:3:4:5:6:7",
                    // A group of five hex digits, and an empty group from a trailing or leading colon.
                    "2001:db8::12345",
                    "2001:db8:",
                    ":2001:db8::1",
                    "2001::db8::",
                    // Non-hex.
                    "2001:db8::gggg",
                    "2001:db8:::1",
                    // A port was not stripped, so this is not a bare literal.
                    "[2001:db8::1]:443"
                })
        @DisplayName("refuses addresses with no network, and malformed ones")
        void refusesUnusableAddresses(String value) {
            assertThat(SourceNetwork.mask(value)).isNull();
        }
    }

    @Nested
    @DisplayName("IPv4-mapped")
    class Ipv4Mapped {

        @Test
        @DisplayName("masks the embedded IPv4, so a mapped address groups with its IPv4 form")
        void mapsToTheIpv4Network() {
            assertThat(SourceNetwork.mask("::ffff:192.0.2.1")).isEqualTo("192.0.2.0/24");
            // The same address written in hex. Two spellings of one address must not produce two
            // different groups, and masking it as IPv6 would give 0:0::/32 — a bucket every mapped
            // address shares, which is the opposite of a useful signal.
            assertThat(SourceNetwork.mask("::ffff:c000:201")).isEqualTo("192.0.2.0/24");
        }
    }
}
