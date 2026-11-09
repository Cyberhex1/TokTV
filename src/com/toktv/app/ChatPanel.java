package com.toktv.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.util.LinkedList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Read-only LIVE chat drawn natively beside the video (sending happens from TokTV Remote on the
 * phone). The LIVE room page runs in a hidden WebView with video, pictures, fonts, trackers and
 * the parts of the page chat doesn't need blocked; a small script pushes each new chat line the
 * moment the page adds it. The page is kept warm for a few minutes after leaving a LIVE, so
 * coming back or switching LIVEs doesn't load TikTok's whole website again.
 */
public class ChatPanel {
    private static final int MAX_LINES = 50;
    private static final long KEEP_WARM_MS = 3 * 60 * 1000;
    private static final String TAG = "TokChat";

    // The hidden page lives across LIVE screens (kept warm), so it's static.
    private static WebView sWeb;
    private static String sUser;
    private static ChatPanel sActive;
    private static final Handler sUi = new Handler(Looper.getMainLooper());

    private final Activity a;
    private final FrameLayout root;
    private final Prefs prefs;
    private LinearLayout panel, lines;
    private boolean open;
    private String user;
    private boolean gotFirst;
    private long t0;
    private final LinkedList<JSONObject> recent = new LinkedList<>();

    public ChatPanel(Activity a, FrameLayout root, Prefs prefs) {
        this.a = a;
        this.root = root;
        this.prefs = prefs;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, a.getResources().getDisplayMetrics());
    }

    public boolean isOpen() { return open; }

    public boolean chatOnRight() { return !"left".equals(prefs.get("chat_side", "right")); }

    // ---------------------------------------------------------------- Open / close

    public void open(String forUser) {
        if (!open) {
            open = true;
            buildViews();
        }
        sActive = this;
        if (forUser != null && !forUser.equalsIgnoreCase(user)) {
            user = forUser;
            gotFirst = false;
            clearLines();
            note("Connecting to chat…");
            loadPage();
        }
        panel.setVisibility(View.VISIBLE);
        panel.bringToFront();
    }

    /** Hides the panel; the hidden page stays warm for a few minutes for a quick return. */
    public void close() {
        open = false;
        user = null;
        if (panel != null) { root.removeView(panel); panel = null; }
        if (sActive == this) sActive = null;
        if (sWeb != null && sWeb.getParent() == root) {
            root.removeView(sWeb);
            sUi.removeCallbacks(destroyWarm);
            sUi.postDelayed(destroyWarm, KEEP_WARM_MS);
        }
    }

    private static final Runnable destroyWarm = new Runnable() {
        @Override
        public void run() {
            if (sWeb != null && sWeb.getParent() == null) {
                sWeb.destroy();
                sWeb = null;
                sUser = null;
            }
        }
    };

    /** Switching LIVE: follow the new room's chat. */
    public void switchTo(String newUser) {
        if (open) open(newUser);
    }

    // ---------------------------------------------------------------- Views

    private void buildViews() {
        boolean right = chatOnRight();
        panel = new LinearLayout(a);
        panel.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#C0101010"));
        bg.setCornerRadius(dp(14));
        panel.setBackground(bg);
        panel.setPadding(dp(14), dp(10), dp(14), dp(10));
        TextView header = new TextView(a);
        header.setTextColor(Color.parseColor("#888888"));
        header.setTextSize(13);
        header.setText("LIVE chat");
        panel.addView(header);
        lines = new LinearLayout(a);
        lines.setOrientation(LinearLayout.VERTICAL);
        lines.setGravity(Gravity.BOTTOM);
        panel.addView(lines, new LinearLayout.LayoutParams(-1, 0, 1f));
        int w = (int) (a.getResources().getDisplayMetrics().widthPixels * 0.27f);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(w, -1, right ? Gravity.END : Gravity.START);
        plp.setMargins(dp(16), dp(16), dp(16), dp(16));
        root.addView(panel, plp);
    }

    private float textSize() {
        try { return Float.parseFloat(prefs.get("chat_size", "18")); } catch (Exception e) { return 18; }
    }

    private void clearLines() {
        if (lines != null) lines.removeAllViews();
    }

    private void note(String s) {
        TextView t = new TextView(a);
        t.setText(s);
        t.setTextColor(Color.parseColor("#888888"));
        t.setTextSize(textSize());
        if (lines != null) lines.addView(t);
    }

    private static final int[] NAME_COLORS = {
            0xFF25F4EE, 0xFFFFC94D, 0xFF8AB4FF, 0xFFB6F36A, 0xFFFF9AC2, 0xFFFFA45C, 0xFFC7A6FF};

    private static int nameColor(String name) {
        return NAME_COLORS[(name.hashCode() & 0x7FFFFFFF) % NAME_COLORS.length];
    }

    private TextView lineView(String name, String msg, int msgColor, boolean small, float size) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        if (name != null && name.length() > 0) {
            sb.append(name);
            sb.setSpan(new ForegroundColorSpan(nameColor(name)), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.append("  ");
        }
        int s = sb.length();
        sb.append(msg);
        sb.setSpan(new ForegroundColorSpan(msgColor), s, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (small) sb.setSpan(new RelativeSizeSpan(0.8f), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        TextView t = new TextView(a);
        t.setText(sb);
        t.setTextSize(size);
        t.setPadding(0, dp(3), 0, dp(3));
        return t;
    }

    // ---------------------------------------------------------------- Hidden room page

    private void loadPage() {
        t0 = SystemClock.uptimeMillis();
        Log.i(TAG, "load " + user + (sWeb != null ? " (warm page)" : " (cold)"));
        sUi.removeCallbacks(destroyWarm);
        if (sWeb == null) {
            sWeb = createWeb(a);
            sUser = null;
        }
        ViewGroup parent = (ViewGroup) sWeb.getParent();
        if (parent != root) {
            if (parent != null) parent.removeView(sWeb);
            // Full size but underneath the (black) video area, so the page lays out its chat
            // column normally without ever being seen.
            root.addView(sWeb, 0, new FrameLayout.LayoutParams(-1, -1));
        }
        sWeb.evaluateJavascript("window.__tvjoins=" + showJoins() + ";", null);
        if (user.equalsIgnoreCase(sUser)) {
            // Same room as before: just show the last lines again.
            if (!sShown.isEmpty()) {
                JSONArray a = new JSONArray();
                for (JSONObject o : sShown) {
                    JSONObject c = new JSONObject();   // without "c": not timed as new lines
                    try { c.put("k", o.optString("k")).put("n", o.optString("n")).put("m", o.optString("m")); } catch (Exception ignored) { }
                    a.put(c);
                }
                sShown.clear();
                onLines(a.toString(), true);
            } else {
                sWeb.evaluateJavascript("window.__tvreplay&&__tvreplay()", null);
            }
            return;
        }
        final String target = user;
        sUser = target;
        sShown.clear();
        sLeftRoomId = sRoomId;
        sRoomId = 0;
        wire = false;
        sUi.removeCallbacks(wireCheck);
        if (sWeb.getUrl() != null && sWeb.getUrl().contains("/live")) {
            // The page is already running: switch rooms inside it (seconds) when it has a link to
            // this LIVE, otherwise load the room page.
            // The page is a single-page app: changing its address and telling its router is enough
            // to switch rooms (a few seconds instead of reloading all of TikTok).
            sWeb.evaluateJavascript(
                    "(function(u){window.__tvreset&&__tvreset();history.pushState({},'','/@'+u+'/live');"
                            + "dispatchEvent(new PopStateEvent('popstate',{state:{}}));return 'ok';})(" + JSONObject.quote(target) + ")", null);
            Log.i(TAG, "switching in page to " + target);
            sUi.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // No chat yet: if the page did switch rooms, it's just a quiet chat; if not, load
                    // the room page.
                    if (sWeb == null || !target.equals(sUser) || sActive != ChatPanel.this || gotFirst) return;
                    sWeb.evaluateJavascript("(function(){return !!document.querySelector('[data-e2e=\"room-header-anchor-name\"]')"
                            + "&&!!document.querySelector('[data-e2e=\"live-chat-container\"]')&&location.pathname.indexOf('/live')>0;})()",
                            new ValueCallback<String>() {
                                @Override
                                public void onReceiveValue(String v) {
                                    if (sWeb == null || !target.equals(sUser) || gotFirst) return;
                                    if ("true".equals(v)) {
                                        clearLines();
                                        note("No chat messages yet.");
                                    } else {
                                        Log.i(TAG, "in-page switch failed, reloading " + target);
                                        sWeb.loadUrl("https://www.tiktok.com/@" + target + "/live");
                                    }
                                }
                            });
                }
            }, 15000);
        } else {
            sWeb.loadUrl("https://www.tiktok.com/@" + target + "/live");
        }
    }

    private boolean showJoins() {
        return "1".equals(prefs.get("chat_joins", "0"));
    }

    // ---------------------------------------------------------------- Direct from the websocket

    /** The room's id, so frames still arriving for the room we just left are dropped. */
    private static long sRoomId, sLeftRoomId;
    /** Lines shown for sUser, to show again when coming back to the same room. */
    private static final LinkedList<JSONObject> sShown = new LinkedList<>();
    /** Chat is coming straight from the websocket: the page's own chat list is ignored. */
    private boolean wire;
    private final java.util.LinkedHashMap<String, long[]> wireSeen = new java.util.LinkedHashMap<String, long[]>() {
        @Override
        protected boolean removeEldestEntry(java.util.Map.Entry<String, long[]> e) { return size() > 300; }
    };
    private final java.util.HashSet<Long> wireIds = new java.util.HashSet<>();
    private final LinkedList<Long> wireIdOrder = new LinkedList<>();
    private static final java.util.concurrent.ExecutorService sDecode = java.util.concurrent.Executors.newSingleThreadExecutor();

    /** Called by the player once it knows the room id for user. */
    public void setRoomId(String forUser, String roomId) {
        if (forUser == null || !forUser.equalsIgnoreCase(sUser) || roomId == null) return;
        try { sRoomId = Long.parseLong(roomId); } catch (NumberFormatException ignored) { }
    }

    /**
     * Hooks the page's WebSocket before TikTok opens its LIVE connection and hands every binary
     * frame from TikTok's LIVE message socket to the app as base64. Safe to run more than once.
     */
    static final String WS_HOOK =
            "(function(){if(window.__tvws||!window.WebSocket||!window.TVChat||!TVChat.frame)return;window.__tvws=1;"
            + "var OW=window.WebSocket;"
            + "function b64(ab){var u=new Uint8Array(ab),s='',C=0x8000;for(var i=0;i<u.length;i+=C)s+=String.fromCharCode.apply(null,u.subarray(i,i+C));return btoa(s);}"
            + "function hand(d,at){try{if(d instanceof ArrayBuffer)TVChat.frame(b64(d),at);"
            + "else if(window.Blob&&d instanceof Blob){var r=new FileReader();r.onload=function(){TVChat.frame(b64(r.result),at);};r.readAsArrayBuffer(d);}}catch(e){}}"
            + "function W(u,p){var s=arguments.length>1?new OW(u,p):new OW(u);"
            + "try{if(/webcast|im\\/ws|ws_proxy/.test(String(u)))s.addEventListener('message',function(e){hand(e.data,Date.now());});}catch(e){}return s;}"
            + "W.prototype=OW.prototype;W.CONNECTING=0;W.OPEN=1;W.CLOSING=2;W.CLOSED=3;window.WebSocket=W;"
            // When the socket can't connect, the page polls /webcast/im/fetch/ instead: read those too.
            + "var IM=/\\/webcast\\/im\\/fetch/;function resp(b,at){try{TVChat.resp(b64(b),at);}catch(e){}}"
            + "var OF=window.fetch;if(OF)window.fetch=function(i){var p=OF.apply(this,arguments);"
            + "try{var u=typeof i==='string'?i:(i&&i.url)||'';if(IM.test(u))p.then(function(r){var at=Date.now();"
            + "r.clone().arrayBuffer().then(function(b){resp(b,at);});}).catch(function(){});}catch(e){}return p;};"
            + "var XO=XMLHttpRequest.prototype.open;XMLHttpRequest.prototype.open=function(m,u){"
            + "try{if(IM.test(String(u)))this.addEventListener('load',function(){var at=Date.now();"
            + "try{if(this.response instanceof ArrayBuffer)resp(this.response,at);}catch(e){}});}catch(e){}"
            + "return XO.apply(this,arguments);};})();";

    private final Runnable wireCheck = new Runnable() {
        @Override
        public void run() { logLag(); }
    };

    /** Decoded websocket lines, on the UI thread. */
    private void onWire(List<ChatWire.Line> got, long pageAt) {
        if (!open || lines == null || got.isEmpty()) return;
        JSONArray arr = new JSONArray();
        for (ChatWire.Line l : got) {
            if (sRoomId != 0 ? l.roomId != sRoomId : (sLeftRoomId != 0 && l.roomId == sLeftRoomId)) continue;
            if (l.msgId != 0) {
                if (!wireIds.add(l.msgId)) continue;
                wireIdOrder.add(l.msgId);
                if (wireIdOrder.size() > 500) wireIds.remove(wireIdOrder.removeFirst());
            }
            if ("chat-message".equals(l.kind)) {
                wireSeen.put(l.name + "\n" + l.text.trim(), new long[]{l.createdAt, pageAt});
                lagWirePage.add(pageAt - l.createdAt);
            }
            try {
                JSONObject o = new JSONObject();
                o.put("k", l.kind);
                o.put("n", l.name);
                o.put("m", l.text);
                o.put("d", pageAt);
                o.put("c", l.createdAt);
                arr.put(o);
            } catch (Exception ignored) { }
        }
        if (arr.length() == 0) return;
        if (!wire) {
            wire = true;
            Log.i(TAG, "chat straight from the websocket (room " + got.get(0).roomId + ")");
        }
        onLines(arr.toString(), true);
    }

    // Lag samples (ms), logged every 15 s: how far behind TikTok's server each path is.
    private final List<Long> lagWirePage = new java.util.ArrayList<>(), lagWireScreen = new java.util.ArrayList<>(),
            lagDomAfterWire = new java.util.ArrayList<>(), lagDomScreen = new java.util.ArrayList<>();
    private long lastLagLog;

    private static String pct(List<Long> a) {
        if (a.isEmpty()) return "n=0";
        List<Long> s = new java.util.ArrayList<>(a);
        java.util.Collections.sort(s);
        return "n=" + s.size() + " p50=" + s.get(s.size() / 2) + " p90=" + s.get(Math.min(s.size() - 1, s.size() * 9 / 10))
                + " max=" + s.get(s.size() - 1);
    }

    private void logLag() {
        long now = SystemClock.uptimeMillis();
        if (now - lastLagLog < 15000) return;
        lastLagLog = now;
        if (lagWirePage.isEmpty() && lagDomScreen.isEmpty() && lagDomAfterWire.isEmpty()) return;
        Log.i(TAG, "LAG server->page(ws) " + pct(lagWirePage) + " | server->TV(ws) " + pct(lagWireScreen)
                + " | ws->pageDOM " + pct(lagDomAfterWire) + " | server->TV(via DOM, old way) " + pct(lagDomScreen)
                + (wire ? " [showing ws]" : " [showing DOM]"));
        lagWirePage.clear();
        lagWireScreen.clear();
        lagDomAfterWire.clear();
        lagDomScreen.clear();
    }

    private static WebView createWeb(Activity a) {
        WebView w = new WebView(a.getApplicationContext());
        WebSettings s = w.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setUserAgentString(Prefs.DESKTOP_UA);
        s.setMediaPlaybackRequiresUserGesture(true);
        s.setBlockNetworkImage(true);
        s.setLoadsImagesAutomatically(false);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(true);
        w.setFocusable(false);
        w.setBackgroundColor(Color.BLACK);
        CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
        w.addJavascriptInterface(new Bridge(), "TVChat");
        w.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return !r.getUrl().toString().startsWith("https://www.tiktok.com/");
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest r) {
                String host = r.getUrl().getHost() == null ? "" : r.getUrl().getHost().toLowerCase();
                if (notNeededForChat(host, r.getUrl().toString())) {
                    return new WebResourceResponse("text/plain", "utf-8", new ByteArrayInputStream(new byte[0]));
                }
                return null;
            }

            @Override
            public void onPageStarted(WebView v, String url, android.graphics.Bitmap icon) {
                // As early as possible, before TikTok opens its LIVE socket.
                v.evaluateJavascript(WS_HOOK, null);
            }

            @Override
            public void onPageCommitVisible(WebView v, String url) {
                v.evaluateJavascript(WS_HOOK, null);
            }

            @Override
            public void onPageFinished(WebView v, String url) {
                ChatPanel p = sActive;
                if (p != null) Log.i(TAG, "pageFinished +" + (SystemClock.uptimeMillis() - p.t0) + "ms");
                v.evaluateJavascript(WS_HOOK + CHAT_JS, null);
            }
        });
        return w;
    }

    private static final Pattern MEDIA = Pattern.compile(
            ".*\\.(flv|m3u8|ts|mp4|mp3|webp|jpe?g|png|gif|svg|woff2?|ttf|otf)$");

    /** Video, pictures, fonts, trackers and page parts that have nothing to do with chat. */
    static boolean notNeededForChat(String host, String url) {
        String path = url.split("\\?")[0].toLowerCase();
        if (host.startsWith("pull-") || MEDIA.matcher(path).matches() || path.contains("~tplv")) return true;
        if (host.startsWith("mcs") || host.startsWith("mon") || host.contains("-mon.") || host.contains("slardar")
                || host.startsWith("log") || MainActivity.isBlocked(host)) return true;
        return path.contains("/webcast/feed/") || path.contains("/game_feed/") || path.contains("/tiktok/event/")
                || path.contains("/global-footer/") || path.contains("/inbox/") || path.contains("/report/inbox")
                || path.contains("/api/ba/") || path.contains("/live_podcast/") || path.contains("/in_room_banner/")
                || path.contains("/wallet_api") || path.contains("/api/share/") || path.contains("/ranklist/")
                || path.contains("/check_alive/") || path.contains("/screen_time/") || path.contains("/app_open_times/")
                || path.contains("/api/recommend/") || path.contains("/api/preload/") || path.contains("/story/");
    }

    /**
     * Watches the page for new chat lines (MutationObserver, so lines arrive as soon as the page
     * shows them) and hands them over in small batches. Also hides everything but the chat list
     * so the page has less to lay out and paint.
     */
    private static final String CHAT_JS =
            "(function(){if(window.__tvchat)return;window.__tvchat=1;"
            + "var st=document.createElement('style');st.textContent='video,img,[data-e2e=\"live-side-nav-channel-title\"],"
            + "[data-e2e=\"discover_category-list-live-card\"],[data-e2e=\"live-second-screen-container\"]{display:none!important}"
            + "*{animation:none!important;transition:none!important}';document.head.appendChild(st);"
            + "var seen=new WeakSet(),last=[];function tx(e){return e?(e.innerText||'').trim():''}"
            + "window.__tvreset=function(){last=[];var o=document.querySelectorAll('[data-e2e]');for(var i=0;i<o.length;i++)seen.add(o[i]);};"
            + "window.__tvreplay=function(){if(last.length)TVChat.push(JSON.stringify(last));};"
            + "function scan(){var v=document.querySelectorAll('video');for(var i=0;i<v.length;i++){if(!v[i].paused){v[i].muted=true;try{v[i].pause()}catch(e){}}}"
            + "var out=[];var els=document.querySelectorAll('[data-e2e=\"chat-message\"],[data-e2e=\"enter-message\"],[data-e2e=\"social-message\"],[data-e2e=\"gift-message\"]');"
            + "for(var i=0;i<els.length;i++){var e=els[i];if(seen.has(e))continue;seen.add(e);var k=e.getAttribute('data-e2e');"
            + "if(k==='enter-message'&&!window.__tvjoins)continue;"
            + "var ne=e.querySelector('[data-e2e=\"message-owner-name\"]');var n=ne?(ne.getAttribute('title')||tx(ne)):'';var m='';"
            + "if(k==='chat-message'){var col=ne?ne.closest('.flex-col'):null;var c=col?col.lastElementChild:null;"
            + "if(c&&!c.contains(ne))m=tx(c);if(!m){var t=tx(e).split('\\n');m=t[t.length-1];}"
            + "var g=e.querySelector('img[src*=\"gift\"],img[src*=\"webcast\"]');if(g&&/sent|\\u00d7|x\\d/i.test(m))k='gift';}"
            + "else{var p=tx(e).split('\\n');if(!n){n=p[0];p=p.slice(1);}else if(p[0]===n){p=p.slice(1);}m=p.join(' ');}"
            + "if(m||n)out.push({k:k,n:n,m:m,d:Date.now()});}"
            + "if(out.length){last=last.concat(out).slice(-30);TVChat.push(JSON.stringify(out.slice(-25)));}}"
            + "var pend=0;function soon(){if(pend)return;pend=setTimeout(function(){pend=0;scan();},120);}"
            + "new MutationObserver(soon).observe(document.body,{childList:true,subtree:true});"
            + "scan();setInterval(scan,3000);})();";

    private static class Bridge {
        @JavascriptInterface
        public void push(final String json) {
            sUi.post(new Runnable() {
                @Override
                public void run() {
                    ChatPanel p = sActive;
                    if (p != null) p.onLines(json, false);
                }
            });
        }

        /** One binary websocket frame (base64) and when the page received it. */
        @JavascriptInterface
        public void frame(final String b64, final double at) { decode(b64, at, true); }

        /** One /webcast/im/fetch/ response (base64): the page's polling fallback. */
        @JavascriptInterface
        public void resp(final String b64, final double at) { decode(b64, at, false); }

        private void decode(final String b64, final double at, final boolean frame) {
            sDecode.execute(new Runnable() {
                @Override
                public void run() {
                    byte[] raw;
                    try { raw = android.util.Base64.decode(b64, android.util.Base64.DEFAULT); } catch (Exception e) { return; }
                    final List<ChatWire.Line> got = frame ? ChatWire.decodeFrame(raw) : ChatWire.decodeResponse(raw);
                    if (got.isEmpty()) return;
                    sUi.post(new Runnable() {
                        @Override
                        public void run() {
                            ChatPanel p = sActive;
                            if (p != null) p.onWire(got, (long) at);
                        }
                    });
                }
            });
        }
    }

    private void onLines(String json, boolean fromWire) {
        if (!open || lines == null) return;
        try {
            JSONArray arr = new JSONArray(json);
            if (arr.length() == 0) return;
            long now = System.currentTimeMillis();
            if (!fromWire) {
                // The page's own chat list: used only when the websocket isn't readable; otherwise
                // it's just timed against the websocket (the old way, for the lag log).
                JSONArray keep = new JSONArray();
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    if ("chat-message".equals(o.optString("k"))) {
                        long[] w = wireSeen.get(o.optString("n") + "\n" + o.optString("m").trim());
                        if (w != null) {
                            lagDomAfterWire.add(o.optLong("d", now) - w[1]);
                            lagDomScreen.add(now - w[0]);
                        }
                    }
                    if (!wire) keep.put(o);
                }
                logLag();
                if (keep.length() == 0) return;
                arr = keep;
            }
            if (!gotFirst) {
                Log.i(TAG, "firstLines +" + (SystemClock.uptimeMillis() - t0) + "ms" + (fromWire ? " (websocket)" : " (page)"));
                clearLines();
                gotFirst = true;
            }
            long lag = 0;
            boolean joins = showJoins();
            boolean filter = "1".equals(prefs.get("chat_filter", "0"));
            float size = textSize();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                lag += now - o.optLong("d", now);
                if (fromWire && o.optLong("c", 0) > 0 && "chat-message".equals(o.optString("k"))) {
                    lagWireScreen.add(now - o.optLong("c"));
                }
                if (sShown.size() >= 30) sShown.removeFirst();
                sShown.add(o);
                String k = o.optString("k"), n = o.optString("n"), m = o.optString("m");
                if (filter) m = Profanity.mask(m);
                TextView v;
                if ("enter-message".equals(k)) {
                    if (!joins) continue;
                    v = lineView(n, "joined", Color.parseColor("#8A8A8A"), true, size);
                } else if ("gift".equals(k) || "gift-message".equals(k)) {
                    v = lineView(n, "🎁 " + m, Color.parseColor("#FFD66B"), false, size);
                } else if ("social-message".equals(k)) {
                    v = lineView(n, m, Color.parseColor("#9FD8FF"), true, size);
                } else {
                    v = lineView(n, m, Color.WHITE, false, size);
                }
                lines.addView(v);
                keep(o);
            }
            int extra = lines.getChildCount() - MAX_LINES;
            if (extra > 0) lines.removeViews(0, extra);
            if (!fromWire) Log.i(TAG, "batch " + arr.length() + " pageToApp avg " + (lag / arr.length()) + "ms");
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- For TokTV Remote (phone)

    /** The last chat lines (for the phone remote's chat mirror). */
    public synchronized JSONArray recentLines() {
        JSONArray arr = new JSONArray();
        for (JSONObject o : recent) arr.put(o);
        return arr;
    }

    private synchronized void keep(JSONObject o) {
        try { o.put("t", System.currentTimeMillis()); } catch (Exception ignored) { }
        recent.add(o);
        while (recent.size() > 60) recent.removeFirst();
    }

    public String currentUser() { return open ? user : null; }

    /** Sends a chat message as the logged-in user, through the LIVE page's own chat box. */
    public void sendMessage(String text, ValueCallback<String> done) {
        String js = "(function(t){var f=document.querySelector('[data-e2e=\"room-chat-input-field\"]');if(!f)return 'none';"
                + "var ed=f.matches('[contenteditable],textarea,input')?f:(f.querySelector('[contenteditable],textarea,input')||f);"
                + "ed.focus();if(!document.execCommand('insertText',false,t)){ed.textContent=t;ed.dispatchEvent(new InputEvent('input',{bubbles:true,data:t}));}"
                + "setTimeout(function(){var b=document.querySelector('[data-e2e=\"room-chat-send-btn\"]');if(b)b.click();"
                + "else ed.dispatchEvent(new KeyboardEvent('keydown',{key:'Enter',keyCode:13,bubbles:true}));},250);return 'ok';})("
                + JSONObject.quote(text) + ")";
        runInPage(js, done);
    }

    /** Taps the LIVE page's like (heart) button. */
    public void like(ValueCallback<String> done) {
        runInPage("(function(){var b=document.querySelector('[data-e2e=\"room-chat-like-btn\"]');if(!b)return 'none';b.click();return 'ok';})()", done);
    }

    /** Runs a script in the hidden LIVE page (used by the remote for follow and gifts). */
    public void runInPage(String js, ValueCallback<String> done) {
        if (sWeb == null || !open) { if (done != null) done.onReceiveValue("\"nochat\""); return; }
        sWeb.evaluateJavascript(js, done);
    }
}
