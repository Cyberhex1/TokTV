package com.toktv.app;

import android.app.Activity;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * TikTok search without showing the website: opens TikTok's LIVE search page and then its
 * account search page in a hidden WebView (images and video off) and keeps a copy of the
 * results those pages fetch for themselves. Destroyed when done.
 */
public final class SearchLoader {
    public interface Callback {
        void onLive(List<Feed.Room> rooms);
        void onAccounts(List<Feed.Room> accounts);
        void onDone();
    }

    private static final long STEP_TIMEOUT_MS = 30000;

    private final Activity host;
    private final ViewGroup parent;
    private final Callback cb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private String query;
    private int step;   // 1 = LIVE search, 2 = accounts
    private boolean done;

    public SearchLoader(Activity host, ViewGroup parent, Callback cb) {
        this.host = host;
        this.parent = parent;
        this.cb = cb;
    }

    public void start(String q) {
        query = q;
        web = new WebView(host);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(Prefs.DESKTOP_UA);
        s.setBlockNetworkImage(true);
        s.setLoadsImagesAutomatically(false);
        s.setMediaPlaybackRequiresUserGesture(true);
        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("https://www.tiktok.com/");
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                return intercept(r);
            }
        });
        parent.addView(web, 0, new FrameLayout.LayoutParams(1, 1));
        go(1);
    }

    private void go(int s) {
        step = s;
        ui.removeCallbacks(stepTimeout);
        ui.postDelayed(stepTimeout, STEP_TIMEOUT_MS);
        String path = s == 1 ? "search/live" : "search/user";
        web.loadUrl("https://www.tiktok.com/" + path + "?q=" + Uri.encode(query));
    }

    private final Runnable stepTimeout = new Runnable() {
        @Override
        public void run() { next(); }
    };

    private void next() {
        if (done) return;
        if (step == 1) go(2);
        else finish();
    }

    public void cancel() {
        done = true;
        destroy();
    }

    private void finish() {
        if (done) return;
        done = true;
        destroy();
        cb.onDone();
    }

    private void destroy() {
        ui.removeCallbacks(stepTimeout);
        if (web != null) {
            web.stopLoading();
            parent.removeView(web);
            web.destroy();
            web = null;
        }
    }

    // ---------------------------------------------------------------- Requests (network thread)

    private WebResourceResponse intercept(WebResourceRequest r) {
        if (done) return FeedLoader.empty();
        String url = r.getUrl().toString();
        String host = r.getUrl().getHost() == null ? "" : r.getUrl().getHost().toLowerCase();
        if (url.contains("/api/search/live/full/") || url.contains("/api/search/user/full/")) {
            final boolean live = url.contains("/live/full/");
            String[] body = new String[1];
            WebResourceResponse resp = FeedLoader.proxy(r, body);
            final List<Feed.Room> out = body[0] == null ? null : (live ? parseLive(body[0]) : parseUsers(body[0]));
            ui.post(new Runnable() {
                @Override
                public void run() {
                    if (done || out == null) return;
                    if (live && step == 1) { cb.onLive(out); next(); }
                    else if (!live && step == 2) { cb.onAccounts(out); next(); }
                }
            });
            return resp;
        }
        if (url.contains("/webcast/feed/") || FeedLoader.isHeavy(host, url)) return FeedLoader.empty();
        return null;
    }

    /** {data:[{live_info:{raw_data:"<room json>"}}]} */
    static List<Feed.Room> parseLive(String body) {
        List<Feed.Room> out = new ArrayList<>();
        try {
            JSONArray data = new JSONObject(body).optJSONArray("data");
            if (data == null) return out;
            for (int i = 0; i < data.length(); i++) {
                JSONObject li = data.getJSONObject(i).optJSONObject("live_info");
                if (li == null) continue;
                String raw = li.optString("raw_data", "");
                if (raw.length() == 0) continue;
                Feed.Room r = Feed.parseRoom(new JSONObject(raw));
                if (r != null) out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** {user_list:[{user_info:{unique_id, nickname, avatar_thumb, room_id, follower_count}}]} */
    static List<Feed.Room> parseUsers(String body) {
        List<Feed.Room> out = new ArrayList<>();
        try {
            JSONArray list = new JSONObject(body).optJSONArray("user_list");
            if (list == null) return out;
            for (int i = 0; i < list.length(); i++) {
                JSONObject u = list.getJSONObject(i).optJSONObject("user_info");
                if (u == null) continue;
                Feed.Room r = new Feed.Room();
                r.user = u.optString("unique_id", "");
                if (r.user.length() == 0) continue;
                r.nick = u.optString("nickname", r.user);
                if (r.nick.trim().length() == 0) r.nick = r.user;
                String room = u.optString("room_id_str", u.optString("room_id", "0"));
                r.live = room.length() > 0 && !"0".equals(room);
                r.roomId = r.live ? room : null;
                r.followers = u.optLong("follower_count", 0);
                r.title = "";
                JSONObject av = u.optJSONObject("avatar_thumb");
                JSONArray ul = av == null ? null : av.optJSONArray("url_list");
                if (ul != null && ul.length() > 0) r.avatar = ul.optString(0);
                out.add(r);
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
