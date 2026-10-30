package com.toktv.app;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Native Fire TV player for TikTok Lives. Looks up the Live's HLS stream through TikTok's
 * web endpoints (with the user's login cookies) and plays it with the system video player.
 * Up/Down switches between Lives, Right shows chat, Back returns to the app.
 */
public class LiveActivity extends Activity {
    private static final String[] QUALITY_ORDER = {"origin", "uhd", "hd", "sd", "ld"};

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private FrameLayout root;
    private VideoView video;
    private LinearLayout info;
    private TextView infoName, infoTitle, infoViewers, status, hint;
    private ProgressBar spinner;
    private WebView chat;
    private boolean chatOpen;

    private final List<String> lives = new ArrayList<>();
    private int index;
    private String user;
    private String pageStream;
    private int loadToken;
    private List<String> streamUrls = new ArrayList<>();
    private int streamAttempt;
    private boolean failed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = new Prefs(this);

        user = getIntent().getStringExtra("user");
        pageStream = getIntent().getStringExtra("stream");
        try {
            JSONArray a = new JSONArray(getIntent().getStringExtra("lives"));
            for (int i = 0; i < a.length(); i++) {
                String u = a.getString(i);
                if (!containsIgnoreCase(lives, u)) lives.add(u);
            }
        } catch (Exception ignored) {
        }
        if (!containsIgnoreCase(lives, user)) lives.add(0, user);
        for (int i = 0; i < lives.size(); i++) if (lives.get(i).equalsIgnoreCase(user)) index = i;

        buildUi();
        load(user);
    }

    private static boolean containsIgnoreCase(List<String> l, String s) {
        for (String x : l) if (x.equalsIgnoreCase(s)) return true;
        return false;
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    // ---------------------------------------------------------------- UI

    private void buildUi() {
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        setContentView(root);

        video = new VideoView(this);
        root.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                spinner.setVisibility(View.GONE);
                status.setVisibility(View.GONE);
                mp.setLooping(false);
                video.start();
                showInfo();
            }
        });
        video.setOnErrorListener(new MediaPlayer.OnErrorListener() {
            @Override
            public boolean onError(MediaPlayer mp, int what, int extra) {
                tryNextStream();
                return true;
            }
        });
        video.setOnCompletionListener(new MediaPlayer.OnCompletionListener() {
            @Override
            public void onCompletion(MediaPlayer mp) {
                // A Live "completes" when it ends or the connection drops. Reload once to check.
                load(user);
            }
        });
        video.setOnInfoListener(new MediaPlayer.OnInfoListener() {
            @Override
            public boolean onInfo(MediaPlayer mp, int what, int extra) {
                if (what == MediaPlayer.MEDIA_INFO_BUFFERING_START) spinner.setVisibility(View.VISIBLE);
                else if (what == MediaPlayer.MEDIA_INFO_BUFFERING_END || what == MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)
                    spinner.setVisibility(View.GONE);
                return false;
            }
        });

        // Info card (top left)
        info = new LinearLayout(this);
        info.setOrientation(LinearLayout.VERTICAL);
        info.setBackgroundResource(R.drawable.card_bg);
        info.setPadding(dp(18), dp(14), dp(18), dp(14));
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView badge = new TextView(this);
        badge.setText("LIVE");
        badge.setTextColor(Color.WHITE);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setTextSize(14);
        badge.setBackgroundResource(R.drawable.live_badge);
        badge.setPadding(dp(8), dp(2), dp(8), dp(2));
        top.addView(badge);
        infoName = text(22, Color.WHITE, true);
        infoName.setPadding(dp(12), 0, 0, 0);
        top.addView(infoName);
        info.addView(top);
        infoTitle = text(17, Color.parseColor("#DDDDDD"), false);
        infoTitle.setMaxLines(2);
        infoTitle.setEllipsize(TextUtils.TruncateAt.END);
        infoTitle.setPadding(0, dp(6), 0, 0);
        info.addView(infoTitle);
        infoViewers = text(15, Color.parseColor("#25F4EE"), false);
        infoViewers.setPadding(0, dp(4), 0, 0);
        info.addView(infoViewers);
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(520), -2, Gravity.TOP | Gravity.START);
        ilp.setMargins(dp(32), dp(28), 0, 0);
        root.addView(info, ilp);

        spinner = new ProgressBar(this);
        root.addView(spinner, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));

        status = text(20, Color.WHITE, false);
        status.setGravity(Gravity.CENTER);
        status.setBackgroundResource(R.drawable.card_bg);
        status.setPadding(dp(24), dp(16), dp(24), dp(16));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        slp.topMargin = dp(110);
        root.addView(status, slp);

        hint = text(15, Color.WHITE, false);
        hint.setBackgroundResource(R.drawable.card_bg);
        hint.setPadding(dp(18), dp(8), dp(18), dp(8));
        hint.setText("▲ ▼  Switch LIVE      " + (prefs.nativeChat() ? "▶  Chat      " : "") + "OK  Info      ≡  Quality      Back  Exit");
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = dp(24);
        root.addView(hint, hlp);
    }

    private TextView text(float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    private final Runnable hideInfo = new Runnable() {
        @Override
        public void run() {
            info.setVisibility(View.GONE);
            hint.setVisibility(View.GONE);
        }
    };

    private void showInfo() {
        info.setVisibility(View.VISIBLE);
        hint.setVisibility(View.VISIBLE);
        ui.removeCallbacks(hideInfo);
        ui.postDelayed(hideInfo, 5000);
    }

    private void setStatus(String s, boolean busy) {
        status.setText(s);
        status.setVisibility(s == null ? View.GONE : View.VISIBLE);
        spinner.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    // ---------------------------------------------------------------- Loading

    private void load(final String u) {
        user = u;
        failed = false;
        final int token = ++loadToken;
        video.stopPlayback();
        infoName.setText("@" + u);
        infoTitle.setText("");
        infoViewers.setText("");
        showInfo();
        setStatus("Opening @" + u + "'s LIVE…", true);
        if (chatOpen) loadChat();

        final String page = pageStream;
        pageStream = null;   // only valid for the first Live we were opened with
        new Thread(new Runnable() {
            @Override
            public void run() {
                final Result r = resolve(u, page);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (token != loadToken || isFinishing()) return;
                        onResolved(r);
                    }
                });
            }
        }).start();
    }

    private void onResolved(Result r) {
        if (r.nickname != null) infoName.setText(r.nickname + "  @" + user);
        if (r.title != null) infoTitle.setText(r.title);
        if (r.viewers > 0) infoViewers.setText(formatCount(r.viewers) + " watching");
        if (!r.live) {
            failed = true;
            setStatus("@" + user + " isn't LIVE right now.\n▲ ▼ to try another LIVE, Back to exit.", false);
            return;
        }
        if (r.hls.isEmpty()) {
            failed = true;
            setStatus("This LIVE has no stream the Fire TV player can play.\nPress OK to watch it in the website player.", false);
            return;
        }
        streamUrls = r.hls;
        streamAttempt = 0;
        play(streamUrls.get(0));
    }

    private void play(String url) {
        setStatus(null, true);
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", Prefs.DESKTOP_UA);
        headers.put("Referer", "https://www.tiktok.com/");
        video.setVideoURI(Uri.parse(url), headers);
        video.requestFocus();
    }

    private void tryNextStream() {
        streamAttempt++;
        if (streamAttempt < streamUrls.size()) {
            play(streamUrls.get(streamAttempt));
            return;
        }
        failed = true;
        video.stopPlayback();
        setStatus("Couldn't play this LIVE in the Fire TV player.\nPress OK to watch it in the website player.", false);
    }

    private static String formatCount(long n) {
        if (n >= 1000000) return String.format("%.1fM", n / 1e6);
        if (n >= 1000) return String.format("%.1fK", n / 1e3);
        return String.valueOf(n);
    }

    // ---------------------------------------------------------------- Stream lookup

    static class Result {
        boolean live;
        String nickname, title;
        long viewers;
        List<String> hls = new ArrayList<>();
    }

    private Result resolve(String u, String pageJson) {
        Result r = new Result();
        // 1) Data embedded in the page the user was on.
        if (pageJson != null && pageJson.length() > 0) {
            try { parseUserRoom(new JSONObject(pageJson), r); } catch (Exception ignored) { }
            if (!r.hls.isEmpty()) return r;
        }
        String roomId = null;
        // 2) The web app's own endpoint for a user's room.
        try {
            String url = "https://www.tiktok.com/api-live/user/room/?aid=1988&app_name=tiktok_web&device_platform=web_pc"
                    + "&app_language=en&browser_language=en-US&browser_platform=Win32&browser_name=Mozilla"
                    + "&sourceType=54&uniqueId=" + URLEncoder.encode(u, "UTF-8");
            JSONObject o = new JSONObject(get(url, "https://www.tiktok.com/@" + u + "/live"));
            JSONObject data = o.optJSONObject("data");
            if (data != null) {
                parseUserRoom(data, r);
                JSONObject usr = data.optJSONObject("user");
                if (usr != null) roomId = usr.optString("roomId", null);
            }
        } catch (Exception ignored) {
        }
        if (!r.hls.isEmpty()) return r;
        // 3) Room info endpoint.
        if (roomId != null && roomId.length() > 0 && !"0".equals(roomId)) {
            try {
                JSONObject o = new JSONObject(get("https://webcast.tiktok.com/webcast/room/info/?aid=1988&room_id=" + roomId,
                        "https://www.tiktok.com/"));
                JSONObject data = o.optJSONObject("data");
                if (data != null) {
                    int st = data.optInt("status", 0);
                    if (st == 2) r.live = true;
                    if (r.title == null) r.title = emptyToNull(data.optString("title"));
                    if (r.viewers == 0) r.viewers = data.optLong("user_count", 0);
                    JSONObject owner = data.optJSONObject("owner");
                    if (owner != null && r.nickname == null) r.nickname = emptyToNull(owner.optString("nickname"));
                    JSONObject su = data.optJSONObject("stream_url");
                    if (su != null) {
                        JSONObject core = su.optJSONObject("live_core_sdk_data");
                        if (core != null && core.optJSONObject("pull_data") != null)
                            addFromStreamData(core.optJSONObject("pull_data").optString("stream_data"), r);
                        JSONObject map = su.optJSONObject("hls_pull_url_map");
                        if (map != null) {
                            for (String k : new String[]{"FULL_HD1", "HD1", "SD1", "SD2"}) addUrl(r, map.optString(k));
                        }
                        addUrl(r, su.optString("hls_pull_url"));
                    }
                }
            } catch (Exception ignored) {
            }
        }
        if (!r.hls.isEmpty()) r.live = true;
        return r;
    }

    /** Parses {user:{...}, liveRoom:{...}} as found in both the page state and api-live. */
    private void parseUserRoom(JSONObject o, Result r) {
        JSONObject usr = o.optJSONObject("user");
        if (usr != null) {
            r.nickname = emptyToNull(usr.optString("nickname"));
            if (usr.optInt("status", 0) == 2) r.live = true;
        }
        JSONObject room = o.optJSONObject("liveRoom");
        if (room == null) return;
        r.title = emptyToNull(room.optString("title"));
        if (room.optInt("status", 0) == 2) r.live = true;
        JSONObject stats = room.optJSONObject("liveRoomStats");
        if (stats != null) r.viewers = stats.optLong("userCount", 0);
        JSONObject sd = room.optJSONObject("streamData");
        if (sd != null && sd.optJSONObject("pull_data") != null)
            addFromStreamData(sd.optJSONObject("pull_data").optString("stream_data"), r);
    }

    /** stream_data is a JSON string: {"data":{"origin":{"main":{"hls":"...","flv":"..."}},"hd":{...},...}} */
    private void addFromStreamData(String json, Result r) {
        if (json == null || json.length() == 0) return;
        try {
            JSONObject data = new JSONObject(json).optJSONObject("data");
            if (data == null) return;
            for (String q : qualityOrder()) {
                JSONObject level = data.optJSONObject(q);
                if (level == null) continue;
                JSONObject main = level.optJSONObject("main");
                if (main != null) addUrl(r, main.optString("hls"));
            }
        } catch (Exception ignored) {
        }
    }

    private List<String> qualityOrder() {
        List<String> order = new ArrayList<>();
        String q = prefs.quality();
        if (!"auto".equals(q)) order.add(q);
        for (String s : QUALITY_ORDER) if (!order.contains(s)) order.add(s);
        return order;
    }

    private static void addUrl(Result r, String u) {
        if (u == null || u.length() == 0 || !u.startsWith("http")) return;
        if (!r.hls.contains(u)) r.hls.add(u);
    }

    private static String emptyToNull(String s) {
        return s == null || s.length() == 0 ? null : s;
    }

    private static String get(String url, String referer) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(10000);
        c.setReadTimeout(15000);
        c.setRequestProperty("User-Agent", Prefs.DESKTOP_UA);
        c.setRequestProperty("Accept", "application/json, text/plain, */*");
        c.setRequestProperty("Accept-Language", "en-US,en;q=0.9");
        c.setRequestProperty("Referer", referer);
        String cookies = CookieManager.getInstance().getCookie("https://www.tiktok.com");
        if (cookies != null) c.setRequestProperty("Cookie", cookies);
        InputStream in = c.getResponseCode() >= 400 ? c.getErrorStream() : c.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while (in != null && (n = in.read(buf)) > 0) out.write(buf, 0, n);
        if (in != null) in.close();
        c.disconnect();
        return out.toString("UTF-8");
    }

    // ---------------------------------------------------------------- Chat panel

    private void toggleChat() {
        if (!prefs.nativeChat()) {
            toast("Chat is turned off in Settings");
            return;
        }
        chatOpen = !chatOpen;
        int w = (int) (getResources().getDisplayMetrics().widthPixels * 0.32f);
        FrameLayout.LayoutParams vlp = (FrameLayout.LayoutParams) video.getLayoutParams();
        if (chatOpen) {
            if (chat == null) {
                chat = new WebView(this);
                WebSettings s = chat.getSettings();
                s.setJavaScriptEnabled(true);
                s.setDomStorageEnabled(true);
                s.setUserAgentString(Prefs.DESKTOP_UA);
                s.setMediaPlaybackRequiresUserGesture(true);
                s.setUseWideViewPort(true);
                s.setLoadWithOverviewMode(true);
                chat.setBackgroundColor(Color.BLACK);
                chat.setFocusable(false);
                CookieManager.getInstance().setAcceptThirdPartyCookies(chat, true);
                chat.setWebViewClient(new WebViewClient() {
                    @Override
                    public void onPageFinished(WebView view, String url) {
                        view.evaluateJavascript(CHAT_JS, null);
                    }
                });
                root.addView(chat, new FrameLayout.LayoutParams(w, -1, Gravity.END));
            }
            chat.setVisibility(View.VISIBLE);
            loadChat();
            vlp.rightMargin = w;
            vlp.gravity = Gravity.CENTER_VERTICAL | Gravity.START;
        } else {
            if (chat != null) {
                chat.setVisibility(View.GONE);
                chat.loadUrl("about:blank");
            }
            vlp.rightMargin = 0;
            vlp.gravity = Gravity.CENTER;
        }
        video.setLayoutParams(vlp);
        info.bringToFront();
        hint.bringToFront();
    }

    private void loadChat() {
        if (chat != null) chat.loadUrl("https://www.tiktok.com/@" + user + "/live");
    }

    /** Keeps the chat page silent: hides and pauses its video so only chat and comments show. */
    private static final String CHAT_JS =
            "(function(){var st=document.createElement('style');"
            + "st.textContent='video{display:none!important}html,body{cursor:none!important}';"
            + "document.head.appendChild(st);"
            + "setInterval(function(){var v=document.querySelectorAll('video');"
            + "for(var i=0;i<v.length;i++){v[i].muted=true;v[i].pause();}"
            + "var c=document.querySelector('[data-e2e=\"chat-room\"],[class*=\"DivChatRoomContainer\"],[class*=\"ChatRoom\"]');"
            + "if(c&&!c.__tv){c.__tv=1;c.scrollIntoView();}"
            + "},1000);})();";

    // ---------------------------------------------------------------- Keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) {
            return e.getKeyCode() == KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(e);
        }
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
            case KeyEvent.KEYCODE_MEDIA_REWIND:
                if (e.getRepeatCount() == 0) switchLive(-1);
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_MEDIA_NEXT:
            case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                if (e.getRepeatCount() == 0) switchLive(1);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (!chatOpen) toggleChat(); else showInfo();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (chatOpen) toggleChat(); else showInfo();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (failed && e.getKeyCode() != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) fallbackToWeb();
                else showInfo();
                return true;
            case KeyEvent.KEYCODE_MENU:
                cycleQuality();
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (chatOpen) toggleChat();
                else finish();
                return true;
        }
        return super.dispatchKeyEvent(e);
    }

    private void switchLive(int dir) {
        if (lives.size() < 2) {
            toast("No other LIVEs in the list. Browse the LIVE page to find more.");
            return;
        }
        index = (index + dir + lives.size()) % lives.size();
        load(lives.get(index));
    }

    private void cycleQuality() {
        String cur = prefs.quality();
        String[][] opts = Prefs.QUALITY_OPTIONS;
        int i = 0;
        for (int k = 0; k < opts.length; k++) if (opts[k][0].equals(cur)) i = k;
        String[] next = opts[(i + 1) % opts.length];
        prefs.put("quality", next[0]);
        toast("Quality: " + next[1]);
        load(user);
    }

    private void fallbackToWeb() {
        Intent data = new Intent();
        data.putExtra("fallbackUser", user);
        setResult(RESULT_OK, data);
        finish();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private boolean paused;

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
        video.stopPlayback();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // A paused Live is stale; reconnect to the live edge.
        if (paused) {
            paused = false;
            load(user);
        }
    }

    @Override
    protected void onDestroy() {
        loadToken++;
        video.stopPlayback();
        if (chat != null) {
            root.removeView(chat);
            chat.destroy();
        }
        super.onDestroy();
    }
}
