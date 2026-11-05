package com.toktv.app;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** What's on the TV right now, and helpers for TokTV Remote to act on it. */
public final class Tv {
    /** The TokTV screen in front (set in each screen's onResume). */
    public static volatile Activity top;

    private static final Handler ui = new Handler(Looper.getMainLooper());

    private Tv() { }

    public interface UiTask<T> {
        T run();
    }

    /** Runs on the UI thread and waits (up to timeoutMs) for the result. */
    public static <T> T onUi(final UiTask<T> task, long timeoutMs) {
        final Object[] out = new Object[1];
        final CountDownLatch done = new CountDownLatch(1);
        ui.post(new Runnable() {
            @Override
            public void run() {
                try { out[0] = task.run(); } catch (Throwable ignored) { }
                done.countDown();
            }
        });
        try { done.await(timeoutMs, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) { }
        @SuppressWarnings("unchecked") T t = (T) out[0];
        return t;
    }

    /** Presses a remote button on the TokTV screen in front, as if pressed on the Fire TV remote. */
    public static boolean press(final int keyCode) {
        Boolean ok = onUi(new UiTask<Boolean>() {
            @Override
            public Boolean run() {
                Activity a = top;
                if (a == null || a.isFinishing()) return false;
                long t = SystemClock.uptimeMillis();
                a.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, keyCode, 0));
                a.dispatchKeyEvent(new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0));
                return true;
            }
        }, 3000);
        return ok != null && ok;
    }

    public static String screen() {
        Activity a = top;
        if (a == null) return "none";
        if (a instanceof LiveActivity) return "live";
        if (a instanceof HomeActivity) return "list";
        if (a instanceof SearchActivity) return "search";
        if (a instanceof MainActivity) return "website";
        if (a instanceof SettingsActivity) return "settings";
        if (a instanceof LoginActivity) return "login";
        return a.getClass().getSimpleName();
    }
}
