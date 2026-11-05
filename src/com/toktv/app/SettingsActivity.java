package com.toktv.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.webkit.CookieManager;
import android.webkit.WebStorage;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** D-pad friendly settings: OK or Left/Right changes a value. */
public class SettingsActivity extends Activity {
    private Prefs prefs;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#101010"));
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(80), dp(40), dp(80), dp(40));
        sv.addView(list);
        setContentView(sv);

        TextView title = new TextView(this);
        title.setText("Settings");
        title.setTextColor(Color.WHITE);
        title.setTextSize(32);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(16), 0, 0, dp(24));
        list.addView(title);

        option("Lite mode (faster)", "For the TikTok website screens: uses the lighter phone site for videos. LIVE still uses the full site.",
                "lite_mode", "1", Prefs.ONOFF);
        option("LIVE player on website screens", "LIVEs opened from the TikTok website screens. The LIVE list always uses the native player.",
                "live_player", Prefs.PLAYER_NATIVE, Prefs.PLAYER_OPTIONS);
        option("Native player quality", "Menu (≡) in the native player also changes this.",
                "quality", "auto", Prefs.QUALITY_OPTIONS);
        option("LIVE chat", "Read-only chat beside the video. ◀ or ▶ while watching shows or hides it.",
                "native_chat", "1", Prefs.ONOFF);
        option("Chat side", "", "chat_side", "right",
                new String[][]{{"right", "Right"}, {"left", "Left"}});
        option("Chat text size", "", "chat_size", "18",
                new String[][]{{"16", "Small"}, {"18", "Medium"}, {"22", "Large"}, {"26", "Extra large"}});
        option("Show who joined", "\"name joined\" lines in chat.", "chat_joins", "0", Prefs.ONOFF);
        option("Profanity filter", "Masks swear words in chat. Off shows chat exactly as TikTok shows it.", "chat_filter", "0", Prefs.ONOFF);
        option("Went-LIVE alerts", "Banner on the LIVE list when a pinned creator goes LIVE.", "golive_alerts", "1", Prefs.ONOFF);
        option("Who's talking", "Blue box around the guest who is speaking in multi-guest LIVEs.",
                "talk_highlight", "1", Prefs.ONOFF);
        option("Free mouse", "A pointer you move with the arrows; OK clicks. Also in ≡ menus.", "mouse", "0", Prefs.ONOFF);
        option("Text and layout size", "How big tiktok.com looks on the TV.",
                "ui_scale", "150", Prefs.SIZE_OPTIONS);
        option("Turn sound on automatically", "TikTok starts some videos muted. This unmutes them.",
                "auto_unmute", "1", Prefs.ONOFF);
        option("Hide \"Get the app\" banners", "", "hide_promos", "1", Prefs.ONOFF);

        updateSection();

        action("Log out and clear data","Removes your TikTok login and cookies from this app.", new Runnable() {
            @Override
            public void run() { confirmLogout(); }
        });

        TextView about = new TextView(this);
        about.setText("TokTV " + Updater.currentName(this) + " LIVE · github.com/" + Updater.REPO);
        about.setTextColor(Color.GRAY);
        about.setTextSize(14);
        about.setPadding(dp(16), dp(24), 0, 0);
        list.addView(about);

        if (list.getChildCount() > 1) list.getChildAt(1).requestFocus();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private LinearLayout row(String name, String desc) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setFocusable(true);
        row.setBackgroundResource(R.drawable.menu_item_bg);
        row.setPadding(dp(20), dp(14), dp(20), dp(14));

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView n = new TextView(this);
        n.setText(name);
        n.setTextColor(Color.WHITE);
        n.setTextSize(20);
        texts.addView(n);
        if (desc.length() > 0) {
            TextView d = new TextView(this);
            d.setText(desc);
            d.setTextColor(Color.parseColor("#BBBBBB"));
            d.setTextSize(14);
            texts.addView(d);
        }
        row.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = dp(6);
        list.addView(row, lp);
        return row;
    }

    private void option(String name, String desc, final String key, final String def, final String[][] opts) {
        final LinearLayout row = row(name, desc);
        final TextView value = new TextView(this);
        value.setTextColor(Color.parseColor("#25F4EE"));
        value.setTextSize(18);
        value.setPadding(dp(24), 0, 0, 0);
        value.setText("◀  " + Prefs.labelFor(opts, prefs.get(key, def)) + "  ▶");
        row.addView(value);

        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { cycle(key, def, opts, value, 1); }
        });
        row.setOnKeyListener(new View.OnKeyListener() {
            @Override
            public boolean onKey(View v, int code, KeyEvent e) {
                if (e.getAction() != KeyEvent.ACTION_DOWN) return false;
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) { cycle(key, def, opts, value, 1); return true; }
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) { cycle(key, def, opts, value, -1); return true; }
                return false;
            }
        });
    }

    private void cycle(String key, String def, String[][] opts, TextView value, int dir) {
        String cur = prefs.get(key, def);
        int i = 0;
        for (int k = 0; k < opts.length; k++) if (opts[k][0].equals(cur)) i = k;
        String[] next = opts[(i + dir + opts.length) % opts.length];
        prefs.put(key, next[0]);
        value.setText("◀  " + next[1] + "  ▶");
    }

    private void action(String name, String desc, final Runnable r) {
        LinearLayout row = row(name, desc);
        row.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { r.run(); }
        });
    }

    // ---------------------------------------------------------------- Update (GitHub Releases)

    private TextView updStatus, updNotes, updInstallLabel;
    private Updater.Release updRelease;
    private boolean updBusy;

    private void updateSection() {
        TextView h = new TextView(this);
        h.setText("Update");
        h.setTextColor(Color.WHITE);
        h.setTextSize(24);
        h.setTypeface(Typeface.DEFAULT_BOLD);
        h.setPadding(dp(16), dp(24), 0, dp(8));
        list.addView(h);

        updStatus = new TextView(this);
        updStatus.setTextColor(Color.parseColor("#25F4EE"));
        updStatus.setTextSize(18);
        updStatus.setPadding(dp(16), 0, 0, dp(4));
        String latest = prefs.get("update_latest", "");
        updStatus.setText("This TokTV: " + Updater.currentName(this)
                + (latest.isEmpty() ? " · Latest: not checked yet" : " · Latest on GitHub: " + latest));
        list.addView(updStatus);

        updNotes = new TextView(this);
        updNotes.setTextColor(Color.parseColor("#BBBBBB"));
        updNotes.setTextSize(14);
        updNotes.setPadding(dp(16), 0, dp(16), dp(10));
        updNotes.setVisibility(View.GONE);
        list.addView(updNotes);

        action("Check now", "Looks for a newer TokTV on GitHub (" + Updater.REPO + ").", new Runnable() {
            @Override
            public void run() { checkUpdate(); }
        });
        LinearLayout inst = row("Download & install", "Downloads the new TokTV, checks it's genuine, then opens the Fire TV installer.");
        updInstallLabel = (TextView) ((LinearLayout) inst.getChildAt(0)).getChildAt(0);
        inst.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) { downloadUpdate(); }
        });
        option("Check automatically on launch", "At most once a day. A short message appears when there's an update.",
                "update_auto", "1", Prefs.ONOFF);
    }

    private static String reason(Exception e) {
        if (e instanceof java.net.UnknownHostException) return "No internet connection.";
        if (e instanceof java.net.SocketTimeoutException) return "GitHub didn't answer in time. Try again.";
        String m = e.getMessage();
        return m == null || m.isEmpty() ? e.getClass().getSimpleName() : m;
    }

    private void checkUpdate() {
        if (updBusy) return;
        updBusy = true;
        updStatus.setText("Checking GitHub…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final Updater.Release r = Updater.check(SettingsActivity.this);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() { showRelease(r); }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updStatus.setText("This TokTV: " + Updater.currentName(SettingsActivity.this) + " · Couldn't check: " + reason(e));
                        }
                    });
                } finally {
                    updBusy = false;
                }
            }
        }, "TokTVUpdate").start();
    }

    private void showRelease(Updater.Release r) {
        updRelease = r;
        String cur = Updater.currentName(this);
        if (r.apkUrl == null) {
            updStatus.setText("This TokTV: " + cur + " · Latest " + r.version + " has no TokTV.apk to download.");
        } else if (r.newer) {
            updStatus.setText("This TokTV: " + cur + " · Update available: " + r.version);
            updInstallLabel.setText("Download & install " + r.version);
        } else {
            updStatus.setText("This TokTV: " + cur + " · Latest on GitHub: " + r.version + " · You're up to date");
        }
        if (r.notes != null && !r.notes.isEmpty()) {
            String n = r.notes.length() > 900 ? r.notes.substring(0, 900) + "…" : r.notes;
            updNotes.setText("What's new in " + r.version + ":\n" + n);
            updNotes.setVisibility(View.VISIBLE);
        }
    }

    private void downloadUpdate() {
        if (updBusy) return;
        if (updRelease == null) { toast("Checking first…"); checkUpdate(); return; }
        if (!updRelease.newer) { toast("You already have the latest TokTV."); return; }
        if (!Updater.canInstall(this)) { explainUnknownSources(); return; }
        updBusy = true;
        final Updater.Release r = updRelease;
        updStatus.setText("Downloading TokTV " + r.version + "…");
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    final java.io.File apk = Updater.download(SettingsActivity.this, r, new Updater.Progress() {
                        long lastPct = -1;

                        @Override
                        public void onProgress(final long done, final long total) {
                            final long pct = total > 0 ? done * 100 / total : -1;
                            if (pct == lastPct) return;
                            lastPct = pct;
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    updStatus.setText("Downloading TokTV " + r.version + "… "
                                            + (pct >= 0 ? pct + "%" : (done / 1024) + " KB"));
                                }
                            });
                        }
                    });
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updStatus.setText("Checked: genuine TokTV " + r.version + ". Opening the installer…");
                            try {
                                Updater.install(SettingsActivity.this, apk);
                            } catch (Exception e) {
                                updStatus.setText("Couldn't open the installer: " + reason(e));
                            }
                        }
                    });
                } catch (final Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            updStatus.setText("Not installed: " + reason(e));
                            new AlertDialog.Builder(SettingsActivity.this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                                    .setTitle("Update not installed")
                                    .setMessage(reason(e))
                                    .setPositiveButton("OK", null)
                                    .show();
                        }
                    });
                } finally {
                    updBusy = false;
                }
            }
        }, "TokTVDownload").start();
    }

    private void explainUnknownSources() {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Allow TokTV to install updates")
                .setMessage("Fire TV needs your OK once.\n\nSettings → My Fire TV → Developer options → Install unknown apps → TokTV → On.\n"
                        + "(On older Fire TVs: Developer options → Apps from Unknown Sources → On.)\n\nThen come back and choose Download & install again.")
                .setPositiveButton("Open settings", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) { Updater.openUnknownSources(SettingsActivity.this); }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private void confirmLogout() {
        new AlertDialog.Builder(this, AlertDialog.THEME_DEVICE_DEFAULT_DARK)
                .setTitle("Log out?")
                .setMessage("This removes your TikTok login from TokTV.")
                .setPositiveButton("Log out", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface d, int w) {
                        CookieManager.getInstance().removeAllCookies(null);
                        CookieManager.getInstance().flush();
                        WebStorage.getInstance().deleteAllData();
                        prefs.put("needs_reload", "1");
                        Toast.makeText(SettingsActivity.this, "Logged out", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    @Override
    public void onBackPressed() {
        setResult(RESULT_OK);
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        Tv.top = this;
    }
}
