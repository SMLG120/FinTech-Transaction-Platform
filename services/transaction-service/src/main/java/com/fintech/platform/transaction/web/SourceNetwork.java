package com.fintech.platform.transaction.web;

/**
 * Reduces a client address to the network it belongs to.
 *
 * <p>A fraud rule needs to answer "did this customer's traffic just come from somewhere else?", and an
 * address is too precise to answer it with. Two payments from the same household share an address, so an
 * address-level rule never fires for a genuine traveller, and a rule that compared whole addresses would
 * also treat a mobile carrier's CGNAT pool as thousands of distinct locations. A /24 is the coarsest
 * grouping that is stable for a fixed-line connection and still changes when a customer moves.
 *
 * <p><b>Parsed with string operations and never handed to a resolver.</b> {@code InetAddress.getByName}
 * on a value that is not a literal address performs a DNS lookup, and this value arrives from a header a
 * client controls. A hostname-shaped string would therefore become an outbound query to a resolver this
 * service did not intend to talk to, with a client-chosen name — a blind SSRF primitive available to
 * anyone who can reach an endpoint. There is a test for that, and it is one of the more valuable ones in
 * this module.
 *
 * <p>The output is still personal data, which is why {@code SubjectDigester.networkReference} reduces it
 * again before it goes anywhere. This class's only job is to make the number of distinct values small
 * enough to be a useful signal.
 */
public final class SourceNetwork {

    private SourceNetwork() {}

    /**
     * @param remoteAddress the address the request arrived from, as the container saw it; may be null
     * @return the /24 (IPv4) or /32 (IPv6) network, or {@code null} when the address is absent, is not a
     *     literal address, or is a form this platform does not mask
     */
    public static String mask(String remoteAddress) {
        if (remoteAddress == null) {
            return null;
        }
        String address = remoteAddress.trim();
        // A bracketed IPv6 literal, which is how an address appears in a Host header and in
        // Forwarded. A port would be rejected by the parsers below rather than silently accepted, so
        // stripping the brackets is the only concession made to that format.
        if (address.startsWith("[") && address.endsWith("]")) {
            address = address.substring(1, address.length() - 1);
        }
        if (address.isEmpty()) {
            return null;
        }
        String ipv4 = maskIpv4(address);
        return ipv4 != null ? ipv4 : maskIpv6(address);
    }

    private static String maskIpv4(String address) {
        String[] octets = address.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        // All four are validated, including the one the mask discards. Discarding it does not make it
        // irrelevant: 192.0.2.256 and 192.0.2.1 produce the same /24, so an unchecked fourth octet would
        // quietly turn a malformed address into a real-looking network and the agreement would be a
        // coincidence rather than a rule. A value that claims to be an address and is not gets no network.
        for (String octet : octets) {
            if (decimalOctet(octet) < 0) {
                return null;
            }
        }
        return octets[0] + "." + octets[1] + "." + octets[2] + ".0/24";
    }

    private static String maskIpv6(String address) {
        if (address.indexOf(':') < 0) {
            return null;
        }
        int[] hextets = expandIpv6(address);
        if (hextets == null) {
            return null;
        }
        // An IPv4-mapped address in hex form (::ffff:c000:201) is the same address as the dotted form
        // handled above, and has to be recognised here too: masked as IPv6 it would land in a /32 that
        // every mapped address shares, and rejecting it would make two spellings of one address behave
        // differently. The check is on the expanded form, so it does not care which spelling arrived.
        if (isIpv4Mapped(hextets)) {
            return maskIpv4(((hextets[6] >> 8) & 0xff) + "." + (hextets[6] & 0xff) + "." + ((hextets[7] >> 8) & 0xff)
                    + "." + (hextets[7] & 0xff));
        }
        // ::/8 — the unspecified address, loopback, and everything else whose first 32 bits are zero.
        // A /32 mask of that is ::/32, which is not a location: it is every address that has no network.
        // Grouping on it would make R004 fire for a customer the moment they connected over IPv6 loopback
        // or a link-local address, which is the rule producing noise rather than signal.
        if (hextets[0] == 0 && hextets[1] == 0) {
            return null;
        }
        return Integer.toHexString(hextets[0]) + ":" + Integer.toHexString(hextets[1]) + "::/32";
    }

    /**
     * Expands an IPv6 literal to its eight 16-bit groups, or returns null if it is not one.
     *
     * <p>Compression is resolved here, once, rather than downstream. The alternative — reading the first
     * two {@code :}-separated fields and treating them as the first two hextets — looks like it works and
     * is wrong for every compressed address, because the run of zeroes that {@code ::} stands for can be
     * anywhere: in {@code 2001:db8::1} the answer is {@code 2001:db8::/32}, while in
     * {@code 2001::db8:1:1} the fields after the compression have to be shifted to find the second
     * hextet, which is zero. Only an expansion gets both right, and it also makes "is the first 32 bits
     * zero?" a question about eight known numbers instead of a guess about string positions.
     *
     * <p>Strict about the grammar, since the value is attacker-supplied. At most one {@code ::}, which
     * must stand for at least one omitted group; exactly eight groups when there is no {@code ::} at all;
     * every group one to four hex digits; nothing else. A group of five digits, or a trailing colon, or a
     * second {@code ::}, is refused rather than truncated — the value is masked or it is not, and there
     * is no third outcome.
     */
    private static int[] expandIpv6(String address) {
        // A dotted-quad tail is an embedded IPv4 address, and it is rewritten into two groups before
        // anything else looks at it. Doing it here rather than in a special case at the end means
        // ::ffff:192.0.2.1 and ::ffff:c000:201 — one address, two spellings — are the same value by the
        // time any rule sees them, instead of one being masked and the other refused. Rewriting also
        // keeps the dotted form subject to the same hextet validation as the hex form, so
        // "::ffff:999.1.1.1" is refused for the same reason "::ffff:3e7" is.
        if (address.indexOf('.') >= 0) {
            int lastColon = address.lastIndexOf(':');
            if (lastColon < 0) {
                return null;
            }
            int[] embedded = parseDottedQuad(address.substring(lastColon + 1));
            if (embedded == null) {
                return null;
            }
            address = address.substring(0, lastColon + 1) + String.format("%x:%x", embedded[0], embedded[1]);
        }
        int compression = address.indexOf("::");
        if (compression != address.lastIndexOf("::")) {
            return null;
        }
        String head;
        String tail;
        if (compression >= 0) {
            head = address.substring(0, compression);
            tail = address.substring(compression + 2);
        } else {
            head = address;
            tail = "";
        }
        int[] left = parseHextets(head);
        int[] right = tail.isEmpty() ? new int[0] : parseHextets(tail);
        if (left == null || right == null) {
            return null;
        }
        int explicit = left.length + right.length;
        if (compression < 0) {
            if (explicit != 8) {
                return null;
            }
        } else if (explicit == 0 || explicit > 7) {
            // Zero means "::" on its own, which is the unspecified address, not a masked network; eight or
            // more means the :: stood for nothing, which RFC 4291 does not allow and which would make
            // "::1:2:3:4:5:6:7:8" another spelling of eight groups.
            return null;
        }
        int[] expanded = new int[8];
        System.arraycopy(left, 0, expanded, 0, left.length);
        System.arraycopy(right, 0, expanded, 8 - right.length, right.length);
        return expanded;
    }

    /** @return the two 16-bit groups an embedded IPv4 address occupies, or null if it is not one */
    private static int[] parseDottedQuad(String text) {
        String[] octets = text.split("\\.", -1);
        if (octets.length != 4) {
            return null;
        }
        int[] values = new int[4];
        for (int i = 0; i < 4; i++) {
            values[i] = decimalOctet(octets[i]);
            if (values[i] < 0) {
                return null;
            }
        }
        return new int[] {(values[0] << 8) | values[1], (values[2] << 8) | values[3]};
    }

    /**
     * @return the groups, or null if any is not one to four hex digits. An empty string yields no groups
     *     rather than an error, because that is how the uncompressed and the compressed forms of an
     *     address both end up here; the caller decides whether the count is legal.
     */
    private static int[] parseHextets(String text) {
        if (text.isEmpty()) {
            return new int[0];
        }
        String[] parts = text.split(":", -1);
        int[] values = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            if (!isHexHextet(parts[i])) {
                return null;
            }
            values[i] = Integer.parseInt(parts[i], 16);
        }
        return values;
    }

    /** True for the {@code ::ffff:0:0/96} range, which is an IPv4 address inside an IPv6 packet. */
    private static boolean isIpv4Mapped(int[] hextets) {
        for (int i = 0; i < 5; i++) {
            if (hextets[i] != 0) {
                return false;
            }
        }
        return hextets[5] == 0xffff;
    }

    /**
     * @return the octet's value, or -1 when it is not a decimal number in 0..255.
     *     Rejecting a leading {@code +}/{@code -}, a leading zero and anything non-numeric matters:
     *     {@code 010} is octal to some parsers and {@code 0} to others, so two implementations of "the
     *     same address" would disagree and a network would fork into two identities.
     */
    private static int decimalOctet(String octet) {
        if (octet.isEmpty() || octet.length() > 3) {
            return -1;
        }
        if (octet.length() > 1 && octet.charAt(0) == '0') {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < octet.length(); i++) {
            char c = octet.charAt(i);
            if (c < '0' || c > '9') {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        return value <= 255 ? value : -1;
    }

    private static boolean isHexHextet(String hextet) {
        if (hextet.isEmpty() || hextet.length() > 4) {
            return false;
        }
        for (int i = 0; i < hextet.length(); i++) {
            char c = hextet.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
