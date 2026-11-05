package com.toktv.app;

import android.app.Activity;
import android.graphics.Color;
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

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Gets the LIVE lists without showing TikTok's website: opens tiktok.com/live in a tiny hidden
 * WebView with images and video blocked, and when the page asks TikTok's LIVE feed for the
 * Following and Recommended lists, makes that same request on the page's behalf, keeps a copy
 * of the answer, and hands it to the page. The WebView is destroyed as soon as the lists arrive,
 * so it doesn't hold memory while you watch.
 */
public final class FeedLoader {
    public interface Callback {
        /** following is null if the page never asked for it (usually: not logged in). */
        void onFeed(List<Feed.Room> following, List<Feed.Room> suggested, List<Feed.Room> gaming, boolean loggedIn);
    }

    private static final long TIMEOUT_MS = 45000;
    private static final long WAIT_FOR_SUGGESTED_MS = 12000;

    private final Activity host;
    private final ViewGroup parent;
    private final Callback cb;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private WebView web;
    private List<Feed.Room> follow, sugg, gaming;
    private boolean done;

    public FeedLoader(Activity host, ViewGroup parent, Callback cb) {
        this.host = host;
        this.parent = parent;
        this.cb = cb;
    }

    public static boolean loggedIn() {
        String c = CookieManager.getInstance().getCookie("https://www.tiktok.com");
        return c != null && c.contains("sessionid=");
    }

    public void start() {
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
        // Full size (the page only fetches its Recommended and Gaming rows when it lays them out),
        // but behind the app's own opaque screen, so it is never seen.
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-1, -1);
        parent.addView(web, 0, lp);
        web.loadUrl("https://www.tiktok.com/live");
        ui.postDelayed(timeout, TIMEOUT_MS);
    }

    private final Runnable timeout = new Runnable() {
        @Override
        public void run() { finish(); }
    };

    private final Runnable finishRunnable = new Runnable() {
        @Override
        public void run() { finish(); }
    };

    public void cancel() {
        done = true;
        destroy();
    }

    private void finish() {
        if (done) return;
        done = true;
        destroy();
        cb.onFeed(follow, sugg, gaming, loggedIn());
    }

    private void destroy() {
        ui.removeCallbacks(timeout);
        ui.removeCallbacks(finishRunnable);
        if (web != null) {
            web.stopLoading();
            parent.removeView(web);
            web.destroy();
            web = null;
        }
    }

    // ---------------------------------------------------------------- Requests (background thread)

    private WebResourceResponse intercept(WebResourceRequest r) {
        String url = r.getUrl().toString();
        String host = r.getUrl().getHost();
        host = host == null ? "" : host.toLowerCase();
        if (done) return empty();
        if (url.contains("/webcast/feed/")) {
            int ci = url.indexOf("channel_id=");
            android.util.Log.i("TokTV", "feed request " + (ci >= 0 ? url.substring(ci, Math.min(url.length(), ci + 20)) : "?"));
            if (url.contains("channel_id=42&") || url.endsWith("channel_id=42")) return proxyFeed(r, 0);
            if (url.contains("channel_id=87&") || url.endsWith("channel_id=87")) return proxyFeed(r, 1);
            if (url.contains("channel_id=1111006&") || url.endsWith("channel_id=1111006")) return proxyFeed(r, 2);
            return empty();
        }
        if (isHeavy(host, url)) return empty();
        return null;
    }

    /** Video, images, chat and trackers: none of it is needed to get the lists. */
    static boolean isHeavy(String host, String url) {
        String path = url.split("\\?")[0].toLowerCase();
        if (host.startsWith("pull-") || host.contains("-pull") || host.startsWith("v16") || host.startsWith("v19")
                || host.startsWith("v77") || host.contains("webcast-") && host.contains("cdn")) return true;
        if (path.endsWith(".flv") || path.endsWith(".m3u8") || path.endsWith(".ts") || path.endsWith(".mp4")
                || path.endsWith(".webp") || path.endsWith(".jpg") || path.endsWith(".jpeg") || path.endsWith(".png")
                || path.endsWith(".gif") || path.endsWith(".woff") || path.endsWith(".woff2") || path.endsWith(".ttf")
                || path.endsWith(".mp3") || path.contains("~tplv")) return true;
        if (host.startsWith("im-") || url.contains("/webcast/im/") || url.contains("/linkmic") 
                || url.contains("/event/feed") || url.contains("/event/recommend") || url.contains("/wallet_api/")) return true;
        return MainActivity.isBlocked(host);
    }

    private WebResourceResponse proxyFeed(WebResourceRequest r, final int kind) {
        String[] body = new String[1];
        WebResourceResponse resp = proxy(r, body);
        final List<Feed.Room> rooms = body[0] == null ? null : Feed.parse(body[0]);
        ui.post(new Runnable() {
            @Override
            public void run() { onList(kind, rooms); }
        });
        return resp;
    }

    /**
     * Makes the page's own request for it (same URL, headers and cookies), keeps a copy of the
     * answer in bodyOut[0] and returns it to the page. Runs on the WebView's network thread.
     */
    static WebResourceResponse proxy(WebResourceRequest r, String[] bodyOut) {
        try {
            String url = r.getUrl().toString();
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            for (Map.Entry<String, String> h : r.getRequestHeaders().entrySet()) {
                c.setRequestProperty(h.getKey(), h.getValue());
            }
            c.setRequestProperty("User-Agent", Prefs.DESKTOP_UA);
            c.setRequestProperty("Origin", "https://www.tiktok.com");
            c.setRequestProperty("Referer", "https://www.tiktok.com/");
            c.setRequestProperty("Accept", "*/*");
            c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) c.setRequestProperty("Cookie", cookies);
            int code = c.getResponseCode();
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
            if (in != null) in.close();
            String ctype = c.getContentType();
            c.disconnect();
            final byte[] body = out.toByteArray();
            bodyOut[0] = new String(body, "UTF-8");
            Map<String, String> headers = new HashMap<>();
            headers.put("Access-Control-Allow-Origin", "https://www.tiktok.com");
            headers.put("Access-Control-Allow-Credentials", "true");
            String reason = code >= 400 ? "Error" : "OK";
            return new WebResourceResponse(ctype != null && ctype.contains("json") ? "application/json" : "text/plain",
                    "utf-8", code, reason, headers, new ByteArrayInputStream(body));
        } catch (Exception e) {
            return empty();
        }
    }

    private void onList(int kind, List<Feed.Room> rooms) {
        android.util.Log.i("TokTV", "feed list " + kind + ": " + (rooms == null ? "unreadable" : rooms.size() + " rooms"));
        if (done || rooms == null) return;
        if (kind == 0) follow = rooms; else if (kind == 1) sugg = rooms; else gaming = rooms;
        if (follow != null && sugg != null && gaming != null) finish();
        else if (follow != null) {
            ui.removeCallbacks(finishRunnable);
            ui.postDelayed(finishRunnable, WAIT_FOR_SUGGESTED_MS);
        } else if (!loggedIn()) {
            // Logged out: the page never asks for Following.
            ui.removeCallbacks(finishRunnable);
            ui.postDelayed(finishRunnable, WAIT_FOR_SUGGESTED_MS);
        }
    }

    static WebResourceResponse empty() {
        return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
    }
}
