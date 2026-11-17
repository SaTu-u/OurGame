package dev.claudebridge;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/** Minimal DER encoder for building X.509 certificates and PKCS#10 requests with plain JDK APIs. */
final class Der {

    static final int[] OID_SHA256_RSA = {1, 2, 840, 113549, 1, 1, 11};
    static final int[] OID_RSA_ENCRYPTION = {1, 2, 840, 113549, 1, 1, 1};
    static final int[] OID_CN = {2, 5, 4, 3};
    static final int[] OID_BASIC_CONSTRAINTS = {2, 5, 29, 19};
    static final int[] OID_SUBJECT_ALT_NAME = {2, 5, 29, 17};
    static final int[] OID_EXTENSION_REQUEST = {1, 2, 840, 113549, 1, 9, 14};
    /** RFC 8737 id-pe-acmeIdentifier. */
    static final int[] OID_ACME_IDENTIFIER = {1, 3, 6, 1, 5, 5, 7, 1, 31};

    private Der() {
    }

    static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(tag);
        int len = value.length;
        if (len < 0x80) {
            out.write(len);
        } else if (len <= 0xFF) {
            out.write(0x81);
            out.write(len);
        } else if (len <= 0xFFFF) {
            out.write(0x82);
            out.write(len >> 8);
            out.write(len & 0xFF);
        } else {
            out.write(0x83);
            out.write(len >> 16);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        }
        out.writeBytes(value);
        return out.toByteArray();
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] p : parts) out.writeBytes(p);
        return out.toByteArray();
    }

    static byte[] seq(byte[]... parts) {
        return tlv(0x30, concat(parts));
    }

    static byte[] set(byte[]... parts) {
        return tlv(0x31, concat(parts));
    }

    static byte[] integer(BigInteger v) {
        return tlv(0x02, v.toByteArray());
    }

    static byte[] octets(byte[] v) {
        return tlv(0x04, v);
    }

    static byte[] bitString(byte[] v) {
        byte[] b = new byte[v.length + 1];
        System.arraycopy(v, 0, b, 1, v.length);
        return tlv(0x03, b);
    }

    static byte[] bool(boolean v) {
        return new byte[]{0x01, 0x01, (byte) (v ? 0xFF : 0x00)};
    }

    static byte[] nul() {
        return new byte[]{0x05, 0x00};
    }

    static byte[] utf8(String s) {
        return tlv(0x0C, s.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] utcTime(ZonedDateTime t) {
        return tlv(0x17, t.format(DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'")).getBytes(StandardCharsets.US_ASCII));
    }

    static byte[] oid(int... arcs) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(arcs[0] * 40 + arcs[1]);
        for (int i = 2; i < arcs.length; i++) {
            int v = arcs[i];
            byte[] tmp = new byte[5];
            int n = 0;
            do {
                tmp[n++] = (byte) (v & 0x7F);
                v >>>= 7;
            } while (v != 0);
            for (int j = n - 1; j >= 0; j--) out.write(tmp[j] | (j > 0 ? 0x80 : 0));
        }
        return tlv(0x06, out.toByteArray());
    }

    static byte[] sha256WithRsa() {
        return seq(oid(OID_SHA256_RSA), nul());
    }

    /** X.501 Name with a single CN. */
    static byte[] name(String commonName) {
        return seq(set(seq(oid(OID_CN), utf8(commonName))));
    }

    static byte[] extension(int[] oid, boolean critical, byte[] value) {
        return critical ? seq(oid(oid), bool(true), octets(value)) : seq(oid(oid), octets(value));
    }

    /** subjectAltName with DNS names and IP addresses. */
    static byte[] subjectAltName(List<String> names) throws UnknownHostException {
        List<byte[]> entries = new ArrayList<>();
        for (String h : names) {
            if (isIpLiteral(h)) entries.add(tlv(0x87, InetAddress.getByName(h).getAddress()));
            else entries.add(tlv(0x82, h.getBytes(StandardCharsets.US_ASCII)));
        }
        return extension(OID_SUBJECT_ALT_NAME, false, seq(entries.toArray(new byte[0][])));
    }

    static byte[] basicConstraints(boolean ca) {
        return extension(OID_BASIC_CONSTRAINTS, false, ca ? seq(bool(true)) : seq());
    }

    static boolean isIpLiteral(String s) {
        return s.matches("\\d{1,3}(\\.\\d{1,3}){3}") || (s.contains(":") && s.matches("[0-9a-fA-F:.]+"));
    }
}
