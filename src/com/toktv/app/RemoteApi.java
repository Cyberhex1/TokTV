package com.toktv.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.view.KeyEvent;
import android.webkit.ValueCallback;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** The commands TokTV Remote (the phone page) can send. Runs on the server's threads. */
final class RemoteApi {
    private static String pageCache;

    private RemoteApi() { }

    static String page(Context c) {
        if (pageCache == null) {
            try {
                InputStream in = c.getAssets().open("remote.html");
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                in.close();
                pageCache = out.toString("UTF-8");
            } catch (Exception e) {
                return "TokTV Remote page missing";
            }
        }
        return pageCache;
    }

    static String handle(final Context c, String method, String path, final JSONObject req) {
        try {
            switch (path) {
                case "state": return state(c).toString();
                case "lives": return lives(c).toString();
                case "chat": return chat(req.optLong("since", 0)).toString();
                case "key": return ok(Tv.press(keyCode(req.optString("key"))));
                case "watch": return watch(req.optString("user"));
                case "home": return home();
                case "search": return search(req.optString("q"));
                case "results": return results(c).toString();
                case "send": return pageAction("send", req.optString("text"));
                case "like": return pageAction("like", null);
                case "follow": return pageAction("follow", null);
                case "gifts": return pageAction("gifts", null);
                case "gift": return pageAction("gift", req.optString("name"));
                case "pin": return pin(c, req.optString("user"));
                case "chat_toggle": return liveCall("chat");
                case "quality": return liveCall("quality");
                case "next": return liveCall("next");
                case "prev": return liveCall("prev");
                case "mouse": return mouse(c);
                case "volume": return volume(c, req.optString("dir"));
                default: return "{\"error\":\"unknown\"}";
            }
        } catch (Exception e) {
            return "{\"error\":" + JSONObject.quote(String.valueOf(e.getMessage())) + "}";
        }
    }

    private static String ok(boolean b) {
        return b ? "{\"ok\":true}" : "{\"ok\":false}";
    }

    private static String err(String s) {
        return "{\"ok\":false,\"error\":" + JSONObject.quote(s) + "}";
    }

    // ---------------------------------------------------------------- Reading

    private static JSONObject state(Context c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("screen", Tv.screen());
        LiveActivity l = LiveActivity.current;
        if (l != null) {
            JSONObject live = new JSONObject();
            live.put("user", l.user());
            live.put("nick", l.curNick);
            live.put("title", l.curTitle);
            live.put("viewers", l.curViewers);
            live.put("pinned", new Picks(new Prefs(c)).isPinned(l.user()));
            live.put("chat", l.chat() != null && l.chat().isOpen());
            Feed.Room r = Feed.find(l.user());
            if (r != null) live.put("avatar", r.avatar);
            o.put("live", live);
        }
        o.put("mouse", "1".equals(new Prefs(c).get("mouse", "0")));
        o.put("searching", SearchActivity.searching);
        return o;
    }

    private static JSONArray rooms(List<Feed.Room> list, Picks picks) throws Exception {
        JSONArray a = new JSONArray();
        for (Feed.Room r : list) {
            JSONObject o = new JSONObject();
            o.put("user", r.user);
            o.put("nick", r.nick);
            o.put("title", r.title);
            o.put("viewers", r.viewers);
            o.put("avatar", r.avatar);
            o.put("live", r.live);
            o.put("followers", r.followers);
            o.put("pinned", picks.isPinned(r.user));
            a.put(o);
        }
        return a;
    }

    private static JSONObject lives(Context c) throws Exception {
        Picks picks = new Picks(new Prefs(c));
        JSONObject o = new JSONObject();
        JSONArray pins = new JSONArray();
        for (String u : picks.pinned()) {
            Feed.Room r = Feed.findListed(u);
            JSONObject p = new JSONObject();
            p.put("user", u);
            p.put("nick", r != null ? r.nick : u);
            p.put("live", r != null);
            if (r != null) {
                p.put("title", r.title);
                p.put("viewers", r.viewers);
                p.put("avatar", r.avatar);
            }
            p.put("pinned", true);
            pins.put(p);
        }
        o.put("pinned", pins);
        o.put("following", rooms(picks.sorted(Feed.following), picks));
        o.put("recommended", rooms(Feed.suggested, picks));
        o.put("gaming", rooms(Feed.gaming, picks));
        o.put("updatedAt", Feed.updatedAt);
        return o;
    }

    private static JSONObject chat(long since) throws Exception {
        JSONObject o = new JSONObject();
        LiveActivity l = LiveActivity.current;
        JSONArray lines = new JSONArray();
        if (l != null && l.chat() != null) {
            JSONArray all = l.chat().recentLines();
            for (int i = 0; i < all.length(); i++) {
                JSONObject x = all.getJSONObject(i);
                if (x.optLong("t") > since) lines.put(x);
            }
            o.put("open", l.chat().isOpen());
        }
        o.put("lines", lines);
        return o;
    }

    private static JSONObject results(Context c) throws Exception {
        Picks picks = new Picks(new Prefs(c));
        JSONObject o = new JSONObject();
        o.put("q", SearchActivity.lastQuery);
        o.put("searching", SearchActivity.searching);
        if (SearchActivity.lastLive != null) o.put("live", rooms(SearchActivity.lastLive, picks));
        if (SearchActivity.lastAccounts != null) o.put("accounts", rooms(SearchActivity.lastAccounts, picks));
        return o;
    }

    // ---------------------------------------------------------------- Actions

    private static int keyCode(String k) {
        switch (k) {
            case "up": return KeyEvent.KEYCODE_DPAD_UP;
            case "down": return KeyEvent.KEYCODE_DPAD_DOWN;
            case "left": return KeyEvent.KEYCODE_DPAD_LEFT;
            case "right": return KeyEvent.KEYCODE_DPAD_RIGHT;
            case "ok": return KeyEvent.KEYCODE_DPAD_CENTER;
            case "back": return KeyEvent.KEYCODE_BACK;
            case "menu": return KeyEvent.KEYCODE_MENU;
            case "playpause": return KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
            case "rew": return KeyEvent.KEYCODE_MEDIA_REWIND;
            case "ff": return KeyEvent.KEYCODE_MEDIA_FAST_FORWARD;
            default: return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    private static String watch(final String user) {
        if (user == null || user.isEmpty()) return err("no user");
        Boolean ok = Tv.onUi(new Tv.UiTask<Boolean>() {
            @Override
            public Boolean run() {
                LiveActivity l = LiveActivity.current;
                if (l != null) {
                    l.remoteWatch(user);
                    return true;
                }
                Activity a = Tv.top;
                if (a == null) return false;
                Intent i = new Intent(a, LiveActivity.class);
                i.putExtra("user", user);
                i.putExtra("lives", Feed.allUsers().toString());
                a.startActivity(i);
                return true;
            }
        }, 3000);
        return ok(ok != null && ok);
    }

    private static String home() {
        Boolean ok = Tv.onUi(new Tv.UiTask<Boolean>() {
            @Override
            public Boolean run() {
                Activity a = Tv.top;
                if (a == null) return false;
                Intent i = new Intent(a, HomeActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                a.startActivity(i);
                return true;
            }
        }, 3000);
        return ok(ok != null && ok);
    }

    private static String search(final String q) {
        if (q == null || q.trim().isEmpty()) return err("empty");
        Boolean ok = Tv.onUi(new Tv.UiTask<Boolean>() {
            @Override
            public Boolean run() {
                Activity a = Tv.top;
                if (a == null) return false;
                SearchActivity.searching = true;
                Intent i = new Intent(a, SearchActivity.class);
                i.putExtra("q", q.trim());
                if (a instanceof SearchActivity) a.finish();
                a.startActivity(i);
                return true;
            }
        }, 3000);
        return ok(ok != null && ok);
    }

    private static String pin(Context c, String user) {
        if (user == null || user.isEmpty()) {
            LiveActivity l = LiveActivity.current;
            if (l == null) return err("nothing playing");
            user = l.user();
        }
        boolean now = new Picks(new Prefs(c)).togglePin(user);
        return "{\"ok\":true,\"pinned\":" + now + "}";
    }

    private static String liveCall(final String what) {
        Boolean ok = Tv.onUi(new Tv.UiTask<Boolean>() {
            @Override
            public Boolean run() {
                LiveActivity l = LiveActivity.current;
                if (l == null) return false;
                if ("chat".equals(what)) l.remoteToggleChat();
                else if ("quality".equals(what)) l.remoteQuality();
                else if ("next".equals(what)) l.remoteSwitch(1);
                else if ("prev".equals(what)) l.remoteSwitch(-1);
                return true;
            }
        }, 3000);
        return ok != null && ok ? "{\"ok\":true}" : err("Open a LIVE on the TV first");
    }

    private static String mouse(final Context c) {
        Tv.onUi(new Tv.UiTask<Boolean>() {
            @Override
            public Boolean run() {
                Prefs p = new Prefs(c);
                boolean on = !"1".equals(p.get("mouse", "0"));
                p.put("mouse", on ? "1" : "0");
                Pointer ptr = Pointer.current();
                if (ptr != null) ptr.setOn(on);
                return true;
            }
        }, 3000);
        return "{\"ok\":true}";
    }

    private static String volume(Context c, String dir) {
        AudioManager am = (AudioManager) c.getSystemService(Context.AUDIO_SERVICE);
        if (am == null) return err("no audio");
        int d = "up".equals(dir) ? AudioManager.ADJUST_RAISE : "down".equals(dir) ? AudioManager.ADJUST_LOWER : AudioManager.ADJUST_TOGGLE_MUTE;
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, d, AudioManager.FLAG_SHOW_UI);
        return "{\"ok\":true}";
    }

    // ---------------------------------------------------------------- In the LIVE page (send, like, follow, gifts)

    private static final String JS_FOLLOW =
            "(function(){var c=document.querySelectorAll('button,[role=button]');for(var i=0;i<c.length;i++){var t=(c[i].innerText||'').trim();"
            + "if(t==='Follow'||t==='Follow back'){c[i].click();return 'ok';}}"
            + "for(var j=0;j<c.length;j++){var u=(c[j].innerText||'').trim();if(u==='Following'||u==='Friends')return 'already';}return 'none';})()";

    private static final String JS_GIFTS =
            "(function(){var g=document.querySelectorAll('[data-e2e=\"gift-item\"]');var a=[];for(var i=0;i<g.length;i++){"
            + "var p=(g[i].innerText||'').split('\\n');var img=g[i].querySelector('img');"
            + "a.push({name:(img&&img.alt)||p[0],coins:parseInt(p[p.length-1])||0,icon:img?img.src:''});}return JSON.stringify(a);})()";

    /** Clicks the named gift, then the page's Send button if it shows one. */
    private static String jsGift(String name) {
        return "(function(n){var g=document.querySelectorAll('[data-e2e=\"gift-item\"]');for(var i=0;i<g.length;i++){"
                + "var img=g[i].querySelector('img');var nm=(img&&img.alt)||(g[i].innerText||'').split('\\n')[0];if(nm===n){g[i].click();"
                + "setTimeout(function(){var b=document.querySelectorAll('button');for(var k=0;k<b.length;k++){"
                + "if(/^send$/i.test((b[k].innerText||'').trim())){b[k].click();break;}}},400);return 'ok';}}return 'none';})("
                + JSONObject.quote(name) + ")";
    }

    private static String pageAction(final String what, final String arg) {
        final String[] result = new String[1];
        final CountDownLatch done = new CountDownLatch(1);
        Boolean started = Tv.onUi(new Tv.UiTask<Boolean>() {
            @Override
            public Boolean run() {
                LiveActivity l = LiveActivity.current;
                if (l == null || l.chat() == null || !l.chat().isOpen()) return false;
                ValueCallback<String> cb = new ValueCallback<String>() {
                    @Override
                    public void onReceiveValue(String v) {
                        result[0] = v;
                        done.countDown();
                    }
                };
                ChatPanel ch = l.chat();
                if ("send".equals(what)) ch.sendMessage(arg, cb);
                else if ("like".equals(what)) ch.like(cb);
                else if ("follow".equals(what)) ch.runInPage(JS_FOLLOW, cb);
                else if ("gifts".equals(what)) ch.runInPage(JS_GIFTS, cb);
                else if ("gift".equals(what)) ch.runInPage(jsGift(arg), cb);
                return true;
            }
        }, 3000);
        if (started == null || !started) return err("Open a LIVE with chat showing on the TV first");
        try { done.await(5, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
        String v = result[0];
        if (v == null) return err("No answer from the LIVE page");
        try {
            if ("gifts".equals(what)) {
                String json = new JSONArray("[" + v + "]").getString(0);
                return "{\"ok\":true,\"gifts\":" + json + "}";
            }
        } catch (Exception e) {
            return err("Couldn't read the gift list yet");
        }
        if (v.contains("ok")) return "{\"ok\":true}";
        if (v.contains("already")) return "{\"ok\":true,\"already\":true}";
        if (v.contains("nochat")) return err("Chat isn't loaded yet");
        return err("That button isn't on the LIVE page right now");
    }
}
