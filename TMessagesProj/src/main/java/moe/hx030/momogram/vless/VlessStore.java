/*
 * Momogram - VLESS support (pure Java, self contained)
 * SPDX-License-Identifier: GPL-3.0-or-later
 */
package moe.hx030.momogram.vless;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Persistence for VLESS profiles. Kept separate from SharedConfig.proxyList on purpose. */
public final class VlessStore {

    private static final String PREF = "momo_vless";
    private static final String KEY_PROFILES = "profiles";
    private static final String KEY_ACTIVE = "active_id";
    private static final String KEY_PORT = "local_port";
    public static final int DEFAULT_PORT = 10809;

    private VlessStore() {
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    public static List<VlessProfile> list(Context ctx) {
        List<VlessProfile> out = new ArrayList<>();
        String raw = prefs(ctx).getString(KEY_PROFILES, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                out.add(VlessProfile.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void saveAll(Context ctx, List<VlessProfile> list) {
        JSONArray arr = new JSONArray();
        for (VlessProfile p : list) {
            arr.put(p.toJson());
        }
        prefs(ctx).edit().putString(KEY_PROFILES, arr.toString()).apply();
    }

    /** Insert or replace by id, returns true when it is a new profile. */
    public static boolean add(Context ctx, VlessProfile profile) {
        List<VlessProfile> list = list(ctx);
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(profile.id)) {
                list.set(i, profile);
                saveAll(ctx, list);
                return false;
            }
        }
        list.add(0, profile);
        saveAll(ctx, list);
        return true;
    }

    public static void remove(Context ctx, String id) {
        List<VlessProfile> list = list(ctx);
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
            }
        }
        saveAll(ctx, list);
        if (id.equals(activeId(ctx))) {
            setActiveId(ctx, null);
        }
    }

    public static VlessProfile find(Context ctx, String id) {
        if (id == null) return null;
        for (VlessProfile p : list(ctx)) {
            if (id.equals(p.id)) return p;
        }
        return null;
    }

    public static String activeId(Context ctx) {
        return prefs(ctx).getString(KEY_ACTIVE, null);
    }

    public static void setActiveId(Context ctx, String id) {
        SharedPreferences.Editor e = prefs(ctx).edit();
        if (id == null) {
            e.remove(KEY_ACTIVE);
        } else {
            e.putString(KEY_ACTIVE, id);
        }
        e.apply();
    }

    public static VlessProfile active(Context ctx) {
        return find(ctx, activeId(ctx));
    }

    public static int localPort(Context ctx) {
        int p = prefs(ctx).getInt(KEY_PORT, DEFAULT_PORT);
        return p > 0 && p <= 65535 ? p : DEFAULT_PORT;
    }

    public static void setLocalPort(Context ctx, int port) {
        if (port > 0 && port <= 65535) {
            prefs(ctx).edit().putInt(KEY_PORT, port).apply();
        }
    }
}
