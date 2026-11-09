package com.toktv.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Draws a box around whoever is talking in a multi-guest LIVE, the way Twitch does.
 * The positions and the talking flags come from the JSON TikTok puts inside the video stream
 * (SEI), so it lines up with the picture. Sits exactly on top of the video surface.
 */
public class SpeakerOverlay extends View {
    private static final long HOLD_MS = 900;      // keep a box this long after the last "talking" signal

    private static final class Grid {
        String uid;
        float x, y, w, h;
        long talkingUntil;
    }

    private final Map<String, Grid> grids = new HashMap<>();
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private boolean enabled = true;

    public SpeakerOverlay(Context c) {
        super(c);
        float w = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 3, getResources().getDisplayMetrics());
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(w);
        stroke.setColor(Color.parseColor("#3D8BFF"));
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeWidth(w * 3);
        glow.setColor(Color.parseColor("#553D8BFF"));
        setWillNotDraw(false);
        setFocusable(false);
        setClickable(false);
    }

    public void setEnabledHighlight(boolean on) {
        enabled = on;
        invalidate();
    }

    public void clear() {
        grids.clear();
        invalidate();
    }

    /** Applies one SEI JSON message. Call on the UI thread. */
    public void apply(String json) {
        try {
            JSONObject o = new JSONObject(json);
            long now = SystemClock.uptimeMillis();
            String app = o.optString("app_data", null);
            if (app != null && app.length() > 0) {
                JSONObject a = new JSONObject(app);
                JSONArray g = a.optJSONArray("grids");
                if (g != null) {
                    Map<String, Grid> next = new HashMap<>();
                    for (int i = 0; i < g.length(); i++) {
                        JSONObject e = g.getJSONObject(i);
                        Grid gr = new Grid();
                        gr.uid = e.optString("uid_str", String.valueOf(i));
                        gr.x = (float) e.optDouble("x", 0);
                        gr.y = (float) e.optDouble("y", 0);
                        gr.w = (float) e.optDouble("w", 0);
                        gr.h = (float) e.optDouble("h", 0);
                        Grid old = grids.get(gr.uid);
                        gr.talkingUntil = old != null ? old.talkingUntil : 0;
                        if (e.optInt("talk", 0) == 1 && !e.optBoolean("mute_a", false)) gr.talkingUntil = now + HOLD_MS;
                        next.put(gr.uid, gr);
                    }
                    grids.clear();
                    grids.putAll(next);
                }
            }
            JSONArray infos = o.optJSONArray("infos");
            if (infos != null) {
                for (int i = 0; i < infos.length(); i++) {
                    JSONObject e = infos.getJSONObject(i);
                    Grid gr = grids.get(e.optString("uid"));
                    if (gr != null && e.optInt("talk", 0) == 1) gr.talkingUntil = now + HOLD_MS;
                }
            }
        } catch (Exception ignored) {
        }
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        if (!enabled || grids.size() < 2) return;   // only for LIVEs with guests
        long now = SystemClock.uptimeMillis();
        float W = getWidth(), H = getHeight();
        float inset = stroke.getStrokeWidth() / 2 + 1;
        boolean any = false;
        List<Grid> list = new ArrayList<>(grids.values());
        for (Grid g : list) {
            if (g.talkingUntil < now || g.w <= 0 || g.h <= 0) continue;
            any = true;
            r.set(g.x * W + inset, g.y * H + inset, (g.x + g.w) * W - inset, (g.y + g.h) * H - inset);
            float rad = Math.min(r.width(), r.height()) * 0.06f;
            c.drawRoundRect(r, rad, rad, glow);
            c.drawRoundRect(r, rad, rad, stroke);
        }
        // Keep redrawing while boxes are fading out.
        if (any) postInvalidateDelayed(150);
    }
}
