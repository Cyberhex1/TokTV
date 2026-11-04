package com.toktv.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** LIVE rooms read from TikTok's LIVE feed responses, kept in memory for the home screen and player. */
public final class Feed {
    public static final class Room {
        public String user, nick, title, avatar, cover, roomId, streamData, hls;
        public boolean live = true;
        public long followers;
        public final long at = System.currentTimeMillis();
        public long viewers;
    }

    public static final List<Room> following = new ArrayList<>();
    public static final List<Room> suggested = new ArrayList<>();
    private static final Map<String, Room> byUser = new HashMap<>();
    public static long updatedAt;

    private Feed() { }

    public static synchronized Room find(String user) {
        if (user == null) return null;
        Room r = byUser.get(user.toLowerCase());
        return r != null ? r : extra.get(user.toLowerCase());
    }

    /** Only rooms in the Following/Suggested lists (not search results). */
    public static synchronized Room findListed(String user) {
        return user == null ? null : byUser.get(user.toLowerCase());
    }

    public static synchronized void set(List<Room> follow, List<Room> sugg) {
        if (follow != null) {
            following.clear();
            following.addAll(follow);
        }
        if (sugg != null) {
            suggested.clear();
            // Don't repeat people already in Following.
            for (Room r : sugg) if (!contains(following, r.user)) suggested.add(r);
        }
        byUser.clear();
        for (Room r : suggested) byUser.put(r.user.toLowerCase(), r);
        for (Room r : following) byUser.put(r.user.toLowerCase(), r);
        updatedAt = System.currentTimeMillis();
    }

    private static boolean contains(List<Room> l, String user) {
        for (Room r : l) if (r.user.equalsIgnoreCase(user)) return true;
        return false;
    }

    /** All usernames in display order (Following first), for Up/Down in the player. */
    public static synchronized JSONArray allUsers() {
        JSONArray a = new JSONArray();
        for (Room r : following) a.put(r.user);
        for (Room r : suggested) a.put(r.user);
        return a;
    }

    /** Parses a /webcast/feed/ response: {data:[{type, data:{room}}...]}. Returns null if it isn't one. */
    public static List<Room> parse(String body) {
        try {
            JSONObject o = new JSONObject(body);
            JSONArray data = o.optJSONArray("data");
            List<Room> out = new ArrayList<>();
            if (data == null) return o.has("status_code") ? out : null;
            for (int i = 0; i < data.length(); i++) {
                JSONObject item = data.optJSONObject(i);
                Room r = parseRoom(item == null ? null : item.optJSONObject("data"));
                if (r != null && !contains(out, r.user)) out.add(r);
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    /** One LIVE room object (as found in the LIVE feeds and in LIVE search results). */
    public static Room parseRoom(JSONObject room) {
        if (room == null) return null;
        JSONObject owner = room.optJSONObject("owner");
        if (owner == null) return null;
        Room r = new Room();
        r.user = owner.optString("display_id", "");
        if (r.user.length() == 0) return null;
        r.nick = owner.optString("nickname", r.user);
        r.title = room.optString("title", "");
        r.viewers = room.optLong("user_count", 0);
        r.roomId = room.optString("id_str", "");
        r.avatar = pickUrl(owner.optJSONObject("avatar_thumb"));
        r.cover = pickUrl(room.optJSONObject("cover"));
        JSONObject su = room.optJSONObject("stream_url");
        if (su != null) {
            JSONObject core = su.optJSONObject("live_core_sdk_data");
            JSONObject pull = core == null ? null : core.optJSONObject("pull_data");
            if (pull != null) r.streamData = pull.optString("stream_data", null);
            JSONObject map = su.optJSONObject("hls_pull_url_map");
            if (map != null) {
                for (String k : new String[]{"FULL_HD1", "HD1", "SD1", "SD2"}) {
                    String u = map.optString(k, "");
                    if (u.startsWith("http")) { r.hls = u; break; }
                }
            }
            if (r.hls == null && su.optString("hls_pull_url", "").startsWith("http")) r.hls = su.optString("hls_pull_url");
        }
        return r;
    }

    /** Makes rooms found elsewhere (search) playable by name without adding them to the lists. */
    public static synchronized void remember(List<Room> rooms) {
        for (Room r : rooms) {
            String k = r.user.toLowerCase();
            if (!byUser.containsKey(k) || extra.containsKey(k)) extra.put(k, r);
        }
    }

    private static final Map<String, Room> extra = new HashMap<>();

    private static String pickUrl(JSONObject img) {
        if (img == null) return null;
        JSONArray l = img.optJSONArray("url_list");
        if (l == null) return null;
        String first = null;
        for (int i = 0; i < l.length(); i++) {
            String u = l.optString(i, "");
            if (!u.startsWith("http")) continue;
            if (first == null) first = u;
            if (!u.contains(".heic") && !u.contains("heif")) return u;
        }
        return first;
    }
}
