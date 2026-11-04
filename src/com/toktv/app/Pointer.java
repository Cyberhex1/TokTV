package com.toktv.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.TypedValue;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

/**
 * Free mouse: an on-screen pointer moved with the remote's arrows. OK taps where it points
 * (hold OK to long-press), pushing past the top or bottom edge scrolls the page.
 * Add it as the last child of a full-screen FrameLayout and pass key events to handleKey().
 */
public class Pointer extends View {
    private final ViewGroup target;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path arrow = new Path();
    private float x = -1, y = -1;
    private boolean on;
    private long downTime;
    private boolean scrolling;

    public Pointer(Context c, ViewGroup target) {
        super(c);
        this.target = target;
        fill.setColor(Color.WHITE);
        fill.setStyle(Paint.Style.FILL);
        edge.setColor(Color.BLACK);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(dp(2));
        float s = dp(1.6f);
        arrow.moveTo(0, 0);
        arrow.lineTo(0, 17 * s);
        arrow.lineTo(4.5f * s, 13 * s);
        arrow.lineTo(8 * s, 20 * s);
        arrow.lineTo(10.5f * s, 19 * s);
        arrow.lineTo(7 * s, 12 * s);
        arrow.lineTo(12.5f * s, 12 * s);
        arrow.close();
        setVisibility(GONE);
        setFocusable(false);
        setClickable(false);
    }

    private float dp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    public boolean isOn() { return on; }

    public void setOn(boolean v) {
        on = v;
        if (on && x < 0) {
            x = target.getWidth() / 2f;
            y = target.getHeight() / 2f;
        }
        setVisibility(on ? VISIBLE : GONE);
        bringToFront();
        invalidate();
    }

    public void toggle() { setOn(!on); }

    @Override
    public boolean onTouchEvent(MotionEvent e) { return false; }

    @Override
    protected void onDraw(Canvas c) {
        if (!on) return;
        c.save();
        c.translate(x, y);
        c.drawPath(arrow, fill);
        c.drawPath(arrow, edge);
        c.restore();
    }

    /** Returns true if the key was used by the pointer. Back, Menu and media keys are left alone. */
    public boolean handleKey(KeyEvent e) {
        if (!on) return false;
        int code = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        float step = dp(10) * Math.min(1 + e.getRepeatCount() * 0.45f, 7f);
        float w = target.getWidth(), h = target.getHeight();
        switch (code) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (down) move(-step, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (down) move(step, 0);
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (down) {
                    if (y <= dp(4)) scroll(1);
                    else move(0, -step);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (down) {
                    if (y >= h - dp(4)) scroll(-1);
                    else move(0, step);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (down && e.getRepeatCount() == 0) {
                    downTime = SystemClock.uptimeMillis();
                    touch(MotionEvent.ACTION_DOWN, x, y, downTime);
                } else if (!down) {
                    touch(MotionEvent.ACTION_UP, x, y, downTime);
                }
                return true;
        }
        return false;
    }

    private void move(float dx, float dy) {
        x = Math.max(0, Math.min(target.getWidth() - 1, x + dx));
        y = Math.max(0, Math.min(target.getHeight() - 1, y + dy));
        invalidate();
    }

    private void touch(int action, float tx, float ty, long down) {
        MotionEvent ev = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, tx, ty, 0);
        ev.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        target.dispatchTouchEvent(ev);
        ev.recycle();
    }

    /** Swipe gesture in the middle of the screen: dir 1 = content moves down (scroll up). */
    private void scroll(final int dir) {
        if (scrolling) return;
        scrolling = true;
        final float sx = target.getWidth() / 2f;
        final float sy = target.getHeight() / 2f - dir * target.getHeight() * 0.2f;
        final float dist = target.getHeight() * 0.4f * dir;
        final long t0 = SystemClock.uptimeMillis();
        touch(MotionEvent.ACTION_DOWN, sx, sy, t0);
        final int steps = 8;
        for (int i = 1; i <= steps; i++) {
            final int k = i;
            ui.postDelayed(new Runnable() {
                @Override
                public void run() {
                    float yy = sy + dist * k / steps;
                    touch(MotionEvent.ACTION_MOVE, sx, yy, t0);
                    if (k == steps) {
                        touch(MotionEvent.ACTION_UP, sx, yy, t0);
                        scrolling = false;
                    }
                }
            }, i * 16L);
        }
    }
}
