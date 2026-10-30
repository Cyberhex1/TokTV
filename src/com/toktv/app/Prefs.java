package com.toktv.app;

import android.content.Context;
import android.content.SharedPreferences;

/** All user settings, stored in SharedPreferences. */
public final class Prefs {
    public static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";

    public static final String HOME_URL = "https://www.tiktok.com/foryou";

    // Live player modes
    public static final String PLAYER_WEB = "web";
    public static final String PLAYER_NATIVE = "native";

    // Option lists (value, label) used by the settings screen
    public static final String[][] PLAYER_OPTIONS = {
            {PLAYER_WEB, "Website player (chat, gifts, full page)"},
            {PLAYER_NATIVE, "Native Fire TV player (smoothest)"},
    };
    public static final String[][] QUALITY_OPTIONS = {
            {"auto", "Auto (best available)"},
            {"origin", "Original"},
            {"uhd", "Full HD"},
            {"hd", "HD"},
            {"sd", "SD"},
            {"ld", "Low (slow internet)"},
    };
    public static final String[][] SIZE_OPTIONS = {
            {"200", "Extra large"},
            {"175", "Large"},
            {"150", "Medium"},
            {"125", "Small"},
    };
    public static final String[][] ONOFF = {
            {"1", "On"},
            {"0", "Off"},
    };

    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getSharedPreferences("toktv", Context.MODE_PRIVATE);
    }

    public String get(String key, String def) {
        return sp.getString(key, def);
    }

    public void put(String key, String value) {
        sp.edit().putString(key, value).apply();
    }

    public String livePlayer() { return get("live_player", PLAYER_WEB); }
    public boolean nativeLive() { return PLAYER_NATIVE.equals(livePlayer()); }
    public String quality() { return get("quality", "auto"); }
    public int uiScale() {
        try { return Integer.parseInt(get("ui_scale", "150")); } catch (Exception e) { return 150; }
    }
    public boolean autoUnmute() { return "1".equals(get("auto_unmute", "1")); }
    public boolean nativeChat() { return "1".equals(get("native_chat", "1")); }
    public boolean hidePromos() { return "1".equals(get("hide_promos", "1")); }

    public static String labelFor(String[][] opts, String value) {
        for (String[] o : opts) if (o[0].equals(value)) return o[1];
        return opts[0][1];
    }
}
