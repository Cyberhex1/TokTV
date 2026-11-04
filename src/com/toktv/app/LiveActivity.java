package com.toktv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
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
import android.view.SurfaceView;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.extractor.DefaultExtractorsFactory;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.video.VideoSize;

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
    /** "Auto": 720p first — smooth on Fire TV sticks — then the rest. */
    private static final String[] QUALITY_ORDER = {"hd", "hd_60", "sd", "uhd", "uhd_60", "origin", "ld"};
    private ExoPlayer exo;
    private SurfaceView surface;
    private FrameLayout videoBox;
    private int videoW, videoH;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private Prefs prefs;
    private FrameLayout root;
    private VideoView video;
    private LinearLayout info;
    private TextView infoName, infoTitle, infoViewers, status, hint;
    private ProgressBar spinner;
    private ChatPanel chatPanel;
    private SpeakerOverlay speakers;

    private final List<String> lives = new ArrayList<>();
    private int index;
    private String user;
    private String pageStream;
    private int loadToken;
    private List<String> streamUrls = new ArrayList<>();
    private int streamAttempt;
    private boolean failed;
    private Pointer pointer;

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

        videoBox = new FrameLayout(this);
        root.addView(videoBox, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        video = new VideoView(this);
        videoBox.addView(video, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        surface = new SurfaceView(this);
        videoBox.addView(surface, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        videoBox.setBackgroundColor(Color.BLACK);   // also covers the hidden chat page underneath
        speakers = new SpeakerOverlay(this);
        speakers.setEnabledHighlight(!"0".equals(prefs.get("talk_highlight", "1")));
        videoBox.addView(speakers, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        videoBox.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                if (r - l != or - ol || b - t != ob - ot) fitSurface();
            }
        });
        video.setOnPreparedListener(new MediaPlayer.OnPreparedListener() {
            @Override
            public void onPrepared(MediaPlayer mp) {
                new Picks(prefs).markWatched(user);
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
        FrameLayout.LayoutParams ilp = new FrameLayout.LayoutParams(dp(520), -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        ilp.setMargins(0, dp(24), 0, 0);
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
        hint.setText("▲ ▼  Switch LIVE      ◀ ▶  Chat / reactions      OK  Info      ≡  Options      Back  LIVE list");
        FrameLayout.LayoutParams hlp = new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        hlp.bottomMargin = dp(24);
        root.addView(hint, hlp);

        chatPanel = new ChatPanel(this, root, prefs);
        if (prefs.nativeChat() && !"0".equals(prefs.get("chat_open", "1"))) chatPanel.open(user);
        info.bringToFront();
        hint.bringToFront();

        pointer = new Pointer(this, root);
        root.addView(pointer, new FrameLayout.LayoutParams(-1, -1));
        pointer.post(new Runnable() {
            @Override
            public void run() { pointer.setOn("1".equals(prefs.get("mouse", "0"))); }
        });
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
        stopPlayback();
        infoName.setText("@" + u);
        infoTitle.setText("");
        infoViewers.setText("");
        showInfo();
        setStatus("Opening @" + u + "'s LIVE…", true);
        if (chatPanel != null) chatPanel.switchTo(u);
        if (speakers != null) speakers.clear();

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
        stopPlayback();
        Map<String, String> headers = new HashMap<>();
        headers.put("User-Agent", Prefs.DESKTOP_UA);
        headers.put("Referer", "https://www.tiktok.com/");
        if (url.split("\\?")[0].endsWith(".flv")) {
            playFlv(url, headers);
            return;
        }
        surface.setVisibility(View.GONE);
        video.setVisibility(View.VISIBLE);
        video.setVideoURI(Uri.parse(url), headers);
        video.requestFocus();
    }

    /** TikTok's web LIVE streams are FLV, which the system player can't open: ExoPlayer plays them. */
    private void playFlv(String url, Map<String, String> headers) {
        video.setVisibility(View.GONE);
        surface.setVisibility(View.VISIBLE);
        if (exo == null) {
            DefaultLoadControl lc = new DefaultLoadControl.Builder()
                    .setBufferDurationsMs(3000, 20000, 1000, 2000)
                    .build();
            exo = new ExoPlayer.Builder(this).setLoadControl(lc).build();
            exo.setVideoSurfaceView(surface);
            exo.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY) {
                        new Picks(prefs).markWatched(user);
                        spinner.setVisibility(View.GONE);
                        status.setVisibility(View.GONE);
                        showInfo();
                    } else if (state == Player.STATE_BUFFERING) {
                        spinner.setVisibility(View.VISIBLE);
                    } else if (state == Player.STATE_ENDED) {
                        load(user);   // ended or dropped: check again
                    }
                }

                @Override
                public void onPlayerError(PlaybackException error) {
                    tryNextStream();
                }

                @Override
                public void onVideoSizeChanged(VideoSize size) {
                    videoW = Math.round(size.width * size.pixelWidthHeightRatio);
                    videoH = size.height;
                    fitSurface();
                }
            });
        }
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Prefs.DESKTOP_UA)
                .setDefaultRequestProperties(headers)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(10000)
                .setReadTimeoutMs(15000);
        seiQueue.clear();
        speakers.clear();
        com.google.android.exoplayer2.extractor.flv.TokFlvExtractor.seiListener = seiListener;
        ui.removeCallbacks(seiTick);
        ui.post(seiTick);
        MediaSource src = new ProgressiveMediaSource.Factory(http, com.google.android.exoplayer2.extractor.flv.TokFlvExtractor.FACTORY)
                .createMediaSource(MediaItem.fromUri(url));
        exo.setMediaSource(src);
        exo.prepare();
        exo.setPlayWhenReady(true);
    }

    /** Keeps the video's shape (most LIVEs are portrait) inside the video area. */
    private void fitSurface() {
        if (videoW <= 0 || videoH <= 0) return;
        int bw = videoBox.getWidth(), bh = videoBox.getHeight();
        if (bw <= 0 || bh <= 0) return;
        float s = Math.min(bw / (float) videoW, bh / (float) videoH);
        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) surface.getLayoutParams();
        lp.width = Math.round(videoW * s);
        lp.height = Math.round(videoH * s);
        lp.gravity = Gravity.CENTER;
        surface.setLayoutParams(lp);
        FrameLayout.LayoutParams olp = (FrameLayout.LayoutParams) speakers.getLayoutParams();
        olp.width = lp.width;
        olp.height = lp.height;
        olp.gravity = Gravity.CENTER;
        speakers.setLayoutParams(olp);
    }

    // ---------------------------------------------------------------- Who's talking (from the stream)

    private final java.util.concurrent.ConcurrentLinkedQueue<Object[]> seiQueue = new java.util.concurrent.ConcurrentLinkedQueue<>();

    private final com.google.android.exoplayer2.extractor.flv.TokFlvExtractor.SeiListener seiListener =
            new com.google.android.exoplayer2.extractor.flv.TokFlvExtractor.SeiListener() {
                @Override
                public void onSei(long timeUs, String json) {
                    if (seiQueue.size() < 200) seiQueue.add(new Object[]{timeUs, json});
                }
            };

    /** Applies each SEI message when its frame is on screen (the loader runs a few seconds ahead). */
    private final Runnable seiTick = new Runnable() {
        @Override
        public void run() {
            if (exo != null) {
                long pos = exo.getCurrentPosition() * 1000L + 100000L;
                Object[] head;
                while ((head = seiQueue.peek()) != null && (Long) head[0] <= pos) {
                    seiQueue.poll();
                    speakers.apply((String) head[1]);
                }
            }
            ui.postDelayed(this, 100);
        }
    };

    private void stopPlayback() {
        video.stopPlayback();
        if (exo != null) exo.stop();
    }

    private void tryNextStream() {
        streamAttempt++;
        if (streamAttempt < streamUrls.size()) {
            play(streamUrls.get(streamAttempt));
            return;
        }
        failed = true;
        stopPlayback();
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
        // 0) Stream data from the LIVE list the home screen already loaded.
        Feed.Room fr = Feed.find(u);
        if (fr != null && System.currentTimeMillis() - fr.at < 10 * 60 * 1000) {
            r.nickname = fr.nick;
            r.title = emptyToNull(fr.title);
            r.viewers = fr.viewers;
            r.live = true;
            addFromStreamData(fr.streamData, r);
            addUrl(r, fr.hls);
            if (!r.hls.isEmpty()) return r;
        }
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
                if (main != null) {
                    addUrl(r, main.optString("flv"));
                    addUrl(r, main.optString("hls"));
                }
            }
        } catch (Exception ignored) {
        }
    }

    private List<String> qualityOrder() {
        List<String> order = new ArrayList<>();
        String q = prefs.quality();
        if (!"auto".equals(q)) {
            order.add(q);
            order.add(q + "_60");
        }
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

    /** Read-only native chat beside the video, with quick reactions on the other side. */
    private void toggleChat() {
        if (chatPanel.isOpen()) {
            chatPanel.close();
            prefs.put("chat_open", "0");
        } else {
            chatPanel.open(user);
            prefs.put("chat_open", "1");
        }
        info.bringToFront();
        hint.bringToFront();
        pointer.bringToFront();
    }
    // ---------------------------------------------------------------- Keys

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        if (pointer != null && pointer.handleKey(e)) return true;
        if (chatPanel != null && chatPanel.handleKey(e)) return true;
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
            case KeyEvent.KEYCODE_DPAD_LEFT:
                // Toward the chat side shows/hides chat (toward the reactions side is ChatPanel's).
                if (e.getRepeatCount() == 0) toggleChat();
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                if (failed && e.getKeyCode() != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) fallbackToWeb();
                else showInfo();
                return true;
            case KeyEvent.KEYCODE_MENU:
                if (e.getRepeatCount() == 0) showOptions();
                return true;
            case KeyEvent.KEYCODE_BACK:
                finish();
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
        if (getCallingActivity() != null) {
            // Opened from the website screen: let it switch to the website player.
            Intent data = new Intent();
            data.putExtra("fallbackUser", user);
            setResult(RESULT_OK, data);
            finish();
            return;
        }
        Intent i = new Intent(this, MainActivity.class);
        i.setData(Uri.parse("https://www.tiktok.com/@" + user + "/live"));
        i.putExtra("webOnly", user);
        startActivity(i);
        finish();
    }

    private void showOptions() {
        final Picks picks = new Picks(prefs);
        final String[] items = {
                "Quality: " + Prefs.labelFor(Prefs.QUALITY_OPTIONS, prefs.quality()),
                picks.isPinned(user) ? "Unpin @" + user : "Pin @" + user + " to top",
                "Free mouse: " + (pointer.isOn() ? "On" : "Off"),
                chatPanel.isOpen() ? "Hide chat" : "Show chat",
                "Watch in website player (gifts, full page)",
                "Back to LIVE list",
        };
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("@" + user)
                .setItems(items, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int which) {
                        switch (which) {
                            case 0: cycleQuality(); break;
                            case 1: toast(picks.togglePin(user) ? "Pinned @" + user : "Unpinned @" + user); break;
                            case 2:
                                pointer.toggle();
                                prefs.put("mouse", pointer.isOn() ? "1" : "0");
                                toast(pointer.isOn() ? "Free mouse on: arrows move, OK clicks, ⏪ ⏩ switch LIVE" : "Free mouse off");
                                break;
                            case 3: toggleChat(); break;
                            case 4: fallbackToWeb(); break;
                            case 5: finish(); break;
                        }
                    }
                })
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private boolean paused;

    @Override
    protected void onPause() {
        super.onPause();
        paused = true;
        stopPlayback();
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
        stopPlayback();
        if (exo != null) {
            exo.release();
            exo = null;
        }
        com.google.android.exoplayer2.extractor.flv.TokFlvExtractor.seiListener = null;
        ui.removeCallbacksAndMessages(null);
        if (chatPanel != null) chatPanel.close();
        super.onDestroy();
    }
}
