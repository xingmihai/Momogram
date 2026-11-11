/*
 * Momogram - VLESS support (pure Java, self contained)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package moe.hx030.momogram.vless;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.regex.Pattern;

/**
 * VLESS v0 wire codec (stateless, no built-in encryption).
 *
 * Request header (Xray-core / Project X layout, port BEFORE address):
 *   [1B version=0][16B UUID][1B addons length][addons][1B command][2B port BE][1B atype][address][payload]
 * Response header:
 *   [1B version=0][1B addons length][addons][payload]
 *
 * atype: 0x01 IPv4, 0x02 domain (1B length prefix), 0x03 IPv6.
 * With flow="" the body is a raw bidirectional stream, so no chunk framing is needed.
 */
public final class VlessCodec {

    private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    public static final byte COMMAND_TCP = 0x01;
    public static final byte ATYPE_IPV4 = 0x01;
    public static final byte ATYPE_DOMAIN = 0x02;
    public static final byte ATYPE_IPV6 = 0x03;

    private VlessCodec() {
    }

    public static byte[] buildRequestHeader(byte[] uuid, String targetHost, int targetPort) throws IOException {
        if (uuid == null || uuid.length != 16) {
            throw new IOException("invalid UUID bytes");
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0);                       // protocol version
        out.write(uuid, 0, 16);             // user id
        out.write(0);                       // addons length = 0 (no Flow, no XTLS)
        out.write(COMMAND_TCP);             // TCP
        out.write((targetPort >>> 8) & 0xff);
        out.write(targetPort & 0xff);

        String host = targetHost == null ? "" : targetHost;
        if (IPV4.matcher(host).matches()) {
            out.write(ATYPE_IPV4);
            out.write(ipv4(host));
        } else if (host.indexOf(':') >= 0) {
            out.write(ATYPE_IPV6);
            byte[] v6;
            try {
                v6 = InetAddress.getByName(host).getAddress();
            } catch (Exception e) {
                throw new IOException("invalid IPv6 target: " + host);
            }
            if (v6 == null || v6.length != 16) {
                throw new IOException("invalid IPv6 target: " + host);
            }
            out.write(v6, 0, 16);
        } else {
            byte[] domain = host.getBytes(StandardCharsets.UTF_8);
            if (domain.length == 0 || domain.length > 255) {
                throw new IOException("invalid domain target: " + host);
            }
            out.write(ATYPE_DOMAIN);
            out.write(domain.length);
            out.write(domain, 0, domain.length);
        }
        return out.toByteArray();
    }

    private static byte[] ipv4(String host) throws IOException {
        String[] parts = host.split("\\.");
        if (parts.length != 4) throw new IOException("invalid IPv4 target: " + host);
        byte[] b = new byte[4];
        for (int i = 0; i < 4; i++) {
            int v = Integer.parseInt(parts[i]);
            if (v < 0 || v > 255) throw new IOException("invalid IPv4 target: " + host);
            b[i] = (byte) v;
        }
        return b;
    }

    /**
     * Consume the VLESS response header. Everything after it is raw payload.
     * Returns the reported protocol version.
     */
    public static int readResponseHeader(InputStream in) throws IOException {
        int version = in.read();
        if (version < 0) {
            throw new IOException("VLESS: connection closed before response header");
        }
        int addonsLen = in.read();
        if (addonsLen < 0) {
            throw new IOException("VLESS: truncated response header");
        }
        int remaining = addonsLen & 0xff;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) throw new IOException("VLESS: truncated response addons");
                skipped = 1;
            }
            remaining -= (int) skipped;
        }
        if (version != 0) {
            throw new IOException("VLESS: unsupported server version " + version);
        }
        return version;
    }

    /** SOCKS5 greeting reply: VER=5, METHOD=NO AUTH. */
    public static byte[] socksMethodReply() {
        return new byte[]{0x05, 0x00};
    }

    /** SOCKS5 CONNECT success reply (bound address is irrelevant for a local bridge). */
    public static byte[] socksConnectReply() {
        return new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
    }

    /** SOCKS5 CONNECT failure reply. */
    public static byte[] socksFailureReply() {
        return new byte[]{0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0};
    }
}
