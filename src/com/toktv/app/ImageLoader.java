package com.toktv.app;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small avatar loader: two download threads, downscaled bitmaps, a little memory cache. */
public final class ImageLoader {
    private static final ExecutorService pool = Executors.newFixedThreadPool(2);
    private static final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override
        protected int sizeOf(String k, Bitmap b) { return b.getByteCount(); }
    };
    private static final Handler ui = new Handler(Looper.getMainLooper());

    private ImageLoader() { }

    public static void load(final ImageView view, final String url, final int sizePx) {
        view.setTag(url);
        if (url == null) { view.setImageDrawable(null); return; }
        Bitmap b = cache.get(url);
        if (b != null) { view.setImageBitmap(b); return; }
        view.setImageDrawable(null);
        pool.execute(new Runnable() {
            @Override
            public void run() {
                final Bitmap bmp = fetch(url, sizePx);
                if (bmp == null) return;
                cache.put(url, bmp);
                ui.post(new Runnable() {
                    @Override
                    public void run() {
                        if (url.equals(view.getTag())) view.setImageBitmap(bmp);
                    }
                });
            }
        });
    }

    private static Bitmap fetch(String url, int size) {
        try {
            HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            c.setRequestProperty("User-Agent", Prefs.DESKTOP_UA);
            c.setRequestProperty("Referer", "https://www.tiktok.com/");
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            in.close();
            c.disconnect();
            byte[] data = out.toByteArray();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= size && o.outHeight / (sample * 2) >= size) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            return BitmapFactory.decodeByteArray(data, 0, data.length, o2);
        } catch (Throwable t) {
            return null;
        }
    }
}
