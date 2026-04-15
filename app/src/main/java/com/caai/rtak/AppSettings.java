package com.caai.rtak;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.preference.PreferenceManager;

public final class AppSettings {

    private AppSettings() {}

    public static final String KEY_TAK_PORT = "tak_port";
    public static final String KEY_TAK_AUTO_START = "tak_auto_start";

    public static final String KEY_RNS_TRANSPORT = "rns_transport";
    public static final String KEY_RNS_AUTO_ANNOUNCE = "rns_auto_announce";
    public static final String KEY_RNS_ANNOUNCE_INTERVAL = "rns_announce_interval";
    public static final String KEY_DEBUG_VERBOSE = "debug_verbose";

    public static final String KEY_IFAC_ENABLED = "ifac_enabled";
    public static final String KEY_IFAC_NETNAME = "ifac_netname";
    public static final String KEY_IFAC_NETKEY = "ifac_netkey";

    private static SharedPreferences prefs(Context context) {
        return PreferenceManager.getDefaultSharedPreferences(
                context.getApplicationContext());
    }

    public static int getTakPort(Context context) {
        String raw = prefs(context).getString(KEY_TAK_PORT, "8087");
        try {
            int port = Integer.parseInt(raw);
            return (port >= 1 && port <= 65535) ? port : 8087;
        } catch (Exception e) {
            return 8087;
        }
    }

    public static boolean getTakAutoStart(Context context) {
        return prefs(context).getBoolean(KEY_TAK_AUTO_START, false);
    }

    public static boolean getRnsTransport(Context context) {
        return prefs(context).getBoolean(KEY_RNS_TRANSPORT, true);
    }

    public static boolean getRnsAutoAnnounce(Context context) {
        return prefs(context).getBoolean(KEY_RNS_AUTO_ANNOUNCE, true);
    }

    public static int getRnsAnnounceInterval(Context context) {
        String raw = prefs(context).getString(KEY_RNS_ANNOUNCE_INTERVAL, "300");
        try {
            int v = Integer.parseInt(raw);
            return Math.max(v, 0);
        } catch (Exception e) {
            return 300;
        }
    }

    public static boolean getDebugVerbose(Context context) {
        return prefs(context).getBoolean(KEY_DEBUG_VERBOSE, false);
    }

    public static boolean getIfacEnabled(Context context) {
        return prefs(context).getBoolean(KEY_IFAC_ENABLED, false);
    }

    public static String getIfacNetName(Context context) {
        String v = prefs(context).getString(KEY_IFAC_NETNAME, "");
        return v == null ? "" : v.trim();
    }

    public static String getIfacNetKey(Context context) {
        String v = prefs(context).getString(KEY_IFAC_NETKEY, "");
        return v == null ? "" : v.trim();
    }
}