package com.toktv.app;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.MediaItem;
import com.google.android.exoplayer2.PlaybackException;
import com.google.android.exoplayer2.Player;
import com.google.android.exoplayer2.extractor.flv.TokFlvExtractor;
import com.google.android.exoplayer2.source.ProgressiveMediaSource;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSource;
import com.google.android.exoplayer2.video.VideoSize;

import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;

/**
 * Muted, low-quality LIVE preview inside a card on the LIVE list. One at a time: starting a new
 * one or stopping releases the player completely (decoder, surface, network). Skipped when the
 * Fire TV is low on memory.
 */
final class LivePreview {
    private static final String TAG = "TokPreview";
    /** Below this much free system memory, previews are skipped (the stick has ~190 MB free). */
    private static final long MIN_FREE_BYTES = 110L * 1024 * 1024;
    private static final String[] LOW_FIRST = {"ld", "sd", "hd", "origin", "uhd"};

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private ExoPlayer exo;
    private TextureView tv;
    private ViewGroup box;
    private String user;
    private int token;

    LivePreview(Context c) { ctx = c.getApplicationContext(); }

    String user() { return exo != null ? user : null; }

    static boolean lowMemory(Context c) {
        ActivityManager am = (ActivityManager) c.getSystemService(Context.ACTIVITY_SERVICE);
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
        am.getMemoryInfo(mi);
        Runtime rt = Runtime.getRuntime();
        long heapLeft = rt.maxMemory() - (rt.totalMemory() - rt.freeMemory());
        boolean low = mi.lowMemory || mi.availMem < MIN_FREE_BYTES || heapLeft < 12L * 1024 * 1024;
        if (low) Log.i(TAG, "skip: free " + (mi.availMem >> 20) + " MB, heap left " + (heapLeft >> 20) + " MB");
        return low;
    }

    /** Lowest-quality FLV for a listed room, or null. */
    static String streamFor(Feed.Room r) {
        if (r == null || r.streamData == null) return null;
        try {
            JSONObject data = new JSONObject(r.streamData).optJSONObject("data");
            if (data == null) return null;
            for (String q : LOW_FIRST) {
                JSONObject level = data.optJSONObject(q);
                JSONObject main = level == null ? null : level.optJSONObject("main");
                String flv = main == null ? null : main.optString("flv", "");
                if (flv != null && flv.startsWith("http")) return flv;
            }
        } catch (Exception ignored) { }
        return null;
    }

    /** Plays u's LIVE in host (a FrameLayout in the card). Returns false if skipped. */
    boolean start(String u, FrameLayout host) {
        stop();
        if (lowMemory(ctx)) return false;
        String url = streamFor(Feed.findListed(u));
        if (url == null) return false;
        final int my = ++token;
        user = u;
        box = host;
        tv = new TextureView(host.getContext());
        tv.setAlpha(0f);   // shown once the first frame is drawn, so the cover stays until then
        host.addView(tv, new FrameLayout.LayoutParams(-1, -1));

        DefaultTrackSelector ts = new DefaultTrackSelector(ctx);
        ts.setParameters(ts.buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true));
        DefaultLoadControl lc = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(1000, 3000, 500, 1000)
                .setTargetBufferBytes(2 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
        exo = new ExoPlayer.Builder(ctx).setTrackSelector(ts).setLoadControl(lc).build();
        exo.setVolume(0f);
        exo.setVideoTextureView(tv);
        exo.addListener(new Player.Listener() {
            @Override
            public void onRenderedFirstFrame() {
                if (my == token && tv != null) tv.animate().alpha(1f).setDuration(200).start();
            }

            @Override
            public void onVideoSizeChanged(VideoSize s) {
                if (my == token) centerCrop(s.width * s.pixelWidthHeightRatio, s.height);
            }

            @Override
            public void onPlayerError(PlaybackException e) {
                Log.i(TAG, "error " + e.getErrorCodeName());
                if (my == token) stop();
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED && my == token) stop();
            }
        });
        Map<String, String> h = new HashMap<>();
        h.put("Referer", "https://www.tiktok.com/");
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(Prefs.DESKTOP_UA)
                .setDefaultRequestProperties(h)
                .setAllowCrossProtocolRedirects(true)
                .setConnectTimeoutMs(8000)
                .setReadTimeoutMs(10000);
        exo.setMediaSource(new ProgressiveMediaSource.Factory(http, TokFlvExtractor.FACTORY)
                .createMediaSource(MediaItem.fromUri(url)));
        exo.prepare();
        exo.setPlayWhenReady(true);
        Log.i(TAG, "start @" + u + " " + url.split("\\?")[0]);
        return true;
    }

    private void centerCrop(float vw, float vh) {
        if (tv == null || vw <= 0 || vh <= 0) return;
        final float fvw = vw, fvh = vh;
        tv.post(new Runnable() {
            @Override
            public void run() {
                if (tv == null) return;
                float w = tv.getWidth(), h = tv.getHeight();
                if (w <= 0 || h <= 0) return;
                // The TextureView stretches the video to fill it; undo that and scale to cover.
                float s = Math.max(w / fvw, h / fvh);
                Matrix m = new Matrix();
                m.setScale(fvw * s / w, fvh * s / h, w / 2f, h / 2f);
                tv.setTransform(m);
            }
        });
    }

    /** Stops and fully releases the player. Safe to call any time. */
    void stop() {
        token++;
        if (exo != null) {
            Log.i(TAG, "stop @" + user);
            try { exo.stop(); } catch (Exception ignored) { }
            exo.release();
            exo = null;
        }
        if (tv != null) {
            tv.animate().cancel();
            if (box != null) box.removeView(tv);
            tv = null;
        }
        box = null;
        user = null;
    }
}
