package com.toktv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/** Pinned creators, recently watched LIVEs and the list sort order, saved in Prefs. */
public final class Picks {
    public static final String SORT_TIKTOK = "tiktok";
    public static final String SORT_RECENT = "recent";
    public static final String SORT_VIEWERS = "viewers";
    public static final String SORT_NAME = "name";
    public static final String[][] SORT_OPTIONS = {
            {SORT_TIKTOK, "TikTok's order"},
            {SORT_RECENT, "Recently watched"},
            {SORT_VIEWERS, "Most viewers"},
            {SORT_NAME, "Name A–Z"},
    };
    private static final int MAX_RECENT = 200;

    private final Prefs prefs;

    public Picks(Prefs prefs) { this.prefs = prefs; }

    // ---------------------------------------------------------------- Pins

    public List<String> pinned() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.get("pinned", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) { }
        return out;
    }

    public boolean isPinned(String user) {
        for (String u : pinned()) if (u.equalsIgnoreCase(user)) return true;
        return false;
    }

    /** Returns true if the user is now pinned. */
    public boolean togglePin(String user) {
        List<String> p = pinned();
        boolean removed = false;
        for (Iterator<String> it = p.iterator(); it.hasNext(); ) {
            if (it.next().equalsIgnoreCase(user)) { it.remove(); removed = true; }
        }
        if (!removed) p.add(user);
        prefs.put("pinned", new JSONArray(p).toString());
        return !removed;
    }

    // ---------------------------------------------------------------- Recently watched

    private JSONObject recent() {
        try { return new JSONObject(prefs.get("recent", "{}")); } catch (Exception e) { return new JSONObject(); }
    }

    public long lastWatched(String user) {
        return user == null ? 0 : recent().optLong(user.toLowerCase(), 0);
    }

    public void markWatched(String user) {
        if (user == null) return;
        JSONObject r = recent();
        try {
            r.put(user.toLowerCase(), System.currentTimeMillis());
            if (r.length() > MAX_RECENT) {
                // Drop the oldest entries.
                List<String> keys = new ArrayList<>();
                for (Iterator<String> it = r.keys(); it.hasNext(); ) keys.add(it.next());
                final JSONObject fr = r;
                Collections.sort(keys, new Comparator<String>() {
                    @Override
                    public int compare(String a, String b) { return Long.compare(fr.optLong(a), fr.optLong(b)); }
                });
                for (int i = 0; i < keys.size() - MAX_RECENT; i++) r.remove(keys.get(i));
            }
        } catch (Exception ignored) { }
        prefs.put("recent", r.toString());
    }

    // ---------------------------------------------------------------- Last seen LIVE

    /** Remembers that these creators were LIVE now (for "last LIVE 2 h ago" on offline pins). */
    public void markSeenLive(List<String> users) {
        JSONObject r;
        try { r = new JSONObject(prefs.get("last_live", "{}")); } catch (Exception e) { r = new JSONObject(); }
        long now = System.currentTimeMillis();
        try {
            for (String u : users) r.put(u.toLowerCase(), now);
            if (r.length() > 400) {
                List<String> keys = new ArrayList<>();
                for (Iterator<String> it = r.keys(); it.hasNext(); ) keys.add(it.next());
                final JSONObject fr = r;
                Collections.sort(keys, new Comparator<String>() {
                    @Override
                    public int compare(String a, String b) { return Long.compare(fr.optLong(a), fr.optLong(b)); }
                });
                for (int i = 0; i < keys.size() - 400; i++) r.remove(keys.get(i));
            }
        } catch (Exception ignored) { }
        prefs.put("last_live", r.toString());
    }

    public long lastSeenLive(String user) {
        try { return new JSONObject(prefs.get("last_live", "{}")).optLong(user.toLowerCase(), 0); } catch (Exception e) { return 0; }
    }

    public static String ago(long t) {
        long m = (System.currentTimeMillis() - t) / 60000;
        if (m < 1) return "just now";
        if (m < 60) return m + " min ago";
        long h = m / 60;
        if (h < 24) return h + " h ago";
        long d = h / 24;
        return d == 1 ? "yesterday" : d + " days ago";
    }

    // ---------------------------------------------------------------- Sorting

    public String sort() { return prefs.get("sort", SORT_TIKTOK); }

    public void setSort(String s) { prefs.put("sort", s); }

    /** A sorted copy. TikTok's order is the tie-breaker for every sort. */
    public List<Feed.Room> sorted(List<Feed.Room> rooms) {
        final List<Feed.Room> base = new ArrayList<>(rooms);
        List<Feed.Room> out = new ArrayList<>(rooms);
        final String s = sort();
        if (SORT_TIKTOK.equals(s)) return out;
        final JSONObject rec = recent();
        Collections.sort(out, new Comparator<Feed.Room>() {
            @Override
            public int compare(Feed.Room a, Feed.Room b) {
                int c = 0;
                if (SORT_RECENT.equals(s)) {
                    c = Long.compare(rec.optLong(b.user.toLowerCase(), 0), rec.optLong(a.user.toLowerCase(), 0));
                } else if (SORT_VIEWERS.equals(s)) {
                    c = Long.compare(b.viewers, a.viewers);
                } else if (SORT_NAME.equals(s)) {
                    c = a.nick.compareToIgnoreCase(b.nick);
                }
                return c != 0 ? c : Integer.compare(base.indexOf(a), base.indexOf(b));
            }
        });
        return out;
    }
}
