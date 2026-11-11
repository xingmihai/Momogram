/*
 * Momogram - VLESS support (pure Java, self contained)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package moe.hx030.momogram.vless;

import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

/** Export helpers: sing-box config for Reality/XTLS nodes, and the plain share link. */
public final class VlessExport {

    private VlessExport() {
    }

    public static String link(VlessProfile p) {
        return p.toLink();
    }

    /**
     * Produce a self-contained sing-box config (socks inbound + vless outbound).
     * Config keys follow the sing-box 1.1x outbound schema; external clients can
     * dial REALITY / XTLS Vision nodes that the built-in Java stack cannot.
     */
    public static String singBox(VlessProfile p) {
        try {
            JSONObject root = new JSONObject();

            JSONObject dns = new JSONObject();
            dns.put("servers", new JSONArray().put(new JSONObject()
                    .put("type", "tls")
                    .put("server", "1.1.1.1")
                    .put("server_port", 853)));
            root.put("dns", dns);

            JSONObject inbound = new JSONObject();
            inbound.put("type", "socks");
            inbound.put("tag", "socks-in");
            inbound.put("listen", "127.0.0.1");
            inbound.put("listen_port", 10808);
            root.put("inbounds", new JSONArray().put(inbound));

            JSONObject out = new JSONObject();
            out.put("type", "vless");
            out.put("tag", "proxy");
            out.put("server", p.address);
            out.put("server_port", p.port);
            out.put("uuid", p.uuid);
            if (!TextUtils.isEmpty(p.flow)) {
                out.put("flow", p.flow);
            }

            JSONObject transport = new JSONObject();
            if (VlessProfile.TRANSPORT_WS.equals(p.type)) {
                transport.put("type", "ws");
                transport.put("path", TextUtils.isEmpty(p.path) ? "/" : p.path);
                JSONObject headers = new JSONObject();
                headers.put("Host", TextUtils.isEmpty(p.host) ? p.address : p.host);
                transport.put("headers", headers);
            } else {
                transport.put("type", "tcp");
            }
            out.put("transport", transport);

            boolean tls = p.useTls() || VlessProfile.SECURITY_REALITY.equals(p.security);
            if (tls) {
                JSONObject tlsObj = new JSONObject();
                tlsObj.put("enabled", true);
                tlsObj.put("server_name", TextUtils.isEmpty(p.sni) ? p.address : p.sni);
                tlsObj.put("insecure", p.allowInsecure);
                if (!TextUtils.isEmpty(p.alpn)) {
                    JSONArray alpn = new JSONArray();
                    for (String part : p.alpn.split(",")) {
                        if (!TextUtils.isEmpty(part.trim())) alpn.put(part.trim());
                    }
                    tlsObj.put("alpn", alpn);
                }
                if (!TextUtils.isEmpty(p.fp)) {
                    tlsObj.put("utls", new JSONObject()
                            .put("enabled", true)
                            .put("fingerprint", p.fp));
                }
                if (VlessProfile.SECURITY_REALITY.equals(p.security)) {
                    JSONObject reality = new JSONObject();
                    reality.put("enabled", true);
                    reality.put("public_key", p.pbk);
                    reality.put("short_id", p.sid);
                    if (!TextUtils.isEmpty(p.spx)) reality.put("spider_x", p.spx);
                    tlsObj.put("reality", reality);
                }
                out.put("tls", tlsObj);
            }

            JSONObject routing = new JSONObject();
            routing.put("rules", new JSONArray().put(new JSONObject()
                    .put("inbound", new JSONArray().put("socks-in"))
                    .put("outbound", "proxy")));
            root.put("outbounds", new JSONArray().put(out));
            root.put("routing", routing);
            return root.toString(2);
        } catch (Exception e) {
            return "{\"error\": \"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}";
        }
    }
}
