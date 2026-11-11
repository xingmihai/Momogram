/*
 * Momogram - VLESS support (pure Java, self contained)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package moe.hx030.momogram.vless;

import android.os.Build;
import android.text.TextUtils;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * Transport layer for VLESS: raw TCP, TCP+TLS and WebSocket (ws/wss).
 *
 * WebSocket framing is implemented in-process instead of pulling in an extra
 * dependency: VLESS over WS is just a bidirectional binary stream whose first
 * message carries the request header.
 */
public final class VlessTransport {

    private static final int CONNECT_TIMEOUT = 15000;
    private static final int MAX_FRAME = 8 * 1024 * 1024;

    public interface Duplex {
        InputStream input();

        OutputStream output();

        void close();
    }

    private VlessTransport() {
    }

    public static Duplex open(VlessProfile p) throws IOException {
        Socket raw = new Socket();
        try {
            raw.connect(new InetSocketAddress(p.address, p.port), CONNECT_TIMEOUT);
            raw.setTcpNoDelay(true);
            raw.setKeepAlive(true);

            Socket socket = p.useTls() ? wrapTls(raw, p) : raw;
            if (VlessProfile.TRANSPORT_WS.equals(p.type)) {
                return new WebSocketDuplex(socket, p);
            }
            return new SocketDuplex(socket);
        } catch (IOException e) {
            try {
                raw.close();
            } catch (Exception ignored) {
            }
            throw e;
        } catch (RuntimeException e) {
            try {
                raw.close();
            } catch (Exception ignored) {
            }
            throw new IOException(e);
        }
    }

    private static Socket wrapTls(Socket raw, VlessProfile p) throws IOException {
        String sni = TextUtils.isEmpty(p.sni) ? p.address : p.sni;
        SSLSocketFactory factory;
        if (p.allowInsecure) {
            factory = insecureFactory();
        } else {
            factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        }
        // Passing `sni` as host makes the platform set the SNI extension on the
        // already-connected socket; routing is unaffected.
        SSLSocket ssl = (SSLSocket) factory.createSocket(raw, sni, p.port, true);
        ssl.setUseClientMode(true);
        if (Build.VERSION.SDK_INT >= 21 && !TextUtils.isEmpty(p.alpn)) {
            try {
                // Reflection keeps us loadable on older devices where
                // SSLParameters#setApplicationProtocols does not exist.
                java.lang.reflect.Method m = javax.net.ssl.SSLParameters.class
                        .getMethod("setApplicationProtocols", String[].class);
                javax.net.ssl.SSLParameters params = ssl.getSSLParameters();
                m.invoke(params, (Object) p.alpn.split(","));
                ssl.setSSLParameters(params);
            } catch (Throwable ignored) {
            }
        }
        ssl.startHandshake();
        ssl.setSoTimeout(0);
        return ssl;
    }

    private static SSLSocketFactory insecureFactory() throws IOException {
        try {
            TrustManager[] trustAll = new TrustManager[]{new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }};
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, trustAll, new SecureRandom());
            return ctx.getSocketFactory();
        } catch (Exception e) {
            throw new IOException("cannot build permissive TLS context", e);
        }
    }

    // ---------------------------------------------------------------------
    // Plain socket
    // ---------------------------------------------------------------------

    private static final class SocketDuplex implements Duplex {
        private final Socket socket;
        private InputStream in;
        private OutputStream out;

        SocketDuplex(Socket socket) throws IOException {
            this.socket = socket;
            this.in = socket.getInputStream();
            this.out = socket.getOutputStream();
        }

        @Override
        public InputStream input() {
            return in;
        }

        @Override
        public OutputStream output() {
            return out;
        }

        @Override
        public void close() {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------------------------------------------------------------
    // WebSocket
    // ---------------------------------------------------------------------

    private static final class WebSocketDuplex implements Duplex {
        private final Socket socket;
        private final InputStream rawIn;
        private final OutputStream rawOut;
        private final PipedInputStream frameIn;
        private final PipedOutputStream frameSink;
        private final FrameOutput frameOut;
        private volatile boolean running = true;
        private final ByteArrayOutputStream fragmented = new ByteArrayOutputStream();

        WebSocketDuplex(Socket socket, VlessProfile p) throws IOException {
            this.socket = socket;
            this.rawIn = socket.getInputStream();
            this.rawOut = socket.getOutputStream();
            handshake(p);
            this.frameIn = new PipedInputStream(128 * 1024);
            this.frameSink = new PipedOutputStream(frameIn);
            this.frameOut = new FrameOutput(this);
            Thread pump = new Thread(this::pumpLoop, "momo-vless-ws");
            pump.setDaemon(true);
            pump.start();
        }

        private void handshake(VlessProfile p) throws IOException {
            String path = TextUtils.isEmpty(p.path) ? "/" : p.path;
            String host = TextUtils.isEmpty(p.host) ? p.address : p.host;
            boolean defaultPort = p.port == 80 || p.port == 443;
            String hostHeader = defaultPort ? host : host + ":" + p.port;
            byte[] keyBytes = new byte[16];
            new SecureRandom().nextBytes(keyBytes);
            String key = Base64.encodeToString(keyBytes, Base64.NO_WRAP);

            String request = "GET " + path + " HTTP/1.1\r\n"
                    + "Host: " + hostHeader + "\r\n"
                    + "Upgrade: websocket\r\n"
                    + "Connection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\n"
                    + "Sec-WebSocket-Version: 13\r\n"
                    + "\r\n";
            rawOut.write(request.getBytes(StandardCharsets.UTF_8));
            rawOut.flush();

            ByteArrayOutputStream header = new ByteArrayOutputStream();
            int matched = 0;
            for (int i = 0; i < 16384; i++) {
                int c = rawIn.read();
                if (c < 0) throw new IOException("websocket handshake: EOF");
                header.write(c);
                // match \r\n\r\n
                if (c == '\r') {
                    matched = 1;
                } else if (c == '\n' && matched == 1) {
                    matched = 2;
                } else if (c == '\r' && matched == 2) {
                    matched = 3;
                } else if (c == '\n' && matched == 3) {
                    break;
                } else {
                    matched = 0;
                }
                if (i == 16383) throw new IOException("websocket handshake: header too large");
            }
            String status = header.toString("UTF-8");
            if (!status.contains(" 101")) {
                throw new IOException("websocket handshake rejected: "
                        + status.split("\r\n")[0]);
            }
        }

        @Override
        public InputStream input() {
            return frameIn;
        }

        @Override
        public OutputStream output() {
            return frameOut;
        }

        @Override
        public void close() {
            running = false;
            try {
                frameSink.close();
            } catch (Exception ignored) {
            }
            try {
                frameIn.close();
            } catch (Exception ignored) {
            }
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }

        private void pumpLoop() {
            try {
                while (running) {
                    byte[] message = readMessage();
                    if (message == null) break;
                    frameSink.write(message);
                    frameSink.flush();
                }
            } catch (Exception ignored) {
            } finally {
                running = false;
                try {
                    frameSink.close();
                } catch (Exception ignored) {
                }
            }
        }

        /** Read one complete WS message (handles fragmentation and control frames). */
        private byte[] readMessage() throws IOException {
            while (true) {
                int b0 = rawIn.read();
                if (b0 < 0) return null;
                boolean fin = (b0 & 0x80) != 0;
                int opcode = b0 & 0x0f;

                int b1 = rawIn.read();
                if (b1 < 0) return null;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7f;
                if (len == 126) {
                    len = (readByte() << 8) | readByte();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | readByte();
                }
                if (len > MAX_FRAME) throw new IOException("websocket frame too large: " + len);

                byte[] mask = null;
                if (masked) {
                    mask = new byte[4];
                    readFully(mask, 4);
                }
                byte[] payload = new byte[(int) len];
                readFully(payload, payload.length);
                if (mask != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= mask[i & 3];
                    }
                }

                if (opcode == 0x8) {
                    return null; // close
                }
                if (opcode == 0x9) {
                    sendFrame(0xA, payload); // pong
                    continue;
                }
                if (opcode == 0xA) {
                    continue;
                }
                if (opcode == 0x1 || opcode == 0x2 || opcode == 0x0) {
                    fragmented.write(payload, 0, payload.length);
                    if (fin) {
                        byte[] message = fragmented.toByteArray();
                        fragmented.reset();
                        return message;
                    }
                }
            }
        }

        private int readByte() throws IOException {
            int v = rawIn.read();
            if (v < 0) throw new IOException("websocket: truncated frame header");
            return v;
        }

        private void readFully(byte[] buf, int length) throws IOException {
            int off = 0;
            while (off < length) {
                int n = rawIn.read(buf, off, length - off);
                if (n < 0) throw new IOException("websocket: truncated frame payload");
                off += n;
            }
        }

        /** Client frames MUST be masked. */
        void sendFrame(int opcode, byte[] payload) throws IOException {
            synchronized (rawOut) {
                rawOut.write(0x80 | opcode);
                int len = payload.length;
                if (len < 126) {
                    rawOut.write(0x80 | len);
                } else if (len <= 0xffff) {
                    rawOut.write(0x80 | 126);
                    rawOut.write((len >>> 8) & 0xff);
                    rawOut.write(len & 0xff);
                } else {
                    rawOut.write(0x80 | 127);
                    for (int i = 7; i >= 0; i--) rawOut.write((int) (((long) len >>> (i * 8)) & 0xff));
                }
                byte[] mask = new byte[4];
                new SecureRandom().nextBytes(mask);
                rawOut.write(mask);
                for (int i = 0; i < len; i++) {
                    rawOut.write(payload[i] ^ mask[i & 3]);
                }
                rawOut.flush();
            }
        }

        private static final class FrameOutput extends OutputStream {
            private final WebSocketDuplex owner;

            FrameOutput(WebSocketDuplex owner) {
                this.owner = owner;
            }

            @Override
            public void write(int b) throws IOException {
                write(new byte[]{(byte) b}, 0, 1);
            }

            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                byte[] payload = new byte[len];
                System.arraycopy(b, off, payload, 0, len);
                owner.sendFrame(0x2, payload); // binary
            }
        }
    }
}
