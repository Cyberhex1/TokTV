package com.toktv.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;

/**
 * Updates from GitHub Releases (Cyberhex1/TokTV): reads /releases/latest (no token), downloads the
 * TokTV.apk asset into the app's cache, checks it really is a newer TokTV signed with the same key,
 * then hands it to the system installer.
 */
public final class Updater {
    public static final String REPO = "Cyberhex1/TokTV";
    static final String API = "https://api.github.com/repos/" + REPO + "/releases/latest";
    static final String APK_NAME = "TokTV.apk";
    private static final long DAY = 24L * 60 * 60 * 1000;

    /** What the latest release says. */
    public static final class Release {
        public String tag, version, notes, apkUrl, shaUrl, page;
        public long apkSize;
        public boolean newer;
    }

    public interface Progress {
        void onProgress(long done, long total);
    }

    private Updater() { }

    // ---------------------------------------------------------------- Versions

    public static String currentName(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionName; } catch (Exception e) { return "?"; }
    }

    public static int currentCode(Context c) {
        try { return c.getPackageManager().getPackageInfo(c.getPackageName(), 0).versionCode; } catch (Exception e) { return 0; }
    }

    /** -1, 0 or 1 comparing "2.5" style versions number by number ("v" and suffixes ignored). */
    static int compare(String a, String b) {
        String[] x = clean(a).split("\\."), y = clean(b).split("\\.");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return p < q ? -1 : 1;
        }
        return 0;
    }

    private static String clean(String v) {
        if (v == null) return "0";
        v = v.trim();
        if (v.startsWith("v") || v.startsWith("V")) v = v.substring(1);
        return v;
    }

    private static int num(String s) {
        int i = 0;
        while (i < s.length() && Character.isDigit(s.charAt(i))) i++;
        try { return i == 0 ? 0 : Integer.parseInt(s.substring(0, i)); } catch (Exception e) { return 0; }
    }

    // ---------------------------------------------------------------- Checking

    /** Blocking: call off the UI thread. Throws with a readable message on failure. */
    public static Release check(Context c) throws Exception {
        String body = get(API);
        JSONObject j = new JSONObject(body);
        Release r = new Release();
        r.tag = j.optString("tag_name", "");
        r.version = clean(r.tag);
        r.notes = j.optString("body", "").trim();
        r.page = j.optString("html_url", "https://github.com/" + REPO + "/releases");
        JSONArray assets = j.optJSONArray("assets");
        if (assets != null) {
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                String n = a.optString("name");
                if (APK_NAME.equalsIgnoreCase(n)) {
                    r.apkUrl = a.optString("browser_download_url");
                    r.apkSize = a.optLong("size");
                } else if (n.equalsIgnoreCase(APK_NAME + ".sha256")) {
                    r.shaUrl = a.optString("browser_download_url");
                }
            }
        }
        r.newer = r.apkUrl != null && compare(r.version, currentName(c)) > 0;
        Prefs p = new Prefs(c);
        p.put("update_last", String.valueOf(System.currentTimeMillis()));
        p.put("update_latest", r.version);
        return r;
    }

    public static boolean autoOn(Context c) {
        return "1".equals(new Prefs(c).get("update_auto", "1"));
    }

    /** On launch: at most once a day, quietly; tells the user with a toast if there's an update. */
    public static void checkOnLaunch(final Activity a) {
        cleanup(a);
        if (!autoOn(a)) return;
        long last = 0;
        try { last = Long.parseLong(new Prefs(a).get("update_last", "0")); } catch (Exception ignored) { }
        if (System.currentTimeMillis() - last < DAY) return;
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Release r = check(a);
                    if (!r.newer) return;
                    a.runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            android.widget.Toast.makeText(a, "TokTV " + r.version + " is available. Menu (≡) → Settings → Update.",
                                    android.widget.Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (Exception ignored) { }
            }
        }, "TokTVUpdateCheck").start();
    }

    // ---------------------------------------------------------------- Downloading and checking the APK

    static File dir(Context c) {
        File d = new File(c.getCacheDir(), "update");
        d.mkdirs();
        return d;
    }

    /** Deletes downloaded APKs (after an install, or left over from an earlier try). */
    public static void cleanup(Context c) {
        File[] fs = new File(c.getCacheDir(), "update").listFiles();
        if (fs != null) for (File f : fs) f.delete();
        File ext = c.getExternalCacheDir();
        if (ext != null) new File(ext, APK_NAME).delete();
    }

    /** Blocking. Downloads and verifies; returns the checked APK, or throws with the reason. */
    public static File download(Context c, Release r, Progress progress) throws Exception {
        cleanup(c);
        File f = new File(dir(c), APK_NAME);
        HttpURLConnection con = open(r.apkUrl);
        long total = con.getContentLength() > 0 ? con.getContentLength() : r.apkSize;
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        InputStream in = con.getInputStream();
        OutputStream out = new FileOutputStream(f);
        try {
            byte[] buf = new byte[32768];
            long done = 0;
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                sha.update(buf, 0, n);
                done += n;
                if (progress != null) progress.onProgress(done, total);
            }
        } finally {
            out.close();
            in.close();
            con.disconnect();
        }
        try {
            verify(c, f, r, hex(sha.digest()));
        } catch (Exception e) {
            f.delete();
            throw e;
        }
        return f;
    }

    private static void verify(Context c, File f, Release r, String sha256) throws Exception {
        if (r.shaUrl != null) {
            String want = get(r.shaUrl).trim().split("\\s+")[0].toLowerCase();
            if (!want.equals(sha256)) throw new Exception("The download is damaged (SHA-256 doesn't match). Try again.");
        }
        PackageManager pm = c.getPackageManager();
        PackageInfo apk = pm.getPackageArchiveInfo(f.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        if (apk == null) throw new Exception("The download isn't a valid app file.");
        if (!c.getPackageName().equals(apk.packageName))
            throw new Exception("The download is a different app (" + apk.packageName + "), not TokTV. Not installing it.");
        if (apk.versionCode <= currentCode(c))
            throw new Exception("The download (" + apk.versionName + ") isn't newer than this TokTV (" + currentName(c) + ").");
        Signature[] mine = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
        if (apk.signatures == null || apk.signatures.length == 0 || !sameSigners(mine, apk.signatures))
            throw new Exception("The download isn't signed by the same key as this TokTV, so it can't update it. "
                    + "If you installed TokTV from somewhere else, uninstall it and install the release by hand.");
    }

    private static boolean sameSigners(Signature[] a, Signature[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        String[] x = new String[a.length], y = new String[b.length];
        for (int i = 0; i < a.length; i++) { x[i] = a[i].toCharsString(); y[i] = b[i].toCharsString(); }
        Arrays.sort(x);
        Arrays.sort(y);
        return Arrays.equals(x, y);
    }

    // ---------------------------------------------------------------- Installing

    /** True when Android will let TokTV open the installer (Android 8+ asks per app). */
    public static boolean canInstall(Context c) {
        if (Build.VERSION.SDK_INT >= 26) return c.getPackageManager().canRequestPackageInstalls();
        try {
            return Settings.Secure.getInt(c.getContentResolver(), Settings.Secure.INSTALL_NON_MARKET_APPS, 1) == 1;
        } catch (Exception e) {
            return true;
        }
    }

    /** Opens the screen where the user allows TokTV to install apps (Android 8+), else the security settings. */
    public static void openUnknownSources(Activity a) {
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                a.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.getPackageName())));
            } else {
                a.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            }
        } catch (Exception e) {
            try { a.startActivity(new Intent(Settings.ACTION_SETTINGS)); } catch (Exception ignored) { }
        }
    }

    /** Hands the checked APK to the system installer. */
    public static void install(Activity a, File apk) throws Exception {
        Uri uri;
        Intent i;
        if (Build.VERSION.SDK_INT >= 24) {
            uri = ApkProvider.uriFor(a, apk);
            i = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            i.setData(uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            // Android 5-6 installers can't read content:// URIs: give them a readable copy.
            File ext = a.getExternalCacheDir();
            if (ext == null) throw new Exception("No storage for the installer.");
            File copy = new File(ext, APK_NAME);
            copy(apk, copy);
            copy.setReadable(true, false);
            uri = Uri.fromFile(copy);
            i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
        }
        i.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        i.putExtra(Intent.EXTRA_RETURN_RESULT, false);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        a.startActivity(i);
    }

    // ---------------------------------------------------------------- HTTP

    private static HttpURLConnection open(String url) throws Exception {
        for (int hop = 0; hop < 5; hop++) {
            HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setInstanceFollowRedirects(false);
            con.setRequestProperty("User-Agent", "TokTV-Updater");
            con.setRequestProperty("Accept", url.contains("api.github.com") ? "application/vnd.github+json" : "*/*");
            int code = con.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = con.getHeaderField("Location");
                con.disconnect();
                if (loc == null) break;
                url = new URL(new URL(url), loc).toString();
                continue;
            }
            if (code == 404) throw new Exception("No TokTV release has been published yet.");
            if (code == 403) throw new Exception("GitHub is busy (rate limit). Try again in a while.");
            if (code != 200) throw new Exception("GitHub answered " + code + ".");
            return con;
        }
        throw new Exception("Too many redirects.");
    }

    private static String get(String url) throws Exception {
        HttpURLConnection con = open(url);
        try {
            InputStream in = con.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > 2 * 1024 * 1024) break;
            }
            in.close();
            return out.toString("UTF-8");
        } catch (java.net.UnknownHostException e) {
            throw new Exception("No internet connection.");
        } finally {
            con.disconnect();
        }
    }

    private static void copy(File from, File to) throws Exception {
        InputStream in = new FileInputStream(from);
        OutputStream out = new FileOutputStream(to);
        byte[] buf = new byte[32768];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.close();
        in.close();
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
