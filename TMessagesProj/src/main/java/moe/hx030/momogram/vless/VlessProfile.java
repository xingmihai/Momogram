/*
 * Momogram - VLESS support (pure Java, self contained)
 *
 * This file is part of Momogram.
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package moe.hx030.momogram.vless;

import android.net.Uri;
import android.text.TextUtils;

import org.json.JSONObject;

import java.util.Locale;

/**
 * A single VLESS outbound profile, parsed from a standard {@code vless://} share link.
 *
 * Link shape (Project X / Xray share-link convention):
 *   vless://UUID@host:port?encryption=none&security=tls&type=ws&path=/&host=...&sni=...#Name
 */
public class VlessProfile {

    public static final String TRANSPORT_TCP = "tcp";
    public static final String TRANSPORT_WS = "ws";
    public static final String SECURITY_NONE = "none";
    public static final String SECURITY_TLS = "tls";
    public static final String SECURITY_REALITY = "reality";

    public String id;
    public String name;
    public String uuid;
    public String address;
    public int port;

    public String encryption = "none";
    public String flow = "";
    public String type = TRANSPORT_TCP;
    public String security = SECURITY_NONE;
    public String sni = "";
    public String fp = "";
    public String alpn = "";
    public String pbk = "";
    public String sid = "";
    public String spx = "";
    public String path = "/";
    public String host = "";
    public String serviceName = "";
    public boolean allowInsecure = false;

    private static String nz(String v) {
        return v == null ? "" : v;
    }

    private static String param(Uri u, String key, String fallback) {
        String v = u.getQueryParameter(key);
        return TextUtils.isEmpty(v) ? fallback : v;
    }

    /** Parse a vless:// link. Throws IllegalArgumentException with a readable reason. */
    public static VlessProfile parse(String link) {
        if (link == null || link.trim().isEmpty()) {
            throw new IllegalArgumentException("链接为空");
        }
        link = link.trim();
        if (!link.regionMatches(true, 0, "vless://", 0, 8)) {
            throw new IllegalArgumentException("不是 vless:// 链接");
        }

        Uri u = Uri.parse(link);
        VlessProfile p = new VlessProfile();
        p.uuid = nz(u.getUserInfo());
        p.address = nz(u.getHost());
        p.port = u.getPort();

        p.encryption = param(u, "encryption", "none").toLowerCase(Locale.US);
        p.flow = nz(u.getQueryParameter("flow"));
        p.type = param(u, "type", TRANSPORT_TCP).toLowerCase(Locale.US);
        p.security = param(u, "security", SECURITY_NONE).toLowerCase(Locale.US);
        p.sni = nz(u.getQueryParameter("sni"));
        p.fp = nz(u.getQueryParameter("fp"));
        p.alpn = nz(u.getQueryParameter("alpn"));
        p.pbk = nz(u.getQueryParameter("pbk"));
        p.sid = nz(u.getQueryParameter("sid"));
        p.spx = nz(u.getQueryParameter("spx"));
        p.path = param(u, "path", "/");
        p.host = param(u, "host", p.address);
        p.serviceName = nz(u.getQueryParameter("serviceName"));
        String insecure = nz(u.getQueryParameter("allowInsecure"));
        p.allowInsecure = "1".equals(insecure) || "true".equalsIgnoreCase(insecure);

        if (TextUtils.isEmpty(p.sni)) {
            p.sni = TextUtils.isEmpty(p.host) ? p.address : p.host;
        }
        if (!p.path.startsWith("/")) {
            p.path = "/" + p.path;
        }

        String frag = u.getFragment();
        p.name = TextUtils.isEmpty(frag) ? (p.address + ":" + p.port) : Uri.decode(frag);
        p.id = Integer.toHexString((p.uuid + "@" + p.address + ":" + p.port
                + "?" + p.type + "|" + p.security + "|" + p.path).hashCode());
        return p;
    }

    /** Basic shape validation, independent of whether the pure-Java stack can dial it. */
    public String validate() {
        if (TextUtils.isEmpty(address)) {
            return "缺少服务器地址";
        }
        if (port <= 0 || port > 65535) {
            return "端口无效";
        }
        if (uuidBytes().length != 16) {
            return "UUID 无效";
        }
        if (!"none".equals(encryption)) {
            return "仅支持 encryption=none";
        }
        return null;
    }

    /**
     * Return a human readable reason why this profile cannot be dialed by the built-in
     * pure-Java stack, or {@code null} when it is dialable.
     *
     * Reality needs a forged TLS ClientHello fingerprint (uTLS) and XTLS Vision needs
     * awareness of the inner TLS record layer. Neither can be reproduced with the
     * platform SSLSocket, so those profiles are stored/exported but not dialed here.
     */
    public String unsupportedReason() {
        if (SECURITY_REALITY.equals(security)) {
            return "REALITY 需要 uTLS 伪造 ClientHello 指纹（fp=" + (TextUtils.isEmpty(fp) ? "chrome" : fp)
                    + "），纯 Java 内核无法实现；请导出 sing-box 配置后用 v2rayNG / NekoBox 连接";
        }
        if (!TextUtils.isEmpty(flow)) {
            return "XTLS flow（" + flow + "）需要 TLS 记录层处理，纯 Java 内核不支持；"
                    + "请导出 sing-box 配置后用外部内核连接";
        }
        if (!TRANSPORT_TCP.equals(type) && !TRANSPORT_WS.equals(type)) {
            return "仅支持 tcp / ws 传输，当前 type=" + type;
        }
        if (!SECURITY_NONE.equals(security) && !SECURITY_TLS.equals(security)) {
            return "仅支持 security=none / tls，当前 security=" + security;
        }
        return null;
    }

    public byte[] uuidBytes() {
        String hex = uuid == null ? "" : uuid.replace("-", "").trim();
        if (hex.length() != 32) {
            return new byte[0];
        }
        byte[] out = new byte[16];
        try {
            for (int i = 0; i < 16; i++) {
                out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
            }
        } catch (Exception e) {
            return new byte[0];
        }
        return out;
    }

    public boolean useTls() {
        return SECURITY_TLS.equals(security);
    }

    public String displayName() {
        return TextUtils.isEmpty(name) ? (address + ":" + port) : name;
    }

    public String describe() {
        return String.format(Locale.US, "%s:%d  ·  %s / %s%s", address, port, type, security,
                TextUtils.isEmpty(flow) ? "" : " / " + flow);
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("uuid", uuid);
            o.put("address", address);
            o.put("port", port);
            o.put("encryption", encryption);
            o.put("flow", flow);
            o.put("type", type);
            o.put("security", security);
            o.put("sni", sni);
            o.put("fp", fp);
            o.put("alpn", alpn);
            o.put("pbk", pbk);
            o.put("sid", sid);
            o.put("spx", spx);
            o.put("path", path);
            o.put("host", host);
            o.put("serviceName", serviceName);
            o.put("allowInsecure", allowInsecure);
        } catch (Exception ignored) {
        }
        return o;
    }

    public static VlessProfile fromJson(JSONObject o) {
        VlessProfile p = new VlessProfile();
        p.id = o.optString("id");
        p.name = o.optString("name");
        p.uuid = o.optString("uuid");
        p.address = o.optString("address");
        p.port = o.optInt("port");
        p.encryption = o.optString("encryption", "none");
        p.flow = o.optString("flow", "");
        p.type = o.optString("type", TRANSPORT_TCP);
        p.security = o.optString("security", SECURITY_NONE);
        p.sni = o.optString("sni", "");
        p.fp = o.optString("fp", "");
        p.alpn = o.optString("alpn", "");
        p.pbk = o.optString("pbk", "");
        p.sid = o.optString("sid", "");
        p.spx = o.optString("spx", "");
        p.path = o.optString("path", "/");
        p.host = o.optString("host", "");
        p.serviceName = o.optString("serviceName", "");
        p.allowInsecure = o.optBoolean("allowInsecure", false);
        if (TextUtils.isEmpty(p.id)) {
            p.id = Integer.toHexString((p.uuid + "@" + p.address + ":" + p.port).hashCode());
        }
        return p;
    }

    /** Rebuild a share link so a stored profile can be copied/exported again. */
    public String toLink() {
        StringBuilder sb = new StringBuilder("vless://").append(uuid).append("@");
        if (address != null && address.indexOf(':') >= 0 && !address.startsWith("[")) {
            sb.append('[').append(address).append(']');
        } else {
            sb.append(address);
        }
        sb.append(':').append(port);
        sb.append("?encryption=").append(encryption);
        sb.append("&type=").append(type);
        sb.append("&security=").append(security);
        if (!TextUtils.isEmpty(flow)) sb.append("&flow=").append(flow);
        if (!TextUtils.isEmpty(sni)) sb.append("&sni=").append(sni);
        if (!TextUtils.isEmpty(fp)) sb.append("&fp=").append(fp);
        if (!TextUtils.isEmpty(alpn)) sb.append("&alpn=").append(alpn);
        if (!TextUtils.isEmpty(pbk)) sb.append("&pbk=").append(pbk);
        if (!TextUtils.isEmpty(sid)) sb.append("&sid=").append(sid);
        if (!TextUtils.isEmpty(path) && !"/".equals(path)) sb.append("&path=").append(path);
        if (!TextUtils.isEmpty(host) && !host.equals(address)) sb.append("&host=").append(host);
        if (allowInsecure) sb.append("&allowInsecure=1");
        if (!TextUtils.isEmpty(name)) sb.append('#').append(Uri.encode(name));
        return sb.toString();
    }
}
