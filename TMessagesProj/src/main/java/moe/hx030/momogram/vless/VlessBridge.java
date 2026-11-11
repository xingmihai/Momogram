/*
 * Momogram - VLESS support (pure Java, self contained)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package moe.hx030.momogram.vless;

import android.content.Context;
import android.content.SharedPreferences;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A tiny local SOCKS5 server that translates tgnet's SOCKS5 connections into
 * VLESS connections. tgnet keeps using its native SOCKS5 code path, so nothing
 * in the native layer has to change.
 *
 * Reality / XTLS profiles are deliberately not dialed here (see
 * {@link VlessProfile#unsupportedReason()}); they can be exported instead.
 */
public final class VlessBridge {

    private static final String LOCAL_ADDRESS = "127.0.0.1";
    private static final int PORT_ATTEMPTS = 16;

    private static volatile ServerSocket serverSocket;
    private static volatile VlessProfile activeProfile;
    private static volatile int boundPort = -1;
    private static volatile String lastError;
    private static volatile Thread acceptThread;

    private VlessBridge() {
    }

    public static boolean isRunning() {
        return serverSocket != null && !serverSocket.isClosed();
    }

    public static int getPort() {
        return boundPort;
    }

    public static String getLastError() {
        return lastError;
    }

    public static VlessProfile getRunningProfile() {
        return activeProfile;
    }

    /** Bind the local SOCKS5 listener. Reuses the already running bridge. */
    public static synchronized boolean start(Context ctx, VlessProfile profile) {
        if (profile == null) {
            lastError = "未选择配置";
            return false;
        }
        String reason = profile.unsupportedReason();
        if (reason != null) {
            lastError = reason;
            return false;
        }
        String invalid = profile.validate();
        if (invalid != null) {
            lastError = invalid;
            return false;
        }
        if (isRunning() && activeProfile != null && activeProfile.id.equals(profile.id)) {
            return true;
        }
        stop();

        int base = VlessStore.localPort(ctx);
        ServerSocket bound = null;
        for (int i = 0; i < PORT_ATTEMPTS; i++) {
            int candidate = base + i;
            if (candidate > 65535) break;
            try {
                bound = new ServerSocket(candidate, 64, InetAddress.getByName(LOCAL_ADDRESS));
                boundPort = candidate;
                break;
            } catch (Exception ignored) {
            }
        }
        if (bound == null) {
            lastError = "本地端口 " + base + " 起的 16 个端口都被占用";
            return false;
        }

        serverSocket = bound;
        activeProfile = profile;
        VlessStore.setLocalPort(ctx, boundPort);
        lastError = null;

        final ServerSocket finalServer = bound;
        final VlessProfile finalProfile = profile;
        acceptThread = new Thread(() -> acceptLoop(finalServer, finalProfile), "momo-vless-socks");
        acceptThread.setDaemon(true);
        acceptThread.start();
        return true;
    }

    public static synchronized void stop() {
        ServerSocket s = serverSocket;
        serverSocket = null;
        activeProfile = null;
        boundPort = -1;
        if (s != null) {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
        Thread t = acceptThread;
        acceptThread = null;
        if (t != null) {
            t.interrupt();
        }
    }

    private static void acceptLoop(ServerSocket server, VlessProfile profile) {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                client.setTcpNoDelay(true);
                Thread worker = new Thread(() -> handle(client, profile), "momo-vless-conn");
                worker.setDaemon(true);
                worker.start();
            } catch (Exception e) {
                if (!server.isClosed()) {
                    FileLog.e("vless accept", e);
                }
                break;
            }
        }
    }

    /** Make sure the bridge is up for the persisted active profile (app restart recovery). */
    public static void ensureStarted(Context ctx) {
        VlessProfile p = VlessStore.active(ctx);
        if (p == null || isRunning()) {
            return;
        }
        int previousPort = VlessStore.localPort(ctx);
        if (!start(ctx, p)) {
            return;
        }
        if (boundPort != previousPort) {
            // The old port was taken; point tgnet at the new one.
            applyTgnetProxy(ctx, p, boundPort, false);
        }
    }

    /** Enable a profile end-to-end: start the bridge and route tgnet through it. */
    public static synchronized boolean enable(Context ctx, VlessProfile profile) {
        if (!start(ctx, profile)) {
            return false;
        }
        VlessStore.setActiveId(ctx, profile.id);
        applyTgnetProxy(ctx, profile, boundPort, true);
        return true;
    }

    /** Disable VLESS, remove the injected local SOCKS5 entry and restore direct connection. */
    public static synchronized void disable(Context ctx) {
        int port = boundPort > 0 ? boundPort : VlessStore.localPort(ctx);
        stop();
        VlessStore.setActiveId(ctx, null);
        try {
            SharedConfig.setProxyEnable(false);
            for (SharedConfig.ProxyInfo info : new ArrayList<>(SharedConfig.proxyList)) {
                if (info != null && info.settings != null
                        && LOCAL_ADDRESS.equals(info.settings.getAddress())
                        && info.settings.getPort() == port) {
                    SharedConfig.deleteProxy(info);
                }
            }
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static void applyTgnetProxy(Context ctx, VlessProfile profile, int port, boolean notify) {
        try {
            ProxySettings socks = ProxySettings.builder()
                    .setType(ProxySettings.Type.SOCKS5)
                    .setAddress(LOCAL_ADDRESS)
                    .setPort(port)
                    .build();
            SharedConfig.ProxyInfo info = SharedConfig.addProxy(new SharedConfig.ProxyInfo(socks));
            info.setRemarks("VLESS · " + profile.displayName());
            SharedConfig.currentProxy = info;

            SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
            info.settings.toSharedPreferences(editor);
            editor.putBoolean("proxy_enabled", true);
            editor.commit();

            ConnectionsManager.setProxySettings(true, info.settings);
            if (notify) {
                NotificationCenter.getGlobalInstance()
                        .postNotificationName(NotificationCenter.proxySettingsChanged);
            }
        } catch (Throwable t) {
            FileLog.e(t);
            lastError = String.valueOf(t.getMessage());
        }
    }

    // ---------------------------------------------------------------------
    // SOCKS5 -> VLESS connection handling
    // ---------------------------------------------------------------------

    private static void handle(Socket client, VlessProfile profile) {
        AtomicBoolean closed = new AtomicBoolean(false);
        VlessTransport.Duplex remote = null;
        try {
            DataInputStream cin = new DataInputStream(client.getInputStream());
            OutputStream cout = client.getOutputStream();

            int ver = cin.read();
            int methods = cin.read();
            if (ver != 5 || methods < 0) {
                return;
            }
            byte[] methodList = new byte[methods];
            cin.readFully(methodList);
            cout.write(VlessCodec.socksMethodReply());
            cout.flush();

            int rver = cin.read();
            int cmd = cin.read();
            cin.read(); // RSV
            int atyp = cin.read();
            if (rver != 5 || cmd != 0x01) {
                cout.write(VlessCodec.socksFailureReply());
                cout.flush();
                return;
            }

            String targetHost;
            if (atyp == 0x01) {
                byte[] b = new byte[4];
                cin.readFully(b);
                targetHost = (b[0] & 0xff) + "." + (b[1] & 0xff) + "." + (b[2] & 0xff) + "." + (b[3] & 0xff);
            } else if (atyp == 0x03) {
                int len = cin.readUnsignedByte();
                byte[] b = new byte[len];
                cin.readFully(b);
                targetHost = new String(b, StandardCharsets.UTF_8);
            } else if (atyp == 0x04) {
                byte[] b = new byte[16];
                cin.readFully(b);
                targetHost = InetAddress.getByAddress(b).getHostAddress();
            } else {
                cout.write(VlessCodec.socksFailureReply());
                cout.flush();
                return;
            }
            int targetPort = cin.readUnsignedShort();

            remote = VlessTransport.open(profile);
            OutputStream rout = remote.output();
            InputStream rin = remote.input();

            rout.write(VlessCodec.buildRequestHeader(profile.uuidBytes(), targetHost, targetPort));
            rout.flush();
            VlessCodec.readResponseHeader(rin);

            cout.write(VlessCodec.socksConnectReply());
            cout.flush();

            final VlessTransport.Duplex remoteRef = remote;
            final InputStream rinRef = rin;
            final OutputStream routRef = rout;
            final DataInputStream cinRef = cin;
            final OutputStream coutRef = cout;
            final Socket clientRef = client;

            Runnable shutdown = () -> {
                if (closed.compareAndSet(false, true)) {
                    try {
                        remoteRef.close();
                    } catch (Exception ignored) {
                    }
                    try {
                        clientRef.close();
                    } catch (Exception ignored) {
                    }
                }
            };

            Thread upstream = new Thread(() -> {
                try {
                    pump(cinRef, routRef);
                } catch (Exception ignored) {
                } finally {
                    shutdown.run();
                }
            }, "momo-vless-up");
            upstream.setDaemon(true);
            upstream.start();

            pump(rinRef, coutRef);
            shutdown.run();
        } catch (Exception e) {
            if (!closed.get()) {
                FileLog.e("vless connection: " + e.getMessage());
                lastError = e.getMessage();
            }
        } finally {
            if (remote != null) {
                try {
                    remote.close();
                } catch (Exception ignored) {
                }
            }
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    private static void pump(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) >= 0) {
            if (n == 0) continue;
            out.write(buf, 0, n);
            out.flush();
        }
    }
}
